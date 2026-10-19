package com.lanshare.app.core.office

/** XSheet -> HTML table (Part 3b, first cut). Pure Kotlin.
 *  Uses only: sh.rows (row -> cells), sh.maxRow, sh.maxCol (0-based), sh.truncated, XCell.col/.text.
 *  Handles merges, col widths, hidden rows/cols. TODO: frozen panes (frozenRows/Cols), cell styles/theme colours. */
object XlsxHtml {
    private const val CSS = "body{margin:0;font:13px Calibri,Carlito,Arial,sans-serif;color:#000;background:#fff}" +
        ".tabs{position:sticky;top:0;z-index:5;display:flex;overflow-x:auto;background:#f0f0f0;border-bottom:1px solid #bbb}" +
        ".tabs a{padding:8px 14px;white-space:nowrap;color:#333;text-decoration:none;border-right:1px solid #ccc}.tabs a.on{background:#fff;font-weight:bold;color:#107c41}" +
        ".w{overflow:auto}table{border-collapse:collapse}td,th{border:1px solid #d4d4d4;padding:2px 6px;white-space:pre;max-width:320px;overflow:hidden;text-overflow:ellipsis;height:20px}" +
        "th{background:#f3f3f3;color:#555;font-weight:normal;text-align:center}thead th{position:sticky;top:0;z-index:2}.rn{position:sticky;left:0;z-index:1;min-width:28px}thead th.rn{z-index:3}" +
        "td.n{text-align:right}.tr{background:#fff3cd;padding:4px 8px}.e{border:0;background:#fff}"

    private val NUM = Regex("^[\\s(+-]*[$€£₺]?[\\d.,]+\\s*[%)]?$")

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> sb.append(c) }
        return sb.toString()
    }

    /** [tabs]: (sheet index, name) of visible sheets; tab links use `lsoffice://sheet/<index>`. */
    fun render(sh: XSheet, tabs: List<Pair<Int, String>>, active: Int): String {
        val sb = StringBuilder(32 * 1024)
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>")
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
            sb.append("<tr><th class=rn>").append(r + 1).append("</th>")
            var i = 0
            while (i < vis.size) {
                val c = vis[i]; val k = key(r, c); val x = byCol[c]
                if (k in covered) { i++; continue }
                val m = heads[k]
                if (m == null && (x == null || x.text.isEmpty())) {
                    var j = i + 1
                    while (j < vis.size) { val cj = vis[j]; val xj = byCol[cj]; if (key(r, cj) in covered || heads.containsKey(key(r, cj)) || !(xj == null || xj.text.isEmpty())) break; j++ }
                    sb.append("<td class=e").append(if (j - i > 1) " colspan=" + (j - i) else "").append("></td>")
                    i = j; continue
                }
                val cs = if (m != null) vis.count { it in m.c1..m.c2 } else 1
                val rs = if (m != null) (m.r1..minOf(m.r2, sh.maxRow)).count { it !in sh.hiddenRows } else 1
                val t = x?.text ?: ""
                sb.append("<td").append(if (NUM.matches(t)) " class=n" else "")
                if (cs > 1) sb.append(" colspan=").append(cs)
                if (rs > 1) sb.append(" rowspan=").append(rs)
                sb.append('>').append(esc(t)).append("</td>")
                i++
            }
            sb.append("</tr>")
        }
        return sb.append("</tbody></table></div></body></html>").toString()
    }
}
