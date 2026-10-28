package com.lanshare.app.core

import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Print diagnostic log (HANDOVER 9.8). One JSON object per line, EVERY print step of the app, written for the developer / Claude to read later:
 * t (local time) run (process start id) n (counter) src ("app") jid (job id A0001..) th (thread) lvl (i / w / e) ev (event) + event fields.
 * Same shape as the PC's diag.jsonl (pcprint.py 31), so both files can be read side by side; the PC's job id is the one shown in the error text.
 * Place: <storage>/LANShare Logs/print-diag.jsonl (open it with this app's own file manager and share it); without storage access: <filesDir>/print-diag.jsonl.
 * Never throws, never blocks printing. Rotates at 3 MB (print-diag.1.jsonl keeps the previous part).
 */
object PrintLog {
    private val lock = Any()
    private val seq = AtomicInteger(0)
    private val jseq = AtomicInteger(0)
    private val run = SimpleDateFormat("MMddHHmmss", Locale.US).format(Date())
    private const val MAX = 3L shl 20
    private const val NAME = "print-diag.jsonl"
    @Volatile private var started = false

    @Volatile private var hint: android.content.Context? = null
    /** For code that may run before the app's Core started (the Android print service): where the private fallback folder is. */
    fun init(c: android.content.Context) { hint = c.applicationContext }

    private val curJid = ThreadLocal<String?>()
    /** Lines written on this thread without an explicit job id get this one (IPP calls deep inside a Wi-Fi print carry the job's id this way). */
    fun bind(jid: String?) { curJid.set(jid) }
    fun cur(): String? = curJid.get()

    /** New job id for one print job of this process (A0001, A0002, ...). */
    fun jid(): String = "A%04d".format(jseq.incrementAndGet())

    /** The file the log is written to right now (shared storage when the app may use it, else the app's private folder). */
    fun file(): File {
        try {
            if (Core.storageOk == true) {
                val d = File(Environment.getExternalStorageDirectory(), "LANShare Logs")
                if (d.isDirectory || d.mkdirs()) return File(d, NAME)
            }
        } catch (_: Throwable) {}
        val base = (Core.appCtx ?: hint)?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        return File(base, NAME)
    }

    fun ev(ev: String, jid: String?, vararg kv: Pair<String, Any?>) = put("i", ev, jid, null, kv)
    fun warn(ev: String, jid: String?, vararg kv: Pair<String, Any?>) = put("w", ev, jid, null, kv)
    fun err(ev: String, jid: String?, e: Throwable, vararg kv: Pair<String, Any?>) = put("e", ev, jid, e, kv)

    private fun clip(s: String, n0: Int = 6000): String {
        val n = if (n0 > 1500 && !(try { Cfg.printVerbose() } catch (_: Throwable) { true })) 1500 else n0   // Settings > Print log > "Full detail" off
        return if (s.length <= n) s else s.take(n) + "...[+${s.length - n} chars]"
    }

    /** Any value as JSON (strings are clipped, lists / maps / arrays are walked, anything else becomes its toString()). */
    private fun j(v: Any?, depth: Int = 0): Any? = when {
        v == null -> JSONObject.NULL
        v is String -> clip(v)
        v is Number || v is Boolean -> v
        v is JSONObject || v is JSONArray -> clip(v.toString(), 16000)
        v is Throwable -> exc(v)
        depth > 3 -> clip(v.toString(), 500)
        v is Map<*, *> -> JSONObject().also { o -> v.entries.take(60).forEach { o.put(it.key.toString(), j(it.value, depth + 1)) } }
        v is Iterable<*> -> JSONArray().also { a -> v.take(80).forEach { a.put(j(it, depth + 1)) } }
        v is Array<*> -> JSONArray().also { a -> v.take(80).forEach { a.put(j(it, depth + 1)) } }
        else -> clip(v.toString())
    }

    /** Exception as plain data: class, message, stack (last 6000 chars) and the cause chain. */
    fun exc(e: Throwable): JSONObject {
        val o = JSONObject().put("type", e.javaClass.name).put("msg", clip(e.message ?: "", 1500)).put("stack", e.stackTraceToString().takeLast(6000))
        var c = e.cause
        val chain = JSONArray()
        var k = 0
        while (c != null && k < 4) { chain.put(c.javaClass.name + ": " + clip(c.message ?: "", 300)); c = c.cause; k++ }
        if (chain.length() > 0) o.put("cause", chain)
        return o
    }

    private fun put(lvl: String, ev: String, jid: String?, e: Throwable?, kv: Array<out Pair<String, Any?>>) {
        try {
            if (!started) { started = true; start() }
            write(lvl, ev, jid ?: curJid.get(), e, kv)
        } catch (_: Throwable) {}
    }

    private fun write(lvl: String, ev: String, jid: String?, e: Throwable?, kv: Array<out Pair<String, Any?>>) {
        val now = System.currentTimeMillis()
        val o = JSONObject()
        o.put("t", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)) + ".%03d".format(now % 1000))
        o.put("run", run).put("n", seq.incrementAndGet()).put("src", "app")
        if (jid != null) o.put("jid", jid)
        o.put("th", Thread.currentThread().name).put("lvl", lvl).put("ev", ev)
        for ((k, v) in kv) o.put(k, j(v))
        if (e != null) o.put("exc", exc(e))
        val line = o.toString() + "\n"
        synchronized(lock) {
            val f = file()
            f.parentFile?.mkdirs()
            if (f.length() > MAX) {
                val old = File(f.parentFile, "print-diag.1.jsonl")
                try { old.delete(); f.renameTo(old) } catch (_: Throwable) {}
            }
            f.appendText(line, Charsets.UTF_8)
        }
    }

    /** First line of every process: what this phone / build is (so the log can be read without asking). */
    private fun start() {
        val ctx = Core.appCtx
        val ver = try { ctx?.packageManager?.getPackageInfo(ctx.packageName, 0)?.versionName } catch (_: Throwable) { null }
        write("i", "start", null, null, arrayOf(
            "app" to ver, "device" to try { Cfg.name } catch (_: Throwable) { null }, "model" to Build.MANUFACTURER + " " + Build.MODEL, "sdk" to Build.VERSION.SDK_INT,
            "storage_ok" to Core.storageOk, "file" to file().absolutePath,
            "readme" to "print-diag.jsonl: one JSON per line. t=time run=process start n=counter src=app|pc jid=job id (A=app, J=PC) lvl=i|w|e ev=event."))
    }

    /** The last [maxBytes] of the log, from the first complete line (for the app's viewer / share). */
    fun tail(maxBytes: Int = 300_000): String = try {
        synchronized(lock) {
            val f = file()
            if (!f.isFile) "" else java.io.RandomAccessFile(f, "r").use { r ->
                val len = r.length()
                val from = maxOf(0L, len - maxBytes)
                r.seek(from)
                val b = ByteArray((len - from).toInt())
                r.readFully(b)
                var s = String(b, Charsets.UTF_8)
                if (from > 0) s = s.substringAfter('\n', "")
                s
            }
        }
    } catch (_: Throwable) { "" }

    fun size(): Long = try { file().length() } catch (_: Throwable) { 0L }

    fun clear() {
        synchronized(lock) {
            try { val f = file(); f.delete(); File(f.parentFile, "print-diag.1.jsonl").delete() } catch (_: Throwable) {}
        }
        started = false
    }
}
