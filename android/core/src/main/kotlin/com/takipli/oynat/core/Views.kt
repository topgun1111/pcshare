package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---- Part 10: part presets, target boxes and follow-camera views. Port of `tracker/parts.py` + `tracker/offline.py`. ----

/** Joint groups (COCO-17 indices); `person` = whole person box. Left/right = the subject's. */
val PRESETS: Map<String, IntArray> = linkedMapOf(
    "person" to intArrayOf(),
    "head" to intArrayOf(0, 1, 2, 3, 4),
    "torso" to intArrayOf(5, 6, 11, 12),
    "left_arm" to intArrayOf(5, 7, 9), "right_arm" to intArrayOf(6, 8, 10),
    "left_leg" to intArrayOf(11, 13, 15), "right_leg" to intArrayOf(12, 14, 16), "legs" to intArrayOf(11, 12, 13, 14, 15, 16),
    "left_foot" to intArrayOf(15), "right_foot" to intArrayOf(16), "feet" to intArrayOf(15, 16),
    "hands" to intArrayOf(9, 10),
)

/** No confident joint: this vertical slice (top, bottom fraction) of the person box is used instead. */
private val REGION: Map<String, Pair<Double, Double>> = mapOf(
    "head" to (0.0 to 0.22), "torso" to (0.18 to 0.55),
    "left_arm" to (0.15 to 0.60), "right_arm" to (0.15 to 0.60),
    "left_leg" to (0.50 to 1.0), "right_leg" to (0.50 to 1.0), "legs" to (0.50 to 1.0),
    "left_foot" to (0.88 to 1.0), "right_foot" to (0.88 to 1.0), "feet" to (0.88 to 1.0),
    "hands" to (0.35 to 0.62),
)

/** Part 10b: missing end joint -> (parent, parent's parent, limb ratio): end = parent + (parent - grandparent) * ratio. */
private val INFER: Map<Int, Triple<Int, Int, Double>> = mapOf(
    15 to Triple(13, 11, 1.0), 16 to Triple(14, 12, 1.0), 9 to Triple(7, 5, 0.8), 10 to Triple(8, 6, 0.8),
)
private const val INFER_CONF = 0.5

/** `parts.py::infer_joint`: pixel estimate `[x, y, conf]` of a missing ankle/wrist from two confident upper joints, or null. */
fun inferJoint(row: List<Double>, j: Int, w: Int, h: Int, minConf: Double): DoubleArray? {
    val (p, g, ratio) = INFER[j] ?: return null
    if (row.size < 3 * N_KPT || row[3 * p + 2] < minConf || row[3 * g + 2] < minConf) return null
    val px = row[3 * p] * w; val py = row[3 * p + 1] * h; val gx = row[3 * g] * w; val gy = row[3 * g + 1] * h
    return doubleArrayOf(px + (px - gx) * ratio, py + (py - gy) * ratio, min(row[3 * p + 2], row[3 * g + 2]) * INFER_CONF)
}

private fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))

private fun round4(v: Double) = round(v, 4) // exact half-even like Python (see Scan.kt); +0.0 there turns -0.0 into 0.0

/** `geometry.py::zoom_for_bbox`: log2 zoom so the box fills `fill` of the frame height (and fits the width). */
fun zoomForBbox(b: BBox, fw: Double, fh: Double, fill: Double, minZoom: Double, maxZoom: Double): Double {
    val scale = min(fill / max(b.h / fh, 1e-6), 0.9 / max(b.w / fw, 1e-6))
    return clamp(log2(max(scale, 1e-6)), minZoom, maxZoom)
}

/** `geometry.py::compute_view`: zoom and the pan that centres the box (clamped so no border shows). */
fun computeView(b: BBox, fw: Double, fh: Double, fill: Double, minZoom: Double, maxZoom: Double): View {
    val zoom = zoomForBbox(b, fw, fh, fill, minZoom, maxZoom)
    val half = 0.5 / 2.0.pow(zoom)
    return View(zoom, 0.5 - clamp(b.cx / fw, half, 1.0 - half), 0.5 - clamp(b.cy / fh, half, 1.0 - half))
}

