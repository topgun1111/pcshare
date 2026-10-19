package com.lanshare.app.core.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Edits a .docx IN PLACE (text patching of word/document.xml; every other zip entry is copied byte-for-byte, so styles, images, charts survive).
 * Paragraph index = position among ALL outermost `<w:p>` of the body in document order (table-cell paragraphs included, text-box paragraphs excluded;
 * the same order [DocxReader] produces for body paragraphs when tables are flattened). Table / row / cell indices are 0-based, outermost tables only.
 * Each call: load -> patch -> temp file -> sanity open -> swap. The previous version is kept as an undo snapshot ([undo], up to [UNDO_MAX]).
 * Known limits: replacing a paragraph's text keeps its pPr and the first text run's rPr but drops hyperlinks, bookmarks and field codes inside it;
 * paragraphs containing images / drawings / fields are refused by [setParagraphText]; tracked changes are not created.
 */
object DocxEditor {
    const val UNDO_MAX = 10
    private const val DOC = "word/document.xml"

    fun paragraphCount(file: File): Int = Doc(file).use { it.paras().size }

    fun paragraphText(file: File, index: Int): String = Doc(file).use { d ->
        val ps = d.paras(); require(index in ps.indices) { "No such paragraph" }
        textOf(d.xml.substring(ps[index].first, ps[index].last + 1))
    }

    /** Replace the text of paragraph [index]. `\n` = line break, `\t` = tab. */
    fun setParagraphText(file: File, index: Int, text: String) = Doc(file).edit { d ->
        val ps = d.paras(); require(index in ps.indices) { "No such paragraph" }
        val r = ps[index]
        d.replace(r, rebuild(d.xml.substring(r.first, r.last + 1), text, d.p))
    }

    /** Insert a new paragraph before paragraph [at] (== count appends at the end of the body). Copies pPr of paragraph [like] (default: the one before, else the one at). */
    fun insertParagraph(file: File, at: Int, text: String, like: Int = -1) = Doc(file).edit { d ->
        val ps = d.paras(); require(at in 0..ps.size) { "Bad position" }
        val src = if (like in ps.indices) like else if (at > 0) at - 1 else if (ps.isNotEmpty()) 0 else -1
        val ppr = if (src >= 0) pPrOf(d.xml.substring(ps[src].first, ps[src].last + 1), d.p) else ""
        val xml = "<${d.p}:p>$ppr${runsXml(text, "", d.p)}</${d.p}:p>"
        val pos = if (at < ps.size) ps[at].first else d.bodyEnd()
        d.xml = d.xml.substring(0, pos) + xml + d.xml.substring(pos)
    }

    /** Delete paragraph [index]. A paragraph that is the only one of a table cell, or carries a section break, is emptied instead (the file must stay valid). */
    fun deleteParagraph(file: File, index: Int) = Doc(file).edit { d ->
        val ps = d.paras(); require(index in ps.indices) { "No such paragraph" }
        val r = ps[index]; val px = d.xml.substring(r.first, r.last + 1)
        val before = d.xml.substring(maxOf(0, r.first - 12), r.first); val after = d.xml.substring(r.last + 1, minOf(d.xml.length, r.last + 1 + 12))
        val onlyInCell = after.startsWith("</${d.p}:tc>") && (before.endsWith("</${d.p}:tcPr>") || before.endsWith("<${d.p}:tc>"))
        if (onlyInCell || px.contains("<${d.p}:sectPr")) {
            require(!hasObject(px, d.p)) { "Paragraph contains an image or field" }
            d.replace(r, rebuild(px, "", d.p))
        } else d.xml = d.xml.substring(0, r.first) + d.xml.substring(r.last + 1)
    }

    /** Text of one table cell (paragraphs joined by `\n`). */
    fun cellText(file: File, table: Int, row: Int, col: Int): String = Doc(file).use { d ->
        val c = d.cell(table, row, col); d.paras(c).joinToString("\n") { textOf(d.xml.substring(it.first, it.last + 1)) }
    }

    /** Set the text of a table cell: first paragraph gets [text], further paragraphs of the cell are removed. */
    fun setCellText(file: File, table: Int, row: Int, col: Int, text: String) = Doc(file).edit { d ->
        val c = d.cell(table, row, col); val ps = d.paras(c); require(ps.isNotEmpty()) { "Cell has no paragraph" }
        require(!d.xml.substring(c.first, c.last + 1).contains("<${d.p}:tbl")) { "Cell contains a nested table" }
        for (r in ps) require(!hasObject(d.xml.substring(r.first, r.last + 1), d.p)) { "Cell contains an image or field" }
        val first = ps[0]; val last = ps[ps.size - 1]
        d.replace(first.first..last.last, rebuild(d.xml.substring(first.first, first.last + 1), text, d.p))
    }

