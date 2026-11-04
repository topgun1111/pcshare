package com.lanshare.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lanshare.app.LanShareService
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permanent page cache for big PDFs (>= [MIN_BYTES]).
 *
 * The first time such a PDF is opened, every page is rendered once at screen width and stored as a JPEG:
 *   - PDF on this phone      -> <folder of the pdf>/cache/<pdf name>/p0001.jpg ...   (+ .nomedia, meta.json)
 *   - PDF from SMB / device  -> <app files>/pdfpages/<hash>/...                      (private, size-capped)
 * Later opens read those images instead of rendering, so every page shows at once.
 *
 * The build is NOT tied to the viewer: it runs on its own thread in the process, is registered as a job in [Jobs.all]
 * (so [com.lanshare.app.LanShareService] shows it in the foreground notification with progress + cancel), holds a
 * partial wake lock, and keeps going after the viewer or the whole app screen is closed. Pages already written are
 * kept, so an interrupted build continues from where it stopped the next time the PDF is opened.
 */
object PdfPageCache {
    const val MIN_BYTES = 10L * 1024 * 1024
    private const val TAG = "PdfPageCache"
    private const val VER = 2                                // v2: older caches may contain blank / empty pages -> rebuilt once
    private const val JPEG_Q = 90
    private const val PRIVATE_CAP = 2L * 1024 * 1024 * 1024
    private const val PER_PAGE_EST = 450L * 1024            // free-space guard only

    /** Number of open PDF viewers: while > 0 the builder pauses briefly between pages so scrolling stays smooth. */
    val viewers = AtomicInteger(0)

    /** Uptime (ms) of the viewer's last live render: the builder waits while the reader is waiting for pages. */
    @Volatile var lastUse = 0L

    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-cache").also { it.isDaemon = true } }

    fun pageFile(dir: File, pos: Int) = File(dir, String.format(java.util.Locale.ROOT, "p%04d.jpg", pos + 1))

    // ------------------------------------------------------------------ viewer side

    /**
     * Called by the viewer once a big PDF is open. Picks the cache folder, drops it when the PDF changed, queues the build when
     * pages are missing and returns the folder the viewer can read finished pages from (null = no caching for this file).
     * [local] = the real file on this phone (cache goes next to it); null = a downloaded copy (private cache).
     */
    fun attach(ctx: Context, pdf: File, local: File?, name: String, key: String, cw: Int, maxW: Int, maxPx: Int): File? {
        return try {
            if (pdf.length() < MIN_BYTES) return null
            val app = ctx.applicationContext
            val sig = if (local != null) "${local.length()}:${local.lastModified()}" else key
            val dir = chooseDir(app, local, name, key) ?: return null
            val old = readMeta(dir)
            val meta: JSONObject
            if (old == null || old.optString("sig") != sig || old.optInt("v", 0) != VER) {        // new or changed PDF: start clean
                dir.listFiles()?.forEach { if (it.name.startsWith("p") || it.name == "meta.json") it.delete() }
                meta = JSONObject()
                meta.put("v", VER); meta.put("sig", sig); meta.put("cw", cw); meta.put("n", 0); meta.put("complete", false)
                writeMeta(dir, meta)
            } else meta = old
            if (!(meta.optBoolean("complete", false) && meta.has("pw"))) enqueue(app, pdf, dir, name, meta.optInt("cw", cw), maxW, maxPx)
            dir
        } catch (e: Throwable) { Log.w(TAG, "attach failed: $e"); null }
    }

    /** A finished cache: folder + page count + real page sizes (points), enough to show the document without opening the PDF. */
    class Hit(val dir: File, val n: Int, val pw: FloatArray, val ph: FloatArray)

    /**
     * Cache-first open: when every page of this PDF is already cached (and the PDF is unchanged) returns the data the viewer needs to
     * show all pages at once WITHOUT opening the PDF with PdfRenderer (opening a big PDF is the slow part). Null = no finished cache.
     * [srcLen] = size of the PDF (local: real length, remote: size from the list).
     */
    fun lookup(ctx: Context, local: File?, name: String, key: String, srcLen: Long): Hit? {
        return try {
            if (srcLen < MIN_BYTES) return null
            val app = ctx.applicationContext
            val sig = if (local != null) "${local.length()}:${local.lastModified()}" else key
            val dir = chooseDir(app, local, name, key) ?: return null
            val m = readMeta(dir) ?: return null
            if (m.optString("sig") != sig || m.optInt("v", 0) != VER || !m.optBoolean("complete", false)) return null
            val n = m.optInt("n", 0)
            val a = m.optJSONArray("pw") ?: return null
            val b = m.optJSONArray("ph") ?: return null
            if (n <= 0 || a.length() != n || b.length() != n) return null
            val pw = FloatArray(n) { a.optDouble(it, 595.0).toFloat() }
            val ph = FloatArray(n) { b.optDouble(it, 842.0).toFloat() }
            for (i in 0 until n) if (!pageFile(dir, i).let { it.isFile && it.length() > 0 }) return null      // a page was deleted: not finished
            Hit(dir, n, pw, ph)
        } catch (_: Throwable) { null }
    }

