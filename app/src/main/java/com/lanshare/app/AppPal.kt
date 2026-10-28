package com.lanshare.app

import android.content.Context
import android.app.AlertDialog
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
    fun pal(c: Context): P = load(c) ?: if (night(c)) P(0xFF1E1E1E.toInt(), 0xFF2B2B2B.toInt(), 0xFFF2F2F2.toInt(), 0xFFB0B0B0.toInt(), 0xFF444444.toInt(), 0xFFFF8A3D.toInt(), Color.WHITE)
        else P(Color.WHITE, 0xFFF3F3F3.toInt(), 0xFF161616.toInt(), 0xFF5F5F5F.toInt(), 0xFFDDDDDD.toInt(), 0xFFC25100.toInt(), Color.WHITE)

    /** AlertDialog.Builder that follows the app theme: palette window colour, 28 dp corners, palette text / button colours. */
    fun dlg(c: Context): AlertDialog.Builder = Dlg(c)

    private class Dlg(val c: Context) : AlertDialog.Builder(c, if (night(c)) R.style.Theme_LANShare_Dialog_Dark else R.style.Theme_LANShare_Dialog) {
        override fun create(): AlertDialog {
            val d = super.create()
            val p = pal(c)
            val r = 28f * c.resources.displayMetrics.density
            val sf = surf(c)
            d.window?.setBackgroundDrawable(if (sf != null) NlTex.card(sf[0], sf[1], r, p.bd, Math.max(1, Math.round(c.resources.displayMetrics.density)))
                else android.graphics.drawable.GradientDrawable().apply { setColor(p.bg); cornerRadius = r })
            d.setOnShowListener {
                try {
                    for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) d.getButton(b)?.setTextColor(p.ac)
                    d.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(p.fg)
                    val t = c.resources.getIdentifier("alertTitle", "id", "android")
                    if (t != 0) d.findViewById<android.widget.TextView>(t)?.setTextColor(p.fg)
                } catch (_: Throwable) { }
            }
            return d
        }
    }
}