    /** Find & replace in body, headers, footers and footnotes; works across split runs. Returns the number of replacements (file untouched when 0). */
    fun replaceAll(file: File, find: String, replace: String, matchCase: Boolean = true, wholeWord: Boolean = false): Int {
        require(find.isNotEmpty()) { "Nothing to find" }
        var total = 0
        Doc(file).edit(extra = true) { d ->
            val re = matcher(find, matchCase, wholeWord)
            for (name in d.textParts()) {
                val xml = if (name == DOC) d.xml else d.str(name)
                val (nx, n) = replaceIn(xml, re, replace)
                if (n > 0) { total += n; if (name == DOC) d.xml = nx else d.put(name, nx) }
            }
        }
        return total
    }

    // ---------------------------------------------------------------------------------------------------------------- undo
    fun undoDepth(file: File): Int = (1..UNDO_MAX).count { snap(file, it).exists() }

    /** Restore the previous version (the current one is dropped). False when there is nothing to undo. */
    fun undo(file: File): Boolean {
        val s1 = snap(file, 1); if (!s1.exists()) return false
        OoxmlPackage(s1).use { check(it.has(DOC)) { "Undo snapshot is damaged" } }
        val bak = File(file.parentFile, file.name + ".bak~"); bak.delete()
        check(file.renameTo(bak)) { "Cannot replace the file" }
        if (!s1.renameTo(file)) { bak.renameTo(file); error("Cannot replace the file") }
        bak.delete()
        for (i in 2..UNDO_MAX) { val s = snap(file, i); if (s.exists()) s.renameTo(snap(file, i - 1)) }
        return true
    }

    fun clearUndo(file: File) { for (i in 1..UNDO_MAX) snap(file, i).delete() }

    private fun snap(file: File, n: Int) = File(file.parentFile, file.name + ".undo$n~")

    // ---------------------------------------------------------------------------------------------------------------- paragraph patching
    private fun re(p: String, tag: String) = Regex("<" + Regex.escape(p) + ":" + tag + "(?=[\\s>/])")
    private fun hasObject(px: String, p: String) = listOf("drawing", "pict", "object", "fldChar", "fldSimple", "instrText").any { re(p, it).containsMatchIn(px) }

    private fun textOf(px: String): String {
        val sb = StringBuilder()
        for (m in Regex("<(?:\\w+:)?(t|tab|br|cr)(?=[\\s>/])[^>]*?(?:/>|>(.*?)</(?:\\w+:)?t>)", RegexOption.DOT_MATCHES_ALL).findAll(px)) when (m.groupValues[1]) {
            "t" -> sb.append(MiniXml.unescape(m.groupValues[2]))
            "tab" -> sb.append('\t')
            else -> sb.append('\n')
        }
        return sb.toString()
    }

    private fun pPrOf(px: String, p: String): String {
        val m = Regex("<" + Regex.escape(p) + ":pPr(?:\\s[^>]*)?>.*?</" + Regex.escape(p) + ":pPr>", RegexOption.DOT_MATCHES_ALL).find(px)
        // a copied pPr must not carry the section break of its source paragraph
        return m?.value?.replace(Regex("<" + Regex.escape(p) + ":sectPr\\b.*?</" + Regex.escape(p) + ":sectPr>", RegexOption.DOT_MATCHES_ALL), "") ?: ""
    }

    private fun firstRPr(px: String, p: String): String {
        val body = px.replace(Regex("<" + Regex.escape(p) + ":pPr(?:\\s[^>]*)?>.*?</" + Regex.escape(p) + ":pPr>", RegexOption.DOT_MATCHES_ALL), "")
        val run = Regex("<" + Regex.escape(p) + ":r(?:\\s[^>]*)?>.*?</" + Regex.escape(p) + ":r>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .firstOrNull { re(p, "t").containsMatchIn(it.value) } ?: return ""
        return Regex("<" + Regex.escape(p) + ":rPr(?:\\s[^>]*)?>.*?</" + Regex.escape(p) + ":rPr>", RegexOption.DOT_MATCHES_ALL).find(run.value)?.value ?: ""
    }

    private fun runsXml(text: String, rPr: String, p: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder("<$p:r>$rPr")
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { sb.append("<$p:t xml:space=\"preserve\">").append(XValue.esc(buf.toString())).append("</$p:t>"); buf.setLength(0) } }
        for (ch in text.replace("\r\n", "\n")) when (ch) {
            '\t' -> { flush(); sb.append("<$p:tab/>") }
            '\n' -> { flush(); sb.append("<$p:br/>") }
            else -> buf.append(ch)
        }
        flush()
        return sb.append("</$p:r>").toString()
    }

