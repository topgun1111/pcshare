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
    private val theme: Theme

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
        theme = Theme.parse(pkg.xml(rels.values.firstOrNull { it.kind == "theme" && !it.external }?.target ?: "xl/theme/theme1.xml"))
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

    // ------------------------------------------------------------------ colours (Part 9)
    /** `<color rgb | theme + tint | indexed | auto>` -> "#RRGGBB"; null = automatic / unknown. */
    private fun colorOf(x: MiniXml): String? {
        x.attr("rgb")?.let { return if (it.length >= 6) "#" + it.takeLast(6).uppercase() else null }
        x.attr("theme")?.toIntOrNull()?.let { i ->
            val b = theme.byIndex(i) ?: return null
            val t = x.attr("tint")?.toDoubleOrNull() ?: 0.0
            return "#" + (if (t != 0.0) Theme.excelTint(b, t) else b)
        }
        x.attr("indexed")?.toIntOrNull()?.let { i -> return INDEXED.getOrNull(i)?.let { "#$it" } }
        return null
    }

    // ------------------------------------------------------------------ styles
    private fun readStyles(part: String): XStyles {
        val x = pkg.xml(part) ?: return XStyles.EMPTY
        val numFmts = HashMap<Int, String>(); val fonts = ArrayList<XFont>(); val fills = ArrayList<String?>(); val xfs = ArrayList<XXf>()
        var inFonts = false; var inFills = false; var inCellXfs = false
        // current font
        var fb = false; var fi = false; var fu = false; var fc: String? = null; var fs = 11.0; var fn: String? = null; var inFont = false
        var inFill = false; var fillColor: String? = null
        // Part 9: borders and dxfs
        val borders = ArrayList<XBorder>(); val dxfs = ArrayList<XDxf>()
        var inBorders = false; var inBorder = false; var side: String? = null; var sideStyle: String? = null; var sideColor: String? = null
        val sides = HashMap<String, XBSide>()
        var inDxfs = false; var inDxf = false; var dxColor: String? = null; var dxFill: String? = null; var dxB: Boolean? = null; var dxI: Boolean? = null; var dxU: Boolean? = null; var dxS: Boolean? = null
        var xfBorder = 0
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
                    "b" -> if (inFont) fb = on(x) else if (inDxf) dxB = on(x)
                    "i" -> if (inFont) fi = on(x) else if (inDxf) dxI = on(x)
                    "u" -> if (inFont) fu = x.attr("val") != "none" else if (inDxf) dxU = x.attr("val") != "none"
                    "sz" -> if (inFont) fs = x.attr("val")?.toDoubleOrNull() ?: 11.0
                    "name" -> if (inFont) fn = x.attr("val")
                    "color" -> if (inFont) fc = colorOf(x) else if (inDxf) dxColor = colorOf(x) else if (inBorder && side != null) sideColor = colorOf(x)
                    "fill" -> if (inFills) { inFill = true; fillColor = null }
                    "fgColor" -> if (inFill && fillColor == null) fillColor = colorOf(x) else if (inDxf && dxFill == null) dxFill = colorOf(x)
                    "bgColor" -> if (inDxf) colorOf(x)?.let { dxFill = it }   // in a dxf the solid fill colour is bgColor
                    "borders" -> inBorders = true
                    "border" -> if (inBorders) { inBorder = true; sides.clear() }
                    "left", "start", "right", "end", "top", "bottom" -> if (inBorder) { side = when (x.name) { "start" -> "left"; "end" -> "right"; else -> x.name }; sideStyle = x.attr("style"); sideColor = null
                        if (x.isEmptyElement()) { if (!sideStyle.isNullOrEmpty() && sideStyle != "none") sides[side!!] = XBSide(sideStyle!!, null); side = null } }
                    "dxfs" -> inDxfs = true
                    "dxf" -> if (inDxfs) { inDxf = true; dxColor = null; dxFill = null; dxB = null; dxI = null; dxU = null; dxS = null }
                    "strike" -> if (inDxf) dxS = on(x)
                    "patternFill" -> if (inFill && x.attr("patternType") == "none") fillColor = null
                    "xf" -> if (inCellXfs) {
                        inXf = true; xfNum = x.attr("numFmtId")?.toIntOrNull() ?: 0; xfFont = x.attr("fontId")?.toIntOrNull() ?: 0
                        xfFill = x.attr("fillId")?.toIntOrNull() ?: 0; xfBorder = x.attr("borderId")?.toIntOrNull() ?: 0; xfH = null; xfW = false; xfV = null
                    }
                    "alignment" -> if (inXf) { xfH = x.attr("horizontal"); xfV = x.attr("vertical"); xfW = x.attr("wrapText").let { it == "1" || it == "true" } }
                }
                MiniXml.T.END -> when (x.name) {
                    "fonts" -> inFonts = false
                    "fills" -> inFills = false
                    "cellXfs" -> inCellXfs = false
                    "font" -> if (inFont) { fonts.add(XFont(fb, fi, fu, fc, fs, fn)); inFont = false }
                    "fill" -> if (inFill) { fills.add(fillColor); inFill = false }
                    "left", "start", "right", "end", "top", "bottom" -> if (inBorder && side != null) {
                        val st = sideStyle; if (!st.isNullOrEmpty() && st != "none") sides[side!!] = XBSide(st, sideColor); side = null }
                    "border" -> if (inBorder) { borders.add(XBorder(sides["left"], sides["right"], sides["top"], sides["bottom"])); inBorder = false }
                    "borders" -> inBorders = false
                    "dxf" -> if (inDxf) { dxfs.add(XDxf(dxColor, dxFill, dxB, dxI, dxU, dxS)); inDxf = false }
                    "dxfs" -> inDxfs = false
                    "xf" -> if (inXf) { xfs.add(XXf(xfNum, xfFont, xfFill, xfH, xfW, xfV, xfBorder)); inXf = false }
                }
                else -> {}
            }
        }
        val dateXf = BooleanArray(xfs.size) { NumFmt.isDate(xfs[it].numFmtId, NumFmt.code(xfs[it].numFmtId, numFmts)) }
        return XStyles(xfs, fonts, fills, numFmts, dateXf, borders, dxfs)
    }
    private fun on(x: MiniXml): Boolean { val v = x.attr("val"); return v == null || !(v == "0" || v == "false") }

    // ------------------------------------------------------------------ sheets
    fun readSheet(index: Int, maxRows: Int = 5000, maxCols: Int = 200, maxCells: Int = 250_000): XSheet = readSheetImpl(index, maxRows, maxCols, maxCells, true)

    private fun readSheetImpl(index: Int, maxRows: Int, maxCols: Int, maxCells: Int, withCharts: Boolean): XSheet {
        val info = sheets[index]
        val x = pkg.xml(info.part) ?: return XSheet(info, TreeMap(), emptyList(), emptyMap(), emptySet(), emptySet(), -1, -1, 0, 0, false, fileWidths = true)
        val rows = TreeMap<Int, List<XCell>>(); val merges = ArrayList<XMerge>(); val widths = HashMap<Int, Double>()
        val hCols = HashSet<Int>(); val hRows = HashSet<Int>()
        var maxR = -1; var maxC = -1; var total = 0; var trunc = false; var fr = 0; var fc = 0
        var grid = true; var defW: Double? = null; val cfs = ArrayList<XCfRule>()
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
                "sheetView" -> if (x.attr("showGridLines").let { it == "0" || it.equals("false", true) }) grid = false
                "sheetFormatPr" -> x.attr("defaultColWidth")?.toDoubleOrNull()?.let { defW = it }
                "conditionalFormatting" -> { val sq = x.attr("sqref"); if (sq == null) x.skip() else readCf(x, sq, cfs) }   // x14 extension blocks have no sqref attribute: skipped
                "mergeCell" -> x.attr("ref")?.let { XRef.range(it) }?.let { merges.add(XMerge(it[1], it[0], it[3], it[2])) }
            }
        }
        flush()
        return XSheet(info, rows, merges, widths, hCols, hRows, maxR, maxC, fr, fc, trunc, grid, cfs, readNotes(info.part), true, defW, if (withCharts) readCharts(info.part) else emptyList())
    }

    // ------------------------------------------------------------------ charts (Part 10)
    /** Charts of the sheet's drawing part. Never throws: a broken drawing / chart just gives fewer charts. */
    private fun readCharts(sheetPart: String): List<XChartFrame> {
        return try {
            val d = pkg.rels(sheetPart).values.firstOrNull { it.kind == "drawing" && !it.external } ?: return emptyList()
            val cache = HashMap<Int, XSheet>()
            ChartParser.parseDrawing(pkg, d.target, theme) { ref -> refCells(ref, cache) }
        } catch (_: Throwable) { emptyList() }
    }

    /** `Sheet1!$B$2:$B$9` / `'My sheet'!A1:A5` -> the cells row-major (null entries = no cell). Only plain single ranges; null otherwise. */
    private fun refCells(ref: String, cache: HashMap<Int, XSheet>): List<XCell?>? {
        val m = Regex("^\\(?'?(.+?)'?!([^,()]+)\\)?$").find(ref.trim()) ?: return null
        val name = m.groupValues[1].replace("''", "'")
        val si = sheets.indexOfFirst { it.name.equals(name, true) }.takeIf { it >= 0 } ?: return null
        val r = XRef.range(m.groupValues[2].replace("$", "")) ?: return null
        if ((r[2] - r[0] + 1).toLong() * (r[3] - r[1] + 1) > 5000L) return null
        val sh = cache.getOrPut(si) { readSheetImpl(si, 5000, 200, 250_000, false) }
        val out = ArrayList<XCell?>()
        for (row in r[1]..r[3]) for (col in r[0]..r[2]) out.add(sh.cell(row, col))
        return out
    }

    private fun selfClosed(x: MiniXml): Boolean = x.isEmptyElement()

    /** Part 9: an empty cell is kept when its style shows something (fill or a border), so coloured / ruled blank cells are drawn. */
    private fun boxed(st: Int): Boolean {
        if (st == 0) return false
        val xf = styles.xfs.getOrNull(st) ?: return false
        return styles.fills.getOrNull(xf.fillId) != null || styles.borders.getOrNull(xf.borderId)?.any == true
    }

    /** Part 9: one `conditionalFormatting` element (parser is on its START) -> rules. Types without a renderer (iconSet, timePeriod ...) are read and dropped later by [XlsxCf]. */
    private fun readCf(x: MiniXml, sqref: String, out: MutableList<XCfRule>) {
        val d = x.depth
        val ranges = sqref.trim().split(Regex("\\s+")).mapNotNull { XRef.range(it) }
        var inRule = false
        var type = ""; var pri = 0; var dxf = -1; var op: String? = null; var rank = 10; var pct = false; var bot = false; var text: String? = null
        var above = true; var eq = false; var stop = false; var minL = 10; var maxL = 90
        val forms = ArrayList<String>(); val vos = ArrayList<XCfvo>(); val cols = ArrayList<String?>()
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) return
            if (t == MiniXml.T.END) {
                if (x.name == "cfRule" && inRule) {
                    if (ranges.isNotEmpty()) out.add(XCfRule(ranges, type, pri, dxf, op, rank, pct, bot, text, above, eq, stop, ArrayList(forms), ArrayList(vos), ArrayList(cols), minL, maxL))
                    inRule = false
                }
                if (x.depth < d) return
                continue
            }
            if (t != MiniXml.T.START) continue
            when (x.name) {
                "cfRule" -> {
                    inRule = true; forms.clear(); vos.clear(); cols.clear()
                    type = x.attr("type") ?: ""; pri = x.attr("priority")?.toIntOrNull() ?: 0; dxf = x.attr("dxfId")?.toIntOrNull() ?: -1; op = x.attr("operator")
                    rank = x.attr("rank")?.toIntOrNull() ?: 10; pct = x.attr("percent").let { it == "1" || it == "true" }; bot = x.attr("bottom").let { it == "1" || it == "true" }
                    text = x.attr("text"); above = x.attr("aboveAverage").let { !(it == "0" || it == "false") }; eq = x.attr("equalAverage").let { it == "1" || it == "true" }
                    stop = x.attr("stopIfTrue").let { it == "1" || it == "true" }; minL = 10; maxL = 90
                }
                "formula" -> { val f = x.readText().trim(); if (inRule) forms.add(f) }
                "cfvo" -> if (inRule) vos.add(XCfvo(x.attr("type") ?: "num", x.attr("val")))
                "color" -> if (inRule) cols.add(colorOf(x))
                "dataBar" -> if (inRule) { minL = x.attr("minLength")?.toIntOrNull() ?: 10; maxL = x.attr("maxLength")?.toIntOrNull() ?: 90 }
                "extLst" -> x.skip()
            }
        }
    }

    /** Part 9: legacy cell comments of a sheet (`xl/commentsN.xml` through the sheet's relationships). */
    private fun readNotes(sheetPart: String): List<XNote> {
        val rel = pkg.rels(sheetPart).values.firstOrNull { it.kind == "comments" && !it.external } ?: return emptyList()
        val x = pkg.xml(rel.target) ?: return emptyList()
        val authors = ArrayList<String>(); val out = ArrayList<XNote>()
        var inAuthors = false; var ref: String? = null; var aid = 0
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> when (x.name) {
                    "authors" -> inAuthors = true
                    "author" -> if (inAuthors) authors.add(MiniXml.unescapeOoxml(x.readText()))
                    "comment" -> { ref = x.attr("ref"); aid = x.attr("authorId")?.toIntOrNull() ?: 0 }
                    "text" -> {
                        val r = ref?.let { XRef.parse(it) }
                        val t = readSi(x).trim()
                        if (r != null && out.size < 2000) out.add(XNote(XRef.name(r[0], r[1]), r[1], r[0], authors.getOrNull(aid) ?: "", cleanThreaded(t)))
                        ref = null
                    }
                }
                MiniXml.T.END -> if (x.name == "authors") inAuthors = false
                else -> {}
            }
        }
        return out
    }

    /** Excel stores a threaded comment as a legacy note with a fixed warning in front of the text. */
    private fun cleanThreaded(t: String): String {
        if (!t.startsWith("[Threaded comment]")) return t
        val i = t.indexOf("Comment:"); return if (i >= 0) t.substring(i + 8).trim() else t
    }

    private fun build(r: Int, c: Int, ty: String, v: String?, inl: String?, f: String?, st: Int): XCell? {
        when (ty) {
            "s" -> { val s = v?.trim()?.toIntOrNull()?.let { sst.getOrNull(it) } ?: return if (boxed(st)) XCell(r, c, "", "", XCell.Kind.EMPTY, f, st) else null; return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "inlineStr" -> { val s = inl ?: v ?: return null; return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "str" -> { val s = MiniXml.unescapeOoxml(v ?: return if (f != null) XCell(r, c, "", "", XCell.Kind.EMPTY, f, st) else null); return XCell(r, c, s, s, XCell.Kind.TEXT, f, st) }
            "b" -> { val b = v?.trim() == "1"; return XCell(r, c, if (b) "TRUE" else "FALSE", if (b) "1" else "0", XCell.Kind.BOOL, f, st) }
            "e" -> { val s = v ?: "#ERR"; return XCell(r, c, s, s, XCell.Kind.ERROR, f, st) }
            "d" -> { val s = v ?: return null; return XCell(r, c, s, s, XCell.Kind.DATE, f, st) }
        }
        val raw = v?.trim()?.takeIf { it.isNotEmpty() } ?: return if (f != null || boxed(st)) XCell(r, c, "", "", XCell.Kind.EMPTY, f, st) else null
        val d = raw.toDoubleOrNull() ?: return XCell(r, c, raw, raw, XCell.Kind.TEXT, f, st)
        val isDate = styles.dateXf.getOrNull(st) == true
        val code = styles.code(st)
        val txt = try { NumFmt.format(d, code, date1904) } catch (_: Throwable) { NumFmt.general(d) }
        return XCell(r, c, txt, raw, if (isDate) XCell.Kind.DATE else XCell.Kind.NUMBER, f, st)
    }

    override fun close() = pkg.close()

    companion object {
        /** Excel's default indexed colour palette (index 0..63; 64 / 65 = system colours = automatic, left to the renderer). */
        private val INDEXED = arrayOf("000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080", "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
            "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF", "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
            "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696", "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333")
        fun isXlsx(name: String): Boolean { val n = name.lowercase(); return n.endsWith(".xlsx") || n.endsWith(".xlsm") || n.endsWith(".xltx") || n.endsWith(".xltm") }
    }
}
