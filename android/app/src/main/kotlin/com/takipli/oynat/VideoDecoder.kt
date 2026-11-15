package com.takipli.oynat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.takipli.oynat.core.SampleSelector
import com.takipli.oynat.core.displaySize
import com.takipli.oynat.core.normalizeRotation
import com.takipli.oynat.core.scaledSize
import com.takipli.oynat.core.yuv709ToArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Video track facts. `width`/`height` are the display size (rotation applied); `durationS` 0 = unknown. */
data class VideoInfo(
    val codedW: Int,
    val codedH: Int,
    val rotation: Int,
    val durationS: Double,
    val frameRate: Double?,
    val mime: String,
) {
    val width: Int get() = displaySize(codedW, codedH, rotation).first
    val height: Int get() = displaySize(codedW, codedH, rotation).second
}

/** One sampled frame: `t` = the frame's real presentation time (s); `bitmap` upright, longer side <= maxSide. */
class SampledFrame(val t: Double, val bitmap: Bitmap)

object VideoDecoder {
    private fun openExtractor(ctx: Context, uri: Uri): Pair<MediaExtractor, Int> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null) // content:// (Storage Access Framework) or file
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return ex to i
            }
        } catch (e: Throwable) {
            ex.release()
            throw e
        }
        ex.release()
        throw IllegalArgumentException("no video track")
    }

    private fun MediaFormat.intOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def

    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val (ex, i) = openExtractor(ctx, uri)
        try {
            val f = ex.getTrackFormat(i)
            return VideoInfo(
                codedW = f.getInteger(MediaFormat.KEY_WIDTH),
                codedH = f.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = normalizeRotation(f.intOr(MediaFormat.KEY_ROTATION, 0)),
                durationS = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0,
                frameRate = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() else null,
                mime = f.getString(MediaFormat.KEY_MIME).orEmpty(),
            )
        } finally {
            ex.release()
        }
    }

    /**
     * Default frame source: [MediaMetadataRetriever] at each grid time (`startS + k/fps`). The framework does the
     * decoding and colour conversion (also 10-bit/HDR), so this process never reads `Image` planes: that path
     * ([sampleImageReader]) killed the app natively on a 4K H.264 clip (vivo, Android 17). The frame is taken at full
     * size and rotated/downscaled here in one pass, because the retriever's own scaling/rotation behaves differently
     * between devices (a rotated clip came out sideways). `t` = the grid time (closest frame, at most half a source
     * frame away). Same contract as before: `onFrame` returns false to stop, cancellable, returns frames delivered.
     */
    suspend fun sample(
        ctx: Context,
        uri: Uri,
        fps: Double,
        startS: Double = 0.0,
        endS: Double = Double.POSITIVE_INFINITY,
        maxSide: Int = 960,
        onFrame: suspend (SampledFrame) -> Boolean,
    ): Int = withContext(Dispatchers.Default) {
        Trace.reset(ctx)
        val info = probe(ctx, uri)
        val lastT = minOf(endS, if (info.durationS > 0) info.durationS - 0.001 else Double.POSITIVE_INFINITY)
        Trace.step(ctx, "retriever display=${info.width}x${info.height} coded=${info.codedW}x${info.codedH} rot=${info.rotation} fps=$fps startS=$startS dur=${info.durationS}")
        val r = MediaMetadataRetriever()
        var delivered = 0
        try {
            r.setDataSource(ctx, uri)
            Trace.step(ctx, "source ok")
            var misses = 0
            var k = 0
            while (true) {
                ensureActive()
                val t = startS + k / fps
                k++
                if (t > lastT) break
                val raw = fullFrame(r, (t * 1e6).toLong())
                if (raw == null) {
                    if (++misses >= 5) break
                    continue
                }
                misses = 0
                val bm = uprightScaled(raw, info, maxSide)
                if (delivered < 3) Trace.step(ctx, "frame t=$t raw=${raw.width}x${raw.height} -> ${bm.width}x${bm.height}")
                if (bm !== raw) raw.recycle()
                delivered++
                if (!onFrame(SampledFrame(t, bm))) break
            }
        } finally {
            runCatching { r.release() }
        }
        delivered
    }

    private fun fullFrame(r: MediaMetadataRetriever, us: Long): Bitmap? {
        val bm = if (Build.VERSION.SDK_INT >= 30) {
            val p = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, p)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        }
        if (bm == null || bm.config == Bitmap.Config.ARGB_8888) return bm
        return bm.copy(Bitmap.Config.ARGB_8888, false).also { bm.recycle() }
    }

    /**
     * Upright and `maxSide` at most, in one matrix pass. Rotation is applied only when the frame's orientation (landscape
     * vs portrait) differs from the clip's display orientation, so it is right whether or not the retriever already
     * rotated it. (A 180 degree clip cannot be told apart this way: it is left as returned.)
     */
    private fun uprightScaled(src: Bitmap, info: VideoInfo, maxSide: Int): Bitmap {
        val needRotate = info.rotation % 180 != 0 && ((src.width >= src.height) != (info.width >= info.height))
        val rotated = if (needRotate) src.height to src.width else src.width to src.height
        val (ow, oh) = scaledSize(rotated.first, rotated.second, maxSide)
        if (!needRotate && ow == src.width && oh == src.height) return src
        val m = Matrix()
        if (needRotate) m.postRotate(info.rotation.toFloat())
        m.postScale(ow.toFloat() / rotated.first, oh.toFloat() / rotated.second)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * LEGACY, unused (crashes natively when reading the first frame on the vivo/Android 17 test phone; kept for a later
     * sequential-decode retry). Decodes in presentation order and hands `onFrame` the frames chosen by [SampleSelector] (grid at `fps`,
     * starting at `startS`, stopping after `endS`). Only chosen frames are rendered/converted, the rest are
     * decoded and dropped. Seeks to the sync frame before `startS`, so resuming mid-video is cheap.
     * `onFrame` returns false to stop. Cancellable. Returns the number of samples delivered.
     */
    suspend fun sampleImageReader(
        ctx: Context,
        uri: Uri,
        fps: Double,
        startS: Double = 0.0,
        endS: Double = Double.POSITIVE_INFINITY,
        maxSide: Int = 960,
        onFrame: suspend (SampledFrame) -> Boolean,
    ): Int = withContext(Dispatchers.Default) {
        Trace.reset(ctx)
        Trace.step(ctx, "sample start fps=$fps startS=$startS")
        val (ex, track) = openExtractor(ctx, uri)
        val format = ex.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val rotation = normalizeRotation(format.intOr(MediaFormat.KEY_ROTATION, 0))
        Trace.step(ctx, "extractor ok: $format")
        // 10-bit / HDR output cannot go through the 8-bit YUV_420_888 reader (and can kill the process on some phones): refuse clearly.
        val profile = format.intOr(MediaFormat.KEY_PROFILE, -1)
        val tenBit = (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && (profile == 2 || profile >= 4096)) ||
            (mime == MediaFormat.MIMETYPE_VIDEO_AVC && profile == 16)
        if (tenBit) {
            ex.release()
            throw IllegalArgumentException("10-bit/HDR video is not supported yet (profile $profile). Use an 8-bit H.264 or HEVC clip.")
        }
        val thread = HandlerThread("frame-reader").also { it.start() }
        val queue = LinkedBlockingQueue<Image>()
        Trace.step(ctx, "imagereader")
        val reader = ImageReader.newInstance(
            format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
            ImageFormat.YUV_420_888, 4,
        )
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireNextImage()?.let { queue.offer(it) } } catch (e: Throwable) { Trace.step(ctx, "listener error: $e") }
        }, Handler(thread.looper))
        Trace.step(ctx, "codec create $mime")
        val codec = MediaCodec.createDecoderByType(mime)
        Trace.step(ctx, "codec ${codec.name}")
        var delivered = 0
        try {
            ex.selectTrack(track)
            if (startS > 0.0) ex.seekTo((startS * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            Trace.step(ctx, "configure")
            codec.configure(format, reader.surface, null, 0)
            Trace.step(ctx, "start")
            codec.start()
            Trace.step(ctx, "loop")
            var seen = 0
            val sel = SampleSelector(fps, startS, endS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi < 0) continue // try-again / format or buffers changed: nothing to do
                if (seen++ < 3) Trace.step(ctx, "out size=${info.size} us=${info.presentationTimeUs} flags=${info.flags}")
                val t = info.presentationTimeUs / 1e6
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val take = info.size > 0 && sel.accept(t)
                codec.releaseOutputBuffer(oi, take) // render only the frames we keep
                var go = !eos && !(info.size > 0 && sel.isPastEnd(t))
                if (take) {
                    if (delivered < 3) Trace.step(ctx, "take t=$t")
                    val img = queue.poll(3, TimeUnit.SECONDS) ?: error("decoder delivered no image")
                    if (delivered < 3) Trace.step(ctx, "image ${img.width}x${img.height} crop=${img.cropRect}")
                    val bmp = try { toBitmap(img, rotation, maxSide) } finally { img.close() }
                    if (delivered < 3) Trace.step(ctx, "bitmap ${bmp.width}x${bmp.height}")
                    delivered++
                    if (!onFrame(SampledFrame(t, bmp))) go = false
                }
                if (!go) outputDone = true
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            reader.close()
            queue.forEach { it.close() }
            thread.quitSafely()
            ex.release()
        }
        delivered
    }

    /** YUV_420_888 -> upright ARGB bitmap, nearest-neighbour downscale to `maxSide` (cheap; quality upgrade is a later step). */
    private fun toBitmap(img: Image, rotation: Int, maxSide: Int): Bitmap {
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val (ow, oh) = scaledSize(cw, ch, maxSide)
        val (yp, up, vp) = img.planes.let { Triple(it[0], it[1], it[2]) }
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val px = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = crop.top + (oy.toLong() * ch / oh).toInt()
            for (ox in 0 until ow) {
                val sx = crop.left + (ox.toLong() * cw / ow).toInt()
                val y = yb.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 255
                val u = ub.get((sy shr 1) * up.rowStride + (sx shr 1) * up.pixelStride).toInt() and 255
                val v = vb.get((sy shr 1) * vp.rowStride + (sx shr 1) * vp.pixelStride).toInt() and 255
                px[oy * ow + ox] = yuv709ToArgb(y, u, v)
            }
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, ow, oh, m, true).also { bmp.recycle() }
    }
}
