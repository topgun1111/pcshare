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
    var script = 0                  // 1 superscript, -1 subscript
    var link: String? = null        // external URL or "#bookmark"
    var image: DImage? = null       // kind == IMAGE
    var endnote = false             // kind == NOTE: text = note id

    fun sameStyle(o: DRun) = bold == o.bold && italic == o.italic && underline == o.underline && strike == o.strike &&
        caps == o.caps && color == o.color && highlight == o.highlight && size == o.size && script == o.script && link == o.link

    companion object { const val TEXT = 0; const val TAB = 1; const val BREAK = 2; const val PAGE = 3; const val IMAGE = 4; const val NOTE = 5 }
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
    val text: String
        get() = runs.joinToString("") {
            when (it.kind) { DRun.TEXT -> it.text; DRun.TAB -> "\t"; DRun.BREAK, DRun.PAGE -> "\n"; else -> "" }
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

class DDoc {
    val body = ArrayList<DBlock>()
    var header: List<DBlock> = emptyList()   // default header of the first section
    var footer: List<DBlock> = emptyList()
    val footnotes = LinkedHashMap<String, List<DBlock>>()
    val endnotes = LinkedHashMap<String, List<DBlock>>()
    var defaultSize = 0                      // half-points of the document default font (0 = unknown)
    var truncated = false                    // paragraph limit hit

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
