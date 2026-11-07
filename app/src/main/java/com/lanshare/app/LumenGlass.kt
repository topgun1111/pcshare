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

    /**
     * Part 4b (N1 b): fill for in-window chrome (top / selection bar, bottom action bar, drawer panel), spec 6.2 tier G1 + 6.5 ladder.
     * A native view cannot blur what the page draws underneath it (blur-behind belongs to a Window), so F0 / F1 use a tint-raised stand-in:
     * the tier alpha 72 % / 62 % would show unblurred page content, so it is lifted to 88 / 84 % (F0) and 92 / 88 % (F1) light / dark; F2 = 94 / 92 %; F3 = opaque.
     * Real backdrop blur for this chrome is task N1 (c), Part 4d. [base] = the colour the page uses for that surface (its own bar / card colour).
     */
    fun chromeFill(c: Context, base: Int): Int {
        if ((base ushr 24) < 0xFF) return base   // the page already chose a translucent colour: keep it
        val night = AppPal.night(c)
        val a = when (step(c)) {
            0 -> if (night) 0.84f else 0.88f
            1 -> if (night) 0.88f else 0.92f
            2 -> if (night) 0.92f else 0.94f
            else -> 1f
        }
        return withAlpha(base, a)
    }

    /** Lumen 4f (N1 c): fill when the real backdrop blur is painted under the chrome (spec 6.2 G1 tier alpha, F0 lower than F1 because it is blurrier). */
    fun blurFill(c: Context, base: Int): Int {
        if ((base ushr 24) < 0xFF) return base
        val night = AppPal.night(c)
        val a = if (step(c) == 0) (if (night) 0.66f else 0.72f) else (if (night) 0.72f else 0.78f)
        return withAlpha(base, a)
    }

    /** gChrome (top light from above) is only drawn for F0-F2; F3 is flat (spec 6.5). */
    fun chromeGradient(c: Context): Boolean = step(c) < 3
}
