package com.takipli.oynat.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Part 4 golden tests: letterbox geometry, pose decoding, NMS, nearest keypoint. */
class PoseTest {
    private fun obj(name: String): JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/golden/$name.json")) { "missing golden/$name.json" }.readText(),
        ).jsonObject

    private fun near(e: Double, a: Double, tol: Double, msg: String) =
        assertTrue(abs(e - a) <= tol, "$msg: expected $e, got $a")

    private val pose = obj("pose")
    private val info = pose.getValue("info").jsonObject.let {
        LetterboxInfo(
            it.getValue("scale").jsonPrimitive.double,
            it.getValue("pad_x").jsonPrimitive.int,
            it.getValue("pad_y").jsonPrimitive.int,
        )
    }
    private val frame = pose.getValue("frame").jsonArray.map { it.jsonPrimitive.int }
    private val dim = pose.getValue("dim").jsonArray.map { it.jsonPrimitive.int } // [56, N]
    private val data = pose.getValue("data").jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()

    private fun transposed(): FloatArray {
        val n = dim[1]
        return FloatArray(data.size) { i -> data[(i % POSE_COLS) * n + i / POSE_COLS] } // [N, 56]
    }

    @Test
    fun letterboxMatchesPython() {
        for (c in obj("letterbox").getValue("cases").jsonArray.map { it.jsonObject }) {
            val size = c.getValue("size").jsonArray.map { it.jsonPrimitive.int }
            val w = c.getValue("w").jsonPrimitive.int
            val h = c.getValue("h").jsonPrimitive.int
            val l = letterboxLayout(w, h, size[0], size[1])
            val tag = "${w}x$h -> $size"
            near(c.getValue("scale").jsonPrimitive.double, l.info.scale, 1e-12, tag)
            assertEquals(c.getValue("pad_x").jsonPrimitive.int, l.info.padX, tag)
            assertEquals(c.getValue("pad_y").jsonPrimitive.int, l.info.padY, tag)
            assertEquals(c.getValue("nw").jsonPrimitive.int, l.newW, tag)
            assertEquals(c.getValue("nh").jsonPrimitive.int, l.newH, tag)
        }
    }

    @Test
    fun preprocessPlacesRgbAndPadsGray() {
        val layout = letterboxLayout(2, 1, 4, 4) // r = 2 -> 4x2, pad_y = 1
        assertEquals(LetterboxInfo(2.0, 0, 1), layout.info)
        val px = IntArray(4 * 2) { 0xFF000000.toInt() or (0x10 shl 16) or (0x20 shl 8) or 0x30 }
        px[0] = 0xFFFF0000.toInt() // red, top-left of the resized image
        val out = preprocessChw(px, layout, 4, 4)
        assertEquals(48, out.size)
        assertEquals(114f / 255f, out[0]) // padding row
        assertEquals(1f, out[1 * 4 + 0]) // R plane, row pad_y
        assertEquals(0f, out[16 + 1 * 4 + 0]) // G plane
        assertEquals(0x30 / 255f, out[32 + 1 * 4 + 1]) // B plane
        assertFailsWith<IllegalArgumentException> { preprocessChw(IntArray(3), layout, 4, 4) }
    }

    private fun assertPoses(case: JsonObject, got: List<Pose>, tag: String) {
        val exp = case.getValue("poses").jsonArray.map { it.jsonObject }
        assertEquals(exp.size, got.size, "$tag count")
        exp.zip(got).forEachIndexed { i, (e, p) ->
            val b = e.getValue("box").jsonArray.map { it.jsonPrimitive.double }
            listOf(p.bbox.x1, p.bbox.y1, p.bbox.x2, p.bbox.y2).forEachIndexed { k, v -> near(b[k], v, 1e-3, "$tag box$i[$k]") }
            near(e.getValue("score").jsonPrimitive.double, p.score, 1e-6, "$tag score$i")
            val kp = e.getValue("kpts").jsonArray.map { it.jsonPrimitive.double }
            assertEquals(kp.size, p.kpts.size)
            kp.indices.forEach { k -> near(kp[k], p.kpts[k], 1e-3, "$tag pose$i kpt[$k]") }
        }
    }

    private fun decode(arr: FloatArray, d0: Int, d1: Int, params: JsonObject) = decodeYoloPose(
        arr, d0, d1, info, frame[0], frame[1],
        confThres = params["conf_thres"]?.jsonPrimitive?.double ?: 0.25,
        iouThres = params["iou_thres"]?.jsonPrimitive?.double ?: 0.45,
        maxDet = params["max_det"]?.jsonPrimitive?.int ?: 20,
    )

    @Test
    fun decodeMatchesPythonBothLayouts() {
        for (c in pose.getValue("cases").jsonArray.map { it.jsonObject }) {
            val params = c.getValue("params").jsonObject
            assertPoses(c, decode(data, dim[0], dim[1], params), "[56,N] $params")
            assertPoses(c, decode(transposed(), dim[1], dim[0], params), "[N,56] $params")
        }
    }

    @Test
    fun nmsSuppressesDuplicateAndKeepsScoreOrder() {
        val boxes = listOf(BBox(0.0, 0.0, 10.0, 10.0), BBox(1.0, 1.0, 11.0, 11.0), BBox(50.0, 50.0, 60.0, 60.0))
        assertEquals(listOf(1, 2), nms(boxes, doubleArrayOf(0.5, 0.9, 0.7), 0.45))
        assertEquals(listOf(1, 2, 0), nms(boxes, doubleArrayOf(0.5, 0.9, 0.7), 0.99))
    }

    @Test
    fun nearestKeypointMatchesPython() {
        val poses = pose.getValue("cases").jsonArray[0].jsonObject.getValue("poses").jsonArray.map {
            val e = it.jsonObject
            val b = e.getValue("box").jsonArray.map { v -> v.jsonPrimitive.double }
            Pose(
                BBox(b[0], b[1], b[2], b[3]),
                e.getValue("score").jsonPrimitive.double,
                e.getValue("kpts").jsonArray.map { v -> v.jsonPrimitive.double }.toDoubleArray(),
            )
        }
        for (c in pose.getValue("picks").jsonArray.map { it.jsonObject }) {
            val p = c.getValue("params").jsonObject
            val got = nearestKeypoint(
                poses, c.getValue("x").jsonPrimitive.double, c.getValue("y").jsonPrimitive.double,
                tol = p["tol"]?.jsonPrimitive?.double ?: 0.12,
                minConf = p["min_conf"]?.jsonPrimitive?.double ?: 0.35,
            )
            val exp = c["result"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
            if (exp == null) assertNull(got, "$c") else assertEquals(exp[0].jsonPrimitive.int to exp[1].jsonPrimitive.int, got, "$c")
        }
    }

    @Test
    fun badShapesAreRejectedAndEmptyIsEmpty() {
        assertFailsWith<IllegalArgumentException> { decodeYoloPose(FloatArray(10 * 7), 10, 7, info, 100, 100) }
        assertFailsWith<IllegalArgumentException> { decodeYoloPose(FloatArray(5), 56, 1, info, 100, 100) }
        assertTrue(decodeYoloPose(FloatArray(POSE_COLS * 3), POSE_COLS, 3, info, 100, 100).isEmpty())
    }

    @Test
    fun posesToDetectionsFilterByScore() {
        val poses = decode(data, dim[0], dim[1], JsonObject(emptyMap()))
        assertEquals(poses.size, poses.toDetections(0.0).size)
        assertTrue(poses.toDetections(0.5).size < poses.size)
        assertEquals(poses.filter { it.score >= 0.7 }.size, poses.withMinScore(0.7).size)
    }
}
