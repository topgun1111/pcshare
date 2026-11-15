package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Part 9: scan pipeline against `golden/scan.json` (Python `run_detection` on the same synthetic poses). */
class ScanTest {
    private val fixture: JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/golden/scan.json")).readText()).jsonObject

    private fun doubles(a: JsonArray) = a.map { it.jsonPrimitive.double }
    private val w = fixture.getValue("w").jsonPrimitive.int
    private val h = fixture.getValue("h").jsonPrimitive.int
    private val steps = fixture.getValue("steps").jsonArray.map { it.jsonObject }

    private fun poses(step: JsonObject): List<Pose> = step.getValue("poses").jsonArray.map {
        val o = it.jsonObject
        val b = doubles(o.getValue("bbox").jsonArray)
        Pose(BBox(b[0], b[1], b[2], b[3]), o.getValue("score").jsonPrimitive.double, doubles(o.getValue("kpts").jsonArray).toDoubleArray())
    }

    private fun motion(step: JsonObject): DoubleArray? {
        val m: JsonElement = step.getValue("motion")
        if (m !is JsonArray) return null
        return m.flatMap { row -> doubles(row.jsonArray) }.toDoubleArray()
    }

    private fun run(pipe: ScanPipeline, range: IntRange) {
        for (i in range) {
            val s = steps[i]
            pipe.step(s.getValue("t").jsonPrimitive.double, w, h, poses(s), motion(s), s.getValue("cut").jsonPrimitive.content == "true")
        }
    }

    private fun assertSamples(expected: List<JsonObject>, actual: List<Sample>) {
        assertEquals(expected.size, actual.size)
        for ((i, e) in expected.withIndex()) {
            val a = actual[i]
            assertEquals(e.getValue("t").jsonPrimitive.double, a.t, 1e-9, "t #$i")
            val ed = e.getValue("d").jsonArray.map { doubles(it.jsonArray) }
            assertEquals(ed.size, a.d.size, "tracks #$i")
            for ((r, row) in ed.withIndex()) for (c in row.indices) assertEquals(row[c], a.d[r][c], 1e-9, "d #$i/$r/$c")
            val ekp = e["kp"]?.jsonObject
            if (ekp == null) assertNull(a.kp, "kp #$i") else {
                assertEquals(ekp.keys, a.kp!!.keys, "kp ids #$i")
                for ((id, row) in ekp) {
                    val ev = doubles(row.jsonArray)
                    assertEquals(51, a.kp!!.getValue(id).size)
                    for (c in ev.indices) assertEquals(ev[c], a.kp!!.getValue(id)[c], 1e-9, "kp #$i/$id/$c")
                }
            }
            assertEquals(e["cut"]?.jsonPrimitive?.int, a.cut, "cut #$i")
            val ecam = e["cam"]?.jsonArray
            if (ecam == null) assertNull(a.cam, "cam #$i") else {
                val ev = doubles(ecam)
                assertEquals(4, a.cam!!.size, "cam size #$i")
                for (c in ev.indices) assertEquals(ev[c], a.cam!![c], 1e-9, "cam #$i/$c")
            }
        }
    }

    @Test
    fun scanMatchesPython() {
        val pipe = ScanPipeline()
        run(pipe, steps.indices)
        val expected = fixture.getValue("samples").jsonArray.map { it.jsonObject }
        assertSamples(expected, pipe.samples)
        val doc = buildScanDoc("clip.mp4", w, h, 30.0, 3.0, 10.0, pipe.samples)
        assertEquals(fixture.getValue("version").jsonPrimitive.int, doc.version)
        assertEquals(doubles(fixture.getValue("cuts").jsonArray), doc.cuts)
        // the file we write must load again (and the PC loader accepts the same shape)
        val back = Formats.parseTrack(Formats.encodeTrack(doc))
        assertEquals(30, back.samples.size)
        assertEquals(doc.cuts, back.cuts)
        assertTrue(back.samples.all { it.kp != null })
    }

    @Test
    fun resumeKeepsEarlierSamplesAndNeverReusesIds() {
        val first = ScanPipeline()
        run(first, 0..9)
        val maxId = first.samples.flatMap { s -> s.d.map { it[0].toInt() } }.max()
        val second = ScanPipeline()
        second.resume(first.samples)
        run(second, 10..13)
        assertEquals(14, second.samples.size)
        for (s in second.samples.drop(10)) for (row in s.d) assertTrue(row[0].toInt() > maxId, "id ${row[0]} reused")
    }

    @Test
    fun associationIsGreedyOncePerPoseAndNeedsOverlap() {
        fun pose(x: Double) = Pose(BBox(x, 0.0, x + 10, 20.0), 0.9, DoubleArray(51))
        fun track(id: Int, x: Double) = Track(id, BBox(x, 0.0, x + 10, 20.0), 0.9, 1, 0)
        val m = associatePoses(listOf(track(1, 0.0), track(2, 1.0), track(3, 100.0)), listOf(pose(0.5)))
        assertEquals(1, m.size) // one pose, used once; far track gets none
        assertTrue(2 in m || 1 in m)
        assertTrue(associatePoses(listOf(track(1, 0.0)), listOf(pose(50.0))).isEmpty()) // IoU below 0.3
    }

    @Test
    fun signatureIsStableAndKeyedOnFpsAndModel() {
        assertEquals("c1ab80b43f92e057", scanSig("v1", 10.0, "m.onnx:123"))
        assertEquals("38b94dcc838f22e5", scanSig("abc", 7.5, ""))
        assertTrue(scanSig("v1", 10.0, "m") != scanSig("v1", 15.0, "m"))
        assertTrue(scanSig("v1", 10.0, "m") != scanSig("v1", 10.0, "n"))
    }
}
