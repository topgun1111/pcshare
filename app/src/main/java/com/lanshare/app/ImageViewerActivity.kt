package com.lanshare.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.PathParser
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lanshare.app.core.Core
import com.lanshare.app.core.mimeFor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Dedicated image viewer. The file browser hands it the images of the open folder as URLs of the app's own local HTTP server
 * (/api/dl), so local files, other devices and SMB shares all work. Swipe between pictures, pinch / double-tap to zoom, drag to
 * pan (at the edge of a zoomed picture the drag turns into a page swipe), EXIF rotation is applied, big photos are decoded
 * down to at most 4096 px (so no out-of-memory), neighbours are preloaded, tap hides the bars, share, slideshow.
 * (Animated GIF and SVG stay in the built-in browser viewer.)
 */
class ImageViewerActivity : Activity() {
    companion object {
        /** JSON handed over by MainActivity.Bridge.viewImages(): {start, items:[{name, url, size}]} */
        @Volatile var pending: String? = null
        private const val MAX_ITEMS = 2000
        private const val SLIDE_MS = 4000L
        private const val REQ_EDIT = 41
        /** Set after a picture was deleted / replaced here: MainActivity refreshes the file list and an open album when it comes back to the front. */
        @Volatile private var changed = false
        fun consumeChanged(): Boolean { val c = changed; changed = false; return c }
    }

