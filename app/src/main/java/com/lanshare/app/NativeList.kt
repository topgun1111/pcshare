package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Parcelable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.*
import android.widget.FrameLayout
import android.content.Intent
import android.os.Build
import androidx.core.graphics.PathParser
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.lanshare.app.core.Core
import com.lanshare.app.core.RemoteThumbs
import com.lanshare.app.core.Thumbs
import com.lanshare.app.core.VideoThumbs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

/*
 * Leaf views of the native file browser: NlIcons (SVG icon paths), NlRow / NlPal (row data + palette), NlRowView (list / compact /
 * large-thumbnail row) and NlGridView (grid card). Custom drawn, no child views. NOT compiled / NOT device-tested.
 */

/** Exact SVG icon paths copied from ui.html's IC map. */
object NlIcons {
    private val raw = mapOf(
        "folder" to "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
        "file" to "M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z",
        "doc" to "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z",
        "img" to "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
        "vid" to "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z",
        "aud" to "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z",
        "pdf" to "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z",
        "zip" to "M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z",
        "apk" to "M17.6 9.48l1.84-3.18c.16-.31.04-.69-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z",
        "check" to "M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z",
        "camera" to "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9z",
        "download" to "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
        "back" to "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
        "close" to "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"
    )
    private val cache = HashMap<String, Path>()
    fun path(k: String): Path? {
        cache[k]?.let { return it }
        val s = raw[k] ?: return null
        val p = PathParser.createPathFromPathData(s) ?: return null
        cache[k] = p
        return p
    }
    // ui.html FOLD (viewBox 56x48), arc flags written with spaces
    val foldBack: Path = PathParser.createPathFromPathData("M2 8a4 4 0 0 1 4-4h14l4 4h26a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4z")
    val foldFront: Path = PathParser.createPathFromPathData("M2 21a4 4 0 0 1 4-4h44a4 4 0 0 1 4 4v19a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4z")
}

class NlRow(
    val nm: String, val dir: Boolean, val k: String, var a: String, val b: String,
    val badge: String?, val badgeCol: Int, val dup: Boolean,
    val path: String?, val size: Long, val mtime: Long,
    val gal: Boolean = false,           // video-gallery cell (drawn by NlGalView, several per adapter row)
    val dev: String = "local",          // endpoint the row belongs to ("local", a peer id, "smb:...")
    val hit: Boolean = false            // subfolder-search hit (tap = go to that path, not "enter the folder shown here")
) {
    val thumbKey: String? = if (path == null) null else "$dev|$path|$size|$mtime"
}

class NlPal(private val j: JSONObject) {
    private fun c(n: String) = j.optLong(n).toInt()
    val card = c("card"); val bg = c("bg"); val fg = c("fg"); val mut = c("mut"); val bd = c("bd"); val sel = c("sel")
    val onsel = c("onsel"); val hov = c("hov"); val ac = c("ac"); val onac = c("onac"); val warn = c("warn"); val cont = c("cont"); val tl = c("tl")
    val kc = HashMap<String, Int>(); val kb = HashMap<String, Int>()
    init {
        val k = j.optJSONObject("k")
        if (k != null) for (n in k.keys()) { val a = k.getJSONArray(n); kc[n] = a.getLong(0).toInt(); kb[n] = a.getLong(1).toInt() }
    }
}

