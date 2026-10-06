package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.lanshare.app.PrintLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Phone-side twin of pcprint.py's layout step, so a Wi-Fi printer (IPP, no PC) gets every option of the FinePrint-style dialog
 * (NativePrintPc): pages per sheet + border, booklet, odd / even, page range, reverse, paper, fit / fill / custom scale, margins,
 * position, turn-to-fit, watermark, header / footer.
 *
 * It follows the maths of [PrintLayout] and of the preview ([com.lanshare.app.PrintPv].drawDoc) one to one, so the sheets that are
 * sent are the sheets the dialog showed. Input: a PDF (documents, text and pictures are turned into one first by [WifiPrint]);
 * output: a new PDF whose pages are the finished sheets - the printer then prints them 1 : 1 (scaling "none").
 *
 * NOT compiled / NOT device-tested.
 */
object WifiCompose {
    /** The dialog's option as a string ("1" = on, "" = default / absent). */
    fun str(o: JSONObject?, k: String): String {
        if (o == null || !o.has(k) || o.isNull(k)) return ""
        val v = o.get(k).toString().trim()
        return if (v == "true") "1" else if (v == "false") "" else v
    }

    /** True when something the printer cannot do by itself (IPP: copies, sides, colour, paper, scaling, page ranges) was asked for. */
    fun needed(o: JSONObject?, isText: Boolean): Boolean {
        if (o == null) return false
        if ((str(o, "nup").toIntOrNull() ?: 1) > 1) return true
        if (str(o, "booklet") == "1" || str(o, "pages").isNotEmpty() || str(o, "reverse") == "1") return true
        if (str(o, "wm").isNotEmpty() || str(o, "hdr").isNotEmpty() || str(o, "ftr").isNotEmpty()) return true
        if (isText) return false   // margin / scale of a text file are the page margin and the font size: applied when the text is laid out
        return str(o, "margin").isNotEmpty() || PrintLayout.num(str(o, "scale")) > 0 || str(o, "align").isNotEmpty() ||
            str(o, "autorot") == "1" || str(o, "fit") == "fill"
    }

