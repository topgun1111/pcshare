package com.lanshare.app.core

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Word (.docx) -> light Markdown (headings, bold/italic, lists, tables) for the read-only viewer in TextEditorActivity. No library. */
object DocxText {
    private const val MAX_XML = 24 shl 20

    fun isDocx(name: String) = name.lowercase().endsWith(".docx")

    /** null = not a Word document. */
    fun toMarkdown(zip: ByteArray): String? {
        val xml = try { entry(zip, "word/document.xml") } catch (_: Throwable) { null } ?: return null
        return try { convert(xml) } catch (_: Throwable) { null }
    }

    private fun entry(zip: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: return null
                if (e.name != name) continue
                val bo = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024); var t = 0
                while (true) { val n = z.read(buf); if (n < 0) break; t += n; if (t > MAX_XML) return null; bo.write(buf, 0, n) }
                return bo.toByteArray()
            }
        }
    }

    private class Line {
        val sb = StringBuilder(); var b = false; var i = false
        fun mark(m: String, open: Boolean) {
            if (open) { sb.append(m); return }
            var k = sb.length; while (k > 0 && sb[k - 1].isWhitespace()) k--
            sb.insert(k, m)
        }
        fun add(t: String, nb: Boolean, ni: Boolean) {
            if (t.isBlank()) { sb.append(t); return }
            val lead = t.length - t.trimStart().length
            if (b && !nb) { mark("**", false); b = false }
            if (i && !ni) { mark("*", false); i = false }
            sb.append(t, 0, lead)
            if (!b && nb) { mark("**", true); b = true }
            if (!i && ni) { mark("*", true); i = true }
            sb.append(t, lead, t.length)
        }
        fun close(): String { if (i) mark("*", false); if (b) mark("**", false); i = false; b = false; return sb.toString() }
    }

    private fun on(p: XmlPullParser): Boolean { val v = p.getAttributeValue(null, "w:val") ?: p.getAttributeValue(null, "val"); return v == null || !(v == "0" || v == "false" || v == "none") }

    private fun convert(xml: ByteArray): String {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(xml), "UTF-8")
        val out = StringBuilder()
        var line = Line()
        var style = ""; var list = -1; var inP = false
        var rb = false; var ri = false; var inRPr = false; var inT = false
        var tbl = 0; var rows = ArrayList<List<String>>(); var cells = ArrayList<String>(); var cell = StringBuilder()
        var ev = p.eventType
        fun para(): String {
            val t = line.close(); line = Line()
            val s = style.lowercase()
            val h = when { s == "title" -> 1; s.startsWith("heading") -> (s.removePrefix("heading").trim().toIntOrNull() ?: 1).coerceIn(1, 6); else -> 0 }
            return if (t.isBlank()) "" else if (h > 0) "#".repeat(h) + " " + t.trim() else if (list >= 0) "  ".repeat(list.coerceAtMost(6)) + "- " + t.trim() else t
        }
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { inP = true; style = ""; list = -1; line = Line() }
                    "w:pStyle" -> style = p.getAttributeValue(null, "w:val") ?: ""
                    "w:ilvl" -> list = (p.getAttributeValue(null, "w:val") ?: "0").toIntOrNull() ?: 0
                    "w:numPr" -> if (list < 0) list = 0
                    "w:r" -> { rb = false; ri = false }
                    "w:rPr" -> inRPr = true
                    "w:b" -> if (inRPr) rb = on(p)
                    "w:i" -> if (inRPr) ri = on(p)
                    "w:t" -> inT = true
                    "w:tab" -> if (!inRPr && inP) line.sb.append("\t")
                    "w:br", "w:cr" -> if (inP) line.sb.append(' ')
                    "w:tbl" -> { tbl++; if (tbl == 1) rows = ArrayList() }
                    "w:tr" -> if (tbl == 1) cells = ArrayList()
                    "w:tc" -> if (tbl == 1) cell = StringBuilder()
                }
                XmlPullParser.TEXT -> if (inT && inP) line.add(p.text, rb, ri)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inT = false
                    "w:rPr" -> inRPr = false
                    "w:p" -> {
                        inP = false
                        val t = para()
                        if (tbl > 0) { if (t.isNotBlank()) { if (cell.isNotEmpty()) cell.append(' '); cell.append(t.trimStart('#', ' ', '-')) } }
                        else out.append(t).append("\n\n")
                    }
                    "w:tc" -> if (tbl == 1) cells.add(cell.toString().replace('|', '/').trim())
                    "w:tr" -> if (tbl == 1) rows.add(cells)
                    "w:tbl" -> {
                        tbl--
                        if (tbl == 0 && rows.isNotEmpty()) {
                            val n = rows.maxOf { it.size }.coerceAtLeast(1)
                            fun row(r: List<String>) = "| " + List(n) { r.getOrElse(it) { "" } }.joinToString(" | ") + " |\n"
                            out.append(row(rows[0])).append("|").append(" --- |".repeat(n)).append('\n')
                            for (k in 1 until rows.size) out.append(row(rows[k]))
                            out.append('\n')
                        }
                    }
                }
            }
            ev = p.next()
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
    }
}
