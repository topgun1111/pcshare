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
    }

    private class Item(val name: String, val url: String, val size: Long, val mtime: Long = 0L)

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
    private val ocr by lazy { MlKitOcr(applicationContext).also { it.onStatus = { m -> if (ocrWant >= 0 && ocrWant == ocrWork) Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } } }   // toasts only while the user waits for T
    private val ocrRes = HashMap<Int, List<OcrLine>>()   // UI thread only: recognised lines per page (coordinates of the displayed bitmap)
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

    override fun attachBaseContext(b: Context) = super.attachBaseContext(UiScale.wrap(b))

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
                override fun onPageSelected(position: Int) { updateTitle(position); holder(position)?.show(position); prefetch(position); ocrWant = -1; updateOcrBar(); scheduleAutoOcr() }
                override fun onPageScrollStateChanged(state: Int) { if (state == ViewPager2.SCROLL_STATE_DRAGGING && slide) setSlide(false) }
            })
        }
        root.addView(pager)

        // top bar: back, file name + date, then three icon actions and a menu (slideshow, details). The position counter lives in a pill at the bottom.
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x66000000, 0x00000000))
            setPadding(dp(4), dp(6), dp(4), dp(28))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // details band: sits right under the top bar and spans the full width, so nothing is cut off with "..." (the dark space around the picture is used for it)
        infoTv = TextView(this).apply {
            setTextColor(0xE6FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setLineSpacing(0f, 1.15f)
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
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) }
        }
        val back = icon(IconBtn.BACK, "Back") { finish() }.apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) }
        titleTv = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }
        subTv = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt()); setPadding(0, dp(1), 0, 0)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, dp(8), 0); addView(titleTv); addView(subTv) }
        ocrBtn = icon(IconBtn.TEXT, "Text on this picture") { toggleOcr() }
        val edit = icon(IconBtn.EDIT, "Edit") { edit() }
        val share = icon(IconBtn.SHARE, "Share") { share() }
        lateinit var more: IconBtn
        more = icon(IconBtn.MORE, "More") {
            PopupMenu(this, more).apply {
                menu.add(0, 1, 0, if (slide) "Stop slideshow" else "Slideshow")
                menu.add(0, 2, 1, "Details")
                setOnMenuItemClickListener { mi -> when (mi.itemId) { 1 -> { setSlide(!slide); true }; 2 -> { showDetails(pager.currentItem); true }; else -> false } }
            }.show()
        }
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ocrBtn); top.addView(edit); top.addView(share); top.addView(more)
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(4) + b.left, dp(6) + b.top, dp(4) + b.right, dp(28))
            infoTv.setPadding(dp(16) + b.left, dp(8), dp(16) + b.right, dp(8))
            insets
        }
        root.addView(topWrap)

        countPill = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
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
            text = txt; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
        }
        // bar shown while a picture's text layer is on: Copy (selection, else all text) / Select all / Share / Close
        fun barBtn(txt: String, act: () -> Unit) = tv(txt, 15f).apply { setOnClickListener { act() } }
        copyBtn = barBtn("Copy all") { copyOcr() }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xE6202124.toInt()); cornerRadius = dp(20).toFloat() }
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
    }

    private fun toggleUi() {
        uiOn = !uiOn
        topWrap.visibility = if (uiOn) View.VISIBLE else View.GONE
        countPill.visibility = if (uiOn) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiOn) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

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
        android.app.AlertDialog.Builder(this).setTitle("Details").setMessage(rows.joinToString("\n\n")).setPositiveButton("Close", null).show()
    }

    // ---------------------------------------------------------------- start
    private fun begin() {
        val json = pending
        pending = null
        val parsed = try { if (json == null) null else parse(json) } catch (_: Exception) { null }
        if (parsed == null || parsed.first.isEmpty()) { finish(); return }
        gen++
        loading.clear(); failed.clear(); dims.clear(); cache.evictAll(); ocrRes.clear(); ocrOn.clear(); ocrNone.clear(); ocrBad.clear(); ocrWork = -1; ocrWant = -1; ocrFails = 0
        ui.removeCallbacks(autoRun)
        selBar.visibility = View.GONE
        File(cacheDir, "img").deleteRecursively()
        items = parsed.first
        pager.adapter = Ad()
        pager.setCurrentItem(parsed.second, false)
        updateTitle(parsed.second)
        prefetch(parsed.second)
    }

    private fun parse(json: String): Pair<List<Item>, Int> {
        val o = JSONObject(json)
        val arr = o.getJSONArray("items")
        val out = ArrayList<Item>()
        for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
            val it = arr.getJSONObject(i)
            val url = it.getString("url")
            if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))   // only the app's own server
                out.add(Item(it.optString("name"), url, it.optLong("size", 0L), it.optLong("mtime", 0L)))
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
    private fun share() {
        val pos = pager.currentItem
        val it = items.getOrNull(pos) ?: return
        val src = File(File(cacheDir, "img"), "$pos.bin")
        if (!src.isFile) { Toast.makeText(this, "Still loading...", Toast.LENGTH_SHORT).show(); return }
        Thread {
            try {
                val dir = File(cacheDir, "open").apply { deleteRecursively(); mkdirs() }   // the FileProvider path the app already exposes
                val dst = File(dir, it.name.replace('/', '_').ifEmpty { "image.jpg" })
                src.copyTo(dst, true)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
                val send = Intent(Intent.ACTION_SEND).setType(mimeFor(dst.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Cannot share: " + (e.message ?: "error"), Toast.LENGTH_LONG).show() }
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
            if (have.isEmpty()) Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show() else showOcr(pos, have)
            return
        }
        val bm = cache.get(pos)
        if (bm == null) { Toast.makeText(this, "Still loading...", Toast.LENGTH_SHORT).show(); return }
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
                    if (ocrWant == p) { ocrWant = -1; Toast.makeText(this, e.message ?: "Cannot read the text", Toast.LENGTH_LONG).show() }
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
            if (lines.isEmpty()) Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show() else showOcr(p, lines)
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
        ocrBtn.tint = if (on) 0xFF8AB4F8.toInt() else Color.WHITE
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
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    /** Opens the selected text (else all text, cut to 200 chars) as a web search in the browser. */
    private fun searchOcr() {
        val t = (ocrText() ?: return).replace(Regex("\\s+"), " ").trim().take(200)
        if (t.isEmpty()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(t)))) }
        catch (e: Exception) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
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
        if (!src.isFile) { Toast.makeText(this, "Still loading...", Toast.LENGTH_SHORT).show(); return }
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
        val pos = pager.currentItem
        val old = items.getOrNull(pos) ?: return
        items = items.toMutableList().also { l -> l[pos] = Item(old.name, old.url, data.getLongExtra(ImageEditActivity.R_SIZE, old.size)) }
        File(File(cacheDir, "img"), "$pos.bin").delete()
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
            if (bm != null) iv.setBmp(bm) else if (e != null) iv.setBmp(null)   // evicted from the cache but still on screen: keep showing it
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
            iv.onHold = { x, y ->   // long press works without the T button: the text read in the background is switched on by the press itself
                val p = pager.currentItem
                var opened = false
                if (!ov.hasLines() && holder(p)?.ov === ov) {
                    val have = ocrRes[p]
                    if (have != null && have.isNotEmpty()) { showOcr(p, have); opened = true }
                    else if (have == null) Toast.makeText(this@ImageViewerActivity, "Reading text...", Toast.LENGTH_SHORT).show()
                }
                val hit = ov.hasLines() && ov.selectWordAt(x, y)
                if (!hit && opened) closeOcr()   // nothing under the finger: do not leave the boxes on
                hit
            }
            ov.onSelection = { _ -> updateOcrBar() }
            ov.onAction = { a -> when (a) { "copy" -> copyOcr(); "search" -> searchOcr(); "share" -> shareOcr() } }   // the floating Copy / Search / Share bubble
            val spin = ProgressBar(c).apply { layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER) }
            val err = TextView(c).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
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

    override fun onDestroy() {
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

    fun setBmp(b: Bitmap?) {
        if (b === cur) return                   // same picture again: keep the current zoom
        cur = b
        if (b == null) { setImageDrawable(null); bw = 0; bh = 0; return }
        setImageBitmap(b); bw = b.width; bh = b.height; reset()
    }

    private fun reset() {
        if (bw == 0 || width == 0 || height == 0) return
        fit = minOf(width / bw.toFloat(), height / bh.toFloat())
        mx.setScale(fit, fit)
        mx.postTranslate((width - bw * fit) / 2f, (height - bh * fit) / 2f)
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
        imageMatrix = mx
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
        override fun onLongPress(e: MotionEvent) { if (onHold?.invoke(e.x, e.y) == true) { held = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }
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
    }
    private val dn = resources.displayMetrics.density
    private val path: Path = PathParser.createPathFromPathData(d) ?: Path()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
