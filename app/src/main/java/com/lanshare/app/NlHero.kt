package com.lanshare.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.PixelCopy
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Lumen Part 4f (M7 b): container transform for the viewer / player launch, done as a SNAPSHOT OVERLAY (owner decision: no Activity shared-element transition).
 *
 *  1. The list calls [launch] with the tapped thumbnail (its bitmap + the rect it is drawn in + its corner radius).
 *  2. A PixelCopy of the launching window is taken (API 26+), then the viewer Activity starts with NO platform animation.
 *  3. The viewer calls [play] right after setContentView: a full-screen overlay shows the window snapshot (so the list stays where it was) and a hero view
 *     that grows the SAME thumbnail bitmap from its list rect (centre-cropped, rounded) to the picture fitted in the screen, while the snapshot fades to black
 *     (400 ms, emphasized). The viewer's bars fade in 120 ms after the start (200 ms). The overlay fades out once the real picture is ready (max 1.5 s).
 * Anything missing (Reduce motion, no thumbnail yet, API < 26, copy failed) falls back to the Part 16 platform scale-up ([LumenPrefs.growFrom]) or a plain start.
 */
object NlHero {
    private class Src(val rect: RectF, val radius: Float, val thumb: Bitmap, val snap: Bitmap?, val at: Long,
                      val launchKey: String? = null, val resolve: ((String) -> Target?)? = null)
    /** A currently visible list cell that can receive the reverse hero after paging to another item. */
    class Target(val view: View, val rect: RectF, val radius: Float, val thumb: Bitmap)
    @Volatile private var src: Src? = null
    /** Lumen session 7 (M7 return): open snapshot + a resolver for the currently paged-to list cell. */
    private class Ret(val owner: Activity, val s: Src, val launchKey: String?, val resolve: ((String) -> Target?)?) { var busy = false }
    @Volatile private var ret: Ret? = null

    private fun take(): Src? {
        val s = src; src = null
        if (s == null) return null
        if (SystemClock.uptimeMillis() - s.at < 4000L) return s
        try { s.snap?.recycle() } catch (_: Throwable) {}
        return null
    }

