package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/*
 * Native side drawer (hamburger menu): tabs (Folders / Favorites / Recent), the scrolling body (Home, Devices, Quick folders, favorites, history ...) and the footer buttons.
 *
 * Same idea as NlChrome, but even more mechanical: the HTML drawer (#dwr) stays in the page UNCHANGED. ui.html still builds every row and owns every handler.
 * After each build ui.html MIRRORS the DOM into flat draw lists (nlDrFlat): filled rects, svg paths, text with the browser's own computed position, size and colour.
 * This file only paints those lists (one DrView per row inside a RecyclerView; tabs and footer are DrViews too) and sends taps back as nlDr(kind, row, j, ver);
 * the page then click()s the very same HTML element. So the look and the behaviour cannot drift from the HTML drawer.
 *
 * States pushed by ui.html: "0" = drawer closed (slide out), "h" = a page dialog / sheet is on top of the drawer (hide at once), else a JSON model {v,w,pt,pb,p,tabs,rows,foot}.
 * Slide-in / slide-out is animated here (220 ms, same as the CSS); the HTML drawer slides underneath at the same time.
 * Kill switches: NlDrawer.ENABLED = false  /  localStorage ls_nld='0'.   NOT compiled / NOT device-tested.
 */

private class DrPth(val path: Path?, val fill: Int, val stroke: Int, val sw: Float)

private class DrIt(val k: Char, val x: Float, val y: Float, val w: Float, val h: Float) {
    var c = 0
    var r = 0f
    var vb = FloatArray(4)
    var paths: List<DrPth> = emptyList()
    var s = ""
    var sz = 14f
    var wt = 400
    var ls = 0f
    var al = 0
    var ml = false
    var lay: StaticLayout? = null
    var layW = 0
}

private class DrHit(val k: String, val j: Int, val x: Float, val y: Float, val w: Float, val h: Float)

private class DrReg(val h: Float, val its: List<DrIt>, val hits: List<DrHit>)

/** One painted region: the tab strip, a body row or the footer. Taps on a hit rectangle go to [onHit]; a touch outside every hit is not consumed. */
private class DrView(c: Context, private val d: Float) : View(c) {
    var reg: DrReg? = null
    var idx = 0
    var hov = 0
    var onHit: ((DrHit, Int) -> Unit)? = null

    private var pressed: DrHit? = null
    private var tracking = false
    private var dx0 = 0f
    private var dy0 = 0f
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val med: Typeface = Fnt.med()
    private val bold: Typeface = Fnt.semi()

    private fun tf(wt: Int): Typeface = if (wt >= 600) bold else med

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), Math.round((reg?.h ?: 0f) * d))
    }

    override fun onDraw(cv: Canvas) {
        val r = reg ?: return
        val pr = pressed
        if (pr != null && hov != 0) {
            fill.style = Paint.Style.FILL
            fill.color = hov
            if (pr.k == "x") cv.drawCircle((pr.x + pr.w / 2f) * d, (pr.y + pr.h / 2f) * d, Math.min(pr.w, pr.h) / 2f * d, fill)
            else cv.drawRect(pr.x * d, pr.y * d, (pr.x + pr.w) * d, (pr.y + pr.h) * d, fill)
        }
        for (it in r.its) {
            when (it.k) {
                'r' -> {
                    fill.style = Paint.Style.FILL
                    fill.color = it.c
                    if (it.r > 0f) cv.drawRoundRect(it.x * d, it.y * d, (it.x + it.w) * d, (it.y + it.h) * d, it.r * d, it.r * d, fill)
                    else cv.drawRect(it.x * d, it.y * d, (it.x + it.w) * d, (it.y + it.h) * d, fill)
                }
                's' -> drawSvg(cv, it)
                't' -> drawText(cv, it)
            }
        }
    }

    private fun drawSvg(cv: Canvas, it: DrIt) {
        val vw = it.vb[2]
        val vh = it.vb[3]
        if (vw <= 0f || vh <= 0f) return
        val s = Math.min(it.w / vw, it.h / vh)
        val ox = it.x + (it.w - vw * s) / 2f - it.vb[0] * s
        val oy = it.y + (it.h - vh * s) / 2f - it.vb[1] * s
        cv.save()
        cv.translate(ox * d, oy * d)
        cv.scale(s * d, s * d)
        for (p in it.paths) {
            val path = p.path ?: continue
            if (p.fill != 0) {
                fill.style = Paint.Style.FILL
                fill.color = p.fill
                cv.drawPath(path, fill)
            }
            if (p.stroke != 0 && p.sw > 0f) {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = p.sw
                fill.color = p.stroke
                cv.drawPath(path, fill)
                fill.style = Paint.Style.FILL
            }
        }
        cv.restore()
    }

    private fun drawText(cv: Canvas, it: DrIt) {
        if (it.s.isEmpty() || it.w <= 0f) return
        val wpx = Math.max(1, Math.round(it.w * d))
        var lay = it.lay
        if (lay == null || it.layW != wpx) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.color = it.c
            tp.textSize = it.sz * d
            tp.typeface = tf(it.wt)
            if (it.ls != 0f && it.sz > 0f) tp.letterSpacing = it.ls / it.sz
            val al = when (it.al) {
                1 -> Layout.Alignment.ALIGN_CENTER
                2 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val b = StaticLayout.Builder.obtain(it.s, 0, it.s.length, tp, wpx).setAlignment(al).setIncludePad(false)
            if (!it.ml) b.setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
            lay = b.build()
            it.lay = lay
            it.layW = wpx
        }
        val ty = it.y * d + Math.max(0f, (it.h * d - lay!!.height) / 2f)
        cv.save()
        cv.translate(it.x * d, ty)
        lay.draw(cv)
        cv.restore()
    }

    private fun find(px: Float, py: Float): DrHit? {
        val r = reg ?: return null
        for (h in r.hits) if (px >= h.x && px <= h.x + h.w && py >= h.y && py <= h.y + h.h) return h
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx0 = e.x
                dy0 = e.y
                val h = find(e.x / d, e.y / d)
                pressed = h
                tracking = h != null
                invalidate()
                return tracking
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking && (Math.abs(e.x - dx0) > slop || Math.abs(e.y - dy0) > slop)) {
                    tracking = false
                    pressed = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val h = pressed
                val was = tracking
                tracking = false
                pressed = null
                invalidate()
                if (was && h != null) onHit?.invoke(h, idx)
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pressed = null
                invalidate()
            }
        }
        return true
    }
}

