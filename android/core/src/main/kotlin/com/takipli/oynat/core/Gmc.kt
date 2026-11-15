package com.takipli.oynat.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Camera-motion step larger than this fraction of the frame size is a shot cut, not a pan (`gmc.py::MAX_SHIFT`). */
const val MAX_SHIFT = 0.5

/** 8-bit gray image, row-major, `data.size == w * h`. */
class GrayImage(val w: Int, val h: Int, val data: ByteArray) {
    init {
        require(data.size == w * h) { "gray data size mismatch" }
    }
}

/** Point pairs for one step in camera-image pixels: `cur[i]` (flat x,y) moved from `prev[i]`. Already outlier-filtered by flow checks. */
class FlowMatches(val cur: DoubleArray, val prev: DoubleArray) {
    val count: Int get() = cur.size / 2
}

/** Feature tracker (OpenCV on the phone, a fake in tests). `mask` = rectangles (x1,y1,x2,y2) excluded from feature selection. */
fun interface FlowTracker {
    fun match(prev: GrayImage, cur: GrayImage, mask: List<IntArray>): FlowMatches?
}

/** Similarity transform `dst = [[a, -b, tx], [b, a, ty]] * src` (rotation + uniform scale + shift). */
class Similarity(val a: Double, val b: Double, val tx: Double, val ty: Double, val inliers: Int) {
    fun apply(x: Double, y: Double) = doubleArrayOf(a * x - b * y + tx, b * x + a * y + ty)
}

/**
 * Equivalent of `cv2.estimateAffinePartial2D(src, dst, RANSAC, ransacReprojThreshold=2.0)`: RANSAC over
 * 2-point samples, then least squares on all inliers. Deterministic (seeded). Returns null when fewer than `minInliers`
 * points agree. `src`/`dst` are flat x,y.
 */
fun estimateSimilarity(
    src: DoubleArray,
    dst: DoubleArray,
    thresh: Double = 2.0,
    iterations: Int = 500,
    minInliers: Int = 8,
    seed: Long = 12345,
): Similarity? {
    val n = src.size / 2
    if (n < 2 || dst.size != src.size) return null
    val rng = Random(seed)
    var best: BooleanArray? = null
    var bestCount = 0
    repeat(iterations) {
        val i = rng.nextInt(n)
        var j = rng.nextInt(n - 1)
        if (j >= i) j++
        val m = fit(doubleArrayOf(src[2 * i], src[2 * i + 1], src[2 * j], src[2 * j + 1]),
            doubleArrayOf(dst[2 * i], dst[2 * i + 1], dst[2 * j], dst[2 * j + 1])) ?: return@repeat
        val inl = BooleanArray(n)
        var c = 0
        for (k in 0 until n) {
            val p = m.apply(src[2 * k], src[2 * k + 1])
            if (hypot(p[0] - dst[2 * k], p[1] - dst[2 * k + 1]) <= thresh) { inl[k] = true; c++ }
        }
        if (c > bestCount) { bestCount = c; best = inl }
    }
    val inl = best ?: return null
    if (bestCount < minInliers) return null
    val s = DoubleArray(2 * bestCount); val d = DoubleArray(2 * bestCount)
    var w = 0
    for (k in 0 until n) if (inl[k]) {
        s[2 * w] = src[2 * k]; s[2 * w + 1] = src[2 * k + 1]; d[2 * w] = dst[2 * k]; d[2 * w + 1] = dst[2 * k + 1]; w++
    }
    val m = fit(s, d) ?: return null
    return Similarity(m.a, m.b, m.tx, m.ty, bestCount)
}

/** Least-squares similarity for flat point lists (n >= 2); null if the sources are degenerate. */
private fun fit(src: DoubleArray, dst: DoubleArray): Similarity? {
    val n = src.size / 2
    var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
    for (i in 0 until n) { sx += src[2 * i]; sy += src[2 * i + 1]; dx += dst[2 * i]; dy += dst[2 * i + 1] }
    sx /= n; sy /= n; dx /= n; dy /= n
    var num1 = 0.0; var num2 = 0.0; var den = 0.0
    for (i in 0 until n) {
        val x = src[2 * i] - sx; val y = src[2 * i + 1] - sy
        val u = dst[2 * i] - dx; val v = dst[2 * i + 1] - dy
        num1 += x * u + y * v
        num2 += x * v - y * u
        den += x * x + y * y
    }
    if (den < 1e-12) return null
    val a = num1 / den; val b = num2 / den
    return Similarity(a, b, dx - (a * sx - b * sy), dy - (b * sx + a * sy), n)
}

