package com.lanshare.app.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lanshare.app.MlKitOcr
import com.lanshare.app.OcrLine
import com.lanshare.app.TiledOcr
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Half 2a of the image search (see HANDOVER_IMAGE_SEARCH.md): the real [ImgSearch.Engine].
 *
 *  - CLIP ViT-B/32 (Xenova quantized ONNX, the SAME files the web page's clip_worker.js uses) through ONNX Runtime
 *  - CLIP BPE tokenizer in Kotlin (vocab.json + merges.txt)
 *  - OCR with ML Kit (Play Services Latin model, offline) through TiledOcr (OcrTiled.kt), the same reader as the picture viewer
 *  - the model files (~150 MB) are NOT in the APK: [install] downloads them once into <filesDir>/models/clip/
 */
object ClipEngine {
    private const val BASE = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main/"
    /** name on disk -> path on the server. All are needed. */
    private val FILES = linkedMapOf(
        "vision.onnx" to "onnx/vision_model_quantized.onnx",
        "text.onnx" to "onnx/text_model_quantized.onnx",
        "vocab.json" to "vocab.json",
        "merges.txt" to "merges.txt",
    )
    private const val ENGINE_ID = "clip-vit-b32-q8-v1"   // change when preprocessing / model changes: the old index is then discarded
    private const val OCR_MAX = 1800                       // fallback reader only (plain ML Kit call)
    private const val OCR_HI = 3072                        // viewer-style reader: whole-picture pass at <= 3072 px, then tiles from the original file

    @Volatile private var installing = false
    @Volatile private var msg = ""
    @Volatile private var err: String? = null
    @Volatile private var bytesDone = 0L
    @Volatile private var bytesTotal = 0L

    private fun dir(ctx: Context) = File(ctx.filesDir, "models/clip")
    fun installed(ctx: Context) = FILES.keys.all { File(dir(ctx), it).length() > 0 }

    fun state(): JSONObject {
        val ctx = Core.appCtx
        return JSONObject().put("installed", ctx != null && installed(ctx)).put("installing", installing)
            .put("msg", msg).put("error", err ?: JSONObject.NULL)
            .put("bytes", bytesDone).put("totalBytes", bytesTotal)
    }

    /** App start: when the files are already there, the engine is ready at once (sessions are created lazily on first use). */
    fun tryLoad(ctx: Context) {
        if (ImgSearch.engine == null && installed(ctx)) ImgSearch.engine = Impl(dir(ctx))
        if (ImgSearch.engine != null) ImgSearch.resumeIfInterrupted()   // a scan the system killed goes on where it stopped
    }

    /** Downloads the model files in the background (progress in [state]), then sets [ImgSearch.engine]. Ignored when already running. */
    @Synchronized
    fun install() {
        val ctx = Core.appCtx ?: throw BadReq("app not ready")
        if (installing) return
        if (installed(ctx)) { tryLoad(ctx); return }
        installing = true; err = null; bytesDone = 0; bytesTotal = 0; msg = "Connecting…"
        Thread({
            try {
                val d = dir(ctx); d.mkdirs()
                var n = 0
                for ((local, remote) in FILES) {
                    n++
                    val dst = File(d, local)
                    if (dst.length() > 0) { bytesDone += dst.length(); bytesTotal += dst.length(); continue }   // finished in an earlier run
                    download(BASE + remote, dst, "Downloading model $n/${FILES.size}")
                }
                tryLoad(ctx)
                msg = "Model ready"
            } catch (t: Throwable) { err = "Download interrupted: " + errText(t) + ". What was already downloaded is kept - press Download again to continue where it stopped."; msg = "" }
            finally { installing = false }
        }, "clip-download").also { it.isDaemon = true; it.start() }
    }

    /** Diagnostics for the first device test (POST /api/imgs {op:"selftest"}): tokenizer ids, text vector norm, timings. Needs the model files. */
    fun selfTest(): JSONObject {
        val ctx = Core.appCtx ?: throw BadReq("app not ready")
        if (!installed(ctx)) throw BadReq("model not downloaded")
        val r = JSONObject()
        val t0 = System.currentTimeMillis()
        val tk = ClipTokenizer(File(dir(ctx), "vocab.json"), File(dir(ctx), "merges.txt"))
        r.put("tokenizerLoadMs", System.currentTimeMillis() - t0)
        val ids = tk.encode("a photo of a cat").toList()
        r.put("catIds", org.json.JSONArray(ids)).put("catIdsOk", ids == listOf(49406, 320, 1125, 539, 320, 2368, 49407))
        val eng = ImgSearch.engine ?: throw BadReq("engine not loaded")
        val t1 = System.currentTimeMillis()
        val a = eng.embedText("a photo of a cat"); val b = eng.embedText("a photo of a dog"); val c = eng.embedText("a photograph of a kitten")
        r.put("textMs", System.currentTimeMillis() - t1)
        fun dot(x: FloatArray, y: FloatArray): Double { var d = 0.0; for (i in x.indices) d += x[i] * y[i]; return d }
        r.put("selfDot", dot(a, a)).put("catVsDog", dot(a, b)).put("catVsKitten", dot(a, c))   // expect ~1.0, < catVsKitten, kitten clearly higher
        return r
    }

    /** Deletes the model files and the engine (the index stays; it is useless without the engine for new pictures but searchable as text). */
    fun uninstall() {
        if (installing) throw BadReq("download is running")
        if (ImgSearch.isRunning()) throw BadReq("a picture scan is running - stop it first")
        val ctx = Core.appCtx ?: return
        (ImgSearch.engine as? Impl)?.close()
        ImgSearch.engine = null
        dir(ctx).deleteRecursively()
        msg = ""; err = null
    }

    private class HttpErr(val code: Int, name: String) : IOException("HTTP $code for $name")
    private class DiskFull(m: String) : IOException(m)

    /**
     * One model file, resumable: the partial data stays in <name>.part (+ <name>.part.size = the total it belongs to).
     * A broken connection is retried up to 8 times with a growing pause; the next try asks the server only for the missing rest (HTTP Range).
     * The final file appears only by renaming a COMPLETE, size-checked .part, so [installed] can never see a half file.
     */
    private fun download(url: String, dst: File, label: String) {
        val tmp = File(dst.path + ".part"); val meta = File(dst.path + ".part.size")
        val doneBase = bytesDone; val totalBase = bytesTotal
        var attempt = 0
        while (true) {
            try { downloadOnce(url, dst, tmp, meta, label, doneBase, totalBase); return }
            catch (t: Throwable) {
                val retry = t is IOException && t !is DiskFull && !(t is HttpErr && t.code in 400..499 && t.code != 408 && t.code != 429)
                if (!retry || ++attempt > 8) throw t
                msg = "$label: connection lost, retrying ($attempt/8)…"
                try { Thread.sleep(min(30000L, 1000L shl attempt)) } catch (_: InterruptedException) { throw t }
            }
        }
    }

    private fun downloadOnce(url: String, dst: File, tmp: File, meta: File, label: String, doneBase: Long, totalBase: Long) {
        var have = if (tmp.isFile) tmp.length() else 0L
        val knownTotal = try { meta.readText().trim().toLong() } catch (_: Exception) { -1L }
        if (have > 0 && knownTotal <= 0) { tmp.delete(); have = 0 }   // cannot tell which file version these bytes belong to
        bytesDone = doneBase + have
        var u = url
        var c: HttpURLConnection? = null
        var code = 0
        for (hop in 0 until 6) {   // HttpURLConnection does not follow redirects that change host reliably: do it by hand
            c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20000; readTimeout = 30000
                setRequestProperty("User-Agent", "LANShare-Android"); setRequestProperty("Accept-Encoding", "identity")   // no transparent gzip: Content-Length must match the bytes we read
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location") ?: throw IOException("bad redirect")).toString(); c.disconnect(); continue }
            break
        }
        val conn = c ?: throw IOException("no connection")
        if (code == 416) {   // asked for bytes beyond the end: the partial file is either complete or does not fit
            val tot = conn.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
            conn.disconnect()
            if (tot != null && tot == have) { finishFile(tmp, dst, meta); return }
            tmp.delete(); meta.delete()
            throw IOException("partial file did not match, starting again")
        }
        if (code != 200 && code != 206) { conn.disconnect(); throw HttpErr(code, dst.name) }
        val resumed = code == 206
        var total = -1L
        if (resumed) {
            val cr = conn.getHeaderField("Content-Range") ?: ""   // "bytes 1000-9999/10000"
            val startAt = cr.substringAfter("bytes ", "").substringBefore('-').trim().toLongOrNull()
            total = cr.substringAfter('/', "").trim().toLongOrNull() ?: -1L
            if (startAt != have) { conn.disconnect(); tmp.delete(); meta.delete(); throw IOException("server resumed at the wrong place, starting again") }
        } else {
            total = conn.contentLengthLong
            have = 0; bytesDone = doneBase   // server ignored Range: the whole file comes again
        }
        if (total > 0 && knownTotal > 0 && total != knownTotal) { conn.disconnect(); tmp.delete(); meta.delete(); throw IOException("the file changed on the server, starting again") }
        if (total > 0) { bytesTotal = totalBase + total; try { meta.writeText(total.toString()) } catch (_: Exception) {} }
        val need = if (total > 0) total - have else 0L
        val free = (dst.parentFile ?: dst).usableSpace
        if (need > 0 && free < need + (50L shl 20)) { conn.disconnect(); throw DiskFull("Not enough free storage: ${(need shr 20) + 50} MB needed, ${free shr 20} MB free") }
        var lastUi = 0L
        try {
            conn.inputStream.use { ins ->
                FileOutputStream(tmp, resumed).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = ins.read(buf); if (r < 0) break
                        fos.write(buf, 0, r); bytesDone += r
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 300) { lastUi = now; msg = "$label: ${bytesDone shr 20} MB" + (if (bytesTotal > 0) " / ${bytesTotal shr 20} MB" else "") }
                    }
                    fos.flush(); fos.fd.sync()   // bytes are on disk before we count them as kept
                }
            }
        } finally { conn.disconnect() }
        val got = tmp.length()
        if (total > 0 && got < total) throw IOException("${dst.name}: connection ended early ($got of $total bytes)")   // .part is kept; the retry continues from here
        if (total > 0 && got > total) { tmp.delete(); meta.delete(); throw IOException("${dst.name}: too many bytes, starting again") }
        finishFile(tmp, dst, meta)
    }

    private fun finishFile(tmp: File, dst: File, meta: File) {
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw IOException("cannot save ${dst.name}") }
        meta.delete()
    }

    // ===================================================================== engine
    private class Impl(private val d: File) : ImgSearch.Engine {
        override val id = ENGINE_ID
        override val canOcr = true

        private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
        private var vis: OrtSession? = null
        private var txt: OrtSession? = null
        private var tok: ClipTokenizer? = null
        private var ocrRec: com.google.mlkit.vision.text.TextRecognizer? = null
        private var tiled: TiledOcr? = null      // the same reader the picture viewer uses (whole picture + full-resolution tiles + 2x for small pictures)

        private fun opts() = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) }   // 4 threads on big phones: the scan is the slow part

        @Synchronized private fun visSession() = vis ?: env.createSession(File(d, "vision.onnx").path, opts()).also { vis = it }
        @Synchronized private fun txtSession() = txt ?: env.createSession(File(d, "text.onnx").path, opts()).also { txt = it }
        @Synchronized private fun tokenizer() = tok ?: ClipTokenizer(File(d, "vocab.json"), File(d, "merges.txt")).also { tok = it }

        /** End of a scan: the vision session (the big one) and the OCR model go; the text tower stays for queries. */
        @Synchronized override fun release() {
            try { vis?.close() } catch (_: Exception) {}; try { ocrRec?.close() } catch (_: Exception) {}
            try { tiled?.close() } catch (_: Exception) {}
            vis = null; ocrRec = null; tiled = null
        }

        @Synchronized fun close() {
            try { vis?.close() } catch (_: Exception) {}; try { txt?.close() } catch (_: Exception) {}
            try { ocrRec?.close() } catch (_: Exception) {}
            try { tiled?.close() } catch (_: Exception) {}
            vis = null; txt = null; ocrRec = null; tiled = null
        }

        /** Output called [want], or else the first [1][512] output: the Xenova exports also return last_hidden_state. */
        private fun pick(res: OrtSession.Result, want: String): FloatArray {
            var v: Any? = res.get(want).orElse(null)?.value
            if (v == null) for (e in res) { val x = e.value.value; if (x is Array<*> && x.size == 1 && x[0] is FloatArray) { v = x; break } }
            @Suppress("UNCHECKED_CAST")
            val a = (v as? Array<FloatArray>) ?: throw IOException("model has no $want output")
            return normalise(a[0])
        }

        private fun normalise(v: FloatArray): FloatArray {
            if (v.size != ImgSearch.DIM) throw IOException("model returned ${v.size} floats, expected ${ImgSearch.DIM}")
            var s = 0.0; for (x in v) s += x * x
            val n = sqrt(s).toFloat().takeIf { it > 1e-12f } ?: 1f
            return FloatArray(v.size) { v[it] / n }
        }

        override fun embedImage(jpeg: ByteArray): FloatArray {
            val bm = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw IOException("cannot decode the image")
            val px = preprocess(bm)
            val s = visSession()
            val input = s.inputNames.first()
            val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(px), longArrayOf(1, 3, 224, 224))
            try { s.run(mapOf(input to t)).use { r -> return pick(r, "image_embeds") } } finally { t.close() }
        }

        override fun embedText(query: String): FloatArray {
            val ids = tokenizer().encode(query)   // <sot> ... <eot>, at most 77
            val s = txtSession()
            // The exports have a dynamic length (the web page does not pad a single query). If a build has a fixed 77, pad with <eot>.
            val fixed = (s.inputInfo["input_ids"]?.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(1) ?: -1L
            val n = if (fixed > 0) fixed.toInt() else ids.size
            val inp = LongArray(n) { if (it < ids.size) ids[it].toLong() else ClipTokenizer.EOT.toLong() }
            val mask = LongArray(n) { if (it < ids.size) 1L else 0L }
            val shape = longArrayOf(1, n.toLong())
            val ti = OnnxTensor.createTensor(env, LongBuffer.wrap(inp), shape)
            var tm: OnnxTensor? = null
            try {
                val feeds = HashMap<String, OnnxTensor>()
                feeds["input_ids"] = ti
                if ("attention_mask" in s.inputNames) { tm = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape); feeds["attention_mask"] = tm }
                s.run(feeds).use { r -> return pick(r, "text_embeds") }
            } finally { tm?.close(); ti.close() }
        }

        /** CLIP preprocessing: shortest side -> 224 (smooth), centre crop 224, RGB/255, mean/std, NCHW. */
        private fun preprocess(src: Bitmap): FloatArray {
            val S = 224
            var bm = src
            if (bm.hasAlpha()) {   // flatten on white like the JPEG thumbnail path
                val flat = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bm, 0f, 0f, null) }
                bm = flat
            }
            val short = minOf(bm.width, bm.height)
            val scale = S.toFloat() / short
            val nw = max(S, (bm.width * scale).roundToInt()); val nh = max(S, (bm.height * scale).roundToInt())
            // halve while still >= 2x too big: plain bilinear alone aliases (the browser uses bicubic)
            var cur = bm
            while (cur.width / 2 >= nw && cur.height / 2 >= nh) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            val scaled = Bitmap.createScaledBitmap(cur, nw, nh, true)
            val x0 = (nw - S) / 2; val y0 = (nh - S) / 2
            val px = IntArray(S * S); scaled.getPixels(px, 0, S, x0, y0, S, S)
            val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
            val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
            val out = FloatArray(3 * S * S)
            for (i in 0 until S * S) {
                val p = px[i]
                out[i] = (((p shr 16) and 255) / 255f - mean[0]) / std[0]
                out[S * S + i] = (((p shr 8) and 255) / 255f - mean[1]) / std[1]
                out[2 * S * S + i] = ((p and 255) / 255f - mean[2]) / std[2]
            }
            if (scaled !== bm) scaled.recycle()
            if (cur !== bm && cur !== scaled) cur.recycle()
            return out
        }

        /** The OCR model lives in Google Play Services: make sure it is there (asks Play Services to download it and waits). False = scan without text. */
        override fun prepareOcr(cancelled: () -> Boolean): Boolean {
            return try {
                val rec = recogniser()
                val mi = ModuleInstall.getClient(Core.appCtx ?: return false)
                if (Tasks.await(mi.areModulesAvailable(rec), 30, java.util.concurrent.TimeUnit.SECONDS).areModulesAvailable()) return true
                Tasks.await(mi.installModules(ModuleInstallRequest.newBuilder().addApi(rec).build()), 30, java.util.concurrent.TimeUnit.SECONDS)
                val end = System.currentTimeMillis() + 10 * 60_000L   // up to 10 minutes for the download; interrupted downloads are continued by Play Services itself
                while (System.currentTimeMillis() < end && !cancelled()) {
                    if (Tasks.await(mi.areModulesAvailable(rec), 30, java.util.concurrent.TimeUnit.SECONDS).areModulesAvailable()) return true
                    Thread.sleep(3000)
                }
                false
            } catch (_: Throwable) { false }
        }

        // ---- OCR (ML Kit via Google Play Services, Latin model downloaded on first use; Turkish letters c g i o s u still to be verified on a device) ----
        @Synchronized private fun recogniser() = ocrRec ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { ocrRec = it }

        @Synchronized private fun tiledOcr(): TiledOcr? = tiled ?: (Core.appCtx?.let { c -> TiledOcr(MlKitOcr(c)).also { it.skipTilesIfEmpty = true; tiled = it } })

        @Synchronized private fun dropTiled() { try { tiled?.close() } catch (_: Exception) {}; tiled = null }

        /** Same reading as the picture viewer's text layer (OcrTiled.kt): better on small print, tilted text and small pictures; slower than one plain pass. */
        override fun ocr(file: File): String {
            val t = tiledOcr() ?: return ocrPlain(file)
            val bm = decodeForOcr(file, OCR_HI)
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var res: Result<List<OcrLine>>? = null
                t.recognizeHi(bm, file) { r -> res = r; latch.countDown() }       // callbacks come on the main thread, this is the scan thread
                if (!latch.await(150, java.util.concurrent.TimeUnit.SECONDS)) { dropTiled(); throw IllegalStateException("text reading timed out") }
                val lines = res!!.getOrThrow()
                return lines.joinToString("\n") { it.text }
            } finally { bm.recycle() }
        }

        /** Fallback (no app context): one ML Kit pass over the picture reduced to 1800 px. */
        private fun ocrPlain(file: File): String {
            val bm = decodeForOcr(file, OCR_MAX)
            try {
                val r = Tasks.await(recogniser().process(InputImage.fromBitmap(bm, 0)))
                return r.text
            } finally { bm.recycle() }
        }

        /** Longest side <= [max] px, EXIF-rotated (same idea as Thumbs.render, but returns the bitmap: no JPEG round trip). */
        private fun decodeForOcr(f: File, max: Int): Bitmap {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            if (b.outWidth <= 0 || b.outHeight <= 0) throw IOException("not an image")
            var sample = 1
            while (b.outWidth / (sample * 2) >= max && b.outHeight / (sample * 2) >= max) sample *= 2
            val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("cannot decode the image")
            val m = Matrix()
            try {
                when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                }
            } catch (_: Exception) {}
            val longest = max(src.width, src.height)
            if (longest > max) { val s = max.toFloat() / longest; m.postScale(s, s) }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) src.recycle()
            return out
        }
    }
}