    /** [paperKey] = A4 / Letter / ... (the dialog's choice, else the paper the printer was asked for). */
    fun compose(job: Job, pdf: File, o: JSONObject, name: String, isText: Boolean, paperKey: String): File {
        val st = HashMap<String, String>()
        for (k in listOf("nup", "booklet", "border", "pages", "range", "reverse", "fit", "margin", "scale", "align", "autorot", "wm", "wm_under", "hdr", "ftr"))
            st[k] = str(o, k)
        fun g(k: String): String = st[k] ?: ""

        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val rr = PdfRenderer(fd)
        val doc = PdfDocument()
        try {
            val total = rr.pageCount
            if (total == 0) throw IOException("the document has no pages")
            var idx = PrintLayout.pageSet(g("range"), total)
            if (g("pages").isNotEmpty()) {
                val want = if (g("pages") == "odd") 1 else 0
                idx = idx.filter { (it + 1) % 2 == want }.toMutableList()
            }
            if (g("reverse") == "1") idx.reverse()
            if (idx.isEmpty()) throw IOException("no pages are left after the page selection")

            // size of the first page: sets the orientation of the sheet when the paper is on "Auto" (as in the preview)
            val p0 = rr.openPage(0)
            val fw = p0.width; val fh = p0.height
            p0.close()

            val booklet = g("booklet") == "1"
            val order: List<Int?>
            val cols: Int; val rows: Int; val land: Boolean
            if (booklet) {
                order = PrintLayout.booklet(idx.size).map { if (it == null) null else idx[it] }
                cols = 2; rows = 1; land = true
            } else {
                order = idx
                val l = PrintLayout.LAY[g("nup").toIntOrNull() ?: 1] ?: PrintLayout.LAY[1]!!
                cols = l.first; rows = l.second; land = l.third
            }
            val per = cols * rows
            val pd = PrintLayout.paper(paperKey)
            val iland = !isText && str(o, "paper").isEmpty() && fw > fh
            val pw0 = if (iland) pd[1] else pd[0]
            val ph0 = if (iland) pd[0] else pd[1]

            val margin = g("margin")
            val scaleV = PrintLayout.num(g("scale"))
            val align = g("align")
            val fitting = !isText && (margin.isNotEmpty() || scaleV > 0 || align.isNotEmpty() || g("autorot") == "1" || g("fit") == "fill")
            val fm = if (fitting && margin.isNotEmpty()) PrintLayout.num(margin) else 0.0
            val clipBox = fitting && per == 1 && fm > 0
            val sw: Double; val sh: Double
            if (per == 1) { sw = pw0; sh = ph0 } else { sw = if (land) pd[1] else pd[0]; sh = if (land) pd[0] else pd[1] }
            val nSheets = ceil(order.size / per.toDouble()).toInt()
            val wmT = g("wm")
            val wmUnder = g("wm_under") == "1"
            val border = g("border") == "1"
            val hdr = g("hdr")
            val ftr = g("ftr")

            val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val bord = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = 0.8f }

            for (s0 in 0 until nSheets) {
                if (job.cancel) throw Cancelled()
                val chunk = order.subList(s0 * per, min(order.size, s0 * per + per))
                val page = doc.startPage(PdfDocument.PageInfo.Builder(Math.round(sw).toInt(), Math.round(sh).toInt(), s0 + 1).create())
                val cv = page.canvas
                cv.drawColor(Color.WHITE)

                val cw = (sw - 2 * fm) / cols
                val ch = (sh - 2 * fm) / rows
                val mg = if (per == 1) 0.0 else if (booklet) 8.0 else 12.0
                class Cl(val pi: Int, val sc: Double, val x: Double, val y: Double)
                val cells = ArrayList<Cl>()
                chunk.forEachIndexed { j, pi ->
                    if (pi == null) return@forEachIndexed   // blank page of a booklet
                    if (!fitting) {
                        val sc = if (per == 1) 1.0 else min((cw - 2 * mg) / pw0, (ch - 2 * mg) / ph0)
                        cells.add(Cl(pi, sc, (j % cols) * cw + (cw - pw0 * sc) / 2, (j / cols) * ch + (ch - ph0 * sc) / 2))
                        return@forEachIndexed
                    }
                    val iw = cw - 2 * mg; val ih = ch - 2 * mg
                    val sc = PrintLayout.fitK(pw0, ph0, iw, ih, st, per)
                    val ix = fm + (j % cols) * cw + mg
                    val iy = fm + (j / cols) * ch + mg
                    cells.add(Cl(pi, sc, if (align == "topleft") ix else ix + (iw - pw0 * sc) / 2, if (align.isNotEmpty()) iy else iy + (ih - ph0 * sc) / 2))
                }

                fun watermark() {
                    if (wmT.isEmpty()) return
                    val t = PrintLayout.fill(wmT, 0, nSheets, name)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 100f }
                    val w1 = (tp.measureText(t) / 100f).let { if (it == 0f) 1f else it }
                    val size = max(8f, min(150f, (hypot(sw, sh) * .7 / w1).toFloat()))
                    tp.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); tp.textSize = size
                    tp.color = Color.argb((.28 * 255).toInt(), 128, 128, 128); tp.textAlign = Paint.Align.CENTER
                    cv.save(); cv.translate((sw / 2).toFloat(), (sh / 2).toFloat()); cv.rotate(-Math.toDegrees(atan2(sh, sw)).toFloat())
                    val m2 = tp.fontMetrics
                    cv.drawText(t, 0f, -(m2.ascent + m2.descent) / 2f, tp)
                    cv.restore()
                }

