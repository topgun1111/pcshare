package com.lanshare.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Applies the shared Lumen surface language to native Activity chrome without
 * overwriting feature-specific artwork, viewer stages, or explicitly styled controls. */
object LumenNativeTheme {
    /** Screens on Theme.LANShare have a LIGHT Material base (also in system night mode). When the app theme is dark, switch them to Theme.LANShare.AppDark so the stock widgets
     * (text fields, menus, progress bars, selection handles, default buttons) are dark-based too, not light grey on a dark canvas. Call before the screen creates any view;
     * same "canvas window background" test as [early], so the player (black stage) and the Clear activities keep their own theme. */
    fun darkBase(activity: Activity) {
        if (!AppPal.night(activity)) return
        val tv = android.util.TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)) return
        if (tv.type < android.util.TypedValue.TYPE_FIRST_COLOR_INT || tv.type > android.util.TypedValue.TYPE_LAST_COLOR_INT) return
        if (tv.data != activity.resources.getColor(R.color.canvas, activity.theme)) return
        activity.setTheme(R.style.Theme_LANShare_AppDark)
    }

    /** Called from Activity.onCreate, before the first frame: the window background (and the bar colours) take the saved app theme right away,
     * so a dark theme never flashes the light canvas of Theme.LANShare. Only screens whose theme background is that canvas are touched:
     * viewers keep their black stage and the one-shot Clear activities stay transparent. Does not touch the decor view (no feature-order side effects). */
    fun early(activity: Activity) {
        val tv = android.util.TypedValue()
        if (!activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)) return
        if (tv.type < android.util.TypedValue.TYPE_FIRST_COLOR_INT || tv.type > android.util.TypedValue.TYPE_LAST_COLOR_INT) return
        if (tv.data != activity.resources.getColor(R.color.canvas, activity.theme)) return
        val canvas = Lumen.of(activity).canvas
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(canvas))
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
    }

    /** Screens that draw edge to edge (spec 9.4 / 15.3): [edgeToEdge] registers them, [applySystemBars] then keeps their bars transparent. */
    private val edge = java.util.WeakHashMap<Activity, Boolean>()

    /** Spec 9.4 / 15.3: transparent status and navigation bars, the screen draws under them and pads [root] by the bar / cutout insets
     * (and by the keyboard, because an edge-to-edge window is no longer resized by adjustResize). [root] must paint its own background. */
    fun edgeToEdge(activity: Activity, root: View) {
        val window = activity.window
        edge[activity] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom   // AndroidX derives this back to API 21 with adjustResize
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime))
            ins
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val lm = Lumen.of(activity)
        val window = activity.window
        applySystemBars(window, lm, edge.containsKey(activity))
        val decor = window.decorView
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        val root = content.getChildAt(0)
        if (root.background == null || isPlainWindowBackground(root.background)) {
            root.setBackgroundColor(lm.canvas)
        }
        styleTree(root, lm)
        if (activity !is MainActivity) try { NlA11y.polish(root) } catch (_: Throwable) { }   // MainActivity: the native list / bars / drawer describe themselves
    }

    private fun applySystemBars(window: Window, lm: Lumen, transparent: Boolean) {
        window.statusBarColor = if (transparent) Color.TRANSPARENT else lm.canvas
        window.navigationBarColor = if (transparent) Color.TRANSPARENT else lm.canvas
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val light = AppPal.isLight(lm.canvas)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun isPlainWindowBackground(drawable: android.graphics.drawable.Drawable?): Boolean {
        val color = (drawable as? ColorDrawable)?.color ?: return false
        return color == Color.WHITE || color == Color.BLACK || color == 0xFFF5F5F5.toInt()
    }

    private fun opq(c: Int) = c or 0xFF000000.toInt()
    private fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(opq(a), opq(b))
    /** Black or white, whichever reads better on [fill]. */
    private fun blackOrWhite(fill: Int) = if (contrast(Color.BLACK, fill) >= contrast(Color.WHITE, fill)) Color.BLACK else Color.WHITE
    private fun pill(c: Int, v: View): GradientDrawable = GradientDrawable().apply { setColor(c); cornerRadius = 999f * v.resources.displayMetrics.density }

    /** A plain Button: a platform-default one (light grey Material fill) becomes the Lumen tonal button (primaryCont fill, onPrimaryCont label, 4.5:1 guaranteed);
     * a button whose screen set its own fill keeps that fill and only gets a different label colour when the current one is below 3:1 on it. The old code made every label
     * `primary`, which is invisible on a primary fill and on the light grey default fill of a dark theme. */
    private fun styleButton(b: Button, lm: Lumen) {
        b.isAllCaps = false
        b.minHeight = maxOf(b.minHeight, (48 * b.resources.displayMetrics.density).toInt())
        val bg = b.background
        val own: Int? = when (bg) {
            is ColorDrawable -> bg.color
            is GradientDrawable -> try { bg.color?.defaultColor } catch (_: Throwable) { null }
            else -> null
        }
        val stock = bg == null || bg is android.graphics.drawable.InsetDrawable || bg is android.graphics.drawable.RippleDrawable
        if (stock) {
            val on = if (contrast(lm.onPrimaryCont, lm.primaryCont) >= 4.5) lm.onPrimaryCont else blackOrWhite(lm.primaryCont)
            b.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(lm.inkDim, on)))
            val face = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled), pill(lm.surfaceHi, b))
                addState(intArrayOf(), pill(lm.primaryCont, b))
            }
            b.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(on, 36)), face, null)
        } else if (own != null && contrast(b.currentTextColor, own) < 3.0) {
            b.setTextColor(blackOrWhite(own))
        }
    }

    private fun styleTree(view: View, lm: Lumen) {
        when (view) {
            is EditText -> {
                view.setTextColor(lm.ink)
                view.setHintTextColor(lm.inkDim)
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(lm.primary)
            }
            is Button -> styleButton(view, lm)
            is TextView -> {
                val current = view.currentTextColor
                // Only normalize platform-default monochrome text. Preserve semantic and custom feature colours.
                if (current == Color.BLACK || current == Color.WHITE || current == 0xFF212121.toInt() || current == 0xFF303030.toInt()) {
                    view.setTextColor(lm.ink)
                }
                if (view.hint != null && view.currentHintTextColor == Color.GRAY) view.setHintTextColor(lm.inkDim)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) styleTree(view.getChildAt(i), lm)
        }
    }
}
