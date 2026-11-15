package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.pow

// ---- Part 12: what the live scan preview draws for one sample. All coordinates normalized to the frame (0..1). ----

/** One tracked person: box `[x1, y1, x2, y2]`, keypoints `[x, y, conf] * 17` (null = no pose for this ID in this sample). */
class PersonOverlay(val id: Int, val box: DoubleArray, val kp: DoubleArray?, val isTarget: Boolean)

/** `partBox` = box of the chosen part, `crop` = the rectangle the follow camera would show (both `[x1, y1, x2, y2]`); null when there is no target in this sample. */
class PreviewOverlay(val persons: List<PersonOverlay>, val partBox: DoubleArray?, val crop: DoubleArray?)

/** Crop the camera would use for a `[cx, cy, w, h]` (normalized) box: `computeView` + the centre/size maths of `cropRect`, unrounded. */
fun cropForBox(box: DoubleArray, w: Int, h: Int, cfg: Config): DoubleArray {
    val bw = box[2] * w; val bh = box[3] * h
    val b = BBox(box[0] * w - bw / 2, box[1] * h - bh / 2, box[0] * w + bw / 2, box[1] * h + bh / 2)
    val v = computeView(b, w.toDouble(), h.toDouble(), cfg.targetFill, cfg.minZoom, cfg.maxZoom)
    val half = 0.5 / 2.0.pow(max(v.zoom, 0.0))
    val cx = 0.5 - v.panX; val cy = 0.5 - v.panY
    return doubleArrayOf(cx - half, cy - half, cx + half, cy + half)
}

/** Raw (unsmoothed) part box of `target` in this one sample, `[cx, cy, w, h]`, or null when the person/part is absent. */
private fun partBoxNow(s: Sample, w: Int, h: Int, target: Target, cfg: Config): DoubleArray? {
    val doc = TrackDoc(version = 2, video = VideoInfo(w = w, h = h), samples = listOf(s))
    val ids = setOf(target.id) + target.linked
    val boxes = when {
        target.preset != null -> presetBoxes(doc, ids, target.preset, cfg)
        target.part != null -> jointBoxes(doc, ids, target.part, cfg)
        else -> personBoxes(doc, ids)
    }
    return boxes[0]
}

fun previewOverlay(s: Sample, w: Int, h: Int, target: Target?, cfg: Config): PreviewOverlay {
    val ids = target?.let { setOf(it.id) + it.linked }.orEmpty()
    val persons = s.d.map { d ->
        val id = d[0].toInt()
        PersonOverlay(id, doubleArrayOf(d[1], d[2], d[3], d[4]), s.kp?.get(id.toString())?.toDoubleArray(), id in ids)
    }
    val box = target?.let { partBoxNow(s, w, h, it, cfg) }
    val part = box?.let { doubleArrayOf(it[0] - it[2] / 2, it[1] - it[3] / 2, it[0] + it[2] / 2, it[1] + it[3] / 2) }
    return PreviewOverlay(persons, part, box?.let { cropForBox(it, w, h, cfg) })
}

/** Target for the preview while the scan runs: the pick resolved on the samples so far (null until a person is at the pick). */
fun livePickTarget(samples: List<Sample>, w: Int, h: Int, pick: TargetPick, cfg: Config): Target? =
    if (samples.isEmpty()) null else resolvePick(TrackDoc(version = 2, video = VideoInfo(w = w, h = h), samples = samples), pick, cfg)
