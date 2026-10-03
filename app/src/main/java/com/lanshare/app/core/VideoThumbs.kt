package com.lanshare.app.core

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.IOException

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

    /** JPEG bytes + duration in ms. */
    fun make(src: Source): Pair<ByteArray, Long> {
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
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            bmp.recycle()
            if (!ok) throw IOException("could not encode the thumbnail")
            return out.toByteArray() to dur
        } finally {
            try { r.release() } catch (_: Exception) {}
            ds.close()
        }
    }
}
