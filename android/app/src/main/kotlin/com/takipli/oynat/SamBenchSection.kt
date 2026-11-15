package com.takipli.oynat

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.takipli.oynat.core.BenchStats
import kotlinx.coroutines.launch

/** One EdgeTAM graph timed on CPU and on the NPU (`null` stats = could not run; the note says why). */
class SamBenchRow(val component: String, val cpu: BenchStats?, val npu: BenchStats?, val npuNote: String?, val loadMs: Long)

class SamBenchReport(val rows: List<SamBenchRow>, val parity: String, val thermal: Int, val lastRun: String) {
    /** Text for the screen / clipboard / "Bench lines" in handover-android.md. */
    fun text(device: String): String = buildString {
        appendLine("$device | thermal $thermal (rerun after cooling if > 1)")
        for (r in rows) {
            val c = r.cpu?.let { "CPU %.1f ms (p95 %.1f)".format(it.meanMs, it.p95Ms) } ?: "CPU failed"
            val n = r.npu?.let { "NPU %.1f ms (p95 %.1f)".format(it.meanMs, it.p95Ms) } ?: "NPU failed"
            val x = if (r.cpu != null && r.npu != null && r.npu.meanMs > 0) " x%.1f".format(r.cpu.meanMs / r.npu.meanMs) else ""
            appendLine("${r.component.removePrefix("sam-").removeSuffix(".onnx")} | $c | $n$x | load ${r.loadMs} ms${r.npuNote?.let { " | NPU: $it" } ?: ""}")
        }
        appendLine(parity)
        if (lastRun.isNotEmpty()) appendLine("last scan: $lastRun")
    }
}

/** EdgeTAM section of the bench screen (only in the `sam` build, i.e. when the EdgeTAM graphs are in the APK). */
@Composable
fun SamBenchSection(image: Bitmap?, locked: Boolean) {
    val ctx = LocalContext.current
    if (!SamEngine.hasModels(ctx)) return
    val scope = rememberCoroutineScope()
    var npuOn by remember { mutableStateOf(SamEngine.npuEnabled(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val synthetic = remember {
        Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); c.drawColor(0xFF606060.toInt())
            c.drawCircle(512f, 512f, 220f, Paint().apply { color = 0xFFE0E0E0.toInt(); isAntiAlias = true })
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text(stringResource(R.string.sam_bench_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (SamEngine.hasMemory(ctx)) R.string.sam_bench_memory_ok else R.string.sam_bench_memory_missing), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = npuOn, enabled = !busy && !locked,
                onClick = { npuOn = !npuOn; SamEngine.setNpuEnabled(ctx, npuOn) },
                label = { Text(stringResource(R.string.sam_bench_npu_scans)) },
            )
        }
        Button(enabled = !busy && !locked, onClick = {
            busy = true; text = ""
            scope.launch {
                val rep = try { SamEngine.bench(ctx, image ?: synthetic, 20) { status = ctx.getString(R.string.sam_bench_running, it) } }
                catch (e: Throwable) { ErrorLog.record(ctx, "sam-bench", e); null }
                text = rep?.text("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") ?: ctx.getString(R.string.sam_bench_failed)
                busy = false
            }
        }) { Text(stringResource(R.string.sam_bench_run)) }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(status, style = MaterialTheme.typography.bodySmall) }
        if (image == null) Text(stringResource(R.string.sam_bench_synthetic), style = MaterialTheme.typography.bodySmall)
        if (text.isNotEmpty()) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("sam bench", text))
            }) { Text(stringResource(R.string.bench_copy)) }
        }
    }
}
