package com.lanshare.app

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.util.Locale

/**
 * Everything the image viewer can read from a picture's own metadata, grouped for the pull-up details sheet (ImageInfoSheet):
 * Location (GPS), Camera, Exposure, Date, Image, XMP and "Other tags" (every EXIF tag the library knows that was not shown above).
 * Pure reading, no UI. Blocking: call from a worker thread. [open] gives a fresh stream per call (a file, or a decrypting vault reader).
 * NOT compiled in the Lumen environment (owner compile). The tag list is found by reflection on ExifInterface.TAG_* (minify is off; with R8 on, keep those fields).
 */
object ImageInfo {
    class Sec(val title: String) {
        val rows = ArrayList<Pair<String, String>>()
        fun row(k: String, v: String?) { if (!v.isNullOrBlank()) rows.add(k to v.trim()) }
    }

    class Result(val secs: List<Sec>, val lat: Double?, val lon: Double?)

    private val allTags: List<String> by lazy {
        val l = ArrayList<String>()
        for (f in ExifInterface::class.java.fields) {
            if (!f.name.startsWith("TAG_") || f.type != String::class.java) continue
            try { (f.get(null) as? String)?.let { if (it !in l) l.add(it) } } catch (_: Throwable) { }
        }
        l
    }

    /** Pointers / raw blobs that mean nothing to a person. */
    private val skip = setOf("MakerNote", "Xmp", "ExifIFDPointer", "GPSInfoIFDPointer", "InteroperabilityIFDPointer", "SubIFDPointer", "JPEGInterchangeFormat",
        "JPEGInterchangeFormatLength", "StripOffsets", "StripByteCounts", "RowsPerStrip", "ThumbnailImage", "ThumbnailImageLength", "ThumbnailImageWidth",
        "ThumbnailOffset", "ThumbnailLength", "PreviewImageStart", "PreviewImageLength", "ISOSpeedRatings", "OrfThumbnailImage", "OrfPreviewImageStart",
        "OrfPreviewImageLength", "OrfAspectFrame", "Rw2SensorBottomBorder", "Rw2SensorLeftBorder", "Rw2SensorRightBorder", "Rw2SensorTopBorder", "Rw2Iso", "Rw2JpgFromRaw")

    private fun trim(d: Double): String = String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    private fun rat(s: String?): Double? {
        if (s == null) return null
        val p = s.split('/')
        if (p.size == 2) {
            val n = p[0].trim().toDoubleOrNull(); val d = p[1].trim().toDoubleOrNull()
            return if (n == null || d == null || d == 0.0) null else n / d
        }
        return s.trim().toDoubleOrNull()
    }

    /** "2024:05:17 14:03:22" -> "2024-05-17 14:03:22". */
    private fun dt(s: String): String = if (s.length >= 10 && s[4] == ':' && s[7] == ':') s.substring(0, 4) + "-" + s.substring(5, 7) + "-" + s.substring(8) else s

    private fun compass(deg: Double): String {
        val n = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = (Math.round(((deg % 360.0) + 360.0) % 360.0 / 45.0).toInt()) % 8
        return n[i]
    }

    private fun dms(v: Double, pos: String, neg: String): String {
        val a = Math.abs(v); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val s = ((a - d) * 60 - m) * 60
        return String.format(Locale.US, "%d\u00B0 %d\u2032 %.1f\u2033 %s", d, m, s, if (v >= 0) pos else neg)
    }

    private fun label(t: String): String =
        t.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")

    private fun clean(s: String, max: Int): String? {
        val t = s.replace(Regex("[\\u0000-\\u001F]"), " ").trim()
        if (t.isEmpty()) return null
        return if (t.length > max) t.substring(0, max) + "\u2026" else t
    }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    fun read(open: () -> InputStream): Result {
        val out = ArrayList<Sec>()
        var lat: Double? = null
        var lon: Double? = null
        val img = Sec("Image")
        try {   // pixels and format come from the decoder, so even a picture without EXIF (PNG, GIF) shows them
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) {
                img.row("Pixels", "${o.outWidth} \u00D7 ${o.outHeight}")
                val mp = o.outWidth.toDouble() * o.outHeight / 1e6
                if (mp >= 0.1) img.row("Megapixels", String.format(Locale.US, "%.1f MP", mp))
            }
            img.row("Format", o.outMimeType)
        } catch (_: Throwable) { }
        try {
            val ex = open().use { ExifInterface(it) }
            val used = HashSet<String>()
            fun a(t: String): String? { used.add(t); return ex.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() } }
            fun add(s: Sec) { if (s.rows.isNotEmpty()) out.add(s) }