    /** Paragraph XML with the same opening tag + pPr + (first run's rPr), new text. */
    private fun rebuild(px: String, text: String, p: String): String {
        require(!hasObject(px, p)) { "Paragraph contains an image or field" }
        val open = Regex("^<[^>]*?>").find(px)!!.value
        if (open.endsWith("/>")) return "$open".dropLast(2) + ">" + runsXml(text, "", p) + "</$p:p>"
        return open + pPrOf(px, p) + runsXml(text, firstRPr(px, p), p) + "</$p:p>"
    }

    // ---------------------------------------------------------------------------------------------------------------- find & replace
    private fun matcher(find: String, matchCase: Boolean, whole: Boolean): Regex {
        var s = Regex.escape(find)
        if (whole) s = "(?<![\\p{L}\\p{N}_])$s(?![\\p{L}\\p{N}_])"
        return if (matchCase) Regex(s) else Regex(s, RegexOption.IGNORE_CASE)
    }

    /** Replace inside every paragraph's `<w:t>` texts joined together; the replacement goes into the run where the match starts. */
    private fun replaceIn(xml: String, re: Regex, rep: String): Pair<String, Int> {
        var count = 0
        val tre = Regex("<(?:\\w+:)?t(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?t>", RegexOption.DOT_MATCHES_ALL)
        val pre = Regex("<(?:\\w+:)?p(?=[\\s>])[^>]*?(?<!/)>.*?</(?:\\w+:)?p>", RegexOption.DOT_MATCHES_ALL)   // innermost-first is fine: texts are found per match
        val out = StringBuilder(); var last = 0
        for (pm in pre.findAll(xml)) {
            val px = pm.value
            val segs = tre.findAll(px).toList()
            if (segs.isEmpty()) continue
            val texts = segs.map { MiniXml.unescape(it.groupValues[1]) }
            val all = texts.joinToString("")
            val ms = re.findAll(all).toList()
            if (ms.isEmpty()) continue
            val own = IntArray(all.length); var k = 0
            texts.forEachIndexed { si, t -> repeat(t.length) { own[k++] = si } }
            val nt = Array(texts.size) { StringBuilder() }
            var i = 0; var mi = 0
            while (i < all.length) {
                if (mi < ms.size && i == ms[mi].range.first) { nt[own[i]].append(rep); i = ms[mi].range.last + 1; mi++; continue }
                nt[own[i]].append(all[i]); i++
            }
            count += ms.size
            val sb = StringBuilder(px)
            for (si in segs.indices.reversed()) {
                val s = segs[si]; val open = s.value.substring(0, s.value.indexOf('>') + 1)
                val tag = Regex("^<((?:\\w+:)?t)").find(open)!!.groupValues[1]
                sb.replace(s.range.first, s.range.last + 1, "<$tag xml:space=\"preserve\">${XValue.esc(nt[si].toString())}</$tag>")
            }
            out.append(xml, last, pm.range.first).append(sb); last = pm.range.last + 1
        }
        out.append(xml, last, xml.length)
        return out.toString() to count
    }

