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
import androidx.core.view.WindowInsetsControllerCompat

/** Applies the shared Lumen surface language to native Activity chrome without
 * overwriting feature-specific artwork, viewer stages, or explicitly styled controls. */
object LumenNativeTheme {
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

    fun apply(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val lm = Lumen.of(activity)
        val window = activity.window
        applySystemBars(window, lm)
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

    private fun applySystemBars(window: Window, lm: Lumen) {
        window.statusBarColor = lm.canvas
        window.navigationBarColor = lm.canvas
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val light = ColorUtils.calculateLuminance(lm.canvas) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun isPlainWindowBackground(drawable: android.graphics.drawable.Drawable?): Boolean {
        val color = (drawable as? ColorDrawable)?.color ?: return false
        return color == Color.WHITE || color == Color.BLACK || color == 0xFFF5F5F5.toInt()
    }

    private fun styleTree(view: View, lm: Lumen) {
        when (view) {
            is EditText -> {
                view.setTextColor(lm.ink)
                view.setHintTextColor(lm.inkDim)
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(lm.primary)
            }
            is Button -> {
                view.setTextColor(if (view.isEnabled) lm.primary else lm.inkDim)
                view.minHeight = maxOf(view.minHeight, (48 * view.resources.displayMetrics.density).toInt())
                view.isAllCaps = false
            }
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
