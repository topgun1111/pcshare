package com.lanshare.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** App-wide diagnostics. No network, no public log files, bounded memory and disk.
 * All ordinary writes run on a dedicated daemon. Errors in logging never affect the app. */
object ErrorLog {
    @Volatile private var store: ErrorLogStore? = null
    @Volatile private var version = "unknown"
    private val queue = ArrayBlockingQueue<String>(256)
    private val dropped = AtomicInteger()
    private val recent = LinkedHashMap<String, Long>()
    private val guard = Any()
    private val crashGuard = ThreadLocal<Boolean>()
    private const val DEDUP_MS = 15_000L

    @Synchronized fun init(context: Context, pdfBuild: Boolean) {
        if (store != null) return
        try {
            val ctx = context.applicationContext
            val target = ErrorLogStore(File(ctx.filesDir, "error-logs"), if (pdfBuild) "pdfbuild" else "main")
            version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown" } catch (_: Throwable) { "unknown" }
            store = target
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    if (crashGuard.get() != true) {
                        crashGuard.set(true)
                        target.append(line("FATAL", "Uncaught exception", describe(error), thread.name))
                    }
                } catch (_: Throwable) {
                } finally {
                    // Preserve Android's existing crash behavior. Never swallow the crash.
                    if (previous != null) previous.uncaughtException(thread, error)
                    else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
                }
            }
            Thread({
                while (true) {
                    try {
                        val entry = queue.take()
                        target.append(entry)
                        val lost = dropped.getAndSet(0)
                        if (lost > 0) target.append(line("WARN", "ErrorLog", "$lost log entries dropped during a burst"))
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (_: Throwable) {
                        // A full/unavailable private disk is not a reason to stop any other feature.
                        try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }, "error-log-writer").also { it.isDaemon = true; it.start() }
            // A small environment marker helps distinguish app updates without collecting identifiers.
            record("App", "Diagnostics ready; Android SDK ${Build.VERSION.SDK_INT}", "INFO")
        } catch (_: Throwable) {}
    }

    private fun sensitive(frames: Array<StackTraceElement>): Boolean =
        frames.any { ErrorLogPrivacy.sensitiveClass(it.className) }

    private fun describe(error: Throwable): String {
        val frames = error.stackTrace
        val private = sensitive(frames)
        val message = ErrorLogPrivacy.clean(error.message ?: "", private)
        val trace = frames.take(12).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        val cause = error.cause?.javaClass?.simpleName?.let { "; cause=$it" } ?: ""
        return "${error.javaClass.name}: $message$cause | $trace"
    }

    private fun line(level: String, component: String, detail: String, thread: String = Thread.currentThread().name): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return "$time [$level] [v$version] [${ErrorLogPrivacy.clean(thread)}] ${ErrorLogPrivacy.clean(component)}: $detail"
    }

    private fun enqueue(level: String, component: String, detail: String) {
        if (store == null) return
        val key = "$level|$component|${detail.hashCode()}"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(guard) {
            val at = recent[key]
            if (at != null && now - at < DEDUP_MS) return
            recent[key] = now
            while (recent.size > 256) recent.remove(recent.keys.first())
        }
        if (!queue.offer(line(level, component, detail))) dropped.incrementAndGet()
    }

    fun exception(component: String, error: Throwable) {
        try {
            if (store == null || error is Cancelled || error is InterruptedException) return
            enqueue("ERROR", component, describe(error))
        } catch (_: Throwable) {}
    }

    fun record(component: String, message: String, level: String = "ERROR", privateDetails: Boolean = false) {
        try {
            if (store == null) return
            val frames = Thread.currentThread().stackTrace
            val detail = ErrorLogPrivacy.clean(message, privateDetails || sensitive(frames))
            enqueue(level, component, detail)
        } catch (_: Throwable) {}
    }

    /** UI bridge never accepts raw JS messages, URLs, or arbitrary stack strings. */
    fun web(kind: String, location: String, line: Int) {
        try {
            val safeKind = when (kind) {
                "script", "promise", "network", "http", "toast" -> kind
                else -> "unknown"
            }
            val safeLoc = location.take(80).filter { it.isLetterOrDigit() || it in "/_.- " }
            record("WebView", "$safeKind at $safeLoc:$line")
        } catch (_: Throwable) {}
    }

    fun recent(): String = store?.recent() ?: ""
    fun export(out: OutputStream) {
        val s = store ?: throw java.io.IOException("Diagnostics not initialized")
        s.export(out)
    }
}
