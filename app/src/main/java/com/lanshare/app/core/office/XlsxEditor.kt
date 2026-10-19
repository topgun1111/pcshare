package com.lanshare.app.core.office

import java.io.File
import java.io.FileOutputStream
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One cell change. Empty [value] clears the content but keeps the cell style. "=SUM(A1:A2)" = formula, [asText] forces text. */
class CellEdit(val row: Int, val col: Int, val value: String, val asText: Boolean = false)

/** How typed text is stored: number / bool / formula / text. */
object XValue {
    enum class K { EMPTY, NUMBER, BOOL, FORMULA, TEXT }
    private val NUM = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
    fun kind(v: String, asText: Boolean): K {
        if (v.isEmpty()) return K.EMPTY
        if (asText) return K.TEXT
        if (v.length > 1 && v[0] == '=') return K.FORMULA
        val t = v.trim()
        if (t.equals("true", true) || t.equals("false", true)) return K.BOOL
        if (t.length <= 15 && NUM.matches(t)) {
            val d = t.trimStart('-')
            if (d.length > 1 && d[0] == '0' && d[1].isDigit()) return K.TEXT   // 007 stays text
            return K.NUMBER
        }
        return K.TEXT
    }
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;"); ch == '"' -> sb.append("&quot;")
            ch.code < 32 && ch != '\t' && ch != '\n' && ch != '\r' -> {}   // illegal in XML 1.0
            else -> sb.append(ch)
        }
        return sb.toString()
    }
    /** The cell XML for [ref] with optional style id [s] (attribute text such as ` s="3"`). */
    fun cellXml(ref: String, s: String, v: String, asText: Boolean): String = when (kind(v, asText)) {
        K.EMPTY -> "<c r=\"$ref\"$s/>"
        K.NUMBER -> "<c r=\"$ref\"$s><v>${v.trim()}</v></c>"
        K.BOOL -> "<c r=\"$ref\"$s t=\"b\"><v>${if (v.trim().equals("true", true)) 1 else 0}</v></c>"
        K.FORMULA -> "<c r=\"$ref\"$s><f>${esc(v.substring(1))}</f></c>"
        K.TEXT -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
    }
}

/**
 * Edits an .xlsx IN PLACE: only the touched parts are rewritten, every other zip entry (styles, images, charts, macros...) is copied unchanged.
 * Each public call = load -> patch -> write temp file -> swap (backup kept until the swap worked). Throws on any problem, the original is then untouched.
 * Text is stored as inline strings (valid; Excel moves them to the shared table on its next save), so sharedStrings.xml is never touched.
 * Formulas are NOT adjusted on row insert/delete or sheet rename (known limit); calcChain is dropped and a full recalculation is requested on open.
 * Rows / cells outside the sheet XML [sheetData] (merges, cond. formatting ranges, drawings) are only shifted for merges.
 */
object XlsxEditor {
    const val MAX_ROW = 1048575
    const val MAX_COL = 16383

    fun setCells(file: File, sheet: Int, edits: List<CellEdit>) {
        if (edits.isEmpty()) return
        for (e in edits) require(e.row in 0..MAX_ROW && e.col in 0..MAX_COL) { "Cell out of range" }
        Session(file).run {
            val part = sheetPart(sheet)
            val sd = SheetData(str(part))
            for (e in edits) sd.set(e.row, e.col, e.value, e.asText)
            put(part, sd.build())
            dropCalc(); commit()
        }
    }

    fun setCell(file: File, sheet: Int, row: Int, col: Int, value: String, asText: Boolean = false) = setCells(file, sheet, listOf(CellEdit(row, col, value, asText)))

    /** Insert [count] empty rows before 0-based row [at]. */
    fun insertRows(file: File, sheet: Int, at: Int, count: Int = 1) {
        require(at >= 0 && count in 1..10000) { "Bad row range" }
        Session(file).run {
            val part = sheetPart(sheet)
            val sd = SheetData(str(part)); sd.shift(at, count, -1)
            put(part, shiftMerges(sd.build(), at, count, -1)); dropCalc(); commit()
        }
    }

    /** Delete [count] rows starting at 0-based row [at]. */
    fun deleteRows(file: File, sheet: Int, at: Int, count: Int = 1) {
        require(at >= 0 && count in 1..10000) { "Bad row range" }
        Session(file).run {
            val part = sheetPart(sheet)
            val sd = SheetData(str(part)); sd.shift(at, -count, at + count)
            put(part, shiftMerges(sd.build(), at, -count, at + count)); dropCalc(); commit()
        }
    }

