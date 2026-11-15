package com.takipli.oynat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takipli.oynat.core.ErrKind
import com.takipli.oynat.core.classifyError
import com.takipli.oynat.core.logLine
import com.takipli.oynat.core.lowBattery
import com.takipli.oynat.core.trimLog
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Part 15: process setup. Installs the crash logger and removes the half-written export a killed process leaves behind. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ErrorLog.installCrashHandler(this)
        runCatching { File(cacheDir, "export").deleteRecursively() }
    }
}

/** Plain-text error log in `files/logs/error.log` (newest last, capped). Shared from the home screen. */
object ErrorLog {
    private const val MAX_CHARS = 100_000
    private const val SHARE_CHARS = 40_000
    private val lock = Any()
    private fun file(ctx: Context) = File(ctx.filesDir, "logs/error.log")

    fun record(ctx: Context, where: String, t: Throwable, codeName: String? = null) {
        runCatching {
            val kind = classifyError(codeName, t.message, t.javaClass.name)
            val frames = t.stackTrace.take(5).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            val line = logLine(stamp, where, kind, "${t.javaClass.simpleName}: ${t.message.orEmpty()} [${codeName ?: "-"}] @ $frames")
            synchronized(lock) {
                val f = file(ctx)
                f.parentFile?.mkdirs()
                f.writeText(trimLog((if (f.isFile) f.readText() else "") + line + "\n", MAX_CHARS))
            }
        }
    }

    fun read(ctx: Context): String = runCatching { synchronized(lock) { file(ctx).takeIf { it.isFile }?.readText().orEmpty() } }.getOrDefault("")

    fun clear(ctx: Context) { runCatching { synchronized(lock) { file(ctx).delete() } } }

    /** Version and device on the first line, then the end of the log: what to paste into the next session. */
    fun report(ctx: Context): String {
        val tr = Trace.read(ctx).takeLast(4000)
        return "Takipli Oynat ${BuildConfig.VERSION_NAME} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            trimLog(read(ctx), SHARE_CHARS) + if (tr.isBlank()) "" else "\n--- last video trace ---\n$tr"
    }

    fun installCrashHandler(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            record(app, "crash:${th.name}", e)
            previous?.uncaughtException(th, e)
        }
    }
}

/** Breadcrumbs for native crashes: every line is appended and closed at once, so the last line survives a killed process. */
object Trace {
    private fun file(ctx: Context) = File(ctx.filesDir, "logs/trace.log")
    fun reset(ctx: Context) { runCatching { file(ctx).apply { parentFile?.mkdirs(); writeText("") } } }
    fun step(ctx: Context, msg: String) {
        runCatching { val f = file(ctx); f.parentFile?.mkdirs(); f.appendText("${System.currentTimeMillis() % 100_000_000L} $msg\n") }
    }
    fun read(ctx: Context): String = runCatching { file(ctx).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
}

/** Preflight checks and user-facing messages. */
object Safety {
    /** An error as one friendly sentence plus the short technical reason in brackets (the log has the full line). */
    fun friendly(ctx: Context, t: Throwable, codeName: String? = null): String {
        val id = when (classifyError(codeName, t.message, t.javaClass.name)) {
            ErrKind.NO_SPACE -> R.string.err_no_space
            ErrKind.MODEL_MISSING -> R.string.err_model
            ErrKind.VIDEO_ACCESS -> R.string.err_video
            ErrKind.CODEC -> R.string.err_codec
            ErrKind.OTHER -> R.string.err_other
        }
        val raw = (codeName ?: t.message ?: t.javaClass.simpleName).take(160)
        return ctx.getString(id) + "\n[" + raw + "]"
    }

    /** False when the picked video can no longer be opened (file moved or deleted, or the SAF grant was lost). */
    fun videoReadable(ctx: Context, uri: Uri): Boolean =
        runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)

    /** Free bytes on the volume of [dir]; `Long.MAX_VALUE` when unknown (never blocks on a failed reading). */
    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun batteryLow(ctx: Context): Boolean {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return lowBattery(if (level >= 0 && scale > 0) level * 100 / scale else -1, charging)
    }

    fun shareLog(ctx: Context) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ErrorLog.report(ctx))
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Shown only while the battery is low and the phone is not charging (checked every 30 s). */
@Composable
fun BatteryWarning() {
    val ctx = LocalContext.current
    var low by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { low = Safety.batteryLow(ctx); delay(30_000) } }
    if (low) Text(stringResource(R.string.battery_low), color = MaterialTheme.colorScheme.error)
}

/** Home screen footer: licence summary and the error log buttons. */
@Composable
fun AboutSection() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    Text(stringResource(R.string.about_licences), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (ErrorLog.read(ctx).isBlank() && Trace.read(ctx).isBlank()) msg = ctx.getString(R.string.log_empty) else { msg = ""; Safety.shareLog(ctx) }
        }) { Text(stringResource(R.string.log_share)) }
        OutlinedButton(onClick = { ErrorLog.clear(ctx); msg = ctx.getString(R.string.log_cleared) }) { Text(stringResource(R.string.log_clear)) }
    }
    if (msg.isNotEmpty()) Text(msg)
}
