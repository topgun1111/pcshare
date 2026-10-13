package com.lanshare.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOCR (PP-OCR) on ONNX Runtime, which the app already ships for the CLIP image search: NO new library, the APK does not grow.
 * The three model files (~10 MB) are downloaded once into <filesDir>/models/ocr by [PaddleModels] (Wi-Fi only), like the CLIP models.
 *  - detection: PP-OCR mobile det (DB), probability map -> connected components -> expanded boxes (axis-aligned)
 *  - recognition: PP-OCRv5 Latin mobile rec (includes Turkish letters), CTC; word boxes are cut from the CTC time steps
 */
object PaddleModels {
    private const val BASE = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/"
    /** name on disk -> path on the server, with the smallest size a good file has (guards against saved error pages). */
    private val FILES = linkedMapOf(
        "det.onnx" to Pair("detection/v3/det.onnx", 1_000_000L),
        "rec.onnx" to Pair("languages/latin/rec.onnx", 3_000_000L),
        "dict.txt" to Pair("languages/latin/dict.txt", 500L),
    )
    @Volatile private var busy = false
    @Volatile var failedAt = 0L; private set

    fun dir(c: Context) = File(c.filesDir, "models/ocr")
    fun installed(c: Context) = FILES.all { (n, v) -> File(dir(c), n).length() >= v.second }

    /** (label, url) of every file, for the daily link check in [ModelLinks]. */
    fun links(): List<Pair<String, String>> = FILES.map { (n, v) -> "PaddleOCR model $n" to BASE + v.first }

    /** Downloads in the background when not installed, not already running, and the network is not metered. Retries at most every 30 min. */
    @Synchronized fun installAsync(c: Context) {
        if (busy || installed(c)) return
        if (System.currentTimeMillis() - failedAt < 30 * 60_000L) return
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null || cm.isActiveNetworkMetered) return
        busy = true
        Thread({
            var cur = ""
            try {
                val d = dir(c).apply { mkdirs() }
                for ((n, v) in FILES) {
                    val dst = File(d, n)
                    if (dst.length() >= v.second) continue
                    val tmp = File(d, "$n.part")
                    cur = BASE + v.first
                    val con = URL(cur).openConnection() as HttpURLConnection
                    con.connectTimeout = 20000; con.readTimeout = 30000
                    con.setRequestProperty("User-Agent", "LANShare-Android")
                    if (con.responseCode != 200) {
                        if (ModelLinks.isDead(con.responseCode)) ModelLinks.report(c, "PaddleOCR model $n", cur, con.responseCode)   // warning screen
                        throw java.io.IOException("HTTP ${con.responseCode} for $n")
                    }
                    con.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                    if (tmp.length() < v.second || !tmp.renameTo(dst)) { tmp.delete(); throw java.io.IOException("bad file $n") }
                }
            } catch (_: Throwable) { failedAt = System.currentTimeMillis() }
            finally { busy = false }
        }, "ocr-model-download").also { it.isDaemon = true; it.start() }
    }

    /** Called when the files turn out to be unusable: they are deleted so the next start downloads them again. */
    fun reset(c: Context) { try { dir(c).deleteRecursively() } catch (_: Throwable) {} }
}

