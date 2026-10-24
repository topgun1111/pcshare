package com.lanshare.app.core.office

/** One cell as shown to the user. [text] is already formatted (dates, %, currency...). [raw] is the stored value (number as text, string, 0/1 for bool). */
class XCell(
    val row: Int,            // 0-based
    val col: Int,            // 0-based
    val text: String,
    val raw: String,
    val kind: Kind,
    val formula: String? = null,
    val style: Int = 0       // index into [XStyles.xfs]
) {
    enum class Kind { EMPTY, TEXT, NUMBER, BOOL, ERROR, DATE }
    val ref: String get() = XRef.name(col, row)
}

class XXf(val numFmtId: Int, val fontId: Int, val fillId: Int, val halign: String?, val wrap: Boolean, val valign: String?, val borderId: Int = 0)
class XFont(val bold: Boolean, val italic: Boolean, val underline: Boolean, val color: String?, val size: Double, val name: String? = null)

/** Part 9: one border side (Excel style name, e.g. thin / medium / dashed; colour "#RRGGBB" or null = automatic black). */
class XBSide(val style: String, val color: String?)
class XBorder(val left: XBSide?, val right: XBSide?, val top: XBSide?, val bottom: XBSide?) {
    val any: Boolean get() = left != null || right != null || top != null || bottom != null
}
/** Part 9: differential format (dxf) used by conditional formatting rules. Null = not set by the rule. */
class XDxf(val color: String?, val fill: String?, val bold: Boolean?, val italic: Boolean?, val underline: Boolean?, val strike: Boolean?)
/** Part 9: one value object (cfvo) of a colour scale / data bar. */
class XCfvo(val type: String, val value: String?)
/** Part 9: a conditional formatting rule with the ranges (c1, r1, c2, r2; 0-based) it applies to. */
class XCfRule(
    val ranges: List<IntArray>, val type: String, val priority: Int, val dxfId: Int, val op: String?, val rank: Int, val percent: Boolean, val bottom: Boolean,
    val text: String?, val above: Boolean, val equalAvg: Boolean, val stopIfTrue: Boolean, val formulas: List<String>,
    val vos: List<XCfvo>, val colors: List<String?>, val minLen: Int, val maxLen: Int
)
/** Part 9: a cell comment (legacy note) of a sheet. */
class XNote(val ref: String, val row: Int, val col: Int, val author: String, val text: String)

class XStyles(
    val xfs: List<XXf>,
    val fonts: List<XFont>,
    val fills: List<String?>,                 // #RRGGBB or null
    val numFmts: Map<Int, String>,
    val dateXf: BooleanArray,                  // per xf: is this a date format
    val borders: List<XBorder> = emptyList(),  // Part 9
    val dxfs: List<XDxf> = emptyList()         // Part 9
) {
    fun code(xf: Int): String = NumFmt.code(xfOrDefault(xf).numFmtId, numFmts)
    fun xfOrDefault(i: Int): XXf = xfs.getOrNull(i) ?: DEFAULT
    companion object { val DEFAULT = XXf(0, 0, 0, null, false, null); val EMPTY = XStyles(emptyList(), emptyList(), emptyList(), emptyMap(), BooleanArray(0)) }
}

class XSheetInfo(val index: Int, val name: String, val part: String, val hidden: Boolean, val veryHidden: Boolean, val sheetId: String?)

class XMerge(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

/** A loaded worksheet. Cells are sparse: [rows] only holds rows that have cells, each row sorted by column. */
class XSheet(
    val info: XSheetInfo,
    val rows: java.util.TreeMap<Int, List<XCell>>,
    val merges: List<XMerge>,
    val colWidths: Map<Int, Double>,           // 0-based col -> width in characters
    val hiddenCols: Set<Int>,
    val hiddenRows: Set<Int>,
    val maxRow: Int,                           // highest 0-based row index with data, -1 if empty
    val maxCol: Int,
    val frozenRows: Int,
    val frozenCols: Int,
    val truncated: Boolean,                    // hit the row/cell limit
    val showGrid: Boolean = true,              // Part 9: sheetView showGridLines
    val cf: List<XCfRule> = emptyList(),       // Part 9: conditional formatting
    val notes: List<XNote> = emptyList(),      // Part 9: cell comments
    val fileWidths: Boolean = false,           // Part 9: colWidths are the xlsx `width` values (already include the padding: pixels = width x digit width); false = characters (xls / ods)
    val defaultColWidth: Double? = null,       // Part 9: sheetFormatPr defaultColWidth (same unit as colWidths)
    val charts: List<XChartFrame> = emptyList()   // Part 10: charts of the sheet's drawing part (xlsx only)
) {
    fun cell(r: Int, c: Int): XCell? = rows[r]?.firstOrNull { it.col == c }
}

object XRef {
    /** 0 -> "A", 25 -> "Z", 26 -> "AA" */
    fun colName(c: Int): String { var n = c; val sb = StringBuilder(); do { sb.append('A' + n % 26); n = n / 26 - 1 } while (n >= 0); return sb.reverse().toString() }
    fun name(col: Int, row: Int) = colName(col) + (row + 1)
    /** "AB12" -> (col=27,row=11); null if malformed. Ignores `$`. */
    fun parse(ref: String): IntArray? {
        var i = 0; var c = 0; var any = false
        while (i < ref.length && (ref[i] == '$' || ref[i].isLetter())) { if (ref[i] != '$') { c = c * 26 + (ref[i].uppercaseChar() - 'A' + 1); any = true }; i++ }
        if (!any) return null
        val r = ref.substring(i).trimStart('$').toIntOrNull() ?: return null
        return intArrayOf(c - 1, r - 1)
    }
    /** "A1:C5" or "B2" -> [c1,r1,c2,r2] */
    fun range(ref: String): IntArray? {
        val p = ref.split(':'); val a = parse(p[0]) ?: return null; val b = if (p.size > 1) parse(p[1]) ?: return null else a
        return intArrayOf(a[0], a[1], b[0], b[1])
    }
}
