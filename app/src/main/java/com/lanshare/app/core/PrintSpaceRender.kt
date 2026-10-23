package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Print space part 6a: the sheet renderer. Takes the layout the editor shows (spec + plan of POST /api/pslayout, composed by
 * [PsLayoutJson.resolve]) and draws it, so what the preview shows is what gets printed.
 *
 *  - [prepare]   POST /api/psrender {op:"prepare", spec, plan}  -> {ok, id, pages, sheets, sw, sh, duplex, list:[{sheet, side}]}
 *                (cheap, nothing is drawn). `id` = hash of the composed layout, so the same layout gives the same id.
 *  - [page]      GET  /api/pspage?id&n&w  -> JPEG of page n (0-based, PRINT order: sheet 0 front, sheet 0 back, sheet 1 front ...), w px wide.
 *  - [startPdf]  POST /api/psrender {op:"pdf", spec, plan, dpi?} -> {job}. A background job (poll /api/job?id, shows in the progress bubble,
 *                Cancel works between sheets) writes ONE PDF to <root>/.LANShare Print/.render/<name>.pdf. The finished job carries
 *                result {path, name, pages, bytes, dpi, duplex}; `path` is an ordinary local virtual path, so the existing print
 *                pipeline (doPrint / Jobs.startPrint, dev "local") can take it unchanged (part 7). Back pages are put into the PDF as they are
 *                READ (see SheetLayout); the printer's duplex option does the flipping.
 *
 * Memory: one picture is decoded at a time, at about the resolution it is drawn with (power-of-two inSampleSize, long side <= [PsRenderGeom.MAXPX]),
 * EXIF turn applied, kept in a small pool (copies of one picture are drawn from the same bitmap) and recycled. PdfDocument keeps every drawn bitmap
 * until it is written, so [PsRenderGeom.autoDpi] lowers the resolution of a big job instead of running out of memory (the result says which dpi was used).
 *
 * Android side NOT compiled / NOT device-tested; the arithmetic is in [PsRenderGeom] (JVM-tested).
 */
object PrintSpaceRender {
    private const val DIR = ".render"                 // inside the print space folder: nothing here is a picture of the space
    private const val KEEP = 4                        // finished PDFs kept (newest), older than a day go anyway
    private const val KEEP_MS = 24L * 3600_000L
    private const val POOL_BYTES = 64L shl 20
    private const val PDF_BUDGET = 400L shl 20        // raw pixel bytes of one PDF job before the dpi is lowered

    private class Prep(val id: String, val res: PsLayoutJson.Resolved, val paths: Map<String, String>) {
        val boxes: List<SheetLayout.Box> = SheetLayout.slotBoxes(res.spec, res.plan.portrait)
    }

