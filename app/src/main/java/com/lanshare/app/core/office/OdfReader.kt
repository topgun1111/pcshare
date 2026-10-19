package com.lanshare.app.core.office

import java.io.File

/**
 * OpenDocument reader (Part 6c): `.odt` -> [DDoc] (same model as DocxReader, so DocxHtml works), `.ods` -> [XSheet]
 * (same model as XlsxReader, so XlsxHtml works). Read-only. No android.* imports.
 * Usage: `OdfReader(file).use { if (it.isSheet) it.readSheet(0) else it.read() }`.
 * Local names only: `text:p` -> "p", `fo:font-weight` -> "font-weight", `xlink:href` -> "href".
 */
class OdfReader(file: File) : AutoCloseable {
    private val pkg = OoxmlPackage(file)
    val isSheet: Boolean
    val sheets: List<XSheetInfo>
    private val styles = HashMap<String, St>()       // "family:name"
    private val listStyles = HashMap<String, HashMap<Int, LvlSt>>()
    private var defSize = 0
    private val content: String
    private val frozen = HashMap<String, IntArray>() // sheet name -> [cols, rows]

    private class St(val family: String, val parent: String?) {
        var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var strike: Boolean? = null
        var caps: Boolean? = null; var color: String? = null; var bg: String? = null; var size: Int? = null; var script: Int? = null
        var align: String? = null; var ml: Int? = null; var ti: Int? = null; var pb: Boolean? = null
        var width: Int? = null                      // table-column: twips
        var display: Boolean? = null                // table: false = hidden sheet
    }
    private class LvlSt(val number: Boolean, val fmt: String, val suffix: String)
    private class Fmt(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
                      val caps: Boolean = false, val color: String? = null, val bg: String? = null, val size: Int = 0, val script: Int = 0, val link: String? = null) {
        fun with(s: St?): Fmt = if (s == null) this else Fmt(s.bold ?: bold, s.italic ?: italic, s.underline ?: underline, s.strike ?: strike,
            s.caps ?: caps, s.color ?: color, s.bg ?: bg, s.size ?: size, s.script ?: script, link)
        fun link(l: String?) = Fmt(bold, italic, underline, strike, caps, color, bg, size, script, l)
    }

    init {
        val mt = pkg.read("mimetype")?.toString(Charsets.US_ASCII)?.trim().orEmpty()
        val man = pkg.read("META-INF/manifest.xml")?.toString(Charsets.UTF_8).orEmpty()
        if (man.contains("encryption-data")) throw IllegalStateException("File is password protected")
        isSheet = when {
            mt.contains("spreadsheet") -> true
            mt.contains("opendocument.text") -> false
            mt.contains("presentation") -> throw IllegalArgumentException("OpenDocument presentations are not supported")
            else -> if (pkg.has("content.xml")) false else throw IllegalArgumentException("Not an OpenDocument file")
        }
        content = String(pkg.read("content.xml") ?: throw IllegalArgumentException("content.xml missing"), Charsets.UTF_8)
        try { pkg.xml("styles.xml")?.let { parseStyles(it) } } catch (_: Throwable) {}
        parseStyles(MiniXml(content))
        if (isSheet) try { pkg.xml("settings.xml")?.let { parseSettings(it) } } catch (_: Throwable) {}
        sheets = if (isSheet) listSheets() else emptyList()
    }

