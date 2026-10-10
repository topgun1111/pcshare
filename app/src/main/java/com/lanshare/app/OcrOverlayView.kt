package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Transparent layer above a ZoomImageView: draws the recognised words (image pixels -> view pixels through the image's own matrix)
 * and lets the user select a word range with two drag handles. A long press on a word starts the selection (the ZoomImageView
 * detects it and calls [selectWordAt]); this view only claims touches that start on a handle, everything else (pinch, pan, swipe,
 * tap) falls through to the image view below it.
 */
class OcrOverlayView(c: Context) : View(c) {
    private class W(val text: String, val box: RectF, val line: Int)

    private var words: List<W> = emptyList()
    private val m = Matrix()
    private var a = -1                 // first selected word (inclusive), -1 = no selection
    private var b = -1                 // last selected word (inclusive)
    private var drag = 0               // 0 none, 1 dragging the start handle, 2 the end handle
    private val d = resources.displayMetrics.density
    private val hr = 9f * d            // handle circle radius

    /** Called with the selected text (null = selection cleared) whenever the selection changes. */
    var onSelection: ((String?) -> Unit)? = null

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x73FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x663D8BFF }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3D8BFF.toInt() }
    private val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFF3D8BFF.toInt() }
    private val r = RectF()

    fun hasLines() = words.isNotEmpty()
    fun hasSelection() = a >= 0

    /** Image pixels -> view pixels; call on every zoom / pan (ZoomImageView.onMatrixChanged). */
    fun setImageMatrix(src: Matrix) { m.set(src); invalidate() }

    fun setLines(l: List<OcrLine>?) {
        val had = a >= 0
        a = -1; b = -1; drag = 0
        val out = ArrayList<W>()
        if (l != null) for ((i, ln) in l.withIndex()) for (w in ln.words) out.add(W(w.text, w.box, i))
        words = out
        invalidate()
        if (had) onSelection?.invoke(null)
    }

    fun clearSelection() {
        if (a < 0) return
        a = -1; b = -1; drag = 0
        invalidate()
        onSelection?.invoke(null)
    }

    fun selectAll() {
        if (words.isEmpty()) return
        a = 0; b = words.size - 1
        invalidate()
        onSelection?.invoke(selectedText())
    }

    /** Long press at view coordinates: selects the nearest word within ~28 dp. False = nothing there. */
    fun selectWordAt(x: Float, y: Float): Boolean {
        val i = nearest(x, y, 28f * d)
        if (i < 0) return false
        a = i; b = i
        invalidate()
        onSelection?.invoke(selectedText())
        return true
    }

    fun selectedText(): String? = if (a < 0) null else textOf(a, b)
    fun allText(): String? = if (words.isEmpty()) null else textOf(0, words.size - 1)

    private fun textOf(i0: Int, i1: Int): String {
        val sb = StringBuilder()
        for (i in i0..i1) {
            if (i > i0) sb.append(if (words[i].line != words[i - 1].line) '\n' else ' ')
            sb.append(words[i].text)
        }
        return sb.toString()
    }

    private fun mapped(i: Int, out: RectF): RectF { out.set(words[i].box); m.mapRect(out); return out }

    /** Index of the word closest to (x, y) in view pixels (vertical distance counts double, so a finger between lines picks its own line); -1 if farther than [maxDist]. */
    private fun nearest(x: Float, y: Float, maxDist: Float): Int {
        var best = -1
        var bd = if (maxDist.isInfinite()) Float.MAX_VALUE else maxDist * maxDist
        for (i in words.indices) {
            mapped(i, r)
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val dd = dx * dx + 4f * dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    private fun handleX(which: Int): Float { mapped(if (which == 1) a else b, r); return if (which == 1) r.left else r.right }
    private fun handleY(which: Int): Float { mapped(if (which == 1) a else b, r); return r.bottom }

    private fun hitHandle(x: Float, y: Float): Int {
        val reach = 26f * d
        var best = 0
        var bd = reach * reach
        for (which in 1..2) {
            val dx = x - handleX(which)
            val dy = y - (handleY(which) + hr)
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = which }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        if (words.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in words.indices) {
            mapped(i, r)
            if (r.right < 0 || r.bottom < 0 || r.left > w || r.top > h) continue
            if (i in a..b && a >= 0) canvas.drawRect(r, fill) else canvas.drawRect(r, outline)
        }
        if (a >= 0) for (which in 1..2) {
            val x = handleX(which)
            val y = handleY(which)
            canvas.drawLine(x, y, x, y + hr, stem)
            canvas.drawCircle(x, y + hr, hr, handle)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (a < 0) return false
                val which = hitHandle(e.x, e.y)
                if (which == 0) return false              // not on a handle: let the image view below take it
                drag = which
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == 0) return false
                val i = nearest(e.x, e.y - (hr + 12f * d), Float.POSITIVE_INFINITY)   // aim above the finger so it does not cover the word
                if (i < 0) return true
                if (drag == 1) { if (i > b) { a = b; b = i; drag = 2 } else a = i }
                else { if (i < a) { b = a; a = i; drag = 1 } else b = i }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag == 0) return false
                drag = 0
                onSelection?.invoke(selectedText())
                return true
            }
        }
        return false
    }
}
