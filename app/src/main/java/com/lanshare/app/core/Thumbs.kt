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

/** JPEG thumbnails (<= 400 px, quality 70) with an on-disk cache keyed by path + mtime + size. Port of make_thumb(). */
object Thumbs {
    private const val MAX = 400
    private const val DRAFT = 800
    private val writes = AtomicInteger()

    private fun cacheRoot() = File(Cfg.dir ?: File("/data/local/tmp"), "thumbcache")

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun mtimeNs(f: File): Long = f.lastModified() * 1_000_000L

    fun make(f: File): ByteArray {
        if (!f.exists()) throw NotFound("No such file or directory")
        if (f.isDirectory) throw IOException("Is a directory")
        val key = sha1("$MAX|${f.path}|${mtimeNs(f)}|${f.length()}")
        val cp = File(File(cacheRoot(), key.substring(0, 2)), "$key.jpg")
        try { if (cp.isFile) return cp.readBytes() } catch (_: IOException) {}

        val data = render(f)
        try {
            cp.parentFile?.mkdirs()
            val tmp = File(cp.path + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(data)
            if (!tmp.renameTo(cp)) { cp.delete(); if (!tmp.renameTo(cp)) tmp.delete() }
            if (writes.incrementAndGet() % 500 == 0) prune()
        } catch (_: IOException) {}
        return data
    }

    private fun render(f: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) throw IOException("not an image")

        var sample = 1   // decode at roughly >= 800 px: far faster and lighter than full size
        while (w0 / (sample * 2) >= DRAFT && h0 / (sample * 2) >= DRAFT) sample *= 2
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
        if (longest > MAX) { val s = MAX.toFloat() / longest; m.postScale(s, s) }
        var bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (bmp !== src) src.recycle()

        if (bmp.hasAlpha()) {   // flatten transparency on white (JPEG has no alpha)
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            bmp.recycle()
            bmp = flat
        }
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
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
