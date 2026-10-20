package com.lanshare.app.core

import org.json.JSONObject
import java.io.InputStream

data class Item(val name: String, val dir: Boolean, val size: Long, val mtime: Long,
                val n: Int? = null, val path: String? = null,
                val hid: Boolean = false) {   // hidden like a dot-folder: cache folders ("thumbnails", or holding a .nomedia file)
    fun toJson(): JSONObject = JSONObject().put("name", name).put("dir", dir).put("size", size).put("mtime", mtime).also {
        if (n != null) it.put("n", n)
        if (path != null) it.put("path", path)
        if (hid) it.put("hid", true)
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(o.getString("name"), o.optBoolean("dir"), o.optLong("size"), o.optLong("mtime"),
            if (o.has("n")) o.optInt("n") else null, if (o.has("path")) o.optString("path") else null, o.optBoolean("hid"))
    }
}

/** [skip] = an entry that could not be walked (link, unreadable folder): never copied, and its parent must not be deleted after a move. */
data class WalkItem(val rel: String, val dir: Boolean, val size: Long, val skip: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("rel", rel).put("dir", dir).put("size", size).also { if (skip) it.put("skip", true) }
}

class SearchResult(val items: List<Item>, val partial: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("items", jarr(items.map { it.toJson() })).put("partial", partial)
}

/** One browsable place: this device, another LANShare device, or an SMB share. Paths are virtual ('/' = root). */
interface Endpoint {
    val id: String
    val name: String
    fun ls(v: String): List<Item>
    /** Same entries without the "N items" count of every sub-folder (those counts read each folder: minutes on a big phone root). For name / size checks. */
    fun lsFast(v: String): List<Item> = ls(v)
    fun search(v: String, q: String): SearchResult
    fun names(v: String): MutableSet<String>
    fun walk(v: String): List<WalkItem>
    /** Same entries as [walk], handed over one by one while the tree is read (progress, cancel): [visit] may throw [Cancelled]. */
    fun walkEach(v: String, visit: (WalkItem) -> Unit) { for (w in walk(v)) visit(w) }
    fun open(v: String): Source
    fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)? = null)
    /** True when this place can continue an interrupted file copy ([partial] / [writeResume]). */
    val resumable: Boolean get() = false
    /**
     * Bytes of an unfinished copy of [v] that can be continued, 0 = none. [tag] identifies the version of the source file ("size:mtime"):
     * a partial of another version is never reused. Never throws for "nothing there".
     */
    fun partial(v: String, tag: String): Long = 0L
    /**
     * Like [write], but for a file of [size] bytes ([size] = the WHOLE file) of which the first [offset] bytes are already there (from [partial]).
     * [input] starts AT [offset]. A failure other than [Cancelled] keeps the part written so far so the next try can continue; a cancel removes it.
     * Places that cannot resume (default) only accept offset 0 and behave exactly like [write].
     */
    fun writeResume(v: String, input: InputStream, size: Long, offset: Long, tag: String, cb: ((Int) -> Unit)? = null) {
        if (offset != 0L) throw BadReq("this place cannot resume a copy")
        write(v, input, size, cb)
    }
    /** True when [setMtime] works here (used to keep the original modification time of copies to / from an FTP server). */
    val setsMtime: Boolean get() = false
    /** Sets the modification time (epoch seconds) of [v]; the caller ignores failures. */
    fun setMtime(v: String, sec: Long) {}
    /** Copies the file [v] to [toV] inside this place without moving its bytes through the phone (FTP: SITE CPFR / CPTO). False = not possible, the caller copies normally. */
    fun copyWithin(v: String, toV: String): Boolean = false
    fun mkdir(v: String)
    /** [progress] is called with the name of every entry that was deleted; it may throw [Cancelled] to stop. */
    fun remove(v: String, progress: ((String) -> Unit)? = null)
    fun rename(v: String, newName: String)
    fun move(v: String, toV: String)
    /** Details of one file/folder: times, flags, folder totals, media info where available. */
    fun stat(v: String): JSONObject
    /** (free, total) bytes of the drive/share that holds [v], or null when unknown. */
    fun space(v: String): Pair<Long, Long>?
}
