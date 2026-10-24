package com.lanshare.app.core.office

/** Read-side Word model. Pure Kotlin (no android.*). Produced by [DocxReader]. */

class DImage(val part: String, val widthEmu: Long, val heightEmu: Long, val alt: String) {
    var anchor: DAnchor? = null     // Part 6: null = inline picture, else a floating (anchored) picture
}

/** Part 6: position and wrapping of a floating object (wp:anchor, or VML style). Lengths in EMU (914400 = 1 inch, 9525 = 1 CSS px). */
class DAnchor {
    var wrap = "none"               // square | tight | tb (top and bottom) | none (in front of / behind text)
    var wrapText = "both"           // both | left | right | largest
    var behind = false              // behindDoc
    var relH = "column"             // page | margin | column | character
    var relV = "paragraph"          // page | margin | paragraph | line
    var hOff = 0L; var vOff = 0L    // posOffset
    var hAlign: String? = null      // left | center | right | inside | outside (when set it replaces hOff)
    var vAlign: String? = null      // top | center | bottom | inside | outside
    var distT = 0L; var distB = 0L; var distL = 114300L; var distR = 114300L
    var z = 0                       // relativeHeight / z-index (stacking order among floating objects)
}

/** Part 6: a drawing shape or text box (wps:wsp, VML rect / roundrect / oval / line / shape with a text box). */
class DShape {
    var anchor: DAnchor? = null     // null = inline
    var wEmu = 0L; var hEmu = 0L
    var geom = "rect"               // rect | roundRect | ellipse | line (anything else is drawn as rect)
    var fill: String? = null        // RRGGBB, null = no fill
    var line: String? = null        // RRGGBB, null = no outline
    var lineW = 9525L               // EMU
    var dash: String? = null
    var rot = 0.0                   // degrees
    var flipH = false; var flipV = false
    var blocks: List<DBlock> = emptyList()   // text box content
    var insL = 91440L; var insT = 45720L; var insR = 91440L; var insB = 45720L
    var dx = 0L; var dy = 0L        // offset inside a group (EMU), added to the anchor offset
    var alt = ""
}

class DRun(val kind: Int, var text: String = "") {
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var caps = false
    var color: String? = null       // RRGGBB
    var highlight: String? = null   // OOXML name: yellow, green, ...
    var size = 0                    // half-points, 0 = default
    var font: String? = null        // Part 3: font family as the document names it (theme fonts resolved), null = document default
    var script = 0                  // 1 superscript, -1 subscript
    var link: String? = null        // external URL or "#bookmark"
    var image: DImage? = null       // kind == IMAGE
    var endnote = false             // kind == NOTE: text = note id
    var field: String? = null       // Part 4, kind == FIELD: PAGE / NUMPAGES / DATE / TITLE (text = cached result)
    var shape: DShape? = null       // Part 6, kind == SHAPE: drawing shape / text box (inline or floating)
    var rev = 0                     // Part 8: tracked change: 0 none, 1 inserted (w:ins / moveTo), 2 deleted (w:del / moveFrom; text comes from w:delText)
    var revBy: String? = null       // Part 8: author of that change
    var cmt: String? = null         // Part 8: ids (comma separated) of the comments whose range covers this run; for kind == CMT the run text is the comment id

    fun sameStyle(o: DRun) = bold == o.bold && italic == o.italic && underline == o.underline && strike == o.strike &&
        caps == o.caps && color == o.color && highlight == o.highlight && size == o.size && script == o.script && link == o.link && font == o.font &&
        rev == o.rev && revBy == o.revBy && cmt == o.cmt

    companion object { const val TEXT = 0; const val TAB = 1; const val BREAK = 2; const val PAGE = 3; const val IMAGE = 4; const val NOTE = 5; const val FIELD = 6; const val SHAPE = 7; const val CMT = 8 }
}

sealed class DBlock

