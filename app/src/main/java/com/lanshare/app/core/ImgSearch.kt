package com.lanshare.app.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * On-device image search for this phone's own pictures (port of the web page arama.php + clip_worker.js).
 *
 *  - visual search: CLIP image vectors (512 floats, L2-normalised) vs. the CLIP text vector of the query, cosine, threshold 0.20, top 60
 *  - text search:   words found INSIDE the pictures by OCR ("Tam kelime" = whole word, "Heceyi iceren" = substring), Turkish case folding
 *
 * This file is the model-independent half: file discovery, incremental index (path + mtime + size), persistence, both searches,
 * progress / cancel. The neural parts plug in through [Engine] (see HANDOVER_IMAGE_SEARCH.md, half 2): until [engine] is set,
 * indexing is refused and only text already stored can be searched.
 *
 * Index files (<filesDir>/imgsearch/): index.json (manifest) + vec.bin (float32 little-endian, rows in manifest order, 512 per entry).
 * Paths are virtual paths below the storage root ("/DCIM/Camera/x.jpg"), the same ones /api/thumb and /api/dl take with dev=local.
 */
object ImgSearch {
    const val DIM = 512
    const val THRESHOLD = 0.20f
    const val TOP_N = 60
    private val EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")   // no gif: animated, useless for CLIP / OCR
    private const val CHECKPOINT = 100                                              // images between saves of the index

    /** The neural part (half 2): CLIP via ONNX Runtime + a text recogniser. All calls come from ONE background thread, except embedText (request thread). */
    interface Engine {
        /** Identifies model + preprocessing. A changed id throws every stored vector away (they would not be comparable). */
        val id: String
        val canOcr: Boolean
        /** [jpeg] = the 600 px thumbnail from [Thumbs.make] (same input the web version feeds CLIP). Return an L2-normalised vector of [DIM] floats. */
        fun embedImage(jpeg: ByteArray): FloatArray
        /** Query -> L2-normalised vector of [DIM] floats (CLIP text tower, BPE tokenizer, 77 tokens). */
        fun embedText(query: String): FloatArray
        /** Text found in the picture (any whitespace; trimmed by the caller). "" = looked, nothing there. */
        fun ocr(file: File): String
        /** Called when a scan ends: free the heavy per-scan resources (vision session, OCR). Searching must keep working (the text tower may stay). */
        fun release() {}
    }

    @Volatile var engine: Engine? = null

    private class E(val path: String, var m: Long, var s: Long) {
        var vec: FloatArray? = null
        var text: String? = null          // null = OCR not done yet, "" = done, no text
        var failed = false                // unreadable picture: not retried until it changes (or "force")
    }

    private val lock = Any()
    private val items = LinkedHashMap<String, E>()
    private var loaded = false
    private var storedEngineId = ""

    // ---- progress of the running scan ----
    @Volatile private var running = false
    @Volatile private var cancel = false
    @Volatile private var total = 0
    @Volatile private var done = 0
    @Volatile private var msg = ""
    @Volatile private var msPerPic = 0L   // average of the last scan (diagnostics)
    @Volatile private var err: String? = null
    private var worker: Thread? = null

    private fun dir() = File(Cfg.dir ?: File("/data/local/tmp"), "imgsearch")

