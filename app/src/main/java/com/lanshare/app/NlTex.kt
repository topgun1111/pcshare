package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.ColorUtils

/**
 * Surfaces for gradient themes (Ubuntu / Yaru): a vertical gradient with an optional film-grain layer on top, like the Ubuntu wallpaper.
 * The palette (ui.html nlPal()) only carries gradient colours for such themes (gt/gb, ct/cb, ...); every other theme stays flat.
 */
object NlTex {
    private var darkGrain: Bitmap? = null
    private var lightGrain: Bitmap? = null

    /** 128 px tile of deterministic noise (alpha 0..~22): white speckles for dark surfaces, black for light ones. */
    private fun grain(light: Boolean): Bitmap {
        (if (light) lightGrain else darkGrain)?.let { return it }
        val n = 128
        val px = IntArray(n * n)
        var seed = if (light) 0x2F6E2B1L else 0x51ED27L
        val base = if (light) 0 else 255
        for (i in px.indices) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val a = ((seed ushr 40) and 0xFF).toInt() * 22 / 255
            px[i] = Color.argb(a, base, base, base)
        }
        val b = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        if (light) lightGrain = b else darkGrain = b
        return b
    }

    fun isLight(top: Int) = ColorUtils.calculateLuminance(top) > 0.5

    /** Rectangular gradient + grain (list background, drawer, full-screen sheets). */
    fun layered(c: Context, top: Int, bottom: Int): Drawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        val t = BitmapDrawable(c.resources, grain(isLight(top))).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        return LayerDrawable(arrayOf(g, t))
    }

    /** Rounded gradient card with a 1 dp edge line (dialogs, bottom sheets). */
    fun card(top: Int, bottom: Int, radiusPx: Float, edge: Int, edgePx: Int): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusPx
            setStroke(edgePx, edge)
        }
}
