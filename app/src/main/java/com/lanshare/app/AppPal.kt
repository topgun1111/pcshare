package com.lanshare.app

import android.content.Context
import android.content.ComponentCallbacks
import android.content.ContextWrapper
import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.json.JSONObject

/**
 * The page theme's colours (ui.html nlPal(): bg, card, cont, fg, mut, bd, ac, onac ...) as MainActivity receives them through nlPalette().
 * Kept in memory and in SharedPreferences so native screens (Storage Analyzer) can use the app's theme, not only the system day / night mode.
 */
object AppPal {
    class P(val bg: Int, val cont: Int, val fg: Int, val mut: Int, val bd: Int, val ac: Int, val onac: Int)

    private const val PREFS = "ls_theme"
    @Volatile private var cur: String? = null

    /** Called by MainActivity.nlPalette(). */
    fun save(c: Context, json: String) {
        if (json == cur) return
        cur = json
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pal", json).apply()
    }

    /** Raw palette JSON (null = the page never sent one: use the system mode). Also used as a change signature. */
    fun json(c: Context): String? {
        cur?.let { return it }
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pal", null)
        cur = s
        return s
    }

    fun load(c: Context): P? {
        val j = json(c) ?: return null
        return try {
            val o = JSONObject(j)
            fun g(n: String) = o.getLong(n).toInt() or Color.BLACK       // opaque
            P(g("bg"), g("cont"), g("fg"), g("mut"), g("bd"), g("ac"), g("onac"))
        } catch (_: Throwable) { null }
    }

    /** App theme first (page palette luminance), system day / night mode only when the page never sent a palette. */
    fun night(c: Context): Boolean {
        load(c)?.let { return ColorUtils.calculateLuminance(it.bg) < 0.5 }
        return (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Gradient surface colours (top, bottom) of gradient themes such as Ubuntu, null for flat themes. */
    fun surf(c: Context): IntArray? = try {
        val o = JSONObject(json(c) ?: "")
        val t = o.optLong("ct").toInt(); val b = o.optLong("cb").toInt()
        if (t != 0) intArrayOf(t, b) else null
    } catch (_: Throwable) { null }

    /** Palette as ARGB ints, with the built-in light / dark defaults when the page sent none. */
    fun pal(c: Context): P = load(c) ?: Lumen.of(c).let { P(it.canvas, it.surfaceHi, it.ink, it.inkMut, it.edge, it.primary, it.onPrimary) }
        else P(Color.WHITE, 0xFFF3F3F3.toInt(), 0xFF161616.toInt(), 0xFF5F5F5F.toInt(), 0xFFDDDDDD.toInt(), 0xFFC25100.toInt(), Color.WHITE)

    /** AlertDialog.Builder that follows the app theme: palette window colour, 32 dp corners, palette text / button colours. */
    fun dlg(c: Context): AlertDialog.Builder = Dlg(c)

    private class Dlg(val c: Context) : AlertDialog.Builder(c, if (night(c)) R.style.Theme_LANShare_Dialog_Dark else R.style.Theme_LANShare_Dialog) {
        override fun create(): AlertDialog {
            val d = super.create()
            val p = pal(c)
            val r = 32f * c.resources.displayMetrics.density
            val sf = surf(c)
            d.window?.setBackgroundDrawable(if (sf != null) NlTex.card(sf[0], sf[1], r, p.bd, Math.max(1, Math.round(c.resources.displayMetrics.density)))
                else android.graphics.drawable.GradientDrawable().apply { setColor(p.bg); cornerRadius = r })
            val act = actOf(c)
            if (act != null) fit(d, act)   // right width for the CURRENT screen before the first frame (folded / unfolded)
            d.setOnShowListener {
                if (act != null) trackFold(d, act)
                try {
                    for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) d.getButton(b)?.setTextColor(p.ac)
                    d.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(p.fg)
                    val t = c.resources.getIdentifier("alertTitle", "id", "android")
                    if (t != 0) d.findViewById<android.widget.TextView>(t)?.setTextColor(p.fg)
                } catch (_: Throwable) { }
                Lm.restyle(d, c)   // Lumen Part 8: filled / tonal buttons, stacked on phones, hero tile, scrim, scale-in
            }
            return d
        }
    }

    private fun actOf(c: Context): Activity? {
        var x: Context? = c
        while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }
        return null
    }

    /** Size (px) of the window the dialog lives in right now. Foldables: the value changes when the phone is folded / unfolded. */
    private fun winSize(a: Activity): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= 30) a.windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else a.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    } catch (_: Throwable) { a.resources.displayMetrics.let { it.widthPixels to it.heightPixels } }

    /**
     * Sizes the dialog window from the CURRENT screen, the app's uniform rule: 90 % of the width in portrait, 400 dp otherwise (never wider
     * than 90 %). Without this a dialog that was measured in the other fold state stayed too wide on the cover screen (cut off at the right
     * edge) or too narrow on the inner one.
     */
    private fun fit(d: AlertDialog, a: Activity) {
        val w = d.window ?: return
        val (sw, sh) = winSize(a)
        if (sw <= 0) return
        val dp = a.resources.displayMetrics.density
        val target = if (sh > sw) (sw * 0.9f).toInt() else minOf((400 * dp).toInt(), (sw * 0.9f).toInt())
        val lp = w.attributes
        if (lp.width != target) w.setLayout(target, lp.height)
    }

    /** The activity handles fold / unfold itself (configChanges), so a showing dialog is told to re-fit; the fold animation takes a moment, hence the repeats. */
    private fun trackFold(d: AlertDialog, a: Activity) {
        val h = d.window?.decorView ?: return
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(n: Configuration) {
                for (t in longArrayOf(0, 120, 350, 800)) h.postDelayed({ if (d.isShowing) fit(d, a) }, t)
            }
            override fun onLowMemory() {}
        }
        try { a.registerComponentCallbacks(cb) } catch (_: Throwable) { return }
        h.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { try { a.unregisterComponentCallbacks(cb) } catch (_: Throwable) {} }
        })
        fit(d, a)
    }
}