    // ---------------------------------------------------------------- persistence
    private fun ensureLoaded() { synchronized(lock) {
        if (loaded) return
        loaded = true
        try {
            val mf = File(dir(), "index.json"); val bf = File(dir(), "vec.bin")
            if (!mf.isFile) return
            val j = JSONObject(mf.readText())
            if (j.optInt("dim") != DIM) return
            storedEngineId = j.optString("engine")
            val a = j.getJSONArray("items")
            val bytes = if (bf.isFile) bf.readBytes() else ByteArray(0)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = E(o.getString("p"), o.optLong("m"), o.optLong("s"))
                e.failed = o.optBoolean("f", false)
                if (o.has("t")) e.text = o.getString("t")
                if (o.optBoolean("v", false) && (i + 1) * DIM <= fb.capacity()) {
                    val v = FloatArray(DIM); fb.position(i * DIM); fb.get(v); e.vec = v
                }
                items[e.path] = e
            }
        } catch (_: Exception) { items.clear() }   // torn / old file: simply rebuild
    } }

    private fun save() = synchronized(lock) {
        try {
            val d = dir(); d.mkdirs()
            val list = items.values.toList()
            val a = JSONArray()
            val bb = ByteBuffer.allocate(list.size * DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (e in list) {
                val o = JSONObject().put("p", e.path).put("m", e.m).put("s", e.s)
                if (e.failed) o.put("f", true)
                e.text?.let { o.put("t", it) }
                val v = e.vec
                if (v != null) { o.put("v", true); for (x in v) bb.putFloat(x) } else for (k in 0 until DIM) bb.putFloat(0f)   // keep rows aligned
                a.put(o)
            }
            val j = JSONObject().put("dim", DIM).put("engine", engine?.id ?: storedEngineId).put("items", a)
            val mt = File(d, "index.json.tmp"); val bt = File(d, "vec.bin.tmp")
            bt.writeBytes(bb.array()); mt.writeText(j.toString())
            val bf = File(d, "vec.bin"); val mf = File(d, "index.json")
            if (!bt.renameTo(bf)) { bf.delete(); bt.renameTo(bf) }
            if (!mt.renameTo(mf)) { mf.delete(); mt.renameTo(mf) }   // manifest last: it is what makes vec.bin valid
        } catch (_: Exception) {}
    }

    /** Wipes the index ("Yeniden Tara" in the web page). */
    fun clear() {
        if (running) throw BadReq("indexing is running")
        synchronized(lock) { items.clear(); loaded = true; storedEngineId = "" }
        File(dir(), "index.json").delete(); File(dir(), "vec.bin").delete()
    }

    // ---------------------------------------------------------------- discovery
    private class Found(val path: String, val m: Long, val s: Long)

    private fun skipDir(rel: String) = rel.startsWith("/Android/") || rel.split('/').any { it.length > 1 && it.startsWith(".") }

    private fun discover(): List<Found> {
        val root = Core.local.root
        val rp = root.path.trimEnd('/')
        val out = ArrayList<Found>()
        val budget = intArrayOf(200000)   // directories
        fun walk(d: File, rel: String) {
            if (budget[0]-- <= 0 || cancel) return
            val fs = d.listFiles() ?: return
            for (f in fs) {
                if (f.name.startsWith(".")) continue
                val r = "$rel/${f.name}"
                if (f.isDirectory) { if (!skipDir("$r/")) walk(f, r) }
                else if (f.extension.lowercase(Locale.ROOT) in EXT) out.add(Found(r, f.lastModified(), f.length()))
            }
        }
        walk(File(rp), "")
        return out
    }

    // ---------------------------------------------------------------- indexing
    fun status(): JSONObject {
        ensureLoaded()
        val e = engine
        var withVec = 0; var withText = 0; var failed = 0
        synchronized(lock) { for (x in items.values) { if (x.vec != null) withVec++; if (!x.text.isNullOrEmpty()) withText++; if (x.failed) failed++ } }
        return JSONObject()
            .put("state", if (running) "running" else if (err != null) "error" else "idle")
            .put("engine", e?.id ?: JSONObject.NULL).put("ocr", e?.canOcr ?: false)
            .put("total", total).put("done", done)
            .put("indexed", withVec).put("withText", withText).put("failed", failed).put("count", items.size)
            .put("msg", msg).put("error", err ?: JSONObject.NULL).put("msPerPic", msPerPic)
            .put("model", ClipEngine.state())   // {installed, installing, msg, error, bytes, totalBytes}: the one-time model download
    }

    /** Starts (or ignores, when one is running) an incremental scan. [ocr] also reads the text inside the pictures. [force] retries unreadable ones. */
    @Synchronized
    fun start(ocr: Boolean, force: Boolean) {
        if (running) return
        val eng = engine ?: throw BadReq("image search engine is not installed yet")
        ensureLoaded()
        err = null; cancel = false; running = true; done = 0; total = 0; msg = "Scanning folders…"
        worker = Thread({ try { scan(eng, ocr && eng.canOcr, force) } catch (t: Throwable) { err = errText(t) } finally { running = false; save(); try { eng.release() } catch (_: Throwable) {} } }, "imgsearch")
            .also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY; it.start() }
    }

    fun cancel() { cancel = true }

    fun isRunning() = running

    private fun scan(eng: Engine, ocr: Boolean, force: Boolean) {
        val found = discover()
        if (cancel) { msg = "Cancelled"; return }
        val todo = ArrayList<Pair<E, Found>>()
        synchronized(lock) {
            if (storedEngineId.isNotEmpty() && storedEngineId != eng.id) { items.clear() }   // vectors of another model are useless
            storedEngineId = eng.id
            val keep = HashSet<String>(found.size * 2)
            for (f in found) {
                keep.add(f.path)
                val e = items.getOrPut(f.path) { E(f.path, -1, -1) }
                val changed = e.m != f.m || e.s != f.s
                if (changed) { e.m = f.m; e.s = f.s; e.vec = null; e.text = null; e.failed = false }
                if (force) e.failed = false
                if (!e.failed && (e.vec == null || (ocr && e.text == null))) todo.add(e to f)
            }
            items.keys.retainAll(keep)   // deleted / moved pictures drop out of the index
        }
        total = todo.size; done = 0
        msg = if (todo.isEmpty()) "Up to date" else "Analysing ${todo.size} pictures…"
        var sinceSave = 0
        var engineFails = 0          // consecutive failures that are NOT "this picture is bad" (model / runtime broken): abort instead of marking everything failed
        val t0 = System.currentTimeMillis()
        for ((e, f) in todo) {
            if (cancel) { msg = "Cancelled"; break }
            val file = Core.local.real(f.path)
            try {
                if (e.vec == null) {
                    val v = eng.embedImage(Thumbs.make(file))
                    if (v.size != DIM) throw IllegalStateException("engine returned ${v.size} floats, expected $DIM")
                    synchronized(lock) { e.vec = v }
                }
                if (ocr && e.text == null) {
                    val t = try { eng.ocr(file).replace(Regex("\\s+"), " ").trim() } catch (x: Exception) { null }   // OCR failing never blocks visual search
                    if (t != null) synchronized(lock) { e.text = t }
                }
                engineFails = 0
            } catch (x: IOException) { synchronized(lock) { e.failed = true } }   // "not an image" / cannot decode: do not retry every scan
            catch (x: OutOfMemoryError) { synchronized(lock) { e.failed = true } }
            catch (x: Exception) {   // ONNX Runtime (OrtException) and anything else
                synchronized(lock) { e.failed = true }
                Log.w("ImgSearch", "embedding failed for ${f.path}: ${errText(x)}")
                if (++engineFails >= 5) throw IOException("The search model keeps failing: " + errText(x) + ". Remove the model in Settings and download it again.")
            }
            done++
            if (++sinceSave >= CHECKPOINT) {
                sinceSave = 0; save()
                Log.i("ImgSearch", "$done/${todo.size} pictures, ${(System.currentTimeMillis() - t0) / done} ms per picture (ocr=$ocr)")
            }
        }
        if (done > 0) msPerPic = (System.currentTimeMillis() - t0) / done
        if (!cancel) msg = "Ready"
    }

    // ---------------------------------------------------------------- search
    private fun hit(path: String, score: Float) = JSONObject().put("path", path).put("score", score.toDouble())

    /** CLIP visual search. Needs [engine] (text tower) and at least one stored vector. */
    fun visual(query: String, top: Int = TOP_N, threshold: Float = THRESHOLD): JSONObject {
        val eng = engine ?: throw BadReq("image search engine is not installed yet")
        ensureLoaded()
        val q = eng.embedText(query)
        val snap = synchronized(lock) { items.values.filter { it.vec != null } }
        val res = ArrayList<Pair<String, Float>>()
        for (e in snap) {
            val v = e.vec ?: continue
            var d = 0f; for (i in 0 until DIM) d += q[i] * v[i]   // both L2-normalised
            if (d >= threshold) res.add(e.path to d)
        }
        res.sortByDescending { it.second }
        return JSONObject().put("q", query).put("total", snap.size)
            .put("results", jarr(res.take(top).map { hit(it.first, it.second) }))
    }

    private fun fold(s: String) = s.lowercase(Locale("tr", "TR")).replace('ı', 'i')

    /** OCR text search. [whole] = whole word ("Tam kelime"), else substring ("Heceyi iceren"). Works without an engine (uses stored text). */
    fun text(query: String, whole: Boolean): JSONObject {
        ensureLoaded()
        val q = fold(query.trim())
        if (q.isEmpty()) throw BadReq("empty query")
        val esc = q.split(Regex("\\s+")).joinToString("\\s+") { w -> Regex.escape(w) }   // any run of blanks matches any run of blanks
        val re = Regex(if (whole) "(?<![\\p{L}\\p{N}])$esc(?![\\p{L}\\p{N}])" else esc)
        val snap = synchronized(lock) { items.values.filter { !it.text.isNullOrEmpty() } }
        val res = snap.filter { re.containsMatchIn(fold(it.text!!)) }
        return JSONObject().put("q", query).put("total", snap.size)
            .put("results", jarr(res.map { hit(it.path, 1f) }))
    }
}
