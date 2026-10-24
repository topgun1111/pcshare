package com.lanshare.app.core.office

/**
 * Part 11: pie / doughnut, area (standard / stacked / 100 % stacked) and scatter charts -> inline SVG (no JS, no android.*).
 * [ChartSvg.render] calls [render] first; it returns null when the chart has column / bar / line plots (those stay with ChartSvg, which then
 * notes the other kinds as "not drawn") or nothing drawable. One chart shows ONE of the three families: pie / doughnut, else area, else scatter.
 */
object ChartSvg2 {
    private const val TXT = ChartSvg.TXT
    private const val GRID = ChartSvg.GRID
    private const val FS = ChartSvg.FS
    private const val PAD = ChartSvg.PAD

    private fun f(d: Double) = ChartSvg.f(d)
    private fun esc(s: String) = ChartSvg.esc(s)
    private fun tw(s: String, px: Double = FS) = ChartSvg.tw(s, px)
    private fun cut(s: String, maxPx: Double, px: Double = FS) = ChartSvg.cut(s, maxPx, px)

    private class Ent(val label: String, val color: String, val line: Boolean, val marker: String?)
    private class Lay(val top: Double, val bottom: Double, val left: Double, val right: Double, val legW: Double, val rows: List<List<Int>>, val ew: List<Double>)

