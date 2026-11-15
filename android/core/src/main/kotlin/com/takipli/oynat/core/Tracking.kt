package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.min

/** Port of `tracker/geometry.py::BBox` (pixels or normalized, caller's choice). */
data class BBox(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val w: Double get() = max(x2 - x1, 0.0)
    val h: Double get() = max(y2 - y1, 0.0)
    val cx: Double get() = (x1 + x2) / 2
    val cy: Double get() = (y1 + y2) / 2

    fun scaled(sx: Double, sy: Double) = BBox(x1 * sx, y1 * sy, x2 * sx, y2 * sy)

    fun contains(px: Double, py: Double) = px in x1..x2 && py in y1..y2

    companion object {
        fun fromXywh(x: Double, y: Double, w: Double, h: Double) = BBox(x, y, x + w, y + h)
    }
}

/** Detector output: box in frame pixels + score. */
data class Detection(val bbox: BBox, val score: Double, val cls: Int = 0)

/** `age` = frames since birth; `lost` = consecutive unmatched frames (0 = seen this frame). */
data class Track(val id: Int, val bbox: BBox, val score: Double, val age: Int, val lost: Int)

/**
 * Port of `tracker/bytetrack.py::linear_assignment` (Hungarian, O(n^3), rectangular).
 * Same loop order and strict comparisons as Python so ties resolve identically.
 */
fun linearAssignment(cost: Array<DoubleArray>): List<Pair<Int, Int>> {
    if (cost.isEmpty() || cost[0].isEmpty()) return emptyList()
    val transposed = cost.size > cost[0].size
    val c = if (transposed) Array(cost[0].size) { j -> DoubleArray(cost.size) { i -> cost[i][j] } } else cost
    val n = c.size
    val m = c[0].size // n <= m
    val inf = Double.POSITIVE_INFINITY
    val u = DoubleArray(n + 1)
    val v = DoubleArray(m + 1)
    val p = IntArray(m + 1)
    val way = IntArray(m + 1)
    for (i in 1..n) {
        p[0] = i
        var j0 = 0
        val minv = DoubleArray(m + 1) { inf }
        val used = BooleanArray(m + 1)
        while (true) {
            used[j0] = true
            val i0 = p[j0]
            var delta = inf
            var j1 = 0
            for (j in 1..m) {
                if (used[j]) continue
                val cur = c[i0 - 1][j - 1] - u[i0] - v[j]
                if (cur < minv[j]) {
                    minv[j] = cur
                    way[j] = j0
                }
                if (minv[j] < delta) {
                    delta = minv[j]
                    j1 = j
                }
            }
            for (j in 0..m) {
                if (used[j]) {
                    u[p[j]] += delta
                    v[j] -= delta
                } else {
                    minv[j] -= delta
                }
            }
            j0 = j1
            if (p[j0] == 0) break
        }
        while (j0 != 0) {
            val j1 = way[j0]
            p[j0] = p[j1]
            j0 = j1
        }
    }
    val pairs = (1..m).filter { p[it] != 0 }.map { (p[it] - 1) to (it - 1) }
    return if (transposed) pairs.map { (a, b) -> b to a } else pairs
}

fun iouMatrix(a: List<BBox>, b: List<BBox>): Array<DoubleArray> =
    Array(a.size) { i ->
        DoubleArray(b.size) { j ->
            val p = a[i]
            val q = b[j]
            val iw = min(p.x2, q.x2) - max(p.x1, q.x1)
            val ih = min(p.y2, q.y2) - max(p.y1, q.y1)
            if (iw <= 0 || ih <= 0) {
                0.0
            } else {
                val inter = iw * ih
                val union = p.w * p.h + q.w * q.h - inter
                if (union > 0) inter / union else 0.0
            }
        }
    }

data class MatchResult(
    val pairs: List<Pair<Int, Int>>,
    val unmatchedTracks: List<Int>,
    val unmatchedDets: List<Int>,
)

/** IoU matching: (matches, unmatched track idx, unmatched detection idx). */
fun match(tracks: List<BBox>, dets: List<BBox>, iouThr: Double): MatchResult {
    if (tracks.isEmpty() || dets.isEmpty()) {
        return MatchResult(emptyList(), tracks.indices.toList(), dets.indices.toList())
    }
    val iou = iouMatrix(tracks, dets)
    val cost = Array(tracks.size) { i -> DoubleArray(dets.size) { j -> 1.0 - iou[i][j] } }
    val pairs = linearAssignment(cost).filter { (i, j) -> iou[i][j] >= iouThr }
    val mt = pairs.map { it.first }.toSet()
    val md = pairs.map { it.second }.toSet()
    return MatchResult(
        pairs,
        tracks.indices.filter { it !in mt },
        dets.indices.filter { it !in md },
    )
}
