package com.takipli.oynat.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Letterbox of a frame into the model input: `scale` frame -> letterbox, `padX`/`padY` = left/top gray. */
data class LetterboxInfo(val scale: Double, val padX: Int, val padY: Int)

/** `info` plus the size the frame must be resized to (`newW` x `newH`) before padding. */
data class LetterboxLayout(val info: LetterboxInfo, val newW: Int, val newH: Int)

/** Port of the geometry in `tracker/detector.py::letterbox` (Python `round` = half-to-even). */
fun letterboxLayout(frameW: Int, frameH: Int, sizeH: Int = 640, sizeW: Int = 640): LetterboxLayout {
    val r = min(sizeH.toDouble() / frameH, sizeW.toDouble() / frameW)
    val nw = max(1, Math.rint(frameW * r).toInt())
    val nh = max(1, Math.rint(frameH * r).toInt())
    return LetterboxLayout(LetterboxInfo(r, (sizeW - nw) / 2, (sizeH - nh) / 2), nw, nh)
}

/**
 * Model input `[1,3,H,W]` (flat CHW, RGB, 0..1) from the frame already resized to
 * `layout.newW x layout.newH` (ARGB ints, e.g. from a Bitmap); gray 114 padding like the PC code.
 */
fun preprocessChw(
    argb: IntArray,
    layout: LetterboxLayout,
    sizeH: Int = 640,
    sizeW: Int = 640,
    out: FloatArray = FloatArray(3 * sizeH * sizeW),
): FloatArray {
    require(argb.size == layout.newW * layout.newH) { "pixels do not match the layout" }
    require(out.size == 3 * sizeH * sizeW) { "output buffer has the wrong size" }
    out.fill(114f / 255f)
    val plane = sizeH * sizeW
    for (y in 0 until layout.newH) {
        val row = (layout.info.padY + y) * sizeW + layout.info.padX
        for (x in 0 until layout.newW) {
            val px = argb[y * layout.newW + x]
            out[row + x] = ((px shr 16) and 255) / 255f
            out[plane + row + x] = ((px shr 8) and 255) / 255f
            out[2 * plane + row + x] = (px and 255) / 255f
        }
    }
    return out
}

const val POSE_COLS = 5 + 3 * N_KPT // cx, cy, w, h, score + 17 x (x, y, conf)

/** `kpts`: flat `[x, y, conf] * n` in frame pixels (17 for COCO). */
class Pose(val bbox: BBox, val score: Double, val kpts: DoubleArray) {
    val count: Int get() = kpts.size / 3
    fun x(k: Int) = kpts[3 * k]
    fun y(k: Int) = kpts[3 * k + 1]
    fun conf(k: Int) = kpts[3 * k + 2]
}

/** Port of `detector.py::nms`: classic NMS, kept indices in score order (ties: lower index first). */
fun nms(boxes: List<BBox>, scores: DoubleArray, iouThres: Double): List<Int> {
    if (boxes.isEmpty()) return emptyList()
    val areas = boxes.map { max(it.x2 - it.x1, 0.0) * max(it.y2 - it.y1, 0.0) }
    var order = boxes.indices.sortedByDescending { scores[it] } // stable
    val keep = ArrayList<Int>()
    while (order.isNotEmpty()) {
        val i = order[0]
        keep.add(i)
        val rest = order.drop(1)
        if (rest.isEmpty()) break
        val a = boxes[i]
        order = rest.filter { j ->
            val b = boxes[j]
            val iw = max(min(a.x2, b.x2) - max(a.x1, b.x1), 0.0)
            val ih = max(min(a.y2, b.y2) - max(a.y1, b.y1), 0.0)
            val inter = iw * ih
            inter / max(areas[i] + areas[j] - inter, 1e-9) <= iouThres
        }
    }
    return keep
}

/**
 * Port of `tracker/pose.py::decode_yolo_pose`. `data` is the flat model output with shape
 * `[dim0, dim1]` (batch 1 already dropped): `[56, N]` (YOLOv8-pose) or `[N, 56]`. Returns poses in
 * frame pixels, best first. Python works in float32; expect agreement within ~1e-3 px.
 */
