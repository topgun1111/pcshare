package com.lanshare.app.core

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * "Save as PDF" destination of LANShare's own print dialog (destination id [ID]): the same layout options as a PC print -
 * pages per sheet (2/4/6/9), booklet, border, page range / odd / even / reverse, paper, margins, scaling, position, turn to fit,
 * watermark, header / footer, black & white, several files joined into one PDF - but the result is a PDF file in
 * <storage>/LANShare PDF/ instead of a printed sheet. Nothing is sent anywhere.
 *
 * Input: PDF, pictures, text / code, html / svg (converted by [PrintPrep]), Word / Excel / PowerPoint (converted by a PC running pcprint.py).
 * A single PDF printed with no layout change is copied untouched (stays vector). Anything that changes the layout re-draws the pages
 * as ~216 dpi pictures on the new sheets (pictures are drawn from the original file, not through a PDF).
 */
object PdfPrint {
    const val ID = "pdf:local"
    const val DIR = "LANShare PDF"
    fun isPdf(id: String) = id == ID

    private const val SC = 3f          // raster scale for PDF pages: 3 px per point = 216 dpi
    private const val MAXPX = 3000     // longest side of one drawn bitmap

    private class Src(val file: File) {
        private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val rr = PdfRenderer(fd)
        fun close() { try { rr.close() } catch (_: Exception) {}; try { fd.close() } catch (_: Exception) {} }
    }

    /** one source page: a page of a PDF ([src]) or a picture ([img]); [rot] = turn set by the user (degrees) */
    private class Pg(val src: Src?, val idx: Int, val img: File?, val w: Float, val h: Float, val rot: Int)

    private fun flag(o: JSONObject?, k: String): Boolean = when (val v = o?.opt(k)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v == "true"
        else -> false
    }

    private fun num(o: JSONObject?, k: String): Float? = when (val v = o?.opt(k)) {
        is Number -> v.toFloat()
        is String -> v.trim().replace(',', '.').toFloatOrNull()
        else -> null
    }

    /** "1-3,5,8-" -> inclusive ranges (open end = last page) */
    private fun ranges(s: String): List<IntArray> = s.split(',', ';', ' ').mapNotNull { t0 ->
        val t = t0.trim()
        if (t.isEmpty()) return@mapNotNull null
        if (t.contains('-')) {
            val a = t.substringBefore('-').trim().toIntOrNull() ?: 1
            val b = t.substringAfter('-').trim().toIntOrNull() ?: Int.MAX_VALUE
            intArrayOf(minOf(a, b), maxOf(a, b))
        } else t.toIntOrNull()?.let { intArrayOf(it, it) }
    }

