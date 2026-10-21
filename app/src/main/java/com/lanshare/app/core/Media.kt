package com.lanshare.app.core

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
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
                if (d.length >= 8 && (d.startsWith("19") || d.startsWith("20"))) o.put("taken", isoDate(d))
            }
            try { details(f, r, o) } catch (_: Throwable) {}   // MediaInfo-style extras: never let them break the basic info above
        } finally {
            try { r.release() } catch (_: Throwable) {}
        }
    }

    // ---- MediaInfo-style details (container, tracks, tags): built-in MediaExtractor + MediaMetadataRetriever, no extra library ----

    /** Adds "container" {mime, fmt, bitrate}, "tracks" [{kind, mime, codec, ...}] and "tags" {title, artist, ...}. Every value is optional. */
    private fun details(f: File, r: MediaMetadataRetriever, o: JSONObject) {
        val c = JSONObject()
        val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        if (!mime.isNullOrBlank()) c.put("mime", mime).put("fmt", containerName(mime))
        var br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
        if (br <= 0 && o.optDouble("dur", 0.0) > 0) br = (f.length() * 8 / o.optDouble("dur", 1.0)).toLong()   // average from size / duration
        if (br > 0) c.put("bitrate", br)
        if (c.length() > 0) o.put("container", c)

        val tags = JSONObject()
        fun tag(key: String, id: Int) { r.extractMetadata(id)?.trim()?.takeIf { it.isNotEmpty() }?.let { tags.put(key, it) } }
        tag("title", MediaMetadataRetriever.METADATA_KEY_TITLE)
        tag("artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
        tag("albumartist", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        tag("album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
        tag("track", MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
        tag("genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
        tag("year", MediaMetadataRetriever.METADATA_KEY_YEAR)
        tag("composer", MediaMetadataRetriever.METADATA_KEY_COMPOSER)
        tag("author", MediaMetadataRetriever.METADATA_KEY_AUTHOR)
        if (tags.length() > 0) o.put("tags", tags)

        val capFps = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
        val tracks = tracks(f, capFps)
        if (tracks.length() > 0) o.put("tracks", tracks)
    }

    private fun tracks(f: File, capFps: Double): JSONArray {
        val out = JSONArray()
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            for (i in 0 until ex.trackCount) {
                val fm = ex.getTrackFormat(i)
                val mime = if (fm.containsKey(MediaFormat.KEY_MIME)) fm.getString(MediaFormat.KEY_MIME) ?: "" else ""
                val kind = when {
                    mime.startsWith("video/") -> "video"
                    mime.startsWith("audio/") -> "audio"
                    mime.startsWith("text/") || mime.contains("subrip") || mime.contains("subtitle") || mime.contains("cea-") || mime.contains("ttml") -> "text"
                    else -> "other"
                }
                val t = JSONObject().put("kind", kind).put("mime", mime).put("codec", codecName(mime))
                num(fm, MediaFormat.KEY_BIT_RATE)?.let { if (it > 0) t.put("bitrate", it.toLong()) }
                lang(fm)?.let { t.put("lang", it) }
                if (kind == "video") {
                    val w = num(fm, MediaFormat.KEY_WIDTH)?.toInt() ?: 0
                    val h = num(fm, MediaFormat.KEY_HEIGHT)?.toInt() ?: 0
                    if (w > 0 && h > 0) t.put("w", w).put("h", h)
                    var fps = num(fm, MediaFormat.KEY_FRAME_RATE) ?: 0.0
                    if (fps <= 0 && capFps > 0) fps = capFps
                    if (fps > 0) t.put("fps", Math.round(fps * 1000) / 1000.0)
                    num(fm, MediaFormat.KEY_PROFILE)?.toInt()?.let { p -> profileName(mime, p)?.let { t.put("profile", it) } }
                    num(fm, "color-transfer")?.toInt()?.let { tr ->   // MediaFormat.KEY_COLOR_TRANSFER (API 24): 6 = PQ (HDR10), 7 = HLG
                        if (tr == 6) t.put("hdr", "HDR10 / PQ") else if (tr == 7) t.put("hdr", "HLG")
                    }
                } else if (kind == "audio") {
                    num(fm, MediaFormat.KEY_SAMPLE_RATE)?.let { if (it > 0) t.put("rate", it.toInt()) }
                    num(fm, MediaFormat.KEY_CHANNEL_COUNT)?.let { if (it > 0) t.put("ch", it.toInt()) }
                    if (mime == "audio/mp4a-latm") num(fm, MediaFormat.KEY_AAC_PROFILE)?.toInt()?.let { p -> aacProfile(p)?.let { t.put("profile", it) } }
                }
                out.put(t)
            }
        } catch (_: Throwable) {
        } finally {
            try { ex.release() } catch (_: Throwable) {}
        }
        return out
    }

    /** MediaFormat numbers are Integer or Float depending on the container: read either. */
    private fun num(fm: MediaFormat, key: String): Double? {
        if (!fm.containsKey(key)) return null
        try { return fm.getInteger(key).toDouble() } catch (_: Throwable) {}
        try { return fm.getFloat(key).toDouble() } catch (_: Throwable) {}
        try { return fm.getLong(key).toDouble() } catch (_: Throwable) {}
        return null
    }

    private fun lang(fm: MediaFormat): String? {
        if (!fm.containsKey(MediaFormat.KEY_LANGUAGE)) return null
        val l = try { fm.getString(MediaFormat.KEY_LANGUAGE) } catch (_: Throwable) { null } ?: return null
        return l.trim().takeIf { it.isNotEmpty() && !it.equals("und", true) }
    }

    private fun containerName(m: String): String = when (m.lowercase(Locale.US)) {
        "video/mp4", "audio/mp4" -> "MPEG-4"
        "video/quicktime" -> "QuickTime"
        "video/3gpp", "audio/3gpp" -> "3GPP"
        "video/x-matroska", "audio/x-matroska" -> "Matroska"
        "video/webm", "audio/webm" -> "WebM"
        "video/mp2ts" -> "MPEG-TS"
        "video/mpeg" -> "MPEG-PS"
        "video/avi", "video/x-msvideo" -> "AVI"
        "audio/mpeg" -> "MP3"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/ogg", "application/ogg" -> "Ogg"
        "audio/x-wav", "audio/wav" -> "WAV"
        "audio/aac", "audio/aacp" -> "AAC (ADTS)"
        "audio/amr" -> "AMR"
        else -> m
    }

    private fun codecName(m: String): String = when (m.lowercase(Locale.US)) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4 Visual"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/mpeg-l2" -> "MP2"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/eac3-joc" -> "E-AC-3 JOC (Atmos)"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"
        "audio/alac" -> "ALAC"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/true-hd" -> "Dolby TrueHD"
        "text/vtt" -> "WebVTT"
        "application/x-subrip" -> "SubRip (SRT)"
        "text/x-ssa" -> "SSA / ASS"
        "application/x-quicktime-tx3g", "text/3gpp-tt" -> "3GPP timed text"
        "" -> ""
        else -> m.substringAfter('/')
    }

    /** MediaCodecInfo.CodecProfileLevel constants for the two codecs where the profile is worth showing. */
    private fun profileName(mime: String, p: Int): String? = when (mime) {
        "video/avc" -> when (p) { 1 -> "Baseline"; 2 -> "Main"; 4 -> "Extended"; 8 -> "High"; 16 -> "High 10"; 32 -> "High 4:2:2"; 64 -> "High 4:4:4"; 65536 -> "Constrained Baseline"; 524288 -> "Constrained High"; else -> null }
        "video/hevc" -> when (p) { 1 -> "Main"; 2 -> "Main 10"; 4 -> "Main Still"; 4096 -> "Main 10 HDR10"; 8192 -> "Main 10 HDR10+"; else -> null }
        "video/x-vnd.on2.vp9" -> when (p) { 1 -> "Profile 0"; 2 -> "Profile 1"; 4 -> "Profile 2"; 8 -> "Profile 3"; 4096 -> "Profile 2 HDR"; else -> null }
        "video/av01" -> when (p) { 1 -> "Main 8-bit"; 2 -> "Main 10-bit"; 4096 -> "Main 10 HDR10"; else -> null }
        else -> null
    }

    private fun aacProfile(p: Int): String? = when (p) { 2 -> "LC"; 5 -> "HE"; 29 -> "HE-v2"; 17 -> "ER LC"; 23 -> "LD"; 39 -> "ELD"; 1 -> "Main"; else -> null }

    /** "2024:05:17 14:03:22" -> "2024-05-17 14:03:22" */
    private fun fixExifDate(s: String): String =
        if (s.length >= 10 && s[4] == ':' && s[7] == ':') s.substring(0, 4) + "-" + s.substring(5, 7) + "-" + s.substring(8).trim() else s.trim()

    /** MediaMetadataRetriever gives "20240517T140322.000Z"; shown as UTC because the file carries no zone. */
    private fun isoDate(s: String): String = try {
        val d = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(s.substringBefore('.').removeSuffix("Z"))
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(d!!)
    } catch (_: Exception) { s }
}
