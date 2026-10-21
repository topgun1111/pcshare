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
 * Part 1 of the Print space plan (HANDOVER.md 9.7). Part 3b: pictures of other devices / shares are streamed in too (`dev` in {op:"add"}).
 * Part 8: clean-up settings ([days] = remove copies older than N days, [mb] = storage limit for new copies), kept in "<root>/.LANShare Print/settings.json".
 * Part 9a: undo for remove / clear (the copies are moved to "<root>/.LANShare Print/.undo" and can be brought back with {op:"undo"} until the next remove / clear,
 * the next app start or the age clean-up), and {op:"set"} for several pictures at once ([ids], [rotBy]).
 * NOT compiled / NOT device-tested.
 */
object PrintSpace {
    const val NAME = ".LANShare Print"
    private const val MANIFEST = "items.json"
    const val MAX_ITEMS = 500
    const val MAX_COPIES = 99
    private const val SETTINGS = "settings.json"
    const val MAX_DAYS = 3650
    const val MAX_MB = 102400
    private const val DAY_MS = 86_400_000L

    /** Pictures only; PDFs and other files may be allowed later by extending this set. */
    val EXT: Set<String> = setOf("jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    class Item(val id: String, val name: String, val size: Long, val from: String, val at: Long, var copies: Int, var rot: Int) {
        fun path(): String = "/$NAME/$id/$name"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("size", size).put("from", from).put("at", at)
            .put("copies", copies).put("rot", rot).put("path", path())
    }

    private val items = ArrayList<Item>()
    private var loaded = false

    /** Part 8: remove copies older than this many days (0 = keep until removed by hand). */
    private var days = 0
    /** Part 8: no new copies once the held pictures take this many MB (0 = no limit). */
    private var mb = 0
    /** Copies removed by the age clean-up since the list was last sent to the screen (reported once, then reset). */
    private var swept = 0

    /** Part 9a: the last remove / clear, kept for one undo: (position in the list, item). The folders wait in [UNDO]. Memory only: a new start deletes them for good. */
    private val undoList = ArrayList<Pair<Int, Item>>()
    private const val UNDO = ".undo"

    private fun root(): File = File(Core.local.root, NAME)

    /** True for the print space folder itself or anything inside it (never added to itself, never offered to the gallery). */
    fun inSpace(v: String): Boolean = vnorm(v).trimStart('/').let { it == NAME || it.startsWith("$NAME/") }

    fun isPicture(name: String): Boolean = EXT.contains(name.substringAfterLast('.', "").lowercase())

