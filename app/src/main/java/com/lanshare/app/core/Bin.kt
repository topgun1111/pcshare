package com.lanshare.app.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Recycle bin for this phone's storage: "<root>/.LANShare Bin/<id>/{<original name>, .meta}".
 * Same volume as the files -> moving in/out is a rename (instant, no copy). Entries older than [KEEP_DAYS] are purged at start.
 * Only deletes the user asks for go through here; internal clean-up (half-copied files, move sources) stays permanent via Endpoint.remove.
 */
object Bin {
    const val NAME = ".LANShare Bin"
    const val KEEP_DAYS = 30L
    private const val META = ".meta"

    class Entry(val id: String, val name: String, val dir: Boolean, val size: Long, val from: String, val at: Long) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("dir", dir).put("size", size).put("from", from).put("at", at)
    }

    private fun root(): File = File(Core.local.root, NAME)

    /** Paths inside the bin (or the bin itself) are never re-trashed: deleting there is permanent. */
    fun inBin(v: String): Boolean = vnorm(v).trimStart('/').let { it == NAME || it.startsWith("$NAME/") }

    fun canTrash(v: String): Boolean = arcSplit(vnorm(v)) == null && vnorm(v) != "/" && !inBin(v)

    /** Moves [v] into the bin; [progress] gets the name once. Throws when it cannot (nothing is deleted then). */
    @Synchronized fun trash(v: String, progress: ((String) -> Unit)? = null) {
        val src = Core.local.real(v)
        if (src == Core.local.root) throw Denied("cannot delete the shared root")
        val link = Core.local.isLink(src)
        if (!src.exists() && !link) throw NotFound("No such file or directory")
        val id = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().replace("-", "").take(4)
        val box = File(root(), id)
        if (!box.mkdirs()) throw IOException("could not create the recycle bin")
        val dst = File(box, src.name)
        val isDir = !link && src.isDirectory
        val size = if (!isDir) src.length() else 0L
        val rel = vnorm(v)
        try {
            File(box, META).writeText(JSONObject().put("name", src.name).put("dir", isDir).put("size", size).put("from", rel).put("at", System.currentTimeMillis()).toString())
            try { File(root(), ".nomedia").createNewFile() } catch (_: Exception) {}   // keep the gallery from indexing deleted pictures
            if (!src.renameTo(dst)) throw IOException("could not move ${src.name} to the recycle bin")
        } catch (e: Exception) { box.deleteRecursively(); throw e }
        progress?.invoke(src.name)
    }

    /** Entries (newest first). Folders starting with "." (being deleted) are skipped. Not locked: a long delete never blocks the list. */
    fun list(): List<Entry> {
        val out = ArrayList<Entry>()
        root().listFiles()?.forEach { box ->
            if (!box.isDirectory || box.name.startsWith(".")) return@forEach
            try {
                val m = JSONObject(File(box, META).readText())
                val name = m.getString("name")
                if (!File(box, name).exists() && !Core.local.isLink(File(box, name))) return@forEach
                out.add(Entry(box.name, name, m.optBoolean("dir"), m.optLong("size"), m.optString("from", "/$name"), m.optLong("at")))
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.at }
    }

    private val restoreLock = Any()

    /** Puts the item back where it came from (missing folders are recreated; a taken name becomes "name (1)"). Returns the path it ended up at. */
    fun restore(id: String): String = synchronized(restoreLock) {
        val box = entryDir(id)
        val m = JSONObject(File(box, META).readText())
        val name = m.getString("name")
        val item = File(box, name)
        if (!item.exists() && !Core.local.isLink(item)) throw NotFound("Item is gone from the recycle bin")
        val from = vnorm(m.optString("from", "/$name"))
        val parentV = vdir(from)
        val parent = Core.local.real(parentV)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("could not recreate the folder $parentV")
        var target = File(parent, name)
        var n = 1
        val dot = name.lastIndexOf('.').let { if (it <= 0 || m.optBoolean("dir")) name.length else it }
        while (target.exists() || Core.local.isLink(target)) { target = File(parent, name.substring(0, dot) + " ($n)" + name.substring(dot)); n++ }
        if (!item.renameTo(target)) throw IOException("could not restore $name")
        box.deleteRecursively()
        vjoin(parentV, target.name)
    }

    /**
     * Takes the entry out of the bin at once (an atomic rename to ".del-<id>", so it vanishes from [list] immediately)
     * and returns the folder that still has to be deleted - see [wipe]. Falls back to the entry itself when the rename fails.
     */
    private fun detach(id: String): File {
        val box = entryDir(id)
        val gone = File(root(), ".del-$id")
        return if (box.renameTo(gone)) gone else box
    }

    private fun wipe(f: File) { if (!deleteTree(f)) throw IOException("could not delete everything in ${f.name}") }

    fun purge(id: String) = wipe(detach(id))

    /** Removes the leftovers of an interrupted delete. */
    fun sweepDeleted() { try { root().listFiles()?.forEach { if (it.name.startsWith(".del-")) deleteTree(it) } } catch (_: Exception) {} }

    // ---- background task: restore / delete / empty run on their own thread, the UI polls [status] and shows the progress
    class Task(val op: String, val total: Int) {
        @Volatile var done = 0
        @Volatile var running = true
        @Volatile var cancel = false
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val paths = java.util.Collections.synchronizedList(ArrayList<String>())
        fun toJson(): JSONObject = JSONObject().put("op", op).put("total", total).put("done", done).put("running", running)
            .put("errors", org.json.JSONArray(errors.toList())).put("paths", org.json.JSONArray(paths.toList()))
    }
    @Volatile private var task: Task? = null

    fun status(): JSONObject? = task?.toJson()

    fun cancelTask() { task?.cancel = true }

    /** op = restore | purge | empty (ids ignored for empty). Throws when another bin task is still running. */
    @Synchronized fun start(op: String, ids0: List<String>): Task {
        if (task?.running == true) throw BadReq("the recycle bin is busy - wait for the current action to finish")
        if (op != "restore" && op != "purge" && op != "empty") throw BadReq("unknown bin op")
        val ids = if (op == "empty") (root().listFiles()?.filter { it.isDirectory && !it.name.startsWith(".del-") }?.map { it.name } ?: emptyList()) else ids0
        val t = Task(op, ids.size)
        task = t
        Thread({
            try {
                for (id in ids) {
                    if (t.cancel) break
                    try {
                        if (op == "restore") t.paths.add(restore(id)) else purge(id)
                    } catch (x: Exception) { if (t.errors.size < 20) t.errors.add(errText(x)) }
                    t.done++
                }
            } finally { t.running = false }
        }, "bin-$op").also { it.isDaemon = true; it.start() }
        return t
    }

    /** Start-up sweep: drops entries older than [KEEP_DAYS]. */
    fun autoPurge() {
        try {
            val cut = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            sweepDeleted()
            list().filter { it.at in 1 until cut }.forEach { try { purge(it.id) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun entryDir(id: String): File {
        if (id.isEmpty() || '/' in id || '\\' in id || id.startsWith(".")) throw BadReq("invalid id")
        return File(root(), id).also { if (!it.isDirectory) throw NotFound("Not in the recycle bin") }
    }

    private fun deleteTree(f: File): Boolean {
        if (!Core.local.isLink(f) && f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        return f.delete() || !f.exists()
    }
}
