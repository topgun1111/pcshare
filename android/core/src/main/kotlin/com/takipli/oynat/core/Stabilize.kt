package com.takipli.oynat.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---- EIS-style stabilization of the follow-cam export. Pure Kotlin (JVM unit tests); OpenCV and the decoder live in the app. ----
//
// Idea: the old export cropped in FRAME coordinates from views sampled at the scan fps (5..20), so shake and roll of the
// source went straight into the output. Here every source frame gets its own camera pose (CamPath, estimated on every
// frame), the crop is kept in WORLD coordinates (camera motion removed), smoothed there, and mapped back into each frame
// with that frame's own pose. Shake and short-term roll cancel; the target is still followed.

/**
 * Camera pose of every source frame. `frame -> world` is the similarity `[[a, -b, tx], [b, a, ty]]` (world = the first
 * frame of the shot; `CameraMotion.world` convention). `tx`/`ty` are divided by the frame height, so the file is
 * independent of the resolution the analysis ran at. `shot` increments at every cut (the world restarts there).
 */
@Serializable
class CamPath(
    val sig: String,
    val t: DoubleArray,
    val shot: IntArray,
    val a: DoubleArray,
    val b: DoubleArray,
    val tx: DoubleArray,
    val ty: DoubleArray,
) {
    val size: Int get() = t.size

    /** Largest index k with `t[k] <= time` (0 when before the first frame). */
    fun indexAt(time: Double): Int {
        if (t.isEmpty() || time <= t[0]) return 0
        var lo = 0; var hi = t.size - 1
        if (time >= t[hi]) return hi
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= time) lo = mid else hi = mid }
        return lo
    }

    /** `frame -> world` 3x3 (row-major) of frame k in pixels of a frame that is `frameH` high. */
    fun worldMatrix(k: Int, frameH: Double): DoubleArray =
        doubleArrayOf(a[k], -b[k], tx[k] * frameH, b[k], a[k], ty[k] * frameH, 0.0, 0.0, 1.0)

    /** Same, interpolated (log-scale, angle, shift) between frames k and k+1 of the same shot. */
    fun worldAt(time: Double, frameH: Double): DoubleArray {
        val k = indexAt(time)
        if (k >= t.size - 1 || shot[k] != shot[k + 1] || t[k + 1] <= t[k] || time <= t[k]) return worldMatrix(k, frameH)
        val f = ((time - t[k]) / (t[k + 1] - t[k])).coerceIn(0.0, 1.0)
        val s0 = ln(hypot2(a[k], b[k])); val s1 = ln(hypot2(a[k + 1], b[k + 1]))
        var d = atan2(b[k + 1], a[k + 1]) - atan2(b[k], a[k])
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        val sc = exp(s0 + (s1 - s0) * f); val th = atan2(b[k], a[k]) + d * f
        val x = tx[k] + (tx[k + 1] - tx[k]) * f; val y = ty[k] + (ty[k + 1] - ty[k]) * f
        return doubleArrayOf(sc * cos(th), -sc * sin(th), x * frameH, sc * sin(th), sc * cos(th), y * frameH, 0.0, 0.0, 1.0)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(p: CamPath): String = json.encodeToString(serializer(), p)
        fun decode(text: String): CamPath? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.t.size == it.shot.size && it.t.size == it.a.size && it.t.size == it.b.size && it.t.size == it.tx.size && it.t.size == it.ty.size }
    }
}

private fun hypot2(x: Double, y: Double) = sqrt(x * x + y * y)

/**
 * Builds a [CamPath] frame by frame. `step` = previous frame -> this frame 3x3 in analysis-frame pixels (what
 * `CameraMotion.step` returns), null when unknown (the pose is held). `cut` starts a new shot with a fresh world.
 */
class CamPathBuilder(private val frameH: Int) {
    private val ts = ArrayList<Double>(); private val shots = ArrayList<Int>()
    private val aa = ArrayList<Double>(); private val bb = ArrayList<Double>()
    private val txs = ArrayList<Double>(); private val tys = ArrayList<Double>()
    private var world = identity3()
    private var shot = 0