                if (wmT.isNotEmpty() && wmUnder) watermark()
                if (clipBox) { cv.save(); cv.clipRect(fm.toFloat(), fm.toFloat(), (sw - fm).toFloat(), (sh - fm).toFloat()) }
                for (m in cells) {
                    val src = rr.openPage(m.pi)      // PdfRenderer allows one open page at a time
                    try {
                        val ar = src.width.toDouble() / src.height
                        var w = pw0; var hh = pw0 / ar
                        if (hh > ph0) { hh = ph0; w = ph0 * ar }
                        val dw = w * m.sc; val dh = hh * m.sc
                        val left = m.x + (pw0 - w) / 2 * m.sc
                        val top = m.y + (ph0 - hh) / 2 * m.sc
                        var bw = (dw * 2).toInt().coerceAtLeast(1); var bh = (dh * 2).toInt().coerceAtLeast(1)   // ~144 dpi
                        val big = max(bw, bh); if (big > 3300) { val f = 3300f / big; bw = (bw * f).toInt().coerceAtLeast(1); bh = (bh * f).toInt().coerceAtLeast(1) }
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        cv.drawBitmap(bmp, null, RectF(left.toFloat(), top.toFloat(), (left + dw).toFloat(), (top + dh).toFloat()), bp)
                        bmp.recycle()
                    } finally { src.close() }
                }
                if (clipBox) cv.restore()
                if (!(wmT.isNotEmpty() && wmUnder)) watermark()
                if (border && per > 1) for (m in cells) cv.drawRect(RectF(m.x.toFloat(), m.y.toFloat(), (m.x + pw0 * m.sc).toFloat(), (m.y + ph0 * m.sc).toFloat()), bord)

                if (hdr.isNotEmpty() || ftr.isNotEmpty()) {
                    val fs = 9f
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
                doc.finishPage(page)
            }
            val out = File(pdf.parentFile, pdf.name + ".cmp.pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } finally {
            doc.close()
            try { rr.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
        }
    }

    /**
     * Pictures only (the dialog's "picture mode", option sheet = 1): every picture on shared sheets, laid out by [PrintLayout.sheets]
     * (= pcprint.py images_to_sheets). [jpgs] = the converted pictures (EXIF applied), [rots] = manual turn of each (0 / 90 / 180 / 270).
     */
    fun pictureSheets(job: Job, jpgs: List<File>, rots: IntArray, o: JSONObject?, paperKey: String): File {
        val st = HashMap<String, String>()
        for (k in listOf("nup", "border", "reverse", "fit", "margin", "noauto")) st[k] = str(o, k)
        st["paper"] = paperKey
        val dims = ArrayList<DoubleArray>()
        for (f in jpgs) {
            val bo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, bo)
            dims.add(doubleArrayOf(max(1, bo.outWidth).toDouble(), max(1, bo.outHeight).toDouble()))
        }
        val plan = PrintLayout.sheets(dims, rots, st)
        val doc = PdfDocument()
        try {
            val line = Paint().apply { style = Paint.Style.STROKE; color = 0xFF555555.toInt(); strokeWidth = 0.8f }
            val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val border = st["border"] == "1"
            for ((sn, cells) in plan.sheets.withIndex()) {
                if (job.cancel) throw Cancelled()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(Math.round(plan.sw).toInt(), Math.round(plan.sh).toInt(), sn + 1).create())
                val cv = page.canvas
                cv.drawColor(Color.WHITE)
                for (c in cells) {
                    val f = jpgs[c.i]
                    val bo = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f.path, bo)
                    var ss = 1
                    while (max(bo.outWidth, bo.outHeight) / ss > 2400) ss *= 2
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) ?: continue
                    try {
                        val ow = dims[c.i][0]; val oh = dims[c.i][1]       // picture size as it looks, unturned
                        val bw = (ow * c.k).toFloat(); val bh = (oh * c.k).toFloat()
                        cv.save()
                        if (st["fit"] == "fill") cv.clipRect((c.cx - c.w / 2).toFloat(), (c.cy - c.h / 2).toFloat(), (c.cx + c.w / 2).toFloat(), (c.cy + c.h / 2).toFloat())
                        cv.translate(c.cx.toFloat(), c.cy.toFloat())
                        if (c.r != 0) cv.rotate(c.r.toFloat())
                        cv.drawBitmap(bmp, null, RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), bp)
                        cv.restore()
                        if (border) cv.drawRect(RectF((c.cx - c.w / 2).toFloat(), (c.cy - c.h / 2).toFloat(), (c.cx + c.w / 2).toFloat(), (c.cy + c.h / 2).toFloat()), line)
                    } finally { bmp.recycle() }
                }
                doc.finishPage(page)
            }
            val out = File(jpgs[0].parentFile, "sheets-" + System.nanoTime().toString(36) + ".pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } finally { doc.close() }
    }
}
