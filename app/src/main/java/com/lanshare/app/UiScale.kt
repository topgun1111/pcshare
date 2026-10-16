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
    /** Text factor for natively painted text (file list, bars, drawer): those paints use px = dp * density and ignore fontScale, so Settings -> Text size is multiplied in here. */
    @Volatile @JvmField var tf: Float = 1f
    fun save(c: Context, iface: Int, text: Int) {
        c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putInt("iface", iface.coerceIn(MIN, MAX)).putInt("text", text.coerceIn(MIN, MAX)).commit()
        tf = text.coerceIn(MIN, MAX) / 100f
    }
    /** Chosen app font id (see [Fnt.LIST]); "figtree" = the built-in default. */
    fun font(c: Context): String = c.getSharedPreferences(P, Context.MODE_PRIVATE).getString("font", "figtree") ?: "figtree"
    fun saveFont(c: Context, id: String) { c.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("font", id).commit() }

    /** Density multiplier for natively painted views of MainActivity (its WebView ignores the density override, so it is scaled by a viewport instead). */
    fun dens(act: android.app.Activity): Float = act.resources.displayMetrics.density * iface(act) / 100f

    fun wrap(base: Context, density: Boolean = true): Context {
        val i = if (density) iface(base) else 100; val t = text(base)
        tf = t / 100f
        if (i == 100 && t == 100) return base
        val cfg = Configuration(base.resources.configuration)
        if (i != 100) cfg.densityDpi = Math.max(60, Math.round(cfg.densityDpi * i / 100f))
        if (t != 100) cfg.fontScale = t / 100f
        return base.createConfigurationContext(cfg)
    }
}
