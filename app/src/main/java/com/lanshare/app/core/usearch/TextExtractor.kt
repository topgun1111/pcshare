package com.lanshare.app.core.usearch

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Plain text out of one text file (Universal Search Part 5). Pure JVM, no android imports.
 * Encoding: UTF-8 BOM, UTF-16 BOM, UTF-16 without BOM (zero-byte pattern), strict UTF-8, else Windows-1254 (Turkish ANSI).
 * Binary files (NUL bytes outside UTF-16, or many control bytes) give null. Markup (html / xml) is stripped to its text.
 * Line breaks are normalised to LF. The result is deterministic, so a position found in the stored (folded) text is the same
 * position in a fresh extraction of the unchanged file (Fold keeps the length).
 */
object TextExtractor {
    val DEFAULT_EXTS: Set<String> = setOf(
        "txt", "md", "csv", "tsv", "log", "json", "xml", "html", "htm", "xhtml", "css", "js", "ts", "kt", "kts", "java", "py", "c", "cpp", "h",
        "cs", "php", "sql", "ini", "cfg", "conf", "yml", "yaml", "properties", "srt", "vtt", "tex", "bat", "sh", "gradle", "lrc", "gpx", "svg"
    )
    private val MARKUP = setOf("html", "htm", "xhtml", "xml", "svg", "gpx")

    class Result(val text: String, val charset: String)

    /** null = not text (binary), unreadable, or larger than [maxBytes]. */
    fun extract(f: File, ext: String, maxBytes: Long): Result? {
        val len = f.length()
        if (len <= 0L || len > maxBytes) return null
        val b = try { f.readBytes() } catch (_: Throwable) { return null }
        return decode(b, ext)
    }

    fun decode(b: ByteArray, ext: String): Result? {
        var off = 0
        var cs: String? = null
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) { off = 3; cs = "UTF-8" }
        else if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) { off = 2; cs = "UTF-16LE" }
        else if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) { off = 2; cs = "UTF-16BE" }
        val n = minOf(b.size, 8192)
        if (cs == null) {
            var even = 0; var odd = 0; var ctl = 0
            for (i in 0 until n) {
                val v = b[i].toInt() and 0xFF
                if (v == 0) { if (i % 2 == 0) even++ else odd++ }
                else if (v < 9 || (v in 14..31 && v != 27)) ctl++
            }
            val zeros = even + odd
            if (zeros * 10 > n * 3 && n >= 4) cs = if (odd >= even) "UTF-16LE" else "UTF-16BE"   // mostly ASCII text in UTF-16
            else if (zeros > 0 || ctl * 50 > n) return null                                        // binary
        }
        var s: String
        if (cs != null && cs.startsWith("UTF-16")) {
            s = String(b, off, b.size - off, Charset.forName(cs))
        } else if (cs == "UTF-8") {
            s = String(b, off, b.size - off, Charsets.UTF_8)
        } else {
            val dec: CharsetDecoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val u = try { dec.decode(ByteBuffer.wrap(b)).toString() } catch (_: Throwable) { null }
            if (u != null) { s = u; cs = "UTF-8" }
            else {
                val c = try { Charset.forName("windows-1254") } catch (_: Throwable) { Charsets.ISO_8859_1 }
                s = String(b, c); cs = c.name()
            }
        }
        if (s.indexOf('\u0000') >= 0) s = s.replace("\u0000", "")
        if (ext in MARKUP) s = stripMarkup(s)
        if (s.indexOf('\r') >= 0) s = s.replace("\r\n", "\n").replace('\r', '\n')
        return Result(s, cs ?: "UTF-8")
    }

    private val RE_BLOCK = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val RE_TAG = Regex("<[^>]*>")

    fun stripMarkup(s: String): String {
        var t = RE_COMMENT.replace(s, " ")
        t = RE_BLOCK.replace(t, " ")
        t = RE_TAG.replace(t, " ")
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
}
