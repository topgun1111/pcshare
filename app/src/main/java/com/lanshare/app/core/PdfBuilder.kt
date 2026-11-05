package com.lanshare.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import java.io.File

/**
 * The page-cache build itself (renders every page with PdfRenderer, writes WebP pages + thumbnails, updates meta.json).
 * Knows nothing about Jobs / viewers / notifications: it reports through [Sink]. Normally runs inside the `:pdfbuild`
 * process ([PdfBuildService]); [PdfBuildClient] runs it in the app process only when that service cannot be bound.
 */
object PdfBuilder {
    private const val TAG = "PdfBuilder"
    private const val PER_PAGE_EST = 200L * 1024            // free-space guard only

    interface Sink {
        fun total(n: Int)
        /** About to render page [i] (the client remembers it: if the process dies now, this page is the suspect). */
        fun cur(i: Int)
        fun page(i: Int, done: Int)
        fun finished(complete: Boolean)
        fun error(msg: String)
    }

    private val lock = Any()                                  // one render at a time in the process

    fun run(app: Context, pdf: File, dir: File, cw: Int, maxW: Int, maxPx: Int, q: Int, s: Sink, cancel: () -> Boolean, focus: () -> Int) {
        // DEFAULT, not BACKGROUND: the background cgroup gets only a few % of CPU once the app is minimized, which made the build crawl
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) } catch (_: Throwable) { }
        val wl = try {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanshare:pdfcache")
                .apply { setReferenceCounted(false); acquire(3L * 3600 * 1000) }
        } catch (_: Throwable) { null }
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        var complete = false
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val rr = PdfRenderer(pfd)
            r = rr
            val n = rr.pageCount
            if (n <= 0) throw java.io.IOException("no pages")
            if (dir.usableSpace < n * PER_PAGE_EST + 50L * 1024 * 1024) throw java.io.IOException("Not enough free space for the page cache")
            s.total(n)
            PdfPageCache.readMeta(dir)?.let { PdfPageCache.writeMeta(dir, it.put("n", n)) }

            // 1) page sizes first (cheap), written to meta.json at once
            var sw = FloatArray(n); var sh = FloatArray(n)
            val old = PdfPageCache.info(dir)
            if (old?.pw != null && old.ph != null && old.n == n) { sw = old.pw; sh = old.ph }
            else {
                for (i in 0 until n) {
                    if (cancel()) { s.finished(false); return }
                    synchronized(lock) { val pg = rr.openPage(i); try { sw[i] = pg.width.toFloat(); sh[i] = pg.height.toFloat() } finally { pg.close() } }
                }
                PdfPageCache.readMeta(dir)?.let {
                    val ja = org.json.JSONArray(); val jb = org.json.JSONArray()
                    for (i in 0 until n) { ja.put(sw[i].toDouble()); jb.put(sh[i].toDouble()) }
                    PdfPageCache.writeMeta(dir, it.put("pw", ja).put("ph", jb))
                }
            }

            // 2) pages, closest to the reader first; pages already in `bl` (given up, e.g. crashed the process twice) are skipped
            val skip: Set<Int> = old?.bl ?: PdfPageCache.info(dir)?.bl ?: emptySet()
            val todo = BooleanArray(n) { it !in skip && !PdfPageCache.pageFile(dir, it).let { f -> f.isFile && f.length() > 0 } }
            var doneCount = n - todo.count { it } - skip.size.coerceAtMost(n)
            doneCount = maxOf(doneCount, 0)
            val blanks = java.util.TreeSet<Int>()
            val failed = java.util.TreeSet<Int>()
            var round = 0
            while (true) {
                while (true) {
                    if (cancel()) { s.finished(false); return }
                    val i = pickNext(todo, focus())
                    if (i < 0) break
                    todo[i] = false
                    s.cur(i)
                    try {
                        val b = renderPage(rr, i, sw[i].toInt(), sh[i].toInt(), cw, maxW, maxPx)
                        if (b == null) blanks.add(i)                                                       // still white: not stored
                        else try {
                            blanks.remove(i)
                            if (!PdfCacheCodec.write(b, PdfPageCache.pageFile(dir, i), q)) throw java.io.IOException("page not written")
                            val t = try { PdfCacheCodec.thumb(b) } catch (_: Throwable) { null }
                            if (t != null) { try { PdfCacheCodec.write(t, PdfPageCache.thumbFile(dir, i), PdfCacheCodec.THUMB_Q) } finally { if (t !== b) t.recycle() } }
                        } finally { try { b.recycle() } catch (_: Throwable) { } }
                    } catch (e: Throwable) {
                        failed.add(i)
                        Log.w(TAG, "page ${i + 1} not cached: $e")
                    }
                    doneCount++
                    s.page(i, minOf(doneCount, n))
                }
                if (failed.isEmpty() || round >= 1) break
                round++                                                                                    // one more try for pages that threw
                try { Thread.sleep(500) } catch (_: InterruptedException) { }
                for (i in failed) todo[i] = true
                failed.clear()
            }
            complete = failed.isEmpty()
            PdfPageCache.readMeta(dir)?.let {
                val jb = org.json.JSONArray(); for (i in blanks + skip) jb.put(i)
                it.put("bl", jb); it.put("complete", complete)
                if (complete) { val ja = org.json.JSONArray(); val jh = org.json.JSONArray(); for (i in 0 until n) { ja.put(sw[i].toDouble()); jh.put(sh[i].toDouble()) }; it.put("pw", ja); it.put("ph", jh) }
                PdfPageCache.writeMeta(dir, it)
            }
            s.finished(complete)
        } catch (e: Throwable) {
            Log.w(TAG, "build failed: $e")
            s.error(e.message ?: "Cannot cache this PDF")
        } finally {
            try { r?.close() } catch (_: Throwable) { }
            try { pfd?.close() } catch (_: Throwable) { }
            try { if (wl?.isHeld == true) wl.release() } catch (_: Throwable) { }
        }
    }

    private fun sizeFor(pw: Int, ph: Int, tw: Int, maxW: Int, maxPx: Int): Pair<Int, Int> {
        var ratio = if (pw > 0) ph.toFloat() / pw else 0f
        if (!ratio.isFinite() || ratio <= 0f) ratio = 1.4142f
        var w = minOf(tw, maxW).toFloat()
        var h = w * ratio
        if (h > 4096f) { w *= 4096f / h; h = 4096f }                          // same GPU texture limit as the viewer
        if (w * h > maxPx) { val f = Math.sqrt((maxPx / (w * h)).toDouble()).toFloat(); w *= f; h *= f }
        return maxOf(w.toInt(), 1) to maxOf(h.toInt(), 1)
    }

    private fun renderTo(r: PdfRenderer, i: Int, w: Int, h: Int): Bitmap = synchronized(lock) {
        val pg = r.openPage(i)
        try {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bm
        } finally { pg.close() }
    }

    fun isBlank(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return true
        val row = IntArray(w)
        val rows = minOf(h, 256)
        for (k in 0 until rows) {
            val y = if (rows == 1) 0 else (k.toLong() * (h - 1) / (rows - 1)).toInt()
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) if (p != Color.WHITE) return false
        }
        return true
    }

    private fun pickNext(todo: BooleanArray, focus: Int): Int {
        val n = todo.size
        if (n == 0) return -1
        val f = focus.coerceIn(0, n - 1)
        var d = 0
        while (d < n) {
            val a = f + d
            if (a < n && todo[a]) return a
            val b = f - d
            if (d > 0 && b >= 0 && todo[b]) return b
            d++
        }
        return -1
    }

    /** Renders page [i] white-checked: full size, then 1/2, 1/3 after a pause (frees native memory). Null = the page stayed white. Caller recycles. */
    private fun renderPage(r: PdfRenderer, i: Int, pwPt: Int, phPt: Int, cw: Int, maxW: Int, maxPx: Int): Bitmap? {
        val (w, h) = sizeFor(pwPt, phPt, cw, maxW, maxPx)
        val b = renderTo(r, i, w, h)
        if (!isBlank(b)) return b
        for (k in 0 until 3) {
            try {
                Thread.sleep(250L)
                val d = k + 1
                val s = renderTo(r, i, maxOf(w / d, 1), maxOf(h / d, 1))
                if (!isBlank(s)) { b.recycle(); return s }
                s.recycle()
            } catch (_: Throwable) { }
        }
        b.recycle()
        return null
    }
}