/** One row, custom drawn (no child views: cheap to create, cheap to scroll). */
class NlRowView(c: Context, private val d: Float) : View(c) {
    var row: NlRow? = null
    var pal: NlPal? = null
    var isSel = false
    var thumb: Bitmap? = null
    var dur: String? = null
    var compact = false
    var large = false      // big-thumbnail list (ui.html #list.t-lg): row 84, lead 76x68

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 16f * d }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 13f * d }
    private val dupP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 11f * d }
    private val dubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 10f * d }
    private val rf = RectF()
    private val src = Rect()
    private val clip = Path()
    private var ell: String? = null
    private var ellFor: NlRow? = null
    private var ellW = -1f

    fun bind(r: NlRow, pl: NlPal, sel: Boolean, cp: Boolean, lg: Boolean) {
        if (cp != compact || lg != large) { compact = cp; large = lg; requestLayout() }
        if ((r.k == "hdr") != (row?.k == "hdr")) requestLayout()
        row = r; pal = pl; isSel = sel; thumb = null; dur = null; ell = null; invalidate()
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), Math.round((if (row?.k == "hdr") 38f else if (compact) 44f else if (large) 84f else 60f) * d)) }

    private fun icon(k: String, x: Float, y: Float, size: Float, color: Int, cv: Canvas) {
        val path = NlIcons.path(k) ?: NlIcons.path("file") ?: return
        p.style = Paint.Style.FILL; p.color = color
        cv.save(); cv.translate(x, y); val s = size / 24f; cv.scale(s, s); cv.drawPath(path, p); cv.restore()
    }

    override fun onDraw(cv: Canvas) {
        val r = row ?: return
        val pl = pal ?: return
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        if (r.k == "hdr") {   // "In subfolders (N)" search header: text only, padding 14/16/6, 13px muted
            smallP.textSize = 13f * d; smallP.color = pl.mut
            val f = smallP.fontMetrics
            val t = TextUtils.ellipsize(r.nm, smallP, w - 32f * d, TextUtils.TruncateAt.END).toString()
            cv.drawText(t, 16f * d, 14f * d + (18.2f * d - (f.descent - f.ascent)) / 2f - f.ascent, smallP)
            return
        }
        if (isSel) { p.color = pl.sel; cv.drawRect(0f, 0f, w, h, p) }
        else if (isPressed) { p.color = pl.hov; cv.drawRect(0f, 0f, w, h, p) }
        p.color = pl.bd; cv.drawRect(0f, h - d, w, h, p)           // border-bottom 1px

        val c = compact
        val lg = large && !c
        // list: row 60, lead 48x44 from x=10, tile/circle 40, icon 24 | compact: row 44, lead 36x36 from x=8, tile/circle 30, icon 20
        val cy = (if (c) 21.5f else if (lg) 41.5f else 29.5f) * d                      // (pad + (row-2*pad-1)/2) dp
        val lcx = (if (c) 26f else if (lg) 48f else 34f) * d
        val tile = (if (c) 15f else if (lg) 32f else 20f) * d                        // half of the 40 / 30 tile
        val ico = (if (c) 20f else if (lg) 36f else 24f) * d
        val leadR = (if (c) 18f else if (lg) 38f else 24f) * d                       // half lead width
        val leadB = (if (c) 18f else if (lg) 34f else 22f) * d                       // half lead height
        // ---- lead ----
        if (isSel) {
            p.color = pl.ac; cv.drawCircle(lcx, cy, tile, p)
            icon("check", lcx - ico / 2f, cy - ico / 2f, ico, pl.onac, cv)
        } else if (r.dir) {
            val fw = (if (c) 34f else if (lg) 68f else 40f) * d; val fh = (if (c) 29f else if (lg) 58f else 34f) * d
            cv.save(); cv.translate(lcx - fw / 2f, cy - fh / 2f); val s = fw / 56f; cv.scale(s, s)
            p.color = 0xFFD49B45.toInt(); cv.drawPath(NlIcons.foldBack, p)
            p.color = 0xFFF7F2EA.toInt(); rf.set(6f, 9f, 50f, 14f); cv.drawRoundRect(rf, 1f, 1f, p)
            p.color = 0xFFDCD3C3.toInt(); rf.set(6f, 13f, 50f, 16f); cv.drawRect(rf, p)
            p.color = 0xFFE2AC5F.toInt(); cv.drawPath(NlIcons.foldFront, p)
            cv.restore()
            r.badge?.let { g ->
                val bs = (if (c) 15f else 24f) * d; val bi = (if (c) 11f else 16f) * d
                val bottom = cy + leadB + (if (c) 2f else 1f) * d; val top = bottom - bs
                p.color = 0x33000000; rf.set(lcx - bs / 2f - d, top - d, lcx + bs / 2f + d, bottom + d); cv.drawRoundRect(rf, (if (c) 5f else 6f) * d, (if (c) 5f else 6f) * d, p)
                p.color = Color.WHITE; rf.set(lcx - bs / 2f, top, lcx + bs / 2f, bottom); cv.drawRoundRect(rf, (if (c) 4f else 5f) * d, (if (c) 4f else 5f) * d, p)
                icon(g, lcx - bi / 2f, top + (bs - bi) / 2f, bi, r.badgeCol, cv)
            }
        } else {
            val bm = thumb
            if (bm != null) {
                val hw = (if (c) 14f else if (lg) 34f else 20f) * d; val hh = (if (c) 15f else if (lg) 31f else 19f) * d   // lead minus 8 / minus 6 px
                val L = lcx - hw; val T = cy - hh; val R = lcx + hw; val B = cy + hh
                val dw = R - L; val dh = B - T
                val sc = Math.max(dw / bm.width, dh / bm.height)
                val sw = (dw / sc).toInt(); val sh = (dh / sc).toInt()
                src.set((bm.width - sw) / 2, (bm.height - sh) / 2, (bm.width - sw) / 2 + sw, (bm.height - sh) / 2 + sh)
                rf.set(L, T, R, B)
                clip.reset(); clip.addRoundRect(rf, 8f * d, 8f * d, Path.Direction.CW)
                cv.save(); cv.clipPath(clip); p.color = Color.WHITE; cv.drawBitmap(bm, src, rf, p); cv.restore()
                dur?.let { t ->
                    val tw = dubP.measureText(t) + 8f * d
                    val right = lcx + leadR - 3f * d; val bottom = cy + leadB - 3f * d
                    p.color = 0x99000000.toInt(); rf.set(right - tw, bottom - 14f * d, right, bottom); cv.drawRoundRect(rf, 7f * d, 7f * d, p)
                    dubP.color = Color.WHITE
                    val fm = dubP.fontMetrics
                    cv.drawText(t, right - tw + 4f * d, bottom - 7f * d - (fm.ascent + fm.descent) / 2f, dubP)
                }
            } else {
                val kb = pl.kb[r.k] ?: pl.kb["file"] ?: 0; val kc = pl.kc[r.k] ?: pl.kc["file"] ?: 0
                p.color = kb; rf.set(lcx - tile, cy - tile, lcx + tile, cy + tile); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
                icon(r.k, lcx - ico / 2f, cy - ico / 2f, ico, kc, cv)
            }
        }
        // ---- texts ----
        val xr = w - 14f * d
        nameP.textSize = (if (c) 15f else 16f) * d
        smallP.textSize = (if (c) 12f else 13f) * d
        nameP.color = if (isSel) pl.onsel else pl.fg
        smallP.color = if (isSel) Color.argb(0xCC, Color.red(pl.onsel), Color.green(pl.onsel), Color.blue(pl.onsel)) else pl.mut
        var dupW = 0f
        if (r.dup) { dupP.color = 0xFF3B2A00.toInt(); dupW = dupP.measureText("duplicate?") + 12f * d }
        val fn = nameP.fontMetrics; val fs = smallP.fontMetrics
        if (c) {
            // compact: one line  [name .......... small]   (name flex 1, small flex none, gap 10)
            val x0 = 54f * d
            val sw = smallP.measureText(r.a)
            val avail = xr - x0 - sw - 10f * d - (if (r.dup) dupW + 6f * d else 0f)
            if (ell == null || ellFor !== r || ellW != avail) { ell = TextUtils.ellipsize(r.nm, nameP, avail, TextUtils.TruncateAt.END).toString(); ellFor = r; ellW = avail }
            cv.drawText(ell!!, x0, cy - (fn.ascent + fn.descent) / 2f, nameP)
            if (r.dup) {
                val bx = x0 + nameP.measureText(ell!!) + 6f * d
                p.color = pl.warn; rf.set(bx, cy - 7.7f * d, bx + dupW, cy + 7.7f * d); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
                val fd = dupP.fontMetrics
                cv.drawText("duplicate?", bx + 6f * d, cy - (fd.ascent + fd.descent) / 2f, dupP)
            }
            cv.drawText(r.a, xr - sw, cy - (fs.ascent + fs.descent) / 2f, smallP)
            return
        }
        val x0 = (if (lg) 98f else 70f) * d
        val top = cy - 20.3f * d                                    // block = 22.4 + 18.2 line heights, centred
        val avail = xr - x0 - (if (r.dup) dupW + 6f * d else 0f)
        if (ell == null || ellFor !== r || ellW != avail) { ell = TextUtils.ellipsize(r.nm, nameP, avail, TextUtils.TruncateAt.END).toString(); ellFor = r; ellW = avail }
        val base1 = top + (22.4f * d - (fn.descent - fn.ascent)) / 2f - fn.ascent
        cv.drawText(ell!!, x0, base1, nameP)
        if (r.dup) {
            val bx = x0 + nameP.measureText(ell!!) + 6f * d; val mid = top + 11.2f * d
            p.color = pl.warn; rf.set(bx, mid - 7.7f * d, bx + dupW, mid + 7.7f * d); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
            val fd = dupP.fontMetrics
            cv.drawText("duplicate?", bx + 6f * d, mid - (fd.ascent + fd.descent) / 2f, dupP)
        }
        val base2 = top + 22.4f * d + (18.2f * d - (fs.descent - fs.ascent)) / 2f - fs.ascent
        cv.drawText(r.a, x0, base2, smallP)
        if (r.b.isNotEmpty()) cv.drawText(r.b, xr - smallP.measureText(r.b), base2, smallP)
    }
}

