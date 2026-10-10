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
import android.webkit.WebView
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
 * NativeList - the folder rows of the file browser drawn by a RecyclerView that sits ON TOP of the WebView.
 * The page (header, path bar, drawer, dialogs, FAB, toast ...) stays HTML. ui.html sends only plain data:
 *   palette (CSS variables, so light/dark/themes match exactly), preformatted texts (size / date), selection.
 * Metrics below are the phone values of ui.html's CSS (the "one size scale" block; compact view = row 44, lead 36, tile 30, name 15 / small 12 on one line; large thumbnails = row 84, lead 76x68, tile 64 (icon 36), folder 68x58): row 60, lead 48x44,
 * tile 40 (icon 24), folder 40x34, name 16 / small 13, gap 12, padding 6 14 6 10.  1 CSS px == 1 dp here
 * (WebView textZoom is 100).  NOT compiled / NOT device-tested.
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

/** "New" = modified within the last 60 s (twin of ui.html NEWSEC / isNew). Drawn as a red dot on the row's lead. */
const val NL_NEW_SEC = 60L
fun nlIsNew(mtimeSec: Long): Boolean = mtimeSec > 0 && (System.currentTimeMillis() / 1000 - mtimeSec) < NL_NEW_SEC

private fun nlDot(cv: Canvas, p: Paint, cx: Float, cy: Float, r: Float, ring: Int, d: Float) {
    p.style = Paint.Style.FILL; p.shader = null
    p.color = ring; cv.drawCircle(cx, cy, r + 1.5f * d, p)
    p.color = 0xFFFF3B30.toInt(); cv.drawCircle(cx, cy, r, p)
}

