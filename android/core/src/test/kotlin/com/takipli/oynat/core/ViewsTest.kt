package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Part 10: person / joint / preset views and tap resolution against `golden/target_views.json` (Python reference). */
class ViewsTest {
    private fun res(name: String): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/golden/$name.json")) { "missing $name" }.readText()).jsonObject

    private val scan = res("scan")
    private val fx = res("target_views")

    private fun docOf(samples: JsonArray): TrackDoc = Formats.parseTrack(
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(2),
                "video" to JsonObject(mapOf("w" to scan.getValue("w"), "h" to scan.getValue("h"))),
                "samples" to samples,
            ),
        ).toString(),
    )

    private val base = docOf(scan.getValue("samples").jsonArray)

    /** `base` plus the variants where one track is removed for a stretch of samples (fixture `docs.removed`). */
    private val docs: Map<String, TrackDoc> = buildMap {
        put("base", base)
        for ((name, v) in fx.getValue("docs").jsonObject.getValue("removed").jsonObject) {
            val (from, to, id) = v.jsonArray.map { it.jsonPrimitive.int }
            put(
                name,
                base.copy(
                    samples = base.samples.mapIndexed { i, s ->
                        if (i !in from until to) s
                        else s.copy(
                            d = s.d.filter { it[0].toInt() != id },
                            kp = s.kp?.filterKeys { it != id.toString() }?.ifEmpty { null },
                        )
                    },
                ),
            )
        }
    }

    private fun cfgOf(name: String): Config = Formats.parseConfig(fx.getValue("cfgs").jsonObject.getValue(name).toString())

    @Test
    fun viewsMatchPython() {
        val cases = fx.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 38)
        for (c in cases) {
            val doc = docs.getValue(c.getValue("doc").jsonPrimitive.content)
            val id = c.getValue("id").jsonPrimitive.int
            val arg = c.getValue("arg")
            val target = when (c.getValue("kind").jsonPrimitive.content) {
                "person" -> Target(id)
                "joint" -> Target(id, part = arg.jsonPrimitive.int)
                else -> Target(id, preset = arg.jsonPrimitive.content)
            }
            val label = "${c["doc"]} ${c["kind"]} id=$id arg=$arg cfg=${c["cfg"]}"
            val got = buildTargetViews(doc, target, cfgOf(c.getValue("cfg").jsonPrimitive.content))
            val exp = c.getValue("views").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double } }
            assertEquals(exp.size, got.size, "keyframes: $label")
            for ((i, row) in exp.withIndex()) for (k in row.indices) assertEquals(row[k], got[i][k], 1e-9, "$label [$i][$k]")
        }
    }

    @Test
    fun tapsMatchPython() {
        for (c in fx.getValue("taps").jsonArray.map { it.jsonObject }) {
            val got = resolveTap(base, c.getValue("t").jsonPrimitive.double, c.getValue("x").jsonPrimitive.double, c.getValue("y").jsonPrimitive.double, Config())
            val e = c.getValue("result")
            if (e is JsonNull) assertNull(got, "tap $c") else {
                val r = e.jsonArray
                assertEquals(r[0].jsonPrimitive.int, got?.first, "tap id $c")
                assertEquals(r[1].let { if (it is JsonNull) null else it.jsonPrimitive.int }, got?.second, "tap joint $c")
            }
        }
    }

    private fun rowsOf(a: JsonElement): List<Double> = a.jsonArray.map { it.jsonPrimitive.double }

    @Test
    fun smoothedKeypointsMatchPython() {
        val e = fx.getValue("smooth").jsonObject
        val ids = e.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toSet()
        val got = smoothKeypoints(docs.getValue(e.getValue("doc").jsonPrimitive.content), ids, cfgOf(e.getValue("cfg").jsonPrimitive.content))
        val exp = e.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(exp.size, got.size)
        var smoothed = 0
        for ((i, rows) in exp.withIndex()) {
            assertEquals(rows.keys, got[i].keys, "ids #$i")
            for ((id, r) in rows) {
                val ev = rowsOf(r)
                val gv = got[i].getValue(id)
                assertEquals(ev.size, gv.size)
                for (k in ev.indices) assertEquals(ev[k], gv[k], 1e-9, "kp #$i/$id/$k")
                if (base.samples[i].kp?.get(id) != gv) smoothed++
            }
        }
        assertTrue(smoothed > 10, "the filter must actually change rows")
    }

    @Test
    fun propagatedBoxesMatchPython() {
        for (e in fx.getValue("propagate").jsonArray.map { it.jsonObject }) {
            val doc = docs.getValue(e.getValue("doc").jsonPrimitive.content)
            val cfg = cfgOf(e.getValue("cfg").jsonPrimitive.content)
            val input = e.getValue("in").jsonObject.entries.associate { (k, v) -> k.toInt() to rowsOf(v).toDoubleArray() }
            val exp = e.getValue("out").jsonObject.entries.associate { (k, v) -> k.toInt() to rowsOf(v) }
            // the boxes in the fixture are the Python preset boxes: ours must agree before propagation too
            val mine = presetBoxes(doc, setOf(e.getValue("id").jsonPrimitive.int), e.getValue("preset").jsonPrimitive.content, cfg)
            assertEquals(input.keys, mine.keys, "visible samples")
            for ((k, v) in input) for (c in 0 until 4) assertEquals(v[c], mine.getValue(k)[c], 1e-9, "preset box $k/$c")
            val got = propagateBoxes(doc, input, cfg)
            assertEquals(exp.keys, got.keys, "propagated samples ${e["doc"]}/${e["cfg"]}")
            assertTrue(got.size > input.size, "something must be propagated")
            for ((k, v) in exp) for (c in 0 until 4) assertEquals(v[c], got.getValue(k)[c], 1e-9, "box $k/$c")
        }
    }

    @Test
    fun rectanglesMatchPython() {
        for (c in fx.getValue("rects").jsonArray.map { it.jsonObject }) {
            val r = rowsOf(c.getValue("rect"))
            val got = resolveRect(base, c.getValue("t").jsonPrimitive.double, r[0], r[1], r[2], r[3], Config())
            val e = c.getValue("result")
            if (e is JsonNull) assertNull(got, "rect $c") else {
                val x = e.jsonArray
                assertEquals(x[0].jsonPrimitive.int, got?.first, "rect id $c")
                assertEquals(x[1].let { if (it is JsonNull) null else it.jsonPrimitive.int }, got?.second, "rect joint $c")
            }
        }
        // corners in any order give the same answer
        val s3 = base.samples[3]
        val d = s3.d[0]
        assertEquals(
            resolveRect(base, s3.t, d[1], d[2], d[3], d[4], Config()),
            resolveRect(base, s3.t, d[3], d[4], d[1], d[2], Config()),
        )
    }

    @Test
    fun ankleIsInferredFromKneeAndHipOnlyWhenBothAreConfident() {
        val row = MutableList(51) { 0.5 }
        for (j in 0 until 17) row[3 * j + 2] = 0.9
        row[3 * 11 + 1] = 0.4; row[3 * 13 + 1] = 0.6; row[3 * 15 + 1] = 0.8; row[3 * 15 + 2] = 0.1
        val e = checkNotNull(inferJoint(row, 15, 1000, 500, 0.35))
        assertEquals(500.0, e[0], 1e-9); assertEquals(400.0, e[1], 1e-9); assertEquals(0.45, e[2], 1e-12)
        assertNull(inferJoint(row, 13, 1000, 500, 0.35)) // knees are not inferred
        row[3 * 11 + 2] = 0.2
        assertNull(inferJoint(row, 15, 1000, 500, 0.35))
        // a foot target with only the knee chain visible now gets a joint-based box, not the person slice
        row[3 * 11 + 2] = 0.9
        val doc = TrackDoc(2, VideoInfo("", 1000, 500), samples = listOf(Sample(0.0, listOf(listOf(1.0, 0.25, 0.1, 0.5, 0.9, 0.9)), mapOf("1" to row))))
        assertEquals(0.8, presetBoxes(doc, setOf(1), "left_foot", Config(kpInfer = true)).getValue(0)[1], 1e-9)
        assertEquals(0.1 + 0.8 * 0.94, presetBoxes(doc, setOf(1), "left_foot", Config(kpInfer = false)).getValue(0)[1], 1e-9)
    }

    @Test
    fun presetFallsBackToPersonSliceWithoutJointsAndRejectsUnknownNames() {
        val s = Sample(0.0, listOf(listOf(1.0, 0.2, 0.1, 0.4, 0.9, 0.9)))
        val doc = TrackDoc(2, VideoInfo("", 1000, 500), samples = listOf(s))
        val b = presetBoxes(doc, setOf(1), "left_leg", Config()).getValue(0)
        assertEquals(0.3, b[0], 1e-12) // person centre x
        assertEquals(0.1 + 0.8 * 0.75, b[1], 1e-12) // middle of the lower half
        assertEquals(0.2, b[2], 1e-12)
        assertEquals(0.4, b[3], 1e-12)
        assertFailsWith<IllegalArgumentException> { presetBoxes(doc, setOf(1), "elbow", Config()) }
        assertEquals(PRESETS.keys.size, 12)
    }
}
