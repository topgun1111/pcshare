package com.lanshare.app.core

import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Gallery albums: every folder of this phone that holds pictures (newest picture = cover); json(true) = the same for videos (Movies). MediaStore query, plain folder walk as fallback. */
object Albums {
    private class A { var n = 0; var cover = ""; var size = 0L; var mtime = 0L }
    private val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val VID = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "flv", "ogv", "m2ts", "mts")

    /** dir = path below the storage root, e.g. "/DCIM/Camera". Hidden folders, app data and the storage root itself are no albums. */
    private fun skip(dir: String): Boolean =
        dir.isEmpty() || dir.startsWith("/Android/") || dir.split('/').any { it.length > 1 && it.startsWith(".") }

    private fun walk(d: File, depth: Int, budget: IntArray, exts: Set<String>, add: (String, Long, Long) -> Unit) {
        if (depth > 6 || budget[0] <= 0) return
        val fs = d.listFiles() ?: return
        budget[0]--
        for (f in fs) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (depth == 0 && f.name == "Android") continue; walk(f, depth + 1, budget, exts, add) }
            else if (f.extension.lowercase() in exts) add(f.path, f.length(), f.lastModified() / 1000)
        }
    }

    fun json(video: Boolean = false): JSONObject {
        val rootFile = Core.local.root
        val root = rootFile.path.trimEnd('/')
        val map = LinkedHashMap<String, A>()
        // MediaStore may spell the storage root differently from the canonical path the app uses
        val roots = listOf(root, "/storage/emulated/0", "/sdcard", try { Environment.getExternalStorageDirectory().path.trimEnd('/') } catch (_: Exception) { root }).distinct()
        fun add(path: String, size: Long, mt: Long) {
            val pre = roots.firstOrNull { path.startsWith("$it/") } ?: return
            val rel = path.removePrefix(pre)
            val dir = rel.substringBeforeLast('/')
            if (skip(dir)) return
            val a = map.getOrPut(dir) { A() }
            a.n++
            if (a.cover.isEmpty() || mt > a.mtime) { a.mtime = mt; a.cover = rel; a.size = size }
        }
        try {
            val ctx = Core.appCtx
            if (ctx != null) {
                ctx.contentResolver.query(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                    null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val p = c.getString(0)
                        if (p != null) add(p, c.getLong(1), c.getLong(2))
                    }
                }
            }
        } catch (_: Exception) {}
        if (map.isEmpty()) {
            try { walk(rootFile, 0, intArrayOf(20000), if (video) VID else IMG) { p, s, m -> add(p, s, m) } } catch (_: Exception) {}
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
        return JSONObject().put("albums", arr).put("video", video)
    }
    /** Folder names (any level) that only ever hold generated / temporary pictures: thumbnails, caches, trash. */
    private val JUNK_DIRS = setOf("thumbnails", "thumbnail", "thumbs", "thumb", "cache", "caches", "tmp", "temp", "trash", "recycle", "recycler", "lost.dir", ".trash", ".thumbnails", ".cache", "thumbcache", "image_cache", "imagecache", "glide", "picasso", "stickers", "sticker", ".statuses", "cropped", "emoji", "emojis")
    /** Top-level folders of the storage that belong to the system or to apps, not to the user's pictures. */
    private val SYS_TOP = setOf("android", "data", "obb", "miui", "lost.dir", "lanshare shared", "lanshare")
    /** Pictures smaller than this (longest side, px) are thumbnails / icons, not full-sized images. */
    private const val MIN_SIDE = 500
    /** Fallback when the size in pixels is unknown (plain folder walk): files below this are thumbnails. */
    private const val MIN_BYTES = 20_000L

    /** Start-menu count: a full-sized picture the user would expect in a gallery (no thumbnails, no system / app / cache folders). */
    private fun fullSized(rel: String, w: Int, h: Int, size: Long): Boolean {
        val dir = rel.substringBeforeLast('/')
        val parts = dir.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts[0].lowercase() in SYS_TOP) return false
        if (parts.any { it.startsWith(".") || it.lowercase() in JUNK_DIRS }) return false
        val name = rel.substringAfterLast('/').lowercase()
        if (name.startsWith(".") || name.contains("thumb") || name.contains("_small") || name.startsWith("tmp_") || name.startsWith("cache_") || name.endsWith(".tmp")) return false
        return if (w > 0 && h > 0) maxOf(w, h) >= MIN_SIDE else size >= MIN_BYTES
    }

    /** Total of the full-sized pictures (or all videos) of this phone: (files, bytes). Pictures: thumbnails, system / app / cache folders are not counted; videos: same sources as [json]. */
    fun total(video: Boolean = false): Pair<Long, Long> {
        val rootFile = Core.local.root
        val root = rootFile.path.trimEnd('/')
        val roots = listOf(root, "/storage/emulated/0", "/sdcard", try { Environment.getExternalStorageDirectory().path.trimEnd('/') } catch (_: Exception) { root }).distinct()
        val seen = HashSet<String>()
        var n = 0L; var bytes = 0L
        fun add(path: String, size: Long, w: Int, h: Int) {
            val pre = roots.firstOrNull { path.startsWith("$it/") } ?: return
            val rel = path.removePrefix(pre)
            if (skip(rel.substringBeforeLast('/'))) return
            val f = File(path)
            val sz = if (size > 0) size else f.length()
            if (!video && !fullSized(rel, w, h, sz)) return
            if (!seen.add(rel)) return
            if (!f.exists()) return   // MediaStore can still list deleted files
            n++; bytes += sz
        }
        try {
            val cols = if (video) arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE)
                else arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            Core.appCtx?.contentResolver?.query(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0)
                    if (p != null) add(p, c.getLong(1), if (video) 0 else c.getInt(2), if (video) 0 else c.getInt(3))
                }
            }
        } catch (_: Exception) {}
        if (n == 0L) try { walk(rootFile, 0, intArrayOf(20000), if (video) VID else IMG) { p, sz, _ -> add(p, sz, 0, 0) } } catch (_: Exception) {}
        return n to bytes
    }
}
