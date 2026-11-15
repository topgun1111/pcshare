package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private typealias Mat = Array<DoubleArray>

private fun eye(n: Int): Mat = Array(n) { i -> DoubleArray(n).also { it[i] = 1.0 } }

private fun diagSq(v: DoubleArray): Mat = Array(v.size) { i -> DoubleArray(v.size).also { it[i] = v[i] * v[i] } }

private fun transpose(a: Mat): Mat = Array(a[0].size) { j -> DoubleArray(a.size) { i -> a[i][j] } }

private fun mul(a: Mat, b: Mat): Mat {
    val out = Array(a.size) { DoubleArray(b[0].size) }
    for (i in a.indices) for (k in b.indices) {
        val aik = a[i][k]
        for (j in b[0].indices) out[i][j] += aik * b[k][j]
    }
    return out
}

private fun mulVec(a: Mat, v: DoubleArray) = DoubleArray(a.size) { i -> a[i].indices.sumOf { a[i][it] * v[it] } }

private fun add(a: Mat, b: Mat): Mat = Array(a.size) { i -> DoubleArray(a[0].size) { j -> a[i][j] + b[i][j] } }

/** Gauss-Jordan inverse with partial pivoting (only used for the 4x4 innovation matrix). */
private fun inverse(a: Mat): Mat {
    val n = a.size
    val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
    for (col in 0 until n) {
        var piv = col
        for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
        require(abs(m[piv][col]) > 1e-300) { "singular matrix" }
        val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
        val d = m[col][col]
        for (j in 0 until 2 * n) m[col][j] /= d
        for (r in 0 until n) {
            if (r == col) continue
            val f = m[r][col]
            if (f != 0.0) for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
        }
    }
    return Array(n) { i -> DoubleArray(n) { j -> m[i][n + j] } }
}

/** Port of `bytetrack.py::KalmanBox`: constant velocity, state `[cx, cy, a, h, v...]`, a = w/h. */
class KalmanBox(bbox: BBox) {
    var x: DoubleArray
        private set
    var p: Array<DoubleArray>
        private set
    private val f: Mat = eye(8).also { m -> for (i in 0 until 4) m[i][i + 4] = 1.0 }
    private val hm: Mat = Array(4) { i -> DoubleArray(8).also { it[i] = 1.0 } }

    init {
        val h = max(bbox.h, 1.0)
        x = doubleArrayOf(bbox.cx, bbox.cy, bbox.w / h, h, 0.0, 0.0, 0.0, 0.0)
        val pos = 2 * STD_POS * h
        val vel = 10 * STD_VEL * h
        p = diagSq(doubleArrayOf(pos, pos, 1e-2, pos, vel, vel, 1e-5, vel))
    }

    fun predict() {
        val h = x[3]
        val q = doubleArrayOf(STD_POS * h, STD_POS * h, 1e-2, STD_POS * h, STD_VEL * h, STD_VEL * h, 1e-5, STD_VEL * h)
        x = mulVec(f, x)
        p = add(mul(mul(f, p), transpose(f)), diagSq(q))
        x[3] = max(x[3], 1.0)
        x[2] = max(x[2], 1e-3)
    }

    fun update(bbox: BBox) {
        val h = max(bbox.h, 1.0)
        val z = doubleArrayOf(bbox.cx, bbox.cy, bbox.w / h, h)
        val r = STD_POS * x[3]
        val s = add(mul(mul(hm, p), transpose(hm)), diagSq(doubleArrayOf(r, r, 1e-1, r)))
        val k = mul(mul(p, transpose(hm)), inverse(s))
        val hx = mulVec(hm, x)
        val innov = DoubleArray(4) { z[it] - hx[it] }
        val dx = mulVec(k, innov)
        x = DoubleArray(8) { x[it] + dx[it] }
        val ikh = add(eye(8), mul(k, hm).map { row -> DoubleArray(8) { -row[it] } }.toTypedArray())
        p = mul(ikh, p)
    }

