package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
 * When it is used (the owner decision "cost on low devices"): F0 while idle and not in battery saver (the user chose Full), F1 only on a device the glass hint does not call low-end and
 * not in battery saver. F2 / F3, API < 31, hardware-accelerated off, or any exception => draw() returns false and the caller keeps the 4b tint-raised stand-in.
 * G1 uses 24 / 12 dp; floating G2 uses 32 / 16 dp. F0 also saturates the blurred native content.
 * The chrome views are invalidated by NativeList (scroll), NlChrome.set (page push) and the bar / dock enter animations: [invalidateAll].
 */
object NlBackdrop {
    const val ENABLED = true
    private const val SCALE = 0.25f
    @Volatile private var broken = false
    private val nodes = WeakHashMap<View, Any>()
    private val scrolling = WeakHashMap<View, Boolean>()
    private data class Material(val radius: Float, val saturation: Float)
    private val effectMaterial = WeakHashMap<View, Material>()
    private val clip = Path()
    private val rf = RectF()
    private val backdropLocation = IntArray(2)
    private val targetLocation = IntArray(2)
    /** Views that repaint when the backdrop moves (set by NlChrome / NlDrawer). */
    private val users = ArrayList<java.lang.ref.WeakReference<View>>()
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = releaseNode(v)
    }

    fun register(v: View) {
        users.removeAll { it.get() == null || it.get() === v }
        users.add(java.lang.ref.WeakReference(v))
        v.removeOnAttachStateChangeListener(attachListener)
        v.addOnAttachStateChangeListener(attachListener)
    }

    fun unregister(v: View) {
        users.removeAll { it.get() == null || it.get() === v }
        v.removeOnAttachStateChangeListener(attachListener)
        releaseNode(v)
    }

    private fun releaseNode(v: View) {
        val node = nodes.remove(v)
        effectMaterial.remove(v)
        if (Build.VERSION.SDK_INT >= 29) (node as? RenderNode)?.discardDisplayList()
    }

    @Volatile private var busy = false

    /** The scroll state belongs to this activity's root, not to another window. */
    fun setScrolling(view: View, active: Boolean) {
        val root = view.rootView
        val was = scrolling[root] == true
        if (active) scrolling[root] = true else scrolling.remove(root)
        if (was != active) repaint(root, force = true)
    }

    private fun repaint(root: View? = null, force: Boolean = false) {
        val it = users.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) { it.remove(); continue }
            if (root != null && v.rootView !== root) continue
            if (!v.isAttachedToWindow || !v.isShown || v.windowVisibility != View.VISIBLE) {
                releaseNode(v)
                continue
            }
            if (force || (scrolling[v.rootView] != true && wanted(v.context))) v.postInvalidateOnAnimation()
        }
    }

    fun invalidateAll(force: Boolean = false) = repaint(force = force)

    fun wanted(c: Context): Boolean {
        if (!ENABLED || broken || Build.VERSION.SDK_INT < 31 || LumenGlass.modalOpen(c)) return false
        val st = LumenGlass.step(c)
        return !LumenPrefs.saver(c) && (st == 0 || (st == 1 && !LumenPrefs.lowEnd(c)))
    }

    private fun ownsSlot(target: View): Boolean {
        var above = 0
        for (ref in users.asReversed()) {
            val view = ref.get() ?: continue
            if (!view.isShown || view.rootView !== target.rootView) continue
            if (view === target) return above < 2
            above++
        }
        return false
    }

    /**
     * Paints the blurred backdrop of [target] into [cv] (the target's own canvas, origin = the target's top-left).
     * [below] = the child of the root FrameLayout that the chrome sits in (everything before it is the backdrop). [round] = corner radius px to clip to (0 = rectangle).
     */
    fun draw(cv: Canvas, target: View, below: View?, round: Float, floating: Boolean = false): Boolean {
        if (busy || below == null || scrolling[target.rootView] == true || !cv.isHardwareAccelerated) return false
        if (!wanted(target.context) || !ownsSlot(target)) {
            releaseNode(target)
            return false
        }
        val w = target.width; val h = target.height
        if (w <= 0 || h <= 0) return false
        if (Build.VERSION.SDK_INT < 31) return false
        busy = true
        return try { drawImpl(cv, target, below, round, floating) } catch (_: Throwable) {
            broken = true
            releaseNode(target)
            false
        } finally { busy = false }
    }

    @RequiresApi(31)
    private fun drawImpl(cv: Canvas, target: View, below: View, round: Float, floating: Boolean): Boolean {
        val w = target.width; val h = target.height
        val root = below.parent as? ViewGroup ?: return false
        run {
            val sw = Math.max(1, Math.round(w * SCALE)); val sh = Math.max(1, Math.round(h * SCALE))
            root.getLocationInWindow(backdropLocation)
            target.getLocationInWindow(targetLocation)
            val ox = (targetLocation[0] - backdropLocation[0]).toFloat()
            val oy = (targetLocation[1] - backdropLocation[1]).toFloat()
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
            val full = LumenGlass.step(target.context) == 0
            val radiusDp = (if (floating) 32f else 24f) * (if (full) 1f else 0.5f)
            val radius = Math.max(1f, radiusDp * target.resources.displayMetrics.density * SCALE)
            val saturation = if (!full) 1f else if (floating) 1.45f else 1.4f
            val material = Material(radius, saturation)
            if (effectMaterial[target] != material) {
                val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                val effect = if (saturation == 1f) blur else {
                    val matrix = ColorMatrix().apply { setSaturation(saturation) }
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), blur)
                }
                node.setRenderEffect(effect)
                effectMaterial[target] = material
            }
            cv.save()
            if (round > 0f) { rf.set(0f, 0f, w.toFloat(), h.toFloat()); clip.reset(); clip.addRoundRect(rf, round, round, Path.Direction.CW); cv.clipPath(clip) }
            cv.scale(w.toFloat() / sw, h.toFloat() / sh)
            cv.drawRenderNode(node)
            cv.restore()
        }
        return true
    }
}
