package com.takipli.oynat

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Part 16b: gray frames (gw x gh) of every sample, kept in a temp file so passes can walk the video in either direction. */
class GrayStore(private val file: File, val gw: Int, val gh: Int, val n: Int, resume: Boolean = false) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private val hasFile = File(file.path + ".has")
    private val has = BooleanArray(n).also { h ->
        if (resume && hasFile.exists() && hasFile.length() == n.toLong()) hasFile.readBytes().forEachIndexed { i, b -> h[i] = b.toInt() == 1 }
    }
    val complete: Boolean get() = has.any { it }
    /** Remember which frames exist so a later run can reuse the file without decoding again. */
    fun saveIndex() { hasFile.writeBytes(ByteArray(n) { if (has[it]) 1 else 0 }) }
    fun put(i: Int, gray: ByteArray) { raf.seek(i.toLong() * gw * gh); raf.write(gray); has[i] = true }
    fun has(i: Int) = i in 0 until n && has[i]
    /** New Mat (caller releases). */
    fun get(i: Int): Mat {
        val b = ByteArray(gw * gh)
        raf.seek(i.toLong() * gw * gh); raf.readFully(b)
        return Mat(gh, gw, CvType.CV_8UC1).also { it.put(0, 0, b) }
    }
    override fun close() = raf.close()
}

/**
 * Follows one spot through the stored frames. Per step: pyramidal LK on a 5x5 cluster around the point (forward-backward
 * check, median displacement, initial flow from the camera motion + last velocity) -> NCC check against the template ->
 * template search in a window around the prediction when LK fails -> whole-frame template search when lost for a while
 * (the point left the frame and came back). Output rows are normalized `[x, y, conf]`; null = lost.
 * `cam[i]` = scan's background similarity (i-1 -> i) in frame-height units, or null.
 */
class PointTracker(private val store: GrayStore, private val cam: List<List<Double>?>) {
    private val gw = store.gw
    private val gh = store.gh
    private val tsz = 41

    private fun patch(m: Mat, x: Double, y: Double): Mat? {
        val r = Rect((x - tsz / 2).toInt(), (y - tsz / 2).toInt(), tsz, tsz)
        if (r.x < 0 || r.y < 0 || r.x + r.width > gw || r.y + r.height > gh) return null
        return Mat(m, r).clone()
    }

    private fun warp(c: List<Double>?, x: Double, y: Double, inverse: Boolean): Pair<Double, Double> {
        if (c == null || c.size < 4) return x to y
        val hx = x / gh; val hy = y / gh
        val (nx, ny) = if (!inverse) (c[0] * hx - c[1] * hy + c[2]) to (c[1] * hx + c[0] * hy + c[3]) else {
            val d = c[0] * c[0] + c[1] * c[1]
            if (d < 1e-9) return x to y
            val ux = hx - c[2]; val uy = hy - c[3]
            ((c[0] * ux + c[1] * uy) / d) to ((-c[1] * ux + c[0] * uy) / d)
        }
        return nx * gh to ny * gh
    }

    /** NCC of `t` against the same-size patch of `m` at (x, y); -1 when the patch does not fit. */
    private fun ncc(m: Mat, t: Mat, x: Double, y: Double): Double {
        val p = patch(m, x, y) ?: return -1.0
        val r = Mat()
        try { Imgproc.matchTemplate(p, t, r, Imgproc.TM_CCOEFF_NORMED); val v = r.get(0, 0)[0]; return if (v.isNaN()) -1.0 else v } finally { p.release(); r.release() }
    }

