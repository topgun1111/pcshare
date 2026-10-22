package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * "Free up space" scan (HANDOVER 12.10): one background walk of the storage root that lists what is usually safe to remove.
 * Nothing is deleted here: the page deletes the chosen paths through the normal `POST /api/rm` (-> Recycle bin).
 *  - big: files >= 100 MB (top 30)   - old: files in /Download not changed for 90 days (top 100)   - apk: installer files (top 50)
 *  - bin: Recycle bin items + bytes   - free / total of the storage volume
 * `GET /api/cleanup` -> {running, scanned, error, result}; `POST /api/cleanup {op: start | cancel}`.
 */
object Cleanup {
    private const val BIG = 100L * 1024 * 1024
    private const val OLD_MS = 90L * 24 * 3600 * 1000
    private const val OLD_MIN = 1L * 1024 * 1024
    private val APK = setOf("apk", "xapk", "apks", "apkm")

    @Volatile private var running = false
    @Volatile private var cancel = false
    @Volatile private var scanned = 0
    @Volatile private var err: String? = null
    @Volatile private var result: JSONObject? = null

    private class F(val path: String, val size: Long, val m: Long)

    fun status(): JSONObject = JSONObject().put("running", running).put("scanned", scanned)
        .put("error", err ?: JSONObject.NULL).put("result", result ?: JSONObject.NULL)

    fun cancel() { cancel = true }

    fun start() {
        synchronized(this) {
            if (running) return
            running = true; cancel = false; err = null; scanned = 0
        }
        Thread({
            try { result = scan() } catch (e: Throwable) { err = errText(e) } finally { running = false }
        }, "cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun top(l: List<F>, n: Int) = l.sortedByDescending { it.size }.take(n)
    private fun arr(l: List<F>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("path", it.path).put("size", it.size).put("m", it.m)) } }

    private fun scan(): JSONObject {
        val root = Core.local.root
        val rp = root.path.trimEnd('/')
        val now = System.currentTimeMillis()
        val big = ArrayList<F>(); val apk = ArrayList<F>(); var old = ArrayList<F>()
        var oldN = 0; var oldB = 0L; var apkN = 0; var apkB = 0L; var bigN = 0; var bigB = 0L
        val budget = intArrayOf(300000)   // directories
        fun walk(d: File, rel: String) {
            if (budget[0]-- <= 0 || cancel) return
            val fs = d.listFiles() ?: return
            val inDl = rel.lowercase(Locale.ROOT).let { it == "/download" || it.startsWith("/download/") || it == "/downloads" || it.startsWith("/downloads/") }
            for (f in fs) {
                if (cancel) return
                if (f.name.startsWith(".")) continue
                val r = "$rel/${f.name}"
                if (f.isDirectory) {
                    if (rel.isEmpty() && f.name == "Android") continue
                    walk(f, r)
                } else {
                    scanned++
                    val sz = f.length(); val m = f.lastModified()
                    if (sz >= BIG) { big.add(F(r, sz, m)); bigN++; bigB += sz }
                    if (f.extension.lowercase(Locale.ROOT) in APK) { apk.add(F(r, sz, m)); apkN++; apkB += sz }
                    if (inDl && sz >= OLD_MIN && now - m > OLD_MS) {
                        old.add(F(r, sz, m)); oldN++; oldB += sz
                        if (old.size > 500) old = ArrayList(top(old, 100))   // keep memory small
                    }
                }
            }
        }
        walk(File(rp), "")
        if (cancel) throw IllegalStateException("cancelled")
        var binN = 0; var binB = 0L
        try { for (e in Bin.list()) { binN++; binB += e.size } } catch (_: Exception) {}
        return JSONObject()
            .put("free", root.freeSpace).put("total", root.totalSpace)
            .put("bin", JSONObject().put("items", binN).put("bytes", binB))
            .put("big", JSONObject().put("count", bigN).put("bytes", bigB).put("items", arr(top(big, 30))))
            .put("old", JSONObject().put("count", oldN).put("bytes", oldB).put("items", arr(top(old, 100))))
            .put("apk", JSONObject().put("count", apkN).put("bytes", apkB).put("items", arr(top(apk, 50))))
    }
}
