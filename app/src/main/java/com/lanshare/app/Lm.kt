package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lumen Part 8: the ONE native snackbar and the ONE native dialog finish (spec 12.12 / 12.14).
 *  - Lm.snack(...) replaces every Toast.makeText: pill on the activity's content view, glyph for ok / warn / err, optional Text action,
 *    one at a time, identical consecutive messages only restart the timer. Without an Activity (receiver, service) it falls back to the system toast.
 *  - Lm.restyle(...) is called by AppPal.dlg for every AlertDialog: filled primary / tonal danger buttons, stacked on phones, hero tile for
 *    destructive dialogs, scrim 32 / 56 %, scale-in 220 ms.
 */
object Lm {
    private val main = Handler(Looper.getMainLooper())
    private var cur: View? = null
    private var curHost: ViewGroup? = null
    private var curMsg = ""
    private var hideJob: Runnable? = null
    private val overlays = ArrayList<java.lang.ref.WeakReference<android.app.Dialog>>()

    /** Full-screen Dialogs (PDF page overview ...) cover the activity: register them so the snackbar is drawn inside the one on top. */
    fun track(d: android.app.Dialog) {
        overlays.add(java.lang.ref.WeakReference(d))
        while (overlays.size > 8) overlays.removeAt(0)
    }

    // ------------------------------------------------------------------ helpers