    private class Item(val name: String, val url: String, val size: Long, val mtime: Long = 0L, val key: String = "")

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "img-load").also { it.isDaemon = true } }
    private var items: List<Item> = emptyList()
    private lateinit var pager: ViewPager2
    private lateinit var top: LinearLayout
    private lateinit var topWrap: LinearLayout   // top bar + details band underneath it
    private lateinit var infoTv: TextView        // wide details band: date, time, pixels, size, position
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var countPill: TextView
    private val loading = HashSet<Int>()          // UI thread only
    private val failed = HashMap<Int, String>()   // UI thread only
    private val dims = HashMap<Int, String>()
    private lateinit var cache: LruCache<Int, Bitmap>
    private var maxDim = 3072
    private var bmBudget = 16 shl 20              // max bytes of ONE decoded picture: current + both neighbours must fit in the cache together
    private var uiOn = true
    private var slide = false
    private var gen = 0

    // select text in the picture (OcrEngine.kt / OcrOverlayView.kt)
    private val ocr by lazy { AutoOcr(applicationContext).also { it.onStatus = { m -> if (ocrWant >= 0 && ocrWant == ocrWork) Lm.snack(this, m, Toast.LENGTH_SHORT) } } }   // toasts only while the user waits for T
    private val ocrRes = HashMap<Int, List<OcrLine>>()   // UI thread only: recognised lines per page (coordinates of the displayed bitmap)
    private val turned = HashMap<Int, Int>()               // page -> quarter turns (0..3) set with the rotate buttons / two-finger twist, NOT yet written to the file (written 0.7 s later, see flushTurns)
    private val saving = HashSet<Int>()                    // pages whose turn is being written right now (UI thread only)
    private val noSave = HashSet<Int>()                    // pages whose turn cannot be written (not on this phone's storage, read only, unsupported type): the turn stays view only
    private val savePool = Executors.newSingleThreadExecutor { r -> Thread(r, "img-turn").also { it.isDaemon = true } }
    private val flushRun = Runnable { flushTurns() }
    private val ocrOn = HashSet<Int>()                   // pages whose text layer is switched on
    private val ocrNone = HashSet<Int>()                 // pages recognised with no text in them (T is dimmed)
    private val ocrBad = HashSet<Int>()                  // pages where recognition failed (auto mode skips them; pressing T retries)
    private var ocrWork = -1                             // page being recognised right now, -1 = idle
    private var ocrWant = -1                             // page the user pressed T on and is waiting for
    private var ocrFails = 0                             // consecutive failures; auto mode stops at 2 (model missing / no Play Services)
    private val autoRun = Runnable { autoOcr() }
    private lateinit var ocrBtn: IconBtn
    private lateinit var selBar: LinearLayout
    private lateinit var copyBtn: TextView

    private val slideTick = object : Runnable {
        override fun run() {
            if (!slide) return
            val n = pager.currentItem + 1
            if (n >= items.size) { setSlide(false); return }
            pager.setCurrentItem(n, true)
            ui.postDelayed(this, SLIDE_MS)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    /** Lumen M7: return transition (fade + 94 % scale, 200 ms) over the screen that is already underneath. */
    private var heroIdx = -9          // item the viewer was opened on (NlHero.close shrinks back only while it is still shown)
    private var heroClosing = false
    override fun finish() {
        if (!heroClosing) { val cur = try { pager.currentItem } catch (_: Throwable) { -1 }
            if (NlHero.close(this, cur, heroIdx, try { items.getOrNull(cur)?.key } catch (_: Throwable) { null }) { heroClosing = true; finish() }) return }   // Lumen M7 return: shrink into the thumbnail
        super.finish()
        if (heroClosing) overridePendingTransition(0, 0) else LumenPrefs.closeAnim(this)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mc = maxOf(am.memoryClass, 96)
        maxDim = if (mc >= 192) 4096 else 3072
        val cacheBytes = mc * 1024 * 1024 / 8 * 3
        bmBudget = cacheBytes / 3 - (1 shl 20)
        cache = object : LruCache<Int, Bitmap>(cacheBytes) {
            override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
        }
        File(cacheDir, "img").deleteRecursively()
        buildUi()
        begin()
        pool.execute { pruneOcr() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        NlHero.release(this)   // the kept list snapshot belongs to the first item
        setIntent(intent)
        begin()
    }

    // ---------------------------------------------------------------- UI (built in code)
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            offscreenPageLimit = 1
            adapter = Ad()
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) { flushTurns(); updateTitle(position); holder(position)?.show(position); prefetch(position); ocrWant = -1; updateOcrBar(); scheduleAutoOcr() }
                override fun onPageScrollStateChanged(state: Int) { if (state == ViewPager2.SCROLL_STATE_DRAGGING && slide) setSlide(false) }
            })
        }
        root.addView(pager)

        // top bar: back, file name + date, then three icon actions and a menu (slideshow, details). The position counter lives in a pill at the bottom.
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = NlViewerBar.scrim()
            setPadding(dp(4), dp(8), dp(4), dp(32))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // details band: sits right under the top bar and spans the full width, so nothing is cut off with "..." (the dark space around the picture is used for it)
        infoTv = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt()); LumenTypography.fit(this, 13f); setLineSpacing(0f, 1.15f)
            maxLines = 3; setPadding(dp(16), dp(8), dp(16), dp(8)); setBackgroundColor(0x99000000.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = -dp(20) }
        }
        topWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            addView(this@ImageViewerActivity.top); addView(infoTv)   // 'top' alone would be View.getTop() (an Int) here
        }
        fun icon(path: String, desc: String, act: () -> Unit) = IconBtn(this, path).apply {
            contentDescription = desc; setOnClickListener { act() }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) }
        }
        val back = icon(IconBtn.BACK, "Back") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) }
        titleTv = NlViewerBar.title(this)   // Lumen N7: shared viewer-bar title / subtitle / column (NlViewerBar.kt)
        subTv = NlViewerBar.subtitle(this)
        val col = NlViewerBar.textColumn(this, titleTv, subTv)
        ocrBtn = icon(IconBtn.SCAN, "Text on this picture") { toggleOcr() }
        val edit = icon(IconBtn.EDIT, "Edit") { edit() }
        val print = icon(IconBtn.PRINT, "Print") { printCurrent() }
        val turn = icon(IconBtn.ROTATE, "Rotate 90\u00b0") { turnCurrent(1) }
        val share = icon(IconBtn.SHARE, "Share") { share() }
        lateinit var more: IconBtn
        more = icon(IconBtn.MORE, "More") {
            PopupMenu(this, more).apply {
                menu.add(0, 1, 0, if (slide) "Stop slideshow" else "Slideshow")
                menu.add(0, 2, 1, "Details")
                menu.add(0, 5, 1, "Rotate left 90\u00b0")
                menu.add(0, 6, 1, "Rotate right 90\u00b0")
                menu.add(0, 4, 2, "To Print space")
                menu.add(0, 3, 3, "Delete")
                setOnMenuItemClickListener { mi -> when (mi.itemId) { 1 -> { setSlide(!slide); true }; 2 -> { showDetails(pager.currentItem); true }; 3 -> { deleteCurrent(); true }; 4 -> { addToPrintSpace(); true }; 5 -> { turnCurrent(-1); true }; 6 -> { turnCurrent(1); true }; else -> false } }
            }.show()
        }
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ocrBtn); top.addView(turn); top.addView(edit); top.addView(print); top.addView(share); top.addView(more)
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(4) + b.left, dp(8) + b.top, dp(4) + b.right, dp(32))
            infoTv.setPadding(dp(16) + b.left, dp(8), dp(16) + b.right, dp(8))
            insets
        }
        root.addView(topWrap)

        countPill = TextView(this).apply {
            setTextColor(Color.WHITE); LumenTypography.fit(this, 13f); gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x99000000.toInt()) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(countPill) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(20) + b.bottom
            v.requestLayout()
            insets
        }
        root.addView(countPill)

        fun tv(txt: String, sp: Float) = TextView(this).apply {
            text = txt; setTextColor(Color.WHITE); LumenTypography.fit(this, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(48)
        }
        // bar shown while a picture's text layer is on: Copy (selection, else all text) / Select all / Share / Close
        fun barBtn(txt: String, act: () -> Unit) = tv(txt, 15f).apply { setOnClickListener { act() } }
        copyBtn = barBtn("Copy all") { copyOcr() }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xE6202124.toInt()); cornerRadius = dp(24).toFloat() }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
            addView(copyBtn)
            addView(barBtn("Select all") { holder(pager.currentItem)?.ov?.selectAll() })
            addView(barBtn("Search") { searchOcr() })
            addView(barBtn("Share") { shareOcr() })
            addView(barBtn("\u2715") { closeOcr() })
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) }
        }
        ViewCompat.setOnApplyWindowInsetsListener(selBar) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (v.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(16) + b.bottom
            v.requestLayout()
            insets
        }
        root.addView(selBar)
        setContentView(root)
        NlHero.play(this, root, listOf(topWrap, countPill)) { cache.size() > 0 }   // Lumen 4f M7 b: container transform from the tapped thumbnail (no-op when not launched from the list)
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs (copy / zip / print ...) while this screen is open
    }

    private fun toggleUi() {
        uiOn = !uiOn
        NlViewerBar.fade(this, listOf(topWrap, countPill), uiOn)   // Lumen N7: 200 ms fade, plain with Reduce motion
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiOn) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
        armAutoHide()
    }

    // Lumen 12.7 / 12.22: the viewer bars hide after 3 s without a touch (not with a screen reader, not while hidden)
    private val autoHide = NlViewerBar.AutoHide(this, { uiOn }, { toggleUi() })
    private fun armAutoHide() = autoHide.arm()
    override fun onUserInteraction() { super.onUserInteraction(); armAutoHide() }

    private fun setSlide(on: Boolean) {
        slide = on
        ui.removeCallbacks(slideTick)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (uiOn) toggleUi()
            ui.postDelayed(slideTick, SLIDE_MS)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateTitle(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        // title: file name; the wide band under the top bar: date \u00b7 time, pixels (+ megapixels), size; the position sits in the pill at the bottom
        titleTv.text = it.name
        countPill.text = (pos + 1).toString() + " / " + items.size
        subTv.visibility = View.GONE   // the date moved into the wide details band below the top bar
        val sep = "   \u2022   "
        val parts = ArrayList<String>()
        if (it.mtime > 0) {
            val d = java.util.Date(it.mtime * 1000)
            parts.add(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(d) + "  \u00b7  " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d))
        }
        dims[pos]?.let { d ->
            val wh = d.split('\u00d7')
            val mp = if (wh.size == 2) (wh[0].trim().toLongOrNull() ?: 0L) * (wh[1].trim().toLongOrNull() ?: 0L) else 0L
            parts.add(if (mp > 0) d + "  \u00b7  " + String.format(java.util.Locale.US, "%.1f MP", mp / 1e6) else d)
        }
        if (it.size > 0) parts.add(android.text.format.Formatter.formatShortFileSize(this, it.size))
        infoTv.text = parts.joinToString(sep)
        infoTv.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showDetails(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        val rows = ArrayList<String>()
        rows.add("Name\n" + it.name)
        if (it.mtime > 0) rows.add("Modified\n" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it.mtime * 1000)))
        dims[pos]?.let { d -> rows.add("Dimensions\n$d") }
        if (it.size > 0) rows.add("Size\n" + android.text.format.Formatter.formatShortFileSize(this, it.size))
        rows.add("Position\n" + (pos + 1) + " of " + items.size)
        val base = rows.joinToString("\n\n")
        val detDlg = AppPal.dlg(this).kind("info").setTitle("Details").setMessage(base + "\n\nReading details\u2026").setPositiveButton("Close", null).show()
        Thread({   // same media details as the file list's Details sheet (date taken etc.), via the app's own /api/stat
            val extra = MediaText.format(MediaText.fetchStat(it.url))
            runOnUiThread { if (!isFinishing && detDlg.isShowing) detDlg.setMessage(if (extra.isEmpty()) base else base + "\n" + extra) }
        }, "viewer-details").also { t -> t.isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- start
    private fun begin() {
        val json = pending
        pending = null
        val parsed = try { if (json == null) null else parse(json) } catch (_: Exception) { null }
        if (parsed == null || parsed.first.isEmpty()) { finish(); return }
        install(parsed.first, parsed.second)
    }

    /** Shows [list] from page [start]: every per-page cache is dropped first (they are keyed by page number). */
    private fun install(list: List<Item>, start: Int) {
        gen++
        loading.clear(); failed.clear(); dims.clear(); cache.evictAll(); ocrRes.clear(); turned.clear(); ocrOn.clear(); ocrNone.clear(); ocrBad.clear(); ocrWork = -1; ocrWant = -1; ocrFails = 0
        ui.removeCallbacks(autoRun)
        selBar.visibility = View.GONE
        File(cacheDir, "img").deleteRecursively()
        items = list
        pager.adapter = Ad()
        pager.setCurrentItem(start, false)
        if (heroIdx == -9) heroIdx = start
        updateTitle(start)
        prefetch(start)
    }

    private fun parse(json: String): Pair<List<Item>, Int> {
        val o = JSONObject(json)
        val arr = o.getJSONArray("items")
        val out = ArrayList<Item>()
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val it = arr.getJSONObject(i)
            val url = it.getString("url")
            if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))   // only the app's own server
                out.add(Item(it.optString("name"), com.lanshare.app.core.RootShell.sign(url), it.optLong("size", 0L), it.optLong("mtime", 0L), it.optString("key", "")))
        }
        return out to o.optInt("start", 0).coerceIn(0, maxOf(out.size - 1, 0))
    }

    // ---------------------------------------------------------------- loading: fetch to cache file -> decode (sampled, EXIF-rotated)
    private fun holder(pos: Int): Pg? = (pager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos) as? Pg

    private fun prefetch(pos: Int) {
        for (p in intArrayOf(pos, pos + 1, pos - 1)) if (p in items.indices) request(p)
    }

    private fun request(pos: Int) {
        if (cache.get(pos) != null || failed.containsKey(pos) || !loading.add(pos)) return
        val my = gen
        val it = items[pos]
        pool.execute {
            var bm: Bitmap? = null
            var err: String? = null
            var dim: String? = null
            try {
                val f = fetch(pos, it)
                val r = decode(f)
                bm = r.first; dim = r.second
            } catch (e: OutOfMemoryError) { err = "Not enough memory for this picture"
            } catch (e: Exception) { err = e.message ?: "Cannot open this picture" }
            val rb = bm
            val re = err
            val rd = dim
            ui.post {
                if (my != gen || isDestroyed) return@post
                loading.remove(pos)
                if (rb != null) {
                    cache.put(pos, rb)
                    if (cache.get(pos) == null) failed[pos] = "Not enough memory for this picture"   // cannot happen with the decode budget, but never leave a blank page
                    if (rd != null) dims[pos] = rd
                } else failed[pos] = re ?: "Cannot open this picture"
                holder(pos)?.show(pos)
                if (pager.currentItem == pos) updateTitle(pos)
                if (rb != null) scheduleAutoOcr()
            }
        }
    }

    private fun fetch(pos: Int, it: Item): File {
        val dir = File(cacheDir, "img").apply { mkdirs() }
        val f = File(dir, "$pos.bin")
        if (f.isFile && f.length() > 0) return f
        val tmp = File(dir, "$pos.part")
        val c = URL(it.url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            c.inputStream.use { ins -> tmp.outputStream().use { o -> ins.copyTo(o, 64 * 1024) } }
        } finally { c.disconnect() }
        if (!tmp.renameTo(f)) throw IOException("cannot cache the picture")
        return f
    }

    private fun decode(f: File): Pair<Bitmap, String> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("This device cannot decode this picture")
        val w0 = o.outWidth
        val h0 = o.outHeight
        // target scale: long side <= maxDim (also the GPU texture limit) and at most bmBudget bytes per picture
        val k = minOf(1.0, maxDim.toDouble() / maxOf(w0, h0), Math.sqrt(bmBudget / 4.0 / (w0.toDouble() * h0)))
        var s = 1
        while (s * 2 <= 1.0 / k) s *= 2          // cheap power-of-two decode first, never smaller than the target
        var bm: Bitmap? = null
        while (bm == null) {
            try {
                bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                if (bm == null) throw IOException("This device cannot decode this picture")
            } catch (e: OutOfMemoryError) { if (s >= 64) throw e; s *= 2 }
        }
        var b: Bitmap = bm!!
        val tw = maxOf(1, Math.round(w0 * k).toInt())
        val th = maxOf(1, Math.round(h0 * k).toInt())
        if (b.width > tw + tw / 20 || b.height > th + th / 20) {   // power-of-two step overshot the budget: scale down exactly
            val r = Bitmap.createScaledBitmap(b, tw, th, true)
            if (r !== b) b.recycle()
            b = r
        }
        val ori = try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> {}
        }
        val swap = ori == ExifInterface.ORIENTATION_TRANSPOSE || ori == ExifInterface.ORIENTATION_ROTATE_90 ||
            ori == ExifInterface.ORIENTATION_TRANSVERSE || ori == ExifInterface.ORIENTATION_ROTATE_270
        val ow = if (swap) h0 else w0
        val oh = if (swap) w0 else h0
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (r !== b) b.recycle()
            b = r
        }
        return b to "$ow\u00d7$oh"
    }

    // ---------------------------------------------------------------- share
    private val phonePrint by lazy { PhonePrint(this) }

    /** Print button: opens the file manager's printer picker for the picture on screen (this phone's print system, every Wi-Fi printer found, PCs, save as PDF). */
    private fun printCurrent() {
        val it = items.getOrNull(pager.currentItem) ?: return
        if (slide) setSlide(false)
        val u = android.net.Uri.parse(it.url)
        val path = u.getQueryParameter("path") ?: ""
        if (path.isEmpty()) {   // no known location: only the Android print system can take it
            phonePrint.start(org.json.JSONObject().put("items", org.json.JSONArray().put(org.json.JSONObject().put("url", it.url).put("name", it.name))).toString())
            return
        }
        try {
            startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_PRINT_FILES)
                .putExtra(MainActivity.X_DEV, u.getQueryParameter("dev") ?: "local")
                .putExtra(MainActivity.X_PATHS, arrayOf(path))
                .putExtra(MainActivity.X_NAMES, arrayOf(it.name))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) { Lm.snack(this, "Cannot print: " + (e.message ?: "error"), Toast.LENGTH_LONG) }
    }

    /** Rotate the picture on screen by [q] quarter turns (view only; the text layer is hidden while turned because its boxes belong to the unturned picture). */
    private fun turnCurrent(q: Int) { holder(pager.currentItem)?.iv?.rotateBy(q) }

    /** Writes every pending turn into its file: 0.7 s after the last turn, when another page is shown, and on pause. */
    private fun flushTurns() {
        ui.removeCallbacks(flushRun)
        for (p in turned.keys.toList()) if ((turned[p] ?: 0) != 0) saveTurn(p)
    }

    /** Makes the turn of page [pos] permanent: JPEG / WebP = only the EXIF orientation changes (lossless, instant, the gallery thumbnails follow), PNG = pixels turned and saved again (lossless). */
    private fun saveTurn(pos: Int) {
        val q = turned[pos] ?: 0
        if (q == 0 || pos in noSave || pos in saving) return
        val item = items.getOrNull(pos) ?: return
        val u = android.net.Uri.parse(item.url)
        val dev = u.getQueryParameter("dev") ?: "local"
        val path = u.getQueryParameter("path") ?: ""
        if (dev != "local" || path.isEmpty() || path.contains('!')) {
            noSave.add(pos)
            Lm.snack(this, "Turn is for viewing only: this picture is not on this phone", Toast.LENGTH_SHORT)
            return
        }
        saving.add(pos)
        val own = loading.add(pos)       // keeps a reload of this page away while the file and the cached copy change
        savePool.execute {
            var done: File? = null
            var err: String? = null
            try {
                Core.local.checkWritable()
                val t = Core.local.real(path)
                if (!t.isFile) throw IOException("The file is gone")
                val m0 = t.lastModified()
                ImgTurn.apply(t, q)
                t.setLastModified(m0 + 1000L)   // thumbnails are keyed by time + size: a new time makes them redraw; +1 s keeps the picture in place in date order
                val bin = File(File(cacheDir, "img"), "$pos.bin")
                val tmp = File(bin.parentFile, "$pos.turn")
                t.copyTo(tmp, true)             // the viewer's own copy follows the file (Edit reads it)
                if (!tmp.renameTo(bin)) { tmp.copyTo(bin, true); tmp.delete() }
                try { com.lanshare.app.core.IndexManager.changed(t) } catch (_: Throwable) {}
                try { android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(t.path), null, null) } catch (_: Throwable) {}
                done = t
            } catch (e: OutOfMemoryError) { err = "Not enough memory"
            } catch (e: Exception) { err = e.message ?: "error" }
            val fe = done
            val re = err
            ui.post {
                saving.remove(pos); if (own) loading.remove(pos)
                if (isDestroyed) return@post
                if (fe == null) { noSave.add(pos); Lm.snack(this, "Cannot save the turn: " + (re ?: "error"), Toast.LENGTH_LONG); return@post }
                changed = true
                items.getOrNull(pos)?.let { old -> items = items.toMutableList().also { l -> l[pos] = Item(old.name, old.url, fe.length(), fe.lastModified() / 1000, old.key) } }
                val h = holder(pos)?.takeIf { it.pos == pos }
                val nb = h?.iv?.rebase(q)
                if (h != null && nb != null) {      // on screen: what is shown stays as it is, only the base picture is now the turned one
                    cache.put(pos, nb)
                    val rest = h.iv.quarter
                    if (rest == 0) turned.remove(pos) else { turned[pos] = rest; ui.postDelayed(flushRun, 700) }   // turned again while saving
                    h.ov.visibility = if (rest == 0) View.VISIBLE else View.INVISIBLE
                } else {                             // not on screen (or no memory): the page reloads from the turned file
                    cache.remove(pos); turned.remove(pos)
                    if (h != null) { h.iv.setBmp(null); h.show(pos) }
                }
                dims[pos]?.let { d -> val i = d.indexOf('\u00d7'); if (i > 0 && q % 2 != 0) dims[pos] = d.substring(i + 1) + "\u00d7" + d.substring(0, i) }
                ocrRes.remove(pos); ocrOn.remove(pos); ocrNone.remove(pos); ocrBad.remove(pos)   // their boxes belong to the old orientation
                h?.ov?.setLines(null)
                updateOcrBar(); updateTitle(pos); scheduleAutoOcr()
            }
        }
    }

    override fun onPause() { flushTurns(); super.onPause() }

    /** More > To Print space (HANDOVER 9.7 part 3b): stores a copy of the picture on screen in the Print space (this phone, other devices and shares alike). */
    private fun addToPrintSpace() {
        val it = items.getOrNull(pager.currentItem) ?: return
        if (slide) setSlide(false)
        val u = android.net.Uri.parse(it.url)
        val dev = u.getQueryParameter("dev") ?: "local"
        val path = u.getQueryParameter("path") ?: ""
        Lm.snack(this, "Adding to Print space\u2026", Toast.LENGTH_SHORT)
        Thread {
            val msg = try {
                val r = com.lanshare.app.core.PrintSpace.add(listOf(path), dev)
                val sk = r.getJSONArray("skipped")
                if (sk.length() > 0) "Not added: " + sk.getJSONObject(0).optString("why") else "Added to Print space"
            } catch (e: Exception) { "Cannot add: " + (e.message ?: "error") }
            runOnUiThread { if (!isFinishing) Lm.snack(this, msg, Toast.LENGTH_LONG) }
        }.start()
    }

    /** Mirrors Jobs.removeUser: only this phone's own, non-archive files go to the bin; every other device / USB / archive entry is deleted for good. */
    private fun toBin(it: Item): Boolean {
        val u = android.net.Uri.parse(it.url)
        return (u.getQueryParameter("dev") ?: "local") == "local" && com.lanshare.app.core.Bin.canTrash(com.lanshare.app.core.vnorm(u.getQueryParameter("path") ?: ""))
    }

    /** More > Delete: asks, then moves the picture to the Recycle bin (this phone) / deletes it (other devices) and shows the next picture. */
    private fun deleteCurrent() {
        val pos = pager.currentItem
        val it = items.getOrNull(pos) ?: return
        if (slide) setSlide(false)
        AppPal.dlg(this).kind("danger").setTitle("Delete this picture?")
            .setMessage(it.name + "\n\n" + (if (toBin(it)) "It goes to the Recycle bin (kept " + com.lanshare.app.core.Bin.KEEP_DAYS + " days)." else "It is deleted for good. This can't be undone."))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val u = android.net.Uri.parse(it.url)
                val dev = u.getQueryParameter("dev") ?: "local"
                val path = u.getQueryParameter("path") ?: ""
                Thread {
                    try {
                        com.lanshare.app.core.Jobs.removeUser(com.lanshare.app.core.Jobs.ep(dev), com.lanshare.app.core.vnorm(path))
                        runOnUiThread { afterDelete(it) }
                    } catch (e: Exception) {
                        runOnUiThread { Lm.snack(this, "Cannot delete: " + (e.message ?: "error"), Toast.LENGTH_LONG) }
                    }
                }.start()
            }.show()
    }

    private fun afterDelete(gone: Item) {
        changed = true
        val i = items.indexOfFirst { x -> x === gone }
        if (i < 0) return
        val left = items.toMutableList().also { l -> l.removeAt(i) }
        if (left.isEmpty()) { finish(); return }
        install(left, minOf(i, left.size - 1))
    }

    private fun share() {
        val pos = pager.currentItem
        val it = items.getOrNull(pos) ?: return
        val src = File(File(cacheDir, "img"), "$pos.bin")
        if (!src.isFile) { Lm.snack(this, "Still loading...", Toast.LENGTH_SHORT); return }
        Thread {
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }   // the FileProvider path the app already exposes
                val dst = File(dir, it.name.replace('/', '_').ifEmpty { "image.jpg" })
                src.copyTo(dst, true)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
                val send = Intent(Intent.ACTION_SEND).setType(mimeFor(dst.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            } catch (e: Exception) {
                runOnUiThread { Lm.snack(this, "Cannot share: " + (e.message ?: "error"), Toast.LENGTH_LONG) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- select text (OCR)
    /** "T" button: shows the text layer of the current picture (already read in the background, else reads it now); next press hides it. */
    private fun toggleOcr() {
        val pos = pager.currentItem
        if (pos in ocrOn) { closeOcr(); return }
        if (slide) setSlide(false)
        val have = ocrRes[pos]
        if (have != null) {
            if (have.isEmpty()) Lm.snack(this, "No text found", Toast.LENGTH_SHORT) else showOcr(pos, have)
            return
        }
        val bm = cache.get(pos)
        if (bm == null) { Lm.snack(this, "Still loading...", Toast.LENGTH_SHORT); return }
        ocrBad.remove(pos); ocrFails = 0
        ocrWant = pos                                  // shown as soon as it is read
        if (ocrWork < 0) startOcr(pos, bm)             // else it is busy: with this page (just wait) or a neighbour (this page goes first next)
        updateOcrBar()
    }

    // ---- background reading: current picture first, then the next and the previous one; results are cached on disk
    private fun scheduleAutoOcr() { ui.removeCallbacks(autoRun); ui.postDelayed(autoRun, if (ocrWant >= 0) 0L else 700L) }   // no delay while the user waits

    private fun autoOcr() {
        if (isDestroyed || slide || ocrWork >= 0 || ocrFails >= 2) return
        val cur = pager.currentItem
        for (p in intArrayOf(cur, cur + 1, cur - 1)) {
            if (p !in items.indices || ocrRes.containsKey(p) || p in ocrBad) continue
            val bm = cache.get(p) ?: continue
            startOcr(p, bm)
            return
        }
    }

    private fun startOcr(p: Int, bm: Bitmap) {
        val item = items.getOrNull(p) ?: return
        val saved = ocrLoad(p, bm)
        if (saved != null) { ocrDone(p, saved); return }
        ocrWork = p
        updateOcrBar()
        val my = gen
        ocr.recognize(bm) { res ->
            if (my != gen || isDestroyed) return@recognize
            ocrWork = -1
            if (items.getOrNull(p) !== item) { scheduleAutoOcr(); return@recognize }   // the picture was replaced meanwhile
            res.onSuccess { lines -> ocrFails = 0; ocrSave(p, bm, lines); ocrDone(p, lines) }
                .onFailure { e ->
                    ocrBad.add(p); ocrFails++
                    if (ocrWant == p) { ocrWant = -1; Lm.snack(this, e.message ?: "Cannot read the text", Toast.LENGTH_LONG) }
                    updateOcrBar()
                }
            scheduleAutoOcr()
        }
    }

    private fun ocrDone(p: Int, lines: List<OcrLine>) {
        ocrRes[p] = lines
        if (lines.isEmpty()) ocrNone.add(p)
        if (ocrWant == p) {
            ocrWant = -1
            if (lines.isEmpty()) Lm.snack(this, "No text found", Toast.LENGTH_SHORT) else showOcr(p, lines)
        }
        updateOcrBar()
        scheduleAutoOcr()
    }

    // ---- disk cache of recognised text (cacheDir/ocr, one small JSON per picture, kept 30 days; valid only for the same decoded size)
    private fun ocrFile(p: Int): File {
        val it = items[p]
        val key = it.url.substringAfter('?', it.url) + "|" + it.size
        return File(File(cacheDir, "ocr").apply { mkdirs() }, java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString() + ".json")
    }

    private fun boxArr(r: RectF) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())
    private fun arrBox(a: JSONArray) = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())

    private fun ocrSave(p: Int, bm: Bitmap, lines: List<OcrLine>) {
        val f = try { ocrFile(p) } catch (e: Exception) { return }
        val w = bm.width
        val h = bm.height
        pool.execute {
            try {
                val ls = JSONArray()
                for (l in lines) {
                    val wa = JSONArray()
                    for (x in l.words) wa.put(JSONObject().put("t", x.text).put("b", boxArr(x.box)))
                    ls.put(JSONObject().put("t", l.text).put("b", boxArr(l.box)).put("w", wa))
                }
                f.writeText(JSONObject().put("w", w).put("h", h).put("l", ls).toString())
            } catch (_: Exception) {}
        }
    }

    private fun ocrLoad(p: Int, bm: Bitmap): List<OcrLine>? {
        try {
            val f = ocrFile(p)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            if (o.getInt("w") != bm.width || o.getInt("h") != bm.height) return null
            val ls = o.getJSONArray("l")
            val out = ArrayList<OcrLine>()
            for (i in 0 until ls.length()) {
                val l = ls.getJSONObject(i)
                val wa = l.getJSONArray("w")
                val ws = ArrayList<OcrWord>()
                for (j in 0 until wa.length()) { val x = wa.getJSONObject(j); ws.add(OcrWord(x.getString("t"), arrBox(x.getJSONArray("b")))) }
                out.add(OcrLine(l.getString("t"), arrBox(l.getJSONArray("b")), ws))
            }
            return out
        } catch (e: Exception) { return null }
    }

    private fun pruneOcr() {
        try {
            val lim = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            File(cacheDir, "ocr").listFiles()?.forEach { f -> if (f.lastModified() < lim) f.delete() }
        } catch (_: Exception) {}
    }

    private fun showOcr(pos: Int, lines: List<OcrLine>) {
        if (lines.isEmpty()) return
        ocrOn.add(pos)
        holder(pos)?.applyOcr(lines)
        updateOcrBar()
    }

    private fun closeOcr() {
        val pos = pager.currentItem
        ocrOn.remove(pos)
        holder(pos)?.ov?.setLines(null)
        updateOcrBar()
    }

    private fun updateOcrBar() {
        val ov = holder(pager.currentItem)?.ov
        val on = pager.currentItem in ocrOn && ov != null && ov.hasLines()
        selBar.visibility = View.GONE   // bottom bar removed: the floating Copy / Search / Share bubble is the only menu
        ocrBtn.tint = if (on) LumenGrad.onDark(Lumen.of(this)) else Color.WHITE   // theme primary on the black stage (was fixed blue)
        val cp = pager.currentItem
        ocrBtn.alpha = when {                                   // T: bright = text found / layer on, dim = no text, half = reading
            cp in ocrOn -> 1f
            ocrRes[cp]?.isNotEmpty() == true -> 1f
            cp in ocrNone -> 0.35f
            ocrWork == cp -> 0.5f
            else -> 0.8f
        }
        copyBtn.text = if (ov != null && ov.hasSelection()) "Copy" else "Copy all"
    }

    private fun ocrText(): String? { val ov = holder(pager.currentItem)?.ov ?: return null; return ov.selectedText() ?: ov.allText() }

    private fun copyOcr() {
        val t = ocrText() ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", t))
        Lm.snack(this, "Copied", Toast.LENGTH_SHORT)
    }

    /** Opens the selected text (else all text, cut to 200 chars) as a web search in the browser. */
    private fun searchOcr() {
        val t = (ocrText() ?: return).replace(Regex("\\s+"), " ").trim().take(200)
        if (t.isEmpty()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(t)))) }
        catch (e: Exception) { Lm.snack(this, "No browser found", Toast.LENGTH_SHORT) }
    }

    private fun shareOcr() {
        val t = ocrText() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share text"))
    }

    // ---------------------------------------------------------------- düzenle (döndür / kırp / boyutlandır)
    private fun edit() {
        val pos = pager.currentItem
        val it = items.getOrNull(pos) ?: return
        val src = File(File(cacheDir, "img"), "$pos.bin")
        if (pos !in noSave && ((turned[pos] ?: 0) != 0 || pos in saving)) { flushTurns(); Lm.snack(this, "Saving the turn, try again in a moment", Toast.LENGTH_SHORT); return }
        if (!src.isFile) { Lm.snack(this, "Still loading...", Toast.LENGTH_SHORT); return }
        if (slide) setSlide(false)
        val u = android.net.Uri.parse(it.url)
        startActivityForResult(
            Intent(this, ImageEditActivity::class.java)
                .putExtra(ImageEditActivity.X_SRC, src.path)
                .putExtra(ImageEditActivity.X_DEV, u.getQueryParameter("dev") ?: "local")
                .putExtra(ImageEditActivity.X_PATH, u.getQueryParameter("path") ?: "")
                .putExtra(ImageEditActivity.X_NAME, it.name), REQ_EDIT)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_EDIT || res != RESULT_OK || data == null || !data.getBooleanExtra(ImageEditActivity.R_REPLACED, false)) return
        // özgün dosyanın üstüne yazıldı: bu sayfanın önbelleğini at, yeniden yükle
        changed = true
        val pos = pager.currentItem
        val old = items.getOrNull(pos) ?: return
        items = items.toMutableList().also { l -> l[pos] = Item(old.name, old.url, data.getLongExtra(ImageEditActivity.R_SIZE, old.size)) }
        File(File(cacheDir, "img"), "$pos.bin").delete()
        turned.remove(pos)
        cache.remove(pos); failed.remove(pos); loading.remove(pos); dims.remove(pos); ocrRes.remove(pos); ocrOn.remove(pos); ocrNone.remove(pos); ocrBad.remove(pos)
        holder(pos)?.let { h -> h.ov.setLines(null); h.iv.setBmp(null); h.show(pos) }
        updateOcrBar()
        updateTitle(pos)
    }

    // ---------------------------------------------------------------- pager pages
    private inner class Pg(val root: FrameLayout, val iv: ZoomImageView, val ov: OcrOverlayView, val spin: ProgressBar, val err: TextView) : RecyclerView.ViewHolder(root) {
        var pos = -1
        /** Text layer on this page: lines are in pixels of the bitmap [iv] shows. */
        fun applyOcr(lines: List<OcrLine>) { ov.setLines(lines); ov.setImageMatrix(iv.matrixNow()) }
        fun show(p: Int) {
            if (p != pos) return
            val bm = cache.get(p)
            val e = failed[p]
            if (bm != null) { iv.setBmp(bm); val tq = turned[p] ?: 0; if (tq != iv.quarter) iv.setQuarter(tq); ov.visibility = if (tq == 0) View.VISIBLE else View.INVISIBLE } else if (e != null) iv.setBmp(null)   // evicted from the cache but still on screen: keep showing it
            if (bm != null && p in ocrOn && !ov.hasLines()) ocrRes[p]?.let { applyOcr(it) }   // page came back from the recycler: restore its text layer
            val wait = !iv.hasBmp() && e == null
            spin.visibility = if (wait) View.VISIBLE else View.GONE
            err.text = e ?: ""
            err.visibility = if (e != null) View.VISIBLE else View.GONE
            if (wait) request(p)      // nothing to show and nothing coming: (re)load, so a page can never stay black
        }
    }

    private inner class Ad : RecyclerView.Adapter<Pg>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Pg {
            val c = parent.context
            val root = FrameLayout(c).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val iv = ZoomImageView(c)
            val ov = OcrOverlayView(c)
            iv.onTap = { if (ov.hasSelection()) ov.clearSelection() else toggleUi() }
            iv.onMatrixChanged = { m -> ov.setImageMatrix(m) }
            iv.onRotated = { q -> val hp = holder(pager.currentItem); if (hp?.iv === iv) { turned[hp.pos] = q; ov.visibility = if (q == 0) View.VISIBLE else View.INVISIBLE; ui.removeCallbacks(flushRun); ui.postDelayed(flushRun, 700) } }
            iv.onTwist = { on -> if (on) ov.visibility = View.INVISIBLE else if (iv.quarter == 0) ov.visibility = View.VISIBLE }   // the text layer does not follow a free twist
            iv.onHold = { x, y ->   // long press works without the T button: the text read in the background is switched on by the press itself
                val p = pager.currentItem
                var opened = false
                if (!ov.hasLines() && holder(p)?.ov === ov) {
                    val have = ocrRes[p]
                    if (have != null && have.isNotEmpty()) { showOcr(p, have); opened = true }
                    else if (have == null) Lm.snack(this@ImageViewerActivity, "Reading text...", Toast.LENGTH_SHORT)
                }
                val hit = ov.hasLines() && ov.selectWordAt(x, y)
                if (!hit && opened) closeOcr()   // nothing under the finger: do not leave the boxes on
                hit
            }
            ov.onSelection = { _ -> updateOcrBar() }
            ov.onAction = { a -> when (a) { "copy" -> copyOcr(); "search" -> searchOcr(); "share" -> shareOcr() } }   // the floating Copy / Search / Share bubble
            val spin = ProgressBar(c).apply { layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER) }
            val err = TextView(c).apply {
                setTextColor(Color.WHITE); LumenTypography.fit(this, 16f); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(spin); root.addView(err)
            return Pg(root, iv, ov, spin, err)
        }
        override fun onBindViewHolder(h: Pg, position: Int) { if (h.pos != position) { h.iv.setBmp(null); h.ov.setLines(null) }; h.pos = position; h.show(position); request(position) }
        // RecyclerView re-attaches cached pages WITHOUT calling bind: this is where a page that finished loading while it was off-screen gets its picture
        override fun onViewAttachedToWindow(h: Pg) { if (h.pos >= 0) h.show(h.pos) }
        override fun onViewRecycled(h: Pg) { h.pos = -1; h.iv.setBmp(null); h.ov.setLines(null) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !uiOn) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() { super.onResume(); ModelLinks.showIfAny(this); armAutoHide() }

    override fun onDestroy() {
        NlHero.release(this)
        gen++
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
        ocr.close()
        cache.evictAll()
        File(cacheDir, "img").deleteRecursively()
        super.onDestroy()
    }
}

