package com.lanshare.app.core

import org.json.JSONObject
import java.io.InputStream

data class Item(val name: String, val dir: Boolean, val size: Long, val mtime: Long,
                val n: Int? = null, val path: String? = null,
                val hid: Boolean = false,   // hidden like a dot-folder: cache folders ("thumbnails", or holding a .nomedia file)
                val lb: String? = null) {   // search only: the picture label that matched ("dog") when the NAME did not
    fun toJson(): JSONObject = JSONObject().put("name", name).put("dir", dir).put("size", size).put("mtime", mtime).also {
        if (n != null) it.put("n", n)
        if (path != null) it.put("path", path)
        if (hid) it.put("hid", true)
        if (lb != null) it.put("lb", lb)
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(o.getString("name"), o.optBoolean("dir"), o.optLong("size"), o.optLong("mtime"),
            if (o.has("n")) o.optInt("n") else null, if (o.has("path")) o.optString("path") else null,
            lb = if (o.has("lb")) o.optString("lb") else null)
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
    fun search(v: String, q: String): SearchResult
    fun names(v: String): MutableSet<String>
    fun walk(v: String): List<WalkItem>
    fun open(v: String): Source
    fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)? = null)
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
