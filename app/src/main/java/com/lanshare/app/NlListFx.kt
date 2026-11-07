package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Lumen Part 4d (N9): press ripple for the native list views (row, grid card). Same look as `NlHitView.ripple()`:
 * 14 % ink from the touch point to the farthest corner in 280 ms (decelerate), fades 160 ms after release; with Reduce motion a steady 8 % fill.
 * Usage in a View: `rpl.touch(ev)` from onTouchEvent (before super), `rpl.draw(cv, rect, radius, color)` in onDraw after the background fill.
 */
class NlRpl(private val v: View) {
    private var on = false
    private var x = 0f
    private var y = 0f
    private var t0 = 0L
    private var end = 0L
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { on = true; x = e.x; y = e.y; t0 = SystemClock.uptimeMillis(); end = 0L; v.invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
        }
    }

    /** Finger lifted or the gesture turned into a scroll: let the ripple fade (4e: also called by views that drop the press themselves). */
    fun release() { if (on && end == 0L) { end = SystemClock.uptimeMillis(); v.invalidate() } }

    /** A recycled view must not carry a ripple of the previous row. */
    fun reset() { on = false; end = 0L }

    fun draw(cv: Canvas, shape: RectF, rad: Float, color: Int) {
        if (!on) return
        val rm = LumenPrefs.reduceMotion(v.context)
        val now = SystemClock.uptimeMillis()
        val grow = if (rm) 1f else Math.min(1f, (now - t0) / 280f)
        val e = 1f - (1f - grow) * (1f - grow)
        var a = if (rm) 0.57f else 1f
        if (end != 0L) { a *= 1f - (now - end) / 160f; if (a <= 0f) { on = false; return } }
        val far = Math.hypot(Math.max(x - shape.left, shape.right - x).toDouble(), Math.max(y - shape.top, shape.bottom - y).toDouble()).toFloat()
        cv.save()
        clip.reset(); clip.addRoundRect(shape, rad, rad, Path.Direction.CW); cv.clipPath(clip)
        fill.style = Paint.Style.FILL
        fill.color = (color and 0x00FFFFFF) or (Math.round(255f * 0.14f * a) shl 24)
        cv.drawCircle(x, y, far * e, fill)
        cv.restore()
        if (!rm || end != 0L) v.postInvalidateOnAnimation()
    }
}

/**
 * Lumen Part 4d (N10): selection check-circle flip, 160 ms. The lead squeezes to nothing around its centre, the face changes at the half way point
 * (tile <-> check circle), then it opens again. Reduce motion = no flip.
 * Usage: `flip.start()` when the same row changes its selected state; in onDraw call `flip.scale()` (1 = no animation) and `flip.showNew()`.
 */
class NlFlip(private val v: View) {
    private var t0 = 0L

    fun start() { if (!LumenPrefs.reduceMotion(v.context)) { t0 = SystemClock.uptimeMillis(); v.invalidate() } }
    fun cancel() { t0 = 0L }

    private fun f(): Float {
        if (t0 == 0L) return 1f
        val k = (SystemClock.uptimeMillis() - t0) / 160f
        if (k >= 1f) { t0 = 0L; return 1f }
        return k
    }

    /** Horizontal scale of the lead (1 -> 0 -> 1); 1 when idle. Keeps the view redrawing while it runs. */
    fun scale(): Float {
        val k = f()
        if (k >= 1f) return 1f
        v.postInvalidateOnAnimation()
        return Math.max(0.02f, Math.abs(1f - 2f * k))
    }

    /** True once the animation is past the half way point (or idle): draw the new face. */
    fun showNew(): Boolean = t0 == 0L || (SystemClock.uptimeMillis() - t0) / 160f >= 0.5f
}

/**
 * Lumen Part 4d (M7 a): loading placeholder for the native list. Rows of a round tile and two bars, shimmer 1.2 s at the page's pace
 * (`LumenGrad.skeleton` = gSkeleton). Shown by NativeList while a tapped folder is still being built; static with Reduce motion.
 */
