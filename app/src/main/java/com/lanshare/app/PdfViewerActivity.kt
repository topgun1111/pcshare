package com.lanshare.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Dedicated PDF reader. The file browser hands it ONE pdf as a URL of the app's own local HTTP server (/api/dl), so local files,
 * other devices, SMB shares and files inside archives all work. The document is fetched to the cache (with progress), opened with
 * the framework [PdfRenderer] (no extra library) and shown as a continuous vertical list of pages. Pinch / double-tap to zoom
 * (pages are re-rendered sharply at the new zoom), drag to pan, "go to page", night mode (inverted colours), share / open with,
 * resume at the last page read. Password-protected or unsupported files offer "Open with..." instead.
 * Fast scroller with page bubble ([PdfScrollBar]), page overview grid (thumbnails, tap to jump), "T" = read the text of the current page
 * with the on-device OCR (ML Kit, same engine as the picture viewer) into a selectable dialog with Copy / Share.
 * Limits of PdfRenderer: no real text layer (so no in-page selection or document search) and no clickable links.
 */
class PdfViewerActivity : Activity() {
    companion object {
        /** JSON handed over by MainActivity.Bridge.viewPdf(): {name, url, size, key} */
        @Volatile var pending: String? = null
        private const val GAP_DP = 6
        private const val POS_PREFS = "ls_pdf"        // key -> "page,timestamp"
        private const val SET_PREFS = "ls_pdf_set"    // night mode
        private const val CACHE_MS = 12L * 3600 * 1000   // a downloaded copy of a file from another device / SMB is reused this long
        private const val CACHE_CAP = 400L * 1024 * 1024
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").also { it.isDaemon = true } }
    private val rlock = Any()                          // PdfRenderer allows ONE open page at a time -> every use is serialised
    @Volatile private var renderer: PdfRenderer? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null

    private var pw = FloatArray(0)
    private var ph = FloatArray(0)
    private var pageCount = 0
    private var ready = false
    private var name = "document.pdf"
    private var url = ""
    private var key = ""
    private var srcSize = 0L
    private var file: File? = null
    private var gen = 0                                // document generation (fetch)
    private var rgen = 0                               // render generation (size / document changes)
    private var viewW = 0
    private var zoomLevel = 1f
    private var curPage = 0
    private var pendingStart: Int? = null
    private var uiOn = true
    private var night = false
    private var maxW = 2400
    private var maxPx = 5_000_000

    private val inflight = HashMap<Int, Int>()         // UI thread only: page -> requested pixel width
    private val failedPages = HashSet<Int>()           // UI thread only
    private val bound = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()   // pages currently bound to a view
    private val ahead = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()   // neighbours of the current page rendered in advance
    private lateinit var cache: LruCache<Int, Bitmap>

    private lateinit var root: FrameLayout
    private lateinit var zf: PdfZoomFrame
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var top: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var nightBtn: TextView
    private lateinit var sb: PdfScrollBar
    private var ocrEng: AutoOcr? = null
    private var ocrBusy = false
    private var thumbDlg: Dialog? = null
    private val thumbCache = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: Int, value: Bitmap) = value.byteCount }
    private val thumbWanted = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private lateinit var pill: TextView
    private lateinit var stateBox: LinearLayout
    private lateinit var stateTv: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btnRow: LinearLayout
    private lateinit var btnOpen: Button
    private val pageAd = Ad()

    private val hidePill = Runnable {
        pill.animate().alpha(0f).setDuration(200).withEndAction { pill.visibility = View.GONE }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun gap() = dp(GAP_DP)
    private fun bgColor() = if (night) 0xFF1A1A1A.toInt() else 0xFF3C3C3C.toInt()

    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)       // it is a reader
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val big = am.memoryClass >= 192
        maxW = if (big) 3200 else 2400
        maxPx = if (big) 9_000_000 else 5_000_000
        cache = object : LruCache<Int, Bitmap>(maxOf(am.memoryClass, 64) * 1024 * 1024 / 3) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        night = getSharedPreferences(SET_PREFS, MODE_PRIVATE).getBoolean("night", false)
        File(cacheDir, "pdf").deleteRecursively()
        buildUi()
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePos()
        begin()
    }

    // ---------------------------------------------------------------- UI (built in code)
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(bgColor()) }

        zf = PdfZoomFrame(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onTap = { toggleUi() }
            onSettled = { s -> zoomLevel = s; refreshVisible() }
        }
        lm = LinearLayoutManager(this)
        rv = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            layoutManager = lm
            itemAnimator = null
            clipToPadding = false
            setItemViewCacheSize(2)
            setPadding(0, dp(56), 0, dp(16))
            adapter = pageAd
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { onScrolledUi(dy != 0) }
                override fun onScrollStateChanged(r: RecyclerView, newState: Int) { if (newState == RecyclerView.SCROLL_STATE_IDLE) refreshVisible() }
            })
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val w = r - l
                if (w > 0 && w != viewW) onWidthChanged(w)
            }
        }
        zf.addView(rv)
        root.addView(zf)
        sb = PdfScrollBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            padT = dp(64); padB = dp(24)
            onDrag = { f -> dragTo(f) }
        }
        root.addView(sb)

        fun tv(txt: String, sp: Float) = TextView(this).apply {
            text = txt; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(8), dp(8), dp(16))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        val back = tv("\u2190", 24f).apply { setOnClickListener { finish() } }
        titleTv = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1; setOnClickListener { askPage() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(subTv) }
        val thumbsBtn = tv("\u25A6", 20f).apply { setOnClickListener { showThumbs() } }
        val textBtn = tv("T", 18f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); setOnClickListener { pageText() } }
        nightBtn = tv("\u263E", 20f).apply { setOnClickListener { setNight(!night) } }
        val openWith = tv("\u2197", 20f).apply { setOnClickListener { openWith() } }
        val share = tv("\u2934", 20f).apply { setOnClickListener { share() } }
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(thumbsBtn)
        top.addView(textBtn)
        top.addView(nightBtn)
        top.addView(openWith)
        top.addView(share)
        root.addView(top)
        paintNightBtn()

        pill = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xCC000000.toInt()) }
            visibility = View.GONE
            setOnClickListener { askPage() }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(24) }
        }
        root.addView(pill)

        // loading / error panel
        stateTv = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER; setPadding(dp(24), dp(8), dp(24), dp(12))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        btnOpen = Button(this).apply { text = "Open with\u2026"; setOnClickListener { openWith() } }
        val btnClose = Button(this).apply { text = "Close"; setOnClickListener { finish() } }
        btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; addView(btnOpen); addView(btnClose) }
        stateBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            addView(stateTv)
            addView(bar, LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(btnRow)
        }
        root.addView(stateBox)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (uiOn) {          // keep the layout still while the bars are hidden
                val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                top.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(16))
                rv.setPadding(0, dp(56) + b.top, 0, dp(16) + b.bottom)
                sb.padT = dp(64) + b.top; sb.padB = dp(24) + b.bottom
                (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(24) + b.bottom
                pill.requestLayout()
            }
            insets
        }
        setContentView(root)
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs (copy / zip / print ...) while this screen is open
    }

    private fun toggleUi() {
        uiOn = !uiOn
        top.visibility = if (uiOn) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiOn) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !uiOn) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setState(msg: String, pct: Int?, err: Boolean) {
        stateBox.visibility = View.VISIBLE
        stateTv.text = msg
        bar.visibility = if (err) View.GONE else View.VISIBLE
        if (!err) {
            if (pct == null) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = pct }
        }
        btnRow.visibility = if (err) View.VISIBLE else View.GONE
        btnOpen.visibility = if (err && file?.isFile == true) View.VISIBLE else View.GONE
    }

    private fun updateTitle() {
        titleTv.text = name
        val size = if (srcSize > 0) android.text.format.Formatter.formatShortFileSize(this, srcSize) else ""
        subTv.text = if (ready) "${curPage + 1} / $pageCount" + (if (size.isNotEmpty()) " \u00b7 $size" else "") else size
    }

    private fun paintNightBtn() { nightBtn.setTextColor(if (night) 0xFFFFD54F.toInt() else Color.WHITE) }

    private fun setNight(on: Boolean) {
        night = on
        getSharedPreferences(SET_PREFS, MODE_PRIVATE).edit().putBoolean("night", on).apply()
        root.setBackgroundColor(bgColor())
        paintNightBtn()
        pageAd.notifyDataSetChanged()      // re-binds the pages with the new mode
    }

    // ---------------------------------------------------------------- start: fetch -> open -> sizes
    private fun begin() {
        val json = pending
        pending = null
        val o = try { if (json == null) null else JSONObject(json) } catch (_: Exception) { null }
        val u = o?.optString("url").orEmpty()
        if (o == null || !(u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost"))) { finish(); return }   // only the app's own server
        gen++; rgen++
        closeDoc()
        ready = false; pageCount = 0; curPage = 0; pendingStart = null; zoomLevel = 1f
        cache.evictAll(); inflight.clear(); failedPages.clear(); bound.clear()
        zf.reset()
        pageAd.notifyDataSetChanged()
        name = o.optString("name").ifEmpty { "document.pdf" }
        url = com.lanshare.app.core.RootShell.sign(u)
        srcSize = o.optLong("size", 0L)
        key = o.optString("key")
        file = null
        updateTitle()
        setState("Loading\u2026", null, false)
        val my = gen
        val dir = File(cacheDir, "pdf").apply { deleteRecursively(); mkdirs() }
        // files from another device / SMB share are kept for a while (reopening is instant, a broken download resumes); local files are always read fresh
        val cacheable = key.isNotEmpty() && !key.startsWith("local|")
        val cdir = File(cacheDir, "pdfc").apply { mkdirs() }
        val cname = "d" + java.lang.Long.toHexString(key.hashCode().toLong() and 0xffffffffL) + "_" + srcSize
        val out = if (cacheable) File(cdir, "$cname.pdf") else File(dir, "doc.pdf")
        val tmp = if (cacheable) File(cdir, "$cname.part") else File(dir, "doc.part")
        Thread {
            var err: String? = null
            try {
                if (cacheable) trimCache(cdir, out)
                val hit = cacheable && out.isFile && out.length() > 0 && (srcSize <= 0 || out.length() == srcSize) && System.currentTimeMillis() - out.lastModified() < CACHE_MS
                if (!hit) {
                    var attempt = 0
                    while (true) {
                        try { pull(my, tmp); break }
                        catch (e: IOException) {
                            if (my != gen) return@Thread
                            if (++attempt > 3 || e.message?.startsWith("HTTP") == true) throw e      // a dropped connection resumes from what is already there
                            ui.post { if (my == gen && !isDestroyed) setState("Connection lost - resuming\u2026", null, false) }
                            Thread.sleep(800L * attempt)
                        }
                    }
                    if (my != gen) return@Thread
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) throw IOException("cannot cache the document")
                    out.setLastModified(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (my != gen) return@Thread
                err = e.message ?: "Cannot download this document"
            }
            val fetchErr = err
            if (fetchErr != null) {
                ui.post { if (my == gen && !isDestroyed) setState(fetchErr, null, true) }
                return@Thread
            }
            ui.post { if (my == gen && !isDestroyed) { file = out; setState("Preparing\u2026", null, false) } }
            openDoc(my, out)
        }.also { it.isDaemon = true }.start()
    }

    /** One download attempt into [tmp]; continues a partial file with a Range request (the app's own /api/dl supports it). */
    private fun pull(my: Int, tmp: File) {
        val have = if (tmp.isFile) tmp.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 30000
        c.setRequestProperty("Accept-Encoding", "identity")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        val code = c.responseCode
        if (code == 416) { tmp.delete(); throw IOException("restart") }
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        val append = code == 206 && have > 0
        val rem = c.contentLengthLong
        val total = if (rem > 0) (if (append) have + rem else rem) else srcSize
        var done = if (append) have else 0L
        var last = -1
        c.inputStream.use { ins ->
            FileOutputStream(tmp, append).use { o2 ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (my != gen) throw IOException("cancelled")
                    val n = ins.read(buf)
                    if (n < 0) break
                    o2.write(buf, 0, n); done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != last) { last = pct; ui.post { if (my == gen) setState("Loading\u2026 $pct%", pct, false) } }
                    }
                }
            }
        }
        if (total > 0 && done < total) throw IOException("connection lost")
    }

    /** Drops stale / surplus cached downloads (old partial files, copies past their time, oldest first above the size cap). */
    private fun trimCache(cdir: File, keep: File) {
        val now = System.currentTimeMillis()
        val fs = cdir.listFiles() ?: return
        for (f in fs) if ((f.name.endsWith(".part") && now - f.lastModified() > 24 * 3600 * 1000L) || (f.name.endsWith(".pdf") && now - f.lastModified() > CACHE_MS && f != keep)) f.delete()
        var sum = 0L
        val pdfs = (cdir.listFiles() ?: return).filter { it.isFile }.sortedBy { it.lastModified() }
        for (f in pdfs) sum += f.length()
        for (f in pdfs) { if (sum <= CACHE_CAP) break; if (f != keep) { sum -= f.length(); f.delete() } }
    }

    /** Background thread: open with PdfRenderer and read every page size (so the list can be laid out without jumping). */
    private fun openDoc(my: Int, f: File) {
        var p: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(p)
            val n = r.pageCount
            if (n <= 0) throw IOException("This PDF has no pages")
            val w = FloatArray(n)
            val h = FloatArray(n)
            for (i in 0 until n) {
                if (my != gen) throw IOException("cancelled")
                try {
                    val pg = r.openPage(i)
                    try { w[i] = pg.width.toFloat(); h[i] = pg.height.toFloat() } finally { pg.close() }
                } catch (_: Exception) { }
                if (w[i] <= 0f || h[i] <= 0f) { w[i] = 595f; h[i] = 842f }
            }
            val rr = r
            val pp = p
            ui.post {
                if (my != gen || isDestroyed) {
                    try { rr.close() } catch (_: Exception) {}
                    try { pp.close() } catch (_: Exception) {}
                } else onDocReady(rr, pp, w, h, n)
            }
        } catch (e: Exception) {
            try { r?.close() } catch (_: Exception) {}
            try { p?.close() } catch (_: Exception) {}
            if (my != gen) return
            val msg = when (e) {
                is SecurityException -> "This PDF is password-protected. Use \u201cOpen with\u2026\u201d to read it in another app."
                is IOException -> if (e.message?.startsWith("This PDF") == true) e.message!! else "Cannot open this PDF (damaged or unsupported file)."
                else -> "Cannot open this PDF: " + (e.message ?: "error")
            }
            ui.post { if (my == gen && !isDestroyed) setState(msg, null, true) }
        }
    }

    private fun onDocReady(r: PdfRenderer, p: ParcelFileDescriptor, w: FloatArray, h: FloatArray, n: Int) {
        renderer = r; pfd = p; pw = w; ph = h; pageCount = n
        ready = true
        stateBox.visibility = View.GONE
        val start = resumePage().coerceIn(0, n - 1)
        curPage = start
        updateTitle()
        if (viewW > 0) showPages(start) else pendingStart = start
    }

    private fun showPages(at: Int) {
        pendingStart = null
        rgen++
        cache.evictAll(); inflight.clear(); bound.clear()
        pageAd.notifyDataSetChanged()
        lm.scrollToPositionWithOffset(at.coerceIn(0, maxOf(pageCount - 1, 0)), 0)
        curPage = at.coerceIn(0, maxOf(pageCount - 1, 0))
        updateTitle()
    }

    private fun onWidthChanged(w: Int) {
        viewW = w
        zoomLevel = 1f
        zf.reset()
        if (ready) rv.post { showPages(pendingStart ?: curPage) }
    }

    private fun closeDoc() {
        val r = renderer
        val p = pfd
        renderer = null; pfd = null
        if (r == null && p == null) return
        Thread {     // waits for a running render, never blocks the UI
            synchronized(rlock) {
                try { r?.close() } catch (_: Exception) {}
                try { p?.close() } catch (_: Exception) {}
            }
        }.start()
    }

    // ---------------------------------------------------------------- resume position
    private fun posKey() = "k" + key.hashCode()

    private fun resumePage(): Int {
        if (key.isEmpty()) return 0
        return getSharedPreferences(POS_PREFS, MODE_PRIVATE).getString(posKey(), null)?.substringBefore(',')?.toIntOrNull() ?: 0
    }

    private fun savePos() {
        if (key.isEmpty() || !ready || pageCount < 2) return
        val sp = getSharedPreferences(POS_PREFS, MODE_PRIVATE)
        val ed = sp.edit()
        ed.putString(posKey(), "$curPage,${System.currentTimeMillis()}")
        val all = sp.all
        if (all.size > 300) {
            all.entries.sortedBy { (it.value as? String)?.substringAfter(',')?.toLongOrNull() ?: 0L }.take(100).forEach { ed.remove(it.key) }
        }
        ed.apply()
    }

    // ---------------------------------------------------------------- scrolling / page indicator
    private fun onScrolledUi(moved: Boolean) {
        if (!ready) return
        val f = lm.findFirstVisibleItemPosition()
        val l = lm.findLastVisibleItemPosition()
        if (f < 0) return
        val cy = (zf.height / 2f - zf.ty) / zf.scale          // screen centre in the list's own (unzoomed) coordinates
        var p = f
        for (i in f..l) {
            val v = lm.findViewByPosition(i) ?: continue
            if (v.top <= cy && cy < v.bottom + gap()) { p = i; break }
        }
        if (p != curPage) { curPage = p; updateTitle() }
        if (!sb.dragging && pageCount > 1) {      // scroller thumb follows the list (position inside the page counts too)
            val fv = lm.findViewByPosition(f)
            val within = if (fv != null) ((rv.paddingTop - fv.top).toFloat() / (fv.height + gap()).coerceAtLeast(1)).coerceIn(0f, 1f) else 0f
            sb.fraction = when {
                !rv.canScrollVertically(1) -> 1f
                !rv.canScrollVertically(-1) -> 0f
                else -> ((f + within) / (pageCount - 1)).coerceIn(0f, 1f)
            }
            if (moved) sb.flash()
        }
        if (moved && !sb.dragging) {
            pill.text = "${curPage + 1} / $pageCount"
            ui.removeCallbacks(hidePill)
            pill.animate().cancel()
            pill.alpha = 1f
            pill.visibility = View.VISIBLE
            ui.postDelayed(hidePill, 1400)
        }
    }

    private fun goTo(i: Int) {
        if (!ready) return
        val p = i.coerceIn(0, pageCount - 1)
        lm.scrollToPositionWithOffset(p, 0)
        curPage = p
        updateTitle()
    }

    private fun askPage() {
        if (!ready) return
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((curPage + 1).toString()); setSelectAllOnFocus(true); setSingleLine(); hint = "1 - $pageCount"
        }
        val d = AlertDialog.Builder(this).setTitle("Go to page").setView(et)
            .setPositiveButton("Go") { _, _ -> et.text.toString().toIntOrNull()?.let { goTo(it - 1) } }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    // ---------------------------------------------------------------- rendering
    private fun pageH(pos: Int): Int = maxOf(1, (viewW * ph[pos] / pw[pos]).toInt())

    private fun renderSize(pos: Int, tw: Int): Pair<Int, Int> {
        val ratio = ph[pos] / pw[pos]
        var w = minOf(tw, maxW).toFloat()
        var h = w * ratio
        if (h > 4096f) { w *= 4096f / h; h = 4096f }                 // GPU texture limit
        if (w * h > maxPx) { val f = Math.sqrt((maxPx / (w * h)).toDouble()).toFloat(); w *= f; h *= f }
        return maxOf(w.toInt(), 1) to maxOf(h.toInt(), 1)
    }

    private fun holder(pos: Int): Pg? = rv.findViewHolderForAdapterPosition(pos) as? Pg

    private fun refreshVisible() {
        if (!ready) return
        for (i in 0 until rv.childCount) (rv.getChildViewHolder(rv.getChildAt(i)) as? Pg)?.let { if (it.pos >= 0) request(it.pos) }
        prefetch()
    }

    /** Once scrolling stops: render the next two pages and the previous one at normal size, so turning a page shows it at once. */
    private fun prefetch() {
        ahead.clear()
        for (dlt in intArrayOf(1, 2, -1)) {
            val q = curPage + dlt
            if (q in 0 until pageCount && !bound.contains(q)) { ahead.add(q); request(q, true) }
        }
    }

    private fun request(pos: Int, base: Boolean = false) {
        if (!ready || viewW <= 0 || pos !in 0 until pageCount || failedPages.contains(pos)) return
        val (w, h) = renderSize(pos, if (base) viewW else (viewW * zoomLevel).toInt().coerceAtLeast(viewW))
        val have = cache.get(pos) ?: holder(pos)?.v?.bmp
        if (have != null && have.width >= w - 1) return
        val flying = inflight[pos]
        if (flying != null && flying >= w - 1) return
        inflight[pos] = w
        val my = rgen
        try {
            pool.execute {
                var bm: Bitmap? = null
                var tried = false
                if (my == rgen && (bound.contains(pos) || ahead.contains(pos))) {          // skipped when the page already scrolled away
                    tried = true
                    if (have == null && w >= 900) {              // quick small version first, so a page is never blank while the sharp one is made
                        val sm = try { renderPage(pos, maxOf(w / 3, 1), maxOf(h / 3, 1)) } catch (_: Throwable) { null }
                        if (sm != null) ui.post { if (my == rgen && !isDestroyed) holder(pos)?.v?.let { if (it.bmp == null) it.bmp = sm } }
                    }
                    try {
                        bm = renderPage(pos, w, h)
                    } catch (e: OutOfMemoryError) {
                        cache.evictAll()
                        try { bm = renderPage(pos, maxOf(w / 2, 1), maxOf(h / 2, 1)) } catch (_: Throwable) { }
                    } catch (_: Exception) { }
                }
                val rb = bm
                val rf = tried && rb == null
                ui.post {
                    if (my != rgen || isDestroyed) return@post
                    inflight.remove(pos)
                    if (rb != null) {
                        cache.put(pos, rb)
                        holder(pos)?.v?.bmp = rb
                    } else if (rf) failedPages.add(pos)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { inflight.remove(pos) }
    }

    private fun renderPage(pos: Int, w: Int, h: Int): Bitmap? {
        synchronized(rlock) {
            val r = renderer ?: return null
            val pg = r.openPage(pos)
            try {
                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bm.eraseColor(Color.WHITE)
                pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bm
            } finally { pg.close() }
        }
    }

    // ---------------------------------------------------------------- fast scroller
    private fun dragTo(frac: Float) {
        if (!ready || pageCount < 1) return
        val pos = frac.coerceIn(0f, 1f) * (pageCount - 1)
        val page = pos.toInt().coerceIn(0, pageCount - 1)
        val within = pos - page
        lm.scrollToPositionWithOffset(page, -(within * (pageH(page) + gap())).toInt())
        sb.label = "${page + 1} / $pageCount"
        if (page != curPage) { curPage = page; updateTitle() }
    }

    // ---------------------------------------------------------------- page overview (thumbnails) + page actions
    private fun showThumbs() {
        if (!ready || thumbDlg != null) return
        val cols = if (resources.configuration.screenWidthDp >= 600) 5 else 3
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val cellW = (resources.displayMetrics.widthPixels - dp(8) * 2) / cols
        val boxH = (cellW * 1.35f).toInt()
        val acc = 0xFFFFB77C.toInt()
        val sel = java.util.TreeSet<Int>()                       // pages picked with a long press
        var adapterRef: RecyclerView.Adapter<*>? = null
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfViewerActivity, cols)
            setBackgroundColor(0xFF121212.toInt()); setPadding(dp(8), dp(40), dp(8), dp(64)); clipToPadding = false
            itemAnimator = null
        }
        fun action(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(acc); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { f() }
        }
        val info = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(8), 0, dp(8), 0) }
        val printB = action("Print") { printPages(if (sel.isEmpty()) null else sel.toList()) }
        val saveB = action("Save PDF") { savePages(sel.toList()) }
        val shareB = action("Share") { sharePages(sel.toList()) }
        val allB = action("All") { for (i in 0 until pageCount) sel.add(i); adapterRef?.notifyDataSetChanged(); refreshBar(info, printB, saveB, shareB, sel.size) }
        val clearB = action("\u2715") { sel.clear(); adapterRef?.notifyDataSetChanged(); refreshBar(info, printB, saveB, shareB, 0) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xF2202020.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(allB); addView(printB); addView(saveB); addView(shareB); addView(clearB)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        refreshBar(info, printB, saveB, shareB, 0)
        class Th(val box: LinearLayout, val iv: ImageView, val num: TextView) : RecyclerView.ViewHolder(box) { var pos = -1 }
        val ad = object : RecyclerView.Adapter<Th>() {
            override fun getItemCount() = pageCount
            override fun onCreateViewHolder(parent: ViewGroup, t: Int): Th {
                val iv = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
                val num = TextView(parent.context).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(4)) }
                val box = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(2))
                    addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, boxH - dp(24)))
                    addView(num)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return Th(box, iv, num)
            }
            override fun onBindViewHolder(h: Th, position: Int) {
                h.pos = position
                thumbWanted.add(position)
                val on = sel.contains(position)
                h.num.text = if (on) "\u2713 ${position + 1}" else (position + 1).toString()
                h.num.setTextColor(if (on) acc else Color.WHITE)
                h.box.background = when {
                    on -> GradientDrawable().apply { setStroke(dp(3), acc); setColor(0x55FFB77C); cornerRadius = dp(4).toFloat() }
                    position == curPage -> GradientDrawable().apply { setStroke(dp(2), 0xAAFFFFFF.toInt()); cornerRadius = dp(4).toFloat() }
                    else -> null
                }
                h.iv.colorFilter = if (night) PdfPageView.INVERT else null
                fun toggle() { if (!sel.remove(position)) sel.add(position); notifyItemChanged(position); refreshBar(info, printB, saveB, shareB, sel.size) }
                h.box.setOnClickListener { if (sel.isNotEmpty()) toggle() else { goTo(position); d.dismiss() } }
                h.box.setOnLongClickListener { toggle(); true }
                val have = thumbCache.get(position)
                h.iv.setImageBitmap(have)
                if (have == null) requestThumb(position) { bm -> if (h.pos == position) h.iv.setImageBitmap(bm) }
            }
            override fun onViewRecycled(h: Th) { if (h.pos >= 0) thumbWanted.remove(h.pos); h.pos = -1; h.iv.setImageBitmap(null) }
        }
        adapterRef = ad
        grid.adapter = ad
        d.setContentView(FrameLayout(this).apply { addView(grid); addView(bar) })
        d.setOnDismissListener { thumbDlg = null; thumbWanted.clear() }
        thumbDlg = d
        d.show()
        (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(curPage, dp(40))
    }

    private fun refreshBar(info: TextView, printB: TextView, saveB: TextView, shareB: TextView, n: Int) {
        info.text = if (n == 0) "Hold a page to select pages" else "$n selected"
        printB.text = if (n == 0) "Print all" else "Print"
        val v = if (n == 0) View.GONE else View.VISIBLE
        saveB.visibility = v; shareB.visibility = v
    }

    /** [0, 1, 2, 5] -> "1-3, 6" (page numbers for file names). */
    private fun rangeText(pages: List<Int>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pages.size) {
            var j = i
            while (j + 1 < pages.size && pages[j + 1] == pages[j] + 1) j++
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(pages[i] + 1); if (j > i) sb.append('-').append(pages[j] + 1)
            i = j + 1
        }
        return sb.toString()
    }

    /** Writes the given pages as a new PDF (each page drawn as a ~180 dpi JPEG, one page in memory at a time; text is not searchable in the copy). */
    private fun writeJpegPdf(pages: List<Int>, out: OutputStream, cancelled: () -> Boolean) {
        val wr = JpegPdfWriter(out)
        for (p in pages) {
            if (cancelled()) throw IOException("cancelled")
            var w = pw[p] * 2.5f
            var h = ph[p] * 2.5f
            val big = maxOf(w, h)
            if (big > 3000f) { val f = 3000f / big; w *= f; h *= f }
            val bm = renderPage(p, maxOf(w.toInt(), 1), maxOf(h.toInt(), 1)) ?: throw IOException("page ${p + 1} could not be drawn")
            try { wr.addPage(pw[p], ph[p], bm) } finally { bm.recycle() }
        }
        wr.finish()
    }

    private fun baseName() = name.replace(Regex("(?i)\\.pdf$"), "").replace('/', '_').ifEmpty { "document" }

    private fun savePages(pages: List<Int>) {
        if (pages.isEmpty()) return
        val fn = "${baseName()} (p. ${rangeText(pages)}).pdf"
        Toast.makeText(this, "Saving ${pages.size} page${if (pages.size > 1) "s" else ""}\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val shown: String
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fn); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("cannot create the file")
                    try {
                        (contentResolver.openOutputStream(uri) ?: throw IOException("cannot write the file")).buffered(1 shl 16).use { writeJpegPdf(pages, it) { isDestroyed } }
                    } catch (e: Throwable) { try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}; throw e }
                    shown = "Downloads/$fn"
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                    var f = File(dir, fn)
                    var n = 1
                    while (f.exists()) { f = File(dir, fn.removeSuffix(".pdf") + " ($n).pdf"); n++ }
                    try { f.outputStream().buffered(1 shl 16).use { writeJpegPdf(pages, it) { isDestroyed } } } catch (e: Throwable) { f.delete(); throw e }
                    shown = "Downloads/${f.name}"
                }
                runOnUiThread { Toast.makeText(this, "Saved to $shown", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Could not save: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun sharePages(pages: List<Int>) {
        if (pages.isEmpty()) return
        Toast.makeText(this, "Preparing\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }       // the FileProvider path the app already exposes
                val f = File(dir, "${baseName()} (p. ${rangeText(pages)}).pdf")
                try { f.outputStream().buffered(1 shl 16).use { writeJpegPdf(pages, it) { isDestroyed } } } catch (e: Throwable) { f.delete(); throw e }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                val intent = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"
                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "No app can open this", Toast.LENGTH_LONG).show() } }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Cannot share: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** System print dialog (it has its own printer / "Save as PDF" / page-range choices). Whole document: the original file goes out untouched; selected pages / a range: rebuilt from page pictures. */
    private fun printPages(pages: List<Int>?) {
        val src = file
        if (!ready || src == null || !src.isFile) { Toast.makeText(this, "Still loading...", Toast.LENGTH_SHORT).show(); return }
        val total = pages?.size ?: pageCount
        val job = baseName()
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(job, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cs: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder("$job.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(total).build(), true)
            }
            override fun onWrite(ranges: Array<out PageRange>, dest: ParcelFileDescriptor, cs: CancellationSignal?, cb: WriteResultCallback) {
                val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
                cs?.setOnCancelListener { cancelled.set(true) }
                Thread {
                    var ok: Array<PageRange>? = null
                    var fail: String? = null
                    try {
                        val all = ranges.any { it.start <= 0 && it.end >= total - 1 }
                        val os = FileOutputStream(dest.fileDescriptor)
                        if (pages == null && all) {
                            src.inputStream().use { it.copyTo(os, 1 shl 16) }
                            os.flush()
                            ok = arrayOf(PageRange.ALL_PAGES)
                        } else {
                            val idx = (0 until total).filter { i -> ranges.any { i >= it.start && i <= it.end } }
                            val bos = os.buffered(1 shl 16)
                            writeJpegPdf(idx.map { pages?.get(it) ?: it }, bos) { cancelled.get() }
                            bos.flush()
                            ok = ranges.map { it }.toTypedArray()
                        }
                    } catch (e: Throwable) { fail = e.message ?: "error" }
                    try { dest.close() } catch (_: Exception) {}
                    ui.post {
                        val r = ok
                        if (cancelled.get()) cb.onWriteCancelled() else if (r != null) cb.onWriteFinished(r) else cb.onWriteFailed(fail)
                    }
                }.start()
            }
        }, null)
    }

    private fun requestThumb(pos: Int, done: (Bitmap) -> Unit) {
        try {
            pool.execute {
                if (!thumbWanted.contains(pos)) return@execute          // scrolled away before its turn
                val tw = 260
                val th = maxOf(1, (tw * ph[pos] / pw[pos]).toInt())
                val bm = try { renderPage(pos, tw, th) } catch (_: Throwable) { null }
                if (bm != null) ui.post { if (!isDestroyed) { thumbCache.put(pos, bm); done(bm) } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    // ---------------------------------------------------------------- text of the current page (OCR)
    private fun pageText() {
        if (!ready) return
        if (ocrBusy) { Toast.makeText(this, "Still reading the text...", Toast.LENGTH_SHORT).show(); return }
        val pos = curPage
        ocrBusy = true
        Toast.makeText(this, "Reading the text on page ${pos + 1}...", Toast.LENGTH_SHORT).show()
        try {
            pool.execute {
                val (w, h) = renderSize(pos, 2400)
                val bm = try { renderPage(pos, w, h) } catch (_: Throwable) { null }
                ui.post {
                    if (isDestroyed) return@post
                    if (bm == null) { ocrBusy = false; Toast.makeText(this, "Could not read this page", Toast.LENGTH_LONG).show(); return@post }
                    val eng = ocrEng ?: AutoOcr(applicationContext).also { e -> e.onStatus = { m -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }; ocrEng = e }
                    try {
                        eng.recognize(bm) { r ->
                            ocrBusy = false
                            bm.recycle()
                            if (isDestroyed) return@recognize
                            r.onSuccess { lines -> showText(pos, lines.joinToString("\n") { it.text }.trim()) }
                             .onFailure { Toast.makeText(this, "Text recognition failed: " + (it.message ?: "error"), Toast.LENGTH_LONG).show() }
                        }
                    } catch (t: Throwable) { ocrBusy = false; Toast.makeText(this, "Text recognition is not available", Toast.LENGTH_LONG).show() }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { ocrBusy = false }
    }

    private fun showText(pos: Int, txt: String) {
        val shown = txt.ifEmpty { "No text found on this page." }
        val tvx = TextView(this).apply { text = shown; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val b = AlertDialog.Builder(this).setTitle("Page ${pos + 1} \u00b7 text (OCR)").setView(ScrollView(this).apply { addView(tvx) })
            .setNegativeButton("Close", null)
        if (txt.isNotEmpty()) {
            b.setPositiveButton("Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF text", txt))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            b.setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Share text"))
            }
        }
        b.show()
    }

    // ---------------------------------------------------------------- share / open with
    private fun exported(make: (Uri) -> Intent) {
        val src = file
        if (src == null || !src.isFile) { Toast.makeText(this, "Still loading...", Toast.LENGTH_SHORT).show(); return }
        Thread {
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }   // the FileProvider path the app already exposes
                var n = name.replace('/', '_').ifEmpty { "document.pdf" }
                if (!n.lowercase().endsWith(".pdf")) n += ".pdf"
                val dst = File(dir, n)
                src.copyTo(dst, true)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
                val intent = make(uri)
                runOnUiThread {
                    try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this, "No app can open this", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Cannot open: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun share() = exported { uri ->
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share"
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun openWith() = exported { uri ->
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Open with"
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ---------------------------------------------------------------- list pages
    private inner class Pg(val v: PdfPageView) : RecyclerView.ViewHolder(v) { var pos = -1 }

    private inner class Ad : RecyclerView.Adapter<Pg>() {
        override fun getItemCount() = if (ready && viewW > 0) pageCount else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Pg {
            val v = PdfPageView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            return Pg(v)
        }
        override fun onBindViewHolder(h: Pg, position: Int) {
            h.pos = position
            bound.add(position)
            val lp = h.v.layoutParams as RecyclerView.LayoutParams
            lp.height = pageH(position)
            lp.bottomMargin = gap()
            h.v.layoutParams = lp
            h.v.night = night
            h.v.bmp = cache.get(position) ?: thumbCache.get(position)      // overview thumbnail as a stand-in until the real render arrives
            request(position)
        }
        override fun onViewRecycled(h: Pg) {
            if (h.pos >= 0) bound.remove(h.pos)
            h.pos = -1
            h.v.bmp = null
        }
    }

    override fun onPause() {
        savePos()
        super.onPause()
    }

    override fun onResume() { super.onResume(); ModelLinks.showIfAny(this) }

    override fun onDestroy() {
        gen++; rgen++
        ui.removeCallbacksAndMessages(null)
        savePos()
        pool.shutdownNow()
        thumbDlg?.dismiss()
        try { ocrEng?.close() } catch (_: Throwable) {}
        closeDoc()
        cache.evictAll()
        File(cacheDir, "pdf").deleteRecursively()
        super.onDestroy()
    }
}

/** One page: draws its bitmap stretched over the whole view, so a low-resolution render stays visible until the sharp one arrives. */
internal class PdfPageView(c: Context) : View(c) {
    companion object {
        internal val INVERT = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f)))
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    var bmp: Bitmap? = null
        set(v) { field = v; invalidate() }
    var night = false
        set(v) { if (field != v) { field = v; paint.colorFilter = if (v) INVERT else null; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val b = bmp
        if (b == null) { canvas.drawColor(if (night) Color.BLACK else Color.WHITE); return }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, null, dst, paint)
    }
}

/**
 * Wraps the page list and zooms / pans it as a whole (scale transform on the child, so touches are mapped back automatically):
 * pinch about the fingers, double-tap 1x <-> 2.5x, horizontal drag when zoomed. Vertical drags stay the list's own scrolling.
 * After a zoom settles, [onSettled] lets the activity re-render the visible pages at the new resolution.
 */
internal class PdfZoomFrame(c: Context) : FrameLayout(c) {
    companion object { private const val MAX_ZOOM = 5f }
    var onTap: (() -> Unit)? = null
    var onSettled: ((Float) -> Unit)? = null
    var scale = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set
    private var anim: ValueAnimator? = null
    private var cancelSent = false
    private var lastFx = 0f
    private var lastFy = 0f

    private fun applyT() {
        val v = getChildAt(0) ?: return
        v.pivotX = 0f; v.pivotY = 0f
        v.scaleX = scale; v.scaleY = scale
        v.translationX = tx; v.translationY = ty
    }

    private fun clampT() {
        tx = tx.coerceIn(width * (1f - scale), 0f)
        ty = ty.coerceIn(height * (1f - scale), 0f)
    }

    fun reset() { anim?.cancel(); scale = 1f; tx = 0f; ty = 0f; applyT() }

    private fun zoomAbout(target: Float, fx: Float, fy: Float) {
        val s = target.coerceIn(1f, MAX_ZOOM)
        val f = s / scale
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scale = s
        clampT(); applyT()
    }

    fun animateTo(target: Float, fx: Float, fy: Float) {
        anim?.cancel()
        val s0 = scale; val tx0 = tx; val ty0 = ty
        val to = target.coerceIn(1f, MAX_ZOOM)
        anim = ValueAnimator.ofFloat(s0, to).apply {
            duration = 200
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                val f = s / s0
                scale = s
                tx = fx - (fx - tx0) * f
                ty = fy - (fy - ty0) * f
                clampT(); applyT()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { onSettled?.invoke(scale) }
            })
            start()
        }
    }

    private val sd = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { anim?.cancel(); lastFx = d.focusX; lastFy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAbout(scale * d.scaleFactor, d.focusX, d.focusY)
            tx += d.focusX - lastFx; ty += d.focusY - lastFy          // two-finger drag pans as well
            lastFx = d.focusX; lastFy = d.focusY
            clampT(); applyT()
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { onSettled?.invoke(scale) }
    }).also { it.isQuickScaleEnabled = false }

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { animateTo(if (scale > 1.05f) 1f else 2.5f, e.x, e.y); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > 1f && !sd.isInProgress) { tx -= dx; clampT(); applyT() }     // vertical drag is the list's own scrolling
            return false
        }
    })

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) cancelSent = false
        sd.onTouchEvent(e)
        gd.onTouchEvent(e)
        if (sd.isInProgress) {
            if (!cancelSent) {       // a pinch has started: the list must stop its own scroll
                cancelSent = true
                val c = MotionEvent.obtain(e)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clampT(); applyT()
    }
}

/**
 * Fast scroller on the right edge: a slim thumb that fades in while the list scrolls; dragging it jumps through the document and shows
 * a bubble with the page number. Touches that do not start on the thumb fall through to the page list.
 */
internal class PdfScrollBar(c: Context) : View(c) {
    var onDrag: ((Float) -> Unit)? = null
    var padT = 0
        set(v) { field = v; invalidate() }
    var padB = 0
        set(v) { field = v; invalidate() }
    var fraction = 0f
        set(v) { if (v != field) { field = v; invalidate() } }
    var label = ""
    var dragging = false
        private set
    private val d = resources.displayMetrics.density
    private val th = 52f * d
    private val thumbP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFB77C.toInt() }
    private val bubP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2000000.toInt() }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * d; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val hide = Runnable { if (!dragging) animate().alpha(0f).setDuration(250).start() }

    init { alpha = 0f }

    /** Thumb colour (ARGB; the alpha is forced to ~90 %). */
    fun setThumbColor(c: Int) { thumbP.color = (c and 0x00FFFFFF) or 0xE6000000.toInt(); invalidate() }

    private fun travel() = maxOf(1f, height - padT - padB - th)
    private fun thumbTop() = padT + fraction * travel()

    /** Show the scroller for a moment (called while the list scrolls). */
    fun flash() {
        animate().cancel(); alpha = 1f
        removeCallbacks(hide); postDelayed(hide, 1300)
    }

    override fun onDraw(canvas: Canvas) {
        val t = thumbTop()
        val w = if (dragging) 8f * d else 5f * d
        rect.set(width - w - 3f * d, t, width - 3f * d, t + th)
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbP)
        if (dragging && label.isNotEmpty()) {
            val bw = txtP.measureText(label) + 28f * d
            val bh = 34f * d
            val r = width - 16f * d
            val cy = t + th / 2
            rect.set(r - bw, cy - bh / 2, r, cy + bh / 2)
            canvas.drawRoundRect(rect, bh / 2, bh / 2, bubP)
            canvas.drawText(label, rect.centerX(), rect.centerY() + txtP.textSize / 3f, txtP)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val t = thumbTop()
                if (alpha < 0.2f || e.y < t - 14f * d || e.y > t + th + 14f * d) return false     // not on the thumb: the list gets it
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                animate().cancel(); alpha = 1f; removeCallbacks(hide)
                move(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> { if (dragging) move(e.y); return dragging }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { dragging = false; invalidate(); removeCallbacks(hide); postDelayed(hide, 900) }
                return true
            }
        }
        return dragging
    }

    private fun move(y: Float) {
        fraction = ((y - padT - th / 2) / travel()).coerceIn(0f, 1f)
        onDrag?.invoke(fraction)
        invalidate()
    }
}