    fun renameSheet(file: File, sheet: Int, newName: String) {
        val name = newName.trim(); checkName(name)
        Session(file).run {
            val info = sheets()[sheet]
            require(sheets().none { it.index != sheet && it.name.equals(name, true) }) { "A sheet with this name exists" }
            var wb = str(WB)
            val m = sheetTags(wb).firstOrNull { unesc(attrOf(it.value, "name") ?: "") == info.name } ?: error("Sheet not found")
            val tag = m.value
            wb = wb.replaceRange(m.range, setAttr(tag, "name", XValue.esc(name)))
            put(WB, wb); commit()
        }
    }

    /** Appends an empty sheet; returns its index. */
    fun addSheet(file: File, newName: String): Int {
        val name = newName.trim(); checkName(name)
        var idx = -1
        Session(file).run {
            require(sheets().none { it.name.equals(name, true) }) { "A sheet with this name exists" }
            var n = 1; while (has("xl/worksheets/sheet$n.xml")) n++
            val part = "xl/worksheets/sheet$n.xml"
            put(part, XlsxWriter.EMPTY_SHEET)
            val rid = newRid()
            put(WB_RELS, str(WB_RELS).replace("</Relationships>", "<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/></Relationships>"))
            val sid = (sheetTags(str(WB)).mapNotNull { attrOf(it.value, "sheetId")?.toIntOrNull() }.maxOrNull() ?: 0) + 1
            val relPfx = relPrefix(str(WB))
            put(WB, str(WB).replace("</sheets>", "<sheet name=\"${XValue.esc(name)}\" sheetId=\"$sid\" $relPfx:id=\"$rid\"/></sheets>"))
            put(CT, str(CT).replace("</Types>", "<Override PartName=\"/$part\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"))
            idx = sheets().size
            commit()
        }
        return idx
    }

