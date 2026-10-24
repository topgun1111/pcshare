package com.lanshare.app.core.office

/** DDoc -> self-contained HTML (Part 3a). Pure Kotlin, no android.*.
 *  [image] maps a DImage to a src (data: URI or file URL); null -> placeholder. */
object DocxHtml {
    private val HL = mapOf("yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff", "blue" to "#0000ff",
        "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000", "darkMagenta" to "#800080",
        "darkRed" to "#800000", "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000", "white" to "#ffffff")

    private const val CSS = "body{margin:0;background:#e8e8e8;color:#000}" +
        ".page{background:#fff;max-width:816px;margin:8px auto;padding:24px 28px;box-sizing:border-box;overflow-wrap:anywhere}" +
        "p{margin:0 0 .6em;white-space:pre-wrap;line-height:1.3}h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;line-height:1.2}" +
        "h1{font-size:1.9em}h2{font-size:1.5em}h3{font-size:1.25em}h4,h5,h6{font-size:1.05em}" +
        "table{border-collapse:collapse;margin:0 0 .8em;table-layout:fixed;max-width:100%;box-sizing:border-box}td,th{padding:3px 5px;vertical-align:top;overflow-wrap:anywhere}" +
        "table.b td,table.b th{border:1px solid #888}td>p:last-child{margin-bottom:0}img{max-width:100%;height:auto}" +
        ".li>.lb{display:inline-block;text-indent:0}.pb{border:0;border-top:1px dashed #aaa;margin:1em 0}" +
        ".hf{color:#666;font-size:.85em;border-bottom:1px solid #ddd;margin-bottom:1em}.hf.f{border:0;border-top:1px solid #ddd;margin:1em 0 0}" +
        ".fn{border-top:1px solid #aaa;margin-top:1.5em;padding-top:.5em;font-size:.85em}.noimg{color:#888;font-style:italic}" +
        "sup a,.fn a{text-decoration:none}.tr{background:#fff3cd;padding:4px 8px;font-size:.8em}" +
        "@media(max-width:700px){.page{padding:12px 10px;margin:0 auto}td,th{padding:2px 3px}}"

