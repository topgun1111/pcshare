package com.lanshare.app

import android.animation.ValueAnimator
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
import android.view.animation.DecelerateInterpolator
import com.lanshare.app.core.SCat
import com.lanshare.app.core.SNode

/**
 * Storage Analyzer: the pie chart view (donut) of the open folder's direct children ([SNode.kids], biggest first).
 *  - slice angle = share of the folder's size; files coloured by category ([StorageTreemap.CAT_COL]), folders by their biggest
 *    category (darker), "N small files" grey; slices under [MIN_DEG] degrees are merged into one grey, non-tappable "smaller items" slice;
 *  - labels are sized to the room a slice really has (arc length x ring thickness, depending on its angle), not to fixed degree limits;
 *  - the hole shows the open folder (name, size, files) or the selected slice (name, size, share);
 *  - tap = select (animated pull-out, accent outline) + [onTap]; the same node tapped again is handled by StorageActivity;
 *  - opening another folder grows the ring from 12 o'clock; all strings / colours are computed once per layout, onDraw does not allocate.
 * Public surface unchanged: [onTap], [colBg] / [colFg] / [colDim] / [colAcc], [hl], [setRoot], [selected].
 */
class StoragePie(ctx: Context) : View(ctx) {
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
    private var prevSel: SNode? = null

    // slice i = nd[i] (null = merged "smaller items"), start angle st[i], sweep sw[i] in degrees (0 = 12 o'clock, clockwise)
    private val nd = arrayOfNulls<SNode>(MAXS)
    private val st = FloatArray(MAXS)
    private val sw = FloatArray(MAXS)
    private val col = IntArray(MAXS)
    private val nm = arrayOfNulls<String>(MAXS)
    private val sz = arrayOfNulls<String>(MAXS)
    private var n = 0
    private var total = 0.0

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val outer = RectF()
    private val inner = RectF()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    private var cx = 0f
    private var cy = 0f
    private var rOut = 0f
    private var rIn = 0f

