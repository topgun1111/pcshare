// JVM self-test for core/office (no Android needed).  Build & run:
//   kotlinc app/src/main/java/com/lanshare/app/core/office/*.kt tools/office_selftest/Main.kt -include-runtime -d /tmp/ost.jar
//   java -jar /tmp/ost.jar some.xlsx
import com.lanshare.app.core.office.*
import java.io.File

fun check(name: String, got: String, want: String) { println((if (got == want) "ok   " else "FAIL ") + name + "  got='" + got + "' want='" + want + "'") }

fun main(a: Array<String>) {
    if (a.isNotEmpty() && a[0] == "--edit") { editTest(); return }
    if (a.isNotEmpty() && a[0] == "--docx") { docxTest(); return }
    check("general int", NumFmt.format(42.0, "General"), "42")
    check("general frac", NumFmt.format(3.14159, "General"), "3.14159")
    check("0.00", NumFmt.format(3.14159, "0.00"), "3.14")
    check("#,##0", NumFmt.format(1234567.0, "#,##0"), "1,234,567")
    check("#,##0.00", NumFmt.format(1234.5, "#,##0.00"), "1,234.50")
    check("percent", NumFmt.format(0.256, "0%"), "26%")
    check("percent2", NumFmt.format(0.256, "0.00%"), "25.60%")
    check("negative paren", NumFmt.format(-5.0, "#,##0;(#,##0)"), "(5)")
    check("currency", NumFmt.format(12.5, "\"$\"#,##0.00"), "$12.50")
    check("date 14", NumFmt.format(45000.0, "m/d/yyyy"), "3/15/2023")
    check("date 1900", NumFmt.format(1.0, "yyyy-mm-dd"), "1900-01-01")
    check("date 1900-03-01", NumFmt.format(61.0, "yyyy-mm-dd"), "1900-03-01")
    check("1970", NumFmt.format(25569.0, "yyyy-mm-dd"), "1970-01-01")
    check("time", NumFmt.format(0.75, "h:mm AM/PM"), "6:00 PM")
    check("datetime", NumFmt.format(45000.5, "yyyy-mm-dd hh:mm:ss"), "2023-03-15 12:00:00")
    check("month name", NumFmt.format(45000.0, "mmmm d, yyyy"), "March 15, 2023")
    check("weekday", NumFmt.format(45000.0, "dddd"), "Wednesday")
    check("sci", NumFmt.format(12345.0, "0.00E+00"), "1.23E+04")
    check("isDate", NumFmt.isDateCode("dd/mm/yyyy").toString(), "true")
    check("isDate no", NumFmt.isDateCode("#,##0.00").toString(), "false")
    check("xml", MiniXml.unescape("a &lt;b&gt; &#65;&#x42;"), "a <b> AB")
    check("colname", XRef.colName(27), "AB")
    check("ref", XRef.parse("AB12")!!.joinToString(","), "27,11")
    val m = MiniXml("<a x=\"1\"><b/><c>hi &amp; bye</c></a>")
    val seen = ArrayList<String>()
    while (true) { val t = m.next(); if (t == MiniXml.T.EOF) break; seen.add(t.name + ":" + (if (t == MiniXml.T.TEXT) m.text else m.name)) }
    check("xml events", seen.joinToString(" "), "START:a START:b END:b START:c TEXT:hi & bye END:c END:a")
    if (a.isNotEmpty() && a[0].endsWith(".docx", true)) dumpDocx(File(a[0]))
    else if (a.isNotEmpty()) XlsxReader(File(a[0])).use { r ->
        for (s in r.sheets) {
            val sh = r.readSheet(s.index, 20)
            println("== ${s.name} (hidden=${s.hidden}) rows<=${sh.maxRow + 1} cols<=${sh.maxCol + 1} merges=${sh.merges.size} trunc=${sh.truncated}")
            for ((_, row) in sh.rows.entries.take(8)) println(row.joinToString(" | ") { it.ref + "=" + it.text + (if (it.formula != null) " {=" + it.formula + "}" else "") })
        }
    }
}

fun dumpDocx(f: File) = DocxReader(f).use { r ->
    val d = r.read()
    fun dump(bs: List<DBlock>, ind: String) {
        for (b in bs) when (b) {
            is DPara -> println(ind + "P" + (if (b.heading > 0) "[h" + b.heading + "]" else "") + (if (b.label != null) "[" + b.label + "]" else "") + (if (b.align != null) "<" + b.align + ">" else "") + " " +
                b.runs.joinToString("") { (if (it.bold) "*" else "") + (when (it.kind) { DRun.NOTE -> "<fn" + it.text + ">"; DRun.IMAGE -> "<img " + it.image?.part + ">"; else -> it.text }) + (if (it.link != null) "{" + it.link + "}" else "") })
            is DTable -> { println(ind + "TABLE rows=" + b.rows.size + " grid=" + b.grid + " borders=" + b.borders); for (row in b.rows) for (c in row.cells) { println(ind + " cell span=" + c.span); dump(c.blocks, ind + "  ") } }
        }
    }
    dump(d.body, "")
    java.io.File(f.path + ".html").writeText(DocxHtml.render(d, title = f.name))
    println("footnotes=" + d.footnotes.keys + " defSize=" + d.defaultSize + " trunc=" + d.truncated)
    for ((k, v) in d.footnotes) { println("fn$k:"); dump(v, "  ") }
}

