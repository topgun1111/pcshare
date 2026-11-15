package com.takipli.oynat.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreePointTest {
    // 20 samples at 10 fps; person (id 1) walks right, the tapped spot is its torso: x = 0.3 + 0.02 i, y = 0.5.
    private fun doc(pass: List<List<Double>>): TrackDoc {
        val samples = (0 until 20).map { i ->
            val cx = 0.3 + 0.02 * i
            Sample(i / 10.0, listOf(listOf(1.0, cx - 0.1, 0.2, cx + 0.1, 0.9, 0.9)))
        }
        return TrackDoc(2, VideoInfo(w = 1920, h = 1080, fps = 30.0), 10.0, samples, pt = PointTrack(a = pass))
    }

    private fun pass(): List<List<Double>> = (0 until 20).map { i ->
        when (i) {
            5 -> listOf(0.9, 0.1, 0.9)         // tracker jumped to the background
            8, 9 -> emptyList()                // lost (blur / fast motion)
            else -> listOf(0.3 + 0.02 * i, 0.5, 0.9)
        }
    }

    private val pick = FreePick(t = 0.0, x = 0.3, y = 0.5, id = 1)

    @Test
    fun jumpIsRejectedAndLossIsFilledFromAnchor() {
        val f = fuseFreePoint(doc(pass()), pick, Config())
        assertTrue(abs(f.getValue(5)[0] - 0.4) < 0.01 && abs(f.getValue(5)[1] - 0.5) < 0.01)
        for (i in listOf(8, 9)) assertTrue(abs(f.getValue(i)[0] - (0.3 + 0.02 * i)) < 0.01, "sample $i")
        assertEquals(20, f.size)
    }

    @Test
    fun targetWithFreePickBuildsViewsAtChosenZoom() {
        val d = doc(pass())
        val views = buildTargetViews(d, Target(id = 1, free = pick.copy(zoom = 1.5)), Config())
        assertTrue(views.isNotEmpty())
        val mid = views.minByOrNull { abs(it[0] - 1.0) }!!
        assertEquals(1.5, mid[1], 1e-3)
        assertNotNull(viewAtSmooth(views, 0.95))
    }

    @Test
    fun smoothSamplingHitsKeysAndDoesNotOvershoot() {
        val keys: List<ViewKey> = listOf(listOf(0.0, 1.0, 0.0, 0.0), listOf(1.0, 1.0, 0.2, 0.0), listOf(2.0, 1.0, 0.2, 0.0), listOf(3.0, 1.0, 0.4, 0.0))
        assertEquals(0.2, viewAtSmooth(keys, 1.0).panX, 1e-9)
        var prev = -1.0
        for (k in 0..300) {
            val x = viewAtSmooth(keys, k / 100.0).panX
            assertTrue(x in -1e-9..0.4 + 1e-9 && x >= prev - 1e-9, "t=${k / 100.0} x=$x")
            prev = x
        }
    }
}

class FreePickResolveTest {
    @Test
    fun freePickBecomesFreeTargetEvenWithoutPerson() {
        val d = TrackDoc(2, VideoInfo(w = 1920, h = 1080, fps = 30.0), 10.0, listOf(Sample(0.0)))
        val t = resolvePick(d, TargetPick(0.0, 0.5, 0.5, free = true, zoom = 2.0), Config())
        assertNotNull(t); assertEquals(-1, t.id); assertEquals(2.0, t.free?.zoom)
    }
}

class RegionTest {
    @Test
    fun boundsAndPick() {
        val shapes = listOf(listOf(listOf(0.2, 0.4), listOf(0.3, 0.9), listOf(0.25, 0.6)), listOf(listOf(0.5, 0.4), listOf(0.6, 0.9)))
        val b = shapeBounds(shapes)!!
        assertEquals(0.4, b[0], 1e-9); assertEquals(0.65, b[1], 1e-9); assertEquals(0.4, b[2], 1e-9); assertEquals(0.5, b[3], 1e-9)
        assertEquals(0.4, regionPick(1.0, shapes).x, 1e-9)
    }

    @Test
    fun simplifyKeepsCornersDropsCollinear() {
        val line = (0..10).map { listOf(it / 10.0, 0.0) } + (1..10).map { listOf(1.0, it / 10.0) }
        val s = simplifyStroke(line, 0.01)
        assertEquals(3, s.size)
    }

    @Test
    fun regionRowsGiveSizedBoxes() {
        val samples = (0 until 6).map { Sample(it / 10.0) }
        val rows = (0 until 6).map { listOf(0.4 + 0.01 * it, 0.5, 0.9, 0.2, 0.3) }
        val d = TrackDoc(2, VideoInfo(w = 1920, h = 1080, fps = 30.0), 10.0, samples, pt = PointTrack(a = rows))
        val pick = regionPick(0.0, listOf(listOf(listOf(0.3, 0.35), listOf(0.5, 0.65), listOf(0.4, 0.5))))
        val boxes = freePointBoxes(d, pick, Config())
        val b = boxes.getValue(3)
        assertTrue(abs(b[2] - 0.2 * 1.15) < 0.01 && abs(b[3] - 0.3 * 1.15) < 0.01, "size ${b[2]} x ${b[3]}")
    }
}