    /** Removes a sheet (not the last visible one). */
    fun deleteSheet(file: File, sheet: Int) {
        Session(file).run {
            val all = sheets(); val info = all[sheet]
            require(all.count { !it.hidden && !it.veryHidden } > 1 || info.hidden || info.veryHidden) { "A workbook needs at least one visible sheet" }
            val tag = sheetTags(str(WB)).first { unesc(attrOf(it.value, "name") ?: "") == info.name }
            var wb = str(WB).replaceRange(tag.range, "")
            // defined names bound to this sheet go away, the ones after it move one down
            wb = Regex("<definedName\\b[^>]*?localSheetId=\"(\\d+)\"[^>]*?(/>|>.*?</definedName>)", RegexOption.DOT_MATCHES_ALL).replace(wb) { m ->
                val l = m.groupValues[1].toInt()
                if (l == sheet) "" else if (l > sheet) m.value.replace("localSheetId=\"$l\"", "localSheetId=\"${l - 1}\"") else m.value
            }
            wb = wb.replace(Regex("\\s(activeTab|firstSheet)=\"\\d+\""), "")
            put(WB, wb)
            val rid = attrOfRaw(tag.value, "id")
            if (rid != null) put(WB_RELS, str(WB_RELS).replace(Regex("<Relationship\\b[^>]*?\\bId=\"" + Regex.escape(rid) + "\"[^>]*?/>"), ""))
            put(CT, str(CT).replace(Regex("<Override\\b[^>]*?PartName=\"/" + Regex.escape(info.part) + "\"[^>]*?/>"), ""))
            remove(info.part)
            remove(info.part.substringBeforeLast('/') + "/_rels/" + info.part.substringAfterLast('/') + ".rels")
            dropCalc(); commit()
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- session

    private const val WB = "xl/workbook.xml"
    private const val WB_RELS = "xl/_rels/workbook.xml.rels"
    private const val CT = "[Content_Types].xml"

    private fun checkName(n: String) {
        require(n.isNotEmpty() && n.length <= 31) { "Sheet name must be 1-31 characters" }
        require(n.none { it in "[]:*?/\\" } && !n.startsWith("'") && !n.endsWith("'")) { "Sheet name has forbidden characters" }
    }

    private class Session(val file: File) {
        private val pkg = OoxmlPackage(file)
        private val changed = LinkedHashMap<String, ByteArray>()
        private val removed = HashSet<String>()
        private var infos: List<XSheetInfo>? = null

        fun has(n: String) = (pkg.has(n) && n.lowercase() !in removed.map { it.lowercase() }) || changed.keys.any { it.equals(n, true) }
        fun str(n: String): String {
            changed.entries.firstOrNull { it.key.equals(n, true) }?.let { return String(it.value, Charsets.UTF_8) }
            val b = pkg.read(n) ?: error("Missing part $n")
            return MiniXml.decode(b)
        }
        fun put(n: String, s: String) { val real = pkg.names.firstOrNull { it.equals(n, true) } ?: n; changed[real] = s.toByteArray(Charsets.UTF_8)}
        fun remove(n: String) { pkg.names.firstOrNull { it.equals(n, true) }?.let { removed.add(it) }; changed.keys.removeAll { it.equals(n, true) } }
        /** Sheet list of the ORIGINAL file (indices stay valid for the whole session). */
        fun sheets(): List<XSheetInfo> = infos ?: XlsxReader(file).use { it.sheets }.also { infos = it }
        fun sheetPart(i: Int): String { val l = sheets(); require(i in l.indices) { "No such sheet" }; return l[i].part }
        fun newRid(): String { val s = str(WB_RELS); var n = 1; while (s.contains("Id=\"rId$n\"")) n++; return "rId$n" }
        /** Drop calcChain (stale after formula / row changes) and ask Excel for a full recalc. */
        fun dropCalc() {
            if (has("xl/calcChain.xml")) {
                remove("xl/calcChain.xml")
                put(WB_RELS, str(WB_RELS).replace(Regex("<Relationship\\b[^>]*?calcChain[^>]*?/>"), ""))
                put(CT, str(CT).replace(Regex("<Override\\b[^>]*?calcChain[^>]*?/>"), ""))
            }
            var wb = str(WB)
            if (Regex("<calcPr\\b").containsMatchIn(wb)) wb = Regex("<calcPr\\b[^>]*?/>").replace(wb) { m -> setAttr(m.value, "fullCalcOnLoad", "1") }
            else {
                var pos = -1
                for (t in listOf("</sheets>", "</functionGroups>", "</externalReferences>", "</definedNames>")) { val i = wb.indexOf(t); if (i >= 0) pos = maxOf(pos, i + t.length) }
                if (pos > 0) wb = wb.substring(0, pos) + "<calcPr fullCalcOnLoad=\"1\"/>" + wb.substring(pos)
            }
            put(WB, wb)
        }

        fun commit() {
            pkg.close()
            val tmp = File(file.parentFile, file.name + ".tmp~")
            try {
                ZipFile(file).use { zin ->
                    ZipOutputStream(FileOutputStream(tmp).buffered()).use { zout ->
                        val written = HashSet<String>()
                        val order = ArrayList<ZipEntry>(); val en = zin.entries(); while (en.hasMoreElements()) order.add(en.nextElement())
                        for (e in order) {
                            if (e.name in removed) continue
                            val ne = ZipEntry(e.name); if (e.time > 0) ne.time = e.time
                            zout.putNextEntry(ne)
                            val repl = changed[e.name]
                            if (repl != null) zout.write(repl) else if (!e.isDirectory) zin.getInputStream(e).use { it.copyTo(zout) }
                            zout.closeEntry(); written.add(e.name)
                        }
                        for ((n, b) in changed) if (n !in written) { zout.putNextEntry(ZipEntry(n)); zout.write(b); zout.closeEntry() }
                    }
                }
                // sanity: the new file must open
                OoxmlPackage(tmp).use { check(it.has(WB)) { "Result is not a workbook" } }
                val bak = File(file.parentFile, file.name + ".bak~")
                bak.delete()
                check(file.renameTo(bak)) { "Cannot replace the file" }
                if (!tmp.renameTo(file)) { bak.renameTo(file); error("Cannot replace the file") }
                bak.delete()
            } catch (t: Throwable) { tmp.delete(); throw t }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- sheet XML

    /** `<sheetData>` as rows -> cells (raw cell XML). Everything before / after it is kept as text. */
    private class SheetData(xml: String) {
        class Row(var attrs: String, val cells: TreeMap<Int, String>)
        val head: String; val tail: String
        val rows = TreeMap<Int, Row>()
        init {
            val m = Regex("<sheetData\\b[^>]*?(/>|>(.*?)</sheetData>)", RegexOption.DOT_MATCHES_ALL).find(xml) ?: error("Sheet has no sheetData")
            head = xml.substring(0, m.range.first).replace(Regex("<dimension\\b[^>]*?/>"), ""); tail = xml.substring(m.range.last + 1)
            val body = m.groups[2]?.value ?: ""
            var auto = 0
            for (rm in Regex("<row\\b([^>]*?)(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL).findAll(body)) {
                val a = rm.groupValues[1]
                val rn = (attrOf(" $a", "r")?.toIntOrNull() ?: (auto + 1)) - 1; auto = rn + 1
                val cells = TreeMap<Int, String>(); var ac = 0
                for (cm in Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(rm.groupValues[2])) {
                    val ca = cm.groupValues[1]
                    val col = attrOf(" $ca", "r")?.let { XRef.parse(it)?.get(0) } ?: ac; ac = col + 1
                    cells[col] = cm.value
                }
                rows[rn] = Row(a, cells)
            }
        }
        fun set(r: Int, c: Int, v: String, asText: Boolean) {
            val row = rows.getOrPut(r) { Row(" r=\"${r + 1}\"", TreeMap()) }
            val old = row.cells[c]
            val s = old?.let { attrOf(it.substringBefore('>'), "s") }?.let { " s=\"$it\"" } ?: ""
            if (old == null && v.isEmpty()) { if (row.cells.isEmpty()) rows.remove(r); return }
            row.cells[c] = XValue.cellXml(XRef.name(c, r), s, v, asText)
        }
        /** Rows >= [at] move by [by]; rows in [at+by, delTo) (deleted) vanish when by < 0. */
        fun shift(at: Int, by: Int, delTo: Int) {
            val out = TreeMap<Int, Row>()
            for ((r, row) in rows) {
                if (r < at) { out[r] = row; continue }
                if (by < 0 && r < delTo) continue
                val nr = r + by; if (nr > MAX_ROW) continue
                row.attrs = setAttr(" ${row.attrs}", "r", "${nr + 1}").trimStart()
                for ((c, x) in row.cells.entries.toList()) row.cells[c] = setAttr(x.substringBefore('>'), "r", XRef.name(c, nr)) + x.substring(x.indexOf('>'))
                out[nr] = row
            }
            rows.clear(); rows.putAll(out)
        }
        fun build(): String {
            val sb = StringBuilder(head); sb.append("<sheetData>")
            for ((_, row) in rows) {
                val a = row.attrs.trim()
                if (row.cells.isEmpty()) sb.append("<row $a/>") else { sb.append("<row $a>"); for (x in row.cells.values) sb.append(x); sb.append("</row>") }
            }
            sb.append("</sheetData>").append(tail)
            return sb.toString()
        }
    }

    /** Move / drop merged ranges like the rows (insert: by > 0; delete: by < 0 and rows [at, delTo) gone). */
    private fun shiftMerges(xml: String, at: Int, by: Int, delTo: Int): String {
        if (!xml.contains("<mergeCell ")) return xml
        var left = 0
        val res = Regex("<mergeCell\\b[^>]*?ref=\"([A-Z]+\\d+(?::[A-Z]+\\d+)?)\"[^>]*?/>").replace(xml) { m ->
            val rg = XRef.range(m.groupValues[1]) ?: return@replace m.value
            var r1 = rg[1]; var r2 = rg[3]
            if (by > 0) { if (r1 >= at) r1 += by; if (r2 >= at) r2 += by }
            else {
                if (r1 >= at && r2 < delTo) return@replace ""                       // fully inside the deleted rows
                if (r1 >= delTo) r1 += by else if (r1 >= at) r1 = at
                if (r2 >= delTo) r2 += by else if (r2 >= at) r2 = at - 1
                if (r2 < r1) return@replace ""
            }
            if (r1 == r2 && rg[0] == rg[2]) return@replace ""
            left++
            "<mergeCell ref=\"${XRef.name(rg[0], r1)}:${XRef.name(rg[2], r2)}\"/>"
        }
        // fix the count or drop the empty container
        return if (left == 0) res.replace(Regex("<mergeCells\\b[^>]*?>\\s*</mergeCells>"), "") else res.replace(Regex("(<mergeCells\\b[^>]*?count=\")\\d+"), "$1$left")
    }

    // ---------------------------------------------------------------------------------------------------------------- tiny xml-text helpers

    private fun sheetTags(wb: String) = Regex("<(?:\\w+:)?sheet\\b[^>]*?/>").findAll(wb).toList()
    private fun relPrefix(wb: String): String = Regex("(\\w+)=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"").find(wb)?.groupValues?.get(1) ?: "r"
    /** value of attribute [n] (local name, no prefix) in a start-tag text, or null */
    private fun attrOf(tag: String, n: String): String? = Regex("[\\s]" + Regex.escape(n) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
    /** value of a prefixed attribute (e.g. r:id) matched by local name */
    private fun attrOfRaw(tag: String, local: String): String? = Regex("\\s(?:\\w+:)?" + Regex.escape(local) + "=\"([^\"]*)\"").findAll(tag).lastOrNull()?.groupValues?.get(1)
    private fun setAttr(tag: String, n: String, v: String): String {
        val m = Regex("\\s" + Regex.escape(n) + "=\"[^\"]*\"").find(tag)
        if (m != null) return tag.replaceRange(m.range, " $n=\"$v\"")
        val end = if (tag.trimEnd().endsWith("/>")) tag.lastIndexOf("/>") else tag.lastIndexOf('>').let { if (it < 0) tag.length else it }
        return tag.substring(0, end).trimEnd() + " $n=\"$v\"" + tag.substring(end)
    }
    private fun unesc(s: String) = MiniXml.unescape(s)
}