class NlRow(
    val nm: String, val dir: Boolean, val k: String, var a: String, val b: String,
    val badge: String?, val badgeCol: Int, val dup: Boolean,
    val path: String?, val size: Long, val mtime: Long,
    val gal: Boolean = false,           // video-gallery cell (drawn by NlGalView, several per adapter row)
    val dev: String = "local",          // endpoint the row belongs to ("local", a peer id, "smb:...")
    val hit: Boolean = false,           // subfolder-search hit (tap = go to that path, not "enter the folder shown here")
    val fresh: Boolean = false          // modified within the last 60 s: red dot on the lead (ui.html isNew / `w`)
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

/** Overlay over the WebView: touches outside the list (or inside "holes" such as the FAB / toast) go to the WebView. */
class NlOverlay(c: Context, private val web: WebView) : FrameLayout(c) {
    val listRect = RectF()
    val headRect = RectF()  // path bar + tool row (NlHeadView); empty = not shown natively
    var holes: List<RectF> = emptyList()
    var dim = 0f            // 0..1 black veil over the list (drawer scrim / dialog scrim in the page)
    var passAll = false     // true while a drawer/dialog is open: every touch belongs to the page
    private var toWeb = false
    private val veil = Paint()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = ev.x; val y = ev.y
            toWeb = passAll || (!listRect.contains(x, y) && !headRect.contains(x, y)) || holes.any { it.contains(x, y) }
        }
        return if (toWeb) web.dispatchTouchEvent(ev) else super.dispatchTouchEvent(ev)
    }

    override fun dispatchDraw(c: Canvas) {
        c.save()
        for (h in holes) c.clipRect(h, Region.Op.DIFFERENCE)
        super.dispatchDraw(c)
        if (dim > 0f) { veil.color = Color.argb((dim * 255f).toInt().coerceIn(0, 255), 0, 0, 0); c.drawRect(listRect, veil); if (!headRect.isEmpty) c.drawRect(headRect, veil) }
        c.restore()
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
    var medium = false     // medium thumbnails (ui.html #list.t-md): row 72, lead 62x56

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.med(); textSize = 16f * d }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.med(); textSize = 13f * d }
    private val dupP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 11f * d }
    private val dubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 10f * d }
    private val rf = RectF()
    private val src = Rect()
    private val clip = Path()
    private var ell: String? = null
    private var ellFor: NlRow? = null
    private var ellW = -1f

    fun bind(r: NlRow, pl: NlPal, sel: Boolean, cp: Boolean, lg: Boolean, md: Boolean = false) {
        if (cp != compact || lg != large || md != medium) { compact = cp; large = lg; medium = md; requestLayout() }
        if ((r.k == "hdr") != (row?.k == "hdr")) requestLayout()
        row = r; pal = pl; isSel = sel; thumb = null; dur = null; ell = null; invalidate()
    }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), Math.round((if (row?.k == "hdr") 38f else if (compact) 44f else if (large) 84f else if (medium) 72f else 60f) * d)) }

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
        val md = medium && !c && !lg
        // list: row 60, lead 48x44 from x=10, tile/circle 40, icon 24 | compact: row 44, lead 36x36 from x=8, tile/circle 30, icon 20
        val cy = (if (c) 21.5f else if (lg) 41.5f else if (md) 35.5f else 29.5f) * d                      // (pad + (row-2*pad-1)/2) dp
        val lcx = (if (c) 26f else if (lg) 48f else if (md) 41f else 34f) * d
        val tile = (if (c) 15f else if (lg) 32f else if (md) 26f else 20f) * d                        // half of the 40 / 30 tile
        val ico = (if (c) 20f else if (lg) 36f else if (md) 30f else 24f) * d
        val leadR = (if (c) 18f else if (lg) 38f else if (md) 31f else 24f) * d                       // half lead width
        val leadB = (if (c) 18f else if (lg) 34f else if (md) 28f else 22f) * d                       // half lead height
        // ---- lead ----
        if (isSel) {
            p.color = pl.ac; cv.drawCircle(lcx, cy, tile, p)
            icon("check", lcx - ico / 2f, cy - ico / 2f, ico, pl.onac, cv)
        } else if (r.dir) {
            val fw = (if (c) 34f else if (lg) 68f else if (md) 54f else 40f) * d; val fh = (if (c) 29f else if (lg) 58f else if (md) 46f else 34f) * d
            cv.save(); cv.translate(lcx - fw / 2f, cy - fh / 2f); val s = fw / 56f; cv.scale(s, s)
            p.color = 0xFFFB8C00.toInt(); cv.drawPath(NlIcons.foldBack, p)
            p.color = 0xFFF1F3F4.toInt(); rf.set(6f, 9f, 50f, 14f); cv.drawRoundRect(rf, 1f, 1f, p)
            p.color = 0xFFDADCE0.toInt(); rf.set(6f, 13f, 50f, 16f); cv.drawRect(rf, p)
            p.color = 0xFFFFB74D.toInt(); cv.drawPath(NlIcons.foldFront, p)
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
                val hw = (if (c) 14f else if (lg) 34f else if (md) 27f else 20f) * d; val hh = (if (c) 15f else if (lg) 31f else if (md) 25f else 19f) * d   // lead minus 8 / minus 6 px
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
        if (r.fresh && !isSel) { val o = (if (c) 2f else 3f) * d; nlDot(cv, p, lcx - tile + o, cy - tile + o, (if (c) 4f else 5f) * d, pl.bg, d) }
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
            val aTxt = TextUtils.ellipsize(r.a, smallP, Math.max(40f * d, (xr - x0) * 0.45f), TextUtils.TruncateAt.END).toString()
            val sw = smallP.measureText(aTxt)
            val avail = xr - x0 - sw - 10f * d - (if (r.dup) dupW + 6f * d else 0f)
            if (ell == null || ellFor !== r || ellW != avail) { ell = TextUtils.ellipsize(r.nm, nameP, avail, TextUtils.TruncateAt.END).toString(); ellFor = r; ellW = avail }
            cv.drawText(ell!!, x0, cy - (fn.ascent + fn.descent) / 2f, nameP)
            if (r.dup) {
                val bx = x0 + nameP.measureText(ell!!) + 6f * d
                p.color = pl.warn; rf.set(bx, cy - 7.7f * d, bx + dupW, cy + 7.7f * d); cv.drawRoundRect(rf, 8f * d, 8f * d, p)
                val fd = dupP.fontMetrics
                cv.drawText("duplicate?", bx + 6f * d, cy - (fd.ascent + fd.descent) / 2f, dupP)
            }
            cv.drawText(aTxt, xr - sw, cy - (fs.ascent + fs.descent) / 2f, smallP)
            return
        }
        val x0 = (if (lg) 98f else if (md) 84f else 70f) * d
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
        val line = xr - x0
        val aTxt = TextUtils.ellipsize(r.a, smallP, line, TextUtils.TruncateAt.END).toString()
        cv.drawText(aTxt, x0, base2, smallP)
        if (r.b.isNotEmpty()) {
            // narrow window: drop the time ("May 12, 2026, 06:54 PM" -> "May 12, 2026"), then ellipsize, then hide
            val room = line - smallP.measureText(aTxt) - 12f * d
            var b = r.b
            if (smallP.measureText(b) > room) b = b.substringBeforeLast(", ", b)
            if (smallP.measureText(b) > room) b = if (room < 28f * d) "" else TextUtils.ellipsize(b, smallP, room, TextUtils.TruncateAt.END).toString()
            if (b.isNotEmpty()) cv.drawText(b, xr - smallP.measureText(b), base2, smallP)
        }
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
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.med(); textSize = 13f * d }
    private val smallP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.med(); textSize = 12f * d }
    private val dupP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 11f * d }
    private val dubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 10f * d }
    private val rf = RectF()
    private val src = Rect()
    private val clip = Path()
    private var lines: List<String> = listOf("")
    private var dupInline = false
    private var dupOwn = false
    private var layRow: NlRow? = null
    private var layW = -1

    var bare = false       // picture-only card of an image folder: no padding, no caption (ui.html #list.imgf .row.im)

    fun bind(r: NlRow, pl: NlPal, sel: Boolean, selm: Boolean, bare: Boolean = false) {
        row = r; pal = pl; isSel = sel; selMode = selm; this.bare = bare; thumb = null; dur = null; layRow = null; invalidate()
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
        if (bare) { setMeasuredDimension(w, w); return }
        prep(w)
        val r = row
        val s = w - 14f * d
        val nameH = (lines.size + (if (dupOwn) 1 else 0)) * 16.25f * d
        val smallH = 16.8f * d                                       // one line (count / size): the date line is not shown on grid cards
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
        p.style = Paint.Style.FILL; p.color = pl.bg; rf.set(0f, 0f, w, h); cv.drawRoundRect(rf, 16f * d, 16f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = pl.bd; rf.set(d / 2f, d / 2f, w - d / 2f, h - d / 2f); cv.drawRoundRect(rf, 15.5f * d, 15.5f * d, p)
        p.style = Paint.Style.FILL
        val s = if (bare) w - 2f * d else w - 14f * d; val lx = if (bare) d else 7f * d; val ly = lx
        val cx = lx + s / 2f; val cy = ly + s / 2f
        // ---- lead ----
        if (r.dir) {
            val fw = 0.62f * s; val fh = fw * 48f / 56f                        // folder icon: 62 % of the square (was 40 %)
            val fy = cy - (if (r.badge != null) 0.07f * s else 0f)            // a little higher when the small category badge sits at the bottom
            cv.save(); cv.translate(cx - fw / 2f, fy - fh / 2f); val sc = fw / 56f; cv.scale(sc, sc)
            p.color = 0xFFFB8C00.toInt(); cv.drawPath(NlIcons.foldBack, p)
            p.color = 0xFFF1F3F4.toInt(); rf.set(6f, 9f, 50f, 14f); cv.drawRoundRect(rf, 1f, 1f, p)
            p.color = 0xFFDADCE0.toInt(); rf.set(6f, 13f, 50f, 16f); cv.drawRect(rf, p)
            p.color = 0xFFFFB74D.toInt(); cv.drawPath(NlIcons.foldFront, p)
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
                clip.reset(); clip.addRoundRect(rf, (if (bare) 15f else 12f) * d, (if (bare) 15f else 12f) * d, Path.Direction.CW)
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
        if (r.fresh) nlDot(cv, p, lx + 9f * d, ly + 9f * d, 5.5f * d, Color.WHITE, d)
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
        if (bare) return
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
    }
}

/**
 * One row of the video gallery (ui.html .gal / .vc): `cols` square cells (3, 5 from 600 dp), 4 dp gap, 4 dp padding, radius 8.
 * Each cell: --k-vid-b background, centred video icon (36, 70 %) until the thumbnail arrives, name on a top gradient (11),
 * play dot bottom-left (22), duration pill bottom-right, select circle top-right (24, white ring) like the grid card.
 * Touches are handled here (several cells per view): onTap / onLong get the index in the combined row list.
 */
class NlGalView(c: Context, private val d: Float) : View(c) {
    var cells: List<NlRow> = emptyList()
    var first = 0
    var cols = 3
    var pal: NlPal? = null
    var sel: Set<Int> = emptySet()
    var selMode = false
    var last = false
    var onTap: ((Int) -> Unit)? = null
    var onLong: ((Int) -> Unit)? = null

    private val th = arrayOfNulls<Bitmap>(5)
    private val du = arrayOfNulls<String>(5)
    private var pressed = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val dubP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fnt.semi(); textSize = 11f * d }
    private val rf = RectF()
    private val src = Rect()
    private val clip = Path()

    private val gd = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { pressed = hit(e.x, e.y); invalidate(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { val i = hit(e.x, e.y); if (i >= 0) onTap?.invoke(first + i); return true }
        override fun onLongPress(e: MotionEvent) {
            val i = hit(e.x, e.y)
            if (i >= 0) { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onLong?.invoke(first + i) }
        }
    })

    fun bind(cs: List<NlRow>, firstIdx: Int, columns: Int, pl: NlPal, selected: Set<Int>, selm: Boolean, isLast: Boolean) {
        cells = cs; first = firstIdx; cols = columns; pal = pl; sel = selected; selMode = selm; last = isLast
        for (i in th.indices) { th[i] = null; du[i] = null }
        pressed = -1
        requestLayout(); invalidate()
    }

    fun setThumb(k: Int, bm: Bitmap, dur: String?) { if (k in th.indices) { th[k] = bm; du[k] = dur; invalidate() } }

    private fun cell(w: Float) = (w - 8f * d - 4f * d * (cols - 1)) / cols

    private fun hit(x: Float, y: Float): Int {
        val cs = cell(width.toFloat())
        if (y < 4f * d || y > 4f * d + cs) return -1
        val k = ((x - 4f * d) / (cs + 4f * d)).toInt()
        if (x < 4f * d || k < 0 || k >= cells.size) return -1
        return if (x <= 4f * d + k * (cs + 4f * d) + cs) k else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) { pressed = -1; invalidate() }
        return true
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = 4f * d + cell(w.toFloat()) + (if (last) 4f * d + d else 0f)
        setMeasuredDimension(w, Math.round(h))
    }

    private fun icon(k: String, x: Float, y: Float, size: Float, color: Int, cv: Canvas) {
        val path = NlIcons.path(k) ?: return
        p.style = Paint.Style.FILL; p.color = color
        cv.save(); cv.translate(x, y); val sc = size / 24f; cv.scale(sc, sc); cv.drawPath(path, p); cv.restore()
    }

    override fun onDraw(cv: Canvas) {
        val pl = pal ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val cs = cell(w)
        for (k in cells.indices) {
            val r = cells[k]
            val x = 4f * d + k * (cs + 4f * d); val y = 4f * d
            val on = (first + k) in sel
            rf.set(x, y, x + cs, y + cs)
            cv.save()
            clip.reset(); clip.addRoundRect(rf, 8f * d, 8f * d, Path.Direction.CW); cv.clipPath(clip)
            p.style = Paint.Style.FILL
            p.color = pl.kb["vid"] ?: pl.kb["file"] ?: 0; p.shader = null; cv.drawRect(rf, p)
            val bm = th[k]
            if (bm != null) {
                val sc = Math.max(cs / bm.width, cs / bm.height)
                val sw = (cs / sc).toInt(); val sh = (cs / sc).toInt()
                src.set((bm.width - sw) / 2, (bm.height - sh) / 2, (bm.width - sw) / 2 + sw, (bm.height - sh) / 2 + sh)
                p.color = Color.WHITE; cv.drawBitmap(bm, src, rf, p)
            } else {
                val kc = pl.kc["vid"] ?: pl.kc["file"] ?: 0
                val a = (0.7f * 255f).toInt()
                val col = Color.argb(a, Color.red(kc), Color.green(kc), Color.blue(kc))
                icon("vid", x + cs / 2f - 18f * d, y + cs / 2f - 18f * d, 36f * d, col, cv)
            }
            // name on a top gradient: padding 14 6 4, line 14
            p.shader = LinearGradient(0f, y, 0f, y + 32f * d, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            rf.set(x, y, x + cs, y + 32f * d); cv.drawRect(rf, p); p.shader = null
            nameP.color = Color.WHITE
            val tw = cs - (if (r.fresh) 18f else 6f) * d - (if (selMode) 34f else 6f) * d
            val fm = nameP.fontMetrics
            val txt = TextUtils.ellipsize(r.nm, nameP, Math.max(0f, tw), TextUtils.TruncateAt.END).toString()
            cv.drawText(txt, x + (if (r.fresh) 18f else 6f) * d, y + 14f * d + 7f * d - (fm.ascent + fm.descent) / 2f, nameP)
            if (r.fresh) nlDot(cv, p, x + 11f * d, y + 21f * d, 4.5f * d, Color.WHITE, d)
            // play dot
            p.style = Paint.Style.FILL; p.color = 0x99000000.toInt()
            cv.drawCircle(x + 6f * d + 11f * d, y + cs - 6f * d - 11f * d, 11f * d, p)
            icon("play", x + 6f * d + 11f * d - 8f * d, y + cs - 6f * d - 11f * d - 8f * d, 16f * d, Color.WHITE, cv)
            // duration pill
            du[k]?.let { t ->
                val tw2 = dubP.measureText(t) + 12f * d
                val right = x + cs - 6f * d; val bottom = y + cs - 6f * d
                p.color = 0x99000000.toInt(); rf.set(right - tw2, bottom - 15f * d, right, bottom); cv.drawRoundRect(rf, 10f * d, 10f * d, p)
                dubP.color = Color.WHITE
                val f2 = dubP.fontMetrics
                cv.drawText(t, right - tw2 + 6f * d, bottom - 7.5f * d - (f2.ascent + f2.descent) / 2f, dubP)
            }
            if (pressed == k) { p.color = 0x26000000; rf.set(x, y, x + cs, y + cs); cv.drawRect(rf, p) }
            cv.restore()
            // select circle (same as the grid card): top 5, right 5, 24
            val kx = x + cs - 5f * d - 12f * d; val ky = y + 5f * d + 12f * d
            if (on) {
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
        }
        if (last) { p.style = Paint.Style.FILL; p.color = pl.bd; cv.drawRect(0f, h - d, w, h, p) }
    }
}

class NativeList(private val act: Activity, private val web: WebView) {
    companion object {
        /** Kill switch: false = the WebView draws the list exactly as before. */
        const val ENABLED = true
        /** Kill switch for the native path bar + tool row (NlHead.kt): false = the HTML rows only. */
        const val HEAD = true
        /** Kill switch for the native search box (NlSearch.kt) and its instant filter: false = the HTML input. */
        const val SEARCH = true
        /** Kill switch for the native Sort / View sheets (NlSheets.kt): false = the page's HTML sheets. */
        const val SHEETS = true

        fun parseRows(json: String, dev: String = "local"): List<NlRow> {
            val a = JSONArray(json); val out = ArrayList<NlRow>(a.length())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(NlRow(o.getString("n"), o.optInt("d") == 1, o.optString("k", "file"), o.optString("a"), o.optString("b"),
                    if (o.has("g")) o.getString("g") else null, o.optLong("gc").toInt(), o.optInt("u") == 1,
                    if (o.has("p")) o.getString("p") else null, o.optLong("s"), o.optLong("t"), o.optInt("v") == 1, dev, o.optInt("h") == 1, o.optInt("w") == 1))
            }
            return out
        }
        fun parseSel(json: String): Set<Int> { val a = JSONArray(json); val s = HashSet<Int>(); for (i in 0 until a.length()) s.add(a.getInt(i)); return s }
    }

    private val d = UiScale.dens(act)
    val overlay = NlOverlay(act, web)
    private val srl = SwipeRefreshLayout(act)
    private val rv = RecyclerView(act)
    private var lm: LinearLayoutManager = LinearLayoutManager(act)
    private var gridOn = false
    private var spanN = 1
    private val gap = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(out: Rect, v: View, parent: RecyclerView, st: RecyclerView.State) {
            if (parent.getChildAdapterPosition(v) >= galStart) { out.set(0, 0, 0, 0); return }     // gallery rows span the full width
            val o = Math.round(4f * d); out.set(o, o, o, o)
        }
    }
    private var rows: List<NlRow> = emptyList()
    private var galStart = 0            // index of the first gallery cell in `rows` (== rows.size when there is no gallery)
    private var gs = 3                  // gallery columns
    private var sel: Set<Int> = emptySet()
    private var pal: NlPal? = null
    private var palJson = ""
    private var curKey = ""
    private var curSig = 0
    private val states = LinkedHashMap<String, Parcelable>()
    private var lastLay = ""
    private var elev = false
    // ---- native path bar + tool row (NlHead.kt) ----
    private val head = NlHeadView(act, d)
    private var headData: NlHeadData? = null
    private var headGeom = false                 // the page sent a geometry ("hd") for the two rows
    private val headRect = RectF()
    // ---- native search box (NlSearch.kt) + native Sort / View sheets (NlSheets.kt) ----
    val search = NlSearchRow(act, d)
    val sheets = NlSheets(act) { o -> pref(o) }
    private var searchOn = false                 // the native box is shown (page: body.srch)
    private var curQ = ""                        // what the box holds (trimmed, lower case) = the query the page is asked to apply
    private var pageQ = ""                       // query the last rows from the page were filtered by (ui.html nlQ, sent right before nlItems)
    private var baseRows: List<NlRow>? = null    // unfiltered rows of baseKey: source of the instant filter
    private var baseKey = ""
    private var prefGen = 0
    // ---- instant folder entry: Kotlin builds the rows of the next folder itself (NlModel); the page only revalidates ----
    private class Built(val rows: List<NlRow>, val sig: Int, val cfg: String, val at: Long, val sumB: String?, val sumS: String?)
    private val built = LinkedHashMap<String, Built>()      // key = dev|view|path (UI thread only)
    private val pending = HashSet<String>()                 // keys being built right now
    private val tooBig = HashSet<String>()                  // idle pre-builds skip folders known to be big
    private var navKey: String? = null                      // folder just tapped whose rows are still being built
    private val buildNow = Executors.newSingleThreadExecutor { Thread(it, "nl-build").also { t -> t.isDaemon = true } }
    private val buildIdle = Executors.newSingleThreadExecutor { Thread(it, "nl-idle").also { t -> t.isDaemon = true; t.priority = Thread.MIN_PRIORITY } }
    private val idleWarm = Runnable { warmNeighbours() }
    private var curCfg = ""
    private var navUntil = 0L           // while the page has not yet answered an instant entry, taps would hit the wrong row list
    private var compact = false
    private var large = false
    private var medium = false
    private var gcols = 0               // grid view: 0 = automatic (2, 4 from 600 dp), else 2 / 3 / 4 (the user's choice)
    private class Th(val bm: Bitmap, val dur: String?)
    private val thumbs = object : android.util.LruCache<String, Th>((Runtime.getRuntime().maxMemory() / 8L).toInt()) {
        override fun sizeOf(k: String, v: Th) = v.bm.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val ad = Ad()

    private class VH(val v: View) : RecyclerView.ViewHolder(v) {
        var key: String? = null; var job: Future<*>? = null
        val gk = arrayOfNulls<String>(5); val gj = arrayOfNulls<Future<*>>(5)
    }

    private fun galSpan() = if (web.width / d >= 600f) 5 else 3
    private fun galRowCount() = if (rows.size > galStart) (rows.size - galStart + gs - 1) / gs else 0
    /** combined row index -> adapter position */
    private fun posOf(i: Int) = if (i < galStart) i else galStart + (i - galStart) / gs
    private var imgMode = false         // image folder (most files are pictures): grid cards of pictures are bare and bigger
    private fun calcGal() {
        galStart = rows.indexOfFirst { it.gal }.let { if (it < 0) rows.size else it }; gs = galSpan()
        var f = 0; var im = 0
        for (r in rows) { if (r.gal || r.dir || r.k == "hdr") continue; f++; if (r.k == "img") im++ }
        imgMode = im > 0 && im * 2 >= f
    }

    init {
        rv.layoutManager = lm; rv.adapter = ad; rv.itemAnimator = null; rv.setHasFixedSize(true)
        srl.addView(rv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        srl.setOnRefreshListener { emit("refresh", 0) }
        // header shadow (#top.el) follows the list scroll: the page itself never scrolls while the native list is shown
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) { val e = r.canScrollVertically(-1); if (e != elev) { elev = e; emit("el", if (e) 1 else 0) }; if (dy != 0) preload(dy > 0) }
        })
        overlay.addView(srl, FrameLayout.LayoutParams(1, 1))
        overlay.addView(head, FrameLayout.LayoutParams(1, 1))
        head.visibility = View.GONE
        search.visibility = View.GONE
        search.onText = { t -> onSearchText(t) }
        search.onClear = { curQ = ""; web.evaluateJavascript("window.nlSr&&nlSr('clear',0)", null); filterNow("") }
        search.onBack = { search.hideKeyboard(); web.evaluateJavascript("window.nlSr&&nlSr('back',0)", null) }
        head.onAct = { ev, i -> if (ev == "crumb") crumbTo(i) else raw(ev, i) }      // crumb: instant (rows from `built`), page confirms; newb / sort -> ui.html nlOn
        overlay.visibility = View.GONE
    }

    private fun raw(ev: String, i: Int) { web.evaluateJavascript("window.nlOn&&nlOn('$ev',$i)", null) }
    private fun emit(ev: String, i: Int) {
        if (navUntil != 0L && ev != "refresh" && ev != "el") { if (android.os.SystemClock.uptimeMillis() < navUntil) return; navUntil = 0L }
        if (ev == "tap" && sel.isEmpty() && openNative(i)) return
        if ((ev == "tap" && sel.isNotEmpty()) || ev == "long") optimisticSel(ev, i)
        raw(ev, i)
    }

    // ---- picture / video / PDF tap: the viewer starts straight from the rows (no page round trip, no JSON of the whole folder built in JS) ----
    private fun encU(x: String): String = java.net.URLEncoder.encode(x, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")   // = encodeURIComponent
    private val NOIMG = Regex("\\.(svg|gif)$", RegexOption.IGNORE_CASE)
    private val HEIC = Regex("\\.(heic|heif)$", RegexOption.IGNORE_CASE)
    private val SUBX = Regex("\\.(srt|vtt|ass|ssa)$", RegexOption.IGNORE_CASE)

    /** Same payloads as ui.html nativePlay / nativeImg / nativePdf. false = not handled here (the page decides: documents, svg/gif, search, archives, newest ...). */
    private fun openNative(i: Int): Boolean {
        try {
            val r = rows.getOrNull(i) ?: return false
            val origin = Core.url ?: return false
            if (r.dir || r.hit || r.k == "hdr" || sqOn) return false
            val kp = curKey.split('|', limit = 3)
            if (kp.size != 3 || kp[2].startsWith("N:") || kp[2].contains('!')) return false
            val dev = kp[0]; val path = kp[2]
            fun url(n: String) = origin + "/api/dl?dev=" + encU(dev) + "&path=" + encU((if (path == "/") "" else path) + "/" + n)
            fun key(n: String, sz: Long) = dev + "|" + path + "/" + n + "|" + sz
            val files = rows.filter { !it.dir && !it.hit && it.k != "hdr" }
            when (r.k) {
                "vid" -> {
                    val v = files.filter { it.k == "vid" }
                    val subs = files.filter { SUBX.containsMatchIn(it.nm) }
                    val base = { n: String -> n.replace(Regex("\\.[^.]+$"), "").lowercase() }
                    val items = JSONArray()
                    for (m in v) {
                        val b = base(m.nm); val sj = JSONArray()
                        for (x in subs) { val c = base(x.nm); if (c == b || c.startsWith("$b.")) sj.put(JSONObject().put("name", x.nm).put("url", url(x.nm))) }
                        items.put(JSONObject().put("name", m.nm).put("url", url(m.nm)).put("key", key(m.nm, m.size)).put("subs", sj))
                    }
                    val at = Math.max(0, v.indexOfFirst { it.nm == r.nm })
                    PlayerActivity.pending = JSONObject().put("start", at).put("items", items).toString()
                    act.startActivity(Intent(act, PlayerActivity::class.java))
                }
                "img" -> {
                    val heicOk = Build.VERSION.SDK_INT >= 28
                    val ok = { x: NlRow -> x.k == "img" && !NOIMG.containsMatchIn(x.nm) && (heicOk || !HEIC.containsMatchIn(x.nm)) }
                    if (!ok(r)) return false
                    val v = files.filter(ok)
                    val items = JSONArray()
                    for (m in v) items.put(JSONObject().put("name", m.nm).put("url", url(m.nm)).put("size", m.size))
                    ImageViewerActivity.pending = JSONObject().put("start", Math.max(0, v.indexOfFirst { it.nm == r.nm })).put("items", items).toString()
                    act.startActivity(Intent(act, ImageViewerActivity::class.java))
                }
                "pdf" -> {
                    PdfViewerActivity.pending = JSONObject().put("name", r.nm).put("url", url(r.nm)).put("size", r.size).put("key", key(r.nm, r.size)).toString()
                    act.startActivity(Intent(act, PdfViewerActivity::class.java))
                }
                else -> return false
            }
            return true
        } catch (_: Throwable) { return false }
    }

    // ---- selection answered here at once; the page repeats the same toggle (S.sel) and its pushes are held back while taps are in flight ----
    private var selHold = 0L
    private var heldSel: Set<Int>? = null
    private val applyHeld = Runnable { val h = heldSel; heldSel = null; if (h != null) applySel(h) }

    /** Long press adds the row, a tap in selection mode toggles it: exactly what ui.html nlOn does for ordinary rows (hits / headers are not selectable). */
    private fun optimisticSel(ev: String, i: Int) {
        val r = rows.getOrNull(i) ?: return
        if (r.hit || r.k == "hdr") return
        val n = HashSet(sel)
        if (ev == "long") n.add(i) else if (!n.remove(i)) n.add(i)
        selHold = android.os.SystemClock.uptimeMillis() + 400; heldSel = null; rv.removeCallbacks(applyHeld)
        applySel(n)
    }

    fun setCfg(c: String) { if (c != curCfg) { built.clear(); baseRows = null }; curCfg = c }
    /** ui.html nlQ(): the query the rows of the next nlItems were filtered by. */
    fun noteQ(q: String) { pageQ = q }

    private fun fresh(e: Built?) = e != null && e.cfg == curCfg && android.os.SystemClock.uptimeMillis() - e.at < 30_000

    /** Row i is a plain folder that can be entered instantly -> (rows key, folder path). Built here only for this phone ([isLocal]); other devices / SMB use rows the page sent earlier. */
    private fun childKey(i: Int): Pair<String, String>? {
        if (sel.isNotEmpty() || i !in 0 until galStart || curCfg.isEmpty()) return null
        val r = rows[i]
        if (!r.dir || r.hit || r.k == "hdr" || r.gal) return null
        val kp = curKey.split('|', limit = 3)
        if (kp.size != 3 || kp[2].startsWith("N:") || kp[2].contains('!')) return null
        val child = (if (kp[2] == "/") "" else kp[2]) + "/" + r.nm
        return Pair(kp[0] + "|" + kp[1] + "|" + child, child)
    }

    /** Build the rows of a folder in the background (NlModel). [urgent] = finger down / tap, otherwise idle pre-build. */
    private fun warm(key: String, path: String, urgent: Boolean) {
        if (fresh(built[key]) || key in pending || (!urgent && key in tooBig)) return
        pending.add(key)
        val cfg = curCfg
        (if (urgent) buildNow else buildIdle).execute {
            val res = NlModel.build(path, cfg, if (urgent) Int.MAX_VALUE else 2000)
            val list = res.rows; val sg = if (list != null) NlModel.sig(list) else 0
            act.runOnUiThread {
                pending.remove(key)
                if (list != null) {
                    built.remove(key); built[key] = Built(list, sg, cfg, android.os.SystemClock.uptimeMillis(), res.sum?.first, res.sum?.second)
                    while (built.size > 24) built.remove(built.keys.first())
                } else if (!urgent && res.count > 0) { if (tooBig.size > 64) tooBig.clear(); tooBig.add(key) }
                if (navKey == key) {
                    navKey = null
                    if (list != null && cfg == curCfg && curKey != key) { applyItems(key, list, emptySet(), sg); headTo(path, res.sum?.first, res.sum?.second) }
                    else navUntil = 0L               // nothing to show early: the page's answer will do
                }
            }
        }
    }

    /** Finger down on a folder row: start building its rows already. */
    private fun isLocal(key: String) = key.startsWith("local|")
    private fun warmFor(i: Int) { val ck = childKey(i) ?: return; if (isLocal(ck.first)) warm(ck.first, ck.second, true) }

    /** After a list is on screen (idle): build the rows of its first sub-folders ahead. */
    private fun warmNeighbours() {
        if (curCfg.isEmpty() || overlay.visibility != View.VISIBLE) return
        var n = 0
        for (i in 0 until galStart) {
            if (rows[i].nm.startsWith('.')) continue
            val ck = childKey(i) ?: continue
            if (!isLocal(ck.first)) return                    // never pre-list other devices (network traffic)
            warm(ck.first, ck.second, false)
            if (++n >= 6) break
        }
    }

    /** A tap on a plain folder row: show its rows now (built here), then let the page navigate and revalidate as usual. */
    private fun tapRow(i: Int) {
        if (navUntil != 0L) { if (android.os.SystemClock.uptimeMillis() < navUntil) return; navUntil = 0L }   // page has not answered the last tap yet: indexes would not match
        val ck = childKey(i)
        if (ck == null) { emit("tap", i); return }
        val e = built[ck.first]
        if (!isLocal(ck.first)) {      // other device / SMB: only a folder the page listed before (<= 10 min) is shown early; the page revalidates over the network
            if (e != null && e.cfg == curCfg && android.os.SystemClock.uptimeMillis() - e.at < 600_000) { navKey = null; applyItems(ck.first, e.rows, emptySet(), e.sig); headTo(ck.second, e.sumB, e.sumS) }
            else { emit("tap", i); return }
        }
        else if (fresh(e)) { navKey = null; applyItems(ck.first, e!!.rows, emptySet(), e.sig); headTo(ck.second, e.sumB, e.sumS) }
        else { navKey = ck.first; warm(ck.first, ck.second, true) }
        raw("tap", i); navUntil = android.os.SystemClock.uptimeMillis() + 2000
    }

    private fun rawPath(p: String) { web.evaluateJavascript("window.nlOn&&nlOn('goto'," + JSONObject.quote(p) + ")", null) }
    private var sqOn = false                                    // search box open / query typed (from the page's layout JSON)

    /** Show a local folder's rows at once (cached or built here), then let the page navigate to the same path and revalidate. */
    private fun goPath(dev: String, path: String, view: String): Boolean {
        val key = "$dev|$view|$path"
        val e = built[key]
        if (e != null && e.cfg == curCfg && android.os.SystemClock.uptimeMillis() - e.at < 600_000) { navKey = null; applyItems(key, e.rows, emptySet(), e.sig); headTo(path, e.sumB, e.sumS) }
        else if (dev == "local") { navKey = key; warm(key, path, true) }
        else return false                                  // not cached and not buildable here: the page does it
        rawPath(path); navUntil = android.os.SystemClock.uptimeMillis() + 2000
        return true
    }

    /** Path-bar tap: root / drive / a parent folder. Same targets as ui.html nlOn('crumb'); instant for this phone's plain folders. */
    private fun crumbTo(i: Int) {
        val hd = headData; val kp = curKey.split('|', limit = 3)
        if (hd == null || kp.size != 3 || kp[2].startsWith("N:") || kp[2].contains('!') || curCfg.isEmpty() || sqOn) { raw("crumb", i); return }
        val path = if (i < 2) "/" else "/" + hd.segs.take(i - 1).joinToString("/")
        if (path == kp[2] || !goPath(kp[0], path, kp[1])) raw("crumb", i)
    }

    /** System back: up one folder instantly when nothing else (dialog, drawer, selection, search, viewer) needs the key. false = the page decides. */
    fun backUp(): Boolean {
        if (overlay.visibility != View.VISIBLE || overlay.passAll || overlay.dim > 0f || sel.isNotEmpty() || sqOn || curCfg.isEmpty()) return false
        if (navUntil != 0L && android.os.SystemClock.uptimeMillis() < navUntil) return false
        if (headData == null) return false
        val kp = curKey.split('|', limit = 3)
        if (kp.size != 3 || kp[2].startsWith("N:") || kp[2].contains('!') || kp[2] == "/" || kp[2].isEmpty()) return false
        return goPath(kp[0], kp[2].substringBeforeLast('/').ifEmpty { "/" }, kp[1])
    }

    /** A list the page sent for a plain local folder is kept too, so going back / up to it later is instant. */
    private fun rememberPage(key: String, list: List<NlRow>, sig: Int) {
        val kp = key.split('|', limit = 3)
        if (kp.size != 3 || kp[2].startsWith("N:") || kp[2].contains('!') || curCfg.isEmpty()) return
        if (list.any { it.hit || it.k == "hdr" }) return
        val hd = headData
        built.remove(key); built[key] = Built(list, sig, curCfg, android.os.SystemClock.uptimeMillis(), hd?.sumB, hd?.sumS)
        while (built.size > 24) built.remove(built.keys.first())
    }

    // ================= native search box =================
    /** json from ui.html nlSrPush(): "0" = closed, else {r:[l,t,w,h] css px, h:1 hidden under a page dialog, p:palette, x?:query set by the page itself}. */
    fun setSearch(json: String) {
        if (!SEARCH) return
        if (json == "0") {
            if (searchOn) { search.hideKeyboard(); search.setText("") }
            searchOn = false; curQ = ""; search.visibility = View.GONE
            return
        }
        val j = JSONObject(json)
        val r = j.getJSONArray("r")
        val l = Math.round((r.getDouble(0) * d).toFloat()); val t = Math.round((r.getDouble(1) * d).toFloat())
        val w = Math.round((r.getDouble(2) * d).toFloat()); val h = Math.round((r.getDouble(3) * d).toFloat())
        j.optJSONObject("p")?.let { search.setPal(NlPal(it)) }
        val lp = search.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != l || lp.topMargin != t || lp.width != w || lp.height != h) {
            lp.leftMargin = l; lp.topMargin = t; lp.width = w; lp.height = h
            search.layoutParams = lp
        }
        searchOn = true
        if (j.has("x")) {                                            // the page set the query itself (e.g. "find this file"): adopt it, then ask for the rows again (answers sent meanwhile were dropped as stale)
            val x = j.getString("x"); search.setText(x); curQ = x.trim().lowercase(java.util.Locale.ROOT)
            web.evaluateJavascript("window.nlRe&&nlRe()", null)
        }
        if (j.optInt("h") == 1) { if (search.visibility == View.VISIBLE) search.hideKeyboard(); search.visibility = View.INVISIBLE }
        else search.visibility = View.VISIBLE
    }

    fun focusSearch() { if (SEARCH && searchOn) search.focusInput() }

    private fun onSearchText(raw: String) {
        val q = raw.trim().lowercase(java.util.Locale.ROOT)
        if (q == curQ) return
        curQ = q
        web.evaluateJavascript("window.nlSr&&nlSr('q'," + JSONObject.quote(raw) + ")", null)   // the page filters / searches sub-folders as before and confirms
        filterNow(q)
    }

    /** Instant filter: same rule as ui.html shown() (name contains the query, order kept) over the unfiltered rows; the page's answer is identical -> no redraw. */
    private fun filterNow(q: String) {
        val base = baseRows ?: return
        if (!searchOn || baseKey != curKey || sel.isNotEmpty() || overlay.visibility != View.VISIBLE) return
        if (base.any { it.gal }) return
        val out = if (q.isEmpty()) base else base.filter { it.nm.lowercase(java.util.Locale.ROOT).contains(q) }
        if (out.isEmpty()) return                                   // "No matches": the page shows that itself
        val sg = NlModel.sig(out)
        if (sg == curSig) return
        val im = imgMode; rows = out; calcGal(); imgMode = im; curSig = sg; ad.notifyDataSetChanged()   // typing must not flip the column count
        navUntil = android.os.SystemClock.uptimeMillis() + 1500     // taps are ignored until the page answers (its row indexes must match ours)
    }

    // ================= native Sort / View =================
    /** A choice made in the native sheet. Sort / view / thumbnails: rows are re-built here at once for this phone's plain folders; in every case the page applies the same preference. */
    fun pref(o: JSONObject) {
        try { if (o.has("cols")) { val n = o.optInt("cols"); gcols = if (n in 2..4) n else 0; if (gridOn) ensureMgr(true) } } catch (_: Throwable) { }
        try { instantPref(o) } catch (_: Throwable) { }
        web.evaluateJavascript("window.nlPref&&nlPref(" + JSONObject.quote(o.toString()) + ")", null)
    }

    private fun instantPref(o: JSONObject) {
        if (!o.has("sort") && !o.has("view") && !o.has("thumb") && !o.has("ff")) return
        val c = curCfg.split(',')
        val kp = curKey.split('|', limit = 3)
        if (c.size != 7 || kp.size != 3 || kp[0] != "local" || kp[2].startsWith("N:") || kp[2].contains('!')) return
        if (sel.isNotEmpty() || sqOn || searchOn || headData == null || overlay.visibility != View.VISIBLE || overlay.passAll) return
        val sort = o.optString("sort", c[0])
        val asc = if (o.has("asc")) o.optInt("asc") else (c[1].toIntOrNull() ?: 1)
        val view = o.optString("view", c[4])
        val thumb = o.optString("thumb", c[5])
        val ff = if (o.has("ff")) o.optInt("ff") else (c[6].toIntOrNull() ?: 1)
        if (sort !in setOf("none", "name", "size", "date", "type") || view !in setOf("list", "compact", "grid") || (thumb != "s" && thumb != "m" && thumb != "l")) return
        val newCfg = listOf(sort, asc.toString(), c[2], c[3], view, thumb, ff.toString()).joinToString(",")
        if (newCfg == curCfg) return
        val oldKey = curKey
        val key = kp[0] + "|" + view + (if (view == "list" && thumb == "l") "L" else if (view == "list" && thumb == "m") "M" else "") + "|" + kp[2]
        val path = kp[2]
        val gen = ++prefGen
        buildNow.execute {
            val res = NlModel.build(path, newCfg, Int.MAX_VALUE)
            val list = res.rows; val sg = if (list != null) NlModel.sig(list) else 0
            act.runOnUiThread {
                if (gen != prefGen || list == null || curKey != oldKey || sel.isNotEmpty()) return@runOnUiThread
                built.clear(); baseRows = null
                curCfg = newCfg
                compact = view == "compact"; large = view == "list" && thumb == "l"; medium = view == "list" && thumb == "m"
                applyItems(key, list, emptySet(), sg)
                headData?.let { h ->
                    val lab = sheets.labels?.optString(sort, "") ?: ""
                    headData = h.copy(sortLabel = if (lab.isNotEmpty()) lab else h.sortLabel, sortDir = if (sort == "none") 0 else if (asc == 1) 1 else 2,
                        sumB = res.sum?.first ?: h.sumB, sumS = res.sum?.second ?: h.sumS)
                    head.set(headData, true)
                }
                navUntil = android.os.SystemClock.uptimeMillis() + 2000    // until the page confirms (setItems resets this)
            }
        }
    }

    fun setPalette(json: String) {
        if (json == palJson) return
        palJson = json; val p = NlPal(JSONObject(json)); pal = p; head.pal = p
        rv.setBackgroundColor(p.card); srl.setProgressBackgroundColorSchemeColor(p.cont); srl.setColorSchemeColors(p.ac)
        ad.notifyDataSetChanged()
    }

    /** List = LinearLayoutManager; grid view = GridLayoutManager (2 columns, 4 from 600 dp), cards 8 dp apart (rv padding 4 + item offset 4). */
    private fun ensureMgr(grid: Boolean) {
        val span = if (grid) (if (gcols in 2..4) gcols else if (web.width / d >= 600f) (if (imgMode) 3 else 4) else 2) else 1
        if (grid == gridOn && span == spanN && rv.layoutManager === lm) return
        gridOn = grid; spanN = span
        rv.removeItemDecoration(gap)
        val pd = if (grid) Math.round(4f * d) else 0
        rv.setPadding(pd, pd, pd, pd); rv.clipToPadding = !grid
        if (grid) rv.addItemDecoration(gap)
        lm = if (grid) GridLayoutManager(act, span).also { g ->
            g.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int) = if (position >= galStart || rows.getOrNull(position)?.k == "hdr") span else 1 }
        } else LinearLayoutManager(act)
        rv.layoutManager = lm
    }

    fun setItems(key: String, list: List<NlRow>, selected: Set<Int>, sig: Int) {
        if (searchOn && pageQ != curQ) return          // stale answer: the box already holds a newer query, the page answers that one next
        navUntil = 0L; navKey = null
        if (pageQ.isEmpty()) { baseRows = if (list.none { it.hit || it.k == "hdr" || it.gal }) list else null; baseKey = key }
        applyItems(key, list, selected, sig)
        if (pageQ.isEmpty()) rememberPage(key, list, sig)      // never cache a search-filtered list as the folder's rows
        rv.removeCallbacks(idleWarm); rv.postDelayed(idleWarm, 400)
    }

    private fun applyItems(key: String, list: List<NlRow>, selected: Set<Int>, sig: Int) {
        if (key == curKey && sig == curSig) { setSel(selected); return }
        sel = selected
        if (key != curKey) {
            lm.onSaveInstanceState()?.let { states[curKey] = it; if (states.size > 40) states.remove(states.keys.first()) }
            rows = list; calcGal(); curKey = key; curSig = sig
            ensureMgr(key.split('|').getOrNull(1) == "grid")
            ad.notifyDataSetChanged()
            val st = states[key]
            if (st != null) lm.onRestoreInstanceState(st) else lm.scrollToPositionWithOffset(0, 0)
        } else { rows = list; calcGal(); curSig = sig; if (gridOn) ensureMgr(true); ad.notifyDataSetChanged() }
    }

    fun setSel(n: Set<Int>) {
        val left = selHold - android.os.SystemClock.uptimeMillis()
        if (left > 0) { heldSel = n; rv.removeCallbacks(applyHeld); rv.postDelayed(applyHeld, left + 10); return }   // a push from the page while taps are in flight: apply the last one afterwards
        applySel(n)
    }

    private fun applySel(n: Set<Int>) {
        val old = sel; sel = n
        if ((gridOn || galStart < rows.size) && old.isEmpty() != n.isEmpty()) { ad.notifyDataSetChanged(); return }
        if (Math.abs(old.size - n.size) > 40) { ad.notifyDataSetChanged(); return }
        for (i in old) if (i !in n) ad.notifyItemChanged(posOf(i))
        for (i in n) if (i !in old) ad.notifyItemChanged(posOf(i))
    }

    /** json = {l,t,w,h (CSS px), v: visible, o: [[l,t,r,b]...] holes, dm: dim 0..1, ps: all touches to the page} */
    fun layout(json: String) {
        if (json == lastLay) return
        lastLay = json
        val j = JSONObject(json)
        val l = (j.getDouble("l") * d).toFloat(); val t = (j.getDouble("t") * d).toFloat()
        val w = (j.getDouble("w") * d).toFloat(); val h = (j.getDouble("h") * d).toFloat()
        val lp = srl.layoutParams as FrameLayout.LayoutParams
        val nl = Math.round(l); val nt = Math.round(t); val nw = Math.round(w); val nh = Math.max(0, Math.round(h))
        if (lp.leftMargin != nl || lp.topMargin != nt || lp.width != nw || lp.height != nh) {   // a dim-only change (drawer fade) must not re-measure the list
            lp.leftMargin = nl; lp.topMargin = nt; lp.width = nw; lp.height = nh
            srl.layoutParams = lp
        }
        overlay.listRect.set(l, t, l + w, t + h)
        val hs = ArrayList<RectF>(); val o = j.optJSONArray("o")
        if (o != null) for (i in 0 until o.length()) { val a = o.getJSONArray(i); hs.add(RectF((a.getDouble(0) * d).toFloat(), (a.getDouble(1) * d).toFloat(), (a.getDouble(2) * d).toFloat(), (a.getDouble(3) * d).toFloat())) }
        overlay.holes = hs
        overlay.dim = j.optDouble("dm", 0.0).toFloat()
        overlay.passAll = j.optInt("ps") == 1
        sqOn = j.optInt("sq") == 1
        val cp = j.optInt("cp") == 1
        val lg = j.optInt("lg") == 1
        val md = j.optInt("md") == 1
        val gc = j.optInt("gc")
        if (cp != compact || lg != large || md != medium) { compact = cp; large = lg; medium = md; ad.notifyDataSetChanged() }
        gcols = if (gc in 2..4) gc else 0
        if (gridOn) ensureMgr(true)
        if (galSpan() != gs) { gs = galSpan(); ad.notifyDataSetChanged() }
        val hd = j.optJSONArray("hd")
        if (HEAD && hd != null && hd.length() >= 5) {
            val hl = Math.round((hd.getDouble(0) * d).toFloat()); val ht = Math.round((hd.getDouble(1) * d).toFloat())
            val hw = Math.round((hd.getDouble(2) * d).toFloat())
            val ha = (hd.getDouble(3) * d).toFloat(); val hb = (hd.getDouble(4) * d).toFloat()
            val hh = Math.round(ha + hb)
            val hp = head.layoutParams as FrameLayout.LayoutParams
            if (hp.leftMargin != hl || hp.topMargin != ht || hp.width != hw || hp.height != hh) {
                hp.leftMargin = hl; hp.topMargin = ht; hp.width = hw; hp.height = hh
                head.layoutParams = hp
            }
            head.setGeom(ha, hb)
            headRect.set(hl.toFloat(), ht.toFloat(), (hl + hw).toFloat(), (ht + hh).toFloat())
            headGeom = true
        } else headGeom = false
        updateHeadVis()
        overlay.visibility = if (j.optBoolean("v", true)) View.VISIBLE else View.INVISIBLE
        overlay.invalidate()
    }

    private fun updateHeadVis() {
        val on = HEAD && headGeom && headData != null
        head.visibility = if (on) View.VISIBLE else View.GONE
        if (on) overlay.headRect.set(headRect) else overlay.headRect.setEmpty()
    }

    /** ui.html nlHeadPush(): what the path bar and tool row show (sent with every render of the native list). "" = nothing. */
    fun setHead(json: String) {
        if (!HEAD) return
        headData = if (json.isEmpty()) null else NlHeadData.parse(json)
        head.set(headData, true)
        updateHeadVis()
    }

    /** Instant folder entry: path bar + summary follow the rows at once (the page's own push confirms or corrects them). */
    private fun headTo(path: String, sumB: String?, sumS: String?) {
        val cur = headData ?: return
        if (sumB == null) return
        headData = cur.copy(segs = NlHeadData.segsOf(path), sumB = sumB, sumS = sumS ?: "")
        head.set(headData, true)
    }

    /** json = [[rowIndex, newSmallText], ...]: folder item counts that arrived late; only those rows are redrawn. */
    fun patch(json: String) {
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val o = a.getJSONArray(i); val x = o.getInt(0)
            if (x < 0 || x >= rows.size) continue
            val r = rows[x]; val t = o.getString(1)
            if (r.a != t) { r.a = t; ad.notifyItemChanged(posOf(x)) }
        }
    }

    class NlSnap(val key: String, val pal: String, val lay: String, val top: Int, val bottom: Int)
    /** What NlSplash may store: a plain, idle, local, native list on screen (no selection / dialog / drawer / newest). */
    fun snapshot(): NlSnap? {
        if (overlay.visibility != View.VISIBLE || rows.isEmpty() || sel.isNotEmpty() || overlay.passAll || overlay.dim > 0f || lastLay.isEmpty() || palJson.isEmpty()) return null
        val kp = curKey.split('|', limit = 3)
        if (kp.size < 3 || kp[0] != "local" || kp[2].startsWith("N:") || kp[2].contains('!')) return null
        return NlSnap(curKey, palJson, lastLay, overlay.listRect.top.toInt(), overlay.listRect.bottom.toInt())
    }

    fun hide() { headData = null; headGeom = false; head.set(null, false); updateHeadVis(); navUntil = 0L; navKey = null; elev = false; lastLay = ""; overlay.visibility = View.GONE; rows = emptyList(); galStart = 0; curKey = ""; curSig = 0; sel = emptySet(); ad.notifyDataSetChanged() }
    fun done() { srl.isRefreshing = false }

    private fun fmtDur(ms: Long): String {
        val s = Math.round(ms / 1000.0); val h = s / 3600; val m = (s % 3600) / 60; val c = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, c) else String.format("%d:%02d", m, c)
    }

    private fun setThumb(v: View, bm: Bitmap, dur: String?) {
        if (v is NlGridView) { v.thumb = bm; v.dur = dur } else if (v is NlRowView) { v.thumb = bm; v.dur = dur }
        v.invalidate()
    }

    /** Decode one thumbnail on the pool; `ok()` is checked on the UI thread (the view may have been recycled meanwhile). */
    // ---- thumbnails of the rows just beyond the screen (in the scroll direction) are decoded ahead into the memory cache ----
    private val preloading = HashSet<String>()      // UI thread only
    private fun preload(down: Boolean) {
        if (preloading.size > 24) { preloading.removeAll { k -> thumbs.get(k) != null || synchronized(failed) { failed.contains(k) } }; if (preloading.size > 24) return }   // failed decodes never call back: drop them here
        val first = lm.findFirstVisibleItemPosition(); val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0 || galStart == 0) return
        val ahead = 10 * Math.max(1, spanN)
        val idx: IntProgression = if (down) (last + 1..Math.min(galStart - 1, last + ahead)) else (first - 1 downTo Math.max(0, first - ahead))
        for (p in idx) {
            val r = rows.getOrNull(p) ?: continue
            val key = r.thumbKey ?: continue
            if (thumbs.get(key) != null || key in preloading) continue
            val bad = synchronized(failed) { failed.contains(key) }
            if (bad) continue
            preloading.add(key)
            loadTh(r, key, { preloading.remove(key); false }, { })
            if (preloading.size > 24) return
        }
    }

    private fun loadTh(r: NlRow, key: String, ok: () -> Boolean, done: (Th) -> Unit): Future<*> = pool.submit(Runnable {
        val th: Th? = try {
            if (r.dev != "local") {   // other LANShare device / SMB share: through the endpoint (disk cached), see RemoteThumbs
                val (data, ms) = RemoteThumbs.make(r.dev, r.path!!, r.size, r.mtime, r.k == "vid")
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, if (ms > 0) fmtDur(ms) else null) }
            }
            else if (r.k == "vid") { val (data, ms) = VideoThumbs.make(Core.local.open(r.path!!)); BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, if (ms > 0) fmtDur(ms) else null) } }
            else { val data = Thumbs.make(Core.local.real(r.path!!)); BitmapFactory.decodeByteArray(data, 0, data.size)?.let { Th(it, null) } }
        } catch (_: Throwable) { null }
        if (th != null) {
            thumbs.put(key, th)
            act.runOnUiThread { if (ok()) done(th) }
        } else synchronized(failed) { failed.add(key) }
    })

    private inner class Ad : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = galStart + galRowCount()
        override fun getItemViewType(position: Int) = if (position >= galStart) 2 else if (gridOn && rows[position].k != "hdr") 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, t: Int): VH {
            if (t == 2) {
                val gv = NlGalView(parent.context, d)
                gv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gv.onTap = { i -> emit("tap", i) }
                gv.onLong = { i -> emit("long", i) }
                return VH(gv)
            }
            val v: View = if (t == 1) NlGridView(parent.context, d) else NlRowView(parent.context, d)
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            v.isClickable = true; v.isLongClickable = true
            val h = VH(v)
            v.setOnClickListener { val i = h.bindingAdapterPosition; if (i >= 0) tapRow(i) }
            v.setOnLongClickListener {
                val i = h.bindingAdapterPosition
                if (i >= 0) { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); emit("long", i); true } else false
            }
            v.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { val i = h.bindingAdapterPosition; if (i >= 0) { warmFor(i); emit("down", i) } }; false }
            return h
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = pal ?: return
            val hv = h.v
            if (hv is NlGalView) { bindGal(h, hv, pos, p); return }
            val r = rows[pos]
            h.job?.cancel(false); h.job = null; h.key = null
            if (hv is NlGridView) hv.bind(r, p, pos in sel, sel.isNotEmpty(), imgMode && !r.dir && r.k == "img") else (hv as NlRowView).bind(r, p, pos in sel, compact, large, medium)
            val key = r.thumbKey ?: return
            val hit = thumbs.get(key)
            if (hit != null) { setThumb(hv, hit.bm, hit.dur); return }
            synchronized(failed) { if (failed.contains(key)) return }
            h.key = key
            h.job = loadTh(r, key, { h.key == key }) { th -> setThumb(h.v, th.bm, th.dur) }
        }
        private fun bindGal(h: VH, gv: NlGalView, pos: Int, p: NlPal) {
            for (k in 0 until 5) { h.gj[k]?.cancel(false); h.gj[k] = null; h.gk[k] = null }
            val first = galStart + (pos - galStart) * gs
            val cs = ArrayList<NlRow>(gs)
            for (k in 0 until gs) if (first + k < rows.size) cs.add(rows[first + k])
            gv.bind(cs, first, gs, p, sel, sel.isNotEmpty(), pos == itemCount - 1)
            for (k in cs.indices) {
                val r = cs[k]; val key = r.thumbKey ?: continue
                val hit = thumbs.get(key)
                if (hit != null) { gv.setThumb(k, hit.bm, hit.dur); continue }
                if (synchronized(failed) { failed.contains(key) }) continue
                h.gk[k] = key
                h.gj[k] = loadTh(r, key, { h.gk[k] == key }) { th -> gv.setThumb(k, th.bm, th.dur) }
            }
        }
        override fun onViewRecycled(h: VH) {
            h.job?.cancel(false); h.job = null; h.key = null
            for (k in 0 until 5) { h.gj[k]?.cancel(false); h.gj[k] = null; h.gk[k] = null }
        }
    }
}
