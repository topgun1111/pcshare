package com.takipli.oynat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takipli.oynat.core.COCO_SKELETON
import com.takipli.oynat.core.Pose
import com.takipli.oynat.core.benchLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val KP_MIN_CONF = 0.35
private val THREAD_CHOICES = listOf(1, 2, 4, 6, 8)

private suspend fun decodeImage(ctx: Context, uri: Uri, maxSide: Int = 2048): Bitmap =
    withContext(Dispatchers.IO) {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri) // applies EXIF rotation
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // pixels must be readable
            val m = max(info.size.width, info.size.height)
            if (m > maxSide) {
                val s = maxSide.toFloat() / m
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
    }

private fun deviceName(): String {
    val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else ""
    return listOf(Build.MANUFACTURER, Build.MODEL, soc).filter { it.isNotBlank() && it != "unknown" }.joinToString(" ")
}

private fun thermalStatus(ctx: Context): Int =
    ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1

private fun ms(v: Double) = String.format(Locale.ROOT, "%.1f", v)

@Composable
fun BenchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { ModelStore.list(ctx) }
    var model by remember { mutableStateOf(models.firstOrNull()) }
    var threads by remember { mutableIntStateOf(4) }
    var npu by remember { mutableStateOf(false) }
    var runner by remember { mutableStateOf<PoseRunner?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var poses by remember { mutableStateOf<List<Pose>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var detectInfo by remember { mutableStateOf("") }
    var benchText by remember { mutableStateOf("") }
    var benchLineText by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val synthetic = remember { Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) } }

    BackHandler(enabled = busy) { } // never close the session while a run is in flight

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { decodeImage(ctx, uri) }
                .onSuccess { image = it; poses = emptyList(); detectInfo = "" }
                .onFailure { status = ctx.getString(R.string.bench_error, it.message ?: it.javaClass.simpleName) }
        }
    }

    LaunchedEffect(model, threads, npu) {
        runner?.close()
        runner = null
        poses = emptyList()
        val m = model ?: return@LaunchedEffect
        status = ctx.getString(R.string.bench_loading, m)
        val r = withContext(Dispatchers.IO) { runCatching { PoseRunner(ctx, m, threads, npu) } }
        if (!isActive) {
            r.getOrNull()?.close()
            return@LaunchedEffect
        }
        r.onSuccess {
            runner = it
            status = ctx.getString(R.string.bench_ready, it.loadMs, it.modelMb, it.inputW, it.inputH) +
                "\n" + it.provider +
                (it.npuFallback?.let { f -> "\n" + ctx.getString(R.string.bench_npu_fallback, f) } ?: "")
        }.onFailure {
            status = ctx.getString(R.string.bench_error, it.message ?: it.javaClass.simpleName)
        }
    }
    DisposableEffect(Unit) { onDispose { runner?.close() } }

    fun runDetect() {
        val r = runner ?: return
        val bmp = image ?: return
        busy = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.Default) { r.detect(bmp) }
                poses = res.poses
                detectInfo = ctx.getString(
                    R.string.bench_detect_info, res.poses.size,
                    ms(res.preMs), ms(res.inferMs), ms(res.postMs), ms(res.totalMs),
                )
            } catch (e: Exception) {
                status = ctx.getString(R.string.bench_error, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun runBench() {
        val r = runner ?: return
        val bmp = image ?: synthetic
        val imgLabel = if (image != null) "photo ${bmp.width}x${bmp.height}" else "synthetic"
        busy = true
        progress = 0
        benchText = ""
        benchLineText = ""
        scope.launch {
            try {
                val rep = r.bench(bmp, runs = 50, warmup = 3) { progress = it }
                benchText = ctx.getString(
                    R.string.bench_result,
                    ms(rep.total.meanMs), ms(rep.total.medianMs), ms(rep.total.p95Ms), ms(rep.total.fps),
                    ms(rep.pre.medianMs), ms(rep.infer.medianMs), ms(rep.post.medianMs),
                    rep.poses, thermalStatus(ctx),
                )
                benchLineText = benchLine(deviceName(), r.modelName, r.provider, r.inputW, imgLabel, rep.total)
            } catch (e: Exception) {
                status = ctx.getString(R.string.bench_error, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.back)) }
        }
        Text(stringResource(R.string.bench_title), style = MaterialTheme.typography.headlineSmall)

        if (models.isEmpty()) {
            Text(stringResource(R.string.bench_no_models))
        } else {
            Text(stringResource(R.string.bench_model), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { m ->
                    FilterChip(selected = m == model, enabled = !busy, onClick = { model = m }, label = { Text(m.removeSuffix(".onnx")) })
                }
            }
            Text(stringResource(R.string.bench_backend), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !npu, enabled = !busy, onClick = { npu = false }, label = { Text("CPU") })
                FilterChip(selected = npu, enabled = !busy, onClick = { npu = true }, label = { Text("NPU (QNN)") })
            }
            Text(stringResource(R.string.bench_threads), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THREAD_CHOICES.forEach { t ->
                    FilterChip(selected = t == threads, enabled = !busy, onClick = { threads = t }, label = { Text("$t") })
                }
            }
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) { Text(stringResource(R.string.bench_pick_image)) }
            Button(enabled = !busy && runner != null && image != null, onClick = { runDetect() }) {
                Text(stringResource(R.string.bench_run_image))
            }
        }
        image?.let { PoseOverlayImage(it, poses, Modifier.fillMaxWidth()) }
        if (detectInfo.isNotEmpty()) Text(detectInfo, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

        Button(enabled = !busy && runner != null, onClick = { runBench() }) {
            Text(stringResource(R.string.bench_run_bench))
        }
        if (busy && progress > 0) LinearProgressIndicator(progress = { progress / 50f }, modifier = Modifier.fillMaxWidth())
        if (image == null) Text(stringResource(R.string.bench_synthetic_note), style = MaterialTheme.typography.bodySmall)
        if (benchText.isNotEmpty()) {
            Text(benchText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(benchLineText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("bench", benchLineText))
            }) { Text(stringResource(R.string.bench_copy)) }
        }
        SamBenchSection(image, locked = busy)
    }
}

/** The photo with person boxes, COCO skeleton and joints on top (frame pixels scaled to the view). */
@Composable
fun PoseOverlayImage(bmp: Bitmap, poses: List<Pose>, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(bmp.width.toFloat() / bmp.height)) {
        Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / bmp.width
            for (p in poses) {
                drawRect(
                    Color.Green,
                    topLeft = Offset((p.bbox.x1 * s).toFloat(), (p.bbox.y1 * s).toFloat()),
                    size = Size(((p.bbox.x2 - p.bbox.x1) * s).toFloat(), ((p.bbox.y2 - p.bbox.y1) * s).toFloat()),
                    style = Stroke(3f),
                )
                for ((a, b) in COCO_SKELETON) {
                    if (p.conf(a) < KP_MIN_CONF || p.conf(b) < KP_MIN_CONF) continue
                    drawLine(
                        Color.Yellow,
                        Offset((p.x(a) * s).toFloat(), (p.y(a) * s).toFloat()),
                        Offset((p.x(b) * s).toFloat(), (p.y(b) * s).toFloat()),
                        strokeWidth = 4f,
                    )
                }
                for (k in 0 until p.count) {
                    if (p.conf(k) < KP_MIN_CONF) continue
                    drawCircle(Color.Red, 6f, Offset((p.x(k) * s).toFloat(), (p.y(k) * s).toFloat()))
                }
            }
        }
    }
}
