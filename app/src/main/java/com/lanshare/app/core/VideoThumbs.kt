package com.lanshare.app.core

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Video thumbnails made natively (MediaMetadataRetriever): any endpoint (this phone, other devices, SMB, archives) via its Source. */
object VideoThumbs {
    private const val MAX = 400

    /** Random access over a [Source] for the platform's media framework. */
    private class SrcDs(private val src: Source) : MediaDataSource() {
        private var pos = 0L
        override fun getSize(): Long = src.size
        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= src.size) return -1
            if (position != pos) { src.seek(position); pos = position }
            var got = 0
            while (got < size) {
                val n = src.read(buffer, offset + got, size - got)
                if (n <= 0) break
                got += n
            }
            pos += got
            return if (got == 0) -1 else got
        }
        override fun close() { try { src.close() } catch (_: Exception) {} }
    }

    /** Longest side to make for a cell [cellPx] wide: a wide frame is centre-cropped to the square cell, so its long side must be ~1.8 x the cell width (otherwise the picture is upscaled and looks grainy). 400 / 640 / 1000 px. */
    fun tierFor(cellPx: Float): Int { val need = cellPx * 1.8f; return if (need <= 420f) MAX else if (need <= 660f) 640 else 1000 }

    private fun cacheFile(tag: String): File {
        val k = MessageDigest.getInstance("SHA-1").digest(tag.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(File(File(Cfg.dir ?: File("/data/local/tmp"), "vthumbcache"), k.substring(0, 2)), "$k.v")
    }

    /** Same as [make] with a size tier and an on-disk cache: [cacheKey] (device | path | size | mtime) + the tier name the cache file; null = no cache. */
    fun make(src: Source, maxSide: Int, cacheKey: String?): Pair<ByteArray, Long> {
        val cf = if (cacheKey != null) cacheFile("$cacheKey|$maxSide|q95") else null
        if (cf != null) try {
            if (cf.isFile) { val b = cf.readBytes(); if (b.size > 8) { val d = java.nio.ByteBuffer.wrap(b, 0, 8).long; src.close(); return b.copyOfRange(8, b.size) to d } }
        } catch (_: Exception) {}
        val r = make(src, maxSide)
        if (cf != null) try {
            cf.parentFile?.mkdirs()
            val tmp = File(cf.path + ".tmp" + Thread.currentThread().id)
            tmp.writeBytes(java.nio.ByteBuffer.allocate(8).putLong(r.second).array() + r.first)
            if (!tmp.renameTo(cf)) { cf.delete(); if (!tmp.renameTo(cf)) tmp.delete() }
        } catch (_: Exception) {}
        return r
    }

    /** JPEG bytes + duration in ms. */
    fun make(src: Source): Pair<ByteArray, Long> = make(src, MAX)

    fun make(src: Source, maxSide: Int): Pair<ByteArray, Long> {
        val MAX = maxSide.coerceIn(200, 1200)
        val quality = 95
        if (!src.seekable) { src.close(); throw IOException("video source is not seekable") }
        val r = MediaMetadataRetriever()
        val ds = SrcDs(src)
        try {
            r.setDataSource(ds)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = if (dur > 2000) minOf(dur / 10, 10_000L) * 1000L else 100_000L
            val frame = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("no video frame")
            val longest = maxOf(frame.width, frame.height)
            val bmp = if (longest > MAX) {
                val s = MAX.toFloat() / longest
                Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * s).toInt()), maxOf(1, (frame.height * s).toInt()), true)
                    .also { if (it !== frame) frame.recycle() }
            } else frame
            val out = ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            if (!ok) throw IOException("could not encode the thumbnail")
            return out.toByteArray() to dur
        } finally {
            try { r.release() } catch (_: Exception) {}
            ds.close()
        }
    }
}
