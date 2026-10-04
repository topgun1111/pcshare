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
 *  - Word / Excel / PowerPoint cannot be converted on the phone: those go through a PC.
 * Options that apply: copies, sides, colour, paper size, scaling, page range (the rest of the PC dialog needs the PC).
 */
object WifiPrint {
    private class PrintFail(msg: String) : IOException(msg)

    private val IMG = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff")
    private val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")
    private val TXT = setOf("txt", "log", "md", "csv")
    private val PAPER = mapOf("A4" to "iso_a4_210x297mm", "A3" to "iso_a3_297x420mm", "A5" to "iso_a5_148x210mm",
        "Letter" to "na_letter_8.5x11in", "Legal" to "na_legal_8.5x14in")

    fun work(job: Job, src: Endpoint, p: WifiPrinters.P, paths: List<String>, opts: JSONObject?) {
        val dir = File(Core.appCtx?.cacheDir ?: Core.cacheDir, "wprint").apply { mkdirs() }
        try { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() } } catch (_: Exception) {}
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

            for ((i, f0) in files.withIndex()) {
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
                    if (ext in OFFICE) throw PrintFail("Word / Excel / PowerPoint files cannot go straight to a Wi-Fi printer - print them through a PC")

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
                    when (cext) {
                        "pdf" -> sendPdf(p, job, cur, name, jo, temps)
                        "jpg", "jpeg" -> sendJpeg(p, cur, name, jo, temps)
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
                job.note = job.label
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

    private fun jobOpts(p: WifiPrinters.P, o: JSONObject?): Ipp.JobOpts {
        if (o == null) return Ipp.JobOpts()
        val copies = o.optInt("copies", 1).coerceIn(1, 99)
        val sides = when (o.optString("duplex")) { "long" -> "two-sided-long-edge"; "short" -> "two-sided-short-edge"; "off" -> "one-sided"; else -> null }
            ?.takeIf { p.sides.isEmpty() || it in p.sides }
        val color = when (o.optString("color")) { "color" -> "color"; "mono" -> "monochrome"; else -> null }
            ?.takeIf { p.colorModes.isEmpty() || it in p.colorModes }
        val scaling = when (o.optString("fit")) { "shrink", "fit" -> "fit"; "fill" -> "fill"; "noscale" -> "none"; else -> null }
        return Ipp.JobOpts(copies, sides, color, PAPER[o.optString("paper")], scaling, ranges(o.optString("range")))
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

    private fun submit(p: WifiPrinters.P, f: File, mime: String, name: String, jo: Ipp.JobOpts) {
        var r = Ipp.printJob(p, name, mime, jo, f)
        if (!r.ok && (r.status == 0x040B || r.status == 0x040E))   // the printer rejected an option: send again with copies only
            r = Ipp.printJob(p, name, mime, Ipp.JobOpts(copies = jo.copies), f)
        if (!r.ok) throw PrintFail(Ipp.statusText(r.status))
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
