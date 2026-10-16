package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lanshare.app.core.SCat
import com.lanshare.app.core.SNode

/**
 * Storage Analyzer, part 4 = the treemap. A plain custom [View] (no dependencies) that draws the size tree of a finished scan
 * ([SNode], read directly from `Scan.root`) as nested rectangles, WizTree style:
 *  - squarified layout (Bruls et al.): tile area = size, tiles as square as possible;
 *  - folders that get enough room are drawn as a box with a name header and their content inside; smaller folders are one solid tile
 *    (colour of their biggest file category, darker than file tiles); files are coloured by category ([SCat]); the "N small files" node is grey;
 *  - tiles under [MIN_AREA] dp² are not drawn (the parent's background shows), so the number of rectangles stays bounded (hard cap [MAXR]);
 *  - labels (name, size) only where the tile is big enough to read them;
 *  - tap = hit-test, the deepest tile under the finger is selected (accent outline) and reported through [onTap].
 * The layout is computed once per root / size change into flat arrays (parents are stored before their children), drawing only walks the arrays.
 * Part 7 added [hl] (category highlight). Drill-in navigation and the list view are in StorageActivity / StorageList; actions come in part 8.
 * NOT compiled / NOT device-tested.
 */
class StorageTreemap(ctx: Context) : View(ctx) {
    /** Called with the node under the finger (file, folder or the synthetic "N small files" node). */
    var onTap: ((SNode) -> Unit)? = null

    var colBg = Color.WHITE
    var colFg = 0xFF161616.toInt()
    var colDim = 0xFF5F5F5F.toInt()
    var colAcc = 0xFFC25100.toInt()

    /** Category to highlight (SCat index, -1 = none, part 7): file tiles of other categories, the "small files" tile and solid folder tiles whose biggest category is another one are drawn faded. */
    var hl = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    private var root: SNode? = null
    private var selNode: SNode? = null
    private var selIdx = -1

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // layout store: tile i = (rx, ry, rw, rh, nd, kind); a parent is always stored before its children
    private val rx = FloatArray(MAXR)
    private val ry = FloatArray(MAXR)
    private val rw = FloatArray(MAXR)
    private val rh = FloatArray(MAXR)
    private val nd = arrayOfNulls<SNode>(MAXR)
    private val kind = IntArray(MAXR)
    private var n = 0

    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init { contentDescription = "Treemap" }

    /** The folder whose content is shown (null = nothing). Its kids fill the whole view. */
    fun setRoot(r: SNode?) {
        root = r; selNode = null; selIdx = -1
        relayout(); invalidate()
    }

