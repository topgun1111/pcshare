package com.lanshare.app

import android.content.Context
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.graphics.PathParser
import org.json.JSONObject

/*
 * Native path bar (#pathrow: breadcrumbs + storage-used pill) and tool row (#toolrow: item/size summary, Newest, Sort).
 * Drawn by NlHeadView on top of the (still present, identical) HTML rows; metrics are the phone values of ui.html's CSS.
 * ui.html sends plain data (nlHead) and the rows' geometry (layout JSON "hd"); taps go back as nlOn('crumb'|'newb'|'sort', i).
 * Kill switches: NativeList.HEAD = false  /  localStorage ls_nlh='0'.  NOT compiled / NOT device-tested.
 */

/** What the two rows show. segs = folder names below the drive icon (already without a trailing '!'). */
data class NlHeadData(
    val segs: List<String>,
    val dn: String,          // device name next to the drive icon ("" = this phone)
    val used: Int,           // storage used in %, -1 = no pill
    val sumB: String,        // bold summary ("12 items · 3.4 MB")
    val sumS: String,        // small summary ("2 folders · 10 files"), may be empty
    val newest: Boolean,
    val sortLabel: String,
    val sortDir: Int         // 0 = no arrow, 1 = up, 2 = down
) {
    companion object {
        fun parse(json: String): NlHeadData? = try {
            val o = JSONObject(json); val c = o.getJSONArray("c"); val s = o.getJSONArray("s")
            val segs = ArrayList<String>(c.length()); for (i in 0 until c.length()) segs.add(c.getString(i))
            NlHeadData(segs, o.optString("dn"), o.optInt("u", -1), s.getString(0), s.optString(1), o.optInt("n") == 1, o.optString("t"), o.optInt("d"))
        } catch (_: Throwable) { null }

        /** Same as ui.html render(): path segments, the archive marker '!' dropped from the shown text. */
        fun segsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }.map { it.removeSuffix("!") }
    }
}

class NlHeadView(c: Context, private val d: Float) : View(c) {
    var pal: NlPal? = null
        set(v) { field = v; invalidate() }
    var onAct: ((String, Int) -> Unit)? = null

    private var data: NlHeadData? = null
    private var hA = 0f          // path bar height (px, includes its 1 css px bottom border)
    private var hB = 0f          // tool row height
    private var dirty = true
    private var pendingEnd = true     // crumb strip starts scrolled to its end, like ui.html's cr.scrollLeft=cr.scrollWidth

