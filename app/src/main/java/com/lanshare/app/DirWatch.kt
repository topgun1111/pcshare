package com.lanshare.app

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Watches ONE folder (the one open in the file list) so files created / deleted / renamed / finished by other programs show up at once.
 *  - FileObserver (inotify): instant, event-driven. Not recursive: only the open folder is watched, which is all the list needs.
 *  - Safety net: while the screen is in front, the folder's own modified time is checked every [POLL] ms (1 stat call). Covers changes that
 *    inotify can miss (MTP / USB writes, some FUSE paths).
 * Events are coalesced: at most one [onChange] per [DEBOUNCE] ms. [onChange] runs on the main thread.
 */
class DirWatch(private val onChange: () -> Unit) {
    private companion object {
        const val DEBOUNCE = 300L
        const val POLL = 2_000L
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    }

    private val ui = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private var obs: FileObserver? = null   // strong reference: a collected FileObserver stops delivering events
    private var stamp = 0L
    private var front = true
    private var pending = false

    private val fire = Runnable { pending = false; onChange() }
    private val poll = object : Runnable {
        override fun run() {
            val d = dir
            if (d != null && front) {
                val m = d.lastModified()
                if (m != stamp) { stamp = m; kick() }
                ui.postDelayed(this, POLL)
            }
        }
    }

    private fun kick() { if (!pending) { pending = true; ui.postDelayed(fire, DEBOUNCE) } }

    /** Watch [d] (null = nothing to watch: remote device, virtual folder, archive). Safe to call from any thread. */
    @Synchronized fun watch(d: File?) {
        if (d?.path == dir?.path && obs != null) return
        halt()
        if (d == null || !d.isDirectory) return
        dir = d
        stamp = d.lastModified()
        @Suppress("DEPRECATION")
        obs = object : FileObserver(d.path, MASK) {
            override fun onEvent(event: Int, path: String?) { ui.post { kick() } }
        }.also { it.startWatching() }
        ui.post { ui.removeCallbacks(poll); if (front) ui.postDelayed(poll, POLL) }
    }

    /** Activity left the front: stop the observer and the poll (no battery use in the background). */
    @Synchronized fun pause() {
        front = false
        obs?.stopWatching(); obs = null
        ui.removeCallbacks(poll)
    }

    /** Activity is back: watch again and ask the page to re-check the folder once (changes made while away). */
    @Synchronized fun resume() {
        front = true
        val d = dir
        if (d != null && obs == null && d.isDirectory) {
            stamp = d.lastModified()
            @Suppress("DEPRECATION")
            obs = object : FileObserver(d.path, MASK) {
                override fun onEvent(event: Int, path: String?) { ui.post { kick() } }
            }.also { it.startWatching() }
            ui.removeCallbacks(poll); ui.postDelayed(poll, POLL)
        }
        if (d != null) kick()
    }

    @Synchronized fun stop() { halt() }

    private fun halt() {
        obs?.stopWatching(); obs = null; dir = null
        ui.removeCallbacks(poll); ui.removeCallbacks(fire); pending = false
    }
}
