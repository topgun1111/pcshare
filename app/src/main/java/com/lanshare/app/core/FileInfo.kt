package com.lanshare.app.core

import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile

/**
 * "Media info for every format": extra Details sections for everything that is not photo / video / audio container data
 * (those stay in Media.kt). Result: o["sections"] = [{ "t": "PDF", "rows": [["Pages","12"], ...] }, ...]. Rendered by ui.html (doDetails)
 * and MediaText.format. Pure best effort: every reader is bounded (bytes / entries / seconds) and a failure only drops that section.
 */
object FileInfo {
    private const val TEXT_CAP = 8L shl 20          // text statistics: read at most 8 MB
    private const val ENTRY_CAP = 200_000           // archives: stop counting after this many entries

    /** Context for readers that need the platform (APK labels). Set from App.onCreate. */
    @Volatile var ctx: android.content.Context? = null

    class Sec(val t: String) {
        val rows = ArrayList<Pair<String, String>>()
        fun row(k: String, v: String?) { if (!v.isNullOrBlank()) rows.add(k to v.trim()) }
        fun row(k: String, v: Long) { if (v >= 0) rows.add(k to String.format(Locale.US, "%,d", v)) }
    }

    fun add(f: File, name: String, mime: String, o: JSONObject) {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val out = ArrayList<Sec>()
        fun run(block: () -> Sec?) { try { block()?.let { if (it.rows.isNotEmpty()) out.add(it) } } catch (_: Throwable) {} }
        when {
            mime.startsWith("image/") && !mime.contains("svg") -> run { exif(f) }
            mime.startsWith("video/") || mime.startsWith("audio/") -> Unit
            ext == "pdf" || mime == "application/pdf" -> run { pdf(f) }
            ext in OOXML -> run { ooxml(f, ext) }
            ext in ODF -> run { odf(f, ext) }
            ext == "epub" -> run { epub(f) }
            ext == "apk" -> { run { apk(f) }; run { zip(f, "Package contents") } }
            ext in ZIPLIKE -> run { zip(f, "Archive") }
            ext in TARLIKE || ext == "7z" -> run { commons(f, ext) }
            ext in FONTS -> run { font(f) }
            ext == "svg" -> { run { svg(f) }; run { text(f, ext) } }
            ext == "db" || ext == "sqlite" || ext == "sqlite3" -> run { sqlite(f) }
            ext in TEXTY || mime.startsWith("text/") -> run { text(f, ext) }
        }
        if (out.isEmpty()) run { sniff(f, ext) }       // unknown / extension-less file: say what the first bytes are, or whether it looks like text
        if (out.isNotEmpty()) {
            val a = JSONArray()
            for (s in out) {
                val r = JSONArray()
                for ((k, v) in s.rows) r.put(JSONArray().put(k).put(v))
                a.put(JSONObject().put("t", s.t).put("rows", r))
            }
            o.put("sections", a)
        }
    }