    private val preps = object : LinkedHashMap<String, Prep>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Prep>): Boolean = size > 12
    }
    private val renderLock = Any()                    // one PDF at a time (memory)
    private val previewSlots = Semaphore(2)           // two preview pages at a time (memory)

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)

    private fun renderDir() = File(File(Core.local.root, PrintSpace.NAME), DIR)

    // ------------------------------------------------------------------ prepare

    private fun make(b: JSONObject): Prep {
        val res = PsLayoutApi.resolve(b)
        val key = sha(PsLayoutJson.specJson(res.spec).toString() + PsLayoutJson.layoutJson(res.spec, res.pics, res.plan, res.layout).toString())
        synchronized(preps) { preps[key]?.let { return it } }
        val paths = HashMap<String, String>()
        for (x in PrintSpace.items()) paths[x.id] = x.path()
        val p = Prep(key, res, paths)
        synchronized(preps) { preps[key] = p }
        return p
    }

    fun prepare(b: JSONObject): JSONObject {
        val p = make(b)
        val lay = p.res.layout
        val list = JSONArray()
        for (pg in lay.pages) list.put(JSONObject().put("sheet", pg.sheet).put("side", pg.side.name.lowercase()))
        return JSONObject().put("ok", true).put("id", p.id).put("pages", lay.pages.size).put("sheets", p.res.plan.sheets.size)
            .put("sw", lay.sw).put("sh", lay.sh).put("duplex", SheetLayout.hasBack(p.res.plan)).put("list", list)
    }

    /** POST /api/psrender body: {op:"prepare" | "pdf", spec, plan, dpi?}. */
    fun post(b: JSONObject): JSONObject = when (val op = b.optString("op", "prepare")) {
        "prepare" -> prepare(b)
        "pdf" -> JSONObject().put("job", startPdf(b))
        else -> throw BadReq("unknown render operation: $op")
    }

    // ------------------------------------------------------------------ drawing (shared by the preview and the PDF)

    /** Decoded pictures of one render / preview call. Copies of a picture on a sheet share one bitmap. Single-threaded. */
    private class Pool(private val limit: Long = POOL_BYTES) {
        private class E(val bmp: Bitmap, val longPx: Int, val sharpest: Boolean)
        private val m = LinkedHashMap<String, E>(8, 0.75f, true)
        private var bytes = 0L

        fun get(id: String, f: File, need: Int): Bitmap {
            m[id]?.let { if (it.sharpest || it.longPx >= need) return it.bmp }
            m.remove(id)?.let { bytes -= PsRenderGeom.bytesOf(it.bmp.width, it.bmp.height); it.bmp.recycle() }
            val e = decode(f, need)
            m[id] = e
            bytes += PsRenderGeom.bytesOf(e.bmp.width, e.bmp.height)
            while (bytes > limit && m.size > 1) {   // oldest first; the newest (just decoded) is last and stays
                val it = m.entries.iterator()
                val first = it.next()
                bytes -= PsRenderGeom.bytesOf(first.value.bmp.width, first.value.bmp.height)
                first.value.bmp.recycle()
                it.remove()
            }
            return e.bmp
        }

        fun close() {
            for (e in m.values) try { e.bmp.recycle() } catch (_: Exception) {}
            m.clear()
            bytes = 0L
        }

        private fun decode(f: File, need: Int): E {
            if (!f.isFile) throw NotFound("A picture of the print space is missing (${f.name}) - remove it from the print space")
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { BitmapFactory.decodeFile(f.path, bo) } catch (_: Exception) {}
            if (bo.outWidth <= 0 || bo.outHeight <= 0) throw IOException("Cannot read the picture ${f.name}")
            var ss = PsRenderGeom.sample(bo.outWidth, bo.outHeight, need)
            var bmp: Bitmap? = null
            var tries = 0
            while (bmp == null && tries < 4) {
                try { bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss }) } catch (_: OutOfMemoryError) {}
                if (bmp == null) { ss *= 2; tries++ }   // too big for the memory we have: a coarser decode
            }
            var out: Bitmap = bmp ?: throw IOException("Cannot read the picture ${f.name} (not enough memory)")
            val sharpest = ss == 1
            // EXIF turn: the layout sizes are "as the picture LOOKS" (PsLayoutApi), so the bitmap must look that way too
            val o = try {
                PsRenderGeom.exif(ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            } catch (_: Exception) { intArrayOf(0, 0) }
            if (o[0] != 0 || o[1] != 0) {
                val mx = Matrix()
                if (o[0] != 0) mx.postRotate(o[0].toFloat())
                if (o[1] != 0) mx.postScale(-1f, 1f)
                val t = Bitmap.createBitmap(out, 0, 0, out.width, out.height, mx, true)
                if (t !== out) out.recycle()
                out = t
            }
            return E(out, maxOf(out.width, out.height), sharpest)
        }
    }

    private fun drawPage(cv: Canvas, p: Prep, page: SheetLayout.Page, pool: Pool, pxPerPt: Double) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        for (c in page.cells) {
            val pic = p.res.pics.getOrNull(c.pic) ?: continue
            val box = p.boxes.getOrNull(c.slot) ?: continue
            val vp = p.paths[pic.id] ?: throw NotFound("A picture was removed from the print space - open Layout again")
            val d = PsRenderGeom.draw(box, maxOf(1, pic.w) * c.k, maxOf(1, pic.h) * c.k, c.rot)
            val bmp = pool.get(pic.id, Core.local.real(vp), PsRenderGeom.needPx(d.dw.toDouble(), d.dh.toDouble(), pxPerPt))
            cv.save()
            cv.clipRect(d.left, d.top, d.right, d.bottom)
            cv.translate(d.cx, d.cy)
            if (d.rot != 0) cv.rotate(d.rot.toFloat())
            cv.drawBitmap(bmp, null, RectF(-d.dw / 2, -d.dh / 2, d.dw / 2, d.dh / 2), paint)
            cv.restore()
        }
        // free placement (spec.free): no clip to a slot, any turn. Part 14c: last resort - nothing is drawn into the strip the printer cannot
        // print (spec.safe), even when a plan that never went through setFree (old saved layout) still has a picture there; the page clamps, this clips.
        val lay = p.res.layout
        val spec = SheetLayout.clean(p.res.spec)
        val safeClip: DoubleArray? = if (spec.free && spec.safe > 0.0 && page.free.isNotEmpty()) SheetLayout.safeRect(spec.safe, lay.sw, lay.sh) else null
        for (f in page.free) {
            val pic = p.res.pics.getOrNull(f.pic) ?: continue
            val vp = p.paths[pic.id] ?: throw NotFound("A picture was removed from the print space - open Layout again")
            val dw = maxOf(1.0, f.dw)
            val dh = maxOf(1.0, f.dh)
            val bmp = pool.get(pic.id, Core.local.real(vp), PsRenderGeom.needPx(dw, dh, pxPerPt))
            cv.save()
            if (safeClip != null) cv.clipRect((safeClip[0] * lay.sw).toFloat(), (safeClip[2] * lay.sh).toFloat(), (safeClip[1] * lay.sw).toFloat(), (safeClip[3] * lay.sh).toFloat())
            cv.translate(f.cx.toFloat(), f.cy.toFloat())
            if (f.rot % 360.0 != 0.0) cv.rotate(f.rot.toFloat())
            cv.drawBitmap(bmp, null, RectF((-dw / 2).toFloat(), (-dh / 2).toFloat(), (dw / 2).toFloat(), (dh / 2).toFloat()), paint)
            cv.restore()
        }
    }

    // ------------------------------------------------------------------ preview page

    /** JPEG of page [n] (print order) of a prepared layout, [width] px wide (120..1100). */
    fun page(id: String, n: Int, width: Int): ByteArray {
        val p = synchronized(preps) { preps[id] } ?: throw NotFound("preview expired - open Layout again")
        val lay = p.res.layout
        if (n < 0 || n >= lay.pages.size) throw BadReq("no such page")
        val (pw, ph) = PsRenderGeom.previewSize(lay.sw, lay.sh, width)
        val scale = pw / lay.sw.toFloat()
        try { previewSlots.acquire() } catch (_: InterruptedException) { throw IOException("interrupted") }
        val pool = Pool(32L shl 20)
        var bmp: Bitmap? = null
        try {
            bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.drawColor(Color.WHITE)
            cv.scale(scale, scale)
            drawPage(cv, p, lay.pages[n], pool, scale * 1.25)   // a little sharper than the screen needs: zooming in the editor stays readable
            val bo = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo)) throw IOException("could not encode the preview")
            return bo.toByteArray()
        } finally {
            pool.close()
            bmp?.recycle()
            previewSlots.release()
        }
    }

    // ------------------------------------------------------------------ PDF job

    /** Starts the background job and returns its id (poll GET /api/job?id=...). Errors in the request come back at once (400). */
    fun startPdf(b: JSONObject): String {
        val p = make(b)
        if (p.res.layout.pages.isEmpty()) throw BadReq("Nothing to print: put pictures on the sheets first")
        val dpi = b.optInt("dpi", PsRenderGeom.DEF_DPI)
        val only = b.optString("only", "").lowercase().let { if (it == "front" || it == "back") it else "" }   // "" = every page, "front" / "back" = one side only (manual two-sided printing)
        val rev = b.optBoolean("reverse", false)
        if (only == "back" && !SheetLayout.hasBack(p.res.plan)) throw BadReq("These sheets have no back side")
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val job = Job("Preparing sheets")
        Jobs.all[id] = job
        Thread({ work(job, p, dpi, only, rev) }, "psrender-$id").also { it.isDaemon = true }.start()
        return id
    }

    private fun work(job: Job, p: Prep, wantDpi: Int, only: String, rev: Boolean) {
        val pool = Pool()
        try {
            job.cur = "Waiting for the previous sheets job\u2026"
            synchronized(renderLock) { renderPdf(job, p, wantDpi, pool, only, rev) }
        } catch (e: Cancelled) {
            job.state = "cancel"
        } catch (e: OutOfMemoryError) {
            job.error = "Not enough memory for these sheets - print fewer sheets at once or use a lower resolution"
            job.state = "error"
        } catch (e: Throwable) {
            job.error = errText(e)
            job.state = "error"
        } finally {
            pool.close()
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    private fun renderPdf(job: Job, p: Prep, wantDpi: Int, pool: Pool, only: String, rev: Boolean) {
        val lay = p.res.layout
        val duplex = SheetLayout.hasBack(p.res.plan)
        // one side only (manual two-sided printing): the pages of that side in sheet order, or newest sheet first when [rev]
        var pages = if (only == "") lay.pages else lay.pages.filter { (it.side == SheetLayout.Side.BACK) == (only == "back") }
        if (rev) pages = pages.reversed()
        // The backs are laid out "as read" after turning the sheet over. A printer with a duplex option turns them itself; by hand the sheet is turned over the
        // edge the layout was made for, and over the horizontal axis of the page (calendar style) the back has to be printed upside down to read upright.
        val turnBack = only == "back" && (p.res.spec.flip == SheetLayout.Flip.LONG) != p.res.plan.portrait
        job.bytes = false
        job.total = pages.size.toLong()
        job.filesTotal = 0
        job.label = "Preparing " + pages.size + (if (pages.size == 1) " page" else " pages")
        val sizes = ArrayList<Pair<Double, Double>>()
        for (pg in pages) for (c in pg.cells) {
            val pic = p.res.pics.getOrNull(c.pic) ?: continue
            sizes.add(Pair(maxOf(1, pic.w) * c.k, maxOf(1, pic.h) * c.k))
        }
        for (pg in pages) for (f in pg.free) sizes.add(Pair(maxOf(1.0, f.dw), maxOf(1.0, f.dh)))
        val dpi = PsRenderGeom.autoDpi(wantDpi, sizes, PDF_BUDGET)
        val dir = renderDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create the sheets folder")
        try { File(dir.parentFile, ".nomedia").createNewFile() } catch (_: Exception) {}
        tidy(dir)

        val (wp, hp) = PsRenderGeom.pageSize(lay.sw, lay.sh)
        val doc = PdfDocument()
        try {
            for ((i, pg) in pages.withIndex()) {
                if (job.cancel) throw Cancelled()
                job.cur = "Sheet " + (pg.sheet + 1) + (if (!duplex) "" else if (pg.side == SheetLayout.Side.BACK) " (back)" else " (front)") + " - page " + (i + 1) + " of " + pages.size
                val page = doc.startPage(PdfDocument.PageInfo.Builder(wp, hp, i + 1).create())
                try {
                    page.canvas.drawColor(Color.WHITE)
                    if (turnBack) page.canvas.rotate(180f, lay.sw.toFloat() / 2f, lay.sh.toFloat() / 2f)
                    drawPage(page.canvas, p, pg, pool, dpi / 72.0)
                } finally { doc.finishPage(page) }
                job.done = (i + 1).toLong()
            }
            job.cur = "Writing the PDF\u2026"
            val name = freeName(dir)
            val tmp = File(dir, "$name.tmp")
            val dst = File(dir, name)
            try {
                FileOutputStream(tmp).use { doc.writeTo(it) }
                if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw IOException("could not save the PDF") }
            } finally { if (tmp.exists()) tmp.delete() }
            val vp = "/" + PrintSpace.NAME + "/" + DIR + "/" + name
            job.result = JSONObject().put("path", vp).put("name", name).put("pages", pages.size).put("bytes", dst.length()).put("dpi", dpi).put("duplex", duplex).put("only", only).put("turned", turnBack).put("flip", p.res.spec.flip.name.lowercase())
            job.label = "Sheets ready"
            job.note = pages.size.toString() + (if (pages.size == 1) " page" else " pages") + ", " + mb(dst.length()) + (if (dpi < wantDpi.coerceIn(PsRenderGeom.MIN_DPI, PsRenderGeom.MAX_DPI)) " (resolution lowered to $dpi dpi to fit the memory)" else "")
            job.state = "done"
        } finally { doc.close() }
    }

    private fun mb(n: Long): String = if (n >= (1L shl 20)) "%.1f MB".format(Locale.US, n / 1048576.0) else (n / 1024).toString() + " KB"

    private fun freeName(dir: File): String {
        val base = "Print space " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        var n = "$base.pdf"
        var k = 1
        while (File(dir, n).exists()) { n = "$base ($k).pdf"; k++ }
        return n
    }

    /** Old results go: anything older than a day, and all but the newest [KEEP] PDFs. Leftover .tmp files of a killed job too. */
    private fun tidy(dir: File) {
        try {
            val now = System.currentTimeMillis()
            val all = dir.listFiles()?.filter { it.isFile } ?: return
            for (f in all) if (f.name.endsWith(".tmp") && now - f.lastModified() > 3_600_000L) f.delete()
            val pdfs = all.filter { it.name.endsWith(".pdf") }.sortedByDescending { it.lastModified() }
            for ((i, f) in pdfs.withIndex()) if (i >= KEEP || now - f.lastModified() > KEEP_MS) f.delete()
        } catch (_: Exception) {}
    }
}