/** Part 4 round trip: java -jar ost.jar --edit  (creates /tmp/ost_edit.xlsx, edits it, reads it back, prints OK / FAIL lines). */
fun editTest() {
    val f = java.io.File("/tmp/ost_edit.xlsx"); f.delete()
    XlsxWriter.fromCsv(f, "Name;Qty;Note\nApple;3;\"a;b\"\nPear;007;x\n")
    fun cell(s: Int, r: Int, c: Int) = XlsxReader(f).use { it.readSheet(s).cell(r, c) }
    fun ok(n: String, c: Boolean) = println((if (c) "OK   " else "FAIL ") + n)
    ok("csv text", cell(0, 1, 0)?.text == "Apple"); ok("csv quoted delimiter", cell(0, 1, 2)?.text == "a;b")
    ok("csv leading zero stays text", cell(0, 2, 1)?.text == "007"); ok("csv number", cell(0, 1, 1)?.kind == XCell.Kind.NUMBER)
    XlsxEditor.setCells(f, 0, listOf(CellEdit(1, 1, "10"), CellEdit(3, 1, "=SUM(B2:B3)"), CellEdit(0, 5, "T & <x>"), CellEdit(2, 2, "")))
    ok("edit number", cell(0, 1, 1)?.raw == "10"); ok("formula stored", cell(0, 3, 1)?.formula == "SUM(B2:B3)")
    ok("escaped text", cell(0, 0, 5)?.text == "T & <x>"); ok("cleared", cell(0, 2, 2)?.text.isNullOrEmpty())
    ok("header style kept", (cell(0, 0, 0)?.style ?: 0) == 1)
    XlsxEditor.insertRows(f, 0, 1, 2)
    ok("insert shifts", cell(0, 3, 0)?.text == "Apple" && cell(0, 1, 0) == null)
    XlsxEditor.deleteRows(f, 0, 1, 2)
    ok("delete shifts back", cell(0, 1, 0)?.text == "Apple")
    val i = XlsxEditor.addSheet(f, "Extra"); XlsxEditor.setCell(f, i, 0, 0, "hi")
    ok("add sheet", XlsxReader(f).use { it.sheets.map { s -> s.name } } == listOf("Sheet1", "Extra") && cell(i, 0, 0)?.text == "hi")
    XlsxEditor.renameSheet(f, i, "Renamed")
    ok("rename", XlsxReader(f).use { it.sheets[1].name } == "Renamed")
    XlsxEditor.deleteSheet(f, 0)
    ok("delete sheet", XlsxReader(f).use { it.sheets.map { s -> s.name } } == listOf("Renamed"))
    println("leftovers: " + java.io.File("/tmp").list()!!.filter { it.startsWith("ost_edit") })
}


/** Part 5 round trip: java -jar ost.jar --docx  (creates /tmp/ost_edit.docx from Markdown, edits it, reads it back, prints OK / FAIL lines). */
fun docxTest() {
    val f = java.io.File("/tmp/ost_edit.docx"); f.delete(); DocxEditor.clearUndo(f)
    fun ok(n: String, c: Boolean) = println((if (c) "OK   " else "FAIL ") + n)
    fun txt() = DocxReader(f).use { it.read().plainText() }
    DocxWriter.fromMarkdown(f, "# Title\n\nHello **bold** and _it_ [site](https://example.com/)\n\n- one\n- two\n\n1. a\n2. b\n\n| H1 | H2 |\n|---|---|\n| x | y |\n\n`code` ~~old~~")
    DocxReader(f).use { r ->
        val d = r.read()
        ok("heading level", (d.body.firstOrNull() as? DPara)?.heading == 1)
        ok("text", txt().contains("Hello bold and it site") && txt().contains("one") && txt().contains("y"))
        ok("table", d.body.any { it is DTable })
        ok("link", d.body.filterIsInstance<DPara>().any { p -> p.runs.any { it.link == "https://example.com/" } })
    }
    val n0 = DocxEditor.paragraphCount(f)
    ok("paragraph count", n0 >= 7)
    DocxEditor.setParagraphText(f, 1, "Changed & <ok>\tTab")
    ok("set paragraph", DocxEditor.paragraphText(f, 1) == "Changed & <ok>\tTab" && txt().contains("Changed & <ok>"))
    DocxEditor.insertParagraph(f, 0, "First!")
    ok("insert", DocxEditor.paragraphCount(f) == n0 + 1 && DocxEditor.paragraphText(f, 0) == "First!")
    DocxEditor.deleteParagraph(f, 0)
    ok("delete", DocxEditor.paragraphCount(f) == n0 && DocxEditor.paragraphText(f, 0) == "Title")
    ok("cell text", DocxEditor.cellText(f, 0, 1, 1) == "y")
    DocxEditor.setCellText(f, 0, 1, 1, "Z")
    ok("set cell", DocxEditor.cellText(f, 0, 1, 1) == "Z" && DocxEditor.cellText(f, 0, 0, 0) == "H1")
    ok("replace split runs", DocxEditor.replaceAll(f, "bold and", "BOLD AND") == 1 && txt().contains("BOLD AND") && DocxEditor.replaceAll(f, "nomatch", "x") == 0)
    ok("replace whole word", DocxEditor.replaceAll(f, "one", "uno", false, true) == 1 && txt().contains("uno"))
    ok("undo depth", DocxEditor.undoDepth(f) >= 6)
    val before = txt(); ok("undo", DocxEditor.undo(f) && txt() != before)
    DocxEditor.clearUndo(f); ok("undo cleared", !DocxEditor.undo(f))
    DocxWriter.fromText(f, "a\tb\nsecond"); ok("plain text", txt().contains("second"))
    println("docx test done")
}