fun decodeYoloPose(
    data: FloatArray,
    dim0: Int,
    dim1: Int,
    info: LetterboxInfo,
    frameW: Int,
    frameH: Int,
    confThres: Double = 0.25,
    iouThres: Double = 0.45,
    maxDet: Int = 20,
): List<Pose> {
    require(data.size == dim0 * dim1) { "data size ${data.size} != $dim0 x $dim1" }
    val channelMajor = dim0 == POSE_COLS && dim1 != dim0
    val n = if (channelMajor) dim1 else dim0
    require(channelMajor || dim1 == POSE_COLS) { "pose output must be [N, $POSE_COLS] or [$POSE_COLS, N]" }
    fun at(row: Int, c: Int): Float = if (channelMajor) data[c * dim1 + row] else data[row * dim1 + c]

    val thr = confThres.toFloat()
    var idx = (0 until n).filter { at(it, 4) >= thr }
    if (idx.isEmpty()) return emptyList()
    if (idx.size > MAX_CANDIDATES) idx = idx.sortedByDescending { at(it, 4) }.take(MAX_CANDIDATES)

    val boxes = ArrayList<BBox>()
    val scores = ArrayList<Double>()
    val kps = ArrayList<DoubleArray>()
    for (r in idx) {
        val cx = at(r, 0).toDouble(); val cy = at(r, 1).toDouble()
        val w = at(r, 2).toDouble(); val h = at(r, 3).toDouble()
        val x1 = ((cx - w / 2 - info.padX) / info.scale).coerceIn(0.0, frameW.toDouble())
        val x2 = ((cx + w / 2 - info.padX) / info.scale).coerceIn(0.0, frameW.toDouble())
        val y1 = ((cy - h / 2 - info.padY) / info.scale).coerceIn(0.0, frameH.toDouble())
        val y2 = ((cy + h / 2 - info.padY) / info.scale).coerceIn(0.0, frameH.toDouble())
        if (x2 - x1 < 1.0 || y2 - y1 < 1.0) continue
        val kp = DoubleArray(3 * N_KPT)
        for (k in 0 until N_KPT) {
            kp[3 * k] = (at(r, 5 + 3 * k) - info.padX) / info.scale
            kp[3 * k + 1] = (at(r, 6 + 3 * k) - info.padY) / info.scale
            kp[3 * k + 2] = at(r, 7 + 3 * k).toDouble()
        }
        boxes.add(BBox(x1, y1, x2, y2))
        scores.add(at(r, 4).toDouble())
        kps.add(kp)
    }
    val keep = nms(boxes, scores.toDoubleArray(), iouThres).take(maxDet)
    return keep.map { Pose(boxes[it], scores[it], kps[it]) }
}

private const val MAX_CANDIDATES = 300 // best candidates kept before NMS (detector.py::_MAX_CANDIDATES)

/**
 * Port of `pose.py::nearest_keypoint`: closest confident joint to (px, py) as (pose index, joint index);
 * the distance limit is `tol` x person height (`fineTol` for joints beyond COCO-17).
 */
fun nearestKeypoint(
    poses: List<Pose>,
    px: Double,
    py: Double,
    tol: Double = 0.12,
    minConf: Double = 0.35,
    fineTol: Double = 0.05,
    fineConf: Double? = null,
): Pair<Int, Int>? {
    var best: Triple<Double, Int, Int>? = null
    poses.forEachIndexed { i, p ->
        val height = max(p.bbox.y2 - p.bbox.y1, 1.0)
        for (k in 0 until p.count) {
            val fine = k >= N_KPT
            val limit = (if (fine) fineTol else tol) * height
            if (p.conf(k) < (if (!fine || fineConf == null) minConf else fineConf)) continue
            val d = hypot(p.x(k) - px, p.y(k) - py)
            if (d <= limit && (best == null || d < best!!.first)) best = Triple(d, i, k)
        }
    }
    return best?.let { it.second to it.third }
}

/** Person detections from poses (`PoseDetector.detect`): score >= `detConf`. */
fun List<Pose>.toDetections(detConf: Double): List<Detection> =
    filter { it.score >= detConf }.map { Detection(it.bbox, it.score) }

/** Poses for keypoint use (`PoseDetector.estimate`): score >= `poseConf`. */
fun List<Pose>.withMinScore(poseConf: Double): List<Pose> = filter { it.score >= poseConf }
