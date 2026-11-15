package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Golden tests for Part 3 (ByteTrack + Kalman); fixtures from `scripts/gen_golden.py`. */
class TrackingTest {
    private fun obj(name: String): JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/golden/$name.json")) { "missing golden/$name.json" }.readText(),
        ).jsonObject

    private fun d(e: JsonElement) = e.jsonPrimitive.double
    private fun ds(e: JsonElement) = e.jsonArray.map { d(it) }
    private fun near(e: Double, a: Double, msg: String) =
        assertTrue(abs(e - a) <= 1e-6 * max(1.0, abs(e)), "$msg: expected $e, got $a")

    private fun nearAll(e: List<Double>, a: List<Double>, msg: String) {
        assertEquals(e.size, a.size, msg)
        e.indices.forEach { near(e[it], a[it], "$msg[$it]") }
    }

    private fun box(l: List<Double>) = BBox(l[0], l[1], l[2], l[3])
    private fun boxList(b: BBox) = listOf(b.x1, b.y1, b.x2, b.y2)

    private fun det(x: Double, y: Double, s: Double = 0.9) = Detection(BBox.fromXywh(x, y, 40.0, 100.0), s)

    @Test
    fun assignmentMatchesPython() {
        for (c in obj("assignment").getValue("cases").jsonArray.map { it.jsonObject }) {
            val cost = c.getValue("cost").jsonArray.map { ds(it).toDoubleArray() }.toTypedArray()
            val got = linearAssignment(cost).sortedWith(compareBy({ it.first }, { it.second }))
            val exp = c.getValue("pairs").jsonArray.map { it.jsonArray[0].jsonPrimitive.int to it.jsonArray[1].jsonPrimitive.int }
            assertEquals(exp, got, "n=${c["n"]} m=${c["m"]}")
        }
    }

    @Test
    fun assignmentOptimalAndRectangular() {
        val c = arrayOf(doubleArrayOf(4.0, 1.0, 3.0), doubleArrayOf(2.0, 0.0, 5.0), doubleArrayOf(3.0, 2.0, 2.0))
        assertEquals(5.0, linearAssignment(c).sumOf { (i, j) -> c[i][j] })
        val r = arrayOf(doubleArrayOf(1.0, 9.0), doubleArrayOf(9.0, 1.0), doubleArrayOf(5.0, 5.0))
        assertEquals(listOf(0 to 0, 1 to 1), linearAssignment(r).sortedBy { it.first }.take(2))
        assertEquals(emptyList(), linearAssignment(emptyArray()))
    }

    @Test
    fun iouBasics() {
        val a = listOf(BBox(0.0, 0.0, 10.0, 10.0))
        assertEquals(1.0, iouMatrix(a, listOf(BBox(0.0, 0.0, 10.0, 10.0)))[0][0])
        assertEquals(0.0, iouMatrix(a, listOf(BBox(20.0, 20.0, 30.0, 30.0)))[0][0])
    }

    @Test
    fun kalmanBoxMatchesPython() {
        val o = obj("kalman_box")
        val kb = KalmanBox(box(ds(o.getValue("init"))))
        val ops = o.getValue("ops").jsonArray.map { it.jsonObject }
        val out = o.getValue("out").jsonArray.map { it.jsonObject }
        ops.forEachIndexed { i, op ->
            when (op.getValue("op").jsonPrimitive.content) {
                "predict" -> kb.predict()
                "update" -> kb.update(box(ds(op.getValue("box"))))
                else -> kb.compensate(op.getValue("m").jsonArray.flatMap { ds(it) }.toDoubleArray())
            }
            nearAll(ds(out[i].getValue("x")), kb.x.toList(), "x op$i")
            nearAll(ds(out[i].getValue("p")), List(8) { kb.p[it][it] }, "P op$i")
        }
    }

    @Test
    fun kalman1dMatchesPython() {
        val o = obj("kalman_1d")
        val a = ds(o.getValue("args"))
        val k = Kalman1D(a[0], a[1], a[2], a[3])
        val steps = o.getValue("steps").jsonArray.map { ds(it) }
        val out = o.getValue("out").jsonArray.map { ds(it) }
        steps.forEachIndexed { i, (dt, z) ->
            k.predict(dt)
            k.update(z)
            nearAll(out[i], listOf(k.p, k.v, k.p00, k.p01, k.p11), "step$i")
        }
    }

    @Test
    fun boxFilterMatchesPython() {
        val o = obj("box_filter")
        val ts = ds(o.getValue("t"))
        val boxes = o.getValue("boxes").jsonArray.map { box(ds(it)) }
        for (run in o.getValue("runs").jsonArray.map { it.jsonObject }) {
            val p = run.getValue("params").jsonObject
            val f = BoxFilter(
                p["accel"]?.let { d(it) } ?: 0.5,
                p["meas"]?.let { d(it) } ?: 0.01,
                p["size_ratio"]?.let { d(it) } ?: 0.2,
                p["max_gap"]?.let { d(it) } ?: 1.0,
            )
            val rows = run.getValue("rows").jsonArray.map { it.jsonObject }
            ts.indices.forEach { i ->
                val fb = f.update(boxes[i], ts[i])
                nearAll(ds(rows[i].getValue("box")), boxList(fb), "box i=$i")
                nearAll(ds(rows[i].getValue("vel")), f.velocity.toList(), "vel i=$i")
                nearAll(ds(rows[i].getValue("look")), boxList(f.lookahead(0.15, 0.15)), "look i=$i")
            }
        }
        assertFailsWith<IllegalStateException> { BoxFilter().lookahead(0.1, 0.1) }
    }

    @Test
    fun byteTrackMatchesPython() {
        val o = obj("bytetrack")
        val frames = o.getValue("frames").jsonArray.map { it.jsonObject }
        for (run in o.getValue("runs").jsonArray.map { it.jsonObject }) {
            val p = run.getValue("params").jsonObject
            val tr = ByteTracker(
                highThr = p["high_thr"]?.let { d(it) } ?: 0.5,
                lowThr = p["low_thr"]?.let { d(it) } ?: 0.1,
                newThr = p["new_thr"]?.let { d(it) },
                matchIou = p["match_iou"]?.let { d(it) } ?: 0.3,
                secondIou = p["second_iou"]?.let { d(it) } ?: 0.5,
                maxLost = p["max_lost"]?.jsonPrimitive?.int ?: 30,
                minHits = p["min_hits"]?.jsonPrimitive?.int ?: 1,
            )
            tr.setNextId(run.getValue("next_id").jsonPrimitive.int)
            val out = run.getValue("out").jsonArray.map { it.jsonObject }
            frames.forEachIndexed { i, f ->
                if (f.getValue("clear").jsonPrimitive.content == "true") tr.clear()
                val motion = f["motion"]?.takeIf { it is kotlinx.serialization.json.JsonArray }
                    ?.jsonArray?.flatMap { ds(it) }?.toDoubleArray()
                val dets = f.getValue("dets").jsonArray.map { ds(it) }.map { Detection(box(it), it[4]) }
                val got = tr.update(dets, motion)
                val exp = out[i].getValue("tracks").jsonArray.map { ds(it) }
                assertEquals(exp.size, got.size, "frame $i track count")
                exp.zip(got).forEach { (e, t) ->
                    assertEquals(e[0].toInt(), t.id, "frame $i id")
                    nearAll(e.subList(1, 5), boxList(t.bbox), "frame $i id=${t.id} box")
                    near(e[5], t.score, "frame $i score")
                    assertEquals(e[6].toInt(), t.age, "frame $i age")
                    assertEquals(e[7].toInt(), t.lost, "frame $i lost")
                }
                val lost = out[i].getValue("lost").jsonArray.map { it.jsonPrimitive.int }
                assertEquals(lost, tr.lostTracks.map { it.id }, "frame $i lost ids")
                assertEquals(out[i].getValue("n_boxes").jsonPrimitive.int, tr.boxes.size, "frame $i boxes")
            }
        }
    }

    @Test
    fun idPersistsOnMovingBox() {
        val t = ByteTracker()
        val ids = (0 until 40).map { t.update(listOf(det(100.0 + 5 * it, 100.0))).single().id }.toSet()
        assertEquals(setOf(1), ids)
    }

    @Test
    fun shortLossKeepsIdAndLongLossDrops() {
        val t = ByteTracker()
        repeat(10) { t.update(listOf(det(100.0 + 5 * it, 100.0))) }
        repeat(5) { assertTrue(t.update(emptyList()).isEmpty()) }
        assertEquals(listOf(1), t.lostTracks.map { it.id })
        val out = t.update(listOf(det(100.0 + 5 * 15, 100.0)))
        assertEquals(listOf(1), out.map { it.id })
        assertEquals(0, out[0].lost)

        val t2 = ByteTracker(maxLost = 5)
        t2.update(listOf(det(100.0, 100.0)))
        repeat(7) { t2.update(emptyList()) }
        assertTrue(t2.lostTracks.isEmpty())
        assertEquals(2, t2.update(listOf(det(100.0, 100.0))).single().id)
    }

    @Test
    fun clearKeepsIdCounter() {
        val t = ByteTracker()
        t.update(listOf(det(100.0, 100.0)))
        t.clear()
        assertTrue(t.boxes.isEmpty())
        assertEquals(2, t.update(listOf(det(100.0, 100.0))).single().id)
    }
}
