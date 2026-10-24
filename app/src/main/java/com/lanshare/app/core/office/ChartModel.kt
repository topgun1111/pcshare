package com.lanshare.app.core.office

/**
 * Part 10: Excel charts. Model + parser for `xl/drawings/drawingN.xml` (anchors) and `xl/charts/chartN.xml`. Pure Kotlin (no android.*).
 * Column / bar / line ([ChartSvg]) and, in Part 11, pie / doughnut / area / scatter ([ChartSvg2]) are drawn; every other plot kind is kept as `type = "other:<name>"`.
 * Values come from the cached `pt` lists in the chart XML. When a series has no cache (openpyxl and some other writers omit it) the parser asks
 * the [Resolver] to read the referenced cells from the workbook instead (no formula engine: plain ranges only).
 */
class XChartSeries(
    val name: String, val cats: List<String>, val vals: List<Double?>,
    val color: String,             // "#RRGGBB" (explicit, or the automatic accent colour decided by the parser)
    val index: Int,                // position among the drawn series of the chart (automatic colour)
    val marker: String?,           // null = automatic, "none", or a symbol name (circle, square, diamond, triangle, x ...)
    val smooth: Boolean, val labels: Boolean, val fmt: String?,  // fmt = number format code of the values
    val xs: List<Double?> = emptyList(),          // Part 11: scatter x values (xVal), empty = use 1..n
    val ptColors: Map<Int, String> = emptyMap(),  // Part 11: pie / doughnut: colour of every point (dPt or automatic accent cycle)
    val pct: Boolean = false, val catName: Boolean = false,   // Part 11: data labels with showPercent / showCatName
    val line: Boolean = true                      // Part 11: scatter: false when the series line is `noFill` (markers only)
)

class XChartPlot(
    val type: String,              // "bar" | "line" | "area" | "pie" | "doughnut" | "scatter" | "other:<kind>"
    val horizontal: Boolean,       // barDir = bar
    val grouping: String,          // clustered | stacked | percentStacked | standard
    val gapWidth: Int, val overlap: Int,
    val markers: Boolean,          // lineChart <marker val=1>
    val labels: Boolean,           // plot-level dLbls showVal
    val secondary: Boolean,        // uses axes other than the first plot's: not drawn in Part 10
    val series: List<XChartSeries>,
    val hole: Int = 50, val firstAngle: Int = 0   // Part 11: doughnut hole size in %, first slice angle in degrees (clockwise from 12 o'clock)
)

class XChartAxis(
    val deleted: Boolean, val title: String?, val min: Double?, val max: Double?, val major: Double?, val fmt: String?,
    val grid: Boolean, val reverse: Boolean, val tickLabels: String   // tickLabels: nextTo | low | high | none
) { companion object { val NONE = XChartAxis(false, null, null, null, null, null, false, false, "nextTo") } }

class XChart(
    val title: String?, val plots: List<XChartPlot>, val cat: XChartAxis, val value: XChartAxis,
    val legend: String?,           // r | l | t | b | null (none)
    val kinds: List<String>        // every plot kind found, e.g. ["bar", "pie"]; used for the "not drawn" note
)

/** Position of a chart on the sheet. Cell anchors: col / row (0-based) + offsets in EMU; absolute anchors: [x] / [y] in EMU from the sheet origin. */
class XAnchor(val c1: Int, val x1: Long, val r1: Int, val y1: Long, val c2: Int, val x2: Long, val r2: Int, val y2: Long,
              val cx: Long, val cy: Long, val kind: Int)   // kind 0 = twoCell, 1 = oneCell (size cx x cy), 2 = absolute (x1 / y1 = position)
class XChartFrame(val anchor: XAnchor, val chart: XChart)

object ChartParser {
    /** Reads the cells of a plain range like `Sheet1!$B$2:$B$9`, row-major; null = cannot resolve. */
    fun interface Resolver { fun cells(ref: String): List<XCell?>? }

    /** Minimal element tree of a (small) part. Attributes kept: val idx formatCode id cx cy. */
    internal class N(val name: String) {
        val kids = ArrayList<N>(); var text = ""; var v: String? = null; var idx: String? = null; var fmt: String? = null; var rid: String? = null; var cx: String? = null; var cy: String? = null; var emb: String? = null; var prst: String? = null; var sz: String? = null
        fun kid(n: String): N? { for (k in kids) if (k.name == n) return k; return null }
        fun all(n: String): List<N> = kids.filter { it.name == n }
        fun path(vararg p: String): N? { var c: N? = this; for (s in p) { c = c?.kid(s) ?: return null }; return c }
        fun find(n: String): N? { for (k in kids) { if (k.name == n) return k; k.find(n)?.let { return it } }; return null }
        fun texts(n: String, out: MutableList<String>) { for (k in kids) { if (k.name == n) out.add(k.text) else k.texts(n, out) } }
    }

