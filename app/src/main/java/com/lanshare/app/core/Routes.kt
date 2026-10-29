package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

// HTTP routing: the /p/ routes are what other devices call, the /api/ routes are what this device's own UI calls. Mirrors the Python H class.
object Routes {
    /** True when this request comes from the app's own screen: loopback AND it carries the per-run root token (cookie `lsroot`, header `x-ls-root` or query `rt`). Other apps / devices cannot know the token. */
    private fun rootTrusted(ex: Exchange): Boolean {
        val ip = ex.remoteIp
        if (!(ip == "::1" || ip.startsWith("127.") || ip.startsWith("0:0:0:0:0:0:0:1") || ip.contains("127.0.0.1"))) return false
        val t = RootShell.token
        if (ex.query["rt"] == t || ex.header("x-ls-root") == t) return true
        val c = ex.header("cookie") ?: return false
        return c.split(';').any { it.trim() == "lsroot=$t" }
    }

    fun handle(ex: Exchange) {
        val trusted = rootTrusted(ex)
        RootShell.untrusted.set(!trusted)   // Jobs.ep("root") reads this on the request thread
        try {
            val p = ex.path
            if (!p.startsWith("/p/")) ex.readBody()   // always consume UI request bodies so they never bleed into the next keep-alive request
            if (p.startsWith("/api/") && !trusted && ex.query["dev"] == "root") throw Denied("Root is only available from the app's own screen")   // also covers cached thumbnails
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
        } catch (e: Throwable) { ex.fail(500, "internal error: " + errText(e))   // OutOfMemoryError, LinkageError (a library missing on this Android version) ...
        } finally { RootShell.untrusted.remove() }
    }

    private fun ok(ex: Exchange) = ex.json(JSONObject().put("ok", true))

    private fun spaceJson(s: Pair<Long, Long>?): JSONObject =
        JSONObject().put("free", s?.first ?: JSONObject.NULL).put("total", s?.second ?: JSONObject.NULL)

