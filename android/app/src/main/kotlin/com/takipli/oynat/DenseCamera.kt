package com.takipli.oynat

import android.content.Context
import android.net.Uri
import com.takipli.oynat.core.BBox
import com.takipli.oynat.core.CamPath
import com.takipli.oynat.core.CamPathBuilder
import com.takipli.oynat.core.Config
import com.takipli.oynat.core.FlowMatches
import com.takipli.oynat.core.FlowTracker
import com.takipli.oynat.core.GrayImage
import com.takipli.oynat.core.TrackDoc
import com.takipli.oynat.core.plausibleCameraStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

/**
 * Feature matcher for fast camera motion, same contract as [OpenCvFlow] but built for accuracy, not speed: more corners,
 * bigger LK window and one more pyramid level, and when too few points survive it retries on a 2x and a 4x smaller
 * image (a big shift becomes a small one there).
 */
class RobustFlow(
    private val maxCorners: Int = 800,
    private val win: Int = 31,
    private val levels: Int = 5,
    private val fbTol: Double = 1.5,
) : FlowTracker {
    override fun match(prev: GrayImage, cur: GrayImage, mask: List<IntArray>): FlowMatches? {
        for (ds in intArrayOf(1, 2, 4)) matchAt(prev, cur, mask, ds)?.let { return it }
        return null
    }

    private fun matchAt(prev: GrayImage, cur: GrayImage, mask: List<IntArray>, ds: Int): FlowMatches? {
        val mats = ArrayList<Mat>()
        fun <T : Mat> T.keep(): T { mats.add(this); return this }
        try {
            val p0 = Mat(prev.h, prev.w, CvType.CV_8UC1).keep().also { it.put(0, 0, prev.data) }
            val c0 = Mat(cur.h, cur.w, CvType.CV_8UC1).keep().also { it.put(0, 0, cur.data) }
            val size = Size(max(prev.w / ds, 16).toDouble(), max(prev.h / ds, 16).toDouble())
            val pm = if (ds == 1) p0 else Mat().keep().also { Imgproc.resize(p0, it, size, 0.0, 0.0, Imgproc.INTER_AREA) }
            val cm = if (ds == 1) c0 else Mat().keep().also { Imgproc.resize(c0, it, size, 0.0, 0.0, Imgproc.INTER_AREA) }
            val mk = Mat(pm.rows(), pm.cols(), CvType.CV_8UC1, Scalar(255.0)).keep()
            for (r in mask) {
                val x1 = (r[0] / ds).coerceIn(0, pm.cols()); val y1 = (r[1] / ds).coerceIn(0, pm.rows())
                val x2 = (r[2] / ds).coerceIn(0, pm.cols()); val y2 = (r[3] / ds).coerceIn(0, pm.rows())
                if (x2 > x1 && y2 > y1) Imgproc.rectangle(mk, Point(x1.toDouble(), y1.toDouble()), Point((x2 - 1).toDouble(), (y2 - 1).toDouble()), Scalar(0.0), -1)
            }
            val corners = MatOfPoint().keep()
            Imgproc.goodFeaturesToTrack(pm, corners, maxCorners, 0.005, if (ds == 1) 10.0 else 6.0, mk)
            if (corners.rows() < 12) return null
            val q0 = MatOfPoint2f(*corners.toArray()).keep()
            val q1 = MatOfPoint2f().keep(); val st = MatOfByte().keep(); val err = MatOfFloat().keep()
            val back = MatOfPoint2f().keep(); val st2 = MatOfByte().keep(); val err2 = MatOfFloat().keep()
            val w = Size(win.toDouble(), win.toDouble())
            val crit = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 30, 0.01)
            Video.calcOpticalFlowPyrLK(pm, cm, q0, q1, st, err, w, levels, crit)
            Video.calcOpticalFlowPyrLK(cm, pm, q1, back, st2, err2, w, levels, crit)
            val a0 = q0.toArray(); val a1 = q1.toArray(); val ab = back.toArray()
            val s1 = st.toArray(); val s2 = st2.toArray()
            val cx = ArrayList<Double>(); val px = ArrayList<Double>()
            for (i in a0.indices) {
                if (s1[i].toInt() == 1 && s2[i].toInt() == 1 && hypot(a0[i].x - ab[i].x, a0[i].y - ab[i].y) < fbTol) {
                    cx.add(a1[i].x * ds); cx.add(a1[i].y * ds); px.add(a0[i].x * ds); px.add(a0[i].y * ds)
                }
            }
            if (cx.size / 2 < 12) return null
            return FlowMatches(cx.toDoubleArray(), px.toDoubleArray())
        } finally {
            mats.forEach { it.release() }
        }
    }
}