class NlSkelView(c: Context, private val d: Float) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val t0 = SystemClock.uptimeMillis()
    var compact = false

    override fun onDraw(cv: Canvas) {
        val lm = Lumen.of(context)
        val rm = LumenPrefs.reduceMotion(context)
        val w = width.toFloat(); val h = height.toFloat()
        val rowH = (if (compact) 44f else 60f) * d
        val ph = if (rm) 0.5f else ((SystemClock.uptimeMillis() - t0) % 1200L) / 1200f
        p.style = Paint.Style.FILL
        p.shader = LumenGrad.skeleton(lm, 0f, w, ph)
        var y = 0f
        var i = 0
        while (y < h) {
            val cy = y + rowH / 2f
            val tile = (if (compact) 15f else 20f) * d
            val lx = (if (compact) 26f else 34f) * d
            cv.drawRoundRect(rf.apply { set(lx - tile, cy - tile, lx + tile, cy + tile) }, 12f * d, 12f * d, p)
            val x0 = (if (compact) 54f else 70f) * d
            val wide = w - x0 - 14f * d
            val nameW = wide * (0.45f + 0.35f * ((i * 37) % 5) / 4f)
            if (compact) {
                cv.drawRoundRect(rf.apply { set(x0, cy - 5f * d, x0 + nameW, cy + 5f * d) }, 5f * d, 5f * d, p)
            } else {
                cv.drawRoundRect(rf.apply { set(x0, cy - 13f * d, x0 + nameW, cy - 3f * d) }, 5f * d, 5f * d, p)
                cv.drawRoundRect(rf.apply { set(x0, cy + 5f * d, x0 + wide * 0.3f, cy + 13f * d) }, 4f * d, 4f * d, p)
            }
            y += rowH; i++
        }
        p.shader = null
        if (!rm) postInvalidateOnAnimation()
    }
}

/**
 * Lumen Part 4e (N11): sticky heading strip of the native list, twin of the page scrubber bubble. A small pill at the top-left of the list
 * names the group of the first visible row (initial / size / month, same text as the fast scroller) while the list scrolls, fades out 900 ms
 * after it stops. Never takes touches. Reduce motion = no fade (appears and disappears at once).
 */
class NlStickyView(c: Context, private val d: Float) : View(c) {
    var fill = 0
    var ink = 0
    var edge = 0
    private var text = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 12f * d * UiScale.tf }
    private val rf = RectF()
    private val hideRun = Runnable { fade(0f) }

    init { alpha = 0f; isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun fade(to: Float) {
        animate().cancel()
        if (LumenPrefs.reduceMotion(context)) { alpha = to; return }
        animate().alpha(to).setDuration(if (to > 0f) 120L else 160L).start()
    }

    /** [label] of the first visible row; empty hides the strip. Call on every scroll step. */
    fun pulse(label: String) {
        removeCallbacks(hideRun)
        if (label.isEmpty()) { if (alpha > 0f) fade(0f); return }
        if (label != text) { text = label; invalidate() }
        if (alpha < 1f) fade(1f)
        postDelayed(hideRun, 900)
    }

    fun hideNow() { removeCallbacks(hideRun); animate().cancel(); alpha = 0f }

    override fun onDraw(cv: Canvas) {
        if (text.isEmpty()) return
        val tw = tp.measureText(text)
        val h = 28f * d
        rf.set(12f * d, 8f * d, 12f * d + tw + 24f * d, 8f * d + h)
        p.style = Paint.Style.FILL; p.color = (fill and 0x00FFFFFF) or (0xEB shl 24); cv.drawRoundRect(rf, h / 2f, h / 2f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = Math.max(1f, d * 0.5f); p.color = edge; cv.drawRoundRect(rf, h / 2f, h / 2f, p)
        tp.color = ink
        val fm = tp.fontMetrics
        cv.drawText(text, rf.left + 12f * d, rf.centerY() - (fm.ascent + fm.descent) / 2f, tp)
    }
}
