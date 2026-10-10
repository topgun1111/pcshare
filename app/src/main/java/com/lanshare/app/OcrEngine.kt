package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One recognised word; [box] is in pixels of the bitmap that was recognised (bounding rectangle).
 * [quad] = the 4 corners x0,y0,...,x3,y3 (clockwise, starting at the top-left of the text) when the word is tilted by more than ~2 degrees, else null.
 */
data class OcrWord(val text: String, val box: RectF, val quad: FloatArray? = null)

/** One recognised line (words in reading order); [text] = words joined by a space. */
data class OcrLine(val text: String, val box: RectF, val words: List<OcrWord>)

/** Text recognition with geometry. Callbacks arrive on the main thread. Kept apart from the selection UI so the engine can be swapped. */
interface OcrEngine {
    fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit)
    fun close()
}

/**
 * ML Kit (Google Play Services, unbundled Latin model): checks that the model module is present, asks Play Services to download it
 * if not (polls up to 3 min, [onStatus] gets "Downloading text model..."), then reads the bitmap. One picture at a time.
 */
class MlKitOcr(private val ctx: Context) : OcrEngine {
    /** Short status texts for a toast ("Downloading text model...", "Reading text..."). Called on the main thread. */
    var onStatus: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "ocr").also { it.isDaemon = true } }
    @Volatile private var closed = false
    private var rec: TextRecognizer? = null

    @Synchronized private fun recogniser(): TextRecognizer =
        rec ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { rec = it }

    private fun status(m: String) { main.post { if (!closed) onStatus?.invoke(m) } }

    override fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        try {
            work.execute {
                val res: Result<List<OcrLine>> = try {
                    if (closed) throw IllegalStateException("closed")
                    ensureModel()
                    status("Reading text...")
                    val t = Tasks.await(recogniser().process(InputImage.fromBitmap(bm, 0)), 60, TimeUnit.SECONDS)
                    Result.success(map(t))
                } catch (e: Throwable) {
                    Result.failure(Exception(e.message ?: "Cannot read the text"))
                }
                main.post { if (!closed) cb(res) }
            }
        } catch (e: Exception) { main.post { cb(Result.failure(Exception("Cannot read the text"))) } }
    }

    private fun ensureModel() {
        val rec = recogniser()
        val mi = ModuleInstall.getClient(ctx)
        fun ok() = Tasks.await(mi.areModulesAvailable(rec), 30, TimeUnit.SECONDS).areModulesAvailable()
        if (ok()) return
        status("Downloading text model...")
        Tasks.await(mi.installModules(ModuleInstallRequest.newBuilder().addApi(rec).build()), 30, TimeUnit.SECONDS)
        val end = System.currentTimeMillis() + 3 * 60_000L
        while (System.currentTimeMillis() < end && !closed) {
            if (ok()) return
            Thread.sleep(2000)
        }
        throw IllegalStateException("Text model is not available (Google Play Services needed)")
    }

    /** Corner polygon of a word, or null when it is (nearly) upright so the plain box is enough. */
    private fun quadOf(p: Array<Point>?, r: Rect): FloatArray? {
        if (p == null || p.size != 4) return null
        val tilt = maxOf(Math.abs(p[1].y - p[0].y), Math.abs(p[0].x - p[3].x))
        if (tilt <= 0.03f * maxOf(r.width(), r.height())) return null
        return floatArrayOf(p[0].x.toFloat(), p[0].y.toFloat(), p[1].x.toFloat(), p[1].y.toFloat(), p[2].x.toFloat(), p[2].y.toFloat(), p[3].x.toFloat(), p[3].y.toFloat())
    }

    private fun map(t: com.google.mlkit.vision.text.Text): List<OcrLine> {
        val out = ArrayList<OcrLine>()
        for (b in t.textBlocks) for (l in b.lines) {
            val ws = ArrayList<OcrWord>()
            for (e in l.elements) {
                val r = e.boundingBox ?: continue
                if (e.text.isBlank()) continue
                ws.add(OcrWord(e.text, RectF(r), quadOf(e.cornerPoints, r)))
            }
            if (ws.isEmpty()) continue
            val box = l.boundingBox?.let { RectF(it) } ?: RectF(ws[0].box).also { u -> for (w in ws) u.union(w.box) }
            out.add(OcrLine(ws.joinToString(" ") { it.text }, box, ws))
        }
        return out
    }

    override fun close() {
        closed = true
        work.shutdownNow()
        try { rec?.close() } catch (_: Exception) {}
        rec = null
    }
}
