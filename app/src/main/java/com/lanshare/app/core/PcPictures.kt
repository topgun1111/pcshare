package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Gallery of a PC (a saved SMB share): ONLY the PC's Pictures folder and its sub-folders, shown as albums like another phone's gallery
 * (`GET /api/ralbums?dev=smb:...`, thumbnails through `/api/rthumb`, grid / viewer through `/api/ls` + `/api/dl`, all read-only).
 * The folder is found by itself (see [find]) or set by hand (`picdir` in the share's settings, `POST /api/pcpicdir`).
 * NOT compiled on a device / NOT device-tested.
 */
object PcPictures {
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff")
    /** Names Windows gives the Pictures folder (English, Turkish, German, French, Spanish, Italian, Russian ...). Compared in lower case. */
    private val NAMES = setOf("pictures", "my pictures", "resimler", "resimlerim", "bilder", "eigene bilder", "images", "mes images", "images ", "imágenes", "mis imágenes",
        "immagini", "mie immagini", "изображения", "fotoğraflar", "photos", "fotos")
    private val SKIP_USERS = setOf("public", "default", "default user", "all users", "defaultuser0", "desktop.ini", "genel", "herkes")
    private val JUNK = setOf("thumbnails", "thumbnail", "thumbs", ".thumbnails", "cache", "caches", "tmp", "temp", "\$recycle.bin", "recycler", "system volume information")
    private const val MAX_FOLDERS = 2500
    private const val BUDGET_MS = 100_000L
    private const val CACHE_MS = 90_000L

    private class Cached(val at: Long, val json: JSONObject)
    private val cache = ConcurrentHashMap<String, Cached>()
    private val found = ConcurrentHashMap<String, String>()

    private fun low(s: String) = s.trim().lowercase()

    /** The Pictures folder of the PC behind [dev] (virtual path like "/C$/Users/Ali/Pictures"). By hand first, then the first one found. */
    fun find(dev: String, fresh: Boolean = false): String {
        val c = Smb.cfg(dev) ?: throw BadReq("this PC is no longer in the list")
        val own = c.optString("picdir", "").trim()
        val ep = Jobs.ep(dev)
        if (own.isNotEmpty()) {
            val p = vnorm(own)
            try { if (ep.stat(p).optBoolean("dir", true)) return p } catch (_: Exception) {}
            throw NotFound("The Pictures folder you set ($p) was not found on ${c.optString("name")}")
        }
        if (!fresh) found[dev]?.let { return it }
        val hits = ArrayList<String>()
        val shares = ep.ls("/").filter { it.dir }.map { it.name }.filter { !it.equals("IPC$", true) && !it.equals("print$", true) && !it.equals("ADMIN$", true) }
        fun kids(p: String): List<com.lanshare.app.core.Item> = try { ep.lsFast(p).filter { it.dir } } catch (_: Exception) { emptyList() }
        // 1. a share that IS the Pictures folder (or has one at its top)
        for (s in shares) {
            if (low(s).trimEnd('$') in NAMES) { hits.add("/$s"); continue }
            for (k in kids("/$s")) if (low(k.name) in NAMES) hits.add(vjoin("/$s", k.name))
        }
        // 2. <share>/Users/<name>/Pictures (a drive share like C$, or a share of the Users folder), and <share>/<name>/Pictures (Users shared directly)
        if (hits.isEmpty()) for (s in shares) {
            val top = kids("/$s")
            val users = top.firstOrNull { low(it.name) == "users" || low(it.name) == "kullanıcılar" || low(it.name) == "benutzer" }?.let { vjoin("/$s", it.name) }
            for (u in listOfNotNull(users, if (users == null && top.size <= 40) "/$s" else null)) {
                for (uk in kids(u)) {
                    if (low(uk.name) in SKIP_USERS) continue
                    for (k in kids(vjoin(u, uk.name))) if (low(k.name) in NAMES) hits.add(vjoin(vjoin(u, uk.name), k.name))
                }
            }
        }
        if (hits.isEmpty()) throw NotFound("No Pictures folder found on ${c.optString("name")} (looked for Pictures / Resimler in the shared folders and in Users). Set it by hand: press and hold the PC chip.")
        // the one with the most recent pictures is the user's real folder; a quick look at each (first level only)
        val best = hits.maxByOrNull { h -> try { ep.lsFast(h).maxOfOrNull { it.mtime } ?: 0L } catch (_: Exception) { 0L } } ?: hits[0]
        found[dev] = best
        return best
    }

    private class A { var n = 0; var cover = ""; var size = 0L; var mtime = 0L }

    /** {albums:[{path,name,n,cover,size,mtime}], root:<Pictures folder>} - one album per folder that holds pictures directly (the Pictures folder itself included). */
    fun albums(dev: String, fresh: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        if (!fresh) cache[dev]?.let { if (now - it.at < CACHE_MS) return it.json }
        val ep = Jobs.ep(dev)
        val root = find(dev, fresh)
        val end = now + BUDGET_MS
        val map = LinkedHashMap<String, A>()
        val stack = ArrayDeque<String>()
        stack.addLast(root)
        var seen = 0
        var cut = false
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            if (++seen > MAX_FOLDERS || System.currentTimeMillis() > end) { cut = true; break }
            val list = try { ep.lsFast(d) } catch (_: Exception) { continue }
            var a: A? = null
            for (i in list) {
                if (i.name.startsWith(".")) continue
                if (i.dir) { if (low(i.name) !in JUNK) stack.addLast(vjoin(d, i.name)); continue }
                val x = i.name.substringAfterLast('.', "").lowercase()
                if (x !in IMG) continue
                val al = a ?: A().also { a = it }
                al.n++
                if (al.cover.isEmpty() || i.mtime > al.mtime) { al.mtime = i.mtime; al.cover = vjoin(d, i.name); al.size = i.size }
            }
            a?.let { map[d] = it }
        }
        val names = map.keys.groupBy { it.substringAfterLast('/') }
        val arr = JSONArray()
        for ((p, a) in map.entries.sortedByDescending { it.value.mtime }) {
            val nm = p.substringAfterLast('/')
            val disp = if ((names[nm]?.size ?: 0) > 1) p.removePrefix(root).trim('/').ifEmpty { nm }.replace("/", " / ") else nm
            arr.put(JSONObject().put("path", p).put("name", disp).put("n", a.n).put("cover", a.cover).put("size", a.size).put("mtime", a.mtime))
        }
        val r = JSONObject().put("albums", arr).put("video", false).put("root", root).put("cut", cut)
        if (!cut) cache[dev] = Cached(System.currentTimeMillis(), r)
        return r
    }

    /** Sets (or, with an empty [path], clears) the Pictures folder of a PC by hand. */
    fun setDir(dev: String, path: String): JSONObject {
        val c = Smb.cfg(dev) ?: throw BadReq("this PC is no longer in the list")
        val p = if (path.isBlank()) "" else vnorm(path.trim().replace('\\', '/'))
        if (p.isNotEmpty()) {
            val ok = try { Jobs.ep(dev).stat(p).let { it.optBoolean("dir", true) } } catch (_: Exception) { false }
            if (!ok) throw NotFound("That folder was not found on the PC: $p")
        }
        val cur = Cfg.smb()
        val list = ArrayList<JSONObject>()
        for (i in 0 until cur.length()) cur.getJSONObject(i).let { list.add(if (it.optString("id") == dev) it.put("picdir", p) else it) }
        Cfg.setSmb(JSONArray(list))
        Cfg.save()
        cache.remove(dev); found.remove(dev)
        return JSONObject().put("ok", true).put("picdir", p).put("name", c.optString("name"))
    }

    fun forget(dev: String) { cache.remove(dev); found.remove(dev) }
}
