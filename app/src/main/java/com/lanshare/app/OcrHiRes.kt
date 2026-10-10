package com.lanshare.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * The ORIGINAL picture file (not the downscaled viewer bitmap) as a virtual picture of [w] x [h] px in display orientation
 * (EXIF applied, at 1/[s] of the original size when that is huge). [crop] decodes just one region of it, so small print can be
 * read at full resolution without ever holding the whole picture in memory. Not thread safe: use from one thread at a time.
 */
class HiRes private constructor(
    private val dec: BitmapRegionDecoder,
    private val orient: Matrix,      // raw -> display orientation (rotate / flip only)
    private val inv: Matrix,         // display (with offset) -> raw coordinates
    private val s: Int,
    private val rw: Int,
    private val rh: Int,
    val w: Int,
    val h: Int
) {
    /** Region (x, y, cw, ch) of the virtual picture, in display orientation. Null when the region is empty. */
    fun crop(x: Int, y: Int, cw: Int, ch: Int): Bitmap? {
        val d = RectF(x.toFloat() * s, y.toFloat() * s, (x + cw).toFloat() * s, (y + ch).toFloat() * s)
        inv.mapRect(d)
        val r = Rect(
            Math.floor(d.left.toDouble()).toInt().coerceIn(0, rw), Math.floor(d.top.toDouble()).toInt().coerceIn(0, rh),
            Math.ceil(d.right.toDouble()).toInt().coerceIn(0, rw), Math.ceil(d.bottom.toDouble()).toInt().coerceIn(0, rh)
        )
        if (r.width() < 1 || r.height() < 1) return null
        val raw = dec.decodeRegion(r, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        if (orient.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, orient, true)
        if (out !== raw) raw.recycle()
        return out
    }

    fun close() { try { dec.recycle() } catch (_: Throwable) {} }

    companion object {
        /** Null when the file cannot be region-decoded (unsupported format, unreadable ...). [maxSide] limits the virtual picture's long side. */
        fun open(f: File, maxSide: Int = 6144): HiRes? {
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(f.path, false) ?: return null
            try {
                val rw = dec.width
                val rh = dec.height
                if (rw <= 0 || rh <= 0) { dec.recycle(); return null }
                val ori = try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
                val m = Matrix()                      // same table as ImageViewerActivity.decode()
                when (ori) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
                    else -> {}
                }
                val b = RectF(0f, 0f, rw.toFloat(), rh.toFloat())
                m.mapRect(b)                          // display bounds
                val full = Matrix(m)
                full.postTranslate(-b.left, -b.top)
                val inv = Matrix()
                if (!full.invert(inv)) { dec.recycle(); return null }
                var s = 1
                val side = maxOf(b.width(), b.height()).toInt()
                while (side / s > maxSide) s *= 2
                return HiRes(dec, m, inv, s, rw, rh, maxOf(1, Math.round(b.width() / s)), maxOf(1, Math.round(b.height() / s)))
            } catch (e: Throwable) {
                try { dec.recycle() } catch (_: Throwable) {}
                return null
            }
        }
    }
}
