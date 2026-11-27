package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** L7 black stage; control panels use Lumen slots and a contrast-safe G2 tint stand-in. */
class PlayerPal(c: Context) {
    private val lm = Lumen.of(c)
    val bg = android.graphics.Color.BLACK
    val text = android.graphics.Color.WHITE
    private val white = android.graphics.Color.WHITE
    private val black = android.graphics.Color.BLACK
    private fun contrast(f: Int, b: Int) = androidx.core.graphics.ColorUtils.calculateContrast(f or black, b or black)
    private fun readable(f: Int, colors: List<Int>): Int? =
        listOf(f, black, white).distinct().firstOrNull { fg -> colors.all { contrast(fg, it) >= 4.5 } }
    private fun extremes(fill: Int) = listOf(black, white).map { androidx.core.graphics.ColorUtils.compositeColors(fill, it) }
    private val tinted = LumenGlass.chromeFill(c, lm.surfaceHi or black)
    val panel = if (readable(lm.ink, extremes(tinted)) != null) tinted else lm.surfaceHi or black
    val icon = readable(lm.ink, extremes(panel)) ?: lm.ink
    val edge = lm.edge
    val focus = lm.focus
    val track = androidx.core.graphics.ColorUtils.blendARGB(lm.surfaceHi or black, icon or black, 0.35f)
    val fill = readable(lm.primary, extremes(panel)) ?: icon
    val buffered = androidx.core.graphics.ColorUtils.blendARGB(track, icon or black, 0.38f)
    private val grad = LumenGrad.primary(lm)
    private val gradInk = readable(lm.onPrimary, (0..16).map { androidx.core.graphics.ColorUtils.blendARGB(grad[0], grad[1], it / 16f) })
    val playColors = if (gradInk != null) grad else intArrayOf(lm.primary, lm.primary)
    val playInk = gradInk ?: readable(lm.onPrimary, playColors.toList()) ?: white
}

/** Icons are painted with canvas primitives (no drawable resources, no font glyphs that differ per device). */
object PIc {
    const val PLAY = 1; const val PAUSE = 2; const val PREV = 3; const val NEXT = 4
    const val ROT_L = 5; const val ROT_R = 6; const val SCREEN = 7; const val SHARE = 8
    const val TRASH = 9; const val MORE = 10; const val BACK = 11; const val CAL = 12

    private val path = Path()
    private val rf = RectF()
    private val canonical = mapOf(PLAY to "play", PAUSE to "pause", PREV to "prev", NEXT to "prev",
        BACK to "back", ROT_L to "rotr", ROT_R to "rotr", SHARE to "share", TRASH to "del", MORE to "more")
    private val canonicalPaths = canonical.values.distinct().mapNotNull { key ->
        LumenIcons.D[key]?.let { data -> try { androidx.core.graphics.PathParser.createPathFromPathData(data)?.let { key to it } } catch (_: Throwable) { null } }
    }.toMap()