/** `smooth.py::ease_view`: smoothstep from `a` to `b` (p clamped to 0..1). */
fun easeView(a: View, b: View, p: Double): View {
    val q = clamp(p, 0.0, 1.0)
    val e = q * q * (3.0 - 2.0 * q)
    return View(a.zoom + (b.zoom - a.zoom) * e, a.panX + (b.panX - a.panX) * e, a.panY + (b.panY - a.panY) * e)
}

/** `smooth.py::smooth_zero_phase`: forward then backward EMA (no lag; offline only). */
fun smoothZeroPhase(values: DoubleArray, alpha: Double): DoubleArray {
    val out = values.copyOf()
    for (i in 1 until out.size) out[i] = out[i - 1] + alpha * (out[i] - out[i - 1])
    for (i in out.size - 2 downTo 0) out[i] = out[i + 1] + alpha * (out[i] - out[i + 1])
    return out
}

fun cutIndices(doc: TrackDoc): Set<Int> = doc.samples.indices.filter { (doc.samples[it].cut ?: 0) != 0 }.toSet()

private fun runsOf(idx: List<Int>): List<List<Int>> {
    val out = ArrayList<MutableList<Int>>()
    for (i in idx) if (out.isNotEmpty() && out.last().last() == i - 1) out.last().add(i) else out.add(mutableListOf(i))
    return out
}

/**
 * `offline.py::_views_from_boxes`. `boxes`: sample index -> `[cx, cy, w, h]` normalized. 1) short gaps
 * (<= `offlineInterpS`) are filled linearly, 2) each visible run is smoothed zero-phase and turned into a view, 3) long
 * gaps hold, then zoom out, and zoom back in before the target returns, 4) repeated keyframes are dropped.
 * Nothing is filled, smoothed or eased across a cut (`cuts` = indices of samples that start a new shot).
 */
fun viewsFromBoxes(ts: List<Double>, boxes: Map<Int, DoubleArray>, cfg: Config, cuts: Set<Int> = emptySet()): List<ViewKey> {
    val n = ts.size
    val bx = HashMap<Int, DoubleArray>()
    for ((k, v) in boxes) bx[k] = v.copyOf()
    fun cutBetween(lo: Int, hi: Int) = cuts.any { it in lo..hi }

    val vis = bx.keys.sorted()
    for (q in 0 until vis.size - 1) {
        val a = vis[q]; val b = vis[q + 1]
        if (b - a > 1 && ts[b] - ts[a] <= cfg.offlineInterpS && !cutBetween(a + 1, b)) {
            val ba = bx.getValue(a); val bb = bx.getValue(b)
            for (i in a + 1 until b) {
                val f = (ts[i] - ts[a]) / max(ts[b] - ts[a], 1e-9)
                bx[i] = DoubleArray(4) { ba[it] + (bb[it] - ba[it]) * f }
            }
        }
    }
    val views = HashMap<Int, View>()
    val runs = ArrayList<List<Int>>()
    for (run in runsOf(bx.keys.sorted())) {
        var cur = ArrayList<Int>()
        for (i in run) {
            if (cur.isNotEmpty() && i in cuts) { runs.add(cur); cur = ArrayList() }
            cur.add(i)
        }
        runs.add(cur)
    }
    for (run in runs) {
        val sm = (0 until 4).map { k ->
            smoothZeroPhase(DoubleArray(run.size) { bx.getValue(run[it])[k] }, cfg.offlineAlpha * (if (k < 2) 1.0 else 0.4))
        }
        for ((j, i) in run.withIndex()) {
            val cx = sm[0][j]; val cy = sm[1][j]; val w = sm[2][j]; val h = sm[3][j]
            views[i] = computeView(BBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), 1.0, 1.0, cfg.targetFill, cfg.minZoom, cfg.maxZoom)
        }
    }
    val hold = max(cfg.lostHoldS, 0.0)
    val dur = cfg.lostZoomoutS
    val zero = View(0.0, 0.0, 0.0)
    var i = 0
    while (i < n) {
        if (i in views) { i++; continue }
        var j = i
        while (j < n && j !in views) j++
        val prev = if (i > 0) views[i - 1] else null
        val nxt = if (j < n) views[j] else null
        val t0 = ts[i]
        for (k in i until j) {
            val t = ts[k]
            val pv = if (!cutBetween(i, k)) prev else null
            val nv = if (!cutBetween(k + 1, j)) nxt else null
            var v = if (pv == null) zero else {
                val el = t - t0 - hold
                easeView(pv, zero, if (dur > 0) el / dur else if (el >= 0) 1.0 else 0.0)
            }
            if (nv != null) v = easeView(v, nv, if (dur > 0) (t - (ts[j] - dur)) / dur else 0.0)
            views[k] = v
        }
        i = j
    }
    val keys = (0 until n).map { k -> listOf(ts[k], round4(views.getValue(k).zoom), round4(views.getValue(k).panX), round4(views.getValue(k).panY)) }
    val out = ArrayList<ViewKey>()
    for ((k, kf) in keys.withIndex()) {
        if (k in 1 until n - 1 && keys[k - 1].drop(1) == kf.drop(1) && kf.drop(1) == keys[k + 1].drop(1)) continue
        out.add(kf)
    }
    return out
}

