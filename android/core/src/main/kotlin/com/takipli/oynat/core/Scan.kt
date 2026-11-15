package com.takipli.oynat.core

import java.security.MessageDigest
import kotlin.math.floor

/** Port of `offline.py::associate_poses`: greedy by box IoU, every pose used once. `{track id: Pose}`. */
fun associatePoses(tracks: List<Track>, poses: List<Pose>, minIou: Double = 0.3): Map<Int, Pose> {
    if (tracks.isEmpty() || poses.isEmpty()) return emptyMap()
    val iou = iouMatrix(tracks.map { it.bbox }, poses.map { it.bbox })
    // Python sorts the (iou, i, j) tuples in reverse: ties go to the larger i, then the larger j.
    val pairs = ArrayList<Triple<Double, Int, Int>>()
    for (i in tracks.indices) for (j in poses.indices) pairs.add(Triple(iou[i][j], i, j))
    pairs.sortWith(compareByDescending<Triple<Double, Int, Int>> { it.first }.thenByDescending { it.second }.thenByDescending { it.third })
    val out = LinkedHashMap<Int, Pose>()
    val used = HashSet<Int>()
    for ((v, i, j) in pairs) {
        if (v < minIou) break
        if (tracks[i].id !in out && j !in used) {
            out[tracks[i].id] = poses[j]
            used.add(j)
        }
    }
    return out
}

/**
 * Python's `round(v, digits)`: rounds the exact binary value half-to-even. `rint(v * 10^digits)` is NOT the same: the
 * multiplication can land exactly on .5 (e.g. 0.66655 -> 0.6665 in Python, 0.6666 with rint) and break the golden test.
 */
internal fun round(v: Double, digits: Int): Double {
    if (v.isNaN() || v.isInfinite()) return v
    return java.math.BigDecimal(v).setScale(digits, java.math.RoundingMode.HALF_EVEN).toDouble() + 0.0
}

/** `pipeline.py::cam_row`: 3x3 row-major previous -> this similarity -> `[a, b, tx/h, ty/h]`; null without a motion. */
fun camRow(motion: DoubleArray?, h: Int): List<Double>? =
    if (motion == null || motion.size < 9) null
    else listOf(round(motion[0], 6), round(motion[3], 6), round(motion[2] / h, 5), round(motion[5] / h, 5))

/** `offline.py::_kp_row`: 17 x (x/w, y/h rounded to 4 decimals, conf to 2). */
fun kpRow(p: Pose, w: Int, h: Int): List<Double> {
    val out = ArrayList<Double>(3 * p.count)
    for (k in 0 until p.count) {
        out.add(round(p.x(k) / w, 4)); out.add(round(p.y(k) / h, 4)); out.add(round(p.conf(k), 2))
    }
    return out
}

/**
 * One scan in progress (`offline.py::run_detection` + `Pipeline.step`, without the video part). Feed it, per sampled
 * frame, the decoded poses (decode with `conf = liveDetConf`: ByteTrack needs the low-score stage), the camera motion
 * 3x3 (previous -> this sample) and the cut flag (`FrameAnalysis`). On a cut the tracks are dropped (IDs are never
 * reissued) and no motion is applied.
 */
class ScanPipeline(private val cfg: Config = Config(), private val tracker: ByteTracker = ByteTracker.fromConfig(cfg)) {
    private val list = ArrayList<Sample>()
    val samples: List<Sample> get() = list

    /** Last boxes of live tracks: the camera estimate masks them out when picking corners. */
    val boxes: List<BBox> get() = tracker.boxes

    /**
     * Continue after an interruption: earlier samples are kept and new IDs start after the highest one used.
     * Tracker state is not stored, so people visible at the resume point get new IDs (same as a shot cut).
     */
    fun resume(existing: List<Sample>) {
        list.addAll(existing)
        val maxId = existing.flatMap { s -> s.d.map { it[0].toInt() } }.maxOrNull() ?: 0
        tracker.setNextId(maxId + 1)
    }

    fun step(t: Double, w: Int, h: Int, poses: List<Pose>, motion: DoubleArray? = null, cut: Boolean = false): Sample {
        if (cut) tracker.clear()
        val tracks = tracker.update(poses.toDetections(cfg.liveDetConf), if (cut) null else motion)
        val d = tracks.map { tr ->
            val b = tr.bbox
            listOf(tr.id.toDouble(), round(b.x1 / w, 4), round(b.y1 / h, 4), round(b.x2 / w, 4), round(b.y2 / h, 4), round(tr.score, 3))
        }
        val kp = associatePoses(tracks, poses.withMinScore(cfg.poseConf)).mapKeys { it.key.toString() }.mapValues { kpRow(it.value, w, h) }
        val s = Sample(round(t, 3), d, kp.ifEmpty { null }, if (cut) 1 else null, if (cut) null else camRow(motion, h))
        list.add(s)
        return s
    }
}

/** `offline.py::make_doc` for a pose scan: version 2, cut times copied from the samples. */
fun buildScanDoc(
    path: String, w: Int, h: Int, srcFps: Double, duration: Double, sampleFps: Double,
    samples: List<Sample>, sig: String? = null, vsig: String? = null,
): TrackDoc = TrackDoc(
    version = FormatVersion.V2,
    video = VideoInfo(path, w, h, round(srcFps, 3), round(duration, 3)),
    sampleFps = sampleFps,
    samples = samples,
    sig = sig, vsig = vsig,
    cuts = samples.filter { it.cut == 1 }.map { it.t },
)

/**
 * Scan cache key (like `cache.py::scan_sig`): video id + sample fps + model id, SHA-1, first 16 hex digits.
 * `videoId` is chosen by the app (uri + size + modified time); it is not identical to the PC's path-based id, so a scan
 * does not hit the cache across devices, but the file format is the same.
 */
fun scanSig(videoId: String, fps: Double, modelTag: String): String {
    val g = if (fps == floor(fps) && fps < 1e15) fps.toLong().toString() else fps.toString() // like Python `:g` for normal values
    val raw = listOf(videoId, g, modelTag).filter { it.isNotEmpty() }.joinToString("|")
    return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
}
