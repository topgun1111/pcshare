package com.takipli.oynat

import com.takipli.oynat.core.Similarity
import com.takipli.oynat.core.estimateSimilarity
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Part 18: follows a drawn region (e.g. both legs) through the stored frames. Per step: corners are detected inside the
 * region mask in the previous frame, tracked with forward-backward LK (initial flow from the scan's camera motion), a
 * similarity (shift + scale + rotation) is fitted with RANSAC (`core.estimateSimilarity`) and the polygons are moved by it.
 * When too few points survive (blur, region left the frame) the polygons are predicted from the camera motion and a
 * template of the region is searched in the whole frame. Rows: normalized `[cx, cy, conf, w, h]` of the polygon bounds.
 * `polys` are flat `[x0, y0, x1, y1, ...]` in store pixels.
 */
class RegionTracker(private val store: GrayStore, private val cam: List<List<Double>?>) {
    private val gw = store.gw
    private val gh = store.gh

    private fun bounds(polys: List<DoubleArray>): DoubleArray {
        var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE; var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
        for (p in polys) for (i in p.indices step 2) { x1 = min(x1, p[i]); x2 = max(x2, p[i]); y1 = min(y1, p[i + 1]); y2 = max(y2, p[i + 1]) }
        return doubleArrayOf((x1 + x2) / 2, (y1 + y2) / 2, x2 - x1, y2 - y1)
    }

    private fun move(polys: List<DoubleArray>, s: Similarity) = polys.map { p -> DoubleArray(p.size) { i -> val q = s.apply(p[i - i % 2], p[i - i % 2 + 1]); q[i % 2] } }

    private fun camSim(c: List<Double>?, inverse: Boolean): Similarity? {
        if (c == null || c.size < 4) return null
        val tx = c[2] * gh; val ty = c[3] * gh
        if (!inverse) return Similarity(c[0], c[1], tx, ty, 0)
        val d = c[0] * c[0] + c[1] * c[1]
        if (d < 1e-9) return null
        val a = c[0] / d; val b = -c[1] / d
        return Similarity(a, b, -(a * tx - b * ty), -(b * tx + a * ty), 0)
    }

    private fun mask(polys: List<DoubleArray>): Mat {
        val m = Mat.zeros(gh, gw, CvType.CV_8UC1)
        for (p in polys) {
            val pts = Array(p.size / 2) { Point(p[2 * it].coerceIn(0.0, gw - 1.0), p[2 * it + 1].coerceIn(0.0, gh - 1.0)) }
            if (pts.size >= 3) { val mop = MatOfPoint(*pts); Imgproc.fillPoly(m, listOf(mop), Scalar(255.0)); mop.release() }
        }
        return m
    }

    /** Corners inside the mask; a regular grid over the mask when the region has little texture. */
    private fun seeds(gray: Mat, polys: List<DoubleArray>): List<Point> {
        val mk = mask(polys); val corners = MatOfPoint()
        try {
            Imgproc.goodFeaturesToTrack(gray, corners, 200, 0.01, 4.0, mk)
            val pts = corners.toArray().map { Point(it.x, it.y) }
            if (pts.size >= 12) return pts
            val b = bounds(polys); val step = max(5.0, sqrt(max(b[2] * b[3], 1.0) / 150.0))
            val out = ArrayList<Point>(); var y = b[1] - b[3] / 2
            while (y < b[1] + b[3] / 2) {
                var x = b[0] - b[2] / 2
                while (x < b[0] + b[2] / 2) {
                    val xi = x.toInt(); val yi = y.toInt()
                    if (xi in 0 until gw && yi in 0 until gh && mk.get(yi, xi)[0] > 0) out.add(Point(x, y))
                    x += step
                }
                y += step
            }
            return out
        } finally { mk.release(); corners.release() }
    }

    private fun template(gray: Mat, polys: List<DoubleArray>): Pair<Mat, Double>? {
        val b = bounds(polys)
        val x1 = max(0, (b[0] - b[2] / 2).toInt()); val y1 = max(0, (b[1] - b[3] / 2).toInt())
        val x2 = min(gw, (b[0] + b[2] / 2).toInt()); val y2 = min(gh, (b[1] + b[3] / 2).toInt())
        if (x2 - x1 < 16 || y2 - y1 < 16) return null
        val crop = Mat(gray, Rect(x1, y1, x2 - x1, y2 - y1))
        val f = min(1.0, 96.0 / max(x2 - x1, y2 - y1))
        if (f >= 1.0) return crop.clone() to 1.0
        val r = Mat(); Imgproc.resize(crop, r, Size((x2 - x1) * f, (y2 - y1) * f), 0.0, 0.0, Imgproc.INTER_AREA)
        return r to f
    }

    fun run(start: Int, polys0: List<DoubleArray>, end: Int, step: Int, out: Array<DoubleArray?>, writeSeed: Boolean, isCancelled: () -> Unit = {}) {
        var polys = polys0
        var last = start
        var prev = store.get(start)
        val tmpl = template(prev, polys)
        var lostRun = 0
        if (writeSeed) bounds(polys).let { out[start] = doubleArrayOf(it[0] / gw, it[1] / gh, 1.0, it[2] / gw, it[3] / gh) }
        try {
            var k = start
            while (k != end) {
                isCancelled()
                k += step
                if (!store.has(k)) continue
                val cur = store.get(k)
                try {
                    val c = if (abs(k - last) != 1) null else if (step > 0) cam.getOrNull(k) else cam.getOrNull(last)
                    val cs = camSim(c, inverse = step < 0)
                    var moved: List<DoubleArray>? = null; var conf = 0.0
                    if (lostRun == 0) {
                        val src = seeds(prev, polys)
                        if (src.size >= 8) {
                            val p0 = MatOfPoint2f(*src.toTypedArray())
                            val p1 = MatOfPoint2f(*src.map { q -> cs?.apply(q.x, q.y)?.let { Point(it[0], it[1]) } ?: q }.toTypedArray())
                            val back = MatOfPoint2f(*src.toTypedArray())
                            val st = MatOfByte(); val er = MatOfFloat(); val st2 = MatOfByte(); val er2 = MatOfFloat()
                            try {
                                val win = Size(31.0, 31.0); val crit = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.01)
                                Video.calcOpticalFlowPyrLK(prev, cur, p0, p1, st, er, win, 5, crit, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
                                Video.calcOpticalFlowPyrLK(cur, prev, p1, back, st2, er2, win, 5, crit, Video.OPTFLOW_USE_INITIAL_FLOW, 1e-4)
                                val a = p0.toArray(); val b = p1.toArray(); val d = back.toArray(); val s1 = st.toArray(); val s2 = st2.toArray()
                                val fx = ArrayList<Double>(); val tx = ArrayList<Double>()
                                for (i in a.indices) if (s1[i].toInt() == 1 && s2[i].toInt() == 1 && hypot(a[i].x - d[i].x, a[i].y - d[i].y) < 1.5) {
                                    fx.add(a[i].x); fx.add(a[i].y); tx.add(b[i].x); tx.add(b[i].y)
                                }
                                val valid = fx.size / 2
                                if (valid >= 6) {
                                    val sim = estimateSimilarity(fx.toDoubleArray(), tx.toDoubleArray(), 2.5, 400, 6)
                                    val sc = sim?.let { hypot(it.a, it.b) } ?: 1.0
                                    val usable = if (sim != null && sc in 0.8..1.25) sim else { // implausible scale jump: shift only
                                        val dx = (0 until valid).map { tx[2 * it] - fx[2 * it] }.sorted()[valid / 2]
                                        val dy = (0 until valid).map { tx[2 * it + 1] - fx[2 * it + 1] }.sorted()[valid / 2]
                                        Similarity(1.0, 0.0, dx, dy, valid / 2)
                                    }
                                    moved = move(polys, usable)
                                    conf = (min(1.0, usable.inliers / 25.0) * min(1.0, usable.inliers.toDouble() / valid + 0.2)).coerceIn(0.0, 1.0)
                                }
                            } finally { listOf(p0, p1, back, st, er, st2, er2).forEach { it.release() } }
                        }
                    }
                    if (moved == null && tmpl != null) { // lost: re-find the region by template anywhere in the frame
                        val (t, f) = tmpl
                        if (lostRun % 2 == 0) {
                            val small = Mat(); val res = Mat()
                            try {
                                if (f < 1.0) Imgproc.resize(cur, small, Size(gw * f, gh * f), 0.0, 0.0, Imgproc.INTER_AREA) else cur.copyTo(small)
                                if (small.cols() > t.cols() && small.rows() > t.rows()) {
                                    Imgproc.matchTemplate(small, t, res, Imgproc.TM_CCOEFF_NORMED)
                                    val mm = Core.minMaxLoc(res)
                                    if (mm.maxVal >= 0.65) {
                                        val cx = (mm.maxLoc.x + t.cols() / 2.0) / f; val cy = (mm.maxLoc.y + t.rows() / 2.0) / f
                                        val b = bounds(polys)
                                        moved = move(polys, Similarity(1.0, 0.0, cx - b[0], cy - b[1], 0)); conf = mm.maxVal * 0.8
                                    }
                                }
                            } finally { small.release(); res.release() }
                        }
                    }
                    if (moved != null) {
                        val b = bounds(moved)
                        if (b[0] < 0 || b[1] < 0 || b[0] > gw || b[1] > gh) { moved = null }
                    }
                    if (moved == null) {
                        lostRun++
                        cs?.let { polys = move(polys, it) } // keep the prediction for the next window, no row
                    } else {
                        lostRun = 0; polys = moved
                        val b = bounds(polys)
                        out[k] = doubleArrayOf(b[0] / gw, b[1] / gh, conf, b[2] / gw, b[3] / gh)
                    }
                    prev.release(); prev = cur.clone(); last = k
                } finally { cur.release() }
            }
        } finally { prev.release(); tmpl?.first?.release() }
    }

    /** Pass A from the pick forward and backward in time; pass B reverse from the last confident sample back to the pick. */
    fun twoPass(pick: Int, polys: List<DoubleArray>, onStep: (Int) -> Unit = {}, isCancelled: () -> Unit = {}): Pair<List<List<Double>>, List<List<Double>>> {
        val n = store.n
        val a = arrayOfNulls<DoubleArray>(n); val b = arrayOfNulls<DoubleArray>(n)
        if (!store.has(pick)) return List(n) { emptyList<Double>() } to List(n) { emptyList<Double>() }
        run(pick, polys, n - 1, +1, a, true, isCancelled); onStep(1)
        run(pick, polys, 0, -1, a, false, isCancelled); onStep(2)
        // reverse verification: polygons rebuilt from the row (same size, shifted to the found centre) at the last confident sample
        fun reseed(row: DoubleArray) = polys.let { ps ->
            val bb = bounds(ps); val dx = row[0] * gw - bb[0]; val dy = row[1] * gh - bb[1]
            val sc = if (bb[3] > 1e-6) row[4] * gh / bb[3] else 1.0
            ps.map { p -> DoubleArray(p.size) { i -> if (i % 2 == 0) row[0] * gw + (p[i] - bb[0]) * sc else row[1] * gh + (p[i] - bb[1]) * sc } }
        }
        val fEnd = (n - 1 downTo pick + 1).firstOrNull { (a[it]?.get(2) ?: 0.0) >= 0.5 }
        if (fEnd != null) a[fEnd]?.let { run(fEnd, reseed(it), pick, -1, b, true, isCancelled) }
        onStep(3)
        val bStart = (0 until pick).firstOrNull { (a[it]?.get(2) ?: 0.0) >= 0.5 }
        if (bStart != null) a[bStart]?.let { run(bStart, reseed(it), pick, +1, b, true, isCancelled) }
        onStep(4)
        return a.map { it?.toList() ?: emptyList() } to b.map { it?.toList() ?: emptyList() }
    }
}
