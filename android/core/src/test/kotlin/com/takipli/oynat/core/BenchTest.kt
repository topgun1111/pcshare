package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchTest {
    private fun near(e: Double, a: Double) = assertTrue(abs(e - a) < 1e-9, "expected $e, got $a")

    @Test
    fun statsOfOneToHundred() {
        val st = benchStats((1..100).map { it.toDouble() }.reversed())
        assertEquals(100, st.n)
        near(50.5, st.meanMs); near(50.5, st.medianMs)
        near(95.0, st.p95Ms); near(1.0, st.minMs); near(100.0, st.maxMs)
    }

    @Test
    fun oddCountAndSingle() {
        val st = benchStats(listOf(30.0, 10.0, 20.0))
        near(20.0, st.medianMs); near(30.0, st.p95Ms)
        val one = benchStats(listOf(40.0))
        near(40.0, one.medianMs); near(40.0, one.p95Ms); near(25.0, one.fps)
    }

    @Test
    fun emptyIsRejected() {
        assertFailsWith<IllegalArgumentException> { benchStats(emptyList()) }
    }

    @Test
    fun lineFormat() {
        val st = benchStats(listOf(80.0, 100.0))
        assertEquals(
            "Phone | yolov8s-pose | CPU x4 | 640 | synthetic | 90.0 ms/frame (median 90.0, p95 100.0, n=2) | 11.1 fps",
            benchLine("Phone", "yolov8s-pose", "CPU x4", 640, "synthetic", st),
        )
    }

    @Test
    fun skeletonIsValidCoco() {
        assertEquals(16, COCO_SKELETON.size)
        assertTrue(COCO_SKELETON.all { (a, b) -> a in 0 until N_KPT && b in 0 until N_KPT && a != b })
        assertEquals(COCO_SKELETON.size, COCO_SKELETON.toSet().size)
    }
}
