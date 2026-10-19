package com.lanshare.app

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
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
    fun nearest(n: SNode): SNode        // [n] when still in the tree, else its nearest ancestor that is (after a delete)
}

internal class WizExt(val key: String, val size: Long, val count: Long, var color: Int)

internal object WizUtil {
    const val AGG = "\u0001"
    private fun c(v: Long) = v.toInt()
    val GRAY = c(0xFF707070)
    val DIM = c(0xFF3A3A3A)
    val RED = c(0xFFF01818)
    val FOLDER = c(0xFFE8B93C)
    val PAL = longArrayOf(0xFFF08A1C, 0xFFD4CB1E, 0xFF2DB84D, 0xFFA848E0, 0xFFE0408C, 0xFF1E9BFF, 0xFFF01A1A, 0xFFF030D0,
        0xFF86C21E, 0xFF7F72F5, 0xFF1ED8B8, 0xFFB0703A, 0xFF4CD0F0, 0xFFF0C040, 0xFF9C6BFF, 0xFF40A080).map { c(it) }.toIntArray()
    val CATC = intArrayOf(c(0xFF707070), c(0xFF2DB84D), c(0xFFA848E0), c(0xFFE0408C), c(0xFF1E9BFF), c(0xFFF08A1C), c(0xFFD4CB1E))   // SCat order

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

/** A tiny percentage bar with its text inside (WizTree "Percent" column). */
internal class WizBar(ctx: Context) : View(ctx) {
    var frac = 0f
    var label = ""
    var fillCol = Color.rgb(0x5A, 0x6E, 0x88)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        p.color = Color.rgb(0x2A, 0x2A, 0x2A); c.drawRect(0f, 1f * d, width.toFloat(), height - 1f * d, p)
        p.color = fillCol; c.drawRect(0f, 1f * d, width * frac.coerceIn(0f, 1f), height - 1f * d, p)
        p.color = Color.WHITE; p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER
        c.drawText(label, width / 2f, height / 2f + 4f * d, p)
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
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor, d.focusX, d.focusY); return true }
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
        if (n.agg) return if (h != null) WizUtil.DIM else WizUtil.GRAY
        if (n.dir) {
            if (h != null) return WizUtil.DIM
            val cs = n.cats ?: return WizUtil.GRAY
            var b = 0
            for (i in cs.indices) if (cs[i] > cs[b]) b = i
            return WizUtil.shade(WizUtil.CATC[b.coerceIn(0, WizUtil.CATC.size - 1)], 0.8f)
        }
        val key = WizUtil.extOf(n.name)
        if (h != null) return if (key == h) WizUtil.RED else WizUtil.DIM
        return colors[key] ?: WizUtil.GRAY
    }

    private fun render() {
        val w = width; val hh = height
        if (w < 8 || hh < 8) return
        var b = bmp
        if (b == null || b.width != w || b.height != hh) { b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888); bmp = b }
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
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
        fill.shader = RadialGradient(x + w * 0.35f, y + h * 0.3f, maxOf(w, h) * 0.85f,
            intArrayOf(WizUtil.shade(base, 1.4f), base, WizUtil.shade(base, 0.4f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(x + 0.5f, y + 0.5f, x + w - 0.5f, y + h - 0.5f, fill)
        fill.shader = null
        // labels sit in the visible part of the tile (when zoomed in a tile can reach far outside the view)
        val vx = maxOf(x, 0f); val vy = maxOf(y, 0f)
        val vw = minOf(x + w, width.toFloat()) - vx; val vh = minOf(y + h, height.toFloat()) - vy
        if (vw > 74f * d && vh > 30f * d) {   // big tile: its name (and size) in white with a dark outline, like the labels of other treemap tools
            val nm = if (n.agg) n.files.toString() + " small files" else n.name
            val s = TextUtils.ellipsize(nm, android.text.TextPaint(txt), vw - 8f * d, TextUtils.TruncateAt.END).toString()
            txt.style = Paint.Style.STROKE; txt.strokeWidth = 2.5f; txt.color = Color.BLACK; c.drawText(s, vx + 4f * d, vy + 14f * d, txt)
            txt.style = Paint.Style.FILL; txt.color = Color.WHITE; c.drawText(s, vx + 4f * d, vy + 14f * d, txt)
            if (vh > 46f * d) {
                val z = NlModel.fmt(n.size)
                txt.style = Paint.Style.STROKE; txt.strokeWidth = 2.5f; txt.color = Color.BLACK; c.drawText(z, vx + 4f * d, vy + 27f * d, txt)
                txt.style = Paint.Style.FILL; txt.color = Color.WHITE; c.drawText(z, vx + 4f * d, vy + 27f * d, txt)
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) { c.drawColor(Color.BLACK); return }
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

    private val cBg = Color.BLACK
    private val cFg = Color.rgb(0xE2, 0xE2, 0xE2)
    private val cDim = Color.rgb(0x9A, 0x9A, 0x9A)
    private val cHdr = Color.rgb(0x2B, 0x2B, 0x2B)
    private val cPanel = Color.rgb(0x16, 0x16, 0x16)
    private val cSel = Color.rgb(0x0B, 0x5C, 0xAD)
    private val cAcc = Color.rgb(0x2D, 0x9B, 0xFF)

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
    private val tabV = arrayOfNulls<TextView>(3)
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
    private fun txt(t: String, sp: Float = 12f, col: Int = cFg, bold: Boolean = false) = TextView(act).apply {
        text = t; textSize = sp; setTextColor(col); if (bold) setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    private fun btn(t: String, fn: () -> Unit) = TextView(act).apply {
        text = t; textSize = 12f; setTextColor(cFg); gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)); setBackgroundColor(cHdr)
        isClickable = true; setOnClickListener { fn() }
    }

    private fun buildHeader(): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cPanel); setPadding(dp(10), dp(6), dp(10), dp(6)) }
        val r1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r1.addView(txt("[" + host.label + "]  -  WizTree", 14f, cFg, true), LinearLayout.LayoutParams(0, -2, 1f))
        r1.addView(btn("Rescan") { host.rescan() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        r1.addView(btn("Export") { export() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        r1.addView(btn("Classic") { host.classic() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        col.addView(r1)
        col.addView(txt("Scan complete in " + String.format(Locale.US, "%.2f", scan.millis() / 1000.0) + " seconds", 12f, cDim), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        col.addView(View(act).apply { setBackgroundColor(Color.rgb(0x00, 0x78, 0xD7)) }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(3) })
        val v = host.volume()
        val total = root.size
        val sp = if (v != null && v[2] > 0) "Space:  Total " + NlModel.fmt(v[2]) + "  ·  Used " + NlModel.fmt(v[0]) + "  ·  Free " + NlModel.fmt(v[1])
        else "Scanned: " + NlModel.fmt(total) + "  ·  " + root.files + " files  ·  " + root.folders + " folders"
        col.addView(txt(sp, 12f, cDim), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        return col
    }

    private fun buildTabs(): View {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(cBg); setPadding(dp(4), dp(4), dp(4), 0) }
        val names = arrayOf("Tree View", "File View", "Types")
        for (i in 0..2) {
            val t = TextView(act).apply {
                text = names[i]; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8)); isClickable = true
                setOnClickListener { showTab(i) }
            }
            tabV[i] = t
            r.addView(t, LinearLayout.LayoutParams(-2, -2))
        }
        return r
    }

    private fun colHeader(vararg cols: Triple<String, Int, Int>): View {   // (title, width dp or 0 = flexible, gravity)
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(cHdr); setPadding(dp(6), dp(4), dp(6), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        for (c in cols) {
            val t = txt(c.first, 11f, cDim).apply { gravity = c.third }
            r.addView(t, if (c.second == 0) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(dp(c.second), -2))
        }
        return r
    }

    private fun buildLists(host2: android.widget.FrameLayout) {
        // Tree View
        val tb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tb.addView(colHeader(Triple("Folder", 0, Gravity.START), Triple("Percent", 58, Gravity.CENTER), Triple("Size", 58, Gravity.END), Triple("Files", 44, Gravity.END)))
        treeList = ListView(act).apply { divider = null; dividerHeight = 0; setBackgroundColor(cBg); adapter = treeAdapter; isVerticalScrollBarEnabled = true }
        treeList.setOnItemClickListener { _, _, pos, _ -> if (pos in rowN.indices) pick(rowN[pos]) }
        treeList.setOnItemLongClickListener { _, _, pos, _ -> if (pos in rowN.indices) { host.details(rowN[pos]); true } else false }
        tb.addView(treeList, LinearLayout.LayoutParams(-1, 0, 1f))
        treeBox = tb
        // File View
        val fb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val fr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cPanel); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        filterEt = EditText(act).apply {
            hint = "Filter:  *.jpg   a|b   !tmp   >10mb   <1gb"; textSize = 13f; setTextColor(cFg); setHintTextColor(Color.rgb(0x70, 0x70, 0x70))
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT; setBackgroundColor(Color.rgb(0x24, 0x24, 0x24)); setPadding(dp(8), dp(6), dp(8), dp(6))
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
        fr.addView(maxBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        fb.addView(fr)
        fileInfo = txt("", 11f, cDim).apply { setPadding(dp(8), dp(3), dp(8), dp(3)); setBackgroundColor(cBg) }
        fb.addView(fileInfo)
        fb.addView(colHeader(Triple("File name", 0, Gravity.START), Triple("Size", 64, Gravity.END)))
        fileList = ListView(act).apply { divider = null; dividerHeight = 0; setBackgroundColor(cBg); adapter = fileAdapter }
        fileList.setOnItemClickListener { _, _, pos, _ -> if (pos in shown.indices) pickFile(shown[pos]) }
        fileList.setOnItemLongClickListener { _, _, pos, _ -> if (pos in shown.indices) { host.details(shown[pos]); true } else false }
        fb.addView(fileList, LinearLayout.LayoutParams(-1, 0, 1f))
        fileBox = fb
        // Types
        val yb = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        yb.addView(colHeader(Triple("Extension", 70, Gravity.START), Triple("File type", 0, Gravity.START), Triple("Percent", 58, Gravity.CENTER), Triple("Size", 58, Gravity.END), Triple("Files", 48, Gravity.END)))
        typeList = ListView(act).apply { divider = null; dividerHeight = 0; setBackgroundColor(cBg); adapter = typeAdapter }
        typeList.setOnItemClickListener { _, _, pos, _ -> if (pos in stats.indices) toggleHl(stats[pos].key) }
        typeList.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in stats.indices && stats[pos].key != WizUtil.AGG) { val k = stats[pos].key; filterEt.setText(if (k.isEmpty()) "!*.*" else "*.$k"); showTab(1); true } else false
        }
        yb.addView(typeList, LinearLayout.LayoutParams(-1, 0, 1f))
        typeBox = yb
        host2.addView(tb, android.widget.FrameLayout.LayoutParams(-1, -1))
        host2.addView(fb, android.widget.FrameLayout.LayoutParams(-1, -1))
        host2.addView(yb, android.widget.FrameLayout.LayoutParams(-1, -1))
    }

    private lateinit var treeBox: LinearLayout

    private fun buildMapBox(): View {
        val b = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg); setPadding(0, dp(2), 0, 0) }
        val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(cPanel); setPadding(dp(6), dp(3), dp(6), dp(3)) }
        tmTitle.apply { textSize = 11f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        tr.addView(tmTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val zl = txt("", 11f, cDim).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }
        map.onZoom = { z -> zl.text = if (z > 1.01f) String.format(Locale.US, "×%.1f", z) else "" }
        tr.addView(zl)
        tr.addView(txt("－", 15f, cAcc).apply { setPadding(dp(8), dp(0), dp(8), dp(0)); isClickable = true; setOnClickListener { map.zoomBy(1f / 1.8f) } })
        tr.addView(txt("＋", 15f, cAcc).apply { setPadding(dp(8), dp(0), dp(8), dp(0)); isClickable = true; setOnClickListener { map.zoomBy(1.8f) } })
        tr.addView(txt("1:1", 11f, cAcc).apply { setPadding(dp(6), dp(2), dp(6), dp(2)); isClickable = true; setOnClickListener { map.resetZoom() } })
        tr.addView(txt("▲ Up", 11f, cAcc).apply { setPadding(dp(8), dp(2), dp(4), dp(2)); isClickable = true; setOnClickListener { focus.parent?.let { pick(it) } } })
        b.addView(tr)
        b.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        return b
    }

    private fun styleStatus(t: TextView) {
        t.textSize = 11f; t.setTextColor(cFg); t.setBackgroundColor(cHdr); t.setPadding(dp(8), dp(5), dp(8), dp(5)); t.maxLines = 2; t.ellipsize = TextUtils.TruncateAt.MIDDLE
    }

    // ------------------------------------------------------------------ tabs
    private fun showTab(i: Int) {
        tab = i
        treeBox.visibility = if (i == 0) View.VISIBLE else View.GONE
        fileBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        typeBox.visibility = if (i == 2) View.VISIBLE else View.GONE
        for (k in 0..2) tabV[k]?.apply {
            setTextColor(if (k == i) Color.WHITE else cDim)
            setBackgroundColor(if (k == i) cHdr else cBg)
            setTypeface(null, if (k == i) Typeface.BOLD else Typeface.NORMAL)
        }
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
        val tog = TextView(c).apply { textSize = 15f; setTextColor(cAcc); gravity = Gravity.CENTER; isClickable = true }
        val sw = View(c)
        val name = TextView(c).apply { textSize = 12.5f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val bar = WizBar(c)
        val size = TextView(c).apply { textSize = 11.5f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        val files = TextView(c).apply { textSize = 11.5f; setTextColor(cDim); gravity = Gravity.END; maxLines = 1 }
        var pos = -1
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0)
            addView(indent, LinearLayout.LayoutParams(0, 1))
            addView(tog, LinearLayout.LayoutParams(dp(24), dp(30)))
            addView(sw, LinearLayout.LayoutParams(dp(12), dp(10)).apply { rightMargin = dp(6) })
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bar, LinearLayout.LayoutParams(dp(58), dp(18)).apply { leftMargin = dp(4) })
            addView(size, LinearLayout.LayoutParams(dp(58), -2))
            addView(files, LinearLayout.LayoutParams(dp(44), -2))
            minimumHeight = dp(30)
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
            v.indent.layoutParams = LinearLayout.LayoutParams(dp(14) * rowD[p], 1)
            v.tog.text = if (n.dir && n.kids.isNotEmpty() && !n.agg) (if (expanded.contains(n)) "−" else "+") else ""
            v.sw.setBackgroundColor(if (n.agg) WizUtil.GRAY else if (n.dir) WizUtil.FOLDER else (colorMap[WizUtil.extOf(n.name)] ?: WizUtil.GRAY))
            v.name.text = if (n.agg) "<" + n.files + " small files>" else if (n.parent == null) host.label else n.name
            v.name.setTextColor(if (n.denied) Color.rgb(0xE0, 0x70, 0x60) else if (n.dir) Color.WHITE else cFg)
            val par = n.parent?.size ?: n.size
            v.bar.frac = if (par > 0) n.size.toFloat() / par else 0f
            v.bar.label = WizUtil.pct(n.size, par)
            v.size.text = NlModel.fmt(n.size)
            v.files.text = if (n.agg) n.files.toString() else if (n.dir) String.format(Locale.US, "%,d", n.files) else ""
            v.setBackgroundColor(if (n === sel) cSel else Color.TRANSPARENT)
            v.bar.invalidate()
            return v
        }
    }

    // ------------------------------------------------------------------ types
    private inner class TypeRow(c: Context) : LinearLayout(c) {
        val sw = View(c)
        val ext = TextView(c).apply { textSize = 12.5f; setTextColor(cFg); maxLines = 1 }
        val type = TextView(c).apply { textSize = 12f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val bar = WizBar(c)
        val size = TextView(c).apply { textSize = 11.5f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        val cntT = TextView(c).apply { textSize = 11.5f; setTextColor(cDim); gravity = Gravity.END; maxLines = 1 }
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0); minimumHeight = dp(30)
            addView(sw, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(6) })
            addView(ext, LinearLayout.LayoutParams(dp(52), -2))
            addView(type, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bar, LinearLayout.LayoutParams(dp(58), dp(18)))
            addView(size, LinearLayout.LayoutParams(dp(58), -2))
            addView(cntT, LinearLayout.LayoutParams(dp(48), -2))
        }
    }

    private val typeAdapter = object : BaseAdapter() {
        override fun getCount() = stats.size
        override fun getItem(p: Int): Any = stats[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? TypeRow) ?: TypeRow(act)
            val e = stats[p]
            v.sw.setBackgroundColor(e.color)
            v.ext.text = if (e.key == WizUtil.AGG) "…" else if (e.key.isEmpty()) "" else "." + e.key
            v.type.text = WizUtil.type(e.key)
            val tot = focus.size
            v.bar.frac = if (tot > 0) e.size.toFloat() / tot else 0f
            v.bar.label = WizUtil.pct(e.size, tot)
            v.size.text = NlModel.fmt(e.size)
            v.cntT.text = String.format(Locale.US, "%,d", e.count)
            v.setBackgroundColor(if (e.key == hlKey) Color.rgb(0x6A, 0x14, 0x14) else Color.TRANSPARENT)
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
        val sw = View(c)
        val name = TextView(c).apply { textSize = 12.5f; setTextColor(cFg); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val dir = TextView(c).apply { textSize = 10.5f; setTextColor(cDim); maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        val size = TextView(c).apply { textSize = 11.5f; setTextColor(cFg); gravity = Gravity.END; maxLines = 1 }
        init {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(3), dp(6), dp(3)); minimumHeight = dp(38)
            addView(sw, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(6) })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(name); col.addView(dir)
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(size, LinearLayout.LayoutParams(dp(64), -2))
        }
    }

    private val fileAdapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int): Any = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val v = (cv as? FileRow) ?: FileRow(act)
            val n = shown[p]
            v.sw.setBackgroundColor(colorMap[WizUtil.extOf(n.name)] ?: WizUtil.GRAY)
            v.name.text = n.name
            v.dir.text = n.parent?.path() ?: "/"
            v.size.text = NlModel.fmt(n.size)
            v.setBackgroundColor(if (n === sel) cSel else Color.TRANSPARENT)
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
                val f = File(dir, "wiztree-" + (if (tab == 1) "files" else if (tab == 2) "types" else "folders") + ".csv")
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
        val body = LinearLayout(act)
        val land = act.resources.configuration.screenWidthDp > act.resources.configuration.screenHeightDp
        body.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val lists = android.widget.FrameLayout(act)
        buildLists(lists)
        body.addView(lists, if (land) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(buildMapBox(), if (land) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(status.also { styleStatus(it) }, LinearLayout.LayoutParams(-1, -2))
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
