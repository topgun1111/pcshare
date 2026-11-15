package com.takipli.oynat.core

import kotlin.math.max

// ---- Part 10b: per-keypoint One-Euro and off-screen propagation. Port of `tracker/offscreen.py`. ----

const val VEL_EMA = 0.3 // same velocity smoothing as the live tracker (`KeypointTracker._set`)
const val VEL_DAMP = 0.9 // while the part is lost the velocity is multiplied by this every step

private class JointFilter(val fx: OneEuro, val fy: OneEuro, var last: Double)

/**
 * `offscreen.py::smooth_keypoints`: per sample `{track id: 51-number row}` for the target IDs. Confident joints
 * (`kpMinConf`) go through One-Euro (coordinates divided by the person height in pixels); others stay as they are.
 * The filter restarts on a cut and after a joint gap longer than `kpHoldS`.
 */
fun smoothKeypoints(doc: TrackDoc, ids: Set<Int>, cfg: Config): List<Map<String, List<Double>>> {
    val (w, h) = videoSize(doc)
    val state = HashMap<Int, JointFilter>()
    val out = ArrayList<Map<String, List<Double>>>(doc.samples.size)
    for (s in doc.samples) {
        if ((s.cut ?: 0) != 0) state.clear()
        val t = s.t
        val rows = LinkedHashMap<String, List<Double>>()
        for (d in s.d) {
            val pid = d[0].toInt()
            val row = s.kp?.get(pid.toString())
            if (pid !in ids || row == null || row.isEmpty()) continue
            val ph = max((d[4] - d[2]) * h, 1.0)
            val next = row.toMutableList()
            for (j in 0 until row.size / 3) {
                if (row[3 * j + 2] < cfg.kpMinConf) continue
                val key = pid * 1000 + j
                var st = state[key]
                if (st == null || t - st.last > cfg.kpHoldS) {
                    st = JointFilter(OneEuro(), OneEuro(), t)
                    state[key] = st
                }
                st.last = t
                next[3 * j] = st.fx.update(row[3 * j] * w / ph, t) * ph / w
                next[3 * j + 1] = st.fy.update(row[3 * j + 1] * h / ph, t) * ph / h
            }
            rows[pid.toString()] = next
        }
        out.add(rows)
    }
    return out
}

/** Background similarity previous -> this sample (frame-height units); identity without `cam`. */
private fun applyCam(cam: List<Double>?, x: Double, y: Double): Pair<Double, Double> =
    if (cam == null || cam.size < 4) x to y
    else (cam[0] * x - cam[1] * y + cam[2]) to (cam[1] * x + cam[0] * y + cam[3])

/** Linear part of the similarity only (for velocity vectors). */
private fun rotCam(cam: List<Double>?, x: Double, y: Double): Pair<Double, Double> =
    if (cam == null || cam.size < 4) x to y
    else (cam[0] * x - cam[1] * y) to (cam[1] * x + cam[0] * y)

/**
 * `offscreen.py::propagate_boxes`: samples with no box (person lost or outside the frame) are filled from the last box,
 * forward only and for at most `kpHoldS` seconds. Position is kept in frame-height units; per step the velocity is
 * damped, the position advances, then the sample's `cam` carries it into the new frame (a part that stands still in
 * the world slides with the background); the velocity is the `VEL_EMA` average of the world displacement over the
 * consecutive visible samples before the gap. Stops at a cut. A gap between two visible boxes that
 * `viewsFromBoxes` fills linearly (<= `offlineInterpS`, no cut) is left alone. Propagated boxes keep the last size and
 * may lie outside the frame (`computeView` clamps the pan); `lostHoldS` + zoom-out take over for longer losses.
 */
fun propagateBoxes(doc: TrackDoc, boxes: Map<Int, DoubleArray>, cfg: Config): Map<Int, DoubleArray> {
    val samples = doc.samples
    val n = samples.size
    val (w, h) = videoSize(doc)
    val asp = w.toDouble() / h
    val ts = samples.map { it.t }
    val cuts = cutIndices(doc)
    val nxt = arrayOfNulls<Int>(n) // first visible sample AFTER i
    var nx: Int? = null
    for (i in n - 1 downTo 0) {
        nxt[i] = nx
        if (i in boxes) nx = i
    }
    val out = HashMap<Int, DoubleArray>()
    for ((k, v) in boxes) out[k] = v.copyOf()
    var pos: DoubleArray? = null
    var vx = 0.0
    var vy = 0.0
    var sw = 0.0
    var sh = 0.0
    var last = -1
    for (i in 0 until n) {
        val cam = samples[i].cam
        if (i in cuts) { pos = null; vx = 0.0; vy = 0.0 }
        val b = boxes[i]
        if (b != null) {
            val cx = b[0] * asp
            val cy = b[1]
            val p = pos
            if (p != null && last == i - 1) {
                val (mx, my) = applyCam(cam, p[0], p[1])
                val (rx, ry) = rotCam(cam, vx, vy)
                vx = rx + VEL_EMA * ((cx - mx) - rx)
                vy = ry + VEL_EMA * ((cy - my) - ry)
            } else {
                vx = 0.0; vy = 0.0
            }
            pos = doubleArrayOf(cx, cy); sw = b[2]; sh = b[3]; last = i
            continue
        }
        val p = pos
        if (p == null || last < 0 || ts[i] - ts[last] > cfg.kpHoldS) { pos = null; continue }
        val b2 = nxt[i]
        if (b2 != null && ts[b2] - ts[last] <= cfg.offlineInterpS && cuts.none { it > last && it <= b2 }) continue
        vx *= VEL_DAMP; vy *= VEL_DAMP
        val (px, py) = applyCam(cam, p[0] + vx, p[1] + vy)
        pos = doubleArrayOf(px, py)
        val (rx, ry) = rotCam(cam, vx, vy)
        vx = rx; vy = ry
        out[i] = doubleArrayOf(px / asp, py, sw, sh)
    }
    return out
}
