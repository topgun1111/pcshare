package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Thumbnail size the cut detector expects (`cuts.py::_features`): width `width`, height `max(round(h*width/w), 8)`. */
fun cutThumbSize(frameW: Int, frameH: Int, width: Int = 64): Pair<Int, Int> =
    width to max((frameH * width.toDouble() / max(frameW, 1)).roundToInt(), 8)

private val SDIV = IntArray(256) { if (it == 0) 0 else Math.rint((255 shl 12).toDouble() / it).toInt() }
private val HDIV = IntArray(256) { if (it == 0) 0 else Math.rint((180 shl 12).toDouble() / (6.0 * it)).toInt() }

/** OpenCV 8-bit RGB -> (H 0..179, S 0..255) with its fixed-point arithmetic (exact histogram bins). */
private fun hueSat(r: Int, g: Int, b: Int): Pair<Int, Int> {
    val v = max(r, max(g, b))
    val d = v - minOf(r, g, b)
    val s = (d * SDIV[v] + 2048) shr 12
    var h = when {
        d == 0 -> 0
        v == r -> g - b
        v == g -> b - r + 2 * d
        else -> r - g + 4 * d
    }
    h = (h * HDIV[d] + 2048) shr 12
    if (h < 0) h += 180
    return h to s
}

/**
 * Port of `cuts.py::CutDetector`. Input: ARGB pixels of the frame already resized to [cutThumbSize]
 * (the app does the area/bilinear resize). A cut needs both the 2x2-grid HSV histogram distance and the mean gray
 * difference over their thresholds; `camOk == false` (flow estimate failed) relaxes both by `relax`.
 */
class CutDetector(
    val histThr: Double = 0.35,
    val diffThr: Double = 0.18,
    val relax: Double = 0.7,
) {
    private var hist: DoubleArray? = null
    private var thumb: FloatArray? = null
    private var size = 0 to 0
    var lastHist = 0.0; private set
    var lastDiff = 0.0; private set

    fun reset() { hist = null; thumb = null }

    fun step(argb: IntArray, w: Int, h: Int, camOk: Boolean? = null): Boolean {
        require(argb.size == w * h) { "pixels do not match w x h" }
        val (hs, gray) = features(argb, w, h)
        val ph = hist; val pt = thumb; val psize = size
        hist = hs; thumb = gray; size = w to h
        if (ph == null || pt == null || psize != (w to h)) { lastHist = 0.0; lastDiff = 0.0; return false }
        var dh = 0.0
        for (i in hs.indices) dh += abs(hs[i] - ph[i])
        lastHist = 0.5 * dh
        var dg = 0.0
        for (i in gray.indices) dg += abs(gray[i] - pt[i])
        lastDiff = dg / gray.size
        val k = if (camOk == false) relax else 1.0
        return lastHist >= histThr * k && lastDiff >= diffThr * k
    }

    private fun features(argb: IntArray, w: Int, h: Int): Pair<DoubleArray, FloatArray> {
        val hue = IntArray(w * h); val sat = IntArray(w * h); val gray = FloatArray(w * h)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val (hh, ss) = hueSat(r, g, b)
            hue[i] = hh; sat[i] = ss
            gray[i] = ((b * 1868 + g * 9617 + r * 4899 + 8192) shr 14) / 255f
        }
        val out = DoubleArray(4 * 32)
        var q = 0
        for ((y0, y1) in listOf(0 to h / 2, h / 2 to h)) for ((x0, x1) in listOf(0 to w / 2, w / 2 to w)) {
            val bins = DoubleArray(32)
            for (y in y0 until y1) for (x in x0 until x1) {
                bins[(hue[y * w + x] * 8 / 180) * 4 + sat[y * w + x] * 4 / 256] += 1.0
            }
            val total = max(bins.sum(), 1.0)
            for (i in 0 until 32) out[q * 32 + i] = bins[i] / total / 4.0
            q++
        }
        return out to gray
    }
}

/** Box-average downscale of ARGB pixels (stand-in for OpenCV INTER_AREA; plain bilinear aliases badly at 8x+). */
fun areaResize(argb: IntArray, w: Int, h: Int, tw: Int, th: Int): IntArray {
    require(argb.size == w * h && tw > 0 && th > 0) { "bad sizes" }
    val out = IntArray(tw * th)
    for (y in 0 until th) {
        val y0 = (y.toLong() * h / th).toInt()
        val y1 = max(y0 + 1, ((y + 1).toLong() * h / th).toInt()).coerceAtMost(h)
        for (x in 0 until tw) {
            val x0 = (x.toLong() * w / tw).toInt()
            val x1 = max(x0 + 1, ((x + 1).toLong() * w / tw).toInt()).coerceAtMost(w)
            var r = 0; var g = 0; var b = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val p = argb[yy * w + xx]
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255
            }
            val n = (y1 - y0) * (x1 - x0)
            out[y * tw + x] = (255 shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    return out
}
