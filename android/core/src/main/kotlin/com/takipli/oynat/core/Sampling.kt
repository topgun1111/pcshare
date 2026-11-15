package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.min

private const val PTS_EPS = 1e-3 // 1 ms: float noise and container rounding

/**
 * Picks which decoded frames to scan so samples land on a fixed grid `startS + k / fps`.
 * Frames arrive in presentation order with their real timestamps (works for variable frame rate);
 * the first frame at or after each grid time is taken, so a sample is at most one source frame late and
 * its reported `t` is always the frame's real timestamp. Resume = a new selector with `startS` = last sample time
 * (+ one step): frames before it are rejected, the grid stays aligned to `startS`.
 */
class SampleSelector(val fps: Double, val startS: Double = 0.0, val endS: Double = Double.POSITIVE_INFINITY) {
    init {
        require(fps > 0.0) { "fps must be > 0" }
    }

    private val step = 1.0 / fps
    private var k = 0L
    var accepted = 0
        private set

    /** True once `ptsS` is beyond the end time: the decode loop can stop. */
    fun isPastEnd(ptsS: Double): Boolean = ptsS > endS + PTS_EPS

    fun accept(ptsS: Double): Boolean {
        if (isPastEnd(ptsS)) return false
        if (ptsS < startS + k * step - PTS_EPS) return false // before the next grid time (or before the resume point)
        while (startS + k * step <= ptsS + PTS_EPS) k++
        accepted++
        return true
    }
}

/** Rotation metadata normalised to 0/90/180/270. */
fun normalizeRotation(deg: Int): Int = (((Math.round(deg / 90.0).toInt() * 90) % 360) + 360) % 360

/** Display size after applying the rotation metadata (coded size w x h). */
fun displaySize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
    if (normalizeRotation(rotation) % 180 == 90) h to w else w to h

/** Output size so that the longer side is at most `maxSide` (never upscales; both sides >= 2). */
fun scaledSize(w: Int, h: Int, maxSide: Int): Pair<Int, Int> {
    val s = min(1.0, maxSide.toDouble() / max(w, h))
    return max(2, Math.round(w * s).toInt()) to max(2, Math.round(h * s).toInt())
}

/** BT.709 limited-range YUV (8 bit) -> opaque ARGB int. */
fun yuv709ToArgb(y: Int, u: Int, v: Int): Int {
    val c = max(y - 16, 0) * 1192
    val d = u - 128
    val e = v - 128
    val r = ((c + 1836 * e + 512) shr 10).coerceIn(0, 255)
    val g = ((c - 218 * d - 546 * e + 512) shr 10).coerceIn(0, 255)
    val b = ((c + 2163 * d + 512) shr 10).coerceIn(0, 255)
    return (255 shl 24) or (r shl 16) or (g shl 8) or b
}
