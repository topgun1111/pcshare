package com.takipli.oynat

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val FPS_CHOICES = listOf(5, 10, 15, 30)
private const val TEST_SECONDS = 20.0

/** Part 7 test screen: open a video (file picker), see its facts, sample it at a chosen fps, resume from the middle. */
@Composable
fun DecodeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var fps by remember { mutableStateOf(10) }
    var analyze by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    BackHandler(enabled = busy) { }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { VideoDecoder.probe(ctx, u) } }
                .onSuccess { uri = u; info = it; report = ""; last = null }
                .onFailure { report = ctx.getString(R.string.bench_error, it.message ?: it.javaClass.simpleName) }
        }
    }

    fun run(startS: Double) {
        val u = uri ?: return
        busy = true
        report = ""
        scope.launch {
            val ts = ArrayList<Double>()
            val cutTimes = ArrayList<Double>()
            var okSteps = 0
            var analysisNs = 0L
            val an = if (analyze && OpenCvFlow.available) FrameAnalyzer() else null
            val note = if (analyze && an == null) "OpenCV failed to load: motion analysis skipped\n" else ""
            val t0 = SystemClock.elapsedRealtime()
            try {
                val n = VideoDecoder.sample(ctx, u, fps.toDouble(), startS, startS + TEST_SECONDS) { f ->
                    ts.add(f.t)
                    last = f.bitmap
                    an?.let { a ->
                        val a0 = System.nanoTime()
                        val r = a.analyze(f.bitmap)
                        analysisNs += System.nanoTime() - a0
                        if (r.motionOk) okSteps++
                        if (r.cut) cutTimes.add(f.t)
                    }
                    true
                }
                val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                val span = if (ts.size > 1) ts.last() - ts.first() else 0.0
                val gaps = ts.zipWithNext { a, b -> b - a }
                fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
                report = buildString {
                    appendLine("samples: $n @ $fps fps, start ${f(startS)} s")
                    appendLine("first t: ${ts.firstOrNull()?.let(::f)} | last t: ${ts.lastOrNull()?.let(::f)}")
                    if (gaps.isNotEmpty()) appendLine("gap min/max: ${f(gaps.min())} / ${f(gaps.max())} s (grid ${f(1.0 / fps)})")
                    appendLine("wall ${f(wall)} s for ${f(span)} s of video = ${f(if (wall > 0) span / wall else 0.0)} x realtime")
                    appendLine("head: " + ts.take(6).joinToString { f(it) })
                    append(note)
                    if (an != null && ts.isNotEmpty()) {
                        val w = an.world
                        appendLine("motion: ok ${okSteps}/${ts.size} steps | ${f(analysisNs / 1e6 / ts.size)} ms/frame")
                        appendLine("camera pan since last cut/start: x ${f(w[2])} px, y ${f(w[5])} px (frame px, + = camera moved right/down)")
                        append("cuts: " + if (cutTimes.isEmpty()) "none" else cutTimes.joinToString { f(it) })
                    }
                }
            } catch (e: Exception) {
                report = ctx.getString(R.string.bench_error, e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.decode_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("video/*")) }) {
            Text(stringResource(R.string.decode_pick))
        }
        info?.let { i ->
            Text(
                "${i.mime} | coded ${i.codedW}x${i.codedH} | rotation ${i.rotation}° | display ${i.width}x${i.height}\n" +
                    "duration ${String.format(Locale.ROOT, "%.2f", i.durationS)} s | declared fps ${i.frameRate ?: "n/a"}",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            Text(stringResource(R.string.decode_fps), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FPS_CHOICES.forEach { c ->
                    FilterChip(selected = c == fps, enabled = !busy, onClick = { fps = c }, label = { Text("$c") })
                }
            }
            FilterChip(selected = analyze, enabled = !busy, onClick = { analyze = !analyze }, label = { Text(stringResource(R.string.decode_analyze)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { run(0.0) }) { Text(stringResource(R.string.decode_from_start)) }
                Button(enabled = !busy && i.durationS > 2 * TEST_SECONDS / 10, onClick = { run(i.durationS / 2) }) {
                    Text(stringResource(R.string.decode_from_middle))
                }
            }
        }
        if (busy) Text(stringResource(R.string.decode_running))
        if (report.isNotEmpty()) Text(report, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        last?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height))
        }
    }
}
