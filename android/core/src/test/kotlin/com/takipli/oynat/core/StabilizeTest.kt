package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun apply3(m: DoubleArray, x: Double, y: Double) =
    doubleArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

/** EIS-style stabilization: crop matrix, zoom fit, camera path bookkeeping, and a shaky-camera plan that keeps the target still. */
class StabilizeTest {
    private val fw = 1920.0
    private val fh = 1080.0

    private fun ndc(px: Double, py: Double) = doubleArrayOf(2 * px / fw - 1, 1 - 2 * py / fh)

    @Test
    fun cropMatrixWithoutRollEqualsTheOldExportMatrix() {
        val v = View(1.0, 0.5 - 0.3, 0.5 - 0.6) // 2x zoom, centre (0.3, 0.6)
        val n = exportNdc(v)
        val m = cropMatrixNdc(0.3 * fw, 0.6 * fh, 2.0, 0.0, fw, fh)
        for ((x, y) in listOf(0.2 to -0.4, -0.9 to 0.7, 0.0 to 0.0)) {
            val o = apply3(m, x, y)
            assertEquals(n[0] * (x + n[1]), o[0], 1e-9)
            assertEquals(n[0] * (y + n[2]), o[1], 1e-9)
        }
    }

    @Test
    fun cropMatrixRotatesAroundTheCropCentre() {
        val phi = 0.2; val z = 1.5; val cx = 1000.0; val cy = 500.0
        val m = cropMatrixNdc(cx, cy, z, phi, fw, fh)
        // a point 100 px along the crop's own x axis lands 100 * z px right of the output centre, same row
        val p = ndc(cx + 100 * cos(phi), cy + 100 * sin(phi))
        val o = apply3(m, p[0], p[1])
        assertEquals(fw / 2 + 100 * z, (o[0] + 1) * fw / 2, 1e-6)
        assertEquals(fh / 2, (1 - o[1]) * fh / 2, 1e-6)
        // the crop centre goes to the middle
        val c = apply3(m, ndc(cx, cy)[0], ndc(cx, cy)[1])
        assertEquals(0.0, c[0], 1e-9); assertEquals(0.0, c[1], 1e-9)
    }

    @Test
    fun zoomToFitKeepsTheRotatedCropInsideTheFrame() {
        assertEquals(1.0, zoomToFit(0.0, 0.0, 0.0, fw, fh), 1e-12)
        assertTrue(zoomToFit(0.0, 0.0, 0.05, fw, fh) > 1.0)
        for ((dx, dy, phi) in listOf(Triple(50.0, 30.0, 0.03), Triple(-120.0, 80.0, -0.05), Triple(200.0, -90.0, 0.1))) {
            val z = zoomToFit(dx, dy, phi, fw, fh)
            val ex = fw / (2 * z) * abs(cos(phi)) + fh / (2 * z) * abs(sin(phi))
            val ey = fw / (2 * z) * abs(sin(phi)) + fh / (2 * z) * abs(cos(phi))
            assertTrue(abs(dx) + ex <= fw / 2 + 1e-6)
            assertTrue(abs(dy) + ey <= fh / 2 + 1e-6)
        }
    }

