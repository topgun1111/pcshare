package com.lanshare.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import com.lanshare.app.core.Item
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.PrintPreview
import com.lanshare.app.core.errText
import com.lanshare.app.core.vjoin
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What is printed: [files] of folder [dir] on device [dev]; [after] runs once the job has been handed over (e.g. clear the selection). */
class PrintSet(val dev: String, val dir: String, val files: List<Item>, val after: () -> Unit = {}) {
    fun paths(): List<String> = files.map { vjoin(dir, it.name) }
}

/**
 * Live page preview strip of the native print dialogs = Kotlin twin of ui.html `drawPv` / `drawImgPv` (PC dialog, [wifi] = false) and of
 * the simpler preview of `wifiOptions` ([wifi] = true). Same maths as the PC ([PrintLayout]), real pages from [PrintPreview].
 *  - PDF (and html / svg / unknown / office converted by the phone or the PC): pages rendered by PdfRenderer, loaded lazily.
 *  - Pictures: the picture itself; text / code: wrapped like pcprint.py.
 *  - [picMode] (only pictures selected): all pictures share the sheets; tap a picture = turn it 90 degrees ([turn], [rots]).
 * [st] is the dialog's option map (strings), read at every redraw; call [redraw] after any change.
 *
 * NOT compiled / NOT device-tested.
 */
