package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

// HTTP routing: the /p/ routes are what other devices call, the /api/ routes are what this device's own UI calls. Mirrors the Python H class.
object Routes {
    fun handle(ex: Exchange) {
        try {
            val p = ex.path
            if (!p.startsWith("/p/")) ex.readBody()   // always consume UI request bodies so they never bleed into the next keep-alive request
            when {
                p == "/p/hello" -> ex.json(Core.disc.hello())
                p.startsWith("/p/") -> peer(ex, p.substring(3))
                p == "/" -> ex.reply(200, Core.page, "text/html; charset=utf-8")
                p.startsWith("/api/") -> api(ex, p.substring(5))
                else -> ex.reply(404, "not found".toByteArray(), "text/plain")
            }
        } catch (e: NotFound) { ex.fail(404, errText(e))
        } catch (e: Denied) { ex.fail(403, errText(e))
        } catch (e: Full) { ex.fail(507, errText(e))
        } catch (e: Exists) { ex.fail(409, errText(e))
        } catch (e: Cancelled) { ex.fail(499, "cancelled")   // the user pressed Cancel while an archive was being opened
        } catch (e: BadReq) { ex.fail(400, errText(e))
        } catch (e: JSONException) { ex.fail(400, errText(e))
        } catch (e: FileNotFoundException) { ex.fail(if ((e.message ?: "").contains("denied", true)) 403 else 404, errText(e))
        } catch (e: Exception) { ex.fail(500, errText(e))
        } catch (e: Throwable) { ex.fail(500, "internal error: " + errText(e)) }   // OutOfMemoryError, LinkageError (a library missing on this Android version) ...
    }

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

