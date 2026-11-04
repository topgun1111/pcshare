package com.lanshare.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import com.lanshare.app.LanShareService
import com.lanshare.app.PdfBuildClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permanent page cache for big PDFs (>= [MIN_BYTES]).
 *
 * The first time such a PDF is opened, every page is rendered once (1.5 x screen width) and stored as lossy WebP + a small thumbnail (format v3, see [PdfCacheCodec]):
 *   - PDF on this phone      -> <folder of the pdf>/cache/<pdf name>/p0001.webp, t0001.webp ...   (+ .nomedia, meta.json)
 *   - PDF from SMB / device  -> <app files>/pdfpages/<hash>/...                      (private, size-capped)
 * Later opens read those images instead of rendering, so every page shows at once.
 *
 * The build is NOT tied to the viewer: it runs in its own PROCESS (:pdfbuild, [PdfBuildService]: a native crash / out-of-memory there cannot take the app down or turn viewer pages white; the client restarts it and gives up on a page that crashes it twice), is registered as a job in [Jobs.all]
 * (so [com.lanshare.app.LanShareService] shows it in the foreground notification with progress + cancel), holds a
 * partial wake lock, and keeps going after the viewer or the whole app screen is closed. Pages already written are
 * kept, so an interrupted build continues from where it stopped the next time the PDF is opened.
 */
object PdfPageCache {
    const val MIN_BYTES = 10L * 1024 * 1024                // default; the real threshold is [minBytes] (Settings > PDF page cache)
    /** Size from which a PDF gets a page cache (setting; 0 MB = every PDF). */
    fun minBytes(): Long = try { val mb = Cfg.pdfMinMb(); if (mb <= 0) 1L else mb * 1024L * 1024 } catch (_: Throwable) { MIN_BYTES }
    /** WebP quality of new pages (setting; already cached pages keep theirs). */
    fun quality(): Int = try { Cfg.pdfQuality() } catch (_: Throwable) { PdfCacheCodec.PAGE_Q }
    private fun capBytes(): Long = try { Cfg.pdfCapMb().toLong() * 1024 * 1024 } catch (_: Throwable) { PRIVATE_CAP }
    private const val TAG = "PdfPageCache"
    private const val VER = 3                                // v3: WebP pages + thumbnails + sizes written early; v2 (JPEG) caches are rebuilt once
    private const val PRIVATE_CAP = 2L * 1024 * 1024 * 1024

    /** Number of open PDF viewers: while > 0 the builder pauses briefly between pages so scrolling stays smooth. */
    val viewers = AtomicInteger(0)

    /** One render at a time in the whole process (viewer live renders + background build): two big PDFs / pages rendering at once exhaust native memory and PdfRenderer then returns WHITE pages. */
    val pdfLock = Any()

    /** Uptime (ms) of the viewer's last live render: the builder waits while the reader is waiting for pages. */
    @Volatile var lastUse = 0L

    /** Short status for the viewer subtitle: cached images / pages that stayed white after a retry. */
    fun status(dir: File?, n: Int): String {
        if (dir == null) return "no cache"
        val c = dir.listFiles { f -> f.name.startsWith("p") && f.name.endsWith("." + PdfCacheCodec.EXT) }?.size ?: 0
        val bl = readMeta(dir)?.optJSONArray("bl")?.length() ?: 0
        return "img $c/$n" + (if (bl > 0) " white $bl" else "")
    }

    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-cache").also { it.isDaemon = true } }

    fun pageFile(dir: File, pos: Int) = File(dir, String.format(java.util.Locale.ROOT, "p%04d.%s", pos + 1, PdfCacheCodec.EXT))
    fun thumbFile(dir: File, pos: Int) = File(dir, String.format(java.util.Locale.ROOT, "t%04d.%s", pos + 1, PdfCacheCodec.EXT))

    /** Small cached picture of a page (<= 240 px wide, RGB_565) for the overview grid; null when not cached yet. */
    fun readThumb(dir: File, pos: Int): Bitmap? = PdfCacheCodec.decodeRaw(thumbFile(dir, pos), true)

    // ---- progressive viewing: the viewer can follow the build page by page and tell the builder where the reader is

    interface Listener {
        /** Page [pos] (0-based) was just written (page + thumbnail). Called on the builder thread. */
        fun onPage(dir: File, pos: Int)
        /** The build of [dir] ended; [complete] = every page is cached (white pages count as given up). Builder thread. */
        fun onFinished(dir: File, complete: Boolean)
    }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun fire(f: (Listener) -> Unit) { for (l in listeners) try { f(l) } catch (_: Throwable) { } }

