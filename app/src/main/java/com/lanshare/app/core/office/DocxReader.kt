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
        fun overlay(o: PPr) {
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

    private val styles = HashMap<String, DStyle>()
    private val resolved = HashMap<String, DStyle>()
    private var defRpr = RPr()
    private var defPara: String? = null
    private val absLevels = HashMap<String, HashMap<Int, NumLvl>>()
    private val numToAbs = HashMap<String, String>()
    private val counters = HashMap<String, IntArray>()

    private var count = 0
    private var max = 20000
    private var truncated = false
    private var hdrRid: String? = null
    private var ftrRid: String? = null

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
        max = maxParagraphs; count = 0; truncated = false; hdrRid = null; ftrRid = null; counters.clear(); cur = mainRels
        val d = DDoc()
        pkg.xml(mainPart)?.let { x ->
            if (root(x)) eachChild(x) { n ->
                if (n == "body") eachChild(x) { c -> blockChild(x, c, d.body) } else x.skip()
            }
        }
        d.truncated = truncated
        d.defaultSize = defRpr.sz
        d.defaultFont = defRpr.font
        count = 0
        hdrRid?.let { d.header = readPart(it) }
        ftrRid?.let { d.footer = readPart(it) }
        readNotes("footnotes", "footnote", d.footnotes)
        readNotes("endnotes", "endnote", d.endnotes)
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

    private fun parseStyles(part: String) {
        val x = pkg.xml(part) ?: return
        if (!root(x)) return
        eachChild(x) { n ->
            when (n) {
                "docDefaults" -> eachChild(x) { c ->
                    if (c == "rPrDefault") eachChild(x) { k -> if (k == "rPr") defRpr = parseRPr(x) else x.skip() } else x.skip()
                }
                "style" -> {
                    val st = DStyle(x.attr("styleId") ?: "")
                    if (x.attr("default") == "1" && x.attr("type") == "paragraph") defPara = st.id
                    eachChild(x) { c ->
                        when (c) {
                            "name" -> { st.name = x.attr("val") ?: ""; x.skip() }
                            "basedOn" -> { st.basedOn = x.attr("val"); x.skip() }
                            "pPr" -> st.ppr.overlay(parsePPr(x))
                            "rPr" -> st.rpr.overlay(parseRPr(x))
                            else -> x.skip()
                        }
                    }
                    styles[st.id] = st
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
            "sectPr" -> parseSectPr(x)
            else -> x.skip()
        }
    }

    private fun parseSectPr(x: MiniXml) {
        eachChild(x) { n ->
            if ((n == "headerReference" || n == "footerReference") && x.attr("type") == "default") {
                val id = x.rawAttr("r:id") ?: x.attr("id")
                if (n == "headerReference") { if (hdrRid == null) hdrRid = id } else if (ftrRid == null) ftrRid = id
            }
            x.skip()
        }
    }

    private fun parseTable(x: MiniXml): DTable {
        val t = DTable()
        eachChild(x) { n ->
            when (n) {
                "tblPr" -> eachChild(x) { c ->
                    when (c) {
                        "tblStyle" -> t.style = x.attr("val") ?: ""
                        "tblBorders" -> t.borders = true
                    }
                    x.skip()
                }
                "tblGrid" -> eachChild(x) { c ->
                    if (c == "gridCol") t.grid.add(x.attr("w")?.toIntOrNull() ?: 0)
                    x.skip()
                }
                "tr" -> t.rows.add(parseRow(x))
                else -> x.skip()
            }
        }
        if (t.style.contains("grid", true)) t.borders = true
        return t
    }

    private fun parseRow(x: MiniXml): DRow {
        val r = DRow()
        eachChild(x) { n ->
            when (n) {
                "trPr" -> eachChild(x) { c -> if (c == "tblHeader") r.header = true; x.skip() }
                "tc" -> r.cells.add(parseCell(x))
                "sdt" -> eachChild(x) { c ->
                    if (c == "sdtContent") eachChild(x) { d -> if (d == "tc") r.cells.add(parseCell(x)) else x.skip() } else x.skip()
                }
                else -> x.skip()
            }
        }
        return r
    }

    private fun parseCell(x: MiniXml): DCell {
        val c = DCell()
        eachChild(x) { n ->
            if (n == "tcPr") eachChild(x) { k ->
                when (k) {
                    "gridSpan" -> c.span = x.attr("val")?.toIntOrNull() ?: 1
                    "vMerge" -> c.vMerge = if (x.attr("val") == "restart") 1 else 2
                    "tcW" -> c.width = x.attr("w")?.toIntOrNull() ?: 0
                    "shd" -> c.fill = x.attr("fill")?.takeIf { it.length == 6 }
                }
                x.skip()
            } else blockChild(x, n, c.blocks)
        }
        return c
    }

    // ---- paragraphs ----

    private fun parsePara(x: MiniXml, out: ArrayList<DBlock>) {
        val p = DPara()
        val extra = ArrayList<DBlock>()
        var st0 = resolve(defPara)
        var base = RPr().also { it.overlay(defRpr); st0?.let { s -> it.overlay(s.rpr) } }
        var eff = PPr().also { e -> st0?.let { s -> e.overlay(s.ppr) } }
        var sname = st0?.name ?: ""
        eachChild(x) { n ->
            if (n == "pPr") {
                val q = parsePPr(x)
                val st = resolve(q.style ?: defPara)
                base = RPr().also { it.overlay(defRpr); st?.let { s -> it.overlay(s.rpr) } }
                eff = PPr().also { e -> st?.let { s -> e.overlay(s.ppr) }; e.overlay(q) }
                sname = st?.name ?: ""
            } else inl(x, n, p, null, base, extra)
        }
        p.style = sname
        p.align = when (eff.jc) { "start", "left" -> "left"; "end", "right" -> "right"; "both", "distribute" -> "justify"; "center" -> "center"; else -> null }
        p.indentLeft = if (eff.left == Int.MIN_VALUE) 0 else eff.left
        p.firstLine = if (eff.first == Int.MIN_VALUE) 0 else eff.first
        p.pageBreakBefore = eff.pbb == true
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
            "ins", "moveTo", "smartTag", "fldSimple", "customXml", "sdt", "sdtContent", "AlternateContent", "Choice" ->
                eachChild(x) { c -> inl(x, c, p, link, base, extra) }
            else -> x.skip()
        }
    }

    private fun parseRun(x: MiniXml, p: DPara, link: String?, base: RPr, extra: ArrayList<DBlock>) {
        var f = base
        fun mk(kind: Int, text: String): DRun {
            val r = DRun(kind, text)
            r.bold = f.b == true; r.italic = f.i == true; r.underline = f.u == true; r.strike = f.strike == true; r.caps = f.caps == true
            r.color = f.color; r.highlight = f.hl; r.size = f.sz; r.script = f.script; r.link = link; r.font = f.font
            p.runs.add(r)
            return r
        }
        fun child(n: String) {
            when (n) {
                "rPr" -> {
                    val q = parseRPr(x)
                    f = RPr().also { it.overlay(base); resolve(q.rStyle)?.let { s -> it.overlay(s.rpr) }; it.overlay(q) }
                }
                "t" -> { val t = x.readText(); if (t.isNotEmpty()) mk(DRun.TEXT, t) }
                "tab" -> { mk(DRun.TAB, "\t"); x.skip() }
                "br" -> { mk(if (x.attr("type") == "page") DRun.PAGE else DRun.BREAK, ""); x.skip() }
                "cr" -> { mk(DRun.BREAK, ""); x.skip() }
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
                "drawing", "pict", "object" -> parseDrawing(x, extra) { img -> mk(DRun.IMAGE, "").image = img }
                "AlternateContent" -> eachChild(x) { c -> if (c == "Choice") eachChild(x) { d -> child(d) } else x.skip() }
                else -> x.skip()
            }
        }
        eachChild(x) { child(it) }
    }

    /** Flat scan of a drawing / pict subtree: images (blip, VML imagedata) and text-box content. */
    private fun parseDrawing(x: MiniXml, extra: ArrayList<DBlock>, emit: (DImage) -> Unit) {
        val d = x.depth
        var cx = 0L; var cy = 0L; var alt = ""
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF || (t == MiniXml.T.END && x.depth < d)) return
            if (t != MiniXml.T.START) continue
            when (x.name) {
                "extent" -> { cx = x.attr("cx")?.toLongOrNull() ?: 0L; cy = x.attr("cy")?.toLongOrNull() ?: 0L }
                "docPr" -> alt = x.attr("descr")?.takeIf { it.isNotEmpty() } ?: x.attr("name") ?: ""
                "shape", "rect" -> { val s = x.attr("style"); cx = vmlLen(s, "width"); cy = vmlLen(s, "height") }
                "blip" -> {
                    val rid = x.rawAttr("r:embed") ?: x.rawAttr("r:link")
                    val rel = if (rid != null) cur[rid] else null
                    if (rel != null && !rel.external) emit(DImage(rel.target, cx, cy, alt))
                }
                "imagedata" -> {
                    val rel = x.rawAttr("r:id")?.let { cur[it] }
                    if (rel != null && !rel.external) emit(DImage(rel.target, cx, cy, x.attr("title") ?: alt))
                }
                "txbxContent" -> eachChild(x) { c -> blockChild(x, c, extra) }
            }
        }
    }

    private fun vmlLen(style: String?, key: String): Long {
        val m = Regex("(?:^|;)\\s*$key\\s*:\\s*([0-9.]+)(pt|in|cm|mm|px)?").find(style ?: "") ?: return 0L
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
                "sectPr" -> parseSectPr(x)
                else -> {
                    when (n) {
                        "pStyle" -> q.style = x.attr("val")
                        "jc" -> q.jc = x.attr("val")
                        "pageBreakBefore" -> q.pbb = on(x)
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

    /** (major, minor) Latin typeface of word/theme/theme1.xml; (null, null) when there is no theme. */
    private var themeCache: Pair<String?, String?>? = null   // NOT `by lazy`: styles are parsed in init, before later property initialisers run
    private fun themeFonts(): Pair<String?, String?> = themeCache ?: loadThemeFonts().also { themeCache = it }
    private fun loadThemeFonts(): Pair<String?, String?> {
        var major: String? = null; var minor: String? = null
        try {
            val t = pkg.xml("word/theme/theme1.xml")
            if (t != null) {
                var mode = 0
                loop@ while (true) when (t.next()) {
                    MiniXml.T.EOF -> break@loop
                    MiniXml.T.START -> when (t.name) {
                        "majorFont" -> mode = 1
                        "minorFont" -> mode = 2
                        "latin" -> { val tf = t.attr("typeface")?.takeIf { it.isNotEmpty() }; if (mode == 1 && major == null) major = tf else if (mode == 2 && minor == null) minor = tf }
                    }
                    MiniXml.T.END -> if (t.name == "majorFont" || t.name == "minorFont") mode = 0
                    else -> {}
                }
            }
        } catch (_: Throwable) {}
        return major to minor
    }

    private fun parseRPr(x: MiniXml): RPr {
        val q = RPr()
        eachChild(x) { n ->
            when (n) {
                "b" -> q.b = on(x)
                "i" -> q.i = on(x)
                "u" -> q.u = x.attr("val") != "none"
                "strike", "dstrike" -> q.strike = on(x)
                "caps", "smallCaps" -> q.caps = on(x)
                "color" -> q.color = x.attr("val")?.takeIf { it.length == 6 }
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
