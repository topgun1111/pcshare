package com.lanshare.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Lumen Part 4a (N1 a): native glass for dialogs and sheets, same F0-F3 ladder as the page (spec 6.5).
 *  F0 / F1 = real backdrop blur (API 31+ blur-behind, radius 40 / 20 dp) under a translucent tint
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
        if (AppPal.secureHost(c)) return 3
        val s = p(c).getInt("glass", 1)
        return if (s <= 1 && (!blurOk(c) || LumenPrefs.saver(c))) 2 else s
    }

    private val observed = WeakHashMap<View, Boolean>()
    private val visibleModals = WeakHashMap<View, WeakReference<Activity>>()

    fun modalOpen(c: Context): Boolean {
        val act = AppPal.hostActivity(c) ?: return false
        return visibleModals.values.any { it.get() === act }
    }

    /** Each native modal demotes the page and native chrome beneath it before the next drawing pass. */
    fun trackModal(dialog: Dialog) {
        val act = AppPal.hostActivity(dialog.context) ?: return
        val decor = dialog.window?.decorView ?: return
        if (observed.put(decor, true) == true) return
        fun update(v: View, attached: Boolean) {
            val before = modalOpen(act)
            if (attached) visibleModals[v] = WeakReference(act) else visibleModals.remove(v)
            val after = modalOpen(act)
            if (before != after) {
                if (act is MainActivity) act.setModalGlassFallback(after)
                NlBackdrop.invalidateAll(force = true)
            }
        }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = update(v, true)
            override fun onViewDetachedFromWindow(v: View) = update(v, false)
        })
        if (decor.isAttachedToWindow) update(decor, true)
    }

    /** G3 native modal glass. Nested windows stay frosted and secure windows stay opaque. */
    fun dialogFill(w: Window?, c: Context, secure: Boolean): Int {
        val lm = Lumen.of(c)
        val windowSecure = (w?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        val material = if (secure || windowSecure) 3 else step(c)
        val blurred = w != null && material <= 1 && !modalOpen(c) &&
            setWindowBlur(w, Math.round((if (material == 0) 40f else 20f) * c.resources.displayMetrics.density))
        if (!blurred && w != null) setWindowBlur(w, 0)
        if (material >= 3) return lm.surface or 0xFF000000.toInt()
        val night = AppPal.night(c)
        val alpha = if (blurred) {
            if (night) 0.76f else 0.84f
        } else {
            if (night) 0.92f else 0.94f
        }
        return raisedAlpha(lm.glassSafe, alpha)
    }

    private fun setWindowBlur(window: Window, radius: Int): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val a = window.attributes
            a.blurBehindRadius = radius
            window.attributes = a
            if (radius > 0) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            radius > 0
        } catch (_: RuntimeException) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            false
        }
    }

    private fun raisedAlpha(base: Int, alpha: Float): Int =
        (maxOf(base ushr 24, Math.round(255f * alpha)) shl 24) or (base and 0x00FFFFFF)

    /** Keep a themed chrome tint only when both text roles read over either backdrop extreme. */
    private fun readableChrome(lm: Lumen, fill: Int): Int {
        if (fill ushr 24 == 255) return fill
        if ((fill and 0x00FFFFFF) == (lm.glassSafe and 0x00FFFFFF)) {
            return raisedAlpha(lm.glassSafe, (fill ushr 24) / 255f)
        }
        val low = ColorUtils.compositeColors(fill, android.graphics.Color.BLACK)
        val high = ColorUtils.compositeColors(fill, android.graphics.Color.WHITE)
        for (foreground in intArrayOf(lm.ink, lm.inkMut)) {
            val text = foreground or 0xFF000000.toInt()
            val min = ColorUtils.calculateContrast(text, low)
            val max = ColorUtils.calculateContrast(text, high)
            val luminance = ColorUtils.calculateLuminance(text)
            if (min < 4.5 || max < 4.5 || luminance in
                ColorUtils.calculateLuminance(low)..ColorUtils.calculateLuminance(high)) {
                return raisedAlpha(lm.glassSafe, (fill ushr 24) / 255f)
            }
        }
        return fill
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
    fun chromeFill(c: Context, base: Int, lm: Lumen = Lumen.of(c)): Int {
        val material = step(c)
        if (material >= 3) return lm.surface or 0xFF000000.toInt()
        val night = AppPal.night(c)
        val a = when (material) {
            0 -> if (night) 0.84f else 0.88f
            1 -> if (night) 0.88f else 0.92f
            2 -> if (night) 0.92f else 0.94f
            else -> 1f
        }
        return readableChrome(lm, withAlpha(base, a))
    }

    /** Lumen 4f (N1 c): fill when the real backdrop blur is painted under the chrome (spec 6.2 G1 tier alpha, F0 lower than F1 because it is blurrier). */
    fun blurFill(c: Context, base: Int, lm: Lumen = Lumen.of(c), floating: Boolean = false): Int {
        val material = step(c)
        if (material >= 2) return chromeFill(c, base, lm)
        val night = AppPal.night(c)
        val a = if (floating) {
            if (night) 0.70f else 0.78f
        } else {
            if (night) 0.62f else 0.72f
        }
        return readableChrome(lm, withAlpha(base, a))
    }

    /** gChrome (top light from above) is only drawn for F0-F2; F3 is flat (spec 6.5). */
    fun chromeGradient(c: Context): Boolean = step(c) < 3
}
