package com.takipli.oynat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import com.takipli.oynat.core.BenchStats
import com.takipli.oynat.core.FreePick
import com.takipli.oynat.core.MemoryBank
import com.takipli.oynat.core.PointTrack
import com.takipli.oynat.core.TrackDoc
import com.takipli.oynat.core.benchStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * `sam` build: tracker for free-point / drawn-region targets with EdgeTAM (SAM 2 family, five static ONNX graphs).
 *
 * Memory tracker (when all five graphs exist): the first frame is prompted (tap = point, drawn region = box + point); from then on
 * every frame is encoded once, conditioned on the memory bank (the prompted frame + the last tracked frames, with their object
 * pointers) by `sam-memattn`, decoded WITHOUT any prompt by `sam-decoder-trk`, and written back to the bank by `sam-memenc`.
 * The mask is carried through memory, so nothing re-derives it from a box guess: rows are `[cx, cy, conf, w, h]` from the mask
 * (centroid and bounds). Runs forward and backward from the pick at [SAM_FPS].
 * Fallback chain: memory tracker -> prompt-per-frame tracker (encoder + decoder only, the previous design) -> optical flow (caller).
 *
 * Every graph runs on the Hexagon NPU (QNN HTP, fp16) when [npuEnabled]; a graph that cannot compile or run there falls back to
 * CPU on its own (see [Qnn]/[OrtModel]); [lastRun] and the benchmark show which provider each graph really used.
 */
object SamEngine {
    private const val ENC = "sam-encoder.onnx"
    private const val DEC = "sam-decoder.onnx"
    private const val DEC_TRK = "sam-decoder-trk.onnx"
    private const val MEMENC = "sam-memenc.onnx"
    private const val MEMATTN = "sam-memattn.onnx"
    val COMPONENTS = listOf(ENC, DEC, DEC_TRK, MEMENC, MEMATTN)
    private const val SAM_FPS = 6.0
    private const val S = 1024
    private const val M = 256
    private const val MIN_CONF = 0.35
    private const val MAX_GAP_S = 1.0
    private const val THREADS = 4
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** What the last memory/prompt scan used (providers + ms per component); for the benchmark screen. */
    @Volatile var lastRun: String = ""

    fun hasModels(ctx: Context): Boolean = ModelStore.list(ctx).let { ENC in it && DEC in it }
    fun hasMemory(ctx: Context): Boolean = ModelStore.list(ctx).let { l -> COMPONENTS.all { it in l } }

    fun npuEnabled(ctx: Context): Boolean = ctx.getSharedPreferences("sam", Context.MODE_PRIVATE).getBoolean("npu", true)
    fun setNpuEnabled(ctx: Context, on: Boolean) { ctx.getSharedPreferences("sam", Context.MODE_PRIVATE).edit().putBoolean("npu", on).apply() }

    // ---------------------------------------------------------------- shared helpers
    private class Box(val x1: Double, val y1: Double, val x2: Double, val y2: Double) { // normalized
        val cx get() = (x1 + x2) / 2
        val cy get() = (y1 + y2) / 2
        val w get() = x2 - x1
        val h get() = y2 - y1
        fun grow(f: Double, minSide: Double = 0.03): Box {
            val nw = max(w * f, minSide); val nh = max(h * f, minSide)
            return Box((cx - nw / 2).coerceIn(0.0, 1.0), (cy - nh / 2).coerceIn(0.0, 1.0), (cx + nw / 2).coerceIn(0.0, 1.0), (cy + nh / 2).coerceIn(0.0, 1.0))
        }
        fun shift(dx: Double, dy: Double) = Box((x1 + dx).coerceIn(0.0, 1.0), (y1 + dy).coerceIn(0.0, 1.0), (x2 + dx).coerceIn(0.0, 1.0), (y2 + dy).coerceIn(0.0, 1.0))
        fun iou(o: Box): Double {
            val iw = max(0.0, min(x2, o.x2) - max(x1, o.x1)); val ih = max(0.0, min(y2, o.y2) - max(y1, o.y1))
            val i = iw * ih; val u = w * h + o.w * o.h - i
            return if (u > 0) i / u else 0.0
        }
    }

