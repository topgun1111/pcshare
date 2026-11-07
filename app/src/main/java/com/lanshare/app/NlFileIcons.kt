package com.lanshare.app

import android.graphics.*

/**
 * File-type icons: a category gradient tile with a Lumen glyph (see draw), inside a square of `size` units at (x, y).
 * Main-thread only (shared Paint/Path).
 */
object NlFileIcons {
    private val q = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val WORD_X = setOf("doc", "docx", "docm", "dot", "dotx", "odt", "rtf")
    private val XLS_X = setOf("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "ods", "csv")
    private val PPT_X = setOf("ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp")
    private val TXT_X = setOf("txt", "md", "ini", "cfg", "conf")

    // ---- additional formats (added later; the icons above are untouched) ----
    private class Tag(val label: String, val bg: Int, val fg: Int)
    private val TAGS: Map<String, Tag> = HashMap<String, Tag>().also { m ->
        fun a(exts: String, label: String, bg: Long, fg: Long = 0xFFFFFFFFL) { val t = Tag(label, bg.toInt(), fg.toInt()); exts.split(' ').forEach { m[it] = t } }
        a("html htm xhtml mhtml", "HTML", 0xFFE44D26)
        a("css", "CSS", 0xFF1572B6)
        a("scss sass", "SASS", 0xFFCD6799)
        a("less", "LESS", 0xFF1D365D)
        a("js mjs cjs", "JS", 0xFFF7DF1E, 0xFF323330)
        a("jsx", "JSX", 0xFF61DAFB, 0xFF1B2733)
        a("ts", "TS", 0xFF3178C6)
        a("tsx", "TSX", 0xFF3178C6)
        a("php phtml", "PHP", 0xFF777BB3)
        a("java", "JAVA", 0xFFE76F00)
        a("kt kts", "KT", 0xFF7F52FF)
        a("c", "C", 0xFF5C6BC0)
        a("h hpp", "H", 0xFF7986CB)
        a("cpp cc", "C++", 0xFF00599C)
        a("cs", "C#", 0xFF68217A)
        a("go", "GO", 0xFF00ADD8)
        a("rs", "RS", 0xFFCE422B)
        a("rb", "RB", 0xFFCC342D)
        a("swift", "SWIFT", 0xFFF05138)
        a("dart", "DART", 0xFF0175C2)
        a("lua", "LUA", 0xFF000080)
        a("pl pm", "PERL", 0xFF39457E)
        a("r", "R", 0xFF276DC3)
        a("scala", "SCALA", 0xFFDC322F)
        a("vb vbs", "VB", 0xFF512BD4)
        a("asm", "ASM", 0xFF6E4C13)
        a("jl", "JL", 0xFF9558B2)
        a("ex exs", "EX", 0xFF6E4A7E)
        a("erl", "ERL", 0xFFA90533)
        a("hs", "HS", 0xFF5E5086)
        a("clj", "CLJ", 0xFF5881D8)
        a("vue", "VUE", 0xFF42B883)
        a("svelte", "SVLT", 0xFFFF3E00)
        a("graphql", "GQL", 0xFFE10098)
        a("proto", "PROTO", 0xFF455A64)
        a("sql", "SQL", 0xFF00758F)
        a("xml", "XML", 0xFFFF6F00)
        a("yml yaml", "YAML", 0xFFCB171E)
        a("toml", "TOML", 0xFF9C4221)
        a("env properties gradle editorconfig inf", "CFG", 0xFF607D8B)
        a("reg", "REG", 0xFF546E7A)
        a("tex bib", "TEX", 0xFF3D6117)
        a("diff patch", "DIFF", 0xFFFB8C00)
        a("gitignore gitattributes", "GIT", 0xFFF05033)
        a("dockerfile", "DOCKER", 0xFF2496ED)
        a("makefile mk cmake", "MAKE", 0xFF6D4C41)
        a("ndjson geojson har", "JSON", 0xFFF57C00)
        a("lock", "LOCK", 0xFF78909C)
        a("dll so", "DLL", 0xFF546E7A)
        a("psd", "PSD", 0xFF001E36, 0xFF31A8FF)
        a("ai", "AI", 0xFF330000, 0xFFFF9A00)
        a("bin dat", "BIN", 0xFF455A64)
    }
    private val TERM_X = setOf("sh", "bash", "zsh", "bat", "cmd", "ps1")
    private val SUB_X = setOf("srt", "vtt", "ass", "ssa", "sub", "lrc")
    private val LIST_X = setOf("m3u", "m3u8", "pls")
    private val EXE_X = setOf("exe", "msi", "com", "scr")
    private val BOOK_X = setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu")
    private val DB_X = setOf("db", "sqlite", "sqlite3", "mdb", "accdb", "sql3")
    private val PIC_X = setOf("ico", "avif", "heif", "dng", "tif", "tiff", "jfif", "jpe", "jfi", "wbmp", "raw", "cr2", "nef", "arw")
    private val AUD_X = setOf("wma", "mid", "midi", "amr", "ape", "aiff", "aif", "wv", "mka")
    private val VID_X = setOf("wmv", "vob", "rm", "rmvb", "divx", "asf")
    private val ZIP_X = setOf("xz", "bz2", "tgz", "lz", "lzma", "zst", "cab", "jar", "z")
    private val TSV_X = setOf("tsv", "tab")
    private val TEXT2_X = setOf("text", "nfo", "rst", "adoc", "org", "readme")

    fun extOf(name: String): String { val p = name.lastIndexOf('.'); return if (p >= 0) name.substring(p + 1).lowercase() else "" }

    // ---- Lumen 5.3 / 11.6: file type = category gradient tile with a Lumen glyph ----
    private val gradCache = HashMap<Long, LinearGradient>()
    private val gm = Matrix()
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textAlign = Paint.Align.CENTER }

