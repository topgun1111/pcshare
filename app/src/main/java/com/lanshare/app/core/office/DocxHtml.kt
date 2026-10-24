package com.lanshare.app.core.office

/** DDoc -> self-contained HTML (Part 3a). Pure Kotlin, no android.*.
 *  [image] maps a DImage to a src (data: URI or file URL); null -> placeholder. */
object DocxHtml {
    private val HL = mapOf("yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff", "blue" to "#0000ff",
        "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000", "darkMagenta" to "#800080",
        "darkRed" to "#800000", "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000", "white" to "#ffffff")

    private const val CSS = "body{margin:0;background:#e8e8e8;color:#000}" +
        ".page{background:#fff;max-width:816px;margin:8px auto;padding:24px 28px;box-sizing:border-box;overflow-wrap:anywhere;isolation:isolate}" +
        "p{margin:0 0 .6em;white-space:pre-wrap;line-height:1.3}h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;line-height:1.2}" +
        "h1{font-size:1.9em}h2{font-size:1.5em}h3{font-size:1.25em}h4,h5,h6{font-size:1.05em}" +
        "table{border-collapse:collapse;margin:0 0 .8em;table-layout:fixed;max-width:100%;box-sizing:border-box}td,th{padding:3px 5px;vertical-align:top;overflow-wrap:anywhere}" +
        "table.b td,table.b th{border:1px solid #888}td>p:last-child{margin-bottom:0}img{max-width:100%;height:auto}" +
        ".li>.lb{display:inline-block;text-indent:0}.pb{border:0;border-top:1px dashed #aaa;margin:1em 0}" +
        ".hf{color:#666;font-size:.85em;border-bottom:1px solid #ddd;margin-bottom:1em}.hf.f{border:0;border-top:1px solid #ddd;margin:1em 0 0}" +
        ".fn{border-top:1px solid #aaa;margin-top:1.5em;padding-top:.5em;font-size:.85em}.noimg{color:#888;font-style:italic}" +
        "sup a,.fn a{text-decoration:none}sup a.cm{color:#8a5a00;background:#ffe08a;border-radius:3px;padding:0 3px;font-weight:bold;font-size:.8em}.tr{background:#fff3cd;padding:4px 8px;font-size:.8em}" +
        "@media(max-width:700px){.page{padding:12px 10px;margin:0 auto}td,th{padding:2px 3px}}"

    /** Part 5: set while [renderPrint] runs, so the shared block renderers emit the page-layout variant (see [renderPrint]). */
    private val PRINT = ThreadLocal<Boolean>()
    private val printing get() = PRINT.get() == true
    /** Part 6: print mode only: floating objects that sit in front of / behind the text are registered here (positioned by the page script). */
    private val OBJS = ThreadLocal<StringBuilder>()
    private val OID = ThreadLocal<IntArray>()
    /** Part 6: [content width, page width] in EMU of the flow being rendered (decides on which side a floating object goes). */
    private val GEO = ThreadLocal<LongArray>()
    /** Part 8: tracked-change view of the render in progress: [MARK_SHOW] = markup + comments, [MARK_FINAL] = text as if all changes were accepted, [MARK_ORIGINAL] = as if all were rejected. */
    private val MARK = ThreadLocal<Int>()
    const val MARK_SHOW = 0; const val MARK_FINAL = 1; const val MARK_ORIGINAL = 2
    private val mark get() = MARK.get() ?: 0
    private val REV_LIGHT = arrayOf("#c0392b", "#1f6fb2", "#1e8449", "#8e44ad", "#b9770e", "#117a65")
    private val REV_DARK = arrayOf("#ff8a80", "#82b1ff", "#69f0ae", "#ce93d8", "#ffd180", "#64ffda")
    /** One colour per author (same author = same colour in a document). */
    private fun revCol(author: String?, dark: Boolean): String {
        val i = Math.abs((author ?: "").hashCode() % REV_LIGHT.size)
        return if (dark) REV_DARK[i] else REV_LIGHT[i]
    }
    private fun geo(): LongArray = GEO.get() ?: longArrayOf(5943600L, 7772400L)
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    /** [rd] != null -> phone reading layout (reflowed text, own font size and theme, no page chrome); null -> page-like layout as before. */
    /** [fontBase]: URL prefix the bundled fonts are served from (FontMap.FONT_URL in the viewer); null = no @font-face, system fonts only. */
    fun render(doc: DDoc, image: (DImage) -> String? = { null }, title: String = "", rd: DocxReading? = null, fontBase: String? = null, markup: Int = MARK_SHOW): String {
        MARK.set(markup)
        try { return render1(doc, image, title, rd, fontBase) } finally { MARK.remove() }
    }

