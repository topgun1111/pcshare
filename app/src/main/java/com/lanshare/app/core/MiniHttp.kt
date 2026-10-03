package com.lanshare.app.core

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One HTTP request + the means to answer it. */
class Exchange(
    val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>,
    val body: InputStream, val remoteIp: String, private val out: OutputStream,
) {
    var keepAlive = true
    var sent = false                      // true once a status line went out (an error can no longer replace it)
    var bodyBytes: ByteArray? = null     // filled for local-UI requests (everything except /p/*)

    fun header(name: String): String? = headers[name.lowercase()]
    fun q(name: String): String = query[name] ?: throw BadReq("missing parameter: $name")

    fun readBody() {
        if (bodyBytes != null) return
        val n = header("content-length")?.toLongOrNull() ?: 0L
        if (n > (16 shl 20)) throw BadReq("request body too large")
        val bo = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) { val r = body.read(buf); if (r < 0) break; bo.write(buf, 0, r) }
        bodyBytes = bo.toByteArray()
    }

    fun bodyJson(): JSONObject {
        readBody()
        val s = String(bodyBytes ?: ByteArray(0), Charsets.UTF_8).trim()
        return if (s.isEmpty()) JSONObject() else JSONObject(s)
    }

    fun reply(code: Int, data: ByteArray, ctype: String, extra: Map<String, String> = emptyMap()) {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = ctype
        h["Content-Length"] = data.size.toString()
        h["Cache-Control"] = "no-store"
        h.putAll(extra)
        sent = true
        out.write(head(code, h) + data)
        out.flush()
    }

    fun json(obj: Any, code: Int = 200) = reply(code, obj.toString().toByteArray(Charsets.UTF_8), "application/json")

    /** Streaming answer: headers now (must include Content-Length), then the caller writes the body to [out]. */
    fun begin(code: Int, headers: Map<String, String>): OutputStream {
        sent = true
        out.write(head(code, headers)); return out
    }

    fun fail(code: Int, msg: String) {
        keepAlive = false
        if (sent) return
        try { json(JSONObject().put("error", msg.ifEmpty { "error" }), code) } catch (_: Exception) {}
    }

    private fun head(code: Int, h: Map<String, String>): ByteArray {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Server: LANShare\r\n")
        for ((k, v) in h) sb.append(k).append(": ").append(v).append("\r\n")
        if (!keepAlive) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun reason(c: Int) = when (c) {
        200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        409 -> "Conflict"; 500 -> "Internal Server Error"; else -> "Status"
    }
}

/** Minimal threaded HTTP/1.1 server (keep-alive, Content-Length bodies, no chunked requests, no Expect: 100-continue). */
class MiniHttp(val port: Int, private val handler: (Exchange) -> Unit) {
    private val server = ServerSocket()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "http").also { it.isDaemon = true } }
    @Volatile private var running = true

    fun bind(): MiniHttp {
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), 128)
        return this
    }

    fun start() {
        Thread({
            while (running) {
                try { val s = server.accept(); pool.execute { serve(s) } }
                catch (_: SocketException) { if (!running) break }
                catch (_: IOException) {}
            }
        }, "http-accept").also { it.isDaemon = true }.start()
    }

    fun stop() { running = false; try { server.close() } catch (_: Exception) {} }

    private fun serve(s: Socket) {
        try {
            s.tcpNoDelay = true; s.keepAlive = true; s.soTimeout = 120_000
            val ins = java.io.BufferedInputStream(s.getInputStream(), 65536)
            val out = s.getOutputStream()
            val ip = s.inetAddress?.hostAddress ?: ""
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) continue
                val parts = line.split(" ")
                if (parts.size < 2) break
                val http10 = parts.getOrNull(2) == "HTTP/1.0"
                val hdrs = HashMap<String, String>()
                while (true) {
                    val l = readLine(ins) ?: return
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) hdrs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val len = hdrs["content-length"]?.toLongOrNull() ?: 0L
                val body = LimitedIn(ins, maxOf(len, 0L))
                val target = parts[1]
                val qi = target.indexOf('?')
                val ex = Exchange(parts[0], if (qi < 0) target else target.substring(0, qi),
                    if (qi < 0) emptyMap() else parseQuery(target.substring(qi + 1)), hdrs, body, ip, out)
                if (http10 || hdrs["connection"].equals("close", true)) ex.keepAlive = false
                handler(ex)
                if (!ex.keepAlive) break
                if (body.remaining() > 0) {                    // unread request body: skip it or give up on the connection
                    if (body.remaining() > (1 shl 20)) break
                    val sink = ByteArray(8192)
                    while (body.read(sink) >= 0) { /* drain */ }
                }
            }
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }

    companion object {
        /** Like Python's parse_qs: '+' and %XX decoded, blank values dropped, first value wins. */
        fun parseQuery(q: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            for (kv in q.split('&')) {
                if (kv.isEmpty()) continue
                val i = kv.indexOf('=')
                if (i < 0) continue
                val k = try { URLDecoder.decode(kv.substring(0, i), "UTF-8") } catch (_: Exception) { continue }
                val v = try { URLDecoder.decode(kv.substring(i + 1), "UTF-8") } catch (_: Exception) { continue }
                if (v.isNotEmpty()) m.putIfAbsent(k, v)
            }
            return m
        }

        fun quote(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20").replace("*", "%2A")
    }
}
