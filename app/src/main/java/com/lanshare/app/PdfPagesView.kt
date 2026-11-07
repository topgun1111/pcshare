package com.lanshare.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.EdgeEffect
import android.widget.OverScroller
import com.lanshare.app.core.PdfCacheCodec
import com.lanshare.app.core.PdfPageCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF image viewer (chapter 19, Part 2). Shows the pages cached by [PdfPageCache] (p0001.webp ... + thumbnails) as one
 * continuous vertical column. No PdfRenderer anywhere: only reads image files, so it never shows white pages.
 *
 * - Layout comes from `PdfPageCache.info(dir)` (page sizes in points, written by the builder before any image).
 * - Pinch zoom (1x - 5x), double-tap zoom, pan, fling, edge glow. Page-number pill while moving.
 * - Pages are decoded on a 2-thread pool for the visible pages +-2 into a byte-capped LRU (RGB_565).
 *   Level 0 = fit width, level 1 = cached full width (only when zoomed in and the cache is wider than the view), level 2 = thumbnail.
 * - Pages not cached yet (or given up) are a dark placeholder with page number + spinner, never white.
 * - Self-contained: works on any folder that holds `meta.json` v3 + `pNNNN.webp` files. The host (Part 3) calls [open], then
 *   [pageReady] from `PdfPageCache.Listener.onPage` and [refreshInfo] when sizes / the page count may have changed.
 */
