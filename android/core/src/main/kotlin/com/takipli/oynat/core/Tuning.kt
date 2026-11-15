package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---- Part 13: tuning sliders and the preview transform. ----

/** The knobs of the result preview; defaults = `Config` defaults. Changing one rebuilds the views from the saved scan (no rescan). */
data class Tuning(
    val targetFill: Double = 0.30,
    val kpBox: Double = 0.10,
    val maxZoom: Double = 2.5,
    /** EMA alpha of the zero-phase smoothing: higher = the camera follows tighter, lower = calmer. */
    val followAlpha: Double = 0.4,
    val lostHoldS: Double = 1.0,
) {
    companion object
}

fun Config.withTuning(t: Tuning): Config =
    copy(targetFill = t.targetFill, kpBox = t.kpBox, maxZoom = t.maxZoom, offlineAlpha = t.followAlpha, lostHoldS = t.lostHoldS)

fun Tuning.Companion.of(c: Config) = Tuning(c.targetFill, c.kpBox, c.maxZoom, c.offlineAlpha, c.lostHoldS)

/** Scan doc + (possibly changed) target + tuning -> new views; the doc is not modified. Same call the export will use. */
fun rebuildViews(doc: TrackDoc, target: Target, cfg: Config, t: Tuning): List<ViewKey> = buildTargetViews(doc, target, cfg.withTuning(t))

/**
 * How to show a view on a full-frame video surface (Part 13 player, same crop as `cropRect`): scale about the centre,
 * then shift. Returns `[scale, tx, ty]`, `tx`/`ty` in fractions of the surface width/height (multiply by its pixel size).
 */
fun viewTransform(v: View): DoubleArray {
    val s = 2.0.pow(max(v.zoom, 0.0))
    val half = 0.5 / s
    val cx = min(max(0.5 - v.panX, half), 1.0 - half)
    val cy = min(max(0.5 - v.panY, half), 1.0 - half)
    return doubleArrayOf(s, (0.5 - cx) * s, (0.5 - cy) * s)
}
