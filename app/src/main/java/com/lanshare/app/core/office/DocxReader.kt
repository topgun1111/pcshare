package com.lanshare.app.core.office

import java.io.File

/**
 * Read-only .docx / .docm / .dotx reader -> [DDoc]. Streaming (MiniXml), no DOM.
 * Usage: DocxReader(file).use { it.read() }
 * Covers: paragraphs, runs (b/i/u/strike/color/size/highlight/sub/sup/caps), styles + heading levels, numbering labels,
 * tables (gridSpan/vMerge/shading), hyperlinks, images (part name + size), default header/footer, footnotes/endnotes,
 * page breaks, text boxes (text only, emitted as extra paragraphs after the host paragraph), content controls.
 */
class DocxReader(file: File) : AutoCloseable {
    private class RPr {
        var b: Boolean? = null; var i: Boolean? = null; var u: Boolean? = null; var strike: Boolean? = null; var caps: Boolean? = null
        var color: String? = null; var hl: String? = null; var rStyle: String? = null
        var sz = 0; var script = 0; var font: String? = null
        fun overlay(o: RPr) {
            o.b?.let { b = it }; o.i?.let { i = it }; o.u?.let { u = it }; o.strike?.let { strike = it }; o.caps?.let { caps = it }
            o.color?.let { color = it }; o.hl?.let { hl = it }; o.font?.let { font = it }
            if (o.sz > 0) sz = o.sz
            if (o.script != 0) script = o.script
        }
    }

    private class PPr {
        var style: String? = null; var jc: String? = null; var numId: String? = null; var pbb: Boolean? = null
        var ilvl = -1; var outline = -1
        var left = Int.MIN_VALUE; var first = Int.MIN_VALUE
        var before = Int.MIN_VALUE; var after = Int.MIN_VALUE; var line = Int.MIN_VALUE; var rule: String? = null   // Part 5: w:spacing (twips / 240ths)
        var tabs: ArrayList<DTab>? = null; var fill: String? = null; var bdr: DBorders? = null   // Part 7
        fun overlay(o: PPr) {
            o.tabs?.let { ot -> val t = tabs ?: ArrayList<DTab>().also { tabs = it }; for (k in ot) { t.removeAll { it.pos == k.pos }; t.add(k) } }
            o.fill?.let { fill = it }
            o.bdr?.let { b -> (bdr ?: DBorders().also { bdr = it }).overlay(b) }
            if (o.before != Int.MIN_VALUE) before = o.before
            if (o.after != Int.MIN_VALUE) after = o.after
            if (o.line != Int.MIN_VALUE) { line = o.line; rule = o.rule }
            o.jc?.let { jc = it }; o.numId?.let { numId = it }; o.pbb?.let { pbb = it }
            if (o.ilvl >= 0) ilvl = o.ilvl
            if (o.outline >= 0) outline = o.outline
            if (o.left != Int.MIN_VALUE) left = o.left
            if (o.first != Int.MIN_VALUE) first = o.first
        }
    }

    private class DStyle(val id: String) {
        var name = ""; var basedOn: String? = null
        val ppr = PPr(); val rpr = RPr()
    }

    private class NumLvl(val fmt: String, val text: String, val start: Int)

    private val pkg = OoxmlPackage(file)
    private val mainPart: String
    private val mainRels: Map<String, OoxmlPackage.Rel>
    private var cur: Map<String, OoxmlPackage.Rel>

    // Part 7: table styles (w:style w:type="table"), theme, table context of the cell being parsed. Declared BEFORE init (styles are parsed there).
    private class TCond {
        val rpr = RPr(); val ppr = PPr(); var fill: String? = null; var bdr: DBorders? = null
        fun overlay(o: TCond) { rpr.overlay(o.rpr); ppr.overlay(o.ppr); o.fill?.let { fill = it }; o.bdr?.let { b -> (bdr ?: DBorders().also { bdr = it }).overlay(b) } }
    }
    private class TStyle(val id: String) {
        var basedOn: String? = null; var rowBand = 0; var colBand = 0; var tbl: DBorders? = null; var mar: IntArray? = null
        val whole = TCond(); val conds = HashMap<String, TCond>()
        private val cache = HashMap<String, TCond>()
        /** Whole table + the conditional formats named in [keys] (lowest priority first). */
        fun merged(keys: List<String>): TCond = cache.getOrPut(keys.joinToString(",")) { val m = TCond(); m.overlay(whole); for (k in keys) conds[k]?.let { m.overlay(it) }; m }
    }
    private class TLook(var firstRow: Boolean = true, var lastRow: Boolean = false, var firstCol: Boolean = true, var lastCol: Boolean = false, var noHBand: Boolean = false, var noVBand: Boolean = true)
    private class TCtx(val ts: TStyle?, val look: TLook, val ncols: Int) { val direct = java.util.IdentityHashMap<DCell, DBorders>() }
    private class RowCtx(val tc: TCtx, val ri: Int) { var col = 0 }
    private val tstyles = HashMap<String, TStyle>()
    private val tresolved = HashMap<String, TStyle>()
    private var defTbl: String? = null
    private var tctx: TCond? = null
    private var themeC: Theme? = null
    private var themeFound = false
    private val styles = HashMap<String, DStyle>()
    private val resolved = HashMap<String, DStyle>()
    private var defRpr = RPr()
    private var defPpr = PPr()      // Part 5: docDefaults / pPrDefault (spacing)
    private var defPara: String? = null
    private val absLevels = HashMap<String, HashMap<Int, NumLvl>>()
    private val numToAbs = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    private var count = 0
    private var max = 20000
    private var truncated = false
    // Part 4: sections. Header / footer references are collected per section, resolved (with inheritance) after the body.
    private class SectRefs { val hdr = HashMap<String, String>(); val ftr = HashMap<String, String>() }
    private class Fld { val instr = StringBuilder(); var resultFrom = -1 }
    private val sects = ArrayList<DSection>()
    private val sectRefs = ArrayList<SectRefs>()
    private var secStart = 0
    private var bodyList: ArrayList<DBlock>? = null
    private var pendingSect: Pair<DSection, SectRefs>? = null
    private val fstack = ArrayList<Fld>()
    // Part 8: tracked changes + comments. [revKind] / [revAuthor] are set while the children of a w:ins / w:del are read; [openCmts] = comment ranges that are open at this point (ranges may span paragraphs).
    private var revKind = 0
    private var revAuthor: String? = null
    private var hasRev = false
    private val openCmts = LinkedHashSet<String>()

    init {
        mainPart = pkg.rels("").values.firstOrNull { it.kind == "officeDocument" && !it.external }?.target
            ?: if (pkg.has("word/document.xml")) "word/document.xml" else throw IllegalArgumentException("Not a Word document (no word/document.xml)")
        mainRels = pkg.rels(mainPart)
        cur = mainRels
        mainRels.values.firstOrNull { it.kind == "styles" && !it.external }?.let { parseStyles(it.target) }
        mainRels.values.firstOrNull { it.kind == "numbering" && !it.external }?.let { parseNumbering(it.target) }
    }

    /** Parse the whole document. [maxParagraphs] protects the phone; [DDoc.truncated] tells the UI. */
    fun read(maxParagraphs: Int = 20000): DDoc {
        max = maxParagraphs; count = 0; truncated = false; counters.clear(); cur = mainRels
        sects.clear(); sectRefs.clear(); secStart = 0; pendingSect = null; fstack.clear()
        revKind = 0; revAuthor = null; hasRev = false; openCmts.clear()
        val d = DDoc()
        bodyList = d.body
        d.evenOdd = readEvenOdd()
        pkg.xml(mainPart)?.let { x ->
            if (root(x)) eachChild(x) { n ->
                if (n == "body") eachChild(x) { c -> blockChild(x, c, d.body) } else x.skip()
            }
        }
        openCmts.clear()   // an unclosed comment range must not leak into headers / footers / notes
        d.truncated = truncated
        d.defaultSize = defRpr.sz
        d.defaultFont = defRpr.font
        if (sects.isEmpty()) addSect(DSection() to SectRefs(), 0)
        resolveSections(d)
        bodyList = null
        readNotes("footnotes", "footnote", d.footnotes)
        readNotes("endnotes", "endnote", d.endnotes)
        readComments(d.comments)
        d.hasRev = hasRev
        cur = mainRels
        return d
    }

    override fun close() { pkg.close() }

    // ---- parts ----

