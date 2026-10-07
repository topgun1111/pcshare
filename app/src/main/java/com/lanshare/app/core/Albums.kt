package com.lanshare.app.core

import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Gallery albums: every folder of this phone that holds pictures (newest picture = cover). MediaStore query, plain folder walk as fallback. */
object Albums {
    private class A { var n = 0; var cover = ""; var size = 0L; var mtime = 0L }
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

    /** dir = path below the storage root, e.g. "/DCIM/Camera". Hidden folders, app data and the storage root itself are no albums. */
    private fun skip(dir: String): Boolean =
        dir.isEmpty() || dir.startsWith("/Android/") || dir.split('/').any { it.length > 1 && it.startsWith(".") }

    private fun walk(d: File, depth: Int, budget: IntArray, add: (String, Long, Long) -> Unit) {
        if (depth > 6 || budget[0] <= 0) return
        val fs = d.listFiles() ?: return
        budget[0]--
        for (f in fs) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (depth == 0 && f.name == "Android") continue; walk(f, depth + 1, budget, add) }
            else if (f.extension.lowercase() in IMG) add(f.path, f.length(), f.lastModified() / 1000)
        }
    }

    fun json(): JSONObject {
        val rootFile = Core.local.root
        val root = rootFile.path.trimEnd('/')
        val map = LinkedHashMap<String, A>()
        fun add(path: String, size: Long, mt: Long) {
            if (!path.startsWith("$root/")) return
            val rel = path.removePrefix(root)
            val dir = rel.substringBeforeLast('/')
            if (skip(dir)) return
            val a = map.getOrPut(dir) { A() }
            a.n++
            if (a.cover.isEmpty() || mt > a.mtime) { a.mtime = mt; a.cover = rel; a.size = size }
        }
        try {
            val ctx = Core.appCtx
            if (ctx != null) {
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_MODIFIED),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val p = c.getString(0)
                        if (p != null) add(p, c.getLong(1), c.getLong(2))
                    }
                }
            }
        } catch (_: Exception) {}
        if (map.isEmpty()) {
            try { walk(rootFile, 0, intArrayOf(20000)) { p, s, m -> add(p, s, m) } } catch (_: Exception) {}
        }
        // MediaStore can still list pictures that were deleted: an album whose cover is gone is left out
        val list = map.entries.filter { File(root + it.value.cover).exists() }.sortedByDescending { it.value.mtime }.toMutableList()
        val cam = list.indexOfFirst { it.key.equals("/DCIM/Camera", true) }
        if (cam > 0) list.add(0, list.removeAt(cam))
        val arr = JSONArray()
        for (e in list) {
            val a = e.value
            arr.put(JSONObject().put("path", e.key).put("name", e.key.substringAfterLast('/'))
                .put("n", a.n).put("cover", a.cover).put("size", a.size).put("mtime", a.mtime))
        }
        return JSONObject().put("albums", arr)
    }
}