/** ImageView with pinch zoom, double-tap zoom and drag; hands the drag to the pager when the picture is not zoomed or at its edge. */
internal class ZoomImageView(c: Context) : ImageView(c) {
    private val mx = Matrix()
    private val v = FloatArray(9)
    private var bw = 0
    private var bh = 0
    private var fit = 1f
    var onTap: (() -> Unit)? = null
    /** Long press at view coordinates; return true if it was used (text selection) - gives a haptic tick. */
    var onHold: ((Float, Float) -> Boolean)? = null
    /** Image px -> view px matrix changed (zoom / pan / new picture / resize). */
    var onMatrixChanged: ((Matrix) -> Unit)? = null
    fun matrixNow() = Matrix(mx)
    private var held = false          // long press was used for text selection: ignore the rest of this touch (no accidental pan)

    init { scaleType = ScaleType.MATRIX }

    private var cur: Bitmap? = null
    fun hasBmp() = cur != null

    private var orig: Bitmap? = null        // the picture as decoded; [cur] is its turned copy while [quarter] != 0
    /** Quarter turns (0..3, clockwise) applied for viewing. */
    var quarter = 0; private set
    /** Fired after the user turned the picture (buttons or two-finger twist), with the new [quarter]. */
    var onRotated: ((Int) -> Unit)? = null