class DPara : DBlock() {
    val runs = ArrayList<DRun>()
    var style = ""                  // resolved style name ("heading 1", "Normal", ...)
    var heading = 0                 // 0 = body text, 1..9 heading level
    var hid = -1                    // Part 12: outline id (set by DocxOutline.build; the heading element gets id="lsh<hid>"), -1 = not in the outline
    var align: String? = null       // left / center / right / justify
    var label: String? = null       // list label: "1.", "a)", "•"
    var bullet = false
    var level = 0                   // list level 0..8
    var indentLeft = 0              // twips
    var firstLine = 0               // twips (negative = hanging)
    var pageBreakBefore = false
    var spBefore = -1.0             // Part 5: space before / after in points (-1 = not set: renderer default)
    var spAfter = -1.0
    var lineMul = 0.0               // Part 5: line spacing as a multiple of single (w:lineRule auto: 1.08, 1.5 ...), 0 = not set
    var lineExact = 0.0             // Part 5: exact / at-least line height in points (0 = not set)
    var lineAtLeast = false
    var tabs: List<DTab> = emptyList()   // Part 7: custom tab stops (style + direct, cleared stops removed), sorted by position
    var fill: String? = null        // Part 7: paragraph shading RRGGBB
    var bdr: DBorders? = null       // Part 7: paragraph borders (top / left / bottom / right)
    var bookmarks: List<String> = emptyList()   // Part 8: names of the bookmarks that start in this paragraph (targets of internal links / TOC entries)
    val text: String
        get() = runs.joinToString("") {
            if (it.rev == 2) "" else when (it.kind) { DRun.TEXT, DRun.FIELD -> it.text; DRun.TAB -> "\t"; DRun.BREAK, DRun.PAGE -> "\n"; else -> "" }
        }
}

/** Part 7: one border line. [style] = OOXML name (single, double, dotted, dashed, thick, ... , "none" / "nil" = explicitly no line), [sz] = eighths of a point, [color] RRGGBB or null (= auto = black). */
class DBorder(val style: String, val sz: Int, val color: String?) {
    val none: Boolean get() = style == "none" || style == "nil" || style.isEmpty()
}

/** Part 7: set of borders (table: six, cell / paragraph: the four outer ones). A null side = not specified (inherit), a DBorder with [DBorder.none] = cleared. */
class DBorders {
    var top: DBorder? = null; var left: DBorder? = null; var bottom: DBorder? = null; var right: DBorder? = null
    var insideH: DBorder? = null; var insideV: DBorder? = null
    fun overlay(o: DBorders) {
        o.top?.let { top = it }; o.left?.let { left = it }; o.bottom?.let { bottom = it }; o.right?.let { right = it }
        o.insideH?.let { insideH = it }; o.insideV?.let { insideV = it }
    }
    val any: Boolean get() = top != null || left != null || bottom != null || right != null || insideH != null || insideV != null
}

/** Part 7: tab stop. [pos] twips from the text margin; [kind] left | center | right | decimal | bar; [leader] none | dot | hyphen | underscore | middleDot | heavy. */
class DTab(val pos: Int, val kind: String, val leader: String)

class DCell {
    val blocks = ArrayList<DBlock>()
    var span = 1                    // gridSpan
    var vMerge = 0                  // 0 none, 1 restart (top of merged), 2 continue (covered)
    var width = 0                   // twips
    var fill: String? = null        // RRGGBB; Part 7: final value (direct shading, else table style: banding / first row / ...)
    var bT: DBorder? = null; var bL: DBorder? = null; var bB: DBorder? = null; var bR: DBorder? = null   // Part 7: final borders (style + table + cell merged), null / none = no line
    var mar: IntArray? = null       // Part 7: cell margins top, left, bottom, right in twips (null = table default)
    var vAlign: String? = null      // Part 7: top | center | bottom
}

class DRow {
    val cells = ArrayList<DCell>()
    var header = false
    var height = 0                  // Part 7: trHeight in twips (0 = auto)
}

class DTable : DBlock() {
    val grid = ArrayList<Int>()     // column widths, twips
    val rows = ArrayList<DRow>()
    var style = ""
    var borders = false             // legacy guess: style name contains "grid" or tblBorders present (used by reading mode and when [resolved] is false)
    var resolved = false            // Part 7: cell borders / fills come from the real table style + tblBorders (the cell fields are authoritative)
    var anyBorder = false           // Part 7: at least one cell has a visible border
    var jc: String? = null          // Part 7: left | center | right
    var indent = 0                  // Part 7: tblInd twips
    var mar: IntArray? = null       // Part 7: default cell margins top, left, bottom, right twips (null = Word default 0 / 108 / 0 / 108)
}

/**
 * Part 4: one Word section (w:sectPr). All lengths in POINTS (twips / 20). Defaults are Word's for a missing value
 * (US Letter, 1 inch margins, header / footer 0.5 inch, column gap 0.5 inch).
 * Header / footer lists are already resolved: a section without its own reference inherits the previous section's.
 */