    /** Camera motion previous -> current frame: row-major 3x3 similarity `m` (position, height, velocity). */
    fun compensate(m: DoubleArray) {
        require(m.size == 9) { "motion must be 3x3 row-major" }
        val (a, b, c, d) = doubleArrayOf(m[0], m[1], m[3], m[4])
        val x0 = x[0]; val x1 = x[1]
        x[0] = a * x0 + b * x1 + m[2]
        x[1] = c * x0 + d * x1 + m[5]
        val v0 = x[4]; val v1 = x[5]
        x[4] = a * v0 + b * v1
        x[5] = c * v0 + d * v1
        val s = sqrt(abs(a * d - b * c))
        if (s > 0) {
            x[3] *= s
            x[7] *= s
            x[3] = max(x[3], 1.0)
        }
    }

    val bbox: BBox
        get() {
            val w = x[2] * x[3]
            return BBox(x[0] - w / 2, x[1] - x[3] / 2, x[0] + w / 2, x[1] + x[3] / 2)
        }

    private companion object {
        const val STD_POS = 1.0 / 20
        const val STD_VEL = 1.0 / 160
    }
}

/** Port of `smooth.py::Kalman1D`: one axis, constant velocity, white-acceleration process noise. */
class Kalman1D(x0: Double, meas: Double, accel: Double, vStd0: Double = 0.5) {
    var p = x0
        private set
    var v = 0.0
        private set
    private val r = meas * meas
    private val q = accel * accel
    var p00 = r
        private set
    var p01 = 0.0
        private set
    var p11 = vStd0 * vStd0
        private set

    fun predict(dt: Double) {
        if (dt <= 0.0) return
        p += v * dt
        val n00 = p00 + dt * (2 * p01) + dt * dt * p11 + q * dt * dt * dt * dt / 4
        val n01 = p01 + dt * p11 + q * dt * dt * dt / 2
        p11 += q * dt * dt
        p00 = n00
        p01 = n01
    }

    fun update(z: Double) {
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - p
        p += k0 * y
        v += k1 * y
        val n00 = p00 - k0 * p00
        val n01 = p01 - k0 * p01
        val n11 = p11 - k1 * p01
        p00 = n00
        p01 = n01
        p11 = n11
    }
}

/**
 * Port of `smooth.py::BoxFilter`: Kalman over (cx, cy, w, h) with velocity lookahead. Restarts from the
 * measurement on a backward time jump or a gap longer than `maxGap` seconds.
 */
class BoxFilter(
    private val accel: Double = 0.5,
    private val meas: Double = 0.01,
    private val sizeRatio: Double = 0.2,
    private val maxGap: Double = 1.0,
) {
    private var f: List<Kalman1D>? = null
    private var lastT: Double? = null

    fun reset() {
        f = null
        lastT = null
    }

    val active: Boolean get() = f != null

    /** Centre velocity (units/s); (0, 0) before the first update. */
    val velocity: Pair<Double, Double> get() = f?.let { it[0].v to it[1].v } ?: (0.0 to 0.0)

    fun update(box: BBox, t: Double): BBox {
        val vals = doubleArrayOf(box.cx, box.cy, box.w, box.h)
        val filters = f
        val prevT = lastT
        if (filters == null || prevT == null || t < prevT - 1e-6 || t - prevT > maxGap) {
            val acc = doubleArrayOf(accel, accel, accel * sizeRatio, accel * sizeRatio)
            f = List(4) { Kalman1D(vals[it], meas, acc[it]) }
        } else {
            val dt = t - prevT
            filters.forEachIndexed { i, k ->
                k.predict(dt)
                k.update(vals[i])
            }
        }
        lastT = t
        return boxAt(0.0, 0.0)
    }

    fun lookahead(aheadS: Double, maxLead: Double): BBox {
        val filters = checkNotNull(f) { "call update before lookahead" }
        if (aheadS <= 0.0) return boxAt(0.0, 0.0)
        val dx = (filters[0].v * aheadS).coerceIn(-maxLead, maxLead)
        val dy = (filters[1].v * aheadS).coerceIn(-maxLead, maxLead)
        return boxAt(dx, dy)
    }

    private fun boxAt(dx: Double, dy: Double): BBox {
        val k = checkNotNull(f)
        val cx = k[0].p + dx
        val cy = k[1].p + dy
        val w = max(k[2].p, 0.0)
        val h = max(k[3].p, 0.0)
        return BBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
}
