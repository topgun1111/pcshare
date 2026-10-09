package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.PrintPrep
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

/**
 * Printing on this phone through the Android print system - ONE print job for all selected files (one dialog).
 *  - PDFs keep their real page count, so the dialog's page range / preview work. A single PDF printed in full is passed through
 *    untouched (sharp vector output); a page range or a mixed job re-renders the chosen pages at 200 dpi.
 *  - Pictures: fit / fill the page, or 2 / 4 per page; EXIF rotation is applied; the paper opens in landscape for a wide picture.
 *  - Text files (txt, csv, log, md ...) are paginated here (UTF-8, falls back to the Turkish Windows code page).
 */
class PhonePrint(private val act: Activity) {
    private val pool = Executors.newSingleThreadExecutor()   // downloads / job set-up
    private val io = Executors.newSingleThreadExecutor()     // layout + page rendering (PdfRenderer is not thread-safe)

    private fun toast(t: String) = act.runOnUiThread { Toast.makeText(act, t, Toast.LENGTH_LONG).show() }

    private class In(val name: String, val file: File, val kind: String, val rot: Int = 0)

    private class PdfSrc(val file: File) {
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        val count = r.pageCount
        fun close() { try { r.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }
    }

    private sealed class Part
    private class PdfPart(val src: PdfSrc) : Part()
    private class ImgPart(val items: List<Pair<File, Int>>) : Part()   // picture + its manual turn in degrees
    private class TxtPart(val file: File) : Part()

    private sealed class Pg
    private class PdfPg(val src: PdfSrc, val idx: Int) : Pg()
    private class ImgPg(val items: List<Pair<File, Int>>) : Pg()
    private class TxtPg(val lay: StaticLayout, val from: Int, val to: Int) : Pg()

