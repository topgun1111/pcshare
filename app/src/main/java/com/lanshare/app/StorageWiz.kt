package com.lanshare.app

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lanshare.app.core.SNode
import com.lanshare.app.core.Scan
import java.io.File
import java.util.Locale

/*
 * Storage Analyzer, WizTree view: black theme, Tree View (folders with % bar / size / files), File View (filter box with WizTree syntax),
 * Types (extensions with colour swatch, %, size, files; tap = highlight in the treemap), a file-level "cushion" treemap coloured by extension
 * and a status bar. Works on the same Scan / SNode tree as the classic screens; StorageActivity only hosts it ([WizHost]).
 * NOT compiled on a device here - written against the same APIs the other Storage*.kt files use.
 */

interface WizHost {
    val label: String
    fun details(n: SNode)               // the existing details dialog (open / share / show in file manager / delete)
    fun classic()                       // back to the pie / list screens
    fun rescan()
    fun volume(): LongArray?            // [used, free, total] of the analyzed volume, null when unknown
    fun realFile(n: SNode): java.io.File?   // the real file of a node (for image / video thumbnails in the treemap), null when unknown
    fun bars(bg: Int, darkIcons: Boolean) {}   // paint the system bars in the screen's colours
    fun rebuild() {}                    // build the screen again (after a theme change)
    fun appsOk(): Boolean = false       // show the Apps tab (per-app sizes, local main storage only)
    fun nearest(n: SNode): SNode        // [n] when still in the tree, else its nearest ancestor that is (after a delete)
}

internal class WizExt(val key: String, val size: Long, val count: Long, var color: Int)

internal object WizUtil {
    const val AGG = "\u0001"
    private fun c(v: Long) = v.toInt()
    val GRAY = c(0xFF707070)
    val DIM = c(0xFF3A3A3A)
    val RED = c(0xFFF01818)
    val FOLDER_DEFAULT = c(0xFFE8B93C)
    var FOLDER = FOLDER_DEFAULT
    val PAL_DEFAULT = longArrayOf(0xFFF08A1C, 0xFFD4CB1E, 0xFF2DB84D, 0xFFA848E0, 0xFFE0408C, 0xFF1E9BFF, 0xFFF01A1A, 0xFFF030D0,
        0xFF86C21E, 0xFF7F72F5, 0xFF1ED8B8, 0xFFB0703A, 0xFF4CD0F0, 0xFFF0C040, 0xFF9C6BFF, 0xFF40A080).map { c(it) }.toIntArray()
    var PAL = PAL_DEFAULT
    val CATC_DEFAULT = intArrayOf(c(0xFF707070), c(0xFF2DB84D), c(0xFFA848E0), c(0xFFE0408C), c(0xFF1E9BFF), c(0xFFF08A1C), c(0xFFD4CB1E))   // SCat order
    var CATC = CATC_DEFAULT

    fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        if (i < 0 || i == name.length - 1 || name.length - i > 12) return ""
        return name.substring(i + 1).lowercase()
    }

    fun shade(col: Int, f: Float): Int {
        val r = (Color.red(col) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(col) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(col) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** Extensions below [root]: size and count, biggest first; the biggest ones get the palette colours (the same ones the treemap uses). */
    fun stats(root: SNode): List<WizExt> {
        val m = HashMap<String, LongArray>()
        val st = ArrayList<SNode>()
        st.add(root)
        var aggS = 0L
        var aggC = 0L
        while (st.isNotEmpty()) {
            val n = st.removeAt(st.size - 1)
            if (n.agg) { aggS += n.size; aggC += n.files }
            else if (n.dir) { for (k in n.kids) st.add(k) }
            else { val a = m.getOrPut(extOf(n.name)) { LongArray(2) }; a[0] += n.size; a[1]++ }
        }
        val out = ArrayList<WizExt>(m.size + 1)
        for ((k, a) in m) out.add(WizExt(k, a[0], a[1], GRAY))
        if (aggC > 0) out.add(WizExt(AGG, aggS, aggC, GRAY))
        out.sortByDescending { it.size }
        var i = 0
        for (e in out) { if (e.key == AGG) continue; e.color = if (i < PAL.size) PAL[i] else GRAY; i++ }
        return out
    }

    private val TYPES = HashMap<String, String>().also { m ->
        fun a(t: String, s: String) { for (e in s.split(' ')) m[e] = t }
        a("JPEG Image", "jpg jpeg jfif"); a("PNG Image", "png"); a("GIF Image", "gif"); a("WebP Image", "webp"); a("HEIC Image", "heic heif")
        a("Bitmap Image", "bmp"); a("SVG Image", "svg"); a("RAW Photo", "raw dng cr2 nef arw")
        a("MP4 Video", "mp4 m4v"); a("MKV Video", "mkv"); a("AVI Video", "avi"); a("QuickTime Video", "mov"); a("WebM Video", "webm"); a("3GP Video", "3gp")
        a("MP3 Audio", "mp3"); a("AAC Audio", "aac m4a"); a("WAV Audio", "wav"); a("FLAC Audio", "flac"); a("OGG Audio", "ogg opus oga")
        a("PDF Document", "pdf"); a("Word Document", "doc docx"); a("Excel Sheet", "xls xlsx"); a("PowerPoint", "ppt pptx"); a("Text File", "txt log")
        a("E-book", "epub mobi azw3"); a("JSON File", "json"); a("XML File", "xml"); a("HTML File", "html htm"); a("CSV File", "csv"); a("Markdown", "md")
        a("ZIP Archive", "zip"); a("RAR Archive", "rar"); a("7-Zip Archive", "7z"); a("TAR Archive", "tar"); a("GZip Archive", "gz tgz"); a("Disc Image", "iso img")
        a("Android Package", "apk"); a("App Bundle", "xapk apks aab"); a("Opaque Binary (OBB)", "obb"); a("Dalvik Executable", "dex"); a("Shared Library", "so")
        a("Java Archive", "jar"); a("Java Class", "class"); a("Kotlin Source", "kt"); a("Java Source", "java"); a("JavaScript", "js"); a("PHP Source File", "php")
        a("Python Script", "py"); a("Cascading Style Sheet", "css"); a("Database", "db sqlite"); a("Temporary File", "tmp temp"); a("Backup File", "bak")
        a("Windows Installer", "msi msp"); a("Application", "exe"); a("Application Extension", "dll"); a("Hard Disk Image", "vhd vhdx vmdk")
    }

    fun type(key: String): String = when {
        key == AGG -> "(small files, not listed one by one)"
        key.isEmpty() -> "(No Extension)"
        else -> TYPES[key] ?: (key.uppercase(Locale.US) + " File")
    }

    fun isUnder(n: SNode, anc: SNode): Boolean { var x: SNode? = n; while (x != null) { if (x === anc) return true; x = x.parent }; return false }

    fun pct(v: Long, of: Long): String = if (of > 0) String.format(Locale.US, "%.1f %%", v * 100.0 / of) else "0 %"
}

/** Thin rounded share bar (optional label inside); colours come from the app theme (see WizScreen). */
internal class WizBar(ctx: Context) : View(ctx) {
    var frac = 0f
    var label = ""
    var fillCol = Color.rgb(0x5A, 0x6E, 0x88)
    var fillCol2 = 0
    var trackCol = Color.rgb(0x2A, 0x2A, 0x2A)
    var textCol = Color.WHITE
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = resources.displayMetrics.density

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // a View's default measure takes ALL the room offered (AT_MOST): inside a wrap_content column that made the bar as wide as the row and left the names no space
        val ws = MeasureSpec.getSize(wSpec)
        val w = if (MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY) ws else minOf(ws, (84f * d).toInt())
        setMeasuredDimension(w, resolveSize((5f * d).toInt(), hSpec))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = h / 2f
        p.color = trackCol; r.set(0f, 0f, w, h); c.drawRoundRect(r, rad, rad, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            val fw = maxOf(h, w * f)
            if (fillCol2 != 0) p.shader = android.graphics.LinearGradient(0f, 0f, fw, 0f, fillCol, fillCol2, Shader.TileMode.CLAMP) else p.color = fillCol
            r.set(0f, 0f, fw, h); c.drawRoundRect(r, rad, rad, p); p.shader = null
        }
        if (label.isNotEmpty()) {
            p.color = textCol; p.textSize = 12f * d * UiScale.tf; p.textAlign = Paint.Align.CENTER
            c.drawText(label, w / 2f, h / 2f + p.textSize * 0.35f, p)
        }
    }
}

