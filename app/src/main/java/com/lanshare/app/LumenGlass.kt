package com.lanshare.app

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager

/**
 * Lumen Part 4a (N1 a): native glass for dialogs and sheets, same F0-F3 ladder as the page (spec 6.5).
 *  F0 / F1 = real backdrop blur (API 31+ blur-behind, radius 32 / 16 dp) under a translucent tint
 *  F2      = no blur, tint raised to 94 % (what NlChrome already does)
 *  F3      = opaque surface + hairline
 * The page picks the step (glassApply in ui.html, including Battery saver / low RAM / high contrast) and reports it through LSAndroid.glassStepSet().
 * Without that report the default is F1; when the system has cross-window blur off (or API < 31) F0 / F1 fall back to F2.
 */
object LumenGlass {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    fun setStep(c: Context, s: String) {
        val n = when (s) { "f0" -> 0; "f1" -> 1; "f2" -> 2; "f3" -> 3; else -> return }
        p(c).edit().putInt("glass", n).apply()
    }

    fun blurOk(c: Context): Boolean = Build.VERSION.SDK_INT >= 31 && try {
        (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
    } catch (_: Throwable) { false }

    /** Effective step 0..3 for this device right now. */
    fun step(c: Context): Int {
        val s = p(c).getInt("glass", 1)
        return if (s <= 1 && !blurOk(c)) 2 else s
    }

    private fun radiusDp(step: Int) = if (step == 0) 32f else if (step == 1) 16f else 0f

    /** Turns on blur-behind for [w] when the step asks for it. Returns the panel fill (ARGB) to paint over it. */
    fun apply(w: Window?, c: Context): Int {
        val lm = Lumen.of(c)
        val st = step(c)
        if (st >= 3) return lm.surface or 0xFF000000.toInt()
        var blurred = false
        if (w != null && st <= 1 && Build.VERSION.SDK_INT >= 31) {
            try {
                val px = Math.round(radiusDp(st) * c.resources.displayMetrics.density)
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val a = w.attributes; a.blurBehindRadius = px; w.attributes = a
                blurred = true
            } catch (_: Throwable) { }
        }
        val alpha = if (blurred) (if (st == 0) 0.72f else 0.82f) else 0.94f
        return (Math.round(255f * alpha) shl 24) or (lm.glassTint and 0x00FFFFFF)
    }

    /** Rounded panel: [fill] + edge hairline. [top] = only the top corners round (bottom sheet). */
    fun panel(c: Context, fill: Int, radiusDp: Float, top: Boolean): GradientDrawable {
        val d = c.resources.displayMetrics.density
        val g = GradientDrawable()
        g.setColor(fill)
        val r = radiusDp * d
        if (top) g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else g.cornerRadius = r
        g.setStroke(Math.max(1, Math.round(d)), Lumen.of(c).edge)
        return g
    }

    fun withAlpha(col: Int, a: Float): Int = (Math.round(255f * a) shl 24) or (col and 0x00FFFFFF)
}