    // ---------------------------------------------------------------- what the local UI calls
    private fun api(ex: Exchange, route: String) {
        val q = ex.query
        val d = Core.disc
        when (route) {
            "info" -> return ex.json(JSONObject().put("id", Cfg.id).put("name", Cfg.name).put("ips", JSONArray(d.ownIps.sorted()))
                .put("port", d.port).put("root", Core.local.root.path).put("storage_ok", Core.storageOk ?: JSONObject.NULL))
            "peers" -> {
                val a = JSONArray()
                for (p in d.list()) a.put(JSONObject().put("id", p.id).put("name", p.name).put("ip", p.ip).put("ok", p.ok)
                    .put("seen", p.seen / 1000.0).put("via", viaOf(p.ip)))
                for (s in Smb.peers()) a.put(s.put("via", viaOf(Smb.split(s.optString("ip")).first)))
                return ex.json(a)
            }
            "diag" -> return ex.json(JSONObject().put("me", Cfg.name).put("id", Cfg.id).put("port", d.port)
                .put("ifaces", JSONArray().also { a -> d.ifaces.forEach { a.put(JSONArray().put(it.ip).put(it.net.toString())) } })
                .put("peers", jarr(d.list().map { it.toJson() })))
            "scan" -> { d.scanNow(); return ok(ex) }
            "ls" -> {
                val dev = ex.q("dev")
                val path = vnorm(q["path"] ?: "/")
                val items = Jobs.ep(dev).ls(path).sortedWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
                var used: Any = JSONObject.NULL   // share of main storage in use, for the "70% USED" pill
                if (dev == "local") try {
                    val r = Core.local.root
                    val total = r.totalSpace
                    if (total > 0) used = Math.round((total - r.usableSpace) * 100.0 / total).toInt()
                } catch (_: Exception) {}
                return ex.json(JSONObject().put("path", path).put("items", jarr(items.map { it.toJson() })).put("used", used))
            }
            "search" -> return ex.json(Jobs.ep(ex.q("dev")).search(vnorm(q["path"] ?: "/"), ex.q("q")).toJson())
            "stat" -> return ex.json(Jobs.ep(ex.q("dev")).stat(vnorm(q["path"] ?: "/")))
            "space" -> return ex.json(spaceJson(Jobs.ep(ex.q("dev")).space(vnorm(q["path"] ?: "/"))))
            "printer" -> return ex.json(Jobs.printerStatus(ex.q("dev"), q["printer"]))
            "dl" -> {
                val p = vnorm(ex.q("path"))
                return sendFile(ex, Jobs.ep(ex.q("dev")).open(p), vbase(p), q["dl"] != "1")
            }
            "thumb" -> {
                val e = Jobs.ep(ex.q("dev")).let { (it as? ArcEp)?.base ?: it }
                if (e !is LocalFs || arcSplit(ex.q("path")) != null) throw BadReq("thumbnails only for this device's own files")
                val data = Thumbs.make(e.real(vnorm(ex.q("path"))))
                return ex.reply(200, data, "image/jpeg", mapOf("Cache-Control" to "private, max-age=86400"))
            }
            "vthumb" -> {
                val p = vnorm(ex.q("path"))
                val (data, dur) = VideoThumbs.make(Jobs.ep(ex.q("dev")).open(p))
                return ex.reply(200, data, "image/jpeg", mapOf("X-Duration" to dur.toString(), "Cache-Control" to "private, max-age=86400"))
            }
            "arcjob" -> return ex.json(ArcProg.current())   // what a slow folder listing is busy with (fetching an archive ...): {} when nothing
            "jobcancel" -> {
                (Jobs.all[ex.q("id")] ?: throw NotFound("unknown job")).cancel = true
                return ok(ex)
            }
            "job" -> return ex.json((Jobs.all[ex.q("id")] ?: throw NotFound("unknown job")).toJson())
            "clip" -> if (ex.method == "GET") return ex.json(Clip.toJson())
            "smb" -> if (ex.method == "GET") return ex.json(Smb.status())
        }
        val b = ex.bodyJson()
        when (route) {
            "clip" -> {
                Clip.clear()
                val op = b.optString("op")
                val ps = b.optJSONArray("paths")
                if ((op == "copy" || op == "cut") && ps != null && ps.length() > 0)
                    Clip.set(op, b.getString("dev"), ps.strings().map { vnorm(it) })
                ex.json(Clip.toJson())
            }
            "paste" -> {
                if (Clip.isEmpty()) throw BadReq("clipboard is empty")
                val cut = Clip.op == "cut"
                ex.json(JSONObject().put("job", Jobs.start(Clip.dev!!, Clip.paths, b.getString("dev"), b.getString("dir"), cut,
                    if (cut) "Moving" else "Copying")))
            }
            "send" -> ex.json(JSONObject().put("job", Jobs.start(b.getString("dev"), b.getJSONArray("paths").strings(),
                b.getString("to"), INBOX, false, "Sending")))
            "rm" -> ex.json(JSONObject().put("job", Jobs.startDelete(b.getString("dev"), b.getJSONArray("paths").strings())))
            "print" -> ex.json(JSONObject().put("job", Jobs.startPrint(b.getString("dev"), b.getJSONArray("paths").strings(), b.getString("to"), b.optJSONObject("opts"))))
            "smb" -> smbUpdate(ex, b)
            "zip" -> ex.json(JSONObject().put("job", Jobs.startZip(b.getString("dev"), b.getJSONArray("paths").strings(),
                b.getString("dir"), b.getString("name"))))
            "extract" -> {   // unpack archives (or parts of one) into a folder: a copy job out of "a.zip!"
                val dev = b.getString("dev")
                val ps = b.getJSONArray("paths").strings().map { vnorm(it) }.map { if (arcSplit(it) == null && isArcName(vbase(it))) "$it!" else it }
                ex.json(JSONObject().put("job", Jobs.start(dev, ps, dev, b.getString("dir"), false, "Extracting")))
            }
            "addip" -> {
                if (!d.addIp(b.getString("ip").trim())) throw IOException("no LANShare device found at that address")
                ok(ex)
            }
            "name" -> {
                val n = b.getString("name").trim().take(40)
                if (n.isNotEmpty()) Cfg.name = n
                Cfg.nameCustom = true
                Cfg.save()
                d.announce()
                ex.json(JSONObject().put("name", Cfg.name))
            }
            "op" -> {
                val e = Jobs.ep(b.getString("dev"))
                when (b.getString("op")) {
                    "mkdir" -> e.mkdir(vnorm(b.getString("path")))
                    "rm" -> {   // delete everything that can be deleted; report what could not instead of stopping at the first problem
                        val ps = b.getJSONArray("paths").strings()
                        val errs = ArrayList<Exception>()
                        for (p in ps) try { e.remove(vnorm(p)) } catch (x: Exception) { errs.add(x) }
                        if (errs.size == 1 && ps.size == 1) throw errs[0]
                        if (errs.isNotEmpty()) throw IOException("${errs.size} of ${ps.size} items not deleted - " + errs.take(3).joinToString("; ") { errText(it) })
                    }
                    "rename" -> e.rename(vnorm(b.getString("path")), b.getString("name"))
                    else -> throw BadReq("unknown op")
                }
                ok(ex)
            }
            else -> ex.fail(404, "unknown api")
        }
    }

    private fun smbUpdate(ex: Exchange, b: JSONObject) {
        val cur = Cfg.smb()
        val list = ArrayList<JSONObject>()
        for (i in 0 until cur.length()) list.add(cur.getJSONObject(i))
        val remove = b.optString("remove")
        if (remove.isNotEmpty()) {
            list.removeAll { it.optString("id") == remove }
            Smb.state.remove(remove)
            SmbFs.forget(remove)
        } else {
            val host = b.optString("host").trim()
            if (host.isEmpty()) throw BadReq("enter the PC's address")
            val c = JSONObject().put("id", "smb:" + UUID.randomUUID().toString().replace("-", "").take(6))
                .put("host", host).put("share", "").put("user", b.optString("user").trim())
                .put("password", b.optString("password"))
                .put("name", b.optString("name").trim().take(40).ifEmpty { Smb.split(host).first })
            SmbFs.create(c).ls("/")   // lists the PC's drives: throws a readable error if the address or login is wrong
            list.add(c)
        }
        Cfg.setSmb(JSONArray(list.take(10)))
        Cfg.save()
        ex.json(JSONObject().put("ok", true).put("list", Smb.status()))
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