    private val focusPage = ConcurrentHashMap<String, Int>()

    /**
     * The reader looks at page [page] (0-based) of the PDF cached in [dir]: the builder renders the pages around it next (forward first),
     * and runs at full speed while the viewer keeps calling this (the reader is waiting for pages).
     */
    fun focus(dir: File, page: Int) { focusPage[dir.path] = page; PdfBuildClient.active[dir.path]?.focus(page) }

    /** What meta.json knows so far: page count, page sizes in points (null until the builder has read them), complete flag, cached width in px. */
    class Info(val n: Int, val pw: FloatArray?, val ph: FloatArray?, val complete: Boolean, val cw: Int, val bl: Set<Int> = emptySet())

    fun info(dir: File): Info? {
        val m = readMeta(dir) ?: return null
        if (m.optInt("v", 0) != VER) return null
        val n = m.optInt("n", 0)
        val a = m.optJSONArray("pw"); val b = m.optJSONArray("ph")
        val ok = n > 0 && a != null && b != null && a.length() == n && b.length() == n
        return Info(n, if (ok) FloatArray(n) { a!!.optDouble(it, 595.0).toFloat() } else null,
            if (ok) FloatArray(n) { b!!.optDouble(it, 842.0).toFloat() } else null, m.optBoolean("complete", false), m.optInt("cw", 0),
            m.optJSONArray("bl")?.let { a -> HashSet<Int>().also { s -> for (k in 0 until a.length()) s.add(a.optInt(k)) } } ?: emptySet())
    }

