package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** One recognised word; [box] is in pixels of the bitmap that was recognised. */
data class OcrWord(val text: String, val box: RectF)

/** One recognised line (reading order); [words] are its elements. */
data class OcrLine(val text: String, val box: RectF, val words: List<OcrWord>)

/**
 * Text recognition with geometry (for select-and-copy in the image viewer). Separate from the search indexer in core/ClipEngine.kt,
 * which only needs a flat string. Callbacks always arrive on the main thread.
 */
interface OcrEngine {
    fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit)
    fun close()
}

/**
 * ML Kit Latin recogniser through Google Play Services (same artifact as ClipEngine): the model is fetched by Play Services on first
 * use and works offline afterwards. If it is missing, [recognize] asks Play Services to download it and polls until it is there
 * (max 3 minutes), reporting through [onStatus].
 */
class MlKitOcr(private val ctx: Context) : OcrEngine {
    private val ui = Handler(Looper.getMainLooper())
    private var rec: TextRecognizer? = null
    @Volatile private var closed = false

    /** Short human-readable progress ("Downloading text model..."), main thread. */
    var onStatus: ((String) -> Unit)? = null

    private fun recogniser(): TextRecognizer = rec ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { rec = it }

    override fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        if (closed) { cb(Result.failure(IllegalStateException("closed"))); return }
        val r = try { recogniser() } catch (e: Throwable) { cb(Result.failure(Exception(NOT_AVAILABLE))); return }
        val client = try { ModuleInstall.getClient(ctx) } catch (e: Throwable) { null }
        if (client == null) { run(r, bm, cb); return }
        client.areModulesAvailable(r)
            .addOnSuccessListener { resp -> if (resp.areModulesAvailable()) run(r, bm, cb) else download(client, r, bm, cb) }
            .addOnFailureListener { run(r, bm, cb) }          // availability check itself failed: just try, process() reports a readable error
    }

    private fun download(client: ModuleInstallClient, r: TextRecognizer, bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        onStatus?.invoke("Downloading text model...")
        client.installModules(ModuleInstallRequest.newBuilder().addApi(r).build())
            .addOnSuccessListener { poll(client, r, bm, cb, System.currentTimeMillis() + DOWNLOAD_MS) }
            .addOnFailureListener { cb(Result.failure(Exception(NOT_AVAILABLE))) }
    }

    private fun poll(client: ModuleInstallClient, r: TextRecognizer, bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit, deadline: Long) {
        ui.postDelayed({
            if (closed) return@postDelayed
            client.areModulesAvailable(r)
                .addOnSuccessListener { resp ->
                    if (resp.areModulesAvailable()) run(r, bm, cb)
                    else if (System.currentTimeMillis() < deadline) poll(client, r, bm, cb, deadline)
                    else cb(Result.failure(Exception("The text model is still downloading. Try again in a few minutes.")))
                }
                .addOnFailureListener { cb(Result.failure(Exception(NOT_AVAILABLE))) }
        }, 2000)
    }

    private fun run(r: TextRecognizer, bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        if (closed) return
        onStatus?.invoke("Reading text...")
        try {
            r.process(InputImage.fromBitmap(bm, 0))
                .addOnSuccessListener { t -> if (!closed) cb(Result.success(map(t))) }
                .addOnFailureListener { e -> if (!closed) cb(Result.failure(Exception(e.message ?: NOT_AVAILABLE))) }
        } catch (e: Throwable) { cb(Result.failure(Exception(e.message ?: "Cannot read this picture"))) }
    }

    private fun map(t: Text): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (b in t.textBlocks) for (l in b.lines) {
            val lb = l.boundingBox ?: continue
            val ws = ArrayList<OcrWord>()
            for (e in l.elements) {
                val eb = e.boundingBox ?: continue
                if (e.text.isNotBlank()) ws.add(OcrWord(e.text, RectF(eb)))
            }
            if (ws.isNotEmpty()) out.add(OcrLine(l.text, RectF(lb), ws))
        }
        return out
    }

    override fun close() {
        closed = true
        ui.removeCallbacksAndMessages(null)
        try { rec?.close() } catch (_: Throwable) {}
        rec = null
    }

    private companion object {
        const val DOWNLOAD_MS = 3 * 60_000L
        const val NOT_AVAILABLE = "Text recognition is not available (needs Google Play Services and a network connection for the first use)."
    }
}