    private class Cand(val box: Box, val cx: Double, val cy: Double, val area: Double, val iouPred: Double, val inPx: Int, val inPy: Int)
    private class Step(val cx: Double, val cy: Double, val conf: Double, val w: Double, val h: Double) {
        fun row(): List<Double> = listOf(cx, cy, conf, w, h)
    }
    private class Mem(val feat: FloatArray, val ptr: FloatArray)
    private class Dec(val masks: FloatArray, val n: Int, val iou: FloatArray, val ptrs: FloatArray, val score: Float)

    private class Models(val env: OrtEnvironment, val enc: OrtModel, val dec: OrtModel, val trk: OrtModel?, val memEnc: OrtModel?, val memAttn: OrtModel?) : AutoCloseable {
        fun all() = listOfNotNull(enc, dec, trk, memEnc, memAttn)
        override fun close() { all().forEach { it.close() } }
        fun summary(ms: Map<String, Double>, frames: Int) = all().joinToString(" | ") { m ->
            "${m.name.removePrefix("sam-").removeSuffix(".onnx")} ${m.provider}${m.fallback?.let { " (fallback: $it)" } ?: ""} ${"%.0f".format(ms[m.name] ?: 0.0)} ms/frame"
        } + " | $frames frames"
    }

    private fun open(ctx: Context, memory: Boolean): Models {
        val npu = npuEnabled(ctx)
        fun m(n: String) = Qnn.open(ctx, n, THREADS, npu)
        return Models(OrtEnvironment.getEnvironment(), m(ENC), m(DEC), if (memory) m(DEC_TRK) else null, if (memory) m(MEMENC) else null, if (memory) m(MEMATTN) else null)
    }

