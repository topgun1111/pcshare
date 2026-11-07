package com.lanshare.app

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * Lumen Part 15 / 16: the few device facts and switches the page asks native for.
 * - glassHint: device class + battery saver + high text contrast, so ui.html can pick glass step F0-F3 (spec 6.5 / 18).
 * - reduce motion: the in-app switch OR the system "remove animations" flag (spec 13.4); Lm.kt and the viewer launch read it.
 * - haptics: the in-app on / off switch (spec 13.5); Haptics.init reads it.
 */
object LumenPrefs {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    fun sysNoAnim(c: Context): Boolean = try {
        Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Throwable) { false }

    fun reduceMotionApp(c: Context): Boolean = p(c).getBoolean("rm", false)
    fun setReduceMotion(c: Context, on: Boolean) { p(c).edit().putBoolean("rm", on).apply() }
    fun reduceMotion(c: Context): Boolean = reduceMotionApp(c) || sysNoAnim(c)

    fun hapticsOn(c: Context): Boolean = p(c).getBoolean("haptics", true)
    fun setHaptics(c: Context, on: Boolean) { p(c).edit().putBoolean("haptics", on).apply(); Haptics.enabled = on }

    /** Lumen Part 16 (13.3 "open from thumbnail"): ActivityOptions so a viewer / player grows out of the thumbnail that was just touched (platform scale-up from a
     *  64 dp square at the last touch-down, see NlOverlay.downX). Not a shared-element transform: the image is not continuous and the chrome does not fade in after 120 ms.
     *  null = default animation (Reduce motion, or no touch seen yet). */
    fun growFrom(act: android.app.Activity): android.os.Bundle? = try {
        if (NlOverlay.downX < 0f || reduceMotion(act)) null else {
            val dv = act.window.decorView; val loc = IntArray(2); dv.getLocationOnScreen(loc)
            val half = (32 * act.resources.displayMetrics.density).toInt()
            android.app.ActivityOptions.makeScaleUpAnimation(dv, NlOverlay.downX.toInt() - loc[0] - half, NlOverlay.downY.toInt() - loc[1] - half, half * 2, half * 2).toBundle()
        }
    } catch (_: Throwable) { null }

    /** {"low":0|1,"saver":0|1,"hc":0|1,"rm":0|1}. low = low-RAM flag, small app heap or 4 cores or fewer (a guess, not measured on the reference devices). */
    fun glassHint(c: Context): String {
        val low = try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.isLowRamDevice || am.memoryClass <= 128 || Runtime.getRuntime().availableProcessors() <= 4
        } catch (_: Throwable) { false }
        val saver = try { (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode } catch (_: Throwable) { false }
        val hc = try { Settings.Secure.getInt(c.contentResolver, "high_text_contrast_enabled", 0) == 1 } catch (_: Throwable) { false }
        fun b(x: Boolean) = if (x) 1 else 0
        return "{\"low\":${b(low)},\"saver\":${b(saver)},\"hc\":${b(hc)},\"rm\":${b(sysNoAnim(c))}}"
    }
}