    @Test
    fun builderComposesStepsAndRestartsAtCuts() {
        val b = CamPathBuilder(1000)
        b.add(0.0, null, false)
        // content moved +10 px between the frames: frame -> world shifts by -10
        b.add(0.033, doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), false)
        b.add(0.066, null, true)
        val p = b.build("sig")
        assertEquals(3, p.size)
        assertEquals(-0.01, p.tx[1], 1e-12)
        assertEquals(intArrayOf(0, 0, 1).toList(), p.shot.toList())
        assertEquals(0.0, p.tx[2], 1e-12) // world restarted
        assertTrue(plausibleCameraStep(doubleArrayOf(1.0, 0.0, 10.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
        assertTrue(!plausibleCameraStep(doubleArrayOf(2.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0)))
        assertTrue(!plausibleCameraStep(doubleArrayOf(cos(1.0), -sin(1.0), 0.0, sin(1.0), cos(1.0), 0.0, 0.0, 0.0, 1.0)))
    }

    @Test
    fun camPathSurvivesTheJsonRoundTripAndRejectsGarbage() {
        val b = CamPathBuilder(1080)
        for (k in 0 until 5) b.add(k / 30.0, if (k == 0) null else doubleArrayOf(1.0, 0.0, 1.5, 0.0, 1.0, -0.5, 0.0, 0.0, 1.0), false)
        val p = b.build("abc")
        val q = assertNotNull(CamPath.decode(CamPath.encode(p)))
        assertEquals("abc", q.sig)
        assertEquals(p.size, q.size)
        assertEquals(p.tx[4], q.tx[4], 1e-12)
        assertNull(CamPath.decode("{not json"))
        assertNull(CamPath.decode("""{"sig":"x","t":[0.0,1.0],"shot":[0],"a":[1.0],"b":[0.0],"tx":[0.0],"ty":[0.0]}"""))
    }

    @Test
    fun shakyCameraCannotMoveTheTarget() {
        val n = 90
        val offX = DoubleArray(n) { 25 * sin(it * 2.3) }; val offY = DoubleArray(n) { 15 * cos(it * 1.7) }
        // pure shifts: frame -> world = + offset
        val t = DoubleArray(n) { it / 30.0 }
        val path = CamPath("s", t, IntArray(n), DoubleArray(n) { 1.0 }, DoubleArray(n), DoubleArray(n) { offX[it] / fh }, DoubleArray(n) { offY[it] / fh })
        // the target stands still in the world at (900, 500); the follow-cam views (10 fps, 2x zoom) track it in frame coordinates
        val views = (0 until n step 3).map { k -> listOf(t[k], 1.0, 0.5 - (900 - offX[k]) / fw, 0.5 - (500 - offY[k]) / fh) }
        val plan = StabPlan(path, views, fw.toInt(), fh.toInt())
        for (k in 5 until n - 5) {
            val px = 900 - offX[k]; val py = 500 - offY[k] // where the target really is in frame k
            val p = ndc(px, py)
            val o = apply3(plan.ndcMatrix((t[k] * 1e6).roundToLong()), p[0], p[1])
            assertTrue(abs(o[0]) < 1e-5 && abs(o[1]) < 1e-5, "frame $k: target at ${o[0]}, ${o[1]}")
        }
        assertEquals(0, plan.slidFrames)
        assertTrue(plan.peakBoost < 1.01)
    }

    @Test
    fun rollIsLevelledAndAZoomBoostKeepsTheCropInsideTheFrame() {
        val n = 120
        val t = DoubleArray(n) { it / 30.0 }
        // fast roll shake of +-0.04 rad, no translation; views = full frame at zoom 1
        val th = DoubleArray(n) { 0.04 * sin(it * 1.9) }
        val path = CamPath("s", t, IntArray(n), DoubleArray(n) { cos(th[it]) }, DoubleArray(n) { sin(th[it]) }, DoubleArray(n), DoubleArray(n))
        val views = listOf(listOf(0.0, 0.0, 0.0, 0.0), listOf(t[n - 1], 0.0, 0.0, 0.0))
        val plan = StabPlan(path, views, fw.toInt(), fh.toInt())
        for (k in 0 until n) {
            val p = plan.paramsAt(t[k]) // cx, cy, zoom, phi
            assertTrue(p[2] >= 1.0)
            val c = abs(cos(p[3])); val s = abs(sin(p[3]))
            val ex = fw / (2 * p[2]) * c + fh / (2 * p[2]) * s
            val ey = fw / (2 * p[2]) * s + fh / (2 * p[2]) * c
            assertTrue(p[0] - ex >= -1e-6 && p[0] + ex <= fw + 1e-6, "x out of frame at $k")
            assertTrue(p[1] - ey >= -1e-6 && p[1] + ey <= fh + 1e-6, "y out of frame at $k")
        }
        assertTrue(plan.peakBoost > 1.0 && plan.peakBoost < 1.3) // some zoom is needed to hide the roll, not a lot
    }
}
