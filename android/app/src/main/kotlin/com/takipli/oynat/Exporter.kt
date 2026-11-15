package com.takipli.oynat

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.takipli.oynat.core.StabPlan
import com.takipli.oynat.core.ViewKey
import com.takipli.oynat.core.exportBitrate
import com.takipli.oynat.core.exportNdc
import com.takipli.oynat.core.viewAtSmooth
import java.io.File

/**
 * Part 14: follow-cam export with Media3 Transformer. The views drive a time-varying [MatrixTransformation] (zoom + pan =
 * the same crop as `cropRect`, see `exportNdc`), the frame size stays the source size, the encoder is the hardware
 * H.264 (or HEVC) one, and the audio track is passed through untouched. Must be called on the main thread (Transformer's
 * looper); callbacks arrive there too. The file is written to the app cache first, then copied to Movies ([saveToMovies]).
 *
 * With a [StabPlan] every frame gets its own matrix (shake and roll removed, see `Stabilize.kt`) instead of the zoom + pan of
 * the views; the frame size is still the source size (native resolution).
 */
object Exporter {
    fun start(
        ctx: Context, source: Uri, views: List<ViewKey>, outFile: File,
        w: Int, h: Int, fps: Double, hevc: Boolean,
        plan: StabPlan? = null,
        onDone: (ExportResult) -> Unit, onError: (ExportException) -> Unit,
    ): Transformer {
        outFile.parentFile?.mkdirs()
        outFile.delete()
        val crop = MatrixTransformation { us ->
            if (plan != null) {
                val m = plan.ndcMatrix(us) // row-major 3x3 = android.graphics.Matrix.setValues order
                Matrix().apply { setValues(FloatArray(9) { m[it].toFloat() }) }
            } else {
                val n = exportNdc(viewAtSmooth(views, us / 1e6))
                Matrix().apply { setTranslate(n[1].toFloat(), n[2].toFloat()); postScale(n[0].toFloat(), n[0].toFloat()) }
            }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setEffects(Effects(emptyList(), listOf(crop))).build()
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(exportBitrate(w, h, fps, hevc)).build())
            .build()
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) = onDone(exportResult)
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) = onError(exportException)
            })
            .build()
        transformer.start(item, outFile.absolutePath)
        return transformer
    }

    /** 0..1, or -1 when the transformer cannot tell yet. Main thread only. */
    fun progress(t: Transformer): Float {
        val holder = ProgressHolder()
        return if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else -1f
    }

    /** Copies the finished file into Movies/TakipliOynat (MediaStore, no storage permission) and returns its URI. */
    fun saveToMovies(ctx: Context, file: File, displayName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TakipliOynat")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) { "cannot create the output file" }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "cannot open the output file" }.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
