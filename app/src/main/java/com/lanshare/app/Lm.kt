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

    private fun dismissNow() {
        hideJob?.let { main.removeCallbacks(it) }
        hideJob = null
        val v = cur
        val h = curHost
        cur = null; curHost = null; curMsg = ""
        if (v != null && h != null) { try { h.removeView(v) } catch (_: Throwable) { } }
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

        // identical message again: keep the pill, only restart the timer
        if (cur != null && curHost === host && curMsg == text && !hasAction) {
            hideJob?.let { main.removeCallbacks(it) }
            val again = Runnable { dismissNow() }
            hideJob = again
            main.postDelayed(again, ms)
            return
        }
        dismissNow()

        val lm = Lumen.of(act)
        val pill = LinearLayout(act)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(act, 16f), dp(act, 12f), dp(act, 16f), dp(act, 12f))
        val bg = shape(lm.surfaceHi, dp(act, 24f).toFloat())
        bg.setStroke(Math.max(1, dp(act, 1f)), lm.edge)
        pill.background = bg
        pill.elevation = dp(act, 6f).toFloat()
        pill.isClickable = true
        pill.setOnClickListener { dismissNow() }

        var used = dp(act, 32f)
        if (kind == "ok" || kind == "warn" || kind == "err") {
            val col = if (kind == "ok") lm.success else if (kind == "warn") lm.warning else lm.error
            val gv = GlyphView(act, kind ?: "ok", col)
            val lp = LinearLayout.LayoutParams(dp(act, 20f), dp(act, 20f))
            lp.rightMargin = dp(act, 8f)
            pill.addView(gv, lp)
            used += dp(act, 28f)
        }

        val tv = TextView(act)
        tv.text = text
        tv.setTextColor(lm.ink)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.maxLines = 2
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        val hostW = if (host.width > 0) host.width else act.resources.displayMetrics.widthPixels
        val avail = Math.min(dp(act, 560f), hostW - dp(act, 32f))
        if (hasAction) used += dp(act, 96f)
        tv.maxWidth = Math.max(dp(act, 120f), avail - used)
        pill.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (hasAction) {
            val ta = TextView(act)
            ta.text = actionLabel
            ta.setTextColor(lm.primary)
            ta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            ta.typeface = android.graphics.Typeface.DEFAULT_BOLD
            ta.gravity = Gravity.CENTER
            ta.minimumHeight = dp(act, 48f)
            ta.setPadding(dp(act, 12f), 0, dp(act, 12f), 0)
            ta.setOnClickListener { dismissNow(); try { action?.invoke() } catch (_: Throwable) { } }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(act, 12f)
            lp.topMargin = -dp(act, 12f)
            lp.bottomMargin = -dp(act, 12f)
            pill.addView(ta, lp)
        }

        val flp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        flp.bottomMargin = dp(act, 12f)
        flp.leftMargin = dp(act, 16f)
        flp.rightMargin = dp(act, 16f)
        host.addView(pill, flp)
        pill.bringToFront()
        cur = pill; curHost = host; curMsg = text

        if (reduceMotion(act)) {   // spec 13.4: no transform, 120 ms fade
            pill.alpha = 0f
            pill.animate().alpha(1f).setDuration(LumenMotion.REDUCE_FADE).start()
        } else {   // spec 13.3 Snackbar: slides up 16 dp + fade (t.base, decelerate)
            pill.alpha = 0f
            pill.translationY = dp(act, 16f).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(LumenMotion.BASE).setInterpolator(LumenMotion.DECEL).start()
        }
        val job = Runnable { dismissNow() }
        hideJob = job
        main.postDelayed(job, ms)
    }

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
        b.textSize = 14f
        b.setPadding(dp(c, padH), 0, dp(c, padH), 0)
        val rad = dp(c, 999f).toFloat()
        if (fill != 0) {
            // Lumen 5.2 gPrimary: 135 degrees, light start (6 % white) to darker end (12 % black); only the primary fill, tonal / danger stay flat
            val face: android.graphics.drawable.Drawable = if (fill == Lumen.of(c).primary) {
                val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(mixInk(fill, 0xFFFFFFFF.toInt(), 0.06f), mixInk(fill, 0xFF000000.toInt(), 0.12f)))
                g.cornerRadius = rad; g
            } else shape(fill, rad)
            b.background = RippleDrawable(ColorStateList.valueOf(withAlpha(text, 0.14f)), face, null)
        } else {
            b.background = RippleDrawable(ColorStateList.valueOf(withAlpha(text, 0.12f)), null, shape(0xFFFFFFFF.toInt(), rad))
        }
    }

    /** Called by AppPal.dlg from the dialog's OnShowListener: everything is optional, any failure leaves the stock dialog as it was. */
    fun restyle(d: AlertDialog, c: Context) {
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

        val danger = pos != null && pos.visibility == View.VISIBLE && isDanger(pos.text, title)
        try {
            if (pos != null && pos.visibility == View.VISIBLE) {
                if (danger) styleButton(pos, lm.errorCont, lm.onErrorCont, c) else styleButton(pos, lm.primary, lm.onPrimary, c)
            }
            if (neu != null && neu.visibility == View.VISIBLE) styleButton(neu, 0, lm.primary, c)
            if (neg != null && neg.visibility == View.VISIBLE) styleButton(neg, 0, if (danger) lm.ink else lm.primary, c)
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
            if (danger && title != null && title.isNotEmpty()) {
                val top = d.findViewById<View>(c.resources.getIdentifier("topPanel", "id", "android")) as? LinearLayout
                val tid = c.resources.getIdentifier("alertTitle", "id", "android")
                if (top != null && top.visibility == View.VISIBLE) {
                    val hero = HeroView(c, lm.errorCont, lm.onErrorCont)
                    val lp = LinearLayout.LayoutParams(dp(c, 56f), dp(c, 56f))
                    lp.gravity = Gravity.CENTER_HORIZONTAL
                    lp.topMargin = dp(c, 16f)
                    lp.bottomMargin = dp(c, 4f)
                    top.addView(hero, 0, lp)
                    if (tid != 0) d.findViewById<TextView>(tid)?.gravity = Gravity.CENTER_HORIZONTAL
                }
            }
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
