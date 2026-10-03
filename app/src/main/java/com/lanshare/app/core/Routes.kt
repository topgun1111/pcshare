package com.lanshare.app.core

import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException

// HTTP routing. /p/* is what other LANShare devices call (listing, download, upload, delete ...). /api/dl is the only local route left:
// the native viewers (video / audio / picture / PDF / text) stream files through it with Range requests, and it works the same for
// this phone, other devices, SMB shares and files inside archives. It answers requests from this phone only.
object Routes {
    fun handle(ex: Exchange) {
        try {
            val p = ex.path
            when {
                p == "/p/hello" -> ex.json(Core.disc.hello())
                p.startsWith("/p/") -> peer(ex, p.substring(3))
                p == "/api/dl" -> {
                    if (!isLoopback(ex.remoteIp)) { ex.fail(403, "local use only"); return }
                    val v = vnorm(ex.q("path"))
                    sendFile(ex, Jobs.ep(ex.q("dev")).open(v), vbase(v), ex.query["dl"] != "1")
                }
                else -> ex.reply(404, "not found".toByteArray(), "text/plain")
            }
        } catch (e: NotFound) { ex.fail(404, errText(e))
        } catch (e: Denied) { ex.fail(403, errText(e))
        } catch (e: Full) { ex.fail(507, errText(e))
        } catch (e: Exists) { ex.fail(409, errText(e))
        } catch (e: Cancelled) { ex.fail(499, "cancelled")
        } catch (e: BadReq) { ex.fail(400, errText(e))
        } catch (e: JSONException) { ex.fail(400, errText(e))
        } catch (e: FileNotFoundException) { ex.fail(if ((e.message ?: "").contains("denied", true)) 403 else 404, errText(e))
        } catch (e: Exception) { ex.fail(500, errText(e))
        } catch (e: Throwable) { ex.fail(500, "internal error: " + errText(e)) }   // OutOfMemoryError, LinkageError ...
    }

    private fun isLoopback(ip: String) = ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1" || ip.startsWith("127.")

    private fun ok(ex: Exchange) = ex.json(JSONObject().put("ok", true))

    private fun spaceJson(s: Pair<Long, Long>?): JSONObject =
        JSONObject().put("free", s?.first ?: JSONObject.NULL).put("total", s?.second ?: JSONObject.NULL)

    // ---------------------------------------------------------------- what other devices call
    private fun peer(ex: Exchange, route: String) {
        val L: Endpoint = ArcEp(Core.local)
        when (route) {
            "ping" -> ok(ex)
            "ls" -> ex.json(jarr(L.ls(ex.q("path")).map { it.toJson() }))
            "stat" -> ex.json(L.stat(ex.q("path")))
            "space" -> ex.json(spaceJson(L.space(ex.q("path"))))
            "walk" -> ex.json(jarr(L.walk(ex.q("path")).map { it.toJson() }))
            "search" -> ex.json(L.search(ex.q("path"), ex.q("q")).toJson())
            "file" -> sendFile(ex, L.open(ex.q("path")), vbase(ex.q("path")), false)
            "put" -> {
                val n = ex.header("content-length")?.toLongOrNull() ?: throw BadReq("missing Content-Length")
                L.write(ex.q("path"), ex.body, n)
                ok(ex)
            }
            "mkdir" -> { L.mkdir(ex.q("path")); ok(ex) }
            "rm" -> { L.remove(ex.q("path")); ok(ex) }
            "rename" -> { L.rename(ex.q("path"), ex.q("name")); ok(ex) }
            "mv" -> { L.move(ex.q("path"), ex.q("to")); ok(ex) }
            else -> ex.fail(404, "unknown route")
        }
    }

    // ---------------------------------------------------------------- file download with Range support
    private fun sendFile(ex: Exchange, src: Source, name: String, inline: Boolean) {
        src.use {
            val size = src.size
            var start = 0L
            var end = size - 1
            var code = 200
            val rng = ex.header("range")
            if (rng != null && rng.startsWith("bytes=") && src.seekable && size > 0) {
                val spec = rng.substring(6).split(",")[0]
                val a = spec.substringBefore("-")
                val b = spec.substringAfter("-", "")
                try {
                    if (a.isEmpty()) start = maxOf(0L, size - b.toLong())
                    else { start = a.toLong(); end = minOf(if (b.isEmpty()) size - 1 else b.toLong(), size - 1) }
                    if (start in 0..end) code = 206 else { start = 0; end = size - 1 }
                } catch (_: NumberFormatException) { start = 0; end = size - 1 }
            }
            val length = maxOf(end - start + 1, 0L)
            val mt = mimeFor(name)
            val h = LinkedHashMap<String, String>()
            h["Content-Type"] = mt
            h["Content-Length"] = length.toString()
            h["Content-Disposition"] = (if (inline && safeInline(mt)) "inline" else "attachment") + "; filename*=UTF-8''" + MiniHttp.quote(name)
            if (!mt.startsWith("video/") && !mt.startsWith("audio/")) h["Content-Security-Policy"] = "sandbox"
            h["X-Content-Type-Options"] = "nosniff"
            h["Cache-Control"] = "no-store"
            if (src.seekable) h["Accept-Ranges"] = "bytes"
            if (code == 206) h["Content-Range"] = "bytes $start-$end/$size"
            val o = ex.begin(code, h)
            if (start > 0) src.seek(start)
            val buf = ByteArray(256 * 1024)
            var left = length
            while (left > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                o.write(buf, 0, n)
                left -= n
            }
            o.flush()
            if (left > 0) ex.keepAlive = false
        }
    }
}
