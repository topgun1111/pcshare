package com.lanshare.app.core.office

import java.io.File

/**
 * Part 8: plain-text extraction (folder content search) and CSV export. No android.* imports.
 * All functions swallow nothing: callers decide how to treat password-protected / corrupt files
 * ([textOrNull] / [containsOrFalse] are the "never throws" variants meant for bulk search).
 */
object OfficeExport {
    val EXTS = setOf("doc", "docx", "odt", "xls", "xlsx", "xlsm", "ods", "pptx", "odp")

    fun isOffice(name: String) = name.substringAfterLast('.', "").lowercase() in EXTS

    /** RFC 4180 CSV of one sheet (displayed text, as the viewer shows it). Hidden rows/cols skipped; merged-cell tails stay empty. */
    fun sheetToCsv(s: XSheet, bom: Boolean = true, sep: Char = ','): String {
        val sb = StringBuilder(); if (bom) sb.append('\uFEFF')
        if (s.maxRow < 0) return sb.toString()
        val cols = (0..s.maxCol).filter { it !in s.hiddenCols }
        for (r in 0..s.maxRow) {
            if (r in s.hiddenRows) continue
            val byCol = HashMap<Int, XCell>(); s.rows[r]?.forEach { byCol[it.col] = it }
            var first = true
            for (c in cols) {
                if (!first) sb.append(sep); first = false
                esc(sb, byCol[c]?.text.orEmpty(), sep)
            }
            sb.append("\r\n")
        }
        return sb.toString()
    }

    private fun esc(sb: StringBuilder, t: String, sep: Char) {
        val q = t.any { it == sep || it == '"' || it == '\n' || it == '\r' } || t.startsWith(" ") || t.endsWith(" ")
        if (!q) { sb.append(t); return }
        sb.append('"'); for (ch in t) { if (ch == '"') sb.append('"'); sb.append(ch) }; sb.append('"')
    }

    /** Safe file name for a sheet export: "Book - Sheet1.csv". */
    fun csvName(file: String, sheet: String): String =
        (file.substringBeforeLast('.') + " - " + sheet).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".csv"

    /** Whole-file plain text, capped at [maxChars]. Sheets: every visible sheet, tab-separated, `## name` headers. Throws like [OfficeOpen]. */
    fun text(file: File, maxChars: Int = 400_000): String = OfficeOpen(file).use { o ->
        val sb = StringBuilder()
        if (!o.isSheet) sb.append(o.readDoc(5000).let { d -> d.plainText() + d.plainText(d.header) + d.plainText(d.footer) })
        else for (si in o.sheets) {
            if (si.hidden || sb.length >= maxChars) continue
            sb.append("## ").append(si.name).append('\n')
            val sh = o.readSheet(si.index, 3000, 100, 60_000)
            for ((_, row) in sh.rows) {
                var first = true
                for (c in row) { if (c.text.isEmpty()) continue; if (!first) sb.append('\t'); first = false; sb.append(c.text) }
                sb.append('\n')
                if (sb.length >= maxChars) break
            }
        }
        if (sb.length > maxChars) sb.setLength(maxChars)
        sb.toString()
    }

    fun textOrNull(file: File, maxChars: Int = 400_000): String? = try { text(file, maxChars) } catch (_: Throwable) { null }

    /** Case-insensitive content match for folder search; false for unreadable / encrypted / oversize files (> [maxBytes]). */
    fun containsOrFalse(file: File, q: String, maxBytes: Long = 30L shl 20): Boolean {
        if (q.isEmpty() || !isOffice(file.name) || file.length() > maxBytes) return false
        return textOrNull(file)?.contains(q, ignoreCase = true) == true
    }
}
