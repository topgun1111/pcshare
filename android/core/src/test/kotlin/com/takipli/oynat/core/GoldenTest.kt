package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures come from the Python reference: `scripts/gen_golden.py` (resources/golden). */
class GoldenTest {
    private fun text(name: String): String =
        checkNotNull(javaClass.getResource("/golden/$name.json")) { "missing golden/$name.json" }.readText()

    private fun obj(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    private fun doubles(a: JsonArray): List<Double> = a.map { it.jsonPrimitive.double }

    private fun assertClose(expected: Double, actual: Double, msg: String = "") =
        assertEquals(expected, actual, 1e-9, msg)

    @Test
    fun viewAtMatchesPython() {
        val o = obj("view_at")
        val views = o.getValue("views").jsonArray.map { doubles(it.jsonArray) }
        for (c in o.getValue("cases").jsonArray.map { it.jsonObject }) {
            val t = c.getValue("t").jsonPrimitive.double
            val e = doubles(c.getValue("v").jsonArray)
            val v = viewAt(views, t)
            assertClose(e[0], v.zoom, "zoom t=$t")
            assertClose(e[1], v.panX, "panX t=$t")
            assertClose(e[2], v.panY, "panY t=$t")
        }
        assertEquals(View(0.0, 0.0, 0.0), viewAt(emptyList(), 1.0))
    }

    @Test
    fun cropRectMatchesPython() {
        val cases = obj("crop_rect").getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val v = doubles(c.getValue("view").jsonArray)
            val w = c.getValue("w").jsonPrimitive.int
            val h = c.getValue("h").jsonPrimitive.int
            val e = c.getValue("rect").jsonArray.map { it.jsonPrimitive.int }
            val r = cropRect(View(v[0], v[1], v[2]), w, h)
            assertEquals(e, listOf(r.x, r.y, r.w, r.h), "view=$v size=${w}x$h")
        }
    }

    @Test
    fun oneEuroMatchesPython() {
        for (run in obj("one_euro").getValue("runs").jsonArray.map { it.jsonObject }) {
            val p = run.getValue("params").jsonObject
            val f = OneEuro(
                p["min_cutoff"]?.jsonPrimitive?.double ?: 1.5,
                p["beta"]?.jsonPrimitive?.double ?: 2.0,
                p["d_cutoff"]?.jsonPrimitive?.double ?: 1.0,
            )
            val ts = doubles(run.getValue("t").jsonArray)
            val xs = doubles(run.getValue("x").jsonArray)
            val ys = doubles(run.getValue("y").jsonArray)
            ts.indices.forEach { i -> assertClose(ys[i], f.update(xs[i], ts[i]), "i=$i") }
        }
    }

    @Test
    fun oneEuroResetForgetsHistory() {
        val f = OneEuro()
        f.update(1.0, 0.0)
        f.update(2.0, 0.1)
        f.reset()
        assertEquals(5.0, f.update(5.0, 10.0))
    }

    @Test
    fun configDefaultsMatchPython() {
        // Golden holds every shared key with the Python default; Android-only keys use defaults.
        assertEquals(Config(), Formats.parseConfig(text("config")))
    }

    @Test
    fun trackV2WithKeypointsLoads() {
        val doc = Formats.parseTrack(text("track_v2"))
        assertEquals(FormatVersion.V2, doc.version)
        assertEquals(1280, doc.video.w)
        assertEquals(Target(id = 1, part = 15), doc.target)
        assertEquals(listOf(0.1), doc.cuts)
        assertEquals(2, doc.samples.size)
        val s = doc.samples[1]
        assertEquals(1, s.cut)
        assertEquals(2, s.d.size)
        assertEquals(51, s.kp!!.getValue("1").size)
        val k = assertNotNull(s.keypoint(1, 15))
        assertClose(0.15, k.x)
        assertClose(0.15, k.y)
        assertClose(0.9, k.conf)
        assertNull(s.keypoint(2, 0))
        assertNull(s.keypoint(1, 17))
    }

    @Test
    fun trackV1HasNoKeypoints() {
        val doc = Formats.parseTrack(text("track_v1"))
        assertEquals(FormatVersion.V1, doc.version)
        assertTrue(doc.samples.all { it.kp == null })
        assertEquals(Target(id = 1), doc.target)
    }

    @Test
    fun trackRoundTripIsStable() {
        for (name in listOf("track_v1", "track_v2")) {
            val a = Formats.parseTrack(text(name))
            assertEquals(a, Formats.parseTrack(Formats.encodeTrack(a)), name)
        }
    }

    @Test
    fun pcViewsFileLoadsAndExports() {
        val v = Formats.parseViews(text("views"))
        assertEquals(FormatVersion.V1, v.version)
        assertEquals("clip.mp4", v.video.path)
        assertEquals(2, v.views.size)
        val r = cropRect(viewAt(v.views, 0.05), 1280, 720)
        assertTrue(r.w in 2..1280 && r.h in 2..720 && r.x + r.w <= 1280 && r.y + r.h <= 720)
    }

    @Test
    fun viewsOfMatchesPcSaveViews() {
        val doc = Formats.parseTrack(text("track_v2"))
        val out = Formats.encodeViews(Formats.viewsOf(doc))
        assertTrue("\"version\":1" in out && "samples" !in out)
        assertEquals(Formats.parseViews(text("views")), Formats.parseViews(out))
        assertContentEquals(
            Json.parseToJsonElement(text("views")).jsonObject.keys.sorted(),
            Json.parseToJsonElement(out).jsonObject.keys.sorted(),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteDocs() {
        val bad = text("track_v2").replace("\"version\": 2", "\"version\": 3")
        assertFailsWith<IllegalArgumentException> { Formats.parseTrack(bad) }
        assertFailsWith<Exception> { Formats.parseTrack("{\"version\":1,\"video\":{}}") }
    }
}
