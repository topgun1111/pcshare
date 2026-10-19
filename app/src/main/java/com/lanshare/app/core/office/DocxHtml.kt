package com.lanshare.app.core.office

/** DDoc -> self-contained HTML (Part 3a). Pure Kotlin, no android.*.
 *  [image] maps a DImage to a src (data: URI or file URL); null -> placeholder. */
object DocxHtml {
    private val HL = mapOf("yellow" to "#ffff00", "green" to "#00ff00", "cyan" to "#00ffff", "magenta" to "#ff00ff", "blue" to "#0000ff",
        "red" to "#ff0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000", "darkMagenta" to "#800080",
        "darkRed" to "#800000", "darkYellow" to "#808000", "darkGray" to "#808080", "lightGray" to "#c0c0c0", "black" to "#000000", "white" to "#ffffff")

    private const val CSS = "body{margin:0;background:#e8e8e8;font-family:Calibri,Carlito,Arial,sans-serif;color:#000}" +
        ".page{background:#fff;max-width:816px;margin:8px auto;padding:24px 28px;box-sizing:border-box;overflow-wrap:anywhere}" +
        "p{margin:0 0 .6em;white-space:pre-wrap;line-height:1.3}h1,h2,h3,h4,h5,h6{margin:.8em 0 .4em;line-height:1.2}" +
        "h1{font-size:1.9em}h2{font-size:1.5em}h3{font-size:1.25em}h4,h5,h6{font-size:1.05em}" +
        "table{border-collapse:collapse;margin:0 0 .8em;table-layout:fixed;max-width:100%}td,th{padding:3px 5px;vertical-align:top;overflow-wrap:anywhere}" +
        "table.b td,table.b th{border:1px solid #888}td>p:last-child{margin-bottom:0}img{max-width:100%;height:auto}" +
        ".li>.lb{display:inline-block;text-indent:0}.pb{border:0;border-top:1px dashed #aaa;margin:1em 0}" +
        ".hf{color:#666;font-size:.85em;border-bottom:1px solid #ddd;margin-bottom:1em}.hf.f{border:0;border-top:1px solid #ddd;margin:1em 0 0}" +
        ".fn{border-top:1px solid #aaa;margin-top:1.5em;padding-top:.5em;font-size:.85em}.noimg{color:#888;font-style:italic}" +
        "sup a,.fn a{text-decoration:none}.tr{background:#fff3cd;padding:4px 8px;font-size:.8em}"

    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    fun render(doc: DDoc, image: (DImage) -> String? = { null }, title: String = ""): String {
        val notes = LinkedHashMap<String, Int>()
        fun noteNo(id: String) = notes.getOrPut(id) { notes.size + 1 }
        val sb = StringBuilder(16 * 1024)
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>")
            .append(esc(title)).append("</title><style>").append(CSS)
        if (doc.defaultSize > 0) sb.append(".page{font-size:").append(doc.defaultSize / 2.0).append("pt}")
        sb.append("</style></head><body><div class=page>")
        if (doc.truncated) sb.append("<div class=tr>Document truncated (too long).</div>")
        if (doc.header.isNotEmpty()) { sb.append("<div class=hf>"); blocks(doc, doc.header, sb, image, ::noteNo); sb.append("</div>") }
        blocks(doc, doc.body, sb, image, ::noteNo)
        if (notes.isNotEmpty()) {
            sb.append("<div class=fn>")
            for ((id, n) in notes) {
                val fb = doc.footnotes[id] ?: doc.endnotes[id] ?: continue
                sb.append("<div id=\"fn").append(esc(id)).append("\"><a href=\"#rf").append(esc(id)).append("\">").append(n).append(".</a> ")
                val t = StringBuilder(); blocks(doc, fb, t, image, ::noteNo)
                sb.append(t.toString().replace("<p>", "<span>").replace("</p>", "</span> ")).append("</div>")
            }
            sb.append("</div>")
        }
        if (doc.footer.isNotEmpty()) { sb.append("<div class=\"hf f\">"); blocks(doc, doc.footer, sb, image, ::noteNo); sb.append("</div>") }
        return sb.append("</div></body></html>").toString()
    }

    private fun blocks(doc: DDoc, bs: List<DBlock>, sb: StringBuilder, img: (DImage) -> String?, note: (String) -> Int) {
        for (b in bs) when (b) {
            is DPara -> para(doc, b, sb, img, note)
            is DTable -> table(doc, b, sb, img, note)
        }
    }