    private fun readPart(rid: String): List<DBlock> {
        val rel = mainRels[rid] ?: return emptyList()
        if (rel.external) return emptyList()
        val out = ArrayList<DBlock>()
        cur = pkg.rels(rel.target)
        pkg.xml(rel.target)?.let { x -> if (root(x)) eachChild(x) { blockChild(x, it, out) } }
        cur = mainRels
        return out
    }

    private fun readEvenOdd(): Boolean {
        val rel = mainRels.values.firstOrNull { it.kind == "settings" && !it.external } ?: return false
        val x = pkg.xml(rel.target) ?: return false
        if (!root(x)) return false
        var v = false
        eachChild(x) { n -> if (n == "evenAndOddHeaders") v = on(x); x.skip() }
        return v
    }

    private fun addSect(s: Pair<DSection, SectRefs>, start: Int) {
        s.first.startsAtBlockIndex = start
        sects.add(s.first); sectRefs.add(s.second)
    }

    /** Header / footer parts per section; a missing reference inherits the previous section's (Word rule). */
    private fun resolveSections(d: DDoc) {
        val types = arrayOf("default", "first", "even")
        val curH = arrayOfNulls<String>(3); val curF = arrayOfNulls<String>(3)
        val cache = HashMap<String, List<DBlock>>()
        fun part(rid: String?): List<DBlock> = if (rid == null) emptyList() else cache.getOrPut(rid) { count = 0; readPart(rid) }
        for ((i, s) in sects.withIndex()) {
            val r = sectRefs[i]
            for (k in 0..2) { r.hdr[types[k]]?.let { curH[k] = it }; r.ftr[types[k]]?.let { curF[k] = it } }
            s.headerDefault = part(curH[0]); s.headerFirst = part(curH[1]); s.headerEven = part(curH[2])
            s.footerDefault = part(curF[0]); s.footerFirst = part(curF[1]); s.footerEven = part(curF[2])
            s.evenOdd = d.evenOdd
            d.sections.add(s)
        }
        // API kept for the HTML view: default header / footer of the first section that has one
        d.header = d.sections.firstOrNull { it.headerDefault.isNotEmpty() }?.headerDefault ?: emptyList()
        d.footer = d.sections.firstOrNull { it.footerDefault.isNotEmpty() }?.footerDefault ?: emptyList()
    }

    private fun readNotes(kind: String, tag: String, into: MutableMap<String, List<DBlock>>) {
        val rel = mainRels.values.firstOrNull { it.kind == kind && !it.external } ?: return
        cur = pkg.rels(rel.target)
        val x = pkg.xml(rel.target) ?: return
        if (!root(x)) return
        eachChild(x) { n ->
            if (n == tag) {
                val id = x.attr("id") ?: ""
                val ty = x.attr("type")
                val out = ArrayList<DBlock>()
                eachChild(x) { c -> blockChild(x, c, out) }
                if (ty == null || ty == "normal") into[id] = out
            } else x.skip()
        }
        cur = mainRels
    }