/** The drawer panel: swallows every touch inside it and turns a clear left swipe into "close". */
private class DrPanel(c: Context, private val d: Float) : LinearLayout(c) {
    var onSwipe: (() -> Unit)? = null
    private var x0 = 0f
    private var y0 = 0f
    private var done = false

    init {
        orientation = VERTICAL
        isClickable = true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; done = false }
            MotionEvent.ACTION_MOVE -> if (!done) {
                val dx = e.x - x0
                val dy = e.y - y0
                if (dx < -50f * d && Math.abs(dy) < Math.abs(dx) / 1.2f) { done = true; onSwipe?.invoke(); return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = true
}

private class DrVh(v: View) : RecyclerView.ViewHolder(v)

/** Owner. [fire] runs nlDr(kind, row, j, ver) in the page. Add [panel] to the activity root last (above the page, the list and the chrome). */
class NlDrawer(private val act: Activity, private val fire: (String, Int, Int, Int) -> Unit) {
    companion object {
        /** Kill switch: false = the page's HTML drawer only. */
        const val ENABLED = true
    }

    private val d = UiScale.dens(act)
    private val pathCache = HashMap<String, Path?>()
    private var hov = 0
    private var ver = 0
    private var last = ""
    private var open = false
    private var wpx = 0

    private val dp = DrPanel(act, d)
    private val tabs = DrView(act, d)
    private val foot = DrView(act, d)
    private val list = RecyclerView(act)
    private val ad = Ad()

    val panel: View get() = dp

    private inner class Ad : RecyclerView.Adapter<DrVh>() {
        var rows: List<DrReg> = emptyList()
        var hov = 0
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DrVh {
            val v = DrView(act, d)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            v.onHit = { h, i -> fire(h.k, i, h.j, ver) }
            return DrVh(v)
        }
        override fun onBindViewHolder(holder: DrVh, position: Int) {
            val v = holder.itemView as DrView
            v.idx = position
            v.hov = hov
            v.reg = rows[position]
            v.requestLayout()
            v.invalidate()
        }
    }

    init {
        tabs.onHit = { h, i -> fire(h.k, i, h.j, ver) }
        foot.onHit = { h, i -> fire(h.k, i, h.j, ver) }
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = ad
        list.itemAnimator = null
        list.overScrollMode = View.OVER_SCROLL_NEVER
        dp.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dp.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dp.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dp.visibility = View.GONE
        dp.elevation = 8f * d
        dp.onSwipe = { fire("close", 0, 0, ver) }
    }

    /** Add this once to the activity root: FrameLayout.LayoutParams(1, MATCH_PARENT); the width is set from the page's drawer width. */
    fun layoutParams(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun path(s: String): Path? {
        if (pathCache.containsKey(s)) return pathCache[s]
        val p = NlGlyph.parse(s)
        pathCache[s] = p
        return p
    }

    private fun parseReg(j: JSONObject): DrReg {
        val its = ArrayList<DrIt>()
        val ia = j.optJSONArray("it")
        if (ia != null) for (i in 0 until ia.length()) {
            val o = ia.getJSONObject(i)
            val g = o.getJSONArray("g")
            val it = DrIt(o.getString("t")[0], g.getDouble(0).toFloat(), g.getDouble(1).toFloat(), g.getDouble(2).toFloat(), g.getDouble(3).toFloat())
            when (it.k) {
                'r' -> { it.c = o.optLong("c").toInt(); it.r = o.optDouble("r", 0.0).toFloat() }
                's' -> {
                    val vb = o.getJSONArray("vb")
                    for (n in 0 until 4) it.vb[n] = vb.getDouble(n).toFloat()
                    val pa = o.optJSONArray("p")
                    val ps = ArrayList<DrPth>()
                    if (pa != null) for (n in 0 until pa.length()) {
                        val po = pa.getJSONObject(n)
                        ps.add(DrPth(path(po.optString("d")), po.optLong("f").toInt(), po.optLong("s").toInt(), po.optDouble("w", 0.0).toFloat()))
                    }
                    it.paths = ps
                }
                't' -> {
                    it.s = o.optString("s")
                    it.c = o.optLong("c").toInt()
                    it.sz = o.optDouble("sz", 14.0).toFloat()
                    it.wt = o.optInt("wt", 400)
                    it.ls = o.optDouble("ls", 0.0).toFloat()
                    it.al = o.optInt("al")
                    it.ml = o.optInt("ml") == 1
                }
            }
            its.add(it)
        }
        val hits = ArrayList<DrHit>()
        val ha = j.optJSONArray("hit")
        if (ha != null) for (i in 0 until ha.length()) {
            val o = ha.getJSONObject(i)
            val g = o.getJSONArray("g")
            hits.add(DrHit(o.getString("k"), o.optInt("j"), g.getDouble(0).toFloat(), g.getDouble(1).toFloat(), g.getDouble(2).toFloat(), g.getDouble(3).toFloat()))
        }
        return DrReg(j.optDouble("h", 0.0).toFloat(), its, hits)
    }

    /** "0" = closed, "h" = hidden behind a page dialog, else the JSON model from ui.html nlDrPush(). */
    fun set(json: String) {
        if (!ENABLED || json == last) return
        last = json
        if (json == "0") {
            open = false
            dp.animate().cancel()
            if (dp.visibility == View.VISIBLE)
                dp.animate().translationX(-wpx.toFloat()).setDuration(220).setInterpolator(DecelerateInterpolator())
                    .withEndAction { if (!open) dp.visibility = View.GONE }.start()
            return
        }
        if (json == "h") {
            dp.animate().cancel()
            dp.visibility = View.GONE
            return
        }
        val j = JSONObject(json)
        ver = j.optInt("v")
        val p = j.getJSONObject("p")
        hov = p.optLong("hov").toInt()
        val card = p.optLong("card").toInt()
        dp.setBackgroundColor(if (card == 0) -1 else card)
        wpx = Math.round(j.optDouble("w", 280.0).toFloat() * d)
        dp.setPadding(0, Math.round(j.optDouble("pt", 0.0).toFloat() * d), 0, Math.round(j.optDouble("pb", 0.0).toFloat() * d))
        val lp = dp.layoutParams
        if (lp != null && lp.width != wpx) { lp.width = wpx; dp.layoutParams = lp }

        tabs.hov = hov
        foot.hov = hov
        tabs.reg = parseReg(j.getJSONObject("tabs"))
        tabs.requestLayout(); tabs.invalidate()
        val fr = parseReg(j.getJSONObject("foot"))
        foot.reg = fr
        foot.visibility = if (fr.h > 0f) View.VISIBLE else View.GONE
        foot.requestLayout(); foot.invalidate()
        val ra = j.getJSONArray("rows")
        val rows = ArrayList<DrReg>(ra.length())
        for (i in 0 until ra.length()) rows.add(parseReg(ra.getJSONObject(i)))
        ad.rows = rows
        ad.hov = hov
        ad.notifyDataSetChanged()

        val wasOpen = open
        open = true
        dp.animate().cancel()
        if (dp.visibility != View.VISIBLE) {
            dp.translationX = if (!wasOpen) -wpx.toFloat() else 0f
            dp.visibility = View.VISIBLE
        }
        if (dp.translationX != 0f)
            dp.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }
}
