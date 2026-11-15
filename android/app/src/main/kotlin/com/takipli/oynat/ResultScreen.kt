package com.takipli.oynat

import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Transformer
import com.takipli.oynat.core.CamPath
import com.takipli.oynat.core.Config
import com.takipli.oynat.core.Formats
import com.takipli.oynat.core.PRESETS
import com.takipli.oynat.core.StabPlan
import com.takipli.oynat.core.Target
import com.takipli.oynat.core.TrackDoc
import com.takipli.oynat.core.enoughSpaceForExport
import com.takipli.oynat.core.exportBitrate
import com.takipli.oynat.core.exportBytesNeeded
import com.takipli.oynat.core.Tuning
import com.takipli.oynat.core.rebuildViews
import com.takipli.oynat.core.viewAtSmooth
import com.takipli.oynat.core.viewTransform
import com.takipli.oynat.core.of
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Part 13: play the video with the follow-camera views, tune them with sliders, switch the body part, save. */
@Composable
fun ResultScreen(file: String, onBack: () -> Unit) {
    var doc by remember { mutableStateOf<TrackDoc?>(null) }
    var err by remember { mutableStateOf("") }
    val ctx0 = LocalContext.current
    LaunchedEffect(file) {
        try { doc = withContext(Dispatchers.IO) { Formats.parseTrack(File(file).readText()) } }
        catch (e: Throwable) { ErrorLog.record(ctx0, "result-load", e); err = Safety.friendly(ctx0, e) }
    }
    val d = doc
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        when {
            err.isNotEmpty() -> Text(err)
            d == null -> Text(stringResource(R.string.result_loading))
            d.target == null -> Text(stringResource(R.string.result_no_target))
            else -> ResultBody(d, d.target!!, File(file))
        }
    }
}

