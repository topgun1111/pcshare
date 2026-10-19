package com.lanshare.app.core.office

/** XSheet -> HTML table (Part 3b, first cut). Pure Kotlin.
 *  Uses only: sh.rows (row -> cells), sh.maxRow, sh.maxCol (0-based), sh.truncated, XCell.col/.text.
 *  Handles merges, col widths, hidden rows/cols, frozen panes (sticky rows/cols) and cell styles (bold/italic/underline/colour/fill/align/wrap) when [render] gets the reader's `styles`.
 *  Theme / indexed colours are only shown when the reader resolved them to #RRGGBB. */
object XlsxHtml {
    private const val CSS = "body{margin:0;font:13px Calibri,Carlito,Arial,sans-serif;color:#000;background:#fff}" +
        ".tabs{position:sticky;top:0;z-index:5;display:flex;overflow-x:auto;background:#f0f0f0;border-bottom:1px solid #bbb}" +
        ".tabs a{padding:8px 14px;white-space:nowrap;color:#333;text-decoration:none;border-right:1px solid #ccc}.tabs a.on{background:#fff;font-weight:bold;color:#107c41}" +
        ".w{overflow:auto}table{border-collapse:collapse}td,th{border:1px solid #d4d4d4;padding:2px 6px;white-space:pre;max-width:320px;overflow:hidden;text-overflow:ellipsis;box-sizing:border-box;height:22px}" +
        "th{background:#f3f3f3;color:#555;font-weight:normal;text-align:center}thead th{position:sticky;top:0;z-index:2}.rn{position:sticky;left:0;z-index:1;min-width:28px}thead th.rn{z-index:3}" +
        "td.n{text-align:right}.tr{background:#fff3cd;padding:4px 8px}.e{border:0;background:#fff}"

    private val NUM = Regex("^[\\s(+-]*[$€£₺]?[\\d.,]+\\s*[%)]?$")

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    /** [tabs]: (sheet index, name) of visible sheets; tab links use `lsoffice://sheet/<index>`. */
    private val HEX = Regex("^[0-9A-Fa-f]{6}$")
    /** "#RRGGBB" / "RRGGBB" / "FFRRGGBB" -> "#rrggbb"; null when it is not a plain colour. */
    private fun hex(c: String?): String? {
        var v = c?.trim()?.trimStart('#') ?: return null
        if (v.length == 8) v = v.substring(2)
        return if (HEX.matches(v)) "#" + v.lowercase() else null
    }