/** Squarified treemap of every FILE below a folder, "cushion" shaded, coloured by extension (see [WizUtil.stats]). */
internal class WizMap(ctx: Context) : View(ctx) {
    var onPick: ((SNode) -> Unit)? = null     // single tap on a tile
    var onOpen: ((SNode) -> Unit)? = null     // double tap on a folder tile: make it the shown folder
    var onLong: ((SNode) -> Unit)? = null     // long press: details
    var hl: String? = null                    // highlighted extension: its files red, everything else dark grey
        set(v) { if (field != v) { field = v; render() } }
    var sel: SNode? = null
        set(v) { field = v; invalidate() }
    var bgCol = Color.BLACK                   // gap / background colour (app theme)
    var dimCol = WizUtil.DIM                  // tiles that are not highlighted

    private var root: SNode? = null
    private var colors: Map<String, Int> = emptyMap()
    private val d = resources.displayMetrics.density
    private val MAXR = 7000
    private val MAXZ = 60f
    private val tx = FloatArray(MAXR)
    private val ty = FloatArray(MAXR)
    private val tw = FloatArray(MAXR)
    private val th = FloatArray(MAXR)
    private val tn = arrayOfNulls<SNode>(MAXR)
    private var cnt = 0
    private var bmp: Bitmap? = null
    private val fill = Paint()
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ---- zoom / pan: the layout is recomputed for the visible part only, so zooming in shows real detail (small files become tiles)
    private var zoom = 1f                 // current zoom (1 = whole folder fits the view)
    private var offX = 0f                 // current pan: virtual content pixel (at [zoom]) shown at the view's left edge
    private var offY = 0f
    private var rZoom = 1f                // state the bitmap was rendered with (used to preview a gesture with a matrix)
    private var rOffX = 0f
    private var rOffY = 0f
    private var gesturing = false
    private val pv = Matrix()
    private val settle = Runnable { gesturing = false; relayout() }
    /** Called after every zoom change with the new zoom factor (1 = not zoomed). */
    var onZoom: ((Float) -> Unit)? = null

    val zoomed: Boolean get() = zoom > 1.01f
    val zoomLevel: Float get() = zoom

    private fun clampOff() {
        val maxX = (width * zoom - width).coerceAtLeast(0f); val maxY = (height * zoom - height).coerceAtLeast(0f)
        offX = offX.coerceIn(0f, maxX); offY = offY.coerceIn(0f, maxY)
    }

    /** Zoom by [f] around the view point ([fx],[fy]); the picture follows the fingers at once (matrix) and is re-laid-out when the gesture settles. */
    fun zoomBy(f: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        val nz = (zoom * f).coerceIn(1f, MAXZ)
        if (nz == zoom) return
        val nx = (fx + offX) / zoom; val ny = (fy + offY) / zoom      // content point under the focus (zoom independent)
        zoom = nz
        offX = nx * zoom - fx; offY = ny * zoom - fy
        clampOff()
        afterChange()
    }

    fun resetZoom() { if (zoom == 1f && offX == 0f && offY == 0f) return; zoom = 1f; offX = 0f; offY = 0f; settle.run(); onZoom?.invoke(zoom) }

    private fun afterChange() {
        gesturing = true
        invalidate()
        removeCallbacks(settle)
        postDelayed(settle, 160)
        onZoom?.invoke(zoom)
    }

