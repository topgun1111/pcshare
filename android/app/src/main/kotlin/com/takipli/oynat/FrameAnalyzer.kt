package com.takipli.oynat

import android.graphics.Bitmap
import com.takipli.oynat.core.BBox
import com.takipli.oynat.core.CameraMotion
import com.takipli.oynat.core.CutDetector
import com.takipli.oynat.core.FlowMatches
import com.takipli.oynat.core.FlowTracker
import com.takipli.oynat.core.GrayImage
import com.takipli.oynat.core.areaResize
import com.takipli.oynat.core.camSize
import com.takipli.oynat.core.identity3
import com.takipli.oynat.core.cutThumbSize
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot

/** `gmc.py::estimate_step` feature part: corners + forward/backward pyramidal LK on OpenCV; RANSAC lives in `core`. */
class OpenCvFlow : FlowTracker {
    override fun match(prev: GrayImage, cur: GrayImage, mask: List<IntArray>): FlowMatches? {
        val mats = ArrayList<Mat>()
        fun <T : Mat> T.keep(): T { mats.add(this); return this }
        try {
            val pm = Mat(prev.h, prev.w, CvType.CV_8UC1).keep().also { it.put(0, 0, prev.data) }
            val cm = Mat(cur.h, cur.w, CvType.CV_8UC1).keep().also { it.put(0, 0, cur.data) }
            val mk = Mat(prev.h, prev.w, CvType.CV_8UC1, Scalar(255.0)).keep()
            for (r in mask) {
                val x1 = r[0].coerceIn(0, prev.w); val y1 = r[1].coerceIn(0, prev.h)
                val x2 = r[2].coerceIn(0, prev.w); val y2 = r[3].coerceIn(0, prev.h)
                if (x2 > x1 && y2 > y1) Imgproc.rectangle(mk, Point(x1.toDouble(), y1.toDouble()), Point((x2 - 1).toDouble(), (y2 - 1).toDouble()), Scalar(0.0), -1)
            }
            val corners = MatOfPoint().keep()
            Imgproc.goodFeaturesToTrack(pm, corners, 250, 0.01, 8.0, mk)
            if (corners.rows() < 10) return null
            val p0 = MatOfPoint2f(*corners.toArray()).keep()
            val p1 = MatOfPoint2f().keep(); val st = MatOfByte().keep(); val err = MatOfFloat().keep()
            val back = MatOfPoint2f().keep(); val st2 = MatOfByte().keep(); val err2 = MatOfFloat().keep()
            val win = Size(21.0, 21.0)
            val crit = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 20, 0.03)
            Video.calcOpticalFlowPyrLK(pm, cm, p0, p1, st, err, win, 4, crit)
            Video.calcOpticalFlowPyrLK(cm, pm, p1, back, st2, err2, win, 4, crit)
            val a0 = p0.toArray(); val a1 = p1.toArray(); val ab = back.toArray()
            val s1 = st.toArray(); val s2 = st2.toArray()
            val cx = ArrayList<Double>(); val px = ArrayList<Double>()
            for (i in a0.indices) {
                if (s1[i].toInt() == 1 && s2[i].toInt() == 1 && hypot(a0[i].x - ab[i].x, a0[i].y - ab[i].y) < 1.0) {
                    cx.add(a1[i].x); cx.add(a1[i].y); px.add(a0[i].x); px.add(a0[i].y)
                }
            }
            if (cx.size / 2 < 10) return null
            return FlowMatches(cx.toDoubleArray(), px.toDoubleArray())
        } finally {
            mats.forEach { it.release() }
        }
    }

    companion object {
        val available: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }
    }
}

class FrameAnalysis(
    /** previous sample -> this sample, 3x3 row-major, full-resolution pixels (identity if unknown) */
    val motion: DoubleArray,
    val motionOk: Boolean,
    val cut: Boolean,
    val histDist: Double,
    val grayDiff: Double,
)

/**
 * Per-sample camera motion + cut detection, same order as `pipeline.py::Pipeline.step`: motion first, then the cut
 * check (with `motionOk`); on a cut the camera estimate restarts. The caller clears its tracker on `cut`
 * (`ByteTracker.clear()`).
 */
class FrameAnalyzer(camSide: Int = 480, histThr: Double = 0.35, diffThr: Double = 0.18, flow: FlowTracker = OpenCvFlow()) {
    private val camSideLocal = camSide
    private val motion = CameraMotion(flow, camSide)
    private val cuts = CutDetector(histThr, diffThr)
    val world: DoubleArray get() = motion.world

    fun reset() { motion.reset(); cuts.reset() }

    fun analyze(bmp: Bitmap, boxes: List<BBox> = emptyList()): FrameAnalysis {
        val w = bmp.width; val h = bmp.height
        val (cw, ch) = camSize(w, h, camSideLocal)
        val small = if (cw == w && ch == h) bmp else Bitmap.createScaledBitmap(bmp, cw, ch, true)
        val px = IntArray(cw * ch)
        small.getPixels(px, 0, cw, 0, 0, cw, ch)
        if (small !== bmp) small.recycle()
        val gray = ByteArray(cw * ch) { i ->
            val p = px[i]
            ((((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8).toByte()
        }
        val m = motion.step(w, h, GrayImage(cw, ch, gray), boxes)
        val (tw, th) = cutThumbSize(w, h)
        val cut = cuts.step(areaResize(px, cw, ch, tw, th), tw, th, motion.ok)
        if (cut) motion.reset() // cut detector keeps its own history
        return FrameAnalysis(if (cut) identity3() else m, motion.ok && !cut, cut, cuts.lastHist, cuts.lastDiff)
    }
}
