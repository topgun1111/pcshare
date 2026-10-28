package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection

/**
 * Google Drive as a browsable place (HANDOVER chapter 17, Part 3 = listing side only).
 * Paths are virtual (D4): '/' = My Drive; path -> id comes from [GdCache], then a `name = '...'` query in the parent,
 * then a full listing of the parent. Duplicate names are shown as `name (2)`, Google-native files get .docx / .xlsx / .pptx (D5),
 * shortcuts are shown as their target, forms / sites etc. are hidden. Part 4 adds reading (open / walk / search) and the wiring
 * (Jobs.ep("gd:")). Writes (Parts 5-6) still throw a clear "not available yet" error.
 */
class GdriveFs(cfg: JSONObject) : Endpoint {
    override val id: String = cfg.getString("id")
    private val email: String = cfg.getString("email")
    override val name: String = cfg.optString("name").ifEmpty { email }
    private val api = GdApi(id, email)

    private class Row(var disp: String, val ent: GdCache.Ent, val size: Long, val mtime: Long, val link: Boolean, val created: Long = 0L) {
        val dir: Boolean get() = ent.dir
    }

    companion object {
        private const val LIST_FIELDS = "nextPageToken,files(" + GdApi.FILE_FIELDS + ",createdTime)"
        private val dirtyAt = java.util.concurrent.ConcurrentHashMap<String, Long>()   // Part 10: last write per account (ms)
        private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        const val SHARED = "[Shared with me]"   // Part 11: virtual folders at the top of the Drive place (ids start with "@")
        const val DRIVES = "[Shared drives]"
        const val TRASH = "[Trash]"
        private const val CHUNK = 8 * 1024 * 1024   // resumable upload chunk (multiple of 256 KiB), D7
    }

    // ------------------------------------------------------------------ metadata -> row
    /** One Drive file as a list row; null = not shown (trashed, hidden kind, broken shortcut). */
    private fun row(o: JSONObject, keepTrashed: Boolean = false): Row? {
        if (!keepTrashed && o.optBoolean("trashed")) return null
        var mime = o.optString("mimeType")
        var fid = o.optString("id")
        var nm = o.optString("name")
        var link = false
        if (mime == GdMime.SHORTCUT) {
            val sd = o.optJSONObject("shortcutDetails") ?: return null
            fid = sd.optString("targetId")
            mime = sd.optString("targetMimeType")
            if (fid.isEmpty() || mime.isEmpty() || mime == GdMime.SHORTCUT) return null
            link = true
        }
        if (fid.isEmpty() || GdMime.hidden(mime)) return null
        val dir = mime == GdMime.FOLDER
        val ex = GdMime.export(mime)
        if (ex != null && !nm.lowercase().endsWith(ex.first)) nm += ex.first
        nm = nm.replace('/', '_').replace('\\', '_')
        if (nm.isEmpty() || nm == "." || nm == "..") nm = "_$nm"
        val size = if (dir || ex != null) 0L else (o.optString("size").toLongOrNull() ?: 0L)
        return Row(nm, GdCache.Ent(fid, dir, mime, link), size, GdTime.parse(o.optString("modifiedTime")), link, GdTime.parse(o.optString("createdTime")))
    }

    /** Part 11: queries that must also see shared drives. */
    private fun lq(m: Map<String, String>) = m + mapOf("corpora" to "allDrives", "includeItemsFromAllDrives" to "true")

    private fun virtualRows() = listOf(SHARED to "@shared", DRIVES to "@drives", TRASH to "@trash")
        .map { Row(it.first, GdCache.Ent(it.second, true, GdMime.FOLDER), 0L, 0L, false, 0L) }

