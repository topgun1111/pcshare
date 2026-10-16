package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lanshare.app.core.SCat
import com.lanshare.app.core.SNode

/**
 * Storage Analyzer: the pie chart view (replaces the treemap in the first tab). A plain custom [View] (no dependencies) that draws the
 * direct children of the open folder ([SNode.kids], biggest first) as a donut chart:
 *  - slice angle = share of the folder's size; files are coloured by category ([StorageTreemap.CAT_COL]), folders by their biggest
 *    category (darker), the "N small files" node is grey;
 *  - slices under [MIN_DEG] degrees are merged into one grey "smaller items" slice (not tappable);
 *  - name + size inside the slice when it is wide enough to read them;
 *  - the hole shows the open folder (name, size); with a slice selected it shows that slice (name, size, share);
 *  - tap = the slice under the finger is selected (pulled out, accent outline) and reported through [onTap]; the same node tapped again
 *    is handled by StorageActivity (folder = open, file = details), exactly like the treemap did.
 * Same public surface as [StorageTreemap]: [onTap], [colBg] / [colFg] / [colDim] / [colAcc], [hl] (category highlight), [setRoot], [selected].
 */
class StoragePie(ctx: Context) : View(ctx) {
    /** Called with the node under the finger (file, folder or the synthetic "N small files" node). */
    var onTap: ((SNode) -> Unit)? = null

    var colBg = Color.WHITE
    var colFg = 0xFF161616.toInt()
    var colDim = 0xFF5F5F5F.toInt()
    var colAcc = 0xFFC25100.toInt()

    /** Category to highlight (SCat index, -1 = none): slices of other categories are drawn faded. */
    var hl = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    private var root: SNode? = null
    private var selNode: SNode? = null

    // slice store: slice i = nd[i] (null = the merged "smaller items" slice), start angle st[i] and sweep sw[i] in degrees (0 = 12 o'clock, clockwise)
    private val nd = arrayOfNulls<SNode>(MAXS)
    private val st = FloatArray(MAXS)
    private val sw = FloatArray(MAXS)
    private var n = 0
    private var total = 0.0

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val outer = RectF()
    private val inner = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    // geometry of the last layout
    private var cx = 0f
    private var cy = 0f
    private var rOut = 0f
    private var rIn = 0f

    init { contentDescription = "Pie chart" }

    /** The folder whose content is shown (null = nothing). */
    fun setRoot(r: SNode?) {
        root = r; selNode = null
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
        n = 0; total = 0.0
        val w = width.toFloat(); val h = height.toFloat()
        val r = root
        if (r == null || w <= 0f || h <= 0f) return
        val margin = dp(18f)                       // room for the pulled-out selected slice
        rOut = (Math.min(w, h) / 2f - margin).coerceAtLeast(dp(40f))
        rIn = rOut * 0.5f
        cx = w / 2f; cy = h / 2f
        for (k in r.kids) if (k.size > 0) total += k.size.toDouble()
        if (total <= 0.0) return
        var a = 0f
        var restSweep = 0f
        for (k in r.kids) {
            if (k.size <= 0) continue
            val deg = (k.size / total * 360.0).toFloat()
            if (deg < MIN_DEG || n >= MAXS - 1) { restSweep += deg; continue }
            nd[n] = k; st[n] = a; sw[n] = deg; n++
            a += deg
        }
        if (restSweep > 0.05f) { nd[n] = null; st[n] = a; sw[n] = restSweep; n++ }
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

    /** Category with the most bytes below a folder (OTHER when it has no files). */
    private fun domCat(k: SNode): Int {
        val c = k.cats ?: return SCat.OTHER
        var b = 0; var bv = -1L
        for (i in 0 until Math.min(c.size, SCat.COUNT)) if (c[i] > bv) { bv = c[i]; b = i }
        return b
    }

    private fun sliceColor(i: Int): Int {
        val k = nd[i] ?: return mix(colBg, colFg, 0.30f)
        if (k.agg) return mix(colBg, colFg, 0.30f)
        return if (k.dir) mix(StorageTreemap.CAT_COL[domCat(k)], 0xFF000000.toInt(), 0.28f)
        else StorageTreemap.CAT_COL[k.cat.coerceIn(0, SCat.COUNT - 1)]
    }

    private fun faded(i: Int): Boolean {
        val k = nd[i] ?: return true
        return when {
            k.agg -> true
            k.dir -> domCat(k) != hl
            else -> k.cat.coerceIn(0, SCat.COUNT - 1) != hl
        }
    }

    // ---------------------------------------------------------------- drawing
    /** Ring sector [from, from + sweep) between radius [r0] and [r1], centre moved by ([ox], [oy]). */
    private fun sector(from: Float, sweep: Float, r0: Float, r1: Float, ox: Float, oy: Float) {
        val s = Math.min(sweep, 359.9f)
        outer.set(cx + ox - r1, cy + oy - r1, cx + ox + r1, cy + oy + r1)
        inner.set(cx + ox - r0, cy + oy - r0, cx + ox + r0, cy + oy + r0)
        path.reset()
        path.arcTo(outer, from - 90f, s)
        path.arcTo(inner, from - 90f + s, -s)
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colBg)
        if (n == 0) {
            tp.color = colDim; tp.textSize = dp(14f); tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT
            canvas.drawText("Nothing to show", width / 2f, height / 2f, tp)
            return
        }
        val ts = dp(11f) * resources.configuration.fontScale
        var selI = -1
        for (i in 0 until n) if (nd[i] != null && nd[i] === selNode) selI = i
        stroke.color = colBg; stroke.strokeWidth = dp(1.5f)
        for (i in 0 until n) {
            if (i == selI) continue                         // drawn last (pulled out, on top)
            drawSlice(canvas, i, 0f, 0f, ts, false)
        }
        if (selI >= 0) {
            val mid = Math.toRadians((st[selI] + sw[selI] / 2f - 90f).toDouble())
            val pull = dp(8f)
            drawSlice(canvas, selI, (Math.cos(mid) * pull).toFloat(), (Math.sin(mid) * pull).toFloat(), ts, true)
        }
        drawCenter(canvas, selI)
    }

