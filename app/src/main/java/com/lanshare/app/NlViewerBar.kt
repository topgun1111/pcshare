package com.lanshare.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lumen 12.7 / 12.22 (N7): the two top-bar variants that are not the list bar, as one shared component.
 *
 * - VIEWER (image, video, PDF): overlay on the picture, `gScrimTop` behind white glyphs, title (Title M 16) + subtitle (Label 12) with a soft
 *   shadow so it reads on bright pictures, fades out after 3 s without a touch (fade 200 ms, plain with Reduce motion, never with a screen reader).
 * - SEARCH (`UsActivity`): 64 dp, G1 chrome fill (same alpha ladder as the list bar), the field fills the bar, back glyph inside the field.
 *
 * The three viewers used to build the scrim, the title styles, the 3 s timer and the show / hide on their own; they now call this object.
 * NOT compiled in the Lumen environment (owner compile).
 */
object NlViewerBar {
    const val HIDE_MS = 3000L        // spec 12.7: inactivity before a viewer bar hides
    const val FADE_MS = 200L         // spec 13.2: exit fade
    const val SEARCH_H_DP = 64       // spec 12.7: Search bar height

    private fun dp(a: Activity, v: Int) = Math.round(v * a.resources.displayMetrics.density)

    /** gScrimTop as the bar background (black 55 % -> 28 % -> 0). */
    fun scrim(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, LumenGrad.SCRIM_TOP_COLORS)

    /** Title M (16 sp medium, white, soft shadow, middle ellipsis like a file name). */
    fun title(a: Activity): TextView = TextView(a).apply {
        setTextColor(Color.WHITE); LumenTypography.fit(this, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        setShadowLayer(dp(a, 3).toFloat(), 0f, dp(a, 1).toFloat(), 0x99000000.toInt())
    }

    /** Label (12 sp, white 70 %, same shadow). */
    fun subtitle(a: Activity): TextView = TextView(a).apply {
        setTextColor(0xB3FFFFFF.toInt()); LumenTypography.fit(this, 12f)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(a, 3).toFloat(), 0f, dp(a, 1).toFloat(), 0x99000000.toInt()); setPadding(0, dp(a, 1), 0, 0)
    }

    /** Title + subtitle column (6 dp / 8 dp side padding), as in the viewers' bars. */
    fun textColumn(a: Activity, title: TextView, sub: TextView): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(a, 6), 0, dp(a, 8), 0); addView(title); addView(sub)
    }

    /** Fades [views] in / out in [FADE_MS]; hidden views end as GONE. Plain show / hide with Reduce motion. */
    fun fade(a: Activity, views: List<View>, on: Boolean) {
        val still = LumenPrefs.reduceMotion(a)
        for (v in views) {
            v.animate().cancel()
            if (on) {
                v.visibility = View.VISIBLE
                if (still) v.alpha = 1f
                else { if (v.alpha >= 1f) v.alpha = 0f; v.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
            } else if (still) { v.alpha = 1f; v.visibility = View.GONE }
            else v.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
        }
    }

    /**
     * The 3 s inactivity timer. [isOn] = bars are showing, [hide] = what the activity does to hide them (its own toggle).
     * Call [arm] after every toggle, in `onUserInteraction()` and `onResume()`. Not armed with TalkBack / explore-by-touch on.
     */
    class AutoHide(private val a: Activity, private val isOn: () -> Boolean, private val hide: () -> Unit, private val ms: Long = HIDE_MS) {
        private val h = Handler(Looper.getMainLooper())
        private val run = Runnable { if (isOn() && !a.isFinishing) hide() }
        fun arm() {
            h.removeCallbacks(run)
            if (!isOn()) return
            val am = a.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am?.isTouchExplorationEnabled == true) return
            h.postDelayed(run, ms)
        }
        fun cancel() { h.removeCallbacks(run) }
    }

    /** SEARCH variant: bar background = G1 chrome fill of the screen colour (same ladder F0-F3 as the list bar) and a 64 dp minimum height. */
    fun styleSearchBar(a: Activity, bar: LinearLayout, screenBg: Int) {
        bar.setBackgroundColor(LumenGlass.chromeFill(a, screenBg))
        bar.minimumHeight = dp(a, SEARCH_H_DP)
    }
}