    fun setBmp(b: Bitmap?) {
        if (b != null && b === orig) return     // same picture again: keep the current zoom and turn
        val g = snap; snap = null; animating = false; twOn = false; twDeg = 0f; g?.cancel()     // snap is cleared first: the end listener then does nothing
        orig = b; quarter = 0; cur = b
        if (b == null) { setImageDrawable(null); bw = 0; bh = 0; return }
        setImageBitmap(b); bw = b.width; bh = b.height; reset()
    }

    /** Shows the picture turned to [q] quarter turns (no callback). */
    fun setQuarter(q: Int) {
        val o = orig ?: return
        val n = ((q % 4) + 4) % 4
        if (n == quarter) return
        val b = if (n == 0) o else try { Bitmap.createBitmap(o, 0, 0, o.width, o.height, Matrix().apply { postRotate(n * 90f) }, true) } catch (_: OutOfMemoryError) { return }
        quarter = n; cur = b
        setImageBitmap(b); bw = b.width; bh = b.height; reset()
    }

    fun rotateBy(d: Int) { if (orig == null || animating) return; setQuarter(quarter + d); onRotated?.invoke(quarter) }

    /** The file was turned by [q] quarter turns: [orig] becomes the picture as the file now shows it. What is on screen (same pixels, same matrix) does not change, so nothing flickers. Returns the new base picture, null when it could not be made. */
    fun rebase(q: Int): Bitmap? {
        val o = orig ?: return null
        val k = ((q % 4) + 4) % 4
        if (k == 0) return o
        val nb: Bitmap = if (k == quarter) (cur ?: return null)
            else try { Bitmap.createBitmap(o, 0, 0, o.width, o.height, Matrix().apply { postRotate(k * 90f) }, true) } catch (_: OutOfMemoryError) { return null }
        orig = nb; quarter = ((quarter - k) % 4 + 4) % 4
        return nb
    }

