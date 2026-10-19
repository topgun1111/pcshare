package com.lanshare.app.core.office

import java.io.File

/**
 * Part 10b: read-only .odp text view (no android.*), same output as [PptxReader]: "Slide N" heading per `draw:page` (page break before 2..),
 * title-class frames as sub-headings, other text paragraphs plain, pictures inline (`DImage.part` = `Pictures/..`, size from the frame),
 * speaker notes as one italic grey "Notes: ..." paragraph. Not read: shapes / layout, animations, master pages. Encrypted files are refused.
 */
class OdpReader(file: File) : AutoCloseable {
    private val pkg = OoxmlPackage(file)

    init {
        val man = pkg.read("META-INF/manifest.xml")?.toString(Charsets.UTF_8).orEmpty()
        if (man.contains("encryption-data")) { pkg.close(); throw IllegalStateException("File is password protected") }
    }

    private fun emu(v: String?): Long {   // "12.5cm" / "4in" / "30mm" / "200pt" -> EMU
        if (v == null) return 0L
        val m = Regex("^\\s*(-?[0-9.]+)\\s*(cm|mm|in|pt|px)?").find(v) ?: return 0L
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val f = when (m.groupValues[2]) { "cm" -> 360000.0; "mm" -> 36000.0; "in" -> 914400.0; "pt" -> 12700.0; "px" -> 9525.0; else -> 360000.0 }
        return (n * f).toLong()
    }

    /** text of the current text:p / text:h up to its END (spaces / tabs / line breaks become one space) */
    private fun para(x: MiniXml): String {
        val d = x.depth; val sb = StringBuilder()
        if (x.isEmptyElement()) { x.next(); return "" }
        while (true) {
            when (x.next()) {
                MiniXml.T.TEXT -> sb.append(x.text)
                MiniXml.T.START -> if (x.name == "s" || x.name == "tab" || x.name == "line-break") sb.append(' ')
                MiniXml.T.END -> if (x.depth < d) return sb.toString()
                MiniXml.T.EOF -> return sb.toString()
            }
        }
    }

    fun read(maxParagraphs: Int = 20000): DDoc {
        val doc = DDoc(); var count = 0; var n = 0
        val x = pkg.xml("content.xml") ?: throw IllegalArgumentException("content.xml missing")
        var inPage = false; var title = false; var fw = 0L; var fh = 0L
        val notes = ArrayList<String>(); var inNotes = false; var notesDepth = 0
        fun flushNotes() {
            if (notes.isNotEmpty()) {
                val p = DPara(); val r = DRun(DRun.TEXT, "Notes: " + notes.joinToString(" / ")); r.italic = true; r.color = "666666"; p.runs.add(r); doc.body.add(p); count++
                notes.clear()
            }
        }
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) break
            if (t == MiniXml.T.END) { if (inNotes && x.depth < notesDepth) inNotes = false; continue }
            if (t != MiniXml.T.START) continue
            if (count >= maxParagraphs) { doc.truncated = true; break }
            when (x.name) {
                "page" -> if (!inNotes) {   // draw:page of the presentation body (the notes' thumbnail page is `page-thumbnail`)
                    if (inPage) flushNotes()
                    inPage = true; val head = DPara(); head.heading = 1; head.style = "heading 1"; head.pageBreakBefore = n > 0
                    head.runs.add(DRun(DRun.TEXT, "Slide " + (n + 1) + (x.attr("name")?.takeIf { it.isNotBlank() && !it.startsWith("page") }?.let { " - $it" } ?: "")))
                    doc.body.add(head); count++; n++; title = false
                }
                "notes" -> if (inPage) { inNotes = true; notesDepth = x.depth }
                "frame" -> if (!inNotes) { fw = emu(x.attr("width")); fh = emu(x.attr("height")); title = x.attr("class") == "title" || x.rawAttr("presentation:class") == "title" }
                "image" -> if (inPage && !inNotes) {
                    val href = x.attr("href")
                    if (href != null && !href.contains(':') && !href.startsWith("..") && pkg.has(href.removePrefix("./"))) {
                        val p = DPara(); val r = DRun(DRun.IMAGE); r.image = DImage(href.removePrefix("./"), fw, fh, ""); p.runs.add(r); doc.body.add(p); count++
                    }
                }
                "p", "h" -> if (inPage) {
                    val txt = para(x)
                    if (txt.isNotBlank()) {
                        if (inNotes) notes.add(txt.trim()) else {
                            val p = DPara(); p.runs.add(DRun(DRun.TEXT, txt))
                            if (title) { p.heading = 2; p.style = "heading 2" }
                            doc.body.add(p); count++
                        }
                    }
                }
            }
        }
        flushNotes()
        return doc
    }

    /** bytes of a `Pictures/..` part */
    fun image(part: String): ByteArray? = try { pkg.read(part) } catch (_: Throwable) { null }

    override fun close() { pkg.close() }
}
