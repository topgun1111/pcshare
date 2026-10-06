package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/*
 * Native app chrome: top bar (main + selection mode), "New folder" button, bottom action dock (+ clipboard strip), snackbar and download cards.
 * Same idea as NlHead / NlSearch: the HTML elements stay in the page UNCHANGED underneath (they keep the layout, --dockh, timers and all handlers);
 * each native view is laid exactly on top of its HTML twin (rect from ui.html nlChPush()) and takes over drawing + touch feedback.
 * A tap goes back to the page as nlCh(event, id) which clicks the very same HTML handler, so behaviour cannot drift.
 * Anything the page covers with a dialog / drawer / viewer is hidden here, so the page's own scrim and animation show as before.
 * Kill switches: NlChrome.ENABLED = false  /  localStorage ls_nlc='0'.   NOT compiled / NOT device-tested.
 */

/** Palette for the chrome (ARGB ints sent by ui.html nlChPal()). */
class NlCp(private val j: JSONObject) {
    private fun c(n: String) = j.optLong(n).toInt()
    val bar = c("bar"); val onbar = c("onbar"); val barmut = c("barmut"); val cont = c("cont"); val bd = c("bd")
    val sel = c("sel"); val onsel = c("onsel"); val ac = c("ac"); val onac = c("onac"); val hov = c("hov")
    val err = c("err"); val fg = c("fg"); val mut = c("mut")
    val fab = c("fab"); val onfab = c("onfab")
}

/** Base: custom-drawn view with tap targets. A touch that starts outside every target is NOT consumed (it falls through to the page). */
abstract class NlHitView(c: Context, protected val d: Float) : View(c) {
    var pal: NlCp? = null
    var icons: Map<String, Path?> = emptyMap()
    var onTap: ((String) -> Unit)? = null

    protected class Hit(val id: String, val r: RectF)
    protected val hits = ArrayList<Hit>()
    protected var pressed: String? = null
    protected val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val rf = RectF()
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    protected val med: Typeface = Fnt.med()
    protected val semi: Typeface = Fnt.semi()

    protected fun tp(sizeDp: Float, tf: Typeface): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizeDp * d }

    /** Baseline that centres the text vertically on [cy]. */
    protected fun ty(cy: Float, p: Paint): Float { val fm = p.fontMetrics; return cy - (fm.ascent + fm.descent) / 2f }

    protected fun fit(s: String, p: TextPaint, w: Float): String =
        if (w <= 0f) "" else TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString()

    /** A 24x24 Material path drawn [sizeDp] wide, centred on (cx, cy). */
    protected fun glyph(cv: Canvas, key: String, cx: Float, cy: Float, sizeDp: Float, color: Int) {
        val path = icons[key] ?: return
        val s = sizeDp * d / 24f
        cv.save(); cv.translate(cx - sizeDp * d / 2f, cy - sizeDp * d / 2f); cv.scale(s, s)
        fillP.style = Paint.Style.FILL; fillP.color = color; fillP.shader = null
        cv.drawPath(path, fillP)
        cv.restore()
    }

    protected fun hitAt(x: Float, y: Float): String? {
        for (i in hits.indices.reversed()) if (hits[i].r.contains(x, y)) return hits[i].id
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = hitAt(e.x, e.y) ?: return false      // not on a button: let the page have it
                pressed = h; downX = e.x; downY = e.y; invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != null && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) { pressed = null; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; invalidate()
                if (p != null) onTap?.invoke(p)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate(); return true }
        }
        return true
    }
}

/** #top .mainb (menu, title, device name, search, view options, refresh, split screen, settings) and #top .selb (cancel, count, select all). 44 dp. */
class NlBarView(c: Context, d: Float) : NlHitView(c, d) {
    private var sel = false
    private var ttl = ""
    private var nm = ""
    private var dual = false
    private var cnt = ""
    private var bw = 0f
    private var bh = 0f
    private var spinAt = 0L
    private val btn = LinkedHashMap<String, RectF>()
    private var tX = 0f; private var tW = 0f; private var nX = 0f; private var nW = 0f
    private val ttlP = tp(18f, semi)
    private val nmP = tp(12f, med)
    private val cntP = tp(20f, semi)