    fun render(c: XChart, w: Int, h: Int): String? {
        val usable = c.plots.filter { !it.secondary && it.series.isNotEmpty() }
        if (usable.any { it.type == "bar" || it.type == "line" }) return null
        val pies = usable.filter { it.type == "pie" || it.type == "doughnut" }
        val areas = usable.filter { it.type == "area" }
        val scat = usable.filter { it.type == "scatter" }
        val family = if (pies.isNotEmpty()) 1 else if (areas.isNotEmpty()) 2 else if (scat.isNotEmpty()) 3 else return null
        val shown = when (family) { 1 -> setOf("pie", "doughnut"); 2 -> setOf("area"); else -> setOf("scatter") }
        val skipped = ArrayList<String>()
        for (k in c.kinds) if (k !in shown && k !in skipped) skipped.add(k)
        if (c.plots.any { it.secondary && it.series.isNotEmpty() }) skipped.add("secondary axis")

        val W = w.toDouble(); val H = h.toDouble()
        val sb = StringBuilder(8 * 1024)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h).append("\" viewBox=\"0 0 ").append(w).append(' ').append(h)
            .append("\" style=\"display:block;font-family:inherit\"><rect x=\"0.5\" y=\"0.5\" width=\"").append(w - 1).append("\" height=\"").append(h - 1).append("\" fill=\"#fff\" stroke=\"").append(GRID).append("\"/>")
            .append("<g font-size=\"").append(f(FS)).append("\" fill=\"").append(TXT).append("\">")
        var titleH = 0.0
        c.title?.let { t ->
            val fs = if (tw(t, 18.0) > W - 2 * PAD) Math.max(11.0, (W - 2 * PAD) / (t.length * 0.55)) else 18.0
            sb.append("<text x=\"").append(f(W / 2)).append("\" y=\"").append(f(PAD + 18)).append("\" text-anchor=\"middle\" font-size=\"").append(f(fs)).append("\">").append(esc(cut(t, W - 2 * PAD, fs))).append("</text>")
            titleH = 34.0
        }
        val ok = when (family) { 1 -> pie(c, pies, sb, W, H, titleH); 2 -> area(c, areas, sb, W, H, titleH); else -> scatter(c, scat, sb, W, H, titleH) }
        if (!ok) sb.append("<text x=\"").append(f(W / 2)).append("\" y=\"").append(f(H / 2 + 4)).append("\" text-anchor=\"middle\">No data</text>")
        if (skipped.isNotEmpty()) sb.append("<text x=\"").append(f(PAD)).append("\" y=\"").append(f(H - 3)).append("\" font-size=\"9\" font-style=\"italic\" fill=\"#999\">Not drawn: ").append(esc(skipped.joinToString(", "))).append("</text>")
        return sb.append("</g></svg>").toString()
    }

    // ------------------------------------------------------------------ shared: legend
    private fun lay(c: XChart, ents: List<Ent>, W: Double, H: Double, titleH: Double): Lay {
        var top = PAD + titleH; var bottom = H - PAD; var left = PAD; var right = W - PAD
        val ew = ents.map { 24 + tw(it.label) + 12 }
        val rows = ArrayList<List<Int>>(); var legW = 0.0
        if (c.legend != null && ents.isNotEmpty()) {
            if (c.legend == "r" || c.legend == "l") {
                legW = Math.min(ew.max() + 8, W * 0.4)
                if (c.legend == "r") right -= legW else left += legW
            } else {
                var row = ArrayList<Int>(); var acc = 0.0
                for (i in ents.indices) {
                    if (row.isNotEmpty() && acc + ew[i] > W - 2 * PAD) { rows.add(row); row = ArrayList(); acc = 0.0 }
                    row.add(i); acc += ew[i]
                }
                if (row.isNotEmpty()) rows.add(row)
                val lh = rows.size * 20.0 + 6
                if (c.legend == "t") top += lh else bottom -= lh
            }
        }
        return Lay(top, bottom, left, right, legW, rows, ew)
    }

    private fun legend(sb: StringBuilder, c: XChart, ents: List<Ent>, L: Lay, W: Double, H: Double, titleH: Double, plotT: Double, plotB: Double) {
        if (c.legend == null || ents.isEmpty()) return
        fun swatch(i: Int, x: Double, y: Double) {
            val e = ents[i]
            if (e.line) {
                sb.append("<line x1=\"").append(f(x)).append("\" x2=\"").append(f(x + 16)).append("\" y1=\"").append(f(y)).append("\" y2=\"").append(f(y)).append("\" stroke=\"").append(e.color).append("\" stroke-width=\"2.5\"/>")
                if (e.marker != null && e.marker != "none") ChartSvg.marker(sb, e.marker, x + 8, y, e.color)
            } else if (e.marker != null) {
                if (e.marker != "none") ChartSvg.marker(sb, e.marker, x + 8, y, e.color)
            } else sb.append("<rect x=\"").append(f(x + 3)).append("\" y=\"").append(f(y - 5)).append("\" width=\"10\" height=\"10\" fill=\"").append(e.color).append("\"/>")
            val room = if (L.legW > 0) Math.max(40.0, L.legW - 34) else W / 2
            sb.append("<text x=\"").append(f(x + 22)).append("\" y=\"").append(f(y + 4)).append("\">").append(esc(cut(e.label, room))).append("</text>")
        }
        if (c.legend == "r" || c.legend == "l") {
            val x0 = if (c.legend == "r") W - PAD - L.legW + 4 else PAD + 4
            val y0 = (plotT + plotB) / 2 - ents.size * 20.0 / 2
            for (i in ents.indices) swatch(i, x0, y0 + i * 20 + 10)
        } else {
            val yBase = if (c.legend == "t") PAD + titleH + 12 else H - PAD - L.rows.size * 20.0 + 8
            for ((r, row) in L.rows.withIndex()) {
                var x = (W - row.sumOf { L.ew[it] }) / 2
                for (i in row) { swatch(i, x, yBase + r * 20); x += L.ew[i] }
            }
        }
    }

    private fun textOn(col: String): String {
        val h = col.removePrefix("#"); if (h.length != 6) return "#fff"
        val r = h.substring(0, 2).toIntOrNull(16) ?: return "#fff"
        val g = h.substring(2, 4).toIntOrNull(16) ?: return "#fff"
        val b = h.substring(4, 6).toIntOrNull(16) ?: return "#fff"
        return if (0.299 * r + 0.587 * g + 0.114 * b > 170) "#333" else "#fff"
    }

    // ------------------------------------------------------------------ pie / doughnut
    private fun slice(sb: StringBuilder, cx: Double, cy: Double, ri: Double, ro: Double, a0: Double, a1: Double, col: String) {
        val sweep = a1 - a0
        if (sweep <= 0.0) return
        if (sweep >= 359.99) {
            if (ri <= 0.5) sb.append("<circle cx=\"").append(f(cx)).append("\" cy=\"").append(f(cy)).append("\" r=\"").append(f(ro)).append("\" fill=\"").append(col).append("\" stroke=\"#fff\" stroke-width=\"1.5\"/>")
            else sb.append("<path fill-rule=\"evenodd\" fill=\"").append(col).append("\" stroke=\"#fff\" stroke-width=\"1.5\" d=\"M").append(f(cx - ro)).append(' ').append(f(cy))
                .append(" A").append(f(ro)).append(' ').append(f(ro)).append(" 0 1 1 ").append(f(cx + ro)).append(' ').append(f(cy))
                .append(" A").append(f(ro)).append(' ').append(f(ro)).append(" 0 1 1 ").append(f(cx - ro)).append(' ').append(f(cy))
                .append(" ZM").append(f(cx - ri)).append(' ').append(f(cy))
                .append(" A").append(f(ri)).append(' ').append(f(ri)).append(" 0 1 0 ").append(f(cx + ri)).append(' ').append(f(cy))
                .append(" A").append(f(ri)).append(' ').append(f(ri)).append(" 0 1 0 ").append(f(cx - ri)).append(' ').append(f(cy)).append("Z\"/>")
            return
        }
        fun px(r: Double, a: Double) = cx + r * Math.sin(Math.toRadians(a))
        fun py(r: Double, a: Double) = cy - r * Math.cos(Math.toRadians(a))
        val large = if (sweep > 180.0) 1 else 0
        sb.append("<path fill=\"").append(col).append("\" stroke=\"#fff\" stroke-width=\"1.5\" stroke-linejoin=\"round\" d=\"M").append(f(px(ro, a0))).append(' ').append(f(py(ro, a0)))
            .append(" A").append(f(ro)).append(' ').append(f(ro)).append(" 0 ").append(large).append(" 1 ").append(f(px(ro, a1))).append(' ').append(f(py(ro, a1)))
        if (ri <= 0.5) sb.append(" L").append(f(cx)).append(' ').append(f(cy)).append("Z\"/>")
        else sb.append(" L").append(f(px(ri, a1))).append(' ').append(f(py(ri, a1)))
            .append(" A").append(f(ri)).append(' ').append(f(ri)).append(" 0 ").append(large).append(" 0 ").append(f(px(ri, a0))).append(' ').append(f(py(ri, a0))).append("Z\"/>")
    }

    private fun pie(c: XChart, plots: List<XChartPlot>, sb: StringBuilder, W: Double, H: Double, titleH: Double): Boolean {
        val p = plots.first()
        val rings = if (p.type == "doughnut") p.series.take(8) else listOf(p.series.first())
        val s0 = rings.first()
        val n = Math.min(Math.max(s0.cats.size, s0.vals.size), 60)
        if (n == 0) return false
        fun colOf(s: XChartSeries, i: Int) = s.ptColors[i] ?: s.color
        val ents = (0 until n).map { i -> Ent(s0.cats.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: (i + 1).toString(), colOf(s0, i), false, null) }.take(30)
        val L = lay(c, ents, W, H, titleH)
        val anyLbl = rings.any { it.pct || it.labels || it.catName }
        val cx = (L.left + L.right) / 2; val cy = (L.top + L.bottom) / 2
        val r = Math.min(L.right - L.left, L.bottom - L.top) / 2 - (if (anyLbl) 16.0 else 6.0)
        if (r < 12) return false
        val hole = if (p.type == "doughnut") r * p.hole / 100.0 else 0.0
        val ringW = (r - hole) / rings.size
        var drawnAny = false
        val labels = StringBuilder()
        for ((k, s) in rings.withIndex()) {
            val vals = (0 until n).map { Math.abs(s.vals.getOrNull(it) ?: 0.0) }
            val total = vals.sum()
            if (!(total > 0.0)) continue
            drawnAny = true
            val ri = if (p.type == "doughnut") hole + k * ringW else 0.0
            val ro = if (p.type == "doughnut") ri + ringW else r
            var acc = 0.0
            for (i in 0 until n) {
                val v = vals[i]
                if (v <= 0.0) continue
                val a0 = p.firstAngle % 360 + acc / total * 360.0
                acc += v
                val a1 = p.firstAngle % 360 + acc / total * 360.0
                val col = colOf(s, i)
                slice(sb, cx, cy, ri, ro, a0, a1, col)
                if (s.pct || s.labels || s.catName) {
                    val parts = ArrayList<String>()
                    if (s.catName) parts.add(s.cats.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: (i + 1).toString())
                    if (s.labels) parts.add(ChartParser.fmtNum(s.vals.getOrNull(i) ?: 0.0, s.fmt))
                    if (s.pct) parts.add(Math.round(100.0 * v / total).toString() + "%")
                    val txt = parts.joinToString(", ")
                    val am = Math.toRadians((a0 + a1) / 2)
                    val frac = v / total
                    if (frac >= 0.05 || p.type == "doughnut") {
                        val rr = if (p.type == "doughnut") (ri + ro) / 2 else ro * 0.62
                        labels.append("<text x=\"").append(f(cx + rr * Math.sin(am))).append("\" y=\"").append(f(cy - rr * Math.cos(am) + 4)).append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"").append(textOn(col)).append("\">").append(esc(txt)).append("</text>")
                    } else {
                        val rr = ro + 8; val sx = Math.sin(am)
                        labels.append("<text x=\"").append(f(cx + rr * sx)).append("\" y=\"").append(f(cy - rr * Math.cos(am) + 4)).append("\" text-anchor=\"").append(if (sx >= 0) "start" else "end").append("\" font-size=\"11\">").append(esc(txt)).append("</text>")
                    }
                }
            }
        }
        if (!drawnAny) return false
        sb.append(labels)
        legend(sb, c, ents, L, W, H, titleH, L.top, L.bottom)
        return true
    }

    // ------------------------------------------------------------------ shared: axis titles
    private fun yTitle(sb: StringBuilder, t: String, left: Double, plotT: Double, plotB: Double) {
        sb.append("<text transform=\"rotate(-90 ").append(f(left + 10)).append(' ').append(f((plotT + plotB) / 2)).append(")\" x=\"").append(f(left + 10)).append("\" y=\"").append(f((plotT + plotB) / 2 + 4)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotB - plotT))).append("</text>")
    }
    private fun xTitle(sb: StringBuilder, t: String, plotL: Double, plotR: Double, bottom: Double) {
        sb.append("<text x=\"").append(f((plotL + plotR) / 2)).append("\" y=\"").append(f(bottom - 5)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotR - plotL))).append("</text>")
    }
    private fun hLine(sb: StringBuilder, x1: Double, x2: Double, y: Double) {
        sb.append("<line x1=\"").append(f(x1)).append("\" x2=\"").append(f(x2)).append("\" y1=\"").append(f(y)).append("\" y2=\"").append(f(y)).append("\" stroke=\"").append(GRID).append("\"/>")
    }
    private fun vLine(sb: StringBuilder, y1: Double, y2: Double, x: Double) {
        sb.append("<line y1=\"").append(f(y1)).append("\" y2=\"").append(f(y2)).append("\" x1=\"").append(f(x)).append("\" x2=\"").append(f(x)).append("\" stroke=\"").append(GRID).append("\"/>")
    }
    private fun fmtOf(ax: XChartAxis): String? = ax.fmt?.takeIf { !it.equals("General", true) }

    // ------------------------------------------------------------------ area
    private fun area(c: XChart, plots: List<XChartPlot>, sb: StringBuilder, W: Double, H: Double, titleH: Double): Boolean {
        val ncat = plots.maxOf { p -> p.series.maxOf { Math.max(it.cats.size, it.vals.size) } }
        if (ncat == 0) return false
        val pct = plots.any { it.grouping == "percentStacked" }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (p in plots) {
            if (p.grouping == "stacked" || p.grouping == "percentStacked") {
                for (i in 0 until ncat) {
                    var pos = 0.0; var neg = 0.0
                    for (s in p.series) { val v = s.vals.getOrNull(i) ?: continue; if (v >= 0) pos += v else neg += v }
                    lo = Math.min(lo, Math.min(neg, 0.0)); hi = Math.max(hi, pos)
                }
            } else for (s in p.series) for (v in s.vals) if (v != null) { lo = Math.min(lo, v); hi = Math.max(hi, v) }
        }
        if (lo > hi) { lo = 0.0; hi = 1.0 }
        val ents = plots.flatMap { p -> p.series.map { Ent(it.name, it.color, false, null) } }.take(30)
        val L = lay(c, ents, W, H, titleH)
        val vFmt = fmtOf(c.value) ?: if (pct) "0%" else plots.firstNotNullOfOrNull { p -> p.series.firstNotNullOfOrNull { it.fmt?.takeIf { f2 -> !f2.equals("General", true) } } }
        val showCat = !c.cat.deleted && c.cat.tickLabels != "none"
        val showVal = !c.value.deleted && c.value.tickLabels != "none"
        val cats = plots.flatMap { it.series }.firstOrNull { it.cats.isNotEmpty() }?.cats ?: emptyList()
        fun catLabel(i: Int) = cats.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: (i + 1).toString()
        val maxCatPx = (0 until ncat).maxOf { tw(catLabel(it)) }
        val sc = ChartSvg.scale(lo, hi, c.value, (L.bottom - L.top) - 60, pct)
        val vl = sc.ticks().map { ChartParser.fmtNum(it, vFmt) }
        val plotL = L.left + (if (c.value.title != null) 22.0 else 0.0) + (if (showVal) vl.maxOf { tw(it) } + 10 else 4.0)
        val plotR = L.right - 14
        val plotB = L.bottom - (if (showCat) FS + 10 else 4.0) - (if (c.cat.title != null) 20.0 else 0.0)
        val plotT = L.top + 8
        if (plotR - plotL < 20 || plotB - plotT < 20) return false
        val span = if (sc.max > sc.min) sc.max - sc.min else 1.0
        fun cl(v: Double) = Math.max(sc.min, Math.min(sc.max, v))
        fun valP(v: Double) = plotB - (cl(v) - sc.min) / span * (plotB - plotT)
        // Excel's area charts put the first / last category on the plot edges ("on tick marks")
        fun xAt(i: Int): Double = if (ncat <= 1) (plotL + plotR) / 2 else plotL + (if (c.cat.reverse) ncat - 1 - i else i).toDouble() / (ncat - 1) * (plotR - plotL)
        val cross = cl(0.0)
        val ticks = sc.ticks()
        if (c.value.grid) for (t in ticks) hLine(sb, plotL, plotR, valP(t))
        if (showVal) for (t in ticks) sb.append("<text x=\"").append(f(plotL - 6)).append("\" y=\"").append(f(valP(t) + 4)).append("\" text-anchor=\"end\">").append(esc(ChartParser.fmtNum(t, vFmt))).append("</text>")
        val skip = Math.ceil((maxCatPx + 8) / (if (ncat > 1) (plotR - plotL) / (ncat - 1) else (plotR - plotL))).toInt().coerceAtLeast(1)
        if (showCat) for (i in 0 until ncat step skip) {
            val x = xAt(i)
            val anchor = if (ncat > 1 && i == 0 && maxCatPx / 2 > x - L.left) "start" else "middle"
            sb.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(plotB + FS + 4)).append("\" text-anchor=\"").append(anchor).append("\">").append(esc(cut(catLabel(i), (plotR - plotL) / Math.max(1, ncat / skip)))).append("</text>")
        }
        for (p in plots) {
            val stacked = p.grouping == "stacked" || p.grouping == "percentStacked"
            val base = DoubleArray(ncat)
            for (s in p.series) {
                val up = DoubleArray(ncat); val lw = DoubleArray(ncat)
                for (i in 0 until ncat) {
                    var raw = s.vals.getOrNull(i) ?: 0.0
                    if (p.grouping == "percentStacked") { val tot = p.series.sumOf { Math.abs(it.vals.getOrNull(i) ?: 0.0) }; raw = if (tot == 0.0) 0.0 else raw / tot }
                    if (stacked) { lw[i] = base[i]; base[i] += raw; up[i] = base[i] } else { lw[i] = cross; up[i] = raw }
                }
                sb.append("<path fill=\"").append(s.color).append("\" d=\"M").append(f(xAt(0))).append(' ').append(f(valP(up[0])))
                for (i in 1 until ncat) sb.append(" L").append(f(xAt(i))).append(' ').append(f(valP(up[i])))
                for (i in ncat - 1 downTo 0) sb.append(" L").append(f(xAt(i))).append(' ').append(f(valP(lw[i])))
                sb.append("Z\"/>")
            }
        }
        if (!c.cat.deleted) hLine(sb, plotL, plotR, valP(cross))
        c.value.title?.let { yTitle(sb, it, L.left, plotT, plotB) }
        c.cat.title?.let { xTitle(sb, it, plotL, plotR, L.bottom) }
        legend(sb, c, ents, L, W, H, titleH, plotT, plotB)
        return true
    }

    // ------------------------------------------------------------------ scatter
    private fun pathD(g: List<DoubleArray>, smooth: Boolean): String {
        val d = StringBuilder("M").append(f(g[0][0])).append(' ').append(f(g[0][1]))
        if (smooth && g.size > 2) {
            for (k in 0 until g.size - 1) {
                val p0 = g[Math.max(k - 1, 0)]; val p1 = g[k]; val p2 = g[k + 1]; val p3 = g[Math.min(k + 2, g.size - 1)]
                d.append(" C").append(f(p1[0] + (p2[0] - p0[0]) / 6)).append(' ').append(f(p1[1] + (p2[1] - p0[1]) / 6)).append(' ')
                    .append(f(p2[0] - (p3[0] - p1[0]) / 6)).append(' ').append(f(p2[1] - (p3[1] - p1[1]) / 6)).append(' ').append(f(p2[0])).append(' ').append(f(p2[1]))
            }
        } else {
            for (k in 1 until g.size) d.append(" L").append(f(g[k][0])).append(' ').append(f(g[k][1]))
        }
        return d.toString()
    }

    private fun scatter(c: XChart, plots: List<XChartPlot>, sb: StringBuilder, W: Double, H: Double, titleH: Double): Boolean {
        val all = plots.flatMap { it.series }
        fun xOf(s: XChartSeries, i: Int): Double? = if (s.xs.isEmpty() || s.xs.all { it == null }) (i + 1).toDouble() else s.xs.getOrNull(i)
        var xlo = Double.POSITIVE_INFINITY; var xhi = Double.NEGATIVE_INFINITY; var ylo = Double.POSITIVE_INFINITY; var yhi = Double.NEGATIVE_INFINITY
        var count = 0
        for (s in all) for (i in 0 until s.vals.size) {
            val y = s.vals[i] ?: continue; val x = xOf(s, i) ?: continue
            xlo = Math.min(xlo, x); xhi = Math.max(xhi, x); ylo = Math.min(ylo, y); yhi = Math.max(yhi, y); count++
        }
        if (count == 0) return false
        val ents = all.map { Ent(it.name, it.color, it.line, if (it.marker == "none") (if (it.line) null else "none") else (it.marker ?: "circle")) }.take(30)
        val L = lay(c, ents, W, H, titleH)
        val yFmt = fmtOf(c.value) ?: all.firstNotNullOfOrNull { it.fmt?.takeIf { f2 -> !f2.equals("General", true) } }
        val xFmt = fmtOf(c.cat)
        val showX = !c.cat.deleted && c.cat.tickLabels != "none"
        val showY = !c.value.deleted && c.value.tickLabels != "none"
        val ysc = ChartSvg.scale(ylo, yhi, c.value, (L.bottom - L.top) - 60, false)
        val yl = ysc.ticks().map { ChartParser.fmtNum(it, yFmt) }
        val plotL = L.left + (if (c.value.title != null) 22.0 else 0.0) + (if (showY) yl.maxOf { tw(it) } + 10 else 4.0)
        val plotR = L.right - 16
        val plotB = L.bottom - (if (showX) FS + 10 else 4.0) - (if (c.cat.title != null) 20.0 else 0.0)
        val plotT = L.top + 8
        if (plotR - plotL < 20 || plotB - plotT < 20) return false
        val xsc = ChartSvg.scale(xlo, xhi, c.cat, plotR - plotL, false)
        val xspan = if (xsc.max > xsc.min) xsc.max - xsc.min else 1.0
        val yspan = if (ysc.max > ysc.min) ysc.max - ysc.min else 1.0
        fun xP(v: Double) = plotL + (Math.max(xsc.min, Math.min(xsc.max, v)) - xsc.min) / xspan * (plotR - plotL)
        fun yP(v: Double) = plotB - (Math.max(ysc.min, Math.min(ysc.max, v)) - ysc.min) / yspan * (plotB - plotT)
        val yt = ysc.ticks(); val xt = xsc.ticks()
        if (c.value.grid) for (t in yt) hLine(sb, plotL, plotR, yP(t))
        if (c.cat.grid) for (t in xt) vLine(sb, plotT, plotB, xP(t))
        if (showY) for (t in yt) sb.append("<text x=\"").append(f(plotL - 6)).append("\" y=\"").append(f(yP(t) + 4)).append("\" text-anchor=\"end\">").append(esc(ChartParser.fmtNum(t, yFmt))).append("</text>")
        if (showX) for (t in xt) sb.append("<text x=\"").append(f(xP(t))).append("\" y=\"").append(f(plotB + FS + 4)).append("\" text-anchor=\"middle\">").append(esc(ChartParser.fmtNum(t, xFmt))).append("</text>")
        if (!c.cat.deleted) hLine(sb, plotL, plotR, yP(0.0))
        if (!c.value.deleted) vLine(sb, plotT, plotB, xP(0.0))
        val labels = StringBuilder()
        for (s in all) {
            val segs = ArrayList<ArrayList<DoubleArray>>(); var cur = ArrayList<DoubleArray>()
            val dots = ArrayList<DoubleArray>()
            for (i in 0 until s.vals.size) {
                val y = s.vals[i]; val x = xOf(s, i)
                if (y == null || x == null) { if (cur.isNotEmpty()) { segs.add(cur); cur = ArrayList() }; continue }
                val pt = doubleArrayOf(xP(x), yP(y)); cur.add(pt); dots.add(pt)
                if (s.labels) labels.append("<text x=\"").append(f(pt[0])).append("\" y=\"").append(f(pt[1] - 8)).append("\" text-anchor=\"middle\" font-size=\"11\">").append(esc(ChartParser.fmtNum(y, s.fmt ?: yFmt))).append("</text>")
            }
            if (cur.isNotEmpty()) segs.add(cur)
            if (s.line) for (g in segs) if (g.size > 1)
                sb.append("<path fill=\"none\" stroke=\"").append(s.color).append("\" stroke-width=\"2.25\" stroke-linejoin=\"round\" stroke-linecap=\"round\" d=\"").append(pathD(g, s.smooth)).append("\"/>")
            val sym = s.marker ?: "circle"
            if (sym != "none") for (d in dots) ChartSvg.marker(sb, sym, d[0], d[1], s.color)
        }
        sb.append(labels)
        c.value.title?.let { yTitle(sb, it, L.left, plotT, plotB) }
        c.cat.title?.let { xTitle(sb, it, plotL, plotR, L.bottom) }
        legend(sb, c, ents, L, W, H, titleH, plotT, plotB)
        return true
    }
}
