package com.lanshare.app.core.office

/** Read-side Word model. Pure Kotlin (no android.*). Produced by [DocxReader]. */

class DImage(val part: String, val widthEmu: Long, val heightEmu: Long, val alt: String)

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

    fun sameStyle(o: DRun) = bold == o.bold && italic == o.italic && underline == o.underline && strike == o.strike &&
        caps == o.caps && color == o.color && highlight == o.highlight && size == o.size && script == o.script && link == o.link && font == o.font

    companion object { const val TEXT = 0; const val TAB = 1; const val BREAK = 2; const val PAGE = 3; const val IMAGE = 4; const val NOTE = 5; const val FIELD = 6 }
}

sealed class DBlock

class DPara : DBlock() {
    val runs = ArrayList<DRun>()
    var style = ""                  // resolved style name ("heading 1", "Normal", ...)
    var heading = 0                 // 0 = body text, 1..9 heading level
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
    val text: String
        get() = runs.joinToString("") {
            when (it.kind) { DRun.TEXT, DRun.FIELD -> it.text; DRun.TAB -> "\t"; DRun.BREAK, DRun.PAGE -> "\n"; else -> "" }
        }
}

class DCell {
    val blocks = ArrayList<DBlock>()
    var span = 1                    // gridSpan
    var vMerge = 0                  // 0 none, 1 restart (top of merged), 2 continue (covered)
    var width = 0                   // twips
    var fill: String? = null        // RRGGBB
}

class DRow {
    val cells = ArrayList<DCell>()
    var header = false
}

class DTable : DBlock() {
    val grid = ArrayList<Int>()     // column widths, twips
    val rows = ArrayList<DRow>()
    var style = ""
    var borders = false
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

    /** Section that contains body block [blockIndex]; a default Letter section when the format has no section info. */
    fun sectionAt(blockIndex: Int): DSection =
        sections.lastOrNull { it.startsAtBlockIndex <= blockIndex } ?: sections.firstOrNull() ?: DSection()

    fun plainText(blocks: List<DBlock> = body): String {
        val sb = StringBuilder()
        fun go(bs: List<DBlock>) {
            for (b in bs) when (b) {
                is DPara -> { if (b.label != null) sb.append(b.label).append(' '); sb.append(b.text).append('\n') }
                is DTable -> for (r in b.rows) {
                    for ((i, c) in r.cells.withIndex()) { if (i > 0) sb.append('\t'); go(c.blocks) }
                }
            }
        }
        go(blocks)
        return sb.toString()
    }
}
