package com.lanshare.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
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

    // ---- folder filters (Settings): "included" limits the scan to those folders (empty = everything), "excluded" is skipped; both also filter the index and every search result ----
    @Volatile private var exclCache: List<String>? = null
    @Volatile private var inclCache: List<String>? = null
    private fun excl(): List<String> = exclCache ?: Cfg.imgExcluded().map { it.lowercase(Locale.ROOT) }.also { exclCache = it }
    private fun incl(): List<String> = inclCache ?: Cfg.imgIncluded().map { it.lowercase(Locale.ROOT) }.also { inclCache = it }

    private fun under(p: String, l: List<String>) = l.any { p == it || p.startsWith("$it/") }

    /** [path] is one of the excluded folders or lies below one (case-insensitive, whole path segments only). */
    private fun isExcluded(path: String): Boolean { val l = excl(); return l.isNotEmpty() && under(path.lowercase(Locale.ROOT), l) }

    /** Out of scope: excluded, or an include list exists and the picture is not inside it. */
    private fun isOut(path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        if (under(p, excl())) return true
        val i = incl(); return i.isNotEmpty() && !under(p, i)
    }

    fun excluded(): List<String> = Cfg.imgExcluded()
    fun included(): List<String> = Cfg.imgIncluded()

    private fun cleanList(list: List<String>) = list.map { vnorm(it).trimEnd('/') }.filter { it.isNotEmpty() && it != "/" }.distinct().take(200)

    /** Pictures that fall out of scope leave the index at once (the saved index is rewritten); widening the scope needs a new scan to bring pictures back. */
    private fun prune() {
        if (running) return
        ensureLoaded()
        val n = synchronized(lock) { val b = items.size; items.keys.removeAll { isOut(it) }; b - items.size }
        if (n > 0) save()
    }

    fun setExcluded(list: List<String>) { Cfg.setImgExcluded(cleanList(list)); exclCache = null; prune() }
    fun setIncluded(list: List<String>) { Cfg.setImgIncluded(cleanList(list)); inclCache = null; prune() }

    private fun discover(only: String = "", honorCancel: Boolean = true): List<Found> {
        val root = Core.local.root
        val rp = root.path.trimEnd('/')
        val out = ArrayList<Found>()
        val budget = intArrayOf(200000)   // directories
        fun walk(d: File, rel: String) {
            if (budget[0]-- <= 0 || (honorCancel && cancel)) return
            val fs = d.listFiles() ?: return
            for (f in fs) {
                if (f.name.startsWith(".")) continue
                val r = "$rel/${f.name}"
                if (f.isDirectory) { if (!skipDir("$r/") && !isExcluded(r)) walk(f, r) }
                else if (f.extension.lowercase(Locale.ROOT) in EXT) out.add(Found(r, f.lastModified(), f.length()))
            }
        }
        val one = vnorm(only).trimEnd('/')
        val inc = if (one.isNotEmpty() && one != "/") listOf(one) else Cfg.imgIncluded().map { vnorm(it).trimEnd('/') }.filter { it.isNotEmpty() && it != "/" }.distinct()
        if (inc.isEmpty()) walk(File(rp), "")
        else for (r in inc.filter { a -> inc.none { it != a && a.startsWith("$it/") } }) {   // only the chosen folders (a folder inside another chosen one is covered by it)
            if (isExcluded(r)) continue
            val d = File(rp + r); if (d.isDirectory) walk(d, r)
        }
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
            .put("excluded", JSONArray(Cfg.imgExcluded())).put("included", JSONArray(Cfg.imgIncluded())).put("msg", msg).put("error", err ?: JSONObject.NULL).put("saveError", saveErr ?: JSONObject.NULL).put("msPerPic", msPerPic).put("auto", Cfg.imgAuto()).put("autoCharging", Cfg.imgAutoCharging())
            .put("model", ClipEngine.state())   // {installed, installing, msg, error, bytes, totalBytes}: the one-time model download
    }

    /** Starts (or ignores, when one is running) an incremental scan. [ocr] also reads the text inside the pictures. [force] retries unreadable ones. */
    @Synchronized
    fun start(ocr: Boolean, force: Boolean, resumed: Boolean = false, dir: String = "") {
        if (running) return
        val eng = engine ?: throw BadReq("image search engine is not installed yet")
        ensureLoaded()
        err = null; cancel = false; running = true; done = 0; total = 0; msg = "Scanning folders…"
        resumeTries = if (resumed) resumeTries + 1 else 0
        scanOcrActive = ocr && eng.canOcr
        worker = Thread({
            try { scan(eng, ocr && eng.canOcr, force, dir) } catch (t: Throwable) { err = errText(t) }
            finally {
                running = false
                scanOcrActive = null; synchronized(lock) { resumeOcr = null; resumeTries = 0 }   // finished, cancelled or failed with a message: nothing to resume
                markCur(null); save(); try { eng.release() } catch (_: Throwable) {}
            }
        }, "imgsearch").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY; it.start() }
        save()   // the "scan running" flag is on disk from the first second
    }

    fun cancel() { cancel = true }

    fun isRunning() = running

    /** For the foreground notification: (running, done, total). total is 0 while folders are still being listed. */
    fun progress(): Triple<Boolean, Int, Int> = Triple(running, done, total)

    private fun scan(eng: Engine, ocr: Boolean, force: Boolean, only: String = "") {
        val found = discover(only)
        if (cancel) { msg = "Cancelled"; return }
        // Never wipe the index because the storage looked empty (permission lost, SD card unmounted): keep everything and say so.
        if (found.isEmpty() && only.isEmpty() && synchronized(lock) { items.isNotEmpty() })
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
            items.keys.removeAll { isOut(it) }   // left the scope after they were indexed: gone for good, not "card removed"
            val keep = HashSet<String>(found.size * 2)
            for (f in found) {
                keep.add(f.path)
                val e = items.getOrPut(f.path) { E(f.path, -1, -1) }
                val changed = e.m != f.m || e.s != f.s
                if (changed) { e.m = f.m; e.s = f.s; e.vec = null; e.text = null; e.failed = false }
                if (force) e.failed = false
                if (!e.failed && (e.vec == null || (useOcr && e.text == null))) todo.add(e to f)
            }
            val gone = items.keys.count { it !in keep && inDir(it, only) }
            if (gone > 20 && gone * 2 > items.size) notice = "Many pictures were not found (card removed?). They stay in the index; use Rescan to clean up."
            else items.keys.removeAll { it !in keep && inDir(it, only) }   // deleted / moved pictures drop out of the index (only inside the scanned folder)
        }
        total = todo.size; done = 0
        msg = if (todo.isEmpty()) "Up to date" else "Analysing ${todo.size} pictures…"
        var sinceSave = 0
        var engineFails = 0          // consecutive failures that are NOT "this picture is bad" (model / runtime broken): abort instead of marking everything failed
        val recent = ArrayList<E>()  // pictures marked failed by those failures: restored when we abort
        var ocrFails = 0
        var lastSave = System.currentTimeMillis()
        val t0 = lastSave
        // Decoding + resizing the picture (Thumbs.make) is independent of the model: do it on 2 helper threads a few pictures ahead.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r, "imgsearch-thumb").also { it.isDaemon = true } }
        val ahead = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.Future<ByteArray>>()
        fun queue(i: Int) { if (i < todo.size && !ahead.containsKey(i) && todo[i].first.vec == null) ahead[i] = pool.submit(java.util.concurrent.Callable { Thumbs.make(Core.local.real(todo[i].second.path)) }) }
        for (k in 0 until 6) queue(k)
        var idx = -1
        try {
        for ((e, f) in todo) {
            idx++
            queue(idx + 6)
            if (cancel) { msg = "Cancelled"; break }
            val file = Core.local.real(f.path)
            markCur(f.path)
            try {
                if (e.vec == null) {
                    val jpeg = try { ahead.remove(idx)?.get() } catch (x: java.util.concurrent.ExecutionException) { throw (x.cause as? Exception) ?: x } ?: Thumbs.make(file)
                    val v = eng.embedImage(jpeg)
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
        } finally { pool.shutdownNow() }
        markCur(null)
        if (done > 0) msPerPic = (System.currentTimeMillis() - t0) / done
        if (!cancel) msg = notice ?: "Ready"
    }


    // ---------------------------------------------------------------- automatic scan of new pictures
    // A MediaStore observer (camera, screenshots, downloads, anything the system indexes) + a slow poll (files that arrive without a
    // MediaStore entry, e.g. received over LANShare) call [autoTick]. It runs the normal incremental scan, which only touches new /
    // changed pictures. Not before the user has done a first scan (the index is empty until then), not while the battery is low.
    private const val AUTO_DEBOUNCE = 20_000L      // let the camera finish writing the file / a burst of changes settle
    private const val AUTO_POLL = 10 * 60_000L     // fallback when no change notification comes
    private const val AUTO_BUSY = 30_000L          // a scan is running: look again afterwards
    private const val AUTO_LOWBAT = 15 * 60_000L   // power rules say wait
    @Volatile private var autoStarted = false
    @Volatile private var autoH: Handler? = null
    @Volatile private var autoCtx: Context? = null
    private val autoRun = Runnable { autoTick() }

    fun autoEnabled(): Boolean = Cfg.imgAuto()

    fun setAuto(on: Boolean) {
        Cfg.setImgAuto(on)
        if (on) autoPoke(3_000) else autoH?.removeCallbacks(autoRun)
    }

    /** Call once at app start (Core.start). Safe to call again. */
    fun startAutoWatch(ctx: Context) {
        synchronized(this) { if (autoStarted) return; autoStarted = true }
        try {
            autoCtx = ctx.applicationContext
            val t = HandlerThread("imgsearch-auto").also { it.isDaemon = true; it.start() }
            val h = Handler(t.looper); autoH = h
            val obs = object : ContentObserver(h) {
                override fun onChange(selfChange: Boolean) { if (Cfg.imgAuto()) autoPoke(AUTO_DEBOUNCE) }
            }
            try { ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs) } catch (_: Throwable) {}
            autoPoke(60_000)   // shortly after start: pictures that arrived while the app was not running
        } catch (t: Throwable) { Log.w("ImgSearch", "auto watch failed: " + errText(t)) }
    }

    private fun autoPoke(delayMs: Long) { val h = autoH ?: return; h.removeCallbacks(autoRun); h.postDelayed(autoRun, delayMs) }

    /** (battery percent or -1, charging). */
    private fun power(): Pair<Int, Boolean> = try {
        val i = autoCtx?.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        (if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1) to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    } catch (_: Throwable) { -1 to false }

    /** True when the power rules say: not now (only while charging, or battery < 20 % and not charging). */
    private fun powerBlocks(): Boolean { val (lvl, chg) = power(); return !chg && (Cfg.imgAutoCharging() || (lvl in 0..19)) }

    /** Cheap check, no writes: is there any picture the incremental scan would still have to analyse? Only lists folders. */
    private fun hasNew(ocr: Boolean): Boolean {
        val found = discover("", false)
        synchronized(lock) {
            for (f in found) {
                val e = items[f.path] ?: return true
                if (e.m != f.m || e.s != f.s) return true
                if (!e.failed && (e.vec == null || (ocr && e.text == null))) return true
            }
        }
        return false
    }

    fun setAutoCharging(on: Boolean) { Cfg.setImgAutoCharging(on); if (Cfg.imgAuto()) autoPoke(3_000) }

    private fun autoTick() {
        var next = AUTO_POLL
        try {
            if (!Cfg.imgAuto()) return                       // switched off: setAuto(true) re-arms
            val eng = engine
            if (eng == null || Core.storageOk == false) {
                // nothing to do now; poll again later
            } else if (running) {
                next = AUTO_BUSY
            } else {
                ensureLoaded()
                val (empty, withText) = synchronized(lock) { items.isEmpty() to items.values.any { !it.text.isNullOrEmpty() } }
                if (empty) {
                    // no first scan yet: the user starts that one
                } else if (powerBlocks()) {
                    next = AUTO_LOWBAT
                } else {
                    val ocr = withText && eng.canOcr         // keeps the mode the existing index was built with
                    if (hasNew(ocr)) start(ocr, false)       // nothing new = no scan, no index rewrite, no notification
                }
            }
        } catch (_: Throwable) {
        } finally { if (Cfg.imgAuto()) autoPoke(next) }
    }

    // ---------------------------------------------------------------- search
    /** Search limited to one folder (gallery album): [dir] itself and everything below it; "" = no limit. */
    private fun inDir(path: String, dir: String): Boolean {
        if (dir.isEmpty()) return true
        val d = vnorm(dir).trimEnd('/').lowercase(Locale.ROOT)
        if (d.isEmpty()) return true
        val p = path.lowercase(Locale.ROOT)
        return p == d || p.startsWith("$d/")
    }

    private fun hit(path: String, score: Float) = JSONObject().put("path", path).put("score", score.toDouble())

    /** CLIP visual search. Needs [engine] (text tower) and at least one stored vector. */
    fun visual(query: String, top: Int = TOP_N, threshold: Float = THRESHOLD, dir: String = ""): JSONObject {
        val eng = engine ?: throw BadReq("image search engine is not installed yet")
        ensureLoaded()
        val q = eng.embedText(query)
        val snap = synchronized(lock) { items.values.filter { it.vec != null && !isOut(it.path) && inDir(it.path, dir) } }
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
    fun text(query: String, whole: Boolean, dir: String = ""): JSONObject {
        ensureLoaded()
        val q = fold(query.trim())
        if (q.isEmpty()) throw BadReq("empty query")
        val esc = q.split(Regex("\\s+")).joinToString("\\s+") { w -> Regex.escape(w) }   // any run of blanks matches any run of blanks
        val re = Regex(if (whole) "(?<![\\p{L}\\p{N}])$esc(?![\\p{L}\\p{N}])" else esc)
        val snap = synchronized(lock) { items.values.filter { !it.text.isNullOrEmpty() && !isOut(it.path) && inDir(it.path, dir) } }
        val res = snap.filter { re.containsMatchIn(fold(it.text!!)) }
        return JSONObject().put("q", query).put("total", snap.size)
            .put("results", jarr(res.map { hit(it.path, 1f) }))
    }
}