    /** Builds the tree of the element the parser is ON (a START). Capped, so a hostile part cannot exhaust memory. */
    internal fun tree(x: MiniXml, cap: Int = 60_000): N {
        val root = N(x.name); fill(root, x)
        val d = x.depth
        val stack = ArrayList<N>(); stack.add(root); var count = 0
        if (x.isEmptyElement()) { x.next(); return root }
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) break
            if (t == MiniXml.T.START) {
                if (++count > cap) { x.skip(); continue }
                val n = N(x.name); fill(n, x); stack.last().kids.add(n)
                if (x.isEmptyElement()) { x.next() } else stack.add(n)
            } else if (t == MiniXml.T.END) {
                if (x.depth < d) break
                if (stack.size > 1) stack.removeAt(stack.size - 1)
            } else if (t == MiniXml.T.TEXT) { if (stack.last().text.length < 4000) stack.last().text += x.text }
        }
        return root
    }
    private fun fill(n: N, x: MiniXml) { n.v = x.attr("val"); n.idx = x.attr("idx"); n.fmt = x.attr("formatCode"); n.rid = x.attr("id"); n.cx = x.attr("cx"); n.cy = x.attr("cy"); n.emb = x.attr("embed"); n.prst = x.attr("prst"); n.sz = x.attr("sz") }

    // ------------------------------------------------------------------ drawing (anchors)
    /** All chart frames of a sheet's drawing part. [theme] gives the automatic colours; [res] fills series without cached values. */
    fun parseDrawing(pkg: OoxmlPackage, drawingPart: String, theme: Theme, res: Resolver?): List<XChartFrame> {
        val x = pkg.xml(drawingPart) ?: return emptyList()
        val rels = pkg.rels(drawingPart)
        val out = ArrayList<XChartFrame>()
        while (out.size < 30) {
            val t = x.next(); if (t == MiniXml.T.EOF) break
            if (t != MiniXml.T.START) continue
            val kind = when (x.name) { "twoCellAnchor" -> 0; "oneCellAnchor" -> 1; "absoluteAnchor" -> 2; else -> -1 }
            if (kind < 0) continue
            val a = tree(x)
            val ch = a.find("chart") ?: continue
            val rel = ch.rid?.let { rels[it] } ?: continue
            if (rel.external) continue
            val anchor = anchorOf(a, kind) ?: continue
            val bytes = pkg.read(rel.target) ?: continue
            if (bytes.size > 4 shl 20) continue
            val chart = try { parseChart(MiniXml(bytes), theme, res) } catch (_: Throwable) { null } ?: continue
            out.add(XChartFrame(anchor, chart))
        }
        return out
    }

    private fun num(n: N?): Long = n?.text?.trim()?.toDoubleOrNull()?.toLong() ?: 0L
    internal fun anchorOf(a: N, kind: Int): XAnchor? {
        val f = a.kid("from"); val t = a.kid("to"); val e = a.kid("ext"); val p = a.kid("pos")
        val cx = e?.cx?.toDoubleOrNull()?.toLong() ?: 0L; val cy = e?.cy?.toDoubleOrNull()?.toLong() ?: 0L
        return when (kind) {
            0 -> if (f == null || t == null) null else XAnchor(num(f.kid("col")).toInt(), num(f.kid("colOff")), num(f.kid("row")).toInt(), num(f.kid("rowOff")),
                num(t.kid("col")).toInt(), num(t.kid("colOff")), num(t.kid("row")).toInt(), num(t.kid("rowOff")), 0, 0, 0)
            1 -> if (f == null || cx <= 0 || cy <= 0) null else XAnchor(num(f.kid("col")).toInt(), num(f.kid("colOff")), num(f.kid("row")).toInt(), num(f.kid("rowOff")), 0, 0, 0, 0, cx, cy, 1)
            else -> if (cx <= 0 || cy <= 0) null else XAnchor(0, p?.cx?.toDoubleOrNull()?.toLong() ?: 0L, 0, p?.cy?.toDoubleOrNull()?.toLong() ?: 0L, 0, 0, 0, 0, cx, cy, 2)
        }
    }

    // ------------------------------------------------------------------ chart part
    private val PLOTS = mapOf("barChart" to "bar", "bar3DChart" to "bar", "lineChart" to "line", "line3DChart" to "line",
        "pieChart" to "pie", "pie3DChart" to "pie", "doughnutChart" to "doughnut", "areaChart" to "area", "area3DChart" to "area", "scatterChart" to "scatter")

    fun parseChart(x: MiniXml, theme: Theme, res: Resolver?): XChart? {
        var root: N? = null
        while (true) { val t = x.next(); if (t == MiniXml.T.EOF) break; if (t == MiniXml.T.START && x.name == "chart") { root = tree(x); break } }
        val chart = root ?: return null
        val area = chart.kid("plotArea") ?: return null
        // axes by id
        val axes = HashMap<String, N>()
        for (k in area.kids) if (k.name == "catAx" || k.name == "valAx" || k.name == "dateAx" || k.name == "serAx") k.kid("axId")?.v?.let { axes[it] = k }
        val plots = ArrayList<XChartPlot>(); val kinds = ArrayList<String>()
        var firstAx: Set<String>? = null; var si = 0
        var catAx: N? = null; var valAx: N? = null
        for (p in area.kids) {
            val kind = PLOTS[p.name] ?: if (p.name.endsWith("Chart") || p.name.endsWith("3DChart")) "other:" + p.name.removeSuffix("3DChart").removeSuffix("Chart") else continue
            kinds.add(kind.removePrefix("other:"))
            val idList = p.all("axId").mapNotNull { it.v }
            val ids = idList.toSet()
            if (kind.startsWith("other:")) { plots.add(XChartPlot(kind, false, "standard", 150, 0, false, false, false, emptyList())); continue }
            val noAxes = kind == "pie" || kind == "doughnut"
            if (!noAxes && firstAx == null && ids.isNotEmpty()) {
                firstAx = ids
                if (kind == "scatter") {   // two value axes: the first axId is x, the second y
                    axes[idList.getOrNull(0) ?: ""]?.let { catAx = it }
                    axes[idList.getOrNull(1) ?: ""]?.let { valAx = it }
                } else for (id in ids) axes[id]?.let { if (it.name == "valAx") valAx = it else if (catAx == null) catAx = it }
            }
            val secondary = !noAxes && ids.isNotEmpty() && firstAx != null && ids.none { it in firstAx!! }
            val isBar = kind == "bar"
            val fillFirst = isBar || kind == "area" || noAxes   // colour from the fill, not the line
            val grouping = p.kid("grouping")?.v ?: if (isBar) "clustered" else "standard"
            val series = ArrayList<XChartSeries>()
            val plotLbl = p.path("dLbls", "showVal")?.v.let { it == "1" || it == "true" }
            val plotPct = p.path("dLbls", "showPercent")?.v.let { it == "1" || it == "true" }
            val plotCat = p.path("dLbls", "showCatName")?.v.let { it == "1" || it == "true" }
            for (s in p.all("ser")) { if (series.size >= 40) break; series.add(series(s, theme, if (secondary) -1 else si++, fillFirst, res, plotLbl, kind, plotPct, plotCat)) }
            plots.add(XChartPlot(kind, isBar && p.kid("barDir")?.v == "bar", grouping, p.kid("gapWidth")?.v?.toIntOrNull() ?: 150,
                p.kid("overlap")?.v?.toIntOrNull() ?: (if (grouping == "stacked" || grouping == "percentStacked") 100 else 0),
                kind == "line" && p.kid("marker")?.v.let { it == "1" || it == "true" }, plotLbl, secondary, series,
                (p.kid("holeSize")?.v?.toIntOrNull() ?: 50).coerceIn(10, 90), p.kid("firstSliceAng")?.v?.toIntOrNull() ?: 0))
        }
        val drawn = plots.filter { !it.secondary && it.series.isNotEmpty() }
        var title: String? = null
        val tn = chart.kid("title")
        if (tn != null && chart.kid("autoTitleDeleted")?.v != "1") {
            title = richText(tn)
            if (title == null && drawn.sumOf { it.series.size } == 1) title = drawn.first().series.first().name
        }
        var legend: String? = null
        chart.kid("legend")?.let { l -> if (l.kid("delete")?.v != "1") legend = when (l.kid("legendPos")?.v ?: "r") { "t" -> "t"; "b" -> "b"; "l" -> "l"; else -> "r" } }
        return XChart(title, plots, axisOf(catAx), axisOf(valAx), legend, kinds)
    }

    private fun axisOf(a: N?): XChartAxis {
        if (a == null) return XChartAxis.NONE
        val sc = a.kid("scaling")
        return XChartAxis(a.kid("delete")?.v.let { it == "1" || it == "true" }, a.kid("title")?.let { richText(it) },
            sc?.kid("min")?.v?.toDoubleOrNull(), sc?.kid("max")?.v?.toDoubleOrNull(), a.kid("majorUnit")?.v?.toDoubleOrNull(),
            a.kid("numFmt")?.fmt, a.kid("majorGridlines") != null, sc?.kid("orientation")?.v == "maxMin", a.kid("tickLblPos")?.v ?: "nextTo")
    }

    /** Text of a `title` / `rich` element: all `t` runs, paragraphs joined by a space; null when empty. */
    private fun richText(t: N): String? {
        val rich = t.path("tx", "rich") ?: return null
        val sb = StringBuilder()
        for (p in rich.all("p")) { val parts = ArrayList<String>(); p.texts("t", parts); if (sb.isNotEmpty()) sb.append(' '); sb.append(parts.joinToString("")) }
        return sb.toString().trim().ifEmpty { null }
    }

    // ------------------------------------------------------------------ series
    private fun series(s: N, theme: Theme, index: Int, isBar: Boolean, res: Resolver?, plotLbl: Boolean, kind: String = "bar", plotPct: Boolean = false, plotCat: Boolean = false): XChartSeries {
        // name
        var name: String? = null
        s.kid("tx")?.let { tx ->
            val c = cache(tx)
            name = c.first.firstOrNull { !it.isNullOrEmpty() } ?: tx.kid("v")?.text?.trim()
            if (name == null) refOf(tx)?.let { r -> res?.cells(r)?.firstOrNull()?.let { name = it.text } }
        }
        // categories and values: cache first, workbook cells as a fallback
        val catN = s.kid("cat") ?: s.kid("xVal"); val valN = s.kid("val") ?: s.kid("yVal")
        var cats: List<String> = emptyList()
        if (catN != null) {
            val c = cache(catN); val fmt = c.second
            cats = c.first.map { v -> if (v == null) "" else if (fmt != null && !fmt.equals("General", true) && v.toDoubleOrNull() != null && isNumRef(catN)) fmtNum(v.toDouble(), fmt) else v }
            if (cats.isEmpty() || cats.all { it.isEmpty() }) refOf(catN)?.let { r -> res?.cells(r)?.let { cs -> cats = cs.map { it?.text ?: "" } } }
        }
        var vals: List<Double?> = emptyList(); var vfmt: String? = null
        if (valN != null) {
            val c = cache(valN); vfmt = c.second
            vals = c.first.map { it?.toDoubleOrNull() }
            if (vals.isEmpty() || vals.all { it == null }) refOf(valN)?.let { r -> res?.cells(r)?.let { cs -> vals = cs.map { cellNum(it) } } }
        }
        if (cats.size < vals.size) cats = cats + List(vals.size - cats.size) { (cats.size + it + 1).toString() }
        // colour: bars use the fill, lines the line colour
        val sp = s.kid("spPr")
        var color: String? = null
        if (sp != null) {
            val fill = sp.kid("solidFill")?.let { colorOf(it, theme) } ?: sp.kid("gradFill")?.find("gs")?.let { colorOf(it, theme) }
            val ln = sp.kid("ln")?.kid("solidFill")?.let { colorOf(it, theme) }
            color = if (isBar) fill ?: ln else ln ?: fill
        }
        // Part 11: scatter x values, per-point colours (pie / doughnut), percent / category labels, hidden series line
        var xs: List<Double?> = emptyList()
        val xn = s.kid("xVal")
        if (xn != null) {
            val c = cache(xn); xs = c.first.map { it?.toDoubleOrNull() }
            if (xs.isEmpty() || xs.all { it == null }) refOf(xn)?.let { r -> res?.cells(r)?.let { cs -> xs = cs.map { cellNum(it) } } }
        }
        val pts = HashMap<Int, String>()
        if (kind == "pie" || kind == "doughnut") {
            val explicit = HashMap<Int, String>()
            for (d in s.all("dPt")) {
                val di = d.kid("idx")?.v?.toIntOrNull() ?: continue
                val dc = d.kid("spPr")?.kid("solidFill")?.let { colorOf(it, theme) }
                if (dc != null) explicit[di] = dc
            }
            for (k in 0 until Math.min(vals.size, 200)) pts[k] = explicit[k] ?: autoColor(theme, k)
        }
        val noLine = sp?.kid("ln")?.kid("noFill") != null
        val sPct = s.path("dLbls", "showPercent")?.v?.let { it == "1" || it == "true" } ?: plotPct
        val sCat = s.path("dLbls", "showCatName")?.v?.let { it == "1" || it == "true" } ?: plotCat
        if (color == null) color = s.path("marker", "spPr")?.kid("solidFill")?.let { colorOf(it, theme) }   // markers-only scatter series
        val sym = s.path("marker", "symbol")?.v
        val smooth = s.kid("smooth")?.let { it.v == null || it.v == "1" || it.v == "true" } ?: false
        val lbl = s.path("dLbls", "showVal")?.v?.let { it == "1" || it == "true" } ?: plotLbl
        return XChartSeries(name ?: "Series${index + 1}", cats, vals, color ?: autoColor(theme, index.coerceAtLeast(0)), index.coerceAtLeast(0), if (sym == "auto") null else sym, smooth, lbl, vfmt, xs, pts, sPct, sCat, !noLine)
    }

    /** Excel's automatic series colours: accent1..6 of the workbook theme, then darker (60 %) and lighter (80 % + 20 %) rounds. */
    private fun autoColor(theme: Theme, i: Int): String {
        val base = theme.color("accent" + (i % 6 + 1)) ?: "4472C4"
        return "#" + when (i / 6) { 0 -> base; 1 -> Theme.lum(base, 0.6, 0.0); else -> Theme.lum(base, 0.8, 0.2) }
    }

    private fun isNumRef(n: N) = n.kid("numRef") != null || n.kid("numLit") != null
    private fun cellNum(c: XCell?): Double? = when {
        c == null -> null
        c.kind == XCell.Kind.NUMBER || c.kind == XCell.Kind.DATE || c.kind == XCell.Kind.BOOL -> c.raw.toDoubleOrNull()
        c.kind == XCell.Kind.TEXT -> 0.0   // Excel plots text as zero
        else -> null
    }

    /** The `f` text of a strRef / numRef / multiLvlStrRef below [n]. */
    private fun refOf(n: N): String? { val r = n.find("f"); return r?.text?.trim()?.takeIf { it.isNotEmpty() } }

    /** Cached points below [n] (strRef / numRef / literals): list by idx (null = missing) + formatCode. */
    private fun cache(n: N): Pair<List<String?>, String?> {
        val c = n.find("strCache") ?: n.find("numCache") ?: n.find("strLit") ?: n.find("numLit") ?: n.find("lvl") ?: return Pair(emptyList(), null)
        val cnt = c.kid("ptCount")?.v?.toIntOrNull() ?: 0
        val pts = c.all("pt")
        var size = cnt.coerceIn(0, 20000)
        for (p in pts) { val i = p.idx?.toIntOrNull() ?: continue; if (i + 1 > size && i < 20000) size = i + 1 }
        val out = arrayOfNulls<String>(size)
        for (p in pts) { val i = p.idx?.toIntOrNull() ?: continue; if (i in 0 until size) out[i] = p.kid("v")?.text }
        return Pair(out.toList(), c.kid("formatCode")?.text?.trim())
    }

    /** `solidFill` / `gs` node -> "#RRGGBB" (srgbClr, schemeClr with lumMod / lumOff / tint / shade, sysClr lastClr). */
    internal fun colorOf(f: N, theme: Theme): String? {
        val c = f.kid("srgbClr") ?: f.kid("schemeClr") ?: f.kid("sysClr") ?: return null
        fun m(n: String) = c.kid(n)?.v?.toIntOrNull()
        val rgb = when (c.name) { "srgbClr" -> c.v; "sysClr" -> c.kid("lastClr")?.v ?: c.v?.takeIf { it.length == 6 }; else -> null }
        val sch = if (c.name == "schemeClr") c.v else null
        if (rgb == null && sch == null) return null
        val r = theme.draw(sch, rgb?.takeIf { it.length == 6 }?.uppercase(), m("lumMod"), m("lumOff"), m("tint"), m("shade")) ?: return null
        return "#$r"
    }

    internal fun fmtNum(d: Double, code: String?): String =
        try { if (code == null || code.equals("General", true)) NumFmt.general(d) else NumFmt.format(d, code, false) } catch (_: Throwable) { NumFmt.general(d) }
}