    fun add(t: Double, step: DoubleArray?, cut: Boolean) {
        if (ts.isNotEmpty()) {
            if (cut) { world = identity3(); shot++ }
            else if (step != null && step.size >= 9 && abs(step[0] * step[4] - step[1] * step[3]) > 1e-9) world = mul3(world, inv3(step))
        }
        ts.add(t); shots.add(shot)
        aa.add(world[0]); bb.add(world[3]); txs.add(world[2] / frameH); tys.add(world[5] / frameH)
    }

    val count: Int get() = ts.size

    fun build(sig: String) = CamPath(sig, ts.toDoubleArray(), shots.toIntArray(), aa.toDoubleArray(), bb.toDoubleArray(), txs.toDoubleArray(), tys.toDoubleArray())
}

/** True when a camera step is believable for consecutive video frames (scale and roll change a little per frame). */
fun plausibleCameraStep(step: DoubleArray): Boolean {
    val sc = sqrt(abs(step[0] * step[4] - step[1] * step[3]))
    val rot = abs(atan2(step[3], step[0]))
    return sc in 0.85..1.18 && rot < 0.35
}

/** Knobs of the stabilizer. Seconds are real time, so the result does not depend on the video's frame rate. */
class StabConfig(
    /** Zero-phase smoothing of the world-space crop (centre and size) on top of the view smoothing. */
    val smoothS: Double = 0.12,
    /** Roll: the crop follows the camera's roll low-passed with this time constant; faster roll is removed. Also cancels drift. */
    val rollS: Double = 0.40,
    /** The zoom needed to hide the correction is looked ahead/behind this long, then smoothed (no zoom pumping). */
    val floorWindowS: Double = 0.6,
    val floorSmoothS: Double = 0.25,
    /** Upper limit of the extra zoom the stabilizer may add; beyond it the crop slides to stay inside the frame. */
    val maxBoost: Double = 3.0,
)

/** 3x3 (row-major) for Media3: input NDC (x right, y UP, -1..1) -> output NDC. See [cropMatrixNdc]. */
fun cropMatrixNdc(cx: Double, cy: Double, z: Double, phi: Double, fw: Double, fh: Double): DoubleArray {
    // pixels (y down): out = z * R(-phi) * (p - c) + mid, then N * P * N^-1 with N: pixel -> NDC.
    val c = cos(-phi); val s = sin(-phi)
    val p = doubleArrayOf(
        z * c, -z * s, fw / 2 - z * (c * cx - s * cy),
        z * s, z * c, fh / 2 - z * (s * cx + c * cy),
        0.0, 0.0, 1.0,
    )
    val n = doubleArrayOf(2 / fw, 0.0, -1.0, 0.0, -2 / fh, 1.0, 0.0, 0.0, 1.0)
    val ni = doubleArrayOf(fw / 2, 0.0, fw / 2, 0.0, -fh / 2, fh / 2, 0.0, 0.0, 1.0)
    return mul3(mul3(n, p), ni)
}

/** Zoom (>= 1) at which a crop rotated by `phi`, centred `dx`,`dy` px off the frame centre, still fits inside the frame. */
fun zoomToFit(dx: Double, dy: Double, phi: Double, fw: Double, fh: Double): Double {
    val c = abs(cos(phi)); val s = abs(sin(phi))
    val zx = (fw * c + fh * s) / max(fw - 2 * abs(dx), 1e-3 * fw)
    val zy = (fw * s + fh * c) / max(fh - 2 * abs(dy), 1e-3 * fh)
    return max(1.0, max(zx, zy))
}

