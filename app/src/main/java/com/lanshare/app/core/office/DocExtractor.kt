package com.lanshare.app.core.office

import java.io.File

/**
 * Universal Search Part 6: plain text out of one Office document for the content index. Pure JVM, no android imports.
 * Wraps [OfficeExport.text] (same text as the folder search: docx / doc / odt paragraphs + tables + header / footer, pptx "Slide N" headings
 * + notes, sheets as `## name` headers + tab-separated cells) and adds: size cap, time cap (worker thread, abandoned on timeout),
 * and "never throws" (corrupt / encrypted / unsupported -> null, so the queue goes on).
 */
object DocExtractor {
    val EXTS: Set<String> = OfficeExport.EXTS
    const val MAX_BYTES = 30L shl 20
    const val CAP_MS = 20_000L

    fun isDoc(ext: String) = ext.lowercase() in EXTS

    /** null = unreadable / too big / timed out. "" = readable but no text. */
    fun extract(f: File, maxBytes: Long = MAX_BYTES, capMs: Long = CAP_MS): String? {
        val len = try { f.length() } catch (_: Throwable) { return null }
        if (len <= 0L || len > maxBytes || !isDoc(f.extension)) return null
        var out: String? = null
        val t = Thread { out = OfficeExport.textOrNull(f, 600_000) }
        t.isDaemon = true
        t.start()
        try { t.join(maxOf(1L, capMs)) } catch (_: InterruptedException) { t.interrupt(); return null }
        if (t.isAlive) { t.interrupt(); return null }
        return out
    }

    /** Label of the sheet (`## name`) or slide (`Slide N`) that holds position [pos] of [text]; "" when there is none (Word, ODT). */
    fun sectionAt(text: String, pos: Int): String {
        var i = minOf(pos, text.length)
        while (i > 0) {
            val s = text.lastIndexOf('\n', i - 1) + 1
            val e = text.indexOf('\n', s).let { if (it < 0) text.length else it }
            if (e > s) {
                val l = text.substring(s, e)
                if (l.startsWith("## ")) return "Sheet " + l.substring(3).trim()
                if (l.length > 6 && l.startsWith("Slide ", ignoreCase = true)) {
                    val d = l.substring(6).takeWhile { it.isDigit() }
                    if (d.isNotEmpty() && (d.length == l.length - 6 || l.substring(6 + d.length).startsWith(" ("))) return "Slide $d"   // "Slide 3" or "Slide 3 (hidden)"
                }
            }
            i = s - 1
            if (s == 0) break
        }
        return ""
    }
}
