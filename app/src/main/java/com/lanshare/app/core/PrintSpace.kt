package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Print space: a holding area for pictures that will be printed later, several per sheet, on one or both sides.
 *
 * Storage (same idea as [Bin]): "<root>/.LANShare Print/<id>/<original name>" plus one manifest "<root>/.LANShare Print/items.json".
 * Pictures are COPIED in (the original may be deleted or edited later and the print must not change); a ".nomedia" keeps the gallery from indexing the copies.
 * The manifest order is the print order. Each item also carries per-picture print settings that later parts use: [Item.copies] and [Item.rot] (0 / 90 / 180 / 270).
 *
 * Because the files live under the shared root, the existing print pipeline can already print them: pass the virtual path from [vpath] as a "local" path
 * (ui.html doPrint(preset, {items, full:true, dev:'local', ...})). Nothing here talks to printers.
 *
 * Part 1 of the Print space plan (HANDOVER.md 9.7). Only pictures of THIS phone can be added for now (remote devices come in part 3).
 * NOT compiled / NOT device-tested.
 */
object PrintSpace {
    const val NAME = ".LANShare Print"
    private const val MANIFEST = "items.json"
    const val MAX_ITEMS = 500
    const val MAX_COPIES = 99

    /** Pictures only; PDFs and other files may be allowed later by extending this set. */
    val EXT: Set<String> = setOf("jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    class Item(val id: String, val name: String, val size: Long, val from: String, val at: Long, var copies: Int, var rot: Int) {
        fun path(): String = "/$NAME/$id/$name"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size).put("from", from).put("at", at)
            .put("copies", copies).put("rot", rot).put("path", path())
    }

    private val items = ArrayList<Item>()
    private var loaded = false

    private fun root(): File = File(Core.local.root, NAME)

    /** True for the print space folder itself or anything inside it (never added to itself, never offered to the gallery). */
    fun inSpace(v: String): Boolean = vnorm(v).trimStart('/').let { it == NAME || it.startsWith("$NAME/") }

    fun isPicture(name: String): Boolean = EXT.contains(name.substringAfterLast('.', "").lowercase())

    // ------------------------------------------------------------------ manifest

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        items.clear()
        val mf = File(root(), MANIFEST)
        if (!mf.isFile) return
        try {
            val a = JSONObject(mf.readText()).optJSONArray("items") ?: return
            for (n in 0 until a.length()) {
                val o = a.optJSONObject(n) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isEmpty() || name.isEmpty()) continue
                val f = File(File(root(), id), name)
                if (!f.isFile) continue   // the copy vanished (cleared app data, manual delete): drop the entry
                items.add(Item(id, name, f.length(), o.optString("from"), o.optLong("at"), clampCopies(o.optInt("copies", 1)), clampRot(o.optInt("rot", 0))))
            }
        } catch (_: Exception) {
            // a damaged manifest must not break the app: start empty (the copies stay on disk and can be re-added)
        }
    }

    @Synchronized private fun save() {
        val dir = root()
        dir.mkdirs()
        try { File(dir, ".nomedia").createNewFile() } catch (_: Exception) {}
        val a = JSONArray()
        for (i in items) a.put(JSONObject().put("id", i.id).put("name", i.name).put("from", i.from).put("at", i.at).put("copies", i.copies).put("rot", i.rot))
        val tmp = File(dir, "$MANIFEST.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("items", a).toString())
        val dst = File(dir, MANIFEST)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("could not save the print space list")
        }
    }

    private fun clampCopies(n: Int) = n.coerceIn(1, MAX_COPIES)

    /** Turns any number into 0 / 90 / 180 / 270. */
    private fun clampRot(n: Int) = ((n % 360) + 360) % 360 / 90 * 90

    // ------------------------------------------------------------------ operations

    @Synchronized fun json(): JSONObject {
        load()
        var bytes = 0L
        val a = JSONArray()
        for (i in items) { bytes += i.size; a.put(i.toJson()) }
        return JSONObject().put("items", a).put("count", items.size).put("bytes", bytes).put("max", MAX_ITEMS)
    }

    /** Copy of the list in print order (the layout editor reads sizes from the files). */
    @Synchronized fun items(): List<Item> { load(); return items.map { x -> Item(x.id, x.name, x.size, x.from, x.at, x.copies, x.rot) } }

    /** Virtual path of one stored picture (for the print pipeline), or null. */
    @Synchronized fun pathOf(id: String): String? { load(); return items.firstOrNull { it.id == id }?.path() }

    /** Copies pictures of this phone into the space. Returns {added:[items], skipped:[{name, why}]}. */
    @Synchronized fun add(paths: List<String>): JSONObject {
        load()
        val added = JSONArray()
        val skipped = JSONArray()
        fun skip(n: String, why: String) { skipped.put(JSONObject().put("name", n).put("why", why)) }
        for (p in paths) {
            val v = vnorm(p)
            val nm = v.substringAfterLast('/')
            try {
                if (items.size >= MAX_ITEMS) { skip(nm, "print space is full ($MAX_ITEMS pictures)"); continue }
                if (inSpace(v) || Bin.inBin(v)) { skip(nm, "already in the print space or in the recycle bin"); continue }
                if (arcSplit(v) != null) { skip(nm, "inside an archive"); continue }
                val src = Core.local.real(v)
                if (!src.exists()) { skip(nm, "not found"); continue }
                if (src.isDirectory) { skip(nm, "folder (select pictures)"); continue }
                if (!isPicture(src.name)) { skip(nm, "not a picture"); continue }
                val id = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().replace("-", "").take(4)
                val box = File(root(), id)
                if (!box.mkdirs()) { skip(nm, "could not create the folder"); continue }
                val dst = File(box, src.name)
                val part = File(box, src.name + ".part")
                try {
                    src.copyTo(part, overwrite = true)
                    if (!part.renameTo(dst)) throw IOException("could not store the copy")
                } catch (e: Exception) {
                    try { box.deleteRecursively() } catch (_: Exception) {}
                    skip(nm, errText(e).take(120))
                    continue
                }
                val it = Item(id, src.name, dst.length(), v, System.currentTimeMillis(), 1, 0)
                items.add(it)
                added.put(it.toJson())
            } catch (e: Exception) {
                skip(nm, errText(e).take(120))
            }
        }
        if (added.length() > 0) save()
        return JSONObject().put("added", added).put("skipped", skipped)
    }

    @Synchronized fun remove(ids: List<String>): Int {
        load()
        val gone = ids.toHashSet()
        var n = 0
        val it = items.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (x.id !in gone) continue
            try { File(root(), x.id).deleteRecursively() } catch (_: Exception) {}
            it.remove()
            n++
        }
        if (n > 0) save()
        return n
    }

    @Synchronized fun clear(): Int {
        load()
        val n = items.size
        for (x in items) try { File(root(), x.id).deleteRecursively() } catch (_: Exception) {}
        items.clear()
        save()
        return n
    }

    /** Moves one picture to position [to] (0-based, clamped) in the print order. */
    @Synchronized fun move(id: String, to: Int) {
        load()
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) throw NotFound("no such picture in the print space")
        val x = items.removeAt(from)
        items.add(to.coerceIn(0, items.size), x)
        save()
    }

    /** Changes [copies] and / or [rot] of one picture; null leaves a value alone. */
    @Synchronized fun set(id: String, copies: Int?, rot: Int?) {
        load()
        val x = items.firstOrNull { it.id == id } ?: throw NotFound("no such picture in the print space")
        if (copies != null) x.copies = clampCopies(copies)
        if (rot != null) x.rot = clampRot(rot)
        save()
    }

    /** POST /api/ps body: {op:"add", paths:[...]} | {op:"rm", ids:[...]} | {op:"clear"} | {op:"move", id, to} | {op:"set", id, copies?, rot?}. Answers the new list. */
    fun post(b: JSONObject): JSONObject {
        val res = JSONObject()
        when (val op = b.optString("op")) {
            "add" -> {
                if (b.optString("dev", "local") != "local") throw BadReq("Print space takes pictures from this phone only (other devices: later part)")
                val r = add(b.getJSONArray("paths").strings())
                res.put("added", r.getJSONArray("added")).put("skipped", r.getJSONArray("skipped"))
            }
            "rm" -> res.put("removed", remove(b.getJSONArray("ids").strings()))
            "clear" -> res.put("removed", clear())
            "move" -> move(b.getString("id"), b.getInt("to"))
            "set" -> set(b.getString("id"), if (b.has("copies")) b.getInt("copies") else null, if (b.has("rot")) b.getInt("rot") else null)
            else -> throw BadReq("unknown print space operation: $op")
        }
        val list = json()
        for (k in list.keys()) res.put(k, list.get(k))
        return res
    }
}
