package com.lanshare.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
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
 * Animated GIF plays on Android 9+ (ImageDecoder); older versions show its first frame. SVG is opened by the browser in another app.
 */
class ImageViewerActivity : Activity() {
    companion object {
        /** JSON handed over by BrowserActivity: {start, items:[{name, url, size}]} */
        @Volatile var pending: String? = null
        private const val MAX_ITEMS = 2000
        private const val SLIDE_MS = 4000L
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
    private var uiOn = true
    private var slide = false
    private var gen = 0

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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 28)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        maxDim = if (am.memoryClass >= 192) 4096 else 3072
        cache = object : LruCache<Int, Bitmap>(maxOf(am.memoryClass, 64) * 1024 * 1024 / 4) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        File(cacheDir, "img").deleteRecursively()
        buildUi()
        begin()
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
                override fun onPageSelected(position: Int) { updateTitle(position); prefetch(position) }
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
        top.addView(back)
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(slideBtn)
        top.addView(share)
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(dp(8) + b.left, dp(8) + b.top, dp(8) + b.right, dp(16))
            insets
        }
        root.addView(top)
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
        loading.clear(); failed.clear(); dims.clear(); cache.evictAll()
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
                    if (rd != null) dims[pos] = rd
                } else failed[pos] = re ?: "Cannot open this picture"
                holder(pos)?.show(pos)
                if (pager.currentItem == pos) updateTitle(pos)
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
        if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
        c.inputStream.use { ins -> tmp.outputStream().use { o -> ins.copyTo(o, 64 * 1024) } }
        if (!tmp.renameTo(f)) throw IOException("cannot cache the picture")
        return f
    }

    private fun decode(f: File): Pair<Bitmap, String> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("This device cannot decode this picture")
        val big = maxOf(o.outWidth, o.outHeight)
        var s = 1
        while (big / (s * 2) >= maxDim) s *= 2
        while (big / s > 4096) s *= 2            // larger bitmaps cannot be drawn (GPU texture limit)
        var bm: Bitmap? = null
        while (bm == null) {
            try {
                bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                if (bm == null) throw IOException("This device cannot decode this picture")
            } catch (e: OutOfMemoryError) { if (s >= 64) throw e; s *= 2 }
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
        val ow = if (swap) o.outHeight else o.outWidth
        val oh = if (swap) o.outWidth else o.outHeight
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bm!!, 0, 0, bm.width, bm.height, m, true)
            if (r !== bm) bm.recycle()
            bm = r
        }
        return bm!! to "$ow\u00d7$oh"
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

    // ---------------------------------------------------------------- pager pages
    private inner class Pg(val root: FrameLayout, val iv: ZoomImageView, val spin: ProgressBar, val err: TextView, val anim: ImageView) : RecyclerView.ViewHolder(root) {
        var pos = -1
        fun stopAnim() {
            val d = anim.drawable
            if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) d.stop()
            anim.setImageDrawable(null); anim.visibility = View.GONE
        }
        /** GIF on Android 9+: the cached file is decoded as an animated drawable (no pinch zoom, tap still hides the bars). */
        private fun tryAnimate(p: Int) {
            if (Build.VERSION.SDK_INT < 28 || !items[p].name.lowercase().endsWith(".gif")) return
            val f = File(File(cacheDir, "img"), "$p.bin")
            if (!f.isFile) return
            try {
                val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f)) { dec, _, _ ->
                    dec.setTargetSampleSize(if (f.length() > 20L * 1024 * 1024) 2 else 1)
                }
                anim.setImageDrawable(d)
                anim.visibility = View.VISIBLE
                iv.visibility = View.INVISIBLE
                if (d is android.graphics.drawable.AnimatedImageDrawable) { d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; d.start() }
            } catch (_: Throwable) { stopAnim(); iv.visibility = View.VISIBLE }   // fall back to the still first frame
        }
        fun show(p: Int) {
            if (p != pos) return
            val bm = cache.get(p)
            val e = failed[p]
            iv.setBmp(bm)
            if (bm != null && anim.visibility != View.VISIBLE) tryAnimate(p)
            spin.visibility = if (bm == null && e == null) View.VISIBLE else View.GONE
            err.text = e ?: ""
            err.visibility = if (e != null) View.VISIBLE else View.GONE
        }
    }

    private inner class Ad : RecyclerView.Adapter<Pg>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Pg {
            val c = parent.context
            val root = FrameLayout(c).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val iv = ZoomImageView(c).apply { onTap = { toggleUi() } }
            val anim = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE; setOnClickListener { toggleUi() } }
            val spin = ProgressBar(c).apply { layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER) }
            val err = TextView(c).apply {
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            root.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(anim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(spin); root.addView(err)
            return Pg(root, iv, spin, err, anim)
        }
        override fun onBindViewHolder(h: Pg, position: Int) { h.pos = position; h.show(position); request(position) }
        override fun onViewRecycled(h: Pg) { h.pos = -1; h.stopAnim(); h.iv.visibility = View.VISIBLE; h.iv.setBmp(null) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !uiOn) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        gen++
        ui.removeCallbacksAndMessages(null)
        pool.shutdownNow()
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

    init { scaleType = ScaleType.MATRIX }

    fun setBmp(b: Bitmap?) {
        if (b == null) { setImageDrawable(null); bw = 0; bh = 0; return }
        setImageBitmap(b); bw = b.width; bh = b.height; reset()
    }

    private fun reset() {
        if (bw == 0 || width == 0 || height == 0) return
        fit = minOf(width / bw.toFloat(), height / bh.toFloat())
        mx.setScale(fit, fit)
        mx.postTranslate((width - bw * fit) / 2f, (height - bh * fit) / 2f)
        imageMatrix = mx
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
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) zoomTo(fit, e.x, e.y, true) else zoomTo(maxOf(fit * 2.5f, minOf(width / bw.toFloat(), 1f)), e.x, e.y, true)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
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
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(zoomed())
        if (e.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
