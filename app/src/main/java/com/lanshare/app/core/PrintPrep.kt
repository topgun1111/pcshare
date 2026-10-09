package com.lanshare.app.core

import android.graphics.BitmapFactory
import android.print.WebPdf
import android.util.Base64
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * Makes every file printable with the full layout options: the PC service (pcprint.py) only knows pdf / pictures / txt / office,
 * so everything else is turned into one of those HERE, on the phone, with what Android decodes natively:
 *  - pictures Android can decode (webp, heic, avif, ico ...) -> JPEG
 *  - html / svg -> PDF (WebView print adapter, no scripts, no network)
 *  - code / config / any plain text (also files without extension) -> .txt
 *  - unknown extension -> looked at inside (PDF, Office/OpenDocument, RTF, picture, HTML/SVG, text) and sent under the right name
 * pcprint.py needs no update.
 */
object PrintPrep {
    enum class Kind { PASS, PIC, WEB, TEXT, SNIFF, NO }

    class Out(val file: File, val name: String)

    /** printed by the PC service as they are (pcprint.py ALLOWED) */
    private val PC = setOf("pdf", "txt", "log", "md", "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff",
        "doc", "docx", "rtf", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp")
    val PICS = setOf("webp", "heic", "heif", "avif", "ico", "wbmp", "dng", "jfif", "jpe", "jfi")
    val WEB = setOf("html", "htm", "xhtml", "svg")
    val TEXT = setOf("php", "phtml", "js", "mjs", "cjs", "ts", "tsx", "jsx", "css", "scss", "sass", "less", "json", "jsonl", "ndjson", "geojson",
        "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "env", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb",
        "sh", "bash", "zsh", "bat", "cmd", "ps1", "gradle", "properties", "tsv", "srt", "vtt", "ass", "ssa", "swift", "dart", "lua", "pl", "pm", "r",
        "scala", "vb", "vbs", "asm", "jl", "ex", "exs", "erl", "hs", "clj", "vue", "svelte", "graphql", "proto", "tex", "bib", "rst", "adoc", "org",
        "ics", "vcf", "eml", "nfo", "lrc", "diff", "patch", "gitignore", "gitattributes", "editorconfig", "dockerfile", "makefile", "mk", "cmake",
        "reg", "inf", "m3u", "m3u8", "har", "lock", "text")
    /** never printable - refused at once instead of downloading the whole file first */
    private val NO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "3gp", "mp3", "wav", "flac", "aac", "ogg", "opus",
        "m4a", "wma", "apk", "xapk", "aab", "exe", "msi", "dll", "so", "bin", "iso", "img", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "dex",
        "db", "sqlite", "lspart")
    const val SNIFF_MAX = 120L shl 20   // unknown types are fetched to the phone before they are looked at

    fun kind(ext: String): Kind = when {
        ext in PC -> Kind.PASS
        ext in PICS -> Kind.PIC
        ext in WEB -> Kind.WEB
        ext in TEXT -> Kind.TEXT
        ext in NO -> Kind.NO
        else -> Kind.SNIFF
    }

    /** Converts the downloaded copy [f] of [name]. Returns the file to send (may be [f] itself) and the name to send it under. */
    fun convert(kind: Kind, f: File, name: String): Out {
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        return when (kind) {
            Kind.PIC -> jpeg(f, base)
            Kind.WEB -> web(f, base, ext == "svg")
            Kind.SNIFF -> sniff(f, base, name)
            else -> throw IOException("nothing to convert")
        }
    }

    /** JPEGs bigger than this are looked at and, if their pixels exceed what the sheet needs, shrunk before the upload (pcprint.py does the same with Pillow). */
    const val SHRINK_MIN = 1_500_000L

    /** Longest side (about 300 dpi for one cell of the sheet) that a picture needs: 1 / 2-4 / 6-9 pictures per sheet. */
    fun printPx(nup: Int, paper: String): Int {
        val base = if (nup >= 6) 1754 else if (nup >= 2) 2480 else 3508
        val k = when (paper) { "A3" -> 1.41; "A5" -> 0.71; else -> 1.0 }
        return (base * k).toInt()
    }

