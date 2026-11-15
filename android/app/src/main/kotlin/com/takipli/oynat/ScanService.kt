package com.takipli.oynat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.takipli.oynat.core.Config
import com.takipli.oynat.core.Formats
import com.takipli.oynat.core.TargetPick
import com.takipli.oynat.core.applyPick
import com.takipli.oynat.core.buildTargetViews
import com.takipli.oynat.core.scanSig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the UI shows. The scan itself lives in [ScanService], so it survives the screen going off and the activity closing. */
sealed interface ScanUi {
    data object Idle : ScanUi
    class Running(val p: ScanProgress?, val pv: ScanPreview? = null) : ScanUi
    /** `targetText` null = nobody was at the picked point (scan is kept; pick again without rescanning). */
    class Done(val samples: Int, val cached: Boolean, val targetText: String?, val file: String, val xRealtime: Double, val preview: Boolean) : ScanUi
    class Failed(val message: String, val cancelled: Boolean) : ScanUi
}

/**
 * Part 11 foreground service: runs [Scanner] with the pose model, then resolves the user's [TargetPick] to {person, part}
 * and stores `target` + `views` in the scan's `track.json` (so the part can be changed later without rescanning).
 * Intent extras say what to scan; START_REDELIVER_INTENT restarts an interrupted scan, which resumes from `.partial`.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { job?.cancel() ?: stopSelf(); return START_NOT_STICKY }
        if (intent == null || job?.isActive == true) return START_NOT_STICKY
        startForeground(NOTE_ID, note(getString(R.string.scan_notify_start), null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "takipli:scan")
            .apply { acquire(WAKE_MAX_MS) }
        post(ScanUi.Running(null))
        job = scope.launch { run(intent); stopSelf(startId) }
        return START_REDELIVER_INTENT
    }

    private suspend fun run(i: Intent) {
        try {
            val uri = Uri.parse(i.getStringExtra(X_URI))
            val videoId = i.getStringExtra(X_VIDEO_ID).orEmpty()
            val fps = i.getDoubleExtra(X_FPS, 10.0)
            val cfg = Config()
            val pick = Formats.json.decodeFromString<TargetPick>(i.getStringExtra(X_PICK).orEmpty())
            val file = scanFile(this, videoId)
            val withPreview = i.getBooleanExtra(X_PREVIEW, true)
            var lastNote = 0L
            var lastPv: ScanPreview? = null
            val res = PoseRunner(this, i.getStringExtra(X_MODEL).orEmpty(), threads = 4, wantNpu = i.getBooleanExtra(X_NPU, false)).use { runner ->
                Scanner.scan(this, uri, videoId, runner, fps, file, cfg, pick = pick, onPreview = if (withPreview) ({ v: ScanPreview -> lastPv = v }) else null) { p ->
                    post(ScanUi.Running(p, lastPv))
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNote >= 1000) { lastNote = now; notify(note(getString(R.string.scan_notify_run, p.samples, p.total), p)) }
                }
            }
            var picked = applyPick(if (pick.free) res.doc.copy(pt = null) else res.doc, pick, cfg)
            val fp = picked?.target?.free
            if (picked != null && fp != null && OpenCvFlow.available) { // Part 16b: tracker passes for the tapped spot
                try {
                    val pt = PointScanner.run(this, uri, picked, fp) { d, tot, t -> post(ScanUi.Running(ScanProgress(d, tot, t, 0, 0), null)) }
                    val d2 = picked.copy(pt = pt)
                    picked = d2.copy(views = buildTargetViews(d2, d2.target!!, cfg))
                } catch (e: CancellationException) { throw e } catch (e: Throwable) { ErrorLog.record(this, "point", e) } // keeps the anchor-only views
            }
            val out = picked ?: res.doc
            if (picked != null) Scanner.writeAtomic(file, picked)
            val tg = picked?.target
            val text = tg?.let { if (it.free != null) "#${it.id} free point" else "#${it.id} " + (it.preset ?: it.part?.let { j -> "joint $j" } ?: "person") }
            post(ScanUi.Done(out.samples.size, res.cached, text, file.path, res.xRealtime, withPreview))
        } catch (e: CancellationException) {
            post(ScanUi.Failed(getString(R.string.scan_cancelled), true))
        } catch (e: Throwable) {
            ErrorLog.record(this, "scan", e)
            post(ScanUi.Failed(Safety.friendly(this, e), false))
        } finally {
            wake?.takeIf { it.isHeld }?.release()
        }
    }

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)

    private fun note(text: String, p: ScanProgress?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.scan_cancel), cancel).build())
            .apply { if (p != null && p.total > 0) setProgress(p.total, p.samples.coerceAtMost(p.total), false) }
            .build()
    }

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "scan"
        private const val NOTE_ID = 11
        private const val WAKE_MAX_MS = 4 * 60 * 60 * 1000L
        private const val ACTION_CANCEL = "com.takipli.oynat.SCAN_CANCEL"
        private const val X_URI = "uri"; private const val X_VIDEO_ID = "video_id"; private const val X_FPS = "fps"
        private const val X_PREVIEW = "preview"; private const val X_PICK = "pick"; private const val X_MODEL = "model"; private const val X_NPU = "npu"

        private val _state = MutableStateFlow<ScanUi>(ScanUi.Idle)
        /** Written by the service, read by the UI. */
        val state: StateFlow<ScanUi> = _state
        private fun post(u: ScanUi) { _state.value = u }

        /** `files/scans/<id>.track.json`: one file per video (id from `videoId`), shared with the Part 9 cache. */
        fun scanFile(ctx: Context, videoId: String) = File(ctx.filesDir, "scans/${scanSig(videoId, 0.0, "")}.track.json")

        fun start(ctx: Context, uri: Uri, videoId: String, model: String, npu: Boolean, fps: Double, pick: TargetPick, preview: Boolean = true) {
            _state.value = ScanUi.Running(null)
            ctx.startForegroundService(
                Intent(ctx, ScanService::class.java).putExtra(X_URI, uri.toString()).putExtra(X_VIDEO_ID, videoId)
                    .putExtra(X_MODEL, model).putExtra(X_NPU, npu).putExtra(X_PREVIEW, preview).putExtra(X_FPS, fps)
                    .putExtra(X_PICK, Formats.json.encodeToString(pick)),
            )
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_CANCEL))
        }

        fun reset() { _state.value = ScanUi.Idle }
    }
}