    private fun render1(doc: DDoc, image: (DImage) -> String?, title: String, rd: DocxReading?, fontBase: String?): String {
        val notes = LinkedHashMap<String, Int>()
        fun noteNo(id: String) = notes.getOrPut(id) { notes.size + 1 }
        val sb = StringBuilder(16 * 1024)
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1,minimum-scale=0.05,maximum-scale=10,user-scalable=yes\"><title>")
            .append(esc(title)).append("</title><style>")
        if (fontBase != null) sb.append(FontMap.fontFaceCss(fontBase))
        sb.append(CSS).append("body{font-family:").append(FontMap.stack(doc.defaultFont)).append('}')
        if (doc.defaultSize > 0) sb.append(".page{font-size:").append(doc.defaultSize / 2.0).append("pt}")
        if (rd != null) sb.append(readingCss(rd))
        sb.append("</style></head><body><div class=page>")
        if (doc.truncated) sb.append("<div class=tr>Document truncated (too long).</div>")
        if (rd == null && doc.header.isNotEmpty()) { sb.append("<div class=hf>"); blocks(doc, doc.header, sb, image, rd, ::noteNo); sb.append("</div>") }
        blocks(doc, doc.body, sb, image, rd, ::noteNo)
        if (notes.isNotEmpty()) {
            sb.append("<div class=fn>")
            for ((id, n) in notes) {
                val fb = doc.footnotes[id] ?: doc.endnotes[id] ?: continue
                sb.append("<div id=\"fn").append(esc(id)).append("\"><a href=\"#rf").append(esc(id)).append("\">").append(n).append(".</a> ")
                val t = StringBuilder(); blocks(doc, fb, t, image, rd, ::noteNo)
                sb.append(t.toString().replace("<p>", "<span>").replace("</p>", "</span> ")).append("</div>")
            }
            sb.append("</div>")
        }
        if (rd == null && doc.footer.isNotEmpty()) { sb.append("<div class=\"hf f\">"); blocks(doc, doc.footer, sb, image, rd, ::noteNo); sb.append("</div>") }
        return sb.append("</div></body></html>").toString()
    }

    private const val PCSS = "html,body{margin:0;padding:0}body{background:#c9ccd1;color:#000}#pages{padding:8px 0 4px}" +
        ".pg{position:relative;background:#fff;margin:0 auto 10px;box-shadow:0 1px 4px rgba(0,0,0,.35);overflow:hidden}.vp,.hf{position:absolute;overflow:hidden}" +
        "p{margin:0;line-height:normal;white-space:pre-wrap;orphans:2;widows:2}.fl{overflow-wrap:break-word;background:#fff}.cb{break-after:column;height:0}" +
        "table{border-collapse:collapse;table-layout:fixed;box-sizing:border-box}tr{break-inside:avoid}td,th{padding:0 5.4pt;vertical-align:top;overflow-wrap:anywhere}" +
        "table.b td,table.b th{border:1px solid #444}img{max-width:100%;max-height:var(--ch,100000px);object-fit:contain;height:auto}" +
        ".li>.lb{display:inline-block;text-indent:0}.fn{border-top:1px solid #aaa;margin-top:1.5em;padding-top:.5em;font-size:.85em}.noimg{color:#888;font-style:italic}" +
        "sup a,.fn a{text-decoration:none}sup a.cm{color:#8a5a00;background:#ffe08a;border-radius:3px;padding:0 3px;font-weight:bold;font-size:.8em}.tr{background:#fff3cd;padding:4px 8px;font-size:.8em}.hft{display:none}#stage{display:block}" +
        "html.js #stage{position:absolute;left:0;top:0;height:0;overflow:hidden;visibility:hidden}.fld{white-space:pre}" +
        "#objs{display:none}.ao{display:inline-block;width:0;height:0}.ob2{position:absolute}.fo{max-width:100%}.sh p{white-space:pre-wrap}"

    private class Flow(val sec: DSection, var to: Int, val from: Int)

    private fun sameGeom(a: DSection, b: DSection) = a.pgW == b.pgW && a.pgH == b.pgH && a.marT == b.marT && a.marB == b.marB &&
        a.marL == b.marL && a.marR == b.marR && a.gutter == b.gutter && a.cols == b.cols && a.colGap == b.colGap

    /** Sections for formats without section info (.doc / .odt ...): A4, 2.5 cm margins. */
    private fun a4() = DSection().also { it.pgW = 595.3; it.pgH = 841.9; it.marT = 70.9; it.marB = 70.9; it.marL = 70.9; it.marR = 70.9 }

    /**
     * Part 5: print layout. Returns a page that needs JavaScript ([DocxPrintJs]) to paginate: white sheets at the real page size, stacked, scaled to the screen
     * by the viewport meta width. Without JS the same HTML still shows each section as one continuous white sheet ("paper mode").
     * Flows: consecutive sections with the same page geometry and breakType continuous share one flow, every other section starts a new flow (new page).
     * Honest limits: the header can overlap the text when it is taller than the top margin (Word pushes the text down); evenPage / oddPage breaks do not add
     * blank pages; unequal column widths become equal; footnotes are collected at the end of the document; page numbers inside the body text are not updated.
     */
    fun renderPrint(doc: DDoc, image: (DImage) -> String? = { null }, title: String = "", fontBase: String? = null, markup: Int = MARK_SHOW): String {
        PRINT.set(true); OBJS.set(StringBuilder()); OID.set(intArrayOf(0)); MARK.set(markup)
        try { return renderPrint0(doc, image, title, fontBase) } finally { PRINT.remove(); OBJS.remove(); OID.remove(); GEO.remove(); MARK.remove() }
    }

    private fun renderPrint0(doc: DDoc, image: (DImage) -> String?, title: String, fontBase: String?): String {
        val secs = if (doc.sections.isNotEmpty()) doc.sections else listOf(a4())
        val flows = ArrayList<Flow>()
        for ((i, s) in secs.withIndex()) {
            val from = if (i == 0) 0 else s.startsAtBlockIndex.coerceIn(0, doc.body.size)
            val to = if (i + 1 < secs.size) secs[i + 1].startsAtBlockIndex.coerceIn(from, doc.body.size) else doc.body.size
            val prev = flows.lastOrNull()
            if (prev != null && s.breakType == "continuous" && sameGeom(prev.sec, s)) prev.to = to else flows.add(Flow(s, to, from))
        }
        fun px(pt: Double) = f2(pt * 4.0 / 3.0)
        val maxW = flows.maxOf { it.sec.pgW } * 4.0 / 3.0
        val notes = LinkedHashMap<String, Int>()
        fun noteNo(id: String) = notes.getOrPut(id) { notes.size + 1 }
        val sb = StringBuilder(32 * 1024)
        sb.append("<!doctype html><html class=pl><head><meta charset=utf-8><meta name=viewport content=\"width=").append(Math.ceil(maxW + 16).toInt())
            .append(",initial-scale=1,minimum-scale=0.05,maximum-scale=10,user-scalable=yes\"><title>").append(esc(title)).append("</title><style>")
        if (fontBase != null) sb.append(FontMap.fontFaceCss(fontBase))
        sb.append(PCSS).append("body{font-family:").append(FontMap.stack(doc.defaultFont)).append(";font-size:").append(if (doc.defaultSize > 0) doc.defaultSize / 2.0 else 10.0).append("pt}")
        sb.append("</style></head><body><div id=pages></div><div id=stage>")
        for ((fi, f) in flows.withIndex()) {
            val s = f.sec
            GEO.set(longArrayOf((s.contentW * 12700).toLong(), (s.pgW * 12700).toLong()))
            sb.append("<div class=fl style=\"width:").append(px(s.pgW)).append("px;max-width:100%;box-sizing:border-box;margin:8px auto;padding:")
                .append(px(Math.abs(s.marT))).append("px ").append(px(Math.abs(s.marR))).append("px ").append(px(Math.abs(s.marB))).append("px ").append(px(Math.abs(s.marL + s.gutter))).append("px\">")
            if (fi == 0 && doc.truncated) sb.append("<div class=tr>Document truncated (too long).</div>")
            blocks(doc, doc.body.subList(f.from, f.to), sb, image, null, ::noteNo)
            if (fi == flows.size - 1 && notes.isNotEmpty()) {
                sb.append("<div class=fn>")
                for ((id, n) in notes) {
                    val fb = doc.footnotes[id] ?: doc.endnotes[id] ?: continue
                    sb.append("<div id=\"fn").append(esc(id)).append("\"><a href=\"#rf").append(esc(id)).append("\">").append(n).append(".</a> ")
                    val t = StringBuilder(); blocks(doc, fb, t, image, null, ::noteNo)
                    sb.append(t.toString().replace("<p>", "<span>").replace("</p>", "</span> ")).append("</div>")
                }
                sb.append("</div>")
            }
            sb.append("</div>")
            val hf = listOf("hd" to s.headerDefault, "hf" to s.headerFirst, "he" to s.headerEven, "fd" to s.footerDefault, "ff" to s.footerFirst, "fe" to s.footerEven)
            for ((k, bs) in hf) if (bs.isNotEmpty()) {
                sb.append("<div class=hft data-f=").append(fi).append(" data-k=").append(k).append('>'); blocks(doc, bs, sb, image, null, ::noteNo); sb.append("</div>")
            }
        }
        sb.append("</div><div id=objs>").append(OBJS.get()).append("</div><script id=meta type=\"application/json\">{\"f\":[")
        for ((fi, f) in flows.withIndex()) {
            val s = f.sec
            if (fi > 0) sb.append(',')
            sb.append("{\"pw\":").append(px(s.pgW)).append(",\"ph\":").append(px(s.pgH)).append(",\"ml\":").append(px(Math.abs(s.marL))).append(",\"mt\":").append(px(Math.abs(s.marT)))
                .append(",\"gut\":").append(px(s.gutter)).append(",\"cw\":").append(px(s.contentW)).append(",\"ch\":").append(px(s.contentH))
                .append(",\"cols\":").append(maxOf(1, s.cols)).append(",\"gap\":").append(px(s.colGap)).append(",\"hd\":").append(px(s.hdrDist)).append(",\"fd\":").append(px(s.ftrDist))
                .append(",\"tp\":").append(s.titlePg).append(",\"eo\":").append(s.evenOdd).append(",\"ns\":").append(s.pgNumStart)
                .append(",\"nf\":").append(if (s.pgNumFmt == null) "null" else "\"" + s.pgNumFmt!!.replace(Regex("[^A-Za-z]"), "") + "\"").append('}')
        }
        sb.append("]}</script><script>").append(DocxPrintJs.JS).append("</script></body></html>")
        return sb.toString()
    }

    private fun blocks(doc: DDoc, bs: List<DBlock>, sb: StringBuilder, img: (DImage) -> String?, rd: DocxReading?, note: (String) -> Int) {
        for (b in bs) when (b) {
            is DPara -> para(doc, b, sb, img, rd, note)
            is DTable -> table(doc, b, sb, img, rd, note)
        }
    }

    private fun para(doc: DDoc, p: DPara, sb: StringBuilder, img: (DImage) -> String?, rd: DocxReading?, note: (String) -> Int) {
        // Part 8: a paragraph whose whole content is a deletion (Final) or an insertion (Original) does not exist in that view, as in Word
        if (p.runs.isNotEmpty() && p.runs.all { hidden(it) } && p.runs.any { it.rev != 0 }) { if (p.bookmarks.isNotEmpty()) for (b in p.bookmarks) sb.append("<a id=\"").append(esc(b)).append("\"></a>"); return }
        val st = StringBuilder()
        if (p.align != null) st.append("text-align:").append(if (p.align == "both") "justify" else p.align).append(';')
        val pr = printing
        val tag = if (p.heading > 0 && !pr) "h" + minOf(p.heading, 6) else "p"
        var cls = if (pr && p.heading > 0) "h" + minOf(p.heading, 6) else ""
        var hang = 0
        val tl = if (rd == null && p.label == null && p.runs.any { it.kind == DRun.TAB }) tabLayout(doc, p) else null   // Part 7: tab stops / leaders (not in reading mode: it reflows)
        if (rd?.theme != DocxReading.DARK) p.fill?.let { st.append("background:#").append(it).append(';') }
        if (rd == null) p.bdr?.let { b -> borderCss(b.top, "top", st); borderCss(b.left, "left", st); borderCss(b.bottom, "bottom", st); borderCss(b.right, "right", st)
            if (b.left != null || b.right != null) st.append("padding-left:4px;padding-right:4px;"); if (b.bottom != null) st.append("padding-bottom:1px;"); if (b.top != null) st.append("padding-top:1px;") }
        if (pr) {
            // Word spacing: before / after in points (adjoining margins collapse to the larger one, as in Word), line spacing as a multiple of the font's natural height
            st.append("margin-top:").append(f2(maxOf(p.spBefore, 0.0))).append("pt;margin-bottom:").append(f2(maxOf(p.spAfter, 0.0))).append("pt;")
            if (p.lineExact > 0 && !p.lineAtLeast) st.append("line-height:").append(f2(p.lineExact)).append("pt;")
            else if (p.lineMul > 0 && Math.abs(p.lineMul - 1.0) > 0.01) {
                val fam = p.runs.firstOrNull { it.kind == DRun.TEXT && it.font != null }?.font ?: doc.defaultFont
                st.append("line-height:").append(f2(FontMap.lineFactor(fam) * p.lineMul)).append(';')
            }
            if (p.pageBreakBefore) st.append("break-before:column;")
            if (p.heading > 0) st.append("break-after:avoid;")
        }
        if (p.label != null) {
            cls = if (cls.isEmpty()) "li" else "$cls li"
            var left = if (p.indentLeft > 0) p.indentLeft / 15 else (p.level + 1) * 24
            hang = if (p.firstLine < 0) -p.firstLine / 15 else 24
            if (rd != null) { hang = minOf(hang, 28); left = maxOf(hang, minOf(left / 2, 24 + p.level * 20)) }   // reading: narrow screen, keep the nesting but not the page margins
            st.append("margin-left:").append(left).append("px;text-indent:-").append(hang).append("px;")
        } else if (tl != null) {
            st.append("margin-left:").append(tl.start / 15).append("px;").append(tl.css)
        } else {
            if (p.indentLeft > 0) st.append("margin-left:").append(if (rd != null) minOf(p.indentLeft / 30, 40) else p.indentLeft / 15).append("px;")
            if (p.firstLine != 0 && rd == null) st.append("text-indent:").append(p.firstLine / 15).append("px;")
        }
        if (p.pageBreakBefore && !pr) sb.append("<hr class=pb>")
        val openAt = sb.length
        val fl = StringBuilder()   // Part 6: floating objects of this paragraph, inserted BEFORE it (they must not sit inside the <p>)
        sb.append('<').append(tag)
        if (cls.isNotEmpty()) sb.append(" class=\"").append(cls).append('"')
        if (st.isNotEmpty()) sb.append(" style=\"").append(st).append('"')
        sb.append('>')
        val openTag = sb.substring(openAt).replace("break-before:column;", "")
        for (b in p.bookmarks) sb.append("<a id=\"").append(esc(b)).append("\"></a>")   // Part 8: target of internal links (TOC entries, cross references)
        if (p.label != null) sb.append("<span class=lb style=\"min-width:").append(hang).append("px\">").append(esc(p.label!!)).append("</span>")
        if (p.runs.isEmpty() && p.label == null) sb.append("&nbsp;")
        fun emit(lo: Int, hi: Int, sb: StringBuilder) {
        var i = lo
        while (i < hi) {
            val r = p.runs[i]
            if (hidden(r)) { i++; continue }   // Part 8: deleted text in the Final view, inserted text in the Original view
            when (r.kind) {
                DRun.CMT -> if (mark == MARK_SHOW) doc.comments[r.text]?.let { _ ->
                    sb.append("<sup><a class=cm href=\"lsoffice://comment/").append(esc(r.text)).append("\">").append(doc.comments.keys.indexOf(r.text) + 1).append("</a></sup>")
                }
                DRun.TEXT -> {
                    val txt = StringBuilder(esc(r.text)); var j = i + 1
                    while (j < hi && p.runs[j].kind == DRun.TEXT && p.runs[j].sameStyle(r)) { txt.append(esc(p.runs[j].text)); j++ }
                    styled(doc, r, txt.toString(), rd, sb); i = j; continue
                }
                DRun.FIELD -> styled(doc, r, if (pr && (r.field == "PAGE" || r.field == "NUMPAGES")) "<span class=fld data-f=\"" + r.field + "\">" + esc(r.text) + "</span>" else esc(r.text), rd, sb)   // Part 4: PAGE / NUMPAGES / DATE / TITLE, cached result
                DRun.TAB -> sb.append("<span style=\"display:inline-block;width:2em\"></span>")
                DRun.BREAK -> sb.append("<br>")
                DRun.PAGE -> if (tl != null) sb.append("<br>") else if (pr) sb.append("</p><div class=cb></div>").append(openTag) else sb.append("<hr class=pb>")
                DRun.IMAGE -> r.image?.let { im ->
                    val src = img(im)
                    val a = im.anchor
                    if (src == null) sb.append("<span class=noimg>[").append(esc(im.alt.ifEmpty { "image" })).append("]</span>")
                    else if (a == null) {
                        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"").append(esc(im.alt)).append('"')
                        if (im.widthEmu > 0) {
                            sb.append(" style=\"width:").append(im.widthEmu / 9525).append("px")
                            if (pr && im.heightEmu > 0) sb.append(";aspect-ratio:").append(im.widthEmu / 9525).append('/').append(maxOf(1L, im.heightEmu / 9525))   // reserves the height before the image decodes (pagination must not shift)
                            sb.append('"')
                        }
                        sb.append('>')
                    } else {
                        val w = if (im.widthEmu > 0) im.widthEmu else 1905000L; val h = im.heightEmu
                        val inner = "<img src=\"" + esc(src) + "\" alt=\"" + esc(im.alt) + "\" style=\"display:block;width:" + w / 9525 + "px;max-width:100%" +
                            (if (h > 0) ";aspect-ratio:" + w / 9525 + "/" + maxOf(1L, h / 9525) else "") + "\">"
                        place(a, w, h, inner, sb, fl, rd, 0L, 0L)
                    }
                }
                DRun.SHAPE -> r.shape?.let { s ->
                    val a = s.anchor
                    if (a == null && s.blocks.isEmpty()) sb.append(shapeHtml(doc, s, img, rd, note, "span"))
                    else if (a == null) fl.append("<div class=fo style=\"clear:both\">").append(shapeHtml(doc, s, img, rd, note, "div")).append("</div>")
                    else place(a, s.wEmu, s.hEmu, shapeHtml(doc, s, img, rd, note, "div"), sb, fl, rd, s.dx, s.dy)
                }
                DRun.NOTE -> { val n = note(r.text); sb.append("<sup><a id=\"rf").append(esc(r.text)).append("\" href=\"#fn").append(esc(r.text)).append("\">").append(n).append("</a></sup>") }
            }
            i++
        }
        }
        if (tl == null) emit(0, p.runs.size, sb) else tabbed(p, tl, sb, ::emit)
        sb.append("</").append(tag).append('>')
        if (fl.isNotEmpty()) sb.insert(openAt, fl)
    }

    private fun px(emu: Long) = emu / 9525

    /** Part 7: one border side as CSS ("border-top:1px solid #RRGGBB;"). [b] null / none -> nothing. Width = eighths of a point -> px. */
    private fun borderCss(b: DBorder?, side: String, st: StringBuilder) {
        if (b == null || b.none) return
        val w = maxOf(1, Math.round(b.sz / 6.0).toInt())
        val kind = when (b.style) { "double" -> "double"; "dotted" -> "dotted"; "dashed", "dashSmallGap", "dashDotStroked" -> "dashed"; "inset" -> "inset"; "outset" -> "outset"; else -> "solid" }
        st.append("border-").append(side).append(':').append(if (kind == "double") maxOf(3, w) else if (b.style == "thick" || b.style.startsWith("thick")) maxOf(2, w) else w).append("px ").append(kind).append(" #").append(b.color ?: "000000").append(';')
    }

    // ---- Part 7: tab stops ----
    /** [mode] 0 = inline boxes (left tabs, default stops), 1 = flex row with a leader and a right-aligned last segment, 2 = grid left / centre / right, 3 = grid with a centred segment. */
    private class TabLay(val start: Int, val stops: List<DTab>, val mode: Int, val css: String)

    /** Assigns every tab run of [p] to a stop (custom stops first, then Word's default 0.5 inch stops) from an estimate of the text widths; decimal stops count as right, bar stops are ignored. */
    private fun tabLayout(doc: DDoc, p: DPara): TabLay {
        val fs = (p.runs.firstOrNull { it.kind == DRun.TEXT && it.size > 0 }?.size ?: doc.defaultSize.takeIf { it > 0 } ?: 22) / 2.0
        val segW = ArrayList<Int>(); var cur = 0
        for (r in p.runs) when (r.kind) {
            DRun.TAB -> { segW.add(cur); cur = 0 }
            DRun.TEXT, DRun.FIELD -> cur += (r.text.length * (if (r.size > 0) r.size / 2.0 else fs) * 0.5 * 20).toInt()
        }
        segW.add(cur)
        val start = maxOf(0, p.indentLeft + p.firstLine)
        val stops = ArrayList<DTab>(); var cursor = start + segW[0]
        for (k in 1 until segW.size) {
            val c = p.tabs.firstOrNull { it.pos > cursor && it.kind != "bar" }
            val st = if (c != null) DTab(c.pos, if (c.kind == "decimal") "right" else c.kind, c.leader) else DTab(((cursor / 720) + 1) * 720, "left", "none")
            stops.add(st)
            cursor = when (st.kind) { "right" -> st.pos; "center" -> st.pos + segW[k] / 2; else -> st.pos + segW[k] }
        }
        val kinds = stops.map { it.kind }
        val mode = when {
            stops.size == 2 && kinds[0] == "center" && kinds[1] == "right" -> 2
            stops.size == 1 && kinds[0] == "center" -> 3
            kinds.last() == "right" && kinds.dropLast(1).all { it == "left" } -> 1
            else -> 0
        }
        val css = when (mode) {
            1 -> "display:flex;align-items:flex-end;max-width:" + (stops.last().pos - start) / 15 + "px;"
            2 -> "display:grid;grid-template-columns:1fr auto 1fr;max-width:" + (stops[1].pos - start) / 15 + "px;"
            3 -> "display:grid;grid-template-columns:1fr auto 1fr;max-width:" + maxOf(1, (stops[0].pos - start) * 2) / 15 + "px;"
            else -> ""
        }
        return TabLay(start, stops, mode, css)
    }

    private fun leaderCss(l: String): String = when (l) {
        "dot", "middleDot" -> "border-bottom:1px dotted currentColor;"; "hyphen" -> "border-bottom:1px dashed currentColor;"
        "underscore" -> "border-bottom:1px solid currentColor;"; "heavy" -> "border-bottom:2px solid currentColor;"; else -> ""
    }

    private fun tabbed(p: DPara, tl: TabLay, sb: StringBuilder, emit: (Int, Int, StringBuilder) -> Unit) {
        val idx = p.runs.indices.filter { p.runs[it].kind == DRun.TAB }
        val segs = ArrayList<StringBuilder>()
        var lo = 0
        for (t in idx + p.runs.size) { segs.add(StringBuilder().also { emit(lo, t, it) }); lo = t + 1 }
        val n = tl.stops.size
        fun w(j: Int) = maxOf(0, tl.stops[j].pos - (if (j == 0) tl.start else tl.stops[j - 1].pos)) / 15
        when (tl.mode) {
            2 -> { sb.append("<span>").append(segs[0]).append("</span><span style=\"text-align:center\">").append(segs[1]).append("</span><span style=\"text-align:right\">").append(segs[2]).append("</span>") }
            3 -> { sb.append("<span>").append(segs[0]).append("</span><span style=\"text-align:center\">").append(segs[1]).append("</span><span></span>") }
            1 -> {
                for (j in 0 until n - 1) sb.append("<span style=\"flex:none;min-width:").append(w(j)).append("px\">").append(segs[j]).append("</span>")
                val lead = leaderCss(tl.stops[n - 1].leader)
                sb.append("<span style=\"flex:0 1 auto;min-width:0\">").append(segs[n - 1]).append("</span>")
                sb.append("<span style=\"flex:1 1 0;min-width:.5em;margin:0 .15em .3em;").append(lead).append("\"></span>")
                sb.append("<span style=\"flex:none;white-space:nowrap;text-align:right\">").append(segs[n]).append("</span>")
            }
            else -> {
                for (j in 0 until n) {
                    val lead = leaderCss(tl.stops[j].leader)
                    if (lead.isNotEmpty()) sb.append("<span style=\"display:inline-flex;align-items:flex-end;min-width:").append(w(j)).append("px\">").append(segs[j])
                        .append("<span style=\"flex:1 1 0;min-width:.3em;margin:0 .15em .3em;").append(lead).append("\"></span></span>")
                    else sb.append("<span style=\"display:inline-block;min-width:").append(w(j)).append("px\">").append(segs[j]).append("</span>")
                }
                sb.append(segs[n])
            }
        }
    }

    /**
     * Part 6: puts a floating object next to its paragraph. square / tight wrap -> CSS float (side from alignment or offset); top-and-bottom -> its own
     * block; in front of / behind the text -> print layout: a marker in the text plus an entry in the object list that the page script places on the
     * sheet; simple / reading view: a zero-height box above the paragraph with the object positioned inside it (approximation).
     * [into] = the paragraph's own output (the marker goes there), [pre] = what is inserted before the paragraph. [dx] / [dy] = group offset (EMU).
     */
    private fun place(a: DAnchor, wE: Long, hE: Long, inner: String, into: StringBuilder, pre: StringBuilder, rd: DocxReading?, dx: Long, dy: Long) {
        val w = maxOf(1L, px(wE)); val h = px(hE)
        val mT = px(a.distT); val mB = px(a.distB); val mL = px(a.distL); val mR = px(a.distR)
        if (a.behind || a.wrap == "none") {
            val hOff = a.hOff + dx; val vOff = a.vOff + dy
            if (printing) {
                val id = OID.get()!!.let { it[0]++; it[0] }
                into.append("<span class=ao data-o=").append(id).append("></span>")
                OBJS.get()!!.append("<div class=ob data-o=").append(id).append(" data-rh=").append(a.relH).append(" data-rv=").append(a.relV)
                    .append(" data-ha=").append(a.hAlign ?: "").append(" data-va=").append(a.vAlign ?: "").append(" data-ho=").append(px(hOff)).append(" data-vo=").append(px(vOff))
                    .append(" data-w=").append(w).append(" data-h=").append(h).append(" data-b=").append(if (a.behind) 1 else 0).append(" data-z=").append(a.z)
                    .append(" style=\"width:").append(w).append("px\">").append(inner).append("</div>")
            } else {
                val al = when (a.hAlign) { "center" -> "center"; "right", "outside" -> "right"; else -> "left" }
                val top = if (a.relV == "paragraph" || a.relV == "line") px(vOff) else 0L
                pre.append("<div class=fo style=\"position:relative;height:0;z-index:").append(if (a.behind) -1 else 1).append("\"><div style=\"position:absolute;left:0;width:100%;top:")
                    .append(top).append("px;text-align:").append(al).append(";pointer-events:none")
                if (a.hAlign == null && px(hOff) > 0) pre.append(";padding-left:").append(minOf(px(hOff), 600L)).append("px;box-sizing:border-box")
                pre.append("\"><div style=\"display:inline-block;vertical-align:top;max-width:100%\">").append(inner).append("</div></div></div>")
            }
        } else if (a.wrap == "tb") {
            val al = a.hAlign
            val ml = if (al == "center") "auto" else if (al == "right" || al == "outside") "auto" else minOf(maxOf(px(a.hOff + dx), 0L), 400L).toString() + "px"
            val mr = if (al == "center") "auto" else "0"
            pre.append("<div class=fo style=\"clear:both;width:").append(w).append("px;margin:").append(mT).append("px ").append(mr).append(' ').append(mB).append("px ").append(ml).append("\">").append(inner).append("</div>")
        } else {
            val g = geo()
            val side = when {
                a.wrapText == "left" -> "right"; a.wrapText == "right" -> "left"
                a.hAlign == "right" || a.hAlign == "outside" -> "right"; a.hAlign != null -> "left"
                else -> if (a.hOff + dx + wE / 2.0 > (if (a.relH == "page") g[1] else g[0]) / 2.0) "right" else "left"
            }
            pre.append("<div class=fo style=\"float:").append(side).append(";margin:").append(mT).append("px ").append(mR).append("px ").append(mB).append("px ").append(mL).append("px;width:").append(w).append("px")
            if (rd != null) pre.append(";max-width:45%")
            pre.append("\">").append(inner).append("</div>")
        }
    }

    /** Part 6: drawing shape / text box as HTML. [tag] = "span" for an inline shape inside a paragraph (no text), "div" otherwise. */
    private fun shapeHtml(doc: DDoc, s: DShape, img: (DImage) -> String?, rd: DocxReading?, note: (String) -> Int, tag: String): String {
        val w = maxOf(1L, px(s.wEmu)); val h = px(s.hEmu)
        val dark = rd != null && rd.theme == DocxReading.DARK
        val st = StringBuilder()
        st.append(if (tag == "span") "display:inline-block;vertical-align:bottom;" else "display:block;").append("box-sizing:border-box;width:").append(w).append("px;max-width:100%;")
        if (s.geom == "line") {
            val lw = maxOf(1L, px(s.lineW)); val col = if (dark) "#ccc" else "#" + (s.line ?: "000000")
            val hh = maxOf(h, lw); val x2 = if (s.flipH) 0L else w; val y1 = if (s.flipV) hh else 0L; val y2 = if (s.flipV) 0L else hh
            val horiz = h < 2 && w >= 2
            return "<$tag style=\"" + st + "height:" + hh + "px\"><svg width=\"" + w + "\" height=\"" + hh + "\" style=\"display:block;overflow:visible\"><line x1=\"" + (if (s.flipH) w else 0L) + "\" y1=\"" + (if (horiz) hh / 2 else y1) +
                "\" x2=\"" + x2 + "\" y2=\"" + (if (horiz) hh / 2 else y2) + "\" stroke=\"" + col + "\" stroke-width=\"" + lw + "\"" +
                (if (s.dash != null && s.dash != "solid") " stroke-dasharray=\"" + (if (s.dash!!.contains("dot", true)) "2,3" else "6,4") + "\"" else "") + "/></svg></$tag>"
        }
        if (h > 0) st.append("height:").append(h).append("px;overflow:hidden;")
        if (s.fill != null) st.append("background:#").append(s.fill).append(';')
        if (s.line != null) st.append("border:").append(maxOf(1L, px(s.lineW))).append("px ").append(when { s.dash == null || s.dash == "solid" -> "solid"; s.dash!!.contains("dot", true) -> "dotted"; else -> "dashed" }).append(" #").append(s.line).append(';')
        when (s.geom) { "ellipse" -> st.append("border-radius:50%;"); "roundRect" -> st.append("border-radius:").append(maxOf(2L, minOf(w, maxOf(h, 1L)) / 6)).append("px;") }
        if (s.rot != 0.0) st.append("transform:rotate(").append(f2(s.rot)).append("deg);")
        if (s.blocks.isNotEmpty()) st.append("padding:").append(px(s.insT)).append("px ").append(px(s.insR)).append("px ").append(px(s.insB)).append("px ").append(px(s.insL)).append("px;")
        val t = StringBuilder()
        if (s.blocks.isNotEmpty()) blocks(doc, s.blocks, t, img, rd, note)
        return "<$tag class=sh style=\"" + st + "\">" + t + "</$tag>"
    }

    /** Part 8: run that does not exist in the current tracked-changes view. */
    private fun hidden(r: DRun): Boolean = (mark == MARK_FINAL && r.rev == 2) || (mark == MARK_ORIGINAL && r.rev == 1)

    private fun styled(doc: DDoc, r: DRun, html: String, rd: DocxReading?, sb: StringBuilder) {
        val dark = rd != null && rd.theme == DocxReading.DARK   // dark reading theme: author colours / highlights would be unreadable
        val st = StringBuilder()
        if (rd == null) r.font?.let { fn -> if (!fn.equals(doc.defaultFont, true)) st.append("font-family:").append(FontMap.stack(fn)).append(';') }   // reading mode keeps its own reading font
        if (r.bold) st.append("font-weight:bold;")
        if (r.italic) st.append("font-style:italic;")
        val rv = mark == MARK_SHOW && r.rev != 0   // Part 8: markup view: insertions underlined, deletions struck through, both in the author's colour
        val ul = r.underline || (rv && r.rev == 1); val sk = r.strike || (rv && r.rev == 2)
        if (ul || sk) st.append("text-decoration:").append(if (ul) "underline" else "").append(if (ul && sk) " " else "").append(if (sk) "line-through" else "").append(';')
        if (r.caps) st.append("text-transform:uppercase;")
        if (!dark) r.color?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) st.append("color:#").append(it).append(';') }
        if (!dark) r.highlight?.let { h -> HL[h]?.let { st.append("background:").append(it).append(';') } }
        if (rv) st.append("color:").append(revCol(r.revBy, dark)).append(';')
        if (mark == MARK_SHOW && r.cmt != null && r.highlight == null) st.append("background:").append(if (dark) "#5a4d1a" else "#fff1a8").append(';')   // Part 8: commented text
        if (r.size > 0 && r.size != doc.defaultSize) st.append("font-size:").append(r.size / 2.0).append("pt;")
        val href = r.link?.takeIf { it.startsWith("#") || it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("mailto:", true) || it.startsWith("tel:", true) }
        if (href != null) sb.append("<a href=\"").append(esc(href)).append("\">")
        val sc = r.script
        if (sc != 0) sb.append(if (sc > 0) "<sup>" else "<sub>")
        if (st.isNotEmpty()) sb.append("<span style=\"").append(st).append("\">").append(html).append("</span>") else sb.append(html)
        if (sc != 0) sb.append(if (sc > 0) "</sup>" else "</sub>")
        if (href != null) sb.append("</a>")
    }

    private fun table(doc: DDoc, t: DTable, sb: StringBuilder, img: (DImage) -> String?, rd: DocxReading?, note: (String) -> Int) {
        val total = t.grid.sum() / 15
        if (rd != null) sb.append("<div class=tw>")   // reading: wide tables scroll sideways inside their own box
        // Part 7: a table whose borders were resolved from its style / tblBorders draws them per cell (inline); legacy tables (.doc, .odt, .pptx, unknown style) keep the grid class; reading mode keeps the grid class for any visible border
        val grid = if (rd != null) (t.borders || t.anyBorder) else (t.borders && !t.resolved)
        sb.append("<table").append(if (grid) " class=b" else "")
        // fixed px widths overflowed the white page on phones (text ran onto the grey body): use % so the table always fits its container
        val tst = StringBuilder()
        if (total > 0 && printing) tst.append("width:").append(total).append("px;max-width:100%;")
        else if (total > 0 && rd == null) tst.append("width:100%;max-width:").append(total).append("px;")
        if (rd == null) {
            if (t.jc == "center") tst.append("margin-left:auto;margin-right:auto;") else if (t.jc == "right" || t.jc == "end") tst.append("margin-left:auto;")
            else if (t.indent > 0) tst.append("margin-left:").append(t.indent / 15).append("px;")
        }
        if (tst.isNotEmpty()) sb.append(" style=\"").append(tst).append('"')
        sb.append('>')
        if (t.grid.isNotEmpty()) { val gs = t.grid.sum().toDouble().coerceAtLeast(1.0); sb.append("<colgroup>"); for (w in t.grid) sb.append("<col style=\"width:").append(String.format(java.util.Locale.US, "%.2f", w * 100.0 / gs)).append("%\">"); sb.append("</colgroup>") }
        // grid start column of every cell, for vMerge row spans
        val starts = t.rows.map { r -> var c = 0; r.cells.map { val s = c; c += maxOf(1, it.span); s } }
        for ((ri, row) in t.rows.withIndex()) {
            sb.append("<tr")
            if (rd == null && row.height > 0) sb.append(" style=\"height:").append(row.height / 15).append("px\"")
            sb.append('>')
            for ((ci, cell) in row.cells.withIndex()) {
                if (cell.vMerge == 2) continue
                var rs = 1
                if (cell.vMerge == 1) {
                    var k = ri + 1
                    while (k < t.rows.size) {
                        val idx = starts[k].indexOf(starts[ri][ci])
                        if (idx >= 0 && t.rows[k].cells[idx].vMerge == 2) { rs++; k++ } else break
                    }
                }
                sb.append(if (row.header) "<th" else "<td")
                if (cell.span > 1) sb.append(" colspan=").append(cell.span)
                if (rs > 1) sb.append(" rowspan=").append(rs)
                val cs = StringBuilder()
                if (rd?.theme != DocxReading.DARK) cell.fill?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) cs.append("background:#").append(it).append(';') }
                if (rd == null) {
                    if (t.resolved) { borderCss(cell.bT, "top", cs); borderCss(cell.bL, "left", cs); borderCss(cell.bB, "bottom", cs); borderCss(cell.bR, "right", cs) }
                    val m = cell.mar; val tm = t.mar
                    for ((k, side) in listOf("top", "left", "bottom", "right").withIndex()) { val v = m?.get(k)?.takeIf { it >= 0 } ?: tm?.get(k)?.takeIf { it >= 0 }; if (v != null) cs.append("padding-").append(side).append(':').append(f2(v / 15.0)).append("px;") }
                    when (cell.vAlign) { "center" -> cs.append("vertical-align:middle;"); "bottom" -> cs.append("vertical-align:bottom;") }
                }
                if (cs.isNotEmpty()) sb.append(" style=\"").append(cs).append('"')
                sb.append('>')
                blocks(doc, cell.blocks, sb, img, rd, note)
                sb.append(if (row.header) "</th>" else "</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        if (rd != null) sb.append("</div>")
    }

    /** CSS appended after the page CSS for reading mode; !important beats the inline sizes / alignment / widths written per run, paragraph and table. */
    private fun readingCss(r: DocxReading): String {
        val bg = r.bg; val fg = r.fg; val link = r.link
        return "body{background:$bg;color:$fg}" +
            ".page{background:$bg;max-width:40em;margin:0 auto;padding:14px 16px 56px;font-size:${r.fontPx}px !important;line-height:1.6;font-family:'Noto Serif',Georgia,serif}" +
            ".page span{font-size:inherit !important}p{line-height:1.6;margin:0 0 .8em}" +
            "[style*='text-align:justify']{text-align:left !important}" +
            "a{color:$link}.pb{display:none}img{max-width:100% !important;height:auto !important}" +
            ".tw{overflow-x:auto;margin:0 0 .9em}table{width:auto !important;max-width:none !important;min-width:100%;table-layout:auto !important;margin:0}" +
            "col{width:auto !important}td,th{min-width:4.5em;padding:4px 6px}table.b td,table.b th{border:1px solid rgba(128,128,128,.55)}" +
            ".fn{border-color:rgba(128,128,128,.6)}.noimg{color:${r.muted}}.tr{background:${r.note};color:$fg}"
    }
}

/** Reading mode settings for [DocxHtml.render]: text size in CSS px and a colour theme (PAPER, SEPIA, DARK). */
class DocxReading(val fontPx: Int = 18, val theme: Int = PAPER) {
    val bg: String get() = when (theme) { SEPIA -> "#f4ecd8"; DARK -> "#121212"; else -> "#fbfaf7" }
    val fg: String get() = when (theme) { SEPIA -> "#3b2f1e"; DARK -> "#d9d9d9"; else -> "#1c1c1c" }
    val link: String get() = when (theme) { SEPIA -> "#8a4b08"; DARK -> "#8ab4f8"; else -> "#1a5fb4" }
    val muted: String get() = when (theme) { DARK -> "#999"; else -> "#777" }
    val note: String get() = when (theme) { DARK -> "#3a3320"; SEPIA -> "#e8dcb8"; else -> "#fff3cd" }
    companion object {
        const val PAPER = 0; const val SEPIA = 1; const val DARK = 2
        const val MIN = 12; const val MAX = 32; const val STEP = 2
        fun clampPx(v: Int) = v.coerceIn(MIN, MAX)
    }
}
