package com.lanshare.app.core.office

import java.io.File
import java.util.TreeMap

/**
 * Read-only .xlsx / .xlsm / .xltx reader. Streams each sheet (no DOM), formats values with the workbook's own number formats.
 * Usage: XlsxReader(file).use { it.sheets; it.readSheet(0) }
 * Limits protect a phone: [maxRows] x [maxCols] cells per sheet and [maxCells] overall; [XSheet.truncated] tells the UI.
 */
class XlsxReader(file: File) : AutoCloseable {
    private val pkg = OoxmlPackage(file)
    val sheets: List<XSheetInfo>
    val date1904: Boolean
    val styles: XStyles
    private val sst: Array<String>

    init {
        val wbPart = findWorkbookPart() ?: throw IllegalArgumentException("Not an Excel workbook (no xl/workbook.xml)")
        val rels = pkg.rels(wbPart)
        val list = ArrayList<XSheetInfo>()
        var d1904 = false
        pkg.xml(wbPart)?.let { x ->
            while (true) {
                when (x.next()) {
                    MiniXml.T.EOF -> break
                    MiniXml.T.START -> when (x.name) {
                        "workbookPr" -> d1904 = x.attr("date1904").let { it == "1" || it.equals("true", true) }
                        "sheet" -> {
                            val rid = x.rawAttr("r:id") ?: x.attr("id")
                            val rel = rid?.let { rels[it] }
                            val st = x.attr("state") ?: "visible"
                            if (rel != null && !rel.external) list.add(XSheetInfo(list.size, MiniXml.unescapeOoxml(x.attr("name") ?: "Sheet${list.size + 1}"), rel.target, st == "hidden", st == "veryHidden", x.attr("sheetId")))
                        }
                    }
                    else -> {}
                }
            }
        }
        sheets = list; date1904 = d1904
        val sstPart = rels.values.firstOrNull { it.kind == "sharedStrings" }?.target ?: "xl/sharedStrings.xml"
        sst = readSharedStrings(sstPart)
        val stPart = rels.values.firstOrNull { it.kind == "styles" }?.target ?: "xl/styles.xml"
        styles = readStyles(stPart)
    }