    // two-finger twist: the picture follows the fingers INSIDE the image matrix (the view itself never turns: a turned full-screen view
    // clipped the picture to a tilted rectangle and, when the finger lifted, jumped to the new bitmap = the flicker); on release it
    // glides to the nearest multiple of 90 degrees AND to the fit of the turned picture, then the turned bitmap replaces it with the same matrix
    private var twLast = 0f
    private var twist = 0f
    private var twOn = false
    private var twDeg = 0f                  // angle drawn on top of [mx], about the view centre
    private var animating = false           // the release glide is running: touches are ignored
    private var snap: ValueAnimator? = null
    /** true when a twist starts showing, false when it is over (the text layer is hidden meanwhile). */
    var onTwist: ((Boolean) -> Unit)? = null
    private fun twAngle(e: MotionEvent) = Math.toDegrees(Math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
    /** Small twitches of a pinch do not turn it: 0 up to 15 degrees, then a smooth ramp that joins the finger angle at 30 (no jump). */
    private fun twEase(t: Float): Float { val a = Math.abs(t); val e = if (a <= 15f) 0f else if (a <= 30f) (a - 15f) * 2f else a; return if (t < 0f) -e else e }
    private fun push() { imageMatrix = if (twDeg == 0f) mx else Matrix(mx).apply { postRotate(twDeg, width / 2f, height / 2f) } }
    private fun twistEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { twOn = true; twist = 0f; twLast = twAngle(e) }
            MotionEvent.ACTION_MOVE -> if (twOn && e.pointerCount == 2) {
                val a = twAngle(e); var d = a - twLast
                if (d > 180f) d -= 360f else if (d < -180f) d += 360f
                twLast = a; twist += d
                val n = twEase(twist)
                if (n != twDeg) { if (twDeg == 0f) onTwist?.invoke(true); twDeg = n; push() }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (twOn) {
                twOn = false
                val q = Math.round(twist / 90f); twist = 0f
                if (twDeg != 0f || q != 0) snapTo(q)
            }
        }
    }

