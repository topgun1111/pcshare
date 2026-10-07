package com.lanshare.app

import android.content.Context
import android.content.res.Configuration

/**
 * User display scale (Settings -> Interface size / Text size), percent, 80..160, default 100.
 * Interface size = the activity's densityDpi is scaled: every dp (WebView CSS px, native painted rows, buttons, icons, spacing) grows or shrinks together.
 * Text size = fontScale (sp text in the native screens) + WebView textZoom. Applied in attachBaseContext of every activity; a change recreates the activity.
 */
object UiScale {
    private const val P = "ls_ui"
    const val MIN = 80
    const val MAX = 160
    fun iface(c: Context): Int = c.getSharedPreferences(P, Context.MODE_PRIVATE).getInt("iface", 100).coerceIn(MIN, MAX)
    fun text(c: Context): Int = c.getSharedPreferences(P, Context.MODE_PRIVATE).getInt("text", 100).coerceIn(MIN, MAX)
    fun save(c: Context, iface: Int, text: Int) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putInt("iface", iface.coerceIn(MIN, MAX)).putInt("text", text.coerceIn(MIN, MAX)).apply()
    }

    /** Density multiplier for natively painted views of MainActivity (its WebView ignores the density override, so it is scaled by a viewport instead). */
    fun dens(act: android.app.Activity): Float = act.resources.displayMetrics.density * iface(act) / 100f

    fun wrap(base: Context, density: Boolean = true): Context {
        val i = if (density) iface(base) else 100; val t = text(base)
        if (i == 100 && t == 100) return base
        val cfg = Configuration(base.resources.configuration)
        if (i != 100) cfg.densityDpi = Math.max(60, Math.round(cfg.densityDpi * i / 100f))
        if (t != 100) cfg.fontScale = t / 100f
        return base.createConfigurationContext(cfg)
    }
}
