package com.lanshare.app

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure layout maths of the print preview = Kotlin twin of ui.html (PV_PAPER, PV_LAY, PV_GRID, pvPageSet, pvSheets, pvFitK,
 * pvBooklet, pvFill, pvTextPages). Like the JS it must stay identical to pcprint.py (images_to_sheets, fit_cell, txt_to_pdf):
 * the preview is what gets printed. All option values are the dialog's strings ("1" = on, "" = default).
 *
 * NOT compiled / NOT device-tested.
 */
object PrintLayout {
    val PAPER: Map<String, DoubleArray> = mapOf(
        "A3" to doubleArrayOf(842.0, 1191.0), "A4" to doubleArrayOf(595.0, 842.0), "A5" to doubleArrayOf(420.0, 595.0),
        "Letter" to doubleArrayOf(612.0, 792.0), "Legal" to doubleArrayOf(612.0, 1008.0)
    )
    fun paper(name: String?): DoubleArray = PAPER[name ?: ""] ?: PAPER["A4"]!!

    /** pages per sheet -> (columns, rows, landscape sheet) */
    val LAY: Map<Int, Triple<Int, Int, Boolean>> = mapOf(
        1 to Triple(1, 1, false), 2 to Triple(2, 1, true), 4 to Triple(2, 2, false), 6 to Triple(3, 2, true), 9 to Triple(3, 3, false)
    )
    /** pictures per sheet -> (cols, rows) on a portrait sheet, (cols, rows) on a landscape sheet */
    private val GRID: Map<Int, Pair<Pair<Int, Int>, Pair<Int, Int>>> = mapOf(
        1 to ((1 to 1) to (1 to 1)), 2 to ((1 to 2) to (2 to 1)), 4 to ((2 to 2) to (2 to 2)),
        6 to ((2 to 3) to (3 to 2)), 9 to ((3 to 3) to (3 to 3))
    )