/** Zero-phase (forward + backward) exponential smoothing with time constant `tau` on samples at times `ts`. */
fun smoothTimed(v: DoubleArray, ts: DoubleArray, from: Int, to: Int, tau: Double) {
    if (tau <= 0.0 || to - from < 2) return
    for (i in from + 1 until to) { val al = 1 - exp(-(ts[i] - ts[i - 1]) / tau); v[i] = v[i - 1] + al * (v[i] - v[i - 1]) }
    for (i in to - 2 downTo from) { val al = 1 - exp(-(ts[i + 1] - ts[i]) / tau); v[i] = v[i + 1] + al * (v[i] - v[i + 1]) }
}

/**
 * The stabilized crop of every frame of `path`, built from the follow-cam `views` (any rate) and the camera poses.
 * Output size = source size `fw` x `fh` (native resolution). Frames of a shot without view keys keep the plain view.
 */
class StabPlan(val path: CamPath, views: List<ViewKey>, val fw: Int, val fh: Int, private val cfg: StabConfig = StabConfig()) {
    private val n = path.size
    private val cx = DoubleArray(n) { fw / 2.0 }
    private val cy = DoubleArray(n) { fh / 2.0 }
    private val lz = DoubleArray(n) // ln(zoom)
    private val ph = DoubleArray(n) // crop rotation inside the frame (rad)

    /** Frames whose crop had to slide to stay inside the frame (the zoom boost was not enough). */
    var slidFrames = 0; private set

    /** Largest zoom the stabilizer added on top of the follow-cam zoom, as a factor. */
    var peakBoost = 1.0; private set

    init {
        var s0 = 0
        while (s0 < n) {
            var s1 = s0 + 1
            while (s1 < n && path.shot[s1] == path.shot[s0]) s1++
            buildShot(views, s0, s1, if (s0 == 0) Double.NEGATIVE_INFINITY else path.t[s0], if (s1 >= n) Double.POSITIVE_INFINITY else path.t[s1])
            s0 = s1
        }
    }

    private fun plainView(views: List<ViewKey>, k: Int) {
        val tr = viewTransform(viewAtSmooth(views, path.t[k]))
        cx[k] = (0.5 - tr[1] / tr[0]) * fw; cy[k] = (0.5 - tr[2] / tr[0]) * fh; lz[k] = ln(tr[0]); ph[k] = 0.0
    }