    fun setData(sel: Boolean, ttl: String, nm: String, dual: Boolean, cnt: String, w: Float, h: Float) {
        this.sel = sel; this.ttl = ttl; this.nm = nm; this.dual = dual; this.cnt = cnt; bw = w; bh = h
        rebuild(); invalidate()
    }

    fun spin() { spinAt = SystemClock.uptimeMillis(); invalidate() }

    private fun rebuild() {
        hits.clear(); btn.clear()
        val s = 40f * d; val top = (bh - s) / 2f
        fun add(id: String, l: Float) { val r = RectF(l, top, l + s, top + s); btn[id] = r; hits.add(Hit(id, r)) }
        if (sel) {
            add("xsel", 4f * d)
            add("allsel", bw - 2f * d - s)
            return
        }
        add("menub", 14f * d)
        var x = bw - 2f * d - s
        add("cog", x); x -= s
        if (dual) { add("dualb", x); x -= s }
        add("scan", x); x -= s
        add("tune", x); x -= s
        add("srch", x)
        // title + device name share the space between the menu button and the first right-hand button (ui.html .ttl: h1 and #nm both flex 0 1 auto, gap 10, #nm max 50 %)
        val l0 = 14f * d + s
        val r0 = btn["srch"]!!.left
        val tw = Math.max(0f, r0 - l0)
        val nmFull = (if (nm.isNotEmpty()) nmP.measureText(nm) + 4f * d else 0f) + 12f * d
        val nmWd = Math.min(nmFull, tw * 0.5f)
        val gap = 10f * d
        tX = l0
        tW = Math.min(ttlP.measureText(ttl), Math.max(0f, tw - nmWd - gap))
        nX = tX + tW + gap
        nW = nmWd
        hits.add(Hit("nm", RectF(nX - 6f * d, 0f, nX + nW + 6f * d, bh)))
    }

    override fun onDraw(cv: Canvas) {
        val p = pal ?: return
        if (bw <= 0f) return
        fillP.shader = null
        fillP.style = Paint.Style.FILL
        fillP.color = if (sel) p.sel else p.bar
        cv.drawRect(0f, 0f, bw, bh, fillP)
        // press feedback: round 40 dp highlight like .ibtn:active
        val pr = pressed
        if (pr != null) {
            val r = btn[pr]
            if (r != null) { fillP.style = Paint.Style.FILL; fillP.color = p.hov; cv.drawCircle(r.centerX(), r.centerY(), 20f * d, fillP) }
        }
        if (sel) {
            btn["xsel"]?.let { glyph(cv, "close", it.centerX(), it.centerY(), 24f, p.onsel) }
            btn["allsel"]?.let { glyph(cv, "selall", it.centerX(), it.centerY(), 24f, p.onsel) }
            val x = 4f * d + 40f * d + 4f * d
            val right = (btn["allsel"]?.left ?: bw) - 4f * d
            cntP.color = p.onsel
            cv.drawText(fit(cnt, cntP, right - x), x, ty(bh / 2f, cntP), cntP)
            return
        }
        btn["menub"]?.let { glyph(cv, "menu", it.centerX(), it.centerY(), 24f, p.onbar) }
        btn["srch"]?.let { glyph(cv, "search", it.centerX(), it.centerY(), 24f, p.onbar) }
        btn["tune"]?.let { glyph(cv, "tune", it.centerX(), it.centerY(), 24f, p.onbar) }
        btn["scan"]?.let {
            val t = SystemClock.uptimeMillis() - spinAt
            if (spinAt != 0L && t >= 0L && t < 800L) {
                cv.save(); cv.rotate(360f * t / 800f, it.centerX(), it.centerY())
                glyph(cv, "refresh", it.centerX(), it.centerY(), 24f, p.onbar)
                cv.restore(); postInvalidateOnAnimation()
            } else glyph(cv, "refresh", it.centerX(), it.centerY(), 24f, p.onbar)
        }
        btn["dualb"]?.let { glyph(cv, "vsplit", it.centerX(), it.centerY(), 24f, p.onbar) }
        btn["cog"]?.let { glyph(cv, "settings", it.centerX(), it.centerY(), 24f, p.onbar) }
        ttlP.color = p.onbar
        cv.drawText(fit(ttl, ttlP, tW), tX, ty(bh / 2f, ttlP), ttlP)
        nmP.color = p.barmut
        val nt = if (nm.isEmpty()) "" else fit(nm, nmP, Math.max(0f, nW - 4f * d - 12f * d))
        if (nt.isNotEmpty()) cv.drawText(nt, nX, ty(bh / 2f, nmP), nmP)
        val iconX = nX + (if (nt.isNotEmpty()) nmP.measureText(nt) + 4f * d else 0f) + 6f * d
        glyph(cv, "edit", iconX, bh / 2f, 12f, p.barmut)
    }
}

