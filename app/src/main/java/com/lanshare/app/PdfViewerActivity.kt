package com.lanshare.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
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
import android.os.Build
import android.os.Bundle
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Dedicated PDF reader. The file browser hands it ONE pdf as a URL of the app's own local HTTP server (/api/dl), so local files,
 * other devices, SMB shares and files inside archives all work. The document is fetched to the cache (with progress), opened with
 * the framework [PdfRenderer] (no extra library) and shown as a continuous vertical list of pages. Pinch / double-tap to zoom
 * (pages are re-rendered sharply at the new zoom), drag to pan, "go to page", night mode (inverted colours), share / open with,
 * resume at the last page read. Password-protected or unsupported files offer "Open with..." instead.
 * Limits of PdfRenderer: no text selection, search or clickable links.
 */
class PdfViewerActivity : Activity() {
    companion object {
        /** JSON handed over by MainActivity.Bridge.viewPdf(): {name, url, size, key} */
        @Volatile var pending: String? = null
        private const val GAP_DP = 6
        private const val POS_PREFS = "ls_pdf"        // key -> "page,timestamp"
        private const val SET_PREFS = "ls_pdf_set"    // night mode
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
    private lateinit var cache: LruCache<Int, Bitmap>

    private lateinit var root: FrameLayout
    private lateinit var zf: PdfZoomFrame
    private lateinit var rv: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var top: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var nightBtn: TextView
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
        nightBtn = tv("\u263E", 20f).apply { setOnClickListener { setNight(!night) } }
        val openWith = tv("\u2197", 20f).apply { setOnClickListener { openWith() } }
        val share = tv("\u2934", 20f).apply { setOnClickListener { share() } }
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(24) + b.bottom
                pill.requestLayout()
            }
            insets
        }
        setContentView(root)
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
        url = u
        srcSize = o.optLong("size", 0L)
        key = o.optString("key")
        file = null
        updateTitle()
        setState("Loading\u2026", null, false)
        val my = gen
        val dir = File(cacheDir, "pdf").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "doc.pdf")
        val tmp = File(dir, "doc.part")
        Thread {
            var err: String? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 30000
                c.setRequestProperty("Accept-Encoding", "identity")
                if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else srcSize
                var done = 0L
                var last = -1
                c.inputStream.use { ins ->
                    tmp.outputStream().use { o2 ->
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
                if (my != gen) return@Thread
                if (!tmp.renameTo(out)) throw IOException("cannot cache the document")
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
        if (moved) {
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
    }

    private fun request(pos: Int) {
        if (!ready || viewW <= 0 || pos !in 0 until pageCount || failedPages.contains(pos)) return
        val (w, h) = renderSize(pos, (viewW * zoomLevel).toInt().coerceAtLeast(viewW))
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
                if (my == rgen && bound.contains(pos)) {          // skipped when the page already scrolled away
                    tried = true
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
            h.v.bmp = cache.get(position)
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

    override fun onDestroy() {
        gen++; rgen++
        ui.removeCallbacksAndMessages(null)
        savePos()
        pool.shutdownNow()
        closeDoc()
        cache.evictAll()
        File(cacheDir, "pdf").deleteRecursively()
        super.onDestroy()
    }
}

/** One page: draws its bitmap stretched over the whole view, so a low-resolution render stays visible until the sharp one arrives. */
internal class PdfPageView(c: Context) : View(c) {
    companion object {
        private val INVERT = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
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
