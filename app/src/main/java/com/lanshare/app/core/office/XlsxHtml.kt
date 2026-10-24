package com.lanshare.app.core.office

/** XSheet -> HTML table (Part 3b, first cut). Pure Kotlin.
 *  Uses only: sh.rows (row -> cells), sh.maxRow, sh.maxCol (0-based), sh.truncated, XCell.col/.text.
 *  Handles merges, col widths, hidden rows/cols, frozen panes (sticky rows/cols) and cell styles (bold/italic/underline/colour/fill/align/wrap) when [render] gets the reader's `styles`.
 *  Theme / indexed colours are only shown when the reader resolved them to #RRGGBB. */
object XlsxHtml {
    private const val CSS = "body{margin:0;font-size:13px;color:#000;background:#fff}" +
        ".tabs{position:sticky;top:0;z-index:5;display:flex;overflow-x:auto;background:#f0f0f0;border-bottom:1px solid #bbb}" +
        ".tabs a{padding:8px 14px;white-space:nowrap;color:#333;text-decoration:none;border-right:1px solid #ccc}.tabs a.on{background:#fff;font-weight:bold;color:#107c41}" +
        ".w{overflow:auto;position:relative}.ch{position:absolute;z-index:1}table{border-collapse:collapse}td,th{padding:2px 6px;white-space:pre;max-width:320px;overflow:hidden;text-overflow:ellipsis;box-sizing:border-box;height:22px}" +
        "td{box-shadow:inset -1px -1px 0 #d4d4d4}th{border:1px solid #d4d4d4;background:#f3f3f3;color:#555;font-weight:normal;text-align:center}thead th{position:sticky;top:0;z-index:2}.rn{position:sticky;left:0;z-index:1;min-width:28px}thead th.rn{z-index:3}" +
        "td.n{text-align:right}.tr{background:#fff3cd;padding:4px 8px}.e{border:0;background:#fff;box-shadow:none}.ng td{box-shadow:none}" +
        "td.cm{position:relative}a.nt{position:absolute;top:0;right:0;width:14px;height:14px;background:linear-gradient(225deg,#d00 50%,transparent 50%)}" +
        ".notes{margin:12px 8px;font-size:12px;color:#333}.notes div{margin:4px 0;padding:4px 6px;border-left:3px solid #d00;background:#fafafa;white-space:pre-wrap}.notes b{color:#555}"

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

    /** Part 9: one border side -> `border-top:1px solid #rrggbb;` (Excel line styles mapped to the closest CSS line). */
    private fun side(name: String, b: XBSide?, sb: StringBuilder) {
        if (b == null) return
        val w: Int; val st: String
        when (b.style) {
            "thin" -> { w = 1; st = "solid" }
            "hair", "dotted" -> { w = 1; st = "dotted" }
            "dashed", "dashDot", "dashDotDot", "slantDashDot" -> { w = 1; st = "dashed" }
            "medium" -> { w = 2; st = "solid" }
            "mediumDashed", "mediumDashDot", "mediumDashDotDot" -> { w = 2; st = "dashed" }
            "thick" -> { w = 3; st = "solid" }
            "double" -> { w = 3; st = "double" }
            else -> { w = 1; st = "solid" }
        }
        sb.append("border-").append(name).append(':').append(w).append("px ").append(st).append(' ').append(hex(b.color) ?: "#000000").append(';')
    }

    /** Part 9: width in pixels of the digit "0" in the workbook's default font (Excel measures column widths in it). Calibri / Carlito 11 pt = 7 px. */
    internal fun digitWidth(f: XFont?): Int {
        if (f == null) return 7
        val n = (f.name ?: "Calibri").lowercase(); val sz = if (f.size > 0) f.size else 11.0
        return when {
            n.contains("calibri") || n.contains("carlito") -> Math.round(sz * 7.0 / 11.0).toInt()
            n.contains("arial") || n.contains("liberation sans") || n.contains("helvetica") -> if (sz <= 10.5) 7 else Math.round(sz * 0.7).toInt().coerceAtLeast(7)
            else -> Math.round(sz * 7.0 / 11.0).toInt()
        }.coerceIn(5, 12)
    }

