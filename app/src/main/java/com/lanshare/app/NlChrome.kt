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
import android.os.Build
import android.graphics.Typeface
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
    // TalkBack (Lumen Round 2 Part C): every hit rectangle with a label becomes a virtual button.
    /** Spoken name of the button [id]; null = not announced. Subclasses override. */
    protected open fun labelOf(id: String): String? = null
    private val ebt = object : androidx.customview.widget.ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            for (i in hits.indices.reversed()) if (hits[i].r.contains(x, y) && labelOf(hits[i].id) != null) return i
            return INVALID_ID
        }
        override fun getVisibleVirtualViews(ids: MutableList<Int>) { for (i in hits.indices) if (labelOf(hits[i].id) != null) ids.add(i) }
        override fun onPopulateNodeForVirtualView(i: Int, n: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
            val h = hits.getOrNull(i) ?: return
            n.contentDescription = labelOf(h.id) ?: ""
            n.className = "android.widget.Button"
            n.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
            val r = android.graphics.Rect(); h.r.roundOut(r); if (r.isEmpty) r.set(0, 0, 1, 1); n.setBoundsInParent(r)   // TalkBack throws on empty bounds
        }
        override fun onPerformActionForVirtualView(i: Int, action: Int, args: android.os.Bundle?): Boolean {
            val h = hits.getOrNull(i) ?: return false
            if (action == androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) { onTap?.invoke(h.id); return true }
            return false
        }
    }
    private val a11yInv = Runnable { ebt.invalidateRoot() }
    private var a11yReady = false   // View's constructor may call invalidate() before the fields above exist
    init { androidx.core.view.ViewCompat.setAccessibilityDelegate(this, ebt); a11yReady = true }
    override fun dispatchHoverEvent(e: MotionEvent): Boolean = ebt.dispatchHoverEvent(e) || super.dispatchHoverEvent(e)
    override fun invalidate() {
        super.invalidate()
        if (a11yReady && (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled) { removeCallbacks(a11yInv); postDelayed(a11yInv, 250) }
    }
    protected val rf = RectF()
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    // Lumen 12 press ripple, drawn natively: from the touch point to the farthest corner in 280 ms (decelerate), 14 % ink, fades 160 ms after release.
    private var rpId: String? = null
    private var rpX = 0f
    private var rpY = 0f
    private var rpT0 = 0L
    private var rpEnd = 0L
    private val rpClip = Path()
    private val rpReduce = LumenPrefs.reduceMotion(c)

    private fun rpStart(id: String, x: Float, y: Float) { rpId = id; rpX = x; rpY = y; rpT0 = SystemClock.uptimeMillis(); rpEnd = 0L }
    private fun rpRelease() { if (rpId != null && rpEnd == 0L) { rpEnd = SystemClock.uptimeMillis(); invalidate() } }

    /** Paint the ripple of target [id] clipped to [shape] (corner radius [rad]) in [color] (alpha is applied here). Call it after the target's fill, before its glyph. */
    protected fun ripple(cv: Canvas, id: String, shape: RectF, rad: Float, color: Int) {
        if (rpId != id) return
        val now = SystemClock.uptimeMillis()
        val grow = if (rpReduce) 1f else Math.min(1f, (now - rpT0) / 280f)
        val e = 1f - (1f - grow) * (1f - grow)
        var a = 1f
        if (rpEnd != 0L) { a = 1f - (now - rpEnd) / 160f; if (a <= 0f) { rpId = null; return } }
        else if (rpReduce) a = 0.6f
        val far = Math.hypot(Math.max(rpX - shape.left, shape.right - rpX).toDouble(), Math.max(rpY - shape.top, shape.bottom - rpY).toDouble()).toFloat()
        cv.save()
        rpClip.reset(); rpClip.addRoundRect(shape, rad, rad, Path.Direction.CW); cv.clipPath(rpClip)
        fillP.shader = null; fillP.style = Paint.Style.FILL
        fillP.color = (color and 0x00FFFFFF) or (Math.round(255f * 0.14f * a) shl 24)
        cv.drawCircle(rpX, rpY, far * e, fillP)
        cv.restore()
        if (rpEnd != 0L || grow < 1f) postInvalidateOnAnimation()   // held and fully grown: no redraw loop
    }

    protected val med: Typeface = Fnt.med()
    protected val semi: Typeface = Fnt.semi()

    // Lumen.of() parses the palette JSON; onDraw runs every frame during a ripple, so keep the result until the page sends a new palette.
    private var lmSig: String? = null
    private var lmCache: Lumen? = null
    protected fun lumen(): Lumen {
        val j = AppPal.json(context)
        val c = lmCache
        if (c != null && j === lmSig) return c
        val n = Lumen.of(context); lmCache = n; lmSig = j
        return n
    }

    protected fun tp(sizeDp: Float, tf: Typeface): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = sizeDp * d * UiScale.tf }

    /** Baseline that centres the text vertically on [cy]. */
    protected fun ty(cy: Float, p: Paint): Float { val fm = p.fontMetrics; return cy - (fm.ascent + fm.descent) / 2f }

    protected fun fit(s: String, p: TextPaint, w: Float): String =
        if (w <= 0f) "" else TextUtils.ellipsize(s, p, w, TextUtils.TruncateAt.END).toString()

    /** A 24x24 Material path drawn [sizeDp] wide, centred on (cx, cy). */
    protected fun glyph(cv: Canvas, key: String, cx: Float, cy: Float, sizeDp: Float, color: Int) {
        val path = icons[key] ?: return
        val s = sizeDp * d / 24f
        cv.save(); cv.translate(cx - sizeDp * d / 2f, cy - sizeDp * d / 2f); cv.scale(s, s)
        val st = ChStroke.keys.contains(key)
        if (st) { fillP.style = Paint.Style.STROKE; fillP.strokeWidth = LumenIcons.SW; fillP.strokeCap = Paint.Cap.ROUND; fillP.strokeJoin = Paint.Join.ROUND }
        else fillP.style = Paint.Style.FILL
        fillP.color = color; fillP.shader = null
        cv.drawPath(path, fillP)
        if (st) { fillP.style = Paint.Style.FILL; fillP.strokeCap = Paint.Cap.BUTT; fillP.strokeJoin = Paint.Join.MITER }
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
                pressed = h; downX = e.x; downY = e.y; rpStart(h, e.x, e.y); invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != null && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) { pressed = null; rpRelease(); invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; rpRelease(); invalidate()
                if (p != null) onTap?.invoke(p)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; rpRelease(); invalidate(); return true }
        }
        return true
    }
}