class PdfPagesView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Current page changed (0-based). Also called once after layout. */
    var onPageChanged: ((page: Int, count: Int) -> Unit)? = null
    /** Single tap (not part of a double tap). [page] = page under the finger (0-based). */
    var onTap: ((x: Float, y: Float, page: Int) -> Unit)? = null
    var onZoomChanged: ((zoom: Float) -> Unit)? = null
    /** Every scroll / fling step (host moves its fast scroller). */
    var onScrolled: (() -> Unit)? = null
    /** Space above the first page in px (the title bar). */
    var topPad = 0f
        set(v) { field = v; if (n > 0) { relayout(); invalidate() } }
    /** Texts (the app has no strings.xml; the host may set translated ones). */
    var textPreparing = "Preparing pages\u2026"
    var textFailed = "Page could not be drawn"

    private val dp = ctx.resources.displayMetrics.density
    private val gap = 6f * dp
    private val maxZ = 5f

    private var dir: File? = null
    private var n = 0
    private var pw = FloatArray(0)
    private var ph = FloatArray(0)
    private var cachedW = 0
    private var tops = FloatArray(1)      // page tops at zoom 1 (view width = page width); tops[n] = content height
    private var z = 1f
    private var offX = 0f                 // scroll offsets in zoomed pixels
    private var offY = 0f
    private var lastPage = -1
    private var lastMove = 0L
    private var lastFocus = 0L
    private var tries = 0
    private var gen = 0
    private var blocked: Set<Int> = emptySet()

    @Volatile private var winLo = 0
    @Volatile private var winHi = -1

    // ---- bitmaps
    private val cache = object : LruCache<Int, android.graphics.Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 5, 120L * 1024 * 1024).toInt()
    ) {
        // never recycle on eviction: the bitmap may be mid-draw; the GC frees it
        override fun sizeOf(key: Int, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val pending: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val miss = ConcurrentHashMap<Int, Long>()
    private var pool: ExecutorService? = null

    // ---- drawing tools
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint().apply { color = PLACE }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; textAlign = Paint.Align.CENTER; textSize = 40f * dp }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = LumenTypography.snap(15f) * dp }
    private val spin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * dp; strokeCap = Paint.Cap.ROUND }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = LumenTypography.snap(14f) * dp }
    private val dst = RectF()
    private val arc = RectF()

    // ---- touch
    private val scroller = OverScroller(ctx)
    private val edgeT = EdgeEffect(ctx)
    private val edgeB = EdgeEffect(ctx)
    private var flingVy = 0f
    private var zoomAnim: ValueAnimator? = null

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { stopMotion(); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(z * d.scaleFactor, d.focusX, d.focusY); return true }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { stopMotion(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            setOff(offX + dx, offY + dy)
            if (offY <= 0f && dy < 0f) edgeT.onPull(-dy / max(1, height))
            else if (offY >= maxY() && dy > 0f) edgeB.onPull(dy / max(1, height))
            postInvalidateOnAnimation()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            flingVy = -vy
            scroller.fling(offX.toInt(), offY.toInt(), (-vx).toInt(), (-vy).toInt(), 0, maxX().toInt(), 0, maxY().toInt())
            postInvalidateOnAnimation()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (z > 1.05f) 1f else min(2.5f, maxZ)
            val from = z
            zoomAnim?.cancel()
            zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                addUpdateListener { zoomAt(from + (target - from) * (it.animatedValue as Float), e.x, e.y) }
                start()
            }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke(e.x, e.y, if (n > 0) pageAt(offY + e.y) else 0)
            return true
        }
    })

    init { scaler.isQuickScaleEnabled = false }

    // ====================================================================== public API

    val pageCount: Int get() = n
    val currentPage: Int get() = if (n > 0) curPage() else 0
    val zoom: Float get() = z

    /** Shows the cache folder [d]. False = sizes not known yet (the view keeps retrying for ~1 minute; call [refreshInfo] to restart). */
    fun open(d: File): Boolean {
        gen++
        dir = d
        cache.evictAll(); pending.clear(); miss.clear()
        n = 0; z = 1f; offX = 0f; offY = 0f; lastPage = -1; blocked = emptySet(); tries = 0
        scroller.forceFinished(true)
        return refreshInfo()
    }

    /** Re-reads meta.json (page sizes arrive early, the page count / complete flag may change). */
    fun refreshInfo(): Boolean {
        val d = dir ?: return false
        removeCallbacks(retry)
        val i = PdfPageCache.info(d)
        val a = i?.pw; val b = i?.ph
        if (i == null || a == null || b == null) {
            if (tries++ < 120) postDelayed(retry, 500)
            invalidate()
            return false
        }
        tries = 0
        pw = a; ph = b; cachedW = i.cw
        if (i.n != n) { n = i.n; lastPage = -1 }
        relayout()
        afterMove()
        postInvalidateOnAnimation()
        return true
    }

    /** Page [pos] (0-based) was just written by the builder. Safe from any thread. */
    fun pageReady(pos: Int) {
        for (l in 0..2) miss.remove(pos * 3 + l)
        postInvalidateOnAnimation()
    }

    /** Pages the builder gave up on (`bl` in meta.json): shown as a "could not be drawn" tile instead of a spinner. */
    fun setBlocked(pages: Set<Int>) { blocked = pages; invalidate() }

    fun scrollToPage(page: Int, smooth: Boolean = false) {
        if (n == 0) return
        val p = page.coerceIn(0, n - 1)
        val ty = (tops[p] * z - gap * z).coerceIn(0f, maxY())
        if (smooth) {
            stopMotion(); flingVy = 0f
            scroller.startScroll(offX.toInt(), offY.toInt(), 0, (ty - offY).toInt(), 250)
            postInvalidateOnAnimation()
        } else { setOff(offX, ty); invalidate() }
    }

    /** 0..1 position in the whole document (for a fast scroller). */
    var scrollFraction: Float
        get() = if (maxY() > 0f) offY / maxY() else 0f
        set(v) { setOff(offX, v.coerceIn(0f, 1f) * maxY()); invalidate() }

    fun close() {
        gen++
        removeCallbacks(retry)
        dir = null; n = 0
        cache.evictAll(); pending.clear(); miss.clear()
        invalidate()
    }

    // ====================================================================== layout / geometry

    private val retry = Runnable { refreshInfo() }

    private fun pageH(i: Int) = width * ph[i] / max(pw[i], 1f)
    private fun maxX() = max(0f, width * (z - 1f))
    private fun maxY() = max(0f, tops[n] * z - height)

    private fun relayout() {
        val w = width.toFloat()
        if (w <= 0f || n == 0 || pw.size < n || ph.size < n) { tops = FloatArray(n + 1); return }
        val t = FloatArray(n + 1)
        var y = gap + topPad
        for (i in 0 until n) { t[i] = y; y += pageH(i) + gap }
        t[n] = y
        tops = t
        clampOff()
    }

    /** Page whose slot contains zoomed content coordinate [y] (gaps belong to the page above). */
    private fun pageAt(y: Float): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] * z <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun curPage() = pageAt(offY + height / 2f)

    private fun clampOff() {
        offX = offX.coerceIn(0f, maxX())
        offY = offY.coerceIn(0f, maxY())
    }

    private fun setOff(x: Float, y: Float) {
        offX = x; offY = y
        clampOff()
        afterMove()
    }

    private fun afterMove() {
        lastMove = SystemClock.uptimeMillis()
        if (n == 0) return
        onScrolled?.invoke()
        val c = curPage()
        if (c != lastPage) {
            lastPage = c
            dir?.let { PdfPageCache.focus(it, c) }
            onPageChanged?.invoke(c, n)
        }
    }

    private fun zoomAt(nz: Float, fx: Float, fy: Float) {
        val c = nz.coerceIn(1f, maxZ)
        if (c == z) return
        val r = c / z
        offX = (offX + fx) * r - fx
        offY = (offY + fy) * r - fy
        z = c
        clampOff()
        afterMove()
        onZoomChanged?.invoke(z)
        postInvalidateOnAnimation()
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        zoomAnim?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (n > 0 && ow > 0 && oh > 0 && tops.size == n + 1) {
            // keep the page / position that was in the middle of the screen (rotation, keyboard, split screen)
            val yc = offY + oh / 2f
            val p = pageAt(yc)
            val oldH = max(1f, (tops[p + 1] - tops[p] - gap) * z)
            val frac = ((yc - tops[p] * z) / oldH).coerceIn(0f, 1f)
            offX *= w.toFloat() / ow
            relayout()
            offY = tops[p] * z + frac * pageH(p) * z - h / 2f
            clampOff()
        } else relayout()
    }

    // ====================================================================== bitmaps

    /** Queue a decode of [page] at [lvl] (0 fit width, 1 cached full width, 2 thumbnail) unless it is there / queued / not on disk yet. */
    private fun want(page: Int, lvl: Int) {
        val key = page * 3 + lvl
        if (cache.get(key) != null) return
        val d = dir ?: return
        val now = SystemClock.uptimeMillis()
        val t = miss[key]
        if (t != null && now - t < 700) return
        val f = if (lvl == 2) PdfPageCache.thumbFile(d, page) else PdfPageCache.pageFile(d, page)
        if (!f.isFile) { miss[key] = now; return }
        miss.remove(key)
        if (!pending.add(key)) return
        val g = gen
        val vw = width
        val vh = max(1, (vw * ph[page] / max(pw[page], 1f)).roundToInt())
        val p = pool
        if (p == null) { pending.remove(key); return }
        try {
            p.execute {
                try {
                    if (g != gen || page < winLo || page > winHi) return@execute
                    val bm = when (lvl) {
                        0 -> PdfCacheCodec.decode(f, vw, vh, true, true)
                        1 -> PdfCacheCodec.size(f)?.let { PdfCacheCodec.decode(f, it[0], it[1], true, true) }
                        else -> PdfPageCache.readThumb(d, page)
                    }
                    if (bm != null && g == gen) { cache.put(key, bm); postInvalidateOnAnimation() }
                } catch (_: OutOfMemoryError) {
                    cache.trimToSize(cache.maxSize() / 2)
                } catch (_: Throwable) {
                } finally { pending.remove(key) }
            }
        } catch (_: Throwable) { pending.remove(key) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "pdfpages").apply { isDaemon = true } }
        if (dir != null && n == 0) { tries = 0; refreshInfo() }
    }

    override fun onDetachedFromWindow() {
        pool?.shutdownNow(); pool = null
        pending.clear()
        removeCallbacks(retry)
        stopMotion()
        super.onDetachedFromWindow()
    }

    // ====================================================================== drawing

    override fun onDraw(c: Canvas) {
        c.drawColor(BG)
        val now = SystemClock.uptimeMillis()
        var animate = false

        if (n == 0 || width == 0) {
            if (dir != null) {
                c.drawText(textPreparing, width / 2f, height / 2f + 40f * dp, msgPaint)
                drawSpinner(c, width / 2f, height / 2f, now)
                animate = true
            }
            if (animate) postInvalidateOnAnimation()
            return
        }

        val lo = pageAt(offY)
        val hi = pageAt(offY + height)
        winLo = max(0, lo - 3); winHi = min(n - 1, hi + 3)
        val wantHi = z > 1.08f && cachedW > width * 1.1f
        var waiting = false

        for (i in lo..hi) {
            val top = tops[i] * z - offY
            dst.set(-offX, top, -offX + width * z, top + pageH(i) * z)
            val k0 = cache.get(i * 3); val k1 = cache.get(i * 3 + 1); val k2 = cache.get(i * 3 + 2)
            val bm = if (wantHi) (k1 ?: k0 ?: k2) else (k0 ?: k1 ?: k2)
            if (bm != null) c.drawBitmap(bm, null, dst, paint)
            else {
                c.drawRect(dst, fill)
                val cx = dst.centerX().coerceIn(width * 0.15f, width * 0.85f)
                val cy = dst.centerY().coerceIn(dst.top + 80f * dp, max(dst.top + 80f * dp, dst.bottom - 80f * dp))
                c.drawText("${i + 1}", cx, cy - 36f * dp, numPaint)
                if (i in blocked) c.drawText(textFailed, cx, cy + 10f * dp, msgPaint)
                else { drawSpinner(c, cx, cy + 14f * dp, now); waiting = true }
            }
            // what this page still needs (cheap level first)
            if (k0 == null && !(k1 != null && wantHi)) want(i, 0)
            if (wantHi && k1 == null) want(i, 1)
            if (bm == null) want(i, 2)
        }
        for (p in max(0, lo - 2)..min(n - 1, hi + 2)) if (p < lo || p > hi) want(p, 0)

        if (waiting) {
            animate = true
            if (now - lastFocus > 1500) { lastFocus = now; dir?.let { PdfPageCache.focus(it, curPage()) } }
        }

        // edge glow
        if (!edgeT.isFinished) {
            val s = c.save(); edgeT.setSize(width, height)
            if (edgeT.draw(c)) animate = true
            c.restoreToCount(s)
        }
        if (!edgeB.isFinished) {
            val s = c.save(); c.translate(-width.toFloat(), height.toFloat()); c.rotate(180f, width.toFloat(), 0f)
            edgeB.setSize(width, height)
            if (edgeB.draw(c)) animate = true
            c.restoreToCount(s)
        }

        // page number pill (fades 0.9 s after the last move)
        val age = now - lastMove
        val a = if (age < 900) 1f else max(0f, 1f - (age - 900) / 400f)
        if (a > 0f) {
            val txt = "${curPage() + 1} / $n"
            val tw = pillText.measureText(txt)
            val padH = 14f * dp; val hh = 30f * dp
            val cx = width / 2f; val bottom = height - 28f * dp
            pill.color = ((a * 0xB0).toInt() shl 24) or 0x000000
            pillText.color = ((a * 255).toInt() shl 24) or 0xFFFFFF
            dst.set(cx - tw / 2 - padH, bottom - hh, cx + tw / 2 + padH, bottom)
            c.drawRoundRect(dst, hh / 2, hh / 2, pill)
            c.drawText(txt, cx, bottom - hh / 2 + pillText.textSize / 3f, pillText)
            animate = true
        }

        if (animate || !scroller.isFinished) postInvalidateOnAnimation()
    }

    private fun drawSpinner(c: Canvas, cx: Float, cy: Float, now: Long) {
        val r = 14f * dp
        arc.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(arc, (now / 3 % 360).toFloat(), 90f, false, spin)
    }

    // ====================================================================== touch / fling

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val before = offY
        offX = scroller.currX.toFloat(); offY = scroller.currY.toFloat()
        clampOff()
        afterMove()
        if (offY <= 0f && before > 0f && flingVy < 0f) { edgeT.onAbsorb(scroller.currVelocity.toInt()); scroller.abortAnimation() }
        else if (offY >= maxY() && before < maxY() && flingVy > 0f) { edgeB.onAbsorb(scroller.currVelocity.toInt()); scroller.abortAnimation() }
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            edgeT.onRelease(); edgeB.onRelease()
            postInvalidateOnAnimation()
        }
        return true
    }

    companion object {
        private const val BG = 0xFF2B2B2B.toInt()
        private const val PLACE = 0xFF3A3A3A.toInt()
    }
}