class PaddleOcr(private val ctx: Context) : OcrEngine {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "paddle-ocr").also { it.isDaemon = true } }
    @Volatile private var closed = false
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var chars: List<String> = emptyList()
    private var recH = 48

    private fun load() {
        if (det != null && rec != null) return
        val d = PaddleModels.dir(ctx)
        val e = OrtEnvironment.getEnvironment()
        fun opts() = OrtSession.SessionOptions().apply { setIntraOpNumThreads(min(4, max(2, Runtime.getRuntime().availableProcessors() / 2))) }
        det = e.createSession(File(d, "det.onnx").path, opts())
        rec = e.createSession(File(d, "rec.onnx").path, opts())
        env = e
        chars = File(d, "dict.txt").readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        // rec input height: from the model when fixed, else 48 (PP-OCRv4 / v5 mobile)
        val sh = (rec!!.inputInfo.values.first().info as? ai.onnxruntime.TensorInfo)?.shape
        recH = sh?.getOrNull(2)?.takeIf { it > 0 }?.toInt() ?: 48
    }

    override fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        try {
            work.execute {
                val res: Result<List<OcrLine>> = try {
                    if (closed) throw IllegalStateException("closed")
                    load()
                    Result.success(read(bm))
                } catch (e: Throwable) {
                    Result.failure(Exception(e.message ?: "Cannot read the text"))
                }
                main.post { if (!closed) cb(res) }
            }
        } catch (e: Exception) { main.post { cb(Result.failure(Exception("Cannot read the text"))) } }
    }

    // ------------------------------------------------------------------ detection

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float) { val w get() = r - l; val h get() = b - t }

    private fun detect(bm: Bitmap): List<Box> {
        val e = env!!
        val side = max(bm.width, bm.height)
        val k = if (side > DET_MAX) DET_MAX.toFloat() / side else 1f
        val w = max(32, ((bm.width * k) / 32f).roundToInt() * 32)
        val h = max(32, ((bm.height * k) / 32f).roundToInt() * 32)
        val sm = Bitmap.createScaledBitmap(bm, w, h, true)
        val px = IntArray(w * h); sm.getPixels(px, 0, w, 0, 0, w, h)
        if (sm !== bm) sm.recycle()
        // PaddleOCR feeds BGR with ImageNet mean / std in that channel order
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val inv = floatArrayOf(1f / 0.229f, 1f / 0.224f, 1f / 0.225f)
        val buf = FloatArray(3 * w * h); val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            buf[i] = (((p and 0xFF) / 255f) - mean[0]) * inv[0]                 // B
            buf[plane + i] = ((((p shr 8) and 0xFF) / 255f) - mean[1]) * inv[1]   // G
            buf[2 * plane + i] = ((((p shr 16) and 0xFF) / 255f) - mean[2]) * inv[2] // R
        }
        val s = det!!
        val prob = OnnxTensor.createTensor(e, FloatBuffer.wrap(buf), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { out ->
                val fb = (out[0] as OnnxTensor).floatBuffer
                FloatArray(plane).also { fb.get(it, 0, min(plane, fb.remaining())) }
            }
        }
        // connected components of prob > 0.3 (4-neighbour flood fill), one box per component
        val seen = BooleanArray(plane); val stack = IntArray(plane)
        val sx = bm.width.toFloat() / w; val sy = bm.height.toFloat() / h
        val boxes = ArrayList<Box>()
        for (start in 0 until plane) {
            if (seen[start] || prob[start] <= 0.3f) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var n = 0; var sum = 0f
            while (sp > 0) {
                val q = stack[--sp]; val x = q % w; val y = q / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                n++; sum += prob[q]
                if (x > 0 && !seen[q - 1] && prob[q - 1] > 0.3f) { seen[q - 1] = true; stack[sp++] = q - 1 }
                if (x < w - 1 && !seen[q + 1] && prob[q + 1] > 0.3f) { seen[q + 1] = true; stack[sp++] = q + 1 }
                if (y > 0 && !seen[q - w] && prob[q - w] > 0.3f) { seen[q - w] = true; stack[sp++] = q - w }
                if (y < h - 1 && !seen[q + w] && prob[q + w] > 0.3f) { seen[q + w] = true; stack[sp++] = q + w }
            }
            val bw = (x1 - x0 + 1).toFloat(); val bh = (y1 - y0 + 1).toFloat()
            if (min(bw, bh) < 3f || sum / n < 0.6f) continue
            // DB "unclip": the map is a shrunk text core, grow it by area * ratio / perimeter on every side
            val d = bw * bh * 1.6f / (2f * (bw + bh))
            boxes.add(Box(max(0f, (x0 - d) * sx), max(0f, (y0 - d) * sy), min(bm.width.toFloat(), (x1 + 1 + d) * sx), min(bm.height.toFloat(), (y1 + 1 + d) * sy)))
        }
        // reading order: rows top to bottom, left to right inside a row
        val sorted = boxes.sortedBy { it.t }
        val rows = ArrayList<MutableList<Box>>()
        for (b in sorted) {
            val row = rows.lastOrNull()
            if (row != null && Math.abs((b.t + b.b) / 2 - (row[0].t + row[0].b) / 2) < 0.5f * max(b.h, row[0].h)) row.add(b) else rows.add(mutableListOf(b))
        }
        return rows.flatMap { r -> r.sortedBy { it.l } }
    }

    // ------------------------------------------------------------------ recognition

    private fun read(bm: Bitmap): List<OcrLine> {
        val lines = ArrayList<OcrLine>()
        for (b in detect(bm)) {
            if (closed) break
            val x = b.l.toInt().coerceIn(0, bm.width - 1); val y = b.t.toInt().coerceIn(0, bm.height - 1)
            val cw = ceil(b.r - x).toInt().coerceIn(1, bm.width - x); val ch = ceil(b.b - y).toInt().coerceIn(1, bm.height - y)
            var crop = Bitmap.createBitmap(bm, x, y, cw, ch)
            val vertical = ch >= 1.5f * cw
            if (vertical) { val m = Matrix().apply { postRotate(-90f) }; val r = Bitmap.createBitmap(crop, 0, 0, cw, ch, m, true); crop.recycle(); crop = r }
            val line = try { recogniseCrop(crop, RectF(b.l, b.t, b.r, b.b), vertical) } finally { crop.recycle() }
            if (line != null) lines.add(line)
        }
        return lines
    }

    private fun recogniseCrop(crop: Bitmap, box: RectF, vertical: Boolean): OcrLine? {
        val e = env!!; val s = rec!!
        val tw = ceil(recH.toFloat() * crop.width / crop.height).toInt().coerceIn(8, 3200)
        val sm = Bitmap.createScaledBitmap(crop, tw, recH, true)
        val px = IntArray(tw * recH); sm.getPixels(px, 0, tw, 0, 0, tw, recH)
        if (sm !== crop) sm.recycle()
        val plane = tw * recH; val buf = FloatArray(3 * plane)
        for (i in 0 until plane) {   // BGR, (v/255 - 0.5) / 0.5
            val p = px[i]
            buf[i] = ((p and 0xFF) / 127.5f) - 1f
            buf[plane + i] = (((p shr 8) and 0xFF) / 127.5f) - 1f
            buf[2 * plane + i] = (((p shr 16) and 0xFF) / 127.5f) - 1f
        }
        val res = OnnxTensor.createTensor(e, FloatBuffer.wrap(buf), longArrayOf(1, 3, recH.toLong(), tw.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                val ot = r[0] as OnnxTensor
                val sh = ot.info.shape
                val n = sh[1].toInt() * sh[2].toInt()
                Triple(sh[1].toInt(), sh[2].toInt(), FloatArray(n).also { ot.floatBuffer.get(it, 0, min(n, ot.floatBuffer.remaining())) })
            }
        }
        val tsteps = res.first; val classes = res.second; val out = res.third
        // CTC: index 0 = blank, 1..N = dict lines, N+1 = space when the model has that extra class
        val hasSpace = classes == chars.size + 2
        val txt = ArrayList<String>(); val at = ArrayList<Int>(); var conf = 0f; var last = 0
        for (t in 0 until tsteps) {
            val o = t * classes
            var bi = 0; var bv = out[o]; var sum = out[o]
            for (c in 1 until classes) { val v = out[o + c]; sum += v; if (v > bv) { bv = v; bi = c } }
            var p = bv
            if (Math.abs(sum - 1f) > 0.02f) { var z = 0f; for (c in 0 until classes) z += exp(out[o + c] - bv); p = 1f / z }   // logits instead of probabilities
            if (bi != 0 && bi != last) {
                val ch = if (bi <= chars.size) chars[bi - 1] else if (hasSpace && bi == chars.size + 1) " " else ""
                if (ch.isNotEmpty()) { txt.add(ch); at.add(t); conf += p }
            }
            last = bi
        }
        if (txt.isEmpty() || conf / txt.size < 0.5f) return null
        val full = txt.joinToString("").trim()
        if (full.isBlank()) return null
        if (vertical) {   // rotated crop: no reliable position per word, the whole box is one selectable piece
            return OcrLine(full, box, listOf(OcrWord(full, box)))
        }
        // words: split at the space characters; borders between words = middle between the last char of one and the first of the next
        class W(val s: StringBuilder, var first: Int, var last: Int)
        val ws = ArrayList<W>(); var cur: W? = null
        for (i in txt.indices) {
            if (txt[i] == " ") { cur = null; continue }
            if (cur == null) { cur = W(StringBuilder(), at[i], at[i]); ws.add(cur) }
            cur.s.append(txt[i]); cur.last = at[i]
        }
        if (ws.isEmpty()) return null
        val step = box.width() / tsteps
        val words = ArrayList<OcrWord>()
        for (i in ws.indices) {
            val l = if (i == 0) box.left else box.left + ((ws[i - 1].last + ws[i].first) / 2f + 0.5f) * step
            val r = if (i == ws.size - 1) box.right else box.left + ((ws[i].last + ws[i + 1].first) / 2f + 0.5f) * step
            words.add(OcrWord(ws[i].s.toString(), RectF(l, box.top, max(l + 1f, r), box.bottom)))
        }
        return OcrLine(words.joinToString(" ") { it.text }, box, words)
    }

    override fun close() {
        closed = true
        work.shutdownNow()
        try { det?.close(); rec?.close() } catch (_: Throwable) {}
        det = null; rec = null
    }

    private companion object { const val DET_MAX = 1280 }
}

