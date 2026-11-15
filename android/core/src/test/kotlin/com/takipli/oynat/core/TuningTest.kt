package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Part 13: tuning -> config, rebuilt views, preview transform. */
class TuningTest {
    private val cfg = Config()

    private fun row(): List<Double> {
        val pts = mapOf(0 to (0.30 to 0.15), 15 to (0.27 to 0.85), 16 to (0.33 to 0.85))
        return (0 until N_KPT).flatMap { j -> pts[j]?.let { listOf(it.first, it.second, 0.9) } ?: listOf(0.0, 0.0, 0.0) }
    }
    private val doc = TrackDoc(
        version = 2, video = VideoInfo(w = 1000, h = 1000, fps = 10.0, duration = 1.0), sampleFps = 10.0,
        samples = (0..9).map { Sample(it * 0.1, d = listOf(listOf(1.0, 0.2, 0.1, 0.4, 0.9, 0.9)), kp = mapOf("1" to row())) },
    )

    @Test
    fun defaultsMatchConfig() {
        assertEquals(Tuning(), Tuning.of(cfg))
        assertEquals(cfg, cfg.withTuning(Tuning()))
    }

    @Test
    fun tuningOverridesOnlyItsKeys() {
        val c = cfg.withTuning(Tuning(targetFill = 0.5, kpBox = 0.2, maxZoom = 1.0, followAlpha = 0.7, lostHoldS = 2.0))
        assertEquals(cfg.copy(targetFill = 0.5, kpBox = 0.2, maxZoom = 1.0, offlineAlpha = 0.7, lostHoldS = 2.0), c)
    }

    @Test
    fun maxZoomLimitsRebuiltViews() {
        val foot = Target(1, preset = "left_foot")
        val wide = rebuildViews(doc, foot, cfg, Tuning(maxZoom = 3.5))
        val capped = rebuildViews(doc, foot, cfg, Tuning(maxZoom = 0.5))
        assertTrue(wide.isNotEmpty() && capped.isNotEmpty())
        assertTrue(capped.all { it[1] <= 0.5 + 1e-9 })
        assertTrue(wide.maxOf { it[1] } > capped.maxOf { it[1] }) // the foot box is small: more zoom allowed, more zoom used
        assertEquals(buildTargetViews(doc, foot, cfg), rebuildViews(doc, foot, cfg, Tuning())) // defaults = no change
    }

    @Test
    fun presetSwitchWithoutRescan() {
        val head = rebuildViews(doc, Target(1, preset = "head"), cfg, Tuning())
        val feet = rebuildViews(doc, Target(1, preset = "feet"), cfg, Tuning())
        assertTrue(head.last()[3] > feet.last()[3]) // pan_y: head is high in the frame (pan = 0.5 - cy)
    }

    @Test
    fun transformMatchesCropCentre() {
        val a = viewTransform(View(0.0, 0.3, -0.2)) // zoom 0: whole frame, pan ignored
        assertEquals(1.0, a[0], 1e-12); assertEquals(0.0, a[1], 1e-12); assertEquals(0.0, a[2], 1e-12)
        val b = viewTransform(View(1.0, 0.2, 0.0)) // 2x: crop centre x = 0.3 -> shift +0.4 of the width
        assertEquals(2.0, b[0], 1e-12); assertEquals(0.4, b[1], 1e-12); assertEquals(0.0, b[2], 1e-12)
        val c = viewTransform(View(1.0, 0.45, -0.45)) // centre clamped to the half-crop (0.25 / 0.75)
        assertEquals(0.5, c[1], 1e-12); assertEquals(-0.5, c[2], 1e-12)
        val v = View(1.0, 0.1, 0.1)
        val r = cropRect(v, 1000, 1000)
        val t = viewTransform(v)
        assertEquals(r.x + r.w / 2.0, 1000 * (0.5 - t[1] / t[0]), 1.0) // crop centre from the transform
    }
}