/** #top .mainb (menu, title, device name, search, view options, refresh, split screen, settings) and #top .selb (cancel, count, select all). 52 dp. */
class NlBarView(c: Context, d: Float) : NlHitView(c, d) {
    override fun labelOf(id: String): String? = when (id) {
        "menub" -> "Menu"; "cog" -> "Settings"; "dualb" -> "Split screen"; "scan" -> "Refresh"
        "tune" -> "View options"; "srch" -> "Search"; "xsel" -> "Cancel selection"; "allsel" -> "Select all"; else -> null
    }
    private var sel = false
    private var ttl = ""
    private var nm = ""
    private var dual = false
    private var cnt = ""
    private var bw = 0f
    private var bh = 0f
    private var spinAt = 0L
    private var selAt = 0L
    private var elev = true          // N7: false = list at its top, the bar is transparent (ink on canvas)
    private var elevK = 1f           // 0..1 fill amount, eased over 160 ms
    private var elevAt = 0L
    fun setElevated(e: Boolean) {
        if (e == elev) return
        elev = e; elevAt = SystemClock.uptimeMillis()
        if (LumenPrefs.reduceMotion(context)) { elevK = if (e) 1f else 0f; elevAt = 0L }
        invalidate()
    }
    private val btn = LinkedHashMap<String, RectF>()
    private var tX = 0f; private var tW = 0f; private var nX = 0f; private var nW = 0f
    private val ttlP = tp(18f, semi)
    private val nmP = tp(12f, med)
    private val cntP = tp(20f, semi)

    var perm = false             // Lumen 9.4: permanent drawer (>= 900 dp) = no menu button, the title starts at the left margin
    fun setData(sel: Boolean, ttl: String, nm: String, dual: Boolean, cnt: String, w: Float, h: Float) {
        if (sel && !this.sel && !LumenPrefs.reduceMotion(context)) selAt = SystemClock.uptimeMillis()   // selection bar slides over the bar (200 ms)
        this.sel = sel; this.ttl = ttl; this.nm = nm; this.dual = dual; this.cnt = cnt; bw = w; bh = h
        rebuild(); invalidate()
    }

    fun spin() { spinAt = SystemClock.uptimeMillis(); invalidate() }

    private fun rebuild() {
        hits.clear(); btn.clear()
        val s = 48f * d; val top = (bh - s) / 2f
        fun add(id: String, l: Float) { val r = RectF(l, top, l + s, top + s); btn[id] = r; hits.add(Hit(id, r)) }
        if (sel) {
            add("xsel", 4f * d)
            add("allsel", bw - 2f * d - s)
            return
        }
        if (!perm) add("menub", 14f * d)
        var x = bw - 2f * d - s
        add("cog", x); x -= s
        if (dual) { add("dualb", x); x -= s }
        add("scan", x); x -= s
        add("tune", x); x -= s
        add("srch", x)
        // title + device name share the space between the menu button and the first right-hand button (ui.html .ttl: h1 and #nm both flex 0 1 auto, gap 10, #nm max 50 %)
        val l0 = if (perm) 16f * d else 14f * d + s
        val r0 = btn["srch"]!!.left
        val tw = Math.max(0f, r0 - l0)
        val nmWd = 0f   // the device name was removed from the top bar (rename it in Settings)
        val gap = 0f
        tW = Math.min(ttlP.measureText(ttl), Math.max(0f, tw - nmWd - gap))
        tX = Math.max(l0, r0 - 4f * d - tW)   // title sits right next to the Search button (right-aligned in its slot)
        nX = tX + tW + gap
        nW = nmWd
    }

