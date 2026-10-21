package com.lanshare.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Print space part 6a: the pure arithmetic of the sheet renderer ([PrintSpaceRender]).
 *
 * No android.*, no org.json: compiles and runs on the JVM (tools/psrender_geom_selftest). Units are PDF points (1/72 inch),
 * origin top-left, y grows downwards, like [SheetLayout].
 */
object PsRenderGeom {
    /** Longest side of one decoded picture (px). Same limit as PdfPrint.MAXPX. */
    const val MAXPX = 3000
    const val MIN_DPI = 96
    const val DEF_DPI = 200
    const val MAX_DPI = 300

    /**
     * One picture drawn into a slot: clip to the slot box (cx, cy = its centre, bw x bh), then turn by [rot] about the centre
     * and draw the UNTURNED picture dw x dh centred there. Cell.dw / dh of the wire format (PsLayoutJson) are exactly these.
     */
    class Draw(val cx: Float, val cy: Float, val bw: Float, val bh: Float, val dw: Float, val dh: Float, val rot: Int) {
        val left: Float get() = cx - bw / 2
        val top: Float get() = cy - bh / 2
        val right: Float get() = cx + bw / 2
        val bottom: Float get() = cy + bh / 2
    }

    fun draw(box: SheetLayout.Box, dw: Double, dh: Double, rot: Int): Draw =
        Draw((box.x + box.w / 2).toFloat(), (box.y + box.h / 2).toFloat(), box.w.toFloat(), box.h.toFloat(),
            max(1.0, dw).toFloat(), max(1.0, dh).toFloat(), ((rot % 360) + 360) % 360)

    /** PDF page size in whole points (PdfDocument.PageInfo takes ints). */
    fun pageSize(sw: Double, sh: Double): Pair<Int, Int> = Pair(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1))

    /** Pixels the decoded picture should have on its LONG side to look sharp when drawn dw x dh points at [pxPerPt] pixels per point. */
    fun needPx(dw: Double, dh: Double, pxPerPt: Double, maxPx: Int = MAXPX): Int =
        (max(dw, dh) * pxPerPt).roundToInt().coerceIn(16, maxPx)

    /** Largest power-of-two inSampleSize that still leaves the decoded long side >= [need] (1 when the picture is not bigger than needed). */
    fun sample(srcW: Int, srcH: Int, need: Int): Int {
        val longest = max(srcW, srcH)
        var s = 1
        while (longest / (s * 2) >= need) s *= 2
        return s
    }

    /**
     * The PDF keeps every drawn bitmap in memory until it is written (PdfDocument), so a big job lowers its resolution
     * instead of running out of memory. [cells] = (dw, dh) in points of every picture drawn; [budget] = raw bytes we allow
     * (3 bytes per pixel, the PDF stores no alpha for opaque pictures). Returns [want] unless the estimate is over the budget,
     * then the highest dpi that fits (never below [MIN_DPI]).
     */
    fun autoDpi(want: Int, cells: List<Pair<Double, Double>>, budget: Long): Int {
        val w = want.coerceIn(MIN_DPI, MAX_DPI)
        fun bytes(dpi: Int): Double {
            val k = dpi / 72.0
            var t = 0.0
            for ((a, b) in cells) {
                // a decoded picture never exceeds MAXPX on its long side
                val pw = a * k
                val ph = b * k
                val f = min(1.0, MAXPX / max(max(pw, ph), 1.0))
                t += pw * f * ph * f * 3.0
            }
            return t
        }
        if (bytes(w) <= budget) return w
        var lo = MIN_DPI
        var hi = w
        while (lo < hi) {   // largest dpi in [lo, hi] that fits (estimate grows with dpi)
            val mid = (lo + hi + 1) / 2
            if (bytes(mid) <= budget) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * EXIF orientation (1..8) as "turn clockwise by deg, THEN mirror left-right if flip" (same table as Thumbs.render).
     * Returns [deg, flip(0|1)]. Anything unknown = no change.
     */
    fun exif(ori: Int): IntArray = when (ori) {
        2 -> intArrayOf(0, 1)
        3 -> intArrayOf(180, 0)
        4 -> intArrayOf(180, 1)
        5 -> intArrayOf(90, 1)
        6 -> intArrayOf(90, 0)
        7 -> intArrayOf(270, 1)
        8 -> intArrayOf(270, 0)
        else -> intArrayOf(0, 0)
    }

    /** Preview size: [w] px wide (clamped 120..1100), height from the sheet's proportions. */
    fun previewSize(sw: Double, sh: Double, w: Int): Pair<Int, Int> {
        val pw = w.coerceIn(120, 1100)
        return Pair(pw, max(1, Math.round(pw * sh / max(sw, 1.0)).toInt()))
    }

    /** Rough decoded size in bytes of a bitmap (ARGB_8888). */
    fun bytesOf(w: Int, h: Int): Long = w.toLong() * h.toLong() * 4L
}