    private fun actOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
        return null
    }

    private fun dp(c: Context, v: Float): Int = Math.round(v * c.resources.displayMetrics.density)

    private fun reduceMotion(c: Context): Boolean = LumenPrefs.reduceMotion(c)   // in-app Reduce motion OR system "remove animations" (Part 16)

    private fun withAlpha(col: Int, a: Float): Int {
        val al = Math.round(255f * a)
        return (al shl 24) or (col and 0x00FFFFFF)
    }

    private fun shape(fill: Int, radiusPx: Float): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = radiusPx
        return g
    }

    // ------------------------------------------------------------------ snackbar

    /** Message kind from its words when the caller gave none: only failures get a glyph, everything else stays neutral. */
    private fun guess(t: String): String? {
        val s = t.lowercase()
        val bad = arrayOf("cannot", "can't", "could not", "couldn't", "failed", "error", "no app can", "başarısız", "başlatılamadı", "yapılamadı", "bad ")
        for (w in bad) if (s.contains(w)) return "err"
        if (s.startsWith("copied") || s.startsWith("saved") || s.endsWith("saved") || s.startsWith("deleted")) return "ok"
        return null
    }

    fun snack(c: Context, msg: CharSequence?, len: Int) { snack(c, msg, len, null, null, null) }

    fun snack(c: Context, resId: Int, len: Int) { snack(c, c.getString(resId), len, null, null, null) }

    /** kind = "ok" | "warn" | "err" | null (guessed). action = Text action at the right ("Undo", "Open", "Details"). */
    fun snack(c: Context, msg: CharSequence?, len: Int, kind: String?, actionLabel: String?, action: (() -> Unit)?) {
        val text = msg?.toString() ?: return
        if (text.isEmpty()) return
        main.post {
            try { show(c, text, len, kind ?: guess(text), actionLabel, action) }
            catch (_: Throwable) { fallback(c, text, len) }
        }
    }

    private fun fallback(c: Context, text: String, len: Int) {
        try { Toast.makeText(c.applicationContext, text, if (len == Toast.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
    }

    private fun dismissNow(animate: Boolean = false) {
        hideJob?.let { main.removeCallbacks(it) }
        hideJob = null
        val v = cur
        val h = curHost
        curBarAnim?.cancel(); curBarAnim = null
        cur = null; curHost = null; curMsg = ""; curTv = null; curHasAction = false
        if (v == null || h == null) return
        if (!animate || v.parent == null) { try { h.removeView(v) } catch (_: Throwable) { }; return }
        // M1 (native): exit = t.exit (0.7 x base = 140 ms), accelerate, slide down 16 dp + fade; Reduce motion = 120 ms fade
        v.isClickable = false
        val end = Runnable { try { h.removeView(v) } catch (_: Throwable) { } }
        if (reduceMotion(v.context)) v.animate().alpha(0f).setDuration(LumenMotion.REDUCE_FADE).withEndAction(end).start()
        else v.animate().alpha(0f).translationY(dp(v.context, 16f).toFloat()).setDuration(LumenMotion.exit(LumenMotion.BASE)).setInterpolator(LumenMotion.ACCEL).withEndAction(end).start()
    }

    // M5 (native): "3 files" then "4 files" = same pill, only the number changes (text after the number identical)
    private val MERGE = Regex("^(\\d+)\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)
    private fun mergeable(a: String, b: String): Boolean {
        val x = MERGE.matchEntire(a); val y = MERGE.matchEntire(b)
        return x != null && y != null && x.groupValues[2] == y.groupValues[2] && x.groupValues[1] != y.groupValues[1]
    }

    // N12 (native): the main screen reports how high its bottom dock is, so a snackbar sits above it instead of covering it
    private var liftAct: java.lang.ref.WeakReference<Activity>? = null
    private var liftPx = 0
    private var curTv: TextView? = null
    private var curHasAction = false
    private var curBarAnim: android.animation.ObjectAnimator? = null

    private fun liftFor(a: Activity, host: ViewGroup): Int =
        if (liftAct?.get() === a && host === a.findViewById<ViewGroup>(android.R.id.content)) liftPx else 0

    /** [px] = distance from the bottom of the activity's content to the top of the dock (0 = no dock). Called by NlChrome. */
    fun setLift(a: Activity, px: Int) {
        val l = Math.max(0, px)
        if (liftAct?.get() === a && liftPx == l) return
        liftAct = java.lang.ref.WeakReference(a); liftPx = l
        main.post {
            val v = cur ?: return@post; val h = curHost ?: return@post
            val act = actOf(v.context) ?: return@post
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@post
            val nb = dp(act, 12f) + liftFor(act, h)
            if (lp.bottomMargin != nb) { lp.bottomMargin = nb; v.layoutParams = lp }
        }
    }

    private fun show(c: Context, text: String, len: Int, kind: String?, actionLabel: String?, action: (() -> Unit)?) {
        val act = actOf(c)
        if (act == null || act.isFinishing || act.isDestroyed) { fallback(c, text, len); return }
        var host: ViewGroup? = null
        for (i in overlays.indices.reversed()) {
            val od = overlays[i].get() ?: continue
            if (od.isShowing && actOf(od.context) === act) { host = od.window?.decorView?.findViewById<ViewGroup>(android.R.id.content); if (host != null) break }
        }
        if (host == null) host = act.findViewById<ViewGroup>(android.R.id.content)
        if (host == null) { fallback(c, text, len); return }

        val hasAction = actionLabel != null && action != null
        val ms = if (kind == "err") 8000L else if (hasAction || len == Toast.LENGTH_LONG) 5000L else 3000L

        if (cur != null && curHost === host && !hasAction && !curHasAction) {
            val same = curMsg == text
            if (same || mergeable(curMsg, text)) {
                // identical message: keep the pill, restart the timer; changed number: same pill, new number, a small pulse and a polite re-announce
                hideJob?.let { main.removeCallbacks(it) }
                val again = Runnable { dismissNow(true) }
                hideJob = again
                main.postDelayed(again, ms)
                if (!same) {
                    curTv?.text = text; curMsg = text
                    cur?.contentDescription = prefix(kind) + text
                    if (!reduceMotion(act)) cur?.let { v -> v.scaleX = 1.04f; v.scaleY = 1.04f; v.animate().scaleX(1f).scaleY(1f).setDuration(LumenMotion.FAST).setInterpolator(LumenMotion.DECEL).start() }
                    cur?.announceForAccessibility(text)
                }
                return
            }
        }
        dismissNow()

        val lm = Lumen.of(act)
        val pill = FrameLayout(act)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(act, 16f), dp(act, 12f), dp(act, 16f), dp(act, 12f))
        pill.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val bg = shape(lm.surfaceHi, dp(act, 24f).toFloat())
        bg.setStroke(Math.max(1, dp(act, 1f)), lm.edge)
        pill.background = bg
        pill.elevation = dp(act, 6f).toFloat()
        pill.clipToOutline = true   // the Undo countdown bar is clipped to the pill
        pill.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, Math.min(dp(act, 24f).toFloat(), v.height / 2f)) }
        }
        pill.isClickable = true
        pill.setOnClickListener { dismissNow(true) }
        // A1: the snackbar is a polite live region ("Error: ..." / "Warning: ..." prefix), the countdown bar and glyph are decoration
        pill.contentDescription = prefix(kind) + text
        pill.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        pill.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        var used = dp(act, 32f)
        if (kind == "ok" || kind == "warn" || kind == "err") {
            val col = if (kind == "ok") lm.success else if (kind == "warn") lm.warning else lm.error
            val gv = GlyphView(act, kind ?: "ok", col)
            gv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val lp = LinearLayout.LayoutParams(dp(act, 20f), dp(act, 20f))
            lp.rightMargin = dp(act, 8f)
            row.addView(gv, lp)
            used += dp(act, 28f)
        }

        val tv = TextView(act)
        tv.text = text
        tv.setTextColor(lm.ink)
        LumenTypography.apply(tv, LumenTypography.BODY_M)   // spec 7 Body M
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        tv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // the pill carries the text
        val hostW = if (host.width > 0) host.width else act.resources.displayMetrics.widthPixels
        val avail = Math.min(dp(act, 560f), hostW - dp(act, 32f))
        if (hasAction) used += dp(act, 96f)
        tv.maxWidth = Math.max(dp(act, 120f), avail - used)
        row.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (hasAction) {
            val ta = TextView(act)
            ta.text = actionLabel
            ta.setTextColor(lm.primary)
            LumenTypography.apply(ta, LumenTypography.LABEL_L)   // spec 7 Label L (semi-bold)
            ta.gravity = Gravity.CENTER
            ta.minimumHeight = dp(act, 48f)
            ta.setPadding(dp(act, 12f), 0, dp(act, 12f), 0)
            ta.setOnClickListener { dismissNow(true); try { action?.invoke() } catch (_: Throwable) { } }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(act, 12f)
            lp.topMargin = -dp(act, 12f)
            lp.bottomMargin = -dp(act, 12f)
            row.addView(ta, lp)
            androidx.core.view.ViewCompat.addAccessibilityAction(pill, actionLabel!!) { _, _ -> ta.performClick(); true }
        }

        // M5: Undo snackbars show a 3 dp countdown bar along the bottom edge that shrinks over the visible time
        if (hasAction && Regex("undo", RegexOption.IGNORE_CASE).containsMatchIn(actionLabel ?: "")) {
            val bar = View(act)
            bar.setBackgroundColor(withAlpha(lm.primary, 0.6f))
            bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            bar.pivotX = 0f
            pill.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 3f), Gravity.BOTTOM or Gravity.START))
            bar.post { bar.pivotX = 0f }
            val an = android.animation.ObjectAnimator.ofFloat(bar, View.SCALE_X, 1f, 0f)
            an.duration = ms; an.interpolator = android.view.animation.LinearInterpolator()
            an.start()
            curBarAnim = an
        }

        val flp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        flp.bottomMargin = dp(act, 12f) + liftFor(act, host)
        flp.leftMargin = dp(act, 16f)
        flp.rightMargin = dp(act, 16f)
        host.addView(pill, flp)
        pill.bringToFront()
        cur = pill; curHost = host; curMsg = text; curTv = tv; curHasAction = hasAction

        if (reduceMotion(act)) {   // spec 13.4: no transform, 120 ms fade
            pill.alpha = 0f
            pill.animate().alpha(1f).setDuration(LumenMotion.REDUCE_FADE).start()
        } else {   // spec 13.3 Snackbar: slides up 16 dp + fade (t.base, decelerate)
            pill.alpha = 0f
            pill.translationY = dp(act, 16f).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(LumenMotion.BASE).setInterpolator(LumenMotion.DECEL).start()
        }
        val job = Runnable { dismissNow(true) }
        hideJob = job
        main.postDelayed(job, ms)
    }

    private fun prefix(kind: String?): String = if (kind == "err") "Error: " else if (kind == "warn") "Warning: " else ""

    /** 20 dp semantic glyph: filled disc in the semantic colour with a check (ok), an exclamation mark (warn) or a cross (err). */
    private class GlyphView(c: Context, private val kind: String, private val col: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            p.style = Paint.Style.FILL; p.color = col
            cv.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p)
            p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt()
            p.strokeWidth = w * 0.11f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            path.reset()
            if (kind == "ok") {
                path.moveTo(w * 0.28f, h * 0.52f); path.lineTo(w * 0.44f, h * 0.67f); path.lineTo(w * 0.73f, h * 0.35f)
                cv.drawPath(path, p)
            } else if (kind == "err") {
                path.moveTo(w * 0.33f, h * 0.33f); path.lineTo(w * 0.67f, h * 0.67f)
                path.moveTo(w * 0.67f, h * 0.33f); path.lineTo(w * 0.33f, h * 0.67f)
                cv.drawPath(path, p)
            } else {
                path.moveTo(w * 0.5f, h * 0.27f); path.lineTo(w * 0.5f, h * 0.55f)
                cv.drawPath(path, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(w * 0.5f, h * 0.72f, w * 0.07f, p)
            }
        }
    }

    // ------------------------------------------------------------------ dialog finish

    private val DANGER = setOf("delete", "remove", "clear", "discard", "erase", "empty", "sil", "kaldır", "temizle", "reset")

    private fun isDanger(label: CharSequence?, title: CharSequence?): Boolean {
        val l = label?.toString()?.lowercase()?.trim() ?: ""
        for (w in l.split(' ', '\u00a0')) if (w in DANGER) return true
        val t = title?.toString()?.lowercase()?.trim() ?: ""
        if ((t.startsWith("delete") || t.startsWith("clear") || t.startsWith("remove") || t.startsWith("discard")) && (l == "ok" || l == "yes" || l == "evet")) return true
        return false
    }

    /** Acknowledgement labels are quiet only in notice dialogs; the same label can submit a form elsewhere. */
    private fun isDismissAction(label: CharSequence?, kind: String?): Boolean {
        val l = label?.toString()?.trim()?.lowercase() ?: return false
        if (l in setOf("close", "dismiss", "got it", "kapat", "fermer", "cancel", "iptal", "vazgeç", "back", "go back", "not now")) return true
        val notice = kind in setOf("info", "warn", "perm", "ok")
        return notice && l in setOf("ok", "okay", "tamam", "done", "understood")
    }

    /** Dialog extras (Lumen A8): semantic hero kind and optional read-only detail rows. */
    class Opts(val kind: String?, val details: List<Pair<String, String>>?)

    /** Hero tile for info / warn / perm / ok: filled disc in the kind's slot colour, Lumen glyph (info / warn / lock / check) in its on-colour. */
    private class KindHero(c: Context, private val bg: Int, private val fg: Int, private val glyph: String) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            p.style = Paint.Style.FILL; p.color = bg
            cv.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p)
            val s = Math.min(w, h) * 0.5f
            NlIcons.draw(cv, p, glyph, (w - s) / 2f, (h - s) / 2f, s, fg)
        }
    }

    /** Optional read-only details block ("label  value" rows on a quiet tile), added above the button row. */
    private fun addExtras(d: AlertDialog, c: Context, lm: Lumen, o: Opts) {
        val rows = o.details
        val hasRows = rows != null && rows.isNotEmpty()
        if (!hasRows) return
        val btnPanel = d.findViewById<View>(c.resources.getIdentifier("buttonPanel", "id", "android"))
        val host = (btnPanel?.parent ?: d.findViewById<View>(c.resources.getIdentifier("contentPanel", "id", "android"))?.parent) as? LinearLayout ?: return
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(c, 24f), dp(c, 8f), dp(c, 24f), dp(c, 8f))
        if (hasRows) {
            val tile = LinearLayout(c)
            tile.orientation = LinearLayout.VERTICAL
            tile.background = shape(withAlpha(lm.ink, 0.06f), dp(c, 16f).toFloat())
            tile.setPadding(dp(c, 16f), dp(c, 12f), dp(c, 16f), dp(c, 12f))
            for ((i, r) in rows!!.withIndex()) {
                val row = LinearLayout(c)
                row.orientation = LinearLayout.HORIZONTAL
                val l = TextView(c); l.text = r.first; l.setTextColor(lm.inkMut); LumenTypography.apply(l, LumenTypography.BODY_S)
                val v = TextView(c); v.text = r.second; v.setTextColor(lm.ink); LumenTypography.apply(v, LumenTypography.BODY_M)
                v.maxLines = 2; v.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; v.gravity = Gravity.END
                row.addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.38f))
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.62f))
                tile.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { if (i > 0) it.topMargin = dp(c, 8f) })
            }
            box.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val at = if (btnPanel != null) host.indexOfChild(btnPanel) else -1
        if (at >= 0) host.addView(box, at, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        else host.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Trash tile for destructive dialogs: errorCont disc, onErrorCont glyph. */
    private class HeroView(c: Context, private val bg: Int, private val fg: Int) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            p.style = Paint.Style.FILL; p.color = bg
            cv.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p)
            p.style = Paint.Style.STROKE; p.color = fg
            p.strokeWidth = w * 0.055f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            r.set(w * 0.35f, h * 0.38f, w * 0.65f, h * 0.70f)                       // bin body
            cv.drawRoundRect(r, w * 0.04f, w * 0.04f, p)
            cv.drawLine(w * 0.31f, h * 0.38f, w * 0.69f, h * 0.38f, p)              // lid
            cv.drawLine(w * 0.44f, h * 0.38f, w * 0.44f, h * 0.33f, p)              // handle
            cv.drawLine(w * 0.56f, h * 0.38f, w * 0.56f, h * 0.33f, p)
            cv.drawLine(w * 0.44f, h * 0.33f, w * 0.56f, h * 0.33f, p)
            cv.drawLine(w * 0.46f, h * 0.45f, w * 0.46f, h * 0.62f, p)              // slats
            cv.drawLine(w * 0.54f, h * 0.45f, w * 0.54f, h * 0.62f, p)
        }
    }

    private fun mixInk(base: Int, with: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = Math.round(x + (y - x) * t)
        return Color.argb(Color.alpha(base), ch(Color.red(base), Color.red(with)), ch(Color.green(base), Color.green(with)), ch(Color.blue(base), Color.blue(with)))
    }

    private fun opq(x: Int) = x or 0xFF000000.toInt()
    /** Label colour for a filled button: [t] when it reads on [fill] (4.5:1), else black / white, whichever is stronger. */
    private fun onFill(fill: Int, t: Int): Int {
        val f = opq(fill)
        if (androidx.core.graphics.ColorUtils.calculateContrast(opq(t), f) >= 4.5) return t
        return if (androidx.core.graphics.ColorUtils.calculateContrast(0xFF000000.toInt(), f) >= androidx.core.graphics.ColorUtils.calculateContrast(0xFFFFFFFF.toInt(), f)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    /** Text colour on the dialog window: [t] when it reads on [bg] (3:1), else [fb]. */
    private fun onWin(t: Int, bg: Int, fb: Int): Int =
        if (androidx.core.graphics.ColorUtils.calculateContrast(opq(t), opq(bg)) >= 3.0) t else fb

    private fun styleButton(b: android.widget.Button, fill: Int, text: Int, c: Context) {
        b.setAllCaps(false)
        b.setTextColor(text)
        // spec 12.1: filled 48 / 24 padding, text 40 / 16 padding, min width 64, Label L 14 sp
        val h = if (fill != 0) 48f else 40f
        val padH = if (fill != 0) 24f else 16f
        b.minHeight = dp(c, h)
        b.minimumHeight = dp(c, h)
        b.minWidth = dp(c, 64f)
        b.minimumWidth = dp(c, 64f)
        LumenTypography.apply(b, LumenTypography.LABEL_L)   // spec 7 Label L
        b.setPadding(dp(c, padH), 0, dp(c, padH), 0)
        val rad = dp(c, 999f).toFloat()
        if (fill != 0) {
            // Lumen 5.2 gPrimary: 135 degrees, gradFrom -> gradTo slots; only the primary fill, tonal / danger stay flat
            val face: android.graphics.drawable.Drawable = if (fill == Lumen.of(c).primary) {
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, LumenGrad.primary(Lumen.of(c)))
                g.cornerRadius = rad; g
            } else shape(fill, rad)
            b.background = RippleDrawable(ColorStateList.valueOf(withAlpha(text, 0.14f)), face, null)
        } else {
            b.background = RippleDrawable(ColorStateList.valueOf(withAlpha(text, 0.12f)), null, shape(0xFFFFFFFF.toInt(), rad))
        }
    }

    /** Called by AppPal.dlg from the dialog's OnShowListener: everything is optional, any failure leaves the stock dialog as it was. */
    fun restyle(d: AlertDialog, c: Context, o: Opts? = null) {
        val lm = Lumen.of(c)
        val night = AppPal.night(c)
        try { d.window?.setDimAmount(if (night) 0.56f else 0.32f) } catch (_: Throwable) { }

        val pos = d.getButton(AlertDialog.BUTTON_POSITIVE)
        val neg = d.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neu = d.getButton(AlertDialog.BUTTON_NEUTRAL)
        val shown = ArrayList<android.widget.Button>()
        if (pos != null && pos.visibility == View.VISIBLE) shown.add(pos)
        if (neu != null && neu.visibility == View.VISIBLE) shown.add(neu)
        if (neg != null && neg.visibility == View.VISIBLE) shown.add(neg)

        var title: CharSequence? = null
        try {
            val tid = c.resources.getIdentifier("alertTitle", "id", "android")
            if (tid != 0) title = d.findViewById<TextView>(tid)?.text
        } catch (_: Throwable) { }
        try {   // spec 7: dialog title = Title L, message = Body L (colours stay as the dialog theme sets them)
            val tv = d.findViewById<TextView>(c.resources.getIdentifier("alertTitle", "id", "android"))
            if (tv != null) LumenTypography.apply(tv, LumenTypography.TITLE_L)
            val mv = d.findViewById<TextView>(android.R.id.message)
            if (mv != null) LumenTypography.apply(mv, LumenTypography.BODY_L)
        } catch (_: Throwable) { }

        val danger = o?.kind == "danger" || (pos != null && pos.visibility == View.VISIBLE && isDanger(pos.text, title))
        val dismissPrimary = pos != null && pos.visibility == View.VISIBLE && isDismissAction(pos.text, o?.kind) && !danger
        try {
            if (pos != null && pos.visibility == View.VISIBLE) {
                when {
                    danger -> styleButton(pos, lm.errorCont, onFill(lm.errorCont, lm.onErrorCont), c)
                    dismissPrimary -> styleButton(pos, 0, onWin(lm.primary, AppPal.pal(c).bg, AppPal.pal(c).fg), c)
                    else -> styleButton(pos, lm.primary, onFill(lm.primary, lm.onPrimary), c)
                }
            }
            // text buttons sit on the dialog window (page --bg): keep them readable on every theme, fall back to the page ink
            val wbg = AppPal.pal(c).bg
            if (neu != null && neu.visibility == View.VISIBLE) styleButton(neu, 0, onWin(if (isDanger(neu.text, title)) lm.error else lm.primary, wbg, AppPal.pal(c).fg), c)
            if (neg != null && neg.visibility == View.VISIBLE) styleButton(neg, 0, onWin(if (isDanger(neg.text, title)) lm.error else if (danger) lm.ink else lm.primary, wbg, AppPal.pal(c).fg), c)
            if (danger && neg != null && neg.visibility == View.VISIBLE) neg.requestFocus()   // Cancel is the default in danger dialogs
        } catch (_: Throwable) { }

        // phones: stacked, full width, primary on top when there are 3+ actions or a long label
        try {
            val phone = c.resources.configuration.screenWidthDp < 600
            var longLabel = false
            for (b in shown) if (b.text.length > 16) longLabel = true
            if (phone && shown.size >= 2 && (shown.size >= 3 || longLabel)) {
                val row = shown[0].parent as? LinearLayout
                if (row != null) {
                    row.removeAllViews()
                    row.orientation = LinearLayout.VERTICAL
                    for (i in shown.indices) {
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        if (i > 0) lp.topMargin = dp(c, 4f)
                        if (i == 0 && shown[i] === pos) { shown[i].minHeight = dp(c, 56f); shown[i].minimumHeight = dp(c, 56f) }   // phone dialog primary = 56
                        row.addView(shown[i], lp)
                    }
                    row.requestLayout()
                }
            }
        } catch (_: Throwable) { }

        // destructive: hero tile above a centred title
        try {
            val k = o?.kind
            if ((danger || k == "info" || k == "warn" || k == "perm" || k == "ok") && title != null && title.isNotEmpty()) {
                val top = d.findViewById<View>(c.resources.getIdentifier("topPanel", "id", "android")) as? LinearLayout
                val tid = c.resources.getIdentifier("alertTitle", "id", "android")
                if (top != null && top.visibility == View.VISIBLE) {
                    val hero: View = when {
                        danger -> HeroView(c, lm.errorCont, lm.onErrorCont)
                        k == "warn" -> KindHero(c, lm.warning, lm.onWarning, "warn")
                        k == "perm" -> KindHero(c, lm.primary, lm.onPrimary, "lock")
                        k == "ok" -> KindHero(c, lm.success, lm.onSuccess, "check")
                        else -> KindHero(c, lm.info, lm.onInfo, "info")
                    }
                    hero.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    val lp = LinearLayout.LayoutParams(dp(c, 56f), dp(c, 56f))
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                    lp.topMargin = dp(c, 16f)
                    lp.bottomMargin = dp(c, 4f)
                    top.addView(hero, 0, lp)
                    if (tid != 0) d.findViewById<TextView>(tid)?.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
        } catch (_: Throwable) { }

        if (o != null) try { addExtras(d, c, lm, o) } catch (_: Throwable) { }

        // M1 (native): dialog exit 140 ms accelerate (window exit animation; 120 ms fade with Reduce motion). A1: the window is announced with the dialog title.
        try {
            d.window?.setWindowAnimations(if (reduceMotion(c)) R.style.LmDialogExitReduced else R.style.LmDialogExit)
            if (title != null && title.isNotEmpty()) d.window?.decorView?.let { androidx.core.view.ViewCompat.setAccessibilityPaneTitle(it, title) }
        } catch (_: Throwable) { }
        // scale 0.92 -> 1 + fade, t.base decelerate; with Reduce motion only a 120 ms fade (spec 13.3 / 13.4)
        try {
            val dv = d.window?.decorView
            if (dv != null) {
                if (reduceMotion(c)) {
                    dv.alpha = 0f
                    dv.animate().alpha(1f).setDuration(LumenMotion.REDUCE_FADE).start()
                } else {
                    dv.alpha = 0f; dv.scaleX = 0.92f; dv.scaleY = 0.92f
                    dv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(LumenMotion.BASE).setInterpolator(LumenMotion.DECEL).start()
                }
            }
        } catch (_: Throwable) { }
    }
}