// ---- 3x3 row-major helpers ----
fun identity3(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

fun mul3(a: DoubleArray, b: DoubleArray): DoubleArray {
    val o = DoubleArray(9)
    for (r in 0 until 3) for (c in 0 until 3) for (k in 0 until 3) o[3 * r + c] += a[3 * r + k] * b[3 * k + c]
    return o
}

fun inv3(m: DoubleArray): DoubleArray {
    val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    require(abs(det) > 1e-12) { "singular matrix" }
    return doubleArrayOf(
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
    )
}

/** Size of the small gray image the camera estimate runs on (`gmc_side` = longer side). */
fun camSize(fw: Int, fh: Int, camSide: Int = 480): Pair<Int, Int> {
    val cs = min(1.0, camSide.toDouble() / max(fw, fh))
    return max(1, (fw * cs).roundToInt()) to max(1, (fh * cs).roundToInt())
}

/**
 * Port of `gmc.py::CameraMotion`. Feed the small gray image (`camSize`) of each sampled frame; `step` returns the
 * previous-frame -> this-frame 3x3 (full-resolution pixels; identity if unknown or a cut-sized jump) and keeps
 * `W` (frame -> world). `boxes` = foreground boxes in previous-frame pixels, masked out of feature selection.
 */
class CameraMotion(private val tracker: FlowTracker, val camSide: Int = 480) {
    var fw = 0; private set
    var fh = 0; private set
    private var cs = 1.0
    private var cprev: GrayImage? = null
    var world: DoubleArray = identity3(); private set
    var last: DoubleArray = identity3(); private set
    var ok = false; private set

    fun reset() {
        cprev = null
        world = identity3(); last = identity3(); ok = false
    }

    fun step(frameW: Int, frameH: Int, cur: GrayImage, boxes: List<BBox> = emptyList()): DoubleArray {
        val prev = cprev
        if (prev == null || frameW != fw || frameH != fh) {
            fw = frameW; fh = frameH
            cs = min(1.0, camSide.toDouble() / max(fw, fh))
            cprev = cur
            world = identity3(); last = identity3(); ok = false
            return last
        }
        val mask = boxes.map {
            intArrayOf(
                max((it.x1 * cs).toInt(), 0), max((it.y1 * cs).toInt(), 0),
                max((it.x2 * cs).toInt() + 1, 0), max((it.y2 * cs).toInt() + 1, 0),
            )
        }
        cprev = cur
        val m = tracker.match(prev, cur, mask)
        val sim = m?.let { estimateSimilarity(it.cur, it.prev) }
        ok = sim != null
        if (sim == null) { last = identity3(); return last }
        val s = doubleArrayOf(cs, 0.0, 0.0, 0.0, cs, 0.0, 0.0, 0.0, 1.0)
        val a3 = doubleArrayOf(sim.a, -sim.b, sim.tx, sim.b, sim.a, sim.ty, 0.0, 0.0, 1.0)
        val back = mul3(mul3(inv3(s), a3), s) // frame t -> frame t-1 (full resolution)
        val fwd = inv3(back) // frame t-1 -> frame t
        if (abs(fwd[2]) > MAX_SHIFT * fw || abs(fwd[5]) > MAX_SHIFT * fh) {
            ok = false; last = identity3(); return last
        }
        world = mul3(world, back)
        last = fwd
        return fwd
    }

    fun toWorld(x: Double, y: Double): Pair<Double, Double> {
        val w = world
        return (w[0] * x + w[1] * y + w[2]) to (w[3] * x + w[4] * y + w[5])
    }

    fun toFrame(wx: Double, wy: Double): Pair<Double, Double> {
        val i = inv3(world)
        return (i[0] * wx + i[1] * wy + i[2]) to (i[3] * wx + i[4] * wy + i[5])
    }
}
