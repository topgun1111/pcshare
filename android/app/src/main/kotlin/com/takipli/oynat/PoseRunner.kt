package com.takipli.oynat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import com.takipli.oynat.core.BenchStats
import com.takipli.oynat.core.Pose
import com.takipli.oynat.core.benchStats
import com.takipli.oynat.core.decodeYoloPose
import com.takipli.oynat.core.letterboxLayout
import com.takipli.oynat.core.preprocessChw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/** Models available to the bench: APK assets `models/NAME.onnx` plus side-loaded `files/models/NAME.onnx`. */
object ModelStore {
    private const val DIR = "models"

    private fun sideDir(ctx: Context): File? = ctx.getExternalFilesDir(DIR)

    fun list(ctx: Context): List<String> {
        val assets = ctx.assets.list(DIR).orEmpty().filter { it.endsWith(".onnx") }
        val side = sideDir(ctx)?.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.map { it.name }.orEmpty()
        return (assets + side).distinct().sorted()
    }

    /** A real file path for ORT. Side-loaded files win; asset copies are refreshed when the size differs. */
    fun materialize(ctx: Context, name: String): File {
        sideDir(ctx)?.resolve(name)?.takeIf { it.isFile }?.let { return it }
        val out = File(ctx.filesDir, "$DIR/$name")
        out.parentFile?.mkdirs()
        val size = runCatching { ctx.assets.openFd("$DIR/$name").use { it.length } }.getOrDefault(-1L)
        if (!out.isFile || size < 0 || out.length() != size) {
            ctx.assets.open("$DIR/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }
}

class PoseResult(
    val poses: List<Pose>,
    val preMs: Double,
    val inferMs: Double,
    val postMs: Double,
) {
    val totalMs: Double get() = preMs + inferMs + postMs
}

class BenchReport(
    val total: BenchStats,
    val pre: BenchStats,
    val infer: BenchStats,
    val post: BenchStats,
    val poses: Int,
)

/** YOLO-pose through ONNX Runtime (CPU). One instance = one session; calls must not overlap. */
class PoseRunner(context: Context, val modelName: String, val threads: Int, val wantNpu: Boolean = false) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val inputH: Int
    val inputW: Int
    val loadMs: Long
    /** True when the session really runs on the Hexagon NPU (QNN HTP). */
    val npu: Boolean
    /** Why the NPU was not used although requested (null = not requested or fine). */
    val npuFallback: String?
    val modelMb: Double
    private var argb = IntArray(0)
    private val chw: FloatArray

    init {
        val t0 = System.nanoTime()
        val file = ModelStore.materialize(context, modelName)
        modelMb = file.length() / 1e6
        var fell: String? = null
        var s: OrtSession? = null
        if (wantNpu) {
            try {
                s = createNpuSession(context, file)
            } catch (e: Throwable) { // QNN missing/unsupported ops/compile failure: keep the app usable on CPU
                fell = (e.message ?: e.javaClass.simpleName).take(160)
            }
        }
        session = s ?: createCpuSession(file)
        npu = s != null
        npuFallback = fell
        inputName = session.inputNames.first()
        val shape = (session.inputInfo.getValue(inputName).info as TensorInfo).shape // [1,3,H,W]
        inputH = shape.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: 640
        inputW = shape.getOrNull(3)?.takeIf { it > 0 }?.toInt() ?: 640
        chw = FloatArray(3 * inputH * inputW)
        loadMs = (System.nanoTime() - t0) / 1_000_000
    }

    val provider: String get() = if (npu) "NPU QNN-HTP v75 fp16" else "CPU x$threads"

    private fun createCpuSession(file: File): OrtSession = Qnn.cpuSession(env, file, threads)

    /** QNN HTP (v75) through the shared [Qnn] helper; throws when the graph cannot run there (caller falls back to CPU). */
    private fun createNpuSession(context: Context, file: File): OrtSession = Qnn.npuSession(context, env, file, modelName, threads)

    /** `bmp` must be a software bitmap (not HARDWARE). */
    fun detect(bmp: Bitmap, conf: Double = 0.25, iou: Double = 0.45): PoseResult {
        val t0 = System.nanoTime()
        val layout = letterboxLayout(bmp.width, bmp.height, inputH, inputW)
        val scaled = if (bmp.width == layout.newW && bmp.height == layout.newH) bmp
        else Bitmap.createScaledBitmap(bmp, layout.newW, layout.newH, true)
        val n = layout.newW * layout.newH
        if (argb.size != n) argb = IntArray(n)
        scaled.getPixels(argb, 0, layout.newW, 0, 0, layout.newW, layout.newH)
        if (scaled !== bmp) scaled.recycle()
        preprocessChw(argb, layout, inputH, inputW, chw)
        val t1 = System.nanoTime()

        val (raw, dim0, dim1) = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(chw), longArrayOf(1, 3, inputH.toLong(), inputW.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { res ->
                val out = res.get(0) as OnnxTensor
                val shape = out.info.shape // [1,56,N] or [1,N,56]
                val fb = out.floatBuffer
                Triple(FloatArray(fb.remaining()).also { fb.get(it) }, shape[1].toInt(), shape[2].toInt())
            }
        }
        val t2 = System.nanoTime()
        val poses = decodeYoloPose(raw, dim0, dim1, layout.info, bmp.width, bmp.height, conf, iou)
        val t3 = System.nanoTime()
        return PoseResult(poses, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6)
    }

    /** Full pipeline (resize + preprocess + inference + decode) `runs` times after `warmup` unmeasured runs. */
    suspend fun bench(
        bmp: Bitmap,
        runs: Int = 50,
        warmup: Int = 3,
        onProgress: (Int) -> Unit = {},
    ): BenchReport = withContext(Dispatchers.Default) {
        repeat(warmup) { ensureActive(); detect(bmp) }
        val all = ArrayList<Double>(runs)
        val pre = ArrayList<Double>(runs)
        val inf = ArrayList<Double>(runs)
        val post = ArrayList<Double>(runs)
        var last = 0
        for (i in 1..runs) {
            ensureActive()
            val r = detect(bmp)
            all.add(r.totalMs); pre.add(r.preMs); inf.add(r.inferMs); post.add(r.postMs)
            last = r.poses.size
            onProgress(i)
        }
        BenchReport(benchStats(all), benchStats(pre), benchStats(inf), benchStats(post), last)
    }

    override fun close() {
        session.close()
    }
}