/**
 * Camera pose of EVERY frame of the video (not only the scan samples), for the stabilized export. Slow on purpose:
 * all frames, 960 px analysis, robust matcher, people from the scan masked out of the feature search. The result is
 * cached next to the scan (`.cam.json`), so changing the tuning or the target does not repeat it.
 */
object DenseCamera {
    const val ANALYSIS_SIDE = 960
    private const val DECODE_SIDE = 1920

    /** Cache key: video, scan and analysis settings. A new scan or another analysis size invalidates the file. */
    fun signature(doc: TrackDoc): String =
        listOf(doc.video.path, doc.video.w, doc.video.h, doc.video.duration, doc.sig.orEmpty(), "cam1", ANALYSIS_SIDE).joinToString("|")

    fun load(file: File, sig: String): CamPath? =
        if (!file.exists()) null else CamPath.decode(file.readText())?.takeIf { it.sig == sig && it.size > 1 }

    fun save(file: File, p: CamPath) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(CamPath.encode(p))
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot write ${file.name}" } }
    }

    /** Foreground boxes (pixels of a `w` x `h` frame, 8 % margin) of the scan sample nearest to `t`; empty when none is close. */
    private fun boxesAt(doc: TrackDoc, t: Double, w: Int, h: Int): List<BBox> {
        val s = doc.samples
        if (s.isEmpty()) return emptyList()
        var lo = 0; var hi = s.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (s[mid].t <= t) lo = mid else hi = mid }
        val near = if (kotlin.math.abs(s[hi].t - t) < kotlin.math.abs(s[lo].t - t)) s[hi] else s[lo]
        if (kotlin.math.abs(near.t - t) > max(0.25, 1.5 / max(doc.sampleFps, 1.0))) return emptyList()
        return near.d.filter { it.size >= 5 }.map { r ->
            val bw = (r[3] - r[1]) * 0.08; val bh = (r[4] - r[2]) * 0.08
            BBox((r[1] - bw) * w, (r[2] - bh) * h, (r[3] + bw) * w, (r[4] + bh) * h)
        }
    }

    suspend fun analyze(ctx: Context, uri: Uri, doc: TrackDoc, sig: String, onProgress: (Float) -> Unit): CamPath = withContext(Dispatchers.Default) {
        check(OpenCvFlow.available) { "OpenCV could not be loaded" }
        val info = VideoDecoder.probe(ctx, uri)
        val cfg = Config()
        val analyzer = FrameAnalyzer(ANALYSIS_SIDE, cfg.cutHist, cfg.cutDiff, RobustFlow())
        var builder: CamPathBuilder? = null
        var lastStep: DoubleArray? = null
        var misses = 0
        var prevT = 0.0
        var n = 0

        val onFrame: suspend (SampledFrame) -> Boolean = { f ->
            currentCoroutineContext().ensureActive()
            val bmp = f.bitmap
            val b = builder ?: CamPathBuilder(bmp.height).also { builder = it }
            n++
            // Breadcrumbs: a native crash leaves no error line, the trace file shows how far the analysis got.
            if (n <= 3 || n % 30 == 0) Trace.step(ctx, "dense frame $n t=${String.format(java.util.Locale.ROOT, "%.2f", f.t)} ${bmp.width}x${bmp.height}")
            val a = runCatching { analyzer.analyze(bmp, boxesAt(doc, prevT, bmp.width, bmp.height)) }
                .onFailure { if (n <= 3) ErrorLog.record(ctx, "dense-analyze", it) }.getOrNull()
            if (n <= 3) Trace.step(ctx, "dense frame $n analysed ok=${a != null}")
            var step: DoubleArray? = null
            val cut = a?.cut == true
            if (cut) { lastStep = null; misses = 0 }
            else if (a != null && a.motionOk && plausibleCameraStep(a.motion)) { step = a.motion; lastStep = a.motion; misses = 0 }
            else { misses++; step = if (misses <= 3) lastStep else null } // short failures: keep moving at the last speed
            b.add(f.t, step, cut)
            prevT = f.t
            if (info.durationS > 0) onProgress((f.t / info.durationS).toFloat().coerceIn(0f, 1f))
            bmp.recycle()
            true
        }
        try {
            VideoDecoder.sampleImageReader(ctx, uri, 1000.0, maxSide = DECODE_SIDE, onFrame = onFrame) // 1000 fps grid = every frame, real timestamps
        } catch (e: IllegalArgumentException) {
            if ((builder?.count ?: 0) > 0) throw e
            VideoDecoder.sample(ctx, uri, info.frameRate ?: 30.0, maxSide = DECODE_SIDE, onFrame = onFrame) // 10-bit/HDR: the retriever path
        }
        val b = builder
        check(b != null && b.count > 1) { "no frames could be analysed" }
        b.build(sig)
    }
}