    /** json = {items:[{name, url}]}; urls must point at the app's own local server. */
    fun start(json: String) {
        toast("Preparing\u2026")   // downloading / converting can take a while: the tap must not look dead
        pool.execute {
            try { prepare(json) } catch (e: Exception) { toast("Print failed: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun prepare(json: String) {
        val root = JSONObject(json)
        val arr = root.getJSONArray("items")
        val given = parseOpts(root.optJSONObject("opts"))   // chosen in the picture-sheet dialog (all pictures); null = ask the small layout question when pictures are mixed in
        val dir = File(act.cacheDir, "print").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        class Dl(val i: Int, val name: String, val url: String, val f: File)
        val dls = ArrayList<Dl>()
        for (i in 0 until minOf(arr.length(), 60)) {
            val o = arr.getJSONObject(i)
            val url = o.getString("url")
            val name = o.optString("name", "file")
            val host = URL(url).host
            if (host != "127.0.0.1" && host != "localhost") throw IOException("bad address")
            dls.add(Dl(i, name, url, File(dir, "${System.nanoTime()}_${i}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))))
        }
        // downloads run 4 at a time (the files come from the app's own local server); the order of the files is kept
        val dl = Executors.newFixedThreadPool(4)
        try {
            val res = dl.invokeAll(dls.map { d -> java.util.concurrent.Callable { URL(d.url).openStream().use { s -> d.f.outputStream().use { out -> s.copyTo(out) } } } })
            for (r in res) try { r.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e }
        } finally { dl.shutdown() }

        // every file -> pdf / picture / text through PrintPrep (the same conversions the PC path uses); Office files through a PC's /convert
        val ins = ArrayList<In>()
        val all = ArrayList<File>()   // downloads + converted copies: deleted when the job ends
        val failed = ArrayList<String>()
        var convIp: String? = null
        for (d in dls) {
            all.add(d.f)
            try {
                var f = d.f
                var name = d.name
                var kind = ""
                for (round in 0 until 2) {   // a second round only for "unknown type": PrintPrep looks inside and renames it
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val base = if (name.contains('.')) name.substringBeforeLast('.') else name
                    kind = when {
                        ext == "pdf" -> "pdf"
                        ext in IMG_NATIVE -> "img"
                        ext in OFFICE -> "office"
                        ext == "md" || ext == "markdown" -> "md"
                        ext in TXT || ext in PrintPrep.TEXT -> "txt"
                        else -> when (PrintPrep.kind(ext)) {
                            PrintPrep.Kind.PIC -> "pic"
                            PrintPrep.Kind.WEB -> "web"
                            PrintPrep.Kind.SNIFF -> "sniff"
                            else -> throw IOException(if (ext.isEmpty()) "this file type cannot be printed" else ".$ext files cannot be printed")
                        }
                    }
                    when (kind) {
                        "pic" -> { val o = PrintPrep.convert(PrintPrep.Kind.PIC, f, name); all.add(o.file); f = o.file; name = o.name; kind = "img" }
                        "web" -> { val o = PrintPrep.convert(PrintPrep.Kind.WEB, f, name); all.add(o.file); f = o.file; name = o.name; kind = "pdf" }
                        "md" -> { val o = PrintPrep.md(f, base); all.add(o.file); f = o.file; name = o.name; kind = "pdf" }
                        "office" -> {
                            val ip = convIp ?: Jobs.converterIp().also { convIp = it }
                            val out = File(f.parentFile, f.name + ".pdf")
                            all.add(out)
                            Jobs.officeToPdf(ip, f, name, out)
                            f = out; name = "$base.pdf"; kind = "pdf"
                        }
                        "sniff" -> {
                            if (round == 1) throw IOException("this file type cannot be printed")
                            val o = PrintPrep.convert(PrintPrep.Kind.SNIFF, f, name)
                            if (o.file != f) all.add(o.file)
                            f = o.file; name = o.name
                            if (round == 0) continue   // classify again under the new name
                        }
                    }
                    break
                }
                if (kind == "sniff") throw IOException("this file type cannot be printed")
                ins.add(In(d.name, f, kind, if (given != null && given.rots.size == dls.size) given.rots[d.i] else 0))
            } catch (e: Exception) {
                failed.add("${d.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (failed.isNotEmpty()) toast("Can't print " + failed.joinToString("; "))
        if (ins.isEmpty()) { all.forEach { it.delete() }; return }
        val imgs = ins.count { it.kind == "img" }
        if (imgs == 0 || given != null) build(ins, all, given ?: ImgOpts())
        else act.runOnUiThread {
            askLayout(imgs) { opts ->
                pool.execute {
                    try { build(ins, all, opts) } catch (e: Exception) { toast("Print failed: " + (e.message ?: e.javaClass.simpleName)) }
                }
            }
        }
    }

    /** Options of the picture-sheet dialog (ui.html printOptions): pictures per sheet, fill, margin, border, per-picture turn, order. */
    private class ImgOpts(val per: Int = 1, val fill: Boolean = false, val margin: Float = 0f, val border: Boolean = false,
                          val rots: List<Int> = emptyList(), val reverse: Boolean = false, val noauto: Boolean = true, val paper: String = "")

    private fun parseOpts(o: JSONObject?): ImgOpts? {
        if (o == null) return null
        val on = { k: String -> o.optString(k).let { it == "1" || it == "true" } }
        val r = o.optJSONArray("rots")
        return ImgOpts(
            per = o.optString("nup").toIntOrNull()?.takeIf { it in GRID } ?: 1,
            fill = o.optString("fit") == "fill",
            margin = o.optString("margin").toFloatOrNull()?.coerceIn(0f, 72f) ?: 14f,
            border = on("border"), rots = if (r == null) emptyList() else List(r.length()) { (r.optInt(it, 0) % 360 + 360) % 360 },
            reverse = on("reverse"), noauto = on("noauto"), paper = o.optString("paper"))
    }

    private fun askLayout(n: Int, done: (ImgOpts) -> Unit) {   // pictures mixed with other files: the small question (all pictures: the full dialog in ui.html)
        val prefs = act.getSharedPreferences("ls_print", Context.MODE_PRIVATE)
        val labels = if (n == 1) arrayOf("Fit on the page", "Fill the page (crop)")
        else arrayOf("1 per page - fit", "1 per page - fill (crop)", "2 per page", "4 per page")
        var sel = prefs.getInt("layout", 0).coerceIn(0, labels.size - 1)
        AlertDialog.Builder(act)
            .setTitle(if (n == 1) "Picture size" else "Pictures per page")
            .setSingleChoiceItems(labels, sel) { _, w -> sel = w }
            .setPositiveButton("Print") { _, _ -> prefs.edit().putInt("layout", sel).apply(); done(ImgOpts(per = intArrayOf(1, 1, 2, 4)[sel], fill = sel == 1)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun build(ins: List<In>, all: List<File>, op: ImgOpts) {
        val parts = ArrayList<Part>()
        val pend = ArrayList<Pair<File, Int>>()
        fun flush() {
            if (pend.isNotEmpty()) { parts.add(ImgPart(if (op.reverse) pend.reversed() else ArrayList(pend))); pend.clear() }
        }
        for (x in ins) {
            when (x.kind) {
                "img" -> pend.add(x.file to x.rot)
                "pdf" -> { flush(); parts.add(PdfPart(PdfSrc(x.file))) }
                else -> { flush(); parts.add(TxtPart(x.file)) }
            }
        }
        flush()
        val title = ins[0].name + if (ins.size > 1) " +" + (ins.size - 1) else ""
        val adapter = Adapter(parts, op, all, title)
        val base = when (op.paper) {
            "A3" -> PrintAttributes.MediaSize.ISO_A3; "A5" -> PrintAttributes.MediaSize.ISO_A5
            "Letter" -> PrintAttributes.MediaSize.NA_LETTER; "Legal" -> PrintAttributes.MediaSize.NA_LEGAL
            else -> PrintAttributes.MediaSize.ISO_A4
        }
        val attrs = PrintAttributes.Builder()
            .setMediaSize(if (adapter.firstIsLandscape()) base.asLandscape() else base.asPortrait()).build()
        act.runOnUiThread {
            try {
                (act.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(title, adapter, attrs)
            } catch (e: Exception) {
                toast("Print failed: " + (e.message ?: e.javaClass.simpleName))
                io.execute { adapter.release() }
            }
        }
    }

    // ------------------------------------------------------------------ the print document
    private inner class Adapter(val parts: List<Part>, val op: ImgOpts, val files: List<File>, val title: String) : PrintDocumentAdapter() {
        @Volatile private var pages: List<Pg> = emptyList()
        @Volatile private var attrs: PrintAttributes? = null
        @Volatile private var layKey = ""   // media size + margins of the last finished layout
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)


        fun firstIsLandscape(): Boolean {
            return when (val p = parts[0]) {
                is PdfPart -> { val pg = p.src.r.openPage(0); try { pg.width > pg.height } finally { pg.close() } }
                is ImgPart -> imgLandscape(p)
                is TxtPart -> false
            }
        }

        fun release() {
            for (p in parts) if (p is PdfPart) p.src.close()
            files.forEach { it.delete() }
        }

        override fun onLayout(old: PrintAttributes?, now: PrintAttributes, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
            attrs = now
            // only media size / orientation / margins change the layout: copies, colour, duplex ... must not make Android re-write the whole document
            val k = (now.mediaSize?.let { "${it.widthMils}x${it.heightMils}${it.isPortrait}" } ?: "") + "|" + (now.minMargins?.let { "${it.leftMils},${it.topMils},${it.rightMils},${it.bottomMils}" } ?: "")
            if (k == layKey && pages.isNotEmpty()) {
                val same = PrintDocumentInfo.Builder("$title.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages.size).build()
                cb.onLayoutFinished(same, false); return
            }
            io.execute {
                try {
                    layKey = k
                    val ms = now.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
                    val mm = now.minMargins
                    val cw = (ms.widthMils - (mm?.leftMils ?: 0) - (mm?.rightMils ?: 0)) * 72 / 1000
                    val ch = (ms.heightMils - (mm?.topMils ?: 0) - (mm?.bottomMils ?: 0)) * 72 / 1000
                    val list = ArrayList<Pg>()
                    for (p in parts) {
                        when (p) {
                            is PdfPart -> for (k in 0 until p.src.count) list.add(PdfPg(p.src, k))
                            is ImgPart -> { val n = op.per; var k = 0; while (k < p.items.size) { list.add(ImgPg(p.items.subList(k, minOf(k + n, p.items.size)))); k += n } }
                            is TxtPart -> paginate(p.file, cw, ch, list)
                        }
                    }
                    pages = list
                    if (list.isEmpty()) { act.runOnUiThread { cb.onLayoutFailed("Nothing to print") }; return@execute }
                    val info = PrintDocumentInfo.Builder("$title.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(list.size).build()
                    act.runOnUiThread { cb.onLayoutFinished(info, true) }
                } catch (e: Exception) {
                    layKey = ""
                    act.runOnUiThread { cb.onLayoutFailed(e.message ?: "layout failed") }
                }
            }
        }

        override fun onWrite(range: Array<out PageRange>, out: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
            io.execute {
                try {
                    val list = pages
                    val a = attrs ?: throw IOException("no layout")
                    val sel = selected(range, list.size)
                    val only = if (parts.size == 1) parts[0] as? PdfPart else null
                    if (only != null && sel.size == list.size) {
                        FileOutputStream(out.fileDescriptor).use { o -> only.src.file.inputStream().use { it.copyTo(o) } }
                    } else {
                        val doc = PrintedPdfDocument(act, a)
                        try {
                            for (k in sel) {
                                if (cancel?.isCanceled == true) { act.runOnUiThread { cb.onWriteCancelled() }; return@execute }
                                val page = doc.startPage(k)
                                drawPage(page.canvas, page.info.contentRect, list[k])
                                doc.finishPage(page)
                            }
                            FileOutputStream(out.fileDescriptor).use { doc.writeTo(it) }
                        } finally { doc.close() }
                    }
                    val done = range.toList().toTypedArray()
                    act.runOnUiThread { cb.onWriteFinished(done) }
                } catch (e: Throwable) {
                    act.runOnUiThread { cb.onWriteFailed(e.message ?: e.javaClass.simpleName) }
                }
            }
        }

        override fun onFinish() { io.execute { release() } }

        private fun selected(r: Array<out PageRange>, n: Int): List<Int> {
            val s = java.util.TreeSet<Int>()
            for (x in r) for (k in maxOf(x.start, 0)..minOf(x.end, n - 1)) s.add(k)
            return s.toList()
        }

        // -------------------------------------------------------------- drawing (units are PDF points, 1/72 inch)
        private fun drawPage(c: Canvas, box: Rect, pg: Pg) {
            when (pg) {
                is PdfPg -> drawPdf(c, box, pg)
                is ImgPg -> drawSheet(c, box, pg)
                is TxtPg -> {
                    if (pg.lay.lineCount == 0) return
                    val top = pg.lay.getLineTop(pg.from)
                    val bottom = pg.lay.getLineBottom(pg.to - 1)
                    c.save()
                    c.translate((box.left + PAD).toFloat(), (box.top + PAD).toFloat())
                    c.clipRect(0f, 0f, pg.lay.width.toFloat(), (bottom - top).toFloat())
                    c.translate(0f, -top.toFloat())
                    pg.lay.draw(c)
                    c.restore()
                }
            }
        }

        private fun drawPdf(c: Canvas, box: Rect, pg: PdfPg) {
            val page = pg.src.r.openPage(pg.idx)
            try {
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                val k = minOf(box.width() / pw, box.height() / ph, 1f)   // fit into the printable area, never enlarge
                val dw = pw * k
                val dh = ph * k
                var bw = dw * DPI / 72f
                var bh = dh * DPI / 72f
                val big = maxOf(bw, bh)
                if (big > 2800f) { bw *= 2800f / big; bh *= 2800f / big }
                val bmp = Bitmap.createBitmap(maxOf(bw.toInt(), 1), maxOf(bh.toInt(), 1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                val l = box.left + (box.width() - dw) / 2f
                val t = box.top + (box.height() - dh) / 2f
                c.drawBitmap(bmp, null, RectF(l, t, l + dw, t + dh), paint)
                bmp.recycle()
            } finally { page.close() }
        }

        /** Best turn (extra 0 / 90 degrees on top of the manual one) for a picture in a cell: the one that shows it larger. -> [turn, scale, shown w, shown h] */
        private fun bestTurn(f: File, rot: Int, cw: Float, ch: Float): FloatArray {
            val (ew, eh) = imgSize(f)
            var best: FloatArray? = null
            for (extra in if (op.noauto) intArrayOf(0) else intArrayOf(0, 90)) {
                val r = (rot + extra) % 360
                val dw = (if (r % 180 != 0) eh else ew).toFloat()
                val dh = (if (r % 180 != 0) ew else eh).toFloat()
                val k = minOf(cw / dw, ch / dh)
                if (best == null || k > best[1] * 1.0001f) best = floatArrayOf(r.toFloat(), k, dw, dh)
            }
            return best!!
        }

        /** (columns, rows, cell width, cell height) of a sheet of w x h points */
        private fun cellSize(w: Float, h: Float, portrait: Boolean): FloatArray {
            val g = GRID[op.per]!!.let { if (portrait) it.first else it.second }
            val cols = g.first
            val rows = g.second
            return floatArrayOf(cols.toFloat(), rows.toFloat(), (w - 2 * op.margin - GAP * (cols - 1)) / cols, (h - 2 * op.margin - GAP * (rows - 1)) / rows)
        }

        /** Portrait or landscape sheet, whichever shows the pictures larger (same rule as pcprint.py images_to_sheets). */
        fun imgLandscape(p: ImgPart): Boolean {
            val pd = when (op.paper) { "A3" -> 842f to 1191f; "A5" -> 420f to 595f; "Letter" -> 612f to 792f; "Legal" -> 612f to 1008f; else -> 595f to 842f }
            fun score(portrait: Boolean): Float {
                val c = cellSize(if (portrait) pd.first else pd.second, if (portrait) pd.second else pd.first, portrait)
                var s = 0f
                for ((f, rot) in p.items) { val b = bestTurn(f, rot, c[2], c[3]); s += b[1] * b[1] * b[2] * b[3] }
                return s
            }
            return score(false) > score(true) * 1.0001f
        }

        private fun drawSheet(c: Canvas, box: Rect, pg: ImgPg) {
            val cs = cellSize(box.width().toFloat(), box.height().toFloat(), box.height() >= box.width())
            val cols = cs[0].toInt()
            pg.items.forEachIndexed { j, (f, rot) ->
                val l = box.left + op.margin + (j % cols) * (cs[2] + GAP)
                val t = box.top + op.margin + (j / cols) * (cs[3] + GAP)
                drawImage(c, f, RectF(l, t, l + cs[2], t + cs[3]), op.fill, bestTurn(f, rot, cs[2], cs[3])[0].toInt(), op.border)
            }
        }

        private fun drawImage(c: Canvas, f: File, cell: RectF, fill: Boolean, turn: Int, border: Boolean) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return
            val need = maxOf(cell.width(), cell.height()) * DPI / 72f
            val big = maxOf(o.outWidth, o.outHeight)
            var s = 1
            while (big / (s * 2) >= need) s *= 2
            while (big / s > 4096) s *= 2
            var bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
            val m = exifMatrix(f)
            if (turn != 0) m.postRotate(turn.toFloat())
            if (!m.isIdentity) {
                val r = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
                if (r !== bm) bm.recycle()
                bm = r
            }
            val k = if (fill) maxOf(cell.width() / bm.width, cell.height() / bm.height) else minOf(cell.width() / bm.width, cell.height() / bm.height)
            val dw = bm.width * k
            val dh = bm.height * k
            val l = cell.left + (cell.width() - dw) / 2f
            val t = cell.top + (cell.height() - dh) / 2f
            c.save()
            c.clipRect(cell)
            c.drawBitmap(bm, null, RectF(l, t, l + dw, t + dh), paint)
            if (border) c.drawRect(maxOf(l, cell.left), maxOf(t, cell.top), minOf(l + dw, cell.right), minOf(t + dh, cell.bottom),
                Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = Color.rgb(153, 153, 153) })
            c.restore()
            bm.recycle()
        }

        private fun orientation(f: File): Int =
            try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

        private fun exifMatrix(f: File): Matrix {
            val m = Matrix()
            when (orientation(f)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
                else -> {}
            }
            return m
        }

        /** Picture size as it will be printed (EXIF rotation applied). */
        private fun imgSize(f: File): Pair<Int, Int> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            val o2 = orientation(f)
            val swap = o2 == ExifInterface.ORIENTATION_TRANSPOSE || o2 == ExifInterface.ORIENTATION_ROTATE_90 ||
                o2 == ExifInterface.ORIENTATION_TRANSVERSE || o2 == ExifInterface.ORIENTATION_ROTATE_270
            return if (swap) o.outHeight to o.outWidth else o.outWidth to o.outHeight
        }

        // -------------------------------------------------------------- text
        private fun paginate(f: File, cw: Int, ch: Int, out: MutableList<Pg>) {
            val txt = readText(f).replace("\t", "    ")
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; typeface = Typeface.MONOSPACE; color = Color.BLACK }
            val w = maxOf(cw - 2 * PAD, 100)
            val lay = StaticLayout.Builder.obtain(txt, 0, txt.length, tp, w).setIncludePad(false).build()
            val avail = ch - 2 * PAD
            val n = lay.lineCount
            if (n == 0) { out.add(TxtPg(lay, 0, 0)); return }
            var line = 0
            while (line < n) {
                val top = lay.getLineTop(line)
                var end = line
                while (end < n && lay.getLineBottom(end) - top <= avail) end++
                if (end == line) end = line + 1
                out.add(TxtPg(lay, line, end))
                line = end
            }
        }

        /** UTF-8 (with or without BOM), otherwise the Turkish Windows code page; at most 4 MB are read. */
        private fun readText(f: File): String {
            val cap = 4_000_000
            val all = f.length() > cap
            val b = f.inputStream().use { ins ->
                val buf = ByteArray(minOf(f.length(), cap.toLong()).toInt())
                var n = 0
                while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                if (n == buf.size) buf else buf.copyOf(n)
            }
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val inb = ByteBuffer.wrap(b)
            val outb = CharBuffer.allocate(b.size + 1)
            val res = dec.decode(inb, outb, !all)
            val s = if (res.isError) String(b, Charset.forName("windows-1254")) else { outb.flip(); outb.toString() }
            return s.removePrefix("\uFEFF")
        }
    }

    private companion object {
        const val PAD = 24      // extra inner margin for text pages (points)
        const val DPI = 200f    // resolution of re-rendered PDF pages and pictures
        const val GAP = 8f      // between pictures on a sheet (points)
        /** pictures per sheet -> (columns, rows) on a portrait sheet / on a landscape sheet: the same table as pcprint.py SHEET_GRID */
        val GRID = mapOf(1 to ((1 to 1) to (1 to 1)), 2 to ((1 to 2) to (2 to 1)), 4 to ((2 to 2) to (2 to 2)), 6 to ((2 to 3) to (3 to 2)), 9 to ((3 to 3) to (3 to 3)))
        val IMG_NATIVE = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")
        val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")   // converted to PDF by a PC running pcprint.py
        val TXT = setOf("txt", "log", "csv", "tsv")
    }
}