    private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(Math.pow(d.scaleFactor.toDouble(), 1.8).toFloat(), d.focusX, d.focusY); return true }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { hit(e.x, e.y)?.let { onPick?.invoke(it) }; return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val t = hit(e.x, e.y)
            if (t != null && t.dir && t.kids.isNotEmpty()) onOpen?.invoke(t)       // folder tile: make it the shown folder (as before)
            else zoomBy(2.5f, e.x, e.y)                                              // file / empty: zoom in there
            return true
        }
        override fun onLongPress(e: MotionEvent) { if (!sgd.isInProgress) hit(e.x, e.y)?.let { onLong?.invoke(it) } }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (sgd.isInProgress || zoom <= 1f) return false
            offX += dx; offY += dy; clampOff(); afterChange()
            return true
        }
    })

    fun setData(r: SNode?, cols: Map<String, Int>) {
        if (r !== root) { zoom = 1f; offX = 0f; offY = 0f; onZoom?.invoke(zoom) }
        root = r; colors = cols; relayout()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); clampOff(); relayout() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        sgd.onTouchEvent(e)
        gd.onTouchEvent(e)
        return true
    }

    private fun relayout() {
        cnt = 0
        val r = root
        if (r == null || width < 8 || height < 8) { bmp = null; invalidate(); return }
        // the whole folder is laid out at [zoom] x the view size, shifted by the pan; parts outside the view are skipped
        lay(r, -offX, -offY, width * zoom, height * zoom, 0)
        rZoom = zoom; rOffX = offX; rOffY = offY
        reqGen++                          // thumbnail requests of the previous layout are outdated
        render()
    }

    /** True when the rectangle is (partly) inside the view. */
    private fun vis(x: Float, y: Float, w: Float, h: Float) = x < width && y < height && x + w > 0f && y + h > 0f

    private fun add(n: SNode, x: Float, y: Float, w: Float, h: Float) {
        if (cnt >= MAXR || w < 0.6f || h < 0.6f) return
        tx[cnt] = x; ty[cnt] = y; tw[cnt] = w; th[cnt] = h; tn[cnt] = n; cnt++
    }

    private fun lay(n: SNode, x: Float, y: Float, w: Float, h: Float, depth: Int) {
        if (w < 0.6f || h < 0.6f || !vis(x, y, w, h)) return
        if (!n.dir || n.kids.isEmpty() || depth > 24 || (depth > 0 && w * h < 10f * d * d)) { add(n, x, y, w, h); return }
        squarify(n, x, y, w, h, depth)
    }

    private fun place(k: SNode, x: Float, y: Float, w: Float, h: Float, depth: Int) {
        if (k.dir && k.kids.isNotEmpty() && w > 7f && h > 7f) lay(k, x + 1f, y + 1f, w - 2f, h - 2f, depth + 1) else lay(k, x, y, w, h, depth + 1)
    }

    private fun squarify(p: SNode, x0: Float, y0: Float, w0: Float, h0: Float, depth: Int) {
        val kids = p.kids
        var rem = 0.0
        for (k in kids) rem += k.size
        if (rem <= 0.0) { add(p, x0, y0, w0, h0); return }
        var x = x0; var y = y0; var w = w0; var h = h0
        var i = 0
        while (i < kids.size && cnt < MAXR && kids[i].size > 0 && rem > 0.0 && w >= 1f && h >= 1f) {
            val side = minOf(w, h).toDouble()
            val scale = w.toDouble() * h / rem
            var j = i
            var rs = 0.0; var mx = 0.0; var mn = Double.MAX_VALUE; var worst = Double.MAX_VALUE
            while (j < kids.size && kids[j].size > 0) {
                val a = kids[j].size * scale
                val nrs = rs + a; val nmx = maxOf(mx, a); val nmn = minOf(mn, a)
                val nw = maxOf(side * side * nmx / (nrs * nrs), nrs * nrs / (side * side * nmn))
                if (j > i && nw > worst) break
                rs = nrs; mx = nmx; mn = nmn; worst = nw; j++
            }
            if (j == i) j = i + 1
            val thick = rs / side
            var off = 0.0
            var used = 0.0
            for (k in i until j) {
                val a = kids[k].size * scale
                val len = if (thick > 0) a / thick else 0.0
                if (w >= h) place(kids[k], x, (y + off).toFloat(), thick.toFloat(), len.toFloat(), depth)
                else place(kids[k], (x + off).toFloat(), y, len.toFloat(), thick.toFloat(), depth)
                off += len
                used += kids[k].size
            }
            if (w >= h) { x += thick.toFloat(); w -= thick.toFloat() } else { y += thick.toFloat(); h -= thick.toFloat() }
            rem -= used
            i = j
        }
    }

    private fun colorOf(n: SNode): Int {
        val h = hl
        if (n.agg) return if (h != null) dimCol else WizUtil.GRAY
        if (n.dir) {
            if (h != null) return dimCol
            val cs = n.cats ?: return WizUtil.GRAY
            var b = 0
            for (i in cs.indices) if (cs[i] > cs[b]) b = i
            return WizUtil.shade(WizUtil.CATC[b.coerceIn(0, WizUtil.CATC.size - 1)], 0.8f)
        }
        val key = WizUtil.extOf(n.name)
        if (h != null) return if (key == h) WizUtil.RED else dimCol
        return colors[key] ?: WizUtil.GRAY
    }

    // ---- image / video thumbnails: drawn inside big enough tiles, loaded in the background (cached, newest layout first)
    var fileOf: ((SNode) -> java.io.File?)? = null
    @Volatile private var reqGen = 0
    private val thumbs = object : LruCache<String, Bitmap>(32 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val want = HashMap<String, Int>()            // key -> layout generation it was requested for (main thread only)
    private val missing = HashSet<String>()              // keys that could not be decoded: never asked again
    private var renderQueued = false
    private val ui = Handler(Looper.getMainLooper())
    private val pool = java.util.concurrent.ThreadPoolExecutor(3, 3, 5L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingDeque<Runnable>(), java.util.concurrent.ThreadFactory { r -> Thread(r, "wiz-thumb").also { it.isDaemon = true } })
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcR = Rect()
    private val dstR = RectF()

    private fun mediaKind(n: SNode): Int {            // 0 none, 1 image, 2 video
        if (n.dir || n.agg) return 0
        return when (WizUtil.extOf(n.name)) {
            "jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif" -> 1
            "mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp" -> 2
            else -> 0
        }
    }

    private fun bucketOf(px: Float): Int = if (px <= 150f * d) 160 else if (px <= 300f * d) 320 else 640

    /** Best thumbnail we have for [n] (the wanted size, else a smaller one); asks for the wanted one when it is missing. */
    private fun thumbFor(n: SNode, px: Float, kind: Int): Bitmap? {
        val b = bucketOf(px)
        val k0 = n.path() + "#" + b
        thumbs.get(k0)?.let { return it }
        if (k0 !in missing && want[k0] != reqGen) {
            want[k0] = reqGen
            val gen = reqGen
            pool.execute { load(n, kind, b, k0, gen) }
        }
        var s = b / 2
        while (s >= 160) { thumbs.get(n.path() + "#" + s)?.let { return it }; s /= 2 }
        return null
    }

    private fun load(n: SNode, kind: Int, side: Int, key: String, gen: Int) {
        if (gen != reqGen) return                      // the view moved on; it asks again if the tile is still wanted
        var bm: Bitmap? = null
        try {
            val f = fileOf?.invoke(n)
            if (f != null && f.isFile) bm = if (kind == 1) decodeImage(f, side) else decodeVideo(f, side)
        } catch (_: Throwable) {}
        val res = bm
        ui.post {
            want.remove(key)
            if (res == null) { missing.add(key); return@post }
            thumbs.put(key, res)
            if (!renderQueued) { renderQueued = true; ui.postDelayed({ renderQueued = false; render() }, 90) }
        }
    }

    private fun decodeImage(f: java.io.File, side: Int): Bitmap? {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var ss = 1
        while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= side) ss *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = ss; o2.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }

    private fun decodeVideo(f: java.io.File, side: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val fr = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L) ?: return null
            val m = maxOf(fr.width, fr.height)
            if (m <= side) return fr
            val k = side.toFloat() / m
            return Bitmap.createScaledBitmap(fr, (fr.width * k).toInt().coerceAtLeast(1), (fr.height * k).toInt().coerceAtLeast(1), true)
        } finally { try { r.release() } catch (_: Throwable) {} }
    }

    /** Centre-crops [bm] into the tile; true when drawn. */
    private fun drawThumb(c: Canvas, bm: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        val sc = maxOf(w / bm.width, h / bm.height)
        val sw = (w / sc).toInt().coerceIn(1, bm.width); val sh = (h / sc).toInt().coerceIn(1, bm.height)
        val sx = (bm.width - sw) / 2; val sy = (bm.height - sh) / 2
        srcR.set(sx, sy, sx + sw, sy + sh)
        dstR.set(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f)
        c.drawBitmap(bm, srcR, dstR, bmpPaint)
    }

    private fun render() {
        val w = width; val hh = height
        if (w < 8 || hh < 8) return
        var b = bmp
        if (b == null || b.width != w || b.height != hh) { b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888); bmp = b }
        val c = Canvas(b)
        c.drawColor(bgCol)
        txt.textSize = 11f * d
        for (i in 0 until cnt) tile(c, i)
        invalidate()
    }

    private fun tile(c: Canvas, i: Int) {
        val x = tx[i]; val y = ty[i]; val w = tw[i]; val h = th[i]
        val n = tn[i] ?: return
        val base = colorOf(n)
        if (w < 5f || h < 5f) {
            fill.shader = null; fill.color = WizUtil.shade(base, 0.7f)
            c.drawRect(x, y, x + w, y + h, fill)
            return
        }
        if (w < 22f * d || h < 22f * d) {      // small tile: flat colour + light top edge (no shader; thousands of these are drawn zoomed out)
            fill.shader = null; fill.color = WizUtil.shade(base, 0.85f)
            c.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, fill)
            if (w >= 8f && h >= 8f) { fill.color = WizUtil.shade(base, 1.25f); c.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + 2f, fill) }
            labelOf(c, n, x, y, w, h)
            return
        }
        if (hl == null) {                       // picture / video tile that is big enough: show the real thumbnail
            val kind = mediaKind(n)
            if (kind != 0) {
                val vw0 = minOf(x + w, width.toFloat()) - maxOf(x, 0f); val vh0 = minOf(y + h, height.toFloat()) - maxOf(y, 0f)
                if (vw0 >= 36f * d && vh0 >= 36f * d) {
                    val bm = thumbFor(n, maxOf(w, h), kind)
                    if (bm != null) { drawThumb(c, bm, x, y, w, h); labelOf(c, n, x, y, w, h); return }
                }
            }
        }
        fill.shader = RadialGradient(x + w * 0.35f, y + h * 0.3f, maxOf(w, h) * 0.85f,
            intArrayOf(WizUtil.shade(base, 1.4f), base, WizUtil.shade(base, 0.4f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, fill)
        fill.shader = null
        labelOf(c, n, x, y, w, h)
    }

    /** Name (extension kept) and size inside the visible part of a tile; the text size follows the tile, so names appear as you zoom in. */
    private fun labelOf(c: Canvas, n: SNode, x: Float, y: Float, w: Float, h: Float) {
        val vx = maxOf(x, 0f); val vy = maxOf(y, 0f)
        val vw = minOf(x + w, width.toFloat()) - vx; val vh = minOf(y + h, height.toFloat()) - vy
        val tf = UiScale.tf
        if (vw < 42f * d || vh < 17f * d * tf) return
        val ts = (vh * 0.42f).coerceIn(10.5f * d * tf, 14.5f * d * tf)
        txt.textSize = ts
        val nm = if (n.agg) n.files.toString() + " small files" else n.name
        val s = TextUtils.ellipsize(nm, android.text.TextPaint(txt), vw - 6f * d, if (n.dir || n.agg) TextUtils.TruncateAt.END else TextUtils.TruncateAt.MIDDLE).toString()
        val ty0 = vy + ts + 1.5f * d
        txt.style = Paint.Style.STROKE; txt.strokeWidth = 2.5f; txt.color = Color.BLACK; c.drawText(s, vx + 3f * d, ty0, txt)
        txt.style = Paint.Style.FILL; txt.color = Color.WHITE; c.drawText(s, vx + 3f * d, ty0, txt)
        if (vh > ts * 2.9f) {
            val z = NlModel.fmt(n.size)
            val ty1 = ty0 + ts + 1f * d
            txt.style = Paint.Style.STROKE; txt.strokeWidth = 2.5f; txt.color = Color.BLACK; c.drawText(z, vx + 3f * d, ty1, txt)
            txt.style = Paint.Style.FILL; txt.color = Color.WHITE; c.drawText(z, vx + 3f * d, ty1, txt)
        }
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) { c.drawColor(bgCol); return }
        if (gesturing) {            // preview: the last picture moved / scaled to the current zoom and pan; the exact one follows when the fingers rest
            val k = zoom / rZoom
            pv.reset(); pv.postScale(k, k); pv.postTranslate(rOffX * k - offX, rOffY * k - offY)
            c.drawBitmap(b, pv, null)
            return
        }
        c.drawBitmap(b, 0f, 0f, null)
        val s = sel ?: return
        if (s === root) return
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var bt = -1f
        for (i in 0 until cnt) {
            val n = tn[i] ?: continue
            if (n === s || (s.dir && WizUtil.isUnder(n, s))) {
                l = minOf(l, tx[i]); t = minOf(t, ty[i]); r = maxOf(r, tx[i] + tw[i]); bt = maxOf(bt, ty[i] + th[i])
            }
        }
        if (r < 0f) return
        line.strokeWidth = 3.5f; line.color = Color.BLACK; c.drawRect(l, t, r, bt, line)
        line.strokeWidth = 1.8f; line.color = Color.WHITE; c.drawRect(l, t, r, bt, line)
    }

    private fun hit(px: Float, py: Float): SNode? {
        for (i in cnt - 1 downTo 0) if (px >= tx[i] && px < tx[i] + tw[i] && py >= ty[i] && py < ty[i] + th[i]) return tn[i]
        return null
    }
}