    private fun findWorkbookPart(): String? {
        val x = pkg.xml("_rels/.rels")
        if (x != null) while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> if (x.name == "Relationship" && (x.attr("Type") ?: "").endsWith("/officeDocument")) {
                    val t = x.attr("Target") ?: continue
                    val p = OoxmlPackage.resolve("", t); if (pkg.has(p)) return p
                }
                else -> {}
            }
        }
        return if (pkg.has("xl/workbook.xml")) "xl/workbook.xml" else null
    }

    // ------------------------------------------------------------------ shared strings
    private fun readSharedStrings(part: String): Array<String> {
        val x = pkg.xml(part) ?: return emptyArray()
        val out = ArrayList<String>(1024)
        while (true) {
            val t = x.next(); if (t == MiniXml.T.EOF) break
            if (t == MiniXml.T.START && x.name == "si") out.add(readSi(x))
        }
        return out.toTypedArray()
    }

    /** Concatenate <t> of plain and rich runs, skipping phonetic <rPh>. Parser is left on the closing </si> / </is>. */
    private fun readSi(x: MiniXml): String {
        val d = x.depth; val sb = StringBuilder(); var inPh = 0
        if (x.type == MiniXml.T.START && x.depth == d) { /* normal */ }
        while (true) {
            when (x.next()) {
                MiniXml.T.START -> when (x.name) { "rPh" -> inPh++; "t" -> { val s = x.readText(); if (inPh == 0) sb.append(s) } }
                MiniXml.T.END -> { if (x.name == "rPh") inPh--; else if (x.depth < d) return MiniXml.unescapeOoxml(sb.toString()) }
                MiniXml.T.EOF -> return MiniXml.unescapeOoxml(sb.toString())
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ styles
    private fun readStyles(part: String): XStyles {
        val x = pkg.xml(part) ?: return XStyles.EMPTY
        val numFmts = HashMap<Int, String>(); val fonts = ArrayList<XFont>(); val fills = ArrayList<String?>(); val xfs = ArrayList<XXf>()
        var inFonts = false; var inFills = false; var inCellXfs = false
        // current font
        var fb = false; var fi = false; var fu = false; var fc: String? = null; var fs = 11.0; var fn: String? = null; var inFont = false
        var inFill = false; var fillColor: String? = null
        var xfNum = 0; var xfFont = 0; var xfFill = 0; var xfH: String? = null; var xfW = false; var xfV: String? = null; var inXf = false
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> when (x.name) {
                    "numFmt" -> { val id = x.attr("numFmtId")?.toIntOrNull(); val c = x.attr("formatCode"); if (id != null && c != null) numFmts[id] = c }
                    "fonts" -> inFonts = true
                    "fills" -> inFills = true
                    "cellXfs" -> inCellXfs = true
                    "font" -> if (inFonts) { inFont = true; fb = false; fi = false; fu = false; fc = null; fs = 11.0; fn = null }
                    "b" -> if (inFont) fb = on(x)
                    "i" -> if (inFont) fi = on(x)
                    "u" -> if (inFont) fu = x.attr("val") != "none"
                    "sz" -> if (inFont) fs = x.attr("val")?.toDoubleOrNull() ?: 11.0
                    "name" -> if (inFont) fn = x.attr("val")
                    "color" -> if (inFont) fc = x.attr("rgb")?.let { "#" + it.takeLast(6) }
                    "fill" -> if (inFills) { inFill = true; fillColor = null }
                    "fgColor" -> if (inFill && fillColor == null) fillColor = x.attr("rgb")?.let { "#" + it.takeLast(6) }
                    "patternFill" -> if (inFill && x.attr("patternType") == "none") fillColor = null
                    "xf" -> if (inCellXfs) {
                        inXf = true; xfNum = x.attr("numFmtId")?.toIntOrNull() ?: 0; xfFont = x.attr("fontId")?.toIntOrNull() ?: 0
                        xfFill = x.attr("fillId")?.toIntOrNull() ?: 0; xfH = null; xfW = false; xfV = null
                    }
                    "alignment" -> if (inXf) { xfH = x.attr("horizontal"); xfV = x.attr("vertical"); xfW = x.attr("wrapText").let { it == "1" || it == "true" } }
                }
                MiniXml.T.END -> when (x.name) {
                    "fonts" -> inFonts = false
                    "fills" -> inFills = false
                    "cellXfs" -> inCellXfs = false
                    "font" -> if (inFont) { fonts.add(XFont(fb, fi, fu, fc, fs, fn)); inFont = false }
                    "fill" -> if (inFill) { fills.add(fillColor); inFill = false }
                    "xf" -> if (inXf) { xfs.add(XXf(xfNum, xfFont, xfFill, xfH, xfW, xfV)); inXf = false }
                }
                else -> {}
            }
        }
        val dateXf = BooleanArray(xfs.size) { NumFmt.isDate(xfs[it].numFmtId, NumFmt.code(xfs[it].numFmtId, numFmts)) }
        return XStyles(xfs, fonts, fills, numFmts, dateXf)
    }
    private fun on(x: MiniXml): Boolean { val v = x.attr("val"); return v == null || !(v == "0" || v == "false") }

    // ------------------------------------------------------------------ sheets
    fun readSheet(index: Int, maxRows: Int = 5000, maxCols: Int = 200, maxCells: Int = 250_000): XSheet {
        val info = sheets[index]
        val x = pkg.xml(info.part) ?: return XSheet(info, TreeMap(), emptyList(), emptyMap(), emptySet(), emptySet(), -1, -1, 0, 0, false)
        val rows = TreeMap<Int, List<XCell>>(); val merges = ArrayList<XMerge>(); val widths = HashMap<Int, Double>()
        val hCols = HashSet<Int>(); val hRows = HashSet<Int>()
        var maxR = -1; var maxC = -1; var total = 0; var trunc = false; var fr = 0; var fc = 0
        var curRow = -1; var rowCells = ArrayList<XCell>(); var nextCol = 0
        fun flush() { if (rowCells.isNotEmpty()) { rows[curRow] = rowCells; rowCells = ArrayList() } }
        while (true) {
            val t = x.next(); if (t == MiniXml.T.EOF) break
            if (t == MiniXml.T.END) { if (x.name == "row") flush(); continue }
            if (t != MiniXml.T.START) continue
            when (x.name) {
                "col" -> {
                    val mn = (x.attr("min")?.toIntOrNull() ?: 1) - 1; val mx = ((x.attr("max")?.toIntOrNull() ?: 1) - 1).coerceAtMost(mn + 1000)
                    val w = x.attr("width")?.toDoubleOrNull(); val h = x.attr("hidden").let { it == "1" || it == "true" }
                    for (c in mn..mx) { if (w != null) widths[c] = w; if (h) hCols.add(c) }
                }
                "pane" -> if (x.attr("state")?.startsWith("frozen") == true) { fc = x.attr("xSplit")?.toDoubleOrNull()?.toInt() ?: 0; fr = x.attr("ySplit")?.toDoubleOrNull()?.toInt() ?: 0 }
                "row" -> {
                    flush()
                    curRow = (x.attr("r")?.toIntOrNull()?.minus(1)) ?: (curRow + 1); nextCol = 0
                    if (x.attr("hidden").let { it == "1" || it == "true" }) hRows.add(curRow)
                    if (curRow >= maxRows) { trunc = true; break }
                }
                "c" -> {
                    val ref = x.attr("r")?.let { XRef.parse(it) }
                    val col = ref?.get(0) ?: nextCol; val rw = ref?.get(1) ?: curRow
                    nextCol = col + 1
                    val st = x.attr("s")?.toIntOrNull() ?: 0; val ty = x.attr("t") ?: "n"
                    var v: String? = null; var f: String? = null; var inl: String? = null
                    val d = x.depth
                    if (!selfClosed(x)) while (true) {
                        val e = x.next()
                        if (e == MiniXml.T.EOF || (e == MiniXml.T.END && x.depth < d)) break
                        if (e == MiniXml.T.START) when (x.name) {
                            "v" -> v = x.readText()
                            "f" -> { val s = x.readText(); if (s.isNotEmpty()) f = s }
                            "is" -> inl = readSi(x)
                        }
                    }
                    if (col >= maxCols) continue
                    val cell = build(rw, col, ty, v, inl, f, st) ?: continue
                    if (++total > maxCells) { trunc = true; break }
                    rowCells.add(cell); if (col > maxC) maxC = col; if (rw > maxR) maxR = rw
                }
                "mergeCell" -> x.attr("ref")?.let { XRef.range(it) }?.let { merges.add(XMerge(it[1], it[0], it[3], it[2])) }
            }
        }
        flush()
        return XSheet(info, rows, merges, widths, hCols, hRows, maxR, maxC, fr, fc, trunc)
    }

    private fun selfClosed(x: MiniXml): Boolean = x.isEmptyElement()

    private fun build(r: Int, c: Int, ty: String, v: String?, inl: String?, f: String?, st: Int): XCell? {
        when (ty) {
            "s" -> { val s = v?.trim()?.toIntOrNull()?.let { sst.getOrNull(it) } ?: return null; return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "inlineStr" -> { val s = inl ?: v ?: return null; return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "str" -> { val s = MiniXml.unescapeOoxml(v ?: return if (f != null) XCell(r, c, "", "", XCell.Kind.EMPTY, f, st) else null); return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "b" -> { val b = v?.trim() == "1"; return XCell(r, c, if (b) "TRUE" else "FALSE", if (b) "1" else "0", XCell.Kind.BOOL, f, st) }
            "e" -> { val s = v ?: "#ERR"; return XCell(r, c, s, s, XCell.Kind.ERROR, f, st) }
            "d" -> { val s = v ?: return null; return XCell(r, c, s, s, XCell.Kind.DATE, f, st) }
        }
        val raw = v?.trim()?.takeIf { it.isNotEmpty() } ?: return if (f != null) XCell(r, c, "", "", XCell.Kind.EMPTY, f, st) else null
        val d = raw.toDoubleOrNull() ?: return XCell(r, c, raw, raw, XCell.Kind.TEXT, f, st)
        val isDate = styles.dateXf.getOrNull(st) == true
        val code = styles.code(st)
        val txt = try { NumFmt.format(d, code, date1904) } catch (_: Throwable) { NumFmt.general(d) }
        return XCell(r, c, txt, raw, if (isDate) XCell.Kind.DATE else XCell.Kind.NUMBER, f, st)
    }

    override fun close() = pkg.close()

    companion object {
        fun isXlsx(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xltx") || n.endsWith(".xltm") }
    }
}