    private fun snapTo(q0: Int) {
        val o = orig; val c0 = cur
        if (o == null || c0 == null) { twDeg = 0f; push(); onTwist?.invoke(false); return }
        var q = q0
        val n = ((quarter + q) % 4 + 4) % 4
        // the turned copy is made BEFORE the glide, so nothing heavy happens at its end
        var nb: Bitmap? = null
        if (q != 0) {
            nb = if (n == 0) o else try { Bitmap.createBitmap(o, 0, 0, o.width, o.height, Matrix().apply { postRotate(n * 90f) }, true) } catch (_: OutOfMemoryError) { null }
            if (nb == null) q = 0       // no memory: spring back instead
        }
        val cx = bw / 2f; val cy = bh / 2f
        mx.getValues(v); val s0 = v[Matrix.MSCALE_X]
        val a0 = twDeg
        val p0 = floatArrayOf(cx, cy).also { Matrix(mx).apply { postRotate(twDeg, width / 2f, height / 2f) }.mapPoints(it) }
        val a1: Float; val s1: Float; val x1: Float; val y1: Float
        if (q == 0) { a1 = 0f; s1 = s0; val p1 = floatArrayOf(cx, cy).also { mx.mapPoints(it) }; x1 = p1[0]; y1 = p1[1] }
        else { val b = nb!!; a1 = q * 90f; s1 = minOf(width / b.width.toFloat(), height / b.height.toFloat()); x1 = width / 2f; y1 = height / 2f }
        val m = Matrix()
        val qF = q; val nbF = nb
        animating = true
        val g = snap; snap = null; g?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        snap = anim      // set BEFORE start: with animations switched off start() ends at once
        anim.apply {
            duration = 180; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { an ->
                val f = an.animatedValue as Float
                val s = s0 + (s1 - s0) * f
                m.setTranslate(-cx, -cy); m.postScale(s, s); m.postRotate(a0 + (a1 - a0) * f)
                m.postTranslate(p0[0] + (x1 - p0[0]) * f, p0[1] + (y1 - p0[1]) * f)
                imageMatrix = m
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) {
                    if (snap == null) return      // cancelled from outside (new picture): nothing to finish
                    snap = null; animating = false; twDeg = 0f
                    if (qF != 0 && nbF != null) {      // same picture, same matrix as the last frame: the swap cannot be seen
                        quarter = n; cur = nbF
                        setImageBitmap(nbF); bw = nbF.width; bh = nbF.height; reset()
                        onRotated?.invoke(quarter)
                    } else { push(); onMatrixChanged?.invoke(mx) }
                    onTwist?.invoke(false)
                }
            })
            start()
        }
    }

    private fun reset() {
        if (bw == 0 || width == 0 || height == 0) return
        fit = minOf(width / bw.toFloat(), height / bh.toFloat())
        mx.setScale(fit, fit)
        mx.postTranslate((width - bw * fit) / 2f, (height - bh * fit) / 2f)
        twDeg = 0f
        imageMatrix = mx
        onMatrixChanged?.invoke(mx)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); reset() }

    private fun scale(): Float { mx.getValues(v); return v[Matrix.MSCALE_X] }
    private fun zoomed() = scale() > fit * 1.02f

    private fun clamp() {
        mx.getValues(v)
        val iw = bw * v[Matrix.MSCALE_X]
        val ih = bh * v[Matrix.MSCALE_X]
        v[Matrix.MTRANS_X] = if (iw <= width) (width - iw) / 2f else v[Matrix.MTRANS_X].coerceIn(width - iw, 0f)
        v[Matrix.MTRANS_Y] = if (ih <= height) (height - ih) / 2f else v[Matrix.MTRANS_Y].coerceIn(height - ih, 0f)
        mx.setValues(v)
        push()
        onMatrixChanged?.invoke(mx)
    }

    private fun zoomTo(target: Float, fx: Float, fy: Float, animate: Boolean) {
        val from = scale()
        val to = target.coerceIn(fit, fit * 8f)
        if (!animate) { val f = to / from; mx.postScale(f, f, fx, fy); clamp(); return }
        ValueAnimator.ofFloat(from, to).apply {
            duration = 200
            addUpdateListener { a -> val s = a.animatedValue as Float; val f = s / scale(); mx.postScale(f, f, fx, fy); clamp() }
            start()
        }
    }

    private val sd = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = scale()
            val n = (s * d.scaleFactor).coerceIn(fit * 0.8f, fit * 8f)
            val f = n / s
            mx.postScale(f, f, d.focusX, d.focusY)
            clamp()
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { if (scale() < fit) zoomTo(fit, width / 2f, height / 2f, true) }
    })

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { if (onHold?.invoke(e.x, e.y) == true) { held = true; Haptics.play("long") } }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) zoomTo(fit, e.x, e.y, true) else zoomTo(maxOf(fit * 2.5f, minOf(width / bw.toFloat(), 1f)), e.x, e.y, true)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (held) return true
            if (!zoomed()) { parent?.requestDisallowInterceptTouchEvent(false); return false }
            mx.getValues(v)
            val s = v[Matrix.MSCALE_X]
            val tx = v[Matrix.MTRANS_X]
            val canX = if (dx > 0) tx > width - bw * s + 1f else tx < -1f   // can the picture still move sideways in this direction?
            parent?.requestDisallowInterceptTouchEvent(canX || Math.abs(dy) > Math.abs(dx))
            mx.postTranslate(-dx, -dy)
            clamp()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bw == 0) return super.onTouchEvent(e)
        if (animating) return true
        twistEvent(e)
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { held = false; parent?.requestDisallowInterceptTouchEvent(zoomed()) }
        if (held) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