/** #fab: "New folder" / "Extract all". 56 dp pill-rect, fixed colours from the CSS. */
class NlFabView(c: Context, d: Float) : NlHitView(c, d) {
    private var lb = ""
    private var ik = "newfolder"
    private var bw = 0f
    private var bh = 0f
    private val lbP = tp(14f, semi)

    init {
        elevation = 6f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 16f * d); o.setAlpha(1f) }
        }
    }

    fun setData(lb: String, ik: String, w: Float, h: Float) {
        this.lb = lb; this.ik = ik; bw = w; bh = h
        hits.clear(); hits.add(Hit("fab", RectF(0f, 0f, bw, bh)))
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        if (pal == null || bw <= 0f) return
        cv.save()
        if (pressed != null) cv.scale(0.96f, 0.96f, bw / 2f, bh / 2f)
        fillP.shader = null; fillP.style = Paint.Style.FILL; val fp = pal ?: return
        fillP.color = fp.fab
        rf.set(0f, 0f, bw, bh); cv.drawRoundRect(rf, 16f * d, 16f * d, fillP)
        glyph(cv, ik, 16f * d + 12f * d, bh / 2f, 24f, fp.onfab)
        lbP.color = fp.onfab
        cv.drawText(fit(lb, lbP, bw - (16f + 24f + 12f + 20f) * d), (16f + 24f + 12f) * d, ty(bh / 2f, lbP), lbP)
        cv.restore()
    }
}

/**
 * #dock: optional clipboard strip (#clip) and the grid of action buttons (#bar, > 5 actions = two rows).
 * The view reaches [inset] px above the dock so the soft shadow (box-shadow 0 -2px 8px) has room; all geometry below is relative to the dock itself.
 */
class NlDockView(c: Context, d: Float) : NlHitView(c, d) {
    class Act(val k: String, val lb: String, val c: String)
    val inset: Float = Math.round(10f * d).toFloat()
    private var acts: List<Act> = emptyList()
    private var clipTx: String? = null
    private var clipPaste = false
    private var bw = 0f
    private var bh = 0f
    private val cells = ArrayList<RectF>()
    private val strip = RectF()
    private val closeR = RectF()
    private val pasteR = RectF()
    private val lbP = tp(11f, semi)
    private val clipP = tp(14f, med)
    private val pasteP = tp(14f, semi)

    fun setData(acts: List<Act>, clipTx: String?, clipPaste: Boolean, w: Float, h: Float) {
        this.acts = acts; this.clipTx = clipTx; this.clipPaste = clipPaste; bw = w; bh = h
        rebuild(); invalidate()
    }

