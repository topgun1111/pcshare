package com.lanshare.app.core.office

import java.io.File

/**
 * Read-only reader for legacy Word 97-2003 (.doc, binary, OLE2) -> the same [DDoc] model as [DocxReader].
 * Text comes from the piece table (Clx) of the FIB; table structure (cell / row ends) from the paragraph
 * FKPs (PlcfBtePapx: sprmPFInTable / sprmPFTtp). No character / paragraph formatting, images, headers,
 * footnotes or text boxes: main document text only. Fields keep their result text; HYPERLINK fields become links.
 * No android.* imports.
 */
class DocReader(file: File) : AutoCloseable {
    private val ole = OleFile(file)
    private val wd: ByteArray = ole.open("WordDocument") ?: throw IllegalArgumentException("Not a Word document")

    private class Piece(val cp0: Int, val cp1: Int, val off: Int, val comp: Boolean)
    private class PRun(val cp0: Int, val cp1: Int, val inTable: Boolean, val ttp: Boolean)

    private fun u16(a: ByteArray, o: Int) = if (o < 0 || o + 2 > a.size) 0 else (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(a: ByteArray, o: Int) = if (o < 0 || o + 4 > a.size) 0 else OleFile.le32(a, o)

    fun read(maxParagraphs: Int = 20000): DDoc {
        if (u16(wd, 0) != 0xA5EC) throw IllegalArgumentException("Not a Word 97-2003 document")
        val nFib = u16(wd, 2)
        if (nFib < 0xC1) throw IllegalArgumentException("Word 6 / 95 documents are not supported")
        val flags = u16(wd, 0x0A)
        if (flags and 0x0100 != 0) throw IllegalStateException("File is password protected")
        val tbl = ole.open(if (flags and 0x0200 != 0) "1Table" else "0Table") ?: ole.open("0Table") ?: ole.open("1Table")
            ?: throw IllegalArgumentException("Table stream missing")
        // FIB layout: base(32) csw(2) rgW(csw*2) cslw(2) rgLw(cslw*4) cbRgFcLcb(2) rgFcLcb(pairs)
        val csw = u16(wd, 32)
        val lwAt = 32 + 2 + csw * 2 + 2
        val cslw = u16(wd, 32 + 2 + csw * 2)
        val pairsAt = lwAt + cslw * 4 + 2
        val ccpText = i32(wd, lwAt + 3 * 4).coerceIn(0, MAX_CHARS)
        fun pair(n: Int) = Pair(i32(wd, pairsAt + n * 8), i32(wd, pairsAt + n * 8 + 4))

        val pieces = readPieces(tbl, pair(33), ccpText, lwAt)
        val text = buildText(pieces, ccpText)
        val runs = try { readPapx(pair(13), pieces) } catch (_: Throwable) { null }
        return build(text, runs, maxParagraphs)
    }

    // ---- piece table -------------------------------------------------------------------------------------------

    private fun readPieces(tbl: ByteArray, clx: Pair<Int, Int>, ccpText: Int, lwAt: Int): List<Piece> {
        val out = ArrayList<Piece>()
        var p = clx.first
        val end = minOf(tbl.size, clx.first + clx.second)
        if (clx.second > 0 && p >= 0) {
            while (p < end && (tbl[p].toInt() and 0xFF) == 1) p += 3 + u16(tbl, p + 1)            // Prc: skip
            if (p + 5 <= end && (tbl[p].toInt() and 0xFF) == 2) {
                val lcb = i32(tbl, p + 1); val s = p + 5
                val n = (lcb - 4) / 12
                for (k in 0 until n) {
                    val cp0 = i32(tbl, s + k * 4); val cp1 = i32(tbl, s + (k + 1) * 4)
                    val pcd = s + (n + 1) * 4 + k * 8
                    val fc = i32(tbl, pcd + 2)
                    val comp = fc and 0x40000000 != 0
                    val off = if (comp) (fc and 0x3FFFFFFF) / 2 else fc and 0x3FFFFFFF
                    if (cp1 > cp0 && cp0 >= 0) out.add(Piece(cp0, cp1, off, comp))
                }
            }
        }
        if (out.isEmpty()) {                       // old / fast-saved without Clx: one run between fcMin and fcMac
            val fcMin = i32(wd, 0x18)
            if (fcMin > 0) out.add(Piece(0, ccpText, fcMin, false))
        }
        return out
    }

    private fun buildText(pieces: List<Piece>, ccpText: Int): String {
        val sb = StringBuilder()
        for (pc in pieces) {
            if (pc.cp0 >= ccpText) break
            val n = minOf(pc.cp1, ccpText) - pc.cp0
            if (pc.comp) {
                for (i in 0 until n) { val o = pc.off + i; if (o >= wd.size) break; sb.append(cp1252(wd[o].toInt() and 0xFF)) }
            } else {
                for (i in 0 until n) { val o = pc.off + i * 2; if (o + 2 > wd.size) break; sb.append(((wd[o].toInt() and 0xFF) or ((wd[o + 1].toInt() and 0xFF) shl 8)).toChar()) }
            }
        }
        return sb.toString()
    }

    private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252_HI[b - 0x80] else b.toChar()

    /** File offset (WordDocument stream) -> character position. [endIncl] = the offset is an exclusive end (may sit at a piece's end). */
    private fun fcToCp(fc: Int, pieces: List<Piece>, endIncl: Boolean): Int {
        for (pc in pieces) {
            val len = (pc.cp1 - pc.cp0) * (if (pc.comp) 1 else 2)
            if (fc > pc.off && fc <= pc.off + len && endIncl || fc >= pc.off && fc < pc.off + len) return pc.cp0 + (fc - pc.off) / (if (pc.comp) 1 else 2)
        }
        return -1
    }

    // ---- paragraph properties (only fInTable / fTtp) -----------------------------------------------------------

    private fun readPapx(pl: Pair<Int, Int>, pieces: List<Piece>): List<PRun>? {
        val tbl = ole.open(if (u16(wd, 0x0A) and 0x0200 != 0) "1Table" else "0Table") ?: return null
        if (pl.second < 12 || pl.first < 0 || pl.first + pl.second > tbl.size) return null
        val n = (pl.second - 4) / 8
        val out = ArrayList<PRun>()
        for (k in 0 until n) {
            val pn = i32(tbl, pl.first + (n + 1) * 4 + k * 4) and 0x3FFFFF
            val fkp = pn.toLong() * 512
            if (fkp + 512 > wd.size) continue
            val base = fkp.toInt()
            val crun = wd[base + 511].toInt() and 0xFF
            for (r in 0 until crun) {
                val fcA = i32(wd, base + r * 4); val fcB = i32(wd, base + (r + 1) * 4)
                val bx = base + (crun + 1) * 4 + r * 13
                val bOff = wd[bx].toInt() and 0xFF
                var inT = false; var ttp = false
                if (bOff != 0) {
                    var o = base + bOff * 2
                    var cb = wd[o].toInt() and 0xFF
                    val start: Int; val len: Int
                    if (cb == 0) { cb = wd[o + 1].toInt() and 0xFF; len = cb * 2; start = o + 2 } else { len = cb * 2 - 1; start = o + 1 }
                    var q = start + 2                                  // skip istd
                    val stop = minOf(start + len, base + 512)
                    while (q + 2 <= stop) {
                        val op = u16(wd, q); q += 2
                        when (op shr 13) {
                            0, 1 -> { if (q < stop) { val v = wd[q].toInt() and 0xFF; if (op == 0x2416 && v != 0) inT = true; if (op == 0x2417 && v != 0) ttp = true }; q += 1 }
                            2, 4, 5 -> q += 2
                            3 -> q += 4
                            7 -> q += 3
                            else -> { if (q >= stop) break; q += 1 + (wd[q].toInt() and 0xFF) }
                        }
                    }
                }
                val cpA = fcToCp(fcA, pieces, false); val cpB = fcToCp(fcB, pieces, true)
                if (cpA >= 0 && cpB > cpA) out.add(PRun(cpA, cpB, inT || ttp, ttp))
            }
        }
        out.sortBy { it.cp0 }
        return out
    }

    // ---- text -> DDoc ------------------------------------------------------------------------------------------

    private class Seg(val cp0: Int, val cp1: Int, val cell: Boolean, val para: DPara)

    private fun build(text: String, pr: List<PRun>?, maxParagraphs: Int): DDoc {
        val doc = DDoc()
        // 1. fields: drop instruction text, keep results; HYPERLINK -> link on the result runs
        val units = ArrayList<Seg>()
        var para = DPara(); var cur: DRun? = null
        val sb = StringBuilder()
        class Fld(var instr: Boolean = true, val ins: StringBuilder = StringBuilder(), var link: String? = null)
        val stack = ArrayList<Fld>()
        var uStart = 0
        fun flush() {
            if (sb.isEmpty()) return
            val link = stack.lastOrNull { !it.instr }?.link ?: stack.firstOrNull { it.link != null }?.link
            val c = cur
            if (c != null && c.link == link) c.text += sb.toString()
            else { val r = DRun(DRun.TEXT, sb.toString()); r.link = link; para.runs.add(r); cur = r }
            sb.setLength(0)
        }
        fun special(kind: Int) { flush(); para.runs.add(DRun(kind)); cur = null }
        for (i in text.indices) {
            val ch = text[i]
            val hidden = stack.any { it.instr }
            when (ch) {
                '\u0013' -> { flush(); stack.add(Fld()) }
                '\u0014' -> { val f = stack.lastOrNull(); if (f != null && f.instr) { f.instr = false; flush(); cur = null; val m = Regex("""(?i)^\s*HYPERLINK\s+(?:\\l\s+)?"([^"]*)"""").find(f.ins); if (m != null) f.link = (if (f.ins.contains("\\l", true) && !m.groupValues[1].startsWith("#")) "#" else "") + m.groupValues[1] } }
                '\u0015' -> { flush(); cur = null; if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) }
                else -> if (hidden) { val f = stack.last { it.instr }; if (ch >= ' ') f.ins.append(ch) }
                else when (ch) {
                    '\r', '\u0007' -> { flush(); units.add(Seg(uStart, i + 1, ch == '\u0007', para)); para = DPara(); cur = null; uStart = i + 1 }
                    '\t' -> special(DRun.TAB)
                    '\u000B' -> special(DRun.BREAK)
                    '\u000C' -> special(DRun.PAGE)
                    '\u001E' -> sb.append('-')
                    '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006', '\u0008', '\u001F' -> {}
                    else -> if (ch >= ' ') sb.append(ch)
                }
            }
        }
        flush()
        if (para.runs.isNotEmpty()) units.add(Seg(uStart, text.length, false, para))

        // 2. blocks: paragraphs and tables
        var pi = 0
        fun run(cp: Int): PRun? {
            val l = pr ?: return null
            while (pi < l.size && l[pi].cp1 <= cp) pi++
            return if (pi < l.size && l[pi].cp0 <= cp) l[pi] else null
        }
        var count = 0
        var table: DTable? = null; var row: DRow? = null; var cell: DCell? = null
        var prevCell = false
        fun closeTable() {
            val t = table ?: return
            if (cell != null) { row?.cells?.add(cell!!); cell = null }
            if (row != null && row!!.cells.isNotEmpty()) t.rows.add(row!!)
            val cols = t.rows.maxOfOrNull { it.cells.size } ?: 0
            for (k in 0 until cols) t.grid.add(1400)                      // no widths read: equal columns (1400 twips = ~2.4 cm)
            if (t.rows.isNotEmpty()) { t.borders = true; doc.body.add(t) }
            table = null; row = null; cell = null
        }
        for (u in units) {
            if (count >= maxParagraphs) { doc.truncated = true; break }
            val r = run(u.cp0)
            val inT: Boolean; val ttp: Boolean
            if (r != null || pr != null) { inT = r?.inTable == true; ttp = r?.ttp == true }
            else { inT = u.cell; ttp = u.cell && prevCell && u.para.runs.isEmpty() }   // no PAPX: guess from the 0x07 marks
            prevCell = u.cell
            if (!inT) { closeTable(); doc.body.add(u.para); count++; continue }
            val t = table ?: DTable().also { table = it }
            if (row == null) row = DRow()
            if (ttp) { if (cell != null) { row!!.cells.add(cell!!); cell = null }; if (row!!.cells.isNotEmpty()) t.rows.add(row!!); row = null; continue }
            val c = cell ?: DCell().also { cell = it }
            c.blocks.add(u.para); count++
            if (u.cell) { row!!.cells.add(c); cell = null }
        }
        closeTable()
        return doc
    }

    override fun close() {}

    companion object {
        private const val MAX_CHARS = 8_000_000
        private val CP1252_HI = charArrayOf(
            '\u20AC', '\u0081', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021', '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\u008D', '\u017D', '\u008F',
            '\u0090', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\u009D', '\u017E', '\u0178')
    }
}
