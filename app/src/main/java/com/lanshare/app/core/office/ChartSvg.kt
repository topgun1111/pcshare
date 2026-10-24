package com.lanshare.app.core.office

/**
 * Part 10: [XChart] -> inline SVG (no JS, no android.*). Clustered / stacked / 100 % stacked column and bar, line (markers, smooth, stacked), combos of those
 * on one axis pair; title, axis titles, major gridlines, tick labels, legend, optional value labels. Other plot kinds and secondary-axis plots are NOT drawn
 * (a small note says so); a chart with nothing drawable becomes a labelled empty box. Text uses the page font (`font-family:inherit`).
 */
object ChartSvg {
    internal const val TXT = "#595959"
    internal const val GRID = "#d9d9d9"
    internal const val FS = 12.0
    internal const val PAD = 10.0

    internal fun f(d: Double): String {
        val r = Math.round(d * 10) / 10.0
        return if (r == Math.rint(r) && Math.abs(r) < 1e9) r.toLong().toString() else r.toString()
    }
    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) { '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;"); else -> if (c >= ' ') sb.append(c) }
        return sb.toString()
    }
    /** Rough text width in px (average glyph = 0.55 em). */
    internal fun tw(s: String, px: Double = FS) = s.length * px * 0.55
    internal fun cut(s: String, maxPx: Double, px: Double = FS): String { val n = (maxPx / (px * 0.55)).toInt().coerceAtLeast(2); return if (s.length <= n) s else s.substring(0, n - 1) + "…" }

    class Scale(val min: Double, val max: Double, val step: Double) {
        fun ticks(): List<Double> {
            val n = Math.round((max - min) / step).toInt().coerceIn(1, 24)
            return (0..n).map { Math.round((min + it * step) * 1e9) / 1e9 }
        }
    }

    private fun nice(raw: Double): Double {
        if (!(raw > 0) || raw.isInfinite()) return 1.0
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw))); val r = raw / mag
        return mag * when { r <= 1.0 + 1e-9 -> 1.0; r <= 2.0 + 1e-9 -> 2.0; r <= 5.0 + 1e-9 -> 5.0; else -> 10.0 }
    }

    /** Excel-like automatic axis: starts at zero unless the data sit in the top sixth, about one tick per 38 px, a little head-room above the maximum. */
    fun scale(lo: Double, hi: Double, ax: XChartAxis, avail: Double, pct: Boolean): Scale {
        if (pct) { val st = ax.major ?: if (avail > 260) 0.1 else 0.2; return Scale(ax.min ?: 0.0, ax.max ?: 1.0, st) }
        var mn = lo; var mx = hi
        if (mn > mx) { mn = 0.0; mx = 1.0 }
        if (mn == mx) { if (mn == 0.0) mx = 1.0 else if (mn > 0) mn = 0.0 else mx = 0.0 }
        else if (mn > 0) { if ((mx - mn) / mx >= 1.0 / 6) mn = 0.0 }
        else if (mx < 0) { if ((mx - mn) / -mn >= 1.0 / 6) mx = 0.0 }
        val n = (avail / 38.0).coerceIn(3.0, 10.0)
        var step = ax.major ?: nice((mx - mn) / n)
        if (step <= 0) step = 1.0
        val range = mx - mn
        val smin = ax.min ?: Math.floor((if (mn < 0) mn - 0.05 * range else mn) / step + 1e-9) * step
        var smax = ax.max ?: Math.ceil((if (mx > 0) mx + 0.05 * range else mx) / step - 1e-9) * step
        if (smax <= smin) smax = smin + step
        while ((smax - smin) / step > 20) step *= 2
        return Scale(smin, smax, step)
    }

    fun render(c: XChart, w: Int, h: Int): String {
        ChartSvg2.render(c, w, h)?.let { return it }   // Part 11: pie / doughnut / area / scatter (null = not a chart for ChartSvg2)
        val W = w.toDouble(); val H = h.toDouble()
        val sb = StringBuilder(6 * 1024)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(w).append("\" height=\"").append(h).append("\" viewBox=\"0 0 ").append(w).append(' ').append(h)
            .append("\" style=\"display:block;font-family:inherit\"><rect x=\"0.5\" y=\"0.5\" width=\"").append(w - 1).append("\" height=\"").append(h - 1).append("\" fill=\"#fff\" stroke=\"").append(GRID).append("\"/>")
            .append("<g font-size=\"").append(f(FS)).append("\" fill=\"").append(TXT).append("\">")
        var drawn = c.plots.filter { (it.type == "bar" || it.type == "line") && !it.secondary && it.series.isNotEmpty() }
        val horiz = drawn.firstOrNull { it.type == "bar" }?.horizontal == true
        if (horiz) drawn = drawn.filter { it.type == "bar" }
        val skipped = ArrayList<String>()
        for (k in c.kinds) if (k != "bar" && k != "line" && k !in skipped) skipped.add(k)
        if (c.plots.any { it.secondary && it.series.isNotEmpty() }) skipped.add("secondary axis")
        val ncat = drawn.maxOfOrNull { p -> p.series.maxOf { maxOf(it.cats.size, it.vals.size) } } ?: 0
        // title (shrinks to fit)
        var titleH = 0.0
        c.title?.let { t ->
            val fs = if (tw(t, 18.0) > W - 2 * PAD) Math.max(11.0, (W - 2 * PAD) / (t.length * 0.55)) else 18.0
            sb.append("<text x=\"").append(f(W / 2)).append("\" y=\"").append(f(PAD + 18)).append("\" text-anchor=\"middle\" font-size=\"").append(f(fs)).append("\">").append(esc(cut(t, W - 2 * PAD, fs))).append("</text>")
            titleH = 34.0
        }
        if (drawn.isEmpty() || ncat == 0) {
            val what = if (drawn.isEmpty() && skipped.isNotEmpty()) skipped.joinToString(", ").replaceFirstChar { it.uppercase() } + " chart: not drawn yet" else "No data"
            sb.append("<text x=\"").append(f(W / 2)).append("\" y=\"").append(f(H / 2 + 4)).append("\" text-anchor=\"middle\">").append(esc(what)).append("</text></g></svg>")
            return sb.toString()
        }
        // data extent
        val pct = drawn.any { it.type == "bar" && it.grouping == "percentStacked" }
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (p in drawn) {
            val stacked = p.grouping == "stacked" || p.grouping == "percentStacked"
            if (stacked) {
                val run = DoubleArray(ncat)
                for (i in 0 until ncat) { var pos = 0.0; var neg = 0.0; for (s in p.series) { val v = s.vals.getOrNull(i) ?: continue; if (v >= 0) pos += v else neg += v; run[i] += v }
                    lo = Math.min(lo, Math.min(neg, 0.0)); hi = Math.max(hi, pos)
                    if (p.type == "line") { lo = Math.min(lo, run[i]); hi = Math.max(hi, run[i]) } }
            } else for (s in p.series) for (v in s.vals) if (v != null) { lo = Math.min(lo, v); hi = Math.max(hi, v) }
        }
        if (lo > hi) { lo = 0.0; hi = 1.0 }
        // legend
        val names = ArrayList<Triple<String, String, XChartPlot>>()
        for (p in drawn) for (s in p.series) names.add(Triple(s.name, s.color, p))
        var top = PAD + titleH; var bottom = H - PAD; var left = PAD; var right = W - PAD
        val legRows = ArrayList<List<Int>>(); var legW = 0.0
        val ew = names.map { 24 + tw(it.first) + 12 }
        if (c.legend != null && names.isNotEmpty()) {
            if (c.legend == "r" || c.legend == "l") {
                legW = Math.min(ew.max() + 8, W * 0.4)
                if (c.legend == "r") right -= legW else left += legW
            } else {
                var row = ArrayList<Int>(); var acc = 0.0
                for (i in names.indices) { if (row.isNotEmpty() && acc + ew[i] > W - 2 * PAD) { legRows.add(row); row = ArrayList(); acc = 0.0 }; row.add(i); acc += ew[i] }
                if (row.isNotEmpty()) legRows.add(row)
                val lh = legRows.size * 20.0 + 6
                if (c.legend == "t") top += lh else bottom -= lh
            }
        }
        val vFmt = c.value.fmt?.takeIf { !it.equals("General", true) } ?: if (pct) "0%" else drawn.firstNotNullOfOrNull { p -> p.series.firstNotNullOfOrNull { it.fmt?.takeIf { f2 -> !f2.equals("General", true) } } }
        val rev = c.cat.reverse
        val showCatLbl = !c.cat.deleted && c.cat.tickLabels != "none"
        val showValLbl = !c.value.deleted && c.value.tickLabels != "none"
        val cats = drawn.flatMap { it.series }.firstOrNull { it.cats.isNotEmpty() }?.cats ?: emptyList()
        fun catLabel(i: Int) = cats.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: (i + 1).toString()
        // layout
        val plotL: Double; val plotR: Double; val plotT: Double; val plotB: Double
        val sc: Scale; var rotate = false; var skip = 1; var rotMax = 0
        val maxCatPx = (0 until ncat).maxOf { tw(catLabel(it)) }
        if (!horiz) {
            sc = scale(lo, hi, c.value, (bottom - top) - 60, pct)
            val vl = sc.ticks().map { ChartParser.fmtNum(it, vFmt) }
            plotL = left + (if (c.value.title != null) 22.0 else 0.0) + (if (showValLbl) vl.maxOf { tw(it) } + 10 else 4.0)
            plotR = right - 8
            val slot = (plotR - plotL) / ncat
            rotate = showCatLbl && maxCatPx > slot - 4
            val lblH = if (!showCatLbl) 4.0 else if (rotate) Math.min(maxCatPx * 0.71 + 12, (bottom - top) * 0.35) else FS + 10
            rotMax = Math.max(4, ((lblH - 12) / 0.71 / (FS * 0.55)).toInt())
            skip = Math.ceil((if (rotate) FS * 1.4 else maxCatPx + 8) / slot).toInt().coerceAtLeast(1)
            plotB = bottom - lblH - (if (c.cat.title != null) 20.0 else 0.0)
            plotT = top + 8
        } else {
            val catW = if (showCatLbl) Math.min(maxCatPx + 10, W * 0.35) else 4.0
            plotL = left + (if (c.cat.title != null) 22.0 else 0.0) + catW
            plotR = right - 16
            plotT = top + 8
            plotB = bottom - (if (showValLbl) FS + 10 else 4.0) - (if (c.value.title != null) 20.0 else 0.0)
            sc = scale(lo, hi, c.value, plotR - plotL, pct)
            val slot = (plotB - plotT) / ncat
            skip = Math.ceil(FS * 1.4 / slot).toInt().coerceAtLeast(1)
        }
        val slot = if (!horiz) (plotR - plotL) / ncat else (plotB - plotT) / ncat
        val span = sc.max - sc.min
        fun cl(v: Double) = Math.max(sc.min, Math.min(sc.max, v))
        fun valP(v: Double): Double = if (!horiz) plotB - (cl(v) - sc.min) / span * (plotB - plotT) else plotL + (cl(v) - sc.min) / span * (plotR - plotL)
        fun catC(i: Int): Double { val k = if (rev) ncat - 1 - i else i; return if (!horiz) plotL + (k + 0.5) * slot else plotB - (k + 0.5) * slot }
        val cross = cl(0.0)
        val hasNeg = sc.min < 0
        // gridlines
        val ticks = sc.ticks()
        if (c.value.grid) for (t in ticks) {
            val p = valP(t)
            if (!horiz) sb.append("<line x1=\"").append(f(plotL)).append("\" x2=\"").append(f(plotR)).append("\" y1=\"").append(f(p)).append("\" y2=\"").append(f(p)).append("\" stroke=\"").append(GRID).append("\"/>")
            else sb.append("<line y1=\"").append(f(plotT)).append("\" y2=\"").append(f(plotB)).append("\" x1=\"").append(f(p)).append("\" x2=\"").append(f(p)).append("\" stroke=\"").append(GRID).append("\"/>")
        }
        // value tick labels
        if (showValLbl) for (t in ticks) {
            val p = valP(t); val s = esc(ChartParser.fmtNum(t, vFmt))
            if (!horiz) sb.append("<text x=\"").append(f(plotL - 6)).append("\" y=\"").append(f(p + 4)).append("\" text-anchor=\"end\">").append(s).append("</text>")
            else sb.append("<text x=\"").append(f(p)).append("\" y=\"").append(f(plotB + FS + 4)).append("\" text-anchor=\"middle\">").append(s).append("</text>")
        }
        // category axis line + labels
        val low = c.cat.tickLabels == "low" || !hasNeg
        if (!c.cat.deleted) {
            val b = valP(cross)
            if (!horiz) sb.append("<line x1=\"").append(f(plotL)).append("\" x2=\"").append(f(plotR)).append("\" y1=\"").append(f(b)).append("\" y2=\"").append(f(b)).append("\" stroke=\"").append(GRID).append("\"/>")
            else sb.append("<line y1=\"").append(f(plotT)).append("\" y2=\"").append(f(plotB)).append("\" x1=\"").append(f(b)).append("\" x2=\"").append(f(b)).append("\" stroke=\"").append(GRID).append("\"/>")
        }
        if (showCatLbl) for (i in 0 until ncat step skip) {
            val s = catLabel(i); val k = catC(i)
            if (!horiz) {
                val y = (if (low) plotB else valP(cross)) + FS + 4
                if (rotate) sb.append("<text x=\"").append(f(k + 4)).append("\" y=\"").append(f(y - 4)).append("\" text-anchor=\"end\" transform=\"rotate(-45 ").append(f(k + 4)).append(' ').append(f(y - 4)).append(")\">").append(esc(cut(s, rotMax * FS * 0.55))).append("</text>")
                else sb.append("<text x=\"").append(f(k)).append("\" y=\"").append(f(y)).append("\" text-anchor=\"middle\">").append(esc(s)).append("</text>")
            } else {
                val x = (if (low) plotL else valP(cross)) - 6
                sb.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(k + 4)).append("\" text-anchor=\"end\">").append(esc(cut(s, plotL - left - (if (c.cat.title != null) 22.0 else 0.0) - 6))).append("</text>")
            }
        }
        // bars first, then lines (Excel draws lines on top)
        val labels = StringBuilder()
        fun label(txt: String, x: Double, y: Double, anchor: String) { labels.append("<text x=\"").append(f(x)).append("\" y=\"").append(f(y)).append("\" text-anchor=\"").append(anchor).append("\" font-size=\"11\">").append(esc(txt)).append("</text>") }
        for (p in drawn) if (p.type == "bar") {
            val stacked = p.grouping == "stacked" || p.grouping == "percentStacked"
            val ns = if (stacked) 1 else p.series.size
            val ov = p.overlap.coerceIn(-100, 100) / 100.0
            val bw = slot / (ns - (ns - 1) * ov + p.gapWidth.coerceIn(0, 500) / 100.0)
            val spacing = bw * (1 - ov)
            val dir = if (horiz) -1.0 else 1.0
            for (i in 0 until ncat) {
                var pos = 0.0; var neg = 0.0
                val total = if (p.grouping == "percentStacked") p.series.sumOf { Math.abs(it.vals.getOrNull(i) ?: 0.0) } else 1.0
                for ((si, s) in p.series.withIndex()) {
                    val raw = s.vals.getOrNull(i) ?: continue
                    val v = if (p.grouping == "percentStacked") (if (total == 0.0) 0.0 else raw / total) else raw
                    val v0: Double; val v1: Double
                    if (stacked) { if (v >= 0) { v0 = pos; pos += v; v1 = pos } else { v0 = neg; neg += v; v1 = neg } } else { v0 = cross; v1 = v }
                    val centre = catC(i) + dir * (if (stacked) 0.0 else (si - (ns - 1) / 2.0) * spacing)
                    val a = valP(v0); val b = valP(v1)
                    if (!horiz) sb.append("<rect x=\"").append(f(centre - bw / 2)).append("\" y=\"").append(f(Math.min(a, b))).append("\" width=\"").append(f(bw)).append("\" height=\"").append(f(Math.abs(a - b))).append("\" fill=\"").append(s.color).append("\"/>")
                    else sb.append("<rect y=\"").append(f(centre - bw / 2)).append("\" x=\"").append(f(Math.min(a, b))).append("\" height=\"").append(f(bw)).append("\" width=\"").append(f(Math.abs(a - b))).append("\" fill=\"").append(s.color).append("\"/>")
                    if (s.labels) {
                        val t = ChartParser.fmtNum(raw, s.fmt ?: vFmt?.takeIf { !pct })
                        if (stacked) { if (!horiz) label(t, centre, (a + b) / 2 + 4, "middle") else label(t, (a + b) / 2, centre + 4, "middle") }
                        else if (!horiz) label(t, centre, if (v >= 0) Math.min(a, b) - 4 else Math.max(a, b) + FS, "middle")
                        else label(t, if (v >= 0) Math.max(a, b) + 4 else Math.min(a, b) - 4, centre + 4, if (v >= 0) "start" else "end")
                    }
                }
            }
        }
        for (p in drawn) if (p.type == "line") {
            val stacked = p.grouping == "stacked" || p.grouping == "percentStacked"
            val run = DoubleArray(ncat)
            for (s in p.series) {
                val segs = ArrayList<ArrayList<DoubleArray>>(); var cur = ArrayList<DoubleArray>()
                val dots = ArrayList<DoubleArray>()
                for (i in 0 until ncat) {
                    val raw = s.vals.getOrNull(i)
                    if (raw == null) { if (cur.isNotEmpty()) { segs.add(cur); cur = ArrayList() }; continue }
                    val v = if (stacked) { run[i] += raw; run[i] } else raw
                    val pt = doubleArrayOf(catC(i), valP(v)); cur.add(pt); dots.add(pt)
                    if (s.labels) label(ChartParser.fmtNum(raw, s.fmt ?: vFmt), pt[0], pt[1] - 8, "middle")
                }
                if (cur.isNotEmpty()) segs.add(cur)
                for (g in segs) if (g.size > 1) {
                    sb.append("<path fill=\"none\" stroke=\"").append(s.color).append("\" stroke-width=\"2.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\" d=\"M").append(f(g[0][0])).append(' ').append(f(g[0][1]))
                    if (s.smooth && g.size > 2) for (k in 0 until g.size - 1) {
                        val p0 = g[Math.max(k - 1, 0)]; val p1 = g[k]; val p2 = g[k + 1]; val p3 = g[Math.min(k + 2, g.size - 1)]
                        sb.append(" C").append(f(p1[0] + (p2[0] - p0[0]) / 6)).append(' ').append(f(p1[1] + (p2[1] - p0[1]) / 6)).append(' ')
                            .append(f(p2[0] - (p3[0] - p1[0]) / 6)).append(' ').append(f(p2[1] - (p3[1] - p1[1]) / 6)).append(' ').append(f(p2[0])).append(' ').append(f(p2[1]))
                    } else for (k in 1 until g.size) sb.append(" L").append(f(g[k][0])).append(' ').append(f(g[k][1]))
                    sb.append("\"/>")
                }
                val sym = markerOf(s, p)
                if (sym != "none") for (d in dots) marker(sb, sym, d[0], d[1], s.color)
            }
        }
        sb.append(labels)
        // axis titles
        c.value.title?.let { t ->
            if (!horiz) sb.append("<text transform=\"rotate(-90 ").append(f(left + 10)).append(' ').append(f((plotT + plotB) / 2)).append(")\" x=\"").append(f(left + 10)).append("\" y=\"").append(f((plotT + plotB) / 2 + 4)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotB - plotT))).append("</text>")
            else sb.append("<text x=\"").append(f((plotL + plotR) / 2)).append("\" y=\"").append(f(bottom - 5)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotR - plotL))).append("</text>")
        }
        c.cat.title?.let { t ->
            if (!horiz) sb.append("<text x=\"").append(f((plotL + plotR) / 2)).append("\" y=\"").append(f(bottom - 5)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotR - plotL))).append("</text>")
            else sb.append("<text transform=\"rotate(-90 ").append(f(left + 10)).append(' ').append(f((plotT + plotB) / 2)).append(")\" x=\"").append(f(left + 10)).append("\" y=\"").append(f((plotT + plotB) / 2 + 4)).append("\" text-anchor=\"middle\" font-weight=\"bold\">").append(esc(cut(t, plotB - plotT))).append("</text>")
        }
        // legend
        if (c.legend != null && names.isNotEmpty()) {
            fun swatch(i: Int, x: Double, y: Double) {
                val n = names[i]
                if (n.third.type == "line") {
                    sb.append("<line x1=\"").append(f(x)).append("\" x2=\"").append(f(x + 16)).append("\" y1=\"").append(f(y)).append("\" y2=\"").append(f(y)).append("\" stroke=\"").append(n.second).append("\" stroke-width=\"2.5\"/>")
                    val s = n.third.series.firstOrNull { it.name == n.first && it.color == n.second }
                    if (s != null) { val sym = markerOf(s, n.third); if (sym != "none") marker(sb, sym, x + 8, y, n.second) }
                } else sb.append("<rect x=\"").append(f(x + 3)).append("\" y=\"").append(f(y - 5)).append("\" width=\"10\" height=\"10\" fill=\"").append(n.second).append("\"/>")
                sb.append("<text x=\"").append(f(x + 22)).append("\" y=\"").append(f(y + 4)).append("\">").append(esc(cut(n.first, Math.max(40.0, legW - 34).let { if (legW > 0) it else W / 2 }))).append("</text>")
            }
            if (c.legend == "r" || c.legend == "l") {
                val x0 = if (c.legend == "r") W - PAD - legW + 4 else PAD + 4
                val y0 = (plotT + plotB) / 2 - names.size * 20.0 / 2
                for (i in names.indices) swatch(i, x0, y0 + i * 20 + 10)
            } else {
                val yBase = if (c.legend == "t") PAD + titleH + 12 else H - PAD - legRows.size * 20.0 + 8
                for ((r, row) in legRows.withIndex()) {
                    var x = (W - row.sumOf { ew[it] }) / 2
                    for (i in row) { swatch(i, x, yBase + r * 20); x += ew[i] }
                }
            }
        }
        if (skipped.isNotEmpty()) sb.append("<text x=\"").append(f(PAD)).append("\" y=\"").append(f(H - 3)).append("\" font-size=\"9\" font-style=\"italic\" fill=\"#999\">Not drawn: ").append(esc(skipped.joinToString(", "))).append("</text>")
        return sb.append("</g></svg>").toString()
    }

    /** Marker symbol of a line series: explicit symbol, else a circle when the plot shows markers; "none" = no marker. */
    private fun markerOf(s: XChartSeries, p: XChartPlot): String = s.marker ?: if (p.markers) "circle" else "none"

    internal fun marker(sb: StringBuilder, sym: String, x: Double, y: Double, col: String) {
        val r = 3.5
        when (sym) {
            "none" -> {}
            "square" -> sb.append("<rect x=\"").append(f(x - r)).append("\" y=\"").append(f(y - r)).append("\" width=\"").append(f(2 * r)).append("\" height=\"").append(f(2 * r)).append("\" fill=\"").append(col).append("\"/>")
            "diamond" -> sb.append("<path d=\"M").append(f(x)).append(' ').append(f(y - r - 1)).append(" L").append(f(x + r + 1)).append(' ').append(f(y)).append(" L").append(f(x)).append(' ').append(f(y + r + 1)).append(" L").append(f(x - r - 1)).append(' ').append(f(y)).append("Z\" fill=\"").append(col).append("\"/>")
            "triangle" -> sb.append("<path d=\"M").append(f(x)).append(' ').append(f(y - r - 1)).append(" L").append(f(x + r + 1)).append(' ').append(f(y + r)).append(" L").append(f(x - r - 1)).append(' ').append(f(y + r)).append("Z\" fill=\"").append(col).append("\"/>")
            "x", "plus", "star", "dash", "dot" -> sb.append("<path d=\"M").append(f(x - r)).append(' ').append(f(y - r)).append(" L").append(f(x + r)).append(' ').append(f(y + r)).append(" M").append(f(x + r)).append(' ').append(f(y - r)).append(" L").append(f(x - r)).append(' ').append(f(y + r)).append("\" stroke=\"").append(col).append("\" stroke-width=\"1.5\" fill=\"none\"/>")
            else -> sb.append("<circle cx=\"").append(f(x)).append("\" cy=\"").append(f(y)).append("\" r=\"").append(f(r)).append("\" fill=\"").append(col).append("\"/>")
        }
    }
}