    private fun drawSlice(canvas: Canvas, i: Int, ox: Float, oy: Float, ts: Float, selected: Boolean) {
        var c = sliceColor(i)
        if (hl >= 0 && faded(i)) c = mix(c, colBg, 0.8f)       // category highlight
        fill.color = c
        sector(st[i], sw[i], rIn, rOut, ox, oy)
        canvas.drawPath(path, fill)
        if (selected) { stroke.color = colAcc; stroke.strokeWidth = dp(2.5f) } else { stroke.color = colBg; stroke.strokeWidth = dp(1.5f) }
        canvas.drawPath(path, stroke)
        stroke.color = colBg; stroke.strokeWidth = dp(1.5f)
        val k = nd[i]
        if (sw[i] < LABEL_DEG) return
        // label at the middle of the ring, horizontal, as wide as the ring is thick
        val mid = Math.toRadians((st[i] + sw[i] / 2f - 90f).toDouble())
        val rm = (rIn + rOut) / 2f
        val lx = cx + ox + (Math.cos(mid) * rm).toFloat()
        val ly = cy + oy + (Math.sin(mid) * rm).toFloat()
        val maxW = (rOut - rIn) * 0.95f
        tp.textAlign = Paint.Align.CENTER; tp.textSize = ts
        tp.color = if (lum(c) > 0.6) 0xFF111111.toInt() else Color.WHITE
        val name = when { k == null -> "smaller items"; k.agg -> k.files.toString() + " small files"; else -> k.name }
        tp.typeface = Typeface.DEFAULT_BOLD
        if (sw[i] >= SIZE_DEG && k != null) {
            canvas.drawText(fit(name, maxW), lx, ly - ts * 0.15f, tp)
            tp.typeface = Typeface.DEFAULT
            canvas.drawText(fit(NlModel.fmt(k.size), maxW), lx, ly + ts * 1.1f, tp)
        } else {
            canvas.drawText(fit(name, maxW), lx, ly + ts * 0.35f, tp)
        }
    }

    /** The hole: the selected slice (name, size, share) or the open folder (name, total). */
    private fun drawCenter(canvas: Canvas, selI: Int) {
        val r = root ?: return
        val k = if (selI >= 0) nd[selI] else null
        val name: String
        val sizeS: String
        val sub: String
        if (k != null) {
            name = if (k.agg) k.files.toString() + " small files" else k.name
            sizeS = NlModel.fmt(k.size)
            sub = String.format("%.1f%%", k.size * 100.0 / total)
        } else {
            name = if (r.name.isEmpty()) "Storage" else r.name
            sizeS = NlModel.fmt(r.size)
            sub = r.files.toString() + (if (r.files == 1L) " file" else " files")
        }
        val maxW = rIn * 1.7f
        val big = dp(18f) * resources.configuration.fontScale
        val small = dp(12f) * resources.configuration.fontScale
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.DEFAULT_BOLD; tp.color = colFg; tp.textSize = big
        canvas.drawText(fit(sizeS, maxW), cx, cy - small * 0.2f, tp)
        tp.typeface = Typeface.DEFAULT; tp.color = colDim; tp.textSize = small
        canvas.drawText(fit(name, maxW), cx, cy - big - small * 0.2f, tp)
        canvas.drawText(fit(sub, maxW), cx, cy + small * 1.5f, tp)
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

    /** Slice under the point (ring only; the hole and the outside do nothing). The "smaller items" slice is not tappable. */
    private fun hit(px: Float, py: Float) {
        if (n == 0) return
        val dx = px - cx; val dy = py - cy
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < rIn || dist > rOut + dp(10f)) return
        var a = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f      // 0 = 12 o'clock, clockwise
        if (a < 0f) a += 360f
        if (a >= 360f) a -= 360f
        for (i in 0 until n) {
            if (a >= st[i] && a < st[i] + sw[i]) {
                val k = nd[i] ?: return
                selNode = k
                invalidate()
                onTap?.invoke(k)
                return
            }
        }
    }

    companion object {
        private const val MAXS = 120              // max slices (including the merged one)
        private const val MIN_DEG = 1.5f          // slices narrower than this are merged into "smaller items"
        private const val LABEL_DEG = 14f         // name only
        private const val SIZE_DEG = 24f          // name + size
    }
}
