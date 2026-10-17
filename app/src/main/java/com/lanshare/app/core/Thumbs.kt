package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** JPEG thumbnails (<= 600 px, quality 70) with an on-disk cache keyed by path + mtime + size. Port of make_thumb(). */
object Thumbs {
    private const val MAX = 600
    private const val DRAFT = 800
    private const val COVER_MAX = 1000     // album covers are shown big (2-3 columns): sharper than the 600 px list thumbnails
    private const val COVER_DRAFT = 1400
    private const val COVER_Q = 95   // every thumbnail is JPEG quality 95
    private const val PRINT_MAX = 3508
    private val writes = AtomicInteger()

    private fun cacheRoot() = File(Cfg.dir ?: File("/data/local/tmp"), "thumbcache")

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun mtimeNs(f: File): Long = f.lastModified() * 1_000_000L

    fun make(f: File): ByteArray = cached(f, MAX, DRAFT, 95, "$MAX-q95")

    /** Larger, sharper thumbnail for gallery album covers (<= 1000 px, quality 82); own cache keys, the 600 px thumbnails are untouched. */
    fun makeCover(f: File): ByteArray = cached(f, COVER_MAX, COVER_DRAFT, COVER_Q, "c$COVER_MAX-q$COVER_Q")

    /** Photo thumbnail for a cell [cellPx] wide: a 4:3 picture is centre-cropped to the square cell, so its long side must be ~1.4 x the cell width. 600 (default) / 960 / 1400 px. */
    fun tierFor(cellPx: Float): Int { val need = cellPx * 1.4f; return if (need <= 620f) MAX else if (need <= 980f) 960 else 1400 }

    /** Thumbnail with its own size tier (own cache keys); the default tier is the ordinary 600 px thumbnail. */
    fun makeSized(f: File, side: Int): ByteArray =
        if (side <= MAX) make(f) else cached(f, side, side * 4 / 3, 95, "s$side-q95")

    private fun cached(f: File, max: Int, draft: Int, quality: Int, keyTag: String): ByteArray {
        if (!f.exists()) throw NotFound("No such file or directory")
        if (f.isDirectory) throw IOException("Is a directory")
        val key = sha1("$keyTag|${f.path}|${mtimeNs(f)}|${f.length()}")
        val cp = File(File(cacheRoot(), key.substring(0, 2)), "$key.jpg")
        try { if (cp.isFile) return cp.readBytes() } catch (_: IOException) {}

        val data = if (f.name.endsWith(".pdf", true)) renderPdf(f) else render(f, max, draft, quality)
        try {
            cp.parentFile?.mkdirs()
            val tmp = File(cp.path + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(data)
            if (!tmp.renameTo(cp)) { cp.delete(); if (!tmp.renameTo(cp)) tmp.delete() }
            if (writes.incrementAndGet() % 500 == 0) prune()
        } catch (_: IOException) {}
        return data
    }

    /** Full-quality JPEG (longest side <= ~A4 at 300 dpi) for pictures the PC cannot decode itself (webp, heic ...). */
    fun forPrint(f: File): ByteArray = render(f, PRINT_MAX, PRINT_MAX, 92)

    /** Thumbnail of a temporary file (no disk cache of its own: the caller caches under its own key). */
    fun makeUncached(f: File): ByteArray = render(f)

    private const val PDF_W = 480
    private val pdfLock = Any()   // PdfRenderer is not meant for parallel use: one PDF at a time

    /** First page of a PDF as a JPEG: rendered 480 px wide on white; a tall page is cut to its top square (the heading and first lines read best in the small tile). Password-protected / broken PDFs throw (the list keeps the type icon). */
    private fun renderPdf(f: File): ByteArray = synchronized(pdfLock) {
        val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val r = try { android.graphics.pdf.PdfRenderer(pfd) } catch (e: SecurityException) { throw IOException("password protected PDF") }
            try {
                if (r.pageCount < 1) throw IOException("empty PDF")
                val pg = r.openPage(0)
                try {
                    val w = PDF_W; val h = (w.toLong() * pg.height.coerceAtLeast(1) / pg.width.coerceAtLeast(1)).toInt().coerceIn(1, 2000)
                    val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    full.eraseColor(Color.WHITE)
                    pg.render(full, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bmp = if (h > w) Bitmap.createBitmap(full, 0, 0, w, w).also { full.recycle() } else full
                    val out = ByteArrayOutputStream()
                    val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    bmp.recycle()
                    if (!ok) throw IOException("could not encode the thumbnail")
                    out.toByteArray()
                } finally { pg.close() }
            } finally { r.close() }
        } finally { try { pfd.close() } catch (_: IOException) {} }
    }

    private fun render(f: File, max: Int = MAX, draft: Int = DRAFT, quality: Int = 95): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) throw IOException("not an image")

        var sample = 1   // decode at roughly >= 800 px: far faster and lighter than full size
        while (w0 / (sample * 2) >= draft && h0 / (sample * 2) >= draft) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(f.path, opts) ?: throw IOException("cannot decode the image")

        val m = Matrix()
        try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
        } catch (_: Exception) {}   // no EXIF / unsupported format: keep as is

        val longest = maxOf(src.width, src.height)
        if (longest > max) { val s = max.toFloat() / longest; m.postScale(s, s) }
        var bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (bmp !== src) src.recycle()

        if (bmp.hasAlpha()) {   // flatten transparency on white (JPEG has no alpha)
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            bmp = flat
        }
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        if (!ok) throw IOException("could not encode the thumbnail")
        return out.toByteArray()
    }

    /** Over 30 000 cached files: drop the oldest 10 000. */
    fun prune(limit: Int = 30000, drop: Int = 10000) {
        try {
            val files = cacheRoot().walkTopDown().filter { it.isFile }.toList()
            if (files.size > limit) files.sortedBy { it.lastModified() }.take(drop).forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