    fun work(job: Job, src: Endpoint, paths: List<String>, opts: JSONObject?) {
        val ctx = Core.appCtx
        val dir = File(ctx?.cacheDir ?: Core.cacheDir, "pdfprint").apply { mkdirs() }
        try { dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() } } catch (_: Exception) {}
        val temps = ArrayList<File>()
        val srcs = ArrayList<Src>()
        try {
            val files = ArrayList<Pair<String, Long>>()
            for (p0 in paths) {
                val v = vnorm(p0)
                for (w in src.walk(v)) if (!w.dir) files.add((if (w.rel.isEmpty()) v else v + "/" + w.rel) to w.size)
            }
            if (files.isEmpty()) throw BadReq("nothing to save")
            job.total = maxOf(files.sumOf { it.second }, 1L)
            val joined = flag(opts, "sheet") || flag(opts, "join")
            val rots = opts?.optJSONArray("rots")
            val failed = ArrayList<String>()
            val saved = ArrayList<String>()

            // 1. every input file -> its pages
            class Group(val name: String, val pages: ArrayList<Pg> = ArrayList())
            val groups = ArrayList<Group>()
            if (joined) groups.add(Group(files[0].first.let { vbase(it) }.substringBeforeLast('.') + (if (files.size > 1) " + ${files.size - 1}" else "")))
            for ((i, f0) in files.withIndex()) {
                if (job.cancel) throw Cancelled()
                val (sp, size) = f0
                val name = vbase(sp)
                job.label = "Preparing ${i + 1}/${files.size}: $name"
                try {
                    val pages = load(job, src, sp, size, dir, temps, srcs, rots?.optInt(i, 0) ?: 0)
                    if (joined) groups[0].pages.addAll(pages)
                    else groups.add(Group(name.substringBeforeLast('.', name)).also { it.pages.addAll(pages) })
                } catch (e: Cancelled) { throw e
                } catch (e: IOException) { failed.add("$name: ${errText(e)}") }
            }

            // 2. lay out and save each result
            var n = 0
            for (g in groups) {
                if (g.pages.isEmpty()) continue
                if (job.cancel) throw Cancelled()
                n++
                job.label = "Making PDF $n/${groups.size}: ${g.name}"
                try {
                    val out = File(dir, "o-" + System.nanoTime().toString(36) + ".pdf").also { temps.add(it) }
                    make(job, g.pages, opts, out, g.name)
                    saved.add(save(g.name, out))
                } catch (e: Cancelled) { throw e
                } catch (e: IOException) { failed.add("${g.name}: ${errText(e)}") }
            }
            job.done = job.total
            if (saved.isNotEmpty() && failed.isEmpty()) {
                job.label = "Saved ${saved.size} PDF${if (saved.size > 1) "s" else ""}"
                job.note = job.label + " in " + saved.joinToString(", ").take(300)
                job.state = "done"
            } else {
                job.label = "PDF problem"
                job.error = (if (saved.isNotEmpty()) "Saved: " + saved.joinToString(", ") + ". " else "") + "Problem - " + failed.joinToString("; ")
                job.state = "error"
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
        } catch (e: Throwable) {
            job.error = errText(e)
            job.state = "error"
        } finally {
            srcs.forEach { it.close() }
            temps.forEach { try { it.delete() } catch (_: Exception) {} }
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    // ---- input: file -> pages -------------------------------------------------------------------------------

    private fun load(job: Job, ep: Endpoint, sp: String, size: Long, dir: File, temps: MutableList<File>, srcs: MutableList<Src>, rot: Int): List<Pg> {
        val name = vbase(sp)
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = PrintPrep.kind(ext)
        if (kind == PrintPrep.Kind.NO || (kind == PrintPrep.Kind.SNIFF && size > PrintPrep.SNIFF_MAX))
            throw IOException(if (ext.isEmpty()) "this file type cannot be converted" else "$ext files cannot be converted")
        val t = File(dir, "p-" + System.nanoTime().toString(36)).also { temps.add(it) }
        ep.open(sp).use { s ->
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
        var cur: File = t
        var cext = ext
        if (ext in WifiPrint.OFFICE) {   // a PC running pcprint.py converts Word / Excel / PowerPoint
            val ip = Jobs.converterIp()
            val pdf = File(dir, "p-" + System.nanoTime().toString(36) + ".pdf").also { temps.add(it) }
            try { Jobs.officeToPdf(ip, t, "x.$ext", pdf) } catch (x: IOException) { throw IOException("conversion on the PC failed (" + errText(x) + ")") }
            cur = pdf; cext = "pdf"
        }
        val k2 = if (ext in WifiPrint.IMG) PrintPrep.Kind.PIC else kind
        if (k2 == PrintPrep.Kind.PIC || k2 == PrintPrep.Kind.WEB || k2 == PrintPrep.Kind.SNIFF) {
            try {
                val out = PrintPrep.convert(k2, t, name)
                cur = out.file; temps.add(out.file)
                cext = out.name.substringAfterLast('.', "").lowercase()
            } catch (x: Cancelled) { throw x
            } catch (x: Throwable) { throw IOException("could not be converted (" + errText(x) + ")") }
        }
        if (kind == PrintPrep.Kind.TEXT || cext in WifiPrint.TXT) { cur = WifiPrint.textToPdf(cur).also { temps.add(it) }; cext = "pdf" }
        return when (cext) {
            "pdf" -> {
                val s = Src(cur).also { srcs.add(it) }
                (0 until s.rr.pageCount).map { i ->
                    val p = s.rr.openPage(i)
                    try { Pg(s, i, null, p.width.toFloat(), p.height.toFloat(), 0) } finally { p.close() }
                }
            }
            "jpg", "jpeg" -> {
                val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(cur.path, bo)
                if (bo.outWidth <= 0) throw IOException("the picture could not be read")
                listOf(Pg(null, 0, cur, bo.outWidth.toFloat(), bo.outHeight.toFloat(), rot))
            }
            else -> throw IOException("$cext files cannot be converted")
        }
    }

    // ---- layout ----------------------------------------------------------------------------------------------

    private fun make(job: Job, all: List<Pg>, o: JSONObject?, out: File, docName: String) {
        val nup = (o?.optInt("nup", 1) ?: 1).takeIf { it in WifiPrint.LAY } ?: 1
        val booklet = flag(o, "booklet")
        val mono = o?.optString("color") == "mono"
        val paper = o?.optString("paper").orEmpty()
        val mgOpt = num(o, "margin")
        val scaleOpt = num(o, "scale")?.takeIf { it > 0f }
        val fit = o?.optString("fit").orEmpty().ifEmpty { "shrink" }
        val align = o?.optString("align").orEmpty()
        val border = flag(o, "border")
        val autorot = flag(o, "autorot")
        val noauto = flag(o, "noauto")
        val wm = o?.optString("wm").orEmpty()
        val wmUnder = flag(o, "wm_under")
        val hdr = o?.optString("hdr").orEmpty()
        val ftr = o?.optString("ftr").orEmpty()
        val range = o?.optString("range").orEmpty().let { if (it.isBlank()) emptyList() else ranges(it) }
        val oddEven = o?.optString("pages").orEmpty()
        val reverse = flag(o, "reverse")

        // which pages, in which order
        var idx = all.indices.filter { i -> range.isEmpty() || range.any { (i + 1) >= it[0] && (i + 1) <= it[1] } }
        if (oddEven == "odd") idx = idx.filter { (it + 1) % 2 == 1 } else if (oddEven == "even") idx = idx.filter { (it + 1) % 2 == 0 }
        if (reverse) idx = idx.reversed()
        if (idx.isEmpty()) throw IOException("the page range selects no pages")
        val sel: List<Pg> = idx.map { all[it] }

        // untouched copy: one PDF, nothing changed
        val s0 = sel[0].src
        if (s0 != null && nup == 1 && !booklet && !mono && paper.isEmpty() && mgOpt == null && scaleOpt == null && !border && align.isEmpty() && !autorot &&
            wm.isEmpty() && hdr.isEmpty() && ftr.isEmpty() && sel.size == s0.rr.pageCount && sel.indices.all { sel[it].src === s0 && sel[it].idx == it } &&
            (fit == "shrink" || fit == "noscale" || fit == "fit")) {
            s0.file.copyTo(out, overwrite = true)
            return
        }

        // sheets: a list of cells (null = empty cell)
        val cols: Int; val rows: Int; val land: Boolean
        val sheets = ArrayList<List<Pg?>>()
        if (booklet) {
            cols = 2; rows = 1; land = true
            val pad = ArrayList<Pg?>(sel); while (pad.size % 4 != 0) pad.add(null)
            val m = pad.size
            for (k in 0 until m / 4) {
                sheets.add(listOf(pad[m - 1 - 2 * k], pad[2 * k]))          // front
                sheets.add(listOf(pad[2 * k + 1], pad[m - 2 - 2 * k]))      // back
            }
        } else {
            val l = WifiPrint.LAY[nup]
            cols = l?.first ?: 1; rows = l?.second ?: 1; land = l?.third ?: false
            for (c in sel.chunked(cols * rows)) sheets.add(c)
        }
        val multi = cols * rows > 1
        val gap = if (multi) 8f else 0f
        val mg = mgOpt ?: if (multi) 18f else if (sel.all { it.img != null }) 18f else 0f

        val doc = PdfDocument()
        val line = Paint().apply { style = Paint.Style.STROKE; color = Color.GRAY; strokeWidth = 0.6f }
        val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (mono) bp.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f; textAlign = Paint.Align.CENTER }
        try {
            for ((sn, cells) in sheets.withIndex()) {
                if (job.cancel) throw Cancelled()
                // sheet size
                val first = cells.firstOrNull { it != null }
                val base = WifiPrint.DIMS[paper]
                var pw: Float; var ph: Float
                if (multi) {
                    val d = base ?: WifiPrint.DIMS.getValue("A4")
                    pw = (if (land) d.second else d.first).toFloat(); ph = (if (land) d.first else d.second).toFloat()
                } else {
                    val turned = first != null && (first.rot % 180 != 0)
                    val fw = if (first == null) 595f else if (turned) first.h else first.w
                    val fh = if (first == null) 842f else if (turned) first.w else first.h
                    if (base != null) {
                        val a = base.first.toFloat(); val b = base.second.toFloat()
                        if (fw > fh) { pw = maxOf(a, b); ph = minOf(a, b) } else { pw = minOf(a, b); ph = maxOf(a, b) }
                    } else if (first?.img != null) {
                        if (fw > fh) { pw = 842f; ph = 595f } else { pw = 595f; ph = 842f }
                    } else { pw = fw; ph = fh }
                }
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt().coerceAtLeast(1), ph.toInt().coerceAtLeast(1), sn + 1).create())
                val cv = page.canvas
                cv.drawColor(Color.WHITE)
                if (wm.isNotEmpty() && wmUnder) watermark(cv, fill(wm, sn, sheets.size, docName), pw, ph)
                val cw = (pw - 2 * mg - (cols - 1) * gap) / cols
                val ch = (ph - 2 * mg - (rows - 1) * gap) / rows
                for ((j, pg) in cells.withIndex()) {
                    if (pg == null) continue
                    val cell = RectF(mg + (j % cols) * (cw + gap), mg + (j / cols) * (ch + gap), 0f, 0f)
                    cell.right = cell.left + cw; cell.bottom = cell.top + ch
                    drawPg(cv, pg, cell, multi, fit, scaleOpt, align, autorot, noauto, border, line, bp)
                }
                if (wm.isNotEmpty() && !wmUnder) watermark(cv, fill(wm, sn, sheets.size, docName), pw, ph)
                if (hdr.isNotEmpty()) cv.drawText(fill(hdr, sn, sheets.size, docName), pw / 2, 14f, tp)
                if (ftr.isNotEmpty()) cv.drawText(fill(ftr, sn, sheets.size, docName), pw / 2, ph - 8f, tp)
                doc.finishPage(page)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } catch (e: Cancelled) { throw e
        } catch (e: IOException) { throw e
        } catch (e: Throwable) { throw IOException("could not lay out the pages (" + errText(e) + ")")
        } finally { doc.close() }
    }

    /** same placeholders as the preview: {page} {pages} {date} {time} {file} */
    private fun fill(t: String, i: Int, n: Int, name: String): String {
        val d = java.util.Calendar.getInstance()
        fun z(x: Int) = x.toString().padStart(2, '0')
        return t.replace("{page}", (i + 1).toString()).replace("{pages}", n.toString())
            .replace("{date}", "${d.get(java.util.Calendar.YEAR)}-${z(d.get(java.util.Calendar.MONTH) + 1)}-${z(d.get(java.util.Calendar.DAY_OF_MONTH))}")
            .replace("{time}", z(d.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + z(d.get(java.util.Calendar.MINUTE))).replace("{file}", name)
    }

    private fun watermark(cv: Canvas, text: String, pw: Float, ph: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 128, 128, 128); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        p.textSize = 100f
        val w = p.measureText(text)
        p.textSize = (100f * minOf(1f, pw * 0.8f / maxOf(w, 1f))).coerceAtLeast(12f)
        cv.save(); cv.translate(pw / 2, ph / 2); cv.rotate(-45f)
        cv.drawText(text, 0f, p.textSize / 3, p); cv.restore()
    }

    private fun drawPg(cv: Canvas, p: Pg, cell: RectF, multi: Boolean, fit: String, scaleOpt: Float?, align: String, autorot: Boolean, noauto: Boolean,
                       border: Boolean, line: Paint, bp: Paint) {
        var rot = p.rot % 360
        val cellLand = cell.width() > cell.height()
        if (p.img != null) {
            if (!noauto && (p.w > p.h) != cellLand && (multi || p.rot == 0)) rot = (rot + 90) % 360
        } else if (autorot && (p.w > p.h) != cellLand) rot = (rot + 90) % 360
        val turned = rot % 180 != 0
        val rw = if (turned) p.h else p.w
        val rh = if (turned) p.w else p.h
        val fitS = minOf(cell.width() / rw, cell.height() / rh)
        val fillS = maxOf(cell.width() / rw, cell.height() / rh)
        val isImg = p.img != null
        val s = when {
            multi -> fitS
            scaleOpt != null && !isImg -> scaleOpt / 100f
            fit == "fill" -> fillS
            fit == "noscale" && !isImg -> 1f
            fit == "shrink" && !isImg -> minOf(1f, fitS)
            else -> fitS
        }
        val dw = rw * s
        val dh = rh * s
        var x = cell.centerX() - dw / 2
        var y = cell.centerY() - dh / 2
        if (!multi && (align == "top" || align == "topleft")) y = cell.top
        if (!multi && align == "topleft") x = cell.left
        val uw = p.w * s       // size of the unturned picture
        val uh = p.h * s
        val bmp: Bitmap? = if (p.img != null) {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p.img.path, bo)
            var ss = 1
            while (maxOf(bo.outWidth, bo.outHeight) / ss > MAXPX) ss *= 2
            BitmapFactory.decodeFile(p.img.path, BitmapFactory.Options().apply { inSampleSize = ss })
        } else {
            var bw = (uw * SC).toInt().coerceAtLeast(1); var bh = (uh * SC).toInt().coerceAtLeast(1)
            val big = maxOf(bw, bh)
            if (big > MAXPX) { val f = MAXPX.toFloat() / big; bw = (bw * f).toInt().coerceAtLeast(1); bh = (bh * f).toInt().coerceAtLeast(1) }
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            val pg = p.src!!.rr.openPage(p.idx)
            try { pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) } finally { pg.close() }
            b
        }
        if (bmp == null) return
        try {
            cv.save()
            cv.clipRect(cell)
            cv.translate(x + dw / 2, y + dh / 2)
            if (rot != 0) cv.rotate(rot.toFloat())
            cv.drawBitmap(bmp, null, RectF(-uw / 2, -uh / 2, uw / 2, uh / 2), bp)
            cv.restore()
        } finally { bmp.recycle() }
        if (border) cv.drawRect(maxOf(x, cell.left), maxOf(y, cell.top), minOf(x + dw, cell.right), minOf(y + dh, cell.bottom), line)
    }

    // ---- output ----------------------------------------------------------------------------------------------

    /** Copies [f] to <storage>/LANShare PDF/<name>.pdf (or Download/LANShare PDF through MediaStore without "All files access"). Returns the place. */
    private fun save(name0: String, f: File): String {
        var base = name0.replace(Regex("[^\\p{L}\\p{N}._ +()-]"), "_").trim().take(80).ifEmpty { "document" }
        base = base.removeSuffix(".pdf").removeSuffix(".PDF").trim().ifEmpty { "document" }
        val dir = File(Environment.getExternalStorageDirectory(), DIR)
        if ((dir.isDirectory || dir.mkdirs()) && dir.canWrite()) {
            var t = File(dir, "$base.pdf"); var k = 1
            while (t.exists()) { t = File(dir, "$base ($k).pdf"); k++ }
            f.copyTo(t)
            return "$DIR/${t.name}"
        }
        val ctx = Core.appCtx
        if (Build.VERSION.SDK_INT >= 29 && ctx != null) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$DIR")
            }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("could not create the PDF file")
            ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: throw IOException("could not write the PDF file")
            return "Download/$DIR/$base.pdf"
        }
        throw IOException("No storage access - allow \"All files access\" for LANShare")
    }
}
