package com.lanshare.app.core.office

import java.io.File

/**
 * Read-only .pptx text view (no android.*): every slide becomes a "Slide N" heading (page break before slides 2..) followed by its text
 * paragraphs in shape order; title placeholders become sub-headings, tables (`a:tbl`) are real tables, text boxes plain paragraphs; hidden slides get "(hidden)". Same model as
 * [DocxReader] (`DDoc`), so `DocxHtml.render` shows it unchanged. Part 10b: pictures (`p:pic`, inline in shape order, size from `a:ext`) and speaker
 * notes (notes slide body placeholder, one italic grey "Notes: ..." paragraph per slide). Not read: other shapes / layout, animations, master text.
 * Encrypted .pptx are OLE containers and are caught by [OfficeOpen] before this class is used.
 */
class PptxReader(private val file: File) : AutoCloseable {
    private val pkg = OoxmlPackage(file)
    /** package paths of the slides in presentation order */
    val slides: List<String>

    init {
        check(pkg.has("ppt/presentation.xml")) { "Not a PowerPoint file" }
        val rels = pkg.rels("ppt/presentation.xml")
        val ids = ArrayList<String>()
        pkg.xml("ppt/presentation.xml")?.let { x ->
            while (true) {
                val t = x.next()
                if (t == MiniXml.T.EOF) break
                if (t == MiniXml.T.START && x.name == "sldId") x.rawAttr("r:id")?.let { ids.add(it) }
            }
        }
        var list = ids.mapNotNull { rels[it]?.target }.filter { pkg.has(it) }
        if (list.isEmpty()) {   // damaged presentation.xml: fall back to the file names
            val re = Regex("ppt/slides/slide(\\d+)\\.xml", RegexOption.IGNORE_CASE)
            list = pkg.names.filter { re.matches(it) }.sortedBy { re.find(it)!!.groupValues[1].toInt() }
        }
        slides = list
    }

    fun read(maxParagraphs: Int = 20000): DDoc {
        val doc = DDoc(); var count = 0
        for ((n, part) in slides.withIndex()) {
            if (count >= maxParagraphs) { doc.truncated = true; break }
            val head = DPara(); head.heading = 1; head.style = "heading 1"; head.pageBreakBefore = n > 0
            head.runs.add(DRun(DRun.TEXT, "Slide " + (n + 1))); doc.body.add(head); count++
            val x = pkg.xml(part) ?: continue
            val rels = try { pkg.rels(part) } catch (_: Throwable) { emptyMap() }
            var title = false; var hidden = false
            while (true) {
                val t = x.next()
                if (t == MiniXml.T.EOF) break
                if (t != MiniXml.T.START) continue
                when (x.name) {
                    "sld" -> if (x.attr("show") == "0") hidden = true
                    "sp" -> title = false
                    "tbl" -> { val tb = readTable(x); if (tb.rows.isNotEmpty()) { doc.body.add(tb); count += tb.rows.size } }
                    "pic" -> {   // p:pic: blip r:embed (picture part), a:ext cx/cy (EMU), cNvPr descr = alt text
                        val d = x.depth; var emb: String? = null; var cx = 0L; var cy = 0L; var alt = ""
                        while (true) {
                            val u = x.next()
                            if (u == MiniXml.T.EOF) break
                            if (u == MiniXml.T.END && x.depth < d) break
                            if (u != MiniXml.T.START) continue
                            when (x.name) {
                                "cNvPr" -> alt = x.attr("descr") ?: x.attr("name") ?: ""
                                "blip" -> if (emb == null) emb = x.rawAttr("r:embed")
                                "ext" -> if (cx == 0L) { cx = x.attr("cx")?.toLongOrNull() ?: 0L; cy = x.attr("cy")?.toLongOrNull() ?: 0L }
                            }
                        }
                        val tgt = emb?.let { rels[it] }?.takeIf { !it.external }?.target
                        if (tgt != null && pkg.has(tgt)) {
                            val p = DPara(); val r = DRun(DRun.IMAGE); r.image = DImage(tgt, cx, cy, alt); p.runs.add(r); doc.body.add(p); count++
                        }
                    }
                    "ph" -> { val ty = x.attr("type"); if (ty == "title" || ty == "ctrTitle") title = true }
                    "p" -> {   // a:p
                        val d = x.depth; val sb = StringBuilder()
                        while (true) {
                            val u = x.next()
                            if (u == MiniXml.T.EOF) break
                            if (u == MiniXml.T.END && x.depth < d) break
                            if (u == MiniXml.T.START) { if (x.name == "t") sb.append(x.readText()) else if (x.name == "br") sb.append('\n') }
                        }
                        val txt = sb.toString()
                        if (txt.isNotBlank()) {
                            val p = DPara(); p.runs.add(DRun(DRun.TEXT, txt))
                            if (title) { p.heading = 2; p.style = "heading 2" }
                            doc.body.add(p); count++
                        }
                    }
                }
                if (count >= maxParagraphs) { doc.truncated = true; break }
            }
            if (hidden) head.runs[0].text += " (hidden)"
            notesOf(rels)?.let { nt ->
                val p = DPara(); val r = DRun(DRun.TEXT, "Notes: " + nt); r.italic = true; r.color = "666666"; p.runs.add(r); doc.body.add(p); count++
            }
        }
        return doc
    }