/** Flat vector icon button for the viewer's top bar (Material icon paths in a 24 x 24 box, tinted, round pressed highlight). */
internal class IconBtn(c: Context, d: String) : View(c) {
    companion object {
        const val BACK = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
        const val SHARE = "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"
        const val EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
        const val MORE = "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
        const val TEXT = "M2.5 4v3h5v12h3V7h5V4h-13zm19 5h-9v3h3v7h3v-7h3V9z"
        /** Lumen Icons "scan" (outline, 24 grid, 1.75 stroke): the "S:" prefix draws the path as a stroke instead of a fill. */
        val SCAN: String = "S:" + (LumenIcons.D["scan"] ?: "")
        const val ROTATE = "M15.55 5.55L11 1v3.07C7.06 4.56 4 7.92 4 12s3.05 7.44 7 7.93v-2.02c-2.84-.48-5-2.94-5-5.91s2.16-5.43 5-5.91V10l4.55-4.45zM19.93 11c-.17-1.39-.72-2.73-1.62-3.89l-1.42 1.42c.54.75.88 1.6 1.02 2.47h2.02zM13 17.9v2.02c1.39-.17 2.74-.72 3.9-1.61l-1.44-1.44c-.75.54-1.59.89-2.46 1.03zm3.89-2.42l1.42 1.41c.9-1.16 1.45-2.5 1.62-3.89h-2.02c-.14.87-.48 1.72-1.02 2.48z"
        const val PRINT = "M19 8H5c-1.66 0-3 1.34-3 3v6h4v4h12v-4h4v-6c0-1.66-1.34-3-3-3zm-3 11H8v-5h8v5zm3-7c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm-1-9H6v4h12V3z"
    }
    private val dn = resources.displayMetrics.density
    private val stroked = d.startsWith("S:")
    private val path: Path = PathParser.createPathFromPathData(d.removePrefix("S:")) ?: Path()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (stroked) { style = Paint.Style.STROKE; strokeWidth = LumenIcons.SW; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } else style = Paint.Style.FILL
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    var tint = Color.WHITE
        set(v) { if (v != field) { field = v; invalidate() } }

