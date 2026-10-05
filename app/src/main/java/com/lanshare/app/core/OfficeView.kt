package com.lanshare.app.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Office file VIEWING (Tier 1): doc/docx/rtf/odt/xls/xlsx/csv/ods/ppt/pptx/odp.
 * The PC converts the file to PDF (pcprint.py v11 `POST /convert`, same path as PrintPreview),
 * the phone caches the PDF and serves it at `GET /api/officepdf?dev&path`; the UI hands that URL
 * to the existing native PDF reader (`LSAndroid.viewPdf`). See OFFICE_VIEW_HANDOVER.md.
 * Not compiled / not device-tested.
 */
object OfficeView {
    val EXTS = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "csv", "ods", "ppt", "pptx", "odp")
    private const val MAX_SRC = 60L shl 20      // bigger files are refused (PC upload + conversion time)
    private const val KEEP = 8                  // converted PDFs kept in cache/ov (LRU by mtime)
    private val busy = HashMap<String, Any>()

    private fun root() = File(Core.cacheDir.parentFile ?: Core.cacheDir, "ov").also { it.mkdirs() }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun isOffice(name: String) = name.substringAfterLast('.', "").lowercase() in EXTS

    /** The converted PDF for [path] on [dev] (cached; one conversion per file at a time). */
    fun pdfFor(dev: String, path: String): File {
        val ep = Jobs.ep(dev)
        val name = vbase(path)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in EXTS) throw IOException("not an office file: $name")
        val size = ep.open(path).use { it.size }
        if (size > MAX_SRC) throw IOException("too large to convert (max ${MAX_SRC shr 20} MB)")
        val id = sha("$dev|$path|$size")
        val out = File(root(), "$id.pdf")
        val lock = synchronized(busy) { busy.getOrPut(id) { Any() } }
        synchronized(lock) {
            try {
                if (out.isFile && out.length() > 0) { out.setLastModified(System.currentTimeMillis()); return out }
                val src = File(root(), "$id.src")
                val tmp = File(root(), "$id.tmp")
                try {
                    ep.open(path).use { s -> src.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n) } } }
                    Jobs.officeToPdf(Jobs.converterIp(), src, "x.$ext", tmp)   // throws a readable IOException (old pcprint.py, no engine, ...)
                    if (!tmp.renameTo(out)) throw IOException("cannot store converted file")
                } finally { src.delete(); tmp.delete() }
                prune()
                return out
            } finally { synchronized(busy) { busy.remove(id) } }
        }
    }


    /** Tier 2: can the phone render this file itself (docx/xlsx/pptx/odf)? */
    fun canText(name: String) = OfficeText.canRead(name)

    /** Is a PC converter reachable right now? (the UI picks tier 1 or 2 with this) */
    @Volatile private var okAt = 0L
    @Volatile private var okVal = false
    fun converterOk(): Boolean {   // cached 30 s (yes) / 15 s (no): a tap must not re-ping the whole LAN
        val now = System.currentTimeMillis()
        if (now - okAt < (if (okVal) 30000L else 15000L)) return okVal
        val v = try { Jobs.converterIp(); true } catch (_: Throwable) { false }
        okVal = v; okAt = System.currentTimeMillis()
        return v
    }

    /** Tier 2: the file rendered to one HTML page on the phone (no PC needed). */
    fun htmlFor(dev: String, path: String): String {
        val ep = Jobs.ep(dev)
        val name = vbase(path)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (!OfficeText.canRead(name)) throw IOException("cannot read .$ext without the PC converter")
        val size = ep.open(path).use { it.size }
        if (size > MAX_SRC) throw IOException("too large (max ${MAX_SRC shr 20} MB)")
        val src = File(root(), "h" + sha("$dev|$path|$size|${System.nanoTime()}") + ".src")
        try {
            ep.open(path).use { s -> src.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; o.write(b, 0, n) } } }
            return OfficeText.toHtml(src, ext)
        } finally { src.delete() }
    }

    private fun prune() {
        val l = root().listFiles { f -> f.name.endsWith(".pdf") }?.sortedByDescending { it.lastModified() } ?: return
        l.drop(KEEP).forEach { it.delete() }
    }

    /** Called at app start, like PrintPreview. */
    fun wipe() { try { root().deleteRecursively() } catch (_: Throwable) {} }
}
