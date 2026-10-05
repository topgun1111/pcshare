package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Office file VIEWING (Tier 2): phone-only reader, no PC and no external app.
 * `toHtml(file, ext)` turns an office file (zip + XML) into one self-contained HTML page for a WebView (JS off).
 * Step 1 = docx, step 4 = odt/ods/odp (Odf), step 2 = xlsx/xlsm (sheets as tables, 2000 rows x 50 cols, dates, shared strings), step 3 = pptx (slides as sections: title, text/bullets, tables, pictures, notes) (headings, bold/italic/underline/strike/sup/sub, alignment, bullet + numbered lists, tables with
 * colspan, embedded png/jpg/gif/bmp downscaled to data URIs). xlsx / pptx / odf follow in later steps
 * (see OFFICE_VIEW_HANDOVER.md). Not compiled / not device-tested.
 * Parser note: `Xml.newPullParser()` is not namespace aware, so names keep their prefix (`w:p`, `a:blip`); Word always writes these prefixes.
 */
object OfficeText {
    /** Extensions this object can render without the PC. */
    val EXTS = setOf("docx", "xlsx", "xlsm", "pptx", "pptm", "odt", "ods", "odp", "ott", "ots", "otp")

    fun canRead(name: String) = name.substringAfterLast('.', "").lowercase() in EXTS

    fun toHtml(file: File, ext: String): String = when (ext.lowercase()) {
        "docx" -> Docx.toHtml(file)
        "xlsx", "xlsm" -> Xlsx.toHtml(file)
        "pptx", "pptm" -> Pptx.toHtml(file)
        "odt", "ods", "odp", "ott", "ots", "otp" -> Odf.toHtml(file, ext.lowercase())
        else -> throw IOException("no phone-only reader for .$ext yet")
    }

    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;")
            '\u0000', '\u0001', '\u0002', '\u0003', '\u0004', '\u0005', '\u0006', '\u0007', '\u0008', '\u000B', '\u000C', '\u000E', '\u000F' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    internal const val HEAD = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta name=\"color-scheme\" content=\"light dark\"><style>" +
        ":root{color-scheme:light dark}" +
        "body{font:16px/1.55 sans-serif;margin:0 auto;padding:12px 14px 40px;max-width:860px;word-wrap:break-word;overflow-wrap:anywhere}" +
        "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.1em 0 .4em}h1{font-size:1.7em}h2{font-size:1.4em}h3{font-size:1.2em}h4,h5,h6{font-size:1.05em}" +
        "p{margin:.5em 0}ul,ol{margin:.4em 0;padding-left:1.6em}li{margin:.15em 0}img{max-width:100%;height:auto}" +
        ".tw{overflow-x:auto;margin:.6em 0}table{border-collapse:collapse}td{border:1px solid #8888;padding:4px 8px;vertical-align:top}" +
        ".title{font-size:2em;font-weight:700;margin:.6em 0 .3em}.sub{font-size:1.2em;opacity:.75}.ph{opacity:.6;font-style:italic}.note{opacity:.7;font-style:italic;margin-top:1em}" +
        ".rn{font-size:.75em;font-weight:400;opacity:.55;text-align:right;padding:4px 6px;border:1px solid #8888;white-space:nowrap}.r{text-align:right}.tabs a{margin-right:.9em}" +
        ".sl{border:1px solid #8888;border-radius:8px;padding:2px 14px 10px;margin:14px 0}.sn{font-size:.75em;opacity:.55;margin:.5em 0 0}.nt{border-top:1px dashed #8888;margin-top:.9em;padding-top:.3em;font-size:.9em;opacity:.8}" +
        "</style></head><body>"

    internal const val IMG_PH = "<span class=\"ph\">[image]</span>"
    private const val MAX_IMG = 20L shl 20         // one embedded picture, bytes