    /** Retry: forget that [pages] were given up on and mark the cache incomplete; the caller then calls [attach] again to queue the build. */
    fun clearBlocked(dir: File, pages: Set<Int>) {
        try {
            val m = readMeta(dir) ?: return
            val old = m.optJSONArray("bl") ?: return
            val keep = org.json.JSONArray()
            for (k in 0 until old.length()) if (!pages.contains(old.optInt(k))) keep.put(old.optInt(k))
            m.put("bl", keep); m.put("complete", false)
            writeMeta(dir, m)
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ viewer side

    /**
     * Called by the viewer once a big PDF is open. Picks the cache folder, drops it when the PDF changed, queues the build when
     * pages are missing and returns the folder the viewer can read finished pages from (null = no caching for this file).
     * [local] = the real file on this phone (cache goes next to it); null = a downloaded copy (private cache).
     */
    fun attach(ctx: Context, pdf: File, local: File?, name: String, key: String, cw: Int, maxW: Int, maxPx: Int): File? {
        return try {
            if (pdf.length() < minBytes()) return null
            val app = ctx.applicationContext
            val sig = if (local != null) "${local.length()}:${local.lastModified()}" else key
            val dir = chooseDir(app, local, name, key) ?: return null
            val old = readMeta(dir)
            val meta: JSONObject
            if (old == null || old.optString("sig") != sig || old.optInt("v", 0) != VER) {        // new or changed PDF: start clean
                dir.listFiles()?.forEach { if (it.name.startsWith("p") || it.name.startsWith("t") || it.name == "meta.json" || it.name == "meta.tmp") it.delete() }
                meta = JSONObject()
                meta.put("v", VER); meta.put("sig", sig); meta.put("cw", (cw * PdfCacheCodec.ZOOM).toInt()); meta.put("n", 0); meta.put("complete", false)
                writeMeta(dir, meta)
            } else meta = old
            if (!(meta.optBoolean("complete", false) && meta.has("pw"))) enqueue(app, pdf, dir, name, meta.optInt("cw", cw), maxW, maxPx)
            dir
        } catch (e: Throwable) { Log.w(TAG, "attach failed: $e"); null }
    }

    /** A finished cache: folder + page count + real page sizes (points), enough to show the document without opening the PDF. */
    class Hit(val dir: File, val n: Int, val pw: FloatArray, val ph: FloatArray)

    /**
     * Cache-first open: when every page of this PDF is already cached (and the PDF is unchanged) returns the data the viewer needs to
     * show all pages at once WITHOUT opening the PDF with PdfRenderer (opening a big PDF is the slow part). Null = no finished cache.
     * [srcLen] = size of the PDF (local: real length, remote: size from the list).
     */
    fun lookup(ctx: Context, local: File?, name: String, key: String, srcLen: Long): Hit? {
        return try {
            if (srcLen < minBytes()) return null
            val app = ctx.applicationContext
            val sig = if (local != null) "${local.length()}:${local.lastModified()}" else key
            val dir = chooseDir(app, local, name, key) ?: return null
            val m = readMeta(dir) ?: return null
            if (m.optString("sig") != sig || m.optInt("v", 0) != VER || !m.optBoolean("complete", false)) return null
            val n = m.optInt("n", 0)
            val a = m.optJSONArray("pw") ?: return null
            val b = m.optJSONArray("ph") ?: return null
            if (n <= 0 || a.length() != n || b.length() != n) return null
            val pw = FloatArray(n) { a.optDouble(it, 595.0).toFloat() }
            val ph = FloatArray(n) { b.optDouble(it, 842.0).toFloat() }
            val bl = m.optJSONArray("bl")
            val blank = HashSet<Int>(); if (bl != null) for (k in 0 until bl.length()) blank.add(bl.optInt(k))
            for (i in 0 until n) if (i !in blank && !pageFile(dir, i).let { it.isFile && it.length() > 0 }) return null      // a page was deleted: not finished
            Hit(dir, n, pw, ph)
        } catch (_: Throwable) { null }
    }

    /**
     * Reads a cached page scaled to [w] x [h]; null when it is not cached or the viewer needs more pixels than were cached (zoom).
     * [fast] decodes as RGB_565 (half the memory / upload time; fine for text pages, not for zoomed photos).
     */
    fun read(dir: File, pos: Int, w: Int, h: Int, upscale: Boolean = false, fast: Boolean = false): Bitmap? =
        PdfCacheCodec.decode(pageFile(dir, pos), w, h, upscale, fast)

    // ------------------------------------------------------------------ folders + meta

    private fun baseName(name: String) = name.replace(Regex("(?i)\\.pdf$"), "").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "document" }

    private fun chooseDir(app: Context, local: File?, name: String, key: String): File? {
        if (local != null) {
            val d = File(File(local.parentFile ?: return privDir(app, key), "cache"), baseName(name))
            try {
                if (d.isDirectory || d.mkdirs()) {
                    val probe = File(d, ".nomedia")                       // keeps the gallery from indexing the page images
                    if (probe.exists() || probe.createNewFile()) { register(app, d); return d }
                }
            } catch (_: Throwable) { }
            // folder not writable (read-only share / USB): fall back to the app's private storage
        }
        return privDir(app, key)
    }

    private fun privDir(app: Context, key: String): File? {
        val root = File(app.filesDir, "pdfpages")
        val d = File(root, java.lang.Long.toHexString(key.hashCode().toLong() and 0xffffffffL) + "_" + java.lang.Long.toHexString(key.length.toLong()))
        if (!d.isDirectory && !d.mkdirs()) return null
        File(d, ".nomedia").let { try { it.createNewFile() } catch (_: Throwable) { } }
        d.setLastModified(System.currentTimeMillis())
        trimPrivate(root, d)
        return d
    }

    private fun trimPrivate(root: File, keep: File) = trimGroup(root.listFiles()?.filter { it.isDirectory } ?: return, keep)

    private fun size(d: File) = d.listFiles()?.sumOf { it.length() } ?: 0L

    /** LRU cap (setting, 0 = unlimited): oldest folders go first; the folder in use and folders being built stay. */
    private fun trimGroup(dirs: List<File>, keep: File?) {
        try {
            val cap = capBytes(); if (cap <= 0) return
            var sum = dirs.sumOf { size(it) }
            for (d in dirs.sortedBy { it.lastModified() }) {
                if (sum <= cap) break
                if (d == keep || queued.contains(d.path) || PdfBuildClient.active.containsKey(d.path)) continue
                sum -= size(d); d.deleteRecursively(); forget(d)
            }
        } catch (_: Throwable) { }
    }

    // ------------------------------------------------------------------ management (Part 4b): registry of cache folders next to PDFs, sizes, clear, LRU, delete / rename of the PDF

    private val regLock = Any()
    @Volatile private var regFile: File? = null
    private fun regF(app: Context) = regFile ?: File(app.filesDir, "pdfcache_dirs.json").also { regFile = it }

    private fun regRead(app: Context): MutableSet<String> = synchronized(regLock) {
        try { val a = org.json.JSONArray(regF(app).readText()); HashSet<String>().also { for (i in 0 until a.length()) it.add(a.optString(i)) } } catch (_: Throwable) { HashSet() }
    }
    private fun regWrite(app: Context, s: Set<String>) = synchronized(regLock) { try { regF(app).writeText(org.json.JSONArray(s.toList()).toString()) } catch (_: Throwable) { } }

    /** Remembers a cache folder that lives next to a PDF (so size / clear / cap can find it) and trims the group by the cap. */
    private fun register(app: Context, d: File) {
        appRef = app
        val s = regRead(app)
        if (s.add(d.path)) regWrite(app, s)
        try { d.setLastModified(System.currentTimeMillis()) } catch (_: Throwable) { }
        trimGroup(s.map { File(it) }.filter { it.isDirectory }, d)
    }
    private fun forget(d: File) { val a = appRef ?: return; val s = regRead(a); if (s.remove(d.path)) regWrite(a, s) }
    @Volatile private var appRef: Context? = null

    /** Existing local cache folders (missing ones are dropped from the registry). */
    private fun localDirs(app: Context): List<File> {
        val s = regRead(app); val ok = s.filter { File(it).isDirectory }
        if (ok.size != s.size) regWrite(app, ok.toSet())
        return ok.map { File(it) }
    }

    /** Settings page: size of the private cache and of the cache folders next to PDFs, number of cached PDFs. */
    fun stats(app: Context): JSONObject {
        appRef = app.applicationContext
        val priv = File(app.filesDir, "pdfpages").listFiles()?.filter { it.isDirectory } ?: emptyList()
        val loc = localDirs(app)
        return JSONObject().put("privBytes", priv.sumOf { size(it) }).put("localBytes", loc.sumOf { size(it) }).put("count", priv.size + loc.size)
            .put("building", queued.size).put("minMb", Cfg.pdfMinMb()).put("quality", Cfg.pdfQuality()).put("capMb", Cfg.pdfCapMb())
    }

    /** Settings page: delete every page cache (private + next to PDFs). Folders being built stay. Returns freed bytes. */
    fun clearAll(app: Context): Long {
        var freed = 0L
        val all = (File(app.filesDir, "pdfpages").listFiles()?.filter { it.isDirectory } ?: emptyList()) + localDirs(app)
        for (d in all) {
            if (queued.contains(d.path) || PdfBuildClient.active.containsKey(d.path)) continue
            freed += size(d); d.deleteRecursively(); forget(d); dropEmptyParent(d)
        }
        return freed
    }

    private fun dropEmptyParent(d: File) { val p = d.parentFile; if (p != null && p.name == "cache" && p.listFiles().isNullOrEmpty()) p.delete() }

    /** The PDF [f] was deleted: its cache folder goes too. */
    fun onPdfRemoved(f: File) {
        try {
            if (!f.name.endsWith(".pdf", true)) return
            val d = File(File(f.parentFile ?: return, "cache"), baseName(f.name))
            if (d.isDirectory && File(d, "meta.json").isFile && !queued.contains(d.path)) { d.deleteRecursively(); forget(d); dropEmptyParent(d) }
        } catch (_: Throwable) { }
    }

    /** The PDF [from] was renamed / moved to [to]: the cache folder follows (signature = size + mtime stays valid); when that fails it is deleted and rebuilt on the next open. */
    fun onPdfMoved(from: File, to: File) {
        try {
            if (!from.name.endsWith(".pdf", true)) return
            val d = File(File(from.parentFile ?: return, "cache"), baseName(from.name))
            if (!d.isDirectory || !File(d, "meta.json").isFile) return
            if (queued.contains(d.path)) { return }                       // being built: leave it, the viewer re-attaches by name later
            val nd = File(File(to.parentFile ?: return, "cache"), baseName(to.name))
            if (nd.path == d.path) return
            nd.parentFile?.mkdirs()
            if (!nd.exists() && d.renameTo(nd)) { forget(d); appRef?.let { register(it, nd) }; dropEmptyParent(d) }
            else { d.deleteRecursively(); forget(d); dropEmptyParent(d) }
        } catch (_: Throwable) { }
    }

    // ---- auto-resume after the APP process died: a small list of builds in progress

    private fun pendF(app: Context) = File(app.filesDir, "pdfpending.json")
    private val pendLock = Any()
    private fun pendRead(app: Context): JSONObject = synchronized(pendLock) { try { JSONObject(pendF(app).readText()) } catch (_: Throwable) { JSONObject() } }
    private fun pendWrite(app: Context, o: JSONObject) = synchronized(pendLock) { try { if (o.length() == 0) pendF(app).delete() else pendF(app).writeText(o.toString()) } catch (_: Throwable) { } }
    private fun pendAdd(app: Context, pdf: File, dir: File, name: String, cw: Int, maxW: Int, maxPx: Int) {
        val o = pendRead(app)
        o.put(dir.path, JSONObject().put("pdf", pdf.path).put("name", name).put("cw", cw).put("maxW", maxW).put("maxPx", maxPx))
        pendWrite(app, o)
    }
    private fun pendDel(app: Context, dir: File) { val o = pendRead(app); if (o.remove(dir.path) != null) pendWrite(app, o) }

    /** App start: continue builds that were interrupted because the app process was killed (not user cancels, not finished ones). */
    fun resumePending(app: Context) {
        appRef = app.applicationContext
        val o = pendRead(app)
        val it = o.keys().asSequence().toList()
        for (k in it) {
            val e = o.optJSONObject(k) ?: continue
            val pdf = File(e.optString("pdf")); val dir = File(k)
            val m = readMeta(dir)
            if (!pdf.isFile || !dir.isDirectory || m == null || m.optBoolean("complete", false)) { o.remove(k); continue }
            enqueue(app, pdf, dir, e.optString("name"), e.optInt("cw"), e.optInt("maxW"), e.optInt("maxPx"))
        }
        pendWrite(app, o)
    }

    internal fun readMeta(dir: File): JSONObject? = try { JSONObject(File(dir, "meta.json").readText()) } catch (_: Throwable) { null }

    internal fun writeMeta(dir: File, m: JSONObject) {
        val tmp = File(dir, "meta.tmp")
        tmp.writeText(m.toString())
        val dst = File(dir, "meta.json")
        if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst) }
    }

    // ------------------------------------------------------------------ background build (runs in the :pdfbuild process, see PdfBuildService)

    /** Builds are queued one at a time; each one runs in the isolated process ([PdfBuildClient]) and falls back to this process only when the service cannot be bound. */
    private fun enqueue(app: Context, pdf: File, dir: File, name: String, cw: Int, maxW: Int, maxPx: Int) {
        if (!queued.add(dir.path)) return                                  // already building / waiting
        val jid = "pc" + java.lang.Long.toHexString(dir.path.hashCode().toLong() and 0xffffffffL)
        val job = Job("Caching PDF: $name")
        job.bytes = false
        job.total = 0L                                                       // indeterminate until the page count is known
        Jobs.all[jid] = job
        appRef = app
        pendAdd(app, pdf, dir, name, cw, maxW, maxPx)
        try { ContextCompat.startForegroundService(app, Intent(app, LanShareService::class.java)) } catch (_: Throwable) { }   // makes sure the notification service is up
        pool.execute {
            try { PdfBuildClient(app, pdf, dir, name, cw, maxW, maxPx, job).runBlocking() }
            finally { queued.remove(dir.path); pendDel(app, dir); if (job.end == 0L) job.end = System.currentTimeMillis(); regRead(app).let { r -> if (dir.path in r) trimGroup(r.map { File(it) }.filter { f -> f.isDirectory }, null) } }
        }
    }

    /** Called by [PdfBuildClient]: a page the isolated process crashed on twice is recorded as given up (shown as "page could not be drawn"; Retry clears it). */
    internal fun addBlocked(dir: File, page: Int) {
        try {
            val m = readMeta(dir) ?: return
            val a = m.optJSONArray("bl") ?: org.json.JSONArray()
            for (k in 0 until a.length()) if (a.optInt(k) == page) return
            m.put("bl", a.put(page)); writeMeta(dir, m)
        } catch (_: Throwable) { }
    }

    internal fun focusOf(dir: File) = focusPage[dir.path] ?: 0
    internal fun fireOnPage(dir: File, i: Int) = fire { it.onPage(dir, i) }
    internal fun fireOnFinished(dir: File, c: Boolean) = fire { it.onFinished(dir, c) }

    /** True when 256 evenly spaced FULL rows of the bitmap are pure white (a page the renderer gave up on; a fresh render starts white). */
    fun isBlank(b: Bitmap): Boolean = PdfBuilder.isBlank(b)
}
