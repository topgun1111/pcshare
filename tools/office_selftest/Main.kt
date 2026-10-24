// JVM self-test for core/office (no Android needed).  Build & run:
//   kotlinc app/src/main/java/com/lanshare/app/core/office/*.kt tools/office_selftest/Main.kt -include-runtime -d /tmp/ost.jar
//   java -jar /tmp/ost.jar some.xlsx
import com.lanshare.app.core.office.*
import java.io.File

fun check(name: String, got: String, want: String) { println((if (got == want) "ok   " else "FAIL ") + name + "  got='" + got + "' want='" + want + "'") }

fun main(a: Array<String>) {
    if (a.isNotEmpty() && a[0] == "--edit") { editTest(); return }
    if (a.isNotEmpty() && a[0] == "--docx") { docxTest(); return }
    if (a.isNotEmpty() && a[0] == "--fonts") { fontsTest(java.io.File(a.getOrElse(1) { "tools/office_selftest" }), java.io.File(a.getOrElse(2) { "app/src/main/assets/fonts/office" })); return }
    if (a.size > 2 && a[0] == "--print") { printOut(File(a[1]), File(a[2])); return }   // Part 5: print-layout HTML of one document (open it in Chromium)
    if (a.isNotEmpty() && a[0] == "--printtest") { printTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 5
    if (a.isNotEmpty() && a[0] == "--styles") { stylesTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 7
    if (a.isNotEmpty() && a[0] == "--chart") { chartTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 10
    if (a.isNotEmpty() && a[0] == "--cf") { cfTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 9
    if (a.isNotEmpty() && a[0] == "--review") { reviewTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 8
    if (a.isNotEmpty() && a[0] == "--float") { floatTest(File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 6
    if (a.isNotEmpty() && a[0] == "--sections") { sectionsTest(java.io.File(a.getOrElse(1) { "tools/office_selftest" })); return }   // Part 4
    if (a.size > 1 && a[0] == "--export") {   // Part 8: text + CSV of any office file
        val f = java.io.File(a[1]); println("TEXT:"); println(OfficeExport.text(f))
        OfficeOpen(f).use { o -> if (o.isSheet) { println("CSV:"); print(OfficeExport.sheetToCsv(o.readSheet(o.sheets.first { !it.hidden }.index))) } }
        println("contains(Bold)=" + OfficeExport.containsOrFalse(f, "bold") + " contains(zzzz)=" + OfficeExport.containsOrFalse(f, "zzzz"))
        return
    }
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
    if (a.isNotEmpty() && (a[0].endsWith(".odt", true) || a[0].endsWith(".ods", true))) { dumpOdf(File(a[0])); return }
    if (a.isNotEmpty() && (a[0].endsWith(".pptx", true) || a[0].endsWith(".odp", true))) { dumpPptx(File(a[0])); return }   // Part 10b: .odp too   // Part 9: expect Slide 1 / [h2] Opening / Intro & Welcome split / Slide 2 / [h2] First slide / Alpha split
    if (a.isNotEmpty() && a[0].endsWith(".docx", true)) dumpDocx(File(a[0]))
    else if (a.isNotEmpty() && a[0].endsWith(".doc", true)) dumpDoc(File(a[0]))
    else if (a.isNotEmpty() && a[0].endsWith(".xls", true)) XlsReader(File(a[0])).use { r ->
        for (s in r.sheets) {
            val sh = r.readSheet(s.index, 20)
            println("== ${s.name} (hidden=${s.hidden}) rows<=${sh.maxRow + 1} cols<=${sh.maxCol + 1} merges=${sh.merges.size} frozen=${sh.frozenRows}/${sh.frozenCols} trunc=${sh.truncated}")
            for ((_, row) in sh.rows.entries.take(8)) println(row.joinToString(" | ") { it.ref + "=" + it.text })
        }
    }
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
    // run BEFORE setParagraphText below: that call overwrites the "Hello **bold** and" paragraph this test searches across (the old FAIL was a test-order bug, not an editor bug)
    ok("replace split runs", DocxEditor.replaceAll(f, "bold and", "BOLD AND") == 1 && txt().contains("BOLD AND") && DocxEditor.replaceAll(f, "nomatch", "x") == 0)
    DocxEditor.setParagraphText(f, 1, "Changed & <ok>\tTab")
    ok("set paragraph", DocxEditor.paragraphText(f, 1) == "Changed & <ok>\tTab" && txt().contains("Changed & <ok>"))
    DocxEditor.insertParagraph(f, 0, "First!")
    ok("insert", DocxEditor.paragraphCount(f) == n0 + 1 && DocxEditor.paragraphText(f, 0) == "First!")
    DocxEditor.deleteParagraph(f, 0)
    ok("delete", DocxEditor.paragraphCount(f) == n0 && DocxEditor.paragraphText(f, 0) == "Title")
    ok("cell text", DocxEditor.cellText(f, 0, 1, 1) == "y")
    DocxEditor.setCellText(f, 0, 1, 1, "Z")
    ok("set cell", DocxEditor.cellText(f, 0, 1, 1) == "Z" && DocxEditor.cellText(f, 0, 0, 0) == "H1")
    ok("replace whole word", DocxEditor.replaceAll(f, "one", "uno", false, true) == 1 && txt().contains("uno"))
    ok("undo depth", DocxEditor.undoDepth(f) >= 6)
    val before = txt(); ok("undo", DocxEditor.undo(f) && txt() != before)
    DocxEditor.clearUndo(f); ok("undo cleared", !DocxEditor.undo(f))
    DocxWriter.fromText(f, "a\tb\nsecond"); ok("plain text", txt().contains("second"))
    println("docx test done")
}

/** Part 6b: legacy .doc -> DDoc, same dump format as dumpDocx (formatting is not read from .doc: no '*' marks, no headings). */
fun dumpDoc(f: File) = DocReader(f).use { r ->
    val d = r.read()
    fun dump(bs: List<DBlock>, ind: String) {
        for (b in bs) when (b) {
            is DPara -> println(ind + "P " + b.runs.joinToString("") { (when (it.kind) { DRun.TAB -> "<tab>"; DRun.BREAK -> "<br>"; DRun.PAGE -> "<page>"; else -> it.text }) + (if (it.link != null) "{" + it.link + "}" else "") })
            is DTable -> { println(ind + "TABLE rows=" + b.rows.size + " grid=" + b.grid.size); for (row in b.rows) { println(ind + " row cells=" + row.cells.size); for (c in row.cells) dump(c.blocks, ind + "   ") } }
        }
    }
    dump(d.body, "")
    java.io.File(f.path + ".html").writeText(DocxHtml.render(d, title = f.name))
    println("trunc=" + d.truncated)
}

/** Part 6c: .odt / .ods through the OfficeOpen dispatcher; also writes <file>.html */
fun dumpOdf(f: File) = OfficeOpen(f).use { o ->
    println("format=" + o.format)
    if (!o.isSheet) {
        val d = o.readDoc()
        fun dump(bs: List<DBlock>, ind: String) {
            for (b in bs) when (b) {
                is DPara -> println(ind + "P" + (if (b.heading > 0) "[h" + b.heading + "]" else "") + (if (b.label != null) "[" + b.label + "]" else "") + " " + b.runs.joinToString("") { (when (it.kind) { DRun.TAB -> "<tab>"; DRun.BREAK -> "<br>"; DRun.IMAGE -> "<img " + it.image!!.part + ">"; DRun.NOTE -> "<fn" + it.text + ">"; else -> (if (it.bold) "*" else "") + it.text }) + (if (it.link != null) "{" + it.link + "}" else "") })
                is DTable -> { println(ind + "TABLE rows=" + b.rows.size + " grid=" + b.grid.size); for (row in b.rows) { println(ind + " row cells=" + row.cells.size); for (c in row.cells) { println(ind + "  cell span=" + c.span + " vm=" + c.vMerge); dump(c.blocks, ind + "    ") } } }
            }
        }
        dump(d.body, ""); println("footnotes=" + d.footnotes.keys + " trunc=" + d.truncated)
        File(f.path + ".html").writeText(DocxHtml.render(d, title = f.name))
    } else {
        val tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        for (s in o.sheets) { val sh = o.readSheet(s.index); println("sheet " + s.name + " hidden=" + s.hidden + " frozen=" + sh.frozenRows + "/" + sh.frozenCols + " merges=" + sh.merges.size + " maxRow=" + sh.maxRow)
            for ((r, cs) in sh.rows) for (c in cs) println("  " + c.ref + " = " + c.text + " [" + c.kind + "]" + (if (c.formula != null) " {" + c.formula + "}" else "")) }
        File(f.path + ".html").writeText(XlsxHtml.render(o.readSheet(tabs.first().first), tabs, tabs.first().first))
    }
}


/** Part 9: java -jar ost.jar tools/office_selftest/sample.pptx  (slide ORDER comes from presentation.xml, not file names) */
fun dumpPptx(f: File) {
    OfficeOpen(f).use { o ->
        println("format=" + o.format + " sheet=" + o.isSheet)
        for (b in o.readDoc().body) if (b is DTable) println("TABLE grid=" + b.grid + " header=" + b.rows.firstOrNull()?.header + " rows=" + b.rows.joinToString(" / ") { r -> r.cells.joinToString(" | ") { c -> c.span.toString() + ":" + c.blocks.joinToString(" ") { (it as DPara).text } } }) else if (b is DPara) println((if (b.heading > 0) "[h" + b.heading + "] " else "") + b.text + b.runs.filter { it.kind == DRun.IMAGE }.joinToString("") { " [img " + it.image?.part + " " + it.image?.widthEmu + "x" + it.image?.heightEmu + (if (o.image(it.image?.part.orEmpty()) != null) " ok" else " MISSING") + "]" } + (if (b.pageBreakBefore) "   <pagebreak>" else ""))
    }
    println(if (OfficeExport.containsOrFalse(f, "welcome")) "OK   content search" else "FAIL content search")
}


/** Office fidelity Part 3: FontMap + font names read from docx / xlsx / xls.  java -jar ost.jar --fonts [fixtures dir] [fonts dir] */
fun fontsTest(fix: File, fonts: File) {
    fun ok(n: String, c: Boolean) = println((if (c) "OK   " else "FAIL ") + n)
    check("stack Calibri", FontMap.stack("Calibri"), "Carlito,Calibri,Arial,sans-serif")
    check("stack quoted+case", FontMap.stack("  'CAMBRIA' "), "Caladea,Cambria,'Times New Roman',serif")
    check("stack Arial", FontMap.stack("Arial"), "'Liberation Sans',Arial,Helvetica,sans-serif")
    check("stack Times", FontMap.stack("Times New Roman"), "'Liberation Serif','Times New Roman',Times,serif")
    check("stack Courier", FontMap.stack("Courier New"), "'Liberation Mono','Courier New',Courier,monospace")
    check("stack list takes first", FontMap.stack("Calibri, sans-serif"), "Carlito,Calibri,Arial,sans-serif")
    check("stack unknown sans", FontMap.stack("Segoe UI"), "'Segoe UI',sans-serif")
    check("stack unknown serif", FontMap.stack("Book Antiqua"), "'Book Antiqua',serif")
    check("stack unknown mono", FontMap.stack("Consolas"), "Consolas,monospace")
    check("stack null = Calibri", FontMap.stack(null), FontMap.stack("Calibri"))
    check("stack injection", FontMap.stack("Evil\"};x{"), "Evilx,sans-serif")
    check("bundled none", FontMap.bundled("Segoe UI").toString(), "null")
    check("assetPath ok", FontMap.assetPath("Carlito-Bold.ttf").toString(), "fonts/office/Carlito-Bold.ttf")
    check("assetPath traversal", FontMap.assetPath("../x.ttf").toString(), "null")
    val css = FontMap.fontFaceCss()
    ok("font-face count 20", Regex("@font-face").findAll(css).count() == 20)
    ok("no double quotes in css", !css.contains('"'))
    for (n in FontMap.files) ok("asset present+TTF: $n", File(fonts, n).let { it.exists() && it.length() > 50_000 && it.inputStream().use { i -> val b = ByteArray(4); i.read(b); b.contentEquals(byteArrayOf(0, 1, 0, 0)) } })
    for (fam in listOf("Carlito", "Caladea", "LiberationSans")) ok("licence file for $fam", File(fonts, "LICENSES").listFiles()!!.any { it.name.startsWith(fam.take(7)) })
    val dx = File(fix, "sample.docx")
    if (dx.exists()) OfficeOpen(dx).use { o ->
        val d = o.readDoc()
        val fonts1 = LinkedHashSet<String?>(); for (b in d.body) if (b is DPara) for (r in b.runs) fonts1.add(r.font)
        println("docx defaultFont=" + d.defaultFont + " run fonts=" + fonts1)
        val h = DocxHtml.render(d, fontBase = FontMap.FONT_URL)
        ok("docx html has @font-face", h.contains("@font-face{font-family:'Carlito'"))
        ok("docx html body font stack", h.contains("body{font-family:" + FontMap.stack(d.defaultFont) + "}"))
        ok("docx html without fontBase has no @font-face", !DocxHtml.render(d).contains("@font-face"))
    }
    File(fix, "sample_fonts.docx").takeIf { it.exists() }?.let { f -> OfficeOpen(f).use { o ->
        val d = o.readDoc(); val ps = d.body.filterIsInstance<DPara>()
        val fs = ps.map { p -> p.runs.joinToString("|") { it.font ?: "-" } }
        println("sample_fonts.docx defaultFont=" + d.defaultFont + " per paragraph=" + fs)
        check("docx default = theme minor", d.defaultFont.toString(), "Calibri")
        check("docx heading = theme major via style", fs[0], "Cambria")
        check("docx body inherits default", fs[1], "Calibri")
        check("docx explicit fonts", fs[2], "Times New Roman|Courier New")
        check("docx unknown font kept", fs[3], "Segoe UI")
        val h = DocxHtml.render(d, fontBase = FontMap.FONT_URL)
        System.getProperty("fonts.html")?.let { File(it).writeText(h) }   // optional dump for a browser check
        ok("html: body Carlito stack", h.contains("body{font-family:Carlito,Calibri,"))
        ok("html: heading span Caladea", h.contains("font-family:Caladea,Cambria,"))
        ok("html: Times -> Liberation Serif", h.contains("font-family:'Liberation Serif',"))
        ok("html: Courier -> Liberation Mono", h.contains("font-family:'Liberation Mono',"))
        ok("html: Segoe kept first then generic", h.contains("font-family:'Segoe UI',sans-serif"))
        ok("html: default-font runs get no extra font-family", Regex("Body in theme minor font[^<]*").find(h)!!.range.first.let { !h.substring(maxOf(0, it - 40), it).contains("font-family") })
        val rh = DocxHtml.render(d, rd = DocxReading(), fontBase = FontMap.FONT_URL)
        ok("reading mode: runs keep the reading font (no run font-family)", !Regex("<span style=\"font-family").containsMatchIn(rh))
    } }
    File(fix, "sample_fonts.xlsx").takeIf { it.exists() }?.let { f -> OfficeOpen(f).use { o ->
        val st = o.styles!!
        println("sample_fonts.xlsx fonts=" + st.fonts.map { it.name })
        check("xlsx first font", st.fonts[0].name.toString(), "Calibri")
        ok("xlsx has Arial + Cambria", st.fonts.any { it.name == "Arial" } && st.fonts.any { it.name == "Cambria" })
        val tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        val h = XlsxHtml.render(o.readSheet(tabs.first().first), tabs, tabs.first().first, st, FontMap.FONT_URL)
        ok("xlsx html: body Carlito", h.contains("body{font-family:Carlito,Calibri,"))
        ok("xlsx html: Arial cell -> Liberation Sans", h.contains("font-family:'Liberation Sans',"))
        ok("xlsx html: Cambria cell -> Caladea", h.contains("font-family:Caladea,"))
    } }
    for (nm in listOf("sample.xlsx", "sample.xls")) File(fix, nm).takeIf { it.exists() }?.let { f -> OfficeOpen(f).use { o ->
        val st = o.styles
        println(nm + " fonts=" + st?.fonts?.map { it.name })
        if (nm.endsWith(".xls")) ok("$nm style fonts have names", st != null && st.fonts.any { it.name != null })   // sample.xlsx was written without <name>; sample_fonts.xlsx covers xlsx
        val tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        val h = XlsxHtml.render(o.readSheet(tabs.first().first), tabs, tabs.first().first, st, FontMap.FONT_URL)
        ok("$nm html has @font-face + body stack", h.contains("@font-face") && h.contains("body{font-family:"))
    } }
}


/** Part 4: Word page model (sections, page geometry, per-section headers / footers, fields). */
fun sectionsTest(dir: File) {
    val d = DocxReader(File(dir, "sample_sections.docx")).use { it.read() }
    fun t(b: List<DBlock>) = d.plainText(b).trim()
    fun f1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)
    check("sections count", d.sections.size.toString(), "3")
    check("evenOdd setting", d.evenOdd.toString(), "true")
    val a = d.sections[0]; val b = d.sections[1]; val c = d.sections[2]
    check("s0 start", a.startsAtBlockIndex.toString(), "0"); check("s1 start", b.startsAtBlockIndex.toString(), "3"); check("s2 start", c.startsAtBlockIndex.toString(), "5")
    check("s0 A4 size", f1(a.pgW) + "x" + f1(a.pgH), "595.3x841.9"); check("s0 orient", a.orient, "portrait")
    check("s0 margins T/R/B/L", listOf(a.marT, a.marR, a.marB, a.marL).joinToString("/") { f1(it) }, "72.0/56.7/72.0/56.7")
    check("s0 hdr/ftr dist", f1(a.hdrDist) + "/" + f1(a.ftrDist), "35.4/35.4"); check("s0 cols", a.cols.toString(), "1"); check("s0 titlePg", a.titlePg.toString(), "false")
    check("s0 pgNumStart", a.pgNumStart.toString(), "1")
    check("s1 Letter landscape", f1(b.pgW) + "x" + f1(b.pgH) + " " + b.orient, "792.0x612.0 landscape")
    check("s1 margins", listOf(b.marT, b.marR, b.marB, b.marL).joinToString("/") { f1(it) }, "54.0/54.0/54.0/54.0")
    check("s1 cols/gap/sep", b.cols.toString() + "/" + f1(b.colGap) + "/" + b.colSep, "2/36.0/true"); check("s1 titlePg", b.titlePg.toString(), "true")
    check("s1 content width", f1(b.contentW), "684.0")
    check("s2 Letter portrait", f1(c.pgW) + "x" + f1(c.pgH) + " " + c.orient, "612.0x792.0 portrait"); check("s2 cols", c.cols.toString(), "1")
    check("s0 header default", t(a.headerDefault), "Header A4 Sections sample"); check("s0 header even", t(a.headerEven), "Even header")
    check("s0 header first (none)", t(a.headerFirst), "")
    check("s1 header first", t(b.headerFirst), "First page header"); check("s1 header default inherited", t(b.headerDefault), "Header A4 Sections sample")
    check("s1 header even inherited", t(b.headerEven), "Even header")
    check("s0 footer", t(a.footerDefault), "Page 1 of 3"); check("s1 footer", t(b.footerDefault), "Landscape footer 2 / 24.10.2026")
    check("s2 footer inherited", t(c.footerDefault), "Landscape footer 2 / 24.10.2026"); check("s2 header first inherited", t(c.headerFirst), "First page header")
    // fields -> FIELD runs with the cached result
    fun fields(bs: List<DBlock>) = bs.filterIsInstance<DPara>().flatMap { it.runs }.filter { it.kind == DRun.FIELD }.joinToString(",") { it.field + "=" + it.text }
    check("s0 footer fields", fields(a.footerDefault), "PAGE=1,NUMPAGES=3"); check("s1 footer fields", fields(b.footerDefault), "PAGE=2,DATE=24.10.2026")
    check("s0 header fields", fields(a.headerDefault), "TITLE=Sections sample")
    val nf = (a.footerDefault[0] as DPara).runs.first { it.field == "NUMPAGES" }
    check("complex field keeps run style", nf.bold.toString(), "true")
    val seq = (d.body[1] as DPara)
    check("unknown field stays plain text", seq.runs.joinToString("|") { it.kind.toString() + ":" + it.text }, "0:Figure |0:7")
    // page selection rules
    check("headerFor first page of s1", t(b.headerFor(true, 1)), "First page header"); check("headerFor even page", t(b.headerFor(false, 2)), "Even header")
    check("headerFor odd page", t(b.headerFor(false, 3)), "Header A4 Sections sample"); check("s0 first page uses default (no titlePg)", t(a.headerFor(true, 1)), "Header A4 Sections sample")
    check("sectionAt", d.sectionAt(0).pgW.toInt().toString() + "," + d.sectionAt(3).pgW.toInt() + "," + d.sectionAt(99).pgW.toInt(), "595,792,612")
    check("legacy header API", t(d.header), "Header A4 Sections sample"); check("legacy footer API", t(d.footer), "Page 1 of 3")
    check("body text", d.plainText().trim().lines().size.toString(), "6")
    // existing fixtures: a docx without any sectPr info still yields one section
    val s0 = DocxReader(File(dir, "sample.docx")).use { it.read() }
    check("sample.docx has >= 1 section", (s0.sections.size >= 1).toString(), "true")
    println("sample.docx section: " + f1(s0.sections[0].pgW) + "x" + f1(s0.sections[0].pgH) + " cols=" + s0.sections[0].cols + " sections=" + s0.sections.size)
}

/** Part 5: writes the print-layout page of [f] to [out]; images inline, fonts from the viewer's URL (the browser test maps it to assets/fonts/office). */
fun printOut(f: File, out: File) {
    OfficeOpen(f).use { o ->
        val doc = o.readDoc()
        val html = DocxHtml.renderPrint(doc, title = f.name, fontBase = FontMap.FONT_URL, image = { im ->
            val b = try { o.image(im.part) } catch (_: Throwable) { null }
            val mime = when (im.part.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; else -> null }
            if (b == null || mime == null) null else "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(b)
        })
        out.writeText(html)
        println("wrote " + out + " (" + html.length + " chars, " + doc.body.size + " blocks, " + doc.sections.size + " sections)")
    }
}

fun printTest(dir: File) {
    fun cnt(h: String, s: String) = Regex(Regex.escape(s)).findAll(h).count()
    val d = DocxReader(File(dir, "sample_sections.docx")).use { it.read() }
    val h = DocxHtml.renderPrint(d, title = "t", fontBase = FontMap.FONT_URL)
    check("print: flows (3 sections, no continuous)", cnt(h, "class=fl ").toString(), "3")
    check("print: meta json present", (h.contains("<script id=meta type=\"application/json\">{\"f\":[")).toString(), "true")
    check("print: viewport width = widest page (Letter landscape 1056px + 16)", Regex("width=(\\d+),initial-scale").find(h)!!.groupValues[1], "1072")
    check("print: no script injection from text", (!h.contains("<script>alert")).toString(), "true")
    check("print: PAGE field marked", (h.contains("class=fld data-f=\"PAGE\"")).toString(), "true")
    check("print: header templates emitted", (cnt(h, "class=hft") > 0).toString(), "true")
    check("print: render() unchanged (no script)", (!DocxHtml.render(d).contains("<script")).toString(), "true")
    check("print: render() has no print flag leak", (!DocxHtml.render(d).contains("class=fl")).toString(), "true")
    // spacing model
    val p = DocxReader(File(dir, "sample_pages.docx")).use { it.read() }
    val paras = p.body.filterIsInstance<DPara>()
    check("spacing: some paragraph has after = 8pt (Normal of the fixture)", paras.any { it.spAfter == 8.0 }.toString(), "true")
    check("spacing: line multiple 1.08 parsed", paras.any { Math.abs(it.lineMul - 1.08) < 0.01 }.toString(), "true")
    val hp = DocxHtml.renderPrint(p, title = "t")
    check("print: page-break paragraph becomes column break", (hp.contains("break-before:column") || hp.contains("class=cb")).toString(), "true")
}

/** Part 6: floating pictures, text boxes and shapes (fixture sample_float.docx; made by tools/office_selftest/make_sample_float.py). */
fun floatTest(dir: File) {
    val d = DocxReader(File(dir, "sample_float.docx")).use { it.read() }
    val runs = d.body.filterIsInstance<DPara>().flatMap { it.runs }
    val pics = runs.filter { it.kind == DRun.IMAGE }.mapNotNull { it.image }
    val shapes = runs.filter { it.kind == DRun.SHAPE }.mapNotNull { it.shape }
    check("float: body blocks (text box text is NOT flattened into the body)", d.body.size.toString(), "6")
    check("float: pictures", pics.size.toString(), "2")
    check("float: shapes", shapes.size.toString(), "2")
    val p0 = pics[0].anchor; val p1 = pics[1].anchor
    check("float: pic1 wrap / relH / align", (p0?.wrap + "/" + p0?.relH + "/" + p0?.hAlign), "square/margin/right")
    check("float: pic1 size px", (pics[0].widthEmu / 9525).toString() + "x" + (pics[0].heightEmu / 9525).toString(), "200x150")
    check("float: pic2 behind / wrap / relH / relV", (p1?.behind.toString() + "/" + p1?.wrap + "/" + p1?.relH + "/" + p1?.relV), "true/none/page/page")
    check("float: pic2 align H / V", (p1?.hAlign + "/" + p1?.vAlign), "center/center")
    val tb = shapes[0]; val rc = shapes[1]
    check("float: text box fill / line", tb.fill + "/" + tb.line, "FFF2CC/BF9000")
    check("float: text box paragraphs", tb.blocks.size.toString(), "2")
    check("float: text box wrap", tb.anchor?.wrap ?: "inline", "square")
    check("float: rect geom / fill / wrap", rc.geom + "/" + rc.fill + "/" + rc.anchor?.wrap, "roundRect/C00000/tb")
    check("float: plainText keeps text box text", d.plainText().contains("TEXTBOX TITLE").toString(), "true")
    val img = { im: DImage -> "data:image/png;base64,AAAA" }
    val h = DocxHtml.renderPrint(d, image = img, title = "t", fontBase = FontMap.FONT_URL)
    check("float print: marker for the watermark", h.contains("class=ao data-o=1").toString(), "true")
    check("float print: object list", h.contains("<div id=objs><div class=ob data-o=1").toString(), "true")
    check("float print: float right / left", (h.contains("float:right") && h.contains("float:left")).toString(), "true")
    check("float print: text box content", h.contains("TEXTBOX TITLE").toString(), "true")
    check("float print: no <div> inside a <p>", Regex("<p[^>]*>[^<]*<div").containsMatchIn(h).toString(), "false")
    val s = DocxHtml.render(d, image = img)
    check("float simple: behind picture is a zero-height box above its paragraph", s.contains("position:relative;height:0").toString(), "true")
    check("float simple: round rect", s.contains("border-radius").toString(), "true")
}

fun stylesTest(dir: File) {
    // Theme maths (Word swatches: accent1 4472C4 + tint 99 = 8FAADC, + shade BF = 2F5496 / lumMod 75 = 2F5597)
    check("theme: tint 99", Theme.DEFAULT.word("accent1", "99") ?: "-", "8FAADC")
    check("theme: shade BF", Theme.DEFAULT.word("accent1", null, "BF") ?: "-", "2F5496")
    check("theme: names text1 / background1", Theme.DEFAULT.color("text1") + "/" + Theme.DEFAULT.color("background1"), "000000/FFFFFF")
    check("theme: lumMod 75 / lumOff 0 (DrawingML)", Theme.DEFAULT.draw("accent1", null, 75000, null) ?: "-", "2F5597")
    check("theme: excel tint -0.25", Theme.excelTint("4472C4", -0.25), "2F5597")
    check("theme: excel index 4 = accent1", Theme.DEFAULT.byIndex(4) ?: "", "4472C4")
    val d = DocxReader(File(dir, "sample_styles.docx")).use { it.read() }
    val paras = d.body.filterIsInstance<DPara>()
    val t = d.body.filterIsInstance<DTable>().single()
    check("styles: table resolved / any border", t.resolved.toString() + "/" + t.anyBorder, "true/true")
    check("styles: rows x cols", t.rows.size.toString() + "x" + t.grid.size, "6x3")
    val f = { r: Int, c: Int -> t.rows[r].cells[c] }
    check("styles: header fill (themeFill accent1)", f(0, 0).fill ?: "-", "1F6F8B")
    check("styles: row1 band1Horz fill (themeFillTint 33)", f(1, 1).fill ?: "-", "C7E7F3")
    check("styles: row2 no band fill", f(2, 0).fill ?: "-", "-")
    check("styles: row3 band1Horz again", f(3, 0).fill ?: "-", "C7E7F3")
    check("styles: direct cell fill wins", f(2, 2).fill ?: "-", "FFF2CC")
    check("styles: header run bold + white (table style rPr, theme bg1)", (f(0, 1).blocks[0] as DPara).runs[0].let { it.bold.toString() + "/" + it.color }, "true/FFFFFF")
    check("styles: first column bold + accent2", (f(1, 0).blocks[0] as DPara).runs[0].let { it.bold.toString() + "/" + it.color }, "true/E4572E")
    check("styles: body cell run plain", (f(1, 1).blocks[0] as DPara).runs[0].let { it.bold.toString() + "/" + it.color }, "false/null")
    check("styles: last row bold (post-pass)", (f(5, 1).blocks[0] as DPara).runs[0].bold.toString(), "true")
    check("styles: top edge border = table style accent1 sz12", f(0, 0).bT?.let { it.style + "/" + it.sz + "/" + it.color } ?: "-", "single/12/1F6F8B")
    check("styles: inside border between rows (insideH)", f(1, 0).bT?.color ?: "-", "BFBFBF")
    check("styles: bottom edge border", f(5, 0).bB?.color ?: "-", "1F6F8B")
    check("styles: lastRow top border double", f(5, 0).bT?.style ?: "-", "double")
    check("styles: direct tcBorders left red sz 18", f(2, 2).bL?.let { it.color + "/" + it.sz } ?: "-", "C00000/18")
    check("styles: no vertical lines by default", (f(1, 1).bL == null).toString(), "true")
    check("styles: cell margins from the style (top 40 / left 108 twips)", (t.mar?.get(0)).toString() + "/" + t.mar?.get(1), "40/108")
    check("styles: vAlign", f(3, 1).vAlign ?: "-", "center")
    check("styles: table style paragraph spacing (after 0)", (f(1, 1).blocks[0] as DPara).spAfter.toString(), "0.0")
    val h1 = paras.first { it.heading == 1 }
    check("styles: heading colour = accent1 shade BF", h1.runs[0].color ?: "-", "175368")
    check("styles: heading font = theme major", h1.runs[0].font ?: "-", "Cambria")
    check("styles: heading bottom border accent2", h1.bdr?.bottom?.color ?: "-", "E4572E")
    check("styles: run colour accent1 tint 99", paras[1].runs[0].color ?: "-", "58B8DA")
    val toc = paras.first { it.style.equals("toc 1", true) }
    check("styles: TOC tab (right, dot leader, 9350)", toc.tabs.joinToString { it.kind + "/" + it.leader + "/" + it.pos }, "right/dot/9350")
    val hd = paras.first { it.style.equals("header", true) }
    check("styles: header tabs", hd.tabs.joinToString { it.kind + "/" + it.pos }, "center/4680, right/9360")
    val sh = paras.last()
    check("styles: paragraph shading + borders", (sh.fill ?: "-") + "/" + (sh.bdr?.top?.color ?: "-") + "/" + (sh.bdr?.bottom != null), "EDF2F4/E4572E/true")
    val img = { _: DImage -> null as String? }
    val h = DocxHtml.renderPrint(d, image = img, title = "t", fontBase = FontMap.FONT_URL)
    check("styles html: per-cell borders, no grid class", (h.contains("border-top:2px solid #1F6F8B") && !h.contains("<table class=b")).toString(), "true")
    check("styles html: left red border + shading", h.contains("background:#FFF2CC;").toString() + "/" + h.contains("border-left:3px solid #C00000"), "true/true")
    check("styles html: vertical-align middle", h.contains("vertical-align:middle").toString(), "true")
    check("styles html: leader (flex + dotted)", (h.contains("display:flex") && h.contains("border-bottom:1px dotted currentColor")).toString(), "true")
    check("styles html: three-part tab line (grid)", h.contains("grid-template-columns:1fr auto 1fr").toString(), "true")
    check("styles html: default tab stop box", h.contains("display:inline-block;min-width:").toString(), "true")
    check("styles html: paragraph shading", h.contains("background:#EDF2F4").toString(), "true")
    check("styles html: no <div> inside a <p>", Regex("<p[^>]*>[^<]*<div").containsMatchIn(h).toString(), "false")
    val r = DocxHtml.render(d, image = img, rd = DocxReading())
    check("styles reading: legacy grid class, no inline cell borders", (r.contains("<table class=b") && !r.contains("border-top:2px solid #1F6F8B")).toString(), "true")
    check("styles reading: tabs stay simple", (!r.contains("display:flex") && r.contains("width:2em")).toString(), "true")
    val sm = DocxHtml.render(d, image = img)
    check("styles simple: table + tabs styled", (sm.contains("border-top:2px solid #1F6F8B") && sm.contains("display:flex")).toString(), "true")
}

/** Part 8: comments, tracked changes (Show / Final / Original), bookmarks + internal links. Fixture: make_sample_review.py -> sample_review.docx. */
fun reviewTest(dir: File) {
    val d = DocxReader(File(dir, "sample_review.docx")).use { it.read() }
    val paras = d.body.filterIsInstance<DPara>()
    check("review: hasRev", d.hasRev.toString(), "true")
    check("review: comments ids", d.comments.keys.joinToString(","), "0,1")
    check("review: comment 0 author / text", d.comments["0"]!!.author + " | " + d.comments["0"]!!.text, "Carol | Is \"jumps\" the right verb here?")
    check("review: comment 1 author / initials / date", d.comments["1"]!!.author + "/" + d.comments["1"]!!.initials + "/" + d.comments["1"]!!.date.take(10), "Dave/D/2026-10-21")
    check("review: paragraph count", paras.size.toString(), "8")
    val p = paras[2]
    check("review: final text of the edited sentence (deleted text excluded)", p.text, "The quick brown fox jumps quickly across the yard.")
    check("review: inserted run", p.runs.filter { it.rev == 1 }.joinToString("|") { it.text + "/" + it.revBy }, " quickly/Alice")
    check("review: deleted run", p.runs.filter { it.rev == 2 }.joinToString("|") { it.text + "/" + it.revBy }, " lazily/Bob")
    check("review: commented runs", p.runs.filter { it.kind == DRun.TEXT && it.cmt != null }.joinToString("|") { it.text + "/" + it.cmt }, "jumps/0|yard/1")
    check("review: comment marker runs", p.runs.filter { it.kind == DRun.CMT }.joinToString(",") { it.text }, "0,1")
    check("review: bookmarks", paras.filter { it.bookmarks.isNotEmpty() }.joinToString(",") { it.bookmarks.joinToString("+") }, "_Toc100001,_Toc100002")
    check("review: w:hyperlink anchor link", paras[1].runs.firstOrNull { it.kind == DRun.TEXT }?.link ?: "-", "#_Toc100002")
    check("review: complex HYPERLINK \\l field link", paras[6].runs.firstOrNull { it.kind == DRun.TEXT }?.link ?: "-", "#_Toc100001")
    // Show markup
    val show = DocxHtml.render(d)
    check("show: insertion underlined in author colour", (show.contains("text-decoration:underline;") && show.contains(" quickly</span>")).toString(), "true")
    check("show: deletion struck through", (show.contains("text-decoration:line-through;") && show.contains(" lazily</span>")).toString(), "true")
    check("show: two comment markers [1] [2]", (show.contains("href=\"lsoffice://comment/0\">1</a>") && show.contains("href=\"lsoffice://comment/1\">2</a>")).toString(), "true")
    check("show: bookmark anchors + TOC link", (show.contains("<a id=\"_Toc100001\"></a>") && show.contains("<a id=\"_Toc100002\"></a>") && show.contains("href=\"#_Toc100002\"")).toString(), "true")
    check("show: added and removed paragraphs both present", (show.contains("This whole paragraph was added.") && show.contains("This whole paragraph was removed.")).toString(), "true")
    // Final
    val fin = DocxHtml.render(d, markup = DocxHtml.MARK_FINAL)
    check("final: no deleted text, no deleted paragraph, no markers", (!fin.contains("lazily") && !fin.contains("was removed") && !fin.contains("lsoffice://comment")).toString(), "true")
    check("final: inserted text kept, plain", (fin.contains("quickly") && fin.contains("This whole paragraph was added.") && !fin.contains("line-through")).toString(), "true")
    check("final: bookmark of a kept heading still there", fin.contains("<a id=\"_Toc100002\"></a>").toString(), "true")
    // Original
    val org = DocxHtml.render(d, markup = DocxHtml.MARK_ORIGINAL)
    check("original: deleted text back, inserted text and paragraph gone", (org.contains("lazily") && org.contains("was removed") && !org.contains("quickly") && !org.contains("was added")).toString(), "true")
    // Print layout takes the same switch
    val pr = DocxHtml.renderPrint(d, markup = DocxHtml.MARK_FINAL)
    check("print final: same rule", (!pr.contains("lazily") && pr.contains("quickly")).toString(), "true")
    // plain text for search / export = final text
    check("review: plainText has no deleted text", (!d.plainText().contains("lazily") && d.plainText().contains("jumps quickly across")).toString(), "true")
}


/** Part 9: Excel theme colours, borders, conditional formatting, notes, gridlines, column widths. Fixture: sample_cf.xlsx (make_sample_cf.py). */
fun cfTest(dir: File) {
    fun ok(name: String, c: Boolean) = check(name, c.toString(), "true")
    val f = File(dir, "sample_cf.xlsx")
    OfficeOpen(f).use { o ->
        val st = o.styles!!
        check("styles: borders / dxfs read", st.borders.size.toString() + "/" + st.dxfs.size, "3/3")
        check("font 1 colour = theme 0 (lt1)", st.fonts[1].color.toString(), "#FFFFFF")
        check("fill 2 = theme 4 (accent1 of the file's own theme)", st.fills[2].toString(), "#1F6F8B")
        val tint = st.fills[3]!!
        ok("fill 3 = accent2 with tint 0.6 (lighter than E4572E, same hue family)", tint != "#E4572E" && tint.substring(1, 3).toInt(16) > 0xE4 && tint.substring(3, 5).toInt(16) > 0x57)
        val dark = st.fonts[2].color!!
        ok("font 2 = accent2 with tint -0.25 (darker)", dark.substring(1, 3).toInt(16) < 0xE4)
        check("border 1 bottom = medium theme 1 (000000)", st.borders[1].bottom!!.style + " " + st.borders[1].bottom!!.color, "medium #000000")
        check("border 2 left colour = indexed 10 (red)", st.borders[2].left!!.color.toString(), "#FF0000")
        check("dxf 0 fill + font colour", st.dxfs[0].fill + " " + st.dxfs[0].color, "#FFC7CE #9C0006")
        check("dxf 1 bold, dxf 2 italic", "" + st.dxfs[1].bold + " " + st.dxfs[2].italic, "true true")

        val sh = o.readSheet(0)
        ok("showGrid true on Sales, false on NoGrid", sh.showGrid && !o.readSheet(1).showGrid)
        check("frozen rows", sh.frozenRows.toString(), "1")
        check("rules read", sh.cf.size.toString(), "5")
        check("notes", sh.notes.joinToString("|") { it.ref + "=" + it.author + ":" + it.text.replace('\n', '/') }, "B2=Tester:Tester:/Check this value|D5=Tester:Above target")
        ok("file widths flag + col A width", sh.fileWidths && Math.abs((sh.colWidths[0] ?: 0.0) - 14.7109375) < 1e-6)
        ok("blank styled cells kept (F1 border + fill, F2 fill)", sh.cell(0, 5)?.kind == XCell.Kind.EMPTY && sh.cell(1, 5)?.kind == XCell.Kind.EMPTY)
        val cf = XlsxCf.evaluate(sh, st)
        fun at(r: Int, c: Int) = cf[XlsxCf.key(r, c)]
        check("colour scale min (B2 = 12)", at(1, 1)?.fill.toString(), "#f8696b")
        check("colour scale max (B6 = 91)", at(5, 1)?.fill.toString(), "#63be7b")
        ok("colour scale middle is between (B4 = 58 -> not an end colour)", at(3, 1)?.fill.let { it != null && it != "#f8696b" && it != "#63be7b" })
        check("data bar min (C6 = 50) 10 %", String.format("%.2f", at(5, 2)?.bar ?: -1.0), "0.10")
        check("data bar max (C5 = 300) 90 %", String.format("%.2f", at(4, 2)?.bar ?: -1.0), "0.90")
        check("data bar colour", at(4, 2)?.barColor.toString(), "#638EC6")
        check("cellIs > 50: D3 D5 D7", listOf(2, 4, 6).joinToString { at(it, 3)?.fill.toString() }, "#FFC7CE, #FFC7CE, #FFC7CE")
        ok("cellIs: D2 (20) untouched", at(1, 3) == null)
        ok("top10 rank 2: E5 + E7 (both 12) bold + green, E3 not", at(4, 4)?.bold == true && at(6, 4)?.bold == true && at(2, 4) == null && at(4, 4)?.fill == "#C6EFCE")
        ok("expression B2>=C2: only A6 (91 >= 50) italic + purple", at(5, 0)?.italic == true && at(5, 0)?.color == "#7030A0" && (1..8).filter { it != 5 }.all { at(it, 0) == null })

        val tabs = o.sheets.filter { !it.hidden }.map { it.index to it.name }
        val h = XlsxHtml.render(sh, tabs, 0, st, FontMap.FONT_URL)
        ok("html: header theme fill + white bold + medium bottom rule", h.contains("background:#1f6f8b") && h.contains("color:#ffffff") && h.contains("border-bottom:2px solid #000000"))
        ok("html: colour scale + cellIs fill", h.contains("background:#f8696b") && h.contains("background:#ffc7ce"))
        ok("html: data bar gradient 10 % and 90 %", h.contains("linear-gradient(90deg,#638ec6 10.0%,transparent 10.0%)") && h.contains("linear-gradient(90deg,#638ec6 90.0%,transparent 90.0%)"))
        ok("html: note corner marks + notes list", h.contains("lsoffice://note/B2") && h.contains("lsoffice://note/D5") && h.contains("Check this value") && h.contains("id=\"n_B2\""))
        ok("html: blank filled cell F2 is drawn", h.contains("background:#f4b6a2") || Regex("<td[^>]*style=\"[^\"]*background:#[0-9a-f]{6}[^\"]*\"></td>").containsMatchIn(h))
        ok("html: column A = 103 px (stored width x 7), B = 75 px", h.contains("<col style=\"width:103px\"><col style=\"width:75px\">"))
        ok("html: grid on (no .ng) for Sales", !h.contains("<table class=ng"))
        val h2 = XlsxHtml.render(o.readSheet(1), tabs, 1, st, FontMap.FONT_URL)
        ok("html: NoGrid sheet -> class ng + explicit thin red box", h2.contains("<table class=ng") && h2.contains("border-top:1px solid #ff0000") && h2.contains("border-left:1px solid #ff0000"))
        ok("html: grid override on / off", !XlsxHtml.render(o.readSheet(1), tabs, 1, st, null, true).contains("<table class=ng") && XlsxHtml.render(sh, tabs, 0, st, null, false).contains("<table class=ng"))
        println("column px: " + Regex("<col style=\"width:\\d+px\">").findAll(h).joinToString(" ") { it.value.substringAfter("width:").substringBefore("\"") })
        File(f.path + ".html").writeText(h); File(dir, "sample_cf_nogrid.html").writeText(h2)
    }
    // expression / rule evaluator on a synthetic sheet (A1..A6 = 1 2 2 5 8 9, text in B)
    val info = XSheetInfo(0, "T", "", false, false, null)
    val rows = java.util.TreeMap<Int, List<XCell>>()
    val vals = listOf(1, 2, 2, 5, 8, 9); val txt = listOf("apple pie", "Banana", "apple pie", "cherry", "", "apple")
    for (i in vals.indices) rows[i] = listOf(XCell(i, 0, vals[i].toString(), vals[i].toString(), XCell.Kind.NUMBER), XCell(i, 1, txt[i], txt[i], if (txt[i].isEmpty()) XCell.Kind.EMPTY else XCell.Kind.TEXT))
    fun rule(type: String, forms: List<String> = emptyList(), op: String? = null, rank: Int = 10, pct: Boolean = false, bottom: Boolean = false, text: String? = null, above: Boolean = true, col: Int = 0) =
        XCfRule(listOf(intArrayOf(col, 0, col, 5)), type, 1, 0, op, rank, pct, bottom, text, above, false, false, forms, emptyList(), emptyList(), 10, 90)
    val dxs = XStyles(emptyList(), emptyList(), emptyList(), emptyMap(), BooleanArray(0), emptyList(), listOf(XDxf(null, "#ff0000", null, null, null, null)))
    fun run(r: XCfRule): String {
        val sh = XSheet(info, rows, emptyList(), emptyMap(), emptySet(), emptySet(), 5, 1, 0, 0, false, cf = listOf(r))
        val m = XlsxCf.evaluate(sh, dxs)
        return (0..5).filter { m[XlsxCf.key(it, r.ranges[0][0])]?.fill != null }.joinToString(",") { (it + 1).toString() }   // 1-based rows that were hit
    }
    check("cf MOD(ROW(),2)=0 -> rows 2 4 6", run(rule("expression", listOf("MOD(ROW(),2)=0"))), "2,4,6")
    check("cf between 2 and 5", run(rule("cellIs", listOf("2", "5"), "between")), "2,3,4")
    check("cf notBetween 2 and 5", run(rule("cellIs", listOf("2", "5"), "notBetween")), "1,5,6")
    check("cf equal to a cell ref (A4 = 5)", run(rule("cellIs", listOf("\$A\$4"), "equal")), "4")
    check("cf expression with AND / OR / arithmetic", run(rule("expression", listOf("AND(A1>1,OR(A1<3,A1*2=16))"))), "2,3,5")
    check("cf aboveAverage (avg 4.5)", run(rule("aboveAverage")), "4,5,6")
    check("cf belowAverage", run(rule("aboveAverage", above = false)), "1,2,3")
    check("cf top 20 % (1 of 6 -> floor 1)", run(rule("top10", rank = 20, pct = true)), "6")
    check("cf bottom 2 (2 and 2 tie with 1)", run(rule("top10", rank = 2, bottom = true)), "1,2,3")
    check("cf duplicateValues (numbers)", run(rule("duplicateValues")), "2,3")
    check("cf uniqueValues (numbers)", run(rule("uniqueValues")), "1,4,5,6")
    check("cf duplicateValues (text, case-insensitive)", run(rule("duplicateValues", col = 1)), "1,3")
    check("cf containsText 'APPLE'", run(rule("containsText", text = "APPLE", col = 1)), "1,3,6")
    check("cf beginsWith 'ch'", run(rule("beginsWith", text = "ch", col = 1)), "4")
    check("cf containsBlanks", run(rule("containsBlanks", col = 1)), "5")
    check("cf ISBLANK relative (B of the same row)", run(rule("expression", listOf("ISBLANK(B1)"))), "5")
    check("cf ISBLANK absolute (B5 for every row)", run(rule("expression", listOf("ISBLANK(\$B\$5)"))), "1,2,3,4,5,6")
    check("cf unsupported formula (range / function) is ignored, not fatal", run(rule("expression", listOf("SUM(A1:A3)>3"))), "")
    check("cf iconSet is ignored", run(rule("iconSet")), "")
    println("cf evaluator: done")
}


// ---------------------------------------------------------------------------------------------------------------- Part 10: Excel charts
fun chartTest(dir: File) {
    fun ok(name: String, c: Boolean) = check(name, c.toString(), "true")
    fun count(s: String, sub: String): Int { var n = 0; var i = s.indexOf(sub); while (i >= 0) { n++; i = s.indexOf(sub, i + sub.length) }; return n }
    val f = File(dir, "sample_chart.xlsx")
    XlsxReader(f).use { r ->
        check("3 sheets", r.sheets.size.toString(), "3")
        // ---- sheet Data: column chart + line chart
        val sh = r.readSheet(0)
        check("Data: 2 charts", sh.charts.size.toString(), "2")
        val c1 = sh.charts[0]; val a1 = c1.anchor
        check("chart1 anchor twoCell E2:M17", "${a1.kind} ${a1.c1} ${a1.r1} ${a1.c2} ${a1.r2}", "0 4 1 13 16")
        val ch1 = c1.chart
        check("chart1 title / legend", "${ch1.title} / ${ch1.legend}", "Sales by month / b")
        check("chart1 axis titles", "${ch1.cat.title} / ${ch1.value.title}", "Month / Units")
        ok("chart1 value gridlines", ch1.value.grid)
        val p1 = ch1.plots[0]
        check("chart1 plot: bar, clustered, gap 80, vertical", "${p1.type} ${p1.grouping} ${p1.gapWidth} ${p1.horizontal}", "bar clustered 80 false")
        check("chart1 series names", p1.series.joinToString(",") { it.name }, "North,South")
        check("chart1 series colours (accent1 of the file's theme, rgb)", p1.series.joinToString(",") { it.color }, "#1F6F8B,#E4572E")
        check("chart1 categories", p1.series[0].cats.joinToString(","), "Jan,Feb,Mar,Apr,May,Jun")
        check("chart1 values", p1.series[1].vals.joinToString(","), "80.0,95.0,130.0,110.0,60.0,140.0")
        val c2 = sh.charts[1]; val a2 = c2.anchor
        check("chart2 anchor oneCell E19 480x300 px", "${a2.kind} ${a2.c1} ${a2.r1} ${a2.cx / 9525} ${a2.cy / 9525}", "1 4 18 480 300")
        val p2 = c2.chart.plots[0]
        check("chart2 line, markers on, legend r", "${p2.type} ${p2.markers} ${c2.chart.legend}", "line true r")
        check("chart2 markers circle / none, smooth false / true", "${p2.series[0].marker} ${p2.series[1].marker} ${p2.series[0].smooth} ${p2.series[1].smooth}", "circle none false true")
        // ---- sheet Bars: no cached values, the reader takes them from the cells
        val sb = r.readSheet(1); val c3 = sb.charts.single().chart; val p3 = c3.plots[0]
        check("Bars: horizontal stacked, overlap 100", "${p3.horizontal} ${p3.grouping} ${p3.overlap}", "true stacked 100")
        check("Bars: values come from the cells (no cache in the XML)", p3.series[0].vals.joinToString(",") + " | " + p3.series[1].cats.joinToString(","), "120.0,150.0,90.0,180.0,210.0,170.0 | Jan,Feb,Mar,Apr,May,Jun")
        check("Bars: series names from cells", p3.series.joinToString(",") { it.name }, "North,South")
        check("Bars: axis min / max / major, legend t", "${c3.value.min} ${c3.value.max} ${c3.value.major} ${c3.legend}", "0.0 400.0 100.0 t")
        // ---- sheet Pie: kept as an unsupported plot
        val pie = r.readSheet(2).charts.single().chart
        check("Pie: kinds", pie.kinds.joinToString(","), "pie")
        // ---- scale
        val sc = ChartSvg.scale(0.0, 210.0, XChartAxis.NONE, 240.0, false)
        check("scale 0..210 -> 0..250 step 50", "${sc.min} ${sc.max} ${sc.step}", "0.0 250.0 50.0")
        ok("scale 95..100 does not start at zero", ChartSvg.scale(95.0, 100.0, XChartAxis.NONE, 240.0, false).min > 0)
        check("scale 80..100 starts at zero", ChartSvg.scale(80.0, 100.0, XChartAxis.NONE, 240.0, false).min.toString(), "0.0")
        val sn = ChartSvg.scale(-30.0, 90.0, XChartAxis.NONE, 240.0, false)
        ok("scale -30..90 includes both with head-room", sn.min <= -30.0 && sn.max >= 90.0 && sn.min % sn.step == 0.0)
        check("scale percent", ChartSvg.scale(0.0, 1.0, XChartAxis.NONE, 300.0, true).let { "${it.min} ${it.max} ${it.step}" }, "0.0 1.0 0.1")
        // ---- SVG
        val svg1 = ChartSvg.render(ch1, 480, 300)
        ok("svg1 well-formed envelope", svg1.startsWith("<svg ") && svg1.endsWith("</svg>"))
        check("svg1 rects = background + 12 bars + 2 legend swatches", count(svg1, "<rect ").toString(), "15")
        ok("svg1 has title, axis titles, legend names", svg1.contains(">Sales by month<") && svg1.contains(">Month<") && svg1.contains(">Units<") && svg1.contains(">North<") && svg1.contains(">South<"))
        ok("svg1 bar colours", svg1.contains("fill=\"#1F6F8B\"") && svg1.contains("fill=\"#E4572E\""))
        ok("svg1 value tick 250 and category Jun", svg1.contains(">250<") && svg1.contains(">Jun<"))
        val svg2 = ChartSvg.render(c2.chart, 480, 300)
        check("svg2: 2 lines, 6 + 1 markers (North + legend)", count(svg2, "<path ").toString() + " " + count(svg2, "<circle "), "2 7")
        ok("svg2 smooth series uses curves", svg2.contains(" C"))
        val svg3 = ChartSvg.render(c3, 480, 300)
        check("svg3 stacked horizontal: 12 bars + bg + 2 swatches", count(svg3, "<rect ").toString(), "15")
        ok("svg3 axis 0..400 labels", svg3.contains(">400<") && svg3.contains(">300<"))
        val svgP = ChartSvg.render(pie, 300, 200)
        ok("pie: placeholder, no bars", svgP.contains("Pie chart: not drawn yet") && !svgP.contains("<path"))
        // ---- locale: a Turkish default locale must not put commas into numbers
        val old = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
        val svgTr = ChartSvg.render(ch1, 480, 300)
        java.util.Locale.setDefault(old)
        ok("same SVG under tr-TR", svgTr == svg1)
        // ---- HTML placement
        val tabs = r.sheets.map { Pair(it.index, it.name) }
        val html = XlsxHtml.render(sh, tabs, 0, r.styles)
        check("html: 2 chart boxes", count(html, "class=ch ").toString(), "2")
        ok("chart1 box at col E (28 + 3 x 75 + 64 = 317 px), row 2 (22 + 22 = 44 px)", html.contains("class=ch style=\"left:317px;top:44px\""))
        ok("chart1 size 576 x 330 (9 default columns, 15 rows)", html.contains("width=\"576\" height=\"330\""))
        ok("chart2 box at row 19 (22 + 18 x 22 = 418 px), 480 x 300", html.contains("top:418px") && html.contains("width=\"480\" height=\"300\""))
        ok("scroll box tall enough for the lowest chart (418 + 300 + 8 = 726 px)", html.contains("<div class=w style=\"min-height:726px\">"))
        for (i in 0..2) { val h = XlsxHtml.render(r.readSheet(i), tabs, i, r.styles); File(System.getProperty("java.io.tmpdir"), "ost_chart_$i.html").writeText(h) }
        println("HTML written to " + System.getProperty("java.io.tmpdir") + "/ost_chart_0..2.html")
        // grid-only sheet without charts is unchanged
        ok("sheet without charts has no chart box", !XlsxHtml.render(XlsxReader(File(dir, "sample_cf.xlsx")).use { it.readSheet(0) }, tabs, 0, null).contains("class=ch"))
    }
    // ---- hostile / broken input never throws
    check("truncated chart XML: no exception, nothing to draw", (ChartParser.parseChart(MiniXml("<c:chartSpace><c:chart><c:plotArea"), Theme.DEFAULT, null)?.plots?.size ?: 0).toString(), "0")
    check("empty plot area -> chart without plots", ChartParser.parseChart(MiniXml("<chartSpace><chart><plotArea/></chart></chartSpace>"), Theme.DEFAULT, null)!!.plots.size.toString(), "0")
    check("render of a chart without plots", ChartSvg.render(ChartParser.parseChart(MiniXml("<chartSpace><chart><plotArea/></chart></chartSpace>"), Theme.DEFAULT, null)!!, 200, 100).contains("No data").toString(), "true")
}