internal fun videoSize(doc: TrackDoc) = (doc.video.w.takeIf { it != 0 } ?: 1) to (doc.video.h.takeIf { it != 0 } ?: 1)

/** `offline.py::build_views` boxes: the person box of the best-scoring ID per sample. */
fun personBoxes(doc: TrackDoc, ids: Set<Int>): Map<Int, DoubleArray> {
    val boxes = HashMap<Int, DoubleArray>(); val best = HashMap<Int, Double>()
    for ((i, s) in doc.samples.withIndex()) for (d in s.d) {
        if (d[0].toInt() in ids && d[5] >= (best[i] ?: -1.0)) {
            val b = BBox(d[1], d[2], d[3], d[4]); boxes[i] = doubleArrayOf(b.cx, b.cy, b.w, b.h); best[i] = d[5]
        }
    }
    return boxes
}

/** `offline.py::part_boxes`: one joint, side = `kpBox` x person height (>= 8 px); joints under `kpMinConf` count as lost. */
fun jointBoxes(doc: TrackDoc, ids: Set<Int>, part: Int, cfg: Config): Map<Int, DoubleArray> {
    require(part in 0 until N_KPT) { "joint index must be 0..${N_KPT - 1}: $part" }
    val (w, h) = videoSize(doc)
    val boxes = HashMap<Int, DoubleArray>(); val best = HashMap<Int, Double>()
    for ((i, s) in doc.samples.withIndex()) for (d in s.d) {
        val k = s.keypoint(d[0].toInt(), part) ?: continue
        if (d[0].toInt() !in ids) continue
        if (k.conf >= cfg.kpMinConf && k.conf >= (best[i] ?: -1.0)) {
            val side = max(cfg.kpBox * (d[4] - d[2]) * h, 8.0)
            boxes[i] = doubleArrayOf(k.x, k.y, side / w, side / h); best[i] = k.conf
        }
    }
    return boxes
}

/**
 * `parts.py::preset_boxes`: group box = union of confident joints + `partMargin` (>= `kpBox` x person height), else the
 * person-box slice for that part. Joint-based candidates beat slices; among equals the later ID wins. With `cfg.kpInfer`
 * a missing ankle/wrist is estimated from its two upper joints (`inferJoint`) before the slice is used. `kps`
 * (Part 10b): per-sample rows from `smoothKeypoints`; null = the raw `kp`.
 */
