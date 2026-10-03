package com.lanshare.app.core

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Photo dimensions + date taken, video/audio duration + resolution. Everything is best effort: a missing value is simply left out. */
object Media {
    fun info(f: File, name: String): JSONObject {
        val o = JSONObject()
        val mt = mimeFor(name)
        try {
            when {
                mt.startsWith("image/") && !mt.contains("svg") -> image(f, o)
                mt.startsWith("video/") -> av(f, o, true)
                mt.startsWith("audio/") -> av(f, o, false)
            }
        } catch (_: Throwable) {}
        return o
    }

    private fun image(f: File, o: JSONObject) {
        var w = 0
        var h = 0
        var rot = 0
        try {
            val ex = ExifInterface(f.path)
            w = ex.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            h = ex.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            rot = when (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> 90
                else -> 0
            }
            val taken = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            if (!taken.isNullOrBlank()) o.put("taken", fixExifDate(taken))
        } catch (_: Throwable) {}
        if (w <= 0 || h <= 0) {   // EXIF has no size (PNG, WebP, stripped JPEG): read the header only
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opt)
            w = opt.outWidth; h = opt.outHeight
        }
        if (w > 0 && h > 0) {
            if (rot == 90) { val t = w; w = h; h = t }   // report the picture the way it is shown
            o.put("w", w).put("h", h)
        }
    }

    private fun av(f: File, o: JSONObject, video: Boolean) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { if (it > 0) o.put("dur", it / 1000.0) }
            if (video) {
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                if (w > 0 && h > 0) o.put("w", w).put("h", h)
            }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d ->
                if (d.length >= 8 && d[0] == '2' || d.startsWith("19")) o.put("taken", isoDate(d))
            }
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    /** "2024:05:17 14:03:22" -> "2024-05-17 14:03:22" */
    private fun fixExifDate(s: String): String =
        if (s.length >= 10 && s[4] == ':' && s[7] == ':') s.substring(0, 4) + "-" + s.substring(5, 7) + "-" + s.substring(8).trim() else s.trim()

    /** MediaMetadataRetriever gives "20240517T140322.000Z"; shown as UTC because the file carries no zone. */
    private fun isoDate(s: String): String = try {
        val d = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substringBefore('.').removeSuffix("Z"))
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d!!)
    } catch (_: Exception) { s }
}