    override fun onDraw(cv: Canvas) {
        val p = pal ?: return
        if (bw <= 0f) return
        fillP.shader = null
        fillP.style = Paint.Style.FILL
        val sliding = sel && selAt != 0L && SystemClock.uptimeMillis() - selAt < 200L
        // Part 4b (N1 b): G1 chrome fill by glass step (tint-raised stand-in, opaque at F3)
        // transparent only where the page twin (#top) is transparent too: glass steps F0 / F1; F2 / F3 stay filled
        val goal = 1f   // 4d fix: the page's own #top bar (title + glyphs) sits under this view, so the fill must stay opaque or both get drawn on top of each other (garbled title)
        if (elevAt != 0L) {
            val t = Math.min(1f, (SystemClock.uptimeMillis() - elevAt) / 160f)
            elevK = if (goal == 1f) t else 1f - t
            if (t < 1f) postInvalidateOnAnimation() else elevAt = 0L
        } else elevK = goal
        val ek = elevK
        // text / glyph colour: the bar's own (on a filled bar) fades to the theme ink (on the transparent bar)
        val ob = if (sel) p.onbar else LumenCategory.mix(lumen().ink, p.onbar, ek)
        // 4f N1 (c): real backdrop blur under the bar (F0 / F1, API 31+); otherwise the 4b tint-raised stand-in
        val barBase = if (sel && !sliding) p.sel else p.bar
        val live = ek > 0.01f && NlBackdrop.draw(cv, this, parent as? View, 0f)
        val cf = if (live) LumenGlass.blurFill(context, barBase) else LumenGlass.chromeFill(context, barBase)
        fillP.color = LumenGlass.withAlpha(cf or 0xFF000000.toInt(), ek)
        cv.drawRect(0f, 0f, bw, bh, fillP)
        // gChrome: the top feels lighter (white 8 % on light bars / 5 % on dark bars, fading out by the bottom edge); flat at F3
        val dark = AppPal.night(context)
        if (LumenGlass.chromeGradient(context)) {
            fillP.alpha = Math.round(255f * ek)
            fillP.shader = LinearGradient(0f, 0f, 0f, bh, if (dark) 0x0DFFFFFF else 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, bw, bh, fillP)
            fillP.shader = null
        }
        // outer hairline (spec 6.1 item 6): 0.5 dp edge at 60 % along the bottom edge; F3 = 1 dp opaque edge
        run {
            val f3 = !LumenGlass.chromeGradient(context)
            val hw = if (f3) Math.max(1f, d) else Math.max(1f, d * 0.5f)
            fillP.style = Paint.Style.FILL
            fillP.color = LumenGlass.withAlpha(lumen().edge, (if (f3) 1f else 0.6f) * ek)
            cv.drawRect(0f, bh - hw, bw, bh, fillP)
        }
        var slid = false
        if (sel && selAt != 0L) {
            val t = Math.min(1f, (SystemClock.uptimeMillis() - selAt) / 200f)
            if (t < 1f) { val e = 1f - (1f - t) * (1f - t); cv.save(); cv.translate(0f, -bh * (1f - e)); cv.drawRect(0f, 0f, bw, bh, fillP.apply { color = p.sel }); slid = true; postInvalidateOnAnimation() } else selAt = 0L
        }
        // press feedback: ripple inside the round 48 dp target
        val rpS = RectF()
        for ((k, r) in btn) { rpS.set(r); ripple(cv, k, rpS, 24f * d, if (sel) p.onsel else p.onbar) }
        if (sel) {
            btn["xsel"]?.let { glyph(cv, "close", it.centerX(), it.centerY(), 24f, p.onsel) }
            btn["allsel"]?.let { glyph(cv, "selall", it.centerX(), it.centerY(), 24f, p.onsel) }
            val x = 4f * d + 48f * d + 4f * d
            val right = (btn["allsel"]?.left ?: bw) - 4f * d
            cntP.color = p.onsel
            cv.drawText(fit(cnt, cntP, right - x), x, ty(bh / 2f, cntP), cntP)
            if (slid) cv.restore()
            return
        }
        btn["menub"]?.let { glyph(cv, "menu", it.centerX(), it.centerY(), 32f, ob) }   // main menu icon: 32 dp (search / view / refresh / split / settings: 30 dp)
        btn["srch"]?.let { glyph(cv, "search", it.centerX(), it.centerY(), 30f, ob) }
        btn["tune"]?.let { glyph(cv, "tune", it.centerX(), it.centerY(), 30f, ob) }
        btn["scan"]?.let {
            val t = SystemClock.uptimeMillis() - spinAt
            if (spinAt != 0L && t >= 0L && t < 800L) {
                cv.save(); cv.rotate(360f * t / 800f, it.centerX(), it.centerY())
                glyph(cv, "refresh", it.centerX(), it.centerY(), 30f, ob)
                cv.restore(); postInvalidateOnAnimation()
            } else glyph(cv, "refresh", it.centerX(), it.centerY(), 30f, ob)
        }
        btn["dualb"]?.let { glyph(cv, "vsplit", it.centerX(), it.centerY(), 30f, ob) }
        btn["cog"]?.let { glyph(cv, "settings", it.centerX(), it.centerY(), 30f, ob) }
        ttlP.color = ob
        cv.drawText(fit(ttl, ttlP, tW), tX, ty(bh / 2f, ttlP), ttlP)
    }
}

