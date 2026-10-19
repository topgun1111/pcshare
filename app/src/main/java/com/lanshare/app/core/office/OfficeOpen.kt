package com.lanshare.app.core.office

import java.io.File

/**
 * Part 6c dispatcher: opens .docx/.xlsx (+m/t variants), .doc, .xls, .odt, .ods by CONTENT (magic bytes), not by extension.
 * `OfficeOpen(file).use { if (it.isSheet) it.readSheet(i) else it.readDoc() }`. No android.* imports.
 * Password-protected files throw IllegalStateException("File is password protected").
 */
class OfficeOpen(private val file: File) : AutoCloseable {
    /** docx, xlsx, doc, xls, odt, ods, pptx / odp (text, pictures, notes) */
    val format: String
    val isSheet: Boolean get() = format == "xlsx" || format == "xls" || format == "ods"
    /** true when Part 4/5 editors can patch the file (docx / xlsx) */
    val editable: Boolean get() = format == "docx" || format == "xlsx"
    val sheets: List<XSheetInfo>
    /** cell styles for [XlsxHtml.render] (xlsx / xls); null for ods (its text is already as saved) */
    val styles: XStyles? get() = xlsx?.styles ?: xls?.styles
    private var pkg: OoxmlPackage? = null
    private var xlsx: XlsxReader? = null
    private var xls: XlsReader? = null
    private var odf: OdfReader? = null

    init {
        val h = ByteArray(8); val n = file.inputStream().use { it.read(h) }
        if (n >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()) {
            val p = OoxmlPackage(file); pkg = p
            try {
                format = when {
                    (p.has("mimetype") || p.has("META-INF/manifest.xml")) && (p.read("mimetype")?.toString(Charsets.US_ASCII).orEmpty().contains("presentation")) -> "odp"   // Part 10b
                    p.has("mimetype") || p.has("META-INF/manifest.xml") -> { val o = OdfReader(file); odf = o; if (o.isSheet) "ods" else "odt" }
                    p.has("xl/workbook.xml") -> "xlsx"
                    p.has("word/document.xml") -> "docx"
                    p.has("ppt/presentation.xml") -> "pptx"
                    else -> throw IllegalArgumentException("Not a supported Office file")
                }
            } catch (e: Throwable) { close(); throw e }
        } else if (OleFile.isOle(file)) {
            val o = OleFile(file)
            format = when {
                o.has("EncryptedPackage") -> throw IllegalStateException("File is password protected")
                o.has("Workbook") || o.has("Book") -> "xls"
                o.has("WordDocument") -> "doc"
                o.has("PowerPoint Document") -> throw IllegalArgumentException("Old .ppt files are not supported (save as .pptx)")
                else -> throw IllegalArgumentException("Unsupported legacy Office file")
            }
        } else throw IllegalArgumentException("Not a supported Office file")
        try {
            sheets = when (format) {
                "xlsx" -> XlsxReader(file).also { xlsx = it }.sheets
                "xls" -> XlsReader(file).also { xls = it }.sheets
                "ods" -> odf!!.sheets
                else -> emptyList()
            }
        } catch (e: Throwable) { close(); throw e }
    }

    fun readSheet(i: Int, maxRows: Int = 5000, maxCols: Int = 200, maxCells: Int = 250_000): XSheet = when (format) {
        "xlsx" -> xlsx!!.readSheet(i, maxRows, maxCols, maxCells)
        "xls" -> xls!!.readSheet(i, maxRows, maxCols, maxCells)
        "ods" -> odf!!.readSheet(i, maxRows, maxCols, maxCells)
        else -> throw IllegalStateException("Not a spreadsheet")
    }

    fun readDoc(maxParagraphs: Int = 20000): DDoc = when (format) {
        "docx" -> DocxReader(file).use { it.read(maxParagraphs) }
        "doc" -> DocReader(file).use { it.read(maxParagraphs) }
        "odt" -> odf!!.read(maxParagraphs)
        "pptx" -> PptxReader(file).use { it.read(maxParagraphs) }
        "odp" -> OdpReader(file).use { it.read(maxParagraphs) }
        else -> throw IllegalStateException("Not a text document")
    }

    /** Bytes of an image part referenced by [DImage.part] (docx / odt / pptx); null for others or when missing. */
    fun image(part: String): ByteArray? = if (format == "docx" || format == "odt" || format == "pptx" || format == "odp") try { pkg?.read(part) } catch (_: Throwable) { null } else null

    override fun close() { try { xlsx?.close() } catch (_: Throwable) {}; try { xls?.close() } catch (_: Throwable) {}; try { odf?.close() } catch (_: Throwable) {}; try { pkg?.close() } catch (_: Throwable) {} }
}