    /** One zip entry as an `<img>` data URI (downscaled when big). Unsupported formats (emf/wmf/...), a missing entry or any failure give a placeholder. Shared by docx + pptx. */
    internal fun imgTag(z: ZipFile, name: String?): String {
        val ph = IMG_PH
        try {
            if (name == null) return ph
            val e = z.getEntry(name) ?: return ph
            if (e.size > MAX_IMG) return ph
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; else -> return ph }
            val bytes = z.getInputStream(e).use { it.readBytes() }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val side = maxOf(o.outWidth, o.outHeight)
            if (side <= 0) return ph
            if (bytes.size <= 250_000 && side <= 1600) return "<img src=\"data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}\">"
            var ss = 1
            while (side / (ss * 2) >= 1024) ss *= 2
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ss }) ?: return ph
            val flat = if (bm.hasAlpha()) Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.RGB_565).also { b ->
                Canvas(b).apply { drawColor(-1); drawBitmap(bm, 0f, 0f, null) }; bm.recycle()
            } else bm
            val out = ByteArrayOutputStream()
            flat.compress(Bitmap.CompressFormat.JPEG, 80, out)
            flat.recycle()
            return "<img src=\"data:image/jpeg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}\">"
        } catch (_: Throwable) { return ph }   // includes OutOfMemoryError for huge pictures
    }


    // ------------------------------------------------------------------ docx
    private object Docx {
        private const val MAX_HTML = 6_000_000        // chars; beyond this the page is cut with a note
        private val HEADING = Regex("^(heading|überschrift|başlık)\\s*(\\d)$")

        fun toHtml(f: File): String {
            val z = try { ZipFile(f) } catch (e: Exception) { throw IOException("not a valid .docx file") }
            z.use {
                if (z.getEntry("word/document.xml") == null) throw IOException("not a valid .docx file (word/document.xml missing)")
                val rels = rels(z)
                val styles = readStyles(z)
                val nums = Nums.load(z)
                val sb = StringBuilder(64 * 1024)
                sb.append(OfficeText.HEAD)
                body(z, rels, styles, nums, sb)
                sb.append("</body></html>")
                return sb.toString()
            }
        }

        private fun parser(z: ZipFile, name: String): XmlPullParser? {
            val e = z.getEntry(name) ?: return null
            return Xml.newPullParser().also { it.setInput(z.getInputStream(e), null) }
        }

        /** relationship id -> zip entry name (only image targets are needed). */
        private fun rels(z: ZipFile): Map<String, String> {
            val m = HashMap<String, String>()
            val p = parser(z, "word/_rels/document.xml.rels") ?: return m
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id") ?: continue
                    var t = p.getAttributeValue(null, "Target") ?: continue
                    if (p.getAttributeValue(null, "TargetMode") == "External") continue
                    t = if (t.startsWith("/")) t.substring(1) else "word/$t"
                    try { t = java.net.URI(t.replace(" ", "%20")).normalize().path } catch (_: Exception) {}
                    m[id] = t
                }
            }
            return m
        }

        /** Style table: styleId -> lower-case name ("heading 1", "title", ...; ids can be localised, names are not) and
         *  lists defined by a style (`List Bullet` / `List Number`: numPr lives in styles.xml, not in the paragraph). */
        private class Styles(val name: Map<String, String>, val num: Map<String, String>, val lvl: Map<String, Int>)

        private fun readStyles(z: ZipFile): Styles {
            val nm = HashMap<String, String>(); val num = HashMap<String, String>(); val lvl = HashMap<String, Int>()
            val p = parser(z, "word/styles.xml") ?: return Styles(nm, num, lvl)
            var cur: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "w:style" -> cur = p.getAttributeValue(null, "w:styleId")
                        "w:name" -> cur?.let { id -> p.getAttributeValue(null, "w:val")?.let { nm[id] = it.lowercase() } }
                        "w:numId" -> cur?.let { id -> p.getAttributeValue(null, "w:val")?.let { num[id] = it } }
                        "w:ilvl" -> cur?.let { id -> lvl[id] = (p.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0).coerceIn(0, 8) }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "w:style") cur = null
            }
            return Styles(nm, num, lvl)
        }

        /** numbering.xml: is a list level ordered (numbered) or a bullet. */
        private class Nums(val numToAbs: Map<String, String>, val fmt: Map<String, String>) {
            fun ordered(numId: String, lvl: Int): Boolean {
                val f = fmt["${numToAbs[numId] ?: return false}:$lvl"] ?: return false
                return f != "bullet" && f != "none"
            }
            companion object {
                fun load(z: ZipFile): Nums {
                    val n2a = HashMap<String, String>(); val fmt = HashMap<String, String>()
                    val p = parser(z, "word/numbering.xml") ?: return Nums(n2a, fmt)
                    var abs: String? = null; var num: String? = null; var lvl = 0
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG) when (p.name) {
                            "w:abstractNum" -> abs = p.getAttributeValue(null, "w:abstractNumId")
                            "w:lvl" -> lvl = p.getAttributeValue(null, "w:ilvl")?.toIntOrNull() ?: 0
                            "w:numFmt" -> abs?.let { a -> p.getAttributeValue(null, "w:val")?.let { v -> fmt["$a:$lvl"] = v } }
                            "w:num" -> num = p.getAttributeValue(null, "w:numId")
                            "w:abstractNumId" -> num?.let { n -> p.getAttributeValue(null, "w:val")?.let { v -> n2a[n] = v } }
                        } else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                            "w:abstractNum" -> abs = null
                            "w:num" -> num = null
                        }
                    }
                    return Nums(n2a, fmt)
                }
            }
        }

        private fun on(p: XmlPullParser): Boolean {   // <w:b/> = on, <w:b w:val="0|false|off|none"/> = off
            val v = p.getAttributeValue(null, "w:val") ?: return true
            return v != "0" && v != "false" && v != "off" && v != "none"
        }

        private fun body(z: ZipFile, rels: Map<String, String>, styles: Styles, nums: Nums, sb: StringBuilder) {
            val p = parser(z, "word/document.xml")!!
            // paragraph state
            var inP = false; var inPPr = false; var inR = false
            var para = StringBuilder(); var pStyle = ""; var numId = ""; var ilvl = 0; var jc = ""
            // run state
            var bold = false; var ital = false; var und = false; var strike = false; var sup = false; var sub = false
            // skipping (text boxes would nest paragraphs; mc:Fallback duplicates mc:Choice)
            var skipName: String? = null; var skipDepth = 0
            // lists: open list tags per level, counters for numbered lists
            val lists = ArrayList<String>()
            val counters = HashMap<String, Int>()
            val tdPos = ArrayList<Int>()
            var tblDepth = 0
            var cut = false

            fun closeLists() { while (lists.isNotEmpty()) sb.append("</").append(lists.removeAt(lists.size - 1)).append('>') }

            fun endPara() {
                val txt = para.toString()
                val align = when (jc) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> "" }
                val st = if (align.isEmpty()) "" else " style=\"text-align:$align\""
                val sn = styles.name[pStyle] ?: pStyle.lowercase()
                val hl = HEADING.find(sn)?.groupValues?.get(2)?.toIntOrNull()
                var nid = numId; var lv = ilvl
                if (nid.isEmpty()) styles.num[pStyle]?.let { nid = it; lv = styles.lvl[pStyle] ?: 0 }   // list given by the paragraph style
                if (nid.isNotEmpty() && nid != "0") {
                    val ord = nums.ordered(nid, lv)
                    val tag = if (ord) "ol" else "ul"
                    val key = "$nid:$lv"
                    counters.keys.filter { it.startsWith("$nid:") && (it.substringAfter(':').toIntOrNull() ?: 0) > lv }.forEach { counters.remove(it) }
                    val n = (counters[key] ?: 0) + 1; counters[key] = n
                    while (lists.size > lv + 1) sb.append("</").append(lists.removeAt(lists.size - 1)).append('>')
                    if (lists.size == lv + 1 && lists[lv] != tag) sb.append("</").append(lists.removeAt(lv)).append('>')
                    while (lists.size < lv + 1) {
                        val last = lists.size == lv
                        sb.append('<').append(tag)
                        if (ord && last && n > 1) sb.append(" start=\"").append(n).append('"')
                        sb.append('>'); lists.add(tag)
                    }
                    sb.append("<li").append(st).append('>').append(txt).append("</li>")
                } else {
                    closeLists()
                    when {
                        hl != null -> { val h = hl.coerceIn(1, 6); sb.append("<h$h$st>").append(txt).append("</h$h>") }
                        sn == "title" -> sb.append("<p class=\"title\"$st>").append(txt).append("</p>")
                        sn == "subtitle" -> sb.append("<p class=\"sub\"$st>").append(txt).append("</p>")
                        txt.isEmpty() -> sb.append("<p>&nbsp;</p>")
                        else -> sb.append("<p$st>").append(txt).append("</p>")
                    }
                }
            }

            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (sb.length > MAX_HTML) { cut = true; break }
                val nm = if (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) p.name else ""
                if (skipName != null) {   // inside a skipped subtree: only track its depth
                    if (ev == XmlPullParser.START_TAG && nm == skipName) skipDepth++
                    else if (ev == XmlPullParser.END_TAG && nm == skipName && --skipDepth == 0) skipName = null
                    continue
                }
                if (ev == XmlPullParser.START_TAG) {
                    when (nm) {
                        "w:txbxContent", "mc:Fallback" -> { skipName = nm; skipDepth = 1 }
                        "w:tbl" -> { closeLists(); tblDepth++; sb.append("<div class=\"tw\"><table>") }
                        "w:tr" -> sb.append("<tr>")
                        "w:tc" -> { tdPos.add(sb.length); sb.append("<td>") }
                        "w:gridSpan" -> if (tdPos.isNotEmpty() && !inP) {
                            val n = p.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 1
                            if (n > 1) sb.insert(tdPos[tdPos.size - 1] + 3, " colspan=\"$n\"")
                        }
                        "w:p" -> { inP = true; para = StringBuilder(); pStyle = ""; numId = ""; ilvl = 0; jc = "" }
                        "w:pPr" -> inPPr = inP
                        "w:pStyle" -> if (inPPr) pStyle = p.getAttributeValue(null, "w:val") ?: ""
                        "w:numId" -> if (inPPr) numId = p.getAttributeValue(null, "w:val") ?: ""
                        "w:ilvl" -> if (inPPr) ilvl = (p.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0).coerceIn(0, 8)
                        "w:jc" -> if (inPPr) jc = p.getAttributeValue(null, "w:val") ?: ""
                        "w:r" -> { inR = inP; bold = false; ital = false; und = false; strike = false; sup = false; sub = false }
                        "w:b" -> if (inR && !inPPr) bold = on(p)
                        "w:i" -> if (inR && !inPPr) ital = on(p)
                        "w:u" -> if (inR && !inPPr) und = on(p)
                        "w:strike" -> if (inR && !inPPr) strike = on(p)
                        "w:vertAlign" -> if (inR && !inPPr) { val v = p.getAttributeValue(null, "w:val"); sup = v == "superscript"; sub = v == "subscript" }
                        "w:t" -> {
                            val t = p.nextText()
                            if (inP && t.isNotEmpty()) {
                                var s = OfficeText.esc(t)
                                if (sup) s = "<sup>$s</sup>"; if (sub) s = "<sub>$s</sub>"
                                if (strike) s = "<s>$s</s>"; if (und) s = "<u>$s</u>"
                                if (ital) s = "<i>$s</i>"; if (bold) s = "<b>$s</b>"
                                para.append(s)
                            }
                        }
                        "w:tab" -> if (inR && !inPPr) para.append("&emsp;")
                        "w:br" -> if (inR) para.append(if (p.getAttributeValue(null, "w:type") == "page") "<hr>" else "<br>")
                        "w:cr" -> if (inR) para.append("<br>")
                        "w:noBreakHyphen" -> if (inR) para.append('-')
                        "a:blip" -> if (inP) para.append(img(z, rels, p.getAttributeValue(null, "r:embed")))
                        "v:imagedata" -> if (inP) para.append(img(z, rels, p.getAttributeValue(null, "r:id")))
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (nm) {
                        "w:pPr" -> inPPr = false
                        "w:r" -> inR = false
                        "w:p" -> { if (inP) endPara(); inP = false }
                        "w:tc" -> { closeLists(); sb.append("</td>"); if (tdPos.isNotEmpty()) tdPos.removeAt(tdPos.size - 1) }
                        "w:tr" -> sb.append("</tr>")
                        "w:tbl" -> { closeLists(); sb.append("</table></div>"); tblDepth-- }
                    }
                }
            }
            if (inP && !cut) endPara()
            closeLists()
            while (tblDepth-- > 0) sb.append("</table></div>")
            if (cut) sb.append("<p class=\"note\">… (document cut: too long to show here)</p>")
        }

        /** One embedded picture (relationship id -> zip entry) via the shared `imgTag`. */
        private fun img(z: ZipFile, rels: Map<String, String>, id: String?): String =
            OfficeText.imgTag(z, rels[id ?: return OfficeText.IMG_PH])

    }

    // ------------------------------------------------------------------ xlsx
    private object Xlsx {
        private const val MAX_HTML = 6_000_000   // chars; beyond this the page is cut with a note
        private const val MAX_ROWS = 2000        // rendered rows per sheet
        private const val MAX_COLS = 50          // columns per sheet (A..AX)

        fun toHtml(f: File): String {
            val z = try { ZipFile(f) } catch (e: Exception) { throw IOException("not a valid .xlsx file") }
            z.use {
                if (z.getEntry("xl/workbook.xml") == null) throw IOException("not a valid .xlsx file (xl/workbook.xml missing)")
                val sheets = sheets(z)
                if (sheets.isEmpty()) throw IOException("no visible worksheets in this file")
                val strings = sharedStrings(z)
                val dateXf = dateStyles(z)
                val sb = StringBuilder(64 * 1024)
                sb.append(OfficeText.HEAD)
                if (sheets.size > 1) {
                    sb.append("<p class=\"tabs\">")
                    sheets.forEachIndexed { i, s -> sb.append("<a href=\"#s${i + 1}\">").append(OfficeText.esc(s.first)).append("</a> ") }
                    sb.append("</p>")
                }
                var cut = false
                for ((i, s) in sheets.withIndex()) {
                    if (sb.length > MAX_HTML) { cut = true; break }
                    sb.append("<h2 id=\"s${i + 1}\">").append(OfficeText.esc(s.first)).append("</h2>")
                    sheet(z, s.second, strings, dateXf, sb)
                }
                if (cut) sb.append("<p class=\"note\">… (remaining sheets not shown: file too large to show here)</p>")
                sb.append("</body></html>")
                return sb.toString()
            }
        }

        private fun parser(z: ZipFile, name: String): XmlPullParser? {
            val e = z.getEntry(name) ?: return null
            return Xml.newPullParser().also { it.setInput(z.getInputStream(e), null) }
        }

        /** Visible worksheets in workbook order: (display name, zip entry). Hidden sheets and chartsheets are skipped. */
        private fun sheets(z: ZipFile): List<Pair<String, String>> {
            val rels = HashMap<String, String>()
            parser(z, "xl/_rels/workbook.xml.rels")?.let { p ->
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                        val id = p.getAttributeValue(null, "Id") ?: continue
                        var t = p.getAttributeValue(null, "Target") ?: continue
                        if (p.getAttributeValue(null, "TargetMode") == "External") continue
                        t = if (t.startsWith("/")) t.substring(1) else "xl/$t"
                        try { t = java.net.URI(t.replace(" ", "%20")).normalize().path } catch (_: Exception) {}
                        rels[id] = t
                    }
                }
            }
            val out = ArrayList<Pair<String, String>>()
            val p = parser(z, "xl/workbook.xml")!!
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    val st = p.getAttributeValue(null, "state")
                    if (st == "hidden" || st == "veryHidden") continue
                    val id = p.getAttributeValue(null, "r:id") ?: continue
                    val path = rels[id] ?: continue
                    if (!path.contains("/worksheets/") || z.getEntry(path) == null) continue
                    out.add((p.getAttributeValue(null, "name") ?: "Sheet ${out.size + 1}") to path)
                }
            }
            if (out.isEmpty()) {   // no usable workbook rels: fall back to the file names
                z.entries().asSequence().map { it.name }
                    .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.sorted()
                    .forEachIndexed { i, n -> out.add("Sheet ${i + 1}" to n) }
            }
            return out
        }

        /** xl/sharedStrings.xml: `<si>` = plain `<t>` or rich runs `<r><t>`; phonetic hints (`<rPh>`) are ignored. */
        private fun sharedStrings(z: ZipFile): ArrayList<String> {
            val out = ArrayList<String>()
            val p = parser(z, "xl/sharedStrings.xml") ?: return out
            var cur: StringBuilder? = null
            var ph = false
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "si" -> cur = StringBuilder()
                        "rPh" -> ph = true
                        "t" -> { val t = p.nextText(); if (!ph) cur?.append(t) }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "rPh" -> ph = false
                        "si" -> { out.add(cur?.toString() ?: ""); cur = null }
                    }
                }
            }
            return out
        }

        /** Indexes (into cellXfs, = the `s=` attribute of a cell) whose number format is a date/time. */
        private fun dateStyles(z: ZipFile): Set<Int> {
            val res = HashSet<Int>()
            val p = parser(z, "xl/styles.xml") ?: return res
            val custom = HashMap<Int, Boolean>()
            val xfs = ArrayList<Int>()
            var inXfs = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "numFmt" -> {
                            val id = p.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                            val code = p.getAttributeValue(null, "formatCode")
                            if (id != null && code != null) custom[id] = isDateFmt(code)
                        }
                        "cellXfs" -> inXfs = true
                        "xf" -> if (inXfs) xfs.add(p.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") inXfs = false
            }
            xfs.forEachIndexed { i, id -> if (id in 14..22 || id in 45..47 || custom[id] == true) res.add(i) }
            return res
        }

        private val FMT_NOISE = Regex("\"[^\"]*\"|\\[[^\\]]*]|\\\\.|_.|\\*.")
        private val FMT_DATE = Regex("[dmyhs]", RegexOption.IGNORE_CASE)
        private fun isDateFmt(code: String) = FMT_DATE.containsMatchIn(FMT_NOISE.replace(code, ""))

        /** Excel serial (1900 system) -> "yyyy-MM-dd" or "yyyy-MM-dd HH:mm[:ss]"; null = show the number as is. */
        private fun dateStr(v: String): String? {
            val d = v.toDoubleOrNull() ?: return null
            if (d < 61 || d > 2_958_465) return null   // before 1900-03-01 Excel has its fake leap day: leave as number
            var days = Math.floor(d).toLong()
            var secs = Math.round((d - days) * 86400.0)
            if (secs >= 86400) { days += 1; secs -= 86400 }
            val z = days - 25569 + 719468              // days since 0000-03-01 (civil-from-days)
            val era = Math.floorDiv(z, 146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val dd = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
            var s = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", y, m, dd)
            if (secs > 0) {
                s += String.format(java.util.Locale.ROOT, " %02d:%02d", secs / 3600, secs % 3600 / 60)
                if (secs % 60 != 0L) s += String.format(java.util.Locale.ROOT, ":%02d", secs % 60)
            }
            return s
        }

        /** 0.1+0.2 is stored as 0.30000000000000004: show 15 significant digits like Excel does. */
        private fun num(v: String): String {
            if (v.length <= 15 || !(v.contains('.') || v.contains('E') || v.contains('e'))) return v
            return try {
                val bd = java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros()
                if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
            } catch (_: Exception) { v }
        }

        private fun colIndex(ref: String?): Int {   // "AB12" -> 27 (zero based), -1 when there is no letter prefix
            if (ref == null) return -1
            var n = 0; var any = false
            for (c in ref) {
                val u = if (c in 'a'..'z') c - 32 else c
                if (u in 'A'..'Z') { n = n * 26 + (u - 'A' + 1); any = true; if (n > 100_000) break } else break
            }
            return if (any) n - 1 else -1
        }

        private fun sheet(z: ZipFile, path: String, strings: List<String>, dateXf: Set<Int>, sb: StringBuilder) {
            val p = parser(z, path) ?: return
            val start = sb.length
            sb.append("<div class=\"tw\"><table>")
            var rows = 0; var rowCut = false; var colCut = false
            var lastRow = 0; var nextCol = 0
            var t = ""; var s = 0; var col = 0; var v: String? = null
            val inl = StringBuilder(); var inIs = false; var ph = false
            loop@ while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "row" -> {
                            if (rows >= MAX_ROWS || sb.length > MAX_HTML) { rowCut = true; break@loop }
                            val r = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (lastRow + 1)
                            val gap = r - lastRow - 1
                            if (lastRow > 0) for (k in 1..minOf(gap, 2)) { sb.append("<tr><th class=\"rn\">${lastRow + k}</th></tr>"); rows++ }
                            sb.append("<tr><th class=\"rn\">$r</th>")
                            lastRow = r; nextCol = 0; rows++
                        }
                        "c" -> {
                            col = colIndex(p.getAttributeValue(null, "r")).let { if (it < 0) nextCol else it }
                            t = p.getAttributeValue(null, "t") ?: ""
                            s = p.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                            v = null; inl.setLength(0)
                        }
                        "v" -> v = p.nextText()
                        "is" -> inIs = true
                        "rPh" -> ph = true
                        "t" -> { val x = p.nextText(); if (inIs && !ph) inl.append(x) }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "rPh" -> ph = false
                        "is" -> inIs = false
                        "row" -> sb.append("</tr>")
                        "c" -> {
                            if (col >= MAX_COLS) { colCut = true } else {
                                while (nextCol < col) { sb.append("<td></td>"); nextCol++ }
                                val raw = v
                                var right = false
                                val text = when (t) {
                                    "s" -> strings.getOrNull(raw?.toIntOrNull() ?: -1) ?: ""
                                    "inlineStr" -> inl.toString()
                                    "str", "e", "d" -> raw ?: ""
                                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                                    else -> if (raw == null) "" else {
                                        right = true
                                        if (s in dateXf) (dateStr(raw) ?: num(raw)) else num(raw)
                                    }
                                }
                                sb.append(if (right) "<td class=\"r\">" else "<td>")
                                    .append(OfficeText.esc(text).replace("\n", "<br>")).append("</td>")
                                nextCol = col + 1
                            }
                        }
                    }
                }
            }
            if (rows == 0) { sb.setLength(start); sb.append("<p class=\"ph\">(empty sheet)</p>"); return }
            sb.append("</table></div>")
            if (rowCut) sb.append("<p class=\"note\">… only the first $MAX_ROWS rows are shown</p>")
            if (colCut) sb.append("<p class=\"note\">… only the first $MAX_COLS columns are shown</p>")
        }
    }

    // ------------------------------------------------------------------ pptx
    private object Pptx {
        private const val MAX_HTML = 6_000_000   // chars; beyond this the page is cut with a note
        private const val MAX_SLIDES = 300

        fun toHtml(f: File): String {
            val z = try { ZipFile(f) } catch (e: Exception) { throw IOException("not a valid .pptx file") }
            z.use {
                if (z.getEntry("ppt/presentation.xml") == null) throw IOException("not a valid .pptx file (ppt/presentation.xml missing)")
                val slides = slides(z)
                if (slides.isEmpty()) throw IOException("no slides in this file")
                val sb = StringBuilder(64 * 1024)
                sb.append(OfficeText.HEAD)
                var cut = false
                for ((i, path) in slides.withIndex()) {
                    if (sb.length > MAX_HTML || i >= MAX_SLIDES) { cut = true; break }
                    slide(z, path, i + 1, sb)
                }
                if (cut) sb.append("<p class=\"note\">… (remaining slides not shown: file too large to show here)</p>")
                sb.append("</body></html>")
                return sb.toString()
            }
        }

        private fun parser(z: ZipFile, name: String): XmlPullParser? {
            val e = z.getEntry(name) ?: return null
            return Xml.newPullParser().also { it.setInput(z.getInputStream(e), null) }
        }

        private fun bool(v: String?) = v == "1" || v == "true"

        /** Relationship id -> zip entry (internal targets only). `base` = folder of the part that owns the rels file ("ppt/slides/"). */
        private fun rels(z: ZipFile, relsPath: String, base: String): Map<String, String> {
            val m = HashMap<String, String>()
            val p = parser(z, relsPath) ?: return m
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.getAttributeValue(null, "Id") ?: continue
                    var t = p.getAttributeValue(null, "Target") ?: continue
                    if (p.getAttributeValue(null, "TargetMode") == "External") continue
                    t = if (t.startsWith("/")) t.substring(1) else base + t
                    try { t = java.net.URI(t.replace(" ", "%20")).normalize().path } catch (_: Exception) {}
                    m[id] = t
                }
            }
            return m
        }

        /** Slide parts in presentation order (`p:sldIdLst`); fallback: ppt/slides/slideN.xml sorted by N. */
        private fun slides(z: ZipFile): List<String> {
            val rels = rels(z, "ppt/_rels/presentation.xml.rels", "ppt/")
            val out = ArrayList<String>()
            val p = parser(z, "ppt/presentation.xml")
            if (p != null) while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "p:sldId") {
                    val t = rels[p.getAttributeValue(null, "r:id") ?: continue] ?: continue
                    if (z.getEntry(t) != null) out.add(t)
                }
            }
            if (out.isEmpty()) {
                val re = Regex("ppt/slides/slide\\d+\\.xml")
                val names = ArrayList<String>()
                val en = z.entries()
                while (en.hasMoreElements()) { val n = en.nextElement().name; if (re.matches(n)) names.add(n) }
                names.sortBy { it.filter { c -> c.isDigit() }.toInt() }
                out.addAll(names)
            }
            return out
        }

        /** Speaker notes: text of the body placeholder of a notesSlide, paragraphs joined with <br>. */
        private fun notes(z: ZipFile, path: String): String {
            val p = parser(z, path) ?: return ""
            val out = StringBuilder()
            val para = StringBuilder()
            var inSp = false
            var ph: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "p:sp" -> { inSp = true; ph = null }
                        "p:ph" -> { if (inSp) ph = p.getAttributeValue(null, "type") ?: "body" }
                        "a:t" -> { val t = p.nextText(); if (inSp && ph == "body") para.append(OfficeText.esc(t)) }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "a:p" -> {
                            if (para.isNotBlank()) { if (out.isNotEmpty()) out.append("<br>"); out.append(para) }
                            para.setLength(0)
                        }
                        "p:sp" -> { inSp = false; ph = null }
                    }
                }
            }
            return out.toString()
        }

        /** One slide as `<section class="sl">`: title (h2), text boxes (placeholders of type body/obj become bullet lists), tables, pictures, notes. */
        private fun slide(z: ZipFile, path: String, n: Int, sb: StringBuilder) {
            val dir = path.substringBeforeLast('/', "") + "/"
            val rels = rels(z, dir + "_rels/" + path.substringAfterLast('/') + ".rels", dir)
            val body = StringBuilder()
            var title = ""
            var hidden = false
            // shape state
            var inSp = false
            var ph: String? = null                 // placeholder type of the current shape (null = plain shape)
            val shape = StringBuilder()
            val shapePlain = StringBuilder()
            var list = ""                          // list tag currently open in `shape`
            // paragraph + run state
            val para = StringBuilder()
            val paraPlain = StringBuilder()
            var paraText = false
            var bullet = ""
            var lvl = 0
            var inLst = false                      // inside <a:lstStyle>: its bullet definitions are not paragraphs
            var rb = false; var ri = false; var ru = false; var rs = false; var rpos = 0
            // table + picture state
            var inTbl = false
            val tbl = StringBuilder()
            val cell = StringBuilder()
            var cellOpen = "<td>"
            var skipCell = false
            var inPic = false
            // depth bookkeeping: `mc:Fallback` duplicates `mc:Choice` content and is skipped
            var depth = 0
            var skipAt = -1

            fun endPara() {
                val txt = para.toString()
                val has = paraText
                val plain = paraPlain.toString()
                val b = bullet
                val l = lvl
                para.setLength(0); paraPlain.setLength(0); paraText = false; bullet = ""; lvl = 0
                if (!has) return
                if (inTbl) { if (cell.isNotEmpty()) cell.append("<br>"); cell.append(txt); return }
                shapePlain.append(plain).append(' ')
                val kind = when (b) {
                    "ul", "ol" -> b
                    "none" -> ""
                    else -> if (ph == "body" || ph == "obj") "ul" else ""
                }
                if (kind != list) {
                    if (list.isNotEmpty()) shape.append("</").append(list).append('>')
                    if (kind.isNotEmpty()) shape.append('<').append(kind).append('>')
                    list = kind
                }
                if (kind.isEmpty()) shape.append("<p>").append(txt).append("</p>")
                else shape.append(if (l > 0) "<li style=\"margin-left:${l}em\">" else "<li>").append(txt).append("</li>")
            }

            val p = parser(z, path) ?: return
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                val ev = p.eventType
                if (ev == XmlPullParser.START_TAG) {
                    depth++
                    if (skipAt >= 0) continue
                    when (p.name) {
                        "mc:Fallback" -> skipAt = depth
                        "p:sld" -> hidden = p.getAttributeValue(null, "show") == "0"
                        "p:sp" -> { inSp = true; ph = null; shape.setLength(0); shapePlain.setLength(0); list = "" }
                        "p:ph" -> { if (inSp) ph = p.getAttributeValue(null, "type") ?: "body" }
                        "a:lstStyle" -> inLst = true
                        "a:pPr" -> lvl = p.getAttributeValue(null, "lvl")?.toIntOrNull() ?: 0
                        "a:buChar" -> { if (!inLst) bullet = "ul" }
                        "a:buAutoNum" -> { if (!inLst) bullet = "ol" }
                        "a:buNone" -> { if (!inLst) bullet = "none" }
                        "a:r", "a:fld" -> { rb = false; ri = false; ru = false; rs = false; rpos = 0 }
                        "a:rPr" -> {
                            rb = bool(p.getAttributeValue(null, "b"))
                            ri = bool(p.getAttributeValue(null, "i"))
                            val u = p.getAttributeValue(null, "u")
                            ru = u != null && u != "none"
                            val st = p.getAttributeValue(null, "strike")
                            rs = st != null && st != "noStrike"
                            rpos = p.getAttributeValue(null, "baseline")?.toIntOrNull() ?: 0
                        }
                        "a:t" -> {
                            val t = p.nextText()          // consumes the end tag too
                            depth--
                            if (t.isNotEmpty()) {
                                var h = OfficeText.esc(t)
                                if (rpos > 0) h = "<sup>$h</sup>" else if (rpos < 0) h = "<sub>$h</sub>"
                                if (rs) h = "<s>$h</s>"
                                if (ru) h = "<u>$h</u>"
                                if (ri) h = "<i>$h</i>"
                                if (rb) h = "<b>$h</b>"
                                para.append(h); paraPlain.append(t)
                                if (t.isNotBlank()) paraText = true
                            }
                        }
                        "a:br" -> para.append("<br>")
                        "a:tbl" -> { inTbl = true; tbl.setLength(0); tbl.append("<div class=\"tw\"><table>") }
                        "a:tr" -> { if (inTbl) tbl.append("<tr>") }
                        "a:tc" -> if (inTbl) {
                            cell.setLength(0)
                            skipCell = bool(p.getAttributeValue(null, "hMerge")) || bool(p.getAttributeValue(null, "vMerge"))
                            val gs = p.getAttributeValue(null, "gridSpan")?.toIntOrNull() ?: 1
                            val rsp = p.getAttributeValue(null, "rowSpan")?.toIntOrNull() ?: 1
                            cellOpen = "<td" + (if (gs > 1) " colspan=\"$gs\"" else "") + (if (rsp > 1) " rowspan=\"$rsp\"" else "") + ">"
                        }
                        "p:pic" -> inPic = true
                        "a:blip" -> { if (inPic) body.append(OfficeText.imgTag(z, rels[p.getAttributeValue(null, "r:embed") ?: ""])) }
                        "c:chart" -> body.append("<p class=\"ph\">[chart]</p>")
                        "dgm:relIds" -> body.append("<p class=\"ph\">[diagram]</p>")
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (skipAt >= 0) { if (depth == skipAt) skipAt = -1; depth--; continue }
                    when (p.name) {
                        "a:p" -> endPara()
                        "a:lstStyle" -> inLst = false
                        "a:tc" -> { if (inTbl && !skipCell) tbl.append(cellOpen).append(cell).append("</td>") }
                        "a:tr" -> { if (inTbl) tbl.append("</tr>") }
                        "a:tbl" -> { if (inTbl) { tbl.append("</table></div>"); body.append(tbl.toString()) }; inTbl = false }
                        "p:pic" -> inPic = false
                        "p:sp" -> {
                            if (list.isNotEmpty()) { shape.append("</").append(list).append('>'); list = "" }
                            when (ph) {
                                "title", "ctrTitle" -> { if (title.isEmpty()) title = OfficeText.esc(shapePlain.toString().trim()) }
                                "subTitle" -> { if (shape.isNotEmpty()) body.append("<div class=\"sub\">").append(shape.toString()).append("</div>") }
                                "dt", "ftr", "sldNum", "hdr" -> {}
                                else -> body.append(shape.toString())
                            }
                            shape.setLength(0); inSp = false; ph = null
                        }
                    }
                    depth--
                }
            }
            sb.append("<section class=\"sl\"><p class=\"sn\">Slide ").append(n)
            if (hidden) sb.append(" (hidden)")
            sb.append("</p>")
            if (title.isNotEmpty()) sb.append("<h2>").append(title).append("</h2>")
            sb.append(body.toString())
            val nt = rels.values.firstOrNull { it.contains("/notesSlides/") }?.let { notes(z, it) } ?: ""
            if (nt.isNotEmpty()) sb.append("<div class=\"nt\"><b>Notes</b><br>").append(nt).append("</div>")
            sb.append("</section>")
        }
    }

    // ------------------------------------------------------------------ odt / ods / odp
    private object Odf {
        private const val MAX_HTML = 6_000_000   // chars; beyond this the page is cut with a note
        private const val MAX_ROWS = 2000        // rendered rows per table / sheet
        private const val MAX_COLS = 50
        private const val MAX_SLIDES = 300

        // elements whose content is never shown (footnotes, comments, deleted text, style/declaration blocks, hidden table cells)
        private val SKIP = setOf("text:note", "office:annotation", "text:tracked-changes", "svg:title", "svg:desc",
            "table:covered-table-cell", "office:forms", "office:scripts", "office:font-face-decls", "office:automatic-styles",
            "office:styles", "office:master-styles", "text:sequence-decls", "text:variable-decls", "text:user-field-decls")
        private val DROP_CLS = setOf("header", "footer", "date-time", "page-number")   // odp placeholders

        private class Styles {
            val flags = HashMap<String, Int>()        // style name -> bit mask: 1 bold, 2 italic, 4 underline, 8 strike, 16 sup, 32 sub
            val parent = HashMap<String, String>()
            val numbered = HashSet<String>()          // "listStyle|level" that is a numbered list level
            fun flagsOf(name: String?): Int {
                var n = name
                var f = 0
                var d = 0
                while (n != null && d < 6) { f = f or (flags[n] ?: 0); n = parent[n]; d++ }
                return f
            }
        }
        private class Para(val open: String, val close: String, val flags: Int) { val sb = StringBuilder() }
        private class Cell(val colspan: Int, val rowspan: Int, val rep: Int, val right: Boolean, val prev: StringBuilder) { val buf = StringBuilder() }
        private class Tbl { val sb = StringBuilder(); var rows = 0; var row: ArrayList<Pair<Boolean, String>>? = null; var rowRep = 1; var blank = 0; var trunc = false }

        fun toHtml(f: File, ext: String): String {
            val z = try { ZipFile(f) } catch (e: Exception) { throw IOException("not a valid .$ext file") }
            z.use {
                if (z.getEntry("content.xml") == null) throw IOException("not a valid .$ext file (content.xml missing)")
                try { return convert(z, ext) }
                catch (e: IOException) { throw e }
                catch (e: Exception) { throw IOException("cannot read this .$ext file: ${e.message}") }
            }
        }

        private fun XmlPullParser.iv(a: String, d: Int): Int = getAttributeValue(null, a)?.toIntOrNull() ?: d

        private fun openTags(f: Int): String {
            val s = StringBuilder()
            if ((f and 1) != 0) s.append("<b>")
            if ((f and 2) != 0) s.append("<i>")
            if ((f and 4) != 0) s.append("<u>")
            if ((f and 8) != 0) s.append("<s>")
            if ((f and 16) != 0) s.append("<sup>")
            if ((f and 32) != 0) s.append("<sub>")
            return s.toString()
        }
        private fun closeTags(f: Int): String {
            val s = StringBuilder()
            if ((f and 32) != 0) s.append("</sub>")
            if ((f and 16) != 0) s.append("</sup>")
            if ((f and 8) != 0) s.append("</s>")
            if ((f and 4) != 0) s.append("</u>")
            if ((f and 2) != 0) s.append("</i>")
            if ((f and 1) != 0) s.append("</b>")
            return s.toString()
        }

        private fun tflags(p: XmlPullParser): Int {
            var f = 0
            val w = p.getAttributeValue(null, "fo:font-weight")
            if (w == "bold" || (w?.toIntOrNull() ?: 0) >= 600) f = f or 1
            val i = p.getAttributeValue(null, "fo:font-style")
            if (i == "italic" || i == "oblique") f = f or 2
            val u = p.getAttributeValue(null, "style:text-underline-style")
            if (u != null && u != "none") f = f or 4
            val s = p.getAttributeValue(null, "style:text-line-through-style")
            if (s != null && s != "none") f = f or 8
            val pos = p.getAttributeValue(null, "style:text-position")
            if (pos != null) { if (pos.startsWith("super")) f = f or 16 else if (pos.startsWith("sub")) f = f or 32 }
            return f
        }

        /** Reads style:style (text/paragraph properties + parent) and text:list-style from styles.xml, or from content.xml up to office:body. */
        private fun scan(p: XmlPullParser, st: Styles, stopAtBody: Boolean) {
            var cur: String? = null
            var curList: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "office:body" -> if (stopAtBody) return
                        "style:style" -> {
                            cur = p.getAttributeValue(null, "style:name")
                            val par = p.getAttributeValue(null, "style:parent-style-name")
                            if (cur != null && par != null) st.parent[cur] = par
                        }
                        "style:text-properties" -> {
                            val c = cur
                            if (c != null) st.flags[c] = (st.flags[c] ?: 0) or tflags(p)
                        }
                        "text:list-style" -> curList = p.getAttributeValue(null, "style:name")
                        "text:list-level-style-number" -> {
                            val c = curList
                            if (c != null) st.numbered.add(c + "|" + p.iv("text:level", 1))
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "style:style" -> cur = null
                        "text:list-style" -> curList = null
                    }
                }
                ev = p.next()
            }
        }

        private fun convert(z: ZipFile, ext: String): String {
            val st = Styles()
            z.getEntry("styles.xml")?.let { e ->
                z.getInputStream(e).use { s -> val p = Xml.newPullParser(); p.setInput(s, null); scan(p, st, false) }
            }
            val ce = z.getEntry("content.xml")!!
            z.getInputStream(ce).use { s -> val p = Xml.newPullParser(); p.setInput(s, null); scan(p, st, true) }
            val sb = StringBuilder(64 * 1024)
            sb.append(OfficeText.HEAD)
            z.getInputStream(ce).use { s -> val p = Xml.newPullParser(); p.setInput(s, null); run(p, z, st, ext, sb) }
            if (sb.length == OfficeText.HEAD.length) sb.append("<p class=\"note\">(empty document)</p>")
            sb.append("</body></html>")
            return sb.toString()
        }

        private fun run(p: XmlPullParser, z: ZipFile, st: Styles, ext: String, sb: StringBuilder) {
            val sheet = ext == "ods" || ext == "ots"
            val cl = ArrayList<String>()              // one "closer" per shown element; "C" marks a pushed table cell
            val paras = ArrayList<Para>()
            val listNames = ArrayList<String?>()
            val tbls = ArrayList<Tbl>()
            val cells = ArrayList<Cell>()
            var out = sb                              // where block output goes now: body, a table cell or the notes box
            var skip = 0
            var frameCls: String? = null
            var slideNo = 0
            var cut = false
            var notes: StringBuilder? = null
            var ev = p.eventType
            loop@ while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = p.name
                        val cls = if (n == "draw:frame") p.getAttributeValue(null, "presentation:class") else null
                        val pageCut = n == "draw:page" && (slideNo >= MAX_SLIDES || sb.length > MAX_HTML)
                        if (skip > 0 || n in SKIP || (cls != null && cls in DROP_CLS) || pageCut) {
                            if (pageCut && skip == 0) cut = true
                            skip++
                        } else {
                            var closer = ""
                            when (n) {
                                "text:h", "text:p" -> {
                                    val fl = st.flagsOf(p.getAttributeValue(null, "text:style-name"))
                                    val tag = when {
                                        frameCls == "title" -> Pair("<h2>", "</h2>")
                                        frameCls == "subtitle" -> Pair("<div class=\"sub\">", "</div>")
                                        n == "text:h" -> { val lv = p.iv("text:outline-level", 1).coerceIn(1, 6); Pair("<h$lv>", "</h$lv>") }
                                        cells.isNotEmpty() -> Pair("<div>", "</div>")
                                        else -> Pair("<p>", "</p>")
                                    }
                                    paras.add(Para(tag.first, tag.second, fl))
                                }
                                "text:span" -> {
                                    val fl = st.flagsOf(p.getAttributeValue(null, "text:style-name"))
                                    if (fl != 0 && paras.isNotEmpty()) { paras[paras.size - 1].sb.append(openTags(fl)); closer = closeTags(fl) }
                                }
                                "text:s" -> if (paras.isNotEmpty()) paras[paras.size - 1].sb.append("&nbsp;".repeat(p.iv("text:c", 1).coerceIn(1, 200)))
                                "text:tab" -> if (paras.isNotEmpty()) paras[paras.size - 1].sb.append("&emsp;")
                                "text:line-break" -> if (paras.isNotEmpty()) paras[paras.size - 1].sb.append("<br>")
                                "text:list" -> {
                                    val nm = p.getAttributeValue(null, "text:style-name") ?: listNames.lastOrNull()
                                    val numbered = nm != null && (nm + "|" + (listNames.size + 1)) in st.numbered
                                    listNames.add(nm)
                                    val tg = if (numbered) "ol" else "ul"
                                    out.append('<').append(tg).append('>')
                                    closer = "</$tg>"
                                }
                                "text:list-item", "text:list-header" -> { out.append("<li>"); closer = "</li>" }
                                "table:table" -> {
                                    val tn = p.getAttributeValue(null, "table:name") ?: ""
                                    if (sheet && tbls.isEmpty()) out.append("<h2>").append(OfficeText.esc(tn)).append("</h2>")
                                    tbls.add(Tbl())
                                }
                                "table:table-row" -> {
                                    val t = tbls.lastOrNull()
                                    if (t != null) { t.row = ArrayList(); t.rowRep = p.iv("table:number-rows-repeated", 1).coerceIn(1, 1_100_000) }
                                }
                                "table:table-cell" -> if (tbls.isNotEmpty()) {
                                    val vt = p.getAttributeValue(null, "office:value-type")
                                    val c = Cell(p.iv("table:number-columns-spanned", 1), p.iv("table:number-rows-spanned", 1),
                                        p.iv("table:number-columns-repeated", 1).coerceIn(1, 20000), vt == "float" || vt == "percentage" || vt == "currency", out)
                                    cells.add(c)
                                    out = c.buf
                                    closer = "C"
                                }
                                "draw:frame" -> frameCls = cls
                                "draw:image" -> {
                                    val h = p.getAttributeValue(null, "xlink:href")?.removePrefix("./")
                                    val t = if (h == null) OfficeText.IMG_PH else OfficeText.imgTag(z, h)
                                    (if (paras.isNotEmpty()) paras[paras.size - 1].sb else out).append(t)
                                }
                                "draw:page" -> { slideNo++; sb.append("<section class=\"sl\"><p class=\"sn\">Slide ").append(slideNo).append("</p>") }
                                "presentation:notes" -> { val nb = StringBuilder(); notes = nb; out = nb }
                            }
                            cl.add(closer)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (skip > 0) skip--
                        else {
                            val closer = if (cl.isNotEmpty()) cl.removeAt(cl.size - 1) else ""
                            when (p.name) {
                                "text:h", "text:p" -> if (paras.isNotEmpty()) {
                                    val pa = paras.removeAt(paras.size - 1)
                                    val body = pa.sb.toString()
                                    if (body.isNotBlank()) {
                                        out.append(pa.open)
                                        if (pa.flags != 0) out.append(openTags(pa.flags)).append(body).append(closeTags(pa.flags)) else out.append(body)
                                        out.append(pa.close)
                                    }
                                    if (out.length > MAX_HTML || sb.length > MAX_HTML) { cut = true; break@loop }
                                }
                                "text:span" -> if (paras.isNotEmpty()) paras[paras.size - 1].sb.append(closer)
                                "text:list" -> { out.append(closer); if (listNames.isNotEmpty()) listNames.removeAt(listNames.size - 1) }
                                "text:list-item", "text:list-header" -> out.append(closer)
                                "table:table-cell" -> if (closer == "C" && cells.isNotEmpty()) {
                                    val c = cells.removeAt(cells.size - 1)
                                    out = c.prev
                                    val content = c.buf.toString()
                                    val t = tbls.lastOrNull()
                                    val row = t?.row
                                    if (t != null && row != null) {
                                        val empty = content.isBlank() && c.colspan <= 1 && c.rowspan <= 1
                                        val html = "<td" + (if (c.right) " class=\"r\"" else "") +
                                            (if (c.colspan > 1) " colspan=\"${c.colspan}\"" else "") +
                                            (if (c.rowspan > 1) " rowspan=\"${c.rowspan}\"" else "") + ">" + content + "</td>"
                                        val times = if (empty) minOf(c.rep, MAX_COLS) else minOf(c.rep, 50)
                                        var k = 0
                                        while (k < times && row.size < MAX_COLS) { row.add(Pair(empty, html)); k++ }
                                        if (k < times) t.trunc = true
                                    }
                                }
                                "table:table-row" -> {
                                    val t = tbls.lastOrNull()
                                    val row = t?.row
                                    if (t != null && row != null) {
                                        t.row = null
                                        while (row.isNotEmpty() && row[row.size - 1].first) row.removeAt(row.size - 1)
                                        if (row.isEmpty()) t.blank = minOf(t.blank + t.rowRep, 2)
                                        else if (t.rows >= MAX_ROWS) t.trunc = true
                                        else {
                                            if (t.rows > 0) repeat(t.blank) { t.sb.append("<tr><td>&nbsp;</td></tr>") }
                                            t.blank = 0
                                            val html = "<tr>" + row.joinToString("") { it.second } + "</tr>"
                                            val reps = minOf(t.rowRep, 50)
                                            var k = 0
                                            while (k < reps && t.rows < MAX_ROWS) { t.sb.append(html); t.rows++; k++ }
                                        }
                                    }
                                }
                                "table:table" -> if (tbls.isNotEmpty()) {
                                    val t = tbls.removeAt(tbls.size - 1)
                                    if (t.rows > 0) {
                                        out.append("<div class=\"tw\"><table>").append(t.sb).append("</table></div>")
                                        if (t.trunc) out.append("<p class=\"note\">… (rows or columns beyond ${MAX_ROWS} x ${MAX_COLS} not shown)</p>")
                                    } else if (sheet) out.append("<p class=\"ph\">(empty sheet)</p>")
                                }
                                "draw:frame" -> frameCls = null
                                "presentation:notes" -> out = sb
                                "draw:page" -> {
                                    val nb = notes
                                    if (nb != null && nb.isNotEmpty()) sb.append("<div class=\"nt\"><b>Notes</b>").append(nb).append("</div>")
                                    notes = null
                                    sb.append("</section>")
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (skip == 0 && paras.isNotEmpty()) paras[paras.size - 1].sb.append(OfficeText.esc(p.text))
                }
                ev = p.next()
            }
            if (cut) sb.append("<p class=\"note\">… (rest not shown: file too large to show here)</p>")
        }
    }
}