class PrintPv(
    private val act: Activity,
    private val c: NlTheme.Cols,
    private val set: PrintSet,
    private val toId: String,
    private val wifi: Boolean,
    private val st: MutableMap<String, String>,
    private val rots: IntArray,
    private val picMode: Boolean,
    private val onTurn: () -> Unit,
    private val officeOk: () -> Boolean = { false }
) {
    private class Info(val kind: String, val pages: Int, val pw: Int, val ph: Int, val id: String?, val img: Bitmap?, val text: String?, val why: String)

    private val d: Float = act.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * d)
    private val h = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "pvload").also { it.isDaemon = true } }
    @Volatile private var alive = true

    private var pinfo: Info? = null
    private val thumbs = arrayOfNulls<Bitmap>(set.files.size)
    private val loaded = BooleanArray(set.files.size)
    private var cur: PrintLayout.Sheets? = null
    private val pages = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val pending = HashSet<String>()   // main thread only
    private val bad = HashSet<String>()
    private var txKey = ""
    private var txPages: PrintLayout.TextPages? = null

    /** A picture finished loading (picture mode): the dialog shows it in its "turn" tiles. */
    var onThumb: ((Int, Bitmap) -> Unit)? = null

    private val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val cap = TextView(act).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(c.mut); setPadding(dp(2), dp(4), dp(2), 0)
        text = "Loading preview\u2026"
    }
    val view: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(strip) }, LinearLayout.LayoutParams(-1, -2))
        addView(cap, LinearLayout.LayoutParams(-1, -2))
    }

    private val drawRun = Runnable {
        try { draw() } catch (e: Throwable) { strip.removeAllViews(); cap.text = "Preview error: " + errText(e) }
    }

    init {
        if (picMode) loadThumbs() else loadInfo()
        redraw()
    }

    fun redraw() { h.removeCallbacks(drawRun); h.postDelayed(drawRun, 90) }
    fun dispose() { alive = false; h.removeCallbacks(drawRun); pool.shutdownNow(); thumbs.fill(null); pages.evictAll() }
    fun thumb(i: Int): Bitmap? = thumbs.getOrNull(i)

    private fun g(k: String) = st[k] ?: ""
    private fun pvFile(): Item? = set.files.firstOrNull { PrintLayout.previewable(it.name) } ?: set.files.firstOrNull()

    // ---------------------------------------------------------------- loading
    private fun loadThumbs() {
        set.files.forEachIndexed { i, f ->
            pool.execute {
                val bm = try { loadPic(vjoin(set.dir, f.name)) } catch (_: Throwable) { null }
                h.post {
                    if (!alive) return@post
                    loaded[i] = true
                    if (bm != null) { thumbs[i] = bm; onThumb?.invoke(i, bm) }
                    redraw()
                }
            }
        }
    }

    /** The picture as it looks (EXIF applied), longest side <= 1400 px. Formats the phone cannot decode go through the print converter (PrintPrep -> JPEG). */
    private fun loadPic(path: String): Bitmap? {
        val tmp = File(act.cacheDir, "pvimg_" + System.nanoTime())
        try {
            Jobs.ep(set.dev).open(path).use { s ->
                if (s.size > (80L shl 20)) return null
                tmp.outputStream().use { o -> s.copyTo(o) }
            }
            decodeFile(tmp)?.let { return it }
            val j = PrintPreview.info(set.dev, path, toId)
            if (j.optString("kind") == "img") return decodeFile(PrintPreview.file(j.getString("id")).first)
            return null
        } finally { try { tmp.delete() } catch (_: Throwable) { } }
    }

    private fun decodeFile(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var n = 1
        while (max(o.outWidth, o.outHeight) / n > 1400) n *= 2
        val bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = n }) ?: return null
        val ori = try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (ori) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bm
        }
        return try { Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true).also { if (it !== bm) bm.recycle() } } catch (_: Throwable) { bm }
    }

    private fun readText(f: File): String {
        val b = f.readBytes()
        val t = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: Throwable) { String(b, charset("windows-1254")) }
        return t.removePrefix("\uFEFF")
    }

    /** ui.html pvInfo: what the preview needs from the first file (real pages, the picture, or the text). */
    private fun loadInfo() {
        val f = pvFile() ?: return
        val path = vjoin(set.dir, f.name)
        pool.execute {
            val info = try {
                val j = PrintPreview.info(set.dev, path, toId)
                when (j.optString("kind")) {
                    "pdf" -> Info("doc", j.optInt("pages"), j.optInt("w"), j.optInt("h"), j.optString("id"), null, null, "")
                    "img" -> Info("img", 1, 0, 0, null, decodeFile(PrintPreview.file(j.getString("id")).first), null, "")
                    "text" -> {
                        val file = PrintPreview.file(j.getString("id")).first
                        if (file.length() > 8_000_000L) Info("doc", 0, 0, 0, null, null, null, "text too large for a preview")
                        else Info("text", 1, 0, 0, null, null, readText(file), "")
                    }
                    else -> Info("office", 0, 0, 0, null, null, null, j.optString("why"))
                }
            } catch (e: Throwable) {
                val m = errText(e)
                if (Regex("cannot be|too large").containsMatchIn(m)) Info("none", 0, 0, 0, null, null, null, m)
                else Info("doc", 0, 0, 0, null, null, null, m)   // sample layout, reason in the caption
            }
            h.post { if (alive) { pinfo = info; redraw() } }
        }
    }

    /** ui.html pvPg: one rendered page (JPEG from PrintPreview.page), loaded lazily; the strip redraws when it arrives. */
    private fun pg(id: String, n: Int, w: Int): Bitmap? {
        val k = "$id|$n|$w"
        pages.get(k)?.let { return it }
        if (k in bad || !pending.add(k)) return null
        pool.execute {
            val bm = try { val b = PrintPreview.page(id, n, w); BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null }
            h.post {
                pending.remove(k)
                if (!alive) return@post
                if (bm != null) pages.put(k, bm) else bad.add(k)
                redraw()
            }
        }
        return null
    }

    private fun txt(t: String): PrintLayout.TextPages {
        val key = listOf(g("paper"), g("margin"), g("scale")).joinToString("|")
        txPages?.let { if (txKey == key) return it }
        return PrintLayout.textPages(t, st).also { txKey = key; txPages = it }
    }

    // ---------------------------------------------------------------- strip
    private val grayPaint by lazy { Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) } }
    private fun maxW() = max(dp(160), act.resources.displayMetrics.widthPixels - dp(104))
    private fun maxH() = max(dp(160), (act.resources.displayMetrics.heightPixels * .45f).toInt())
    private fun label(s0: Int, two: Boolean) = if (two) (s0 / 2 + 1).toString() + (if (s0 % 2 == 1) " back" else " front") else (s0 + 1).toString()
    private fun nup(): Int = g("nup").trim().toIntOrNull() ?: 1
    private fun copies(): Int = g("copies").trim().toIntOrNull() ?: 1

    /** One sheet: [sw] x [sh] points drawn by [paint] (canvas already scaled to points, [k] = pixels per point). [tap] gets sheet coordinates. */
    private fun addSheet(sw: Double, sh: Double, label: String, mono: Boolean, tap: ((Double, Double) -> Unit)?, paint: (Canvas, Float) -> Unit) {
        val k = min(maxW() / sw, maxH() / sh).toFloat()
        val wpx = max(1, (sw * k).roundToInt())
        val hpx = max(1, (sh * k).roundToInt())
        val frame = Paint().apply { style = Paint.Style.STROKE; color = 0xFFBDBDBD.toInt(); strokeWidth = 1f }
        val v = object : View(act) {
            override fun onMeasure(a: Int, b: Int) { setMeasuredDimension(wpx, hpx) }
            override fun onDraw(cv: Canvas) {
                cv.save(); cv.scale(k, k); cv.clipRect(0f, 0f, sw.toFloat(), sh.toFloat()); cv.drawColor(Color.WHITE)
                paint(cv, k)
                cv.restore()
                cv.drawRect(0.5f, 0.5f, wpx - 0.5f, hpx - 0.5f, frame)
            }
        }
        if (mono) v.setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint)
        if (tap != null) {
            var lx = 0f; var ly = 0f
            v.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { lx = e.x; ly = e.y }; false }
            v.setOnClickListener { tap(lx / k.toDouble(), ly / k.toDouble()) }
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 0, dp(10), 0) }
        box.addView(v, LinearLayout.LayoutParams(wpx, hpx))
        box.addView(TextView(act).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(c.mut); gravity = Gravity.CENTER })
        strip.addView(box)
    }

    private fun draw() {
        strip.removeAllViews()
        val mono = g("color") == "mono"
        if (picMode) { if (wifi) drawWifiPics(mono) else drawPics(mono) } else drawDoc(mono)
    }

    /** ui.html turn(): turn picture [i] by 90 degrees from how it looks NOW (the current auto-turns are frozen into the manual turns first, so the others do not jump). */
    fun turn(i: Int) {
        val L = cur
        if (g("noauto") != "1" && L != null) { L.sheets.forEach { sh -> sh.forEach { m -> rots[m.i] = m.r } }; st["noauto"] = "1" }
        val m = L?.sheets?.flatten()?.firstOrNull { it.i == i }
        rots[i] = ((if (m != null && g("noauto") == "1") m.r else rots[i]) + 90) % 360
        onTurn()
    }

    private fun tail(nSheets: Int, shown: Int, two: Boolean): String {
        val paper = if (two) ceil(nSheets / 2.0).toInt() else nSheets
        return " \u2192 " + nSheets + " side" + (if (nSheets == 1) "" else "s") + ", " + paper + " sheet" + (if (paper == 1) "" else "s") + " of paper" +
            (if (copies() > 1) " \u00d7 " + copies() else "") + (if (nSheets > shown) " (first $shown shown)" else "")
    }

    // ---------------------------------------------------------------- pictures (PC dialog): shared sheets, tap = turn
    private fun drawPics(mono: Boolean) {
        val n = set.files.size
        val dims = (0 until n).map { i -> thumbs[i]?.let { doubleArrayOf(it.width.toDouble(), it.height.toDouble()) } ?: doubleArrayOf(3.0, 2.0) }
        val L = PrintLayout.sheets(dims, rots, st)
        cur = L
        val ns = L.sheets.size
        val shown = min(ns, 60)
        val two = g("duplex") == "long" || g("duplex") == "short"
        val fill = g("fit") == "fill"
        val border = g("border") == "1"
        for (s0 in 0 until shown) {
            val cells = L.sheets[s0]
            addSheet(L.sw, L.sh, label(s0, two), mono, { x, y ->
                cells.firstOrNull { abs(x - it.cx) <= it.w / 2 && abs(y - it.cy) <= it.h / 2 }?.let { turn(it.i) }
            }) { cv, k ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val ln = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = max(1f, .8f / k) }
                val gr = Paint().apply { color = 0xFFCCCCCC.toInt() }
                for (m in cells) {
                    val t = thumbs[m.i]
                    val pw = dims[m.i][0] * m.k
                    val ph = dims[m.i][1] * m.k
                    cv.save()
                    if (fill) cv.clipRect((m.cx - m.w / 2).toFloat(), (m.cy - m.h / 2).toFloat(), (m.cx + m.w / 2).toFloat(), (m.cy + m.h / 2).toFloat())
                    cv.translate(m.cx.toFloat(), m.cy.toFloat())
                    cv.rotate(m.r.toFloat())
                    val dst = RectF((-pw / 2).toFloat(), (-ph / 2).toFloat(), (pw / 2).toFloat(), (ph / 2).toFloat())
                    if (t != null) cv.drawBitmap(t, null, dst, p) else cv.drawRect(dst, gr)
                    cv.restore()
                    if (border) cv.drawRect((m.cx - m.w / 2).toFloat(), (m.cy - m.h / 2).toFloat(), (m.cx + m.w / 2).toFloat(), (m.cy + m.h / 2).toFloat(), ln)
                }
            }
        }
        cap.text = n.toString() + " picture" + (if (n == 1) "" else "s") + " (tap a picture to turn it)" + tail(ns, shown, two) +
            (if (loaded.any { !it }) " \u00b7 loading\u2026" else "")
    }

    // ---------------------------------------------------------------- pictures (Wi-Fi dialog): simple grid
    private fun drawWifiPics(mono: Boolean) {
        val lay = PrintLayout.LAY[nup()] ?: PrintLayout.LAY[1]!!
        val cols = lay.first; val rows = lay.second; val landS = lay.third
        val per = cols * rows
        val pd = PrintLayout.paper(g("paper"))
        val n = set.files.size
        val ns = ceil(n / per.toDouble()).toInt()
        val shown = min(ns, 60)
        val two = g("duplex") == "long" || g("duplex") == "short"
        val border = g("border") == "1"
        for (s0 in 0 until shown) {
            val chunk = (s0 * per until min(n, s0 * per + per)).toList()
            val t0 = thumbs[chunk[0]]
            val fl = t0 != null && t0.width > t0.height
            val sw = if (per == 1) (if (fl) pd[1] else pd[0]) else (if (landS) pd[1] else pd[0])
            val sh = if (per == 1) (if (fl) pd[0] else pd[1]) else (if (landS) pd[0] else pd[1])
            addSheet(sw, sh, label(s0, two), mono, null) { cv, k ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val gr = Paint().apply { color = 0xFFDDDDDD.toInt() }
                val ln = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = max(1f, .8f / k) }
                val m = 18.0; val gap = 8.0
                val cw = (sw - 2 * m - (cols - 1) * gap) / cols
                val ch = (sh - 2 * m - (rows - 1) * gap) / rows
                chunk.forEachIndexed { j, i ->
                    val t = thumbs[i]
                    val cx = m + (j % cols) * (cw + gap) + cw / 2
                    val cy = m + (j / cols) * (ch + gap) + ch / 2
                    if (t == null) { cv.drawRect((cx - cw / 2).toFloat(), (cy - ch / 2).toFloat(), (cx + cw / 2).toFloat(), (cy + ch / 2).toFloat(), gr); return@forEachIndexed }
                    val rot = per > 1 && ((t.width > t.height) != (cw > ch))
                    val iw = (if (rot) t.height else t.width).toDouble()
                    val ih = (if (rot) t.width else t.height).toDouble()
                    val q = min(cw / iw, ch / ih)
                    val bw = t.width * q; val bh = t.height * q
                    cv.save(); cv.translate(cx.toFloat(), cy.toFloat()); if (rot) cv.rotate(90f)
                    val dst = RectF((-bw / 2).toFloat(), (-bh / 2).toFloat(), (bw / 2).toFloat(), (bh / 2).toFloat())
                    cv.drawBitmap(t, null, dst, p)
                    if (border && per > 1) cv.drawRect(dst, ln)
                    cv.restore()
                }
                ln.color = 0xFFCFCFCF.toInt()
                cv.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), ln)
            }
        }
        cap.text = n.toString() + " pictures" + tail(ns, shown, two) + (if (loaded.any { !it }) " \u00b7 loading\u2026" else "")
    }

    // ---------------------------------------------------------------- documents (PDF, text, one picture; office / html converted)
    private fun drawDoc(mono: Boolean) {
        val f = pvFile()
        if (f == null) { cap.text = "No preview"; return }
        val info = pinfo
        if (info == null) { cap.text = "Loading preview\u2026"; return }
        if (!wifi) {
            if (info.kind == "office" && !officeOk()) {
                cap.text = "Printed by the PC\u2019s own app (Word, Excel ...): only printer and copies apply. Install LibreOffice (or Microsoft Office) on the PC and run the new pcprint.py to unlock layout options"
                return
            }
            if (info.kind == "none") { cap.text = info.why.ifEmpty { "No layout preview for this file type" } + " - only printer and copies apply"; return }
        } else if (info.kind == "none" || info.kind == "office") { cap.text = info.why.ifEmpty { "No preview for this file type" }; return }

        val isText = info.kind == "text"
        val tx = if (isText) txt(info.text ?: "") else null
        val total = if (isText) tx!!.pages.size else if (info.pages > 0) info.pages else (if (wifi) 1 else 6)
        val sample = !wifi && !isText && info.pages == 0
        val name = f.name
        var idx = PrintLayout.pageSet(g("range"), total)
        if (g("pages").isNotEmpty()) { val want = if (g("pages") == "odd") 1 else 0; idx = idx.filter { (it + 1) % 2 == want }.toMutableList() }
        if (g("reverse") == "1") idx.reverse()
        if (idx.isEmpty()) { cap.text = "No pages left after the page selection"; return }

        val booklet = !wifi && g("booklet") == "1"
        val order: List<Int?>
        val cols: Int; val rows: Int; val land: Boolean
        if (booklet) { order = PrintLayout.booklet(idx.size).map { if (it == null) null else idx[it] }; cols = 2; rows = 1; land = true }
        else { order = idx; val l = PrintLayout.LAY[nup()] ?: PrintLayout.LAY[1]!!; cols = l.first; rows = l.second; land = l.third }
        val per = cols * rows
        val pd = PrintLayout.paper(g("paper"))
        val im = info.img
        val iland = (im != null && im.width > im.height) || (!isText && (wifi || g("paper").isEmpty()) && info.pw > info.ph)
        val pw0 = if (iland) pd[1] else pd[0]
        val ph0 = if (iland) pd[0] else pd[1]
        val ext0 = PrintLayout.ext(name)
        val margin = g("margin")
        val scaleV = PrintLayout.num(g("scale"))
        val align = g("align")
        val fitting = !wifi && im == null && !isText && ext0 !in PrintLayout.TXT &&
            (margin.isNotEmpty() || scaleV > 0 || align.isNotEmpty() || g("autorot") == "1" || g("fit") == "fill")
        val FM = if (fitting && margin.isNotEmpty()) PrintLayout.num(margin) else 0.0
        val clipBox = fitting && per == 1 && FM > 0
        val sw: Double; val sh: Double
        if (per == 1) { sw = pw0; sh = ph0 } else { sw = if (land) pd[1] else pd[0]; sh = if (land) pd[0] else pd[1] }
        val nSheets = ceil(order.size / per.toDouble()).toInt()
        val shown = min(nSheets, 60)
        val two = g("duplex") == "long" || g("duplex") == "short" || booklet
        val wmT = if (wifi) "" else g("wm")
        val wmUnder = g("wm_under") == "1"
        val border = g("border") == "1"
        val fillFit = g("fit") == "fill"
        val hdr = if (wifi) "" else g("hdr")
        val ftr = if (wifi) "" else g("ftr")

        for (s0 in 0 until shown) {
            val chunk = order.subList(s0 * per, min(order.size, s0 * per + per))
            addSheet(sw, sh, label(s0, two), mono, null) { cv, k ->
                val cw = (sw - 2 * FM) / cols
                val ch = (sh - 2 * FM) / rows
                val mg = if (per == 1) 0.0 else if (booklet) 8.0 else if (wifi) 10.0 else 12.0
                val tm = if ((isText || ext0 in PrintLayout.TXT) && margin.isNotEmpty()) PrintLayout.num(margin) else 50.0
                class Cl(val pi: Int, val sc: Double, val x: Double, val y: Double)
                val cells = ArrayList<Cl>()
                chunk.forEachIndexed { j, pi ->
                    if (pi == null) return@forEachIndexed
                    if (!fitting) {
                        val sc = if (per == 1) 1.0 else min((cw - 2 * mg) / pw0, (ch - 2 * mg) / ph0)
                        cells.add(Cl(pi, sc, (j % cols) * cw + (cw - pw0 * sc) / 2, (j / cols) * ch + (ch - ph0 * sc) / 2))
                        return@forEachIndexed
                    }
                    val iw = cw - 2 * mg; val ih = ch - 2 * mg
                    val sc = PrintLayout.fitK(pw0, ph0, iw, ih, st, per)
                    val ix = FM + (j % cols) * cw + mg
                    val iy = FM + (j / cols) * ch + mg
                    cells.add(Cl(pi, sc, if (align == "topleft") ix else ix + (iw - pw0 * sc) / 2, if (align.isNotEmpty()) iy else iy + (ih - ph0 * sc) / 2))
                }
                val lw = max(1f, 1f / k * .8f)
                val fillP = Paint().apply { color = Color.WHITE }
                val edge = Paint().apply { style = Paint.Style.STROKE; color = 0xFFCFCFCF.toInt(); strokeWidth = lw }
                val bord = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = lw }
                val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                fun clipOn() { if (clipBox) { cv.save(); cv.clipRect(FM.toFloat(), FM.toFloat(), (sw - FM).toFloat(), (sh - FM).toFloat()) } }
                fun clipOff() { if (clipBox) cv.restore() }
                fun rectOf(m: Cl) = RectF(m.x.toFloat(), m.y.toFloat(), (m.x + pw0 * m.sc).toFloat(), (m.y + ph0 * m.sc).toFloat())
                fun watermark() {
                    if (wmT.isEmpty()) return
                    val t = PrintLayout.fill(wmT, 0, nSheets, name)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 100f }
                    val w1 = (tp.measureText(t) / 100f).let { if (it == 0f) 1f else it }
                    val size = max(8f, min(150f, (hypot(sw, sh) * .7 / w1).toFloat()))
                    tp.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); tp.textSize = size
                    tp.color = Color.argb((.28 * 255).toInt(), 128, 128, 128); tp.textAlign = Paint.Align.CENTER
                    cv.save(); cv.translate((sw / 2).toFloat(), (sh / 2).toFloat()); cv.rotate(-Math.toDegrees(atan2(sh, sw)).toFloat())
                    val fm = tp.fontMetrics
                    cv.drawText(t, 0f, -(fm.ascent + fm.descent) / 2f, tp)
                    cv.restore()
                }

                clipOn(); for (m in cells) { val r = rectOf(m); cv.drawRect(r, fillP); cv.drawRect(r, edge) }; clipOff()
                if (wmT.isNotEmpty() && wmUnder) watermark()
                clipOn()
                for (m in cells) {
                    cv.save(); cv.translate(m.x.toFloat(), m.y.toFloat()); cv.scale(m.sc.toFloat(), m.sc.toFloat())
                    if (im != null) {
                        val a = if (!wifi && margin.isNotEmpty()) PrintLayout.num(margin) else 28.0
                        val fw = pw0 - 2 * a; val fh = ph0 - 2 * a
                        val q = (if (fillFit) max(fw / im.width, fh / im.height) else min(fw / im.width, fh / im.height))
                        val w = im.width * q; val hh = im.height * q
                        cv.save(); cv.clipRect(a.toFloat(), a.toFloat(), (pw0 - a).toFloat(), (ph0 - a).toFloat())
                        cv.drawBitmap(im, null, RectF(((pw0 - w) / 2).toFloat(), ((ph0 - hh) / 2).toFloat(), ((pw0 + w) / 2).toFloat(), ((ph0 + hh) / 2).toFloat()), bmp)
                        cv.restore()
                    } else if (isText && tx != null) {
                        val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = 0xFF222222.toInt(); typeface = Typeface.MONOSPACE; textSize = tx.size.toFloat() }
                        val lines = tx.pages.getOrNull(m.pi) ?: emptyList()
                        lines.forEachIndexed { l, line -> if (line.isNotEmpty()) cv.drawText(line, tx.mm.toFloat(), (tx.mm + tx.size + l * tx.lead).toFloat(), tp) }
                    } else if (info.id != null) {
                        val pgm = pg(info.id, m.pi, if (per > 1) 340 else 760)
                        if (pgm != null) {
                            val ar = pgm.width.toDouble() / pgm.height
                            var w = pw0; var hh = pw0 / ar
                            if (hh > ph0) { hh = ph0; w = ph0 * ar }
                            cv.drawBitmap(pgm, null, RectF(((pw0 - w) / 2).toFloat(), ((ph0 - hh) / 2).toFloat(), ((pw0 + w) / 2).toFloat(), ((ph0 + hh) / 2).toFloat()), bmp)
                        } else {
                            fillP.color = 0xFFE4E4E4.toInt()
                            cv.drawRect((pw0 * .08).toFloat(), (ph0 * .08).toFloat(), (pw0 * .92).toFloat(), (ph0 * .92).toFloat(), fillP)
                            fillP.color = Color.WHITE
                        }
                    } else {
                        fillP.color = 0xFFD6D6D6.toInt()
                        for (l in 0 until 22) {
                            val w = (pw0 - 2 * tm - 20) * (.55 + .45 * (((m.pi + 1) * (l + 3) * 37) % 100) / 100.0)
                            cv.drawRect((tm + 10).toFloat(), (tm + 20 + l * 30).toFloat(), (tm + 10 + (if (l % 7 == 6) w * .5 else w)).toFloat(), (tm + 29 + l * 30).toFloat(), fillP)
                        }
                        fillP.color = Color.WHITE
                        if (!wifi) {
                            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb((.55 * 255).toInt(), 0x9a, 0xa7, 0xbd); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 260f; textAlign = Paint.Align.CENTER }
                            val fm = tp.fontMetrics
                            cv.drawText((m.pi + 1).toString(), (pw0 / 2).toFloat(), (ph0 / 2).toFloat() - (fm.ascent + fm.descent) / 2f, tp)
                        }
                    }
                    cv.restore()
                }
                clipOff()
                if (!(wmT.isNotEmpty() && wmUnder)) watermark()
                if (border && per > 1) for (m in cells) cv.drawRect(rectOf(m), bord)

                if (hdr.isNotEmpty() || ftr.isNotEmpty()) {
                    val fs = max(9f, 5.5f / k)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt(); typeface = Typeface.SANS_SERIF; textSize = fs }
                    fun zone(txt: String, y: Float) {
                        if (txt.isEmpty()) return
                        val pr = txt.split('|')
                        val z = if (pr.size == 1) listOf("", pr[0], "") else listOf(pr[0], pr.getOrElse(1) { "" }, pr.getOrElse(2) { "" })
                        for (i2 in 0 until 3) {
                            val t = PrintLayout.fill(z[i2].trim(), s0, nSheets, name)
                            if (t.isEmpty()) continue
                            tp.textAlign = when (i2) { 0 -> Paint.Align.LEFT; 1 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT }
                            cv.drawText(t, floatArrayOf(28f, (sw / 2).toFloat(), (sw - 28).toFloat())[i2], y, tp)
                        }
                    }
                    zone(hdr, 22f + fs * .6f)
                    zone(ftr, (sh - 14).toFloat())
                }
            }
        }
        cap.text = total.toString() + " page" + (if (total == 1) "" else "s") + (if (sample) " (sample - count unknown)" else "") +
            (if (order.size != total) " \u2192 " + order.size + " printed" else "") + tail(nSheets, shown, two) +
            (if (set.files.size > 1) " \u00b7 previewing " + name + " (+" + (set.files.size - 1) + " more)" else "") +
            (if (sample && info.why.isNotEmpty()) " \u00b7 real preview unavailable: " + info.why else "")
    }
}