/** #fab: "New folder" / "Extract all". 56 dp pill-rect, fixed colours from the CSS. */
class NlFabView(c: Context, d: Float) : NlHitView(c, d) {
    override fun labelOf(id: String): String? = if (id == "fab") lb.ifEmpty { "Add" } else null
    private var lb = ""
    private var ik = "newfolder"
    private var bw = 0f
    private var bh = 0f
    private val lbP = tp(14f, semi)

    init {
        elevation = 6f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height / 2f); o.setAlpha(1f) }   // extended FAB = pill (spec 8.2 / 12.1), same as the page #fab
        }
        if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = (lumen().shadowTint and 0x00FFFFFF) or 0x80000000.toInt(); outlineAmbientShadowColor = 0x33000000 }   // E3 shadow tinted by shadowTint (N4)
    }

    /** Width the pill needs for [text] with the natively scaled label font (icon + paddings = 72 dp). */
    fun needWidth(text: String): Float = (16f + 24f + 12f + 20f) * d + lbP.measureText(text)

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
        val fr = bh / 2f   // pill (extended FAB)
        rf.set(0f, 0f, bw, bh)
        // gGlow: a restrained theme-accent halo, kept outside the pill so it reads as light rather than a colour wash.
        if (LumenGlass.chromeGradient(context) && !LumenPrefs.reduceMotion(context)) {
            fillP.shader = LumenGrad.glow(lumen(), bw * .5f, bh * .5f, Math.max(bw, bh) * .78f, AppPal.night(context))
            cv.drawRoundRect(-8f * d, -8f * d, bw + 8f * d, bh + 8f * d, fr + 8f * d, fr + 8f * d, fillP)
        }
        if (LumenGlass.chromeGradient(context)) fillP.shader = LumenGrad.diag(LumenGrad.fab(fp.fab), 0f, 0f, bw, bh)   // gFab (flat at F3)
        else fillP.shader = null
        cv.drawRoundRect(rf, fr, fr, fillP)
        fillP.shader = null
        ripple(cv, "fab", rf, fr, fp.onfab)
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
    override fun labelOf(id: String): String? = when {
        id == "clipx" -> "Dismiss"; id == "paste" -> "Paste here"
        id.startsWith("a") -> acts.getOrNull(id.substring(1).toIntOrNull() ?: -1)?.lb
        else -> null
    }
    class Act(val k: String, val lb: String, val c: String)
    /** Lumen 12.8: the dock floats (12 dp from the edges, r.xl, 72 dp row); the page reports the card's own rect, so no extra room is needed for a shadow (the system elevation shadow is drawn). */
    val inset: Float = 0f
    private var acts: List<Act> = emptyList()
    private var clipTx: String? = null
    private var clipPaste = false
    private var bw = 0f
    private var bh = 0f
    private var rx = 24f
    private var bg = 0
    private val cells = ArrayList<RectF>()
    private val strip = RectF()
    private val closeR = RectF()
    private val pasteR = RectF()
    private val lbP = tp(14f, semi)
    private var shownAt = 0L

    init {
        elevation = 8f * d
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rx * d); o.setAlpha(1f) }
        }
        if (Build.VERSION.SDK_INT >= 28) {   // E3: shadow tinted by the primary slot (spec 5.4)
            val pr = Lumen.of(c).shadowTint   // N4: real slot (was primary)
            outlineSpotShadowColor = (pr and 0x00FFFFFF) or 0x80000000.toInt()
            outlineAmbientShadowColor = 0x33000000
        }
    }

    /** Icon colour per action key, from the theme's category tones (spec 4.5: light value in light themes, pastel in dark ones) and semantic slots. Unknown keys fall back to the theme text colour. */
    private fun iconColor(k: String, fallback: Int, lm: Lumen): Int {
        val cat = when (k) {
            "copy", "fwd", "back", "send", "search" -> "aud"
            "share", "openw" -> "doc"
            "cut" -> "ppt"
            "zip", "print" -> "zip"
            "edit", "extract", "paste", "add" -> "xls"
            "dup", "merge" -> "vid"
            "palette" -> "pic"
            else -> null
        }
        if (cat != null) return LumenCategory.of(context)[cat].base
        return when (k) { "info" -> lm.info; "hide" -> lm.inkMut; else -> fallback }
    }
    private val clipP = tp(14f, med)
    private val pasteP = tp(14f, semi)

    /** [bg] = the page dock's computed background (ARGB, 0 = unknown), [rx] = its corner radius in CSS px. */
    fun setData(acts: List<Act>, clipTx: String?, clipPaste: Boolean, w: Float, h: Float, bg: Int, rx: Float) {
        this.acts = acts; this.clipTx = clipTx; this.clipPaste = clipPaste; bw = w; bh = h; this.bg = bg
        if (rx > 0f && rx != this.rx) { this.rx = rx; invalidateOutline() }
        rebuild(); invalidate()
    }

    private var leaving = false
    /** Spec 13.3 bar slide, exit half: slides down 24 dp + fades (140 ms accelerate) instead of vanishing; [NlChrome.set] calls it when the page drops the dock. */
    fun leave() {
        if (visibility != VISIBLE || leaving) return
        if (LumenPrefs.reduceMotion(context)) { visibility = GONE; return }
        leaving = true; animate().cancel()
        animate().translationY(24f * d).alpha(0f).setDuration(140).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { leaving = false; if (alpha == 0f) visibility = GONE; translationY = 0f; alpha = 1f }.start()
    }
    /** True while [leave] runs; a dock that comes back mid-exit is brought in again by [enter]. */
    val isLeaving: Boolean get() = leaving
    fun cancelLeave() { if (leaving) { leaving = false; animate().cancel(); translationY = 0f; alpha = 1f } }

    /** Slide-up + fade when the bar appears (280 ms = LumenMotion.MED, decelerate; same as the page lmdock); skipped with Reduce motion (spec 13.4: 120 ms fade only). */
    fun enter() {
        animate().cancel()
        if (LumenPrefs.reduceMotion(context)) { shownAt = 0L; translationY = 0f; alpha = 0f; animate().alpha(1f).setDuration(120).start(); return }
        shownAt = SystemClock.uptimeMillis(); invalidate()   // Lumen 4e N10: the action cells stagger in 20 ms apart after the bar
        translationY = 24f * d; alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(LumenMotion.MED).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun rebuild() {
        hits.clear(); cells.clear()
        var y = 0f
        if (clipTx != null) {
            strip.set(8f * d, 8f * d, bw - 8f * d, 8f * d + 48f * d)
            closeR.set(strip.right - 6f * d - 36f * d, strip.centerY() - 18f * d, strip.right - 6f * d, strip.centerY() + 18f * d)
            hits.add(Hit("clipx", closeR))
            if (clipPaste) {
                val pw = pasteP.measureText("Paste here") + 32f * d
                pasteR.set(closeR.left - 8f * d - pw, strip.centerY() - 18f * d, closeR.left - 8f * d, strip.centerY() + 18f * d)
                hits.add(Hit("paste", pasteR))
            }
            y = strip.bottom
        }
        val n = Math.min(acts.size, 5)   // the page already collapses everything past 5 into "More"
        if (n > 0) {
            val cw = (bw - 8f * d) / n
            val rh = Math.max(0f, bh - y)
            for (i in 0 until n) {
                val r = RectF(4f * d + i * cw, y, 4f * d + (i + 1) * cw, y + rh)
                cells.add(r); hits.add(Hit("a$i", r))
            }
        }
    }

    override fun onDraw(cv: Canvas) {
        val p = pal ?: return
        if (bw <= 0f) return
        val lm = lumen()
        val rad = rx * d
        val rl = LumenRadius.l(context) * d   // N6: strip / pills follow the theme's r-l (Windows 11 = 12)
        // N1 (b): the page's own tint (or glassTint) by glass step. No backdrop blur is possible under a native view, so F0-F2 are tint-raised (see LumenGlass.chromeFill); F3 = opaque
        val base = if (bg != 0) bg else lm.glassTint
        val live = NlBackdrop.draw(cv, this, parent as? View, rad)   // 4f N1 (c): real blur under the dock pill (F0 / F1, API 31+)
        val raised = if (live) Math.max(0xD2, LumenGlass.blurFill(context, base) ushr 24)   // blurred rows: never less than 82 %
                     else Math.max(0xEB, LumenGlass.chromeFill(context, base) ushr 24)   // list rows run under the dock: never less than 92 %
        val f3 = !LumenGlass.chromeGradient(context)
        fillP.shader = null; fillP.style = Paint.Style.FILL
        fillP.color = if (f3) ((base and 0x00FFFFFF) or 0xFF000000.toInt()) else ((base and 0x00FFFFFF) or (raised shl 24))
        rf.set(0f, 0f, bw, bh); cv.drawRoundRect(rf, rad, rad, fillP)
        // gChrome light from the top (not at F3) + edge: highlight from the top-left (edgeHi) over a 0.5 dp outer hairline (spec 6.1 items 4-6); F3 = 1 dp edge
        if (!f3) { fillP.shader = LinearGradient(0f, 0f, 0f, bh, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); cv.drawRoundRect(rf, rad, rad, fillP); fillP.shader = null }
        val sw = Math.max(1f, d)
        fillP.style = Paint.Style.STROKE
        fillP.strokeWidth = if (f3) sw else Math.max(1f, d * 0.5f); fillP.color = if (f3) lm.edge else LumenGlass.withAlpha(lm.edge, 0.6f)
        rf.set(sw / 2f, sw / 2f, bw - sw / 2f, bh - sw / 2f); cv.drawRoundRect(rf, rad, rad, fillP)
        if (!f3) {
            fillP.strokeWidth = sw
            fillP.shader = LinearGradient(0f, 0f, bw, bh, lm.edgeHi, 0x00FFFFFF, Shader.TileMode.CLAMP)
            rf.set(sw / 2f, sw / 2f, bw - sw / 2f, bh - sw / 2f); cv.drawRoundRect(rf, rad, rad, fillP); fillP.shader = null
        }
        fillP.style = Paint.Style.FILL
        // clipboard strip
        val ct = clipTx
        if (ct != null) {
            fillP.color = p.sel; cv.drawRoundRect(strip, rl, rl, fillP)
            val x = strip.left + 16f * d
            val right = (if (clipPaste) pasteR.left else closeR.left) - 8f * d
            clipP.color = p.onsel
            cv.drawText(fit(ct, clipP, right - x), x, ty(strip.centerY(), clipP), clipP)
            if (clipPaste) {
                fillP.color = p.ac; cv.drawRoundRect(pasteR, 18f * d, 18f * d, fillP)
                ripple(cv, "paste", pasteR, 18f * d, p.onac)
                pasteP.color = p.onac
                val tw = pasteP.measureText("Paste here")
                cv.drawText("Paste here", pasteR.centerX() - tw / 2f, ty(pasteR.centerY(), pasteP), pasteP)
            }
            ripple(cv, "clipx", closeR, 18f * d, p.onsel)
            glyph(cv, "close", closeR.centerX(), closeR.centerY(), 24f, p.onsel)
        }
        // action cells: 24 dp icon in a 56 x 32 pill over a Label M caption; Lumen 4e N10: each cell fades up 6 dp, 20 ms after the one before (160 ms, decelerate)
        val now = SystemClock.uptimeMillis()
        var moving = false
        for ((i, a2) in acts.withIndex()) {
            if (i >= cells.size) break
            val r = cells[i]
            var sk = 1f
            if (shownAt != 0L) {
                val k = ((now - shownAt - 20L * i) / 160f).coerceIn(0f, 1f)
                if (k < 1f) moving = true
                sk = 1f - (1f - k) * (1f - k)
            }
            val lay = if (sk < 1f) cv.saveLayerAlpha(r.left, r.top - 8f * d, r.right, r.bottom + 8f * d, Math.round(255f * sk)) else -1
            if (sk < 1f) cv.translate(0f, (1f - sk) * 6f * d)
            val pri = a2.c.contains("pri"); val dng = a2.c.contains("dng")
            val col = if (dng) lm.error else p.fg
            val icol = if (dng) lm.error else iconColor(a2.k, p.fg, lm)   // colourful icon; label keeps the theme colour
            val top = r.top + Math.max(0f, (r.height() - (40f + 6f + 17f) * d) / 2f)
            val pill = RectF(r.centerX() - 32f * d, top, r.centerX() + 32f * d, top + 40f * d)
            if (pri) {
                // gPrimary: 135 degrees, 2 % white start to 12 % black end (same as Lm.styleButton)
                fillP.shader = LumenGrad.diag(LumenGrad.primary(lm), pill.left, pill.top, pill.right, pill.bottom)
                cv.drawRoundRect(pill, rl, rl, fillP); fillP.shader = null
            }
            ripple(cv, "a$i", pill, rl, if (pri) p.onac else col)
            glyph(cv, a2.k, pill.centerX(), pill.centerY(), 30f, if (pri) p.onac else icol)
            lbP.color = col
            val t = fit(a2.lb, lbP, r.width() - 2f * d)
            cv.drawText(t, r.centerX() - lbP.measureText(t) / 2f, ty(pill.bottom + 6f * d + 8.5f * d, lbP), lbP)
            if (lay >= 0) cv.restoreToCount(lay)
        }
        if (moving) postInvalidateOnAnimation() else shownAt = 0L
    }
}