    /** Part 5: set while [renderPrint] runs, so the shared block renderers emit the page-layout variant (see [renderPrint]). */
    private val PRINT = ThreadLocal<Boolean>()
    private val printing get() = PRINT.get() == true
    private fun f2(v: Double) = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    /** [rd] != null -> phone reading layout (reflowed text, own font size and theme, no page chrome); null -> page-like layout as before. */
    /** [fontBase]: URL prefix the bundled fonts are served from (FontMap.FONT_URL in the viewer); null = no @font-face, system fonts only. */
    fun render(doc: DDoc, image: (DImage) -> String? = { null }, title: String = "", rd: DocxReading? = null, fontBase: String? = null): String {
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
        "sup a,.fn a{text-decoration:none}.tr{background:#fff3cd;padding:4px 8px;font-size:.8em}.hft{display:none}#stage{display:block}" +
        "html.js #stage{position:absolute;left:0;top:0;height:0;overflow:hidden;visibility:hidden}.fld{white-space:pre}"

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
    fun renderPrint(doc: DDoc, image: (DImage) -> String? = { null }, title: String = "", fontBase: String? = null): String {
        PRINT.set(true)
        try { return renderPrint0(doc, image, title, fontBase) } finally { PRINT.remove() }
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
        sb.append("</div><script id=meta type=\"application/json\">{\"f\":[")
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
        val st = StringBuilder()
        if (p.align != null) st.append("text-align:").append(if (p.align == "both") "justify" else p.align).append(';')
        val pr = printing
        val tag = if (p.heading > 0 && !pr) "h" + minOf(p.heading, 6) else "p"
        var cls = if (pr && p.heading > 0) "h" + minOf(p.heading, 6) else ""
        var hang = 0
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
        } else {
            if (p.indentLeft > 0) st.append("margin-left:").append(if (rd != null) minOf(p.indentLeft / 30, 40) else p.indentLeft / 15).append("px;")
            if (p.firstLine != 0 && rd == null) st.append("text-indent:").append(p.firstLine / 15).append("px;")
        }
        if (p.pageBreakBefore && !pr) sb.append("<hr class=pb>")
        val openAt = sb.length
        sb.append('<').append(tag)
        if (cls.isNotEmpty()) sb.append(" class=\"").append(cls).append('"')
        if (st.isNotEmpty()) sb.append(" style=\"").append(st).append('"')
        sb.append('>')
        val openTag = sb.substring(openAt).replace("break-before:column;", "")
        if (p.label != null) sb.append("<span class=lb style=\"min-width:").append(hang).append("px\">").append(esc(p.label!!)).append("</span>")
        if (p.runs.isEmpty() && p.label == null) sb.append("&nbsp;")
        var i = 0
        while (i < p.runs.size) {
            val r = p.runs[i]
            when (r.kind) {
                DRun.TEXT -> {
                    val txt = StringBuilder(esc(r.text)); var j = i + 1
                    while (j < p.runs.size && p.runs[j].kind == DRun.TEXT && p.runs[j].sameStyle(r)) { txt.append(esc(p.runs[j].text)); j++ }
                    styled(doc, r, txt.toString(), rd, sb); i = j; continue
                }
                DRun.FIELD -> styled(doc, r, if (pr && (r.field == "PAGE" || r.field == "NUMPAGES")) "<span class=fld data-f=\"" + r.field + "\">" + esc(r.text) + "</span>" else esc(r.text), rd, sb)   // Part 4: PAGE / NUMPAGES / DATE / TITLE, cached result
                DRun.TAB -> sb.append("<span style=\"display:inline-block;width:2em\"></span>")
                DRun.BREAK -> sb.append("<br>")
                DRun.PAGE -> if (pr) sb.append("</p><div class=cb></div>").append(openTag) else sb.append("<hr class=pb>")
                DRun.IMAGE -> r.image?.let { im ->
                    val src = img(im)
                    if (src == null) sb.append("<span class=noimg>[").append(esc(im.alt.ifEmpty { "image" })).append("]</span>")
                    else {
                        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"").append(esc(im.alt)).append('"')
                        if (im.widthEmu > 0) {
                            sb.append(" style=\"width:").append(im.widthEmu / 9525).append("px")
                            if (pr && im.heightEmu > 0) sb.append(";aspect-ratio:").append(im.widthEmu / 9525).append('/').append(maxOf(1L, im.heightEmu / 9525))   // reserves the height before the image decodes (pagination must not shift)
                            sb.append('"')
                        }
                        sb.append('>')
                    }
                }
                DRun.NOTE -> { val n = note(r.text); sb.append("<sup><a id=\"rf").append(esc(r.text)).append("\" href=\"#fn").append(esc(r.text)).append("\">").append(n).append("</a></sup>") }
            }
            i++
        }
        sb.append("</").append(tag).append('>')
    }

    private fun styled(doc: DDoc, r: DRun, html: String, rd: DocxReading?, sb: StringBuilder) {
        val dark = rd != null && rd.theme == DocxReading.DARK   // dark reading theme: author colours / highlights would be unreadable
        val st = StringBuilder()
        if (rd == null) r.font?.let { fn -> if (!fn.equals(doc.defaultFont, true)) st.append("font-family:").append(FontMap.stack(fn)).append(';') }   // reading mode keeps its own reading font
        if (r.bold) st.append("font-weight:bold;")
        if (r.italic) st.append("font-style:italic;")
        if (r.underline || r.strike) st.append("text-decoration:").append(if (r.underline) "underline" else "").append(if (r.underline && r.strike) " " else "").append(if (r.strike) "line-through" else "").append(';')
        if (r.caps) st.append("text-transform:uppercase;")
        if (!dark) r.color?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) st.append("color:#").append(it).append(';') }
        if (!dark) r.highlight?.let { h -> HL[h]?.let { st.append("background:").append(it).append(';') } }
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
        sb.append("<table").append(if (t.borders) " class=b" else "")
        // fixed px widths overflowed the white page on phones (text ran onto the grey body): use % so the table always fits its container
        if (total > 0 && printing) sb.append(" style=\"width:").append(total).append("px;max-width:100%\"")
        else if (total > 0 && rd == null) sb.append(" style=\"width:100%;max-width:").append(total).append("px\"")
        sb.append('>')
        if (t.grid.isNotEmpty()) { val gs = t.grid.sum().toDouble().coerceAtLeast(1.0); sb.append("<colgroup>"); for (w in t.grid) sb.append("<col style=\"width:").append(String.format(java.util.Locale.US, "%.2f", w * 100.0 / gs)).append("%\">"); sb.append("</colgroup>") }
        // grid start column of every cell, for vMerge row spans
        val starts = t.rows.map { r -> var c = 0; r.cells.map { val s = c; c += maxOf(1, it.span); s } }
        for ((ri, row) in t.rows.withIndex()) {
            sb.append("<tr>")
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
                if (rd?.theme != DocxReading.DARK) cell.fill?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) sb.append(" style=\"background:#").append(it).append('"') }
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