    // ---------------------------------------------------------------- styles
    private fun len(s: String?): Int? {                       // -> twips
        if (s == null) return null
        val m = Regex("^\\s*(-?[0-9.]+)\\s*([a-z%]*)").find(s) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) { "cm" -> v * 567; "mm" -> v * 56.7; "in" -> v * 1440; "pt" -> v * 20; "px" -> v * 15; "pc" -> v * 240; else -> return null }.toInt()
    }

    private fun parseStyles(x: MiniXml) {
        var cur: St? = null; var curKey = ""
        var ls: HashMap<Int, LvlSt>? = null; var lvl = 0
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return
                MiniXml.T.START -> when (x.name) {
                    "style" -> { val n = x.attr("name"); val fam = x.attr("family") ?: ""
                        if (n != null) { cur = St(fam, x.attr("parent-style-name")); curKey = "$fam:$n"; styles[curKey] = cur } }
                    "default-style" -> { val fam = x.attr("family") ?: ""; cur = St(fam, null); curKey = "$fam:"; styles[curKey] = cur }
                    "text-properties" -> cur?.let { s ->
                        x.attr("font-weight")?.let { s.bold = it == "bold" || (it.toIntOrNull() ?: 0) >= 600 }
                        x.attr("font-style")?.let { s.italic = it == "italic" || it == "oblique" }
                        x.attr("text-underline-style")?.let { s.underline = it != "none" }
                        x.attr("text-line-through-style")?.let { s.strike = it != "none" }
                        x.attr("text-transform")?.let { s.caps = it == "uppercase" }
                        x.attr("font-variant")?.let { if (it == "small-caps") s.caps = true }
                        x.attr("color")?.let { s.color = hex(it) }
                        x.attr("background-color")?.let { s.bg = hex(it) }
                        x.attr("font-size")?.let { if (it.endsWith("pt")) it.removeSuffix("pt").toDoubleOrNull()?.let { v -> s.size = Math.round(v * 2).toInt() } }
                        x.attr("text-position")?.let { s.script = when { it.startsWith("sub") || it.startsWith("-") -> -1; it.startsWith("0%") -> 0; else -> 1 } }
                        if (curKey == "paragraph:" && s.size != null) defSize = s.size!!
                    }
                    "paragraph-properties" -> cur?.let { s ->
                        x.attr("text-align")?.let { s.align = when (it) { "start" -> "left"; "end" -> "right"; else -> it } }
                        len(x.attr("margin-left"))?.let { s.ml = it }
                        len(x.attr("text-indent"))?.let { s.ti = it }
                        x.attr("break-before")?.let { s.pb = it == "page" }
                        x.attr("background-color")?.let { if (s.bg == null) s.bg = hex(it) }
                    }
                    "table-cell-properties" -> cur?.let { s -> x.attr("background-color")?.let { s.bg = hex(it) } }
                    "table-column-properties" -> cur?.let { s -> len(x.attr("column-width"))?.let { s.width = it } }
                    "table-properties" -> cur?.let { s -> x.attr("display")?.let { s.display = it != "false" } }
                    "list-style" -> { val n = x.attr("name"); if (n != null) { ls = HashMap(); listStyles[n] = ls } }
                    "list-level-style-number", "list-level-style-bullet", "list-level-style-image" -> {
                        lvl = (x.attr("level")?.toIntOrNull() ?: 1) - 1
                        ls?.put(lvl, LvlSt(x.name == "list-level-style-number", x.attr("num-format") ?: "1", x.attr("num-suffix") ?: "."))
                    }
                }
                MiniXml.T.END -> when (x.name) { "style", "default-style" -> cur = null; "list-style" -> ls = null }
                else -> {}
            }
        }
    }

    private fun hex(c: String): String? = if (c.length == 7 && c[0] == '#') c.substring(1).uppercase() else null

    private fun parseSettings(x: MiniXml) {
        var entry: String? = null; var item: String? = null
        val vals = HashMap<String, HashMap<String, Int>>()
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> when (x.name) {
                    "config-item-map-entry" -> entry = x.attr("name")
                    "config-item" -> { item = x.attr("name"); val e = entry
                        if (e != null && item != null && (item.endsWith("SplitMode") || item.endsWith("SplitPosition"))) vals.getOrPut(e) { HashMap() }[item] = x.readText().trim().toIntOrNull() ?: 0 }
                }
                MiniXml.T.END -> if (x.name == "config-item-map-entry") entry = null
                else -> {}
            }
        }
        for ((n, m) in vals) {
            val c = if (m["HorizontalSplitMode"] == 2) m["HorizontalSplitPosition"] ?: 0 else 0
            val r = if (m["VerticalSplitMode"] == 2) m["VerticalSplitPosition"] ?: 0 else 0
            if (c > 0 || r > 0) frozen[n] = intArrayOf(c, r)
        }
    }

    private fun st(family: String, name: String?): St? = if (name == null) null else styles["$family:$name"]

    /** Merge a style with its parent chain into one St (child wins). */
    private fun resolve(family: String, name: String?): St? {
        if (name == null) return null
        val chain = ArrayList<St>(); var n: String? = name; var g = 0
        while (n != null && g++ < 12) { val s = styles["$family:$n"] ?: break; chain.add(s); n = s.parent }
        if (chain.isEmpty()) return null
        val r = St(family, null)
        for (s in chain.asReversed()) {
            s.bold?.let { r.bold = it }; s.italic?.let { r.italic = it }; s.underline?.let { r.underline = it }; s.strike?.let { r.strike = it }
            s.caps?.let { r.caps = it }; s.color?.let { r.color = it }; s.bg?.let { r.bg = it }; s.size?.let { r.size = it }; s.script?.let { r.script = it }
            s.align?.let { r.align = it }; s.ml?.let { r.ml = it }; s.ti?.let { r.ti = it }; s.pb?.let { r.pb = it }; s.width?.let { r.width = it }; s.display?.let { r.display = it }
        }
        return r
    }

    // ---------------------------------------------------------------- text documents
    private var paraCount = 0
    private var paraMax = 20000
    private lateinit var doc: DDoc

    fun read(maxParagraphs: Int = 20000): DDoc {
        if (isSheet) throw IllegalStateException("Not a text document")
        doc = DDoc(); paraCount = 0; paraMax = maxParagraphs; doc.defaultSize = defSize
        val x = MiniXml(content)
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) break
            if (t == MiniXml.T.START && x.name == "text" && x.rawName == "office:text") { blocks(x, doc.body, null); break }
        }
        return doc
    }

    private class ListCtx(var style: String?) { val counters = IntArray(10) }

    /** Reads children of the current container (parser is ON its START) until its END; appends block elements to [out]. */
    private fun blocks(x: MiniXml, out: MutableList<DBlock>, list: ListCtx?, listLevel: Int = -1) {
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return
                MiniXml.T.END -> if (x.depth < d) return
                MiniXml.T.START -> when (x.name) {
                    "p", "h" -> {
                        if (paraCount >= paraMax) { doc.truncated = true; x.skip() }
                        else { paraCount++; out.add(para(x, list, listLevel)) }
                    }
                    "list" -> {
                        val ctx = list ?: ListCtx(x.attr("style-name"))
                        if (list != null) x.attr("style-name")?.let { ctx.style = it }
                        val lv = listLevel + 1
                        ctx.counters.fill(0, minOf(lv + 1, 10), 10)
                        list(x, out, ctx, lv)
                    }
                    "table" -> out.add(table(x))
                    "tracked-changes", "sequence-decls", "variable-decls", "user-field-decls", "forms", "table-of-content-source", "alphabetical-index-source",
                    "bibliography-source", "illustration-index-source", "user-index-source", "object-index-source", "table-index-source" -> x.skip()
                    else -> blocks(x, out, list, listLevel)      // section, index-body, ...
                }
                else -> {}
            }
        }
    }

    private fun list(x: MiniXml, out: MutableList<DBlock>, ctx: ListCtx, lv: Int) {
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return
                MiniXml.T.END -> if (x.depth < d) return
                MiniXml.T.START -> if (x.name == "list-item" || x.name == "list-header") {
                    if (x.name == "list-item") ctx.counters[minOf(lv, 9)]++
                    blocks(x, out, ctx, lv)
                } else x.skip()
                else -> {}
            }
        }
    }

    private fun label(ctx: ListCtx, lv: Int): Pair<String, Boolean> {
        val ls = ctx.style?.let { listStyles[it] }?.let { it[lv] ?: it[lv.coerceAtMost(9)] }
        if (ls == null || !ls.number) return "•" to true
        val n = ctx.counters[minOf(lv, 9)].coerceAtLeast(1)
        val s = when (ls.fmt) {
            "a" -> alpha(n, false); "A" -> alpha(n, true); "i" -> roman(n).lowercase(); "I" -> roman(n)
            else -> n.toString()
        }
        return (s + ls.suffix) to false
    }
    private fun alpha(n: Int, up: Boolean): String { var k = n - 1; val sb = StringBuilder(); do { sb.append((if (up) 'A' else 'a') + k % 26); k = k / 26 - 1 } while (k >= 0); return sb.reverse().toString() }
    private fun roman(n0: Int): String {
        var n = n0; val sb = StringBuilder()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1); val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }

    private fun para(x: MiniXml, list: ListCtx?, lv: Int): DPara {
        val p = DPara()
        val isH = x.name == "h"
        val sname = x.attr("style-name")
        val ps = resolve("paragraph", sname)
        p.style = sname ?: "Standard"
        if (isH) p.heading = (x.attr("outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 9).also { p.style = "heading $it" }
        ps?.align?.let { p.align = it }
        ps?.ml?.let { p.indentLeft = it }; ps?.ti?.let { p.firstLine = it }; ps?.pb?.let { p.pageBreakBefore = it }
        if (list != null && lv >= 0) {
            val (lab, bul) = label(list, lv)
            p.label = lab; p.bullet = bul; p.level = lv.coerceIn(0, 8)
            if (p.indentLeft == 0) p.indentLeft = 360 * (lv + 1) + 360
            p.firstLine = -360
        }
        val base = Fmt().with(ps?.let { St("text", null).also { t -> t.bold = it.bold; t.italic = it.italic; t.underline = it.underline; t.strike = it.strike; t.caps = it.caps; t.color = it.color; t.size = it.size; t.script = it.script } })
        inline(x, p, base)
        return p
    }

    private fun addText(p: DPara, s: String, f: Fmt) {
        if (s.isEmpty()) return
        val r = DRun(DRun.TEXT, s)
        r.bold = f.bold; r.italic = f.italic; r.underline = f.underline; r.strike = f.strike; r.caps = f.caps; r.color = f.color
        r.size = f.size; r.script = f.script; r.link = f.link; r.highlight = f.bg?.let { nearestHl(it) }
        val last = p.runs.lastOrNull()
        if (last != null && last.kind == DRun.TEXT && last.sameStyle(r)) last.text += s else p.runs.add(r)
    }

    private val HLS = mapOf("yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "blue" to 0x0000FF, "red" to 0xFF0000)
    private fun nearestHl(h: String): String? {
        val v = h.toIntOrNull(16) ?: return null
        if (v == 0xFFFFFF) return null
        fun dist(a: Int, b: Int): Int { var s = 0; for (sh in intArrayOf(0, 8, 16)) { val d = ((a shr sh) and 255) - ((b shr sh) and 255); s += d * d }; return s }
        return HLS.minByOrNull { dist(it.value, v) }?.key
    }

    private val WS = Regex("[ \\t\\r\\n]+")

    /** Inline content of the current element (parser ON its START) until its END. */
    private fun inline(x: MiniXml, p: DPara, f: Fmt) {
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return
                MiniXml.T.END -> if (x.depth < d) return
                MiniXml.T.TEXT -> addText(p, WS.replace(x.text, " "), f)
                MiniXml.T.START -> when (x.name) {
                    "span" -> inline(x, p, f.with(resolve("text", x.attr("style-name"))))
                    "a" -> inline(x, p, f.link(x.attr("href")?.takeIf { it.isNotEmpty() }))
                    "s" -> { addText(p, " ".repeat((x.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200)), f); x.skip() }
                    "tab" -> { p.runs.add(DRun(DRun.TAB)); x.skip() }
                    "line-break" -> { p.runs.add(DRun(DRun.BREAK)); x.skip() }
                    "soft-page-break", "bookmark", "bookmark-start", "bookmark-end", "annotation", "annotation-end", "change", "change-start", "change-end" -> x.skip()
                    "frame" -> frame(x, p, f)
                    "note" -> note(x, p)
                    else -> inline(x, p, f)
                }
                else -> {}
            }
        }
    }

    private fun frame(x: MiniXml, p: DPara, f: Fmt) {
        val wTw = len(x.attr("width")) ?: 0; val hTw = len(x.attr("height")) ?: 0; val nm = x.attr("name") ?: ""
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return
                MiniXml.T.END -> if (x.depth < d) return
                MiniXml.T.START -> when (x.name) {
                    "image" -> {
                        val href = x.attr("href")
                        if (href != null && !href.contains(':') && !href.startsWith("..")) {
                            val r = DRun(DRun.IMAGE); r.image = DImage(href.removePrefix("./"), wTw * 635L, hTw * 635L, nm); p.runs.add(r)
                        }
                        x.skip()
                    }
                    "text-box" -> inline(x, p, f)
                    else -> {}
                }
                else -> {}
            }
        }
    }

    private fun note(x: MiniXml, p: DPara) {
        val id = x.attr("id") ?: "n" + (doc.footnotes.size + doc.endnotes.size + 1)
        val end = x.attr("note-class") == "endnote"
        val body = ArrayList<DBlock>()
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.END -> if (x.depth < d) break
                MiniXml.T.START -> if (x.name == "note-body") blocks(x, body, null) else x.skip()
                else -> {}
            }
        }
        if (end) doc.endnotes[id] = body else doc.footnotes[id] = body
        val r = DRun(DRun.NOTE, id); r.endnote = end; p.runs.add(r)
    }

    private fun table(x: MiniXml): DTable {
        val t = DTable(); t.borders = true; t.style = x.attr("style-name") ?: ""
        val d = x.depth
        var hdrDepth = -1
        var vrem = IntArray(64); var vw = IntArray(64)
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.END -> { if (x.depth < d) break; if (hdrDepth >= 0 && x.depth < hdrDepth) hdrDepth = -1 }
                MiniXml.T.START -> when (x.name) {
                    "table-column" -> {
                        val w = resolve("table-column", x.attr("style-name"))?.width ?: 1400
                        repeat((x.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 100)) { if (t.grid.size < 200) t.grid.add(w) }
                    }
                    "table-header-rows" -> hdrDepth = x.depth
                    "table-row" -> {
                        if (vrem.size < t.grid.size + 2) { vrem = vrem.copyOf(t.grid.size + 64); vw = vw.copyOf(t.grid.size + 64) }
                        val rep = (x.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 20)
                        val row = tableRow(x, hdrDepth >= 0, vrem, vw)
                        repeat(rep) { t.rows.add(row) }
                    }
                    "table-columns", "table-header-columns", "table-column-group", "table-rows", "table-row-group" -> {}   // descend
                    else -> x.skip()
                }
                else -> {}
            }
        }
        if (t.grid.isEmpty()) { val n = t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1; repeat(n) { t.grid.add(1400) } }
        return t
    }

    private fun tableRow(x: MiniXml, header: Boolean, vrem: IntArray, vw: IntArray): DRow {
        val row = DRow(); row.header = header
        var col = 0; var hskip = 0
        val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.END -> if (x.depth < d) break
                MiniXml.T.START -> when (x.name) {
                    "table-cell", "covered-table-cell" -> {
                        val covered = x.name == "covered-table-cell"
                        val rep = (x.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 100)
                        val cs = (x.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val rs = (x.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                        val cell = DCell()
                        if (!covered) {
                            cell.span = cs; cell.fill = resolve("table-cell", x.attr("style-name"))?.bg
                            if (rs > 1) cell.vMerge = 1
                            blocks(x, cell.blocks, null)
                        } else x.skip()
                        for (k in 0 until rep) {
                            if (col >= vrem.size) break
                            if (hskip > 0) { hskip--; col++; continue }
                            if (covered) {
                                val w = vw[col].coerceAtLeast(1)
                                if (vrem[col] > 0) { val c2 = DCell(); c2.vMerge = 2; c2.span = w; row.cells.add(c2); for (j in col until minOf(col + w, vrem.size)) vrem[j]-- }
                                else row.cells.add(DCell())
                                hskip = w - 1; col++
                            } else {
                                row.cells.add(cell)
                                if (rs > 1) for (j in col until minOf(col + cs, vrem.size)) { vrem[j] = rs - 1; vw[j] = cs }
                                hskip = cs - 1; col++
                            }
                        }
                    }
                    else -> x.skip()
                }
                else -> {}
            }
        }
        return row
    }

    // ---------------------------------------------------------------- spreadsheets
    private fun listSheets(): List<XSheetInfo> {
        val out = ArrayList<XSheetInfo>()
        val x = MiniXml(content)
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> when {
                    x.name == "table" && x.rawName == "table:table" -> {
                        val hidden = resolve("table", x.attr("style-name"))?.display == false
                        out.add(XSheetInfo(out.size, x.attr("name") ?: "Sheet${out.size + 1}", "", hidden, false, null)); x.skip()
                    }
                    else -> {}
                }
                else -> {}
            }
        }
        return out
    }

    fun readSheet(index: Int, maxRows: Int = 5000, maxCols: Int = 200, maxCells: Int = 250_000): XSheet {
        val info = sheets.getOrNull(index) ?: throw IndexOutOfBoundsException("No sheet $index")
        val x = MiniXml(content)
        var n = -1
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) throw IllegalStateException("Sheet not found")
            if (t == MiniXml.T.START && x.name == "table" && x.rawName == "table:table") { n++; if (n == index) break else x.skip() }
        }
        val rows = java.util.TreeMap<Int, List<XCell>>()
        val merges = ArrayList<XMerge>(); val colW = HashMap<Int, Double>(); val hCols = HashSet<Int>(); val hRows = HashSet<Int>()
        var maxR = -1; var maxC = -1; var cells = 0; var trunc = false
        var colIdx = 0; var r = 0
        val d = x.depth
        loop@ while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break@loop
                MiniXml.T.END -> if (x.depth < d) break@loop
                MiniXml.T.START -> when (x.name) {
                    "table-column" -> {
                        val rep = (x.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 20000)
                        val w = resolve("table-column", x.attr("style-name"))?.width
                        val hid = x.attr("visibility").let { it == "collapse" || it == "filter" }
                        for (k in 0 until minOf(rep, maxCols - colIdx).coerceAtLeast(0)) { if (w != null) colW[colIdx + k] = w / 105.0; if (hid) hCols.add(colIdx + k) }
                        colIdx += rep
                    }
                    "table-row" -> {
                        val rep = (x.attr("number-rows-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 2_000_000)
                        val hid = x.attr("visibility").let { it == "collapse" || it == "filter" }
                        val row = ArrayList<XCell>(); val rowIdx = r
                        var c = 0
                        val rd = x.depth
                        rowLoop@ while (true) {
                            when (x.next()) {
                                MiniXml.T.EOF -> break@rowLoop
                                MiniXml.T.END -> if (x.depth < rd) break@rowLoop
                                MiniXml.T.START -> if (x.name == "table-cell" || x.name == "covered-table-cell") {
                                    val covered = x.name == "covered-table-cell"
                                    val crep = (x.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 2_000_000)
                                    val vt = x.attr("value-type")
                                    val cs = (x.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                                    val rs = (x.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                                    val value = x.attr("value"); val dv = x.attr("date-value"); val tv = x.attr("time-value")
                                    val bv = x.attr("boolean-value"); val sv = x.attr("string-value"); val fm = x.attr("formula")
                                    val sb = StringBuilder(); var np = 0
                                    val cd = x.depth
                                    while (true) {
                                        val e = x.next()
                                        if (e == MiniXml.T.EOF || (e == MiniXml.T.END && x.depth < cd)) break
                                        if (e == MiniXml.T.START && x.name == "p") { if (np++ > 0) sb.append('\n'); sb.append(cellText(x)) }
                                        else if (e == MiniXml.T.START && x.name != "s" && x.name != "span" && x.name != "a") x.skip()
                                    }
                                    var txt = sb.toString()
                                    if (!covered && (vt != null || txt.isNotEmpty())) {
                                        val kind: XCell.Kind; val raw: String
                                        when (vt) {
                                            "float", "percentage", "currency" -> { kind = XCell.Kind.NUMBER; raw = value ?: txt; if (txt.isEmpty() && value != null) txt = value.toDoubleOrNull()?.let { NumFmt.general(it) } ?: value }
                                            "date" -> { kind = XCell.Kind.DATE; raw = dv ?: txt; if (txt.isEmpty()) txt = dv?.replace('T', ' ') ?: "" }
                                            "time" -> { kind = XCell.Kind.DATE; raw = tv ?: txt; if (txt.isEmpty()) txt = tv?.removePrefix("PT") ?: "" }
                                            "boolean" -> { kind = XCell.Kind.BOOL; raw = if (bv == "true") "1" else "0"; if (txt.isEmpty()) txt = if (bv == "true") "TRUE" else "FALSE" }
                                            else -> { kind = if (txt.startsWith("#") && txt.endsWith("!") ) XCell.Kind.ERROR else XCell.Kind.TEXT; raw = sv ?: txt }
                                        }
                                        val formula = fm?.let { odfFormula(it) }
                                        for (k in 0 until crep) {
                                            if (c + k >= maxCols) { trunc = true; break }
                                            if (cells >= maxCells) { trunc = true; break }
                                            row.add(XCell(rowIdx, c + k, txt, raw, kind, formula, 0)); cells++
                                            if (c + k > maxC) maxC = c + k
                                        }
                                    }
                                    if (!covered && (cs > 1 || rs > 1)) for (k in 0 until minOf(crep, 100)) merges.add(XMerge(rowIdx, c + k, rowIdx + rs - 1, c + k + cs - 1))
                                    c += crep
                                } else x.skip()
                                else -> {}
                            }
                        }
                        if (row.isNotEmpty()) {
                            val reps = minOf(rep, (maxRows - rowIdx).coerceAtLeast(0), if (rep > 1) 1000 else 1)
                            for (k in 0 until reps) {
                                if (cells >= maxCells && k > 0) { trunc = true; break }
                                rows[rowIdx + k] = if (k == 0) row else row.map { XCell(rowIdx + k, it.col, it.text, it.raw, it.kind, it.formula, 0) }
                                if (hid) hRows.add(rowIdx + k)
                                if (rowIdx + k > maxR) maxR = rowIdx + k
                            }
                            if (rowIdx >= maxRows) { trunc = true; break@loop }
                        } else if (hid) for (k in 0 until minOf(rep, 1000)) hRows.add(rowIdx + k)
                        r += rep
                        if (r > maxRows + 1 && row.isEmpty()) { /* trailing empty repeat: nothing more to read */ }
                    }
                    "table-columns", "table-header-columns", "table-column-group", "table-header-rows", "table-rows", "table-row-group" -> {}
                    else -> x.skip()
                }
                else -> {}
            }
        }
        val fr = frozen[info.name]
        return XSheet(info, rows, merges, colW, hCols, hRows, maxR, maxC, fr?.get(1) ?: 0, fr?.get(0) ?: 0, trunc)
    }

    /** Text of one `text:p` in a cell (parser ON its START; ends ON its END). Handles spans, text:s, tab, line-break. */
    private fun cellText(x: MiniXml): String {
        val sb = StringBuilder(); val d = x.depth
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> return sb.toString()
                MiniXml.T.END -> if (x.depth < d) return sb.toString()
                MiniXml.T.TEXT -> sb.append(x.text)
                MiniXml.T.START -> when (x.name) {
                    "s" -> { sb.append(" ".repeat((x.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200))); x.skip() }
                    "tab" -> { sb.append('\t'); x.skip() }
                    "line-break" -> { sb.append('\n'); x.skip() }
                    "span", "a" -> {}
                    else -> x.skip()
                }
                else -> {}
            }
        }
    }

    /** `of:=SUM([.A1:.A3];[.B1])` -> `=SUM(A1:A3,B1)` (good enough for display). */
    private fun odfFormula(f: String): String {
        var s = f; val c = s.indexOf(':')
        if (c in 1..5 && s.substring(0, c).all { it.isLetter() }) s = s.substring(c + 1)
        s = Regex("\\[([^\\]]*)]").replace(s) { m -> m.groupValues[1].replace(Regex("(^|:)\\$?[^.:]*\\."), "$1").replace("$", "") }
        return s.replace(';', ',')
    }

    override fun close() { pkg.close() }
}
