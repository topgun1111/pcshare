package com.lanshare.app.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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
        /** Before a scan with text: make sure the OCR model is available (may download it). false = scan without text this time. */
        fun prepareOcr(cancelled: () -> Boolean): Boolean = true
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
    private var resumeOcr: Boolean? = null    // a scan was running when the process died (true = with text); null = none
    private var resumeTries = 0               // restarts of that scan without progress: stops crash loops
    @Volatile private var scanOcrActive: Boolean? = null
    private var curVec = ""                   // vector file of the current index.json
    private var prevVec = ""                  // vector file of index.json.bak (the fallback)
    @Volatile private var saveErr: String? = null

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
    // Every save writes a NEW vector file (vec-<time>.bin), then a new index.json that names it; the previous index.json is kept as
    // index.json.bak together with its vector file. A kill at any moment therefore leaves at least one complete, matching pair.
    private class Loaded(val items: LinkedHashMap<String, E>, val engineId: String, val vecName: String, val resumeOcr: Boolean?, val tries: Int)

    private fun readIndex(mf: File, strict: Boolean): Loaded? {
        try {
            if (!mf.isFile) return null
            val j = JSONObject(mf.readText())
            if (j.optInt("dim") != DIM) return null
            val vecName = j.optString("vec", "vec.bin")
            val bf = File(mf.parentFile, vecName)
            val bytes = if (bf.isFile) bf.readBytes() else ByteArray(0)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val a = j.getJSONArray("items")
            val map = LinkedHashMap<String, E>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = E(o.getString("p"), o.optLong("m"), o.optLong("s"))
                e.failed = o.optBoolean("f", false)
                if (o.has("t")) e.text = o.getString("t")
                if (o.optBoolean("v", false)) {
                    if ((i + 1) * DIM <= fb.capacity()) { val v = FloatArray(DIM); fb.position(i * DIM); fb.get(v); e.vec = v }
                    else if (strict) return null   // vector file shorter than the manifest says: torn pair, use the backup
                }
                map[e.path] = e
            }
            val rs = j.optInt("scanOcr", -1)
            return Loaded(map, j.optString("engine"), vecName, if (rs < 0) null else rs == 1, j.optInt("tries", 0))
        } catch (_: Throwable) { return null }
    }

    private fun ensureLoaded() { synchronized(lock) {
        if (loaded) return
        loaded = true
        val d = dir()
        val r = readIndex(File(d, "index.json"), true) ?: readIndex(File(d, "index.json.bak"), true) ?: readIndex(File(d, "index.json"), false) ?: return
        items.putAll(r.items); storedEngineId = r.engineId; curVec = r.vecName; resumeOcr = r.resumeOcr; resumeTries = r.tries
    } }

    private fun save(): Boolean = synchronized(lock) {
        try {
            val d = dir(); d.mkdirs()
            val list = items.values.toList()
            val a = JSONArray()
            val vecName = "vec-" + System.currentTimeMillis() + ".bin"
            val bt = File(d, "$vecName.tmp")
            FileOutputStream(bt).use { fos ->
                val out = BufferedOutputStream(fos, 1 shl 16)
                val row = ByteBuffer.allocate(DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (e in list) {
                    val o = JSONObject().put("p", e.path).put("m", e.m).put("s", e.s)
                    if (e.failed) o.put("f", true)
                    e.text?.let { o.put("t", it) }
                    row.clear()
                    val v = e.vec
                    if (v != null) { o.put("v", true); for (x in v) row.putFloat(x) } else for (k in 0 until DIM) row.putFloat(0f)   // keep rows aligned
                    out.write(row.array(), 0, DIM * 4)
                    a.put(o)
                }
                out.flush(); fos.fd.sync()
            }
            if (!bt.renameTo(File(d, vecName))) throw IOException("cannot save vector file")
            val j = JSONObject().put("dim", DIM).put("engine", engine?.id ?: storedEngineId).put("vec", vecName).put("items", a)
            scanOcrActive?.let { j.put("scanOcr", if (it) 1 else 0).put("tries", resumeTries) }   // a scan is running: remember it, so a restart can go on
            val mt = File(d, "index.json.tmp")
            FileOutputStream(mt).use { fos -> fos.write(j.toString().toByteArray(Charsets.UTF_8)); fos.flush(); fos.fd.sync() }
            val mf = File(d, "index.json")
            if (mf.isFile) { try { mf.copyTo(File(d, "index.json.bak"), true) } catch (_: Exception) {} }
            if (!mt.renameTo(mf)) { mf.delete(); if (!mt.renameTo(mf)) throw IOException("cannot save index") }
            prevVec = curVec; curVec = vecName
            d.listFiles()?.forEach { f -> val n = f.name
                if (((n.startsWith("vec") && n.endsWith(".bin")) || n.endsWith(".tmp")) && n != curVec && n != prevVec) f.delete() }
            saveErr = null
            true
        } catch (t: Throwable) {   // disk full etc.: the old files stay valid, the scan goes on and the next checkpoint tries again
            saveErr = errText(t); Log.w("ImgSearch", "save failed: " + errText(t)); false
        }
    }

    /** Wipes the index ("Yeniden Tara" in the web page). */
    fun clear() {
        if (running) throw BadReq("indexing is running")
        synchronized(lock) { items.clear(); loaded = true; storedEngineId = ""; resumeOcr = null; resumeTries = 0; curVec = ""; prevVec = "" }
        dir().listFiles()?.forEach { f -> val n = f.name; if (n.startsWith("index.json") || n.startsWith("vec") || n == "cur.txt") f.delete() }
    }

    /** The picture being analysed right now: if the process dies inside the native code, the next start knows the suspect. */
    private fun markCur(path: String?) {
        try { val f = File(dir(), "cur.txt"); if (path == null) f.delete() else { f.parentFile?.mkdirs(); f.writeText(path) } } catch (_: Exception) {}
    }

    /** App start: a scan that was running when the app was killed continues (what is already done stays done). Gives up after 3 restarts without progress. */
    fun resumeIfInterrupted() {
        try {
            ensureLoaded()
            val ocr = synchronized(lock) { resumeOcr } ?: return
            if (engine == null || running) return
            if (resumeTries >= 3) { synchronized(lock) { resumeOcr = null; resumeTries = 0 }; save(); return }
            val cf = File(dir(), "cur.txt")
            if (cf.isFile) {
                val p = cf.readText()
                synchronized(lock) { items[p]?.let { if (it.vec == null) it.failed = true } }   // not retried until it changes or "force"
                cf.delete()
            }
            start(ocr, false, true)
        } catch (_: Throwable) {}
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
            .put("msg", msg).put("error", err ?: JSONObject.NULL).put("saveError", saveErr ?: JSONObject.NULL).put("msPerPic", msPerPic)
            .put("model", ClipEngine.state())   // {installed, installing, msg, error, bytes, totalBytes}: the one-time model download
    }

    /** Starts (or ignores, when one is running) an incremental scan. [ocr] also reads the text inside the pictures. [force] retries unreadable ones. */
    @Synchronized
    fun start(ocr: Boolean, force: Boolean, resumed: Boolean = false) {
        if (running) return
        val eng = engine ?: throw BadReq("image search engine is not installed yet")
        ensureLoaded()
        err = null; cancel = false; running = true; done = 0; total = 0; msg = "Scanning folders…"
        resumeTries = if (resumed) resumeTries + 1 else 0
        scanOcrActive = ocr && eng.canOcr
        worker = Thread({
            try { scan(eng, ocr && eng.canOcr, force) } catch (t: Throwable) { err = errText(t) }
            finally {
                running = false
                scanOcrActive = null; synchronized(lock) { resumeOcr = null; resumeTries = 0 }   // finished, cancelled or failed with a message: nothing to resume
                markCur(null); save(); try { eng.release() } catch (_: Throwable) {}
            }
        }, "imgsearch").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY; it.start() }
        save()   // the "scan running" flag is on disk from the first second
    }

    fun cancel() { cancel = true }

    fun isRunning() = running

    private fun scan(eng: Engine, ocr: Boolean, force: Boolean) {
        val found = discover()
        if (cancel) { msg = "Cancelled"; return }
        // Never wipe the index because the storage looked empty (permission lost, SD card unmounted): keep everything and say so.
        if (found.isEmpty() && synchronized(lock) { items.isNotEmpty() })
            throw IOException("No pictures were found (is storage access allowed / the card inserted?). The saved index was left untouched.")
        var useOcr = ocr
        var notice: String? = null
        if (useOcr) {
            msg = "Preparing text recognition…"
            useOcr = try { eng.prepareOcr { cancel } } catch (_: Throwable) { false }
            if (cancel) { msg = "Cancelled"; return }
            if (!useOcr) notice = "Text recognition model is not available yet (Google Play Services). Scanned without text: run the scan again later to add it."
        }
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
                if (!e.failed && (e.vec == null || (useOcr && e.text == null))) todo.add(e to f)
            }
            val gone = items.keys.count { it !in keep }
            if (gone > 20 && gone * 2 > items.size) notice = "Many pictures were not found (card removed?). They stay in the index; use Rescan to clean up."
            else items.keys.retainAll(keep)   // deleted / moved pictures drop out of the index
        }
        total = todo.size; done = 0
        msg = if (todo.isEmpty()) "Up to date" else "Analysing ${todo.size} pictures…"
        var sinceSave = 0
        var engineFails = 0          // consecutive failures that are NOT "this picture is bad" (model / runtime broken): abort instead of marking everything failed
        val recent = ArrayList<E>()  // pictures marked failed by those failures: restored when we abort
        var ocrFails = 0
        var lastSave = System.currentTimeMillis()
        val t0 = lastSave
        for ((e, f) in todo) {
            if (cancel) { msg = "Cancelled"; break }
            val file = Core.local.real(f.path)
            markCur(f.path)
            try {
                if (e.vec == null) {
                    val v = eng.embedImage(Thumbs.make(file))
                    if (v.size != DIM) throw IllegalStateException("engine returned ${v.size} floats, expected $DIM")
                    synchronized(lock) { e.vec = v }
                }
                if (useOcr && e.text == null) {
                    val t = try { eng.ocr(file).replace(Regex("\\s+"), " ").trim() } catch (x: Exception) { null }   // OCR failing never blocks visual search
                    if (t != null) { synchronized(lock) { e.text = t }; ocrFails = 0 }
                    else if (++ocrFails >= 5) { useOcr = false; notice = "Text recognition keeps failing (Google Play Services?). Continued without text; run the scan again later." }
                }
                engineFails = 0; recent.clear()
            } catch (x: IOException) { synchronized(lock) { e.failed = true } }   // "not an image" / cannot decode: do not retry every scan
            catch (x: OutOfMemoryError) { synchronized(lock) { e.failed = true } }
            catch (x: Exception) {   // ONNX Runtime (OrtException) and anything else
                synchronized(lock) { e.failed = true }; recent.add(e)
                Log.w("ImgSearch", "embedding failed for ${f.path}: ${errText(x)}")
                if (++engineFails >= 5) {
                    synchronized(lock) { for (r in recent) r.failed = false }   // the pictures were fine, the engine was not
                    throw IOException("The search model keeps failing: " + errText(x) + ". Remove the model in Settings and download it again.")
                }
            }
            done++
            val now = System.currentTimeMillis()
            if (++sinceSave >= CHECKPOINT || now - lastSave > 60_000L) {   // every 100 pictures, or once a minute when pictures are slow
                if (save()) { sinceSave = 0; lastSave = now; resumeTries = 0 }
                Log.i("ImgSearch", "$done/${todo.size} pictures, ${(now - t0) / done} ms per picture (ocr=$useOcr)")
            }
        }
        markCur(null)
        if (done > 0) msPerPic = (System.currentTimeMillis() - t0) / done
        if (!cancel) msg = notice ?: "Ready"
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