    private fun buildShot(views: List<ViewKey>, s0: Int, s1: Int, tStart: Double, tEnd: Double) {
        val keys = views.filter { it[0] >= tStart && it[0] < tEnd }
        if (keys.isEmpty()) { for (k in s0 until s1) plainView(views, k); return }
        val fwD = fw.toDouble(); val fhD = fh.toDouble()
        // 1) every view key -> world: centre (x, y) and ln(width); width = frame width * scale / zoom.
        val wk = keys.map { key ->
            val tr = viewTransform(View(key[1], key[2], key[3]))
            val w = path.worldAt(key[0].coerceIn(path.t[s0], path.t[s1 - 1]), fhD)
            val px = (0.5 - tr[1] / tr[0]) * fwD; val py = (0.5 - tr[2] / tr[0]) * fhD
            val sc = sqrt(abs(w[0] * w[4] - w[1] * w[3]))
            listOf(key[0], w[0] * px + w[1] * py + w[2], w[3] * px + w[4] * py + w[5], ln(sc * fwD / tr[0]))
        }
        // 2) world-space keys -> every frame (C1 cubic, no overshoot), then a light zero-phase smoothing.
        val m = s1 - s0
        val ts = DoubleArray(m) { path.t[s0 + it] }
        val wx = DoubleArray(m); val wy = DoubleArray(m); val wl = DoubleArray(m)
        for (i in 0 until m) { val v = viewAtSmooth(wk, ts[i]); wx[i] = v.zoom; wy[i] = v.panX; wl[i] = v.panY }
        smoothTimed(wx, ts, 0, m, cfg.smoothS); smoothTimed(wy, ts, 0, m, cfg.smoothS); smoothTimed(wl, ts, 0, m, cfg.smoothS)
        // 3) camera roll of this shot, unwrapped, low-passed: the crop follows the slow part and removes the fast part.
        val th = DoubleArray(m)
        for (i in 0 until m) {
            var a = atan2(path.b[s0 + i], path.a[s0 + i])
            if (i > 0) { while (a - th[i - 1] > PI) a -= 2 * PI; while (a - th[i - 1] < -PI) a += 2 * PI }
            th[i] = a
        }
        val ths = th.copyOf(); smoothTimed(ths, ts, 0, m, cfg.rollS)
        // 4) back into every frame with that frame's own pose.
        val req = DoubleArray(m); val z0 = DoubleArray(m); val px = DoubleArray(m); val py = DoubleArray(m); val rot = DoubleArray(m)
        for (i in 0 until m) {
            val k = s0 + i
            val a = path.a[k]; val b = path.b[k]; val tx = path.tx[k] * fhD; val ty = path.ty[k] * fhD
            val d = a * a + b * b
            val ux = wx[i] - tx; val uy = wy[i] - ty
            px[i] = (a * ux + b * uy) / d; py[i] = (-b * ux + a * uy) / d // W^-1 (world centre)
            z0[i] = max(fwD * sqrt(d) / exp(wl[i]), 1.0)
            rot[i] = ths[i] - th[i]
            req[i] = min(zoomToFit(px[i] - fwD / 2, py[i] - fhD / 2, rot[i], fwD, fhD), cfg.maxBoost)
        }
        // 5) zoom floor: dilate (look ahead/behind), then smooth, so the zoom never pumps with the shake.
        val half = cfg.floorWindowS
        val fl = DoubleArray(m)
        var lo = 0; var hi = 0
        for (i in 0 until m) {
            while (ts[i] - ts[lo] > half) lo++
            while (hi < m - 1 && ts[hi + 1] - ts[i] <= half) hi++
            var mx = 1.0
            for (j in lo..hi) if (req[j] > mx) mx = req[j]
            fl[i] = mx
        }
        smoothTimed(fl, ts, 0, m, cfg.floorSmoothS)
        for (i in 0 until m) {
            val k = s0 + i
            val z = max(z0[i], fl[i])
            val c = abs(cos(rot[i])); val s = abs(sin(rot[i]))
            val ex = (fwD * c + fhD * s) / (2 * z); val ey = (fwD * s + fhD * c) / (2 * z)
            var x = px[i]; var y = py[i]
            val nx = if (ex < fwD / 2) x.coerceIn(ex, fwD - ex) else fwD / 2
            val ny = if (ey < fhD / 2) y.coerceIn(ey, fhD - ey) else fhD / 2
            if (nx != x || ny != y) slidFrames++
            x = nx; y = ny
            cx[k] = x; cy[k] = y; lz[k] = ln(z); ph[k] = rot[i]
            if (z / z0[i] > peakBoost) peakBoost = z / z0[i]
        }
    }

    /** Crop of the source frame at `timeS`: `[cx, cy, zoom, phi]` (pixels of the fw x fh frame, rotation in radians). */
    fun paramsAt(timeS: Double): DoubleArray {
        if (n == 0) return doubleArrayOf(fw / 2.0, fh / 2.0, 1.0, 0.0)
        val k = path.indexAt(timeS)
        if (k >= n - 1 || path.shot[k] != path.shot[k + 1] || path.t[k + 1] <= path.t[k] || timeS <= path.t[k])
            return doubleArrayOf(cx[k], cy[k], exp(lz[k]), ph[k])
        val f = ((timeS - path.t[k]) / (path.t[k + 1] - path.t[k])).coerceIn(0.0, 1.0)
        fun l(x: DoubleArray) = x[k] + (x[k + 1] - x[k]) * f
        return doubleArrayOf(l(cx), l(cy), exp(l(lz)), l(ph))
    }

    /** The Media3 matrix of the frame with presentation time `timeUs` (row-major 3x3). */
    fun ndcMatrix(timeUs: Long): DoubleArray {
        val p = paramsAt(timeUs / 1e6)
        return cropMatrixNdc(p[0], p[1], p[2], p[3], fw.toDouble(), fh.toDouble())
    }
}