@Composable
private fun ResultBody(doc: TrackDoc, base: Target, file: File) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { Config() }
    var target by remember { mutableStateOf(base) }
    var tuning by remember { mutableStateOf(Tuning.of(cfg)) }
    var views by remember { mutableStateOf(doc.views) }
    var follow by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf((doc.video.duration * 1000).toLong().coerceAtLeast(1)) }
    var saved by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var hevc by remember { mutableStateOf(false) }
    var stab by remember { mutableStateOf(false) } // EIS-style stabilization of the export (needs one slow pass over every frame): opt-in, so opening the preview never starts it
    var camPath by remember { mutableStateOf<CamPath?>(null) }
    var stabBusy by remember { mutableStateOf(false) }
    var stabP by remember { mutableStateOf(0f) }
    var stabMsg by remember { mutableStateOf("") }
    var exporter by remember { mutableStateOf<Transformer?>(null) }
    var exportP by remember { mutableStateOf(0f) }
    var exportMsg by remember { mutableStateOf("") }
    var playerMsg by remember { mutableStateOf("") }
    var readable by remember { mutableStateOf(true) } // false = the picked video cannot be opened any more (moved, deleted, SAF grant lost)
    val exporting = exporter != null
    val view = LocalView.current
    SideEffect { view.keepScreenOn = exporting } // the export runs in this process: keep the screen (and the app) alive
    DisposableEffect(Unit) { onDispose { exporter?.cancel(); view.keepScreenOn = false } }
    LaunchedEffect(stab) {
        if (!stab || camPath != null) return@LaunchedEffect
        stabBusy = true; stabP = 0f; stabMsg = ""
        try {
            val camFile = File(file.path.replace(".track.json", ".cam.json"))
            val sig = DenseCamera.signature(doc)
            camPath = withContext(Dispatchers.Default) {
                DenseCamera.load(camFile, sig)
                    ?: DenseCamera.analyze(ctx, Uri.parse(doc.video.path), doc, sig) { stabP = it }.also { DenseCamera.save(camFile, it) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.record(ctx, "stabilize", e)
            stabMsg = Safety.friendly(ctx, e)
            stab = false
        } finally {
            stabBusy = false
        }
    }
    LaunchedEffect(exporter) {
        val t = exporter ?: return@LaunchedEffect
        while (exporter === t) { exportP = Exporter.progress(t); delay(300) }
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(Unit) {
        readable = withContext(Dispatchers.IO) { Safety.videoReadable(ctx, Uri.parse(doc.video.path)) }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                ErrorLog.record(ctx, "player", error, error.errorCodeName)
                playerMsg = Safety.friendly(ctx, error, error.errorCodeName)
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.parse(doc.video.path)))
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.prepare()
    }
    LaunchedEffect(player) { // ~display rate while visible; cheap (reads one Long)
        while (true) {
            posMs = player.currentPosition
            if (player.duration > 0) durMs = player.duration
            playing = player.isPlaying
            delay(33)
        }
    }

    fun rebuild(t: Target, tu: Tuning) {
        job?.cancel(); saved = ""
        job = scope.launch { views = withContext(Dispatchers.Default) { rebuildViews(doc, t, cfg, tu) } }
    }

    val vw = doc.video.w.coerceAtLeast(1); val vh = doc.video.h.coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().aspectRatio(vw / vh.toFloat()).clipToBounds()) {
        AndroidView(
            factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                if (follow && views.isNotEmpty()) {
                    val tr = viewTransform(viewAtSmooth(views, posMs / 1000.0)) // same crop as the export will cut
                    scaleX = tr[0].toFloat(); scaleY = tr[0].toFloat()
                    translationX = (tr[1] * size.width).toFloat(); translationY = (tr[2] * size.height).toFloat()
                }
            },
        )
    }
    if (!readable) Text(stringResource(R.string.err_video), color = MaterialTheme.colorScheme.error)
    if (playerMsg.isNotEmpty()) Text(playerMsg, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(stringResource(if (playing) R.string.result_pause else R.string.result_play)) }
        FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text(stringResource(R.string.result_follow)) })
    }
    Slider(value = posMs.toFloat(), valueRange = 0f..durMs.toFloat(), onValueChange = { posMs = it.toLong(); player.seekTo(it.toLong()) })

    Text(stringResource(R.string.pick_part), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PRESETS.keys.forEach { p ->
            FilterChip(selected = target.preset == p, onClick = { target = Target(base.id, preset = p, linked = base.linked); rebuild(target, tuning) }, label = { Text(p.replace('_', ' ')) })
        }
    }
    @Composable
    fun tune(label: Int, v: Double, r: ClosedFloatingPointRange<Float>, set: (Double) -> Tuning) {
        Text(stringResource(label, String.format(Locale.ROOT, "%.2f", v)))
        Slider(value = v.toFloat(), valueRange = r, onValueChange = { tuning = set(it.toDouble()) }, onValueChangeFinished = { rebuild(target, tuning) })
    }
    tune(R.string.tune_fill, tuning.targetFill, 0.15f..0.7f) { tuning.copy(targetFill = it) }
    tune(R.string.tune_box, tuning.kpBox, 0.04f..0.4f) { tuning.copy(kpBox = it) }
    tune(R.string.tune_maxzoom, tuning.maxZoom, 0.5f..3.5f) { tuning.copy(maxZoom = it) }
    tune(R.string.tune_follow, tuning.followAlpha, 0.1f..0.9f) { tuning.copy(followAlpha = it) }
    tune(R.string.tune_hold, tuning.lostHoldS, 0f..3f) { tuning.copy(lostHoldS = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { tuning = Tuning.of(cfg); rebuild(target, tuning) }) { Text(stringResource(R.string.tune_reset)) }
        Button(onClick = {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val out = doc.copy(target = target, views = views)
                        Scanner.writeAtomic(file, out)
                        File(file.path.replace(".track.json", ".views.json")).writeText(Formats.encodeViews(Formats.viewsOf(out)))
                    }
                    saved = ctx.getString(R.string.result_saved)
                } catch (e: Throwable) {
                    ErrorLog.record(ctx, "save-views", e)
                    saved = Safety.friendly(ctx, e)
                }
            }
        }) { Text(stringResource(R.string.result_save)) }
    }
    if (saved.isNotEmpty()) Text(saved)

    Text(stringResource(R.string.export_title), style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !hevc, enabled = !exporting, onClick = { hevc = false }, label = { Text("H.264") })
        FilterChip(selected = hevc, enabled = !exporting, onClick = { hevc = true }, label = { Text("HEVC") })
    }
    FilterChip(selected = stab, enabled = !exporting && !stabBusy, onClick = { stab = !stab }, label = { Text(stringResource(R.string.stab_chip)) })
    if (stabBusy) {
        LinearProgressIndicator(progress = { stabP.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.stab_working))
    }
    if (stabMsg.isNotEmpty()) Text(stabMsg)
    BatteryWarning()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !exporting && !stabBusy && views.isNotEmpty() && readable, onClick = {
            exportMsg = ""; exportP = 0f
            // preflight: enough storage for the cache file plus the copy in Movies (core `enoughSpaceForExport`)
            val need = exportBytesNeeded(exportBitrate(vw, vh, doc.video.fps, hevc), durMs / 1000.0)
            val freeCache = Safety.freeBytes(ctx.cacheDir)
            val freeMovies = Safety.freeBytes(ctx.getExternalFilesDir(null) ?: ctx.cacheDir)
            if (!enoughSpaceForExport(freeCache, freeMovies, need)) {
                exportMsg = ctx.getString(R.string.err_no_space_export, need / 1_048_576L, minOf(freeCache, freeMovies) / 1_048_576L)
                return@Button
            }
            val t0 = SystemClock.elapsedRealtime()
            val tmp = File(ctx.cacheDir, "export/out.mp4")
            var finished = false // an error can arrive before start() returns: then there is no running exporter to remember
            fun finish(msg: String) { finished = true; exporter = null; exportMsg = msg }
            val started = try { Exporter.start(
                ctx, Uri.parse(doc.video.path), views, tmp, vw, vh, doc.video.fps, hevc,
                plan = if (stab) camPath?.let { StabPlan(it, views, vw, vh) } else null,
                onDone = {
                    scope.launch {
                        val name = "Takipli_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(java.util.Date()) + ".mp4"
                        try {
                            withContext(Dispatchers.IO) { Exporter.saveToMovies(ctx, tmp, name); tmp.delete() }
                            val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                            val x = if (wall > 0) durMs / 1000.0 / wall else 0.0
                            finish(ctx.getString(R.string.export_done, name, String.format(Locale.ROOT, "%.2f", x)))
                        } catch (e: Throwable) {
                            ErrorLog.record(ctx, "export-save", e)
                            tmp.delete()
                            finish(Safety.friendly(ctx, e))
                        }
                    }
                },
                onError = { e ->
                    ErrorLog.record(ctx, "export", e, e.errorCodeName)
                    tmp.delete()
                    finish(Safety.friendly(ctx, e, e.errorCodeName))
                },
            ) } catch (e: Throwable) {
                ErrorLog.record(ctx, "export-start", e)
                finish(Safety.friendly(ctx, e))
                null
            }
            if (started != null && !finished) exporter = started
        }) { Text(stringResource(R.string.export_start)) }
        OutlinedButton(enabled = exporting, onClick = { exporter?.cancel(); exporter = null; exportMsg = ctx.getString(R.string.export_cancelled) }) { Text(stringResource(R.string.scan_cancel)) }
    }
    if (exporting) {
        if (exportP >= 0f) LinearProgressIndicator(progress = { exportP.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.export_keep_open))
    }
    if (exportMsg.isNotEmpty()) Text(exportMsg)
}