    /** [styles]: pass the reader's `styles` to show bold / italic / colours / fills / alignment; null = plain. */
    /** [fontBase]: URL prefix the bundled fonts are served from (FontMap.FONT_URL in the viewer); null = no @font-face, system fonts only. */
    /** [grid]: gridlines on / off; null = as the sheet says (`showGridLines`). */
    fun render(sh: XSheet, tabs: List<Pair<Int, String>>, active: Int, styles: XStyles? = null, fontBase: String? = null, grid: Boolean? = null): String {
        val defFont = styles?.fonts?.firstOrNull()?.name
        val sb = StringBuilder(32 * 1024)
        sb.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1,minimum-scale=0.05,maximum-scale=10,user-scalable=yes\"><style>")
            .append(if (fontBase != null) FontMap.fontFaceCss(fontBase) else "").append(CSS).append("body{font-family:").append(FontMap.stack(defFont)).append("}</style></head><body>")
        if (tabs.size > 1) {
            sb.append("<div class=tabs>")
            for ((i, n) in tabs) sb.append("<a href=\"lsoffice://sheet/").append(i).append('"').append(if (i == active) " class=on" else "").append('>').append(esc(n)).append("</a>")
            sb.append("</div>")
        }
        if (sh.truncated) sb.append("<div class=tr>Sheet truncated (too large) - showing the first part only.</div>")
        val cols = sh.maxCol + 1
        val rowsN = sh.maxRow + 1
        val mdw = digitWidth(styles?.fonts?.firstOrNull())
        // xlsx: the stored width already contains the cell padding (pixels = width x digit width); xls / ods keep the old "characters x 7 + 5"
        fun cw(c: Int): Int = if (sh.fileWidths) Math.round((sh.colWidths[c] ?: sh.defaultColWidth ?: 64.0 / mdw) * mdw).toInt().coerceAtLeast(2)
            else ((sh.colWidths[c] ?: 8.43) * 7 + 5).toInt()
        val gridOn = grid ?: sh.showGrid
        val cf = XlsxCf.evaluate(sh, styles)
        val notes = HashMap<Long, XNote>(); for (n in sh.notes) notes[XlsxCf.key(n.row, n.col)] = n
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
        fun look(x: XCell?, o: CfOut?): String {
            if (styles == null || x == null) return ""
            val xf = styles.xfOrDefault(x.style)
            val sb2 = StringBuilder()
            val f = styles.fonts.getOrNull(xf.fontId)
            var bold = f?.bold == true; var ital = f?.italic == true; var und = f?.underline == true; val strike = o?.strike == true
            if (o != null) { o.bold?.let { bold = it }; o.italic?.let { ital = it }; o.underline?.let { und = it } }
            if (f != null) f.name?.let { fn -> if (!fn.equals(defFont, true)) sb2.append("font-family:").append(FontMap.stack(fn)).append(';') }
            if (bold) sb2.append("font-weight:bold;")
            if (ital) sb2.append("font-style:italic;")
            if (und || strike) sb2.append("text-decoration:").append(if (und && strike) "underline line-through" else if (und) "underline" else "line-through").append(';')
            (hex(o?.color) ?: hex(f?.color))?.let { if (it != "#000000") sb2.append("color:").append(it).append(';') }
            (hex(o?.fill) ?: hex(styles.fills.getOrNull(xf.fillId)))?.let { sb2.append("background:").append(it).append(';') }
            if (o != null && o.bar >= 0) {
                val p = Math.round(o.bar * 1000) / 10.0
                sb2.append("background-image:linear-gradient(90deg,").append(hex(o.barColor) ?: "#638ec6").append(' ').append(p).append("%,transparent ").append(p).append("%);")
            }
            when (xf.halign) { "left", "center", "right" -> sb2.append("text-align:").append(xf.halign).append(';'); else -> {} }
            if (xf.wrap) sb2.append("white-space:pre-wrap;")
            styles.borders.getOrNull(xf.borderId)?.let { b ->
                side("top", b.top, sb2); side("right", b.right, sb2); side("bottom", b.bottom, sb2); side("left", b.left, sb2)
            }
            return sb2.toString()
        }
        /** a cell that is drawn although it has no text: it has its own fill, a border or a conditional-format effect */
        fun boxed(x: XCell?, o: CfOut?): Boolean {
            if (o != null && (o.fill != null || o.bar >= 0)) return true
            if (styles == null || x == null) return false
            val xf = styles.xfOrDefault(x.style)
            return styles.fills.getOrNull(xf.fillId) != null || styles.borders.getOrNull(xf.borderId)?.any == true
        }
        val heads = HashMap<Long, XMerge>(); val covered = HashSet<Long>()
        fun key(r: Int, c: Int) = r.toLong() * 100000L + c
        for (m in sh.merges) {
            heads[key(m.r1, m.c1)] = m
            for (r in m.r1..minOf(m.r2, sh.maxRow)) for (c in m.c1..minOf(m.c2, sh.maxCol)) if (r != m.r1 || c != m.c1) covered.add(key(r, c))
        }
        // Part 10: chart boxes, absolutely placed over the grid (same coordinates as the table: 28 px row-number column, 22 px header row, 22 px rows).
        // Computed BEFORE the table so the scroll box can be made tall enough for charts that extend below the last row.
        val boxes = ArrayList<String>(); var chartBottom = 0
        for (fr in sh.charts) {
            try {
                val a = fr.anchor
                fun colX(c: Int): Double { var x = 28.0; for (k in 0 until c) if (k !in sh.hiddenCols) x += cw(k); return x }
                fun rowY(r: Int): Double { var hid = 0; for (k in sh.hiddenRows) if (k < r) hid++; return 22.0 + (r - hid) * 22.0 }
                val x: Double; val y: Double; val wd: Double; val ht: Double
                when (a.kind) {
                    0 -> { x = colX(a.c1) + a.x1 / 9525.0; y = rowY(a.r1) + a.y1 / 9525.0; wd = colX(a.c2) + a.x2 / 9525.0 - x; ht = rowY(a.r2) + a.y2 / 9525.0 - y }
                    1 -> { x = colX(a.c1) + a.x1 / 9525.0; y = rowY(a.r1) + a.y1 / 9525.0; wd = a.cx / 9525.0; ht = a.cy / 9525.0 }
                    else -> { x = 28.0 + a.x1 / 9525.0; y = 22.0 + a.y1 / 9525.0; wd = a.cx / 9525.0; ht = a.cy / 9525.0 }
                }
                val w = Math.round(wd).toInt().coerceIn(60, 4000); val h = Math.round(ht).toInt().coerceIn(60, 4000)
                val svg = ChartSvg.render(fr.chart, w, h)   // rendered first: a failure must not leave a half-written <div>
                boxes.add("<div class=ch style=\"left:" + Math.round(x) + "px;top:" + Math.round(y) + "px\">" + svg + "</div>")
                chartBottom = Math.max(chartBottom, Math.round(y).toInt() + h + 8)
            } catch (_: Throwable) { }
        }
        sb.append("<div class=w").append(if (chartBottom > 0) " style=\"min-height:" + chartBottom + "px\"" else "").append("><table").append(if (gridOn) "" else " class=ng").append(" style=\"table-layout:fixed;width:").append(28 + vis.sumOf { cw(it) }).append("px\"><colgroup><col style=\"width:28px\">")
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
                val o = cf[k]
                if (m == null && (x == null || x.text.isEmpty()) && !boxed(x, o)) {
                    var j = i + 1
                    while (c >= frC && j < vis.size) { val cj = vis[j]; val xj = byCol[cj]; if (key(r, cj) in covered || heads.containsKey(key(r, cj)) || !((xj == null || xj.text.isEmpty()) && !boxed(xj, cf[key(r, cj)]))) break; j++ }
                    val stk = sticky(r, c)
                    sb.append("<td class=e").append(if (j - i > 1) " colspan=" + (j - i) else "")
                    if (stk.isNotEmpty()) sb.append(" style=\"").append(stk).append('"')
                    sb.append("></td>")
                    i = j; continue
                }
                val cs = if (m != null) vis.count { it in m.c1..m.c2 } else 1
                val rs = if (m != null) (m.r1..minOf(m.r2, sh.maxRow)).count { it !in sh.hiddenRows } else 1
                val t = x?.text ?: ""
                val nt = notes[k]
                sb.append("<td").append(if (NUM.matches(t)) (if (nt != null) " class=\"n cm\"" else " class=n") else if (nt != null) " class=cm" else "")
                if (cs > 1) sb.append(" colspan=").append(cs)
                if (rs > 1) sb.append(" rowspan=").append(rs)
                val lk = look(x, o); val stk = sticky(r, c)
                if (lk.isNotEmpty() || stk.isNotEmpty()) {
                    sb.append(" style=\"").append(lk).append(stk)
                    if (stk.isNotEmpty() && !lk.contains("background:")) sb.append("background:#fff;")   // frozen cells must hide what scrolls under them
                    sb.append('"')
                }
                sb.append('>').append(esc(t))
                if (nt != null) sb.append("<a class=nt href=\"lsoffice://note/").append(nt.ref).append("\"></a>")
                sb.append("</td>")
                i++
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table>")
        for (bx in boxes) sb.append(bx)
        sb.append("</div>")
        if (sh.notes.isNotEmpty()) {
            sb.append("<div class=notes><b>Notes (").append(sh.notes.size).append(")</b>")
            for (n in sh.notes) sb.append("<div id=\"n_").append(n.ref).append("\"><b>").append(n.ref).append(if (n.author.isNotEmpty()) " \u00b7 " + esc(n.author) else "").append("</b>\n").append(esc(n.text)).append("</div>")
            sb.append("</div>")
        }
        return sb.append("</body></html>").toString()
    }
}