    /** Draws icon [k] centred at (cx, cy), fitting a box of [s] px. [p] is a stroke/fill paint already coloured. */
    fun draw(c: Canvas, k: Int, cx: Float, cy: Float, s: Float, p: Paint) {
        val u = s / 24f                       // 24 x 24 design grid
        c.save(); c.translate(cx - s / 2f, cy - s / 2f)
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = LumenIcons.SW * u
        val shared = canonicalPaths[canonical[k]]
        if (shared != null) {
            c.scale(u, u)
            if (k == NEXT || k == ROT_L) c.scale(-1f, 1f, 12f, 12f)
            p.style = Paint.Style.STROKE; p.strokeWidth = LumenIcons.SW
            c.drawPath(shared, p); c.restore(); return
        }
        when (k) {
            PLAY -> { fill(p); path.reset(); path.moveTo(8*u, 5*u); path.lineTo(19*u, 12*u); path.lineTo(8*u, 19*u); path.close(); c.drawPath(path, p) }
            PAUSE -> { fill(p); rf.set(6*u, 5*u, 10.2f*u, 19*u); c.drawRoundRect(rf, u, u, p); rf.set(13.8f*u, 5*u, 18*u, 19*u); c.drawRoundRect(rf, u, u, p) }
            PREV -> { fill(p); rf.set(5*u, 5*u, 7.4f*u, 19*u); c.drawRect(rf, p); tri(c, p, 19*u, 5*u, 19*u, 19*u, 8.5f*u, 12*u) }
            NEXT -> { fill(p); rf.set(16.6f*u, 5*u, 19*u, 19*u); c.drawRect(rf, p); tri(c, p, 5*u, 5*u, 5*u, 19*u, 15.5f*u, 12*u) }
            BACK -> { line(p); path.reset(); path.moveTo(19*u, 12*u); path.lineTo(5*u, 12*u); path.moveTo(11*u, 5.5f*u); path.lineTo(4.5f*u, 12*u); path.lineTo(11*u, 18.5f*u); c.drawPath(path, p) }
            ROT_R -> circArrow(c, p, u, false)
            ROT_L -> { c.save(); c.scale(-1f, 1f, 12*u, 12*u); circArrow(c, p, u, false); c.restore() }
            SCREEN -> {   // a phone drawn on the diagonal with a turning arrow on each side
                line(p)
                c.save(); c.rotate(45f, 12*u, 12*u)
                rf.set(8.3f*u, 6.2f*u, 15.7f*u, 17.8f*u); c.drawRoundRect(rf, 1.8f*u, 1.8f*u, p)
                c.restore()
                rf.set(2.5f*u, 2.5f*u, 21.5f*u, 21.5f*u)
                c.drawArc(rf, 200f, 50f, false, p); head(c, p, 12*u, 12*u, 9.5f*u, 250f, true, 3.2f*u)
                c.drawArc(rf, 20f, 50f, false, p); head(c, p, 12*u, 12*u, 9.5f*u, 70f, true, 3.2f*u)
            }
            SHARE -> {
                line(p)
                path.reset(); path.moveTo(7.6f*u, 10.8f*u); path.lineTo(16.4f*u, 6.2f*u); path.moveTo(7.6f*u, 13.2f*u); path.lineTo(16.4f*u, 17.8f*u); c.drawPath(path, p)
                fill(p); c.drawCircle(18*u, 5.5f*u, 2.6f*u, p); c.drawCircle(6*u, 12*u, 2.6f*u, p); c.drawCircle(18*u, 18.5f*u, 2.6f*u, p)
            }
            TRASH -> {
                line(p)
                path.reset(); path.moveTo(4*u, 6.5f*u); path.lineTo(20*u, 6.5f*u); path.moveTo(9*u, 6.5f*u); path.lineTo(9*u, 4*u); path.lineTo(15*u, 4*u); path.lineTo(15*u, 6.5f*u)
                path.moveTo(6*u, 6.5f*u); path.lineTo(7*u, 19.5f*u); path.lineTo(17*u, 19.5f*u); path.lineTo(18*u, 6.5f*u)
                path.moveTo(10*u, 10*u); path.lineTo(10*u, 16*u); path.moveTo(14*u, 10*u); path.lineTo(14*u, 16*u)
                c.drawPath(path, p)
            }
            MORE -> { fill(p); c.drawCircle(12*u, 5*u, 2f*u, p); c.drawCircle(12*u, 12*u, 2f*u, p); c.drawCircle(12*u, 19*u, 2f*u, p) }
            CAL -> {
                line(p); p.strokeWidth = 1.8f * u
                rf.set(3.5f*u, 5*u, 20.5f*u, 20*u); c.drawRoundRect(rf, 3*u, 3*u, p)
                path.reset(); path.moveTo(3.5f*u, 10*u); path.lineTo(20.5f*u, 10*u); path.moveTo(8*u, 2.8f*u); path.lineTo(8*u, 6.8f*u); path.moveTo(16*u, 2.8f*u); path.lineTo(16*u, 6.8f*u)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }

    private fun fill(p: Paint) { p.style = Paint.Style.FILL }
    private fun line(p: Paint) { p.style = Paint.Style.STROKE }
    private fun tri(c: Canvas, p: Paint, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close(); c.drawPath(path, p)
    }

    /** Clockwise circular arrow: ~300 degree arc whose end (upper left) carries the arrow head. */
    private fun circArrow(c: Canvas, p: Paint, u: Float, @Suppress("UNUSED_PARAMETER") ccw: Boolean) {
        line(p)
        rf.set(4.5f*u, 4.5f*u, 19.5f*u, 19.5f*u)
        c.drawArc(rf, -55f, 285f, false, p)
        head(c, p, 12*u, 12*u, 7.5f*u, 230f, true, 4.2f*u)
    }

    /** Arrow head on the circle (centre cx,cy radius r) at canvas angle [deg], pointing along the clockwise (or counter-clockwise) tangent. */
    private fun head(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, deg: Float, cw: Boolean, len: Float) {
        val a = Math.toRadians(deg.toDouble())
        val px = cx + r * Math.cos(a).toFloat(); val py = cy + r * Math.sin(a).toFloat()
        val tx = (if (cw) -Math.sin(a) else Math.sin(a)).toFloat(); val ty = (if (cw) Math.cos(a) else -Math.cos(a)).toFloat()
        val nx = -ty; val ny = tx
        val bx = px + tx * len * 0.35f; val by = py + ty * len * 0.35f      // tip slightly ahead of the arc end
        val tipX = bx; val tipY = by
        path.reset()
        path.moveTo(tipX - tx * len + nx * len * 0.75f, tipY - ty * len + ny * len * 0.75f)
        path.lineTo(tipX, tipY)
        path.lineTo(tipX - tx * len - nx * len * 0.75f, tipY - ty * len - ny * len * 0.75f)
        val st = p.style; p.style = Paint.Style.STROKE
        c.drawPath(path, p); p.style = st
    }
}

/** Round frosted button with one icon (the circles of the control rows). */
class RoundBtn(c: Context, private val kind: Int) : View(c) {
    var pal: PlayerPal = PlayerPal(c); set(v) { field = v; invalidate() }
    var iconKind = kind; set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    init { isClickable = true; isFocusable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = Math.min(w, h) / 2f
        if (r <= d) return
        val primary = kind == PIc.PLAY
        bg.style = Paint.Style.FILL
        bg.color = if (primary) android.graphics.Color.WHITE else pal.panel
        if (primary) bg.shader = LumenGrad.diag(pal.playColors, 0f, 0f, w, h)
        c.drawCircle(w / 2f, h / 2f, r - d, bg)
        bg.shader = null
        bg.style = Paint.Style.STROKE; bg.strokeWidth = if (isFocused) 2f * d else d
        bg.color = if (isFocused) pal.focus else pal.edge
        c.drawCircle(w / 2f, h / 2f, r - d, bg)
        ic.color = if (primary) pal.playInk else pal.icon
        PIc.draw(c, iconKind, w / 2f, h / 2f, minOf(24f * d, r), ic)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { alpha = 0.55f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { alpha = if (isEnabled) 1f else 0.4f }
        }
        return super.onTouchEvent(e)
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.4f }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate() }
    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
}

/**
 * The seek pill: current time left, total right, thin progress bar under them. Touch or drag anywhere on it to seek;
 * [onSeek] gets the new position while dragging (preview = true) and when the finger is lifted (preview = false).
 */
class SeekPill(c: Context) : View(c) {
    var pal: PlayerPal = PlayerPal(c); set(v) { field = v; invalidate() }
    var onSeek: ((fraction: Float, preview: Boolean) -> Unit)? = null
    var onTouching: ((Boolean) -> Unit)? = null
    var posMs = 0L; var durMs = 0L; var bufMs = 0L

    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); fontFeatureSettings = "tnum" }   // times: tabular figures (spec 7)
    private val rf = RectF()
    private var drag = false
    private var dragFrac = 0f

    init { isFocusable = true; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription = "Playback position" }

    fun set(pos: Long, dur: Long, buf: Long = 0L) {
        if (drag) return
        if (pos == posMs && dur == durMs && buf == bufMs) return
        posMs = pos; durMs = dur; bufMs = buf; invalidate()
    }

    private fun padX() = 18f * d
    private fun frac(): Float = if (drag) dragFrac else if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        rf.set(d, d, w - d, h - d)
        p.style = Paint.Style.FILL; p.color = pal.panel; c.drawRoundRect(rf, r, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = if (isFocused) 2f * d else d; p.color = if (isFocused) pal.focus else pal.edge; c.drawRoundRect(rf, r, r, p)

        tp.color = pal.icon; tp.typeface = Fnt.semi()
        tp.textSize = minOf(LumenTypography.LABEL_M.sp * resources.displayMetrics.scaledDensity, h * 0.34f)
        val shown = if (drag) (dragFrac * durMs).toLong() else posMs
        val left = fmt(shown); val right = fmt(durMs)
        val room = (w - 2 * padX() - 8f * d).coerceAtLeast(1f)
        val total = tp.measureText(left) + tp.measureText(right)
        if (total > room) tp.textSize *= room / total
        val ty = h * 0.36f - (tp.ascent() + tp.descent()) / 2f
        tp.textAlign = Paint.Align.LEFT; c.drawText(left, padX(), ty, tp)
        tp.textAlign = Paint.Align.RIGHT; c.drawText(right, w - padX(), ty, tp)

        val bh = 4f * d; val by = h * 0.72f
        val x0 = padX(); val x1 = w - padX()
        p.style = Paint.Style.FILL; p.color = pal.track; rf.set(x0, by - bh / 2, x1, by + bh / 2); c.drawRoundRect(rf, bh / 2, bh / 2, p)
        if (x1 <= x0) return
        val buffered = if (durMs > 0) (bufMs.toDouble() / durMs).coerceIn(0.0, 1.0).toFloat() else 0f
        val bx = x0 + (x1 - x0) * buffered
        p.color = pal.buffered; rf.set(x0, by - bh / 2, bx, by + bh / 2); if (bx > x0) c.drawRoundRect(rf, bh / 2, bh / 2, p)
        val fx = x0 + (x1 - x0) * frac()
        p.color = pal.fill; rf.set(x0, by - bh / 2, fx, by + bh / 2); if (fx > x0) c.drawRoundRect(rf, bh / 2, bh / 2, p)
        if (durMs > 0) c.drawCircle(fx, by, (if (drag) 8f else 6f) * d, p)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.SeekBar::class.java.name
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = "Playback position, " + fmt(posMs) + " of " + fmt(durMs)
        if (durMs > 0) {
            info.rangeInfo = android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 100f, frac() * 100f)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        }
    }

    private fun seekAccessible(fraction: Float): Boolean {
        if (durMs <= 0 || !fraction.isFinite()) return false
        val f = fraction.coerceIn(0f, 1f)
        posMs = (f * durMs).toLong()
        onSeek?.invoke(f, false)
        invalidate()
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val info = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS
        return when (action) {
            info.id -> if (arguments?.containsKey(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) == true)
                seekAccessible(arguments.getFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) / 100f) else false
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> seekAccessible(frac() + 0.05f)
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> seekAccessible(frac() - 0.05f)
            else -> super.performAccessibilityAction(action, arguments)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> seekAccessible(frac() + 0.05f)
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> seekAccessible(frac() - 0.05f)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) { super.onFocusChanged(gainFocus, direction, previouslyFocusedRect); invalidate() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (durMs <= 0) { if (drag && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) { drag = false; onTouching?.invoke(false); parent?.requestDisallowInterceptTouchEvent(false) }; return true }
        val x0 = padX(); val x1 = width - padX()
        val f = ((e.x - x0) / Math.max(x1 - x0, 1f)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); drag = true; dragFrac = f; onTouching?.invoke(true); onSeek?.invoke(f, true); invalidate() }
            MotionEvent.ACTION_MOVE -> { if (drag) { dragFrac = f; onSeek?.invoke(f, true); invalidate() } }
            MotionEvent.ACTION_UP -> { if (drag) { drag = false; posMs = (f * durMs).toLong(); onSeek?.invoke(f, false); onTouching?.invoke(false); parent?.requestDisallowInterceptTouchEvent(false); performClick(); invalidate() } }
            MotionEvent.ACTION_CANCEL -> { if (drag) { drag = false; onTouching?.invoke(false); parent?.requestDisallowInterceptTouchEvent(false); invalidate() } }
        }
        return true
    }

    companion object {
        fun fmt(ms: Long): String {
            val s = Math.max(0L, ms) / 1000
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
        }
    }
}

/** A bare icon (e.g. the little calendar in the header). */
class IconView(c: Context, private val kind: Int) : View(c) {
    var pal: PlayerPal = PlayerPal(c); set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        p.color = pal.text
        PIc.draw(c, kind, width / 2f, height / 2f, Math.min(width, height).toFloat(), p)
    }
}
