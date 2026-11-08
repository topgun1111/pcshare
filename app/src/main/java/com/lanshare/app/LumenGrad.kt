package com.lanshare.app

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import org.json.JSONObject

/**
 * Lumen Part 4b (N4): the named gradients of spec 5.2 as native shaders, derived from the same slots as the page (ui.html block LUMEN-UI4):
 *  gPrimary  gradFrom -> gradTo slots   (135 deg)   gFab    fab 6 % white -> 10 % black
 *  gDanger   error   6 % white  -> 14 % black   (135 deg)   gSuccess success 8 % white -> 15 % black
 *  gSurface  surfaceHi -> surface (180 deg)                    gGlow   primary 28 % centre -> 0 % edge (radial)
 *  gSkeleton surfaceLo -> surfaceHi -> surfaceLo (90 deg, moving)       gScrimTop / gScrimBottom  black 55 % -> 0 %
 * Shaders are built per call from a few ints (cheap); callers keep them while the view size and theme stay the same.
 * First callers: NlFabView (gFab), NlToastView / NlDlView (progress gPrimary, done gSuccess, card gSurface), NlDockView (pill gPrimary).
 * gGlow, gSkeleton, gScrimTop / gScrimBottom have no caller yet: tiles / splash (Part 4c), skeleton rows (Part 4d), viewer bars (Part 4c).
 */
object LumenGrad {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** gPrimary stops (spec 5.2): the theme's gradFrom -> gradTo slots (Ember = the spec's #D2590A -> #A93F00 light, #FFB77C -> #FF9A4D dark, derived for the other themes). */
    fun primary(lm: Lumen): IntArray = intArrayOf(lm.gradFrom, lm.gradTo)
    fun fab(fab: Int): IntArray = intArrayOf(LumenCategory.mix(fab, WHITE, 0.06f), LumenCategory.mix(fab, BLACK, 0.10f))
    fun danger(lm: Lumen): IntArray = intArrayOf(LumenCategory.mix(lm.error, WHITE, 0.06f), LumenCategory.mix(lm.error, BLACK, 0.14f))
    fun success(lm: Lumen): IntArray = intArrayOf(LumenCategory.mix(lm.success, WHITE, 0.08f), LumenCategory.mix(lm.success, BLACK, 0.15f))

    /** 135 degrees: top-left to bottom-right of the rectangle. */
    fun diag(c: IntArray, l: Float, t: Float, r: Float, b: Float): Shader = LinearGradient(l, t, r, b, c[0], c[1], Shader.TileMode.CLAMP)

    /** 180 degrees: top to bottom. */
    fun vert(top: Int, bottom: Int, t: Float, b: Float): Shader = LinearGradient(0f, t, 0f, b, top, bottom, Shader.TileMode.CLAMP)

    /**
     * Accent for the always-dark viewer stage (L7 black, spec 12.22): the theme primary, lifted towards white until it is bright
     * enough on black (light themes have a dark primary, e.g. Ember light #C25100 -> about #D9792F; dark themes stay as they are).
     */
    fun onDark(lm: Lumen): Int {
        val c = lm.primary
        val lum = (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f
        val col = if (lum >= 0.5f) c else LumenCategory.mix(c, WHITE, (0.5f - lum) / (1f - lum))
        return col or 0xFF000000.toInt()
    }

    /** gSurface: cards (surfaceHi at the top, surface at the bottom). */
    fun surface(lm: Lumen, t: Float, b: Float): Shader = vert(lm.surfaceHi, lm.surface, t, b)

    /** gGlow: radial halo of the primary colour (28 % in the centre, 0 % at [radius]). */
    fun glow(lm: Lumen, cx: Float, cy: Float, radius: Float, night: Boolean): Shader {
        val a = if (night) 0.22f else 0.28f
        return RadialGradient(cx, cy, Math.max(1f, radius), LumenGlass.withAlpha(lm.primary, a), LumenGlass.withAlpha(lm.primary, 0f), Shader.TileMode.CLAMP)
    }

    /** gSkeleton: loading placeholder shimmer. [phase] 0..1 moves the bright band across [l]..[r]. */
    fun skeleton(lm: Lumen, l: Float, r: Float, phase: Float): Shader {
        val w = Math.max(1f, r - l)
        val x0 = l + (phase * 2f - 1f) * w
        return LinearGradient(x0, 0f, x0 + w, 0f, intArrayOf(lm.surfaceLo, lm.surfaceHi, lm.surfaceLo), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    /** gScrimTop: black 55 % at the top edge fading to 0 % at [b]; gScrimBottom is the mirror image (55 % at the bottom edge [b], 0 % at [t]). */
    /** gScrimTop as drawable colours (viewer top bars): black 55 % -> 28 % -> 0 (the middle stop keeps the fade smooth on bright pictures). */
    val SCRIM_TOP_COLORS = intArrayOf(0x8C000000.toInt(), 0x47000000, 0x00000000)

    fun scrimTop(t: Float, b: Float): Shader = LinearGradient(0f, t, 0f, b, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    fun scrimBottom(t: Float, b: Float): Shader = LinearGradient(0f, t, 0f, b, 0x00000000, 0x8C000000.toInt(), Shader.TileMode.CLAMP)
}

/**
 * Lumen Part 4b (N6): radius flavour for native chrome. The page sends its computed `--r-*` values in the palette ("rad", CSS px = dp), so a theme
 * with its own radii (Windows 11: r-l 12, r-xxl 24) rounds the native chrome the same way. Native dialogs (Lm) keep their 32 dp on purpose.
 */
object LumenRadius {
    private var cj: String? = null
    private var cm: Map<String, Float> = emptyMap()

    /** Radius [key] (xs s m l xl xxl) in dp; [def] when the page never sent one. */
    fun dp(c: Context, key: String, def: Float): Float {
        val j = AppPal.json(c) ?: return def
        if (j !== cj) {
            cm = try {
                val o = JSONObject(j).optJSONObject("rad")
                val m = HashMap<String, Float>()
                if (o != null) { val ks = o.keys(); while (ks.hasNext()) { val k = ks.next(); val v = o.optDouble(k, -1.0); if (v >= 0.0) m[k] = v.toFloat() } }
                m
            } catch (_: Throwable) { emptyMap() }
            cj = j
        }
        return cm[key] ?: def
    }

    fun l(c: Context): Float = dp(c, "l", 16f)
    fun xl(c: Context): Float = dp(c, "xl", 24f)
    fun xxl(c: Context): Float = dp(c, "xxl", 32f)
}