    /** Part 8: word/comments.xml -> id, author, initials, date and the plain text of each comment (own paragraphs, joined by a newline). */
    private fun readComments(into: MutableMap<String, DComment>) {
        val rel = mainRels.values.firstOrNull { it.kind == "comments" && !it.external } ?: return
        cur = pkg.rels(rel.target)
        val x = pkg.xml(rel.target)
        if (x != null && root(x)) {
            eachChild(x) { n ->
                if (n == "comment") {
                    val id = x.attr("id") ?: ""
                    val author = x.attr("author") ?: ""
                    val ini = x.attr("initials") ?: ""
                    val date = x.attr("date") ?: ""
                    val out = ArrayList<DBlock>()
                    eachChild(x) { c -> blockChild(x, c, out) }
                    val sb = StringBuilder()
                    for (b in out) if (b is DPara) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(b.text) }
                    if (id.isNotEmpty()) into[id] = DComment(id, author, ini, date, sb.toString().trim())
                } else x.skip()
            }
        }
        cur = mainRels
    }

    private fun parseStyles(part: String) {
        val x = pkg.xml(part) ?: return
        if (!root(x)) return
        eachChild(x) { n ->
            when (n) {
                "docDefaults" -> eachChild(x) { c ->
                    if (c == "rPrDefault") eachChild(x) { k -> if (k == "rPr") defRpr = parseRPr(x) else x.skip() }
                    else if (c == "pPrDefault") eachChild(x) { k -> if (k == "pPr") defPpr = parsePPr(x) else x.skip() } else x.skip()
                }
                "style" -> {
                    val st = DStyle(x.attr("styleId") ?: "")
                    val isTbl = x.attr("type") == "table"
                    val ts = if (isTbl) TStyle(st.id) else null
                    if (x.attr("default") == "1" && x.attr("type") == "paragraph") defPara = st.id
                    if (x.attr("default") == "1" && isTbl) defTbl = st.id
                    eachChild(x) { c ->
                        when (c) {
                            "name" -> { st.name = x.attr("val") ?: ""; x.skip() }
                            "basedOn" -> { st.basedOn = x.attr("val"); ts?.basedOn = st.basedOn; x.skip() }
                            "pPr" -> { val q = parsePPr(x); if (ts != null) ts.whole.ppr.overlay(q) else st.ppr.overlay(q) }
                            "rPr" -> { val q = parseRPr(x); if (ts != null) ts.whole.rpr.overlay(q) else st.rpr.overlay(q) }
                            "tblPr" -> if (ts != null) eachChild(x) { k ->
                                when (k) {
                                    "tblStyleRowBandSize" -> { ts.rowBand = x.attr("val")?.toIntOrNull() ?: 1; x.skip() }
                                    "tblStyleColBandSize" -> { ts.colBand = x.attr("val")?.toIntOrNull() ?: 1; x.skip() }
                                    "tblBorders" -> ts.tbl = parseBorders(x)
                                    "tblCellMar" -> ts.mar = parseMar(x)
                                    else -> x.skip()
                                }
                            } else x.skip()
                            "tcPr" -> if (ts != null) parseCondTc(x, ts.whole) else x.skip()
                            "tblStylePr" -> if (ts != null) {
                                val cond = TCond(); val type = x.attr("type") ?: ""
                                eachChild(x) { k ->
                                    when (k) {
                                        "pPr" -> cond.ppr.overlay(parsePPr(x))
                                        "rPr" -> cond.rpr.overlay(parseRPr(x))
                                        "tcPr" -> parseCondTc(x, cond)
                                        else -> x.skip()
                                    }
                                }
                                ts.conds.getOrPut(type) { TCond() }.overlay(cond)
                            } else x.skip()
                            else -> x.skip()
                        }
                    }
                    styles[st.id] = st
                    if (ts != null) tstyles[st.id] = ts
                }
                else -> x.skip()
            }
        }
    }

    private fun parseNumbering(part: String) {
        val x = pkg.xml(part) ?: return
        if (!root(x)) return
        eachChild(x) { n ->
            when (n) {
                "abstractNum" -> {
                    val id = x.attr("abstractNumId") ?: ""
                    val m = HashMap<Int, NumLvl>()
                    eachChild(x) { c ->
                        if (c == "lvl") {
                            val il = x.attr("ilvl")?.toIntOrNull() ?: 0
                            var fmt = "decimal"; var txt = ""; var st = 1
                            eachChild(x) { k ->
                                when (k) {
                                    "numFmt" -> fmt = x.attr("val") ?: "decimal"
                                    "lvlText" -> txt = x.attr("val") ?: ""
                                    "start" -> st = x.attr("val")?.toIntOrNull() ?: 1
                                }
                                x.skip()
                            }
                            m[il] = NumLvl(fmt, txt, st)
                        } else x.skip()
                    }
                    absLevels[id] = m
                }
                "num" -> {
                    val id = x.attr("numId") ?: ""
                    eachChild(x) { c ->
                        if (c == "abstractNumId") numToAbs[id] = x.attr("val") ?: ""
                        x.skip()
                    }
                }
                else -> x.skip()
            }
        }
    }

    // ---- style resolution ----

    private fun resolve(id: String?): DStyle? {
        if (id == null) return null
        resolved[id]?.let { return it }
        val s = styles[id] ?: return null
        val out = DStyle(id)
        out.name = s.name
        val chain = ArrayList<DStyle>()
        var c: DStyle? = s
        var guard = 0
        while (c != null && guard++ < 12) { chain.add(c); c = c.basedOn?.let { styles[it] } }
        for (k in chain.indices.reversed()) { out.ppr.overlay(chain[k].ppr); out.rpr.overlay(chain[k].rpr) }
        resolved[id] = out
        return out
    }

    // ---- Part 7: theme, colours, borders, table styles ----

    private fun theme(): Theme = themeC ?: loadTheme().also { themeC = it }
    private fun loadTheme(): Theme = try {
        val part = mainRels.values.firstOrNull { it.kind == "theme" && !it.external }?.target ?: "word/theme/theme1.xml"
        themeFound = pkg.has(part)
        Theme.parse(pkg.xml(part))
    } catch (_: Throwable) { Theme.DEFAULT }

    private fun hexColor(v: String?): String? = v?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }?.uppercase()

    /** Word colour attributes: themeColor (+ tint / shade) wins over the literal value; "auto" -> null. */
    private fun colorOf(x: MiniXml, v: String = "val", th: String = "themeColor", ti: String = "themeTint", sh: String = "themeShade"): String? {
        x.attr(th)?.let { n -> theme().word(n, x.attr(ti), x.attr(sh))?.let { return it } }
        return hexColor(x.attr(v))
    }

    /** `w:shd` (positioned on its START, not consumed) -> fill colour. */
    private fun shdFill(x: MiniXml): String? = when (x.attr("val")) {
        "nil" -> null
        "solid" -> colorOf(x, "color", "themeColor", "themeTint", "themeShade")
        else -> colorOf(x, "fill", "themeFill", "themeFillTint", "themeFillShade")
    }

    private fun parseBorder(x: MiniXml): DBorder = DBorder(x.attr("val") ?: "none", x.attr("sz")?.toIntOrNull() ?: 4, colorOf(x, "color"))

    /** tblBorders / tcBorders / pBdr (positioned on the START; consumes the element). */
    private fun parseBorders(x: MiniXml): DBorders {
        val b = DBorders()
        eachChild(x) { c ->
            val bd = parseBorder(x)
            when (c) { "top" -> b.top = bd; "left", "start" -> b.left = bd; "bottom" -> b.bottom = bd; "right", "end" -> b.right = bd; "insideH" -> b.insideH = bd; "insideV" -> b.insideV = bd }
            x.skip()
        }
        return b
    }

    /** tblCellMar / tcMar (consumes the element) -> top, left, bottom, right in twips, -1 = not set. */
    private fun parseMar(x: MiniXml): IntArray {
        val m = intArrayOf(-1, -1, -1, -1)
        eachChild(x) { c ->
            val w = x.attr("w")?.toIntOrNull() ?: -1
            when (c) { "top" -> m[0] = w; "left", "start" -> m[1] = w; "bottom" -> m[2] = w; "right", "end" -> m[3] = w }
            x.skip()
        }
        return m
    }

    /** `w:tcPr` of a table style / conditional format (consumes the element): shading + cell borders. */
    private fun parseCondTc(x: MiniXml, into: TCond) {
        eachChild(x) { k ->
            when (k) {
                "shd" -> { shdFill(x)?.let { into.fill = it }; x.skip() }
                "tcBorders" -> { val b = parseBorders(x); (into.bdr ?: DBorders().also { into.bdr = it }).overlay(b) }
                else -> x.skip()
            }
        }
    }

    private fun tresolve(id: String?): TStyle? {
        if (id == null) return null
        tresolved[id]?.let { return it }
        val s0 = tstyles[id] ?: return null
        val chain = ArrayList<TStyle>(); var c: TStyle? = s0; var guard = 0
        while (c != null && guard++ < 12) { chain.add(c); c = c.basedOn?.let { tstyles[it] } }
        val out = TStyle(id)
        for (k in chain.indices.reversed()) {
            val q = chain[k]
            if (q.rowBand > 0) out.rowBand = q.rowBand
            if (q.colBand > 0) out.colBand = q.colBand
            q.tbl?.let { b -> (out.tbl ?: DBorders().also { out.tbl = it }).overlay(b) }
            q.mar?.let { m -> val o = out.mar ?: intArrayOf(-1, -1, -1, -1).also { out.mar = it }; for (i in 0..3) if (m[i] >= 0) o[i] = m[i] }
            out.whole.overlay(q.whole)
            for ((key, v) in q.conds) out.conds.getOrPut(key) { TCond() }.overlay(v)
        }
        tresolved[id] = out
        return out
    }

    /** Conditional-format keys that apply to a cell, LOWEST priority first (Word order). [nrows] = Int.MAX_VALUE while the last row is not known yet. */
    private fun condKeys(ts: TStyle, look: TLook, ri: Int, col: Int, span: Int, nrows: Int, ncols: Int): List<String> {
        val l = ArrayList<String>(6)
        val isFirstCol = look.firstCol && col == 0; val isLastCol = look.lastCol && col + span >= ncols && ncols > 0
        val isFirstRow = look.firstRow && ri == 0; val isLastRow = look.lastRow && ri == nrows - 1
        if (!look.noVBand && !isFirstCol && !isLastCol) { val i = col - (if (look.firstCol) 1 else 0); l.add(if ((i / maxOf(1, ts.colBand)) % 2 == 0) "band1Vert" else "band2Vert") }
        if (!look.noHBand && !isFirstRow && !isLastRow) { val i = ri - (if (look.firstRow) 1 else 0); l.add(if ((i / maxOf(1, ts.rowBand)) % 2 == 0) "band1Horz" else "band2Horz") }
        if (isFirstCol) l.add("firstCol"); if (isLastCol) l.add("lastCol"); if (isFirstRow) l.add("firstRow"); if (isLastRow) l.add("lastRow")
        if (isFirstRow && isFirstCol) l.add("nwCell"); if (isFirstRow && isLastCol) l.add("neCell")
        if (isLastRow && isFirstCol) l.add("swCell"); if (isLastRow && isLastCol) l.add("seCell")
        return l
    }

    // ---- blocks ----

    private fun blockChild(x: MiniXml, n: String, out: ArrayList<DBlock>) {
        when (n) {
            "p", "tbl" -> {
                if (count >= max) { truncated = true; x.skip() }
                else if (n == "p") parsePara(x, out)
                else out.add(parseTable(x))
            }
            "sdt" -> eachChild(x) { c ->
                if (c == "sdtContent") eachChild(x) { d -> blockChild(x, d, out) } else x.skip()
            }
            "sectPr" -> {
                val s = parseSectPr(x)
                if (out === bodyList) { addSect(s, secStart); secStart = out.size }   // body-level sectPr = last section
            }
            else -> x.skip()
        }
    }

    private fun tw(v: String?): Double? = v?.trim()?.toDoubleOrNull()?.let { it / 20.0 }

    /** Part 4: one w:sectPr -> geometry + header / footer references (parts are read later, see [resolveSections]). */
    private fun parseSectPr(x: MiniXml): Pair<DSection, SectRefs> {
        val s = DSection(); val r = SectRefs()
        var orient: String? = null
        eachChild(x) { n ->
            when (n) {
                "headerReference", "footerReference" -> {
                    val t = x.attr("type") ?: "default"
                    val id = x.rawAttr("r:id") ?: x.attr("id")
                    if (id != null) (if (n == "headerReference") r.hdr else r.ftr)[t] = id
                    x.skip()
                }
                "pgSz" -> {
                    tw(x.attr("w"))?.let { s.pgW = it }; tw(x.attr("h"))?.let { s.pgH = it }
                    orient = x.attr("orient"); x.skip()
                }
                "pgMar" -> {
                    tw(x.attr("top"))?.let { s.marT = it }; tw(x.attr("bottom"))?.let { s.marB = it }
                    (tw(x.attr("left")) ?: tw(x.attr("start")))?.let { s.marL = it }
                    (tw(x.attr("right")) ?: tw(x.attr("end")))?.let { s.marR = it }
                    tw(x.attr("header"))?.let { s.hdrDist = it }; tw(x.attr("footer"))?.let { s.ftrDist = it }
                    tw(x.attr("gutter"))?.let { s.gutter = it }
                    x.skip()
                }
                "cols" -> {
                    x.attr("num")?.toIntOrNull()?.let { s.cols = it.coerceIn(1, 45) }
                    tw(x.attr("space"))?.let { s.colGap = it }
                    s.colSep = x.attr("sep").let { it == "1" || it == "true" || it == "on" }
                    val equal = x.attr("equalWidth").let { !(it == "0" || it == "false" || it == "off") }
                    val ws = ArrayList<Double>()
                    eachChild(x) { c -> if (c == "col") tw(x.attr("w"))?.let { ws.add(it) }; x.skip() }
                    if (!equal && ws.isNotEmpty()) { s.colWidths = ws; s.cols = ws.size }
                }
                "titlePg" -> { s.titlePg = on(x); x.skip() }
                "type" -> { s.breakType = x.attr("val") ?: "nextPage"; x.skip() }
                "pgNumType" -> { s.pgNumStart = x.attr("start")?.toIntOrNull() ?: -1; s.pgNumFmt = x.attr("fmt"); x.skip() }
                else -> x.skip()
            }
        }
        s.orient = orient ?: if (s.pgW > s.pgH) "landscape" else "portrait"
        return s to r
    }

    private fun parseTable(x: MiniXml): DTable {
        val t = DTable()
        var sid: String? = null; var direct: DBorders? = null; var dmar: IntArray? = null
        val look = TLook()
        var tc: TCtx? = null
        fun ctx(): TCtx = tc ?: TCtx(tresolve(sid ?: defTbl), look, t.grid.size).also { tc = it }
        eachChild(x) { n ->
            when (n) {
                "tblPr" -> eachChild(x) { c ->
                    when (c) {
                        "tblStyle" -> { sid = x.attr("val"); t.style = sid ?: ""; x.skip() }
                        "tblBorders" -> { direct = parseBorders(x); t.borders = true }
                        "tblCellMar" -> dmar = parseMar(x)
                        "jc" -> { t.jc = x.attr("val"); x.skip() }
                        "tblInd" -> { t.indent = x.attr("w")?.toIntOrNull() ?: 0; x.skip() }
                        "tblLook" -> {
                            x.attr("val")?.toIntOrNull(16)?.let { v -> look.firstRow = v and 0x20 != 0; look.lastRow = v and 0x40 != 0; look.firstCol = v and 0x80 != 0; look.lastCol = v and 0x100 != 0; look.noHBand = v and 0x200 != 0; look.noVBand = v and 0x400 != 0 }
                            fun fl(a: String): Boolean? = x.attr(a)?.let { it == "1" || it == "true" || it == "on" }
                            fl("firstRow")?.let { look.firstRow = it }; fl("lastRow")?.let { look.lastRow = it }; fl("firstColumn")?.let { look.firstCol = it }
                            fl("lastColumn")?.let { look.lastCol = it }; fl("noHBand")?.let { look.noHBand = it }; fl("noVBand")?.let { look.noVBand = it }
                            x.skip()
                        }
                        else -> x.skip()
                    }
                }
                "tblGrid" -> eachChild(x) { c ->
                    if (c == "gridCol") t.grid.add(x.attr("w")?.toIntOrNull() ?: 0)
                    x.skip()
                }
                "tr" -> t.rows.add(parseRow(x, RowCtx(ctx(), t.rows.size)))
                else -> x.skip()
            }
        }
        val c = ctx(); val ts = c.ts
        if (t.style.contains("grid", true)) t.borders = true
        t.resolved = ts != null || direct != null
        // cell margins: table style, then direct
        if (ts?.mar != null || dmar != null) { val m = intArrayOf(-1, -1, -1, -1); for (src in listOf(ts?.mar, dmar)) if (src != null) for (k in 0..3) if (src[k] >= 0) m[k] = src[k]; t.mar = m }
        // final fill + borders of every cell (style whole table < conditional formats < table borders < direct cell formatting)
        val nrows = t.rows.size
        val ncols = if (t.grid.isNotEmpty()) t.grid.size else (t.rows.maxOfOrNull { r -> r.cells.sumOf { maxOf(1, it.span) } } ?: 0)
        fun tblSide(b: DBorders?, side: Int, edge: Boolean): DBorder? = if (b == null) null else if (edge) when (side) { 0 -> b.top; 1 -> b.left; 2 -> b.bottom; else -> b.right } else if (side % 2 == 0) b.insideH else b.insideV
        fun cellSide(b: DBorders?, side: Int, edge: Boolean): DBorder? = if (b == null) null else (when (side) { 0 -> b.top; 1 -> b.left; 2 -> b.bottom; else -> b.right } ?: if (edge) null else if (side % 2 == 0) b.insideH else b.insideV)
        for ((ri, row) in t.rows.withIndex()) {
            var col = 0
            for (cell in row.cells) {
                val span = maxOf(1, cell.span)
                val merged = ts?.merged(condKeys(ts, look, ri, col, span, nrows, ncols))
                if (cell.fill == null) cell.fill = merged?.fill
                val dc = c.direct[cell]
                val edges = booleanArrayOf(ri == 0, col == 0, ri == nrows - 1, col + span >= ncols)
                val out = arrayOfNulls<DBorder>(4)
                for (side in 0..3) {
                    var b: DBorder? = null
                    tblSide(ts?.tbl, side, edges[side])?.let { b = it }
                    cellSide(merged?.bdr, side, edges[side])?.let { b = it }
                    tblSide(direct, side, edges[side])?.let { b = it }
                    cellSide(dc, side, edges[side])?.let { b = it }
                    out[side] = b?.takeIf { !it.none }
                }
                cell.bT = out[0]; cell.bL = out[1]; cell.bB = out[2]; cell.bR = out[3]
                if (out.any { it != null }) t.anyBorder = true
                col += span
            }
        }
        // last row: run formatting of the style's lastRow format could not be applied while streaming (the row count was unknown): bold / italic / colour only
        if (ts != null && look.lastRow && nrows > 1) ts.conds["lastRow"]?.let { lc ->
            for (cell in t.rows[nrows - 1].cells) for (b in cell.blocks) if (b is DPara) for (r in b.runs) {
                if (lc.rpr.b == true) r.bold = true
                if (lc.rpr.i == true) r.italic = true
                if (r.color == null) lc.rpr.color?.let { r.color = it }
            }
        }
        return t
    }

    private fun parseRow(x: MiniXml, rc: RowCtx): DRow {
        val r = DRow()
        eachChild(x) { n ->
            when (n) {
                "trPr" -> eachChild(x) { c -> if (c == "tblHeader") r.header = true else if (c == "trHeight") r.height = x.attr("val")?.toIntOrNull() ?: 0; x.skip() }
                "tc" -> r.cells.add(parseCell(x, rc))
                "sdt" -> eachChild(x) { c ->
                    if (c == "sdtContent") eachChild(x) { d -> if (d == "tc") r.cells.add(parseCell(x, rc)) else x.skip() } else x.skip()
                }
                else -> x.skip()
            }
        }
        return r
    }

    private fun parseCell(x: MiniXml, rc: RowCtx): DCell {
        val c = DCell()
        val saved = tctx
        var ready = false
        fun ready() {
            if (ready) return
            ready = true
            val ts = rc.tc.ts
            tctx = if (ts == null) null else ts.merged(condKeys(ts, rc.tc.look, rc.ri, rc.col, maxOf(1, c.span), Int.MAX_VALUE, rc.tc.ncols))
        }
        eachChild(x) { n ->
            if (n == "tcPr") eachChild(x) { k ->
                when (k) {
                    "tcBorders" -> rc.tc.direct[c] = parseBorders(x)
                    "tcMar" -> c.mar = parseMar(x)
                    else -> {
                        when (k) {
                            "gridSpan" -> c.span = x.attr("val")?.toIntOrNull() ?: 1
                            "vMerge" -> c.vMerge = if (x.attr("val") == "restart") 1 else 2
                            "tcW" -> c.width = x.attr("w")?.toIntOrNull() ?: 0
                            "shd" -> c.fill = shdFill(x)
                            "vAlign" -> c.vAlign = x.attr("val")
                        }
                        x.skip()
                    }
                }
            } else { ready(); blockChild(x, n, c.blocks) }
        }
        ready()
        tctx = saved
        rc.col += maxOf(1, c.span)
        return c
    }

    // ---- paragraphs ----

    private fun parsePara(x: MiniXml, out: ArrayList<DBlock>) {
        val p = DPara()
        val extra = ArrayList<DBlock>()
        var mySect: Pair<DSection, SectRefs>? = null
        val savedFields = ArrayList(fstack); fstack.clear()   // a text box inside a field must not see the outer field state
        var st0 = resolve(defPara)
        val tc = tctx   // Part 7: table style formatting of the enclosing cell (between document defaults and the paragraph style)
        var base = RPr().also { it.overlay(defRpr); tc?.let { c -> it.overlay(c.rpr) }; st0?.let { s -> it.overlay(s.rpr) } }
        var eff = PPr().also { e -> e.overlay(defPpr); tc?.let { c -> e.overlay(c.ppr) }; st0?.let { s -> e.overlay(s.ppr) } }
        var sname = st0?.name ?: ""
        eachChild(x) { n ->
            if (n == "pPr") {
                val q = parsePPr(x)
                mySect = pendingSect; pendingSect = null
                val st = resolve(q.style ?: defPara)
                base = RPr().also { it.overlay(defRpr); tc?.let { c -> it.overlay(c.rpr) }; st?.let { s -> it.overlay(s.rpr) } }
                eff = PPr().also { e -> e.overlay(defPpr); tc?.let { c -> e.overlay(c.ppr) }; st?.let { s -> e.overlay(s.ppr) }; e.overlay(q) }
                sname = st?.name ?: ""
            } else inl(x, n, p, null, base, extra)
        }
        p.style = sname
        p.align = when (eff.jc) { "start", "left" -> "left"; "end", "right" -> "right"; "both", "distribute" -> "justify"; "center" -> "center"; else -> null }
        p.indentLeft = if (eff.left == Int.MIN_VALUE) 0 else eff.left
        p.firstLine = if (eff.first == Int.MIN_VALUE) 0 else eff.first
        p.pageBreakBefore = eff.pbb == true
        eff.tabs?.let { t -> p.tabs = t.filter { it.kind != "clear" }.sortedBy { it.pos } }
        p.fill = eff.fill; p.bdr = eff.bdr?.takeIf { it.any }
        if (eff.before != Int.MIN_VALUE) p.spBefore = eff.before / 20.0
        if (eff.after != Int.MIN_VALUE) p.spAfter = eff.after / 20.0
        if (eff.line != Int.MIN_VALUE && eff.line > 0) {
            if (eff.rule == "exact" || eff.rule == "atLeast") { p.lineExact = eff.line / 20.0; p.lineAtLeast = eff.rule == "atLeast" } else p.lineMul = eff.line / 240.0
        }
        val hm = Regex("heading\\s*(\\d)", RegexOption.IGNORE_CASE).find(sname)
        p.heading = hm?.groupValues?.get(1)?.toIntOrNull() ?: if (eff.outline in 0..8) eff.outline + 1 else 0
        val nid = eff.numId
        if (nid != null && nid != "0") numLabel(nid, eff.ilvl.coerceIn(0, 8), p)
        val rs = p.runs
        var i = 0
        while (i + 1 < rs.size) {
            val a = rs[i]; val b = rs[i + 1]
            if (a.kind == DRun.TEXT && b.kind == DRun.TEXT && a.sameStyle(b)) { a.text = a.text + b.text; rs.removeAt(i + 1) } else i++
        }
        count++
        out.add(p)
        out.addAll(extra)
        fstack.clear(); fstack.addAll(savedFields)
        mySect?.let { if (out === bodyList) { addSect(it, secStart); secStart = out.size } }   // paragraph-level sectPr ends a section
    }

    /** Paragraph-level children (also used inside wrappers such as hyperlink / sdt / ins). */
    private fun inl(x: MiniXml, n: String, p: DPara, link: String?, base: RPr, extra: ArrayList<DBlock>) {
        when (n) {
            "r" -> parseRun(x, p, link, base, extra)
            "hyperlink" -> {
                val rid = x.rawAttr("r:id") ?: x.attr("id")
                val anc = x.attr("anchor")
                val l = (if (rid != null) cur[rid]?.takeIf { it.external }?.target else null) ?: anc?.let { "#$it" } ?: link
                eachChild(x) { c -> inl(x, c, p, l, base, extra) }
            }
            "fldSimple" -> {
                val type = fieldType(x.attr("instr"))
                val from = p.runs.size
                eachChild(x) { c -> inl(x, c, p, link, base, extra) }
                if (type != null) collapseField(p, from, type, base, link)
            }
            "ins", "moveTo" -> revWrap(x, 1, p, link, base, extra)
            "del", "moveFrom" -> revWrap(x, 2, p, link, base, extra)
            "smartTag", "customXml", "sdt", "sdtContent", "AlternateContent", "Choice" ->
                eachChild(x) { c -> inl(x, c, p, link, base, extra) }
            "commentRangeStart" -> { x.attr("id")?.let { openCmts.add(it) }; x.skip() }
            "commentRangeEnd" -> { x.attr("id")?.let { openCmts.remove(it) }; x.skip() }
            "bookmarkStart" -> { x.attr("name")?.takeIf { it.isNotEmpty() }?.let { p.bookmarks = p.bookmarks + it }; x.skip() }
            else -> x.skip()
        }
    }

    /** Part 8: children of a w:ins (kind 1) / w:del (kind 2) become runs marked with that revision and its author. */
    private fun revWrap(x: MiniXml, kind: Int, p: DPara, link: String?, base: RPr, extra: ArrayList<DBlock>) {
        val sk = revKind; val sa = revAuthor
        revKind = kind; revAuthor = x.attr("author"); hasRev = true
        eachChild(x) { c -> inl(x, c, p, link, base, extra) }
        revKind = sk; revAuthor = sa
    }

    /** Part 8: a complex field `HYPERLINK "url"` / `HYPERLINK \l "bookmark"` (the old way of writing links and TOC entries): the result runs become a link. */
    private fun hyperField(p: DPara, fl: Fld) {
        val ins = fl.instr.toString().trim()
        if (!ins.startsWith("HYPERLINK", true) || fl.resultFrom < 0) return
        val q = Regex("\"([^\"]*)\"").findAll(ins).map { it.groupValues[1] }.toList()
        if (q.isEmpty()) return
        val local = Regex("\\\\l\\s").containsMatchIn(ins)
        val target = if (local) (if (q.size >= 2) q[0] + "#" + q[1] else "#" + q[0]) else q[0]
        for (i in fl.resultFrom until p.runs.size) { val r = p.runs[i]; if (r.link == null) r.link = target }
    }

    private fun parseRun(x: MiniXml, p: DPara, link: String?, base: RPr, extra: ArrayList<DBlock>) {
        var f = base
        fun mk(kind: Int, text: String): DRun {
            val r = DRun(kind, text)
            r.bold = f.b == true; r.italic = f.i == true; r.underline = f.u == true; r.strike = f.strike == true; r.caps = f.caps == true
            r.color = f.color; r.highlight = f.hl; r.size = f.sz; r.script = f.script; r.link = link; r.font = f.font
            r.rev = revKind; r.revBy = revAuthor; if (openCmts.isNotEmpty()) r.cmt = openCmts.joinToString(",")   // Part 8
            p.runs.add(r)
            return r
        }
        fun child(n: String) {
            when (n) {
                "rPr" -> {
                    val q = parseRPr(x)
                    f = RPr().also { it.overlay(base); resolve(q.rStyle)?.let { s -> it.overlay(s.rpr) }; it.overlay(q) }
                }
                "t", "delText" -> { val t = x.readText(); if (t.isNotEmpty()) mk(DRun.TEXT, t) }   // Part 8: delText = text of a tracked deletion
                "tab" -> { mk(DRun.TAB, "\t"); x.skip() }
                "br" -> { mk(if (x.attr("type") == "page") DRun.PAGE else DRun.BREAK, ""); x.skip() }
                "cr" -> { mk(DRun.BREAK, ""); x.skip() }
                "fldChar" -> {
                    when (x.attr("fldCharType")) {
                        "begin" -> fstack.add(Fld())
                        "separate" -> fstack.lastOrNull()?.let { if (it.resultFrom < 0) it.resultFrom = p.runs.size }
                        "end" -> fstack.removeLastOrNull()?.let { fl ->
                            val type = fieldType(fl.instr.toString())
                            if (type != null) collapseField(p, if (fl.resultFrom >= 0) fl.resultFrom else p.runs.size, type, f, link) else hyperField(p, fl)
                        }
                    }
                    x.skip()
                }
                "instrText" -> { val t = x.readText(); fstack.lastOrNull()?.takeIf { it.resultFrom < 0 }?.instr?.append(t) }
                "commentReference" -> { mk(DRun.CMT, x.attr("id") ?: "").cmt = null; x.skip() }   // Part 8: marker run, text = comment id
                "noBreakHyphen" -> { mk(DRun.TEXT, "-"); x.skip() }
                "sym" -> {
                    val c = x.attr("char")?.toIntOrNull(16)
                    if (c != null) mk(DRun.TEXT, if (c in 0xF000..0xF0FF) "•" else String(Character.toChars(c)))
                    x.skip()
                }
                "footnoteReference", "endnoteReference" -> {
                    mk(DRun.NOTE, x.attr("id") ?: "").endnote = (n == "endnoteReference")
                    x.skip()
                }
                "drawing", "pict", "object" -> parseDrawing(x, { img -> mk(DRun.IMAGE, "").image = img }, { sh -> mk(DRun.SHAPE, "").shape = sh })
                "AlternateContent" -> eachChild(x) { c -> if (c == "Choice") eachChild(x) { d -> child(d) } else x.skip() }
                else -> x.skip()
            }
        }
        eachChild(x) { child(it) }
    }

    /** Part 4: field type for the instruction text, null = not one of the fields the page model needs (cached result stays plain text). */
    private fun fieldType(instr: String?): String? {
        val w = instr?.trim()?.split(Regex("\\s+"), 2)?.firstOrNull()?.uppercase() ?: return null
        return if (w == "PAGE" || w == "NUMPAGES" || w == "DATE" || w == "TITLE") w else null
    }

    /** Replaces the cached-result runs from index [from] on by ONE FIELD run (text = cached result, style of the first result run). */
    private fun collapseField(p: DPara, from: Int, type: String, f: RPr, link: String?) {
        val at = from.coerceIn(0, p.runs.size)
        val old = p.runs.subList(at, p.runs.size)
        val first = old.firstOrNull { it.kind == DRun.TEXT || it.kind == DRun.FIELD }
        val txt = old.filter { it.kind == DRun.TEXT || it.kind == DRun.FIELD }.joinToString("") { it.text }
        val r = DRun(DRun.FIELD, txt)
        if (first != null) {
            r.bold = first.bold; r.italic = first.italic; r.underline = first.underline; r.strike = first.strike; r.caps = first.caps
            r.color = first.color; r.highlight = first.highlight; r.size = first.size; r.script = first.script; r.link = first.link; r.font = first.font
            r.rev = first.rev; r.revBy = first.revBy; r.cmt = first.cmt   // Part 8
        } else {
            r.bold = f.b == true; r.italic = f.i == true; r.underline = f.u == true; r.strike = f.strike == true; r.caps = f.caps == true
            r.color = f.color; r.highlight = f.hl; r.size = f.sz; r.script = f.script; r.link = link; r.font = f.font
            r.rev = revKind; r.revBy = revAuthor   // Part 8
        }
        r.field = type
        old.clear()
        p.runs.add(r)
    }

    /** Part 6 / 7: DrawingML scheme colour name -> RRGGBB from the document's own theme (Office default theme when the package has none). */
    private fun schemeColor(v: String?): String? = theme().color(v)

    /** Colour of a DrawingML fill element (`a:solidFill`, positioned on its START): srgbClr / schemeClr / sysClr with lumMod / lumOff / tint / shade. Consumes the element. */
    private fun fillColor(x: MiniXml): String? {
        var c: String? = null
        eachChild(x) { k ->
            when (k) {
                "srgbClr" -> c = clrMods(x, x.attr("val")?.takeIf { it.length == 6 }, null)
                "schemeClr" -> c = clrMods(x, null, x.attr("val"))
                "sysClr" -> c = clrMods(x, x.attr("lastClr") ?: x.attr("val")?.takeIf { it.length == 6 }, null)
                else -> x.skip()
            }
        }
        return c
    }

    /** Colour modifiers (children of srgbClr / schemeClr); consumes the element. */
    private fun clrMods(x: MiniXml, rgb: String?, scheme: String?): String? {
        var lm: Int? = null; var lo: Int? = null; var ti: Int? = null; var sh: Int? = null
        eachChild(x) { m ->
            val v = x.attr("val")?.toIntOrNull()
            when (m) { "lumMod" -> lm = v; "lumOff" -> lo = v; "tint" -> ti = v; "shade" -> sh = v }
            x.skip()
        }
        return theme().draw(scheme, rgb?.uppercase(), lm, lo, ti, sh)
    }

    /** `a:xfrm` (positioned on its START) -> [offX, offY, extCx, extCy, chOffX, chOffY, chExtCx, chExtCy] (EMU). Consumes the element. */
    private fun xfrmOf(x: MiniXml): LongArray {
        val v = LongArray(8)
        fun l(n: String) = x.attr(n)?.toLongOrNull() ?: 0L
        eachChild(x) { k ->
            when (k) {
                "off" -> { v[0] = l("x"); v[1] = l("y") }
                "ext" -> { v[2] = l("cx"); v[3] = l("cy") }
                "chOff" -> { v[4] = l("x"); v[5] = l("y") }
                "chExt" -> { v[6] = l("cx"); v[7] = l("cy") }
            }
            x.skip()
        }
        return v
    }

    /** `wp:anchor` attributes (on its START); the position / wrap children are read by [parseDrawing]. */
    private fun anchorOf(x: MiniXml): DAnchor {
        val a = DAnchor()
        fun l(n: String, d: Long) = x.attr(n)?.toLongOrNull() ?: d
        a.behind = x.attr("behindDoc") == "1" || x.attr("behindDoc") == "true"
        a.distT = l("distT", 0); a.distB = l("distB", 0); a.distL = l("distL", 114300); a.distR = l("distR", 114300)
        a.z = (l("relativeHeight", 0) and 0x7fffffffL).toInt()
        return a
    }

    private fun relName(v: String?, horizontal: Boolean): String = when (v) {
        "page" -> "page"
        "margin", "leftMargin", "rightMargin", "insideMargin", "outsideMargin", "topMargin", "bottomMargin" -> "margin"
        "character" -> "character"
        "line" -> "line"
        "paragraph" -> "paragraph"
        else -> if (horizontal) "column" else "paragraph"
    }

    /**
     * Part 6: scan of a drawing / pict subtree. Pictures (`wp:inline` / `wp:anchor` + `a:blip`, VML `imagedata`) go to [emitImg] with their anchor;
     * drawing shapes (`wps:wsp`) and VML shapes go to [emitShape]; text-box content is parsed into the shape (it is no longer flattened into
     * extra body paragraphs). Group shapes (`wpg:wgp`) are flattened: each child shape keeps its offset inside the group.
     */
    private fun parseDrawing(x: MiniXml, emitImg: (DImage) -> Unit, emitShape: (DShape) -> Unit) {
        val d = x.depth
        var cx = 0L; var cy = 0L; var alt = ""
        var anc: DAnchor? = null
        var grp: LongArray? = null
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF || (t == MiniXml.T.END && x.depth < d)) return
            if (t != MiniXml.T.START) continue
            when (x.name) {
                "inline" -> anc = null
                "anchor" -> anc = anchorOf(x)
                "extent" -> { if (cx == 0L) { cx = x.attr("cx")?.toLongOrNull() ?: 0L; cy = x.attr("cy")?.toLongOrNull() ?: 0L } }
                "docPr" -> alt = x.attr("descr")?.takeIf { it.isNotEmpty() } ?: x.attr("name") ?: ""
                "positionH", "positionV" -> {
                    val h = x.name == "positionH"
                    val a = anc
                    val rel = relName(x.attr("relativeFrom"), h)
                    if (a == null) x.skip() else {
                        if (h) a.relH = rel else a.relV = rel
                        eachChild(x) { c ->
                            when (c) {
                                "posOffset" -> { val v = x.readText().trim().toLongOrNull() ?: 0L; if (h) a.hOff = v else a.vOff = v }
                                "align" -> { val v = x.readText().trim(); if (h) a.hAlign = v else a.vAlign = v }
                                else -> x.skip()
                            }
                        }
                    }
                }
                "wrapSquare", "wrapThrough", "wrapTight" -> anc?.let { it.wrap = if (x.name == "wrapSquare") "square" else "tight"; it.wrapText = x.attr("wrapText") ?: "both" }
                "wrapTopAndBottom" -> anc?.wrap = "tb"
                "wrapNone" -> anc?.wrap = "none"
                "grpSpPr" -> if (grp == null) eachChild(x) { c -> if (c == "xfrm") { grp = xfrmOf(x) } else { x.skip() } } else x.skip()
                "blip" -> {
                    val rid = x.rawAttr("r:embed") ?: x.rawAttr("r:link")
                    val rel = if (rid != null) cur[rid] else null
                    if (rel != null && !rel.external) emitImg(DImage(rel.target, cx, cy, alt).also { it.anchor = anc })
                }
                "wsp" -> emitShape(parseWsp(x, anc, cx, cy, alt, grp))
                "shape", "rect", "roundrect", "oval", "line" -> parseVml(x, alt, emitImg, emitShape)
                "imagedata" -> {   // VML picture outside a v:shape (old files)
                    val rel = x.rawAttr("r:id")?.let { cur[it] }
                    if (rel != null && !rel.external) emitImg(DImage(rel.target, cx, cy, x.attr("title") ?: alt))
                }
                "txbxContent" -> x.skip()   // only reached for text boxes this scan does not understand; content is read by parseWsp / parseVml
            }
        }
    }

    /** `wps:wsp` (positioned on its START): geometry, fill, outline and text-box content. Consumes the element. */
    private fun parseWsp(x: MiniXml, anc: DAnchor?, cx: Long, cy: Long, alt: String, grp: LongArray?): DShape {
        val s = DShape(); s.anchor = anc; s.wEmu = cx; s.hEmu = cy; s.alt = alt
        var xf: LongArray? = null
        var fillNone = false; var lineNone = false
        var styleFill: String? = null; var styleLine: String? = null
        eachChild(x) { c ->
            when (c) {
                "spPr" -> eachChild(x) { q ->
                    when (q) {
                        "xfrm" -> { s.rot = (x.attr("rot")?.toDoubleOrNull() ?: 0.0) / 60000.0; s.flipH = x.attr("flipH") == "1"; s.flipV = x.attr("flipV") == "1"; xf = xfrmOf(x) }
                        "prstGeom" -> { s.geom = x.attr("prst") ?: "rect"; x.skip() }
                        "solidFill" -> s.fill = fillColor(x)
                        "noFill" -> { fillNone = true; x.skip() }
                        "ln" -> {
                            s.lineW = x.attr("w")?.toLongOrNull() ?: 9525L
                            eachChild(x) { l ->
                                when (l) {
                                    "solidFill" -> s.line = fillColor(x)
                                    "noFill" -> { lineNone = true; x.skip() }
                                    "prstDash" -> { s.dash = x.attr("val"); x.skip() }
                                    else -> x.skip()
                                }
                            }
                        }
                        else -> x.skip()
                    }
                }
                "style" -> eachChild(x) { q ->
                    val idx = x.attr("idx")?.toIntOrNull() ?: 0
                    var col: String? = null
                    if (q == "fillRef" || q == "lnRef") eachChild(x) { k -> if (k == "schemeClr") col = schemeColor(x.attr("val")); x.skip() } else x.skip()
                    if (idx > 0 && col != null) { if (q == "fillRef") styleFill = col else if (q == "lnRef") styleLine = col }
                }
                "txbx" -> eachChild(x) { q ->
                    if (q == "txbxContent") { val l = ArrayList<DBlock>(); eachChild(x) { b -> blockChild(x, b, l) }; s.blocks = l } else x.skip()
                }
                "bodyPr" -> {
                    fun ins(n: String, d: Long) = x.attr(n)?.toLongOrNull() ?: d
                    s.insL = ins("lIns", 91440); s.insT = ins("tIns", 45720); s.insR = ins("rIns", 91440); s.insB = ins("bIns", 45720)
                    x.skip()
                }
                else -> x.skip()
            }
        }
        if (fillNone) s.fill = null else if (s.fill == null) s.fill = styleFill
        if (lineNone) s.line = null else if (s.line == null) s.line = styleLine
        val f = xf
        if (s.wEmu == 0L && f != null) { s.wEmu = f[2]; s.hEmu = f[3] }
        val g = grp
        if (g != null && f != null && g[6] > 0 && g[7] > 0) {   // child of a group: position and size scaled from the group's child space
            val sx = g[2].toDouble() / g[6]; val sy = g[3].toDouble() / g[7]
            s.dx = ((f[0] - g[4]) * sx).toLong(); s.dy = ((f[1] - g[5]) * sy).toLong()
            s.wEmu = (f[2] * sx).toLong(); s.hEmu = (f[3] * sy).toLong()
        }
        return s
    }

    /** VML shape element (`v:shape / rect / roundrect / oval / line`, positioned on its START) -> a picture or a shape. Consumes the element. */
    private fun parseVml(x: MiniXml, alt0: String, emitImg: (DImage) -> Unit, emitShape: (DShape) -> Unit) {
        val tag = x.name
        val st = HashMap<String, String>()
        for (kv in (x.attr("style") ?: "").split(';')) { val i = kv.indexOf(':'); if (i > 0) st[kv.substring(0, i).trim().lowercase()] = kv.substring(i + 1).trim() }
        val fillc = vmlColor(x.attr("fillcolor")); val strokec = vmlColor(x.attr("strokecolor"))
        val filled = x.attr("filled") != "f" && x.attr("filled") != "false"
        val stroked = x.attr("stroked") != "f" && x.attr("stroked") != "false"
        val strokeW = x.attr("strokeweight")?.let { vmlLen("x:$it", "x") } ?: 9525L
        val title = x.attr("title"); val alt = x.attr("alt") ?: alt0
        var img: DImage? = null; var wmText: String? = null; var wrapT: String? = null
        val blocks = ArrayList<DBlock>()
        eachChild(x) { c ->
            when (c) {
                "imagedata" -> { val rel = x.rawAttr("r:id")?.let { cur[it] }; if (rel != null && !rel.external) img = DImage(rel.target, 0, 0, title ?: alt); x.skip() }
                "textbox" -> eachChild(x) { q -> if (q == "txbxContent") eachChild(x) { b -> blockChild(x, b, blocks) } else x.skip() }
                "textpath" -> { wmText = x.attr("string"); x.skip() }   // WordArt watermark text
                "wrap" -> { wrapT = x.attr("type"); x.skip() }
                else -> x.skip()
            }
        }
        val w = vmlLen(st["width"].orEmpty().let { "x:$it" }, "x"); val h = vmlLen(st["height"].orEmpty().let { "x:$it" }, "x")
        var a: DAnchor? = null
        if (st["position"] == "absolute") {
            a = DAnchor()
            a.wrap = when (wrapT) { "square" -> "square"; "tight", "through" -> "tight"; "topAndBottom" -> "tb"; else -> "none" }
            a.behind = (st["z-index"]?.toLongOrNull() ?: 0L) < 0
            a.z = (st["z-index"]?.toLongOrNull() ?: 0L).coerceIn(-2000000000L, 2000000000L).toInt()
            a.hOff = vmlLen("x:" + (st["margin-left"] ?: st["left"] ?: "0"), "x"); a.vOff = vmlLen("x:" + (st["margin-top"] ?: st["top"] ?: "0"), "x")
            a.hAlign = st["mso-position-horizontal"]?.takeIf { it in setOf("left", "center", "right", "inside", "outside") }
            a.vAlign = st["mso-position-vertical"]?.takeIf { it in setOf("top", "center", "bottom", "inside", "outside") }
            a.relH = when (st["mso-position-horizontal-relative"]) { "page" -> "page"; "margin", "left-margin-area", "right-margin-area", "inner-margin-area", "outer-margin-area" -> "margin"; "char" -> "character"; else -> "column" }
            a.relV = when (st["mso-position-vertical-relative"]) { "page" -> "page"; "margin", "top-margin-area", "bottom-margin-area", "inner-margin-area", "outer-margin-area" -> "margin"; "line" -> "line"; else -> "paragraph" }
            if (st["mso-wrap-distance-left"] != null) a.distL = vmlLen("x:" + st["mso-wrap-distance-left"], "x")
            if (st["mso-wrap-distance-right"] != null) a.distR = vmlLen("x:" + st["mso-wrap-distance-right"], "x")
        }
        val im = img
        if (im != null && blocks.isEmpty()) { emitImg(DImage(im.part, w, h, im.alt).also { it.anchor = a }); return }
        val s = DShape(); s.anchor = a; s.wEmu = w; s.hEmu = h; s.alt = alt
        s.geom = when (tag) { "roundrect" -> "roundRect"; "oval" -> "ellipse"; "line" -> "line"; else -> "rect" }
        s.fill = if (filled && (tag != "shape" || fillc != null || blocks.isNotEmpty())) (fillc ?: if (blocks.isNotEmpty() || tag == "rect") "FFFFFF" else null) else null
        s.line = if (stroked && (tag != "shape" || strokec != null || blocks.isNotEmpty())) (strokec ?: "000000") else null
        s.lineW = strokeW
        s.rot = st["rotation"]?.toDoubleOrNull() ?: 0.0
        val wm = wmText
        if (wm != null && wm.isNotEmpty()) {   // watermark text: one centred paragraph sized to fit the shape
            val pt = minOf(h / 12700.0 * 0.8, w / 12700.0 / (wm.length * 0.6)).coerceIn(8.0, 400.0)
            val p = DPara(); p.align = "center"; p.spBefore = 0.0; p.spAfter = 0.0
            val r = DRun(DRun.TEXT, wm); r.size = (pt * 2).toInt(); r.color = fillc ?: "C0C0C0"; r.bold = true; p.runs.add(r)
            s.blocks = listOf(p); s.fill = null; s.line = null
        } else s.blocks = blocks
        emitShape(s)
    }

    private fun vmlColor(v: String?): String? {
        if (v == null) return null
        val m = Regex("#?([0-9a-fA-F]{6})").find(v)
        if (m != null) return m.groupValues[1].uppercase()
        return when (v.trim().lowercase().substringBefore(' ')) { "silver" -> "C0C0C0"; "black" -> "000000"; "white" -> "FFFFFF"; "red" -> "FF0000"; "blue" -> "0000FF"; "green" -> "008000"; "yellow" -> "FFFF00"; "gray", "grey" -> "808080"; else -> null }
    }

    private fun vmlLen(style: String?, key: String): Long {
        val m = Regex("(?:^|;)\\s*$key\\s*:\\s*(-?[0-9.]+)(pt|in|cm|mm|px)?").find(style ?: "") ?: return 0L
        val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mul = when (m.groupValues[2]) { "in" -> 914400.0; "cm" -> 360000.0; "mm" -> 36000.0; "px" -> 9525.0; else -> 12700.0 }
        return (v * mul).toLong()
    }

    // ---- properties ----

    private fun on(x: MiniXml): Boolean { val v = x.attr("val"); return !(v == "0" || v == "false" || v == "off") }

    private fun parsePPr(x: MiniXml): PPr {
        val q = PPr()
        eachChild(x) { n ->
            when (n) {
                "numPr" -> eachChild(x) { c ->
                    when (c) {
                        "ilvl" -> q.ilvl = x.attr("val")?.toIntOrNull() ?: -1
                        "numId" -> q.numId = x.attr("val")
                    }
                    x.skip()
                }
                "sectPr" -> pendingSect = parseSectPr(x)
                "tabs" -> { val l = ArrayList<DTab>(); eachChild(x) { c ->
                    if (c == "tab") x.attr("pos")?.toIntOrNull()?.let { pos ->
                        val k = when (val v = x.attr("val") ?: "left") { "start", "num" -> "left"; "end" -> "right"; else -> v }
                        l.add(DTab(pos, k, x.attr("leader") ?: "none")) }
                    x.skip() }
                    q.tabs = l }
                "pBdr" -> q.bdr = parseBorders(x)
                else -> {
                    when (n) {
                        "shd" -> q.fill = shdFill(x)
                        "pStyle" -> q.style = x.attr("val")
                        "jc" -> q.jc = x.attr("val")
                        "pageBreakBefore" -> q.pbb = on(x)
                        "spacing" -> {
                            x.attr("before")?.toIntOrNull()?.let { q.before = it }
                            x.attr("after")?.toIntOrNull()?.let { q.after = it }
                            x.attr("line")?.toIntOrNull()?.let { q.line = it; q.rule = x.attr("lineRule") ?: "auto" }
                        }
                        "outlineLvl" -> q.outline = x.attr("val")?.toIntOrNull() ?: -1
                        "ind" -> {
                            (x.attr("left") ?: x.attr("start"))?.toIntOrNull()?.let { q.left = it }
                            val h = x.attr("hanging")?.toIntOrNull()
                            val fl = x.attr("firstLine")?.toIntOrNull()
                            if (h != null) q.first = -h else if (fl != null) q.first = fl
                        }
                    }
                    x.skip()
                }
            }
        }
        return q
    }

    /** Part 3: w:rFonts -> family name. Explicit ascii / hAnsi name wins; otherwise the theme font (minor = body, major = headings). */
    private fun fontOf(x: MiniXml): String? {
        FontMap.clean(x.attr("ascii") ?: x.attr("hAnsi"))?.let { return it }
        val th = x.attr("asciiTheme") ?: x.attr("hAnsiTheme") ?: return null
        return FontMap.clean(if (th.startsWith("major")) themeFonts().first else if (th.startsWith("minor")) themeFonts().second else null)
    }

    /** (major, minor) Latin typeface of the document theme; (null, null) when there is no theme part. */
    private fun themeFonts(): Pair<String?, String?> { val t = theme(); return if (themeFound) t.major to t.minor else null to null }

    private fun parseRPr(x: MiniXml): RPr {
        val q = RPr()
        eachChild(x) { n ->
            when (n) {
                "b" -> q.b = on(x)
                "i" -> q.i = on(x)
                "u" -> q.u = x.attr("val") != "none"
                "strike", "dstrike" -> q.strike = on(x)
                "caps", "smallCaps" -> q.caps = on(x)
                "color" -> q.color = colorOf(x)
                "sz" -> q.sz = x.attr("val")?.toIntOrNull() ?: 0
                "rFonts" -> q.font = fontOf(x)
                "highlight" -> q.hl = x.attr("val")?.takeIf { it != "none" }
                "vertAlign" -> q.script = when (x.attr("val")) { "superscript" -> 1; "subscript" -> -1; else -> 0 }
                "rStyle" -> q.rStyle = x.attr("val")
            }
            x.skip()
        }
        return q
    }

    // ---- numbering ----

    private fun numLabel(numId: String, ilvl: Int, p: DPara) {
        val abs = numToAbs[numId] ?: return
        val lvls = absLevels[abs] ?: return
        val lv = lvls[ilvl] ?: return
        val c = counters.getOrPut(abs) { IntArray(9) { -1 } }
        c[ilvl] = if (c[ilvl] < 0) lv.start else c[ilvl] + 1
        for (k in ilvl + 1 until 9) c[k] = -1
        p.level = ilvl
        if (lv.fmt == "bullet") { p.bullet = true; p.label = bulletChar(lv.text); return }
        p.label = Regex("%([1-9])").replace(lv.text) { m ->
            val d = m.groupValues[1].toInt() - 1
            val ld = lvls[d]
            fmtNum(if (c[d] < 0) (ld?.start ?: 1) else c[d], ld?.fmt ?: "decimal")
        }
    }

    private fun bulletChar(t: String): String {
        if (t.isEmpty()) return "•"
        val c = t[0]
        return when {
            c in '\uF000'..'\uF0FF' -> when (c.code) { 0xF0A7 -> "▪"; 0xF0D8 -> "➢"; 0xF0FC -> "✓"; 0xF076 -> "❖"; else -> "•" }
            c == 'o' -> "◦"
            else -> t.take(1)
        }
    }

    private fun fmtNum(n: Int, fmt: String): String = when (fmt) {
        "decimalZero" -> "%02d".format(n)
        "lowerLetter" -> ('a' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
        "upperLetter" -> ('A' + (n - 1) % 26).toString().repeat((n - 1) / 26 + 1)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "none" -> ""
        else -> n.toString()
    }

    private fun roman(n0: Int): String {
        if (n0 !in 1..3999) return n0.toString()
        var n = n0
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
        return sb.toString()
    }

    // ---- xml helpers ----

    private fun root(x: MiniXml): Boolean {
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.START) return true
            if (t == MiniXml.T.EOF) return false
        }
    }

    /** Call right after a START: runs [f] for each child START (f must consume that child, e.g. via skip()); returns after the END of the current element. */
    private fun eachChild(x: MiniXml, f: (String) -> Unit) {
        val d = x.depth
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) return
            if (t == MiniXml.T.END && x.depth < d) return
            if (t == MiniXml.T.START) f(x.name)
        }
    }
}