    // ------------------------------------------------------------------ manifest

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        items.clear()
        undoList.clear()
        try { File(root(), UNDO).deleteRecursively() } catch (_: Exception) {}   // an undo never survives an app start
        loadSettings()
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
        sweep()
    }

    @Synchronized private fun loadSettings() {
        days = 0
        mb = 0
        val f = File(root(), SETTINGS)
        if (!f.isFile) return
        try {
            val o = JSONObject(f.readText())
            days = o.optInt("days", 0).coerceIn(0, MAX_DAYS)
            mb = o.optInt("mb", 0).coerceIn(0, MAX_MB)
        } catch (_: Exception) {
            // damaged settings file: defaults (nothing is deleted automatically)
        }
    }

    @Synchronized private fun saveSettings() {
        val dir = root()
        dir.mkdirs()
        try { File(dir, ".nomedia").createNewFile() } catch (_: Exception) {}
        val tmp = File(dir, "$SETTINGS.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("days", days).put("mb", mb).toString())
        val dst = File(dir, SETTINGS)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("could not save the print space settings")
        }
    }

    /** Age clean-up: removes copies added more than [days] days ago. Runs when the list is loaded, read or added to, never in the background. */
    @Synchronized private fun sweep() {
        if (days <= 0 || items.isEmpty()) return
        val limit = System.currentTimeMillis() - days * DAY_MS
        var n = 0
        val it = items.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (x.at <= 0L || x.at >= limit) continue   // unknown age: keep
            try { File(root(), x.id).deleteRecursively() } catch (_: Exception) {}
            it.remove()
            n++
        }
        if (n > 0) {
            swept += n
            try { save() } catch (_: Exception) {}
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
        sweep()
        var bytes = 0L
        val a = JSONArray()
        for (i in items) { bytes += i.size; a.put(i.toJson()) }
        val res = JSONObject().put("items", a).put("count", items.size).put("bytes", bytes).put("max", MAX_ITEMS)
            .put("cfg", JSONObject().put("days", days).put("mb", mb))
        if (undoList.isNotEmpty()) res.put("undo", undoList.size)
        if (swept > 0) { res.put("swept", swept); swept = 0 }
        return res
    }

    /** Part 8: sets the clean-up age (days, 0 = off) and / or the storage limit (MB, 0 = none); null leaves a value alone. Lowering the limit deletes nothing. */
    @Synchronized fun setSettings(newDays: Int?, newMb: Int?) {
        load()
        if (newDays != null) days = newDays.coerceIn(0, MAX_DAYS)
        if (newMb != null) mb = newMb.coerceIn(0, MAX_MB)
        saveSettings()
        sweep()
    }

    /** Copy of the list in print order (the layout editor reads sizes from the files). */
    @Synchronized fun items(): List<Item> { load(); return items.map { x -> Item(x.id, x.name, x.size, x.from, x.at, x.copies, x.rot) } }

    /** Virtual path of one stored picture (for the print pipeline), or null. */
    @Synchronized fun pathOf(id: String): String? { load(); return items.firstOrNull { it.id == id }?.path() }

    /** Largest single picture taken from another device (bigger ones are skipped, they would block the request for minutes). */
    private const val MAX_REMOTE = 60L * 1024 * 1024

    /**
     * Copies pictures into the space. [dev] = "local" (this phone) or any other device / share id known to [Jobs.ep] (part 3b: the bytes are
     * streamed into the copy, nothing is kept on the other device). Returns {added:[items], skipped:[{name, why}]}.
     */
    @Synchronized fun add(paths: List<String>, dev: String = "local"): JSONObject {
        load()
        sweep()
        val added = JSONArray()
        val skipped = JSONArray()
        fun skip(n: String, why: String) { skipped.put(JSONObject().put("name", n).put("why", why)) }
        val remote = dev != "local"
        val ep: Endpoint? = if (remote) {
            try { Jobs.ep(dev) } catch (e: Exception) {
                for (p in paths) skip(vnorm(p).substringAfterLast('/'), errText(e).take(120))
                return JSONObject().put("added", added).put("skipped", skipped)
            }
        } else null
        for (p in paths) {
            val v = vnorm(p)
            val nm = v.substringAfterLast('/')
            try {
                if (items.size >= MAX_ITEMS) { skip(nm, "print space is full ($MAX_ITEMS pictures)"); continue }
                if (!remote && (inSpace(v) || Bin.inBin(v))) { skip(nm, "already in the print space or in the recycle bin"); continue }
                if (!remote && arcSplit(v) != null) { skip(nm, "inside an archive"); continue }
                if (!isPicture(nm)) { skip(nm, "not a picture"); continue }
                var srcLen = 0L
                var src: File? = null
                if (!remote) {
                    val f = Core.local.real(v)
                    if (!f.exists()) { skip(nm, "not found"); continue }
                    if (f.isDirectory) { skip(nm, "folder (select pictures)"); continue }
                    src = f
                    srcLen = f.length()
                } else {
                    try {
                        val st = ep!!.stat(v)
                        if (st.optBoolean("dir")) { skip(nm, "folder (select pictures)"); continue }
                        srcLen = st.optLong("size", 0L)
                    } catch (e: Exception) { skip(nm, errText(e).take(120)); continue }
                    if (srcLen > MAX_REMOTE) { skip(nm, "too big to take from another device (max ${MAX_REMOTE / 1048576} MB)"); continue }
                }
                if (mb > 0 && items.sumOf { x -> x.size } + srcLen > mb * 1024L * 1024L) { skip(nm, "storage limit reached ($mb MB)"); continue }
                val id = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().replace("-", "").take(4)
                val box = File(root(), id)
                if (!box.mkdirs()) { skip(nm, "could not create the folder"); continue }
                val fname = src?.name ?: nm
                val dst = File(box, fname)
                val part = File(box, "$fname.part")
                try {
                    if (src != null) src.copyTo(part, overwrite = true)
                    else ep!!.open(v).use { ins -> part.outputStream().use { out -> ins.copyTo(out) } }
                    if (!part.renameTo(dst)) throw IOException("could not store the copy")
                } catch (e: Exception) {
                    try { box.deleteRecursively() } catch (_: Exception) {}
                    skip(nm, errText(e).take(120))
                    continue
                }
                val it = Item(id, fname, dst.length(), if (remote) "$dev:$v" else v, System.currentTimeMillis(), 1, 0)
                items.add(it)
                added.put(it.toJson())
            } catch (e: Exception) {
                skip(nm, errText(e).take(120))
            }
        }
        if (added.length() > 0) save()
        return JSONObject().put("added", added).put("skipped", skipped)
    }

    /**
     * Part 3b (share intent): stores ONE picture that arrives as a stream (Android share sheet). [mime] is only used to give a name without
     * a picture extension one ("image/jpeg" -> ".jpg"). Returns null when it was added, otherwise the reason it was not.
     */
    @Synchronized fun addStream(name: String, mime: String?, open: () -> java.io.InputStream?): String? {
        load()
        sweep()
        var fname = name.replace("/", "_").ifEmpty { "shared_" + System.currentTimeMillis() }
        if (!isPicture(fname)) {
            val sub = (mime ?: "").substringAfter("image/", "").substringBefore(';').lowercase()
            val ext = when (sub) { "jpeg", "pjpeg" -> "jpg"; "heic", "heif", "png", "webp", "gif", "bmp", "avif" -> sub; else -> "" }
            if (ext.isEmpty()) return "not a picture"
            fname = "$fname.$ext"
        }
        if (items.size >= MAX_ITEMS) return "print space is full ($MAX_ITEMS pictures)"
        if (mb > 0 && items.sumOf { x -> x.size } >= mb * 1024L * 1024L) return "storage limit reached ($mb MB)"
        val id = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().replace("-", "").take(4)
        val box = File(root(), id)
        if (!box.mkdirs()) return "could not create the folder"
        val dst = File(box, fname)
        val part = File(box, "$fname.part")
        try {
            val ins = open() ?: throw IOException("could not read the shared picture")
            ins.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
            if (!part.renameTo(dst)) throw IOException("could not store the copy")
        } catch (e: Exception) {
            try { box.deleteRecursively() } catch (_: Exception) {}
            return errText(e).take(120)
        }
        items.add(Item(id, fname, dst.length(), "share", System.currentTimeMillis(), 1, 0))
        save()
        return null
    }

    /** Part 9a: deletes the folders of the previous undo for good (called before a new remove / clear and when the age clean-up runs). */
    @Synchronized private fun dropUndo() {
        if (undoList.isEmpty()) return
        undoList.clear()
        try { File(root(), UNDO).deleteRecursively() } catch (_: Exception) {}
    }

    /** Part 9a: takes the picture folder out of the list but keeps it for one undo (a failed move deletes it for good: no undo for that picture). */
    private fun park(index: Int, x: Item) {
        val dst = File(File(root(), UNDO), x.id)
        try {
            dst.parentFile?.mkdirs()
            if (File(root(), x.id).renameTo(dst)) { undoList.add(Pair(index, x)); return }
        } catch (_: Exception) {}
        try { File(root(), x.id).deleteRecursively() } catch (_: Exception) {}
    }

    @Synchronized fun remove(ids: List<String>): Int {
        load()
        dropUndo()
        val gone = ids.toHashSet()
        var n = 0
        var idx = 0
        val it = items.iterator()
        while (it.hasNext()) {
            val x = it.next()
            if (x.id in gone) {
                park(idx, x)
                it.remove()
                n++
            } else idx++
        }
        if (n > 0) save()
        return n
    }

    @Synchronized fun clear(): Int {
        load()
        dropUndo()
        val n = items.size
        for ((i, x) in items.withIndex()) park(i, x)
        items.clear()
        save()
        return n
    }

    /** Part 9a: brings back what the last remove / clear took out, at the old positions. Returns how many pictures came back. */
    @Synchronized fun undo(): Int {
        load()
        var n = 0
        for ((index, x) in undoList.sortedBy { p -> p.first }) {
            if (items.size >= MAX_ITEMS) break
            val src = File(File(root(), UNDO), x.id)
            if (!src.isDirectory) continue
            val dst = File(root(), x.id)
            if (dst.exists() || !src.renameTo(dst)) continue
            items.add(index.coerceIn(0, items.size), x)
            n++
        }
        dropUndo()
        if (n > 0) save()
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

    /** Part 9a: [copies] and / or [rot] for several pictures; [rotBy] turns each picture by that many degrees from its OWN rotation. null leaves a value alone. Returns how many changed. */
    @Synchronized fun setMany(ids: List<String>, copies: Int?, rot: Int?, rotBy: Int?): Int {
        load()
        val want = ids.toHashSet()
        var n = 0
        for (x in items) {
            if (x.id !in want) continue
            if (copies != null) x.copies = clampCopies(copies)
            if (rot != null) x.rot = clampRot(rot)
            if (rotBy != null) x.rot = clampRot(x.rot + rotBy)
            n++
        }
        if (n > 0) save()
        return n
    }

    /** POST /api/ps body: {op:"add", paths:[...]} | {op:"rm", ids:[...]} | {op:"clear"} | {op:"move", id, to} | {op:"set", id | ids, copies?, rot?, rotBy?} | {op:"undo"} | {op:"cfg", days?, mb?}. Answers the new list. */
    fun post(b: JSONObject): JSONObject {
        val res = JSONObject()
        when (val op = b.optString("op")) {
            "add" -> {
                val r = add(b.getJSONArray("paths").strings(), b.optString("dev", "local").ifEmpty { "local" })
                res.put("added", r.getJSONArray("added")).put("skipped", r.getJSONArray("skipped"))
            }
            "rm" -> res.put("removed", remove(b.getJSONArray("ids").strings()))
            "clear" -> res.put("removed", clear())
            "move" -> move(b.getString("id"), b.getInt("to"))
            "cfg" -> setSettings(if (b.has("days")) b.getInt("days") else null, if (b.has("mb")) b.getInt("mb") else null)
            "undo" -> res.put("restored", undo())
            "set" -> {
                val c = if (b.has("copies")) b.getInt("copies") else null
                val r = if (b.has("rot")) b.getInt("rot") else null
                if (b.has("ids")) res.put("changed", setMany(b.getJSONArray("ids").strings(), c, r, if (b.has("rotBy")) b.getInt("rotBy") else null))
                else set(b.getString("id"), c, r)
            }
            else -> throw BadReq("unknown print space operation: $op")
        }
        val list = json()
        for (k in list.keys()) res.put(k, list.get(k))
        return res
    }
}
