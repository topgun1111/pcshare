package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Text layer above the picture (see HANDOVER_OCR.md). Draws the selection, two drag handles, a floating toolbar
 * (Copy / Search / Share) and - while a handle is dragged - a magnifier. It only claims touches that start on a
 * handle or on the toolbar; everything else (pinch, pan, taps, page swipe) goes to the [ZoomImageView] below it.
 *
 * Word boxes are NOT drawn all the time: they flash for ~1.5 s when the layer is switched on, then only the words near the
 * finger are outlined (fading out), like a native gallery.
 *
 * Coordinates: words are in bitmap pixels; [setImageMatrix] maps them to view pixels. All public x / y are view pixels.
 * Selection = inclusive range of words in reading order. Taps on a word (called by ZoomImageView): 1 = word, 2 = line, 3 = paragraph.
 */
class OcrOverlayView(c: Context) : View(c) {
    var onSelection: ((String?) -> Unit)? = null
    /** Toolbar button pressed: "copy", "search" or "share". */
    var onAction: ((String) -> Unit)? = null

    private class W(val text: String, val box: RectF, val line: Int, val quad: FloatArray?)

    private var words: List<W> = emptyList()
    private var lineText: List<String> = emptyList()   // all lines, also those without words
    private var lineBox: List<RectF> = emptyList()     // only lines with words (index = W.line)
    private var lineFirst = IntArray(0)
    private var lineLast = IntArray(0)
    private var vb: Array<RectF> = emptyArray()        // word boxes in view pixels
    private var vq: Array<FloatArray?> = emptyArray()  // corner polygons (8 floats) of tilted words in view pixels, null = upright
    private val path = Path()
    private val mx = Matrix()