    /** Every visible entry of a folder, straight from Drive (no cache, no duplicate renaming). [folderId] "@shared" / "@drives" / "@trash" = the virtual views (Part 11). */
    private fun fetchRows(folderId: String): List<Row> {
        val out = ArrayList<Row>()
        if (folderId == "@drives") {
            api.pages("/drives", mapOf("fields" to "nextPageToken,drives(id,name,createdTime)", "pageSize" to "100"), "drives") { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val did = o.optString("id"); if (did.isEmpty()) continue
                    val nm = o.optString("name").replace('/', '_').replace('\\', '_').ifEmpty { "_" }
                    out.add(Row(nm, GdCache.Ent(did, true, GdMime.FOLDER), 0L, 0L, false, GdTime.parse(o.optString("createdTime"))))
                }
                true
            }
        } else {
            val trash = folderId == "@trash"
            val qq = when (folderId) {
                "@shared" -> "sharedWithMe = true and trashed = false"
                "@trash" -> "trashed = true and 'me' in owners"
                else -> "${GdApi.qs(folderId)} in parents and trashed = false"
            }
            val m = mapOf("q" to qq, "fields" to LIST_FIELDS, "pageSize" to "1000")   // no orderBy: Drive sorts big folders slowly; oldest-first is done here (duplicate names)
            api.pages("/files", if (trash) m else lq(m)) { arr ->
                for (i in 0 until arr.length()) row(arr.getJSONObject(i), trash)?.let { out.add(it) }
                true
            }
        }
        out.sortBy { it.created }   // stable: oldest keeps the plain name in dedup
        return out
    }

    /** Oldest keeps the name, the others become "name (2)", "name (3)" ... (never a name that exists in the folder). */
    private fun dedup(rows: List<Row>): List<Row> {
        val all = HashSet<String>(); for (r in rows) all.add(r.disp)
        val seen = HashSet<String>()
        for (r in rows) {
            if (seen.add(r.disp)) continue
            val (base, ext) = if (r.dir) r.disp to "" else splitExt(r.disp)
            var i = 2
            while ("$base ($i)$ext" in all || "$base ($i)$ext" in seen) i++
            r.disp = "$base ($i)$ext"
            seen.add(r.disp)
        }
        return rows
    }

    /** Lists folder [path] (id [folderId]) and refreshes the path -> id cache below it. */
    private fun listDir(path: String, folderId: String, virt: Boolean = false): List<Row> {
        val base = fetchRows(folderId)
        val rows = dedup(if (virt && folderId == "root") virtualRows() + base else base)   // the three views only in the folder LIST of My Drive, never in walk / search / totals
        GdCache.putChildren(id, path, rows.associate { it.disp to it.ent })
        return rows
    }

    // ------------------------------------------------------------------ path -> id
    private fun resolve(v: String): GdCache.Ent {
        val p = vnorm(v)
        GdCache.get(id, p)?.let { return it }
        val parent = resolve(vdir(p))
        if (!parent.dir) throw NotFound("No such file or directory")
        val nm = vbase(p)
        if (parent.id.startsWith("@")) { listDir(vdir(p), parent.id); return GdCache.get(id, p) ?: throw NotFound("No such file or directory") }
        val r = api.json("GET", "/files", lq(mapOf("q" to "name = ${GdApi.qs(nm)} and ${GdApi.qs(parent.id)} in parents and trashed = false",
            "fields" to "files(${GdApi.FILE_FIELDS})", "pageSize" to "2")))
        val arr = r.optJSONArray("files")
        if (arr != null && arr.length() == 1) {
            val one = row(arr.getJSONObject(0))
            if (one != null && one.disp == nm) { GdCache.put(id, p, one.ent); return one.ent }
        }
        // 0 or 2+ matches, or the shown name differs from the real one (duplicate "(2)", native extension): read the whole parent
        listDir(vdir(p), parent.id, true)
        return GdCache.get(id, p) ?: throw NotFound("No such file or directory")
    }

    // ------------------------------------------------------------------ Endpoint: reading metadata
    /**
     * Part 10: listings live in [FtpCache] (memory + disk, the Drive account id is the key): fresh -> no call; old -> shown at once and
     * refreshed in the background (the page reloads through [Core.onFtpChanged] when Drive's answer differs); never seen -> asks Drive.
     */
    override fun ls(v: String): List<Item> {
        val p = vnorm(v)
        FtpCache.get(id, p)?.let { return it }
        FtpCache.getStale(id, p)?.let { old -> refreshLater(p, old); return old }
        return fresh(p)
    }

    private fun fresh(p: String): List<Item> {
        val t0 = System.currentTimeMillis()
        val e = resolve(p)
        if (!e.dir) throw IOException("Not a directory")
        val items = listDir(p, e.id, true).map { Item(it.disp, it.dir, it.size, it.mtime) }
            .sortedWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        if ((dirtyAt[id] ?: 0L) < t0) FtpCache.put(id, p, items)   // a write that finished while Drive answered may make this list old
        return items
    }

    private fun refreshLater(p: String, old: List<Item>) {
        if (!refreshing.add("$id|$p")) return
        Thread({
            try {
                val now = fresh(p)
                if (now != old) Core.onFtpChanged?.invoke(id, p)
            } catch (_: Throwable) {
            } finally { refreshing.remove("$id|$p") }
        }, "gd-refresh").also { it.isDaemon = true }.start()
    }

    /** A write changed [p]: forget it, everything below, and the list of its folder (memory + disk). */
    private fun forget(p: String) {
        dirtyAt[id] = System.currentTimeMillis()
        GdCache.drop(id, p)
        FtpCache.drop(id, p, true)
        if (vnorm(p) != "/") FtpCache.drop(id, vdir(vnorm(p)), false)
    }

    // ------------------------------------------------------------------ Part 8: previews and links
    /** Drive's own preview of a picture / video (`thumbnailLink`, 400 px) + the video length in ms (0 for pictures); null when Drive has none
     *  (the caller then downloads the file like for any other place). Never throws for a missing preview. */
    fun thumb(v: String): Pair<ByteArray, Long>? {
        val e = resolve(vnorm(v))
        if (e.dir) return null
        val o = api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "thumbnailLink,videoMediaMetadata(durationMillis)"))
        val link = o.optString("thumbnailLink", "")
        if (link.isEmpty()) return null
        val dur = o.optJSONObject("videoMediaMetadata")?.optString("durationMillis", "")?.toLongOrNull() ?: 0L
        return try {
            val c = api.stream(link.replace(Regex("=s\\d+$"), "") + "=s400")
            try {
                val data = c.inputStream.use { it.readBytes() }
                if (data.isEmpty() || data.size > (8 shl 20)) null else data to dur
            } finally { c.disconnect() }
        } catch (_: NotFound) { null } catch (_: Denied) { null } catch (_: java.io.FileNotFoundException) { null }
    }

    /** The https link of the file / folder in Google Drive (opens in the browser or the Drive app). */
    fun link(v: String): String {
        val n = vnorm(v)
        if (n == "/") return "https://drive.google.com/drive/my-drive"
        val e = resolve(n)
        when (e.id) {
            "@shared" -> return "https://drive.google.com/drive/shared-with-me"
            "@drives" -> return "https://drive.google.com/drive/shared-drives"
            "@trash" -> return "https://drive.google.com/drive/trash"
        }
        val o = api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "webViewLink"))
        return o.optString("webViewLink", "").ifEmpty { throw NotFound("Drive has no link for this item") }
    }

    override fun invalidate(v: String) { forget(v) }

    /** A Drive place was opened: get the token and list My Drive in the background so the first folder paints fast (result goes into [GdCache]). Never throws. */
    override fun warmUp() { try { if (FtpCache.get(id, "/") == null) fresh("/") } catch (_: Exception) {} }

    override fun names(v: String): MutableSet<String> =   // name-clash checks: always fresh from Drive
        try {
            val p = vnorm(v)
            val e = resolve(p)
            if (!e.dir) mutableSetOf() else listDir(p, e.id).map { it.disp }.toMutableSet()
        } catch (_: IOException) { mutableSetOf() }

    override fun stat(v: String): JSONObject {
        val n = vnorm(v)
        if (n == "/") return JSONObject().put("name", name).put("path", "/").put("dir", true).put("size", 0L).put("mtime", 0L)
        val e = resolve(n)
        if (e.id.startsWith("@")) return JSONObject().put("name", vbase(n)).put("path", n).put("dir", true).put("size", 0L).put("mtime", 0L)   // a view: no totals
        val me = row(api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to GdApi.FILE_FIELDS))) ?: throw NotFound("No such file or directory")
        val o = JSONObject().put("name", vbase(n)).put("path", n).put("dir", me.dir).put("size", me.size).put("mtime", me.mtime)
        if (me.dir) {   // totals, bounded like the SMB / SFTP ones (20 s / 300 000 entries); shortcuts are not followed (no loops)
            val tot = LongArray(3); var partial = false; var seen = 0
            val end = System.currentTimeMillis() + 20_000L
            val visited = HashSet<String>(); visited.add(e.id)
            val stack = ArrayDeque<String>(); stack.addLast(e.id)
            loop@ while (stack.isNotEmpty()) {
                val d = stack.removeLast()
                val kids = try { fetchRows(d) } catch (_: NotFound) { continue } catch (_: Denied) { continue }
                for (k in kids) {
                    if (k.link) continue
                    if (k.dir) { tot[1]++; if (visited.add(k.ent.id)) stack.addLast(k.ent.id) } else { tot[0]++; tot[2] += k.size }
                    if (++seen >= 300_000) { partial = true; break@loop }
                }
                if (System.currentTimeMillis() > end) { partial = true; break }
            }
            o.put("files", tot[0]).put("folders", tot[1]).put("total", tot[2]).put("partial", partial)
        }
        return o
    }

    /** (free, total) of the account's storage; null for unlimited accounts or when Google does not say. */
    override fun space(v: String): Pair<Long, Long>? = try {
        val q = api.json("GET", "/about", mapOf("fields" to "storageQuota")).optJSONObject("storageQuota")
        val limit = q?.optString("limit")?.toLongOrNull() ?: 0L
        val usage = q?.optString("usage")?.toLongOrNull() ?: 0L
        if (limit <= 0L) null else Pair((limit - usage).coerceAtLeast(0L), limit)
    } catch (_: IOException) { null }

    // ------------------------------------------------------------------ Part 4: reading
    override fun open(v: String): Source {
        val e = resolve(v)
        if (e.dir) throw IOException("Is a directory")
        val ex = GdMime.export(e.mime)
        if (ex != null) return exportToTemp(e.id, ex.second)   // Google-native: no bytes, exported into a temp file (D5)
        val size = api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "size")).optString("size").toLongOrNull() ?: 0L
        return GdSource(api, GdApi.url("/files/${GdApi.enc(e.id)}", mapOf("alt" to "media")), size)
    }

    private fun exportToTemp(fileId: String, mime: String): Source {
        val dir = File(Core.appCtx?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "gdrive").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
        val tmp = File.createTempFile("exp", ".tmp", dir)
        try {
            val c = api.stream(GdApi.url("/files/${GdApi.enc(fileId)}/export", mapOf("mimeType" to mime)))
            try { c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } } } finally { c.disconnect() }
            return GdTempSource(tmp)
        } catch (e: Throwable) { tmp.delete(); throw e }
    }

    override fun walk(v: String): List<WalkItem> { val res = ArrayList<WalkItem>(); walkEach(v) { res.add(it) }; return res }

    /** Folder tree below [v], parents before children. Shortcuts are handed over as skipped entries (never followed: no loops, and a move keeps its parent). */
    override fun walkEach(v: String, visit: (WalkItem) -> Unit) {
        val p = vnorm(v)
        val e = resolve(p)
        if (!e.dir) {
            val me = row(api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to GdApi.FILE_FIELDS))) ?: throw NotFound("No such file or directory")
            visit(WalkItem("", false, me.size))
            return
        }
        visit(WalkItem("", true, 0))
        val visited = HashSet<String>(); visited.add(e.id)
        val stack = ArrayDeque<Pair<String, String>>(); stack.addLast("" to e.id)
        while (stack.isNotEmpty()) {
            val (r0, fid) = stack.removeLast()
            val kids = try { listDir(if (r0.isEmpty()) p else vjoin(p, r0), fid) }   // fills the path -> id cache, so the copy's open() needs no extra lookup
                       catch (_: NotFound) { visit(WalkItem(if (r0.isEmpty()) "(folder)" else r0, false, 0, true)); continue }
                       catch (_: Denied) { visit(WalkItem(if (r0.isEmpty()) "(folder)" else r0, false, 0, true)); continue }
            for (k in kids) {
                val r = if (r0.isEmpty()) k.disp else r0 + "/" + k.disp
                when {
                    k.link -> visit(WalkItem(r, k.dir, 0, true))
                    k.dir -> if (visited.add(k.ent.id)) { visit(WalkItem(r, true, 0)); stack.addLast(r to k.ent.id) } else visit(WalkItem(r, true, 0, true))
                    else -> visit(WalkItem(r, false, k.size))
                }
            }
        }
    }

    /** Name contains [q] (any case) below [v]: bounded walk, 20 s / 300 hits, then partial = true. Drive's own name search only matches word starts, so it is not used. */
    override fun search(v: String, q: String): SearchResult {
        val ql = q.lowercase()
        val p = vnorm(v)
        val e = resolve(p)
        if (!e.dir) throw IOException("Not a directory")
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + 20_000L
        var partial = false
        val visited = HashSet<String>(); visited.add(e.id)
        val stack = ArrayDeque<Pair<String, String>>()
        if (p == "/") { stack.addLast("/$SHARED" to "@shared"); stack.addLast("/$DRIVES" to "@drives") }   // Part 11: searched after My Drive (the stack pops My Drive first); Trash is not searched
        stack.addLast(p to e.id)
        loop@ while (stack.isNotEmpty()) {
            val (d, fid) = stack.removeLast()
            val rows = try { listDir(d, fid) } catch (_: NotFound) { continue } catch (_: Denied) { continue }
            for (r in rows) {
                val path = vjoin(d, r.disp)
                if (r.dir && !r.link && visited.add(r.ent.id)) stack.addLast(path to r.ent.id)
                if (r.disp.lowercase().contains(ql)) {
                    out.add(Item(r.disp, r.dir, r.size, r.mtime, path = path))
                    if (out.size >= 300) { partial = true; break@loop }
                }
            }
            if (System.currentTimeMillis() > end) { partial = true; break }
        }
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    // ------------------------------------------------------------------ later parts
    override fun mkdir(v: String) { ensureDir(v) }

    /** Folder [path] and every missing parent below My Drive; an existing folder is returned as is (idempotent). */
    private fun ensureDir(path: String): GdCache.Ent {
        val p = vnorm(path)
        if (p == "/") return GdCache.Ent("root", true, GdMime.FOLDER)
        try {
            val e = resolve(p)
            if (!e.dir) throw IOException("File exists: ${vbase(p)} is not a folder")
            if (e.id.startsWith("@")) throw IOException("Google Drive: $p is a view; choose a real folder (My Drive or inside a shared drive)")
            return e
        } catch (_: NotFound) {}
        val parent = ensureDir(vdir(p))
        val o = api.json("POST", "/files", mapOf("fields" to "id"),
            JSONObject().put("name", vbase(p)).put("mimeType", GdMime.FOLDER).put("parents", JSONArray().put(parent.id)))
        val e = GdCache.Ent(o.getString("id"), true, GdMime.FOLDER)
        GdCache.put(id, p, e)
        dirtyAt[id] = System.currentTimeMillis(); FtpCache.drop(id, vdir(p), false)
        return e
    }

    private fun fatal(e: IOException) = e is NotFound || e is Denied || e is Full

    /** Existing plain file called [nm] in folder [parentId] (oldest first), or null. Folders / Google-native files of that name refuse the upload. */
    private fun existingFile(p: String, parentId: String, nm: String): String? {
        GdCache.get(id, p)?.let {
            if (it.dir) throw IOException("Is a directory")
            if (GdMime.export(it.mime) != null) throw IOException("Google Drive: a Google Docs file cannot be overwritten")
            return it.id
        }
        val r = api.json("GET", "/files", lq(mapOf("q" to "name = ${GdApi.qs(nm)} and ${GdApi.qs(parentId)} in parents and trashed = false",
            "fields" to "files(id,mimeType)", "orderBy" to "createdTime", "pageSize" to "10")))
        val arr = r.optJSONArray("files") ?: return null
        var sawDir = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val m = o.optString("mimeType")
            if (m == GdMime.FOLDER) { sawDir = true; continue }
            if (m == GdMime.SHORTCUT || GdMime.export(m) != null || GdMime.hidden(m)) continue
            return o.getString("id")
        }
        if (sawDir) throw IOException("Is a directory")
        return null
    }

    /** Opens a resumable upload session (new file, or [fileId] updated in place = new version, D7); returns the session URI. */
    private fun startSession(fileId: String?, parentId: String, nm: String, size: Long): String {
        val meta = JSONObject()
        if (fileId == null) meta.put("name", nm).put("parents", JSONArray().put(parentId))
        val q = mapOf("uploadType" to "resumable", "fields" to "id")
        val url = if (fileId == null) GdApi.url("/files", q, GdApi.UPLOAD) else GdApi.url("/files/${GdApi.enc(fileId)}", q, GdApi.UPLOAD)
        var last: IOException? = null
        for (i in 0 until 3) {
            try {
                val r = api.send(if (fileId == null) "POST" else "PATCH", url, meta.toString().toByteArray(Charsets.UTF_8),
                    "application/json; charset=UTF-8", mapOf("X-Upload-Content-Length" to size.toString()))
                return r.headers["location"] ?: throw IOException("Google Drive did not give an upload address")
            } catch (e: IOException) {
                if (fatal(e) || (e.message ?: "").startsWith("Google Drive")) throw e   // session start creates nothing, so other errors may be repeated
                last = e
                try { Thread.sleep(1000L * (i + 1)) } catch (_: InterruptedException) { throw Cancelled() }
            }
        }
        throw last ?: IOException("Google Drive upload could not start")
    }

    private fun readFully(input: InputStream, b: ByteArray, len: Int) {
        var n = 0
        while (n < len) { val k = input.read(b, n, len - n); if (k < 0) throw IOException("connection lost"); n += k }
    }

    /** Bytes Drive has confirmed after a 308 ("Range: bytes=0-N" -> N+1, no header -> nothing). */
    private fun received(r: GdApi.Resp): Long = r.headers["range"]?.substringAfterLast('-')?.trim()?.toLongOrNull()?.plus(1) ?: 0L

    /** Resumable upload (D7): 8 MiB chunks held in memory, a failed chunk is re-sent after asking the session how much arrived. Same name = updated in place. [cb] gets the bytes Drive confirmed. */
    // ------------------------------------------------------------------ Part 9: resume after an interruption / app restart
    // The upload session URI (valid about 7 days, like Partials) is kept in Partials under "gdsess|acc|path"; "gd|acc|path" holds the source tag.
    private fun pkey(p: String) = "gd|$id|$p"
    private fun skey(p: String) = "gdsess|$id|$p"
    private fun dropPartial(p: String) { Partials.forget(pkey(p)); Partials.forget(skey(p)) }

    override val resumable: Boolean get() = true

    /** Bytes Drive already has of the unfinished upload of [v] for the source version [tag] (asked from the saved session), 0 = start again. */
    override fun partial(v: String, tag: String): Long {
        val p = vnorm(v)
        try {
            if (!Partials.matches(pkey(p), tag)) return 0L
            val session = Partials.tagOf(skey(p)) ?: return 0L
            val r = api.send("PUT", session, ByteArray(0), null, mapOf("Content-Range" to "bytes */*"), setOf(308, 404, 410))
            if (r.code == 308) return received(r)
            dropPartial(p)   // finished already, expired or gone: a new copy starts from 0
        } catch (e: Cancelled) { throw e } catch (_: Exception) {}
        return 0L
    }

    override fun writeResume(v: String, input: InputStream, size: Long, offset: Long, tag: String, cb: ((Int) -> Unit)?) {
        upload(v, input, size, offset, tag, cb)
    }

    /** A cancelled copy: tell Drive to forget the session (best effort; it would expire by itself). */
    private fun killSession(session: String) {
        try { api.send("DELETE", session, null, null, emptyMap(), setOf(499, 404, 410)) } catch (_: Exception) {}
    }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) { upload(v, input, size, 0L, null, cb) }

    private fun upload(v: String, input: InputStream, size: Long, offset: Long, tag: String?, cb: ((Int) -> Unit)?) {
        if (size < 0) throw BadReq("unknown file size")
        val p = vnorm(v)
        if (p == "/") throw IOException("Is a directory")
        val parent = ensureDir(vdir(p))
        val nm = vbase(p)
        var target = existingFile(p, parent.id, nm)
        val saved = if (offset > 0 && tag != null && Partials.matches(pkey(p), tag)) Partials.tagOf(skey(p)) else null
        if (offset > 0 && saved == null) throw BadReq("resume offset lost")   // Jobs then starts this file again from 0
        val session = saved ?: try { startSession(target, parent.id, nm, size) }
            catch (e: NotFound) {   // stale cache: the file is gone, create it again
                if (target == null) throw e
                forget(p); target = null
                startSession(null, parent.id, nm, size)
            }
        if (tag != null && saved == null) { Partials.put(pkey(p), tag); Partials.put(skey(p), session) }
        val buf = ByteArray(minOf(size, CHUNK.toLong()).toInt())
        var off = offset; var bufStart = offset; var bufLen = 0; var fails = 0
        var res: JSONObject? = null
        try {
        while (res == null) {
            if (off >= bufStart + bufLen && off < size) {   // next chunk from the input
                bufStart = off; bufLen = minOf(buf.size.toLong(), size - off).toInt()
                readFully(input, buf, bufLen)
            }
            val end = bufStart + bufLen
            val body = if (off == bufStart && bufLen == buf.size) buf else buf.copyOfRange((off - bufStart).toInt(), bufLen)
            try {
                val r = try {
                    api.send("PUT", session, body, "application/octet-stream",
                        if (size == 0L) emptyMap() else mapOf("Content-Range" to "bytes $off-${end - 1}/$size"), setOf(308))
                } catch (e: IOException) {
                    if (e is NotFound) throw IOException("Google Drive: the upload session expired")
                    if (fatal(e) || (e.message ?: "").startsWith("Google Drive")) throw e
                    // network failure: ask the session how much arrived (a 308 with Range, or the finished file)
                    fails++
                    if (fails > 5) throw e
                    try { Thread.sleep(1000L * fails) } catch (_: InterruptedException) { throw Cancelled() }
                    api.send("PUT", session, ByteArray(0), null, mapOf("Content-Range" to "bytes */$size"), setOf(308))
                }
                if (r.code == 308) {
                    val got = received(r)
                    if (got < bufStart || got > end) throw IOException("Google Drive upload out of step (${got} of ${size})")
                    if (got > off) { val d = (got - off).toInt(); off = got; fails = 0; cb?.invoke(d) }
                    else {   // nothing confirmed: try again, but not for ever
                        if (++fails > 5) throw IOException("Google Drive upload does not advance")
                        try { Thread.sleep(1000L * fails) } catch (_: InterruptedException) { throw Cancelled() }
                    }
                } else {
                    val d = (size - off).toInt(); off = size
                    res = r.json()
                    if (d > 0) cb?.invoke(d)
                }
            } catch (e: NotFound) { throw IOException("Google Drive: the upload session expired")
            }
        }
        } catch (e: Cancelled) { killSession(session); dropPartial(p); throw e
        } catch (e: IOException) {
            if ((e.message ?: "").contains("session expired")) dropPartial(p)   // nothing to continue: the next try starts again
            throw e
        }
        dropPartial(p)
        val fid = res.optString("id").ifEmpty { target ?: throw IOException("Google Drive did not confirm the upload") }
        GdCache.put(id, p, GdCache.Ent(fid, false, "application/octet-stream"))
        dirtyAt[id] = System.currentTimeMillis(); FtpCache.drop(id, vdir(p), false)
    }

    // ------------------------------------------------------------------ Part 6: change things in place
    /** Entry of [v] that may be changed: not the root, and not a shortcut (its cached id is the TARGET's, so a change would hit the original). */
    private fun changeable(v: String): GdCache.Ent {
        val p = vnorm(v)
        if (p == "/") throw IOException("Google Drive: My Drive itself cannot be changed")
        val e = resolve(p)
        if (e.id.startsWith("@")) throw IOException("Google Drive: this is a view and cannot be changed")
        if (vdir(p) == "/$DRIVES") throw IOException("Google Drive: a shared drive itself cannot be renamed, moved or deleted here")
        if (e.link) throw IOException("Google Drive: shortcuts cannot be renamed, moved or deleted here")
        return e
    }

    /** The name Drive stores for the shown name [shown] (a Google-native file is listed with .docx / .xlsx / .pptx added). */
    private fun realName(e: GdCache.Ent, shown: String): String {
        val ex = GdMime.export(e.mime) ?: return shown
        return if (shown.lowercase().endsWith(ex.first)) shown.substring(0, shown.length - ex.first.length) else shown
    }

    /** Trash, not delete (D6): recoverable in Drive for 30 days. A folder goes with everything in it, so [progress] gets the one name. */
    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val p = vnorm(v)
        val e = changeable(p)
        if (p.startsWith("/$TRASH/")) throw IOException("Google Drive: items in the Trash view can be deleted for good only in drive.google.com")
        if (vdir(p) == "/$SHARED") throw IOException("Google Drive: this belongs to someone else - remove it in Drive")
        try {
            api.json("PATCH", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "id"), JSONObject().put("trashed", true))
        } catch (n: NotFound) { /* already gone */ }
        forget(p)
        progress?.invoke(vbase(p))
    }

    override fun rename(v: String, newName: String) {
        val p = vnorm(v)
        val e = changeable(p)
        if (p.startsWith("/$TRASH/")) throw IOException("Google Drive: restore the item first, then rename it")
        if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) throw BadReq("invalid name")
        if (newName == vbase(p)) return
        if (names(vdir(p)).contains(newName)) throw Exists("name already exists")
        api.json("PATCH", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "id"), JSONObject().put("name", realName(e, newName)))
        forget(p)
        forget(if (vdir(p) == "/") "/$newName" else "${vdir(p)}/$newName")
        GdCache.put(id, if (vdir(p) == "/") "/$newName" else "${vdir(p)}/$newName", e)
    }

    override fun move(v: String, toV: String) {
        val p = vnorm(v); val t = vnorm(toV)
        val e = changeable(p)
        if (t == p) return
        if (t.startsWith("$p/")) throw IOException("Cannot move a folder into itself")
        val oldParent = resolve(vdir(p))
        if (oldParent.id == "@trash") { restore(p, t, e); return }   // Part 11: moving out of the Trash view = restore
        if (oldParent.id.startsWith("@")) throw IOException("Google Drive: open the real folder of this item first, then move it")
        val newParent = ensureDir(vdir(t))
        val sameDir = vdir(p) == vdir(t)
        if (!sameDir || vbase(t) != vbase(p)) {
            if (names(vdir(t)).contains(vbase(t))) throw Exists("name already exists")
        }
        val q = HashMap<String, String>()
        q["fields"] = "id"
        if (!sameDir) { q["addParents"] = newParent.id; q["removeParents"] = oldParent.id }
        val body = JSONObject()
        if (vbase(t) != vbase(p)) body.put("name", realName(e, vbase(t)))
        api.json("PATCH", "/files/${GdApi.enc(e.id)}", q, body)
        forget(p)
        forget(t)
        GdCache.put(id, t, e)
    }

    /** Part 11: Trash view -> real folder: un-trash, put under the new parent, drop the old parents (a trashed item keeps its parents). */
    private fun restore(p: String, t: String, e: GdCache.Ent) {
        val newParent = ensureDir(vdir(t))
        if (names(vdir(t)).contains(vbase(t))) throw Exists("name already exists")
        val old = api.json("GET", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "parents")).optJSONArray("parents")
        val q = HashMap<String, String>(); q["fields"] = "id"; q["addParents"] = newParent.id
        if (old != null && old.length() > 0) q["removeParents"] = (0 until old.length()).joinToString(",") { old.getString(it) }
        val body = JSONObject().put("trashed", false)
        if (vbase(t) != vbase(p)) body.put("name", realName(e, vbase(t)))
        api.json("PATCH", "/files/${GdApi.enc(e.id)}", q, body)
        forget(p); forget(t)
        GdCache.put(id, t, e)
    }

    /** Same account: Drive copies the file itself (nothing travels through the phone). False = the caller copies normally (folders, native docs, shortcuts, an existing target). */
    override fun copyWithin(v: String, toV: String): Boolean {
        val p = vnorm(v); val t = vnorm(toV)
        val e = resolve(p)
        if (p == "/" || e.dir || e.link || GdMime.export(e.mime) != null) return false
        if (names(vdir(t)).contains(vbase(t))) return false
        val parent = ensureDir(vdir(t))
        val o = api.json("POST", "/files/${GdApi.enc(e.id)}/copy", mapOf("fields" to "id"),
            JSONObject().put("name", vbase(t)).put("parents", JSONArray().put(parent.id)))
        GdCache.put(id, t, GdCache.Ent(o.getString("id"), false, e.mime))
        dirtyAt[id] = System.currentTimeMillis(); FtpCache.drop(id, vdir(t), false)
        return true
    }

    override val setsMtime: Boolean get() = true
    override fun setMtime(v: String, sec: Long) {
        if (sec <= 0) return
        val p = vnorm(v)
        val e = resolve(p)
        if (p == "/" || e.link) return
        api.json("PATCH", "/files/${GdApi.enc(e.id)}", mapOf("fields" to "id"), JSONObject().put("modifiedTime", GdTime.format(sec)))
        dirtyAt[id] = System.currentTimeMillis(); FtpCache.drop(id, vdir(p), false)   // the shown date changed
    }
}

