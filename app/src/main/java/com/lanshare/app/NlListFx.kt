package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.text.TextPaint
import androidx.recyclerview.widget.RecyclerView

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
        if (end != 0L || (!rm && grow < 1f)) v.postInvalidateOnAnimation()   // 4g: stop once fully grown while held
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


/**
 * Lumen P1 (native twin of the page swipe in ui.html (block UI14-JS)): drag a list row right = Share, left = Delete.
 * Same numbers as the page: starts after 12 dp when the move is clearly horizontal (1.5 x the vertical move), follows the finger to 160 dp,
 * threshold 96 dp (haptic tick, label to full strength), release past it fires, otherwise the row springs back (200 ms, spring).
 * Touch / pen only, never from the screen edges (system back gesture), never in grid / gallery / selection mode (the owner decides through [allowed]).
 * The row view itself is moved (translationX); the coloured strip with glyph + label is an ItemDecoration drawn under the rows, only in the vacated part.
 * [fire] gets the adapter position and true = Delete; the page selects that row and runs its own Delete dialog / Share action (ui.html nlOn `swd` / `swr`).
 */
class NlSwipe(
    private val rv: RecyclerView, private val d: Float,
    private val allowed: (Int) -> Boolean,
    private val fire: (Int, Boolean) -> Unit
) : RecyclerView.OnItemTouchListener {
    private val slop = Math.max(ViewConfiguration.get(rv.context).scaledTouchSlop.toFloat(), 12f * d)
    private val maxX = 160f * d
    private val th = 96f * d
    private val edge = 24f * d
    private var down: View? = null
    private var cur: View? = null
    private var x0 = 0f; private var y0 = 0f
    private var on = false; private var go = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 14f * d * UiScale.tf }

    init {
        rv.addOnItemTouchListener(this)
        rv.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, st: RecyclerView.State) { reveal(c) }
        })
    }

    override fun onInterceptTouchEvent(r: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cur?.let { it.animate().cancel(); it.translationX = 0f }
                cur = null; on = false; go = false; down = null
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE || e.x < edge || e.x > r.width - edge) return false
                val c = r.findChildViewUnder(e.x, e.y) ?: return false
                val pos = r.getChildAdapterPosition(c)
                if (pos < 0 || !allowed(pos)) return false
                down = c; x0 = e.x; y0 = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val c = down ?: return on
                val dx = e.x - x0; val dy = e.y - y0
                if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.5f) { if (Math.abs(dy) > slop) down = null; return false }
                on = true; cur = c; go = false; down = null
                r.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> down = null
        }
        return on
    }

    override fun onTouchEvent(r: RecyclerView, e: MotionEvent) {
        val c = cur ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val t = (e.x - x0).coerceIn(-maxX, maxX)
                c.translationX = t
                val g = Math.abs(t) >= th
                if (g && !go) Haptics.view(r, "tick")
                go = g; r.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(r, e.actionMasked == MotionEvent.ACTION_UP)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}

    private fun finish(r: RecyclerView, up: Boolean) {
        val c = cur ?: return
        val t = c.translationX
        val doIt = up && go
        on = false; go = false; down = null
        val pos = r.getChildAdapterPosition(c)
        c.animate().cancel()
        if (LumenPrefs.reduceMotion(r.context)) { c.translationX = 0f; cur = null; r.invalidate() }
        else c.animate().translationX(0f).setDuration(LumenMotion.BASE).setInterpolator(LumenMotion.SPRING)
            .setUpdateListener { r.invalidate() }
            .withEndAction { if (cur === c) cur = null; r.invalidate() }.start()
        if (doIt && pos >= 0) fire(pos, t < 0f)
    }

    private fun reveal(c: Canvas) {
        val v = cur ?: return
        val t = v.translationX
        if (Math.abs(t) < 1f || v.parent !== rv) return
        val del = t < 0f
        val lm = Lumen.of(rv.context)
        val top = v.top + v.translationY; val bot = v.bottom + v.translationY
        val l = if (del) v.right + t else v.left.toFloat()
        val r = if (del) v.right.toFloat() else v.left + t
        p.style = Paint.Style.FILL; p.color = if (del) lm.errorCont else lm.primaryCont
        c.save(); c.clipRect(l, top, r, bot); c.drawRect(l, top, r, bot, p)
        val ink0 = if (del) lm.onErrorCont else lm.onPrimaryCont
        val ink = (ink0 and 0x00FFFFFF) or ((if (go) 255 else 153) shl 24)
        val label = if (del) "Delete" else "Share"
        val gs = 24f * d; val gap = 8f * d; val gw = gs + gap + tp.measureText(label)
        val cx = if (del) v.right - 24f * d - gw / 2f else v.left + 24f * d + gw / 2f
        val cy = (top + bot) / 2f
        val sc = if (go) 1.08f else 1f
        c.save(); c.scale(sc, sc, cx, cy)
        NlIcons.draw(c, p, if (del) "del" else "share", cx - gw / 2f, cy - gs / 2f, gs, ink)
        tp.color = ink
        c.drawText(label, cx - gw / 2f + gs + gap, cy - (tp.descent() + tp.ascent()) / 2f, tp)
        c.restore(); c.restore()
    }
}