    private var selA = -1                              // anchor (fixed end while dragging)
    private var selB = -1                              // moving end
    private val lo get() = minOf(selA, selB)
    private val hi get() = maxOf(selA, selB)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f); color = 0xFFFFFFFF.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x663B82F6 }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3B82F6.toInt() }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xFF3B82F6.toInt() }
    private val tbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xF2202124.toInt() }
    private val tbPress = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF3C4043.toInt() }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = sp(14f); textAlign = Paint.Align.CENTER }

    // faint outlines: all words flash after the layer opens, later only those near the last touch
    private var flashT0 = 0L
    private var hintX = 0f
    private var hintY = 0f
    private var hintT0 = 0L
    private val hintR = dp(120f)

    // floating toolbar
    private val tbIds = arrayOf("copy", "search", "share")
    private val tbLabels = arrayOf("Copy", "Search", "Share")
    private val tbRects = Array(3) { RectF() }
    private val tbRect = RectF()
    private var tbShown = false
    private var tbDown = -1

    // handle drag
    private var dragging = false
    private var mag: Any? = null

    // ---------------------------------------------------------------- data
    fun setImageMatrix(m: Matrix) { mx.set(m); rebuild(); invalidate() }

    fun setLines(l: List<OcrLine>?) {
        dismissMagnifier()
        dragging = false
        selA = -1; selB = -1
        val ws = ArrayList<W>()
        val lb = ArrayList<RectF>()
        val lf = ArrayList<Int>()
        val ll = ArrayList<Int>()
        val lt = ArrayList<String>()
        if (l != null) for (ln in l) {
            lt.add(ln.text)
            if (ln.words.isEmpty()) continue
            val idx = lb.size
            lb.add(RectF(ln.box))
            lf.add(ws.size)
            for (w in ln.words) ws.add(W(w.text, RectF(w.box), idx, w.quad))
            ll.add(ws.size - 1)
        }
        words = ws; lineText = lt; lineBox = lb; lineFirst = lf.toIntArray(); lineLast = ll.toIntArray()
        rebuild()
        flashT0 = if (ws.isEmpty()) 0L else SystemClock.uptimeMillis()
        hintT0 = 0L
        invalidate()
    }

    private fun rebuild() {
        vb = Array(words.size) { i -> RectF(words[i].box).also { mx.mapRect(it) } }
        vq = Array<FloatArray?>(words.size) { i -> words[i].quad?.let { q -> FloatArray(8).also { o -> mx.mapPoints(o, q) } } }
    }

    fun hasLines() = words.isNotEmpty()
    fun hasSelection() = selA >= 0

    fun selectedText(): String? {
        if (selA < 0) return null
        val sb = StringBuilder()
        var line = -1
        for (i in lo..hi) {
            val w = words[i]
            if (line >= 0) sb.append(if (w.line != line) '\n' else ' ')
            sb.append(w.text)
            line = w.line
        }
        return sb.toString().ifBlank { null }
    }

    fun allText(): String? = lineText.joinToString("\n").ifBlank { null }

    // ---------------------------------------------------------------- selection API
    fun selectAll() { if (words.isEmpty()) return; selA = 0; selB = words.size - 1; changed() }

    fun clearSelection() { if (selA < 0) return; selA = -1; selB = -1; changed() }

    /** Long press: selects the word under the point (generous reach). */
    fun selectWordAt(x: Float, y: Float): Boolean {
        val i = wordAt(x, y, dp(14f))
        if (i < 0) return false
        selA = i; selB = i; changed()
        return true
    }

    /** Is there a recognised word under this point? (used to route taps) */
    fun hitsWord(x: Float, y: Float) = words.isNotEmpty() && wordAt(x, y, dp(6f)) >= 0

    /** Tap on a word: 1 = word, 2 = its line, 3 = its paragraph. Returns false when no word was hit. */
    fun tapSelect(x: Float, y: Float, count: Int): Boolean {
        val i = wordAt(x, y, dp(6f))
        if (i < 0) return false
        val ln = words[i].line
        when (count) {
            1 -> { selA = i; selB = i }
            2 -> { selA = lineFirst[ln]; selB = lineLast[ln] }
            else -> { val r = paragraph(ln); selA = lineFirst[r[0]]; selB = lineLast[r[1]] }
        }
        changed()
        return true
    }

    /** Finger position (view px) - outlines the words around it for a moment. */
    fun touchHint(x: Float, y: Float) {
        if (words.isEmpty()) return
        hintX = x; hintY = y; hintT0 = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun changed() { invalidate(); onSelection?.invoke(selectedText()) }

    /** Index of the word whose box (grown by [slop]) contains the point; the nearest centre wins. -1 = none. */
    private fun wordAt(x: Float, y: Float, slop: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in vb.indices) {
            val r = vb[i]
            if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) continue
            val q = vq[i]
            if (q != null && !nearQuad(q, x, y, slop)) continue          // tilted word: its box is only a bounding rectangle
            val dx = x - r.centerX()
            val dy = y - r.centerY()
            val dd = dx * dx + dy * dy
            if (dd < bd) { bd = dd; best = i }
        }
        return best
    }

    /** Is the point inside the 4-corner polygon, or within [slop] of its edge? */
    private fun nearQuad(q: FloatArray, x: Float, y: Float, slop: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0..3) {
            val xi = q[i * 2]; val yi = q[i * 2 + 1]; val xj = q[j * 2]; val yj = q[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            if (segDist(x, y, xi, yi, xj, yj) <= slop) return true
            j = i
        }
        return inside
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l = dx * dx + dy * dy
        val t = if (l == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l).coerceIn(0f, 1f)
        return dist(px, py, ax + t * dx, ay + t * dy)
    }

    // handle anchor points: bottom-left corner of the first selected word, bottom-right corner of the last (follow tilted text)
    private fun sx(i: Int) = vq[i]?.get(6) ?: vb[i].left
    private fun sy(i: Int) = vq[i]?.get(7) ?: vb[i].bottom
    private fun ex(i: Int) = vq[i]?.get(4) ?: vb[i].right
    private fun ey(i: Int) = vq[i]?.get(5) ?: vb[i].bottom

    /** Nearest word to a point (drag handles): distance to the box, vertical distance counts more so it stays on the line. */
    private fun nearest(x: Float, y: Float): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in vb.indices) {
            val r = vb[i]
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val dd = dx * dx + dy * dy * 2.25f
            if (dd < bd) { bd = dd; best = i }
        }
        return if (bd > dp(140f) * dp(140f)) -1 else best
    }

    /** Lines [first, last] that form one paragraph with line [ln]: small vertical gaps, similar height, overlapping horizontally. */
    private fun paragraph(ln: Int): IntArray {
        fun joined(p: Int, q: Int): Boolean {          // p is above q
            val a = lineBox[p]
            val b = lineBox[q]
            val h = maxOf(a.height(), b.height(), 1f)
            val gap = b.top - a.bottom
            val ratio = a.height() / maxOf(b.height(), 1f)
            return gap > -0.3f * h && gap < 0.9f * h && ratio > 0.6f && ratio < 1.67f && b.left < a.right && a.left < b.right
        }
        var a = ln
        var b = ln
        while (a > 0 && joined(a - 1, a)) a--
        while (b < lineBox.size - 1 && joined(b, b + 1)) b++
        return intArrayOf(a, b)
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(cv: Canvas) {
        if (words.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        var animate = false

        // selection: one rounded bar per line
        if (selA >= 0) {
            var i = lo
            while (i <= hi) {
                val ln = words[i].line
                var j = i
                while (j < hi && words[j + 1].line == ln) j++
                val q1 = vq[i]
                val q2 = vq[j]
                if (q1 != null && q2 != null) {                       // tilted line: polygon from the first word's left edge to the last word's right edge
                    path.reset()
                    path.moveTo(q1[0], q1[1]); path.lineTo(q2[2], q2[3]); path.lineTo(q2[4], q2[5]); path.lineTo(q1[6], q1[7]); path.close()
                    cv.drawPath(path, fillPaint)
                } else {
                    val r = RectF(vb[i].left, vb[i].top, vb[j].right, vb[i].bottom)
                    for (k in i..j) { r.top = minOf(r.top, vb[k].top); r.bottom = maxOf(r.bottom, vb[k].bottom) }
                    cv.drawRoundRect(r, dp(3f), dp(3f), fillPaint)
                }
                i = j + 1
            }
        }

        // faint outlines: flash of all words after opening, later only around the finger
        val fa = if (flashT0 == 0L) 0f else (1f - (now - flashT0 - 600) / 900f).coerceIn(0f, 1f)
        val ha = if (hintT0 == 0L) 0f else (1f - (now - hintT0 - 1200) / 800f).coerceIn(0f, 1f)
        if (fa > 0f || ha > 0f) {
            animate = true
            for (wi in vb.indices) {
                val r = vb[wi]
                if (r.right < 0 || r.left > w || r.bottom < 0 || r.top > h) continue
                var a = fa
                if (ha > 0f) {
                    val dx = if (hintX < r.left) r.left - hintX else if (hintX > r.right) hintX - r.right else 0f
                    val dy = if (hintY < r.top) r.top - hintY else if (hintY > r.bottom) hintY - r.bottom else 0f
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < hintR) a = maxOf(a, ha * (1f - dist / hintR))
                }
                if (a < 0.03f) continue
                boxPaint.alpha = (a * 150).toInt()
                val q = vq[wi]
                if (q != null) {
                    path.reset()
                    path.moveTo(q[0], q[1]); path.lineTo(q[2], q[3]); path.lineTo(q[4], q[5]); path.lineTo(q[6], q[7]); path.close()
                    cv.drawPath(path, boxPaint)
                } else cv.drawRoundRect(r, dp(2f), dp(2f), boxPaint)
            }
        }

        // handles + toolbar
        tbShown = false
        if (selA >= 0) {
            val hr = dp(9f)
            val sx0 = sx(lo); val sy0 = sy(lo); val ex0 = ex(hi); val ey0 = ey(hi)
            cv.drawLine(sx0, sy0, sx0, sy0 + dp(3f), stemPaint)
            cv.drawCircle(sx0, sy0 + dp(3f) + hr, hr, handlePaint)
            cv.drawLine(ex0, ey0, ex0, ey0 + dp(3f), stemPaint)
            cv.drawCircle(ex0, ey0 + dp(3f) + hr, hr, handlePaint)
            if (!dragging) drawToolbar(cv)
        }
        if (animate) postInvalidateOnAnimation()
    }

    private fun drawToolbar(cv: Canvas) {
        var tw = 0f
        val ws = FloatArray(3) { txtPaint.measureText(tbLabels[it]) + dp(28f) }
        for (x in ws) tw += x
        val bh = dp(40f)
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in lo..hi) { val r = vb[i]; top = minOf(top, r.top); bottom = maxOf(bottom, r.bottom); left = minOf(left, r.left); right = maxOf(right, r.right) }
        var y = top - bh - dp(12f)
        if (y < dp(76f)) y = bottom + dp(40f)                       // no room above (top bar): put it under the handles
        y = y.coerceIn(dp(8f), maxOf(dp(8f), height - bh - dp(80f)))
        val x = ((left + right) / 2f - tw / 2f).coerceIn(dp(8f), maxOf(dp(8f), width - tw - dp(8f)))
        tbRect.set(x, y, x + tw, y + bh)
        cv.drawRoundRect(tbRect, bh / 2f, bh / 2f, tbPaint)
        var cx = x
        val fm = txtPaint.fontMetrics
        for (i in 0 until 3) {
            tbRects[i].set(cx, y, cx + ws[i], y + bh)
            if (tbDown == i) cv.drawRoundRect(tbRects[i], bh / 2f, bh / 2f, tbPress)
            cv.drawText(tbLabels[i], cx + ws[i] / 2f, y + bh / 2f - (fm.ascent + fm.descent) / 2f, txtPaint)
            cx += ws[i]
        }
        tbShown = true
    }

    // ---------------------------------------------------------------- touches: only handles + toolbar
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (selA < 0) return false
                if (tbShown && tbRect.contains(e.x, e.y)) {
                    tbDown = (0 until 3).firstOrNull { tbRects[it].contains(e.x, e.y) } ?: -1
                    invalidate()
                    return true
                }
                val hr = dp(9f)
                val reach = dp(30f)
                val ds = dist(e.x, e.y, sx(lo), sy(lo) + dp(3f) + hr)
                val de = dist(e.x, e.y, ex(hi), ey(hi) + dp(3f) + hr)
                if (minOf(ds, de) > reach) return false
                // dragging the start handle moves the lower index, the other handle stays fixed (and the other way round)
                if (ds <= de) { selA = hi; selB = lo } else { selA = lo; selB = hi }
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                moveTo(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) { moveTo(e.x, e.y); return true }
                if (tbDown >= 0) {
                    val inside = tbRects[tbDown].contains(e.x, e.y)
                    if (!inside) { tbDown = -1; invalidate() }
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    dismissMagnifier()
                    changed()
                    return true
                }
                if (tbDown >= 0) {
                    val id = tbDown
                    tbDown = -1
                    invalidate()
                    if (e.actionMasked == MotionEvent.ACTION_UP) onAction?.invoke(tbIds[id])
                    return true
                }
                return false
            }
        }
        return dragging || tbDown >= 0
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun moveTo(x: Float, y: Float) {
        val i = nearest(x, y - dp(26f))               // aim above the finger so the word stays visible
        if (i >= 0 && i != selB) { selB = i; invalidate() }
        showMagnifier(x, y - dp(26f))
    }

    // ---------------------------------------------------------------- magnifier (Android 9+)
    private fun showMagnifier(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 28) return
        try {
            val m = (mag as? android.widget.Magnifier) ?: android.widget.Magnifier(this).also { mag = it }
            m.show(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))
        } catch (_: Throwable) {}
    }

    private fun dismissMagnifier() {
        if (Build.VERSION.SDK_INT < 28) return
        try { (mag as? android.widget.Magnifier)?.dismiss() } catch (_: Throwable) {}
    }

    override fun onDetachedFromWindow() { dismissMagnifier(); super.onDetachedFromWindow() }
}