    private val OOXML = setOf("docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "potx", "ppsx")
    private val ODF = setOf("odt", "ods", "odp", "odg", "ott", "ots", "otp")
    private val ZIPLIKE = setOf("zip", "jar", "war", "aar", "xpi", "cbz", "apks", "xapk", "kmz", "crx")
    private val TARLIKE = setOf("tar", "tgz", "gz", "bz2", "xz", "tbz2", "txz", "lzma")
    private val FONTS = setOf("ttf", "otf", "ttc")
    private val TEXTY = setOf("txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "xhtml", "css", "js", "mjs", "ts", "kt", "kts", "java",
        "py", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "ps1", "sql", "yml", "yaml", "toml", "ini", "cfg", "conf", "log", "srt", "vtt",
        "ass", "ssa", "tex", "rtf", "gradle", "properties", "gitignore", "lrc", "m3u", "m3u8", "pls", "vcf", "ics", "gpx", "kml", "swift", "dart", "lua", "r")

    // ------------------------------------------------------------------ helpers
    private fun readHead(f: File, n: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
        val b = ByteArray(minOf(n.toLong(), r.length()).toInt()); r.readFully(b); b
    }
    private fun readTail(f: File, n: Int): ByteArray = RandomAccessFile(f, "r").use { r ->
        val len = minOf(n.toLong(), r.length()).toInt(); r.seek(r.length() - len); val b = ByteArray(len); r.readFully(b); b
    }
    private fun lat1(b: ByteArray) = String(b, Charsets.ISO_8859_1)
    private fun fmtSize(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / 1073741824.0)
        n >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", n / 1048576.0)
        n >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> "$n B"
    }
    private fun xmlTag(xml: String, tag: String): String? {
        val m = Regex("<" + Regex.escape(tag) + "(?:\\s[^>]*)?>([^<]*)</" + Regex.escape(tag) + ">").find(xml) ?: return null
        return xmlUnescape(m.groupValues[1]).trim().ifEmpty { null }
    }
    private fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun isoNice(s: String?): String? = s?.replace('T', ' ')?.removeSuffix("Z")?.substringBefore('.')?.let { if (it.length >= 10) it else null }
    private fun zipText(z: ZipFile, name: String, cap: Int = 1 shl 20): String? {
        val e = z.getEntry(name) ?: return null
        return z.getInputStream(e).use { ins -> val b = ins.readNBytesCompat(cap); String(b, Charsets.UTF_8) }
    }
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var left = max
        while (left > 0) { val n = read(buf, 0, minOf(buf.size, left)); if (n <= 0) break; bo.write(buf, 0, n); left -= n }
        return bo.toByteArray()
    }

    // ------------------------------------------------------------------ images: camera / exposure / GPS
    private fun exif(f: File): Sec? {
        val s = Sec("Camera")
        val ex = ExifInterface(f.path)
        fun a(t: String) = ex.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() }
        val make = a(ExifInterface.TAG_MAKE); val model = a(ExifInterface.TAG_MODEL)
        s.row("Camera", if (make != null && model != null && !model.startsWith(make, true)) "$make $model" else model ?: make)
        s.row("Lens", a(ExifInterface.TAG_LENS_MODEL))
        a(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { s.row("Aperture", "f/" + trim(it)) }
        a(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { s.row("Shutter", if (it in 0.0..0.5 && it > 0) "1/" + Math.round(1 / it) + " s" else trim(it) + " s") }
        (a(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: a("ISOSpeedRatings"))?.let { s.row("ISO", it) }
        a(ExifInterface.TAG_FOCAL_LENGTH)?.let { fl -> ratio(fl)?.let { v -> s.row("Focal length", trim(v) + " mm") } }
        a(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { s.row("35 mm equivalent", "$it mm") }
        a(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { s.row("Flash", if (it and 1 == 1) "Fired" else "Did not fire") }
        a(ExifInterface.TAG_SOFTWARE)?.let { s.row("Software", it) }
        a(ExifInterface.TAG_ARTIST)?.let { s.row("Artist", it) }
        a(ExifInterface.TAG_COPYRIGHT)?.let { s.row("Copyright", it) }
        a(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { s.row("Description", it) }
        val ll = ex.latLong
        if (ll != null) s.row("GPS", String.format(Locale.US, "%.6f, %.6f", ll[0], ll[1]))
        if (ex.hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) ex.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { s.row("Altitude", Math.round(it).toString() + " m") }
        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, opt)
        if (s.rows.isNotEmpty() && !opt.outMimeType.isNullOrBlank()) s.row("Image format", opt.outMimeType)
        return s
    }
    private fun trim(d: Double) = String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
    private fun ratio(s: String): Double? { val p = s.split('/'); return if (p.size == 2) (p[0].toDoubleOrNull() ?: return null) / (p[1].toDoubleOrNull() ?: return null) else s.toDoubleOrNull() }

    // ------------------------------------------------------------------ PDF
    private fun pdf(f: File): Sec? {
        val s = Sec("PDF")
        val head = readHead(f, 1 shl 20); val tail = readTail(f, 1 shl 20)
        val h = lat1(head)
        Regex("%PDF-(\\d\\.\\d)").find(h)?.let { s.row("PDF version", it.groupValues[1]) }
        val both = h + "\n" + lat1(tail)
        s.row("Title", pdfField(both, "Title")); s.row("Author", pdfField(both, "Author")); s.row("Subject", pdfField(both, "Subject"))
        s.row("Keywords", pdfField(both, "Keywords")); s.row("Creator", pdfField(both, "Creator")); s.row("Producer", pdfField(both, "Producer"))
        s.row("Created", pdfDate(pdfField(both, "CreationDate"))); s.row("Modified", pdfDate(pdfField(both, "ModDate")))
        if (both.contains("/Encrypt")) s.row("Encrypted", "Yes")
        if (both.contains("/Linearized")) s.row("Fast web view", "Yes")
        try {
            val pfd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val r = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    s.row("Pages", r.pageCount.toLong())
                    if (r.pageCount > 0) { val p = r.openPage(0); try { s.row("Page size", paper(p.width.toDouble(), p.height.toDouble())) } finally { p.close() } }
                } finally { r.close() }
            } finally { pfd.close() }
        } catch (_: SecurityException) { s.row("Pages", "locked (password protected)") } catch (_: Throwable) {
            Regex("/Count\\s+(\\d+)").findAll(both).map { it.groupValues[1].toLong() }.maxOrNull()?.let { s.row("Pages", it) }
        }
        return s
    }
    private fun paper(wPt: Double, hPt: Double): String {
        val mm = { p: Double -> p * 25.4 / 72.0 }
        val w = mm(minOf(wPt, hPt)); val h = mm(maxOf(wPt, hPt))
        val names = listOf("A3" to (297.0 to 420.0), "A4" to (210.0 to 297.0), "A5" to (148.0 to 210.0), "Letter" to (215.9 to 279.4), "Legal" to (215.9 to 355.6))
        val hit = names.firstOrNull { Math.abs(it.second.first - w) < 3 && Math.abs(it.second.second - h) < 3 }?.first
        val orient = if (wPt > hPt) "landscape" else "portrait"
        return String.format(Locale.US, "%.0f × %.0f mm", w, h) + (if (hit != null) " ($hit, $orient)" else " ($orient)")
    }
    private fun pdfField(s: String, key: String): String? {
        var i = s.lastIndexOf("/$key")
        while (i >= 0) {
            var j = i + key.length + 1
            while (j < s.length && s[j].isWhitespace()) j++
            if (j < s.length && s[j] == '(') return pdfLiteral(s, j + 1)
            if (j < s.length && s[j] == '<' && j + 1 < s.length && s[j + 1] != '<') return pdfHex(s, j + 1)
            i = s.lastIndexOf("/$key", i - 1)
        }
        return null
    }
    private fun pdfLiteral(s: String, start: Int): String? {
        val bo = java.io.ByteArrayOutputStream(); var depth = 1; var i = start
        while (i < s.length && depth > 0 && bo.size() < 2000) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { i++; when (val e = s[i]) { 'n' -> bo.write(10); 'r' -> bo.write(13); 't' -> bo.write(9); 'b' -> bo.write(8); 'f' -> bo.write(12)
                    in '0'..'7' -> { var v = 0; var n = 0; while (n < 3 && i < s.length && s[i] in '0'..'7') { v = v * 8 + (s[i] - '0'); i++; n++ }; i--; bo.write(v) }
                    '\n', '\r' -> Unit
                    else -> bo.write(e.code) } }
                c == '(' -> { depth++; bo.write(c.code) }
                c == ')' -> { depth--; if (depth > 0) bo.write(c.code) }
                else -> bo.write(c.code and 0xFF)
            }
            i++
        }
        return pdfDecode(bo.toByteArray())
    }
    private fun pdfHex(s: String, start: Int): String? {
        val end = s.indexOf('>', start); if (end < 0) return null
        val hx = s.substring(start, end).filter { !it.isWhitespace() }.let { if (it.length % 2 == 1) it + "0" else it }
        return pdfDecode(ByteArray(hx.length / 2) { hx.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    }
    private fun pdfDecode(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val t = if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte()) String(b, 3, b.size - 3, Charsets.UTF_8)
        else String(b, Charsets.ISO_8859_1)
        return t.trim().ifEmpty { null }
    }
    private fun pdfDate(d: String?): String? {   // D:20240517140322+03'00'
        val m = Regex("D:(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?").find(d ?: return null) ?: return d
        val g = m.groupValues
        return "${g[1]}-${g[2].ifEmpty { "01" }}-${g[3].ifEmpty { "01" }} ${g[4].ifEmpty { "00" }}:${g[5].ifEmpty { "00" }}:${g[6].ifEmpty { "00" }}"
    }

    // ------------------------------------------------------------------ Office (OOXML) / OpenDocument / EPUB
    private fun ooxml(f: File, ext: String): Sec? {
        val kind = when { ext.startsWith("doc") || ext.startsWith("dot") -> "Word document"; ext.startsWith("xl") -> "Excel workbook"; else -> "PowerPoint presentation" }
        val s = Sec(kind)
        ZipFile(f).use { z ->
            zipText(z, "docProps/core.xml")?.let { x ->
                s.row("Title", xmlTag(x, "dc:title")); s.row("Subject", xmlTag(x, "dc:subject")); s.row("Author", xmlTag(x, "dc:creator"))
                s.row("Last modified by", xmlTag(x, "cp:lastModifiedBy")); s.row("Keywords", xmlTag(x, "cp:keywords")); s.row("Description", xmlTag(x, "dc:description"))
                s.row("Created", isoNice(xmlTag(x, "dcterms:created"))); s.row("Modified", isoNice(xmlTag(x, "dcterms:modified")))
                s.row("Revision", xmlTag(x, "cp:revision"))
            }
            zipText(z, "docProps/app.xml")?.let { x ->
                s.row("Application", listOfNotNull(xmlTag(x, "Application"), xmlTag(x, "AppVersion")).joinToString(" "))
                s.row("Company", xmlTag(x, "Company"))
                xmlTag(x, "Pages")?.toLongOrNull()?.let { s.row("Pages", it) }
                xmlTag(x, "Words")?.toLongOrNull()?.let { s.row("Words", it) }
                xmlTag(x, "Characters")?.toLongOrNull()?.let { s.row("Characters", it) }
                xmlTag(x, "Slides")?.toLongOrNull()?.let { s.row("Slides", it) }
                xmlTag(x, "Notes")?.toLongOrNull()?.takeIf { it > 0 }?.let { s.row("Slides with notes", it) }
                xmlTag(x, "TotalTime")?.toLongOrNull()?.takeIf { it > 0 }?.let { s.row("Editing time", "$it min") }
            }
            if (ext.startsWith("xl")) zipText(z, "xl/workbook.xml")?.let { x ->
                val names = Regex("<sheet\\s[^>]*name=\"([^\"]*)\"").findAll(x).map { xmlUnescape(it.groupValues[1]) }.toList()
                if (names.isNotEmpty()) { s.row("Sheets", names.size.toLong()); s.row("Sheet names", names.take(12).joinToString(", ") + if (names.size > 12) " …" else "") }
            }
            if (ext.startsWith("ppt") || ext.startsWith("pot") || ext.startsWith("pps")) if (s.rows.none { it.first == "Slides" }) {
                var n = 0L; val en = z.entries(); while (en.hasMoreElements()) if (Regex("ppt/slides/slide\\d+\\.xml").matches(en.nextElement().name)) n++
                if (n > 0) s.row("Slides", n)
            }
            var media = 0L; val en = z.entries(); while (en.hasMoreElements()) if (en.nextElement().name.contains("/media/")) media++
            if (media > 0) s.row("Embedded images / media", media)
        }
        return s
    }
    private fun odf(f: File, ext: String): Sec? {
        val s = Sec(when (ext.take(3)) { "odt", "ott" -> "OpenDocument text"; "ods", "ots" -> "OpenDocument spreadsheet"; "odp", "otp" -> "OpenDocument presentation"; else -> "OpenDocument" })
        ZipFile(f).use { z ->
            zipText(z, "meta.xml")?.let { x ->
                s.row("Title", xmlTag(x, "dc:title")); s.row("Subject", xmlTag(x, "dc:subject")); s.row("Author", xmlTag(x, "meta:initial-creator") ?: xmlTag(x, "dc:creator"))
                s.row("Keywords", xmlTag(x, "meta:keyword")); s.row("Description", xmlTag(x, "dc:description"))
                s.row("Created", isoNice(xmlTag(x, "meta:creation-date"))); s.row("Modified", isoNice(xmlTag(x, "dc:date"))); s.row("Application", xmlTag(x, "meta:generator"))
                Regex("meta:document-statistic([^>]*)").find(x)?.groupValues?.get(1)?.let { st ->
                    fun at(n: String) = Regex("$n=\"(\\d+)\"").find(st)?.groupValues?.get(1)?.toLongOrNull()
                    at("meta:page-count")?.let { s.row("Pages", it) }; at("meta:word-count")?.let { s.row("Words", it) }
                    at("meta:character-count")?.let { s.row("Characters", it) }; at("meta:table-count")?.let { s.row("Tables", it) }; at("meta:image-count")?.let { s.row("Images", it) }
                }
            }
        }
        return s
    }
    private fun epub(f: File): Sec? {
        val s = Sec("E-book")
        ZipFile(f).use { z ->
            val cont = zipText(z, "META-INF/container.xml") ?: return null
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(cont)?.groupValues?.get(1) ?: return null
            val x = zipText(z, opfPath) ?: return null
            s.row("Title", xmlTag(x, "dc:title")); s.row("Author", xmlTag(x, "dc:creator")); s.row("Language", xmlTag(x, "dc:language"))
            s.row("Publisher", xmlTag(x, "dc:publisher")); s.row("Published", xmlTag(x, "dc:date")); s.row("Identifier", xmlTag(x, "dc:identifier"))
            s.row("EPUB version", Regex("<package[^>]*version=\"([^\"]+)\"").find(x)?.groupValues?.get(1))
            val ch = Regex("<itemref\\b").findAll(x).count(); if (ch > 0) s.row("Content documents", ch.toLong())
        }
        return s
    }

    // ------------------------------------------------------------------ archives
    private fun zip(f: File, title: String): Sec? {
        val s = Sec(title)
        var files = 0L; var dirs = 0L; var raw = 0L; var packed = 0L; var enc = false; var capped = false
        ZipFile(f).use { z ->
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (files + dirs >= ENTRY_CAP) { capped = true; break }
                if (e.isDirectory) dirs++ else { files++; if (e.size > 0) raw += e.size; if (e.compressedSize > 0) packed += e.compressedSize }
                if (e.method != 0 && e.method != 8) enc = true
            }
            s.row("Files", files); if (dirs > 0) s.row("Folders", dirs)
            if (raw > 0) { s.row("Unpacked size", fmtSize(raw) + " ($raw bytes)"); if (packed > 0) s.row("Compression", String.format(Locale.US, "%.0f%% of original", packed * 100.0 / raw)) }
            if (capped) s.row("Note", "counted the first $ENTRY_CAP entries only")
            if (enc) s.row("Note", "uses a compression method / encryption this reader does not describe")
            z.comment?.takeIf { it.isNotBlank() }?.let { s.row("Comment", it.take(300)) }
        }
        return s
    }
    private fun commons(f: File, ext: String): Sec? {
        val s = Sec("Archive")
        var files = 0L; var dirs = 0L; var raw = 0L
        if (ext == "7z") {
            org.apache.commons.compress.archivers.sevenz.SevenZFile(f).use { z ->
                while (files + dirs < ENTRY_CAP) { val e = z.nextEntry ?: break; if (e.isDirectory) dirs++ else { files++; raw += maxOf(0L, e.size) } }
            }
            s.row("Format", "7-Zip")
        } else {
            val inner = when (ext) { "tar" -> null; "tgz", "gz" -> "gzip"; "tbz2", "bz2" -> "bzip2"; "txz", "xz" -> "xz"; else -> "lzma" }
            s.row("Compression", inner ?: "none (tar)")
            f.inputStream().buffered().use { raw0 ->
                val ins: java.io.InputStream = when (inner) {
                    "gzip" -> java.util.zip.GZIPInputStream(raw0)
                    "bzip2" -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw0)
                    "xz" -> org.apache.commons.compress.compressors.xz.XZCompressorInputStream(raw0)
                    "lzma" -> org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream(raw0)
                    else -> raw0
                }
                // plain .gz / .bz2 / .xz without tar inside is a single compressed file: tell it from a tar by the "ustar" magic
                val pb = java.io.PushbackInputStream(ins, 512); val hdr = ByteArray(512)
                var got = 0; while (got < 512) { val r = pb.read(hdr, got, 512 - got); if (r <= 0) break; got += r }
                if (got > 0) pb.unread(hdr, 0, got)
                val isTar = got >= 262 && String(hdr, 257, 5, Charsets.ISO_8859_1) == "ustar"
                if (!isTar && ext !in setOf("tar", "tgz", "tbz2", "txz")) {
                    var total = got.toLong(); val buf = ByteArray(65536); val end = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < end) { val r = pb.read(buf); if (r <= 0) break; total += r }
                    s.row("Contents", "single file (" + fmtSize(total) + " unpacked)"); return s
                }
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(pb).use { t ->
                    while (files + dirs < ENTRY_CAP) { val e = t.nextTarEntry ?: break; if (e.isDirectory) dirs++ else { files++; raw += e.size } }
                }
            }
        }
        s.row("Files", files); if (dirs > 0) s.row("Folders", dirs); if (raw > 0) s.row("Unpacked size", fmtSize(raw) + " ($raw bytes)")
        return s
    }

    // ------------------------------------------------------------------ APK
    private fun apk(f: File): Sec? {
        val c = ctx ?: return null
        val pm = c.packageManager
        val pi = pm.getPackageArchiveInfo(f.path, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_ACTIVITIES) ?: return null
        val s = Sec("Android package")
        pi.applicationInfo?.let { ai -> ai.sourceDir = f.path; ai.publicSourceDir = f.path; try { s.row("App name", pm.getApplicationLabel(ai).toString()) } catch (_: Throwable) {} }
        s.row("Package", pi.packageName)
        @Suppress("DEPRECATION") val vc: Long = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        s.row("Version", "${pi.versionName ?: "?"} (code $vc)")
        pi.applicationInfo?.let { ai ->
            if (android.os.Build.VERSION.SDK_INT >= 24) s.row("Min Android", "API ${ai.minSdkVersion}")
            s.row("Target Android", "API ${ai.targetSdkVersion}")
        }
        pi.requestedPermissions?.let { p ->
            s.row("Permissions", p.size.toLong())
            if (p.isNotEmpty()) s.row("Permission list", p.take(8).joinToString(", ") { it.substringAfterLast('.') } + if (p.size > 8) " …" else "")
        }
        pi.activities?.let { s.row("Activities", it.size.toLong()) }
        return s
    }

    // ------------------------------------------------------------------ fonts (name table + glyph count)
    private fun font(f: File): Sec? {
        val s = Sec("Font")
        RandomAccessFile(f, "r").use { r ->
            var base = 0L
            if (r.length() < 12) return null
            var tag = r.readInt()
            if (tag == 0x74746366) {   // 'ttcf': a collection, describe the first font
                r.readInt(); val n = r.readInt(); s.row("Fonts in collection", n.toLong()); base = r.readInt().toLong() and 0xFFFFFFFFL; r.seek(base); tag = r.readInt()
            }
            s.row("Format", when (tag) { 0x4F54544F -> "OpenType (CFF)"; 0x00010000, 0x74727565 -> "TrueType"; else -> return null })
            val num = r.readUnsignedShort(); r.skipBytes(6)
            val tabs = HashMap<String, Pair<Long, Long>>()
            for (i in 0 until num) {
                val t = ByteArray(4); r.readFully(t); r.readInt(); val off = r.readInt().toLong() and 0xFFFFFFFFL; val len = r.readInt().toLong() and 0xFFFFFFFFL
                tabs[String(t, Charsets.ISO_8859_1)] = off to len
            }
            tabs["name"]?.let { (off, _) ->
                r.seek(off); r.readUnsignedShort(); val cnt = r.readUnsignedShort(); val so = r.readUnsignedShort()
                val names = HashMap<Int, String>()
                for (i in 0 until cnt) {
                    val pid = r.readUnsignedShort(); r.readUnsignedShort(); val lang = r.readUnsignedShort(); val nid = r.readUnsignedShort(); val len = r.readUnsignedShort(); val no = r.readUnsignedShort()
                    if (nid !in listOf(0, 1, 2, 4, 5, 8, 9, 13) || (names.containsKey(nid) && !(pid == 3 && lang == 0x409))) continue
                    val save = r.filePointer; r.seek(off + so + no); val b = ByteArray(len.coerceAtMost(600)); r.readFully(b); r.seek(save)
                    names[nid] = if (pid == 0 || pid == 3) String(b, Charsets.UTF_16BE) else String(b, Charsets.ISO_8859_1)
                }
                s.row("Family", names[1]); s.row("Style", names[2]); s.row("Full name", names[4]); s.row("Version", names[5]?.removePrefix("Version "))
                s.row("Designer", names[9]); s.row("Copyright", names[0]?.take(200)); s.row("License", names[13]?.take(200))
            }
            tabs["maxp"]?.let { (off, _) -> r.seek(off + 4); s.row("Glyphs", r.readUnsignedShort().toLong()) }
            tabs["head"]?.let { (off, _) -> r.seek(off + 18); s.row("Units per em", r.readUnsignedShort().toLong()) }
            tabs["fvar"]?.let { s.row("Variable font", "Yes") }
        }
        return s
    }

    // ------------------------------------------------------------------ SVG / SQLite
    private fun svg(f: File): Sec? {
        val h = String(readHead(f, 64 * 1024), Charsets.UTF_8)
        val tag = Regex("<svg\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).find(h)?.value ?: return null
        fun at(n: String) = Regex("\\s$n=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
        val s = Sec("Vector image")
        at("width")?.let { s.row("Width", it) }; at("height")?.let { s.row("Height", it) }; at("viewBox")?.let { s.row("View box", it) }
        s.row("Title", xmlTag(h, "title")); s.row("Description", xmlTag(h, "desc"))
        s.row("Contains script", if (h.contains("<script", true)) "Yes" else null)
        return s
    }
    private fun sqlite(f: File): Sec? {
        val b = readHead(f, 100)
        if (b.size < 100 || String(b, 0, 15, Charsets.ISO_8859_1) != "SQLite format 3") return null
        val bb = ByteBuffer.wrap(b)
        var ps = bb.getShort(16).toInt() and 0xFFFF; if (ps == 1) ps = 65536
        val s = Sec("SQLite database")
        s.row("Page size", "$ps bytes"); s.row("Pages", (bb.getInt(28).toLong() and 0xFFFFFFFFL))
        s.row("Text encoding", when (bb.getInt(56)) { 1 -> "UTF-8"; 2 -> "UTF-16le"; 3 -> "UTF-16be"; else -> null })
        s.row("Schema version", (bb.getInt(40).toLong() and 0xFFFFFFFFL)); s.row("User version", bb.getInt(60).toLong())
        s.row("Journal mode", if (b[18].toInt() == 2) "WAL" else "Rollback")
        try {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
            try { db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { if (it.moveToFirst()) s.row("Tables", it.getLong(0)) } } finally { db.close() }
        } catch (_: Throwable) {}
        return s
    }

    // ------------------------------------------------------------------ text / code / data files
    private fun text(f: File, ext: String): Sec? {
        val len = f.length()
        val data = RandomAccessFile(f, "r").use { r -> val b = ByteArray(minOf(len, TEXT_CAP).toInt()); r.readFully(b); b }
        if (data.isEmpty()) return Sec("Text").also { it.row("Lines", 0L) }
        val s = Sec("Text")
        var off = 0; var enc: String
        when {
            data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> { enc = "UTF-8 with BOM"; off = 3 }
            data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> { enc = "UTF-16 LE"; off = 2 }
            data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> { enc = "UTF-16 BE"; off = 2 }
            else -> {
                if (data.take(8192).any { it == 0.toByte() }) return null    // binary, not text
                enc = if (validUtf8(data)) (if (data.all { it >= 0 }) "ASCII" else "UTF-8") else "Legacy 8-bit (not UTF-8; probably Windows-1254 / Latin-1)"
            }
        }
        s.row("Encoding", enc)
        val cs = when { enc.startsWith("UTF-16 LE") -> Charsets.UTF_16LE; enc.startsWith("UTF-16 BE") -> Charsets.UTF_16BE; enc.startsWith("Legacy") -> charset("windows-1254"); else -> Charsets.UTF_8 }
        val str = String(data, off, data.size - off, cs)
        var lf = 0; var crlf = 0; var cr = 0; var lines = 0L; var words = 0L; var inWord = false; var maxLine = 0; var cur = 0; var blank = 0L; var lineHasText = false
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\n') { if (i > 0 && str[i - 1] == '\r') { crlf++; } else lf++; lines++; if (!lineHasText) blank++; lineHasText = false; if (cur > maxLine) maxLine = cur; cur = 0; inWord = false }
            else if (c == '\r') { if (i + 1 >= str.length || str[i + 1] != '\n') { cr++; lines++; if (!lineHasText) blank++; lineHasText = false; if (cur > maxLine) maxLine = cur; cur = 0; inWord = false } }
            else { cur++; if (c.isWhitespace()) inWord = false else { lineHasText = true; if (!inWord) { words++; inWord = true } } }
            i++
        }
        if (cur > 0) { lines++; if (cur > maxLine) maxLine = cur }
        val partial = len > TEXT_CAP
        val suffix = if (partial) " (first ${fmtSize(TEXT_CAP)} only)" else ""
        s.row("Line endings", listOfNotNull(if (lf > 0) "LF" else null, if (crlf > 0) "CRLF" else null, if (cr > 0) "CR" else null).joinToString(" + ").let { if (it.isEmpty()) null else if (it.contains('+')) "Mixed ($it)" else it + (if (it == "CRLF") " (Windows)" else if (it == "LF") " (Unix)" else "") })
        s.row("Lines", String.format(Locale.US, "%,d", lines) + suffix)
        if (blank > 0) s.row("Blank lines", blank)
        s.row("Words", words); s.row("Characters", str.length.toLong()); s.row("Longest line", maxLine.toLong())
        when (ext) {
            "csv", "tsv" -> csv(str, if (ext == "tsv") '\t' else sniffDelim(str), s, partial)
            "json" -> json(str, s, partial)
            "html", "htm", "xhtml" -> { s.row("Page title", Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(str)?.groupValues?.get(1)?.let { xmlUnescape(it) }) }
            "xml", "kml", "gpx" -> s.row("Root element", Regex("<([A-Za-z_][\\w:.-]*)[\\s>/]").findAll(str.replace(Regex("<\\?[^>]*\\?>|<!--.*?-->|<!DOCTYPE[^>]*>", RegexOption.DOT_MATCHES_ALL), "")).firstOrNull()?.groupValues?.get(1))
            "srt", "vtt", "ass", "ssa", "lrc" -> {
                val cues = if (ext == "srt") Regex("-->").findAll(str).count() else if (ext == "vtt") Regex("-->").findAll(str).count() else Regex("(?m)^Dialogue:").findAll(str).count().let { if (ext == "lrc") Regex("(?m)^\\[\\d+:\\d+").findAll(str).count() else it }
                if (cues > 0) s.row("Subtitle cues", cues.toLong())
            }
            "m3u", "m3u8", "pls" -> s.row("Entries", str.lineSequence().count { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("[") && !it.startsWith("NumberOfEntries") && !it.startsWith("Version") }.toLong())
            "vcf" -> s.row("Contacts", Regex("(?m)^BEGIN:VCARD").findAll(str).count().toLong())
            "ics" -> s.row("Events", Regex("(?m)^BEGIN:VEVENT").findAll(str).count().toLong())
        }
        return s
    }
    private fun validUtf8(b: ByteArray): Boolean {
        // a multi-byte character cut by the read limit is not an error: also try without the last 1-3 bytes
        for (cut in 0..3) {
            if (cut >= b.size) break
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try { dec.decode(ByteBuffer.wrap(b, 0, b.size - cut)); return true } catch (_: CharacterCodingException) {}
        }
        return false
    }
    private fun sniffDelim(s: String): Char { val l = s.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','; return listOf(',', ';', '\t', '|').maxByOrNull { d -> l.count { it == d } } ?: ',' }
    private fun csv(str: String, d: Char, s: Sec, partial: Boolean) {
        var rows = 0L; var cols = 0; var inQ = false; var cc = 1; var any = false
        for (ch in str) {
            if (ch == '"') inQ = !inQ
            else if (!inQ && ch == d) { cc++; any = true }
            else if (!inQ && ch == '\n') { if (rows == 0L) cols = cc; rows++; cc = 1; any = false } else if (!ch.isWhitespace()) any = true
        }
        if (any) rows++
        s.row("Delimiter", when (d) { ',' -> "Comma"; ';' -> "Semicolon"; '\t' -> "Tab"; else -> "Pipe" })
        s.row("Columns", cols.toLong()); s.row("Rows" + if (partial) " (so far)" else "", rows)
    }
    private fun json(str: String, s: Sec, partial: Boolean) {
        if (partial) return
        try {
            val t = str.trimStart('\uFEFF').trim()
            if (t.startsWith("[")) { val a = JSONArray(t); s.row("JSON", "Valid · array with ${a.length()} items") }
            else if (t.startsWith("{")) { val o = JSONObject(t); s.row("JSON", "Valid · object with ${o.length()} keys") }
            else s.row("JSON", "Valid")
        } catch (e: Throwable) { s.row("JSON", "Not valid: " + (e.message ?: "parse error").take(100)) }
    }

    // ------------------------------------------------------------------ fallback: identify by magic bytes
    private fun sniff(f: File, ext: String): Sec? {
        val b = readHead(f, 600)
        if (b.isEmpty()) return Sec("Content").also { it.row("Content", "Empty file") }
        fun starts(vararg x: Int) = b.size >= x.size && x.indices.all { (b[it].toInt() and 0xFF) == x[it] }
        fun at(o: Int, t: String) = b.size >= o + t.length && String(b, o, t.length, Charsets.ISO_8859_1) == t
        val kind = when {
            starts(0x89, 0x50, 0x4E, 0x47) -> "PNG image"; starts(0xFF, 0xD8, 0xFF) -> "JPEG image"; at(0, "GIF8") -> "GIF image"; at(0, "BM") -> "BMP image"
            at(0, "RIFF") && at(8, "WEBP") -> "WebP image"; at(0, "RIFF") && at(8, "WAVE") -> "WAV audio"; at(0, "RIFF") && at(8, "AVI ") -> "AVI video"
            at(4, "ftyp") -> "MP4 / QuickTime family (" + String(b, 8, 4, Charsets.ISO_8859_1).trim() + ")"
            starts(0x1A, 0x45, 0xDF, 0xA3) -> "Matroska / WebM video"; at(0, "OggS") -> "Ogg media"; at(0, "fLaC") -> "FLAC audio"; at(0, "ID3") -> "MP3 audio (ID3 tag)"
            at(0, "%PDF") -> "PDF document"; at(0, "PK") -> "ZIP-based container"; starts(0x1F, 0x8B) -> "gzip data"; at(0, "BZh") -> "bzip2 data"
            starts(0xFD, 0x37, 0x7A, 0x58, 0x5A) -> "xz data"; starts(0x37, 0x7A, 0xBC, 0xAF) -> "7-Zip archive"; at(0, "Rar!") -> "RAR archive"
            at(0, "SQLite format 3") -> "SQLite database"; starts(0x7F, 0x45, 0x4C, 0x46) -> "ELF executable"; at(0, "MZ") -> "Windows executable (PE)"
            starts(0xD0, 0xCF, 0x11, 0xE0) -> "Legacy Microsoft Office / OLE document"; at(0, "{\\rtf") -> "Rich Text document"
            at(0, "dex\n") -> "Android Dalvik executable"; at(0, "wOFF") || at(0, "wOF2") -> "Web font"; at(0, "<?xml") -> "XML document"
            else -> null
        }
        val s = Sec("Content")
        if (kind != null) { s.row("Detected type", kind); return s }
        if (!b.any { it == 0.toByte() }) { text(f, ext)?.let { return it.also { t -> t.rows.add(0, "Detected type" to "Plain text") } } }
        s.row("Detected type", "Binary data (no known signature)")
        return s
    }
}