    val PICS = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "webp", "heic", "heif", "avif", "ico", "wbmp", "dng", "jfif", "jpe", "jfi")
    val WEB = setOf("html", "htm", "xhtml", "svg")
    val OFFICE = setOf("doc", "docx", "rtf", "odt", "xls", "xlsx", "csv", "ppt", "pptx", "odp", "ods")
    val TXT = setOf(
        "txt", "log", "md", "php", "phtml", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "json", "xml", "yml", "yaml", "toml", "ini", "cfg",
        "conf", "env", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "sh", "bat", "ps1", "gradle",
        "properties", "tsv", "srt", "vtt", "cjs", "sass", "less", "jsonl", "ndjson", "geojson", "bash", "zsh", "cmd", "cc", "ass", "ssa",
        "swift", "dart", "lua", "pl", "pm", "r", "scala", "vb", "vbs", "asm", "jl", "ex", "exs", "erl", "hs", "clj", "vue", "svelte",
        "graphql", "proto", "tex", "bib", "rst", "adoc", "org", "ics", "vcf", "eml", "nfo", "lrc", "diff", "patch", "gitignore",
        "gitattributes", "editorconfig", "dockerfile", "makefile", "mk", "cmake", "reg", "inf", "m3u", "m3u8", "har", "lock", "text"
    )
    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    /** ui.html pvOK: the types the preview knows (everything else shows the sample layout). */
    fun previewable(name: String): Boolean { val e = ext(name); return e == "pdf" || e in PICS || e in TXT || e in OFFICE || e in WEB }

    fun num(s: String?): Double = s?.trim()?.toDoubleOrNull() ?: 0.0

    /** ui.html pvPageSet: "1-3,5,8-" -> zero-based page indexes (empty / unusable = all pages). */
    fun pageSet(rng: String?, n: Int): MutableList<Int> {
        val all = (0 until n).toMutableList()
        if (rng == null || rng.isBlank()) return all
        val out = ArrayList<Int>()
        val re = Regex("^(\\d*)(-?)(\\d*)$")
        for (part in rng.replace(" ", "").split(',')) {
            if (part.isEmpty()) continue
            val m = re.find(part) ?: continue
            val g1 = m.groupValues[1]; val g2 = m.groupValues[2]; val g3 = m.groupValues[3]
            var a: Int; var b: Int
            if (g2.isNotEmpty()) { a = if (g1.isNotEmpty()) g1.toInt() else 1; b = if (g3.isNotEmpty()) g3.toInt() else n }
            else { if (g1.isEmpty()) continue; a = g1.toInt(); b = a }
            a = max(1, min(n, a)); b = max(1, min(n, b))
            if (a <= b) for (i in a..b) out.add(i - 1) else for (i in a downTo b) out.add(i - 1)
        }
        return if (out.isEmpty()) all else out
    }

    class Cell(val i: Int, val r: Int, val k: Double, val w: Double, val h: Double, val cx: Double, val cy: Double)
    class Sheets(val sw: Double, val sh: Double, val sheets: List<List<Cell>>, val score: Double)

    /** ui.html pvSheets = pcprint.py images_to_sheets: pictures on shared sheets. dims[i] = (w, h) as the picture looks (EXIF applied), rots = manual turns. */
    fun sheets(dims: List<DoubleArray>, rots: IntArray, st: Map<String, String>): Sheets {
        val pd = paper(st["paper"])
        val mg = st["margin"]
        val m = if (!mg.isNullOrEmpty()) num(mg) else 14.0
        val g = 8.0
        val fill = st["fit"] == "fill"
        val nupI = st["nup"]?.trim()?.toIntOrNull() ?: 1
        val per = if (GRID.containsKey(nupI)) nupI else 1
        val order = dims.indices.toMutableList()
        if (st["reverse"] == "1") order.reverse()
        val noauto = st["noauto"] == "1"
        fun plan(portrait: Boolean): Sheets {
            val sw = if (portrait) pd[0] else pd[1]
            val sh = if (portrait) pd[1] else pd[0]
            val (cols, rows) = if (portrait) GRID[per]!!.first else GRID[per]!!.second
            val cw = (sw - 2 * m - g * (cols - 1)) / cols
            val ch = (sh - 2 * m - g * (rows - 1)) / rows
            val sheets = ArrayList<List<Cell>>()
            var score = 0.0
            var s0 = 0
            while (s0 < order.size) {
                val cells = ArrayList<Cell>()
                order.subList(s0, min(order.size, s0 + cols * rows)).forEachIndexed { j, i ->
                    var bk = -1.0; var br = 0; var bdw = 0.0; var bdh = 0.0
                    for (ex in if (noauto) intArrayOf(0) else intArrayOf(0, 90)) {
                        val r = ((rots.getOrElse(i) { 0 }) + ex) % 360
                        val w = dims[i][0]; val h = dims[i][1]
                        val dw = if (r % 180 != 0) h else w
                        val dh = if (r % 180 != 0) w else h
                        val k = min(cw / dw, ch / dh)
                        if (bk < 0 || k > bk * 1.0001) { bk = k; br = r; bdw = dw; bdh = dh }
                    }
                    score += bk * bk * bdw * bdh
                    val kk = if (fill) max(cw / bdw, ch / bdh) else bk
                    cells.add(Cell(i, br, kk, if (fill) cw else bdw * kk, if (fill) ch else bdh * kk,
                        m + (j % cols) * (cw + g) + cw / 2, m + (j / cols) * (ch + g) + ch / 2))
                }
                sheets.add(cells)
                s0 += cols * rows
            }
            return Sheets(sw, sh, sheets, score)
        }
        val a = plan(true); val b = plan(false)
        return if (b.score > a.score * 1.0001) b else a
    }

    /** ui.html pvFitK = pcprint.py fit_cell: scale of one page in a cell ([per] = pages per sheet). */
    fun fitK(w: Double, h: Double, cw: Double, ch: Double, st: Map<String, String>, per: Int): Double {
        val fk = min(cw / w, ch / h)
        val scale = num(st["scale"])
        val fit = st["fit"] ?: ""
        val sc = if (scale > 0) max(10.0, min(500.0, scale)) / 100.0
        else if (fit == "noscale") 1.0
        else if (fit == "fill" && per == 1) max(cw / w, ch / h)
        else if (fit == "fit" || per > 1) fk
        else min(1.0, fk)
        return if (per > 1) min(sc, fk) else sc
    }

    /** ui.html pvBooklet: page order of a folded booklet (null = blank page). */
    fun booklet(n: Int): List<Int?> {
        val m = ceil(n / 4.0).toInt() * 4
        val q = ArrayList<Int>()
        for (i in 0 until m / 4) { q.add(m - 1 - 2 * i); q.add(2 * i); q.add(2 * i + 1); q.add(m - 2 - 2 * i) }
        return q.map { if (it < n) it else null }
    }

    /** ui.html pvFill: {page} {pages} {date} {time} {file}. */
    fun fill(t: String, i: Int, n: Int, name: String): String {
        val d = java.util.Calendar.getInstance()
        fun z(x: Int) = x.toString().padStart(2, '0')
        return t.replace("{page}", (i + 1).toString()).replace("{pages}", n.toString())
            .replace("{date}", d.get(java.util.Calendar.YEAR).toString() + "-" + z(d.get(java.util.Calendar.MONTH) + 1) + "-" + z(d.get(java.util.Calendar.DAY_OF_MONTH)))
            .replace("{time}", z(d.get(java.util.Calendar.HOUR_OF_DAY)) + ":" + z(d.get(java.util.Calendar.MINUTE)))
            .replace("{file}", name)
    }

    class TextPages(val pages: List<List<String>>, val size: Double, val lead: Double, val mm: Double)

    /** ui.html pvTextPages = pcprint.py txt_to_pdf wrapping (Courier, 0.6 em per character, 1.2 line height; custom scale = font size, margin = page margin). */
    fun textPages(text: String, st: Map<String, String>): TextPages {
        val pd = paper(st["paper"])
        val mg = st["margin"]
        val mm = if (!mg.isNullOrEmpty()) num(mg) else 50.0
        val scale = num(st["scale"])
        val size = max(4.0, min(40.0, 10 * (if (scale > 0) scale / 100.0 else 1.0)))
        val lead = size * 1.2
        val cpl = max(1, floor((pd[0] - 2 * mm) / (.6 * size)).toInt())
        val lpp = max(1, floor((pd[1] - 2 * mm) / lead).toInt())
        val lines = ArrayList<String>()
        val ctl = Regex("[\\u0000-\\u0008\\u000b\\u000e-\\u001f]")
        for (raw in text.replace("\r\n", "\n").replace("\r", "\n").replace("\u000c", "\n").split('\n')) {
            val ln = ctl.replace(raw, "")
            val sb = StringBuilder()
            for (ch in ln) { if (ch == '\t') sb.append(" ".repeat(4 - sb.length % 4)) else sb.append(ch) }
            var o = sb.toString()
            while (o.length > cpl) { lines.add(o.substring(0, cpl)); o = o.substring(cpl) }
            lines.add(o)
        }
        val pages = ArrayList<List<String>>()
        var i = 0
        while (i < max(lines.size, 1)) { pages.add(lines.subList(min(i, lines.size), min(i + lpp, lines.size)).toList()); i += lpp }
        return TextPages(pages, size, lead, mm)
    }
}
