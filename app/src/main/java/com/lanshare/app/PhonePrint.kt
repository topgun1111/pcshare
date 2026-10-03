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

    private class In(val name: String, val file: File, val kind: String)

    private class PdfSrc(val file: File) {
        private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        val count = r.pageCount
        fun close() { try { r.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }
    }

    private sealed class Part
    private class PdfPart(val src: PdfSrc) : Part()
    private class ImgPart(val files: List<File>) : Part()
    private class TxtPart(val file: File) : Part()

    private sealed class Pg
    private class PdfPg(val src: PdfSrc, val idx: Int) : Pg()
    private class ImgPg(val files: List<File>) : Pg()
    private class TxtPg(val lay: StaticLayout, val from: Int, val to: Int) : Pg()

    /** json = {items:[{name, url}]}; urls must point at the app's own local server. */
    fun start(json: String) {
        pool.execute {
            try { prepare(json) } catch (e: Exception) { toast("Print failed: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun prepare(json: String) {
        val arr = JSONObject(json).getJSONArray("items")
        val dir = File(act.cacheDir, "print").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val ins = ArrayList<In>()
        val skipped = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 60)) {
            val o = arr.getJSONObject(i)
            val url = o.getString("url")
            val name = o.optString("name", "file")
            val host = URL(url).host
            if (host != "127.0.0.1" && host != "localhost") throw IOException("bad address")
            val ext = name.substringAfterLast('.', "").lowercase()
            val kind = when (ext) {
                "pdf" -> "pdf"
                "png", "jpg", "jpeg", "bmp", "gif", "webp" -> "img"
                "txt", "log", "md", "csv", "tsv", "json", "xml", "ini", "cfg", "conf", "yml", "yaml", "srt", "sql", "py", "kt", "java", "js", "html", "htm" -> "txt"
                else -> { skipped.add(".$ext"); continue }
            }
            val f = File(dir, "${System.nanoTime()}_${i}_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            URL(url).openStream().use { s -> f.outputStream().use { out -> s.copyTo(out) } }
            ins.add(In(name, f, kind))
        }
        if (skipped.isNotEmpty())
            toast("Can't print " + skipped.distinct().joinToString(", ") + " on the phone. Use a PC with pcprint.py for Office files.")
        if (ins.isEmpty()) return
        val imgs = ins.count { it.kind == "img" }
        if (imgs == 0) build(ins, 0)
        else act.runOnUiThread {
            askLayout(imgs) { layout ->
                pool.execute {
                    try { build(ins, layout) } catch (e: Exception) { toast("Print failed: " + (e.message ?: e.javaClass.simpleName)) }
                }
            }
        }
    }

    private fun askLayout(n: Int, done: (Int) -> Unit) {
        val prefs = act.getSharedPreferences("ls_print", Context.MODE_PRIVATE)
        val labels = if (n == 1) arrayOf("Fit on the page", "Fill the page (crop)")
        else arrayOf("1 per page - fit", "1 per page - fill (crop)", "2 per page", "4 per page")
        var sel = prefs.getInt("layout", 0).coerceIn(0, labels.size - 1)
        AlertDialog.Builder(act)
            .setTitle(if (n == 1) "Picture size" else "Pictures per page")
            .setSingleChoiceItems(labels, sel) { _, w -> sel = w }
            .setPositiveButton("Print") { _, _ -> prefs.edit().putInt("layout", sel).apply(); done(sel) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun build(ins: List<In>, layout: Int) {
        val parts = ArrayList<Part>()
        val pend = ArrayList<File>()
        fun flush() { if (pend.isNotEmpty()) { parts.add(ImgPart(ArrayList(pend))); pend.clear() } }
        for (x in ins) {
            when (x.kind) {
                "img" -> pend.add(x.file)
                "pdf" -> { flush(); parts.add(PdfPart(PdfSrc(x.file))) }
                else -> { flush(); parts.add(TxtPart(x.file)) }
            }
        }
        flush()
        val title = ins[0].name + if (ins.size > 1) " +" + (ins.size - 1) else ""
        val adapter = Adapter(parts, layout, ins.map { it.file }, title)
        val base = PrintAttributes.MediaSize.ISO_A4
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
    private inner class Adapter(val parts: List<Part>, val layout: Int, val files: List<File>, val title: String) : PrintDocumentAdapter() {
        @Volatile private var pages: List<Pg> = emptyList()
        @Volatile private var attrs: PrintAttributes? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        private fun perPage() = when (layout) { 2 -> 2; 3 -> 4; else -> 1 }

        fun firstIsLandscape(): Boolean {
            return when (val p = parts[0]) {
                is PdfPart -> { val pg = p.src.r.openPage(0); try { pg.width > pg.height } finally { pg.close() } }
                is ImgPart -> if (perPage() == 1) { val (w, h) = imgSize(p.files[0]); w > h } else false
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
            io.execute {
                try {
                    val ms = now.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
                    val mm = now.minMargins
                    val cw = (ms.widthMils - (mm?.leftMils ?: 0) - (mm?.rightMils ?: 0)) * 72 / 1000
                    val ch = (ms.heightMils - (mm?.topMils ?: 0) - (mm?.bottomMils ?: 0)) * 72 / 1000
                    val list = ArrayList<Pg>()
                    for (p in parts) {
                        when (p) {
                            is PdfPart -> for (k in 0 until p.src.count) list.add(PdfPg(p.src, k))
                            is ImgPart -> { val n = perPage(); var k = 0; while (k < p.files.size) { list.add(ImgPg(p.files.subList(k, minOf(k + n, p.files.size)))); k += n } }
                            is TxtPart -> paginate(p.file, cw, ch, list)
                        }
                    }
                    pages = list
                    if (list.isEmpty()) { act.runOnUiThread { cb.onLayoutFailed("Nothing to print") }; return@execute }
                    val info = PrintDocumentInfo.Builder("$title.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(list.size).build()
                    act.runOnUiThread { cb.onLayoutFinished(info, true) }
                } catch (e: Exception) {
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
                is ImgPg -> {
                    val cells = cells(box, perPage())
                    pg.files.forEachIndexed { i, f -> drawImage(c, f, cells[i], layout == 1) }
                }
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

        private fun cells(box: Rect, n: Int): List<RectF> {
            val gap = 10f
            val cols = if (n == 1) 1 else if (n == 2) (if (box.width() >= box.height()) 2 else 1) else 2
            val rows = if (n == 1) 1 else if (n == 2) (if (box.width() >= box.height()) 1 else 2) else 2
            val cw = (box.width() - gap * (cols - 1)) / cols
            val ch = (box.height() - gap * (rows - 1)) / rows
            val out = ArrayList<RectF>()
            for (r in 0 until rows) for (k in 0 until cols) {
                val l = box.left + k * (cw + gap)
                val t = box.top + r * (ch + gap)
                out.add(RectF(l, t, l + cw, t + ch))
            }
            return out
        }

        private fun drawImage(c: Canvas, f: File, cell: RectF, fill: Boolean) {
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
    }
}
