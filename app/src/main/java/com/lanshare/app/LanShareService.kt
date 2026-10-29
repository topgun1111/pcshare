package com.lanshare.app

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lanshare.app.core.Core

class LanShareService : Service() {
    private var started = false
    private var lastScan = 0L
    private val netCb = object : android.net.ConnectivityManager.NetworkCallback() {
        private fun go() {   // debounce: network events come in bursts
            val now = System.currentTimeMillis()
            if (now - lastScan < 3000) return
            lastScan = now
            Thread {
                try {
                    Thread.sleep(1500)
                    Core.rescan()
                    com.lanshare.app.core.Jobs.autoResume()   // interrupted FTP copies continue by themselves
                } catch (_: Exception) {}
            }.start()
        }
        override fun onAvailable(n: android.net.Network) = go()
        override fun onLinkPropertiesChanged(n: android.net.Network, lp: android.net.LinkProperties) = go()
    }

    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            // screen on / unlock: peers and printers may have been missed while asleep -> look again
            Thread { try { Thread.sleep(800); Core.rescan() } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
    }

    companion object {
        @Volatile private var inst: LanShareService? = null
        /** Re-posts the foreground notification now (clears the inline "Send text" box after a send). Safe from any thread. */
        fun refresh() { val s = inst ?: return; s.ui.post { s.repost() } }
    }

    private fun repost() {
        try {
            val (run, done, total) = com.lanshare.app.core.ImgSearch.progress()
            lastNote = noteKey(run, done, total)
            getSystemService(NotificationManager::class.java).notify(1, buildNote(run, done, total))
        } catch (_: Exception) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") {            // notification "Stop" button: kill server + app
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid()); return START_NOT_STICKY
        }
        val ch = "lanshare"
        if (Build.VERSION.SDK_INT >= 26)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, NoteTint.appName, NotificationManager.IMPORTANCE_LOW))
        try { val (run, done, total) = com.lanshare.app.core.ImgSearch.progress(); lastNote = noteKey(run, done, total); startForeground(1, buildNote(run, done, total)) }
        catch (e: Exception) {   // ForegroundServiceStartNotAllowedException etc.: keep running as a normal service rather than crash
            android.util.Log.w("LANShare", "startForeground refused: " + e)
        }
        if (!started) {
            started = true
            val root = Environment.getExternalStorageDirectory().absolutePath
            try { getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(netCb) }
            catch (_: Exception) {}
            try {
                val f = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }
                registerReceiver(screenRx, f)
            } catch (_: Exception) {}
            Thread { Core.start(applicationContext, root); try { ClipSync.refresh(applicationContext) } catch (_: Throwable) {} }.apply { isDaemon = true; start() }   // config is loaded now: apply the opt-in clipboard sync
            ui.postDelayed(noteTick, 2000)
        }
        return START_STICKY
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastNote = ""
    private var tickN = 0
    private val noteTick = object : Runnable {
        override fun run() {
            if (tickN++ % 5 == 0) {   // every ~10 s: keep the Direct Share targets (online devices) current
                val app = applicationContext
                Thread { Shortcuts.sync(app) }.also { it.isDaemon = true }.start()
            }
            try {
                val (run, done, total) = com.lanshare.app.core.ImgSearch.progress()
                val key = noteKey(run, done, total)
                if (key != lastNote) {   // notify only when the text changes
                    lastNote = key
                    getSystemService(NotificationManager::class.java).notify(1, buildNote(run, done, total))
                }
            } catch (_: Exception) {}
            ui.postDelayed(this, 2000)
        }
    }

    /** Changes whenever the notification text would change (scan progress or the running background jobs). */
    private fun noteKey(run: Boolean, done: Int, total: Int): String {
        val jr = try { com.lanshare.app.ops.JobView.running() } catch (_: Exception) { emptyList() }
        return (if (run) "$done/$total" else "") + "|" + com.lanshare.app.core.Cfg.defTarget() + (try { TextSend.defaultName() } catch (_: Throwable) { null }) + "|" + jr.size + ":" + com.lanshare.app.ops.JobView.overallPct(jr) + ":" + (jr.firstOrNull()?.label ?: "")
    }

    /** The one foreground notification: shows scan progress while a picture scan runs, so the scan is covered by a visible foreground service. */
    private fun buildNote(scanning: Boolean, done: Int, total: Int): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, "lanshare")
            .setSmallIcon(R.drawable.ic_notification).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        b.setColor(NoteTint.lite(this))   // lite: mor bildirim rengi
        // text sharing: type here (inline box) -> default device, or send the clipboard; both without opening the app
        val tgt = try { TextSend.defaultName() ?: (if (com.lanshare.app.core.Cfg.defTarget().isEmpty()) "ask each time" else "offline device") } catch (_: Throwable) { "device" }
        val replyFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val replyPi = PendingIntent.getBroadcast(this, 2, Intent(this, QuickSendReceiver::class.java).setAction("com.lanshare.app.QUICK_SEND"), replyFlags)
        b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification, "Send text", replyPi)
            .addRemoteInput(androidx.core.app.RemoteInput.Builder(QuickSendReceiver.KEY).setLabel("Text to $tgt").build())
            .setAllowGeneratedReplies(false).build())
        // device selection: each tap moves the default device on to the next online one (last step = ask every time); Android shows only 3 actions, so this takes the place of "Clipboard" (still on the tile / share sheet)
        b.addAction(R.drawable.ic_notification, "To: $tgt",
            PendingIntent.getBroadcast(this, 4, Intent(this, QuickSendReceiver::class.java).setAction(QuickSendReceiver.ACT_CYCLE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        b.addAction(R.drawable.ic_notification, "Stop",
            PendingIntent.getService(this, 1, Intent(this, LanShareService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE))
        val jobs = try { com.lanshare.app.ops.JobView.running() } catch (_: Exception) { emptyList() }
        if (jobs.isNotEmpty()) {   // copy / zip / print ... running in the background: progress stays visible when the app is not on screen
            val pct = com.lanshare.app.ops.JobView.overallPct(jobs)
            b.setContentTitle(if (jobs.size == 1) jobs[0].label else "${jobs.size} tasks running")
            b.setContentText(if (pct >= 0) "$pct %" else "Working\u2026")
            b.setProgress(100, maxOf(pct, 0), pct < 0)
        } else if (scanning) {
            b.setContentTitle("Scanning pictures")
            b.setContentText(if (total > 0) "$done / $total" else "Listing folders\u2026")
            b.setProgress(total, done, total <= 0)
        } else {
            b.setContentTitle(NoteTint.appName + " is running").setContentText("Sharing on your network")
        }
        return b.build()
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        ui.removeCallbacks(noteTick)
        try { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(netCb) } catch (_: Exception) {}
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        super.onDestroy()
    }
}
