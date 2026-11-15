package com.takipli.oynat

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import java.io.File

/**
 * ONNX Runtime sessions on the Hexagon NPU (QNN HTP, Snapdragon 8 Gen 3 = v75) with a CPU fallback, shared by the pose
 * models ([PoseRunner]) and the EdgeTAM graphs (`sam` build). fp32 graphs run with HTP fp16 precision; QDQ int8 graphs run natively.
 */
object Qnn {
    private const val HTP_ARCH = "75"

    fun cpuSession(env: OrtEnvironment, file: File, threads: Int): OrtSession =
        OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(threads)
            o.setInterOpNumThreads(1)
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(file.absolutePath, o)
        }

    /**
     * QNN HTP session. The first load compiles the graph and dumps a QNN context model to `files/qnn_ctx/`; later loads open
     * that file directly. The cache name carries the model file's size and mtime, so a re-exported model never reuses a stale
     * context (older contexts of the same model are deleted). Throws when QNN cannot run the graph: callers fall back to CPU.
     */
    fun npuSession(ctx: Context, env: OrtEnvironment, file: File, base: String, threads: Int): OrtSession {
        val libDir = ctx.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", libDir, true) // HTP skel libs are loaded from disk by the DSP
        val dir = File(ctx.filesDir, "qnn_ctx").also { it.mkdirs() }
        val stem = base.removeSuffix(".onnx")
        val sig = java.lang.Long.toHexString(file.length() xor (file.lastModified() * 31))
        val ctxFile = File(dir, "${stem}_${sig}_htp_v$HTP_ARCH.onnx")
        dir.listFiles { f -> f.name.startsWith("${stem}_") && f.name.endsWith("_htp_v$HTP_ARCH.onnx") && f != ctxFile }?.forEach { it.delete() }
        val cached = ctxFile.isFile && ctxFile.length() > 0
        return OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(threads)
            o.addConfigEntry("session.disable_cpu_ep_fallback", "0") // unsupported ops may run on CPU
            if (!cached) {
                o.addConfigEntry("ep.context_enable", "1")
                o.addConfigEntry("ep.context_file_path", ctxFile.absolutePath)
                o.addConfigEntry("ep.context_embed_mode", "1")
            }
            o.addQnn(
                mapOf(
                    "backend_path" to "$libDir/libQnnHtp.so",
                    "htp_performance_mode" to "burst",
                    "htp_arch" to HTP_ARCH,
                    "enable_htp_fp16_precision" to "1",
                ),
            )
            val sess = env.createSession((if (cached) ctxFile else file).absolutePath, o)
            if (!cached && !ctxFile.isFile) ctxFile.delete()
            sess
        }
    }

    /** One graph on NPU (when `wantNpu`) or CPU; see [OrtModel]. */
    fun open(ctx: Context, name: String, threads: Int, wantNpu: Boolean): OrtModel {
        val env = OrtEnvironment.getEnvironment()
        val file = ModelStore.materialize(ctx, name)
        val t0 = System.nanoTime()
        var fell: String? = null
        var s: OrtSession? = null
        if (wantNpu) {
            try { s = npuSession(ctx, env, file, name, threads) }
            catch (e: Throwable) { fell = (e.message ?: e.javaClass.simpleName).take(160) }
        }
        return OrtModel(env, file, name, threads, s ?: cpuSession(env, file, threads), s != null, fell, (System.nanoTime() - t0) / 1_000_000)
    }
}

/**
 * A graph with its provider. [run] switches to CPU for good when the NPU session fails while running (some HTP failures only
 * show on the first `run`), so a tracker never dies because of the accelerator; [fallback] says why.
 */
class OrtModel internal constructor(
    private val env: OrtEnvironment,
    private val file: File,
    val name: String,
    private val threads: Int,
    private var session: OrtSession,
    npu: Boolean,
    fallback: String?,
    val loadMs: Long,
) : AutoCloseable {
    var npu: Boolean = npu; private set
    var fallback: String? = fallback; private set
    val provider: String get() = if (npu) "NPU" else "CPU"

    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result =
        try {
            session.run(inputs)
        } catch (e: OrtException) {
            if (!npu) throw e
            fallback = "run: " + (e.message ?: "").take(140)
            session.close()
            session = Qnn.cpuSession(env, file, threads)
            npu = false
            session.run(inputs)
        }

    /** Static shape of an input (all EdgeTAM graphs are static; unknown dims come back as -1). */
    fun inputShape(n: String): LongArray = (session.inputInfo.getValue(n).info as TensorInfo).shape
    fun inputNames(): Set<String> = session.inputNames
    fun outputShape(n: String): LongArray = (session.outputInfo.getValue(n).info as TensorInfo).shape

    override fun close() = session.close()
}
