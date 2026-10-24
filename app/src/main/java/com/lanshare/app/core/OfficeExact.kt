package com.lanshare.app.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * "Exact pages" for Office files: the paired PC (pcprint.py v11+ `/convert`, Word / Excel / PowerPoint or LibreOffice) turns the file into a PDF,
 * which the native PDF viewer shows. Office fidelity Part 2 (HANDOVER_OFFICE_FIDELITY.md).
 *
 * - Setting: SharedPreferences `office_view` / `mode` = `auto` (default: try the PC, fall back to the simple view) | `exact` (same, the user asked for it) | `simple` (never convert).
 * - Cache: `cache/office-pdf/<md5 of the file content>.pdf` - the same content is reused whatever its path or time stamp (the viewer re-downloads remote files every time),
 *   an edited file has another md5 and is converted again. 12 h, 300 MB cap, oldest first.
 * - One conversion at a time on this phone (pcprint.py also converts one at a time); a second open waits its turn.
 */
object OfficeExact {
    private const val PREFS = "office_view"
    private const val MAX_AGE = 12L * 3600 * 1000
    private const val CAP = 300L * 1024 * 1024
    private val lock = Any()
    @Volatile private var failAt = 0L          // last time no PC could convert (auto opens skip the PC search for a minute after that)
    @Volatile private var failWhy = ""
    private val EXT = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")   // csv is not worth a round trip

    fun eligible(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXT

    fun mode(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", "auto").let { if (it == "exact" || it == "simple") it else "auto" }
    fun setMode(c: Context, m: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", m).apply() }

    private fun dir(c: Context) = File(c.cacheDir, "office-pdf").apply { mkdirs() }

    fun md5(f: File): String {
        val md = MessageDigest.getInstance("MD5"); val b = ByteArray(64 * 1024)
        f.inputStream().use { i -> while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** A converted copy that is still fresh, or null. */
    fun cached(c: Context, src: File): File? {
        val out = File(dir(c), md5(src) + ".pdf")
        return if (out.isFile && out.length() > 0 && System.currentTimeMillis() - out.lastModified() < MAX_AGE) out else null
    }

    /** Converted PDF of [src] (from the cache when possible). Throws IOException with a readable reason when no PC can do it. Blocking: call on a background thread. */
    fun convert(c: Context, src: File, quick: Boolean = false, status: (String) -> Unit = {}): File {
        val d = dir(c)
        val out = File(d, md5(src) + ".pdf")
        synchronized(lock) {
            if (out.isFile && out.length() > 0 && System.currentTimeMillis() - out.lastModified() < MAX_AGE) return out
            if (quick && failWhy.isNotEmpty() && System.currentTimeMillis() - failAt < 60_000) throw IOException(failWhy)   // do not wait 3.5 s again for every file
            status("Looking for your PC\u2026")
            val ip = try { Jobs.converterIp().also { failWhy = "" } } catch (e: IOException) { failWhy = e.message ?: "no PC found"; failAt = System.currentTimeMillis(); throw e }
            status("Preparing exact pages on your PC\u2026")
            val tmp = File(d, out.name + ".part")
            try {
                Jobs.officeToPdf(ip, src, src.name, tmp)
                if (tmp.length() < 100) throw IOException("the PC returned an empty PDF")
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) throw IOException("cannot store the converted PDF")
                out.setLastModified(System.currentTimeMillis())
            } finally { if (tmp.exists()) tmp.delete() }
            trim(d, out)
            return out
        }
    }

    private fun trim(d: File, keep: File) {
        val now = System.currentTimeMillis()
        val all = d.listFiles() ?: return
        for (f in all) if (f != keep && (f.name.endsWith(".part") || now - f.lastModified() > MAX_AGE)) f.delete()
        val pdfs = (d.listFiles() ?: return).filter { it.isFile }.sortedBy { it.lastModified() }
        var sum = pdfs.sumOf { it.length() }
        for (f in pdfs) { if (sum <= CAP) break; if (f != keep) { sum -= f.length(); f.delete() } }
    }
}
