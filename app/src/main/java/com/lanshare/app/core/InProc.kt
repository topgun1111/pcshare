package com.lanshare.app.core

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** An answer produced in-process: HTTP-style status + headers, body = raw[off until raw.size]. */
class InProcReply(val code: Int, val reason: String, val ctype: String, val headers: Map<String, String>, val raw: ByteArray, val off: Int) {
    fun body() = ByteArrayInputStream(raw, off, raw.size - off)
}

/**
 * Answers the WebView's own quick GET requests (folder listing, free space, stat, job progress, clipboard, info,
 * device list, SMB share list, archive progress: all cheap in-memory/local reads) directly
 * inside the app instead of through a localhost TCP connection: no socket, no accept/thread hand-off, no HTTP parsing.
 * It runs the very same [Routes.handle] code, so the answers are byte-identical to the HTTP ones and ui.html is untouched.
 * Anything else (POSTs, downloads, thumbnails, other devices, archives, searches ...) returns null and goes the normal HTTP way.
 * Called from MainActivity's WebViewClient.shouldInterceptRequest (a WebView background thread).
 */
object InProc {
    /** Set to false to switch the shortcut off completely (everything then uses HTTP as before). */
    @Volatile var enabled = true

    // Only routes that never block on the network or do heavy work. Thumbnails (HTTP-cached by the WebView), downloads (streamed,
    // Range), counts (thread pool), search, and remote/archive listings deliberately stay on the normal HTTP path.
    private val ROUTES = setOf("/api/ls", "/api/space", "/api/stat", "/api/job", "/api/clip", "/api/info", "/api/peers", "/api/smb", "/api/arcjob")
    private val LOCAL_ONLY = setOf("/api/ls", "/api/space", "/api/stat")   // these also reach other devices / archives: those stay on HTTP
    private val SKIP = setOf("content-length", "server", "connection", "content-type")

    fun serve(method: String?, host: String?, port: Int, path: String, rawQuery: String?): InProcReply? {
        if (!enabled || method != "GET" || path !in ROUTES) return null
        val base = Core.url ?: return null
        val mine = base.substringAfterLast(':').toIntOrNull() ?: return null
        if (port != mine || (host != "127.0.0.1" && host != "localhost")) return null
        val query = if (rawQuery.isNullOrEmpty()) emptyMap<String, String>() else MiniHttp.parseQuery(rawQuery)
        if (path in LOCAL_ONLY && (query["dev"] != "local" || (query["path"] ?: "").contains('!'))) return null
        val t0 = System.currentTimeMillis()
        val out = ByteArrayOutputStream(16 * 1024)
        val ex = Exchange("GET", path, query, mapOf("x-ls" to "1"), ByteArrayInputStream(ByteArray(0)), "127.0.0.1", out)
        Routes.handle(ex)
        val raw = out.toByteArray()
        var end = -1
        var i = 0
        while (i + 3 < raw.size) {   // end of the header block (it is at the very start, so this stops after ~150 bytes)
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() && raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) { end = i; break }
            i++
        }
        if (end < 0) return null
        val lines = String(raw, 0, end, Charsets.ISO_8859_1).split("\r\n")
        val st = lines[0].split(" ", limit = 3)
        val code = st.getOrNull(1)?.toIntOrNull() ?: return null
        if (code >= 500) return null   // let the normal HTTP route answer (and report) real server errors
        var ctype = "application/json"
        val headers = LinkedHashMap<String, String>()
        for (k in 1 until lines.size) {
            val c = lines[k].indexOf(':')
            if (c <= 0) continue
            val name = lines[k].substring(0, c).trim()
            val value = lines[k].substring(c + 1).trim()
            if (name.equals("Content-Type", true)) ctype = value.substringBefore(';').trim()
            if (name.lowercase() !in SKIP) headers[name] = value
        }
        val dt = System.currentTimeMillis() - t0
        if (dt > 300) Log.w("LANShare", "slow in-process $path ${query["path"] ?: ""}: $dt ms")
        return InProcReply(code, (st.getOrNull(2) ?: "").ifBlank { "OK" }, ctype, headers, raw, end + 4)
    }
}