    private fun rebuild() {
        hits.clear(); cells.clear()
        var y = inset + 8f * d
        if (clipTx != null) {
            strip.set(4f * d, y, bw - 4f * d, y + 48f * d)
            closeR.set(strip.right - 6f * d - 36f * d, strip.centerY() - 18f * d, strip.right - 6f * d, strip.centerY() + 18f * d)
            hits.add(Hit("clipx", closeR))
            if (clipPaste) {
                val pw = pasteP.measureText("Paste here") + 32f * d
                pasteR.set(closeR.left - 8f * d - pw, strip.centerY() - 18f * d, closeR.left - 8f * d, strip.centerY() + 18f * d)
                hits.add(Hit("paste", pasteR))
            }
            y += 48f * d + 8f * d
        }
        val n = acts.size
        if (n > 0) {
            val cols = if (n <= 5) Math.max(n, 1) else (n + 1) / 2
            val cw = (bw - 8f * d) / cols
            val rh = 63.4f * d
            for (i in 0 until n) {
                val row = i / cols; val col = i % cols
                val top = y + row * (rh + 2f * d)
                val r = RectF(4f * d + col * cw, top, 4f * d + (col + 1) * cw, top + rh)
                cells.add(r); hits.add(Hit("a$i", r))
            }
        }
    }

    override fun onDraw(cv: Canvas) {
        val p = pal ?: return
        if (bw <= 0f) return
        // shadow above the top edge
        fillP.style = Paint.Style.FILL
        fillP.shader = LinearGradient(0f, 0f, 0f, inset, 0x00000000, 0x22000000, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, bw, inset, fillP)
        fillP.shader = null
        fillP.color = p.cont; cv.drawRect(0f, inset, bw, bh, fillP)
        fillP.color = p.bd; cv.drawRect(0f, inset, bw, inset + d, fillP)
        // clipboard strip
        val ct = clipTx
        if (ct != null) {
            fillP.color = p.sel; cv.drawRoundRect(strip, 16f * d, 16f * d, fillP)
            val x = strip.left + 16f * d
            val right = (if (clipPaste) pasteR.left else closeR.left) - 8f * d
            clipP.color = p.onsel
            cv.drawText(fit(ct, clipP, right - x), x, ty(strip.centerY(), clipP), clipP)
            if (clipPaste) {
                fillP.color = p.ac; cv.drawRoundRect(pasteR, 18f * d, 18f * d, fillP)
                if (pressed == "paste") { fillP.color = p.hov; cv.drawRoundRect(pasteR, 18f * d, 18f * d, fillP) }
                pasteP.color = p.onac
                val tw = pasteP.measureText("Paste here")
                cv.drawText("Paste here", pasteR.centerX() - tw / 2f, ty(pasteR.centerY(), pasteP), pasteP)
            }
            if (pressed == "clipx") { fillP.color = p.hov; cv.drawCircle(closeR.centerX(), closeR.centerY(), 18f * d, fillP) }
            glyph(cv, "close", closeR.centerX(), closeR.centerY(), 24f, p.onsel)
        }
        // action buttons
        for ((i, a) in acts.withIndex()) {
            if (i >= cells.size) break
            val r = cells[i]
            val pri = a.c.contains("pri"); val dng = a.c.contains("dng")
            val col = if (dng) p.err else p.fg
            val pill = RectF(r.centerX() - 28f * d, r.top + 4f * d, r.centerX() + 28f * d, r.top + 4f * d + 36f * d)
            if (pri) { fillP.color = p.ac; cv.drawRoundRect(pill, 18f * d, 18f * d, fillP) }
            if (pressed == "a$i") { fillP.color = p.hov; cv.drawRoundRect(pill, 18f * d, 18f * d, fillP) }
            glyph(cv, a.k, pill.centerX(), pill.centerY(), 30f, if (pri) p.onac else col)
            lbP.color = col
            val t = fit(a.lb, lbP, r.width() - 2f * d)
            cv.drawText(t, r.centerX() - lbP.measureText(t) / 2f, ty(pill.bottom + 4f * d + 7.7f * d, lbP), lbP)
        }
    }
}