/** #toast: message, optional Cancel and progress bar. Lumen snackbar (Part 8): surfaceHi pill with an edge hairline, ink text, primary action, same as the page. */
class NlToastView(c: Context, d: Float) : NlHitView(c, d) {
    override fun labelOf(id: String): String? = if (id == "tc") "Cancel" else null
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
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Math.min(LumenRadius.xl(context) * d, v.height / 2f)); o.setAlpha(1f) }
        }
        if (Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = (lumen().shadowTint and 0x00FFFFFF) or 0x80000000.toInt(); outlineAmbientShadowColor = 0x33000000 }   // N4
    }

    fun setData(tx: String, pct: Float, cancel: Boolean, w: Float, h: Float) {
        this.tx = tx; this.pct = pct; this.cancel = cancel; bw = w; bh = h
        hits.clear()
        val cw = if (cancel) cxP.measureText("Cancel") + 24f * d else 0f
        val cTop = if (pct < 0f) (bh - 31.6f * d) / 2f else 10f * d
        cancelR.set(bw - 16f * d + 6f * d - cw, cTop, bw - 16f * d + 6f * d, cTop + 31.6f * d)
        if (cancel) hits.add(Hit("tc", cancelR))
        val avail = Math.max(1, (bw - 32f * d - (if (cancel) cw + 6f * d else 0f)).toInt())
        val fm = txP.fontMetrics
        val fh = fm.descent - fm.ascent
        val lh = 19.6f * d                                   // CSS line-height 1.4 x 14
        lay = StaticLayout.Builder.obtain(tx, 0, tx.length, txP, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(Math.max(0f, lh - fh), 1f).build()
        layY = if (pct < 0f) Math.max(0f, (bh - (lay?.height ?: 0)) / 2f) else 12f * d + (lh - fh) / 2f
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        if (pal == null || bw <= 0f) return
        val lm = lumen()
        val rad = Math.min(LumenRadius.xl(context) * d, bh / 2f)   // N6: r-xl of the theme
        fillP.shader = null; fillP.style = Paint.Style.FILL; fillP.color = lm.surfaceHi
        if (LumenGlass.chromeGradient(context)) fillP.shader = LumenGrad.surface(lm, 0f, bh)   // gSurface (flat at F3)
        rf.set(0f, 0f, bw, bh); cv.drawRoundRect(rf, rad, rad, fillP)
        fillP.shader = null
        val sw = Math.max(1f, d)
        fillP.style = Paint.Style.STROKE; fillP.strokeWidth = sw; fillP.color = lm.edge
        rf.set(sw / 2f, sw / 2f, bw - sw / 2f, bh - sw / 2f); cv.drawRoundRect(rf, rad, rad, fillP)
        fillP.style = Paint.Style.FILL
        txP.color = lm.ink
        lay?.let { cv.save(); cv.translate(16f * d, layY); it.draw(cv); cv.restore() }
        if (cancel) {
            if (pressed == "tc") { fillP.color = (lm.primary and 0x00FFFFFF) or 0x24000000; cv.drawRoundRect(cancelR, 12f * d, 12f * d, fillP) }
            cxP.color = lm.primary
            cv.drawText("Cancel", cancelR.left + 12f * d, ty(cancelR.centerY(), cxP), cxP)
        }
        if (pct >= 0f) {
            val top = bh - 14f * d - 4f * d; val l = 16f * d; val r = bw - 16f * d
            fillP.color = lm.edge; rf.set(l, top, r, top + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
            val w = (r - l) * Math.min(100f, pct) / 100f
            if (w > 0f) {
                // gPrimary progress fill; a finished transfer (100 %) turns gSuccess
                fillP.color = if (pct >= 100f) lm.success else lm.primary
                if (LumenGlass.chromeGradient(context)) fillP.shader = LumenGrad.diag(if (pct >= 100f) LumenGrad.success(lm) else LumenGrad.primary(lm), l, top, l + w, top + 4f * d)
                rf.set(l, top, l + w, top + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP); fillP.shader = null
            }
        }
    }
}

/** #dlw: one card per download (name, Cancel, progress bar, info line). Sits on the container's rect; each card's y/height come from the page. */
class NlDlView(c: Context, d: Float) : NlHitView(c, d) {
    override fun labelOf(id: String): String? = if (id.startsWith("dl:")) "Cancel" else null
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
        val lm = lumen()   // Lumen slots: card = surfaceHi + edge, text = ink, accent = primary (was fixed dark colours)
        for (k in cards) {
            val y = k.y * d; val h = k.h * d
            fillP.shader = null; fillP.style = Paint.Style.FILL; fillP.color = lm.surfaceHi
            rf.set(0f, y, bw, y + h); cv.drawRoundRect(rf, 16f * d, 16f * d, fillP)
            fillP.color = lm.edge; fillP.style = Paint.Style.STROKE; fillP.strokeWidth = d
            rf.set(0.5f * d, y + 0.5f * d, bw - 0.5f * d, y + h - 0.5f * d); cv.drawRoundRect(rf, 16f * d, 16f * d, fillP)
            fillP.style = Paint.Style.FILL
            val rowC = y + 12f * d + 13.1f * d
            val br = btnR[k.key]
            nmP.color = lm.ink
            val right = (br?.left ?: (bw - 14f * d)) - (if (br != null) 10f * d else 0f)
            cv.drawText(fit(k.n, nmP, right - 14f * d), 14f * d, ty(rowC, nmP), nmP)
            if (br != null) {
                if (pressed == "dl:" + k.key) { fillP.color = (lm.ink and 0x00FFFFFF) or 0x22000000; cv.drawRoundRect(br, 12f * d, 12f * d, fillP) }
                cxP.color = lm.primary
                cv.drawText("Cancel", br.left + 8f * d, ty(br.centerY(), cxP), cxP)
            }
            val bt = y + 12f * d + 26.2f * d + 8f * d
            val l = 14f * d; val r = bw - 14f * d
            fillP.color = (lm.primary and 0x00FFFFFF) or 0x33000000; rf.set(l, bt, r, bt + 4f * d); cv.drawRoundRect(rf, 2f * d, 2f * d, fillP)
            fillP.color = lm.primary
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
            infoP.color = lm.inkMut
            cv.drawText(fit(k.info, infoP, r - l), l, ty(bt + 4f * d + 6f * d + 8.4f * d, infoP), infoP)
        }
        if (anim) postInvalidateOnAnimation()
    }
}