    /** A big JPEG [f] -> smaller JPEG with the EXIF turn applied; [f] itself when it is not larger than needed. */
    fun shrink(f: File, name: String, maxPx: Int): Out {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }
        if (maxOf(b.outWidth, b.outHeight) <= maxPx * 1.15) return Out(f, name)
        val o = tmp(f, ".s.jpg")
        o.writeBytes(Thumbs.forPrintMax(f, maxPx))
        return Out(o, name)
    }

    /** Markdown -> PDF (WebView print, no scripts / network): headings, lists, quotes, code, tables, bold / italic / strike / links. */
    fun md(f: File, base: String): Out {
        val ctx = Core.appCtx ?: throw IOException("app not ready")
        if (f.length() > 8L shl 20) throw IOException("the file is too large to convert")
        val o = tmp(f, ".md.pdf")
        WebPdf.render(ctx, mdHtml(decode(f.readBytes()).removePrefix("\uFEFF")), o, 400)
        return Out(o, "$base.pdf")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun inline(t0: String): String {
        var t = esc(t0)
        t = Regex("`([^`]+)`").replace(t) { "<code>" + it.groupValues[1] + "</code>" }
        t = Regex("!?\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)").replace(t) { it.groupValues[1] }   // links / images: the text only (no network)
        t = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(t) { "<b>" + it.groupValues[1] + it.groupValues[2] + "</b>" }
        t = Regex("~~(.+?)~~").replace(t) { "<s>" + it.groupValues[1] + "</s>" }
        t = Regex("\\*(?!\\s)([^*]+?)(?<!\\s)\\*|(?<!\\w)_(?!\\s)([^_]+?)(?<!\\s)_(?!\\w)").replace(t) { "<i>" + it.groupValues[1] + it.groupValues[2] + "</i>" }
        return t
    }

    private fun mdHtml(src: String): String {
        val sb = StringBuilder("<html><head><meta charset=\"utf-8\"><style>" +
            "body{font-family:sans-serif;font-size:11pt;line-height:1.45;color:#000}h1,h2,h3,h4{margin:.9em 0 .3em}h1{font-size:20pt}h2{font-size:16pt}h3{font-size:13pt}" +
            "pre{background:#f1f1f1;padding:8px;white-space:pre-wrap;font-size:9.5pt}code{background:#f1f1f1;font-family:monospace}" +
            "blockquote{border-left:3px solid #999;margin:.5em 0;padding-left:10px;color:#444}table{border-collapse:collapse}td,th{border:1px solid #999;padding:3px 7px}" +
            "hr{border:0;border-top:1px solid #999}</style></head><body>")
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        var list = ""   // "ul" / "ol" while a list is open
        fun closeList() { if (list.isNotEmpty()) { sb.append("</$list>"); list = "" } }
        val table = Regex("^\\s*\\|?.*\\|.*$")
        val sep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
        while (i < lines.size) {
            val ln = lines[i]
            val t = ln.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    closeList(); i++
                    sb.append("<pre>")
                    while (i < lines.size && !lines[i].trim().startsWith("```") && !lines[i].trim().startsWith("~~~")) { sb.append(esc(lines[i])).append('\n'); i++ }
                    sb.append("</pre>")
                }
                t.isEmpty() -> closeList()
                Regex("^(-\\s*){3,}$|^(\\*\\s*){3,}$|^(_\\s*){3,}$").matches(t) -> { closeList(); sb.append("<hr>") }
                Regex("^#{1,6}\\s+.*").matches(t) -> {
                    closeList()
                    val n = t.takeWhile { it == '#' }.length
                    sb.append("<h$n>").append(inline(t.substring(n).trim().trimEnd('#').trim())).append("</h$n>")
                }
                t.startsWith(">") -> { closeList(); sb.append("<blockquote>").append(inline(t.removePrefix(">").trim())).append("</blockquote>") }
                Regex("^([-*+]|\\d+[.)])\\s+.*").matches(t) -> {
                    val kind = if (t[0].isDigit()) "ol" else "ul"
                    if (list != kind) { closeList(); sb.append("<$kind>"); list = kind }
                    sb.append("<li>").append(inline(t.replaceFirst(Regex("^([-*+]|\\d+[.)])\\s+"), ""))).append("</li>")
                }
                i + 1 < lines.size && table.matches(ln) && sep.matches(lines[i + 1]) -> {
                    closeList()
                    fun cells(r: String) = r.trim().trim('|').split('|').map { inline(it.trim()) }
                    sb.append("<table><tr>").append(cells(ln).joinToString("") { "<th>$it</th>" }).append("</tr>")
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { sb.append("<tr>").append(cells(lines[i]).joinToString("") { "<td>$it</td>" }).append("</tr>"); i++ }
                    sb.append("</table>")
                    continue
                }
                else -> { closeList(); sb.append("<p>").append(inline(t)).append("</p>") }
            }
            i++
        }
        closeList()
        return sb.append("</body></html>").toString()
    }

    private fun tmp(f: File, suffix: String) = File(f.parentFile, f.name + suffix)

    private fun jpeg(f: File, base: String): Out {
        val o = tmp(f, ".jpg")
        o.writeBytes(Thumbs.forPrint(f))
        return Out(o, "$base.jpg")
    }

    private fun decode(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8)
        return if (s.indexOf('\uFFFD') >= 0) String(b, Charset.forName("windows-1254")) else s
    }

    private fun web(f: File, base: String, svg: Boolean): Out {
        val ctx = Core.appCtx ?: throw IOException("app not ready")
        if (f.length() > 25L shl 20) throw IOException("the page is too large to convert")
        val bytes = f.readBytes()
        val html = if (svg) "<html><body style=\"margin:0\"><img style=\"width:100%\" src=\"data:image/svg+xml;base64," +
            Base64.encodeToString(bytes, Base64.NO_WRAP) + "\"></body></html>" else decode(bytes)
        val o = tmp(f, ".pdf")
        WebPdf.render(ctx, html, o, if (svg) 0 else 400)
        return Out(o, "$base.pdf")
    }

    // ---- unknown extension: look inside
    private fun sniff(f: File, base: String, name: String): Out {
        if (f.length() > SNIFF_MAX) throw IOException("this file type cannot be printed")
        val head = ByteArray(8192)
        val n = f.inputStream().use { readFully(it, head) }
        fun b(vararg v: Int) = n >= v.size && v.indices.all { (head[it].toInt() and 0xFF) == v[it] }
        fun same(newExt: String) = Out(f, "$base.$newExt")
        if (n == 0) throw IOException("the file is empty")
        if (b(0x25, 0x50, 0x44, 0x46)) return same("pdf")                                   // %PDF
        if (b(0x7B, 0x5C, 0x72, 0x74, 0x66)) return same("rtf")                              // {\rtf
        if (b(0x50, 0x4B, 0x03, 0x04)) zipKind(f)?.let { return same(it) }                   // OOXML / OpenDocument
        if (b(0xD0, 0xCF, 0x11, 0xE0)) oleKind(f)?.let { return same(it) }                  // legacy doc / xls / ppt
        val text = String(head, 0, n, Charsets.ISO_8859_1).lowercase()
        if (BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.outWidth > 0) return jpeg(f, base)
        if (text.contains("<svg")) return web(f, base, true)
        if (text.contains("<!doctype html") || text.contains("<html")) return web(f, base, false)
        // UTF-16 text with a byte-order mark -> UTF-8
        if (b(0xFF, 0xFE) || b(0xFE, 0xFF)) {
            val o = tmp(f, ".txt")
            o.writeText(String(f.readBytes(), Charset.forName("UTF-16")), Charsets.UTF_8)
            return Out(o, "$base.txt")
        }
        var odd = 0
        for (i in 0 until n) { val c = head[i].toInt() and 0xFF; if (c == 0 || (c < 9) || (c in 14..31 && c != 27)) odd++ }
        if (odd == 0 || odd * 100 < n) return Out(f, "$name.txt")
        throw IOException("this file type cannot be printed")
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray): Int {
        var t = 0
        while (t < b.size) { val r = i.read(b, t, b.size - t); if (r < 0) break; t += r }
        return t
    }

    private fun zipKind(f: File): String? = try {
        ZipFile(f).use { z ->
            when {
                z.getEntry("word/document.xml") != null -> "docx"
                z.getEntry("xl/workbook.xml") != null -> "xlsx"
                z.getEntry("ppt/presentation.xml") != null -> "pptx"
                else -> z.getEntry("mimetype")?.let { e ->
                    val m = z.getInputStream(e).use { String(it.readBytes(), Charsets.US_ASCII) }
                    when {
                        m.endsWith("opendocument.text") -> "odt"
                        m.endsWith("opendocument.spreadsheet") -> "ods"
                        m.endsWith("opendocument.presentation") -> "odp"
                        else -> null
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    /** Compound-file Office documents: the directory entries name the main stream (UTF-16LE). */
    private fun oleKind(f: File): String? {
        val probes = listOf("WordDocument" to "doc", "Workbook" to "xls", "PowerPoint Document" to "ppt")
            .map { (s, e) -> s.toByteArray(Charsets.UTF_16LE) to e }
        val keep = probes.maxOf { it.first.size }
        val buf = ByteArray((1 shl 20) + keep)
        var carry = 0
        var seen = 0L
        try {
            f.inputStream().use { i ->
                while (seen < (48L shl 20)) {
                    val r = i.read(buf, carry, (1 shl 20))
                    if (r < 0) break
                    val len = carry + r
                    for ((pat, ext) in probes) if (indexOf(buf, len, pat) >= 0) return ext
                    carry = minOf(keep, len)
                    System.arraycopy(buf, len - carry, buf, 0, carry)
                    seen += r
                }
            }
        } catch (_: IOException) {}
        return null
    }

    private fun indexOf(h: ByteArray, len: Int, p: ByteArray): Int {
        var i = 0
        outer@ while (i <= len - p.size) {
            for (j in p.indices) if (h[i + j] != p[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
