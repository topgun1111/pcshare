package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Google Drive REST v3 plumbing (HANDOVER chapter 17, Part 2). No Drive logic here (that is GdriveFs, Part 3+): only
 * - [GdApi]   one HTTP helper per account: Bearer token, one 401 retry with a fresh token, back-off on 429 / 5xx / rate-limit 403,
 *             Google error -> [NotFound] / [Denied] / [Full] / [IOException], paging helper, PATCH sent as POST + override header (D3).
 * - [GdTime]  RFC 3339 <-> epoch seconds without java.time (minSdk 24).
 * - [GdMime]  Drive mime types: folder, shortcut, Google-native documents and their export format (D5).
 * - [GdCache] process-wide path -> id cache keyed by account id (D4); NOT in the endpoint object because Jobs.ep() builds a new one per call.
 */
object GdMime {
    const val FOLDER = "application/vnd.google-apps.folder"
    const val SHORTCUT = "application/vnd.google-apps.shortcut"
    private const val DOC = "application/vnd.google-apps.document"
    private const val SHEET = "application/vnd.google-apps.spreadsheet"
    private const val SLIDES = "application/vnd.google-apps.presentation"
    private const val DRAWING = "application/vnd.google-apps.drawing"

    /** (extension added to the name, export mime) of a Google-native file, null = a normal file with bytes or a hidden kind. */
    fun export(mime: String): Pair<String, String>? = when (mime) {
        DOC -> ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        SHEET -> ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        SLIDES -> ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        DRAWING -> ".pdf" to "application/pdf"
        else -> null
    }

    /** Google-native kinds that cannot be downloaded as a file at all (forms, sites, maps, scripts ...): not listed. */
    fun hidden(mime: String): Boolean =
        mime.startsWith("application/vnd.google-apps.") && mime != FOLDER && mime != SHORTCUT && export(mime) == null
}