    /** [styles]: pass the reader's `styles` to show bold / italic / colours / fills / alignment; null = plain. */
    fun render(sh: XSheet, tabs: List<Pair<Int, String>>, active: Int, styles: XStyles? = null): String {
        val sb = StringBuilder(32 * 1024)
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1,minimum-scale=0.05,maximum-scale=10,user-scalable=yes\"><style>")
            .append(CSS).append("</style></head><body>")
        if (tabs.size > 1) {
            sb.append("<div class=tabs>")
            for ((i, n) in tabs) sb.append("<a href=\"lsoffice://sheet/").append(i).append('"').append(if (i == active) " class=on" else "").append('>').append(esc(n)).append("</a>")
            sb.append("</div>")
        }
        if (sh.truncated) sb.append("<div class=tr>Sheet truncated (too large) - showing the first part only.</div>")
        val cols = sh.maxCol + 1
        val rowsN = sh.maxRow + 1
        fun cw(c: Int) = ((sh.colWidths[c] ?: 8.43) * 7 + 5).toInt()
        val vis = (0 until cols).filter { it !in sh.hiddenCols }
        val leftOf = HashMap<Int, Int>(); var acc = 28
        for (c in vis) { leftOf[c] = acc; acc += cw(c) }
        val frR = maxOf(0, sh.frozenRows); val frC = maxOf(0, sh.frozenCols)
        /** sticky position for a cell inside the frozen rows / cols ("" elsewhere); rows are a fixed 22 px, the column-letter row too */
        fun sticky(r: Int, c: Int): String {
            val inR = r < frR; val inC = c < frC
            if (!inR && !inC) return ""
            val sb2 = StringBuilder("position:sticky;")
            if (inR) sb2.append("top:").append(22 + r * 22).append("px;")
            if (inC) sb2.append("left:").append(leftOf[c] ?: 28).append("px;")
            return sb2.append("z-index:").append(if (inR) 2 else 1).append(';').toString()
        }
        fun look(x: XCell?): String {
            if (styles == null || x == null) return ""
            val xf = styles.xfOrDefault(x.style)
            val sb2 = StringBuilder()
            val f = styles.fonts.getOrNull(xf.fontId)
            if (f != null) {
                if (f.bold) sb2.append("font-weight:bold;")
                if (f.italic) sb2.append("font-style:italic;")
                if (f.underline) sb2.append("text-decoration:underline;")
                hex(f.color)?.let { if (it != "#000000") sb2.append("color:").append(it).append(';') }
            }
            hex(styles.fills.getOrNull(xf.fillId))?.let { sb2.append("background:").append(it).append(';') }
            when (xf.halign) { "left", "center", "right" -> sb2.append("text-align:").append(xf.halign).append(';'); else -> {} }
            if (xf.wrap) sb2.append("white-space:pre-wrap;")
            return sb2.toString()
        }
        val heads = HashMap<Long, XMerge>(); val covered = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100000L + c
        for (m in sh.merges) {
            heads[key(m.r1, m.c1)] = m
            for (r in m.r1..minOf(m.r2, sh.maxRow)) for (c in m.c1..minOf(m.c2, sh.maxCol)) if (r != m.r1 || c != m.c1) covered.add(key(r, c))
        }
        sb.append("<div class=w><table style=\"table-layout:fixed;width:").append(28 + vis.sumOf { cw(it) }).append("px\"><colgroup><col style=\"width:28px\">")
        for (c in vis) sb.append("<col style=\"width:").append(cw(c)).append("px\">")
        sb.append("</colgroup><thead><tr><th class=rn></th>")
        for (c in vis) sb.append("<th>").append(XRef.colName(c)).append("</th>")
        sb.append("</tr></thead><tbody>")
        for (r in 0 until rowsN) {
            if (r in sh.hiddenRows) continue
            val byCol = HashMap<Int, XCell>()
            sh.rows[r]?.forEach { byCol[it.col] = it }
            sb.append("<tr><th class=rn").append(if (r < frR) " style=\"top:" + (22 + r * 22) + "px;z-index:3\"" else "").append('>').append(r + 1).append("</th>")
            var i = 0
            while (i < vis.size) {
                val c = vis[i]; val k = key(r, c); val x = byCol[c]
                if (k in covered) { i++; continue }
                val m = heads[k]
                if (m == null && (x == null || x.text.isEmpty())) {
                    var j = i + 1
                    while (c >= frC && j < vis.size) { val cj = vis[j]; val xj = byCol[cj]; if (key(r, cj) in covered || heads.containsKey(key(r, cj)) || !(xj == null || xj.text.isEmpty())) break; j++ }
                    val stk = sticky(r, c)
                    sb.append("<td class=e").append(if (j - i > 1) " colspan=" + (j - i) else "")
                    if (stk.isNotEmpty()) sb.append(" style=\"").append(stk).append('"')
                    sb.append("></td>")
                    i = j; continue
                }
                val cs = if (m != null) vis.count { it in m.c1..m.c2 } else 1
                val rs = if (m != null) (m.r1..minOf(m.r2, sh.maxRow)).count { it !in sh.hiddenRows } else 1
                val t = x?.text ?: ""
                sb.append("<td").append(if (NUM.matches(t)) " class=n" else "")
                if (cs > 1) sb.append(" colspan=").append(cs)
                if (rs > 1) sb.append(" rowspan=").append(rs)
                val lk = look(x); val stk = sticky(r, c)
                if (lk.isNotEmpty() || stk.isNotEmpty()) {
                    sb.append(" style=\"").append(lk).append(stk)
                    if (stk.isNotEmpty() && !lk.contains("background:")) sb.append("background:#fff;")   // frozen cells must hide what scrolls under them
                    sb.append('"')
                }
                sb.append('>').append(esc(t)).append("</td>")
                i++
            }
            sb.append("</tr>")
        }
        return sb.append("</tbody></table></div></body></html>").toString()
    }
}
