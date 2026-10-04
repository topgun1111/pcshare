package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Real page previews for the print dialog, for every format.
 *  pdf -> as is | html/svg/unknown -> PrintPrep (phone) | office -> the PC converts it (pcprint.py /convert, v11) | pictures / text -> the file itself.
 * PDFs are rendered page by page with Android's PdfRenderer; the UI asks for `pvpage` images lazily.
 */
object PrintPreview {
    private const val MAX_SRC = 150L shl 20
    private const val KEEP = 6

    private class Doc(val id: String, val dir: File, val file: File, val kind: String, val ext: String) {
        var fd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        var pages = 0
        var w = 595
        var h = 842
        @Volatile var used = System.currentTimeMillis()
        @Synchronized fun renderer(): PdfRenderer {
            r?.let { return it }
            val f = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { fd = it }
            return PdfRenderer(f).also { r = it }
        }
        @Synchronized fun close() { try { r?.close() } catch (_: Throwable) {}; try { fd?.close() } catch (_: Throwable) {}; r = null; fd = null }
        fun wipe() { close(); try { dir.deleteRecursively() } catch (_: Throwable) {} }
    }

    private val docs = object : LinkedHashMap<String, Doc>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Doc>): Boolean {
            if (size > KEEP) { e.value.wipe(); return true }
            return false
        }
    }
    private val busy = HashMap<String, Any>()   // one preparation per document at a time (two taps on the same file)

    private fun root() = File(Core.cacheDir.parentFile ?: Core.cacheDir, "pv").also { it.mkdirs() }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** {kind: pdf|img|text|office, id, pages?, w?, h?, why?}  - "office" = the PC could not convert it (sample layout in the UI). */
    fun info(dev: String, path: String, to: String): JSONObject {
        val ep = Jobs.ep(dev)
        val name = vbase(path)
        val ext = name.substringAfterLast('.', "").lowercase()
        val src0 = ep.open(path)
        val size = try { src0.size } finally { src0.close() }
        val id = sha("$dev|$path|$size|$to")
        val lock = synchronized(busy) { busy.getOrPut(id) { Any() } }
        synchronized(lock) {
            synchronized(docs) { docs[id] }?.let { d -> d.used = System.currentTimeMillis(); synchronized(busy) { busy.remove(id) }; return json(d) }
            if (size > MAX_SRC) { synchronized(busy) { busy.remove(id) }; throw IOException("too large for a preview") }
            val dir = File(root(), id).also { it.deleteRecursively(); it.mkdirs() }
            try {
                val raw = File(dir, "src.bin")
                src(ep, path, raw)
                var f = raw
                var e = ext
                var kind = PrintPrep.kind(e)
                // phone-side conversion (pictures, html/svg, unknown types)
                if (kind == PrintPrep.Kind.PIC || kind == PrintPrep.Kind.WEB || kind == PrintPrep.Kind.SNIFF) {
                    if (kind == PrintPrep.Kind.SNIFF && size > PrintPrep.SNIFF_MAX) throw IOException("this file type cannot be previewed")
                    val o = PrintPrep.convert(kind, raw, name)
                    f = o.file
                    e = o.name.substringAfterLast('.', "").lowercase()
                    kind = PrintPrep.kind(e)
                } else if (kind == PrintPrep.Kind.NO) throw IOException("$ext files cannot be printed")
                val d: Doc
                when {
                    e == "pdf" -> d = Doc(id, dir, f, "pdf", e)
                    e in OFFICE -> {
                        val pdf = File(dir, "office.pdf")
                        try { office(to, f, "x.$e", pdf) } catch (x: Exception) {
                            dir.deleteRecursively()
                            return JSONObject().put("kind", "office").put("why", errText(x))
                        }
                        d = Doc(id, dir, pdf, "pdf", "pdf")
                    }
                    e in IMG -> d = Doc(id, dir, f, "img", e)
                    else -> d = Doc(id, dir, f, "text", e)
                }
                if (d.kind == "pdf") {
                    val r = d.renderer()
                    d.pages = r.pageCount
                    if (d.pages > 0) r.openPage(0).use { p -> d.w = p.width; d.h = p.height }
                    if (d.pages <= 0) throw IOException("the PDF has no pages")
                }
                synchronized(docs) { docs[id] = d }
                return json(d)
            } catch (x: Throwable) { try { dir.deleteRecursively() } catch (_: Throwable) {}; throw x }
            finally { synchronized(busy) { busy.remove(id) } }
        }
    }

    private val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp")
    private val IMG = setOf("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff")

    private fun json(d: Doc) = JSONObject().put("kind", d.kind).put("id", d.id).put("pages", d.pages).put("w", d.w).put("h", d.h).put("ext", d.ext)

    private fun src(ep: Endpoint, path: String, out: File) {
        ep.open(path).use { s -> out.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n) } } }
    }

    private fun office(to: String, f: File, name: String, out: File) {
        val (ip, _) = Jobs.printTarget(to)
        f.inputStream().use { ins ->
            val r = Http.request(ip, Jobs.PRINT_PORT, "POST", "/convert?name=" + java.net.URLEncoder.encode(name, "UTF-8"), emptyMap(), 180_000, ins, f.length())
            try {
                if (r.status == 404) throw IOException("update pcprint.py on the PC (v11) for real page previews of office files")
                if (r.status != 200) {
                    val t = String(r.readUpTo(2000), Charsets.UTF_8)
                    throw IOException(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                }
                out.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = r.body.read(b); if (n < 0) break; o.write(b, 0, n) } }
                if (out.length() == 0L) throw IOException("empty answer from the PC")
            } finally { r.close() }
        }
    }

    private fun doc(id: String): Doc = synchronized(docs) { docs[id] } ?: throw NotFound("preview expired - reopen the print dialog")

    /** The converted / original picture or text file (kind img | text). */
    fun file(id: String): Pair<File, String> { val d = doc(id); return d.file to d.ext }

    /** JPEG of one page (0-based), [width] px wide (<= 1100). */
    fun page(id: String, n: Int, width: Int): ByteArray {
        val d = doc(id)
        if (d.kind != "pdf" || n < 0 || n >= d.pages) throw BadReq("no such page")
        d.used = System.currentTimeMillis()
        val w = width.coerceIn(120, 1100)
        synchronized(d) {   // PdfRenderer is not thread-safe
            val r = d.renderer()
            r.openPage(n).use { p ->
                val h = maxOf(1, Math.round(w.toFloat() * p.height / p.width))
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(bmp).drawColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bo = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo)
                    return bo.toByteArray()
                } finally { bmp.recycle() }
            }
        }
    }

    fun clear() = synchronized(docs) { docs.values.forEach { it.wipe() }; docs.clear() }
}