object GdTime {
    /** "2026-10-28T11:29:00.123Z" or with an offset "+02:00" -> epoch seconds. 0 when the text is empty or malformed. */
    fun parse(s: String?): Long {
        if (s == null || s.length < 19) return 0L
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(5, 7).toInt()
            val d = s.substring(8, 10).toInt()
            val h = s.substring(11, 13).toInt()
            val mi = s.substring(14, 16).toInt()
            val se = s.substring(17, 19).toInt()
            var i = 19
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
            var off = 0L
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val sign = if (s[i] == '-') -1L else 1L
                val oh = s.substring(i + 1, i + 3).toInt()
                val om = if (s.length >= i + 6) s.substring(i + 4, i + 6).toInt() else 0
                off = sign * (oh * 3600L + om * 60L)
            }
            days(y, mo, d) * 86400L + h * 3600L + mi * 60L + se - off
        } catch (_: Exception) { 0L }
    }

    /** Epoch seconds -> "2026-10-28T11:29:00.000Z" (for modifiedTime when a copy keeps the original time). */
    fun format(sec: Long): String {
        val dayN = Math.floorDiv(sec, 86400L)
        val rem = Math.floorMod(sec, 86400L).toInt()
        val z = dayN + 719468L
        val era = Math.floorDiv(z, 146097L)
        val doe = (z - era * 146097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = (yoe + era * 400L).toInt() + (if (m <= 2) 1 else 0)
        return String.format(java.util.Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.000Z", y, m, d, rem / 3600, rem % 3600 / 60, rem % 60)
    }

    /** Days since 1970-01-01 of a civil date (Howard Hinnant's algorithm). */
    private fun days(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}

/**
 * Part 10: one pacing gate per account, shared by every thread (listing, thumbnails, walks, copies).
 * Requests START at least [gap] ms apart (40 ms = 25 / s, far below Drive's quota). A rate-limit answer ([hit]) doubles the gap
 * (max 1 s) and holds ALL threads of the account until the back-off is over, so parallel jobs slow down together instead of each
 * hammering Drive; 20 good answers in a row halve the gap again ([ok]). Chunk bodies are not paced (only the start of a request).
 */
object GdGate {
    private const val MIN_GAP = 40L
    private const val MAX_GAP = 1000L
    private class S { var next = 0L; var gap = MIN_GAP; var good = 0 }
    private val m = ConcurrentHashMap<String, S>()
    private fun s(acc: String) = m.getOrPut(acc) { S() }

    fun await(acc: String) {
        val st = s(acc)
        val delay = synchronized(st) {
            val now = System.currentTimeMillis()
            val t = maxOf(now, st.next)
            st.next = t + st.gap
            t - now
        }
        if (delay > 0) try { Thread.sleep(delay) } catch (_: InterruptedException) { throw Cancelled() }
    }

    fun hit(acc: String, holdMs: Long) {
        val st = s(acc)
        synchronized(st) {
            st.gap = (st.gap * 2).coerceAtMost(MAX_GAP); st.good = 0
            st.next = maxOf(st.next, System.currentTimeMillis() + holdMs)
        }
    }

    fun ok(acc: String) {
        val st = s(acc)
        synchronized(st) { if (st.gap > MIN_GAP && ++st.good >= 20) { st.good = 0; st.gap = (st.gap / 2).coerceAtLeast(MIN_GAP) } }
    }
}

class GdApi(val accountId: String, private val email: String) {
    class Resp(val code: Int, val body: ByteArray, val headers: Map<String, String>) {
        fun text(): String = body.toString(Charsets.UTF_8)
        fun json(): JSONObject = if (body.isEmpty()) JSONObject() else JSONObject(text())
    }

    companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        /** Metadata fields every listing / lookup asks for (Part 3 builds "files(...)" from it). */
        const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,parents,trashed,shortcutDetails(targetId,targetMimeType)"
        private const val MAX_TRIES = 6

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Query-string value for a Drive "q" literal: backslash and single quote escaped, wrapped in quotes. */
        fun qs(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

        private val SAD = Regex("^/files(/[^/]+(/copy)?)?$")

        /** Part 11: file calls (list / get / create / update / copy; NOT export) always say supportsAllDrives=true so shared-drive items work. */
        fun url(path: String, query0: Map<String, String> = emptyMap(), base: String = BASE): String {
            val query = if (SAD.matches(path) && !query0.containsKey("supportsAllDrives")) query0 + ("supportsAllDrives" to "true") else query0
            if (query.isEmpty()) return base + path
            return base + path + "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        }

        /** (HTTP status, first Google reason, message) read from an error body; tolerant of non-JSON bodies. */
        fun parseError(code: Int, body: String): Triple<Int, String, String> {
            return try {
                val e = JSONObject(body).getJSONObject("error")
                val reason = e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty().ifEmpty { e.optString("status") }
                Triple(code, reason, e.optString("message").ifEmpty { body.take(200) })
            } catch (_: Exception) { Triple(code, "", body.take(200).ifEmpty { "HTTP $code" }) }
        }

        fun isRateLimit(code: Int, reason: String): Boolean =
            code == 429 || (code == 403 && (reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" || reason == "sharingRateLimitExceeded"))

        /** The exception a failed call turns into (see decision D3 / Part 2 error mapping). */
        fun errorFor(code: Int, body: String): IOException {
            val (_, reason, msg) = parseError(code, body)
            return when {
                code == 404 -> NotFound("Google Drive: not found" + if (msg.isNotEmpty()) " ($msg)" else "")
                code == 403 && (reason == "storageQuotaExceeded" || msg.contains("storage quota", true)) -> Full("Google Drive is full: $msg")
                code == 403 && (msg.contains("has not been used", true) || reason == "accessNotConfigured") ->
                    Denied("Google Drive API is switched off for this project: $msg - enable it in Google Cloud Console (APIs & Services > Library)")
                code == 403 -> Denied("Google Drive refused: $msg")
                code == 401 -> Denied("Google Drive needs you to sign in again: Main menu > Google Drive > Reconnect")
                code == 400 -> IOException("Google Drive rejected the request: $msg")
                else -> IOException("Google Drive error $code: $msg")
            }
        }

        private fun pause(ms: Long) {
            try { Thread.sleep(ms) } catch (_: InterruptedException) { throw Cancelled() }
        }
    }

    private fun backoff(attempt: Int, retryAfterSec: Long): Long {
        val own = (1000L shl attempt).coerceAtMost(32_000L) + (Math.random() * 500).toLong()
        return maxOf(own, retryAfterSec * 1000L).coerceAtMost(60_000L)
    }

    private fun connect(method: String, url: String, token: String, body: ByteArray?, contentType: String?, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        // HttpURLConnection has no PATCH: POST + override header (D3)
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        val sends = body != null || method == "POST" || method == "PATCH" || method == "PUT"
        if (sends) {
            c.doOutput = true
            val b = body ?: ByteArray(0)
            if (contentType != null) c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(b.size)
            if (b.isNotEmpty()) c.outputStream.use { it.write(b) }
        }
        return c
    }

    private fun readAll(c: HttpURLConnection, code: Int): ByteArray =
        try { ((if (code in 200..399) c.inputStream else c.errorStream)?.use { it.readBytes() }) ?: ByteArray(0) } catch (_: IOException) { ByteArray(0) }

    private fun headersOf(c: HttpURLConnection): Map<String, String> {
        val m = HashMap<String, String>()
        for ((k, v) in c.headerFields) if (k != null && !v.isNullOrEmpty()) m[k.lowercase()] = v[0]
        return m
    }

    /**
     * One call with all the retry rules. [ok] = extra status codes that are NOT errors (308 for a resumable upload chunk).
     * 401 -> one retry with a forced fresh token. 429 / 5xx / rate-limit 403 -> back-off, up to 5 tries. Network errors are
     * retried only for GET / HEAD (a repeated POST could create twice; chunk PUTs are re-sent by the caller after asking the session).
     */
    fun send(method: String, url: String, body: ByteArray? = null, contentType: String? = null,
             headers: Map<String, String> = emptyMap(), ok: Set<Int> = emptySet()): Resp {
        var token = GAuth.token(email)
        var refreshed = false
        var attempt = 0
        while (true) {
            var c: HttpURLConnection? = null
            try {
                GdGate.await(accountId)
                c = connect(method, url, token, body, contentType, headers)
                val code = c.responseCode
                val data = readAll(c, code)
                val hdr = headersOf(c)
                if (code in 200..299 || code in ok) { GdGate.ok(accountId); return Resp(code, data, hdr) }
                if (code == 401 && !refreshed) { refreshed = true; token = GAuth.token(email, force = true); continue }
                val (_, reason, _) = parseError(code, data.toString(Charsets.UTF_8))
                val retry = isRateLimit(code, reason) || code == 500 || code == 502 || code == 503 || code == 504
                if (retry && attempt < MAX_TRIES - 1) {
                    val wait = backoff(attempt, hdr["retry-after"]?.toLongOrNull() ?: 0L)
                    if (isRateLimit(code, reason)) GdGate.hit(accountId, wait) else pause(wait)   // rate limit: hold every thread of the account
                    attempt++
                    continue
                }
                throw errorFor(code, data.toString(Charsets.UTF_8))
            } catch (e: IOException) {
                if (e is NotFound || e is Denied || e is Full || e.javaClass == IOException::class.java && (e.message ?: "").startsWith("Google Drive")) throw e
                if ((method == "GET" || method == "HEAD") && attempt < 3) { pause(backoff(attempt, 0L)); attempt++; continue }
                throw e
            } finally { c?.disconnect() }
        }
    }

    /** JSON call: [path] like "/files/ID", [query] added to the URL, [body] sent as JSON. [base] = [UPLOAD] for upload endpoints. */
    fun json(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null, base: String = BASE): JSONObject =
        send(method, url(path, query, base), body?.toString()?.toByteArray(Charsets.UTF_8), if (body != null) "application/json; charset=UTF-8" else null).json()

    /**
     * Walks a paged list ("files", "drives" ...): [visit] gets every page's array and returns false to stop early.
     * [query] must already hold "fields" with nextPageToken in it, e.g. "nextPageToken,files(...)".
     */
    fun pages(path: String, query: Map<String, String>, key: String = "files", visit: (JSONArray) -> Boolean) {
        var token: String? = null
        do {
            val q = if (token == null) query else query + ("pageToken" to token)
            val r = json("GET", path, q)
            if (!visit(r.optJSONArray(key) ?: JSONArray())) return
            token = r.optString("nextPageToken").ifEmpty { null }
        } while (token != null)
    }

    /**
     * A GET whose body is streamed by the caller (file download / export). Checked and retried like [send]; the caller MUST
     * disconnect the returned connection. [range] = "bytes=START-" or null.
     */
    fun stream(url: String, range: String? = null): HttpURLConnection {
        var token = GAuth.token(email)
        var refreshed = false
        var attempt = 0
        while (true) {
            GdGate.await(accountId)
            val c = connect("GET", url, token, null, null, if (range != null) mapOf("Range" to range) else emptyMap())
            try {
                val code = c.responseCode
                if (code in 200..299) { GdGate.ok(accountId); return c }
                val data = readAll(c, code)
                val hdr = headersOf(c)
                c.disconnect()
                if (code == 401 && !refreshed) { refreshed = true; token = GAuth.token(email, force = true); continue }
                val (_, reason, _) = parseError(code, data.toString(Charsets.UTF_8))
                val retry = isRateLimit(code, reason) || code == 500 || code == 502 || code == 503 || code == 504
                if (retry && attempt < MAX_TRIES - 1) {
                    val wait = backoff(attempt, hdr["retry-after"]?.toLongOrNull() ?: 0L)
                    if (isRateLimit(code, reason)) GdGate.hit(accountId, wait) else pause(wait)
                    attempt++; continue
                }
                throw errorFor(code, data.toString(Charsets.UTF_8))
            } catch (e: IOException) {
                c.disconnect()
                if (e is NotFound || e is Denied || e is Full || (e.message ?: "").startsWith("Google Drive")) throw e
                if (attempt < 3) { pause(backoff(attempt, 0L)); attempt++; continue }
                throw e
            }
        }
    }
}

object GdCache {
    class Ent(val id: String, val dir: Boolean, val mime: String, val link: Boolean = false)

    private const val MAX = 30_000
    private val maps = ConcurrentHashMap<String, ConcurrentHashMap<String, Ent>>()
    private fun m(acc: String) = maps.getOrPut(acc) { ConcurrentHashMap() }

    /** The id of [path] if known. The root is always "root" (Drive's alias for My Drive). */
    fun get(acc: String, path: String): Ent? {
        val p = vnorm(path)
        return if (p == "/") Ent("root", true, GdMime.FOLDER) else m(acc)[p]
    }

    fun put(acc: String, path: String, e: Ent) {
        val mm = m(acc)
        if (mm.size >= MAX) mm.clear()
        mm[vnorm(path)] = e
    }

    /** A folder was listed: its [kids] (name -> entry) replace what was known directly below [dir]; stale children and their subtrees go. */
    fun putChildren(acc: String, dir: String, kids: Map<String, Ent>) {
        val d = vnorm(dir)
        val pre = if (d == "/") "/" else "$d/"
        val mm = m(acc)
        for (k in mm.keys.toList()) {
            if (k.length > pre.length && k.startsWith(pre)) {
                val rest = k.substring(pre.length)
                val first = rest.substringBefore('/')
                if (first !in kids) mm.remove(k)
            }
        }
        if (mm.size + kids.size >= MAX) mm.clear()
        for ((n, e) in kids) mm[if (d == "/") "/$n" else "$d/$n"] = e
    }

    /** [path] and everything below it are forgotten (after rename / move / delete / write). */
    fun drop(acc: String, path: String) {
        val p = vnorm(path)
        if (p == "/") { maps.remove(acc); return }
        val mm = m(acc)
        mm.remove(p)
        val pre = "$p/"
        for (k in mm.keys.toList()) if (k.startsWith(pre)) mm.remove(k)
    }

    fun clear(acc: String) { maps.remove(acc) }
}