/**
 * Minimal PDF writer: every page is one JPEG (DCTDecode) scaled to the page size in points. Only one bitmap is alive at a time, so any
 * number of pages fits in memory, and the file stays small. Object ids: 1 catalog, 2 page tree, then page / content / image per page.
 */
internal class JpegPdfWriter(private val out: OutputStream) {
    private var pos = 0L
    private val offs = HashMap<Int, Long>()
    private var n = 0

    private fun w(b: ByteArray) { out.write(b); pos += b.size }
    private fun w(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))
    private fun obj(id: Int) { offs[id] = pos; w("$id 0 obj\n") }

    init { w("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n") }

    fun addPage(wPt: Float, hPt: Float, bm: Bitmap) {
        val bo = java.io.ByteArrayOutputStream(256 * 1024)
        bm.compress(Bitmap.CompressFormat.JPEG, 85, bo)
        val jpg = bo.toByteArray()
        val base = 3 + 3 * n
        val ws = String.format(java.util.Locale.US, "%.2f", wPt)
        val hs = String.format(java.util.Locale.US, "%.2f", hPt)
        obj(base)
        w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $ws $hs] /Resources << /XObject << /Im0 ${base + 2} 0 R >> >> /Contents ${base + 1} 0 R >>\nendobj\n")
        val cs = "q $ws 0 0 $hs 0 0 cm /Im0 Do Q"
        obj(base + 1)
        w("<< /Length ${cs.length} >>\nstream\n$cs\nendstream\nendobj\n")
        obj(base + 2)
        w("<< /Type /XObject /Subtype /Image /Width ${bm.width} /Height ${bm.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
        w(jpg)
        w("\nendstream\nendobj\n")
        n++
    }

    fun finish() {
        obj(1); w("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        obj(2)
        val kids = StringBuilder()
        for (i in 0 until n) kids.append(3 + 3 * i).append(" 0 R ")
        w("<< /Type /Pages /Count $n /Kids [$kids] >>\nendobj\n")
        val size = 3 + 3 * n
        val xref = pos
        w("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) w(String.format(java.util.Locale.US, "%010d 00000 n \n", offs[id] ?: 0L))
        w("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }
}