    private fun para(doc: DDoc, p: DPara, sb: StringBuilder, img: (DImage) -> String?, note: (String) -> Int) {
        val st = StringBuilder()
        if (p.align != null) st.append("text-align:").append(if (p.align == "both") "justify" else p.align).append(';')
        val tag = if (p.heading > 0) "h" + minOf(p.heading, 6) else "p"
        var cls = ""
        var hang = 0
        if (p.label != null) {
            cls = "li"
            val left = if (p.indentLeft > 0) p.indentLeft / 15 else (p.level + 1) * 24
            hang = if (p.firstLine < 0) -p.firstLine / 15 else 24
            st.append("margin-left:").append(left).append("px;text-indent:-").append(hang).append("px;")
        } else {
            if (p.indentLeft > 0) st.append("margin-left:").append(p.indentLeft / 15).append("px;")
            if (p.firstLine != 0) st.append("text-indent:").append(p.firstLine / 15).append("px;")
        }
        if (p.pageBreakBefore) sb.append("<hr class=pb>")
        sb.append('<').append(tag)
        if (cls.isNotEmpty()) sb.append(" class=").append(cls)
        if (st.isNotEmpty()) sb.append(" style=\"").append(st).append('"')
        sb.append('>')
        if (p.label != null) sb.append("<span class=lb style=\"min-width:").append(hang).append("px\">").append(esc(p.label!!)).append("</span>")
        if (p.runs.isEmpty() && p.label == null) sb.append("&nbsp;")
        var i = 0
        while (i < p.runs.size) {
            val r = p.runs[i]
            when (r.kind) {
                DRun.TEXT -> {
                    val txt = StringBuilder(esc(r.text)); var j = i + 1
                    while (j < p.runs.size && p.runs[j].kind == DRun.TEXT && p.runs[j].sameStyle(r)) { txt.append(esc(p.runs[j].text)); j++ }
                    styled(doc, r, txt.toString(), sb); i = j; continue
                }
                DRun.TAB -> sb.append("<span style=\"display:inline-block;width:2em\"></span>")
                DRun.BREAK -> sb.append("<br>")
                DRun.PAGE -> sb.append("<hr class=pb>")
                DRun.IMAGE -> r.image?.let { im ->
                    val src = img(im)
                    if (src == null) sb.append("<span class=noimg>[").append(esc(im.alt.ifEmpty { "image" })).append("]</span>")
                    else {
                        sb.append("<img src=\"").append(esc(src)).append("\" alt=\"").append(esc(im.alt)).append('"')
                        if (im.widthEmu > 0) sb.append(" style=\"width:").append(im.widthEmu / 9525).append("px\"")
                        sb.append('>')
                    }
                }
                DRun.NOTE -> { val n = note(r.text); sb.append("<sup><a id=\"rf").append(esc(r.text)).append("\" href=\"#fn").append(esc(r.text)).append("\">").append(n).append("</a></sup>") }
            }
            i++
        }
        sb.append("</").append(tag).append('>')
    }

    private fun styled(doc: DDoc, r: DRun, html: String, sb: StringBuilder) {
        val st = StringBuilder()
        if (r.bold) st.append("font-weight:bold;")
        if (r.italic) st.append("font-style:italic;")
        if (r.underline || r.strike) st.append("text-decoration:").append(if (r.underline) "underline" else "").append(if (r.underline && r.strike) " " else "").append(if (r.strike) "line-through" else "").append(';')
        if (r.caps) st.append("text-transform:uppercase;")
        r.color?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) st.append("color:#").append(it).append(';') }
        r.highlight?.let { h -> HL[h]?.let { st.append("background:").append(it).append(';') } }
        if (r.size > 0 && r.size != doc.defaultSize) st.append("font-size:").append(r.size / 2.0).append("pt;")
        val href = r.link?.takeIf { it.startsWith("#") || it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("mailto:", true) || it.startsWith("tel:", true) }
        if (href != null) sb.append("<a href=\"").append(esc(href)).append("\">")
        val sc = r.script
        if (sc != 0) sb.append(if (sc > 0) "<sup>" else "<sub>")
        if (st.isNotEmpty()) sb.append("<span style=\"").append(st).append("\">").append(html).append("</span>") else sb.append(html)
        if (sc != 0) sb.append(if (sc > 0) "</sup>" else "</sub>")
        if (href != null) sb.append("</a>")
    }

    private fun table(doc: DDoc, t: DTable, sb: StringBuilder, img: (DImage) -> String?, note: (String) -> Int) {
        val total = t.grid.sum() / 15
        sb.append("<table").append(if (t.borders) " class=b" else "")
        if (total > 0) sb.append(" style=\"width:").append(total).append("px\"")
        sb.append('>')
        if (t.grid.isNotEmpty()) { sb.append("<colgroup>"); for (w in t.grid) sb.append("<col style=\"width:").append(w / 15).append("px\">"); sb.append("</colgroup>") }
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
                cell.fill?.let { if (Regex("[0-9A-Fa-f]{6}").matches(it)) sb.append(" style=\"background:#").append(it).append('"') }
                sb.append('>')
                blocks(doc, cell.blocks, sb, img, note)
                sb.append(if (row.header) "</th>" else "</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
    }
}