            // ---- Location
            val loc = Sec("Location")
            used.addAll(listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef"))
            val ll = ex.latLong
            if (ll != null) {
                lat = ll[0]; lon = ll[1]
                loc.row("Coordinates", String.format(Locale.US, "%.6f, %.6f", ll[0], ll[1]))
                loc.row("Latitude", dms(ll[0], "N", "S"))
                loc.row("Longitude", dms(ll[1], "E", "W"))
            }
            if (ex.hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) {
                val al = ex.getAltitude(Double.NaN)
                if (!al.isNaN()) loc.row("Altitude", Math.round(al).toString() + " m " + (if (al < 0) "below" else "above") + " sea level")
            }
            rat(a("GPSSpeed"))?.let { sp -> loc.row("Speed", trim(sp) + " " + (when (a("GPSSpeedRef")) { "M" -> "mph"; "N" -> "knots"; else -> "km/h" })) }
            rat(a("GPSImgDirection"))?.let { loc.row("Camera facing", trim(it) + "\u00B0 " + compass(it)) }
            rat(a("GPSTrack"))?.let { loc.row("Travel direction", trim(it) + "\u00B0 " + compass(it)) }
            val gt = a("GPSTimeStamp"); val gd = a("GPSDateStamp")
            if (gt != null || gd != null) loc.row("GPS time (UTC)", ((if (gd != null) dt(gd) + " " else "") + (gt ?: "")).trim())
            loc.row("Map datum", a("GPSMapDatum"))
            loc.row("Method", a("GPSProcessingMethod")?.let { clean(it, 80) })
            loc.row("Place", a("GPSAreaInformation")?.let { clean(it, 120) })
            rat(a("GPSHPositioningError"))?.let { loc.row("Accuracy", "\u00B1" + trim(it) + " m") }
            a("GPSSatellites")?.let { loc.row("Satellites", it) }
            rat(a("GPSDOP"))?.let { loc.row("Dilution of precision", trim(it)) }
            add(loc)

            // ---- Camera
            val cam = Sec("Camera")
            val make = a("Make"); val model = a("Model")
            cam.row("Camera", if (make != null && model != null && !model.startsWith(make, true)) "$make $model" else model ?: make)
            val lmk = a("LensMake"); val lmd = a("LensModel")
            cam.row("Lens", if (lmk != null && lmd != null && !lmd.startsWith(lmk, true)) "$lmk $lmd" else lmd ?: lmk)
            a("LensSpecification")?.let { ls ->
                val p = ls.split(',').map { rat(it.trim()) }
                if (p.size == 4 && p[0] != null && p[1] != null) {
                    val fl = if (p[0] == p[1]) trim(p[0]!!) else trim(p[0]!!) + "\u2013" + trim(p[1]!!)
                    val fn = if (p[2] != null && p[3] != null) " f/" + (if (p[2] == p[3]) trim(p[2]!!) else trim(p[2]!!) + "\u2013" + trim(p[3]!!)) else ""
                    cam.row("Lens range", "$fl mm$fn")
                }
            }
            cam.row("Software", a("Software")); cam.row("Artist", a("Artist")); cam.row("Copyright", a("Copyright"))
            cam.row("Description", a("ImageDescription")?.let { clean(it, 300) })
            cam.row("Comment", a("UserComment")?.replace(Regex("^(ASCII|UNICODE|JIS)"), "")?.let { clean(it, 300) })
            cam.row("Owner", a("CameraOwnerName")); cam.row("Body serial", a("BodySerialNumber")); cam.row("Lens serial", a("LensSerialNumber"))
            add(cam)

            // ---- Exposure
            val exp = Sec("Exposure")
            a("ExposureTime")?.let { s -> rat(s)?.let { t -> exp.row("Shutter", if (t > 0 && t < 0.5) "1/" + Math.round(1 / t) + " s" else trim(t) + " s") } }
            rat(a("FNumber"))?.let { exp.row("Aperture", "f/" + trim(it)) }
            used.add("ISOSpeedRatings")
            exp.row("ISO", a("PhotographicSensitivity") ?: ex.getAttribute("ISOSpeedRatings")?.trim())
            rat(a("FocalLength"))?.let { exp.row("Focal length", trim(it) + " mm") }
            a("FocalLengthIn35mmFilm")?.let { exp.row("35 mm equivalent", "$it mm") }
            rat(a("ExposureBiasValue"))?.let { exp.row("Exposure bias", (if (it > 0) "+" else "") + trim(it) + " EV") }
            a("ExposureProgram")?.toIntOrNull()?.let { p ->
                exp.row("Program", arrayOf("Not defined", "Manual", "Normal", "Aperture priority", "Shutter priority", "Creative", "Action", "Portrait", "Landscape").getOrNull(p))
            }
            a("ExposureMode")?.toIntOrNull()?.let { exp.row("Exposure mode", arrayOf("Auto", "Manual", "Auto bracket").getOrNull(it)) }
            a("MeteringMode")?.toIntOrNull()?.let { m ->
                exp.row("Metering", when (m) { 1 -> "Average"; 2 -> "Center-weighted"; 3 -> "Spot"; 4 -> "Multi-spot"; 5 -> "Pattern"; 6 -> "Partial"; else -> null })
            }
            a("Flash")?.toIntOrNull()?.let { exp.row("Flash", if (it and 1 == 1) "Fired" else "Did not fire") }
            a("WhiteBalance")?.toIntOrNull()?.let { exp.row("White balance", if (it == 0) "Auto" else "Manual") }
            a("LightSource")?.toIntOrNull()?.let { l ->
                exp.row("Light source", when (l) { 1 -> "Daylight"; 2 -> "Fluorescent"; 3 -> "Tungsten"; 4 -> "Flash"; 9 -> "Fine weather"; 10 -> "Cloudy"; 11 -> "Shade"; else -> null })
            }
            rat(a("SubjectDistance"))?.let { if (it > 0) exp.row("Subject distance", trim(it) + " m") }
            rat(a("DigitalZoomRatio"))?.let { if (it > 0 && Math.abs(it - 1.0) > 0.01) exp.row("Digital zoom", "\u00D7" + trim(it)) }
            a("SceneCaptureType")?.toIntOrNull()?.let { exp.row("Scene", arrayOf("Standard", "Landscape", "Portrait", "Night").getOrNull(it)) }
            add(exp)

            // ---- Date
            val dat = Sec("Date")
            fun stamp(t: String, sub: String, off: String): String? {
                val v = a(t) ?: return null
                val ss = a(sub); val of = a(off)
                return dt(v) + (if (ss != null) ".$ss" else "") + (if (of != null) " $of" else "")
            }
            dat.row("Taken", stamp("DateTimeOriginal", "SubSecTimeOriginal", "OffsetTimeOriginal"))
            dat.row("Digitized", stamp("DateTimeDigitized", "SubSecTimeDigitized", "OffsetTimeDigitized"))
            dat.row("Edited", stamp("DateTime", "SubSecTime", "OffsetTime"))
            add(dat)

            // ---- Image
            val pw = a("PixelXDimension") ?: a("ImageWidth"); val ph = a("PixelYDimension") ?: a("ImageLength")
            if (img.rows.isEmpty() && pw != null && ph != null) img.row("Pixels", "$pw \u00D7 $ph")
            a("Orientation")?.toIntOrNull()?.let { o ->
                img.row("Orientation", arrayOf("", "Normal", "Mirrored", "Rotated 180\u00B0", "Flipped", "Transposed", "Rotated 90\u00B0", "Transversed", "Rotated 270\u00B0").getOrNull(o))
            }
            val xr = rat(a("XResolution")); val yr = rat(a("YResolution"))
            if (xr != null && yr != null) img.row("Resolution", trim(xr) + " \u00D7 " + trim(yr) + (if (a("ResolutionUnit") == "3") " dpcm" else " dpi"))
            a("ColorSpace")?.toIntOrNull()?.let { img.row("Color space", if (it == 1) "sRGB" else if (it == 65535) "Uncalibrated" else null) }
            a("BitsPerSample")?.let { img.row("Bits per sample", it) }
            a("ExifVersion")?.let { img.row("EXIF version", it) }
            a("ImageUniqueID")?.let { img.row("Unique ID", it) }
            try { if (ex.hasThumbnail()) img.row("Embedded thumbnail", "Yes") } catch (_: Throwable) { }
            used.addAll(listOf("ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension"))

            // ---- XMP (title, author, keywords, rating and the app's own fields)
            val xm = Sec("XMP")
            a("Xmp")?.let { x -> xmp(x, xm) }
            add(img); add(xm)

            // ---- everything else the library found
            val oth = Sec("Other tags")
            for (t in allTags) {
                if (t in used || t in skip || oth.rows.size >= 80) continue
                val raw = ex.getAttribute(t)?.trim() ?: continue
                if (raw.isEmpty()) continue
                val v = if (raw.indexOf('/') > 0 && raw.indexOf(',') < 0 && raw.indexOf(' ') < 0) (rat(raw)?.let { trim(it) } ?: raw) else raw
                oth.row(label(t), clean(v, 160))
            }
            add(oth)
        } catch (_: Throwable) {
            if (img.rows.isNotEmpty()) out.add(img)
        }
        return Result(out, lat, lon)
    }

    private fun xmp(x: String, s: Sec) {
        val seen = HashSet<String>()
        for (m in Regex("<dc:(title|description|creator|subject|rights)>(.*?)</dc:\\1>", RegexOption.DOT_MATCHES_ALL).findAll(x)) {
            val items = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL).findAll(m.groupValues[2]).map { unesc(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
            if (items.isNotEmpty() && seen.add(m.groupValues[1])) s.row(m.groupValues[1].replaceFirstChar { c -> c.uppercase() }, clean(items.joinToString(", "), 300))
        }
        for (m in Regex("\\s([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9]+)=\"([^\"]{1,160})\"").findAll(x)) {
            if (s.rows.size >= 40) break
            val pre = m.groupValues[1]
            if (pre == "xmlns" || pre == "rdf" || pre == "x") continue
            val key = label(m.groupValues[2])
            if (seen.add(pre + ":" + key)) s.row(key, clean(unesc(m.groupValues[3]), 160))
        }
    }
}