/** One grid card (ui.html #list.v-grid .row): square lead, name (max 2 lines, centred), size + date lines. Card bg = --bg, 1px --bd border, radius 12, padding 6 6 8. */
class NlGridView(c: Context, private val d: Float) : View(c) {
    var row: NlRow? = null
    var pal: NlPal? = null
    var isSel = false
    var selMode = false
    var thumb: Bitmap? = null
    var dur: String? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 13f * d }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 12f * d }
    private val dupP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 11f * d }
    private val dubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 10f * d }
    private val rf = RectF()
    private val src = Rect()
    private val clip = Path()
    private var lines: List<String> = listOf("")
    private var dupInline = false
    private var dupOwn = false
    private var layRow: NlRow? = null
    private var layW = -1

    fun bind(r: NlRow, pl: NlPal, sel: Boolean, selm: Boolean) {
        row = r; pal = pl; isSel = sel; selMode = selm; thumb = null; dur = null; layRow = null; invalidate()
    }

    private fun prep(w: Int) {
        val r = row ?: return
        if (layRow === r && layW == w) return
        layRow = r; layW = w
        val tw = w - 14f * d
        val sl = StaticLayout(r.nm, nameP, Math.max(1, tw.toInt()), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        val n = Math.min(2, sl.lineCount)
        val out = ArrayList<String>()
        for (i in 0 until n) {
            val st = sl.getLineStart(i); val en = sl.getLineEnd(i)
            out.add(if (i == n - 1 && sl.lineCount > n) TextUtils.ellipsize(r.nm.substring(st), nameP, tw, TextUtils.TruncateAt.END).toString()
                    else r.nm.substring(st, en).trimEnd('\n', ' '))
        }
        if (out.isEmpty()) out.add("")
        lines = out
        dupInline = false; dupOwn = false
        if (r.dup) {
            val dw = dupP.measureText("duplicate?") + 12f * d
            if (nameP.measureText(out.last()) + 6f * d + dw <= tw) dupInline = true else if (out.size < 2) dupOwn = true
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        prep(w)
        val r = row
        val s = w - 14f * d
        val nameH = (lines.size + (if (dupOwn) 1 else 0)) * 16.25f * d
        val smallH = (if (r != null && r.b.isNotEmpty()) 2 else 1) * 16.8f * d
        setMeasuredDimension(w, Math.round(1f * d + 6f * d + s + 6f * d + nameH + 2f * d + smallH + 8f * d + 1f * d))
    }

    private fun icon(k: String, x: Float, y: Float, size: Float, color: Int, cv: Canvas) {
        val path = NlIcons.path(k) ?: NlIcons.path("file") ?: return
        p.style = Paint.Style.FILL; p.color = color
        cv.save(); cv.translate(x, y); val sc = size / 24f; cv.scale(sc, sc); cv.drawPath(path, p); cv.restore()
    }

    override fun onDraw(cv: Canvas) {
        val r = row ?: return
        val pl = pal ?: return
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = pl.bg; rf.set(0f, 0f, w, h); cv.drawRoundRect(rf, 12f * d, 12f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = pl.bd; rf.set(d / 2f, d / 2f, w - d / 2f, h - d / 2f); cv.drawRoundRect(rf, 11.5f * d, 11.5f * d, p)
        p.style = Paint.Style.FILL
        val s = w - 14f * d; val lx = 7f * d; val ly = 7f * d
        val cx = lx + s / 2f; val cy = ly + s / 2f
        // ---- lead ----
        if (r.dir) {
            val fw = 0.4f * s; val fh = fw * 48f / 56f
            cv.save(); cv.translate(cx - fw / 2f, cy - fh / 2f); val sc = fw / 56f; cv.scale(sc, sc)
            p.color = 0xFFD49B45.toInt(); cv.drawPath(NlIcons.foldBack, p)
            p.color = 0xFFF7F2EA.toInt(); rf.set(6f, 9f, 50f, 14f); cv.drawRoundRect(rf, 1f, 1f, p)
            p.color = 0xFFDCD3C3.toInt(); rf.set(6f, 13f, 50f, 16f); cv.drawRect(rf, p)
            p.color = 0xFFE2AC5F.toInt(); cv.drawPath(NlIcons.foldFront, p)
            cv.restore()
            r.badge?.let { g ->
                val bs = 24f * d; val bi = 16f * d
                val bottom = ly + s + d; val top = bottom - bs
                p.color = 0x33000000; rf.set(cx - bs / 2f - d, top - d, cx + bs / 2f + d, bottom + d); cv.drawRoundRect(rf, 6f * d, 6f * d, p)
                p.color = Color.WHITE; rf.set(cx - bs / 2f, top, cx + bs / 2f, bottom); cv.drawRoundRect(rf, 5f * d, 5f * d, p)
                icon(g, cx - bi / 2f, top + (bs - bi) / 2f, bi, r.badgeCol, cv)
            }
        } else {
            val bm = thumb
            if (bm != null) {
                val sc = Math.max(s / bm.width, s / bm.height)
                val sw = (s / sc).toInt(); val sh = (s / sc).toInt()
                src.set((bm.width - sw) / 2, (bm.height - sh) / 2, (bm.width - sw) / 2 + sw, (bm.height - sh) / 2 + sh)
                rf.set(lx, ly, lx + s, ly + s)
                clip.reset(); clip.addRoundRect(rf, 8f * d, 8f * d, Path.Direction.CW)
                cv.save(); cv.clipPath(clip); p.color = Color.WHITE; cv.drawBitmap(bm, src, rf, p); cv.restore()
                dur?.let { t ->
                    val tw = dubP.measureText(t) + 8f * d
                    val right = lx + s - 3f * d; val bottom = ly + s - 3f * d
                    p.color = 0x99000000.toInt(); rf.set(right - tw, bottom - 14f * d, right, bottom); cv.drawRoundRect(rf, 7f * d, 7f * d, p)
                    dubP.color = Color.WHITE
                    val fm = dubP.fontMetrics
                    cv.drawText(t, right - tw + 4f * d, bottom - 7f * d - (fm.ascent + fm.descent) / 2f, dubP)
                }
            } else {
                val kb = pl.kb[r.k] ?: pl.kb["file"] ?: 0; val kc = pl.kc[r.k] ?: pl.kc["file"] ?: 0
                val t = 0.4f * s
                p.color = kb; rf.set(cx - t / 2f, cy - t / 2f, cx + t / 2f, cy + t / 2f); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
                icon(r.k, cx - t / 2f, cy - t / 2f, t, kc, cv)
            }
        }
        // ---- check circle (top 5, right 5, 24px) ----
        val kx = lx + s - 5f * d - 12f * d; val ky = ly + 5f * d + 12f * d
        if (isSel) {
            p.style = Paint.Style.FILL
            p.color = 0x26000000; cv.drawCircle(kx, ky + d, 14.5f * d, p)
            p.color = Color.WHITE; cv.drawCircle(kx, ky, 14f * d, p)
            p.color = pl.ac; cv.drawCircle(kx, ky, 12f * d, p)
            icon("check", kx - 8f * d, ky - 8f * d, 16f * d, pl.onac, cv)
        } else if (selMode) {
            p.style = Paint.Style.FILL; p.color = 0x33000000; cv.drawCircle(kx, ky, 12f * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = Color.WHITE; cv.drawCircle(kx, ky, 11f * d, p)
            p.style = Paint.Style.FILL
        }
        // ---- texts ----
        val fn = nameP.fontMetrics; val fs = smallP.fontMetrics
        nameP.color = pl.fg; smallP.color = pl.mut
        var y = ly + s + 6f * d
        val dw = dupP.measureText("duplicate?") + 12f * d
        fun pill(x: Float, mid: Float) {
            p.style = Paint.Style.FILL; p.color = pl.warn; rf.set(x, mid - 7.7f * d, x + dw, mid + 7.7f * d); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
            dupP.color = 0xFF3B2A00.toInt(); val fd = dupP.fontMetrics
            cv.drawText("duplicate?", x + 6f * d, mid - (fd.ascent + fd.descent) / 2f, dupP)
        }
        for ((i, ln) in lines.withIndex()) {
            val base = y + (16.25f * d - (fn.descent - fn.ascent)) / 2f - fn.ascent
            val lw = nameP.measureText(ln)
            if (dupInline && i == lines.size - 1) {
                val x = (w - (lw + 6f * d + dw)) / 2f
                cv.drawText(ln, x, base, nameP); pill(x + lw + 6f * d, y + 8.125f * d)
            } else cv.drawText(ln, (w - lw) / 2f, base, nameP)
            y += 16.25f * d
        }
        if (dupOwn) { pill((w - dw) / 2f, y + 8.125f * d); y += 16.25f * d }
        y += 2f * d
        val b1 = y + (16.8f * d - (fs.descent - fs.ascent)) / 2f - fs.ascent
        cv.drawText(r.a, (w - smallP.measureText(r.a)) / 2f, b1, smallP)
        if (r.b.isNotEmpty()) cv.drawText(r.b, (w - smallP.measureText(r.b)) / 2f, b1 + 16.8f * d, smallP)
    }
}