/**
 * What the viewers use: PaddleOCR once its model files are on the phone, ML Kit until then (and whenever PaddleOCR fails).
 * Starts the model download by itself on the first call (Wi-Fi only). Same contract as any [OcrEngine].
 */
class AutoOcr(private val ctx: Context) : OcrEngine {
    /** Status texts for a toast; passed on to the ML Kit engine (its model download). */
    var onStatus: ((String) -> Unit)?
        get() = ml.onStatus
        set(v) { ml.onStatus = v }
    private val ml = MlKitOcr(ctx)
    private var pd: PaddleOcr? = null
    private var fails = 0

    private var told = false

    override fun recognize(bm: Bitmap, cb: (Result<List<OcrLine>>) -> Unit) {
        val mode = OcrPrefs.engine(ctx)   // Settings -> Text recognition model, read on every call so a change applies at once
        if (mode == OcrPrefs.ENG_MLKIT) { ml.recognize(bm, cb); return }
        if (!PaddleModels.installed(ctx)) {
            PaddleModels.installAsync(ctx)
            if (mode == OcrPrefs.ENG_PADDLE && !told) { told = true; onStatus?.invoke("PaddleOCR model is not downloaded yet (needs Wi-Fi), using ML Kit for now") }
            ml.recognize(bm, cb); return
        }
        if (fails >= 2) { ml.recognize(bm, cb); return }
        val p = pd ?: PaddleOcr(ctx).also { pd = it }
        p.recognize(bm) { r ->
            if (r.isSuccess) { fails = 0; cb(r) }
            else {
                // second failure in a row: the files are probably damaged, drop them (they download again later) and use ML Kit
                if (++fails >= 2) { PaddleModels.reset(ctx); pd?.close(); pd = null }
                ml.recognize(bm, cb)
            }
        }
    }

    override fun close() { try { pd?.close() } catch (_: Throwable) {}; ml.close() }
}