    private fun tensor(env: OrtEnvironment, d: FloatArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(d), shape)
    private fun floats(t: OnnxTensor): FloatArray = t.floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }

    private fun chw(bmp: Bitmap): FloatArray {
        val sb = if (bmp.width == S && bmp.height == S) bmp else Bitmap.createScaledBitmap(bmp, S, S, true)
        val px = IntArray(S * S); sb.getPixels(px, 0, S, 0, 0, S, S)
        if (sb !== bmp) sb.recycle()
        val out = FloatArray(3 * S * S)
        for (i in px.indices) {
            val p = px[i]
            out[i] = (((p shr 16) and 255) / 255f - MEAN[0]) / STD[0]
            out[S * S + i] = (((p shr 8) and 255) / 255f - MEAN[1]) / STD[1]
            out[2 * S * S + i] = ((p and 255) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    /** Runs one of the decoders (3 prompt slots for `sam-decoder`, 1 for `sam-decoder-trk`); unused slots carry label -1. */
    private fun decode(env: OrtEnvironment, dec: OrtModel, embed: OnnxTensor, hr0: OnnxTensor, hr1: OnnxTensor, coords: FloatArray, labels: FloatArray): Dec {
        val n = labels.size.toLong()
        val c = tensor(env, coords, 1, n, 2); val l = tensor(env, labels, 1, n)
        try {
            dec.run(mapOf("image_embed" to embed, "high_res_feats_0" to hr0, "high_res_feats_1" to hr1, "point_coords" to c, "point_labels" to l)).use { r ->
                val m = r.get("low_res_masks").get() as OnnxTensor
                check(m.info.shape.let { it.size == 4 && it[2].toInt() == M && it[3].toInt() == M }) { "unexpected mask size ${m.info.shape.toList()}" }
                val iou = floats(r.get("iou_predictions").get() as OnnxTensor)
                return Dec(floats(m), iou.size, iou, floats(r.get("obj_ptrs").get() as OnnxTensor), floats(r.get("object_score").get() as OnnxTensor)[0])
            }
        } finally { c.close(); l.close() }
    }

    private fun analyse(masks: FloatArray, k: Int, iouPred: Double): Cand {
        val o = k * M * M
        var n = 0; var sx = 0.0; var sy = 0.0; var x1 = M; var y1 = M; var x2 = -1; var y2 = -1
        var ix = -1; var iy = -1; var best = 0f
        for (y in 0 until M) for (x in 0 until M) {
            val v = masks[o + y * M + x]
            if (v > 0f) {
                n++; sx += x; sy += y
                if (x < x1) x1 = x; if (x > x2) x2 = x; if (y < y1) y1 = y; if (y > y2) y2 = y
                if (v > best) { best = v; ix = x; iy = y }
            }
        }
        if (n == 0) return Cand(Box(0.0, 0.0, 0.0, 0.0), 0.0, 0.0, 0.0, iouPred, -1, -1)
        return Cand(Box(x1.toDouble() / M, y1.toDouble() / M, (x2 + 1).toDouble() / M, (y2 + 1).toDouble() / M), sx / n / M, sy / n / M, n.toDouble() / (M * M), iouPred, ix, iy)
    }

    /** Prompt for the 3-slot decoder: region = box corners + a point inside, tap = one positive point; free slots = label -1. */
    private fun prompt3(box: Box?, px: Double, py: Double): Pair<FloatArray, FloatArray> {
        val c = FloatArray(6); val l = floatArrayOf(-1f, -1f, -1f)
        if (box != null) {
            c[0] = (box.x1 * S).toFloat(); c[1] = (box.y1 * S).toFloat(); l[0] = 2f
            c[2] = (box.x2 * S).toFloat(); c[3] = (box.y2 * S).toFloat(); l[1] = 3f
            c[4] = (px * S).toFloat(); c[5] = (py * S).toFloat(); l[2] = 1f
        } else { c[0] = (px * S).toFloat(); c[1] = (py * S).toFloat(); l[0] = 1f }
        return c to l
    }

    private fun seedBox(pick: FreePick): Box? {
        val region = pick.shapes.filter { it.size >= 3 }.flatten()
        return if (region.isNotEmpty()) Box(region.minOf { it[0] }, region.minOf { it[1] }, region.maxOf { it[0] }, region.maxOf { it[1] }) else null
    }

    private fun pickCandidate(cands: List<Cand>): Int { // several valid masks (part vs whole): the smallest one almost as confident as the best = the tapped detail
        val top = cands.filter { it.area > 0 }.maxOfOrNull { it.iouPred } ?: return -1
        return cands.indices.filter { cands[it].area > 0 && cands[it].iouPred >= 0.85 * top }.minByOrNull { cands[it].area } ?: -1
    }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

    // ---------------------------------------------------------------- memory tracker
    private suspend fun walkMemory(s: Models, files: List<File>, order: List<Int>, pick: FreePick, ms: MutableMap<String, Double>, onFrame: () -> Unit): Map<Int, Step> {
        val env = s.env
        val trk = s.trk!!; val memEnc = s.memEnc!!; val memAttn = s.memAttn!!
        val memShape = memAttn.inputShape("mem_feats")           // [K, ...] static
        val k = memShape[0].toInt(); val p = memAttn.inputShape("ptrs")[0].toInt()
        val frameFloats = memShape.drop(1).fold(1L) { a, b -> a * b }.toInt()
        val bank = MemoryBank<Mem>(k, p)
        val res = HashMap<Int, Step>()
        var started = false; var area = 0.0
        fun <T> timed(name: String, f: () -> T): T { val t0 = System.nanoTime(); val r = f(); ms[name] = (ms[name] ?: 0.0) + (System.nanoTime() - t0) / 1e6; return r }
        fun encodeMemory(imageFeat: OnnxTensor, mask: FloatArray, off: Int, score: Float): FloatArray {
            val mt = tensor(env, mask.copyOfRange(off, off + M * M), 1, 1, M.toLong(), M.toLong()); val st = tensor(env, floatArrayOf(score), 1, 1)
            try {
                memEnc.run(mapOf("image_feat" to imageFeat, "low_res_mask" to mt, "object_score" to st)).use { r ->
                    return floats(r.get("mem_feat").get() as OnnxTensor).also { check(it.size == frameFloats) { "memory frame has ${it.size} floats, memattn expects $frameFloats" } }
                }
            } finally { mt.close(); st.close() }
        }
        for ((step, fi) in order.withIndex()) {
            currentCoroutineContext().ensureActive()
            val bmp = BitmapFactory.decodeFile(files[fi].path) ?: continue
            val img = try { chw(bmp) } finally { bmp.recycle() }
            val imgT = tensor(env, img, 1, 3, S.toLong(), S.toLong())
            try {
                timed(ENC) { s.enc.run(mapOf("image" to imgT)) }.use { enc ->
                    val hr0 = enc.get("high_res_feats_0").get() as OnnxTensor; val hr1 = enc.get("high_res_feats_1").get() as OnnxTensor
                    val feat = enc.get("image_feat").get() as OnnxTensor
                    if (!started) {
                        val seed = seedBox(pick)
                        val (c, l) = prompt3(seed, seed?.cx ?: pick.x, seed?.cy ?: pick.y)
                        val d = timed(DEC) { decode(env, s.dec, enc.get("image_embed").get() as OnnxTensor, hr0, hr1, c, l) }
                        val cands = (0 until d.n).map { analyse(d.masks, it, d.iou[it].toDouble()) }
                        val b = pickCandidate(cands)
                        if (b < 0) return res                      // nothing segmented at the tap: caller falls back
                        val cd = cands[b]
                        val mf = timed(MEMENC) { encodeMemory(feat, d.masks, b * M * M, d.score) }
                        bank.reset(Mem(mf, d.ptrs.copyOfRange(b * 256, b * 256 + 256)))
                        started = true; area = cd.area
                        res[fi] = Step(cd.cx, cd.cy, cd.iouPred, cd.box.w, cd.box.h)
                    } else {
                        val slots = bank.memSlots(); val ptrSlots = bank.ptrSlots()
                        val mem = FloatArray(k * frameFloats); slots.forEachIndexed { i, m -> m.feat.copyInto(mem, i * frameFloats) }
                        val ptrs = FloatArray(p * 256); ptrSlots.forEachIndexed { i, m -> m.ptr.copyInto(ptrs, i * 256) }
                        val mt = tensor(env, mem, *memShape); val pt = tensor(env, ptrs, p.toLong(), 256)
                        try {
                            timed(MEMATTN) { memAttn.run(mapOf("image_feat" to feat, "mem_feats" to mt, "ptrs" to pt)) }.use { ca ->
                                val d = timed(DEC_TRK) { decode(env, trk, ca.get("image_embed").get() as OnnxTensor, hr0, hr1, floatArrayOf(0f, 0f), floatArrayOf(-1f)) }
                                val cd = analyse(d.masks, 0, d.iou[0].toDouble())
                                val ratio = if (area > 0) cd.area / area else 1.0
                                val conf = (d.iou[0] * sigmoid(d.score.toDouble())) * (if (ratio in 0.25..4.0) 1.0 else 0.5)
                                if (d.score > 0f && cd.area > 0 && conf >= MIN_CONF) {
                                    val mf = timed(MEMENC) { encodeMemory(feat, d.masks, 0, d.score) }
                                    bank.push(Mem(mf, d.ptrs.copyOfRange(0, 256)))
                                    area = 0.8 * area + 0.2 * cd.area
                                    res[fi] = Step(cd.cx, cd.cy, conf.coerceAtMost(1.0), cd.box.w, cd.box.h)
                                }
                            }
                        } finally { mt.close(); pt.close() }
                    }
                }
            } finally { imgT.close() }
            onFrame()
        }
        return res
    }

    // ---------------------------------------------------------------- previous design: prompt per frame (encoder + decoder only)
    private suspend fun walkPrompt(s: Models, files: List<File>, order: List<Int>, pick: FreePick, ms: MutableMap<String, Double>, onFrame: () -> Unit): Map<Int, Step> {
        val env = s.env
        val res = HashMap<Int, Step>()
        var box: Box? = null; var area = 0.0; var vx = 0.0; var vy = 0.0; var lastCx = 0.0; var lastCy = 0.0
        var px = pick.x; var py = pick.y; var lost = 0
        for ((step, fi) in order.withIndex()) {
            currentCoroutineContext().ensureActive()
            val bmp = BitmapFactory.decodeFile(files[fi].path) ?: continue
            val img = try { chw(bmp) } finally { bmp.recycle() }
            val imgT = tensor(env, img, 1, 3, S.toLong(), S.toLong())
            try {
                val t0 = System.nanoTime()
                s.enc.run(mapOf("image" to imgT)).use { enc ->
                    ms[ENC] = (ms[ENC] ?: 0.0) + (System.nanoTime() - t0) / 1e6
                    val hr0 = enc.get("high_res_feats_0").get() as OnnxTensor; val hr1 = enc.get("high_res_feats_1").get() as OnnxTensor
                    val emb = enc.get("image_embed").get() as OnnxTensor
                    val t1 = System.nanoTime()
                    fun cands(c: FloatArray, l: FloatArray): List<Cand> {
                        val d = decode(env, s.dec, emb, hr0, hr1, c, l)
                        return (0 until d.n).map { analyse(d.masks, it, d.iou[it].toDouble()) }.filter { it.area > 0 }
                    }
                    if (step == 0) {
                        val seed = seedBox(pick)
                        val (c, l) = prompt3(seed, seed?.cx ?: pick.x, seed?.cy ?: pick.y)
                        val cs = cands(c, l)
                        if (cs.isEmpty()) return res
                        val pc = cs.filter { it.iouPred >= 0.85 * cs.maxOf { x -> x.iouPred } }.minByOrNull { it.area }!!
                        box = pc.box; area = pc.area; lastCx = pc.cx; lastCy = pc.cy
                        px = pc.inPx.toDouble() / M; py = pc.inPy.toDouble() / M
                        res[fi] = Step(pc.cx, pc.cy, pc.iouPred, pc.box.w, pc.box.h)
                    } else {
                        val b0 = box!!
                        val pred = b0.shift(vx, vy).grow(1.2 + 0.3 * lost.coerceAtMost(4))
                        val (c, l) = prompt3(pred, px, py)
                        val ratio = { a: Double -> if (area > 0) a / area else 1.0 }
                        val best = cands(c, l).map { cd -> cd to cd.iouPred * sqrt(cd.box.iou(pred)) * (if (ratio(cd.area) in 0.4..2.5) 1.0 else 0.5) }.maxByOrNull { it.second }
                        val conf = best?.second ?: 0.0
                        if (best != null && conf >= MIN_CONF) {
                            val cd = best.first
                            vx = (cd.cx - lastCx).coerceIn(-b0.w, b0.w); vy = (cd.cy - lastCy).coerceIn(-b0.h, b0.h)
                            lastCx = cd.cx; lastCy = cd.cy; box = cd.box; area = 0.7 * area + 0.3 * cd.area
                            px = cd.inPx.toDouble() / M; py = cd.inPy.toDouble() / M; lost = 0
                            res[fi] = Step(cd.cx, cd.cy, conf.coerceAtMost(1.0), cd.box.w, cd.box.h)
                        } else { lost++; vx *= 0.7; vy *= 0.7; box = pred }
                    }
                    ms[DEC] = (ms[DEC] ?: 0.0) + (System.nanoTime() - t1) / 1e6
                }
            } finally { imgT.close() }
            onFrame()
        }
        return res
    }

    /** Rows aligned to `doc.samples` (`[x, y, conf, w, h]`), or null when SAM cannot run (the caller falls back to the optical-flow tracker). */
    suspend fun track(ctx: Context, uri: Uri, doc: TrackDoc, pick: FreePick, onProgress: (Int, Int, Double) -> Unit): PointTrack? =
        withContext(Dispatchers.Default) {
            if (!hasModels(ctx)) return@withContext null
            val dir = File(ctx.cacheDir, "sam-${System.nanoTime()}").also { it.mkdirs() }
            try {
                val files = ArrayList<File>(); val ts = ArrayList<Double>()
                VideoDecoder.sample(ctx, uri, SAM_FPS, 0.0, maxSide = S) { f ->
                    currentCoroutineContext().ensureActive()
                    val file = File(dir, "${files.size}.jpg")
                    file.outputStream().use { f.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    files += file; ts += f.t
                    f.bitmap.recycle()
                    onProgress(files.size, 0, f.t)
                    true
                }
                if (files.size < 2) return@withContext null
                val total = files.size * 2
                var p = 0
                for (i in ts.indices) if (abs(ts[i] - pick.t) < abs(ts[p] - pick.t)) p = i

                suspend fun run(memory: Boolean): Map<Int, Step> {
                    var done = files.size
                    val ms = HashMap<String, Double>()
                    open(ctx, memory).use { s ->
                        val tick = { done++; onProgress(done, total, ts[(done - files.size - 1).coerceIn(0, ts.size - 1)]) }
                        val walk: suspend (List<Int>) -> Map<Int, Step> = { order -> if (memory) walkMemory(s, files, order, pick, ms, tick) else walkPrompt(s, files, order, pick, ms, tick) }
                        val steps = HashMap<Int, Step>(walk((p until files.size).toList()))
                        steps += walk((p downTo 0).toList()).filterKeys { it != p }
                        val n = max(1, files.size)
                        lastRun = (if (memory) "EdgeTAM memory tracker: " else "EdgeTAM prompt tracker: ") + s.summary(ms.mapValues { it.value / n }, files.size)
                        return steps
                    }
                }

                var steps: Map<Int, Step> = emptyMap()
                if (hasMemory(ctx)) {
                    steps = try { run(true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ErrorLog.record(ctx, "sam-memory", e); emptyMap() }
                }
                if (steps.isEmpty()) steps = run(false) // memory graphs missing/failed/nothing segmented: previous design
                if (steps.isEmpty()) return@withContext null
                val keys = steps.keys.sorted()
                val rows = doc.samples.map { smp ->
                    val t = smp.t
                    val hi = keys.firstOrNull { ts[it] >= t }
                    val lo = keys.lastOrNull { ts[it] <= t }
                    when {
                        lo == null || hi == null -> if ((lo ?: hi)?.let { abs(ts[it] - t) <= 0.5 / SAM_FPS } == true) steps[lo ?: hi]!!.row() else emptyList()
                        lo == hi -> steps[lo]!!.row()
                        ts[hi] - ts[lo] > MAX_GAP_S -> { // inside a long lost span: only the near-edge frames count
                            val near = if (t - ts[lo] <= ts[hi] - t) lo else hi
                            if (abs(ts[near] - t) <= 0.5 / SAM_FPS) steps[near]!!.row() else emptyList()
                        }
                        else -> {
                            val a = steps[lo]!!; val b = steps[hi]!!; val u = (t - ts[lo]) / (ts[hi] - ts[lo])
                            fun mix(x: Double, y: Double) = x + (y - x) * u
                            listOf(mix(a.cx, b.cx), mix(a.cy, b.cy), min(a.conf, b.conf), mix(a.w, b.w), mix(a.h, b.h))
                        }
                    }
                }
                PointTrack(a = rows, b = emptyList())
            } finally { dir.deleteRecursively() }
        }

    // ---------------------------------------------------------------- smart select preview ("Any object" on the pick screen)
    /** Runs the encoder ONCE on the shown frame (CPU: no long NPU compile while the user waits); every tap then only runs the small decoder. */
    suspend fun encodeFrame(ctx: Context, bmp: Bitmap): SamFrameCache? = withContext(Dispatchers.Default) {
        if (!hasModels(ctx)) return@withContext null
        val env = OrtEnvironment.getEnvironment()
        val imgT = tensor(env, chw(bmp), 1, 3, S.toLong(), S.toLong())
        try {
            Qnn.open(ctx, ENC, THREADS, false).use { enc ->
                enc.run(mapOf("image" to imgT)).use { r ->
                    val h0 = r.get("high_res_feats_0").get() as OnnxTensor
                    val h1 = r.get("high_res_feats_1").get() as OnnxTensor
                    val em = r.get("image_embed").get() as OnnxTensor
                    SamFrameCache(floats(h0), h0.info.shape, floats(h1), h1.info.shape, floats(em), em.info.shape)
                }
            }
        } finally { imgT.close() }
    }

    /** The object under a tap (same prompt and candidate rule as the scan's first frame, so what is highlighted is what gets followed). */
    suspend fun segmentAt(ctx: Context, cache: SamFrameCache, x: Double, y: Double): SamMask? = withContext(Dispatchers.Default) {
        if (!hasModels(ctx)) return@withContext null
        val env = OrtEnvironment.getEnvironment()
        val em = tensor(env, cache.embed, *cache.embedShape)
        val h0 = tensor(env, cache.hr0, *cache.hr0Shape)
        val h1 = tensor(env, cache.hr1, *cache.hr1Shape)
        try {
            Qnn.open(ctx, DEC, THREADS, false).use { dec ->
                val (c, l) = prompt3(null, x, y)
                val d = decode(env, dec, em, h0, h1, c, l)
                val cands = (0 until d.n).map { analyse(d.masks, it, d.iou[it].toDouble()) }
                val b = pickCandidate(cands)
                if (b < 0) null else {
                    val cd = cands[b]; val o = b * M * M
                    SamMask(M, BooleanArray(M * M) { d.masks[o + it] > 0f }, cd.box.x1, cd.box.y1, cd.box.x2, cd.box.y2, cd.area)
                }
            }
        } finally { em.close(); h0.close(); h1.close() }
    }

    // ---------------------------------------------------------------- benchmark (per graph, CPU vs NPU) + accuracy check
    private fun randomInputs(env: OrtEnvironment, m: OrtModel, image: FloatArray?): Map<String, OnnxTensor> {
        val rnd = java.util.Random(1)
        return m.inputNames().associateWith { n ->
            val shape = m.inputShape(n)
            val len = shape.fold(1L) { a, b -> a * b }.toInt()
            val data = when {
                n == "image" && image != null -> image
                n.endsWith("labels") -> FloatArray(len) { -1f }
                n.endsWith("coords") -> FloatArray(len) { rnd.nextFloat() * S }
                n == "object_score" -> FloatArray(len) { 5f }
                else -> FloatArray(len) { rnd.nextGaussian().toFloat() * 0.5f }
            }
            tensor(env, data, *shape)
        }
    }

    private fun thermal(ctx: Context): Int = ctx.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1

    private fun best(masks: FloatArray, n: Int, iou: FloatArray): FloatArray { val b = iou.indices.maxByOrNull { iou[it] } ?: 0; return masks.copyOfRange(b * M * M, (b + 1) * M * M) }

    private fun encDec(env: OrtEnvironment, enc: OrtModel, dec: OrtModel, img: FloatArray): Pair<FloatArray, FloatArray> {
        val imgT = tensor(env, img, 1, 3, S.toLong(), S.toLong())
        try {
            enc.run(mapOf("image" to imgT)).use { er ->
                val embed = er.get("image_embed").get() as OnnxTensor
                val (c, l) = prompt3(null, 0.5, 0.5)
                val d = decode(env, dec, embed, er.get("high_res_feats_0").get() as OnnxTensor, er.get("high_res_feats_1").get() as OnnxTensor, c, l)
                return floats(embed) to best(d.masks, d.n, d.iou)
            }
        } finally { imgT.close() }
    }

    /** Times every present graph on CPU and on the NPU (random inputs of the right shape; the encoder gets `bmp`) and compares the encoder+decoder result of both providers on `bmp`. */
    suspend fun bench(ctx: Context, bmp: Bitmap, runs: Int, onProgress: (String) -> Unit): SamBenchReport? = withContext(Dispatchers.Default) {
        if (!hasModels(ctx)) return@withContext null
        val env = OrtEnvironment.getEnvironment()
        val img = chw(bmp)
        val rows = ArrayList<SamBenchRow>()
        val present = ModelStore.list(ctx)
        for (name in COMPONENTS.filter { it in present }) {
            fun time(npu: Boolean): Triple<BenchStats?, String?, Long> {
                onProgress("$name ${if (npu) "NPU" else "CPU"}")
                var opened: OrtModel? = null
                try {
                    val model = Qnn.open(ctx, name, THREADS, npu)
                    opened = model
                    val inputs = randomInputs(env, model, img)
                    try {
                        repeat(2) { model.run(inputs).close() }
                        val t = ArrayList<Double>(runs)
                        repeat(runs) { val t0 = System.nanoTime(); model.run(inputs).close(); t += (System.nanoTime() - t0) / 1e6 }
                        val note = if (npu) (model.fallback ?: if (!model.npu) "ran on CPU" else null) else null
                        return Triple(benchStats(t), note, model.loadMs)
                    } finally { inputs.values.forEach { it.close() } }
                } catch (e: Throwable) {
                    return Triple(null, (e.message ?: e.javaClass.simpleName).take(120), opened?.loadMs ?: 0L)
                } finally { opened?.close() }
            }
            val cpu = time(false); val npu = time(true)
            rows += SamBenchRow(name, cpu.first, npu.first, npu.second, npu.third)
        }
        onProgress("accuracy")
        val parity = try {
            Qnn.open(ctx, ENC, THREADS, false).use { ce -> Qnn.open(ctx, DEC, THREADS, false).use { cd ->
                Qnn.open(ctx, ENC, THREADS, true).use { ne -> Qnn.open(ctx, DEC, THREADS, true).use { nd ->
                    if (!ne.npu || !nd.npu) "NPU not used for encoder/decoder (${ne.fallback ?: nd.fallback ?: "ran on CPU"}): nothing to compare"
                    else {
                        val (ea, ma) = encDec(env, ce, cd, img); val (eb, mb) = encDec(env, ne, nd, img)
                        var dot = 0.0; var na = 0.0; var nb = 0.0
                        for (i in ea.indices) { dot += ea[i] * eb[i]; na += ea[i] * ea[i]; nb += eb[i] * eb[i] }
                        var inter = 0; var uni = 0
                        for (i in ma.indices) { val a = ma[i] > 0; val b = mb[i] > 0; if (a && b) inter++; if (a || b) uni++ }
                        "NPU vs CPU: image-embed cosine %.4f, mask IoU %s".format(dot / sqrt(na * nb + 1e-12), if (uni == 0) "n/a (empty mask)" else "%.3f".format(inter.toDouble() / uni))
                    }
                } } } }
        } catch (e: Throwable) { "accuracy check failed: ${(e.message ?: e.javaClass.simpleName).take(120)}" }
        SamBenchReport(rows, parity, thermal(ctx), lastRun)
    }
}