/**
 * Owner of the five views. [layer] goes into MainActivity's root above the page and the native list; it has no background and no click handling,
 * so every touch outside a button reaches the views below. [fire] runs nlCh(event, id) in the page.
 */
class NlChrome(private val act: Activity, private val fire: (String, String) -> Unit) {
    companion object {
        /** Kill switch: false = the page's HTML bars / buttons / snackbar only. */
        const val ENABLED = true
        /** Lumen N7: NativeList tells the top bar whether the list is scrolled (true) or at its top (false = transparent small bar). */
        @JvmField var onElev: ((Boolean) -> Unit)? = null
    }

    private val d = UiScale.dens(act)
    val layer = FrameLayout(act)
    private val bar = NlBarView(act, d)
    private val dock = NlDockView(act, d)
    private val fab = NlFabView(act, d)
    private val dls = NlDlView(act, d)
    private val toast = NlToastView(act, d)
    private val all: List<NlHitView> = listOf(bar, dock, fab, dls, toast)
    private val icons = HashMap<String, Path?>()
    private var last = ""
    private val elevCb: (Boolean) -> Unit = { e -> bar.setElevated(e) }

    init {
        onElev = elevCb
        NlBackdrop.register(bar); NlBackdrop.register(dock)
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

    /** App returned from the background: the page's next push must not be skipped as "unchanged". */
    fun resync() { last = "" }

    /** Activity destroyed: drop the static scroll callback so this chrome (and its views) is not kept alive; a newer chrome's callback is left alone. */
    fun release() { if (onElev === elevCb) onElev = null }

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
        layer.post { NlBackdrop.invalidateAll() }   // 4f: the page under the chrome changed
        val j = JSONObject(json)
        j.optJSONObject("p")?.let { pj -> val cp = NlCp(pj); for (v in all) v.pal = cp }
        j.optJSONObject("ic")?.let { ic ->
            val ks = ic.keys()
            while (ks.hasNext()) { val k = ks.next(); if (!icons.containsKey(k)) { val raw = ic.getString(k); if (raw.startsWith("~")) { ChStroke.keys.add(k); icons[k] = NlGlyph.parse(raw.substring(1)) } else icons[k] = NlGlyph.parse(raw) } }
        }

        val b = j.optJSONObject("bar")
        if (place(bar, b?.optJSONArray("r")) && b != null) {
            bar.perm = b.optInt("pm") == 1
            bar.setData(b.optInt("sel") == 1, b.optString("t"), b.optString("nm"), b.optInt("du") == 1, b.optString("c"),
                bar.layoutParams.width.toFloat(), bar.layoutParams.height.toFloat())
        }

        val f = j.optJSONObject("fab")
        if (place(fab, f?.optJSONArray("r")) && f != null) {
            // the label is painted natively with UiScale.tf, the page measured it unscaled: grow the pill to the left (right edge stays) so the text is never ellipsized
            val flp = fab.layoutParams as FrameLayout.LayoutParams
            val nw = Math.ceil(fab.needWidth(f.optString("l")).toDouble()).toInt()
            if (nw > flp.width) { flp.leftMargin = Math.max(0, flp.leftMargin - (nw - flp.width)); flp.width = nw; fab.layoutParams = flp }
            fab.setData(f.optString("l"), f.optString("k", "newfolder"), fab.layoutParams.width.toFloat(), fab.layoutParams.height.toFloat())
        }

        val k = j.optJSONObject("dock")
        val dockWas = dock.visibility == View.VISIBLE && !dock.isLeaving
        val dockRect = k?.optJSONArray("r")
        if (dockRect == null || dockRect.length() < 4) {
            if (dock.visibility == View.VISIBLE) dock.leave()   // Lumen M2: the dock slides away, it does not vanish
        } else if (dock.isLeaving) dock.cancelLeave()
        if ((dockRect != null && dockRect.length() >= 4 || !dock.isLeaving) && place(dock, dockRect, Math.round(dock.inset)) && k != null) {
            if (!dockWas) dock.enter()
            val a = k.optJSONArray("a"); val acts = ArrayList<NlDockView.Act>()
            if (a != null) for (i in 0 until a.length()) { val o = a.getJSONObject(i); acts.add(NlDockView.Act(o.optString("k"), o.optString("l"), o.optString("c"))) }
            val cl = k.optJSONObject("cl")
            dock.setData(acts, cl?.optString("t"), cl != null && cl.optInt("p") == 1, dock.layoutParams.width.toFloat(), dock.layoutParams.height.toFloat(),
                k.optLong("bg").toInt(), k.optDouble("rx", 0.0).toFloat())
        }

        // N12: Lm.snack (other native messages) sits above the dock while it is on screen: distance from the bottom of the content to the card's top edge
        try { Lm.setLift(act, if (dock.visibility == View.VISIBLE && k != null) Math.max(0, layer.height - px(k.getJSONArray("r").getDouble(1))) else 0) } catch (_: Throwable) { }

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

/** Keys of page icons that arrive with the "~" prefix = Lumen stroke icons (ui.html nlIc). */
private object ChStroke { val keys = HashSet<String>() }
