package com.lanshare.app.core

import org.json.JSONObject
import java.io.InputStream

data class Item(val name: String, val dir: Boolean, val size: Long, val mtime: Long,
                val n: Int? = null, val path: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("dir", dir).put("size", size).put("mtime", mtime).also {
        if (n != null) it.put("n", n)
        if (path != null) it.put("path", path)
    }

    companion object {
        fun fromJson(o: JSONObject) = Item(o.getString("name"), o.optBoolean("dir"), o.optLong("size"), o.optLong("mtime"),
            if (o.has("n")) o.optInt("n") else null, if (o.has("path")) o.optString("path") else null)
    }
}

data class WalkItem(val rel: String, val dir: Boolean, val size: Long) {
    fun toJson(): JSONObject = JSONObject().put("rel", rel).put("dir", dir).put("size", size)
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
    fun remove(v: String)
    fun rename(v: String, newName: String)
    fun move(v: String, toV: String)
}
