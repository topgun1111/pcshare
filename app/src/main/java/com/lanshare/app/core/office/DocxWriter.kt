package com.lanshare.app.core.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates brand-new .docx files (pure java.util.zip, no android.*). Input is plain text or a small Markdown subset:
 * `#`..`######` headings, `-` `*` `+` bullets (indent 2 spaces = next level), `1.` numbered lists (each list restarts at 1), `> quote`,
 * ``` code fences, `---` rule, pipe tables (header + `|---|` row), inline `**bold**` `*italic*` `_italic_` `~~strike~~` `` `code` `` `[text](url)`, `\x` escapes.
 * One non-blank line = one paragraph (notes style, lines are NOT joined). A4, Calibri 11. Writes to a temp file, then renames.
 */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val RNS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val HDR = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun createEmpty(out: File) = fromText(out, "")

    /** Plain text: every line is a paragraph, nothing is interpreted. */
    fun fromText(out: File, text: String) {
        val b = Body()
        for (ln in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) b.sb.append(para(listOf(run(ln, Fmt())), ""))
        write(out, b)
    }

    fun fromMarkdown(out: File, md: String) = write(out, parse(md))

    private class Fmt(val b: Boolean = false, val i: Boolean = false, val s: Boolean = false, val code: Boolean = false, val link: Boolean = false)

    private class Body {
        val sb = StringBuilder()
        val links = ArrayList<String>()      // external targets, rId = "rIdL" + (index + 1)
        var numCount = 0                      // numbered lists started (numId 2..)
        fun link(url: String): String { links.add(url); return "rIdL${links.size}" }
    }

    // ---------------------------------------------------------------------------------------------------------------- markdown
    private fun parse(md: String): Body {
        val b = Body()
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0; var curNum = 0; var inList = 0   // inList: 0 none, 1 bullet, 2 numbered
        fun inl(t: String, extra: String = "") = para(runsOf(t, b), extra)
        while (i < lines.size) {
            val ln = lines[i]; val t = ln.trim()
            if (t.isEmpty()) { inList = 0; i++; continue }
            if (t.startsWith("```")) {
                inList = 0; i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { b.sb.append(para(listOf(run(lines[i], Fmt(code = true))), "<w:spacing w:after=\"0\"/>")); i++ }
                i++; continue
            }
            if (Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t)) { inList = 0; b.sb.append(para(emptyList(), "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"999999\"/></w:pBdr>")); i++; continue }
            val h = Regex("^(#{1,6})\\s+(.*?)\\s*#*$").find(t)
            if (h != null) { inList = 0; b.sb.append(inl(h.groupValues[2], "<w:pStyle w:val=\"Heading${h.groupValues[1].length}\"/>")); i++; continue }
            if (t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(lines[i + 1].trim())) {
                inList = 0
                val rows = ArrayList<List<String>>(); rows.add(cells(t)); i += 2
                while (i < lines.size && lines[i].trim().startsWith("|")) { rows.add(cells(lines[i].trim())); i++ }
                table(b, rows); continue
            }
            if (t.startsWith(">")) { inList = 0; b.sb.append(inl(t.trimStart('>').trim(), "<w:ind w:left=\"567\"/><w:pBdr><w:left w:val=\"single\" w:sz=\"12\" w:space=\"8\" w:color=\"AAAAAA\"/></w:pBdr>")); i++; continue }
            val bl = Regex("^(\\s*)[-*+]\\s+(.*)$").find(ln)
            if (bl != null) {
                inList = 1
                val lv = minOf(bl.groupValues[1].replace("\t", "  ").length / 2, 8)
                b.sb.append(inl(bl.groupValues[2], "<w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"$lv\"/><w:numId w:val=\"1\"/></w:numPr>")); i++; continue
            }
            val nl = Regex("^(\\s*)\\d+[.)]\\s+(.*)$").find(ln)
            if (nl != null) {
                if (inList != 2) { b.numCount++; curNum = b.numCount + 1 }
                inList = 2
                val lv = minOf(nl.groupValues[1].replace("\t", "  ").length / 2, 8)
                b.sb.append(inl(nl.groupValues[2], "<w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"$lv\"/><w:numId w:val=\"$curNum\"/></w:numPr>")); i++; continue
            }
            inList = 0; b.sb.append(inl(t)); i++
        }
        return b
    }

    private fun cells(row: String): List<String> {
        var r = row.trim(); if (r.startsWith("|")) r = r.substring(1); if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        return r.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    }

    private fun table(b: Body, rows: List<List<String>>) {
        val n = rows.maxOf { it.size }.coerceAtLeast(1)
        val w = 9638 / n
        val sb = b.sb
        sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (s in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$s w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        sb.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        repeat(n) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { ri, r ->
            sb.append(if (ri == 0) "<w:tr><w:trPr><w:tblHeader/></w:trPr>" else "<w:tr>")
            for (c in 0 until n) {
                val txt = r.getOrElse(c) { "" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>")
                if (ri == 0) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E7E6E6\"/>")
                sb.append("</w:tcPr>").append(para(runsOf(txt, b, if (ri == 0) Fmt(b = true) else Fmt()), "<w:spacing w:after=\"0\"/>")).append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        sb.append(para(emptyList(), ""))   // a table must not be the last body element / two tables must not touch
    }

    // ---------------------------------------------------------------------------------------------------------------- inline
    private fun runsOf(t: String, b: Body, base: Fmt = Fmt()): List<String> {
        val out = ArrayList<String>()
        var bold = base.b; var ital = base.i; var strike = base.s
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(run(buf.toString(), Fmt(bold, ital, strike, base.code, base.link))); buf.setLength(0) } }
        var p = 0
        while (p < t.length) {
            val c = t[p]
            val prev = if (p > 0) t[p - 1] else ' '; val next = if (p + 1 < t.length) t[p + 1] else ' '
            when {
                c == '\\' && p + 1 < t.length && !t[p + 1].isLetterOrDigit() -> { buf.append(t[p + 1]); p += 2 }
                c == '`' -> {
                    val e = t.indexOf('`', p + 1)
                    if (e < 0) { buf.append(c); p++ } else { flush(); out.add(run(t.substring(p + 1, e), Fmt(bold, ital, strike, true, base.link))); p = e + 1 }
                }
                c == '*' && next == '*' -> { flush(); bold = !bold; p += 2 }
                c == '~' && next == '~' -> { flush(); strike = !strike; p += 2 }
                (c == '*' || c == '_') && !(prev.isWhitespace() && next.isWhitespace()) && (c == '*' || !prev.isLetterOrDigit() || !next.isLetterOrDigit()) -> { flush(); ital = !ital; p++ }
                c == '[' -> {
                    val m = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(t.substring(p))
                    if (m != null && Regex("^(https?://|mailto:|tel:)", RegexOption.IGNORE_CASE).containsMatchIn(m.groupValues[2])) {
                        flush()
                        val rid = b.link(m.groupValues[2])
                        val inner = runsOf(m.groupValues[1], b, Fmt(bold, ital, strike, base.code, true)).joinToString("")
                        out.add("<w:hyperlink r:id=\"$rid\">$inner</w:hyperlink>")
                        p += m.value.length
                    } else { buf.append(c); p++ }
                }
                else -> { buf.append(c); p++ }
            }
        }
        flush()
        return out
    }

    /** A run for [text]; `\t` -> tab, `\n` -> line break. */
    private fun run(text: String, f: Fmt): String {
        if (text.isEmpty()) return ""
        val rp = StringBuilder()
        if (f.link) rp.append("<w:rStyle w:val=\"Hyperlink\"/>")
        if (f.code) rp.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        if (f.b) rp.append("<w:b/>"); if (f.i) rp.append("<w:i/>"); if (f.s) rp.append("<w:strike/>")
        if (f.code) rp.append("<w:sz w:val=\"20\"/>")
        val sb = StringBuilder("<w:r>"); if (rp.isNotEmpty()) sb.append("<w:rPr>").append(rp).append("</w:rPr>")
        val parts = text.split(Regex("(?<=[\t\n])|(?=[\t\n])"))
        for (s in parts) when (s) {
            "\t" -> sb.append("<w:tab/>")
            "\n" -> sb.append("<w:br/>")
            "" -> {}
            else -> sb.append("<w:t xml:space=\"preserve\">").append(XValue.esc(s)).append("</w:t>")
        }
        return sb.append("</w:r>").toString()
    }

    private fun para(runs: List<String>, pPr: String): String =
        "<w:p>" + (if (pPr.isNotEmpty()) "<w:pPr>$pPr</w:pPr>" else "") + runs.joinToString("") + "</w:p>"

    // ---------------------------------------------------------------------------------------------------------------- package
    private fun write(out: File, b: Body) {
        val tmp = File(out.absoluteFile.parentFile, out.name + ".tmp~")
        try {
            ZipOutputStream(FileOutputStream(tmp).buffered()).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
                val M = "application/vnd.openxmlformats-officedocument.wordprocessingml"
                put("[Content_Types].xml", HDR + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"$M.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"$M.styles+xml\"/>" +
                    "<Override PartName=\"/word/numbering.xml\" ContentType=\"$M.numbering+xml\"/></Types>")
                put("_rels/.rels", HDR + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$RNS/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
                val rels = StringBuilder(HDR + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"$RNS/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId2\" Type=\"$RNS/numbering\" Target=\"numbering.xml\"/>")
                b.links.forEachIndexed { i, u -> rels.append("<Relationship Id=\"rIdL${i + 1}\" Type=\"$RNS/hyperlink\" Target=\"${XValue.esc(u)}\" TargetMode=\"External\"/>") }
                put("word/_rels/document.xml.rels", rels.append("</Relationships>").toString())
                val body = if (b.sb.isEmpty()) "<w:p/>" else b.sb.toString()
                put("word/document.xml", HDR + "<w:document xmlns:w=\"$W\" xmlns:r=\"$RNS\"><w:body>$body" +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1134\" w:bottom=\"1440\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>")
                put("word/styles.xml", styles())
                put("word/numbering.xml", numbering(b.numCount))
            }
            if (out.exists() && !out.delete()) error("Cannot replace ${out.name}")
            if (!tmp.renameTo(out)) error("Cannot create ${out.name}")
        } catch (t: Throwable) { tmp.delete(); throw t }
    }

    private fun styles(): String {
        val sb = StringBuilder(HDR + "<w:styles xmlns:w=\"$W\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/></w:pPr></w:style>" +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:basedOn w:val=\"DefaultParagraphFont\"/><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>")
        val sizes = intArrayOf(40, 32, 28, 26, 24, 22)
        for (n in 1..6) sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"${if (n == 1) 360 else 240}\" w:after=\"80\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"${sizes[n - 1]}\"/><w:szCs w:val=\"${sizes[n - 1]}\"/></w:rPr></w:style>")
        return sb.append("</w:styles>").toString()
    }

    /** abstractNum 0 = bullets, 1 = decimal; numId 1 = bullets, numId 2.. = one numbered list each (restart at 1). */
    private fun numbering(lists: Int): String {
        val sb = StringBuilder(HDR + "<w:numbering xmlns:w=\"$W\">")
        val bul = arrayOf("\u2022", "o", "\u25AA")
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${bul[l % 3]}\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        sb.append("</w:abstractNum><w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        val fm = arrayOf("decimal", "lowerLetter", "lowerRoman")
        for (l in 0..8) sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${fm[l % 3]}\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * l}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        sb.append("</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        for (n in 1..lists) sb.append("<w:num w:numId=\"${n + 1}\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        return sb.append("</w:numbering>").toString()
    }
}
