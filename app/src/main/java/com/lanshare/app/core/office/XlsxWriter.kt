package com.lanshare.app.core.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A sheet to write: [rows] of text, typed automatically like [XValue.kind] (numbers, TRUE/FALSE, "=formulas", text). */
class WSheet(val name: String, val rows: List<List<String>>)

/**
 * Creates brand-new .xlsx files (pure java.util.zip, no android.*). Text is written as inline strings.
 * Options: [headerBold] makes row 1 bold, column widths are guessed from the first 200 rows. Writes to a temp file, then renames.
 */
object XlsxWriter {
    const val EMPTY_SHEET = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>"

    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val HDR = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /** Empty workbook with one sheet. */
    fun createEmpty(out: File, sheetName: String = "Sheet1") = write(out, listOf(WSheet(sheetName, emptyList())))

    /** CSV / TSV text -> one-sheet workbook. Delimiter is detected (, ; tab) unless given. */
    fun fromCsv(out: File, csv: String, sheetName: String = "Sheet1", delimiter: Char? = null, headerBold: Boolean = true) =
        write(out, listOf(WSheet(sheetName, parseCsv(csv, delimiter))), headerBold)

    fun write(out: File, sheets: List<WSheet>, headerBold: Boolean = true) {
        require(sheets.isNotEmpty()) { "No sheets" }
        val names = HashSet<String>()
        for (s in sheets) {
            val n = s.name.trim()
            require(n.isNotEmpty() && n.length <= 31 && n.none { it in "[]:*?/\\" } && names.add(n.lowercase())) { "Bad or duplicate sheet name: ${s.name}" }
        }
        val tmp = File(out.absoluteFile.parentFile, out.name + ".tmp~")
        try {
            ZipOutputStream(FileOutputStream(tmp).buffered()).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
                val ct = StringBuilder(HDR + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
                val wb = StringBuilder(HDR + "<workbook xmlns=\"$NS\" xmlns:r=\"$RNS\"><sheets>")
                val rels = StringBuilder(HDR + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                sheets.forEachIndexed { i, s ->
                    val n = i + 1
                    ct.append("<Override PartName=\"/xl/worksheets/sheet$n.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
                    wb.append("<sheet name=\"${XValue.esc(s.name.trim())}\" sheetId=\"$n\" r:id=\"rId$n\"/>")
                    rels.append("<Relationship Id=\"rId$n\" Type=\"$RNS/worksheet\" Target=\"worksheets/sheet$n.xml\"/>")
                }
                rels.append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"$RNS/styles\" Target=\"styles.xml\"/></Relationships>")
                put("[Content_Types].xml", ct.append("</Types>").toString())
                put("_rels/.rels", HDR + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$RNS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                put("xl/workbook.xml", wb.append("</sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>").toString())
                put("xl/_rels/workbook.xml.rels", rels.toString())
                put("xl/styles.xml", STYLES)
                sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s.rows, headerBold)) }
            }
            if (out.exists() && !out.delete()) error("Cannot replace ${out.name}")
            if (!tmp.renameTo(out)) error("Cannot create ${out.name}")
        } catch (t: Throwable) { tmp.delete(); throw t }
    }

    // xf 0 = default, xf 1 = bold (header)
    private const val STYLES = HDR + "<styleSheet xmlns=\"$NS\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun sheetXml(rows: List<List<String>>, headerBold: Boolean): String {
        val sb = StringBuilder(HDR + "<worksheet xmlns=\"$NS\">")
        var maxC = 0
        for (r in rows) if (r.size > maxC) maxC = r.size
        if (rows.isNotEmpty() && maxC > 0) sb.append("<dimension ref=\"A1:${XRef.name(maxC - 1, rows.size - 1)}\"/>")
        if (headerBold && rows.size > 1) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        if (maxC > 0) {
            sb.append("<cols>")
            for (c in 0 until maxC) {
                var w = 0
                for (i in 0 until minOf(rows.size, 200)) { val v = rows[i].getOrNull(c) ?: continue; val l = if (v.length > 60) 60 else v.length; if (l > w) w = l }
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"${(w + 2).coerceIn(8, 62)}\" customWidth=\"1\"/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        for ((ri, r) in rows.withIndex()) {
            if (r.isEmpty()) continue
            sb.append("<row r=\"${ri + 1}\">")
            val st = if (headerBold && ri == 0 && rows.size > 1) " s=\"1\"" else ""
            for ((ci, v) in r.withIndex()) { if (v.isEmpty() && st.isEmpty()) continue; sb.append(XValue.cellXml(XRef.name(ci, ri), st, v, false)) }
            sb.append("</row>")
        }
        return sb.append("</sheetData></worksheet>").toString()
    }

    /** RFC-4180 style CSV (quotes, doubled quotes, newlines inside quotes). BOM stripped. Trailing empty lines dropped. */
    fun parseCsv(text: String, delimiter: Char? = null): List<List<String>> {
        val t = text.removePrefix("\uFEFF")
        val d = delimiter ?: detect(t)
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val f = StringBuilder()
        var q = false; var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (q) {
                if (ch == '"') { if (i + 1 < t.length && t[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(ch)
            } else when (ch) {
                '"' -> if (f.isEmpty()) q = true else f.append(ch)
                d -> { row.add(f.toString()); f.setLength(0) }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList() }
                else -> f.append(ch)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.size - 1)
        return rows
    }

    private fun detect(t: String): Char {
        val line = t.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        var best = ','; var n = -1
        for (c in charArrayOf(',', ';', '\t')) { val k = line.count { it == c }; if (k > n) { n = k; best = c } }
        return best
    }
}
