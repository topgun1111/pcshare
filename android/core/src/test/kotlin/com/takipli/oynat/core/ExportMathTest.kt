package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Part 14 cross-test: the export matrix cuts the same rectangle as `cropRect` (<= 1 px) at 20 times. */
class ExportMathTest {
    private fun row(): List<Double> {
        val pts = mapOf(0 to (0.30 to 0.15), 15 to (0.27 to 0.85), 16 to (0.33 to 0.85))
        return (0 until N_KPT).flatMap { j -> pts[j]?.let { listOf(it.first, it.second, 0.9) } ?: listOf(0.0, 0.0, 0.0) }
    }

    @Test
    fun knownViews() {
        val a = exportNdc(View(0.0, 0.3, -0.2)) // whole frame
        assertEquals(1.0, a[0], 1e-12); assertEquals(0.0, a[1], 1e-12); assertEquals(0.0, a[2], 1e-12)
        val b = exportNdc(View(1.0, 0.2, 0.0)) // 2x, crop centre x = 0.3 -> NDC -0.4 -> translate +0.4
        assertEquals(2.0, b[0], 1e-12); assertEquals(0.4, b[1], 1e-12); assertEquals(0.0, b[2], 1e-12)
        val c = exportNdc(View(1.0, 0.0, 0.2)) // centre y = 0.3 (down) -> NDC y +0.4 (up) -> translate -0.4
        assertEquals(-0.4, c[2], 1e-12)
    }

    @Test
    fun matrixCropMatchesCropRectAt20Times() {
        val doc = TrackDoc(
            version = 2, video = VideoInfo(w = 1920, h = 1080, fps = 30.0, duration = 2.0), sampleFps = 10.0,
            samples = (0..19).map { i ->
                val x = 0.2 + 0.02 * i // person walks right
                Sample(i * 0.1, d = listOf(listOf(1.0, x, 0.1, x + 0.2, 0.9, 0.9)), kp = mapOf("1" to row().mapIndexed { k, v -> if (k % 3 == 0 && v != 0.0) v + 0.02 * i else v }))
            },
        )
        val views = buildTargetViews(doc, Target(1, preset = "left_foot"), Config())
        assertTrue(views.isNotEmpty())
        for (i in 0 until 20) {
            val t = 1.9 * i / 19.0
            val v = viewAt(views, t)
            val n = exportNdc(v)
            val half = 0.5 / n[0]
            val cx = (1 - n[1]) / 2; val cy = (1 + n[2]) / 2
            val r = cropRect(v, 1920, 1080)
            assertTrue(abs((cx - half) * 1920 - r.x) <= 1.0 + 1e-6, "x at t=$t")
            assertTrue(abs((cy - half) * 1080 - r.y) <= 1.0 + 1e-6, "y at t=$t")
            assertTrue(abs(2 * half * 1920 - r.w) <= 1.0 + 1e-6, "w at t=$t")
            assertTrue(abs(2 * half * 1080 - r.h) <= 1.0 + 1e-6, "h at t=$t")
        }
    }

    @Test
    fun bitrate() {
        assertTrue(abs(exportBitrate(1920, 1080, 30.0, false) - 6_842_880) <= 1)
        assertTrue(exportBitrate(1920, 1080, 30.0, true) < exportBitrate(1920, 1080, 30.0, false))
        assertEquals(2_000_000, exportBitrate(160, 90, 30.0, false)) // lower clamp
        assertEquals(100_000_000, exportBitrate(7680, 4320, 60.0, false)) // upper clamp
        assertEquals(exportBitrate(1280, 720, 30.0, false), exportBitrate(1280, 720, 0.0, false)) // unknown fps -> 30
    }
}