    /** Category key (LumenCategory.KEYS) of a file: [kind] is the list kind (img / vid / aud / zip / pdf / apk or \"\"), then the extension. */
    fun catOf(name: String, kind: String): String {
        val e = extOf(name)
        return when {
            kind == "pdf" || e == "pdf" -> "pdf"
            kind == "img" || e in PIC_X -> "pic"
            kind == "vid" || e in VID_X -> "vid"
            kind == "aud" || e in AUD_X || e in LIST_X -> "aud"
            kind == "zip" || e in ZIP_X || e == "iso" || e == "dmg" || e == "mdf" || e == "nrg" -> "zip"
            kind == "apk" || e in EXE_X -> "apk"
            e in WORD_X || e in BOOK_X -> "doc"
            e in XLS_X || e in TSV_X || e in DB_X -> "xls"
            e in PPT_X -> "ppt"
            e == "py" || e == "pyw" || e == "json" || e == "jsonl" || e == "log" || e in TXT_X || e in TEXT2_X || e in TERM_X || e in SUB_X ||
                TAGS.containsKey(e) || TAGS.containsKey(name.lowercase()) -> "code"
            else -> "other"
        }
    }

    /** Lumen Icons glyph (icons.json) that stands for a category. */
    fun glyphOf(cat: String): String = when (cat) {
        "pic" -> "img"; "vid" -> "vid"; "aud" -> "aud"; "doc" -> "doc"; "xls" -> "sheet"; "ppt" -> "slides"
        "pdf" -> "pdf"; "zip" -> "zip"; "code" -> "code"; "apk" -> "apk"; else -> "file"
    }

    private fun gradient(top: Int, bottom: Int): LinearGradient {
        val k = (top.toLong() shl 32) xor (bottom.toLong() and 0xFFFFFFFFL)
        return gradCache.getOrPut(k) { LinearGradient(0f, 0f, 1f, 1f, top, bottom, Shader.TileMode.CLAMP) }
    }

    /**
     * File without a thumbnail: rounded category tile (gradient top-left light to bottom-right 15 % darker, 1 px light rim) with the category
     * glyph in white (light mode) or the deep tone (dark mode). Radius 30 % of the tile (12 dp at 40 dp). With [d] (screen density) > 0 and a tile of
     * 64 dp or more the extension is written under a smaller glyph (spec 11.5). Main-thread only.
     */
    fun draw(cv: Canvas, name: String, kind: String, x: Float, y: Float, size: Float, cat: LumenCategory.Cats = LumenCategory.DEFAULT_LIGHT, d: Float = 0f) {
        val key = catOf(name, kind)
        val t = cat[key]
        val rad = size * 0.3f
        gm.setScale(size, size); gm.postTranslate(x, y)
        val g = gradient(t.top, t.bottom); g.setLocalMatrix(gm)
        q.reset(); q.isAntiAlias = true
        q.style = Paint.Style.FILL; q.shader = g
        rf.set(x, y, x + size, y + size); cv.drawRoundRect(rf, rad, rad, q)
        q.shader = null
        val rim = Math.max(1f, size / 48f)
        q.style = Paint.Style.STROKE; q.strokeWidth = rim; q.color = if (cat.night) 0x22FFFFFF else 0x33FFFFFF
        rf.set(x + rim / 2f, y + rim / 2f, x + size - rim / 2f, y + size - rim / 2f); cv.drawRoundRect(rf, rad, rad, q)
        q.style = Paint.Style.FILL
        val fg = cat.glyph(key)
        val withLabel = d > 0f && size >= 64f * d
        val gs = if (withLabel) size * 0.42f else size * 0.58f
        val gy = if (withLabel) y + size * 0.14f else y + (size - gs) / 2f
        NlIcons.draw(cv, q, glyphOf(key), x + (size - gs) / 2f, gy, gs, fg)
        if (withLabel) {
            val ext = extOf(name).uppercase().take(4)
            if (ext.isNotEmpty()) { labelP.color = fg; labelP.textSize = size * 0.16f; cv.drawText(ext, x + size / 2f, y + size * 0.84f, labelP) }
        }
        q.style = Paint.Style.FILL; q.strokeCap = Paint.Cap.BUTT
    }
}