    // ---------------------------------------------------------------- what other devices call
    private fun peer(ex: Exchange, route: String) {
        val L: Endpoint = ArcEp(Core.local)
        when (route) {
            "ping" -> ok(ex)
            "ls" -> { if (ex.query["fresh"] == "1") L.invalidate(ex.q("path")); ex.json(jarr((if (ex.query["counts"] == "0") L.lsFast(ex.q("path")) else L.ls(ex.q("path"))).map { it.toJson() })) }
            "stat" -> ex.json(L.stat(ex.q("path")))
            "space" -> ex.json(spaceJson(L.space(ex.q("path"))))
            "walk" -> ex.json(jarr(L.walk(ex.q("path")).map { it.toJson() }))
            // remote Storage Analyzer: another phone asks this one to scan itself (fast: native scan here), polls, then downloads the finished tree
            "sscan" -> ex.json(sscanStart(vnorm(ex.q("path")), ex.query["rescan"] == "1"))
            "sscanp" -> ex.json(sscanProgress(scanOf(ex)))
            "sscancancel" -> { StorageScan.cancel(ex.q("id")); ok(ex) }
            "sscandl" -> {
                val s = scanOf(ex)
                if (s.state != "done") throw BadReq("the scan is not finished")
                ex.reply(200, StorageStore.encode(s) ?: throw BadReq("no result"), "application/octet-stream")
            }
            "search" -> ex.json(L.search(ex.q("path"), ex.q("q")).toJson())
            "file" -> sendFile(ex, L.open(ex.q("path")), vbase(ex.q("path")), false)
            "put" -> {
                val n = ex.header("content-length")?.toLongOrNull() ?: throw BadReq("missing Content-Length")
                val tag = ex.query["tag"]
                if (tag != null) {   // resumable upload: the first `offset` bytes of `total` are already here (see "partial"); a broken upload keeps what arrived
                    val off = ex.query["offset"]?.toLongOrNull() ?: 0L
                    val total = ex.query["total"]?.toLongOrNull() ?: throw BadReq("missing total")
                    if (off < 0 || off > total || n != total - off) throw BadReq("Content-Length does not match total - offset")
                    L.writeResume(ex.q("path"), ex.body, total, off, tag)
                } else L.write(ex.q("path"), ex.body, n)
                ok(ex)
            }
            "partial" -> ex.json(JSONObject().put("have", L.partial(ex.q("path"), ex.q("tag"))))   // how much of an interrupted upload of this file (same source version) is still here
            // "Other phones" gallery (HANDOVER_GALLERY.md part 1): another phone asks for this phone's albums and for small thumbnails made HERE
            "albums" -> ex.json(Albums.json(ex.query["kind"] == "vid"))
            "thumb" -> {
                val rf = Core.local.real(vnorm(ex.q("path")))
                val data = if (ex.query["big"] == "1") Thumbs.makeCover(rf) else Thumbs.make(rf)
                ex.reply(200, data, "image/jpeg", mapOf("Cache-Control" to "private, max-age=86400"))
            }
            "vthumb" -> {
                val (data, dur) = VideoThumbs.make(Core.local.open(vnorm(ex.q("path"))))
                ex.reply(200, data, "image/jpeg", mapOf("X-Duration" to dur.toString(), "Cache-Control" to "private, max-age=86400"))
            }
            // AI search on THIS phone for another phone (HANDOVER_GALLERY.md part 4): ?mode=status|visual|text&q=&whole=1&dir=  -> same JSON as POST /api/imgs
            "imgsearch" -> ex.json(when (ex.query["mode"]) {
                "visual" -> ImgSearch.visual((ex.query["q"] ?: "").trim().ifEmpty { throw BadReq("empty query") }, dir = ex.query["dir"] ?: "")
                "text" -> ImgSearch.text(ex.query["q"] ?: "", ex.query["whole"] != "0", ex.query["dir"] ?: "")
                else -> ImgSearch.status()
            })
            // Universal Search on THIS phone for another phone / the PC page (Part 9): same JSON as GET /api/usearch
            "usearch" -> ex.json(UsSearch.toJson(UsSearch.run(ex.query["q"] ?: "", ex.query["kind"] ?: "", UsSearch.optsOf(false, false, false, false, false),
                UsSearch.sortOf(ex.query["sort"], false), ex.query["offset"]?.toIntOrNull() ?: 0, ex.query["limit"]?.toIntOrNull() ?: 100,
                com.lanshare.app.core.usearch.CancelToken()) ?: throw BadReq("Universal search is off on that phone")))
            "wifiprinters" -> ex.json(WifiPrinters.listJson())   // this device's Wi-Fi printers, for printing on them from another device
            "ipp" -> {   // forwards one IPP request from another device to a printer this device discovered (not to arbitrary hosts)
                val n = ex.header("content-length")?.toLongOrNull() ?: throw BadReq("missing Content-Length")
                val p = WifiPrinters.known(ex.q("id")) ?: throw Denied("not a printer of this device")
                ex.reply(200, Ipp.forward(p, ex.body, n), "application/ipp")
            }
            "clip" -> {   // text / link pushed from another device's "LANShare Send"
                val n = ex.header("content-length")?.toLongOrNull() ?: throw BadReq("missing Content-Length")
                if (n > (4 shl 20)) throw BadReq("text too large")
                val buf = ByteArray(n.toInt()); var o = 0
                while (o < buf.size) { val r = ex.body.read(buf, o, buf.size - o); if (r < 0) throw BadReq("short body"); o += r }
                val j = JSONObject(String(buf, Charsets.UTF_8))
                val t = j.optString("text")
                if (t.isBlank()) throw BadReq("empty text")
                com.lanshare.app.ClipPush.received(t, j.optString("from").ifBlank { ex.remoteIp }, j.optBoolean("quiet"))
                ok(ex)
            }
            "mkdir" -> { L.mkdir(ex.q("path")); ok(ex) }
            "rm" -> { Jobs.removeUser(L, vnorm(ex.q("path"))); ok(ex) }   // same as a local delete: into the recycle bin where possible
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
                    .put("seen", p.seen / 1000.0).put("via", viaOf(p.ip)).put("kind", "LANShare").put("bat", p.bat).put("chg", p.chg)
                    .put("vias", JSONArray((listOf(p.ip) + p.ips).map { viaOf(it) }.distinct())))
                for (s in Smb.peers()) { val v = viaOf(Smb.split(s.optString("ip")).first); a.put(s.put("via", v).put("kind", "SMB").put("vias", JSONArray(listOf(v)))) }
                for (s in Ftp.peers()) { val v = viaOf(Ftp.split(s.optString("ip")).first); a.put(s.put("via", v).put("kind", "FTP").put("vias", JSONArray(listOf(v)))) }
                for (s in Gdrive.peers()) a.put(s.put("via", "Google Drive").put("kind", "Drive").put("vias", JSONArray(listOf("Google Drive"))))
                for (s in Otg.peers()) a.put(s)   // USB / OTG drives (Settings > USB / OTG drives)
                if (Cfg.rootEnabled() && RootShell.granted())   // Settings > Root access is on: the whole file system shows up as one more place
                    a.put(JSONObject().put("id", "root").put("name", "Root").put("ip", "").put("ok", true).put("seen", System.currentTimeMillis() / 1000.0).put("via", "su").put("kind", "Root").put("vias", JSONArray(listOf("su"))))
                return ex.json(a)
            }
            "diag" -> return ex.json(JSONObject().put("me", Cfg.name).put("id", Cfg.id).put("port", d.port)
                .put("ifaces", JSONArray().also { a -> d.ifaces.forEach { a.put(JSONArray().put(it.ip).put(it.net.toString())) } })
                .put("peers", jarr(d.list().map { it.toJson() })))
            "scan" -> { d.scanNow(); return ok(ex) }
            "counts" -> {   // item counts of the sub-folders of this phone's folder, filled in after the list is on screen
                val o = JSONObject()
                if (ex.q("dev") == "local" && arcSplit(vnorm(q["path"] ?: "/")) == null) Core.local.counts(vnorm(q["path"] ?: "/")).forEach { (k, n) -> o.put(k, n) }
                return ex.json(o)
            }
            "folderstat" -> {   // {files, bytes, partial} of one folder of this phone, recursive (start menu tiles)
                val o = JSONObject()
                val sp = vnorm(q["path"] ?: "/")
                val mk = q["media"]   // "img" / "vid": every picture / video of the phone (MediaStore), not one folder
                if (mk == "img" || mk == "vid") {
                    val (n, b) = try { Albums.total(mk == "vid") } catch (_: Exception) { -1L to 0L }
                    if (n >= 0) o.put("files", n).put("bytes", b).put("partial", false)
                } else if (ex.q("dev") == "local" && arcSplit(sp) == null) {
                    val (n, b, part) = try { Core.local.stat(sp, 8) } catch (_: Exception) { Triple(-1L, 0L, false) }
                    if (n >= 0) o.put("files", n).put("bytes", b).put("partial", part)
                }
                return ex.json(o)
            }
            "ls" -> {
                val dev = ex.q("dev")
                val path = vnorm(q["path"] ?: "/")
                if (q["fresh"] == "1" && dev != "local") try { Jobs.ep(dev).invalidate(path) } catch (_: Exception) {}   // pull-to-refresh on FTP / SFTP: drop the cached listing first
                val items = (if (dev == "local" && arcSplit(path) == null) Core.local.ls(path, false) else Jobs.ep(dev).ls(path))   // paths inside a .zip/.rar must go through ArcEp
                    .map { it to it.name.lowercase() }   // lower-case each name once, not on every comparison (big folders)
                    .sortedWith(compareBy<Pair<Item, String>>({ !it.first.dir }, { it.second })).map { it.first }
                var used: Any = JSONObject.NULL   // share of main storage in use, for the "70% USED" pill
                if (dev == "local") try {
                    val r = Core.local.root
                    val total = r.totalSpace
                    if (total > 0) used = Math.round((total - r.usableSpace) * 100.0 / total).toInt()
                } catch (_: Exception) {}
                return ex.json(JSONObject().put("path", path).put("items", jarr(items.map { it.toJson() })).put("used", used))
            }
            "search" -> {
                if (q["content"] == "1") {   // Part 8: match the TEXT of Word / Excel / ODF / PowerPoint files instead of the name (this device's own files only)
                    val e = Jobs.ep(ex.q("dev")).let { (it as? ArcEp)?.base ?: it }
                    if (e !is LocalFs) throw BadReq("content search only for this device's own files")
                    if (ex.q("q").length < 2) throw BadReq("type at least 2 letters")
                    return ex.json(e.searchOfficeContent(vnorm(q["path"] ?: "/"), ex.q("q")).toJson())
                }
                return ex.json(Jobs.ep(ex.q("dev")).search(vnorm(q["path"] ?: "/"), ex.q("q")).toJson())
            }
            "office" -> {   // Part 3 leftover: read-only HTML preview of an office file for the PC web UI
                val e = Jobs.ep(ex.q("dev")).let { (it as? ArcEp)?.base ?: it }
                if (e !is LocalFs || arcSplit(ex.q("path")) != null) throw BadReq("office preview only for this device's own files")
                val rf = e.real(vnorm(ex.q("path")))
                if (!rf.isFile || !com.lanshare.app.core.office.OfficeExport.isOffice(rf.name)) throw NotFound("Not an office file")
                if (rf.length() > (60L shl 20)) throw BadReq("file too large to preview (over 60 MB)")
                val link = "/api/office?dev=" + java.net.URLEncoder.encode(ex.q("dev"), "UTF-8") + "&path=" + java.net.URLEncoder.encode(ex.q("path"), "UTF-8") + "&sheet="
                val html = OfficeWeb.page(rf, (q["sheet"] ?: "0").toIntOrNull() ?: 0, link)
                return ex.reply(200, html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8", mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "sandbox; default-src 'none'; img-src data:; style-src 'unsafe-inline'"))
            }
            "bin" -> if (ex.method == "GET") return ex.json(JSONObject().put("days", Bin.KEEP_DAYS).put("task", Bin.status() ?: JSONObject.NULL)
                .also { if (q["items"] != "0") it.put("items", jarr(Bin.list().map { e -> e.toJson() })) })   // POST falls through to the task starter below
            "albums" -> return ex.json(Albums.json(q["kind"] == "vid"))
            // "Other phones" gallery (HANDOVER_GALLERY.md part 1): ?dev=<phone id>[&kind=vid]  -> that phone's {albums:[...]}
            "ralbums" -> {
                if (ex.q("dev").startsWith("smb:")) {   // a PC (SMB share): its Pictures folder + sub-folders as albums, pictures only
                    if (q["kind"] == "vid") return ex.json(JSONObject().put("albums", JSONArray()).put("video", true))
                    return ex.json(PcPictures.albums(ex.q("dev"), q["fresh"] == "1"))
                }
                val e = remotePhone(ex.q("dev"))
                val body = try { e.rpc("albums", mapOf("kind" to (if (q["kind"] == "vid") "vid" else "img"))) }
                    catch (x: NotFound) { throw NotFound("Update LANShare on ${e.name}: its version cannot share its gallery yet") }
                return ex.reply(200, body, "application/json")
            }
            // Universal Search on ANOTHER phone (Part 9): ?dev=&q=&kind=&sort=&limit=&offset=
            "rusearch" -> {
                val e = remotePhone(ex.q("dev"))
                val ps = mutableMapOf<String, String>()
                for (k in listOf("q", "kind", "sort", "limit", "offset")) q[k]?.let { ps[k] = it }
                val body = try { e.rpc("usearch", ps, timeoutMs = 60_000) }
                    catch (x: NotFound) { throw NotFound("Update LANShare on ${e.name}: its version cannot search yet") }
                return ex.reply(200, body, "application/json")
            }
            // AI search on another phone (HANDOVER_GALLERY.md part 4): ?dev=&mode=status|visual|text&q=&whole=0|1&dir=<album path>
            "rimgs" -> {
                val e = remotePhone(ex.q("dev"))
                val ps = mutableMapOf("mode" to (q["mode"] ?: "status"))
                for (k in listOf("q", "whole", "dir")) q[k]?.let { ps[k] = it }
                val body = try { e.rpc("imgsearch", ps, timeoutMs = 90_000) }
                    catch (x: NotFound) { throw NotFound("Update LANShare on ${e.name}: its version cannot search its pictures yet") }
                return ex.reply(200, body, "application/json")
            }
            // thumbnail of a picture / video on another phone: ?dev=&path=[&big=1][&video=1]. Made on THAT phone (small download);
            // an older build without the route falls back to the old way (whole picture pulled, then shrunk, cached)
            "rthumb" -> {
                val dev = ex.q("dev"); val path = vnorm(ex.q("path")); val vid = q["video"] == "1"
                val e = try { remotePhone(dev) } catch (_: BadReq) { null }
                if (e != null) {
                    try {
                        val r = e.callRaw("GET", if (vid) "vthumb" else "thumb", mapOf("path" to path, "big" to (if (q["big"] == "1") "1" else "0")), timeoutMs = 60_000)
                        try {
                            val data = r.readUpTo(8 shl 20)
                            val h = mutableMapOf("Cache-Control" to "private, max-age=86400")
                            r.headers["x-duration"]?.let { h["X-Duration"] = it }
                            return ex.reply(200, data, "image/jpeg", h)
                        } finally { r.close() }
                    } catch (_: NotFound) { /* older build: no such route */ }
                }
                val st = Jobs.ep(dev).stat(path)
                val (data, dur) = RemoteThumbs.make(dev, path, st.optLong("size"), st.optLong("mtime"), vid)
                return ex.reply(200, data, "image/jpeg", mapOf("X-Duration" to dur.toString(), "Cache-Control" to "private, max-age=86400"))
            }
            "pcpicdir" -> if (ex.method == "GET") {   // PC gallery: the Pictures folder in use (by hand or found) for ?dev=smb:...
                val dev = ex.q("dev"); val c = Smb.cfg(dev) ?: throw BadReq("not a PC share")
                return ex.json(JSONObject().put("picdir", c.optString("picdir", "")).put("auto", c.optString("picdir", "").isEmpty()))
            }
            "cleanup" -> if (ex.method == "GET") return ex.json(Cleanup.status())   // Free up space scan (POST starts / cancels it)
            "ps" -> if (ex.method == "GET") return ex.json(PrintSpace.json())   // Print space list (HANDOVER 9.7)
            "stat" -> return ex.json(Jobs.ep(ex.q("dev")).stat(vnorm(q["path"] ?: "/")))
            "gdlink" -> return ex.json(JSONObject().put("url", (((Jobs.ep(ex.q("dev")) as? ArcEp)?.base as? GdriveFs) ?: throw BadReq("not a Google Drive place")).link(vnorm(q["path"] ?: "/"))))   // Part 8: "Open in Google Drive"
            "space" -> return ex.json(spaceJson(Jobs.ep(ex.q("dev")).space(vnorm(q["path"] ?: "/"))))
            "printer" -> return ex.json(Jobs.printerStatus(ex.q("dev"), q["printer"]))
            "pcqueue" -> return ex.json(Jobs.pcQueue(ex.q("dev")))   // print queue screen: current job, waiting jobs, recent jobs of that PC
            "pcscreen" -> return ex.json(Jobs.pcScreen(ex.q("dev"), (q["pos"] ?: "-1").toLongOrNull() ?: -1L))   // PC window mirror: status line + live log
            "pcfind" -> return ex.json(JSONArray().also { a -> com.lanshare.app.pe.PePc.find().forEach { a.put(JSONObject().put("ip", it.ip).put("name", it.name).put("port", it.port)) } })   // Phone Explorer: PCs running phone_explorer.py
            "pcphone" -> {   // Phone Explorer: that PC opens this phone's storage in Explorer
                com.lanshare.app.pe.PePc.open(ex.q("ip"), (q["hport"] ?: "8798").toIntOrNull() ?: 8798, (q["port"] ?: "2121").toIntOrNull() ?: 2121, q["user"] ?: "phone", q["pin"] ?: "", q["name"] ?: "Phone")
                return ex.json(JSONObject().put("ok", true))
            }
            "pccancel" -> return ex.json(Jobs.pcCancel(ex.q("dev"), (q["id"] ?: "").toIntOrNull() ?: throw BadReq("id missing")))
            "wifiprinters" -> {
                val rmBox = arrayOfNulls<org.json.JSONArray>(1)
                val rt = Thread { rmBox[0] = try { WifiPrinters.remoteJson() } catch (_: Throwable) { null } }.also { it.isDaemon = true; it.start() }   // the other devices' printers are asked in parallel with the local search
                val a = if (q["scan"] == "1") WifiPrinters.searchNow() else WifiPrinters.listJson(true)
                try { rt.join(4000) } catch (_: InterruptedException) {}
                rmBox[0]?.let { rm -> for (k in 0 until rm.length()) a.put(rm.get(k)) }
                return ex.json(a)
            }   // printers on this Wi-Fi (mDNS) + the other devices' printers, printed to over IPP
            "pvinfo" -> return ex.json(PrintPreview.info(ex.q("dev"), vnorm(ex.q("path")), ex.q("to")))
            "pvpage" -> return ex.reply(200, PrintPreview.page(ex.q("id"), ex.q("n").toIntOrNull() ?: 0, ex.q("w").toIntOrNull() ?: 700), "image/jpeg",
                mapOf("Cache-Control" to "private, max-age=600"))
            "pssess" -> if (ex.method == "GET") return ex.json(PrintSessions.list())   // Print space Recent prints: kept snapshots (HANDOVER 9.7, part 13)
            "pssprev" -> return ex.reply(200, PrintSessions.previewFile(ex.q("sid"), ex.q("n").toIntOrNull() ?: 0).readBytes(), "image/jpeg",
                mapOf("Cache-Control" to "private, max-age=3600"))   // stored preview page of a kept print
            "pspage" -> return ex.reply(200, PrintSpaceRender.page(ex.q("id"), ex.q("n").toIntOrNull() ?: 0, ex.q("w").toIntOrNull() ?: 700), "image/jpeg",
                mapOf("Cache-Control" to "private, max-age=600"))   // Print space preview page (the id is a hash of the layout, so a cached page is never stale)
            "pvfile" -> {
                val (f, e) = PrintPreview.file(ex.q("id"))
                val mt = when (e) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "tif", "tiff" -> "image/tiff"; else -> "text/plain; charset=utf-8" }
                return ex.reply(200, f.readBytes(), mt, mapOf("Cache-Control" to "private, max-age=600"))
            }
            "dl" -> {
                val p = vnorm(ex.q("path"))
                return sendFile(ex, Jobs.ep(ex.q("dev")).open(p), vbase(p), q["dl"] != "1")
            }
            "thumb" -> {
                val e = Jobs.ep(ex.q("dev")).let { (it as? ArcEp)?.base ?: it }
                if (e !is LocalFs || arcSplit(ex.q("path")) != null) throw BadReq("thumbnails only for this device's own files")
                val rf = e.real(vnorm(ex.q("path")))
                if (com.lanshare.app.core.office.OfficeExport.isOffice(rf.name)) {   // office files: the preview image embedded by the producing app, else 404 (ui falls back to the icon)
                    val eb = com.lanshare.app.core.office.OfficeThumb.embedded(rf) ?: throw NotFound("No embedded thumbnail")
                    return ex.reply(200, eb, com.lanshare.app.core.office.OfficeThumb.mime(eb), mapOf("Cache-Control" to "private, max-age=86400"))
                }
                val data = if (q["big"] == "1") Thumbs.makeCover(rf) else Thumbs.make(rf)   // big=1: album cover (sharper, 1000 px)
                return ex.reply(200, data, "image/jpeg", mapOf("Cache-Control" to "private, max-age=86400"))
            }
            "vthumb" -> {
                val p = vnorm(ex.q("path"))
                val (data, dur) = VideoThumbs.make(Jobs.ep(ex.q("dev")).open(p))
                return ex.reply(200, data, "image/jpeg", mapOf("X-Duration" to dur.toString(), "Cache-Control" to "private, max-age=86400"))
            }
            "arcinfo" -> return ex.json(ArcInfo.json(Jobs.ep(ex.q("dev")), ex.q("path")))   // archive details: strip + sheet in the web UI (ZIP: central directory only)
            "arcjob" -> return ex.json(ArcProg.current())   // what a slow folder listing is busy with (fetching an archive ...): {} when nothing
            "jobcancel" -> {
                (Jobs.all[ex.q("id")] ?: throw NotFound("unknown job")).cancel = true
                return ok(ex)
            }
            "ftpcache" -> {   // part 5: GET stats; ?clear=1 forgets every saved listing; ?ttl=<seconds 0-3600> sets how long a listing counts as fresh
                if (q["clear"] == "1") FtpCache.clearAll()
                q["ttl"]?.toIntOrNull()?.let { FtpCache.setTtlSec(it) }
                return ex.json(FtpCache.statsJson())
            }
            "warm" -> { Jobs.ep(ex.q("dev")).warmUp(); return ok(ex) }   // a place was opened (FTP part 3: connection warm-up); never fails the page
            "resumable" -> return ex.json(Jobs.resumable())   // interrupted copies / moves that can be continued (survive an app restart)
            "jobresume" -> return ex.json(JSONObject().put("job", Jobs.resume(ex.q("id"))))   // continue one of them: a new job id comes back
            "resumedrop" -> { Partials.dropJob(ex.q("id")); return ok(ex) }   // give up on one: forget the record (partial files are purged after 7 days)
            "job" -> return ex.json((Jobs.all[ex.q("id")] ?: throw NotFound("unknown job")).toJson(ex.query["since"]?.toIntOrNull() ?: -1))
            "jobs" -> {   // every running job (copy / move / send / extract / zip / delete / print / scans) + ones finished in the last 10 s: feeds the floating progress bubble
                val now = System.currentTimeMillis()
                val arr = org.json.JSONArray()
                for ((k, j) in Jobs.all.entries.toList().map { it.key to it.value }) {
                    if (j.state != "run" && (j.end <= 0L || now - j.end > 10_000L)) continue
                    arr.put(j.toJson().put("id", k))
                }
                return ex.json(arr)
            }
            "jobanswer" -> {   // the user answered "replace this file?" of a running copy
                (Jobs.all[ex.q("id")] ?: throw NotFound("unknown job")).give(ex.q("q").toIntOrNull() ?: -1, ex.q("ans"))
                return ok(ex)
            }
            "clip" -> if (ex.method == "GET") return ex.json(Clip.toJson())
            "imgs" -> if (ex.method == "GET") return ex.json(ImgSearch.status())   // image search: index progress (POST = start / cancel / clear / visual / text)
            "uindex" -> if (ex.method == "GET") return ex.json(IndexManager.status())   // Universal Search name index: state / progress / settings (POST = rescan / pause / settings)
            // Universal Search names: ?q=&kind=&sort=name|path|size|date|best&rev=1&ff=1&limit=&offset=&case=1&word=1&pathm=1&diac=1&re=1
            "usearch" -> if (ex.method == "GET") {
                val o = UsSearch.run(q["q"] ?: "", q["kind"] ?: "",
                    UsSearch.optsOf(q["case"] == "1", q["word"] == "1", q["pathm"] == "1", q["diac"] == "1", q["re"] == "1"),
                    UsSearch.sortOf(q["sort"], q["rev"] == "1", q["ff"] == "1"),
                    q["offset"]?.toIntOrNull() ?: 0, q["limit"]?.toIntOrNull() ?: 100, com.lanshare.app.core.usearch.CancelToken())
                    ?: throw BadReq("Universal search is off")
                return ex.json(UsSearch.toJson(o))
            }
            "smb" -> if (ex.method == "GET") return ex.json(Smb.status())
            "ftp" -> if (ex.method == "GET") return ex.json(Ftp.status())
            "gdrive" -> if (ex.method == "GET") return ex.json(Gdrive.status())   // POST = remove an account (below); sign-in itself runs in MainActivity (needs an Activity)
            "root" -> if (ex.method == "GET") return ex.json(rootStatus())   // POST = switch on / off (below)
            "otg" -> if (ex.method == "GET") return ex.json(Otg.status())   // POST = change one switch (below)
            "smbscan" -> return ex.json(d.smbScan())
            // ---- Storage Analyzer (scan engine: StorageScan.kt). path "/" = the whole main storage, anything else = that folder, recursive.
            "sscan" -> return ex.json(sscanStart(vnorm(q["path"] ?: "/"), q["rescan"] == "1"))   // start (or return the running / newest finished scan of that folder)
            "sscanp" -> return ex.json(sscanProgress(scanOf(ex)))
            "sscannode" -> return ex.json(sscanNode(scanOf(ex), vnorm(q["path"] ?: "/"), (q["n"]?.toIntOrNull() ?: 60).coerceIn(1, 500)))
            "sscantop" -> return ex.json(sscanTop(scanOf(ex)))
            "sscancancel" -> { StorageScan.cancel(ex.q("id")); return ok(ex) }
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
                    if (cut) "Moving" else "Copying", b.optString("conflict", "rename"))))
            }
            "send" -> ex.json(JSONObject().put("job", Jobs.start(b.getString("dev"), b.getJSONArray("paths").strings(),
                b.getString("to"), INBOX, false, "Sending")))
            "sendclip" -> {   // clipboard text / link -> another LANShare device (shown there in a box, links open in its browser)
                val t = b.getString("text")
                if (t.isBlank()) throw BadReq("clipboard is empty")
                val peer = Core.disc.get(b.getString("to")) ?: throw IOException("that device is offline")
                try { RemoteFs(peer).pushClip(t, Cfg.name) }
                catch (e: NotFound) { throw IOException("${peer.name} runs an older LANShare - update it to receive the clipboard") }
                Notes.add(true, peer.name, t)   // also listed in the text inbox
                ok(ex)
            }
            "bin" -> {   // starts a background task (poll GET /api/bin?items=0 for progress); "cancel" stops it after the current item
                if (b.getString("op") == "cancel") { Bin.cancelTask(); ex.json(JSONObject().put("ok", true)) }
                else ex.json(JSONObject().put("task", Bin.start(b.getString("op"), b.optJSONArray("ids")?.strings() ?: emptyList()).toJson()))
            }
            "ps" -> ex.json(PrintSpace.post(b))   // Print space add / rm / clear / move / set (HANDOVER 9.7)
            "pslayout" -> ex.json(PsLayoutApi.post(b))   // Print space layout editor: fill / assign / swap / ... (HANDOVER 9.7, part 5)
            "psrender" -> ex.json(PrintSpaceRender.post(b))   // Print space sheets: prepare (preview id) / pdf (background job) (HANDOVER 9.7, part 6a)
            "pssess" -> ex.json(PrintSessions.post(b))   // Print space Recent prints: save / get / restore / pdf / mark / rm / clear / cfg (HANDOVER 9.7, part 13)
            "rm" -> ex.json(JSONObject().put("job", Jobs.startDelete(b.getString("dev"), b.getJSONArray("paths").strings())))
            "print" -> ex.json(JSONObject().put("job", Jobs.startPrint(b.getString("dev"), b.getJSONArray("paths").strings(), b.getString("to"), b.optJSONObject("opts"))))
            "smb" -> smbUpdate(ex, b)
            "pcpicdir" -> ex.json(PcPictures.setDir(b.optString("dev"), b.optString("path")))   // PC gallery: set / clear (empty path = find it again) the Pictures folder
            "ftp" -> ftpUpdate(ex, b)
            "gdrive" -> { Gdrive.remove(b.optString("remove")); ex.json(JSONObject().put("ok", true).put("list", Gdrive.status())) }
            "otg" -> { Otg.set(b.optString("key"), b.optBoolean("value")); ex.json(Otg.status()) }   // Settings > USB / OTG drives
            "root" -> {   // Settings > Root access: "enable" asks su (the root manager shows its grant prompt) and only then stores the switch; "disable" = off + forget
                if (RootShell.untrusted.get() == true) throw Denied("Root is only available from the app's own screen")
                when (b.optString("op")) {
                    "enable" -> { if (RootShell.check()) Cfg.setRootEnabled(true) else Cfg.setRootEnabled(false) }
                    "disable" -> { Cfg.setRootEnabled(false); RootShell.forget() }
                    "check" -> RootShell.check()
                    else -> throw BadReq("unknown op")
                }
                ex.json(rootStatus())
            }
            "uindex" -> ex.json(run {
                when (b.getString("op")) {
                    "rescan" -> IndexManager.rescan()
                    "pause" -> IndexManager.pause(b.optBoolean("on", true))
                    "on" -> IndexManager.setEnabled(b.optBoolean("on", true))
                    "charging" -> IndexManager.setCharging(b.optBoolean("on", false))
                    "included" -> IndexManager.setIncluded(b.optJSONArray("paths")?.strings() ?: emptyList())
                    "excluded" -> IndexManager.setExcluded(b.optJSONArray("paths")?.strings() ?: emptyList())
                    "content" -> IndexManager.setContent(b.optBoolean("on", true))
                    "contentlimits" -> IndexManager.setContentLimits(b.optInt("maxMb", 5), b.optInt("budgetMb", 300))
                    "contentexts" -> IndexManager.setContentExts(b.optString("exts", ""))
                    "contentrebuild" -> IndexManager.contentRebuild()
                    "images" -> { Cfg.setUsImg(b.optBoolean("on", true)); if (b.has("threshold")) Cfg.setUsAiThreshold(b.optDouble("threshold", 0.25).toFloat()) }
                    else -> throw BadReq("unknown op")
                }
                IndexManager.status()
            })
            "imgs" -> ex.json(when (b.getString("op")) {
                "install" -> { ClipEngine.install(); ImgSearch.status() }     // downloads the CLIP model files once (~150 MB), poll GET /api/imgs -> model
                "uninstall" -> { ClipEngine.uninstall(); ImgSearch.status() }
                "start" -> { ImgSearch.start(b.optBoolean("ocr", true), b.optBoolean("force", false), dir = b.optString("dir", "")); ImgSearch.status() }
                "cancel" -> { ImgSearch.cancel(); ImgSearch.status() }
                "auto" -> { if (b.has("charging")) ImgSearch.setAutoCharging(b.optBoolean("charging", false)); if (b.has("on")) ImgSearch.setAuto(b.optBoolean("on", true)); ImgSearch.status() }   // scan new pictures by itself (default on)
                "included" -> { ImgSearch.setIncluded(b.optJSONArray("paths")?.strings() ?: emptyList()); ImgSearch.status() }   // folders the picture scan is limited to (empty = all)
                "excluded" -> { ImgSearch.setExcluded(b.optJSONArray("paths")?.strings() ?: emptyList()); ImgSearch.status() }   // folders the picture scan skips
                "clear" -> { ImgSearch.clear(); ImgSearch.status() }
                "visual" -> ImgSearch.visual(b.getString("q").trim().ifEmpty { throw BadReq("empty query") }, dir = b.optString("dir", ""))
                "text" -> ImgSearch.text(b.getString("q"), b.optString("mode", "word") != "part", b.optString("dir", ""))
                "list" -> ImgSearch.listDir(b.optString("dir", ""))   // scanned pictures of an album, for picking examples in the Similar tab
                "similar" -> ImgSearch.similar(b.optJSONArray("paths").strings(), b.optString("dir", ""))   // few-shot: more pictures like the selected ones
                else -> throw BadReq("unknown op")
            })
            "cleanup" -> ex.json(when (b.optString("op")) {
                "cancel" -> { Cleanup.cancel(); Cleanup.status() }
                "clearcache" -> Cleanup.clearAppCache()   // LANShare's own caches (thumbnails, FTP listings, temp copies)
                else -> { Cleanup.start(); Cleanup.status() }
            })
            "merge" -> ex.json(JSONObject().put("job", AlbumMerge.start(b.getString("target"), b.getJSONArray("paths").strings(), b.optBoolean("skipSimilar", false))))   // gallery: merge the selected albums into "target"
            "dups" -> ex.json(JSONObject().put("job", DupFinder.start(b.getString("dev"), b.getJSONArray("paths").strings(), b.optBoolean("similar"))))
            "zip" -> ex.json(JSONObject().put("job", Jobs.startZip(b.getString("dev"), b.getJSONArray("paths").strings(),
                b.getString("dir"), b.getString("name"), b.optBoolean("replace", false), b.optJSONArray("exclude")?.strings() ?: emptyList())))
            "zipscan" -> ex.json(Jobs.zipScan(b.getString("dev"), b.getJSONArray("paths").strings()))   // {size, files, est, items[]}: totals for the ZIP dialog + what it offers to leave out
            "extract" -> {   // unpack archives (or parts of one) into a folder: a copy job out of "a.zip!"
                val dev = b.getString("dev")
                val ps = b.getJSONArray("paths").strings().map { vnorm(it) }.map { if (arcSplit(it) == null && isArcName(vbase(it))) "$it!" else it }
                ex.json(JSONObject().put("job", Jobs.start(dev, ps, dev, b.getString("dir"), false, "Extracting", b.optString("conflict", "rename"))))
            }
            "addzip" -> {   // add files / folders of this phone into an archive (dir = "/x/a.zip!/inner"): a copy job whose destination is the archive
                val ps = b.getJSONArray("paths").strings().map { vnorm(it) }
                if (ps.any { arcSplit(it) != null }) throw BadReq("choose files that are not inside an archive")
                ex.json(JSONObject().put("job", Jobs.start("local", ps, "local", b.getString("dir"), false, "Adding to archive", b.optString("conflict", "rename"))))
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
                        for (p in ps) try { Jobs.removeUser(e, vnorm(p)) } catch (x: Exception) { errs.add(x) }
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

    /** The LANShare phone behind ?dev= (not this phone, not SMB / FTP / root). */
    private fun remotePhone(dev: String): RemoteFs =
        (Jobs.ep(dev).let { (it as? ArcEp)?.base ?: it } as? RemoteFs) ?: throw BadReq("not another LANShare phone")

    private fun rootStatus(): JSONObject = JSONObject().put("enabled", Cfg.rootEnabled()).put("state", RootShell.stateName()).put("why", RootShell.why)

    private fun smbUpdate(ex: Exchange, b: JSONObject) {
        val remove = b.optString("remove")
        val edit = b.optString("edit")
        if (remove.isNotEmpty()) Smb.remove(remove)
        else if (edit.isNotEmpty()) Smb.update(edit, b.optString("host"), b.optString("user"), b.optString("password"), b.optString("name"), b.optString("share"))
        else Smb.add(b.optString("host"), b.optString("user"), b.optString("password"), b.optString("name"))
        ex.json(JSONObject().put("ok", true).put("list", Smb.status()))
    }

    private fun ftpUpdate(ex: Exchange, b: JSONObject) {
        val remove = b.optString("remove")
        val edit = b.optString("edit")
        if (remove.isNotEmpty()) Ftp.remove(remove)
        else if (edit.isNotEmpty()) Ftp.update(edit, b.optString("host"), b.optString("user"), b.optString("password"), b.optString("name"), b.optBoolean("ftps"), b.optString("dir"), b.optString("mode", "passive"), b.optBoolean("natfix", true), b.optBoolean("verify", true), b.optString("timeout").toIntOrNull() ?: 60, b.optString("encoding"), b.optBoolean("hidden"), b.optBoolean("sftp"))
        else Ftp.add(b.optString("host"), b.optString("user"), b.optString("password"), b.optString("name"), b.optBoolean("ftps"), b.optString("dir"), b.optString("mode", "passive"), b.optBoolean("natfix", true), b.optBoolean("verify", true), b.optString("timeout").toIntOrNull() ?: 60, b.optString("encoding"), b.optBoolean("hidden"), b.optBoolean("sftp"))
        ex.json(JSONObject().put("ok", true).put("list", Ftp.status()))
    }

    // ---------------------------------------------------------------- Storage Analyzer API (part 2)
    private fun scanOf(ex: Exchange): Scan = StorageScan.get(ex.q("id")) ?: throw NotFound("unknown scan (it may have been dropped: scan again)")

    private fun longs(a: LongArray?): JSONArray { val r = JSONArray(); for (i in 0 until SCat.COUNT) r.put(a?.getOrNull(i) ?: 0L); return r }

    /** Starts a scan of this phone's folder [vpath] ("/" = whole main storage) unless one of it is running already; a finished one is reused unless [rescan]. */
    private fun sscanStart(vpath: String, rescan: Boolean): JSONObject {
        if (arcSplit(vpath) != null) throw BadReq("not inside an archive")
        val dir = Core.local.real(vpath)
        val whole = vpath == "/"
        var s = StorageScan.running(dir)
        if (s == null && !rescan) s = StorageScan.latest(dir)
        if (s == null) {
            var used = 0L
            if (whole) try { val r = Core.local.root; used = maxOf(0L, r.totalSpace - r.usableSpace) } catch (_: Exception) {}   // only feeds the percentage
            s = StorageScan.start(dir, if (whole) "" else vbase(vpath), used)
        }
        return JSONObject().put("id", s.id).put("state", s.state).put("path", vpath).put("whole", whole).put("error", s.error ?: JSONObject.NULL)
    }

    private fun sscanProgress(s: Scan): JSONObject {
        val o = JSONObject().put("id", s.id).put("state", s.state).put("percent", s.percent()).put("files", s.files.get()).put("dirs", s.dirs.get())
            .put("bytes", s.bytes.get()).put("used", s.usedBytes).put("cur", s.cur).put("error", s.error ?: JSONObject.NULL).put("ms", s.millis())
            .put("denied", s.denied.get()).put("keys", JSONArray(SCat.KEYS.toList())).put("catFiles", longs(s.catFiles()))
        if (s.state == "done") {
            o.put("catBytes", longs(s.catBytes()))
            s.root?.let { o.put("total", it.size).put("folders", it.folders) }
            o.put("deniedSample", JSONArray(s.deniedSample.toList()))
        }
        return o
    }

    /** One folder of a finished scan: its totals + its first [n] kids (already biggest first) + "rest" = the kids that were cut off. */
    private fun sscanNode(s: Scan, path: String, n: Int): JSONObject {
        if (s.state != "done") throw BadReq("scan not finished")
        val node = s.find(path) ?: throw NotFound("folder not in the scan (empty or unknown)")
        val o = JSONObject().put("id", s.id).put("path", node.path()).put("name", node.name.ifEmpty { s.label }).put("size", node.size)
            .put("files", node.files).put("folders", node.folders).put("denied", node.denied).put("cats", longs(node.cats))
        val kids = JSONArray()
        val m = minOf(n, node.kids.size)
        for (i in 0 until m) {
            val k = node.kids[i]
            val j = JSONObject().put("n", k.name).put("d", k.dir).put("s", k.size).put("f", k.files)
            if (k.dir) { j.put("fo", k.folders); j.put("cats", longs(k.cats)); if (k.denied) j.put("x", true) }   // x: could not be read
            else if (k.agg) j.put("a", true)   // "N small files" row
            else j.put("c", k.cat)
            kids.put(j)
        }
        o.put("kids", kids)
        if (m < node.kids.size) {
            var rs = 0L; var rf = 0L
            for (i in m until node.kids.size) { rs += node.kids[i].size; rf += node.kids[i].files }
            o.put("rest", JSONObject().put("count", node.kids.size - m).put("s", rs).put("f", rf))
        }
        return o
    }

    private fun sscanTop(s: Scan): JSONObject {
        if (s.state != "done") throw BadReq("scan not finished")
        val a = JSONArray()
        for (k in s.top) a.put(JSONObject().put("n", k.name).put("p", k.path()).put("s", k.size).put("c", k.cat))
        return JSONObject().put("id", s.id).put("top", a)
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
