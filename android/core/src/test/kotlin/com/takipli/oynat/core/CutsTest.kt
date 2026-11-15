package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

/** Part 8: cut detector vs the Python reference (`golden/cuts.json`) + size helpers. */
class CutsTest {
    private val g = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/golden/cuts.json")) { "missing golden/cuts.json" }.readText(),
    ).jsonObject

    @Test
    fun matchesPythonOnSyntheticScenes() {
        val w = g.getValue("w").jsonPrimitive.int
        val h = g.getValue("h").jsonPrimitive.int
        val det = CutDetector(
            g.getValue("hist_thr").jsonPrimitive.double,
            g.getValue("diff_thr").jsonPrimitive.double,
            g.getValue("relax").jsonPrimitive.double,
        )
        var cuts = 0
        for ((i, s) in g.getValue("steps").jsonArray.withIndex()) {
            val o = s.jsonObject
            val px = o.getValue("argb").jsonArray.map { it.jsonPrimitive.content.toLong().toInt() }.toIntArray()
            val ok = o.getValue("cam_ok").jsonPrimitive.booleanOrNull
            val cut = det.step(px, w, h, ok)
            assertEquals(o.getValue("cut").jsonPrimitive.boolean, cut, "cut at step $i")
            // hist: float32 noise only; gray: OpenCV rounds the luma slightly differently (<= 1/255 per pixel)
            assertTrue(abs(det.lastHist - o.getValue("hist").jsonPrimitive.double) < 1e-4, "hist at step $i: ${det.lastHist}")
            assertTrue(abs(det.lastDiff - o.getValue("diff").jsonPrimitive.double) < 3e-3, "diff at step $i: ${det.lastDiff}")
            if (cut) cuts++
        }
        assertEquals(2, cuts)
    }

    @Test
    fun firstFrameAndResetAreNeverCuts() {
        val det = CutDetector()
        val a = IntArray(64 * 36) { 0xFF0000FF.toInt() }
        val b = IntArray(64 * 36) { 0xFFFFFF00.toInt() }
        assertFalse(det.step(a, 64, 36))
        assertTrue(det.step(b, 64, 36))
        det.reset()
        assertFalse(det.step(a, 64, 36))
    }

    @Test
    fun thumbSize() {
        assertEquals(64 to 36, cutThumbSize(1920, 1080))
        assertEquals(64 to 114, cutThumbSize(1080, 1920))
        assertEquals(64 to 8, cutThumbSize(6400, 100)) // minimum height 8
    }

    @Test
    fun areaResizeAveragesBlocks() {
        val px = IntArray(4 * 2) { if (it % 4 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val out = areaResize(px, 4, 2, 2, 1)
        assertEquals(0xFF000000.toInt(), out[0]); assertEquals(0xFFFFFFFF.toInt(), out[1])
        val mix = areaResize(IntArray(4) { if (it % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 2, 2, 1, 1)
        assertEquals(0xFF7F7F7F.toInt(), mix[0]) // (0 + 255) / 2 per channel, truncated
        assertEquals(3, areaResize(IntArray(1), 1, 1, 3, 1).size) // upscale falls back to nearest
    }
}