    /** `a:tbl` -> real table (grid from `a:gridCol`, gridSpan / hMerge / rowSpan / vMerge, first row = header when `firstRow="1"`). [x] is on the START of tbl. */
    private fun readTable(x: MiniXml): DTable {
        val d = x.depth; val tb = DTable(); tb.borders = true
        var firstRow = false; var cell: DCell? = null
        while (true) {
            val u = x.next()
            if (u == MiniXml.T.EOF) break
            if (u == MiniXml.T.END && x.depth < d) break
            if (u != MiniXml.T.START) continue
            when (x.name) {
                "tblPr" -> if (x.attr("firstRow") == "1") firstRow = true
                "gridCol" -> tb.grid.add(((x.attr("w")?.toLongOrNull() ?: 0L) / 635L).toInt())   // EMU -> twips
                "tr" -> { tb.rows.add(DRow()); cell = null }
                "tc" -> {
                    val row = tb.rows.lastOrNull()
                    if (row == null || x.attr("hMerge") == "1") cell = null   // covered by the previous cell's gridSpan
                    else {
                        val c = DCell(); c.span = x.attr("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        if (x.attr("vMerge") == "1") c.vMerge = 2 else if ((x.attr("rowSpan")?.toIntOrNull() ?: 1) > 1) c.vMerge = 1
                        row.cells.add(c); cell = c
                    }
                }
                "p" -> {
                    val pd = x.depth; val sb = StringBuilder()
                    while (true) {
                        val v = x.next()
                        if (v == MiniXml.T.EOF) break
                        if (v == MiniXml.T.END && x.depth < pd) break
                        if (v == MiniXml.T.START) { if (x.name == "t") sb.append(x.readText()) else if (x.name == "br") sb.append('\n') }
                    }
                    val c = cell
                    if (c != null && sb.isNotBlank()) { val p = DPara(); p.runs.add(DRun(DRun.TEXT, sb.toString())); c.blocks.add(p) }
                }
            }
        }
        if (firstRow) tb.rows.firstOrNull()?.header = true
        if (tb.grid.isEmpty()) { val n = tb.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 0; repeat(n) { tb.grid.add(2000) } }
        return tb
    }

    /** Speaker notes of a slide: text of the body placeholder of its notes slide (null when none / empty). */
    private fun notesOf(rels: Map<String, OoxmlPackage.Rel>): String? {
        val np = rels.values.firstOrNull { it.type.endsWith("/notesSlide") && !it.external }?.target ?: return null
        val x = try { pkg.xml(np) } catch (_: Throwable) { null } ?: return null
        val sb = StringBuilder(); var body = false
        while (true) {
            val t = x.next()
            if (t == MiniXml.T.EOF) break
            if (t != MiniXml.T.START) continue
            when (x.name) {
                "sp" -> body = false
                "ph" -> if (x.attr("type") == "body") body = true
                "p" -> if (body) {
                    val d = x.depth; val line = StringBuilder()
                    while (true) {
                        val u = x.next()
                        if (u == MiniXml.T.EOF) break
                        if (u == MiniXml.T.END && x.depth < d) break
                        if (u == MiniXml.T.START) { if (x.name == "t") line.append(x.readText()) else if (x.name == "br") line.append(' ') }
                    }
                    if (line.isNotBlank()) { if (sb.isNotEmpty()) sb.append(" / "); sb.append(line.trim()) }
                }
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    override fun close() { pkg.close() }
}
