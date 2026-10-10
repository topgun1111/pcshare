package com.lanshare.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        private const val OCR_VER = 3          // bump when the reading method changes (TiledOcr = 2, + corner polygons + full-resolution tiles = 3): older cached results are ignored
    }

    private class Item(val name: String, val url: String, val size: Long)

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "img-load").also { it.isDaemon = true } }
    private var items: List<Item> = emptyList()
    private lateinit var pager: ViewPager2
    private lateinit var top: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var slideBtn: TextView
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
    private val ocr: TiledOcr by lazy { TiledOcr(MlKitOcr(applicationContext).also { it.onStatus = { m -> if (ocrWant >= 0 && ocrWant == ocrWork) Toast.makeText(this, m, Toast.LENGTH_SHORT).show() } }) }   // toasts only while the user waits for T
    private val ocrRes = HashMap<Int, List<OcrLine>>()   // UI thread only: recognised lines per page (coordinates of the displayed bitmap)
    private val ocrOn = HashSet<Int>()                   // pages whose text layer is switched on
    private val ocrNone = HashSet<Int>()                 // pages recognised with no text in them (T is dimmed)
    private val ocrBad = HashSet<Int>()                  // pages where recognition failed (auto mode skips them; pressing T retries)
    private var ocrWork = -1                             // page being recognised right now, -1 = idle
    private var ocrWant = -1                             // page the user pressed T on and is waiting for
    private var ocrFails = 0                             // consecutive failures; auto mode stops at 2 (model missing / no Play Services)
    private val autoRun = Runnable { autoOcr() }
    private lateinit var ocrBtn: TextView
    private lateinit var selBar: LinearLayout
    private lateinit var smartBtn: TextView                   // shown only when links / phone numbers / e-mails were found in the (selected) text
    private lateinit var copyBtn: TextView
    private val translator = OcrTranslate()
    private var trBusy = false

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

        fun tv(txt: String, sp: Float) = TextView(this).apply {
            text = txt; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8)); minWidth = dp(44)
        }
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(8), dp(8), dp(16))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        val back = tv("\u2190", 24f).apply { setOnClickListener { finish() } }
        titleTv = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        subTv = TextView(this).apply { setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1 }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(titleTv); addView(subTv) }
        slideBtn = tv("\u25B6", 18f).apply { setOnClickListener { setSlide(!slide) } }
        val share = tv("\u2934", 20f).apply { setOnClickListener { share() } }
        val edit = tv("\u270e", 20f).apply { setOnClickListener { edit() } }
        ocrBtn = tv("T", 20f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; setOnClickListener { toggleOcr() }; setOnLongClickListener { ocrMenu(); true } }
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(slideBtn)
        top.addView(ocrBtn)
        top.addView(edit)
        top.addView(share)
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(16))
            insets
        }
        root.addView(top)

        // bar shown while a picture's text layer is on: Copy (selection, else all text) / Select all / Share / Close
        fun barBtn(txt: String, act: () -> Unit) = tv(txt, 15f).apply { setPadding(dp(9), dp(8), dp(9), dp(8)); setOnClickListener { act() } }
        copyBtn = barBtn("Copy all") { copyOcr() }
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xE6202124.toInt()); cornerRadius = dp(20).toFloat() }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            visibility = View.GONE
            addView(copyBtn)
            addView(barBtn("Select all") { holder(pager.currentItem)?.ov?.selectAll() })
            addView(barBtn("Translate") { translateOcr() })
            smartBtn = barBtn("Links") { smartOcr() }.apply { visibility = View.GONE }
            addView(smartBtn)
            addView(barBtn("Save") { saveOcr() })
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
        top.visibility = if (uiOn) View.VISIBLE else View.GONE
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (uiOn) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun setSlide(on: Boolean) {
        slide = on
        slideBtn.text = if (on) "\u275A\u275A" else "\u25B6"
        ui.removeCallbacks(slideTick)
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (uiOn) toggleUi()
            ui.postDelayed(slideTick, SLIDE_MS)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateTitle(pos: Int) {
        val it = items.getOrNull(pos) ?: return
        titleTv.text = it.name
        subTv.text = (pos + 1).toString() + " / " + items.size + (dims[pos]?.let { d -> " \u00b7 $d" } ?: "") + (if (it.size > 0) " \u00b7 " + android.text.format.Formatter.formatShortFileSize(this, it.size) else "")
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
                out.add(Item(it.optString("name"), url, it.optLong("size", 0L)))
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
    /** Long press on the bare picture = T button (+ selects the word under the finger when the text is already read). True = press was used. */
    private fun holdOcr(ov: OcrOverlayView, x: Float, y: Float): Boolean {
        val pos = pager.currentItem
        if (pos in ocrOn) return false
        toggleOcr()
        if (pos in ocrOn && ov.hasLines()) ov.selectWordAt(x, y)
        return true
    }

    private fun scheduleAutoOcr() { ui.removeCallbacks(autoRun); ui.postDelayed(autoRun, if (ocrWant >= 0) 0L else 700L) }   // no delay while the user waits

    private fun autoOcr() {
        if (isDestroyed || slide || ocrWork >= 0 || ocrFails >= 2) return
        val cur = pager.currentItem
        val auto = autoAllowed()
        for (p in intArrayOf(cur, cur + 1, cur - 1)) {
            if (!auto && p != ocrWant) continue                      // background reading is off: only the page the user pressed T on
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
        ocr.recognizeHi(bm, File(File(cacheDir, "img"), "$p.bin").takeIf { it.isFile }) { res ->   // original file = full-resolution tiles for small print
            if (my != gen || isDestroyed) return@recognizeHi
            ocrWork = -1
            if (items.getOrNull(p) !== item) { scheduleAutoOcr(); return@recognizeHi }   // the picture was replaced meanwhile
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
    private fun ocrFile(p: Int): File = ocrFileOf(items[p])

    private fun ocrFileOf(it: Item): File {
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
                    for (x in l.words) {
                        val wo = JSONObject().put("t", x.text).put("b", boxArr(x.box))
                        x.quad?.let { q -> wo.put("q", JSONArray().apply { for (v in q) put(v.toDouble()) }) }
                        wa.put(wo)
                    }
                    ls.put(JSONObject().put("t", l.text).put("b", boxArr(l.box)).put("w", wa))
                }
                f.writeText(JSONObject().put("v", OCR_VER).put("w", w).put("h", h).put("l", ls).toString())
            } catch (_: Exception) {}
        }
    }

    private fun ocrLoad(p: Int, bm: Bitmap): List<OcrLine>? {
        try {
            val f = ocrFile(p)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            if (o.optInt("v") != OCR_VER || o.getInt("w") != bm.width || o.getInt("h") != bm.height) return null   // other reading method / size: read again
            val ls = o.getJSONArray("l")
            val out = ArrayList<OcrLine>()
            for (i in 0 until ls.length()) {
                val l = ls.getJSONObject(i)
                val wa = l.getJSONArray("w")
                val ws = ArrayList<OcrWord>()
                for (j in 0 until wa.length()) { val x = wa.getJSONObject(j); val qa = x.optJSONArray("q"); ws.add(OcrWord(x.getString("t"), arrBox(x.getJSONArray("b")), if (qa != null && qa.length() == 8) FloatArray(8) { n -> qa.getDouble(n).toFloat() } else null)) }
                out.add(OcrLine(l.getString("t"), arrBox(l.getJSONArray("b")), ws))
            }
            return out
        } catch (e: Exception) { return null }
    }

    private fun pruneOcr() {
        try {
            val lim = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            val fs = File(cacheDir, "ocr").listFiles() ?: return
            var total = 0L
            for (f in fs.sortedByDescending { it.lastModified() }) {          // newest first; too old or beyond 8 MB in total = deleted
                total += f.length()
                if (f.lastModified() < lim || total > 8L * 1024 * 1024) f.delete()
            }
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
        selBar.visibility = if (on) View.VISIBLE else View.GONE
        ocrBtn.setTextColor(if (on) 0xFF8AB4F8.toInt() else Color.WHITE)
        val cp = pager.currentItem
        ocrBtn.alpha = when {                                   // T: bright = text found / layer on, dim = no text, half = reading
            cp in ocrOn -> 1f
            ocrRes[cp]?.isNotEmpty() == true -> 1f
            cp in ocrNone -> 0.35f
            ocrWork == cp -> 0.5f
            else -> 0.8f
        }
        copyBtn.text = if (ov != null && ov.hasSelection()) "Copy" else "Copy all"
        smartBtn.visibility = if (on && OcrSmart.find(ocrText() ?: "").isNotEmpty()) View.VISIBLE else View.GONE
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

    /** Links / phone numbers / e-mails found in the selection (else all text): pick one to open, dial or write to. */
    private fun smartOcr() {
        val hits = OcrSmart.find(ocrText() ?: return)
        if (hits.isEmpty()) { Toast.makeText(this, "Nothing found", Toast.LENGTH_SHORT).show(); return }
        android.app.AlertDialog.Builder(this)
            .setItems(hits.map { it.label() }.toTypedArray()) { _, i ->
                try { startActivity(hits[i].intent()) } catch (e: Exception) { Toast.makeText(this, "No app found", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Save menu: the text as .txt, the vocabulary list (every different word once) as .txt, or the word list to the clipboard. */
    private fun saveOcr() {
        val t = ocrText() ?: return
        android.app.AlertDialog.Builder(this)
            .setItems(arrayOf("Save text (.txt)", "Save word list (.txt)", "Copy word list")) { _, i ->
                when (i) {
                    0 -> saveTxt(t)
                    else -> {
                        val w = OcrSmart.words(t)
                        if (w.isEmpty()) Toast.makeText(this, "No words", Toast.LENGTH_SHORT).show()
                        else if (i == 1) saveTxt(w)
                        else {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("words", w))
                            Toast.makeText(this, "Copied " + (w.count { it == '\n' } + 1) + " words", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun saveTxt(t: String) {
        try { Toast.makeText(this, "Saved: " + OcrSmart.saveTxt(this, t), Toast.LENGTH_LONG).show() }
        catch (e: Exception) { Toast.makeText(this, e.message ?: "Cannot save", Toast.LENGTH_LONG).show() }
    }

    // ---- background reading setting + search in the pictures that were already read (long press on T)
    private fun autoMode() = OcrPrefs.mode(this)                            // 0 always, 1 only while charging, 2 off (Settings -> Text in pictures)
    private fun charging(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }
    private fun autoAllowed() = when (autoMode()) { 2 -> false; 1 -> charging(); else -> true }

    private fun ocrMenu() {
        val modes = arrayOf("always", "only while charging", "off")
        android.app.AlertDialog.Builder(this)
            .setItems(arrayOf("Find text in these pictures", "Background reading: " + modes[autoMode().coerceIn(0, 2)])) { _, i ->
                if (i == 0) findDialog()
                else android.app.AlertDialog.Builder(this)
                    .setTitle("Background reading")
                    .setSingleChoiceItems(arrayOf("Always", "Only while charging", "Off"), autoMode().coerceIn(0, 2)) { d, w ->
                        OcrPrefs.setMode(this, w)
                        d.dismiss()
                        if (w == 0) scheduleAutoOcr()
                    }
                    .show()
            }
            .show()
    }

    private fun findDialog() {
        val et = android.widget.EditText(this).apply { setSingleLine(); hint = "word or phrase" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Find text in these pictures")
            .setView(et)
            .setPositiveButton("Search") { _, _ -> findText(et.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Looks through the saved text of every picture of this list (only pictures that were already read); accents and Turkish dotted / dotless i are ignored. */
    private fun findText(q0: String) {
        val q = OcrSmart.fold(q0.trim())
        if (q.isEmpty()) return
        val snap = items.toList()
        val my = gen
        pool.execute {
            val hits = ArrayList<Pair<Int, String>>()
            var unread = 0
            for ((p, item) in snap.withIndex()) {
                val f = try { ocrFileOf(item) } catch (e: Exception) { null }
                if (f == null || !f.isFile) { unread++; continue }
                try {
                    val ls = JSONObject(f.readText()).getJSONArray("l")
                    for (i in 0 until ls.length()) {
                        val line = ls.getJSONObject(i).getString("t")
                        if (OcrSmart.fold(line).contains(q)) { hits.add(p to line.trim().take(60)); break }
                    }
                } catch (_: Exception) { unread++ }
                if (hits.size >= 60) break
            }
            ui.post {
                if (my != gen || isDestroyed) return@post
                if (hits.isEmpty()) { Toast.makeText(this, "Not found" + (if (unread > 0) " ($unread pictures not read yet)" else ""), Toast.LENGTH_LONG).show(); return@post }
                android.app.AlertDialog.Builder(this)
                    .setTitle(hits.size.toString() + " found" + (if (unread > 0) " ($unread not read yet)" else ""))
                    .setItems(hits.map { (p, l) -> snap[p].name + "\n" + l }.toTypedArray()) { _, i -> pager.setCurrentItem(hits[i].first, false) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun shareOcr() {
        val t = ocrText() ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share text"))
    }

    /** Translates the selection (else all text of the picture): Turkish -> English, anything else -> Turkish; "Swap" reverses the pair. */
    private fun translateOcr(text0: String? = null, pair: Pair<String, String>? = null) {
        if (trBusy) return
        val raw = text0 ?: ocrText() ?: return
        val t = OcrTranslate.prep(raw)
        if (t.isEmpty()) return
        trBusy = true
        Toast.makeText(this, "Translating... (the first time a language pack is downloaded)", Toast.LENGTH_SHORT).show()
        translator.run(t, pair) { res ->
            trBusy = false
            if (isDestroyed || isFinishing) return@run
            res.onSuccess { o -> showTranslation(raw, o) }
                .onFailure { e -> Toast.makeText(this, e.message ?: "Cannot translate", Toast.LENGTH_LONG).show() }
        }
    }

    private fun showTranslation(raw: String, o: OcrTranslate.Out) {
        val tv = TextView(this).apply { text = o.text; setTextIsSelectable(true); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle(o.src.uppercase() + " \u2192 " + o.dst.uppercase())
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("text", o.text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Swap") { _, _ -> translateOcr(raw, o.dst to o.src) }
            .setNegativeButton("Close", null)
            .show()
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
            iv.onHold = { x, y -> if (ov.hasLines()) ov.selectWordAt(x, y) else holdOcr(ov, x, y) }   // layer on: select word; layer off: same as pressing T
            iv.onTextHit = { x, y -> ov.hitsWord(x, y) }                        // tap on a word selects it (1 = word, 2 = line, 3 = paragraph)
            iv.onTextTap = { x, y, n -> ov.tapSelect(x, y, n) }
            iv.onTouchAt = { x, y -> ov.touchHint(x, y) }                       // outlines the words around the finger
            ov.onAction = { id -> when (id) { "copy" -> copyOcr(); "search" -> searchOcr(); "translate" -> translateOcr(); "share" -> shareOcr() } }
            ov.onSelection = { _ -> updateOcrBar() }
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
        translator.close()
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
    /** Is there a recognised word under this view point? Taps starting on a word go to [onTextTap] instead of hide-bars / double-tap zoom. */
    var onTextHit: ((Float, Float) -> Boolean)? = null
    /** Tap on a word: count 1 = word, 2 = line, 3 = paragraph (taps within 450 ms of each other). */
    var onTextTap: ((Float, Float, Int) -> Unit)? = null
    /** Finger position while it is down (single pointer) - for the outlines around the finger. */
    var onTouchAt: ((Float, Float) -> Unit)? = null
    fun matrixNow() = Matrix(mx)
    private val slop = android.view.ViewConfiguration.get(c).scaledTouchSlop
    private var downOnWord = false
    private var tapMoved = false
    private var textTapUsed = false   // the last tap was used for text: swallow the delayed single-tap (hide bars)
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastTapT = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var taps = 0
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
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (textTapUsed) { textTapUsed = false; return true }; onTap?.invoke(); return true }
        override fun onLongPress(e: MotionEvent) { if (onHold?.invoke(e.x, e.y) == true) { held = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (downOnWord) return true                       // double tap on a word selects the line, no zoom
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
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnWord = onTextHit?.invoke(e.x, e.y) == true   // must be known before the gesture detector sees a 2nd tap
            tapMoved = false; textTapUsed = false
            downX = e.x; downY = e.y; downT = e.eventTime
        }
        sd.onTouchEvent(e)
        if (!sd.isInProgress) gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { held = false; parent?.requestDisallowInterceptTouchEvent(zoomed()) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouchAt?.invoke(e.x, e.y)
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) tapMoved = true
                if (e.pointerCount == 1) onTouchAt?.invoke(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapMoved = true
            MotionEvent.ACTION_UP -> if (downOnWord && !tapMoved && !held && !sd.isInProgress && e.eventTime - downT < 400) {
                taps = if (e.eventTime - lastTapT < 450 && Math.hypot((e.x - lastTapX).toDouble(), (e.y - lastTapY).toDouble()) < slop * 6) taps + 1 else 1
                if (taps > 3) taps = 1
                lastTapT = e.eventTime; lastTapX = e.x; lastTapY = e.y
                textTapUsed = true
                onTextTap?.invoke(e.x, e.y, taps)
            }
        }
        if (held) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