    /** Reads a cached page scaled to [w] x [h]; null when it is not cached or the viewer needs more pixels than were cached (zoom). */
    fun read(dir: File, pos: Int, w: Int, h: Int, upscale: Boolean = false): Bitmap? {
        val f = pageFile(dir, pos)
        if (!f.isFile) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val cw0 = o.outWidth
            if (cw0 <= 0 || (cw0 < w - 2 && !upscale)) return null      // upscale = normal reading size: a narrower cached page (cached on another screen width) is still better than a blank page
            var ss = 1
            while (cw0 / (ss * 2) >= w) ss *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = ss; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var bm = BitmapFactory.decodeFile(f.path, o2) ?: return null
            if (bm.width != w || bm.height != h) {
                val s = Bitmap.createScaledBitmap(bm, w, h, true)
                if (s !== bm) bm.recycle()
                bm = s
            }
            bm
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------ folders + meta

    private fun baseName(name: String) = name.replace(Regex("(?i)\\.pdf$"), "").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "document" }

    private fun chooseDir(app: Context, local: File?, name: String, key: String): File? {
        if (local != null) {
            val d = File(File(local.parentFile ?: return privDir(app, key), "cache"), baseName(name))
            try {
                if (d.isDirectory || d.mkdirs()) {
                    val probe = File(d, ".nomedia")                       // keeps the gallery from indexing the page images
                    if (probe.exists() || probe.createNewFile()) return d
                }
            } catch (_: Throwable) { }
            // folder not writable (read-only share / USB): fall back to the app's private storage
        }
        return privDir(app, key)
    }

    private fun privDir(app: Context, key: String): File? {
        val root = File(app.filesDir, "pdfpages")
        val d = File(root, java.lang.Long.toHexString(key.hashCode().toLong() and 0xffffffffL) + "_" + java.lang.Long.toHexString(key.length.toLong()))
        if (!d.isDirectory && !d.mkdirs()) return null
        File(d, ".nomedia").let { try { it.createNewFile() } catch (_: Throwable) { } }
        d.setLastModified(System.currentTimeMillis())
        trimPrivate(root, d)
        return d
    }

