package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SamplingTest {
    private fun pick(sel: SampleSelector, pts: List<Double>) = pts.filter { sel.accept(it) }
    private fun cfr(fps: Double, seconds: Double) = (0 until (fps * seconds).toInt()).map { it / fps }

    @Test
    fun cfr30to10() {
        val got = pick(SampleSelector(10.0), cfr(30.0, 1.0))
        assertEquals(10, got.size)
        got.forEachIndexed { i, t -> assertTrue(kotlin.math.abs(t - i * 0.1) < 1e-6, "sample $i at $t") }
    }

    @Test
    fun scanFasterThanSourceTakesEveryFrame() {
        assertEquals(30, pick(SampleSelector(60.0), cfr(30.0, 1.0)).size)
        assertEquals(30, pick(SampleSelector(30.0), cfr(30.0, 1.0)).size)
    }

    @Test
    fun variableFrameRateKeepsRealTimestamps() {
        // 30 fps, then a 0.5 s hole, then 12 fps
        val pts = cfr(30.0, 0.5) + (0 until 6).map { 1.0 + it / 12.0 }
        val got = pick(SampleSelector(10.0), pts)
        assertTrue(got.all { it in pts }) // timestamps are the frames' own
        assertEquals(got.sorted(), got)
        // the hole yields a single sample, not a burst of duplicates
        assertEquals(1, got.count { it in 0.5..1.0 })
        // each sample is late by at most one source frame (0.5 s hole excepted)
        assertTrue(got.filter { it < 0.5 }.mapIndexed { i, t -> t - i * 0.1 }.all { it < 1.0 / 30 + 1e-6 })
    }

    @Test
    fun resumeFromMiddleStaysOnGrid() {
        val all = cfr(30.0, 4.0)
        val full = pick(SampleSelector(10.0), all)
        val resumed = pick(SampleSelector(10.0, startS = 2.0), all)
        assertEquals(full.filter { it >= 2.0 - 1e-9 }, resumed)
        assertTrue(resumed.first() >= 2.0 - 1e-3)
    }

    @Test
    fun endTimeStops() {
        val sel = SampleSelector(10.0, endS = 1.0)
        val got = pick(sel, cfr(30.0, 3.0))
        assertTrue(got.last() <= 1.0 + 1e-3)
        assertTrue(sel.isPastEnd(1.1))
        assertFalse(sel.isPastEnd(1.0))
    }

    @Test
    fun nonMonotonicAndDuplicateTimestampsAreIgnored() {
        val sel = SampleSelector(10.0)
        assertTrue(sel.accept(0.0))
        assertFalse(sel.accept(0.0)) // duplicate
        assertTrue(sel.accept(0.11))
        assertFalse(sel.accept(0.05)) // goes backwards
    }

    @Test
    fun rotationAndSizes() {
        assertEquals(90, normalizeRotation(90)); assertEquals(270, normalizeRotation(-90))
        assertEquals(0, normalizeRotation(360)); assertEquals(180, normalizeRotation(540))
        assertEquals(1080 to 1920, displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, displaySize(1920, 1080, 180))
        assertEquals(960 to 540, scaledSize(1920, 1080, 960))
        assertEquals(640 to 480, scaledSize(640, 480, 960)) // no upscaling
        assertEquals(2 to 240, scaledSize(1, 480, 240)) // sides stay >= 2
    }

    @Test
    fun yuvConversion() {
        assertEquals(0xFFFFFFFF.toInt(), yuv709ToArgb(235, 128, 128))
        assertEquals(0xFF000000.toInt(), yuv709ToArgb(16, 128, 128))
        val red = yuv709ToArgb(63, 102, 240)
        assertTrue(((red shr 16) and 255) >= 250 && ((red shr 8) and 255) <= 3 && (red and 255) <= 3, "red=${red.toString(16)}")
    }
}
