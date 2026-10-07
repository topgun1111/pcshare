package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset

/**
 * Prints files straight to a Wi-Fi printer over IPP (no PC). The printer takes PDF and/or JPEG:
 *  - PDF goes as it is (printers without PDF: every page is rendered to a JPEG and sent as its own job);
 *  - pictures are turned into JPEG (EXIF rotation applied), or into a PDF when the printer has no JPEG;
 *  - text / code is laid out into a PDF here; html / svg are converted by [PrintPrep];
 *  - Word / Excel / PowerPoint are converted to PDF by a PC running pcprint.py (/convert), then sent from here.
 * Options that apply: copies, sides, colour, paper size, scaling, page range (the rest of the PC dialog needs the PC).
 */
object WifiPrint {
    private class PrintFail(msg: String) : IOException(msg)

    private val IMG = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff")
    private val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")
    private val TXT = setOf("txt", "log", "md", "csv")
    /** pages per sheet -> columns, rows, landscape sheet (same table as pcprint.py / the preview) */
    private val LAY = mapOf(2 to Triple(2, 1, true), 4 to Triple(2, 2, false), 6 to Triple(3, 2, true), 9 to Triple(3, 3, false))
    private val DIMS = mapOf("A4" to (595 to 842), "A3" to (842 to 1191), "A5" to (420 to 595), "Letter" to (612 to 792), "Legal" to (612 to 1008))
    private val PAPER = mapOf("A4" to "iso_a4_210x297mm", "A3" to "iso_a3_297x420mm", "A5" to "iso_a5_148x210mm",
        "Letter" to "na_letter_8.5x11in", "Legal" to "na_legal_8.5x14in")