    /** Currently selected node (null = none). */
    val selected: SNode? get() = selNode

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout(); invalidate()
    }

    // ---------------------------------------------------------------- layout
    private fun relayout() {
        n = 0; selIdx = -1
        val r = root ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f || r.kids.isEmpty()) return
        squarify(r, 0f, 0f, w, h, 1)
        val s = selNode
        if (s != null) for (i in 0 until n) if (nd[i] === s) { selIdx = i; break }
    }

    /** Lays the kids of [parent] out inside the rectangle (squarified) and records them (folders recurse through [place]). */
    private fun squarify(parent: SNode, x0: Float, y0: Float, w0: Float, h0: Float, depth: Int) {
        if (w0 <= 1f || h0 <= 1f) return
        val kids = parent.kids
        var tot = 0.0
        for (k in kids) if (k.size > 0) tot += k.size.toDouble()
        if (tot <= 0.0) return
        // drop the kids that would be smaller than MIN_AREA (they are the last ones: kids come biggest first, but do not rely on it)
        val minA = dp(MIN_AREA_DP) * dp(MIN_AREA_DP)
        val scale0 = (w0.toDouble() * h0) / tot
        val idx = IntArray(kids.size)
        var cnt = 0
        var tot2 = 0.0
        for (i in kids.indices) {
            val sz = kids[i].size
            if (sz > 0 && sz * scale0 >= minA) { idx[cnt++] = i; tot2 += sz.toDouble() }
        }
        if (cnt == 0) return

        var x = x0; var y = y0; var w = w0; var h = h0
        var i = 0
        var remaining = tot2
        while (i < cnt && w > 0.5f && h > 0.5f) {
            val sc = (w.toDouble() * h) / remaining
            val side = Math.min(w, h).toDouble()
            // grow the row while the worst aspect ratio does not get worse
            var j = i
            var rowSum = 0.0; var rowMax = 0.0; var rowMin = Double.MAX_VALUE
            var worst = Double.MAX_VALUE
            while (j < cnt) {
                val a = kids[idx[j]].size * sc
                val ns = rowSum + a
                val mx = Math.max(rowMax, a); val mn = Math.min(rowMin, a)
                val nw = Math.max(side * side * mx / (ns * ns), (ns * ns) / (side * side * mn))
                if (j > i && nw > worst) break
                rowSum = ns; rowMax = mx; rowMin = mn; worst = nw; j++
            }
            val thick = (rowSum / side).toFloat()
            var rowBytes = 0.0
            if (w >= h) {                       // row = a column on the left, tiles stacked top to bottom
                var cy = y
                for (k in i until j) {
                    val kid = kids[idx[k]]; rowBytes += kid.size.toDouble()
                    val th = if (k == j - 1) (y + h) - cy else (kid.size * sc / thick).toFloat()
                    place(kid, x, cy, thick, th, depth)
                    cy += th
                }
                x += thick; w -= thick
            } else {                            // row = a strip on top, tiles side by side
                var cx = x
                for (k in i until j) {
                    val kid = kids[idx[k]]; rowBytes += kid.size.toDouble()
                    val tw = if (k == j - 1) (x + w) - cx else (kid.size * sc / thick).toFloat()
                    place(kid, cx, y, tw, thick, depth)
                    cx += tw
                }
                y += thick; h -= thick
            }
            remaining -= rowBytes
            i = j
            if (remaining <= 0.0) break
        }
    }

    private fun place(k: SNode, x: Float, y: Float, w: Float, h: Float, depth: Int) {
        if (n >= MAXR || w <= 0f || h <= 0f) return
        val i = n++
        rx[i] = x; ry[i] = y; rw[i] = w; rh[i] = h; nd[i] = k
        if (k.agg) { kind[i] = K_AGG; return }
        if (!k.dir) { kind[i] = K_FILE; return }
        val canSplit = k.kids.isNotEmpty() && depth < MAXD && n < MAXR * 8 / 10 && w >= dp(MIN_BOX_W_DP) && h >= dp(MIN_BOX_H_DP)
        if (!canSplit) { kind[i] = K_DIRLEAF; return }
        val hdr = if (w >= dp(60f) && h >= dp(46f)) dp(HEADER_DP) else 0f
        kind[i] = if (hdr > 0f) K_BOX else K_BOXNH
        val pad = dp(PAD_DP)
        val before = n
        squarify(k, x + pad, y + pad + hdr, w - 2 * pad, h - 2 * pad - hdr, depth + 1)
        if (n == before) kind[i] = K_DIRLEAF        // nothing fitted inside: show the folder as one solid tile
    }

    // ---------------------------------------------------------------- colours
    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt())
    }

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun tileColor(i: Int): Int {
        val k = nd[i]!!
        return when (kind[i]) {
            K_FILE -> CAT_COL[k.cat.coerceIn(0, SCat.COUNT - 1)]
            K_AGG -> mix(colBg, colFg, 0.30f)
            K_DIRLEAF -> mix(CAT_COL[domCat(k)], 0xFF000000.toInt(), 0.28f)
            else -> mix(colBg, colFg, 0.10f)         // folder boxes
        }
    }

    /** Category with the most bytes below a folder (OTHER when it has no files). */
    private fun domCat(k: SNode): Int {
        val c = k.cats ?: return SCat.OTHER
        var b = 0; var bv = -1L
        for (i in 0 until Math.min(c.size, SCat.COUNT)) if (c[i] > bv) { bv = c[i]; b = i }
        return b
    }

    /** Part 7: true when tile [i] does not belong to the highlighted category [hl] (folder boxes are never faded, their content is). */
    private fun faded(i: Int): Boolean {
        val k = nd[i] ?: return false
        return when (kind[i]) {
            K_FILE -> k.cat.coerceIn(0, SCat.COUNT - 1) != hl
            K_AGG -> true
            K_DIRLEAF -> domCat(k) != hl
            else -> false
        }
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colBg)
        if (n == 0) {
            tp.color = colDim; tp.textSize = dp(14f); tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT
            canvas.drawText("Nothing to show", width / 2f, height / 2f, tp)
            return
        }
        val ts = dp(11f) * resources.configuration.fontScale
        val pad = dp(3f)
        val half = dp(0.6f)
        tp.textAlign = Paint.Align.LEFT; tp.textSize = ts
        for (i in 0 until n) {
            val x = rx[i]; val y = ry[i]; val w = rw[i]; val h = rh[i]
            var c = tileColor(i)
            if (hl >= 0 && faded(i)) c = mix(c, colBg, 0.8f)       // category highlight (part 7)
            fill.color = c
            val kd = kind[i]
            if (kd == K_BOX || kd == K_BOXNH) canvas.drawRect(x, y, x + w, y + h, fill)
            else canvas.drawRect(x + half, y + half, x + w - half, y + h - half, fill)
            val k = nd[i]!!
            if (kd == K_BOX) {
                tp.color = colFg; tp.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(fit(k.name + "  ·  " + NlModel.fmt(k.size), w - 2 * pad), x + pad, y + dp(PAD_DP) + ts, tp)
            } else if (kd != K_BOXNH && w >= dp(46f) && h >= dp(24f)) {
                tp.color = if (lum(c) > 0.6) 0xFF111111.toInt() else Color.WHITE
                tp.typeface = Typeface.DEFAULT
                val name = if (k.agg) k.files.toString() + " small files" else k.name
                canvas.drawText(fit(name, w - 2 * pad), x + pad, y + pad + ts, tp)
                if (h >= dp(38f)) canvas.drawText(fit(NlModel.fmt(k.size), w - 2 * pad), x + pad, y + pad + ts * 2.2f, tp)
            }
        }
        if (selIdx in 0 until n) {
            stroke.color = colAcc; stroke.strokeWidth = dp(2.5f)
            val s = dp(1.25f)
            canvas.drawRect(rx[selIdx] + s, ry[selIdx] + s, rx[selIdx] + rw[selIdx] - s, ry[selIdx] + rh[selIdx] - s, stroke)
        }
    }

    /** [s] cut to [maxW] pixels with an ellipsis (uses the current paint). */
    private fun fit(s: String, maxW: Float): String {
        if (s.isEmpty() || maxW <= 0f) return ""
        val cnt = tp.breakText(s, true, maxW, null)
        if (cnt >= s.length) return s
        return if (cnt <= 1) s.substring(0, cnt) else s.substring(0, cnt - 1) + "…"
    }

    // ---------------------------------------------------------------- touch
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (Math.abs(e.x - downX) <= slop && Math.abs(e.y - downY) <= slop) { performClick(); hit(e.x, e.y) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Deepest tile under the point: tiles are stored parents first, so the last one that contains the point is the deepest. */
    private fun hit(px: Float, py: Float) {
        var i = n - 1
        while (i >= 0) {
            if (px >= rx[i] && px < rx[i] + rw[i] && py >= ry[i] && py < ry[i] + rh[i]) break
            i--
        }
        if (i < 0) return
        selIdx = i; selNode = nd[i]
        invalidate()
        nd[i]?.let { onTap?.invoke(it) }
    }

    companion object {
        private const val MAXR = 20000            // hard cap of drawn tiles
        private const val MAXD = 12               // max folder nesting drawn
        private const val MIN_AREA_DP = 3f        // tiles smaller than this (dp x dp) are left out
        private const val MIN_BOX_W_DP = 30f      // a folder smaller than this stays one solid tile
        private const val MIN_BOX_H_DP = 24f
        private const val HEADER_DP = 15f
        private const val PAD_DP = 1.5f
        private const val K_FILE = 0
        private const val K_AGG = 1
        private const val K_DIRLEAF = 2
        private const val K_BOX = 3               // folder with name header, content inside
        private const val K_BOXNH = 4             // folder box without header
        /** File category colours, SCat order: other img vid aud doc arc app. */
        val CAT_COL = intArrayOf(
            0xFF8E8E93.toInt(),   // other  - grey
            0xFF43A047.toInt(),   // img    - green
            0xFFE64A19.toInt(),   // vid    - red-orange
            0xFF8E24AA.toInt(),   // aud    - purple
            0xFF1E88E5.toInt(),   // doc    - blue
            0xFFFBC02D.toInt(),   // arc    - amber
            0xFF00ACC1.toInt()    // app    - teal
        )
    }
}
