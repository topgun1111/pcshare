package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Drive girdisi. [id] = girdinin kendisi (rename/çöp/taşı), [tid] = hedef (kısayolsa hedef; listeleme/açma), [real] = Drive'daki gerçek ad, [name] = gösterilen ad. */
data class GF(val id: String, val tid: String, val real: String, val name: String, val mime: String,
              val size: Long, val mtime: Long, val parent: String) {
    val dir: Boolean get() = mime == GDriveFs.FOLDER
    val native: Boolean get() = GDriveFs.NATIVE.containsKey(mime)
    val unsupported: Boolean get() = mime.startsWith("application/vnd.google-apps.") && !dir && !native
}

private class BytesSource(private val data: ByteArray) : Source() {
    private var pos = 0
    override val size: Long get() = data.size.toLong()
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) { this.pos = pos.coerceIn(0L, data.size.toLong()).toInt() }
    override fun read(): Int = if (pos >= data.size) -1 else data[pos++].toInt() and 0xff
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= data.size) return -1
        val n = minOf(len, data.size - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

/** Drive dosyası: aranabilir (Range), kopmada yeniden açar. */
private class GSource(private val url: String, override val size: Long) : Source() {
    private var pos = 0L
    private var conn: HttpURLConnection? = null
    private var ins: InputStream? = null
    override val seekable: Boolean get() = true

    private fun drop() {
        try { ins?.close() } catch (_: Exception) {}
        try { conn?.disconnect() } catch (_: Exception) {}
        ins = null; conn = null
    }

    override fun seek(pos: Long) { drop(); this.pos = pos }

    override fun read(): Int {
        val b = ByteArray(1)
        while (true) { val n = read(b, 0, 1); if (n < 0) return -1; if (n == 1) return b[0].toInt() and 255 }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (size <= 0L || pos >= size) return -1   // boş dosya: Drive Range isteğini reddeder, hiç bağlanma
        var last: Exception? = null
        for (attempt in 0 until 5) {
            try {
                if (ins == null) {
                    val c = Gdrive.openStream(url, pos)
                    conn = c; ins = c.inputStream
                }
                val n = ins!!.read(b, off, len)
                if (n < 0) throw IOException("connection lost")   // beklenenden erken bitti -> yeniden aç
                pos += n
                return n
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Full) { throw e
            } catch (e: Exception) {
                last = e
                drop()
                Thread.sleep(500L * (attempt + 1))
            }
        }
        throw IOException("connection lost (${last?.message})")
    }

    override fun close() { drop() }
}

/** Google Drive, [Endpoint] olarak: telefon / SMB ile aynı arayüz, böylece kopyala/taşı/sil/zip/arama hepsi çalışır. */
class GDriveFs : Endpoint {
    override val id: String = Gdrive.ID
    override val name: String = "Google Drive"

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UP = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
        private const val SHORTCUT = "application/vnd.google-apps.shortcut"
        private const val FIELDS = "id,name,mimeType,size,modifiedTime,shortcutDetails(targetId,targetMimeType)"
        private const val TTL = 30_000L
        private const val UP_CHUNK = 4 shl 20   // 256 KiB'ın katı olmalı

        /** Google'a özgü belgeler: (eklenen uzantı, export mime). */
        val NATIVE = mapOf(
            "application/vnd.google-apps.document" to (".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "application/vnd.google-apps.spreadsheet" to (".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "application/vnd.google-apps.presentation" to (".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            "application/vnd.google-apps.drawing" to (".pdf" to "application/pdf"),
        )

        private val TIME = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }

        /** RFC3339 -> saniye (minSdk 24: java.time yok). */
        fun secs(s: String?): Long {
            if (s.isNullOrEmpty() || s.length < 19) return 0L
            return try { (TIME.get()!!.parse(s.substring(0, 19))?.time ?: 0L) / 1000 } catch (_: Exception) { 0L }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun clean(n: String) = n.replace('/', '∕').replace('\\', '＼')
        private fun jsonOf(r: GResp) = JSONObject(String(r.body, Charsets.UTF_8))
        private fun jbody(o: JSONObject) = o.toString().toByteArray(Charsets.UTF_8)
        private val JSON_H = mapOf("Content-Type" to "application/json; charset=UTF-8")
    }

    private class Listing(val t: Long, val map: LinkedHashMap<String, GF>)

    private val cache = HashMap<String, Listing>()
    @Volatile private var rootId: String? = null

    fun clearCache() { synchronized(cache) { cache.clear() }; rootId = null }

    // ---------------------------------------------------------------- ad / kayıt yardımcıları
    private fun disp(real: String, mime: String): String {
        val ext = NATIVE[mime]?.first ?: return clean(real)
        val c = clean(real)
        return if (c.lowercase().endsWith(ext)) c else c + ext
    }

    /** Kullanıcının yazdığı ad -> Drive'a gönderilecek ad (Google belgelerinde eklenen uzantıyı at). */
    private fun realOf(shown: String, mime: String): String {
        val ext = NATIVE[mime]?.first ?: return shown
        return if (shown.lowercase().endsWith(ext)) shown.dropLast(ext.length) else shown
    }

    private fun parse(o: JSONObject, parent: String): GF {
        val id = o.getString("id")
        val m0 = o.optString("mimeType")
        val sc = o.optJSONObject("shortcutDetails")
        val isSc = m0 == SHORTCUT && sc != null
        val tid = if (isSc) sc!!.optString("targetId", id).ifEmpty { id } else id
        val mime = if (isSc) sc!!.optString("targetMimeType", m0).ifEmpty { m0 } else m0
        val real = o.optString("name")
        return GF(id, tid, real, disp(real, mime), mime, o.optString("size").toLongOrNull() ?: 0L, secs(o.optString("modifiedTime")), parent)
    }

    private fun root(): GF {
        val id = rootId ?: jsonOf(Gdrive.call("GET", "$API/files/root?fields=id")).getString("id").also { rootId = it }
        return GF(id, id, "", "", FOLDER, 0L, 0L, "")
    }

    // ---------------------------------------------------------------- liste önbelleği (30 sn, değişiklikte ARTIMLI güncellenir)
    private fun kids(parentTid: String): LinkedHashMap<String, GF> {
        synchronized(cache) {
            val l = cache[parentTid]
            if (l != null && System.currentTimeMillis() - l.t < TTL) return LinkedHashMap(l.map)
        }
        val all = ArrayList<GF>()
        var token: String? = null
        do {
            val url = "$API/files?q=" + enc("'$parentTid' in parents and trashed=false") +
                "&fields=" + enc("nextPageToken,files($FIELDS)") + "&pageSize=1000&orderBy=" + enc("folder,name,createdTime") +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" + (token?.let { "&pageToken=" + enc(it) } ?: "")
            val j = jsonOf(Gdrive.call("GET", url))
            val a = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) all.add(parse(a.getJSONObject(i), parentTid))
            token = j.optString("nextPageToken").ifEmpty { null }
        } while (token != null)
        val m = LinkedHashMap<String, GF>()
        for (f in all) {   // aynı klasörde aynı ad: " (2)", " (3)" (sıra sabit olduğu için her seferinde aynı sonuç)
            var n = f.name
            if (n in m) {
                val (b, e) = if (f.dir) f.name to "" else splitExt(f.name)
                var i = 2
                while ("$b ($i)$e" in m) i++
                n = "$b ($i)$e"
            }
            m[n] = f.copy(name = n)
        }
        synchronized(cache) { cache[parentTid] = Listing(System.currentTimeMillis(), LinkedHashMap(m)) }
        return m
    }

    private fun cachePut(parentTid: String, f: GF) {
        synchronized(cache) {
            val l = cache[parentTid] ?: return
            l.map.values.removeAll { it.id == f.id }
            l.map[f.name] = f.copy(parent = parentTid)
        }
    }

    private fun cacheDel(parentTid: String, shown: String) { synchronized(cache) { cache[parentTid]?.map?.remove(shown) } }

    private fun cacheSeedEmpty(tid: String) { synchronized(cache) { cache[tid] = Listing(System.currentTimeMillis(), LinkedHashMap()) } }

    private fun resolve(v: String): GF {
        var cur = root()
        for (seg in vnorm(v).split('/').filter { it.isNotEmpty() }) {
            if (!cur.dir) throw NotFound("No such file or directory")
            cur = kids(cur.tid)[seg] ?: throw NotFound("No such file or directory")
        }
        return cur
    }

    private fun isRoot(v: String) = vnorm(v) == "/"

    // ---------------------------------------------------------------- okuma
    override fun ls(v: String): List<Item> {
        val g = resolve(v)
        if (!g.dir) throw IOException("Not a directory")
        return kids(g.tid).values.map { Item(it.name, it.dir, if (it.dir) 0L else it.size, it.mtime) }
    }

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> {
        val g = resolve(v)
        if (!g.dir) return listOf(if (g.unsupported) WalkItem("", false, 0L, true) else WalkItem("", false, if (g.native) 0L else g.size))
        val res = arrayListOf(WalkItem("", true, 0L))
        val seen = HashSet<String>()
        seen.add(g.tid)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast("" to g.tid)
        while (stack.isNotEmpty()) {   // bir klasörün girdileri, çocuklarından önce eklenir: mkdir hep dosyalardan önce gelir
            val (r0, tid) = stack.removeLast()
            val ks = try { kids(tid) } catch (e: NotFound) { res.add(WalkItem(if (r0.isEmpty()) "(folder)" else r0, false, 0L, true)); continue }
            for ((n, c) in ks) {
                val r = if (r0.isEmpty()) n else "$r0/$n"
                when {
                    c.dir -> if (seen.add(c.tid)) { res.add(WalkItem(r, true, 0L)); stack.addLast(r to c.tid) } else res.add(WalkItem(r, false, 0L, true))   // kısayol döngüsü
                    c.unsupported -> res.add(WalkItem(r, false, 0L, true))
                    c.native -> res.add(WalkItem(r, false, 0L))
                    else -> res.add(WalkItem(r, false, c.size))
                }
            }
        }
        return res
    }

    override fun open(v: String): Source {
        val g = resolve(v)
        if (g.dir) throw IOException("Is a directory")
        if (g.unsupported) throw Denied("This kind of Google item (form, site, ...) cannot be downloaded")
        val nat = NATIVE[g.mime]
        if (nat != null) {
            val r = Gdrive.call("GET", "$API/files/${enc(g.tid)}/export?mimeType=" + enc(nat.second), timeoutMs = 120_000)
            return BytesSource(r.body)
        }
        return GSource("$API/files/${enc(g.tid)}?alt=media&supportsAllDrives=true", g.size)
    }

    // ---------------------------------------------------------------- yazma
    private fun createFolder(parent: GF, nm: String): GF {
        val body = JSONObject().put("name", nm).put("mimeType", FOLDER).put("parents", JSONArray().put(parent.tid))
        val r = Gdrive.call("POST", "$API/files?supportsAllDrives=true&fields=" + enc(FIELDS), jbody(body), JSON_H)
        val f = parse(jsonOf(r), parent.tid)
        cachePut(parent.tid, f)
        cacheSeedEmpty(f.tid)
        return f
    }

    /** [v] klasörünü döndürür; eksik üst klasörleri oluşturur, var olanı kabul eder, yoldaki dosya -> Exists. */
    private fun ensureDir(v: String): GF {
        var cur = root()
        for (seg in vnorm(v).split('/').filter { it.isNotEmpty() }) {
            val k = kids(cur.tid)[seg]
            cur = when {
                k == null -> createFolder(cur, seg)
                k.dir -> k
                else -> throw Exists("a file is in the way: $seg")
            }
        }
        return cur
    }

    override fun mkdir(v: String) { ensureDir(v) }

    private fun readFull(ins: InputStream, buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val k = ins.read(buf, got, n - got)
            if (k < 0) throw IOException("connection lost")
            got += k
        }
        return got
    }

    private fun rangeEnd(r: GResp): Long {   // "bytes=0-1048575" -> 1048576 ; başlık yok -> 0
        val h = r.headers["range"] ?: return 0L
        val e = h.substringAfter('-', "").trim().toLongOrNull() ?: return 0L
        return e + 1
    }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        val n = vnorm(v)
        if (n == "/") throw BadReq("invalid path")
        val nm = vbase(n)
        val dirGf = ensureDir(vdir(n))
        val ex = kids(dirGf.tid)[nm]
        var updateId: String? = null
        if (ex != null) {
            when {
                ex.dir -> throw Exists("a folder with that name already exists")
                ex.native || ex.unsupported -> throw Denied("cannot overwrite a Google document")
                ex.id != ex.tid -> throw Denied("cannot overwrite a shortcut")
            }
            updateId = ex.id
        }
        val meta = JSONObject()
        if (updateId == null) meta.put("name", nm).put("parents", JSONArray().put(dirGf.tid))
        val initUrl = (if (updateId == null) "$UP/files" else "$UP/files/" + enc(updateId)) +
            "?uploadType=resumable&supportsAllDrives=true&fields=" + enc(FIELDS)
        val hdr = HashMap(JSON_H)
        hdr["X-Upload-Content-Type"] = mimeFor(nm)
        hdr["X-Upload-Content-Length"] = size.toString()
        val init = Gdrive.call(if (updateId == null) "POST" else "PATCH", initUrl, jbody(meta), hdr)
        val session = init.headers["location"] ?: throw IOException("Google Drive gave no upload address")

        var done: GResp? = null
        if (size <= 0L) {
            val r = Gdrive.call("PUT", session, ByteArray(0), emptyMap(), auth = false, raw = true)
            if (r.code != 200 && r.code != 201) Gdrive.fail(r.code, r.body)
            done = r
        } else {
            val buf = ByteArray(UP_CHUNK)
            var base = 0L; var have = 0
            var acked = 0L
            var tries = 0
            while (done == null) {
                if (acked >= base + have) {   // tampon bitti: sıradaki parçayı oku
                    base = acked
                    have = readFull(input, buf, minOf(UP_CHUNK.toLong(), size - base).toInt())
                }
                val off = (acked - base).toInt()
                val end = base + have - 1
                val r = try {
                    Gdrive.call("PUT", session, buf, mapOf("Content-Range" to "bytes $acked-$end/$size"),
                        bodyOff = off, bodyLen = have - off, auth = false, raw = true, timeoutMs = 120_000)
                } catch (e: IOException) { null }
                val before = acked
                when {
                    r != null && (r.code == 200 || r.code == 201) -> { done = r; acked = size }
                    r != null && r.code == 308 -> { acked = maxOf(acked, rangeEnd(r)); tries = 0 }
                    r != null && (r.code == 404 || r.code == 410) -> throw IOException("Google Drive upload session expired - try again")
                    r != null && r.code < 500 && r.code != 429 -> Gdrive.fail(r.code, r.body)
                    else -> {   // ağ hatası / 5xx: Drive'a ne kadarını aldığını sor, kaldığı yerden devam et
                        if (++tries > 5) throw IOException("Google Drive upload failed" + (r?.let { " (HTTP ${it.code})" } ?: ""))
                        Thread.sleep(1000L * tries)
                        val q = try { Gdrive.call("PUT", session, ByteArray(0), mapOf("Content-Range" to "bytes */$size"), auth = false, raw = true) } catch (e: IOException) { null }
                        if (q != null) when (q.code) {
                            200, 201 -> { done = q; acked = size }
                            308 -> acked = maxOf(acked, rangeEnd(q))
                        }
                    }
                }
                if (acked > before) cb?.invoke((acked - before).toInt())   // try dışında: Cancelled fırlatabilir
            }
        }
        val f = try { parse(jsonOf(done!!), dirGf.tid) } catch (_: Exception) { null }
        if (f != null) cachePut(dirGf.tid, f) else synchronized(cache) { cache.remove(dirGf.tid) }
    }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val g = try { resolve(v) } catch (_: NotFound) { return }   // zaten yok
        if (g.id == root().id) throw Denied("cannot delete the Drive root")
        Gdrive.call("PATCH", "$API/files/${enc(g.id)}?supportsAllDrives=true&fields=id", jbody(JSONObject().put("trashed", true)), JSON_H)   // çöp kutusuna: geri alınabilir
        cacheDel(g.parent, g.name)
        progress?.invoke(g.name)
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        val g = resolve(v)
        if (g.id == root().id) throw BadReq("cannot rename the Drive root")
        if (newName == g.name) return
        val taken = kids(g.parent)[newName]
        if (taken != null && taken.id != g.id) throw Exists("name already exists")
        val r = Gdrive.call("PATCH", "$API/files/${enc(g.id)}?supportsAllDrives=true&fields=" + enc(FIELDS),
            jbody(JSONObject().put("name", realOf(newName, g.mime))), JSON_H)
        cacheDel(g.parent, g.name)
        cachePut(g.parent, parse(jsonOf(r), g.parent))
    }

    override fun move(v: String, toV: String) {
        val g = resolve(v)
        if (g.id == root().id) throw Denied("cannot move the Drive root")
        var newParent: GF
        var newShown = g.name
        val dest = try { resolve(toV) } catch (_: NotFound) { null }
        if (dest != null) {
            if (!dest.dir) throw Exists("name already exists")
            newParent = dest   // mevcut klasör: içine taşı
        } else {                // yoksa: son parça yeni ad
            newParent = resolve(vdir(toV))
            if (!newParent.dir) throw NotFound("No such file or directory")
            newShown = vbase(toV)
        }
        if (g.dir && newParent.tid == g.tid) throw BadReq("cannot move a folder into itself")
        if (newParent.tid == g.parent && newShown == g.name) return
        val taken = kids(newParent.tid)[newShown]
        if (taken != null && taken.id != g.id) throw Exists("name already exists")
        val body = JSONObject()
        if (newShown != g.name) body.put("name", realOf(newShown, g.mime))
        val url = "$API/files/${enc(g.id)}?supportsAllDrives=true&addParents=${enc(newParent.tid)}&removeParents=${enc(g.parent)}&fields=" + enc(FIELDS)
        val r = Gdrive.call("PATCH", url, jbody(body), JSON_H)
        cacheDel(g.parent, g.name)
        cachePut(newParent.tid, parse(jsonOf(r), newParent.tid))
    }

    // ---------------------------------------------------------------- bilgi
    private fun tree(g: GF, capMs: Long): Pair<LongArray, Boolean> {
        val tot = LongArray(3)
        val end = System.currentTimeMillis() + capMs
        val seen = HashSet<String>()
        seen.add(g.tid)
        val stack = ArrayDeque<String>()
        stack.addLast(g.tid)
        while (stack.isNotEmpty()) {
            if (System.currentTimeMillis() > end) return tot to true
            val ks = try { kids(stack.removeLast()) } catch (e: IOException) { if (e is NotFound || e is Denied) continue else throw e }
            for (c in ks.values) {
                if (c.dir) { if (seen.add(c.tid)) { tot[1]++; stack.addLast(c.tid) } }
                else { tot[0]++; if (!c.native && !c.unsupported) tot[2] += c.size }
            }
        }
        return tot to false
    }

    override fun stat(v: String): JSONObject {
        val n = vnorm(v)
        val g = resolve(n)
        val j = jsonOf(Gdrive.call("GET", "$API/files/${enc(g.id)}?supportsAllDrives=true&fields=" + enc("createdTime,capabilities(canEdit)")))
        val o = JSONObject().put("name", if (n == "/") "My Drive" else g.name).put("path", n).put("dir", g.dir)
            .put("size", if (g.dir) 0L else g.size).put("mtime", g.mtime).put("ctime", secs(j.optString("createdTime")))
            .put("readonly", !(j.optJSONObject("capabilities")?.optBoolean("canEdit", true) ?: true))
        if (g.dir) {
            val (t, partial) = tree(g, 6_000)
            o.put("files", t[0]).put("folders", t[1]).put("total", t[2]).put("partial", partial)
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = try {
        val q = jsonOf(Gdrive.call("GET", "$API/about?fields=" + enc("storageQuota(limit,usage)"))).optJSONObject("storageQuota")
        val limit = q?.optString("limit")?.toLongOrNull()
        val usage = q?.optString("usage")?.toLongOrNull() ?: 0L
        if (limit == null || limit <= 0) null else maxOf(limit - usage, 0L) to limit   // limit yok = sınırsız -> null
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- arama
    override fun search(v: String, q: String): SearchResult {
        val start = vnorm(v)
        val rid = root().id
        resolve(start)   // yoksa NotFound
        val deadline = System.currentTimeMillis() + 15_000L
        val esc = q.replace("\\", "\\\\").replace("'", "\\'")
        val memo = HashMap<String, Pair<String, String?>?>()   // klasör id -> (ad, üst id)
        fun info(id: String): Pair<String, String?>? {
            if (memo.containsKey(id)) return memo[id]
            val r = try {
                val j = jsonOf(Gdrive.call("GET", "$API/files/${enc(id)}?supportsAllDrives=true&fields=" + enc("name,parents")))
                j.optString("name") to j.optJSONArray("parents")?.optString(0)
            } catch (e: IOException) { null }
            memo[id] = r
            return r
        }
        fun pathOf(parentId: String): String? {
            val segs = ArrayList<String>()
            var cur = parentId
            var guard = 0
            while (cur != rid) {
                val i = info(cur) ?: return null
                segs.add(clean(i.first))
                cur = i.second ?: return null
                if (++guard > 64) return null
            }
            return "/" + segs.reversed().joinToString("/")
        }
        val prefix = if (start == "/") "/" else "$start/"
        val out = ArrayList<Item>()
        var partial = false
        var token: String? = null
        var pages = 0
        do {
            val url = "$API/files?q=" + enc("name contains '$esc' and trashed=false") +
                "&fields=" + enc("nextPageToken,files($FIELDS,parents)") + "&pageSize=100" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" + (token?.let { "&pageToken=" + enc(it) } ?: "")
            val j = jsonOf(Gdrive.call("GET", url))
            val a = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) {
                if (System.currentTimeMillis() > deadline) { partial = true; break }
                val o = a.getJSONObject(i)
                val par = o.optJSONArray("parents")?.optString(0) ?: continue
                val dirPath = pathOf(par) ?: continue
                val f = parse(o, par)
                val full = if (dirPath == "/") "/" + f.name else dirPath + "/" + f.name
                if (!full.startsWith(prefix)) continue
                out.add(Item(f.name, f.dir, if (f.dir) 0L else f.size, f.mtime, null, full))
            }
            token = j.optString("nextPageToken").ifEmpty { null }
            pages++
        } while (token != null && !partial && out.size < 100 && pages < 5 && System.currentTimeMillis() < deadline)
        if (token != null || System.currentTimeMillis() >= deadline) partial = true
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }
}
