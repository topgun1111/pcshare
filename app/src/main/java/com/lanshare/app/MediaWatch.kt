package com.lanshare.app

import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File

/**
 * Gallery / Videos live updates: new, deleted, renamed or finished photos and videos show up at once, whoever made them (camera, screenshot, browser download, other app, PC over FTP).
 *  - ContentObserver on the MediaStore image + video tables: fires when ANY app (or the media scanner) changes a media row.
 *  - FileObserver (inotify) on every album folder + the usual media roots: fires the moment a file is finished on disk, BEFORE the media scanner knows it.
 *    A new / finished / moved-in media file is handed to [MediaScannerConnection] so MediaStore learns about it immediately (the album list is a MediaStore query).
 *  - Events are coalesced: at most one [onChange] per [DEBOUNCE] ms. [onChange] runs on the main thread. Only while the screen is in front (no battery use in the background).
 * Not recursive on purpose: [setDirs] gets the album folders (Albums.json feeds it), capped at [MAX_DIRS] watches.
 */
class MediaWatch(private val ctx: Context, private val onChange: () -> Unit) {
    companion object {
        /** The running watcher of the main screen (null when closed): [com.lanshare.app.core.Albums] feeds it the album folders. */
        @Volatile var cur: MediaWatch? = null

        private const val DEBOUNCE = 350L
        private const val MAX_DIRS = 150
        private const val MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.DELETE_SELF
        private val EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpg", "mpeg", "flv", "ogv", "m2ts", "mts")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val dirs = LinkedHashSet<String>()
    private val obs = HashMap<String, FileObserver>()   // strong references: a collected FileObserver stops delivering events
    private var front = false
    private var pending = false
    private val fire = Runnable { pending = false; if (front) onChange() }
    private fun kick() { if (!pending) { pending = true; ui.postDelayed(fire, DEBOUNCE) } }

    private val cr = object : ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) { kick() }
        override fun onChange(selfChange: Boolean, uri: Uri?) { kick() }
    }

    private fun isMedia(name: String?): Boolean {
        if (name == null || name.startsWith(".")) return false   // .pending-* / .trashed-* / dot files are temporary
        val e = name.substringAfterLast('.', "").lowercase()
        return e in EXT
    }

    private fun observe(path: String) {
        if (obs.containsKey(path)) return
        val d = File(path)
        if (!d.isDirectory) return
        @Suppress("DEPRECATION")
        val o = object : FileObserver(path, MASK) {
            override fun onEvent(event: Int, name: String?) {
                if ((event and FileObserver.DELETE_SELF) != 0) { ui.post { unobserve(path); kick() }; return }
                if (!isMedia(name)) return
                val f = File(path, name!!)
                // finished / appeared / moved in / removed: tell MediaStore now (a missing file is dropped from it), then redraw
                if ((event and (FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM)) != 0) {
                    try { MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(f.path), null, null) } catch (_: Throwable) { }
                    ui.post { kick() }
                }
            }
        }
        try { o.startWatching(); obs[path] = o } catch (_: Throwable) { }
    }

    private fun unobserve(path: String) { obs.remove(path)?.stopWatching() }

    /** Album folders (absolute paths) to watch; the usual media roots are always included. Safe to call from any thread. */
    fun setDirs(paths: Collection<String>, root: String) {
        ui.post {
            dirs.clear()
            val base = root.trimEnd('/')
            for (r in listOf("/DCIM", "/DCIM/Camera", "/DCIM/Screenshots", "/Pictures", "/Pictures/Screenshots", "/Movies", "/Download", "/Download/Telegram", "/Pictures/Telegram", "/Pictures/WhatsApp", "/Pictures/Messenger")) dirs.add(base + r)
            for (p in paths) { if (dirs.size >= MAX_DIRS) break; dirs.add(p) }
            obs.keys.filter { it !in dirs }.forEach { unobserve(it) }
            if (front) dirs.forEach { observe(it) }
        }
    }

    /** Activity in front: watch again and ask the page to re-check the albums once (changes made while away). */
    fun resume() {
        if (front) return
        front = true
        val r = ctx.contentResolver
        try {
            r.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, cr)
            r.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, cr)
        } catch (_: Throwable) { }
        dirs.forEach { observe(it) }
        kick()
    }

    /** Activity left the front: stop everything. */
    fun pause() {
        front = false
        try { ctx.contentResolver.unregisterContentObserver(cr) } catch (_: Throwable) { }
        obs.values.forEach { try { it.stopWatching() } catch (_: Throwable) { } }
        obs.clear()
        ui.removeCallbacks(fire); pending = false
    }
}
