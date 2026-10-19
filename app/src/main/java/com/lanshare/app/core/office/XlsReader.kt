package com.lanshare.app.core.office

import java.io.File
import java.util.TreeMap

/**
 * Read-only legacy .xls (Excel 97-2003, BIFF8 inside an OLE2 container) reader. Same shape as [XlsxReader]:
 *   XlsReader(file).use { it.sheets; it.readSheet(0) }  -> [XSheet] / [XStyles] of XlsxModel.kt.
 * Supported: sheets (hidden / very hidden; worksheets only), SST incl. CONTINUE, LABEL/LABELSST/NUMBER/RK/MULRK/BOOLERR/FORMULA cached results,
 * number formats + date detection (via [NumFmt]), merges, column widths, hidden rows/cols, frozen panes, XF fonts / fills / alignment, 1904 date mode.
 * Not supported (gaps): formula TEXT (rgce tokens; [XCell.formula] stays null, cached value shown), BIFF5 (Excel 5/95), encrypted files (throws "password protected"),
 * rich text runs, comments, charts/images, number-format conditions.  No android.* imports.
 */
class XlsReader(file: File) : AutoCloseable {
    val sheets: List<XSheetInfo>
    val date1904: Boolean
    val styles: XStyles
    private val wb: ByteArray
    private val offsets = ArrayList<Int>()
    private val sst = ArrayList<String>()

    init {
        val ole = OleFile(file)
        if (ole.has("EncryptedPackage")) throw IllegalStateException("File is password protected")
        wb = ole.open("Workbook") ?: if (ole.has("Book")) throw IllegalArgumentException("Excel 5.0/95 (BIFF5) files are not supported") else throw IllegalArgumentException("Not an Excel workbook (no Workbook stream)")
        val infos = ArrayList<XSheetInfo>()
        var d1904 = false
        val fmts = HashMap<Int, String>()
        val xfRaw = ArrayList<IntArray>()      // [fontIdx, fmtId, halign, wrap, valign, fillColorIdx(-1 none)]
        val fonts = ArrayList<XFont>()
        val pal = HashMap<Int, String>()
        var pos = 0; var first = true; var depth = 0
        while (pos + 4 <= wb.size) {
            val t = u16(wb, pos); val len = u16(wb, pos + 2); val d = pos + 4
            if (d + len > wb.size) break
            when (t) {
                0x0809 -> { if (first) { first = false; if (u16(wb, d) != 0x0600) throw IllegalArgumentException("Unsupported .xls version (only Excel 97-2003 / BIFF8)") }; depth++ }
                0x000A -> { depth--; if (depth <= 0) { pos = wb.size; continue } }
                0x002F -> throw IllegalStateException("File is password protected")
                0x0022 -> d1904 = u16(wb, d) == 1
                0x0085 -> {
                    val dt = wb[d + 5].toInt() and 0xFF
                    if (dt == 0) {
                        val st = wb[d + 4].toInt() and 3
                        val nm = shortStr(wb, d + 6)
                        infos.add(XSheetInfo(infos.size, nm, "", st == 1, st == 2, null)); offsets.add(le32(wb, d))
                    }
                }
                0x041E -> { val id = u16(wb, d); fmts[id] = longStr(wb, d + 2, d + len) }
                0x0031 -> {
                    val h = u16(wb, d) / 20.0; val g = u16(wb, d + 2); val ic = u16(wb, d + 4); val bold = u16(wb, d + 6) >= 700; val ul = wb[d + 10].toInt() != 0
                    fonts.add(XFont(bold, (g and 2) != 0, ul, colorOf(ic, pal), h))
                }
                0x0092 -> { val n = u16(wb, d); for (k in 0 until minOf(n, 56, (len - 2) / 4)) { val o = d + 2 + k * 4; pal[8 + k] = hex(wb[o], wb[o + 1], wb[o + 2]) } }
                0x00E0 -> if (len >= 20) {
                    val al = wb[d + 6].toInt() and 0xFF
                    val fls = (le32(wb, d + 14) ushr 26) and 0x3F
                    val fore = u16(wb, d + 18) and 0x7F
                    xfRaw.add(intArrayOf(u16(wb, d), u16(wb, d + 2), al and 7, (al shr 3) and 1, (al shr 4) and 7, if (fls == 1) fore else -1))
                }
                0x00FC -> { // SST (+CONTINUE records)
                    val chunks = ArrayList<ByteArray>(); chunks.add(wb.copyOfRange(d, d + len))
                    var q = d + len
                    while (q + 4 <= wb.size && u16(wb, q) == 0x003C) { val l2 = u16(wb, q + 2); if (q + 4 + l2 > wb.size) break; chunks.add(wb.copyOfRange(q + 4, q + 4 + l2)); q += 4 + l2 }
                    readSst(chunks); pos = q; continue
                }
            }
            pos = d + len
        }
        // fonts: BIFF skips index 4
        val fontOf = { i: Int -> if (i >= 4) i - 1 else i }
        val xfs = ArrayList<XXf>(); val fills = ArrayList<String?>(); val dateXf = BooleanArray(xfRaw.size)
        val hal = arrayOf(null, "left", "center", "right", "left", "center", "right", "center")
        val val_ = arrayOf("top", "center", "bottom", null, null, null, null, null)
        for ((i, r) in xfRaw.withIndex()) {
            fills.add(if (r[5] >= 0) colorOf(r[5], pal) else null)
            xfs.add(XXf(r[1], fontOf(r[0]), i, hal[r[2]], r[3] == 1, val_[r[4]]))
            dateXf[i] = try { NumFmt.isDate(r[1], NumFmt.code(r[1], fmts)) } catch (_: Throwable) { false }
        }
        sheets = infos; date1904 = d1904
        styles = XStyles(xfs, fonts, fills, fmts, dateXf)
    }

