package com.lanshare.app

import android.content.Context
import android.graphics.Color
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
}