class DSection {
    var pgW = 612.0; var pgH = 792.0
    var orient = "portrait"                  // "portrait" | "landscape" (w:pgSz orient, else derived from the size)
    var marT = 72.0; var marB = 72.0; var marL = 72.0; var marR = 72.0   // may be negative (= exact distance in Word)
    var gutter = 0.0
    var hdrDist = 36.0; var ftrDist = 36.0   // header / footer distance from the page edge
    var cols = 1; var colGap = 36.0
    var colSep = false                       // line between columns
    var colWidths: List<Double> = emptyList()   // only when the columns are NOT equal width
    var titlePg = false                      // different first page
    var evenOdd = false                      // document setting evenAndOddHeaders (copied into every section)
    var breakType = "nextPage"               // nextPage | continuous | nextColumn | evenPage | oddPage
    var pgNumStart = -1                      // w:pgNumType start, -1 = continue
    var pgNumFmt: String? = null
    var headerDefault: List<DBlock> = emptyList(); var headerFirst: List<DBlock> = emptyList(); var headerEven: List<DBlock> = emptyList()
    var footerDefault: List<DBlock> = emptyList(); var footerFirst: List<DBlock> = emptyList(); var footerEven: List<DBlock> = emptyList()
    var startsAtBlockIndex = 0               // index into DDoc.body of the first block of this section

    /** Width of the text area (page minus margins and gutter), points. */
    val contentW: Double get() = pgW - marL - marR - gutter
    /** Height of the text area, points. */
    val contentH: Double get() = pgH - marT - marB

    /** Header for a page: [firstOfSection] = first page of this section, [pageNo] = displayed page number. */
    fun headerFor(firstOfSection: Boolean, pageNo: Int): List<DBlock> =
        if (titlePg && firstOfSection) headerFirst else if (evenOdd && pageNo % 2 == 0) headerEven else headerDefault
    fun footerFor(firstOfSection: Boolean, pageNo: Int): List<DBlock> =
        if (titlePg && firstOfSection) footerFirst else if (evenOdd && pageNo % 2 == 0) footerEven else footerDefault
}

/** Part 8: one comment of word/comments.xml. [date] is the ISO text as stored (may be empty), [text] the plain text (paragraphs joined by a newline). */
class DComment(val id: String, val author: String, val initials: String, val date: String, val text: String)

class DDoc {
    val body = ArrayList<DBlock>()
    /** Part 4: sections of a .docx in document order (never empty after DocxReader; EMPTY for other formats: use [sectionAt]). */
    val sections = ArrayList<DSection>()
    var evenOdd = false                      // w:evenAndOddHeaders in settings.xml
    var header: List<DBlock> = emptyList()   // default header (first section that has one; kept for the HTML view)
    var footer: List<DBlock> = emptyList()
    val footnotes = LinkedHashMap<String, List<DBlock>>()
    val endnotes = LinkedHashMap<String, List<DBlock>>()
    var defaultSize = 0                      // half-points of the document default font (0 = unknown)
    var defaultFont: String? = null          // Part 3: document default font family (null = unknown, Calibri assumed)
    var truncated = false                    // paragraph limit hit
    val comments = LinkedHashMap<String, DComment>()   // Part 8: comment id -> comment, in file order (the number shown in the page is the position + 1)
    var hasRev = false                       // Part 8: the document contains tracked insertions / deletions

    /** Section that contains body block [blockIndex]; a default Letter section when the format has no section info. */
    fun sectionAt(blockIndex: Int): DSection =
        sections.lastOrNull { it.startsAtBlockIndex <= blockIndex } ?: sections.firstOrNull() ?: DSection()

    fun plainText(blocks: List<DBlock> = body): String {
        val sb = StringBuilder()
        fun go(bs: List<DBlock>) {
            for (b in bs) when (b) {
                is DPara -> {
                    if (b.label != null) sb.append(b.label).append(' ')
                    sb.append(b.text).append('\n')
                    for (r in b.runs) r.shape?.let { go(it.blocks) }   // Part 6: text box content stays searchable
                }
                is DTable -> for (r in b.rows) {
                    for ((i, c) in r.cells.withIndex()) { if (i > 0) sb.append('\t'); go(c.blocks) }
                }
            }
        }
        go(blocks)
        return sb.toString()
    }
}
