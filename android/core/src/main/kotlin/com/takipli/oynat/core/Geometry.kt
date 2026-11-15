package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One keyframe row of `views.json`: `[t, zoom_log2, pan_x, pan_y]`. */
typealias ViewKey = List<Double>

/** `zoom` is log2 (scale = 2^max(zoom, 0)); `panX`/`panY` are normalized. */
data class View(val zoom: Double, val panX: Double, val panY: Double)

data class CropRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Port of `tracker/offline.py::view_at`: linear between keyframes, clamped at both ends. */
fun viewAt(views: List<ViewKey>, t: Double): View {
    if (views.isEmpty()) return View(0.0, 0.0, 0.0)
    val first = views.first()
    val last = views.last()
    if (t <= first[0]) return View(first[1], first[2], first[3])
    if (t >= last[0]) return View(last[1], last[2], last[3])
    var lo = 0
    var hi = views.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (views[mid][0] <= t) lo = mid else hi = mid
    }
    val a = views[lo]
    val b = views[hi]
    val f = if (b[0] > a[0]) (t - a[0]) / (b[0] - a[0]) else 0.0
    return View(a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f)
}

/**
 * Port of `tracker/export.py::crop_rect`: pixel crop inside the frame, sizes >= 2, never below 1x.
 * Rounding is half-to-even like Python's `round` (`Math.rint`).
 */
fun cropRect(view: View, vw: Int, vh: Int): CropRect {
    val s = max(2.0.pow(max(view.zoom, 0.0)), 1.0)
    val cw = vw / s
    val ch = vh / s
    val cx = min(max((0.5 - view.panX) * vw, cw / 2), vw - cw / 2)
    val cy = min(max((0.5 - view.panY) * vh, ch / 2), vh - ch / 2)
    val w = max(Math.rint(cw).toInt(), 2)
    val h = max(Math.rint(ch).toInt(), 2)
    val x = min(max(Math.rint(cx - cw / 2).toInt(), 0), vw - w)
    val y = min(max(Math.rint(cy - ch / 2).toInt(), 0), vh - h)
    return CropRect(x, y, w, h)
}
