package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import java.util.WeakHashMap

/**
 * Lumen Part 4f (N1 c): real backdrop blur for the in-window chrome (top / selection bar, dock, drawer panel).
 *
 * A native view cannot blur what its siblings draw (blur-behind belongs to a Window), so the chrome view paints the blur itself:
 * every sibling BELOW it in the root FrameLayout (page WebView, native list overlay, search box, and for the drawer also the chrome layer)
 * is re-recorded into a small RenderNode (1/4 scale, only the rect under the chrome view), the node gets a RenderEffect blur, and the node is drawn
 * scaled back up in the chrome view's own onDraw. GPU only, no bitmap read-back. API 31+.
 *
 * When it is used (the owner decision "cost on low devices"): F0 always (the user chose Full), F1 only on a device the glass hint does not call low-end and
 * not in battery saver. F2 / F3, API < 31, hardware-accelerated off, or any exception => draw() returns false and the caller keeps the 4b tint-raised stand-in.
 * Radius F0 24 dp, F1 12 dp (plan 4b). Kill switch: ENABLED.
 * The chrome views are invalidated by NativeList (scroll), NlChrome.set (page push) and the bar / dock enter animations: [invalidateAll].
 */
object NlBackdrop {
    const val ENABLED = true
    private const val SCALE = 0.25f
    @Volatile private var broken = false
    private val nodes = WeakHashMap<View, Any>()
    private val clip = Path()
    private val rf = RectF()
    /** Views that repaint when the backdrop moves (set by NlChrome / NlDrawer). */
    private val users = ArrayList<java.lang.ref.WeakReference<View>>()

    fun register(v: View) { users.add(java.lang.ref.WeakReference(v)) }
    fun invalidateAll() {
        val it = users.iterator()
        while (it.hasNext()) { val v = it.next().get(); if (v == null) it.remove() else if (v.visibility == View.VISIBLE && v.isAttachedToWindow) v.invalidate() }
    }

    fun wanted(c: Context): Boolean {
        if (!ENABLED || broken || Build.VERSION.SDK_INT < 31) return false
        val st = LumenGlass.step(c)
        return st == 0 || (st == 1 && !LumenPrefs.lowEnd(c) && !LumenPrefs.saver(c))
    }

    fun radiusDp(c: Context): Float = if (LumenGlass.step(c) == 0) 24f else 12f

    /**
     * Paints the blurred backdrop of [target] into [cv] (the target's own canvas, origin = the target's top-left).
     * [below] = the child of the root FrameLayout that the chrome sits in (everything before it is the backdrop). [round] = corner radius px to clip to (0 = rectangle).
     */
    fun draw(cv: Canvas, target: View, below: View?, round: Float): Boolean {
        if (below == null || !wanted(target.context) || !cv.isHardwareAccelerated) return false
        val w = target.width; val h = target.height
        if (w <= 0 || h <= 0) return false
        if (Build.VERSION.SDK_INT < 31) return false
        return try { drawImpl(cv, target, below, round) } catch (_: Throwable) { broken = true; false }
    }

    @RequiresApi(31)
    private fun drawImpl(cv: Canvas, target: View, below: View, round: Float): Boolean {
        val w = target.width; val h = target.height
        val root = below.parent as? ViewGroup ?: return false
        run {
            val sw = Math.max(1, Math.round(w * SCALE)); val sh = Math.max(1, Math.round(h * SCALE))
            val a = IntArray(2); val b = IntArray(2)
            root.getLocationInWindow(a); target.getLocationInWindow(b)
            val ox = (b[0] - a[0]).toFloat(); val oy = (b[1] - a[1]).toFloat()
            val node = nodes.getOrPut(target) { RenderNode("lmblur") } as RenderNode
            node.setPosition(0, 0, sw, sh)
            val rc = node.beginRecording(sw, sh)
            try {
                rc.scale(sw.toFloat() / w, sh.toFloat() / h)
                rc.drawColor(Lumen.of(target.context).canvas or 0xFF000000.toInt())   // always an opaque base: the blur never flips between "live" and "see-through" while the list overlay hides / shows during swipes
                rc.translate(-ox, -oy)
                val end = root.indexOfChild(below)
                for (i in 0 until end) {
                    val ch = root.getChildAt(i)
                    if (ch.visibility != View.VISIBLE || ch is android.webkit.WebView) continue   // a WebView drawn into a second recording can come out blank for a frame = flicker; the page area blurs as the plain canvas colour
                    rc.save(); rc.translate(ch.left.toFloat(), ch.top.toFloat()); ch.draw(rc); rc.restore()
                }
            } finally { node.endRecording() }
            val r = Math.max(1f, radiusDp(target.context) * target.resources.displayMetrics.density * SCALE)
            node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            cv.save()
            if (round > 0f) { rf.set(0f, 0f, w.toFloat(), h.toFloat()); clip.reset(); clip.addRoundRect(rf, round, round, Path.Direction.CW); cv.clipPath(clip) }
            cv.scale(w.toFloat() / sw, h.toFloat() / sh)
            cv.drawRenderNode(node)
            cv.restore()
        }
        return true
    }
}