fun presetBoxes(doc: TrackDoc, ids: Set<Int>, preset: String, cfg: Config, kps: List<Map<String, List<Double>>>? = null): Map<Int, DoubleArray> {
    val joints = requireNotNull(PRESETS[preset]) { "unknown part preset: $preset" }
    val (w, h) = videoSize(doc)
    val boxes = HashMap<Int, DoubleArray>(); val best = HashMap<Int, Double>()
    for ((i, s) in doc.samples.withIndex()) for (d in s.d) {
        val id = d[0].toInt()
        if (id !in ids) continue
        val x1 = d[1]; val y1 = d[2]; val x2 = d[3]; val y2 = d[4]; val score = d[5]
        var box: DoubleArray? = null
        var q = 0.0
        if (preset == "person") {
            box = doubleArrayOf((x1 + x2) / 2, (y1 + y2) / 2, x2 - x1, y2 - y1); q = score
        } else {
            val row = if (kps != null) kps[i][id.toString()] else s.kp?.get(id.toString())
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val cs = ArrayList<Double>()
            if (row != null && row.size >= 3 * N_KPT) for (j in joints) {
                if (row[3 * j + 2] >= cfg.kpMinConf) {
                    xs.add(row[3 * j] * w); ys.add(row[3 * j + 1] * h); cs.add(row[3 * j + 2])
                } else if (cfg.kpInfer) {
                    inferJoint(row, j, w, h, cfg.kpMinConf)?.let { xs.add(it[0]); ys.add(it[1]); cs.add(it[2]) }
                }
            }
            if (xs.isNotEmpty()) {
                val m = cfg.partMargin * h
                val side = max(cfg.kpBox * (y2 - y1) * h, 8.0)
                val bw = max(xs.max() - xs.min() + 2 * m, side); val bh = max(ys.max() - ys.min() + 2 * m, side)
                box = doubleArrayOf((xs.max() + xs.min()) / 2 / w, (ys.max() + ys.min()) / 2 / h, bw / w, bh / h)
                q = cs.sum() / cs.size
            } else {
                val (top, bot) = REGION.getValue(preset)
                val ry1 = y1 + (y2 - y1) * top; val ry2 = y1 + (y2 - y1) * bot
                box = doubleArrayOf((x1 + x2) / 2, (ry1 + ry2) / 2, x2 - x1, ry2 - ry1); q = -1.0 + score
            }
        }
        if (q >= (best[i] ?: -2.0)) { boxes[i] = box!!; best[i] = q }
    }
    return boxes
}

/** Views for a target: preset group, single joint (`part`) or the whole person; linked IDs count as the same target. */
fun buildTargetViews(doc: TrackDoc, target: Target, cfg: Config): List<ViewKey> {
    val ts = doc.samples.map { it.t }
    val ids = setOf(target.id) + target.linked
    val boxes = when {
        target.free != null -> {
            // Part 16a: any tapped spot; fused multi-pass point, then the same camera-compensated off-screen propagation
            val b = freePointBoxes(doc, target.free, cfg)
            if (cfg.kpWorld && cfg.kpHoldS > 0) propagateBoxes(doc, b, cfg) else b
        }
        target.preset != null -> {
            // Part 10b (`offline.py::build_preset_views`): per-joint One-Euro, then off-screen propagation
            val part = target.preset != "person"
            val kps = if (part && cfg.kpFilter) smoothKeypoints(doc, ids, cfg) else null
            val b = presetBoxes(doc, ids, target.preset, cfg, kps)
            if (part && cfg.kpWorld && cfg.kpHoldS > 0) propagateBoxes(doc, b, cfg) else b
        }
        target.part != null -> jointBoxes(doc, ids, target.part, cfg)
        else -> personBoxes(doc, ids)
    }
    return viewsFromBoxes(ts, boxes, cfg, cutIndices(doc))
}

/** `live.py::pick_track` on pixel boxes: inside a box -> smallest centre offset (then area); else nearest within `tol`. */
fun pickTrack(tracks: List<Track>, px: Double, py: Double, tol: Double = 0.0): Track? {
    val hits = tracks.filter { it.bbox.contains(px, py) }
    if (hits.isNotEmpty()) {
        return hits.minWithOrNull(
            compareBy<Track> {
                val nx = (px - it.bbox.cx) / max(it.bbox.w, 1e-6); val ny = (py - it.bbox.cy) / max(it.bbox.h, 1e-6)
                nx * nx + ny * ny
            }.thenBy { it.bbox.w * it.bbox.h },
        )
    }
    if (tol <= 0) return null
    fun gap(t: Track): Double {
        val b = t.bbox
        return hypot(max(max(b.x1 - px, 0.0), px - b.x2), max(max(b.y1 - py, 0.0), py - b.y2))
    }
    return tracks.filter { gap(it) <= tol }.minByOrNull { gap(it) }
}

/**
 * `scan.py::resolve_selection`, point case: tap at normalized (`nx`, `ny`) at time `t` -> (person ID, joint or null).
 * A joint within `kpTol` x person height wins (nearest); otherwise the person under/near the tap (`pickTol`).
 * Rectangle selection: `resolveRect`.
 */
