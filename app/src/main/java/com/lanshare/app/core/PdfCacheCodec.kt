package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Image side of the PDF page cache (format v3): lossy WebP pages + small WebP thumbnails.
 * WebP lossy is ~30-40% smaller than the old JPEG at the same sharpness for text pages -> less disk I/O per page, so pages appear faster.
 * Pages may be decoded as RGB_565 ([decode] `fast = true`): half the memory and upload time, no visible change on text pages.
 */
object PdfCacheCodec {
    const val EXT = "webp"
    const val PAGE_Q = 78
    const val THUMB_Q = 65
    const val THUMB_W = 240
    /** Cached page width = screen width x this: the pages are sharp up to ~1.5x zoom without any live render. */
    const val ZOOM = 1.5f

    @Suppress("DEPRECATION")
    private fun fmt(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Writes [b] to [dst] atomically (tmp file + rename). False = nothing written. */
    fun write(b: Bitmap, dst: File, q: Int): Boolean {
        val tmp = File(dst.parentFile, dst.name + ".tmp")
        return try {
            val ok = FileOutputStream(tmp).use { b.compress(fmt(), q, it) }
            if (!ok || tmp.length() <= 0L) { tmp.delete(); false }
            else if (tmp.renameTo(dst)) true
            else { dst.delete(); if (tmp.renameTo(dst)) true else { tmp.delete(); false } }
        } catch (_: Throwable) { try { tmp.delete() } catch (_: Throwable) { }; false }
    }

    /** Thumbnail copy of a page ([THUMB_W] px wide at most). */
    fun thumb(b: Bitmap): Bitmap {
        val w = minOf(THUMB_W, b.width).coerceAtLeast(1)
        val h = maxOf(1, (b.height.toLong() * w / maxOf(b.width, 1)).toInt())
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** Pixel size of an image file without decoding it; null when unreadable. */
    fun size(f: File): IntArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
    }

    /**
     * Decodes [f] scaled to [w] x [h]. Null when the file is missing / broken, or when it is narrower than [w] and [upscale] is false
     * (zoom: the caller wants more pixels than were cached). The decoder subsamples by powers of two first, then scales exactly.
     */
    fun decode(f: File, w: Int, h: Int, upscale: Boolean, fast: Boolean = false): Bitmap? {
        if (!f.isFile) return null
        return try {
            val sz = size(f) ?: return null
            val cw0 = sz[0]
            if (cw0 < w - 2 && !upscale) return null
            var ss = 1
            while (w > 0 && cw0 / (ss * 2) >= w) ss *= 2
            val o = BitmapFactory.Options().apply {
                inSampleSize = ss
                inPreferredConfig = if (fast) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            var bm = BitmapFactory.decodeFile(f.path, o) ?: return null
            if (w > 0 && h > 0 && (bm.width != w || bm.height != h)) {
                val s = Bitmap.createScaledBitmap(bm, w, h, true)
                if (s !== bm) bm.recycle()
                bm = s
            }
            bm
        } catch (_: Throwable) { null }
    }

    /** Decodes [f] at its own size (thumbnails). */
    fun decodeRaw(f: File, fast: Boolean = true): Bitmap? {
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
                inPreferredConfig = if (fast) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            })
        } catch (_: Throwable) { null }
    }
}
