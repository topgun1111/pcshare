package com.takipli.oynat

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takipli.oynat.core.scanSig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val SCAN_FPS = listOf(5, 10, 15)

/** Part 9 test screen: scan a whole video into `files/scans/<id>.track.json` (v2), cancel, resume, see x-realtime. */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { ModelStore.list(ctx).filterNot { it.startsWith("sam-") } } // sam-* graphs are not pose models (side-loaded ones show up in the list too)
    var uri by remember { mutableStateOf<Uri?>(null) }
    var videoId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(models.firstOrNull { it.contains("v8s") } ?: models.firstOrNull().orEmpty()) }
    var npu by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(10) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<ScanProgress?>(null) }
    var report by remember { mutableStateOf("") }
    val busy = job != null

    BackHandler(enabled = busy) { }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            videoId = withContext(Dispatchers.IO) { videoIdOf(ctx, u) }
            uri = u; report = ""; progress = null
        }
    }

    fun start() {
        val u = uri ?: return
        report = ""
        job = scope.launch {
            try {
                val res = withContext(Dispatchers.Default) {
                    PoseRunner(ctx, model, threads = 4, wantNpu = npu).use { runner ->
                        val file = File(ctx.filesDir, "scans/${scanSig(videoId, 0.0, "")}.track.json")
                        val r = Scanner.scan(ctx, u, videoId, runner, fps.toDouble(), file) { progress = it }
                        r to (runner.npu to file)
                    }
                }
                val (r, rest) = res
                val (usedNpu, file) = rest
                fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)
                val provider = if (usedNpu) "NPU" else "CPU"
                report = if (r.cached) "cached: ${r.doc.samples.size} samples (same video, fps and model)\n${file.path}"
                else "scan | $model | $provider | $fps fps | ${r.newSamples} new samples (${r.doc.samples.size} total) | " +
                    "${f(r.wallS)} s wall for ${f(r.videoS)} s of video = ${f(r.xRealtime)} x realtime | " +
                    "cuts ${r.doc.cuts.size} | v${r.doc.version}\n${file.path} (${file.length() / 1024} KB)"
            } catch (e: CancellationException) {
                report = ctx.getString(R.string.scan_cancelled)
            } catch (e: Throwable) {
                report = ctx.getString(R.string.bench_error, e.message ?: e.javaClass.simpleName)
            } finally {
                job = null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineSmall)
        if (models.isEmpty()) Text(stringResource(R.string.bench_no_models))
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("video/*")) }) { Text(stringResource(R.string.decode_pick)) }
        uri?.let {
            Text(videoId, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(stringResource(R.string.bench_model), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEach { m -> FilterChip(selected = m == model, enabled = !busy, onClick = { model = m }, label = { Text(m.removeSuffix(".onnx")) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SCAN_FPS.forEach { c -> FilterChip(selected = c == fps, enabled = !busy, onClick = { fps = c }, label = { Text("$c fps") }) }
                FilterChip(selected = npu, enabled = !busy, onClick = { npu = !npu }, label = { Text("NPU") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && model.isNotEmpty(), onClick = { start() }) { Text(stringResource(R.string.scan_start)) }
                OutlinedButton(enabled = busy, onClick = { job?.cancel() }) { Text(stringResource(R.string.scan_cancel)) }
            }
        }
        progress?.takeIf { busy }?.let { p ->
            if (p.total > 0) LinearProgressIndicator(progress = { (p.samples.toFloat() / p.total).coerceIn(0f, 1f) })
            Text(
                String.format(Locale.ROOT, "%d / %d samples | t %.1f s | cuts %d | resumed from %d", p.samples, p.total, p.t, p.cuts, p.resumed),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
        }
        if (report.isNotEmpty()) Text(report, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

/** uri + size + modified time: changes when the file is replaced, stable across app restarts. */
internal fun videoIdOf(ctx: android.content.Context, u: Uri): String {
    var size = -1L
    var mtime = -1L
    runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) size = c.getLong(0)
                if (c.columnCount > 1 && !c.isNull(1)) mtime = c.getLong(1)
            }
        }
    }
    return "$u|$size|$mtime"
}