/** #toast: message, optional Cancel and progress bar. Fixed dark snackbar colours from the CSS. */
class NlToastView(c: Context, d: Float) : NlHitView(c, d) {
    private var tx = ""
    private var pct = -1f
    private var cancel = false
    private var bw = 0f
    private var bh = 0f
    private val txP = tp(14f, med)
    private val cxP = tp(14f, semi)
    private var lay: StaticLayout? = null
    private var layY = 0f
    private val cancelR = RectF()

    init {
        elevation = 6f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 12f * d); o.setAlpha(1f) }
        }
    }

    fun setData(tx: String, pct: Float, cancel: Boolean, w: Float, h: Float) {
        this.tx = tx; this.pct = pct; this.cancel = cancel; bw = w; bh = h
        hits.clear()
        val cw = if (cancel) cxP.measureText("Cancel") + 24f * d else 0f
        cancelR.set(bw - 16f * d + 6f * d - cw, 10f * d, bw - 16f * d + 6f * d, 10f * d + 31.6f * d)
        if (cancel) hits.add(Hit("tc", cancelR))
        val avail = Math.max(1, (bw - 32f * d - (if (cancel) cw + 6f * d else 0f)).toInt())
        val fm = txP.fontMetrics
        val fh = fm.descent - fm.ascent
        val lh = 19.6f * d                                   // CSS line-height 1.4 x 14
        lay = StaticLayout.Builder.obtain(tx, 0, tx.length, txP, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(Math.max(0f, lh - fh), 1f).build()
        layY = 14f * d + (lh - fh) / 2f
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        if (pal == null || bw <= 0f) return
        fillP.shader = null; fillP.style = Paint.Style.FILL; fillP.color = 0xFF2E3133.toInt()
        rf.set(0f, 0f, bw, bh); cv.drawRoundRect(rf, 12f * d, 12f * d, fillP)
        txP.color = 0xFFF0F0F0.toInt()
        lay?.let { cv.save(); cv.translate(16f * d, layY); it.draw(cv); cv.restore() }
        if (cancel) {
            if (pressed == "tc") { fillP.color = 0x22FFFFFF; cv.drawRoundRect(cancelR, 8f * d, 8f * d, fillP) }
            cxP.color = 0xFFA8C7FA.toInt()
            cv.drawText("Cancel", cancelR.left + 12f * d, ty(cancelR.centerY(), cxP), cxP)
        }
        if (pct >= 0f) {
            val top = bh - 14f * d - 4f * d; val l = 16f * d; val r = bw - 16f * d
            fillP.color = 0x33FFFFFF; rf.set(l, top, r, top + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
            val w = (r - l) * Math.min(100f, pct) / 100f
            if (w > 0f) { fillP.color = 0xFFA8C7FA.toInt(); rf.set(l, top, l + w, top + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP) }
        }
    }
}

/** #dlw: one card per download (name, Cancel, progress bar, info line). Sits on the container's rect; each card's y/height come from the page. */
class NlDlView(c: Context, d: Float) : NlHitView(c, d) {
    class Card(val key: String, val n: String, val cancel: Boolean, val w: Float, val ind: Boolean, val info: String, val y: Float, val h: Float)
    private var cards: List<Card> = emptyList()
    private var bw = 0f
    private var bh = 0f
    private val nmP = tp(14f, semi)
    private val cxP = tp(13f, med)
    private val infoP = tp(12f, med)
    private val btnR = HashMap<String, RectF>()

    fun setData(cards: List<Card>, w: Float, h: Float) {
        this.cards = cards; bw = w; bh = h
        hits.clear(); btnR.clear()
        for (k in cards) if (k.cancel) {
            val bwid = cxP.measureText("Cancel") + 16f * d
            val top = k.y * d + 12f * d                                    // k.y arrives in CSS px
            val r = RectF(bw - 14f * d - bwid, top, bw - 14f * d, top + 26.2f * d)
            btnR[k.key] = r; hits.add(Hit("dl:" + k.key, r))
        }
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        if (pal == null || bw <= 0f) return
        var anim = false
        for (k in cards) {
            val y = k.y * d; val h = k.h * d
            fillP.shader = null; fillP.style = Paint.Style.FILL; fillP.color = 0xFF2E3133.toInt()
            rf.set(0f, y, bw, y + h); cv.drawRoundRect(rf, 12f * d, 12f * d, fillP)
            val rowC = y + 12f * d + 13.1f * d
            val br = btnR[k.key]
            nmP.color = 0xFFF0F0F0.toInt()
            val right = (br?.left ?: (bw - 14f * d)) - (if (br != null) 10f * d else 0f)
            cv.drawText(fit(k.n, nmP, right - 14f * d), 14f * d, ty(rowC, nmP), nmP)
            if (br != null) {
                if (pressed == "dl:" + k.key) { fillP.color = 0x22FFFFFF; cv.drawRoundRect(br, 8f * d, 8f * d, fillP) }
                cxP.color = 0xFFA8C7FA.toInt()
                cv.drawText("Cancel", br.left + 8f * d, ty(br.centerY(), cxP), cxP)
            }
            val bt = y + 12f * d + 26.2f * d + 8f * d
            val l = 14f * d; val r = bw - 14f * d
            fillP.color = 0x33FFFFFF; rf.set(l, bt, r, bt + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
            fillP.color = 0xFFA8C7FA.toInt()
            if (k.ind) {
                anim = true
                val ph = (SystemClock.uptimeMillis() % 1100L) / 1100f
                val sw = (r - l) * 0.35f
                val sl = l + (r - l) * (-0.35f + 1.35f * ph)
                cv.save(); cv.clipRect(l, bt, r, bt + 4f * d)
                rf.set(sl, bt, sl + sw, bt + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
                cv.restore()
            } else if (k.w > 0f) {
                rf.set(l, bt, l + (r - l) * Math.min(100f, k.w) / 100f, bt + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
            }
            infoP.color = 0xFFF0F0F0.toInt(); infoP.alpha = 204
            cv.drawText(fit(k.info, infoP, r - l), l, ty(bt + 4f * d + 6f * d + 8.4f * d, infoP), infoP)
        }
        if (anim) postInvalidateOnAnimation()
    }
}

/**
 * Owner of the five views. [layer] goes into MainActivity's root above the page and the native list; it has no background and no click handling,
 * so every touch outside a button reaches the views below. [fire] runs nlCh(event, id) in the page.
 */
class NlChrome(act: Activity, private val fire: (String, String) -> Unit) {
    companion object {
        /** Kill switch: false = the page's HTML bars / buttons / snackbar only. */
        const val ENABLED = true
    }

    private val d = act.resources.displayMetrics.density
    val layer = FrameLayout(act)
    private val bar = NlBarView(act, d)
    private val dock = NlDockView(act, d)
    private val fab = NlFabView(act, d)
    private val dls = NlDlView(act, d)
    private val toast = NlToastView(act, d)
    private val all: List<NlHitView> = listOf(bar, dock, fab, dls, toast)
    private val icons = HashMap<String, Path?>()
    private var last = ""

    init {
        for (v in all) { v.visibility = View.GONE; v.icons = icons; layer.addView(v, FrameLayout.LayoutParams(1, 1)) }
        bar.onTap = { id -> if (id == "scan") bar.spin(); fire("btn", id) }
        fab.onTap = { _ -> fire("btn", "fab") }
        toast.onTap = { _ -> fire("btn", "tc") }
        dock.onTap = { id ->
            when {
                id == "paste" -> fire("paste", "")
                id == "clipx" -> fire("clipx", "")
                id.startsWith("a") -> fire("act", id.substring(1))
                else -> { }
            }
        }
        dls.onTap = { id -> fire("dlx", id.removePrefix("dl:")) }
    }

    private fun px(v: Double): Int = Math.round((v * d).toFloat())

    /** Put [v] on the CSS-px rect [r] = [left, top, width, height]; null hides it. Returns whether it is shown. */
    private fun place(v: View, r: JSONArray?, extraTop: Int = 0): Boolean {
        if (r == null || r.length() < 4) { v.visibility = View.GONE; return false }
        val l = px(r.getDouble(0)); val t = px(r.getDouble(1)) - extraTop
        val w = px(r.getDouble(2)); val h = px(r.getDouble(3)) + extraTop
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
            lp.leftMargin = l; lp.topMargin = t; lp.width = w; lp.height = h
            v.layoutParams = lp
        }
        v.visibility = View.VISIBLE
        return true
    }

    /** json from ui.html nlChPush(): "0" = nothing (page dialog / drawer / viewer on top), else {p, ic, bar?, fab?, dock?, t?, dl?}. */
    fun set(json: String) {
        if (!ENABLED || json == last) return
        last = json
        if (json == "0") { for (v in all) v.visibility = View.GONE; return }
        val j = JSONObject(json)
        j.optJSONObject("p")?.let { pj -> val cp = NlCp(pj); for (v in all) v.pal = cp }
        j.optJSONObject("ic")?.let { ic ->
            val ks = ic.keys()
            while (ks.hasNext()) { val k = ks.next(); if (!icons.containsKey(k)) icons[k] = NlGlyph.parse(ic.getString(k)) }
        }

        val b = j.optJSONObject("bar")
        if (place(bar, b?.optJSONArray("r")) && b != null)
            bar.setData(b.optInt("sel") == 1, b.optString("t"), b.optString("nm"), b.optInt("du") == 1, b.optString("c"),
                bar.layoutParams.width.toFloat(), bar.layoutParams.height.toFloat())

        val f = j.optJSONObject("fab")
        if (place(fab, f?.optJSONArray("r")) && f != null)
            fab.setData(f.optString("l"), f.optString("k", "newfolder"), fab.layoutParams.width.toFloat(), fab.layoutParams.height.toFloat())

        val k = j.optJSONObject("dock")
        if (place(dock, k?.optJSONArray("r"), Math.round(dock.inset)) && k != null) {
            val a = k.optJSONArray("a"); val acts = ArrayList<NlDockView.Act>()
            if (a != null) for (i in 0 until a.length()) { val o = a.getJSONObject(i); acts.add(NlDockView.Act(o.optString("k"), o.optString("l"), o.optString("c"))) }
            val cl = k.optJSONObject("cl")
            dock.setData(acts, cl?.optString("t"), cl != null && cl.optInt("p") == 1, dock.layoutParams.width.toFloat(), dock.layoutParams.height.toFloat())
        }

        val t = j.optJSONObject("t")
        if (place(toast, t?.optJSONArray("r")) && t != null)
            toast.setData(t.optString("x"), t.optDouble("p", -1.0).toFloat(), t.optInt("c") == 1,
                toast.layoutParams.width.toFloat(), toast.layoutParams.height.toFloat())

        val dl = j.optJSONObject("dl")
        if (place(dls, dl?.optJSONArray("r")) && dl != null) {
            val a = dl.optJSONArray("k"); val cards = ArrayList<NlDlView.Card>()
            if (a != null) for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                cards.add(NlDlView.Card(o.optString("k"), o.optString("n"), o.optInt("x") == 1, o.optDouble("w", 0.0).toFloat(),
                    o.optInt("i") == 1, o.optString("s"), o.optDouble("y", 0.0).toFloat(), o.optDouble("h", 0.0).toFloat()))
            }
            dls.setData(cards, dls.layoutParams.width.toFloat(), dls.layoutParams.height.toFloat())
        }
    }
}