fun resolveTap(doc: TrackDoc, t: Double, nx: Double, ny: Double, cfg: Config): Pair<Int, Int?>? {
    val s = doc.samples.minByOrNull { abs(it.t - t) } ?: return null
    val (w, h) = videoSize(doc)
    val px = nx * w; val py = ny * h
    val persons = LinkedHashMap<Int, DoubleArray>()
    for (d in s.d) persons[d[0].toInt()] = doubleArrayOf(d[1] * w, d[2] * h, d[3] * w, d[4] * h)
    var bestD = Double.MAX_VALUE; var bestId = -1; var bestK = -1
    for ((id, b) in persons) {
        val row = s.kp?.get(id.toString()) ?: continue
        if (row.size < 3 * N_KPT) continue
        val ph = b[3] - b[1]
        var jd = Double.MAX_VALUE; var jk = -1
        for (k in 0 until row.size / 3) {
            if (row[3 * k + 2] < cfg.kpMinConf) continue
            val d = hypot(row[3 * k] * w - px, row[3 * k + 1] * h - py)
            if (d > cfg.kpTol * ph) continue
            if (d < jd) { jd = d; jk = k }
        }
        if (jk >= 0 && jd < bestD) { bestD = jd; bestId = id; bestK = jk }
    }
    if (bestK >= 0) return bestId to bestK
    val tracks = s.d.map { Track(it[0].toInt(), BBox(it[1] * w, it[2] * h, it[3] * w, it[4] * h), it[5], 0, 0) }
    return pickTrack(tracks, px, py, cfg.pickTol * h)?.let { it.id to null }
}

/**
 * `scan.py::resolve_selection`, rectangle case (Part 10b): the person holding >= 50% of the rectangle's area (largest
 * share, then the smaller box); a rectangle no person holds falls back to the person at its centre (`pickTol`). A
 * rectangle at most 30% of the person's height with a confident joint inside returns that joint (nearest to the
 * centre). Normalized corners in any order.
 */
fun resolveRect(doc: TrackDoc, t: Double, x1: Double, y1: Double, x2: Double, y2: Double, cfg: Config): Pair<Int, Int?>? {
    val s = doc.samples.minByOrNull { abs(it.t - t) } ?: return null
    val (w, h) = videoSize(doc)
    val px1 = min(x1, x2) * w; val py1 = min(y1, y2) * h; val px2 = max(x1, x2) * w; val py2 = max(y1, y2) * h
    val cx = (px1 + px2) / 2; val cy = (py1 + py2) / 2
    val persons = LinkedHashMap<Int, DoubleArray>()
    for (d in s.d) persons[d[0].toInt()] = doubleArrayOf(d[1] * w, d[2] * h, d[3] * w, d[4] * h)
    val area = max((px2 - px1) * (py2 - py1), 1e-9)
    var bestId = -1; var bestFrac = 0.0; var bestNegArea = 0.0
    for ((id, b) in persons) {
        val iw = min(px2, b[2]) - max(px1, b[0]); val ih = min(py2, b[3]) - max(py1, b[1])
        val frac = max(iw, 0.0) * max(ih, 0.0) / area
        val negArea = -(b[2] - b[0]) * (b[3] - b[1])
        if (frac >= 0.5 && (bestId < 0 || frac > bestFrac || (frac == bestFrac && negArea > bestNegArea))) {
            bestId = id; bestFrac = frac; bestNegArea = negArea
        }
    }
    if (bestId < 0) {
        val tracks = s.d.map { Track(it[0].toInt(), BBox(it[1] * w, it[2] * h, it[3] * w, it[4] * h), it[5], 0, 0) }
        val nx = (min(x1, x2) + max(x1, x2)) / 2; val ny = (min(y1, y2) + max(y1, y2)) / 2 // like pick_target_id: normalized centre * size
        return pickTrack(tracks, nx * w, ny * h, cfg.pickTol * h)?.let { it.id to null }
    }
    val pb = persons.getValue(bestId)
    val row = s.kp?.get(bestId.toString())
    if ((py2 - py1) <= 0.3 * (pb[3] - pb[1]) && row != null && row.size >= 3 * N_KPT) {
        var bd = Double.MAX_VALUE; var bk = -1
        for (k in 0 until row.size / 3) {
            if (row[3 * k + 2] < cfg.kpMinConf) continue
            val x = row[3 * k] * w; val y = row[3 * k + 1] * h
            if (x < px1 || x > px2 || y < py1 || y > py2) continue
            val d = hypot(x - cx, y - cy)
            if (d < bd) { bd = d; bk = k }
        }
        if (bk >= 0) return bestId to bk
    }
    return bestId to null
}
