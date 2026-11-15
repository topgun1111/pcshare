package com.takipli.oynat

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.takipli.oynat.core.Config
import com.takipli.oynat.core.Formats
import com.takipli.oynat.core.ScanPipeline
import com.takipli.oynat.core.Target
import com.takipli.oynat.core.TargetPick
import com.takipli.oynat.core.TrackDoc
import com.takipli.oynat.core.buildScanDoc
import com.takipli.oynat.core.livePickTarget
import com.takipli.oynat.core.previewOverlay
import com.takipli.oynat.core.scanSig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class ScanProgress(
    /** samples so far, including the ones loaded from an interrupted scan */
    val samples: Int,
    /** estimate of the final sample count (0 = unknown duration) */
    val total: Int,
    val t: Double,
    val cuts: Int,
    /** samples that came from the interrupted scan this run resumed */
    val resumed: Int,
)

class ScanResult(val doc: TrackDoc, val cached: Boolean, val wallS: Double, val newSamples: Int, val videoS: Double) {
    val xRealtime: Double get() = if (wallS > 0) videoS / wallS else 0.0
}

/**
 * Part 9 scan engine: decode (Part 7) -> pose model (Part 5/6) -> camera motion + cut (Part 8) -> ByteTrack +
 * pose association (`core.ScanPipeline`) -> `track.json` version 2. Resumable: an interrupted or cancelled scan leaves
 * `<trackFile>.partial`; the next call with the same signature (video, fps, model) continues after its last sample.
 * A finished scan with a matching signature is returned without work (cache).
 */
object Scanner {
    private const val SAVE_EVERY_MS = 30_000L

    private fun readDoc(f: File, sig: String): TrackDoc? =
        runCatching { Formats.parseTrack(f.readText()) }.getOrNull()?.takeIf { it.sig == sig && it.samples.isNotEmpty() }

    internal fun writeAtomic(f: File, doc: TrackDoc) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Formats.encodeTrack(doc))
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "cannot write ${f.name}" } }
    }

    /**
     * `videoId` identifies the video file (the caller uses uri + size + modified time). `fps` = sample rate.
     * Throws on decode/model errors after saving the partial scan; cancels cleanly (partial saved).
     */
    suspend fun scan(
        ctx: Context,
        uri: Uri,
        videoId: String,
        runner: PoseRunner,
        fps: Double,
        trackFile: File,
        cfg: Config = Config(),
        maxSide: Int = 960,
        pick: TargetPick? = null,
        onPreview: ((ScanPreview) -> Unit)? = null,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        val info = VideoDecoder.probe(ctx, uri)
        val modelTag = "${runner.modelName}:${(runner.modelMb * 1e6).toLong()}" + if (runner.npu) ":npu" else ""
        val sig = scanSig(videoId, fps, modelTag)
        val partial = File(trackFile.path + ".partial")

        readDoc(trackFile, sig)?.takeIf { it.version >= 2 }?.let { return@withContext ScanResult(it, true, 0.0, 0, 0.0) }
        val prior = readDoc(partial, sig)?.samples.orEmpty()
        val pipe = ScanPipeline(cfg)
        pipe.resume(prior)
        val startS = prior.lastOrNull()?.let { it.t + 0.5 / fps } ?: 0.0 // half a grid step: next grid time, no repeat
        val useAnalysis = (cfg.gmc || cfg.cutDetect) && OpenCvFlow.available
        val analyzer = if (useAnalysis) FrameAnalyzer(cfg.gmcSide, cfg.cutHist, cfg.cutDiff) else null
        val total = if (info.durationS > 0) prior.size + ((info.durationS - startS) * fps).toInt().coerceAtLeast(0) else 0
        var cuts = prior.count { it.cut == 1 }
        var lastSave = SystemClock.elapsedRealtime()
        var lastPreview = 0L
        var liveTarget: Target? = null

        fun snapshot() = buildScanDoc(
            uri.toString(), info.width, info.height, info.frameRate ?: fps, info.durationS, fps,
            pipe.samples.toList(), sig, videoId,
        )

        try {
            VideoDecoder.sample(ctx, uri, fps, startS, maxSide = maxSide) { f ->
                currentCoroutineContext().ensureActive()
                val bmp = f.bitmap
                val poses = runner.detect(bmp, cfg.liveDetConf, cfg.detIou).poses
                // Like Pipeline.step: a failed camera estimate never stops tracking.
                val a = analyzer?.let { runCatching { it.analyze(bmp, pipe.boxes) }.getOrNull() }
                val cut = cfg.cutDetect && a?.cut == true
                val motion = if (cfg.gmc && a != null && !cut && a.motionOk) a.motion else null
                val s = pipe.step(f.t, bmp.width, bmp.height, poses, motion, cut)
                // Part 12: at most ~5 small previews per second; only a bitmap copy + a tiny overlay, never blocks the scan
                val now = SystemClock.elapsedRealtime()
                if (onPreview != null && now - lastPreview >= PREVIEW_MIN_MS) {
                    lastPreview = now
                    if (liveTarget == null && pick != null) liveTarget = livePickTarget(pipe.samples, bmp.width, bmp.height, pick, cfg)
                    onPreview(ScanPreview(f.t, previewBitmap(bmp), previewOverlay(s, bmp.width, bmp.height, liveTarget, cfg)))
                }
                bmp.recycle()
                if (s.cut == 1) cuts++
                onProgress(ScanProgress(pipe.samples.size, total, f.t, cuts, prior.size))
                if (SystemClock.elapsedRealtime() - lastSave >= SAVE_EVERY_MS) {
                    writeAtomic(partial, snapshot()); lastSave = SystemClock.elapsedRealtime()
                }
                true
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (pipe.samples.isNotEmpty()) runCatching { writeAtomic(partial, snapshot()) }
                Unit
            }
            throw e
        }
        val doc = snapshot()
        writeAtomic(trackFile, doc)
        partial.delete()
        val newSamples = pipe.samples.size - prior.size
        val videoS = if (newSamples > 0) pipe.samples.last().t - (prior.lastOrNull()?.t ?: pipe.samples.first().t) else 0.0
        ScanResult(doc, false, (SystemClock.elapsedRealtime() - t0) / 1000.0, newSamples, videoS)
    }
}
