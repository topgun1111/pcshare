package com.takipli.oynat

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import com.takipli.oynat.core.COCO_SKELETON
import com.takipli.oynat.core.PRESETS
import com.takipli.oynat.core.Pose
import com.takipli.oynat.core.TargetPick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val PICK_FPS = listOf(10, 15, 20, 30, 60)

private fun fmtClock(s: Double): String {
    val v = s.coerceAtLeast(0.0)
    val m = (v / 60).toInt()
    return String.format(Locale.US, "%d:%04.1f", m, v - m * 60)
}

/**
 * Part 11: open a video -> first frame -> tap a person or a body part (and/or choose a part preset) -> scan in the
 * foreground service ([ScanService]); the screen can go off or the app can be closed while it runs.
 */
@Composable
fun PickScreen(onBack: () -> Unit, onResult: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { ModelStore.list(ctx).filterNot { it.startsWith("sam-") } } // sam-* graphs are not pose models
    val ui by ScanService.state.collectAsState()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var videoId by remember { mutableStateOf("") }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var frameT by remember { mutableStateOf(0.0) }
    var durationS by remember { mutableStateOf(0.0) } // 0 = unknown
    var seekT by remember { mutableStateOf(0f) } // where the user wants the pick frame (seconds)
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var tap by remember { mutableStateOf<Offset?>(null) } // normalized 0..1
    var preset by remember { mutableStateOf<String?>(null) } // null = automatic (tapped joint decides)
    var exact by remember { mutableStateOf(false) }
    var draw by remember { mutableStateOf(false) } // Part 18: freehand region instead of a tap
    val strokes = remember { androidx.compose.runtime.mutableStateListOf<List<Offset>>() }
    var curStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var zoomLog2 by remember { mutableStateOf(1.5f) }
    var fps by remember { mutableStateOf(PICK_FPS[3]) }
    var npu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(true) }
    var model by remember { mutableStateOf(models.firstOrNull { it.contains("v8s") } ?: models.firstOrNull().orEmpty()) }
    var error by remember { mutableStateOf("") }
    var objectMode by remember { mutableStateOf(false) } // false = person / body part (pose model), true = any object or spot
    val free = objectMode // "any object" = the free-point path (SAM when installed, optical flow otherwise)
    var adv by remember { mutableStateOf(false) }
    val samOk = remember { SamEngine.hasModels(ctx) }
    val samMem = remember { SamEngine.hasMemory(ctx) }
    var samCache by remember { mutableStateOf<SamFrameCache?>(null) }
    var samMask by remember { mutableStateOf<SamMask?>(null) }
    var segBusy by remember { mutableStateOf(false) }
    val running = ui is ScanUi.Running
    // People the model finds on the first frame (null = still detecting). Only a guide: the tap is resolved after the scan.
    var found by remember { mutableStateOf<List<Pose>?>(null) }
    LaunchedEffect(frame, model) {
        val f = frame ?: return@LaunchedEffect
        if (model.isEmpty()) return@LaunchedEffect
        found = null
        found = try {
            withContext(Dispatchers.Default) { PoseRunner(ctx, model, threads = 4).use { it.detect(f).poses } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.record(ctx, "first-frame-detect", e)
            emptyList()
        }
    }

    // Smart select ("Any object", sam build): encode the shown frame once, then every tap only runs the small decoder.
    LaunchedEffect(frame, objectMode, draw) {
        samMask = null; samCache = null
        val f = frame ?: return@LaunchedEffect
        if (!objectMode || !samOk || draw) return@LaunchedEffect
        segBusy = true
        try { samCache = SamEngine.encodeFrame(ctx, f) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { ErrorLog.record(ctx, "sam-preview-encode", e) }
        finally { segBusy = false }
    }
    LaunchedEffect(tap, samCache) {
        samMask = null
        val c = samCache ?: return@LaunchedEffect
        val t = tap ?: return@LaunchedEffect
        segBusy = true
        try { samMask = SamEngine.segmentAt(ctx, c, t.x.toDouble(), t.y.toDouble()) }
        catch (e: CancellationException) { throw e }
        catch (e: Throwable) { ErrorLog.record(ctx, "sam-preview-segment", e) }
        finally { segBusy = false }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        scope.launch {
            error = ""; tap = null; frame = null; ScanService.reset()
            try {
                videoId = withContext(Dispatchers.IO) { videoIdOf(ctx, u) }
                durationS = withContext(Dispatchers.IO) { runCatching { VideoDecoder.probe(ctx, u).durationS }.getOrDefault(0.0) }
                seekT = 0f
                VideoDecoder.sample(ctx, u, 1.0, 0.0) { f -> frame = f.bitmap; frameT = f.t; false }
                uri = u
            } catch (e: Throwable) {
                uri = null; ErrorLog.record(ctx, "open-video", e); error = Safety.friendly(ctx, e)
            }
        }
    }

    fun clampT(t: Double): Double =
        if (durationS > 0) t.coerceIn(0.0, (durationS - 0.1).coerceAtLeast(0.0)) else t.coerceAtLeast(0.0)

    // The first frame is often black or unrelated to the rest of the clip: let the user pick the target on any later frame.
    // The scan tracks both ways in time from the picked frame, so nothing else changes.
    fun loadAt(t: Double) {
        val u = uri ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                VideoDecoder.sample(ctx, u, 1.0, clampT(t)) { f ->
                    frame = f.bitmap; frameT = f.t
                    tap = null; strokes.clear(); curStroke = emptyList() // those points belonged to the previous frame
                    false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ErrorLog.record(ctx, "seek-frame", e); error = Safety.friendly(ctx, e)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack, enabled = !running) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.pick_title), style = MaterialTheme.typography.headlineSmall)
        // What really works in this install, in plain words
        Text(stringResource(R.string.status_version, BuildConfig.VERSION_NAME), fontSize = 12.sp)
        Text(stringResource(if (samMem) R.string.status_sam_full else if (samOk) R.string.status_sam_basic else R.string.status_sam_none), fontSize = 12.sp)
        Text(stringResource(if (models.isNotEmpty()) R.string.status_pose_ok else R.string.status_pose_none), fontSize = 12.sp)
        if (models.isEmpty()) Text(stringResource(R.string.bench_no_models))
        OutlinedButton(enabled = !running, onClick = { picker.launch(arrayOf("video/*")) }) { Text(stringResource(R.string.decode_pick)) }
        if (error.isNotEmpty()) Text(error)

        val bmp = frame
        if (uri != null && bmp != null && !running && ui !is ScanUi.Done) {
            // 1. WHAT to follow: a person / body part (pose model) or any object (smart select)
            Text(stringResource(R.string.pick_what), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val toPerson = { objectMode = false; draw = false; tap = null }
                val toObject = { objectMode = true; tap = null }
                if (!objectMode) Button(onClick = toPerson, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pick_mode_person)) }
                else OutlinedButton(onClick = toPerson, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pick_mode_person)) }
                if (objectMode) Button(onClick = toObject, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pick_mode_object)) }
                else OutlinedButton(onClick = toObject, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.pick_mode_object)) }
            }
            // 2. say what to do next, in plain words
            val fl = found
            Text(
                when {
                    !objectMode -> when {
                        fl == null -> stringResource(R.string.pick_detecting)
                        fl.isEmpty() -> stringResource(R.string.pick_none)
                        else -> stringResource(R.string.pick_found, fl.size)
                    }
                    draw -> stringResource(R.string.pick_draw_hint)
                    !samOk -> stringResource(R.string.pick_obj_spot)
                    samCache == null && segBusy -> stringResource(R.string.pick_obj_prep)
                    samMask != null -> stringResource(R.string.pick_obj_sel)
                    tap != null && !segBusy -> stringResource(R.string.pick_obj_none)
                    else -> stringResource(R.string.pick_obj_tap)
                },
            )
            val maskImg = remember(samMask) {
                samMask?.let { m ->
                    val b = Bitmap.createBitmap(m.size, m.size, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Color.argb(140, 0, 229, 255)
                    b.setPixels(IntArray(m.size * m.size) { if (m.inside[it]) c else 0 }, 0, m.size, 0, 0, m.size, m.size)
                    b.asImageBitmap()
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(bmp.width / bmp.height.toFloat())) {
                Image(bmp.asImageBitmap(), null, Modifier.matchParentSize())
                Canvas(
                    Modifier.matchParentSize().pointerInput(draw) {
                        fun nrm(o: Offset) = Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                        if (draw) detectDragGestures(
                            onDragStart = { o -> curStroke = listOf(nrm(o)) },
                            onDrag = { c, _ -> curStroke = curStroke + nrm(c.position) },
                            onDragEnd = { if (curStroke.size >= 3) strokes.add(curStroke); curStroke = emptyList() },
                            onDragCancel = { curStroke = emptyList() },
                        ) else detectTapGestures { o -> tap = nrm(o) }
                    },
                ) {
                    if (!objectMode) found?.forEach { p ->
                        val b = p.bbox
                        fun fx(x: Double) = (x / bmp.width * size.width).toFloat()
                        fun fy(y: Double) = (y / bmp.height * size.height).toFloat()
                        drawRect(Color.White, Offset(fx(b.x1), fy(b.y1)), Size(fx(b.x2) - fx(b.x1), fy(b.y2) - fy(b.y1)), style = Stroke(2f))
                        for ((a, c) in COCO_SKELETON) {
                            if (p.conf(a) >= 0.35 && p.conf(c) >= 0.35) drawLine(Color.Yellow, Offset(fx(p.x(a)), fy(p.y(a))), Offset(fx(p.x(c)), fy(p.y(c))), 3f)
                        }
                    }
                    maskImg?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt())) }
                    samMask?.let { m ->
                        drawRect(Color.Cyan, Offset((m.x1 * size.width).toFloat(), (m.y1 * size.height).toFloat()),
                            Size(((m.x2 - m.x1) * size.width).toFloat(), ((m.y2 - m.y1) * size.height).toFloat()), style = Stroke(3f))
                    }
                    for (st in strokes + listOf(curStroke)) if (st.size >= 2) {
                        val path = androidx.compose.ui.graphics.Path()
                        st.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x * size.width, o.y * size.height) else path.lineTo(o.x * size.width, o.y * size.height) }
                        path.close()
                        drawPath(path, Color(0x4400E5FF)); drawPath(path, Color.Cyan, style = Stroke(3f))
                    }
                    if (!draw) tap?.let { c ->
                        val p = Offset(c.x * size.width, c.y * size.height)
                        drawCircle(Color.Red, 14f, p, style = Stroke(width = 4f)); drawCircle(Color.White, 4f, p)
                    }
                }
            }
            // 3. which part of the video
            Text(stringResource(R.string.pick_frame_time, fmtClock(seekT.toDouble()), if (durationS > 0) fmtClock(durationS) else "?"), style = MaterialTheme.typography.labelLarge)
            if (durationS > 0.5) {
                androidx.compose.material3.Slider(
                    value = seekT.coerceIn(0f, durationS.toFloat()),
                    onValueChange = { seekT = it },
                    onValueChangeFinished = { loadAt(seekT.toDouble()) },
                    valueRange = 0f..durationS.toFloat(),
                )
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-10.0 to "\u221210 s", -1.0 to "\u22121 s", -0.2 to "\u22120.2 s", 0.2 to "+0.2 s", 1.0 to "+1 s", 10.0 to "+10 s").forEach { (d, label) ->
                    OutlinedButton(onClick = { val t = clampT(seekT + d); seekT = t.toFloat(); loadAt(t) }) { Text(label) }
                }
            }
            Text(stringResource(R.string.pick_frame_hint), fontSize = 12.sp)
            // 4. mode specific choices
            if (!objectMode) {
                Text(stringResource(R.string.pick_part), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = preset == null, onClick = { preset = null }, label = { Text(stringResource(R.string.pick_auto)) })
                    PRESETS.keys.forEach { p -> FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.replace('_', ' ')) }) }
                }
            } else {
                OutlinedButton(onClick = { draw = !draw; tap = null; strokes.clear(); curStroke = emptyList() }) {
                    Text(stringResource(if (draw) R.string.pick_tap_instead else R.string.pick_draw_instead))
                }
                if (draw) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = strokes.isNotEmpty(), onClick = { strokes.removeLastOrNull() }) { Text(stringResource(R.string.pick_undo)) }
                    OutlinedButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear() }) { Text(stringResource(R.string.pick_clear)) }
                }
            }
            // 5. technical settings stay out of sight
            TextButton(onClick = { adv = !adv }) { Text(stringResource(if (adv) R.string.pick_adv_hide else R.string.pick_adv_show)) }
            if (adv) {
                if (!objectMode) FilterChip(selected = exact, enabled = preset == null, onClick = { exact = !exact }, label = { Text(stringResource(R.string.pick_exact)) })
                if (models.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { m -> FilterChip(selected = m == model, onClick = { model = m }, label = { Text(m.removeSuffix(".onnx")) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PICK_FPS.forEach { c -> FilterChip(selected = c == fps, onClick = { fps = c }, label = { Text("$c fps") }) }
                    FilterChip(selected = npu, onClick = { npu = !npu }, label = { Text("NPU") })
                    FilterChip(selected = preview, onClick = { preview = !preview }, label = { Text(stringResource(R.string.pick_preview)) })
                }
                if (objectMode && !draw) {
                    Text(stringResource(R.string.pick_zoom, Math.pow(2.0, zoomLog2.toDouble())), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.material3.Slider(value = zoomLog2, onValueChange = { zoomLog2 = it }, valueRange = 0.5f..2.5f)
                }
            }
            BatteryWarning()
            Button(
                enabled = (if (draw) strokes.isNotEmpty() else tap != null) && model.isNotEmpty() && !segBusy,
                onClick = {
                    val shapes = if (draw) strokes.map { st -> com.takipli.oynat.core.simplifyStroke(st.map { listOf(it.x.toDouble(), it.y.toDouble()) }, 0.004) }.filter { it.size >= 3 } else emptyList()
                    if (draw && shapes.isEmpty()) return@Button
                    val t = if (draw) com.takipli.oynat.core.shapeBounds(shapes)!!.let { Offset(it[0].toFloat(), it[1].toFloat()) } else tap ?: return@Button
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    ScanService.start(ctx, uri!!, videoId, model, npu, fps.toDouble(), TargetPick(frameT, t.x.toDouble(), t.y.toDouble(), preset = if (objectMode) null else preset, exact = exact && preset == null && !objectMode, free = free, zoom = if (free && !draw) zoomLog2.toDouble() else null, shapes = shapes), preview)
                },
            ) { Text(stringResource(R.string.pick_start)) }
        }

        when (val s = ui) {
            is ScanUi.Running -> {
                val p = s.p
                s.pv?.let { ScanPreviewView(it) }
                if (p != null && p.total > 0) LinearProgressIndicator(progress = { (p.samples.toFloat() / p.total).coerceIn(0f, 1f) })
                Text(
                    p?.let { String.format(Locale.ROOT, "%d / %d samples | t %.1f s | cuts %d", it.samples, it.total, it.t, it.cuts) }
                        ?: stringResource(R.string.pick_preparing),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                Text(stringResource(R.string.pick_background))
                OutlinedButton(onClick = { ScanService.cancel(ctx) }) { Text(stringResource(R.string.scan_cancel)) }
            }
            is ScanUi.Done -> {
                Text(
                    if (s.targetText != null) stringResource(R.string.pick_done, s.targetText, s.samples)
                    else stringResource(R.string.pick_nobody, s.samples),
                )
                if (!s.cached) Text(stringResource(R.string.pick_speed, String.format(Locale.ROOT, "%.2f", s.xRealtime), if (s.preview) "on" else "off"))
                Text(s.file, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (s.targetText != null) Button(onClick = { onResult(s.file) }) { Text(stringResource(R.string.result_open)) }
                OutlinedButton(onClick = { ScanService.reset() }) { Text(stringResource(R.string.pick_again)) }
            }
            is ScanUi.Failed -> Text(s.message)
            ScanUi.Idle -> {}
        }
    }
}