    /** Best template match inside rect (clipped to the frame): (x, y, score) of the patch centre. */
    private fun search(m: Mat, t: Mat, cx: Double, cy: Double, half: Double): Triple<Double, Double, Double>? {
        val x1 = max(0, (cx - half).toInt()); val y1 = max(0, (cy - half).toInt())
        val x2 = min(gw, (cx + half).toInt()); val y2 = min(gh, (cy + half).toInt())
        if (x2 - x1 < tsz + 2 || y2 - y1 < tsz + 2) return null
        val win = Mat(m, Rect(x1, y1, x2 - x1, y2 - y1)); val r = Mat()
        try {
            Imgproc.matchTemplate(win, t, r, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(r)
            return Triple(x1 + mm.maxLoc.x + tsz / 2.0, y1 + mm.maxLoc.y + tsz / 2.0, mm.maxVal)
        } finally { r.release() }
    }

    /** Median LK displacement of a 5x5 cluster; null when fewer than 8 points survive the forward-backward check. */
    private fun lk(prev: Mat, cur: Mat, x: Double, y: Double, gx: Double, gy: Double): Pair<DoubleArray, Double>? {
        val offs = ArrayList<Point>()
        for (j in -2..2) for (i in -2..2) offs.add(Point(x + i * 5.0, y + j * 5.0))
        val p0 = MatOfPoint2f(*offs.toTypedArray())
        val p1 = MatOfPoint2f(*offs.map { Point(it.x + gx, it.y + gy) }.toTypedArray())
        val back = MatOfPoint2f(*offs.toTypedArray())
        val st = MatOfByte(); val er = MatOfFloat(); val st2 = MatOfByte(); val er2 = MatOfFloat()
        try {
            val win = Size(31.0, 31.0); val crit = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.01)
            Video.calcOpticalFlowPyrLK(prev, cur, p0, p1, st, er, win, 5, crit, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
            Video.calcOpticalFlowPyrLK(cur, prev, p1, back, st2, er2, win, 5, crit, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
            val a = p0.toArray(); val b = p1.toArray(); val c = back.toArray(); val s1 = st.toArray(); val s2 = st2.toArray()
            val dx = ArrayList<Double>(); val dy = ArrayList<Double>()
            for (k in a.indices) if (s1[k].toInt() == 1 && s2[k].toInt() == 1 && hypot(a[k].x - c[k].x, a[k].y - c[k].y) < 1.5) {
                dx.add(b[k].x - a[k].x); dy.add(b[k].y - a[k].y)
            }
            if (dx.size < 8) return null
            return doubleArrayOf(dx.sorted()[dx.size / 2], dy.sorted()[dy.size / 2]) to min(1.0, dx.size / 15.0)
        } finally { listOf(p0, p1, back, st, er, st2, er2).forEach { it.release() } }
    }

    /**
     * Tracks from sample `start` (point `sx`,`sy` normalized) to `end` (inclusive) in direction `step` (+1/-1) and writes rows
     * into `out`. The seed sample gets conf 1.0 only when `writeSeed`.
     */
    fun run(start: Int, sx: Double, sy: Double, end: Int, step: Int, out: Array<DoubleArray?>, writeSeed: Boolean, isCancelled: () -> Unit = {}) {
        var px = sx * gw; var py = sy * gh
        var vx = 0.0; var vy = 0.0
        var last = start
        var prev = store.get(start)
        var tmpl = patch(prev, px, py)
        val tmpl0 = tmpl?.clone()
        var lostRun = 0
        if (writeSeed) out[start] = doubleArrayOf(sx, sy, 1.0)
        try {
            var k = start
            while (k != end) {
                isCancelled()
                k += step
                if (!store.has(k)) continue
                val cur = store.get(k)
                try {
                    val adj = abs(k - last) == 1
                    val c = if (!adj) null else if (step > 0) cam.getOrNull(k) else cam.getOrNull(last)
                    val (wx, wy) = warp(c, px, py, inverse = step < 0)
                    val gx = wx - px + vx; val gy = wy - py + vy       // initial flow guess for LK
                    val ex = px + gx; val ey = py + gy                 // predicted position
                    var nx = Double.NaN; var ny = Double.NaN; var conf = 0.0; var good = false
                    val inside = px in 0.0..gw.toDouble() && py in 0.0..gh.toDouble()
                    if (lostRun == 0 && inside) {
                        lk(prev, cur, px, py, gx, gy)?.let { (d, q) ->
                            val cx = px + d[0] + gx; val cy = py + d[1] + gy
                            val s = tmpl?.let { ncc(cur, it, cx, cy) } ?: 0.5
                            if (s >= 0.3) { nx = cx; ny = cy; conf = q * (0.5 + 0.5 * s.coerceAtLeast(0.0)); good = s >= 0.7 && q >= 0.5 }
                        }
                    }
                    if (nx.isNaN() && tmpl != null) {
                        val half = max(48.0, 2.5 * hypot(vx, vy) + 32.0)
                        var r = search(cur, tmpl, ex, ey, half)
                        if (lostRun in 1..2 || r == null || r.third < 0.55) {
                            if (lostRun % 3 == 0) { // whole-frame re-acquisition: the point may have left and re-entered
                                val whole = listOfNotNull(search(cur, tmpl, gw / 2.0, gh / 2.0, max(gw, gh).toDouble()), tmpl0?.let { search(cur, it, gw / 2.0, gh / 2.0, max(gw, gh).toDouble()) })
                                    .maxByOrNull { it.third }
                                if (whole != null && whole.third >= 0.7 && (r == null || whole.third > r.third)) r = whole
                            }
                        }
                        if (r != null && r.third >= 0.55) { nx = r.first; ny = r.second; conf = r.third * 0.9 }
                    }
                    if (nx.isNaN() || nx < 0 || ny < 0 || nx > gw || ny > gh) {
                        lostRun++
                        px = ex; py = ey; vx *= 0.8; vy *= 0.8 // keep a hint for the next window, no row
                    } else {
                        lostRun = 0
                        vx = 0.5 * (nx - wx); vy = 0.5 * (ny - wy)
                        px = nx; py = ny
                        out[k] = doubleArrayOf(px / gw, py / gh, conf.coerceIn(0.0, 1.0))
                        if (good) patch(cur, px, py)?.let { p ->
                            tmpl?.let { t -> Core.addWeighted(t, 0.85, p, 0.15, 0.0, t) } ?: run { tmpl = p.clone() }
                            p.release()
                        }
                    }
                    prev.release(); prev = cur.clone(); last = k
                } finally { cur.release() }
            }
        } finally { prev.release(); tmpl?.release(); tmpl0?.release() }
    }

    /**
     * Passes from the seed(s) (`seeds` = sample index, x, y; one seed = the pick). A: forward and backward in time from the seed.
     * B: reverse run from the last confident sample back to the seed. `onStep` counts stages (seeds + 3).
     */
    fun multiPass(seeds: List<Triple<Int, Double, Double>>, onStep: (Int) -> Unit = {}, isCancelled: () -> Unit = {}): Pair<List<List<Double>>, List<List<Double>>> {
        val n = store.n
        val a = arrayOfNulls<DoubleArray>(n); val b = arrayOfNulls<DoubleArray>(n)
        val pins = seeds.filter { store.has(it.first) }.distinctBy { it.first }.sortedBy { it.first }
        if (pins.isEmpty()) return List(n) { emptyList<Double>() } to List(n) { emptyList<Double>() }
        var step = 0
        for ((k, p) in pins.withIndex()) {
            val next = pins.getOrNull(k + 1)
            run(p.first, p.second, p.third, next?.first ?: (n - 1), +1, a, true, isCancelled)
            if (next != null) run(next.first, next.second, next.third, p.first, -1, b, false, isCancelled)
            onStep(++step)
        }
        val first = pins.first(); val last = pins.last()
        run(first.first, first.second, first.third, 0, -1, a, false, isCancelled); onStep(++step)
        val fEnd = (n - 1 downTo last.first + 1).firstOrNull { (a[it]?.get(2) ?: 0.0) >= 0.5 }
        if (fEnd != null) a[fEnd]?.let { run(fEnd, it[0], it[1], last.first, -1, b, false, isCancelled) }
        onStep(++step)
        val bStart = (0 until first.first).firstOrNull { (a[it]?.get(2) ?: 0.0) >= 0.5 }
        if (bStart != null) a[bStart]?.let { run(bStart, it[0], it[1], first.first, +1, b, false, isCancelled) }
        onStep(++step)
        for (p in pins) a[p.first] = doubleArrayOf(p.second, p.third, 1.0)
        return a.map { it?.toList() ?: emptyList() } to b.map { it?.toList() ?: emptyList() }
    }
}