/**
 * OpenAI CLIP byte-level BPE (same as the Python `SimpleTokenizer` / HF CLIPTokenizer): lowercase, whitespace collapsed,
 * regex split, GPT-2 byte->unicode, merges by rank, `</w>` word end. Output: [SOT, ..., EOT], at most 77 ids.
 */
class ClipTokenizer(vocabFile: File, mergesFile: File) {
    companion object {
        const val SOT = 49406
        const val EOT = 49407
        const val MAX = 77
        private val PAT = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+", RegexOption.IGNORE_CASE)
    }
    private val vocab = HashMap<String, Int>()
    private val ranks = HashMap<String, Int>()
    private val b2u = HashMap<Int, Char>()
    private val cache = HashMap<String, List<String>>()

    init {
        val j = JSONObject(vocabFile.readText())
        for (k in j.keys()) vocab[k] = j.getInt(k)
        var rank = 0
        mergesFile.useLines { lines ->
            for (l in lines) { if (l.isEmpty() || l.startsWith("#version")) continue; ranks[l] = rank++ }   // "a b" per line, rank = line order
        }
        // GPT-2 bytes_to_unicode
        val bs = ArrayList<Int>()
        for (c in '!'.code..'~'.code) bs.add(c); for (c in 0xA1..0xAC) bs.add(c); for (c in 0xAE..0xFF) bs.add(c)
        var n = 0
        for (b in 0 until 256) {
            if (b in bs) b2u[b] = b.toChar() else { b2u[b] = (256 + n).toChar(); n++ }
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var w = token.map { it.toString() }.toMutableList()
        w[w.size - 1] = w.last() + "</w>"
        while (w.size > 1) {
            var best = Int.MAX_VALUE; var bi = -1
            for (i in 0 until w.size - 1) { val r = ranks[w[i] + " " + w[i + 1]]; if (r != null && r < best) { best = r; bi = i } }
            if (bi < 0) break
            val first = w[bi]; val second = w[bi + 1]; val merged = first + second
            val nw = ArrayList<String>(w.size - 1)
            var i = 0
            while (i < w.size) {   // ALL occurrences of the best pair, left to right (as the reference tokenizer does)
                if (i < w.size - 1 && w[i] == first && w[i + 1] == second) { nw.add(merged); i += 2 } else { nw.add(w[i]); i++ }
            }
            w = nw
        }
        cache[token] = w
        return w
    }

    fun encode(text: String): IntArray {
        val clean = text.replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Int>()
        ids.add(SOT)
        for (m in PAT.findAll(clean)) {
            val word = m.value.toByteArray(Charsets.UTF_8).joinToString("") { b2u[it.toInt() and 255].toString() }
            for (p in bpe(word)) ids.add(vocab[p] ?: continue)
        }
        while (ids.size > MAX - 1) ids.removeAt(ids.size - 1)
        ids.add(EOT)
        return ids.toIntArray()
    }
}
