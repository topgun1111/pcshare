package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URLEncoder

/** Read-only, seekable view of a file on another device. Re-opens with a Range header on seek and after a dropped connection. */
class RemoteSource(private val rm: RemoteFs, private val v: String) : Source() {
    private var pos = 0L
    private var s: HttpResp? = null
    override val size: Long
    override val seekable: Boolean get() = true

    init {
        val r = rm.callRaw("GET", "file", mapOf("path" to v))
        s = r
        size = maxOf(r.contentLength, 0L)
    }

    override fun seek(pos: Long) { s?.close(); s = null; this.pos = pos }

    private fun reopen() {
        s = rm.callRaw("GET", "file", mapOf("path" to v), headers = if (pos > 0) mapOf("Range" to "bytes=$pos-") else emptyMap())
    }

    override fun read(): Int {
        val b = ByteArray(1)
        while (true) { val n = read(b, 0, 1); if (n < 0) return -1; if (n == 1) return b[0].toInt() and 255 }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var last: Exception? = null
        for (attempt in 0 until 5) {
            try {
                if (s == null) reopen()
                val n = s!!.body.read(b, off, len)
                if (n > 0) pos += n
                return n
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: Exception) {}
                s = null
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw IOException("connection lost (${last?.message})")
    }

    override fun close() { try { s?.close() } catch (_: Exception) {}; s = null }
}

/** Another LANShare device, same interface as LocalFs. */
class RemoteFs(peer: Peer) : Endpoint {
    override val id = peer.id
    override val name = peer.name
    private val port = peer.port
    @Volatile private var ip = peer.ip
    private val ips: List<String> = listOf(peer.ip) + peer.ips.filter { it != peer.ip }

    private fun query(params: Map<String, String>) =
        params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }

    /** Sends the request and returns the open response (status 200/206). Caller closes it. */
    fun callRaw(method: String, route: String, params: Map<String, String> = emptyMap(), body: InputStream? = null,
                size: Long = 0, headers: Map<String, String> = emptyMap(), onSent: ((Int) -> Unit)? = null, timeoutMs: Int = 30_000): HttpResp {
        val retry = (method == "GET" || route == "mkdir") && body == null
        var r: HttpResp? = null
        var err: Exception? = null
        for (rnd in 0 until (if (retry) 3 else 1)) {
            for (cand in ips) {
                try {
                    r = Http.request(cand, port, method, "/p/$route?" + query(params), headers, timeoutMs, body, size, onSent)
                    err = null
                    if (cand != ip) {   // remember the address that works
                        ip = cand
                        Core.discOrNull()?.setPeerIp(id, cand)
                    }
                    break
                } catch (e: Cancelled) { throw e
                } catch (e: Exception) {
                    err = e
                    if (body != null) break   // an upload body cannot be replayed
                }
            }
            if (err == null) break
            Thread.sleep(400)
        }
        if (err != null || r == null) {
            val hint = if (err is ConnectException && (err.message ?: "").contains("refused", true))
                " - LANShare is not running there (Android may have paused it); open it on that phone" else ""
            throw IOException("$name unreachable at ${ips.joinToString(", ")} (${err?.message})$hint")
        }
        if (r.status != 200 && r.status != 206) {
            var txt = try { String(r.readUpTo(800), Charsets.UTF_8) } catch (_: Exception) { "" }
            r.close()
            try { txt = JSONObject(txt).optString("error", txt) } catch (_: Exception) {}
            when (r.status) {
                404 -> throw NotFound(txt)
                403 -> throw Denied(txt)
                507 -> throw Full(txt)
                else -> throw IOException("$name: $txt")
            }
        }
        return r
    }

    private fun bytes(method: String, route: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): ByteArray {
        val r = callRaw(method, route, params, timeoutMs = timeoutMs)
        try { return r.readUpTo(64 shl 20) } finally { r.close() }
    }

    fun ping() { bytes("GET", "ping") }

    override fun ls(v: String): List<Item> {
        val a = JSONArray(String(bytes("GET", "ls", mapOf("path" to v)), Charsets.UTF_8))
        return (0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }
    }

    override fun search(v: String, q: String): SearchResult {
        val o = JSONObject(String(bytes("GET", "search", mapOf("path" to v, "q" to q), 110_000), Charsets.UTF_8))
        val a = o.getJSONArray("items")
        return SearchResult((0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }, o.optBoolean("partial"))
    }

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: NotFound) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> {
        val a = JSONArray(String(bytes("GET", "walk", mapOf("path" to v)), Charsets.UTF_8))
        return (0 until a.length()).map { val o = a.getJSONObject(it); WalkItem(o.getString("rel"), o.optBoolean("dir"), o.optLong("size"), o.optBoolean("skip")) }
    }

    override fun open(v: String): Source = RemoteSource(this, v)

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        callRaw("POST", "put", mapOf("path" to v), body = input, size = size, onSent = cb).close()
    }

    override fun stat(v: String): JSONObject = try {
        JSONObject(String(bytes("GET", "stat", mapOf("path" to v)), Charsets.UTF_8))
    } catch (e: NotFound) {
        if ((e.message ?: "").contains("unknown route", true)) basicStat(v) else throw e   // an older LANShare build on the other side
    }

    /** Fallback for devices without the stat route: what the folder listing and a walk can tell. */
    private fun basicStat(v: String): JSONObject {
        val n = vnorm(v)
        val me = if (n == "/") Item("/", true, 0L, 0L) else ls(vdir(n)).firstOrNull { x -> x.name == vbase(n) } ?: throw NotFound("No such file or directory")
        val o = JSONObject().put("name", me.name).put("path", n).put("dir", me.dir).put("size", me.size).put("mtime", me.mtime)
        if (me.dir) {
            val w = walk(n)
            o.put("files", w.count { x -> !x.dir && !x.skip }.toLong()).put("folders", w.count { x -> x.dir && x.rel.isNotEmpty() }.toLong())
                .put("total", w.sumOf { x -> x.size }).put("partial", false)
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = try {
        val o = JSONObject(String(bytes("GET", "space", mapOf("path" to v)), Charsets.UTF_8))
        if (o.isNull("free") || o.isNull("total")) null else o.getLong("free") to o.getLong("total")
    } catch (_: NotFound) { null }

    override fun mkdir(v: String) { bytes("POST", "mkdir", mapOf("path" to v)) }
    override fun remove(v: String, progress: ((String) -> Unit)?) { bytes("POST", "rm", mapOf("path" to v)); progress?.invoke(vbase(v)) }
    override fun rename(v: String, newName: String) { bytes("POST", "rename", mapOf("path" to v, "name" to newName)) }
    override fun move(v: String, toV: String) { bytes("POST", "mv", mapOf("path" to v, "to" to toV)) }
}