/** Parses the WizTree-style filter: words (AND, separated by spaces), a|b (OR), !x (NOT), * and ? wildcards, >10mb <1gb size tests, a / or \ = match the whole path. */
internal class WizFilter(text: String) {
    private class Term(val not: Boolean, val alts: List<Alt>)
    private class Alt(val minSize: Long, val maxSize: Long, val rx: Regex?, val sub: String?, val path: Boolean)
    private val terms = ArrayList<Term>()
    val needPath: Boolean
    val empty: Boolean

    init {
        var np = false
        for (raw in text.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            val not = raw.startsWith("!")
            val body = if (not) raw.substring(1) else raw
            if (body.isEmpty()) continue
            val alts = ArrayList<Alt>()
            for (s in body.split('|')) {
                if (s.isEmpty()) continue
                val a = alt(s)
                if (a.path) np = true
                alts.add(a)
            }
            if (alts.isNotEmpty()) terms.add(Term(not, alts))
        }
        needPath = np
        empty = terms.isEmpty()
    }

    private fun alt(s: String): Alt {
        val m = Regex("^(>=|<=|>|<)(\\d+(?:\\.\\d+)?)(b|kb|mb|gb|tb)?$", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            val mult = when (m.groupValues[3].lowercase()) { "kb" -> 1L shl 10; "mb" -> 1L shl 20; "gb" -> 1L shl 30; "tb" -> 1L shl 40; else -> 1L }
            val v = (m.groupValues[2].toDouble() * mult).toLong()
            return when (m.groupValues[1]) {
                ">" -> Alt(v + 1, Long.MAX_VALUE, null, null, false)
                ">=" -> Alt(v, Long.MAX_VALUE, null, null, false)
                "<" -> Alt(0, v - 1, null, null, false)
                else -> Alt(0, v, null, null, false)
            }
        }
        val path = s.contains('/') || s.contains('\\')
        val t = s.replace('\\', '/').lowercase()
        if (t.contains('*') || t.contains('?')) {
            val sb = StringBuilder()
            for (ch in t) when (ch) { '*' -> sb.append(".*"); '?' -> sb.append('.'); else -> sb.append(Regex.escape(ch.toString())) }
            return Alt(-1, -1, Regex(sb.toString(), RegexOption.IGNORE_CASE), null, path)
        }
        return Alt(-1, -1, null, t, path)
    }

    fun matches(name: String, size: Long, path: String?): Boolean {
        val ln = name.lowercase()
        val lp = path?.lowercase()
        for (t in terms) {
            var hit = false
            for (a in t.alts) {
                val ok = when {
                    a.rx == null && a.sub == null -> size >= a.minSize && size <= a.maxSize
                    else -> {
                        val subject = if (a.path) (lp ?: ln) else ln
                        if (a.rx != null) a.rx.matches(subject) else subject.contains(a.sub!!)
                    }
                }
                if (ok) { hit = true; break }
            }
            if (hit == t.not) return false
        }
        return true
    }
}

class WizScreen(private val act: Activity, private val scan: Scan, private val host: WizHost) {
    private val ui = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    // colours: the app theme (AppPal, the palette the page pushes), so the screen follows light / dark and the chosen theme
    private val theme = WizThemes.resolve(act)
    private val themeApplied = run { WizUtil.PAL = theme.pal; WizUtil.CATC = theme.cat; WizUtil.FOLDER = theme.folder }
    private val night = ColorUtils.calculateLuminance(theme.bg) < 0.5
    private fun mix(a: Int, b: Int, t: Float) = ColorUtils.blendARGB(a, b, t)
    private val cBg = theme.bg
    private val cFg = theme.fg
    private val cDim = theme.mut
    private val cHdr = theme.cont                      // chips, fields, status bar
    private val cPanel = theme.cont
    private val cLine = theme.bd
    private val cAcc = theme.ac
    private val cOnAcc = theme.onac
    private val cSel = mix(theme.bg, theme.ac, 0.22f)  // selected card
    private val cHl = mix(theme.bg, WizUtil.RED, 0.28f)  // highlighted extension card
    private val cErr = if (night) 0xFFEF5350.toInt() else 0xFFC62828.toInt()

    private val root: SNode = scan.root!!
    private var focus: SNode = root
    private var sel: SNode? = null
    private var stats: List<WizExt> = emptyList()
    private var colorMap: Map<String, Int> = emptyMap()
    private var statSeq = 0
    private var tab = 0
    private var hlKey: String? = null
    private val expanded = HashSet<SNode>()

    // views
    val view: LinearLayout = LinearLayout(act)
    private val map = WizMap(act)
    private val tmTitle = TextView(act)
    private val status = TextView(act)
    private val statusR = TextView(act)
    private val tabV = arrayOfNulls<TextView>(5)
    private val tabI = arrayOfNulls<View>(5)
    private lateinit var treeList: ListView
    private lateinit var fileBox: LinearLayout
    private lateinit var typeBox: LinearLayout
    private lateinit var typeList: ListView
    private lateinit var fileList: ListView
    private lateinit var filterEt: EditText
    private lateinit var fileInfo: TextView
    private lateinit var maxBtn: TextView

    // tree rows
    private val rowN = ArrayList<SNode>()
    private val rowD = ArrayList<Int>()

    // file view
    private var allFiles: ArrayList<SNode>? = null
    private var shown: List<SNode> = emptyList()
    private var matchCount = 0
    private var matchSize = 0L
    private var maxShow = 1000
    private var fileSeq = 0
    private var building = false
    private var filterText = ""

    // ------------------------------------------------------------------ build
    private fun txt(t: String, sp: Float = 15f, col: Int = cFg, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(col); if (bold) setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun round(col: Int, rdp: Int) = GradientDrawable().apply { setColor(col); cornerRadius = rdp * d }

    /** Rounded chip button (min 44 dp touch height). */
    private fun btn(t: String, fn: () -> Unit) = TextView(act).apply {
        text = t; textSize = 14f; setTextColor(cFg); gravity = Gravity.CENTER; minimumHeight = dp(44); minimumWidth = dp(44)
        setPadding(dp(16), dp(8), dp(16), dp(8)); background = round(cHdr, 22)
        isClickable = true; setOnClickListener { fn() }
    }

    /** Text-only toolbar button (treemap bar), 44 dp touch target. */
    private fun tbtn(t: String, sp: Float, fn: () -> Unit) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(cAcc); gravity = Gravity.CENTER; minimumHeight = dp(44); minimumWidth = dp(44)
        setPadding(dp(8), 0, dp(8), 0); isClickable = true; setOnClickListener { fn() }
    }

    private fun hline() = View(act).apply { setBackgroundColor(cLine) }

    private fun styleList(l: ListView) {
        l.divider = ColorDrawable(Color.TRANSPARENT); l.dividerHeight = dp(6); l.setBackgroundColor(cBg)
        l.setPadding(dp(12), dp(6), dp(12), dp(10)); l.clipToPadding = false
        l.selector = ColorDrawable(Color.TRANSPARENT); l.isVerticalScrollBarEnabled = true
    }