    private fun trimPrivate(root: File, keep: File) {
        try {
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
            fun size(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L
            var sum = dirs.sumOf { size(it) }
            for (d in dirs.sortedBy { it.lastModified() }) {
                if (sum <= PRIVATE_CAP) break
                if (d == keep) continue
                sum -= size(d); d.deleteRecursively()
            }
        } catch (_: Throwable) { }
    }

    private fun readMeta(dir: File): JSONObject? = try { JSONObject(File(dir, "meta.json").readText()) } catch (_: Throwable) { null }

    private fun writeMeta(dir: File, m: JSONObject) {
        val tmp = File(dir, "meta.tmp")
        tmp.writeText(m.toString())
        val dst = File(dir, "meta.json")
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
    }

    // ------------------------------------------------------------------ background build

    private fun enqueue(app: Context, pdf: File, dir: File, name: String, cw: Int, maxW: Int, maxPx: Int) {
        if (!queued.add(dir.path)) return                                  // already building / waiting
        val jid = "pc" + java.lang.Long.toHexString(dir.path.hashCode().toLong() and 0xffffffffL)
        val job = Job("Caching PDF: $name")
        job.bytes = false
        job.total = 0L                                                       // indeterminate until the page count is known
        Jobs.all[jid] = job
        try { ContextCompat.startForegroundService(app, Intent(app, LanShareService::class.java)) } catch (_: Throwable) { }   // makes sure the notification service is up
        pool.execute {
            try { build(app, pdf, dir, name, cw, maxW, maxPx, job) }
            finally { queued.remove(dir.path); if (job.end == 0L) job.end = System.currentTimeMillis() }
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

    private fun renderTo(r: PdfRenderer, i: Int, w: Int, h: Int): Bitmap {
        val pg = r.openPage(i)
        try {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bm
        } finally { pg.close() }
    }

    /** True when (a 200x200 sample of) the bitmap is pure white. */
    private fun isBlank(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        for (yy in 0 until 200) { val y = (yy * (h - 1)) / 199
            for (xx in 0 until 200) if (b.getPixel((xx * (w - 1)) / 199, y) != Color.WHITE) return false }
        return true
    }

    private fun build(app: Context, pdf: File, dir: File, name: String, cw: Int, maxW: Int, maxPx: Int, job: Job) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) { }
        val wl = try {
            app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lanshare:pdfcache")
                .apply { setReferenceCounted(false); acquire(3L * 3600 * 1000) }
        } catch (_: Throwable) { null }
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)   // own handle: survives the viewer (and a trimmed download copy)
            r = PdfRenderer(pfd)
            val n = r.pageCount
            if (n <= 0) throw java.io.IOException("no pages")
            if (dir.usableSpace < n * PER_PAGE_EST + 50L * 1024 * 1024) throw java.io.IOException("Not enough free space for the page cache")
            job.total = n.toLong()
            readMeta(dir)?.let { writeMeta(dir, it.put("n", n)) }
            var failed = 0
            val sw = FloatArray(n)
            val sh = FloatArray(n)
            for (i in 0 until n) {
                if (job.cancel) { job.state = "cancel"; return }
                val dst = pageFile(dir, i)
                var bm: Bitmap? = null
                try {
                    val pg = r.openPage(i)
                    try {
                        sw[i] = pg.width.toFloat(); sh[i] = pg.height.toFloat()                              // real page size (kept in meta.json for the instant open)
                        if (dst.isFile && dst.length() > 0) { job.done = (i + 1).toLong(); continue }        // resume: page already there
                        val (w, h) = sizeFor(pg.width, pg.height, cw, maxW, maxPx)
                        bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bm.eraseColor(Color.WHITE)
                        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally { pg.close() }
                    var b = bm ?: throw java.io.IOException("no bitmap")
                    if (isBlank(b)) {                                    // PdfRenderer can silently return a white page for heavy pages: retry smaller
                        try {
                            val s = renderTo(r, i, maxOf(b.width / 2, 1), maxOf(b.height / 2, 1))
                            if (!isBlank(s)) { b.recycle(); b = s; bm = s } else s.recycle()
                        } catch (_: Throwable) { }
                    }
                    val tmp = File(dir, String.format(java.util.Locale.ROOT, "p%04d.tmp", i + 1))
                    val okJ = FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, it) }
                    if (!okJ) { tmp.delete(); throw java.io.IOException("jpeg failed") }
                    if (!tmp.renameTo(dst)) { tmp.delete(); failed++ }
                } catch (e: Throwable) {                                                                 // one bad page must not stop the rest
                    failed++
                    Log.w(TAG, "page ${i + 1} not cached: $e")
                } finally { try { bm?.recycle() } catch (_: Throwable) { } }
                job.done = (i + 1).toLong()
                job.label = "Caching PDF: $name  ${i + 1}/$n"
                var wait = 0                                                                                       // PDFium is process-wide locked: stay out of the way while the reader waits for a live render
                while (viewers.get() > 0 && wait < 25 && !job.cancel && android.os.SystemClock.uptimeMillis() - lastUse < 1500) { try { Thread.sleep(200) } catch (_: InterruptedException) { break }; wait++ }
                if (viewers.get() > 0) try { Thread.sleep(150) } catch (_: InterruptedException) { }
            }
            if (failed == 0) readMeta(dir)?.let {
                val ja = org.json.JSONArray(); val jb = org.json.JSONArray()
                for (i in 0 until n) { ja.put(sw[i].toDouble()); jb.put(sh[i].toDouble()) }
                it.put("pw", ja); it.put("ph", jb); it.put("complete", true)
                writeMeta(dir, it)
            }
            job.label = "PDF ready: $name"
            job.state = "done"
        } catch (e: Throwable) {
            Log.w(TAG, "build failed: $e")
            job.error = e.message ?: "Cannot cache this PDF"
            job.state = "error"
        } finally {
            try { r?.close() } catch (_: Throwable) { }
            try { pfd?.close() } catch (_: Throwable) { }
            try { if (wl?.isHeld == true) wl.release() } catch (_: Throwable) { }
            job.end = System.currentTimeMillis()
        }
    }
}