/** Seekable read of a Drive file (`alt=media`): re-opens with a Range header after a seek or a dropped connection (like RemoteSource). */
internal class GdSource(private val api: GdApi, private val url: String, override val size: Long) : Source() {
    private var pos = 0L
    private var c: HttpURLConnection? = null
    private var ins: InputStream? = null
    override val seekable: Boolean get() = true

    private fun drop() {
        try { ins?.close() } catch (_: Exception) {}
        try { c?.disconnect() } catch (_: Exception) {}
        ins = null; c = null
    }

    override fun seek(pos: Long) { drop(); this.pos = pos }

    private fun begin() {
        val k = api.stream(url, if (pos > 0) "bytes=$pos-" else null)
        c = k
        if (pos > 0 && k.responseCode != 206) { drop(); throw IOException("Google Drive did not continue the download") }
        ins = k.inputStream
    }

    override fun read(): Int {
        val b = ByteArray(1)
        while (true) { val n = read(b, 0, 1); if (n < 0) return -1; if (n == 1) return b[0].toInt() and 255 }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= size) return -1
        var last: Exception? = null
        for (attempt in 0 until 5) {
            try {
                if (ins == null) begin()
                val n = ins!!.read(b, off, len)
                if (n < 0) throw java.io.EOFException("download ended early")
                pos += n
                if (pos >= size) drop()
                return n
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Cancelled) { drop(); throw e
            } catch (e: Exception) {
                last = e
                drop()
                try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) { throw Cancelled() }
            }
        }
        throw IOException("connection lost (${last?.message})")
    }

    override fun close() { drop() }
}

/** Exported Google-native file (temp file, deleted on close). */
internal class GdTempSource(private val f: File) : Source() {
    private val raf = RandomAccessFile(f, "r")
    override val size: Long = raf.length()
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) { raf.seek(pos) }
    override fun read(): Int = raf.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
    override fun close() { try { raf.close() } catch (_: Exception) {}; f.delete() }
}