    init { isClickable = true; isFocusable = true }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (isPressed) canvas.drawCircle(w / 2, h / 2, minOf(w, h) / 2f - 2f * dn, glow)
        ink.color = tint
        ink.setShadowLayer(2.5f * dn, 0f, 1f * dn, 0x80000000.toInt())      // keeps the glyph readable over bright pictures
        canvas.save()
        canvas.translate((w - 24f * dn) / 2f, (h - 24f * dn) / 2f)
        canvas.scale(dn, dn)
        canvas.drawPath(path, ink)
        canvas.restore()
    }
}

/** Makes a picture turn permanent (viewer rotate): JPEG / WebP = EXIF orientation only (lossless, nothing is re-encoded), PNG = pixels turned and saved again losslessly. */
internal object ImgTurn {
    private fun rot(k: Int) = when (k and 3) { 0 -> intArrayOf(1, 0, 0, 1); 1 -> intArrayOf(0, -1, 1, 0); 2 -> intArrayOf(-1, 0, 0, -1); else -> intArrayOf(0, 1, -1, 0) }
    private fun mul(a: IntArray, b: IntArray) = intArrayOf(a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3], a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3])   // a after b
    private val flipX = intArrayOf(-1, 0, 0, 1)
    /** Raw pixels -> display, as a 2 x 2 matrix, for the EXIF orientation values 1..8 (same table as the decoders use). */
    private fun mat(o: Int) = when (o) {
        2 -> flipX
        3 -> rot(2)
        4 -> mul(flipX, rot(2))
        5 -> mul(flipX, rot(1))
        6 -> rot(1)
        7 -> mul(flipX, rot(3))
        8 -> rot(3)
        else -> rot(0)
    }
    /** The orientation value that shows the picture [n] clockwise quarter turns further than [o] does. */
    fun compose(o: Int, n: Int): Int {
        val t = mul(rot(n), mat(o))
        for (c in 1..8) if (mat(c).contentEquals(t)) return c
        return 1
    }

    fun apply(f: File, q: Int) {
        val n = ((q % 4) + 4) % 4
        if (n == 0) return
        when (f.name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "webp" -> {
                val ex = ExifInterface(f.path)
                val o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ex.setAttribute(ExifInterface.TAG_ORIENTATION, compose(o, n).toString())
                ex.saveAttributes()
            }
            "png" -> {
                val bm = BitmapFactory.decodeFile(f.path) ?: throw IOException("Cannot read the picture")
                val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(n * 90f) }, true)
                if (r !== bm) bm.recycle()
                val tmp = File(f.parentFile, f.name + ".lspart")
                try {
                    tmp.outputStream().use { o -> if (!r.compress(Bitmap.CompressFormat.PNG, 100, o)) throw IOException("cannot encode the picture") }
                } finally { r.recycle() }
                if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
            }
            else -> throw IOException("Turning is saved for JPEG, WebP and PNG pictures only")
        }
    }
}
