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

    private fun styleTree(view: View, lm: Lumen) {
        when (view) {
            is EditText -> {
                view.setTextColor(lm.ink)
                view.setHintTextColor(lm.inkDim)
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(lm.primary)
            }
            is Button -> LumenButtons.refresh(view, lm)
            is TextView -> {
                if (LumenButtons.refreshManaged(view, lm)) return
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

/** Explicit button roles from Lumen 12.1; never replace listeners or enabled/selected state. */
object LumenButtons {
    enum class Role { PRIMARY, TONAL, OUTLINE, TEXT, DANGER, GLASS }
    private data class Spec(val role: Role, val compact: Boolean, val label: Int?, val backdrop: Int?)
    private val managed = java.util.WeakHashMap<TextView, Spec>()
    private fun dp(v: TextView, n: Float) = Math.round(n * v.resources.displayMetrics.density)
    private fun opq(c: Int) = c or 0xFF000000.toInt()
    private fun contrast(a: Int, b: Int) = ColorUtils.calculateContrast(opq(a), opq(b))
    private fun readable(preferred: Int, colors: IntArray): Int? {
        val samples = if (colors.size == 2) (0..16).map { ColorUtils.blendARGB(colors[0], colors[1], it / 16f) } else colors.toList()
        return listOf(preferred, Color.BLACK, Color.WHITE).distinct().firstOrNull { fg -> samples.all { contrast(fg, it) >= 4.5 } }
    }

    fun apply(v: TextView, role: Role, compact: Boolean = false, label: Int? = null, backdrop: Int? = null) {
        val spec = Spec(role, compact, label, backdrop)
        managed[v] = spec
        render(v, spec, Lumen.of(v.context))
    }

    /** A RippleDrawable alone does not identify a stock button. Leave all custom controls intact. */
    fun refresh(b: Button, lm: Lumen) {
        if (b is android.widget.CompoundButton) return
        if (refreshManaged(b, lm)) return
        if (b.javaClass != Button::class.java) return
        val current = b.background?.constantState ?: return
        val stock = Button(b.context).background?.constantState ?: return
        if (current === stock && b.backgroundTintList == null) apply(b, Role.TONAL)
    }

    fun refreshManaged(v: TextView, lm: Lumen): Boolean {
        val spec = managed[v] ?: return false
        render(v, spec, lm)
        return true
    }

    private fun render(v: TextView, spec: Spec, lm: Lumen) {
        val role = spec.role
        val bg = spec.backdrop ?: lm.surface
        val transparent = role == Role.TEXT || role == Role.OUTLINE
        val flat = when (role) {
            Role.PRIMARY -> lm.primary
            Role.TONAL -> lm.primaryCont
            Role.DANGER -> lm.error
            Role.GLASS -> lm.surfaceHi
            else -> bg
        }
        var fills = when (role) {
            Role.PRIMARY -> LumenGrad.primary(lm)
            Role.DANGER -> LumenGrad.danger(lm)
            else -> intArrayOf(flat)
        }
        val preferred = spec.label ?: when (role) {
            Role.PRIMARY -> lm.onPrimary
            Role.TONAL -> lm.onPrimaryCont
            Role.DANGER -> lm.onError
            Role.GLASS -> lm.ink
            else -> lm.primary
        }
        val backgrounds = if (transparent && Color.alpha(bg) < 255)
            intArrayOf(ColorUtils.compositeColors(bg, Color.BLACK), ColorUtils.compositeColors(bg, Color.WHITE))
        else fills
        val initial = readable(preferred, backgrounds)
        if (initial == null && !transparent) fills = intArrayOf(flat)
        val on = initial ?: if (transparent) preferred else readable(preferred, fills) ?: lm.ink
        val disabledFill = ColorUtils.blendARGB(opq(lm.surface), opq(lm.ink), 0.12f)
        val disabledOn = ColorUtils.blendARGB(disabledFill, opq(lm.ink), 0.38f)
        fun shape(colors: IntArray, stroke: Int? = null): GradientDrawable {
            val g = if (colors.size == 2) GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
                else GradientDrawable().apply { setColor(colors[0]) }
            g.cornerRadius = dp(v, 999f).toFloat()
            if (stroke != null) g.setStroke(dp(v, 1.5f).coerceAtLeast(1), stroke)
            return g
        }
        fun face(focused: Boolean) = shape(if (transparent) intArrayOf(Color.TRANSPARENT) else fills,
            if (focused) lm.focus else if (role == Role.OUTLINE || role == Role.GLASS) lm.edge else null)
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(if (transparent) intArrayOf(Color.TRANSPARENT) else intArrayOf(disabledFill)))
            addState(intArrayOf(android.R.attr.state_focused), face(true))
            addState(intArrayOf(), face(false))
        }
        v.backgroundTintList = null
        v.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(opq(on), 36)), states, shape(intArrayOf(Color.WHITE)))
        v.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(disabledOn, on)))
        if (v is Button) {
            v.isAllCaps = false
            v.stateListAnimator = null
            v.minHeight = dp(v, 48f)
            v.minWidth = dp(v, 64f)
        }
        v.minimumHeight = dp(v, 48f)
        v.minimumWidth = dp(v, 64f)
        v.gravity = android.view.Gravity.CENTER
        LumenTypography.apply(v, LumenTypography.LABEL_L)
        val pad = dp(v, if (spec.compact || transparent) 16f else 24f)
        v.setPaddingRelative(pad, dp(v, 4f), pad, dp(v, 4f))
        v.elevation = if (role == Role.PRIMARY || role == Role.DANGER) dp(v, 1f).toFloat() else 0f
    }
}