    // ------------------------------------------------------------------ sheet
    fun readSheet(index: Int, maxRows: Int = 5000, maxCols: Int = 200, maxCells: Int = 250_000): XSheet {
        val info = sheets[index]
        var pos = offsets[index]
        val rows = HashMap<Int, ArrayList<XCell>>()
        val merges = ArrayList<XMerge>(); val widths = HashMap<Int, Double>()
        val hCols = HashSet<Int>(); val hRows = HashSet<Int>()
        var maxR = -1; var maxC = -1; var total = 0; var trunc = false
        var paneX = 0; var paneY = 0; var frozen = false
        var depth = 0; var pendingFormula: IntArray? = null   // row, col, xf of a formula waiting for its STRING record
        fun put(r: Int, c: Int, text: String, raw: String, kind: XCell.Kind, xf: Int) {
            if (r >= maxRows || c >= maxCols) { trunc = true; return }
            if (++total > maxCells) { trunc = true; return }
            rows.getOrPut(r) { ArrayList() }.add(XCell(r, c, text, raw, kind, null, xf))
            if (r > maxR) maxR = r; if (c > maxC) maxC = c
        }
        fun num(r: Int, c: Int, xf: Int, v: Double) {
            val raw = NumFmt.general(v)
            val txt = try { NumFmt.format(v, styles.code(xf), date1904) } catch (_: Throwable) { raw }
            put(r, c, txt, raw, if (styles.dateXf.getOrNull(xf) == true) XCell.Kind.DATE else XCell.Kind.NUMBER, xf)
        }
        while (pos + 4 <= wb.size) {
            val t = u16(wb, pos); val len = u16(wb, pos + 2); val d = pos + 4
            if (d + len > wb.size) break
            pos = d + len
            if (t == 0x0809) { depth++; continue }
            if (t == 0x000A) { depth--; if (depth <= 0) break; continue }
            if (depth != 1) continue
            when (t) {
                0x0203 -> if (len >= 14) num(u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), java.lang.Double.longBitsToDouble(le64(wb, d + 6)))
                0x027E -> if (len >= 10) num(u16(wb, d), u16(wb, d + 2), u16(wb, d + 4), rk(le32(wb, d + 6)))
                0x00BD -> { val r = u16(wb, d); val c0 = u16(wb, d + 2); val n = (len - 6) / 6
                    for (k in 0 until n) { val o = d + 4 + k * 6; num(r, c0 + k, u16(wb, o), rk(le32(wb, o + 2))) } }
                0x00FD -> if (len >= 10) { val s = sst.getOrNull(le32(wb, d + 6)) ?: ""; put(u16(wb, d), u16(wb, d + 2), s, s, XCell.Kind.TEXT, u16(wb, d + 4)) }
                0x0204 -> if (len >= 8) { val s = longStr(wb, d + 6, d + len); put(u16(wb, d), u16(wb, d + 2), s, s, XCell.Kind.TEXT, u16(wb, d + 4)) }
                0x0205 -> if (len >= 8) {
                    val v = wb[d + 6].toInt() and 0xFF
                    if ((wb[d + 7].toInt() and 1) == 0) put(u16(wb, d), u16(wb, d + 2), if (v != 0) "TRUE" else "FALSE", if (v != 0) "1" else "0", XCell.Kind.BOOL, u16(wb, d + 4))
                    else { val e = errName(v); put(u16(wb, d), u16(wb, d + 2), e, e, XCell.Kind.ERROR, u16(wb, d + 4)) }
                }
                0x0006 -> if (len >= 20) {
                    val r = u16(wb, d); val c = u16(wb, d + 2); val xf = u16(wb, d + 4)
                    if ((wb[d + 12].toInt() and 0xFF) == 0xFF && (wb[d + 13].toInt() and 0xFF) == 0xFF) {
                        val v = wb[d + 6].toInt() and 0xFF; val x = wb[d + 8].toInt() and 0xFF
                        when (v) {
                            0 -> pendingFormula = intArrayOf(r, c, xf)
                            1 -> put(r, c, if (x != 0) "TRUE" else "FALSE", if (x != 0) "1" else "0", XCell.Kind.BOOL, xf)
                            2 -> { val e = errName(x); put(r, c, e, e, XCell.Kind.ERROR, xf) }
                        }
                    } else num(r, c, xf, java.lang.Double.longBitsToDouble(le64(wb, d + 6)))
                }
                0x0207 -> pendingFormula?.let { p -> val s = longStr(wb, d, d + len); put(p[0], p[1], s, s, XCell.Kind.TEXT, p[2]); pendingFormula = null }
                0x00E5 -> { val n = u16(wb, d); for (k in 0 until minOf(n, (len - 2) / 8)) { val o = d + 2 + k * 8; merges.add(XMerge(u16(wb, o), u16(wb, o + 4), u16(wb, o + 2), u16(wb, o + 6))) } }
                0x007D -> if (len >= 12) {
                    val c1 = u16(wb, d); val c2 = minOf(u16(wb, d + 2), 255); val w = u16(wb, d + 4) / 256.0; val hid = (u16(wb, d + 8) and 1) != 0
                    for (c in c1..c2) { widths[c] = w; if (hid) hCols.add(c) }
                }
                0x0208 -> if (len >= 14 && (wb[d + 12].toInt() and 0x20) != 0) hRows.add(u16(wb, d))
                0x0041 -> if (len >= 4) { paneX = u16(wb, d); paneY = u16(wb, d + 2) }
                0x023E -> if (len >= 2) frozen = (u16(wb, d) and 0x08) != 0
            }
        }
        val sorted = TreeMap<Int, List<XCell>>()
        for ((r, l) in rows) { l.sortBy { it.col }; sorted[r] = l }
        return XSheet(info, sorted, merges, widths, hCols, hRows, maxR, maxC, if (frozen) paneY else 0, if (frozen) paneX else 0, trunc)
    }

    // ------------------------------------------------------------------ SST with CONTINUE boundaries
    private class Chunks(val c: List<ByteArray>) {
        var i = 0; var p = 0
        fun left() = if (i < c.size) c[i].size - p else 0
        fun atEnd() = i >= c.size || (left() <= 0 && i + 1 >= c.size)
        fun u8(): Int { while (i < c.size && p >= c[i].size) { i++; p = 0 }; return if (i < c.size) c[i][p++].toInt() and 0xFF else 0 }
        fun u16() = u8() or (u8() shl 8)
        fun i32() = u16() or (u16() shl 16)
        fun skip(n0: Int) { var n = n0; while (n > 0 && i < c.size) { val k = minOf(n, c[i].size - p); if (k <= 0) { i++; p = 0; continue }; p += k; n -= k } }
    }

    private fun readSst(chunks: List<ByteArray>) {
        val s = Chunks(chunks)
        s.i32(); val unique = s.i32()
        for (k in 0 until minOf(unique, 2_000_000)) {
            if (s.atEnd()) break
            val cch = s.u16(); val flags = s.u8()
            var wide = (flags and 1) != 0
            val runs = if ((flags and 8) != 0) s.u16() else 0
            val ext = if ((flags and 4) != 0) s.i32() else 0
            val sb = StringBuilder(cch)
            var n = 0
            while (n < cch) {
                if (s.left() <= 0 && s.i + 1 < s.c.size) { s.i++; s.p = 0; wide = (s.u8() and 1) != 0; continue }   // continuation: new flag byte
                if (s.left() <= 0) break
                sb.append(if (wide) s.u16().toChar() else s.u8().toChar()); n++
            }
            s.skip(runs * 4 + maxOf(ext, 0))
            sst.add(sb.toString())
        }
    }

    // ------------------------------------------------------------------ helpers
    override fun close() {}

    private fun shortStr(a: ByteArray, o: Int): String { val n = a[o].toInt() and 0xFF; return chars(a, o + 2, n, (a[o + 1].toInt() and 1) != 0) }
    /** XLUnicodeString starting at [o] (cch u16, flags u8, [rich u16][ext i32], chars). */
    private fun longStr(a: ByteArray, o: Int, end: Int): String {
        if (o + 3 > end) return ""
        val n = u16(a, o); val f = a[o + 2].toInt() and 0xFF
        var q = o + 3; if ((f and 8) != 0) q += 2; if ((f and 4) != 0) q += 4
        return chars(a, q, minOf(n, if ((f and 1) != 0) (end - q) / 2 else end - q), (f and 1) != 0)
    }
    private fun chars(a: ByteArray, o: Int, n: Int, wide: Boolean): String {
        if (n <= 0 || o < 0) return ""
        val l = if (wide) minOf(n * 2, a.size - o) else minOf(n, a.size - o)
        if (l <= 0) return ""
        return if (wide) String(a, o, l - l % 2, Charsets.UTF_16LE) else String(a, o, l, Charsets.ISO_8859_1)
    }
    private fun rk(v: Int): Double {
        var x = if ((v and 2) != 0) (v shr 2).toDouble() else java.lang.Double.longBitsToDouble((v.toLong() and 0xFFFFFFFCL) shl 32)
        if ((v and 1) != 0) x /= 100.0
        return x
    }
    private fun errName(c: Int) = when (c) { 0 -> "#NULL!"; 7 -> "#DIV/0!"; 15 -> "#VALUE!"; 23 -> "#REF!"; 29 -> "#NAME?"; 36 -> "#NUM!"; 42 -> "#N/A"; else -> "#ERR" }
    private fun hex(r: Byte, g: Byte, b: Byte) = String.format("#%02X%02X%02X", r.toInt() and 0xFF, g.toInt() and 0xFF, b.toInt() and 0xFF)
    private fun colorOf(i: Int, pal: Map<Int, String>): String? = pal[i] ?: (if (i in 8..63) PAL[i - 8] else if (i in 0..7) PAL[i] else null)
    private fun u16(a: ByteArray, o: Int) = if (o < 0 || o + 2 > a.size) 0 else (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(a: ByteArray, o: Int) = OleFile.le32(a, o)
    private fun le64(a: ByteArray, o: Int): Long = (le32(a, o).toLong() and 0xFFFFFFFFL) or (le32(a, o + 4).toLong() shl 32)

    companion object {
        private val PAL = ("000000 FFFFFF FF0000 00FF00 0000FF FFFF00 FF00FF 00FFFF 800000 008000 000080 808000 800080 008080 C0C0C0 808080 " +
            "9999FF 993366 FFFFCC CCFFFF 660066 FF8080 0066CC CCCCFF 000080 FF00FF FFFF00 00FFFF 800080 800000 008080 0000FF " +
            "00CCFF CCFFFF CCFFCC FFFF99 99CCFF FF99CC CC99FF FFCC99 3366FF 33CCCC 99CC00 FFCC00 FF9900 FF6600 666699 969696 " +
            "003366 339966 003300 333300 993300 993366 333399 333333").split(' ').map { "#$it" }
    }
}
