package com.takipli.oynat.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// File-level so the nested FakeFlow (not an inner class) can use them too.
private fun points(n: Int, w: Double, h: Double, seed: Long): DoubleArray {
    val r = Random(seed)
    return DoubleArray(2 * n) { if (it % 2 == 0) r.nextDouble() * w else r.nextDouble() * h }
}

private fun transform(src: DoubleArray, a: Double, b: Double, tx: Double, ty: Double) =
    DoubleArray(src.size) { i -> if (i % 2 == 0) a * src[i] - b * src[i + 1] + tx else b * src[i - 1] + a * src[i] + ty }

/** Part 8: camera motion on synthetic shifts (feature tracking is faked; OpenCV runs only on the phone). */
class GmcTest {
    private fun near(e: Double, a: Double, tol: Double, msg: String = "") =
        assertTrue(abs(e - a) <= tol, "$msg expected $e got $a")

    @Test
    fun similarityRecoversShiftRotationScaleWithOutliers() {
        val th = 3 * PI / 180
        val a = 1.05 * cos(th); val b = 1.05 * sin(th)
        val src = points(120, 480.0, 270.0, 1)
        val dst = transform(src, a, b, 14.0, -9.0)
        val r = Random(5)
        for (k in 0 until 36) { dst[2 * k] += (r.nextDouble() - 0.5) * 300; dst[2 * k + 1] += (r.nextDouble() - 0.5) * 300 } // 30% outliers
        for (k in 36 until 120) { dst[2 * k] += r.nextGaussian() * 0.3; dst[2 * k + 1] += r.nextGaussian() * 0.3 }
        val s = assertNotNull(estimateSimilarity(src, dst))
        near(a, s.a, 0.01, "a"); near(b, s.b, 0.01, "b"); near(14.0, s.tx, 1.0, "tx"); near(-9.0, s.ty, 1.0, "ty")
        assertTrue(s.inliers >= 80)
    }

    @Test
    fun similarityRejectsGarbageAndTinyInput() {
        val src = points(40, 480.0, 270.0, 2)
        val noise = points(40, 480.0, 270.0, 3)
        assertNull(estimateSimilarity(src, noise)) // no consistent motion: fewer than 8 inliers
        assertNull(estimateSimilarity(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 2.0)))
    }

    /** Fake tracker: reports every point moved by (dx, dy) in camera pixels, records the mask it was given. */
    private class FakeFlow(var dx: Double = 0.0, var dy: Double = 0.0, var fail: Boolean = false) : FlowTracker {
        var lastMask: List<IntArray> = emptyList()
        override fun match(prev: GrayImage, cur: GrayImage, mask: List<IntArray>): FlowMatches? {
            lastMask = mask
            if (fail) return null
            val p = points(60, prev.w.toDouble(), prev.h.toDouble(), 9)
            return FlowMatches(transform(p, 1.0, 0.0, dx, dy), p)
        }
    }

    private fun gray(w: Int, h: Int) = GrayImage(w, h, ByteArray(w * h))

    @Test
    fun pureShiftAtHalfResolutionScalesToFullResolution() {
        val flow = FakeFlow()
        val gm = CameraMotion(flow, camSide = 480)
        val (cw, ch) = camSize(1920, 1080)
        assertEquals(480 to 270, cw to ch)
        assertTrue(gm.step(1920, 1080, gray(cw, ch)).contentEquals(identity3())) // first frame = start
        assertFalse(gm.ok)
        // content moves right by 10 cam-px => 40 full-res px (scale 0.25); `cur -> prev` is the negative shift
        flow.dx = -10.0; flow.dy = 5.0 // points in cur are at prev + (dx, dy)? here cur = p + d, prev = p
        // FakeFlow puts cur = p + (dx,dy); cur->prev maps back by (-dx,-dy); fwd (prev->cur) = +(dx,dy)*4
        val m = gm.step(1920, 1080, gray(cw, ch))
        assertTrue(gm.ok)
        near(-40.0, m[2], 0.5, "tx"); near(20.0, m[5], 0.5, "ty"); near(1.0, m[0], 1e-3, "scale")
        // two equal steps accumulate in W (frame -> world = product of `back`)
        gm.step(1920, 1080, gray(cw, ch))
        val (wx, wy) = gm.toWorld(0.0, 0.0)
        near(80.0, wx, 1.0, "world x"); near(-40.0, wy, 1.0, "world y")
        val (fx, fy) = gm.toFrame(wx, wy)
        near(0.0, fx, 1e-6); near(0.0, fy, 1e-6)
    }

    @Test
    fun hugeJumpIsACutNotACameraMove() {
        val flow = FakeFlow()
        val gm = CameraMotion(flow)
        gm.step(1920, 1080, gray(480, 270))
        flow.dx = 150.0 // 600 px at full res: below 0.5 * 1920 = 960, stays a pan
        gm.step(1920, 1080, gray(480, 270))
        assertTrue(gm.ok)
        flow.dx = 300.0 // 1200 px > 960 => cut-sized
        val m = gm.step(1920, 1080, gray(480, 270))
        assertFalse(gm.ok)
        assertTrue(m.contentEquals(identity3()))
    }

    @Test
    fun failureResetAndSizeChange() {
        val flow = FakeFlow(dx = 3.0)
        val gm = CameraMotion(flow)
        gm.step(1280, 720, gray(480, 270))
        gm.step(1280, 720, gray(480, 270))
        assertTrue(gm.ok)
        flow.fail = true
        assertTrue(gm.step(1280, 720, gray(480, 270)).contentEquals(identity3()))
        assertFalse(gm.ok)
        flow.fail = false
        gm.reset()
        assertTrue(gm.world.contentEquals(identity3()))
        gm.step(1280, 720, gray(480, 270)) // first frame after reset: no estimate
        assertFalse(gm.ok)
        gm.step(1280, 720, gray(480, 270)); assertTrue(gm.ok)
        gm.step(1920, 1080, gray(480, 270)) // frame size changed: restart
        assertFalse(gm.ok)
        assertTrue(gm.world.contentEquals(identity3()))
    }

    @Test
    fun foregroundBoxesBecomeScaledMasks() {
        val flow = FakeFlow()
        val gm = CameraMotion(flow, camSide = 480)
        gm.step(1920, 1080, gray(480, 270))
        gm.step(1920, 1080, gray(480, 270), listOf(BBox(400.0, 200.0, 800.0, 600.0), BBox(-50.0, -10.0, 40.0, 40.0)))
        val m = flow.lastMask
        assertEquals(listOf(100, 50, 201, 151), m[0].toList())
        assertEquals(listOf(0, 0, 11, 11), m[1].toList())
    }

    @Test
    fun matrixHelpers() {
        val m = doubleArrayOf(2.0, 0.0, 5.0, 0.0, 2.0, -3.0, 0.0, 0.0, 1.0)
        val p = mul3(m, inv3(m))
        for (i in 0 until 9) near(identity3()[i], p[i], 1e-12)
    }
}