    // ---- paints ----
    private val med: Typeface = Fnt.med()
    private val w600: Typeface = Fnt.semi()
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crumbP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = med; textSize = 14f * d }
    private val curP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 14f * d }
    private val sumBP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = w600; textSize = 13f * d }
    private val sumSP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = med; textSize = 12f * d }
    private val newP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = med; textSize = 12f * d }
    private val sortP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = w600; textSize = 13f * d }
    private val pillP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 12f * d; letterSpacing = 0.2f / 12f }
    private val rf = RectF()

    // ---- icons (ui.html IC / HOME / DRIVE; arc flags written with spaces) ----
    private fun pp(s: String): Path = PathParser.createPathFromPathData(s)
    private val icChev = pp("M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    private val icSort = pp("M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z")
    private val icUp = pp("M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z")
    private val icDown = pp("M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z")
    private val hm1 = pp("M6 17l10-8.5L26 17v11H6z")
    private val hm2 = pp("M2 16L16 4l14 12-2 2.2L16 8 4 18.2z")
    private val hm3 = pp("M13 20h6v8h-6z")
    private val dr1 = pp("M9 4h14l4 15v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7z")
    private val dr2 = pp("M5 19h22v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2z")

    // ---- layout (content coordinates of the crumb strip) ----
    private class Cr(val kind: Int, val text: String, val cur: Boolean) { var x = 0f; var w = 0f }   // kind 0 home, 1 drive, 2 folder
    private val crs = ArrayList<Cr>()
    private var contentW = 0f
    private var areaL = 0f
    private var areaR = 0f
    private var pillW = 0f
    private val newR = RectF()
    private val sortR = RectF()
    private var sx = 0f                       // crumb strip scroll (px)
    private val scroller = OverScroller(c)

    // ---- touch ----
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var vt: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var moved = false
    private var dragStrip = false
    private var pressKind = 0                 // 0 none, 1 crumb, 2 newest, 3 sort
    private var pressIdx = -1

    fun set(dt: NlHeadData?, toEnd: Boolean) {
        val chg = dt != data
        data = dt
        if (chg) dirty = true
        if (toEnd || chg) { pendingEnd = true; scroller.forceFinished(true); invalidate() }
    }

    fun setGeom(a: Float, b: Float) { if (a != hA || b != hB) { hA = a; hB = b; dirty = true; invalidate() } }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { dirty = true }

    private fun maxScroll() = Math.max(0f, contentW - (areaR - areaL))

    private fun crumbW(c: Cr): Float = when (c.kind) {
        0 -> (5f + 24f + 5f) * d
        1 -> (5f + 24f + 5f) * d + (if (c.text.isNotEmpty()) 6f * d + (if (c.cur) curP else crumbP).measureText(c.text) else 0f)
        else -> 10f * d + (if (c.cur) curP else crumbP).measureText(c.text)
    }

    private fun ensure() {
        val dt = data ?: return
        if (width <= 0) return
        if (!dirty) { if (pendingEnd) { sx = maxScroll(); pendingEnd = false }; return }
        dirty = false
        val w = width.toFloat()
        // crumbs: home, drive, folders - a chevron between neighbours
        crs.clear()
        crs.add(Cr(0, "", false)); crs.add(Cr(1, dt.dn, dt.segs.isEmpty()))
        for ((i, s) in dt.segs.withIndex()) crs.add(Cr(2, s, i == dt.segs.size - 1))
        var x = 0f
        for ((i, c) in crs.withIndex()) { c.x = x; c.w = crumbW(c); x += c.w; if (i < crs.size - 1) x += 18f * d }
        contentW = x
        // storage pill
        pillW = if (dt.used >= 0) 2f * d + 18f * d + 14f * d + 6f * d + pillP.measureText(dt.used.toString() + "%") else 0f
        areaL = 4f * d
        areaR = w - 8f * d - (if (pillW > 0f) pillW + 8f * d else 0f)
        // tool row, built from the right edge: Sort, (gap 4 + margin 6) Newest, (gap 4) summary
        val cy2 = hA + (hB - d) / 2f
        val sortW = 8f * d + 18f * d + 6f * d + sortP.measureText(dt.sortLabel) + (if (dt.sortDir != 0) 6f * d + 14f * d else 0f) + 8f * d
        sortR.set(w - 2f * d - sortW, cy2 - 16f * d, w - 2f * d, cy2 + 16f * d)
        val newW = 10f * d + newP.measureText("Newest") + 10f * d + 2f * d
        newR.set(sortR.left - 10f * d - newW, cy2 - 14f * d, sortR.left - 10f * d, cy2 + 14f * d)
        sx = if (pendingEnd) maxScroll() else Math.min(sx, maxScroll())
        pendingEnd = false
    }

    // ---------------------------------------------------------------- drawing
    private fun textY(cy: Float, p: Paint): Float { val fm = p.fontMetrics; return cy - (fm.ascent + fm.descent) / 2f }

    private fun ico(cv: Canvas, path: Path, x: Float, cy: Float, sizeDp: Float, color: Int) {
        val s = sizeDp * d / 24f
        cv.save(); cv.translate(x, cy - sizeDp * d / 2f); cv.scale(s, s)
        fillP.style = Paint.Style.FILL; fillP.color = color
        cv.drawPath(path, fillP)
        cv.restore()
    }

    private fun home(cv: Canvas, x: Float, cy: Float) {
        val s = 24f * d / 32f
        cv.save(); cv.translate(x, cy - 12f * d); cv.scale(s, s)
        fillP.style = Paint.Style.FILL; fillP.color = 0xFFF5F5F5.toInt(); cv.drawPath(hm1, fillP)
        fillP.style = Paint.Style.STROKE; fillP.strokeWidth = 1f; fillP.color = 0xFFB5B5B5.toInt(); cv.drawPath(hm1, fillP)
        fillP.style = Paint.Style.FILL; fillP.color = 0xFFE53935.toInt(); cv.drawPath(hm2, fillP)
        fillP.color = 0xFF3D8FD6.toInt(); cv.drawPath(hm3, fillP)
        cv.restore()
    }

    private fun drive(cv: Canvas, x: Float, cy: Float) {
        val s = 24f * d / 32f
        cv.save(); cv.translate(x, cy - 12f * d); cv.scale(s, s)
        fillP.style = Paint.Style.FILL; fillP.color = 0xFFC9C9C9.toInt(); cv.drawPath(dr1, fillP)
        fillP.color = 0xFFB2B2B2.toInt(); cv.drawPath(dr2, fillP)
        fillP.color = 0xFF4CAF50.toInt(); cv.drawCircle(9.5f, 24f, 1.4f, fillP)
        cv.restore()
    }

    override fun onDraw(cv: Canvas) {
        val p = pal ?: return
        val dt = data ?: return
        ensure()
        if (hA <= 0f || hB <= 0f) return
        val w = width.toFloat()
        val line = d

        // backgrounds + bottom borders
        fillP.style = Paint.Style.FILL
        fillP.color = p.card; cv.drawRect(0f, 0f, w, hA + hB, fillP)
        fillP.color = p.bd
        cv.drawRect(0f, hA - line, w, hA, fillP)
        cv.drawRect(0f, hA + hB - line, w, hA + hB, fillP)

        // ---- path bar ----
        val cy = (hA - line) / 2f
        cv.save(); cv.clipRect(areaL, 0f, areaR, hA - line)
        val mutChev = (p.mut and 0x00FFFFFF) or (((p.mut ushr 24) * 0.6f).toInt() shl 24)
        for ((i, c) in crs.withIndex()) {
            val x = areaL + c.x - sx
            if (x + c.w >= areaL - 1f && x <= areaR + 1f) {
                if (pressKind == 1 && pressIdx == i) { fillP.style = Paint.Style.FILL; fillP.color = p.hov; rf.set(x, cy - 17f * d, x + c.w, cy + 17f * d); cv.drawRoundRect(rf, 6f * d, 6f * d, fillP) }
                val tp = if (c.cur) curP else crumbP
                tp.color = if (c.cur) p.fg else p.mut
                when (c.kind) {
                    0 -> home(cv, x + 5f * d, cy)
                    1 -> { drive(cv, x + 5f * d, cy); if (c.text.isNotEmpty()) cv.drawText(c.text, x + 5f * d + 24f * d + 6f * d, textY(cy, tp), tp) }
                    else -> cv.drawText(c.text, x + 5f * d, textY(cy, tp), tp)
                }
            }
            if (i < crs.size - 1) ico(cv, icChev, x + c.w, cy, 18f, mutChev)
        }
        cv.restore()

        if (dt.used >= 0) {
            val r = w - 8f * d; val l = r - pillW
            rf.set(l + line / 2f, cy - 14f * d + line / 2f, r - line / 2f, cy + 14f * d - line / 2f)
            fillP.style = Paint.Style.STROKE; fillP.strokeWidth = line; fillP.color = p.mut
            cv.drawRoundRect(rf, 14f * d, 14f * d, fillP)
            val ccx = l + line + 9f * d + 7f * d
            fillP.style = Paint.Style.FILL; fillP.color = p.bd; cv.drawCircle(ccx, cy, 7f * d, fillP)
            fillP.color = p.fg; rf.set(ccx - 7f * d, cy - 7f * d, ccx + 7f * d, cy + 7f * d)
            cv.drawArc(rf, -90f, 360f * Math.min(100, dt.used) / 100f, true, fillP)
            pillP.color = p.fg
            cv.drawText(dt.used.toString() + "%", ccx + 7f * d + 6f * d, textY(cy, pillP), pillP)
        }

        // ---- tool row ----
        val cy2 = hA + (hB - line) / 2f
        val base = cy2 + 5.1f * d               // baseline of the 15 px / line-height 1.25 strut that #sum sits on
        val sl = 14f * d; val sr = newR.left - 4f * d
        if (sr > sl) {
            sumBP.color = p.fg; sumSP.color = p.mut
            val avail = sr - sl
            val bw = sumBP.measureText(dt.sumB)
            if (bw >= avail) cv.drawText(TextUtils.ellipsize(dt.sumB, sumBP, avail, TextUtils.TruncateAt.END).toString(), sl, base, sumBP)
            else {
                cv.drawText(dt.sumB, sl, base, sumBP)
                if (dt.sumS.isNotEmpty()) {
                    val sm = " \u00b7 " + dt.sumS
                    cv.drawText(TextUtils.ellipsize(sm, sumSP, avail - bw, TextUtils.TruncateAt.END).toString(), sl + bw, base, sumSP)
                }
            }
        }
        // Newest
        if (dt.newest) { fillP.style = Paint.Style.FILL; fillP.color = p.sel; cv.drawRoundRect(newR, 14f * d, 14f * d, fillP); newP.color = p.onsel }
        else {
            rf.set(newR.left + line / 2f, newR.top + line / 2f, newR.right - line / 2f, newR.bottom - line / 2f)
            fillP.style = Paint.Style.STROKE; fillP.strokeWidth = line; fillP.color = p.bd; cv.drawRoundRect(rf, 14f * d - line / 2f, 14f * d - line / 2f, fillP)
            newP.color = p.fg
        }
        cv.drawText("Newest", newR.left + 1f * d + 10f * d, textY(newR.centerY(), newP), newP)
        // Sort
        if (pressKind == 3) { fillP.style = Paint.Style.FILL; fillP.color = p.hov; cv.drawRoundRect(sortR, 16f * d, 16f * d, fillP) }
        var x = sortR.left + 8f * d
        ico(cv, icSort, x, sortR.centerY(), 18f, p.ac); x += 18f * d + 6f * d
        sortP.color = p.ac
        cv.drawText(dt.sortLabel, x, textY(sortR.centerY(), sortP), sortP); x += sortP.measureText(dt.sortLabel)
        if (dt.sortDir != 0) { x += 6f * d; ico(cv, if (dt.sortDir == 1) icUp else icDown, x, sortR.centerY(), 14f, p.ac) }
    }

    // ---------------------------------------------------------------- touch
    private fun crumbAt(x: Float, y: Float): Int {
        if (x < areaL || x > areaR) return -1
        val cy = (hA - d) / 2f
        if (Math.abs(y - cy) > 17f * d) return -1
        val cx = x - areaL + sx
        for ((i, c) in crs.withIndex()) if (cx >= c.x && cx <= c.x + c.w) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (data == null || pal == null) return false
        ensure()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = e.x; downY = e.y; lastX = e.x; moved = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                dragStrip = e.y < hA && e.x >= areaL && e.x <= areaR
                pressKind = 0; pressIdx = -1
                if (dragStrip) { val i = crumbAt(e.x, e.y); if (i >= 0) { pressKind = 1; pressIdx = i } }
                else if (e.y >= hA) {
                    if (sortR.contains(e.x, e.y)) pressKind = 3
                    else if (newR.contains(e.x, e.y)) pressKind = 2
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!moved && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) { moved = true; pressKind = 0; pressIdx = -1; invalidate() }
                if (moved && dragStrip) {
                    sx = Math.max(0f, Math.min(maxScroll(), sx - (e.x - lastX)))
                    invalidate()
                }
                lastX = e.x
                return true
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                if (!moved && pressKind != 0) {
                    when (pressKind) { 1 -> onAct?.invoke("crumb", pressIdx); 2 -> onAct?.invoke("newb", 0); 3 -> onAct?.invoke("sort", 0) }
                } else if (moved && dragStrip) {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    if (Math.abs(vx) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        scroller.fling(sx.toInt(), 0, (-vx).toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                }
                pressKind = 0; pressIdx = -1; vt?.recycle(); vt = null; invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressKind = 0; pressIdx = -1; vt?.recycle(); vt = null; invalidate(); return true }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { sx = Math.max(0f, Math.min(maxScroll(), scroller.currX.toFloat())); postInvalidateOnAnimation() }
    }
}