    fun work(job: Job, src: Endpoint, p: WifiPrinters.P, paths: List<String>, opts: JSONObject?) {
        val dir = File(Core.appCtx?.cacheDir ?: Core.cacheDir, "wprint").apply { mkdirs() }
        try { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() } } catch (_: Exception) {}
        warns.set(ArrayList())
        try {
            val st = WifiPrinters.status(p.id)
            if (!st.optBoolean("ok")) throw IOException("${p.name} is not reachable - is it switched on and on this Wi-Fi? (${st.optString("why")})")
            val problem = if (st.isNull("problem")) "" else st.optString("problem")
            val jo = jobOpts(p, opts)

            val files = ArrayList<Pair<String, Long>>()
            for (p0 in paths) {
                val v = vnorm(p0)
                for (w in src.walk(v)) if (!w.dir) files.add((if (w.rel.isEmpty()) v else v + "/" + w.rel) to w.size)
            }
            if (files.isEmpty()) throw BadReq("nothing to print")
            job.total = maxOf(files.sumOf { it.second }, 1L)
            val failed = ArrayList<String>()

            // several pictures + several pages per sheet: all pictures share the sheets
            val nup0 = (opts?.optInt("nup", 1) ?: 1).takeIf { it in LAY } ?: 1
            val allPics = files.size > 1 && files.all { (sp, _) -> vbase(sp).substringAfterLast('.', "").lowercase().let { it in IMG || it in PrintPrep.PICS } }
            val merge = nup0 > 1 && allPics
            if (merge) mergeImages(job, src, p, files, jo, nup0, (opts?.optInt("border", 0) ?: 0) == 1, dir, failed)

            for ((i, f0) in (if (merge) emptyList<Pair<String, Long>>() else files).withIndex()) {
                if (job.cancel) throw Cancelled()
                val (sp, size) = f0
                val name = vbase(sp)
                job.label = "Printing ${i + 1}/${files.size} on ${p.name}" + (if (problem.isNotEmpty()) " (printer reports: $problem)" else "")
                val temps = ArrayList<File>()
                var got = 0L
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val kind = PrintPrep.kind(ext)
                    if (kind == PrintPrep.Kind.NO || (kind == PrintPrep.Kind.SNIFF && size > PrintPrep.SNIFF_MAX))
                        throw PrintFail(if (ext.isEmpty()) "this file type cannot be printed" else "$ext files cannot be printed")

                    val t = File(dir, "w-" + System.nanoTime().toString(36)).also { temps.add(it) }
                    src.open(sp).use { s ->
                        t.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (job.cancel) throw Cancelled()
                                val n = s.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n); got += n; job.done += n
                            }
                        }
                    }

                    var cur: File = t
                    var cext = ext
                    if (ext in OFFICE) {   // no converter on the phone: a PC running pcprint.py turns it into a PDF, the phone sends that to the printer
                        val ip = try { Jobs.converterIp() } catch (x: IOException) { throw PrintFail(errText(x)) }
                        val pdf = File(dir, "w-" + System.nanoTime().toString(36) + ".pdf").also { temps.add(it) }
                        try { Jobs.officeToPdf(ip, t, "x.$ext", pdf) } catch (x: IOException) { throw PrintFail("conversion on the PC failed (" + errText(x) + ")") }
                        cur = pdf; cext = "pdf"
                    }
                    val k2 = if (ext in IMG) PrintPrep.Kind.PIC else kind
                    if (k2 == PrintPrep.Kind.PIC || k2 == PrintPrep.Kind.WEB || k2 == PrintPrep.Kind.SNIFF) {
                        try {
                            val out = PrintPrep.convert(k2, t, name)
                            cur = out.file; temps.add(out.file)
                            cext = out.name.substringAfterLast('.', "").lowercase()
                        } catch (x: Cancelled) { throw x
                        } catch (x: Throwable) { throw PrintFail("this file could not be converted for printing (" + errText(x) + ")") }
                    }
                    if (kind == PrintPrep.Kind.TEXT || cext in TXT) {
                        cur = textToPdf(cur).also { temps.add(it) }; cext = "pdf"
                    }
                    var joUse = jo
                    val nup = (opts?.optInt("nup", 1) ?: 1).takeIf { it in LAY } ?: 1
                    if (nup > 1 && (cext == "pdf" || cext == "jpg" || cext == "jpeg")) {   // several pages on one sheet: laid out here, then sent as a normal PDF
                        if (cext != "pdf") { cur = imageToPdf(cur).also { temps.add(it) }; cext = "pdf" }
                        val pk = PAPER.entries.firstOrNull { it.value == jo.media }?.key ?: "A4"
                        cur = nupPdf(job, cur, nup, (opts?.optInt("border", 0) ?: 0) == 1, jo.ranges, pk).also { temps.add(it) }
                        joUse = Ipp.JobOpts(jo.copies, jo.sides, jo.color, jo.media, jo.scaling)   // page range was applied while laying out
                    }
                    when (cext) {
                        "pdf" -> sendPdf(p, job, cur, name, joUse, temps)
                        "jpg", "jpeg" -> sendJpeg(p, cur, name, joUse, temps)
                        else -> throw PrintFail("$cext files cannot be printed on a Wi-Fi printer")
                    }
                } catch (e: PrintFail) {
                    failed.add("$name: ${errText(e)}")
                    job.done += maxOf(size - got, 0L)
                } catch (e: Cancelled) { throw e
                } catch (e: IOException) {
                    failed.add("$name: ${errText(e)}")
                    job.done += maxOf(size - got, 0L)
                } finally { temps.forEach { try { it.delete() } catch (_: Exception) {} } }
            }
            job.done = job.total
            if (failed.isEmpty()) {   // sent: look once more whether the printer complains (jam, out of paper ...)
                try { Thread.sleep(2500) } catch (_: InterruptedException) {}
                val after = WifiPrinters.status(p.id)
                val w = if (after.optBoolean("ok") && !after.isNull("problem")) after.optString("problem") else ""
                if (w.isNotEmpty()) failed.add("sent, but the printer reports $w")
            }
            if (failed.isEmpty()) {
                job.label = "Sent ${files.size} file${if (files.size > 1) "s" else ""} to ${p.name} (Wi-Fi)"
                job.note = job.label + (warns.get()?.distinct()?.takeIf { it.isNotEmpty() }?.let { " - " + it.joinToString("; ") } ?: "")
                job.state = "done"
            } else {
                job.label = "Print problem on ${p.name}"
                job.error = "Problem on ${p.name} (Wi-Fi) - " + failed.joinToString("; ")
                job.state = "error"
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
        } catch (e: Throwable) {
            job.error = errText(e)
            job.state = "error"
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    /** Always name the paper explicitly: without it Brother / Epson inkjets guess a size from the image (e.g. 4x6) and stop with "media needed / media empty". */
    private fun pickMedia(p: WifiPrinters.P, want: String?): String? {
        val sup = p.mediaSupported
        fun ok(m: String) = sup.isEmpty() || m in sup
        if (want != null && ok(want)) return want
        p.mediaReady.firstOrNull { it.startsWith("iso_a4") && ok(it) }?.let { return it }
        p.mediaReady.firstOrNull { ok(it) }?.let { return it }
        p.mediaDefault.firstOrNull { ok(it) }?.let { return it }
        return if (ok("iso_a4_210x297mm")) "iso_a4_210x297mm" else null
    }

    private fun jobOpts(p: WifiPrinters.P, o0: JSONObject?): Ipp.JobOpts {
        val o = o0 ?: JSONObject()
        val copies = o.optInt("copies", 1).coerceIn(1, 99)
        val sides = when (o.optString("duplex")) { "long" -> "two-sided-long-edge"; "short" -> "two-sided-short-edge"; "off" -> "one-sided"; else -> null }
            ?.takeIf { p.sides.isEmpty() || it in p.sides }
        val color = when (o.optString("color")) { "color" -> "color"; "mono" -> "monochrome"; else -> null }
            ?.takeIf { p.colorModes.isEmpty() || it in p.colorModes }
        val scaling = when (o.optString("fit")) { "shrink", "fit" -> "fit"; "fill" -> "fill"; "noscale" -> "none"; else -> "fit" }
        return Ipp.JobOpts(copies, sides, color, pickMedia(p, PAPER[o.optString("paper")]), scaling, ranges(o.optString("range")))
    }

    /** "1-3,5,8-" -> [1,3] [5,5] [8,MAX] */
    private fun ranges(s: String): List<IntArray> = s.split(',').mapNotNull { t0 ->
        val t = t0.trim()
        if (t.isEmpty()) return@mapNotNull null
        val a = t.substringBefore('-').trim()
        val b = if (t.contains('-')) t.substringAfter('-').trim() else a
        val lo = if (a.isEmpty()) 1 else a.toIntOrNull() ?: return@mapNotNull null
        val hi = if (b.isEmpty()) Int.MAX_VALUE else b.toIntOrNull() ?: return@mapNotNull null
        if (lo < 1 || hi < lo) null else intArrayOf(lo, hi)
    }

    /** Warnings collected while one job runs (each job has its own thread); shown with the "Sent ..." note. */
    private val warns = ThreadLocal<ArrayList<String>>()

    /**
     * Sends one job. When the printer rejects an option, options are dropped one at a time and two-sided printing ("sides") is dropped LAST,
     * so a printer that dislikes e.g. print-scaling or print-color-mode still prints on both sides. If sides had to go, the user is told.
     */
    private fun submit(p: WifiPrinters.P, f: File, mime: String, name: String, jo: Ipp.JobOpts) {
        fun rejected(r: Ipp.Resp) = !r.ok && (r.status == 0x040B || r.status == 0x040E)
        val tries = listOf(
            jo,
            Ipp.JobOpts(jo.copies, jo.sides, jo.color, jo.media, null, jo.ranges),   // 1: no print-scaling
            Ipp.JobOpts(jo.copies, jo.sides, null, jo.media, null, jo.ranges),       // 2: no colour mode either
            Ipp.JobOpts(jo.copies, null, null, jo.media, null, jo.ranges),           // 3: no sides (last resort, keeps paper + range)
            Ipp.JobOpts(copies = jo.copies)                                          // 4: bare job
        )
        var last: Ipp.JobOpts? = null
        var r: Ipp.Resp? = null
        for (t in tries) {
            val key = "${t.sides}|${t.color}|${t.media}|${t.scaling}|${t.ranges.size}"
            if (last != null && key == "${last.sides}|${last.color}|${last.media}|${last.scaling}|${last.ranges.size}") continue   // nothing new to try
            last = t
            r = Ipp.printJob(p, name, mime, t, f)
            if (!rejected(r)) {
                if (r.ok && jo.sides != null && t.sides == null) warns.get()?.add("two-sided printing was refused by the printer, printed one-sided")
                break
            }
        }
        if (r == null || !r.ok) throw PrintFail(Ipp.statusText(r?.status ?: 0x0400))
    }

    private fun fmtList(p: WifiPrinters.P) = p.formats.filter { !it.contains("octet") }.take(4).joinToString(", ")

    private fun sendPdf(p: WifiPrinters.P, job: Job, pdf: File, name: String, jo: Ipp.JobOpts, temps: MutableList<File>) {
        val fm = p.formats
        if (fm.isEmpty() || "application/pdf" in fm) { submit(p, pdf, "application/pdf", name, jo); return }
        if ("image/jpeg" !in fm) throw PrintFail("this printer only accepts ${fmtList(p)} - print through a PC instead")
        // no PDF support (typical for AirPrint-only printers): one JPEG per page
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val rr = PdfRenderer(fd)
        try {
            val n = rr.pageCount
            val pages = (0 until n).filter { i -> jo.ranges.isEmpty() || jo.ranges.any { (i + 1) >= it[0] && (i + 1) <= it[1] } }
            if (pages.isEmpty()) throw PrintFail("the page range selects no pages")
            val one = Ipp.JobOpts(copies = 1, sides = jo.sides, color = jo.color, media = jo.media, scaling = jo.scaling)
            repeat(jo.copies) {
                for (i in pages) {
                    if (job.cancel) throw Cancelled()
                    val jpg = File(pdf.parentFile, "pg-" + System.nanoTime().toString(36) + ".jpg").also { temps.add(it) }
                    val pg = rr.openPage(i)
                    try {
                        val k = 200f / 72f
                        var w = (pg.width * k).toInt().coerceAtLeast(1)
                        var h = (pg.height * k).toInt().coerceAtLeast(1)
                        val big = maxOf(w, h)
                        if (big > 3300) { val s = 3300f / big; w = (w * s).toInt().coerceAtLeast(1); h = (h * s).toInt().coerceAtLeast(1) }
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        FileOutputStream(jpg).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                    } finally { pg.close() }
                    submit(p, jpg, "image/jpeg", "$name (${i + 1}/$n)", one)
                    jpg.delete()
                }
            }
        } finally { try { rr.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }
    }

    private fun sendJpeg(p: WifiPrinters.P, jpg: File, name: String, jo: Ipp.JobOpts, temps: MutableList<File>) {
        val fm = p.formats
        if (fm.isEmpty() || "image/jpeg" in fm) { submit(p, jpg, "image/jpeg", name, jo); return }
        if ("application/pdf" !in fm) throw PrintFail("this printer only accepts ${fmtList(p)} - print through a PC instead")
        val pdf = imageToPdf(jpg).also { temps.add(it) }
        submit(p, pdf, "application/pdf", name, jo)
    }

    /** Downloads every picture, turns it into JPEG and prints them together on shared sheets. */
    private fun mergeImages(job: Job, src: Endpoint, p: WifiPrinters.P, files: List<Pair<String, Long>>, jo: Ipp.JobOpts, nup: Int,
                            border: Boolean, dir: File, failed: MutableList<String>) {
        val temps = ArrayList<File>()
        try {
            job.label = "Merging ${files.size} pictures for ${p.name}"
            val jpgs = ArrayList<File>()
            for ((sp, _) in files) {
                if (job.cancel) throw Cancelled()
                val nm = vbase(sp)
                val t = File(dir, "w-" + System.nanoTime().toString(36)).also { temps.add(it) }
                src.open(sp).use { s ->
                    t.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (job.cancel) throw Cancelled()
                            val n = s.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); job.done += n
                        }
                    }
                }
                try {
                    val out = PrintPrep.convert(PrintPrep.Kind.PIC, t, nm)
                    temps.add(out.file); jpgs.add(out.file)
                } catch (x: Cancelled) { throw x
                } catch (x: Throwable) { failed.add("$nm: could not be read (" + errText(x) + ")") }
            }
            if (jpgs.isEmpty()) return
            val pk = PAPER.entries.firstOrNull { it.value == jo.media }?.key ?: "A4"
            val pdf = picturesToSheets(job, jpgs, nup, border, pk).also { temps.add(it) }
            sendPdf(p, job, pdf, "pictures", Ipp.JobOpts(jo.copies, jo.sides, jo.color, jo.media, jo.scaling), temps)
        } catch (e: PrintFail) { failed.add("pictures: ${errText(e)}")
        } catch (e: IOException) { failed.add("pictures: ${errText(e)}")
        } finally { temps.forEach { try { it.delete() } catch (_: Exception) {} } }
    }

    /** [nup] pictures per sheet; a picture is turned 90 degrees when that fills its cell better. */
    private fun picturesToSheets(job: Job, jpgs: List<File>, nup: Int, border: Boolean, paper: String): File {
        val (cols, rows, land) = LAY[nup] ?: throw PrintFail("unsupported pages per sheet")
        val d = DIMS[paper] ?: DIMS.getValue("A4")
        val pw = if (land) d.second else d.first
        val ph = if (land) d.first else d.second
        val m = 18f; val gap = 8f
        val cw = (pw - 2 * m - (cols - 1) * gap) / cols
        val ch = (ph - 2 * m - (rows - 1) * gap) / rows
        val doc = PdfDocument()
        try {
            val line = Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.6f }
            val bp = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((sn, chunk) in jpgs.chunked(nup).withIndex()) {
                if (job.cancel) throw Cancelled()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, sn + 1).create())
                val cv = page.canvas
                cv.drawColor(Color.WHITE)
                for ((j, f) in chunk.withIndex()) {
                    val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bo)
                    var ss = 1
                    while (maxOf(bo.outWidth, bo.outHeight) / ss > 2400) ss *= 2
                    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = ss }) ?: continue
                    try {
                        val cx = m + (j % cols) * (cw + gap) + cw / 2
                        val cy = m + (j / cols) * (ch + gap) + ch / 2
                        val rot = (bmp.width > bmp.height) != (cw > ch)
                        val iw = if (rot) bmp.height else bmp.width
                        val ih = if (rot) bmp.width else bmp.height
                        val k = minOf(cw / iw, ch / ih)
                        val bw = bmp.width * k; val bh = bmp.height * k
                        cv.save()
                        cv.translate(cx, cy)
                        if (rot) cv.rotate(90f)
                        val r = RectF(-bw / 2, -bh / 2, bw / 2, bh / 2)
                        cv.drawBitmap(bmp, null, r, bp)
                        if (border) cv.drawRect(r, line)
                        cv.restore()
                    } finally { bmp.recycle() }
                }
                doc.finishPage(page)
            }
            val out = File(jpgs[0].parentFile, "sheets-" + System.nanoTime().toString(36) + ".pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } catch (e: PrintFail) { throw e
        } catch (e: Cancelled) { throw e
        } catch (e: Throwable) { throw PrintFail("could not lay out the pictures (" + errText(e) + ")")
        } finally { doc.close() }
    }

    /** [nup] source pages per sheet (page range applied first) -> new PDF with the sheets. */
    private fun nupPdf(job: Job, pdf: File, nup: Int, border: Boolean, ranges: List<IntArray>, paper: String): File {
        val (cols, rows, land) = LAY[nup] ?: throw PrintFail("unsupported pages per sheet")
        val d = DIMS[paper] ?: DIMS.getValue("A4")
        val pw = if (land) d.second else d.first
        val ph = if (land) d.first else d.second
        val m = 18f; val gap = 8f
        val cw = (pw - 2 * m - (cols - 1) * gap) / cols
        val ch = (ph - 2 * m - (rows - 1) * gap) / rows
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val rr = PdfRenderer(fd)
        val doc = PdfDocument()
        try {
            val pages = (0 until rr.pageCount).filter { i -> ranges.isEmpty() || ranges.any { (i + 1) >= it[0] && (i + 1) <= it[1] } }
            if (pages.isEmpty()) throw PrintFail("the page range selects no pages")
            val line = Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.6f }
            val bp = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((sn, chunk) in pages.chunked(nup).withIndex()) {
                if (job.cancel) throw Cancelled()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, sn + 1).create())
                page.canvas.drawColor(Color.WHITE)
                for ((j, pi) in chunk.withIndex()) {
                    val src = rr.openPage(pi)
                    try {
                        val s = minOf(cw / src.width, ch / src.height)
                        val w = src.width * s; val h = src.height * s
                        val x = m + (j % cols) * (cw + gap) + (cw - w) / 2
                        val y = m + (j / cols) * (ch + gap) + (ch - h) / 2
                        var bw = (w * 2).toInt().coerceAtLeast(1); var bh = (h * 2).toInt().coerceAtLeast(1)   // ~144 dpi
                        val big = maxOf(bw, bh); if (big > 2400) { val f = 2400f / big; bw = (bw * f).toInt().coerceAtLeast(1); bh = (bh * f).toInt().coerceAtLeast(1) }
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), bp)
                        bmp.recycle()
                        if (border) page.canvas.drawRect(x, y, x + w, y + h, line)
                    } finally { src.close() }
                }
                doc.finishPage(page)
            }
            val out = File(pdf.parentFile, pdf.name + ".nup.pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } catch (e: PrintFail) { throw e
        } catch (e: Cancelled) { throw e
        } catch (e: Throwable) { throw PrintFail("could not lay out the pages (" + errText(e) + ")")
        } finally { doc.close(); try { rr.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }
    }

    private fun imageToPdf(f: File): File {
        val bmp = BitmapFactory.decodeFile(f.path) ?: throw PrintFail("the picture could not be read")
        val doc = PdfDocument()
        try {
            val land = bmp.width > bmp.height
            val pw = if (land) 842 else 595
            val ph = if (land) 595 else 842
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val m = 18f
            val k = minOf((pw - 2 * m) / bmp.width, (ph - 2 * m) / bmp.height)
            val w = bmp.width * k
            val h = bmp.height * k
            val l = (pw - w) / 2
            val t = (ph - h) / 2
            page.canvas.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            val out = File(f.parentFile, f.name + ".pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } finally { doc.close(); bmp.recycle() }
    }

    private fun textToPdf(f: File): File {
        if (f.length() > 4L shl 20) throw PrintFail("the text file is too large for Wi-Fi printing (max 4 MB)")
        val raw = f.readBytes()
        var s = String(raw, Charsets.UTF_8)
        if (s.indexOf('\uFFFD') >= 0) s = String(raw, Charset.forName("windows-1254"))
        s = s.removePrefix("\uFEFF")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 9f; color = Color.BLACK }
        val pw = 595
        val ph = 842
        val m = 40f
        val width = pw - 2 * m
        val lead = 11.5f
        val per = ((ph - 2 * m) / lead).toInt()
        val lines = ArrayList<String>()
        for (l0 in s.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            var l = l0.replace("\t", "    ")
            if (l.isEmpty()) { lines.add(""); continue }
            while (l.isNotEmpty()) {
                val n = paint.breakText(l, true, width, null).coerceAtLeast(1)
                lines.add(l.substring(0, n)); l = l.substring(n)
            }
            if (lines.size > 30000) break
        }
        val doc = PdfDocument()
        try {
            var i = 0
            var pn = 1
            while (true) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pn).create())
                var y = m + 9f
                for (k in 0 until per) {
                    if (i >= lines.size) break
                    page.canvas.drawText(lines[i], m, y, paint)
                    y += lead; i++
                }
                doc.finishPage(page)
                pn++
                if (i >= lines.size) break
            }
            val out = File(f.parentFile, f.name + ".txt.pdf")
            FileOutputStream(out).use { doc.writeTo(it) }
            return out
        } finally { doc.close() }
    }
}