    // ---------------------------------------------------------------------------------------------------------------- session
    private class Doc(val file: File) : AutoCloseable {
        private val pkg = OoxmlPackage(file)
        val part: String = (pkg.rels("").values.firstOrNull { it.kind == "officeDocument" }?.target ?: DOC).also { check(pkg.has(it)) { "Not a Word document" } }
        var xml: String = MiniXml.decode(pkg.read(part) ?: error("Missing document part"))
        private val changed = LinkedHashMap<String, ByteArray>()
        val p: String = Regex("xmlns:(\\w+)=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"").find(xml)?.groupValues?.get(1) ?: "w"
        init { check(part == DOC) { "Unsupported package layout" } }

        override fun close() = pkg.close()
        fun str(n: String): String = MiniXml.decode(pkg.read(n) ?: error("Missing part $n"))
        fun put(n: String, s: String) { changed[pkg.names.firstOrNull { it.equals(n, true) } ?: n] = s.toByteArray(Charsets.UTF_8) }
        fun textParts(): List<String> = listOf(DOC) + pkg.names.filter { Regex("word/(header|footer)\\d*\\.xml|word/(footnotes|endnotes)\\.xml", RegexOption.IGNORE_CASE).matches(it) }.sorted()

        fun replace(r: IntRange, s: String) { xml = xml.substring(0, r.first) + s + xml.substring(r.last + 1) }
        fun bodyEnd(): Int {
            val s = xml.lastIndexOf("<$p:sectPr"); val e = xml.lastIndexOf("</$p:body>")
            require(e >= 0) { "No body" }
            return if (s in 0 until e && !xml.substring(s, e).contains("</$p:p>")) s else e
        }

        /** Outermost elements named [tag] inside [range] (nesting of the same tag handled by depth). */
        fun elems(tag: String, range: IntRange = bodyRange()): List<IntRange> {
            val t = Regex.escape("$p:$tag")
            val rx = Regex("<$t(?=[\\s>/])[^>]*?/>|<$t(?=[\\s>/])[^>]*>|</$t>")
            val out = ArrayList<IntRange>(); var depth = 0; var start = 0
            for (m in rx.findAll(xml.substring(range.first, range.last + 1))) {
                val a = range.first + m.range.first; val b = range.first + m.range.last
                when {
                    m.value.startsWith("</") -> { if (depth > 0 && --depth == 0) out.add(start..b) }
                    m.value.endsWith("/>") -> if (depth == 0) out.add(a..b)
                    else -> { if (depth++ == 0) start = a }
                }
            }
            return out
        }
        fun bodyRange(): IntRange {
            val s = Regex("<$p:body(?=[\\s>])[^>]*>").find(xml) ?: error("No body"); val e = xml.lastIndexOf("</$p:body>")
            require(e > s.range.last) { "Empty body" }
            return (s.range.last + 1)..(e - 1)
        }
        fun paras(range: IntRange = bodyRange()): List<IntRange> = elems("p", range)

        /** Inner range (between the opening and closing tag) of an element. */
        private fun inner(r: IntRange): IntRange = (xml.indexOf('>', r.first) + 1)..(xml.lastIndexOf('<', r.last) - 1)
        fun cell(table: Int, row: Int, col: Int): IntRange {
            val ts = elems("tbl"); require(table in ts.indices) { "No such table" }
            val rs = elems("tr", inner(ts[table])); require(row in rs.indices) { "No such row" }
            val cs = elems("tc", inner(rs[row])); require(col in cs.indices) { "No such column" }
            return inner(cs[col])
        }

        /** Runs [fn], writes the changed parts, rotates the undo snapshots and swaps the file. */
        fun edit(extra: Boolean = false, fn: (Doc) -> Unit) {
            try { fn(this); put(DOC, xml); commit() } finally { close() }
        }

        private fun commit() {
            val tmp = File(file.parentFile, file.name + ".tmp~")
            try {
                ZipFile(file).use { zin ->
                    ZipOutputStream(FileOutputStream(tmp).buffered()).use { zout ->
                        val written = HashSet<String>(); val en = zin.entries()
                        while (en.hasMoreElements()) {
                            val e = en.nextElement()
                            val ne = ZipEntry(e.name); if (e.time > 0) ne.time = e.time
                            zout.putNextEntry(ne)
                            val repl = changed[e.name]
                            if (repl != null) zout.write(repl) else if (!e.isDirectory) zin.getInputStream(e).use { it.copyTo(zout) }
                            zout.closeEntry(); written.add(e.name)
                        }
                        for ((n, b) in changed) if (n !in written) { zout.putNextEntry(ZipEntry(n)); zout.write(b); zout.closeEntry() }
                    }
                }
                OoxmlPackage(tmp).use { check(it.has(DOC)) { "Result is not a document" }; DocxReader(tmp).use { r -> r.read(maxParagraphs = 50) } }
                close()
                // undo rotation: 1 -> 2 -> ... (oldest dropped), current file becomes snapshot 1
                snap(file, UNDO_MAX).delete()
                for (i in UNDO_MAX - 1 downTo 1) { val s = snap(file, i); if (s.exists()) s.renameTo(snap(file, i + 1)) }
                val s1 = snap(file, 1)
                check(file.renameTo(s1)) { "Cannot replace the file" }
                if (!tmp.renameTo(file)) { s1.renameTo(file); error("Cannot replace the file") }
            } catch (t: Throwable) { tmp.delete(); throw t }
        }
    }
}