    // animations: prog = ring grow-in (0..1), selT = pull-out of the selected slice (0..1)
    private var prog = 1f
    private var selT = 1f
    private val grow = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480; interpolator = DecelerateInterpolator(1.6f)
        addUpdateListener { prog = it.animatedValue as Float; invalidate() }
    }
    private val pullAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 200; interpolator = DecelerateInterpolator()
        addUpdateListener { selT = it.animatedValue as Float; invalidate() }
    }

    init { contentDescription = "Pie chart" }

    /** The folder whose content is shown (null = nothing). */
    fun setRoot(r: SNode?) {
        val same = r === root
        root = r; selNode = null; prevSel = null; selT = 1f
        pullAnim.cancel()
        relayout()
        if (!same && n > 0) { prog = 0f; grow.cancel(); grow.start() } else { grow.cancel(); prog = 1f }
        invalidate()
    }

    /** Currently selected node (null = none). */
    val selected: SNode? get() = selNode

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout(); invalidate()
    }

    override fun onDetachedFromWindow() {
        grow.cancel(); pullAnim.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- layout
    private fun relayout() {
        n = 0; total = 0.0
        val w = width.toFloat(); val h = height.toFloat()
        val r = root
        if (r == null || w <= 0f || h <= 0f) return
        val margin = dp(18f)                       // room for the pulled-out slice
        rOut = (Math.min(w, h) / 2f - margin).coerceAtLeast(dp(40f))
        rIn = rOut * 0.52f
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
        for (i in 0 until n) {                      // strings + colours once per layout
            val k = nd[i]
            col[i] = sliceColor(i)
            nm[i] = when { k == null -> "smaller items"; k.agg -> k.files.toString() + " small files"; else -> k.name }
            sz[i] = if (k != null) NlModel.fmt(k.size) else null
        }
        contentDescription = "Pie chart: " + (if (r.name.isEmpty()) "Storage" else r.name) + ", " + NlModel.fmt(r.size) + ", " + n + " slices"
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

    /** Current pull-out distance of slice [i] in px (selected grows, the previously selected one shrinks). */
    private fun pullOf(i: Int): Float {
        val k = nd[i] ?: return 0f
        return when {
            k === selNode -> dp(9f) * selT
            k === prevSel -> dp(9f) * (1f - selT)
            else -> 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colBg)
        if (n == 0) {
            tp.color = colDim; tp.textSize = dp(14f); tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT
            canvas.drawText("Nothing to show", width / 2f, height / 2f, tp)
            return
        }
        val ts = dp(11f) * resources.configuration.fontScale
        var selI = -1; var prevI = -1
        for (i in 0 until n) {
            val k = nd[i] ?: continue
            if (k === selNode) selI = i else if (k === prevSel) prevI = i
        }
        for (i in 0 until n) if (i != selI && i != prevI) drawSlice(canvas, i, ts, false)   // selected / previous drawn last (on top)
        if (prevI >= 0) drawSlice(canvas, prevI, ts, false)
        if (selI >= 0) drawSlice(canvas, selI, ts, true)
        drawCenter(canvas, selI)
    }

    private fun drawSlice(canvas: Canvas, i: Int, ts: Float, selected: Boolean) {
        var c = col[i]
        if (hl >= 0 && faded(i)) c = mix(c, colBg, 0.8f)
        val from = st[i] * prog
        val sweep = sw[i] * prog
        val pull = pullOf(i)
        var ox = 0f; var oy = 0f
        if (pull > 0f) {
            val m = Math.toRadians((st[i] + sw[i] / 2f - 90f).toDouble())
            ox = (Math.cos(m) * pull).toFloat(); oy = (Math.sin(m) * pull).toFloat()
        }
        fill.color = c
        sector(from, sweep, rIn, rOut, ox, oy)
        canvas.drawPath(path, fill)
        if (selected) { stroke.color = colAcc; stroke.strokeWidth = dp(2.5f) } else { stroke.color = colBg; stroke.strokeWidth = dp(1.5f) }
        canvas.drawPath(path, stroke)

        val la = ((prog - 0.6f) / 0.4f).coerceIn(0f, 1f)           // labels fade in at the end of the grow-in
        if (la <= 0f) return
        // room the slice really has: arc length along the ring, ring thickness across it
        val rm = (rIn + rOut) / 2f
        val thick = rOut - rIn
        val arc = Math.toRadians(sw[i].toDouble()).toFloat() * rm
        val midDeg = st[i] + sw[i] / 2f
        val rad = Math.toRadians(midDeg.toDouble())
        val ac = Math.abs(Math.cos(rad)).toFloat()                  // 1 at 12 / 6 o'clock (text runs along the arc)
        val as_ = Math.abs(Math.sin(rad)).toFloat()                 // 1 at 3 / 9 o'clock (text runs across the ring)
        val availW = ac * arc + as_ * thick
        val availH = ac * thick + as_ * arc
        if (availH < ts * 1.25f) return
        val twoLines = sz[i] != null && availH >= ts * 2.5f
        val mp = Math.toRadians((midDeg * prog - 90f).toDouble())
        val lx = cx + ox + (Math.cos(mp) * rm).toFloat()
        val ly = cy + oy + (Math.sin(mp) * rm).toFloat()
        val maxW = availW * 0.92f
        tp.textAlign = Paint.Align.CENTER; tp.textSize = ts
        tp.color = if (lum(c) > 0.6) 0xFF111111.toInt() else Color.WHITE
        tp.alpha = (la * 255f).toInt()
        tp.typeface = Typeface.DEFAULT_BOLD
        val name = fit(nm[i] ?: "", maxW)
        if (name.length < 3 && (nm[i]?.length ?: 0) > 3) return    // not even a readable stub fits
        if (twoLines) {
            canvas.drawText(name, lx, ly - ts * 0.15f, tp)
            tp.typeface = Typeface.DEFAULT
            canvas.drawText(fit(sz[i] ?: "", maxW), lx, ly + ts * 1.1f, tp)
        } else {
            canvas.drawText(name, lx, ly + ts * 0.35f, tp)
        }
        tp.alpha = 255
    }

    /** The hole: the selected slice (name, size, share) or the open folder (name, total). */
    private fun drawCenter(canvas: Canvas, selI: Int) {
        val r = root ?: return
        val k = if (selI >= 0) nd[selI] else null
        val name: String
        val sizeS: String
        val sub: String
        if (k != null) {
            name = nm[selI] ?: ""
            sizeS = sz[selI] ?: ""
            sub = pct(k.size)
        } else {
            name = if (r.name.isEmpty()) "Storage" else r.name
            sizeS = NlModel.fmt(r.size)
            sub = r.files.toString() + (if (r.files == 1L) " file" else " files")
        }
        val maxW = rIn * 1.7f
        val big = dp(20f) * resources.configuration.fontScale
        val small = dp(12f) * resources.configuration.fontScale
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.DEFAULT_BOLD; tp.color = colFg; tp.textSize = big
        canvas.drawText(fit(sizeS, maxW), cx, cy + big * 0.35f, tp)
        tp.typeface = Typeface.DEFAULT; tp.color = colDim; tp.textSize = small
        canvas.drawText(fit(name, maxW), cx, cy - big * 0.75f, tp)
        canvas.drawText(fit(sub, maxW), cx, cy + big * 0.35f + small * 1.6f, tp)
    }

    /** "12.3%" ("<0.1%" for tiny shares). */
    private fun pct(size: Long): String {
        val p = size * 100.0 / total
        return if (p < 0.1) "<0.1%" else String.format(java.util.Locale.US, "%.1f%%", p)
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

    /** Is the point inside slice [i] drawn with its centre moved by ([ox], [oy])? */
    private fun inSlice(i: Int, px: Float, py: Float, ox: Float, oy: Float): Boolean {
        val dx = px - cx - ox; val dy = py - cy - oy
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (dist < rIn - dp(4f) || dist > rOut + dp(10f)) return false
        var a = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (a < 0f) a += 360f
        if (a >= 360f) a -= 360f
        return a >= st[i] && a < st[i] + sw[i]
    }

    /** Slice under the point (ring only). The pulled-out slice is tested at its shifted position first; "smaller items" is not tappable. */
    private fun hit(px: Float, py: Float) {
        if (n == 0 || prog < 1f) return
        var found = -1
        for (i in 0 until n) {
            val k = nd[i]
            if (k != null && k === selNode) {
                val m = Math.toRadians((st[i] + sw[i] / 2f - 90f).toDouble())
                val pull = pullOf(i)
                if (inSlice(i, px, py, (Math.cos(m) * pull).toFloat(), (Math.sin(m) * pull).toFloat())) { found = i; break }
            }
        }
        if (found < 0) for (i in 0 until n) if (inSlice(i, px, py, 0f, 0f)) { found = i; break }
        if (found < 0) return
        val k = nd[found] ?: return
        if (k !== selNode) {
            prevSel = selNode; selNode = k; selT = 0f
            pullAnim.cancel(); pullAnim.start()
        }
        invalidate()
        onTap?.invoke(k)
    }

    companion object {
        private const val MAXS = 120              // max slices (including the merged one)
        private const val MIN_DEG = 1.5f          // slices narrower than this are merged into "smaller items"
    }
}