    /** [tr] = the thumbnail rect in [v]'s own coordinates, [radius] px. */
    fun launch(act: Activity, v: View, tr: RectF, radius: Float, thumb: Bitmap, it: Intent,
               returnKey: String? = null, resolve: ((String) -> Target?)? = null) {
        if (Build.VERSION.SDK_INT < 26 || LumenPrefs.reduceMotion(act) || tr.isEmpty || thumb.isRecycled) { act.startActivity(it, LumenPrefs.growFrom(act)); return }
        try {
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            val rect = RectF(tr); rect.offset(loc[0].toFloat(), loc[1].toFloat())
            val dv = act.window.decorView
            if (dv.width <= 0 || dv.height <= 0) { act.startActivity(it, LumenPrefs.growFrom(act)); return }
            val snap = Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.ARGB_8888)
            var done = false
            var timedOut = false
            val h = Handler(Looper.getMainLooper())
            PixelCopy.request(act.window, snap, { res ->
                if (!done) {
                    done = true
                    if (res == PixelCopy.SUCCESS) {
                        src = Src(rect, radius, thumb, snap, SystemClock.uptimeMillis(), returnKey, resolve)
                        act.startActivity(it, ActivityOptions.makeCustomAnimation(act, 0, 0).toBundle())
                    } else {
                        src = null
                        try { snap.recycle() } catch (_: Throwable) {}
                        act.startActivity(it, LumenPrefs.growFrom(act))
                    }
                } else if (timedOut) {
                    // PixelCopy answered after the 250 ms launch fallback; release its now-unused bitmap.
                    try { snap.recycle() } catch (_: Throwable) {}
                }
            }, h)
            h.postDelayed({
                if (!done) {
                    done = true; timedOut = true; src = null
                    act.startActivity(it, LumenPrefs.growFrom(act))
                }
            }, 250L)   // if PixelCopy stalls, start with the normal platform scale-up; late callback frees the bitmap
        } catch (_: Throwable) { src = null; act.startActivity(it, LumenPrefs.growFrom(act)) }
    }

    /** Called by the viewer / player in onCreate after setContentView. [chrome] = bars that fade in after 120 ms; [ready] = true once the real picture is on screen. No-op when [launch] did not prepare a transition. */
    fun play(ctx: Context, root: FrameLayout, chrome: List<View>, ready: () -> Boolean) {
        val s = take() ?: return
        try {
            for (v in chrome) v.alpha = 0f
            val ov = FrameLayout(ctx)
            val bgv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; if (s.snap != null) setImageBitmap(s.snap) else setBackgroundColor(Color.BLACK) }
            val scrim = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = if (s.snap == null) 1f else 0f }
            val hero = HeroView(ctx, s)
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            ov.addView(bgv, FrameLayout.LayoutParams(mp, mp)); ov.addView(scrim, FrameLayout.LayoutParams(mp, mp)); ov.addView(hero, FrameLayout.LayoutParams(mp, mp))
            root.addView(ov, FrameLayout.LayoutParams(mp, mp))
            val a = ValueAnimator.ofFloat(0f, 1f).apply { duration = LumenMotion.SLOW; interpolator = LumenMotion.EMPH }
            a.addUpdateListener { x -> val t = x.animatedValue as Float; hero.t = t; hero.invalidate(); if (s.snap != null) scrim.alpha = t }
            val t0 = SystemClock.uptimeMillis()
            if (ctx is Activity && s.snap != null) { ret?.s?.snap?.recycle(); ret = Ret(ctx, s, s.launchKey, s.resolve) }
            fun finish() { ov.animate().alpha(0f).setDuration(LumenMotion.FAST).withEndAction { try { root.removeView(ov) } catch (_: Throwable) { }; if (ret?.s !== s) s.snap?.recycle() }.start() }
            fun waitReady() { if (ready() || SystemClock.uptimeMillis() - t0 > 1500L) finish() else ov.postDelayed({ waitReady() }, 50L) }
            a.addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(an: android.animation.Animator) { waitReady() } })
            a.start()
            ov.postDelayed({ for (v in chrome) v.animate().alpha(1f).setDuration(LumenMotion.BASE).start() }, 120L)
        } catch (_: Throwable) {
            for (v in chrome) v.alpha = 1f
            if (ret?.s === s) ret = null
            try { s.snap?.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Lumen session 7 (M7 return): shrink back into the launching thumbnail. Called from the viewer's `finish()`.
     * Runs only when this Activity played the open transition and Reduce motion is off. If the viewer paged to another item, [resolve] supplies that
     * item's currently visible list cell; if it is off-screen or the window changed size, the caller uses the safe fade + scale fallback. The overlay
     * shows the list snapshot taken at launch (so the list looks as it did), the thumbnail bitmap shrinks from the fitted picture to its cell while the black scrim fades
     * out; 280 ms accelerate. [done] then really finishes the Activity (no platform animation). Returns true = [done] will be called, the caller returns.
     */
    fun close(act: Activity, idxNow: Int, idx0: Int, keyNow: String? = null, done: () -> Unit): Boolean {
        val r = ret ?: return false
        if (r.owner !== act) return false
        if (r.busy) return true
        val snap = r.s.snap
        if (snap == null || snap.isRecycled || LumenPrefs.reduceMotion(act)) return false
        val source = if (idxNow == idx0 || keyNow == r.launchKey) r.s else {
            if (keyNow.isNullOrEmpty()) return false
            val target = try { r.resolve?.invoke(keyNow) } catch (_: Throwable) { null } ?: return false
            if (target.thumb.isRecycled || target.rect.isEmpty) return false
            val loc = IntArray(2); target.view.getLocationOnScreen(loc)
            val rect = RectF(target.rect); rect.offset(loc[0].toFloat(), loc[1].toFloat())
            Src(rect, target.radius, target.thumb, snap, r.s.at, keyNow, r.resolve)
        }
        if (source.thumb.isRecycled) return false
        try {
            val root = act.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? FrameLayout ?: return false
            if (root.width <= 0 || root.height <= 0) return false
            val tol = (8f * act.resources.displayMetrics.density).toInt()
            if (kotlin.math.abs(snap.width - root.width) > tol || kotlin.math.abs(snap.height - root.height) > tol) return false
            r.busy = true
            val ov = FrameLayout(act)
            val bgv = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_XY; setImageBitmap(snap) }
            val scrim = View(act).apply { setBackgroundColor(Color.BLACK); alpha = 1f }
            val hero = HeroView(act, source).apply { t = 1f }
            val mp = ViewGroup.LayoutParams.MATCH_PARENT
            ov.addView(bgv, FrameLayout.LayoutParams(mp, mp)); ov.addView(scrim, FrameLayout.LayoutParams(mp, mp)); ov.addView(hero, FrameLayout.LayoutParams(mp, mp))
            root.addView(ov, FrameLayout.LayoutParams(mp, mp))
            val a = ValueAnimator.ofFloat(1f, 0f).apply { duration = LumenMotion.exit(LumenMotion.SLOW); interpolator = LumenMotion.ACCEL }
            a.addUpdateListener { x -> val t = x.animatedValue as Float; hero.t = t; hero.invalidate(); scrim.alpha = t }
            a.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) { done() }   // the overlay stays up until the window is gone: no flash of the viewer
            })
            a.start()
            return true
        } catch (_: Throwable) { r.busy = false; return false }
    }

    /** Frees the kept snapshot when [act] (the viewer) goes away or loads another item. */
    fun release(act: Activity) {
        val r = ret ?: return
        if (r.owner !== act) return
        ret = null
        try { r.s.snap?.recycle() } catch (_: Throwable) { }
    }

    /** The hero: the thumbnail bitmap, interpolated from the centre-cropped rounded list rect to the picture fitted in the view. */
    private class HeroView(c: Context, private val s: Src) : View(c) {
        var t = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val from = RectF(); private val to = RectF(); private val crop = Rect()
        private val clip = Path(); private val dst = RectF(); private val sr = Rect()
        private fun lerp(a: Float, b: Float) = a + (b - a) * t

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val bm = s.thumb
            val loc = IntArray(2); getLocationOnScreen(loc)
            from.set(s.rect); from.offset(-loc[0].toFloat(), -loc[1].toFloat())
            // end state: the picture fitted (centre) in the whole view
            val k = Math.min(w.toFloat() / bm.width, h.toFloat() / bm.height)
            val fw = bm.width * k; val fh = bm.height * k
            to.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
            // start state: what the list showed = bitmap centre-cropped to the rect
            val dw = from.width(); val dh = from.height()
            val sc = Math.max(dw / bm.width, dh / bm.height)
            val sw = (dw / sc).toInt(); val sh = (dh / sc).toInt()
            crop.set((bm.width - sw) / 2, (bm.height - sh) / 2, (bm.width - sw) / 2 + sw, (bm.height - sh) / 2 + sh)
        }

        override fun onDraw(cv: Canvas) {
            val bm = s.thumb
            if (bm.isRecycled || from.isEmpty) return
            dst.set(lerp(from.left, to.left), lerp(from.top, to.top), lerp(from.right, to.right), lerp(from.bottom, to.bottom))
            sr.set(lerp(crop.left.toFloat(), 0f).toInt(), lerp(crop.top.toFloat(), 0f).toInt(), lerp(crop.right.toFloat(), bm.width.toFloat()).toInt(), lerp(crop.bottom.toFloat(), bm.height.toFloat()).toInt())
            val r = s.radius * (1f - t)
            clip.reset(); clip.addRoundRect(dst, r, r, Path.Direction.CW)
            cv.save(); cv.clipPath(clip); cv.drawBitmap(bm, sr, dst, p); cv.restore()
        }
    }
}