    /** Rounded card of a row; [sel] = selected (tinted + accent outline), [hl] = highlighted type. */
    private fun card(sel: Boolean, hl: Boolean = false): android.graphics.drawable.Drawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (hl) cHl else if (sel) cSel else cHdr)
        if (hl) setStroke(dp(2), WizUtil.RED) else if (sel) setStroke(dp(2), cAcc) else setStroke(1, mix(cBg, cLine, 0.55f))
    }

    /** Row tile of a file: the real picture / video frame when it is one (loaded in the background), else the coloured extension tile. */
    private fun setFileGlyph(g: WizGlyph, n: SNode) {
        val ext = WizUtil.extOf(n.name)
        val col = colorMap[ext] ?: WizUtil.GRAY
        val k = WizThumbs.kind(ext)
        var bm: Bitmap? = null
        if (k != 0) {
            val key = n.path() + "#160"
            bm = WizThumbs.get(key)
            if (bm == null) WizThumbs.request(key, k, { host.realFile(n) }) { thumbDone() }
        }
        g.set(1, col, ext.uppercase().ifEmpty { "·" }, bm, k == 2 && bm != null)
    }

    private var thumbPosted = false
    private fun thumbDone() {
        if (thumbPosted) return
        thumbPosted = true
        ui.postDelayed({ thumbPosted = false; treeAdapter.notifyDataSetChanged(); fileAdapter.notifyDataSetChanged() }, 120)
    }

    private fun menu() {
        val items = arrayOf("Rescan", "Export CSV", "Theme…", "Classic view")
        AppPal.dlg(act).setItems(items) { _, i -> when (i) { 0 -> host.rescan(); 1 -> export(); 2 -> pickTheme(); else -> host.classic() } }.show()
    }

    private fun pickTheme() {
        val p = AppPal.pal(act)
        val cur = WizThemes.pref(act)
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
        var dlg: android.app.AlertDialog? = null
        for (t in listOf(WizThemes.app(act)) + WizThemes.ALL) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(dp(8), dp(6), dp(12), dp(6))
                isClickable = true
                setOnClickListener { WizThemes.save(act, t.id); dlg?.dismiss(); host.rebuild() }
            }
            row.addView(WizSwatch(act, t), LinearLayout.LayoutParams(dp(88), dp(52)).apply { rightMargin = dp(14) })
            val nm = TextView(act).apply { text = if (t.id == "app") "App theme (follows the app)" else t.name; textSize = 16f; setTextColor(p.fg); maxLines = 2 }
            row.addView(nm, LinearLayout.LayoutParams(0, -2, 1f))
            if (t.id == cur) row.addView(TextView(act).apply { text = "✓"; textSize = 20f; setTextColor(p.ac) })
            list.addView(row)
        }
        val sc = android.widget.ScrollView(act).apply { addView(list) }
        dlg = AppPal.dlg(act).setTitle("Storage theme").setView(sc).setNegativeButton("Close", null).show()
    }

    private fun buildHeader(): View {
        val hd = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(8), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(mix(cBg, cAcc, 0.18f), cBg))
        }
        val v = host.volume()
        val known = v != null && v[2] > 0
        val totalVol = if (known) v!![2] else maxOf(1L, root.size)
        val used = if (known) v!![0] else root.size
        val r1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tc.addView(txt(host.label, 22f, cFg, true))
        tc.addView(txt("Scanned in " + String.format(Locale.US, "%.1f", scan.millis() / 1000.0) + " s  ·  " + String.format(Locale.US, "%,d", root.files) + " files", 13f, cDim))
        r1.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        if (known) {
            val ring = WizRing(act).apply { frac = used.toFloat() / totalVol; col = cAcc; track = mix(cBg, cFg, 0.16f); textCol = cFg }
            r1.addView(ring, LinearLayout.LayoutParams(dp(60), dp(60)))
        }
        r1.addView(tbtn("⋮", 24f) { menu() }.apply { setTextColor(cFg); minimumHeight = dp(48); minimumWidth = dp(48) })
        hd.addView(r1)
        // category bar: what the scanned files are (images / videos / ...), share of the volume
        val cats = root.cats
        val seg = WizSeg(act).apply { track = mix(cBg, cFg, 0.16f) }
        val legend = android.text.SpannableStringBuilder()
        if (cats != null) {
            val order = cats.indices.filter { cats[it] > 0 }.sortedByDescending { cats[it] }
            seg.set(FloatArray(order.size) { cats[order[it]].toFloat() / totalVol }, IntArray(order.size) { WizUtil.CATC[order[it].coerceIn(0, WizUtil.CATC.size - 1)] })
            val names = arrayOf("Other", "Images", "Videos", "Audio", "Docs", "Archives", "Apps")
            for (i in order.take(4)) {
                val a = legend.length
                legend.append("● "); legend.setSpan(android.text.style.ForegroundColorSpan(WizUtil.CATC[i.coerceIn(0, WizUtil.CATC.size - 1)]), a, legend.length, 0)
                legend.append(names[i.coerceIn(0, names.size - 1)] + " " + NlModel.fmt(cats[i]) + "     ")
            }
        }
        hd.addView(seg, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12); rightMargin = dp(8) })
        if (legend.isNotEmpty()) hd.addView(TextView(act).apply { text = legend; textSize = 13f; setTextColor(cDim); maxLines = 2 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val sp = if (known) NlModel.fmt(v!![0]) + " used of " + NlModel.fmt(v[2]) + "  ·  " + NlModel.fmt(v[1]) + " free" else "Scanned: " + NlModel.fmt(root.size) + "  ·  " + root.folders + " folders"
        hd.addView(txt(sp, 13f, cDim), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        return hd
    }

    private fun buildTabs(): View {
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg); setPadding(dp(12), dp(2), dp(12), dp(8)) }
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; background = round(cHdr, 26); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val names = arrayOf("Folders", "Files", "Types", "Treemap", "Apps")
        for (i in 0..(if (host.appsOk()) 4 else 3)) {
            val t = TextView(act).apply {
                text = names[i]; textSize = 14f; gravity = Gravity.CENTER; minimumHeight = dp(44); maxLines = 1; isClickable = true
                setOnClickListener { showTab(i) }
            }
            tabV[i] = t
            r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        wrap.addView(r)
        return wrap
    }

    private fun buildLists(host2: android.widget.FrameLayout) {
        // Folders
        val tb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        treeList = ListView(act).apply { adapter = treeAdapter }
        styleList(treeList)
        treeList.setOnItemClickListener { _, _, pos, _ -> if (pos in rowN.indices) pick(rowN[pos]) }
        treeList.setOnItemLongClickListener { _, _, pos, _ -> if (pos in rowN.indices) { host.details(rowN[pos]); true } else false }
        tb.addView(treeList, LinearLayout.LayoutParams(-1, 0, 1f))
        treeBox = tb
        // Files
        val fb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val fr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cBg); setPadding(dp(12), dp(8), dp(12), dp(4)) }
        filterEt = EditText(act).apply {
            hint = "Filter, e.g. *.jpg  >10mb  !tmp"; textSize = 15f; setTextColor(cFg); setHintTextColor(mix(cBg, cDim, 0.8f))
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT; background = round(cHdr, 14); minimumHeight = dp(48); setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    if (q == filterText) return
                    filterText = q
                    ui.removeCallbacks(refilter); ui.postDelayed(refilter, 300)
                }
            })
        }
        fr.addView(filterEt, LinearLayout.LayoutParams(0, -2, 1f))
        maxBtn = btn("Max 1000") { maxShow = when (maxShow) { 1000 -> 5000; 5000 -> 20000; else -> 1000 }; maxBtn.text = "Max $maxShow"; runFilter() }
        fr.addView(maxBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        fb.addView(fr)
        fileInfo = txt("", 13f, cDim).apply { setPadding(dp(16), dp(4), dp(16), dp(8)); setBackgroundColor(cBg) }
        fb.addView(fileInfo)
        fb.addView(hline(), LinearLayout.LayoutParams(-1, 1))
        fileList = ListView(act).apply { adapter = fileAdapter }
        styleList(fileList)
        fileList.setOnItemClickListener { _, _, pos, _ -> if (pos in shown.indices) pickFile(shown[pos]) }
        fileList.setOnItemLongClickListener { _, _, pos, _ -> if (pos in shown.indices) { host.details(shown[pos]); true } else false }
        fb.addView(fileList, LinearLayout.LayoutParams(-1, 0, 1f))
        fileBox = fb
        // Types
        val yb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        typeList = ListView(act).apply { adapter = typeAdapter }
        styleList(typeList)
        typeList.setOnItemClickListener { _, _, pos, _ -> if (pos in stats.indices) { toggleHl(stats[pos].key); if (hlKey != null) showTab(3) } }
        typeList.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in stats.indices && stats[pos].key != WizUtil.AGG) { val k = stats[pos].key; filterEt.setText(if (k.isEmpty()) "!*.*" else "*.$k"); showTab(1); true } else false
        }
        yb.addView(typeList, LinearLayout.LayoutParams(-1, 0, 1f))
        typeBox = yb
        host2.addView(tb, android.widget.FrameLayout.LayoutParams(-1, -1))
        host2.addView(fb, android.widget.FrameLayout.LayoutParams(-1, -1))
        host2.addView(yb, android.widget.FrameLayout.LayoutParams(-1, -1))
        mapBox = buildMapBox()
        host2.addView(mapBox, android.widget.FrameLayout.LayoutParams(-1, -1))    // Treemap: its own full-screen tab
        appsBox = buildApps()
        host2.addView(appsBox, android.widget.FrameLayout.LayoutParams(-1, -1))   // Apps: per-app sizes (StorageApps.kt)
    }

    private lateinit var treeBox: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var mapBox: View

    private fun buildMapBox(): View {
        val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cBg); setPadding(dp(16), dp(2), dp(4), dp(2)) }
        tmTitle.apply { textSize = 14f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        tr.addView(tmTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val zl = txt("", 13f, cDim).apply { setPadding(dp(4), 0, dp(4), 0) }
        map.onZoom = { z -> zl.text = if (z > 1.01f) String.format(Locale.US, "×%.1f", z) else "" }
        tr.addView(zl)
        tr.addView(tbtn("－", 20f) { map.zoomBy(1f / 1.8f) })
        tr.addView(tbtn("＋", 20f) { map.zoomBy(1.8f) })
        tr.addView(tbtn("1:1", 14f) { map.resetZoom() })
        tr.addView(tbtn("▲ Up", 14f) { focus.parent?.let { pick(it) } })
        b.addView(tr)
        map.clipToOutline = true
        map.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18f * d) }
        }
        b.addView(map, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
        return b
    }

    private fun styleStatus(t: TextView) {
        t.textSize = 13f; t.setTextColor(cFg); t.background = round(cHdr, 18); t.setPadding(dp(16), dp(10), dp(16), dp(12)); t.maxLines = 2; t.ellipsize = TextUtils.TruncateAt.MIDDLE
    }

    // ------------------------------------------------------------------ tabs
    private fun showTab(i: Int) {
        tab = i
        treeBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        fileBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        typeBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        mapBox.visibility = if (i == 3) View.VISIBLE else View.GONE
        appsBox.visibility = if (i == 4) View.VISIBLE else View.GONE
        for (k in 0..4) tabV[k]?.apply {
            setTextColor(if (k == i) cOnAcc else cDim)
            setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
            background = if (k == i) round(cAcc, 22) else null
        }
        if (i == 4) showApps()
        if (i == 1) { if (allFiles == null) buildAllFiles() else runFilter() }
    }

    // ------------------------------------------------------------------ focus / selection
    /** The treemap shows the files below [f]; the extension list and colours follow it. */
    private fun setFocus(f: SNode) {
        focus = f
        val seq = ++statSeq
        tmTitle.text = titleOf(f)
        Thread({
            val st = WizUtil.stats(f)
            ui.post {
                if (seq == statSeq) {
                    stats = st
                    colorMap = HashMap<String, Int>().also { m -> for (e in st) m[e.key] = e.color }
                    if (hlKey != null && st.none { it.key == hlKey }) { hlKey = null; map.hl = null }
                    map.setData(f, colorMap)
                    map.sel = sel
                    typeAdapter.notifyDataSetChanged()
                    paintStatus()
                }
            }
        }, "wiz-stats").also { it.isDaemon = true }.start()
    }

    private fun titleOf(f: SNode): String = (if (f === root) host.label else f.name) + "\\  (" + NlModel.fmt(f.size) + ")"

    /** Select [n] from the tree / Up button: a folder becomes the shown folder; a file keeps the shown folder when it is inside it. */
    private fun pick(n: SNode) {
        val again = lastTapped === n
        sel = n
        map.sel = n
        if (n.dir && n.kids.isNotEmpty()) { if (n !== focus) setFocus(n) }
        else if (!WizUtil.isUnder(n, focus)) setFocus(n.parent ?: root)
        if (n.dir && n.kids.isNotEmpty() && again) toggleExpand(n)   // second tap on the same folder: expand / collapse it
        lastTapped = n
        revealRow(n)
        paintStatus()
        treeAdapter.notifyDataSetChanged()
    }

    private var lastTapped: SNode? = null

    private fun pickFromMap(n: SNode) {
        sel = n; lastTapped = n
        map.sel = n
        expandPath(n)
        rebuildRows()
        revealRow(n)
        paintStatus()
    }

    private fun pickFile(n: SNode) {
        sel = n; lastTapped = n
        val f = n.parent ?: root
        if (!WizUtil.isUnder(n, focus)) setFocus(f)
        map.sel = n
        expandPath(n)
        rebuildRows()
        paintStatus()
        Toast.makeText(act, n.path(), Toast.LENGTH_SHORT).show()
    }

    private fun toggleHl(key: String) {
        if (key == WizUtil.AGG) return
        hlKey = if (hlKey == key) null else key
        map.hl = hlKey
        typeAdapter.notifyDataSetChanged()
        paintStatus()
    }

    fun onBack(): Boolean {
        if (map.zoomed) { map.resetZoom(); return true }
        if (hlKey != null) { toggleHl(hlKey!!); return true }
        val up = focus.parent
        if (up != null) { pick(up); return true }
        return false
    }

    /** After a delete: the tree was patched in place; show the nearest folder that is still there. */
    fun refresh() {
        sel = null; map.sel = null; lastTapped = null
        val f = host.nearest(focus)
        allFiles = null
        rebuildRows()
        setFocus(f)
        if (tab == 1) buildAllFiles()
        paintStatus()
    }

    fun select(n: SNode) { pick(n) }

    private fun paintStatus() {
        val s = sel
        val hk = hlKey
        val left: String
        if (hk != null) {
            val e = stats.firstOrNull { it.key == hk }
            left = "Highlighted: " + (if (hk.isEmpty()) "(No Extension)" else ".$hk") + "   Files: " + String.format(Locale.US, "%,d", e?.count ?: 0L) + "   Total Size: " + NlModel.fmt(e?.size ?: 0L)
        } else {
            val n = s ?: focus
            left = (if (n.dir) "Selected Files: " + String.format(Locale.US, "%,d", n.files) + "   Folders: " + String.format(Locale.US, "%,d", n.folders) else "Selected File: 1") +
                "   Total Size: " + NlModel.fmt(n.size) + (if (n.dir && root.size > 0) "   (" + WizUtil.pct(n.size, root.size) + " of scan)" else "")
        }
        status.text = left + (if (s != null && hk == null) "\n" + (if (s.agg) "small files in " else "") + s.path() else "")
    }

    // ------------------------------------------------------------------ tree rows
    private fun rebuildRows() {
        rowN.clear(); rowD.clear()
        fun walk(n: SNode, depth: Int) {
            rowN.add(n); rowD.add(depth)
            if (n.dir && !n.agg && expanded.contains(n)) for (k in n.kids) walk(k, depth + 1)
        }
        walk(root, 0)
        treeAdapter.notifyDataSetChanged()
    }

    private fun expandPath(n: SNode) { var x = n.parent; while (x != null) { expanded.add(x); x = x.parent } }

    private fun toggleExpand(n: SNode) {
        if (!n.dir || n.kids.isEmpty()) return
        if (!expanded.remove(n)) expanded.add(n)
        rebuildRows()
    }

    private fun revealRow(n: SNode) {
        expandPath(n)
        rebuildRows()
        val i = rowN.indexOfFirst { it === n }
        if (i >= 0 && tab == 0) { val first = treeList.firstVisiblePosition; val last = treeList.lastVisiblePosition; if (i < first || i > last) treeList.setSelectionFromTop(i, dp(60)) }
    }

    private inner class TreeRow(c: Context) : LinearLayout(c) {
        val indent = View(c)
        val tog = TextView(c).apply { textSize = 22f; setTextColor(cAcc); gravity = Gravity.CENTER; isClickable = true }
        val sw = WizGlyph(c)
        val name = TextView(c).apply { textSize = 16f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val meta = TextView(c).apply { textSize = 13f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val size = TextView(c).apply { textSize = 15f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        val bar = WizBar(c).apply { fillCol = cAcc; fillCol2 = mix(cAcc, Color.WHITE, 0.38f); trackCol = mix(cBg, cFg, 0.16f) }
        var pos = -1
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(6), dp(12), dp(6)); minimumHeight = dp(64)
            addView(indent, LinearLayout.LayoutParams(0, 1))
            addView(tog, LinearLayout.LayoutParams(dp(30), dp(48)))
            addView(sw, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(name); mid.addView(meta)
            addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; minimumWidth = dp(72) }
            right.addView(size, LinearLayout.LayoutParams(-1, -2))
            right.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(7) })
            addView(right, LinearLayout.LayoutParams(dp(72), -2).apply { leftMargin = dp(8) })
            tog.setOnClickListener { if (pos in rowN.indices) { toggleExpand(rowN[pos]); treeAdapter.notifyDataSetChanged() } }
        }
    }

    private val treeAdapter = object : BaseAdapter() {
        override fun getCount() = rowN.size
        override fun getItem(p: Int): Any = rowN[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? TreeRow) ?: TreeRow(act)
            val n = rowN[p]
            v.pos = p
            v.indent.layoutParams = LinearLayout.LayoutParams(dp(7) * minOf(rowD[p], 8), 1)   // narrow steps, capped: deep levels keep room for the names
            v.tog.text = if (n.dir && n.kids.isNotEmpty() && !n.agg) (if (expanded.contains(n)) "−" else "+") else ""
            if (n.agg) v.sw.set(1, WizUtil.GRAY, "…") else if (n.dir) v.sw.set(0, WizUtil.FOLDER) else setFileGlyph(v.sw, n)
            v.name.text = if (n.agg) "<" + n.files + " small files>" else if (n.parent == null) host.label else n.name
            v.name.setTextColor(if (n.denied) cErr else cFg)
            v.name.setTypeface(null, if (n.dir && !n.agg) Typeface.BOLD else Typeface.NORMAL)
            val par = n.parent?.size ?: n.size
            v.bar.frac = if (par > 0) n.size.toFloat() / par else 0f
            val fc = if (n.agg) n.files else if (n.dir) n.files else 0L
            v.meta.text = WizUtil.pct(n.size, par) + (if (fc > 0) "  ·  " + String.format(Locale.US, "%,d", fc) + (if (fc == 1L) " file" else " files") else "")
            v.size.text = NlModel.fmt(n.size)
            v.background = card(n === sel)
            v.bar.invalidate()
            return v
        }
    }

    // ------------------------------------------------------------------ types
    private inner class TypeRow(c: Context) : LinearLayout(c) {
        val sw = WizGlyph(c)
        val ext = TextView(c).apply { textSize = 16f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) }
        val meta = TextView(c).apply { textSize = 13f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val size = TextView(c).apply { textSize = 15f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        val bar = WizBar(c).apply { fillCol = cAcc; fillCol2 = mix(cAcc, Color.WHITE, 0.38f); trackCol = mix(cBg, cFg, 0.16f) }
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); minimumHeight = dp(64)
            addView(sw, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(ext); mid.addView(meta)
            addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; minimumWidth = dp(84) }
            right.addView(size, LinearLayout.LayoutParams(-1, -2))
            right.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(7) })
            addView(right, LinearLayout.LayoutParams(dp(84), -2).apply { leftMargin = dp(12) })
        }
    }

    private val typeAdapter = object : BaseAdapter() {
        override fun getCount() = stats.size
        override fun getItem(p: Int): Any = stats[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? TypeRow) ?: TypeRow(act)
            val e = stats[p]
            v.sw.set(1, e.color, if (e.key == WizUtil.AGG) "…" else e.key.uppercase().ifEmpty { "·" })
            v.ext.text = if (e.key == WizUtil.AGG) "Small files" else if (e.key.isEmpty()) "No extension" else WizUtil.type(e.key)
            val tot = focus.size
            val files = String.format(Locale.US, "%,d", e.count) + (if (e.count == 1L) " file" else " files")
            v.meta.text = files + "  ·  " + WizUtil.pct(e.size, tot)
            v.bar.frac = if (tot > 0) e.size.toFloat() / tot else 0f
            v.size.text = NlModel.fmt(e.size)
            v.background = card(false, e.key == hlKey)
            v.bar.invalidate()
            return v
        }
    }

    // ------------------------------------------------------------------ file view
    private val refilter = Runnable { runFilter() }

    private fun buildAllFiles() {
        if (building) return
        building = true
        fileInfo.text = "Reading the file list…"
        Thread({
            val out = ArrayList<SNode>()
            val st = ArrayList<SNode>()
            st.add(root)
            while (st.isNotEmpty()) {
                val n = st.removeAt(st.size - 1)
                if (n.agg) continue
                if (n.dir) { for (k in n.kids) st.add(k) } else out.add(n)
            }
            out.sortByDescending { it.size }
            ui.post { allFiles = out; building = false; runFilter() }
        }, "wiz-files").also { it.isDaemon = true }.start()
    }

    private fun runFilter() {
        val all = allFiles ?: return
        val seq = ++fileSeq
        val txt = filterText
        val cap = maxShow
        Thread({
            val f = WizFilter(txt)
            val out = ArrayList<SNode>()
            var cnt = 0
            var sz = 0L
            for (n in all) {
                if (f.empty || f.matches(n.name, n.size, if (f.needPath) n.path() else null)) {
                    cnt++; sz += n.size
                    if (out.size < cap) out.add(n)
                }
            }
            ui.post {
                if (seq == fileSeq) {
                    shown = out; matchCount = cnt; matchSize = sz
                    fileInfo.text = String.format(Locale.US, "%,d", cnt) + " files  ·  " + NlModel.fmt(sz) + (if (cnt > out.size) "  ·  showing the biggest ${out.size}" else "")
                    fileAdapter.notifyDataSetChanged()
                    fileList.setSelection(0)
                }
            }
        }, "wiz-filter").also { it.isDaemon = true }.start()
    }

    private inner class FileRow(c: Context) : LinearLayout(c) {
        val sw = WizGlyph(c)
        val name = TextView(c).apply { textSize = 16f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val dir = TextView(c).apply { textSize = 13f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        val size = TextView(c).apply { textSize = 15f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); minimumHeight = dp(64)
            addView(sw, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(name); col.addView(dir)
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(size, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        }
    }

    private val fileAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? FileRow) ?: FileRow(act)
            val n = shown[p]
            setFileGlyph(v.sw, n)
            v.name.text = n.name
            v.dir.text = n.parent?.path() ?: "/"
            v.size.text = NlModel.fmt(n.size)
            v.background = card(n === sel)
            return v
        }
    }


    // ------------------------------------------------------------------ apps (StorageApps.kt)
    private var apps: List<AppRec>? = null
    private var appsLoading = false
    private var appSort = 0                      // 0 size, 1 cache, 2 name
    private var appSys = false                   // include system apps
    private var appShown: List<AppRec> = emptyList()
    private var appMax = 1L
    private lateinit var appsPerm: LinearLayout
    private lateinit var appsMain: LinearLayout
    private lateinit var appsList: ListView
    private lateinit var appsInfo: TextView
    private lateinit var appSortBtn: TextView
    private lateinit var appSysBtn: TextView

    /** Back from the system settings (Usage access): check again. */
    fun onResume() { if (tab == 4) showApps() }

    private fun buildApps(): LinearLayout {
        val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }
        val pm = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
        pm.addView(TextView(act).apply { text = "Allow usage access"; textSize = 20f; setTextColor(cFg); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER })
        pm.addView(TextView(act).apply {
            text = "Android only tells an app how big the other apps are when Usage access is switched on for it. Only the sizes are read, never the files inside other apps."
            textSize = 15f; setTextColor(cDim); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24))
        })
        pm.addView(TextView(act).apply {
            text = "Open settings"; textSize = 15f; setTextColor(cOnAcc); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            minimumHeight = dp(48); setPadding(dp(28), dp(10), dp(28), dp(10)); background = round(cAcc, 24); isClickable = true
            setOnClickListener { AppsUtil.openUsageSettings(act) }
        })
        appsPerm = pm
        val m = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        appSortBtn = btn("Sort: Size") { appSort = (appSort + 1) % 3; applyApps() }
        appSysBtn = btn("User apps") { appSys = !appSys; applyApps() }
        chips.addView(appSortBtn)
        chips.addView(appSysBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        m.addView(chips)
        appsInfo = txt("", 13f, cDim).apply { setPadding(dp(16), dp(4), dp(16), dp(8)) }
        m.addView(appsInfo)
        m.addView(hline(), LinearLayout.LayoutParams(-1, 1))
        appsList = ListView(act).apply { adapter = appsAdapter }
        styleList(appsList)
        appsList.setOnItemClickListener { _, _, pos, _ -> if (pos in appShown.indices) appDialog(appShown[pos]) }
        m.addView(appsList, LinearLayout.LayoutParams(-1, 0, 1f))
        appsMain = m
        b.addView(pm, LinearLayout.LayoutParams(-1, 0, 1f))
        b.addView(m, LinearLayout.LayoutParams(-1, 0, 1f))
        return b
    }

    private fun showApps() {
        val ok = AppsUtil.hasUsage(act)
        appsPerm.visibility = if (ok) View.GONE else View.VISIBLE
        appsMain.visibility = if (ok) View.VISIBLE else View.GONE
        if (!ok) { apps = null; return }
        if (apps == null) loadApps()
    }

    private fun loadApps() {
        if (appsLoading) return
        appsLoading = true
        appsInfo.text = "Reading app sizes…"
        val px = dp(48)
        Thread({
            val l = AppsUtil.load(act, px)
            ui.post { apps = l; appsLoading = false; applyApps() }
        }, "wiz-apps").also { it.isDaemon = true }.start()
    }

    private fun applyApps() {
        appSortBtn.text = "Sort: " + (if (appSort == 0) "Size" else if (appSort == 1) "Cache" else "Name")
        appSysBtn.text = if (appSys) "All apps" else "User apps"
        val all = apps ?: return
        val l = all.filter { appSys || !it.system }
        val sorted = when (appSort) { 0 -> l.sortedByDescending { it.total }; 1 -> l.sortedByDescending { it.cache }; else -> l.sortedBy { it.label.lowercase() } }
        appShown = sorted
        appMax = maxOf(1L, sorted.maxOfOrNull { if (appSort == 1) it.cache else it.total } ?: 1L)
        appsInfo.text = sorted.size.toString() + " apps  ·  " + NlModel.fmt(sorted.sumOf { it.total }) + "  ·  cache " + NlModel.fmt(sorted.sumOf { it.cache })
        appsAdapter.notifyDataSetChanged()
        appsList.setSelection(0)
    }

    private fun appDialog(a: AppRec) {
        val msg = "Total   " + NlModel.fmt(a.total) + "\nApp   " + NlModel.fmt(a.code) + "\nData   " + NlModel.fmt(a.userData) + "\nCache   " + NlModel.fmt(a.cache) +
            "\n\n" + a.pkg + "\n\nClear cache / data is in App settings, under Storage."
        AppPal.dlg(act).setTitle(a.label).setMessage(msg)
            .setPositiveButton("App settings") { _, _ -> AppsUtil.openAppInfo(act, a.pkg) }.setNegativeButton("Close", null).show()
    }

    private inner class AppRow(c: Context) : LinearLayout(c) {
        val icon = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = TextView(c).apply { textSize = 16f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) }
        val meta = TextView(c).apply { textSize = 13f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val size = TextView(c).apply { textSize = 15f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        val bar = WizBar(c).apply { fillCol = cAcc; fillCol2 = mix(cAcc, Color.WHITE, 0.38f); trackCol = mix(cBg, cFg, 0.16f) }
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); minimumHeight = dp(68)
            addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
            val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            mid.addView(name); mid.addView(meta)
            addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; minimumWidth = dp(84) }
            right.addView(size, LinearLayout.LayoutParams(-1, -2))
            right.addView(bar, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(7) })
            addView(right, LinearLayout.LayoutParams(dp(84), -2).apply { leftMargin = dp(12) })
        }
    }

    private val appsAdapter = object : BaseAdapter() {
        override fun getCount() = appShown.size
        override fun getItem(p: Int): Any = appShown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? AppRow) ?: AppRow(act)
            val a = appShown[p]
            v.background = card(false)
            v.icon.setImageBitmap(a.icon)
            v.name.text = a.label
            v.meta.text = (if (a.ver.isNotEmpty()) "v" + a.ver + "  ·  " else "") + "cache " + NlModel.fmt(a.cache)
            v.size.text = NlModel.fmt(a.total)
            v.bar.frac = (if (appSort == 1) a.cache else a.total).toFloat() / appMax
            v.bar.invalidate()
            return v
        }
    }

    // ------------------------------------------------------------------ export (CSV)
    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun export() {
        Thread({
            try {
                val sb = StringBuilder()
                sb.append('﻿')
                when (tab) {
                    1 -> {
                        sb.append("File Name,Size,Folder\n")
                        for (n in shown) sb.append(q(n.name)).append(',').append(n.size).append(',').append(q(n.parent?.path() ?: "/")).append('\n')
                    }
                    4 -> {
                        sb.append("Package,Name,Version,Total,App,Data,Cache\n")
                        for (a in appShown) sb.append(q(a.pkg)).append(',').append(q(a.label)).append(',').append(q(a.ver)).append(',').append(a.total).append(',').append(a.code).append(',').append(a.userData).append(',').append(a.cache).append('\n')
                    }
                    2 -> {
                        sb.append("Extension,File Type,Files,Size\n")
                        for (e in stats) sb.append(q(if (e.key == WizUtil.AGG) "(small files)" else e.key)).append(',').append(q(WizUtil.type(e.key))).append(',').append(e.count).append(',').append(e.size).append('\n')
                    }
                    else -> {
                        sb.append("Path,Size,Files,Folders\n")
                        val st = ArrayList<SNode>()
                        st.add(focus)
                        var lines = 0
                        while (st.isNotEmpty() && lines < 200_000) {
                            val n = st.removeAt(st.size - 1)
                            if (n.agg) continue
                            sb.append(q(n.path())).append(',').append(n.size).append(',').append(n.files).append(',').append(if (n.dir) n.folders else 0).append('\n'); lines++
                            if (n.dir) for (i in n.kids.indices.reversed()) st.add(n.kids[i])
                        }
                    }
                }
                val dir = File(act.cacheDir, "open"); dir.mkdirs()
                val f = File(dir, "wiztree-" + (if (tab == 1) "files" else if (tab == 2) "types" else if (tab == 4) "apps" else "folders") + ".csv")
                f.writeText(sb.toString())
                ui.post {
                    try {
                        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
                        val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        act.startActivity(Intent.createChooser(i, "Export CSV"))
                    } catch (e: Exception) { Toast.makeText(act, "Cannot share the CSV: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Throwable) { ui.post { Toast.makeText(act, "Export failed: " + (e.message ?: ""), Toast.LENGTH_LONG).show() } }
        }, "wiz-export").also { it.isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ start (kept last: every property above must exist first)
    private fun start() {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(cBg)
        expanded.add(root)
        view.addView(buildHeader(), LinearLayout.LayoutParams(-1, -2))
        view.addView(buildTabs(), LinearLayout.LayoutParams(-1, -2))
        // every tab (Tree View, File View, Types, Treemap) gets the whole body, one at a time
        val body = android.widget.FrameLayout(act)
        buildLists(body)
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(status.also { styleStatus(it) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(12)) })
        map.bgCol = cHdr
        host.bars(cBg, !night)
        map.dimCol = mix(cBg, cFg, 0.16f)
        map.fileOf = { n -> host.realFile(n) }
        map.onPick = { n -> pickFromMap(n) }
        map.onOpen = { n -> if (n.kids.isNotEmpty()) pick(n) }
        map.onLong = { n -> host.details(n) }
        rebuildRows()
        setFocus(root)
        showTab(0)
        paintStatus()
    }


    init { start() }
}
