package com.lanshare.app

import android.graphics.*

/**
 * Realistic, colourful file-type icons (Word blue "W", Excel green "X", PowerPoint orange "P", red PDF, zip, apk, image, video, audio,
 * text, generic page) drawn on a Canvas inside a square of `size` units at (x, y). Stroke widths scale with the box.
 * Main-thread only (shared Paint/Path).
 */
object NlFileIcons {
    private val q = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val pa = Path()
    private val clip = Path()
    private val bold: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private const val WORD = 0xFF185ABD.toInt(); private const val WORD_L = 0xFF41A5EE.toInt()
    private const val XLS = 0xFF107C41.toInt(); private const val XLS_L = 0xFF33C481.toInt()
    private const val PPT = 0xFFC43E1C.toInt(); private const val PPT_L = 0xFFFF8F6B.toInt()
    private const val PDF = 0xFFE5252A.toInt()
    private const val BORDER = 0xFFC4C9CE.toInt()

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
    private val FONT_X = setOf("ttf", "otf", "woff", "woff2", "fon")
    private val BOOK_X = setOf("epub", "mobi", "azw", "azw3", "fb2", "djvu")
    private val DB_X = setOf("db", "sqlite", "sqlite3", "mdb", "accdb", "sql3")
    private val PIC_X = setOf("ico", "avif", "heif", "dng", "tif", "tiff", "jfif", "jpe", "jfi", "wbmp", "raw", "cr2", "nef", "arw")
    private val AUD_X = setOf("wma", "mid", "midi", "amr", "ape", "aiff", "aif", "wv", "mka")
    private val VID_X = setOf("wmv", "vob", "rm", "rmvb", "divx", "asf")
    private val ZIP_X = setOf("xz", "bz2", "tgz", "lz", "lzma", "zst", "cab", "jar", "z")
    private val TSV_X = setOf("tsv", "tab")
    private val TEXT2_X = setOf("text", "nfo", "rst", "adoc", "org", "readme")

    fun extOf(name: String): String { val p = name.lastIndexOf('.'); return if (p >= 0) name.substring(p + 1).lowercase() else "" }

    fun draw(cv: Canvas, name: String, kind: String, x: Float, y: Float, size: Float) {
        val e = extOf(name)
        cv.save(); cv.translate(x, y); val s = size / 48f; cv.scale(s, s)
        q.style = Paint.Style.FILL; q.shader = null; q.pathEffect = null; q.strokeWidth = 1f; q.strokeCap = Paint.Cap.BUTT; q.alpha = 255
        when {
            e in WORD_X -> office(cv, WORD, WORD_L, "W", 0)
            e in XLS_X -> office(cv, XLS, XLS_L, "X", 1)
            e in PPT_X -> office(cv, PPT, PPT_L, "P", 2)
            kind == "pdf" || e == "pdf" -> pdf(cv)
            kind == "zip" -> zip(cv)
            kind == "apk" -> apk(cv)
            kind == "img" -> img(cv)
            kind == "vid" -> vid(cv)
            kind == "aud" -> aud(cv)
            e == "py" || e == "pyw" -> py(cv)
            e == "json" || e == "jsonl" -> json(cv)
            e == "log" -> log(cv)
            e in TXT_X -> page(cv, 0xFFFFFFFF.toInt(), 0xFFCFD8DC.toInt(), true)
            e in TSV_X -> office(cv, XLS, XLS_L, "X", 1)
            e in PIC_X -> img(cv)
            e in AUD_X -> aud(cv)
            e in VID_X -> vid(cv)
            e in ZIP_X -> zip(cv)
            e in TERM_X -> term(cv, e)
            e in SUB_X -> subtitle(cv)
            e == "ics" || e == "ical" -> calendar(cv)
            e == "vcf" -> contact(cv)
            e == "eml" || e == "msg" || e == "mbox" -> mail(cv)
            e in LIST_X -> playlist(cv)
            e == "iso" || e == "dmg" || e == "mdf" || e == "nrg" -> disc(cv)
            e in EXE_X -> exe(cv)
            e in FONT_X -> font(cv)
            e in BOOK_X -> book(cv)
            e in DB_X -> database(cv)
            e in TEXT2_X -> page(cv, 0xFFFFFFFF.toInt(), 0xFFCFD8DC.toInt(), true)
            TAGS.containsKey(e) -> tag(cv, TAGS[e]!!)
            TAGS.containsKey(name.lowercase()) -> tag(cv, TAGS[name.lowercase()]!!)
            else -> page(cv, 0xFFECEFF1.toInt(), 0xFFB0BEC5.toInt(), false)
        }
        cv.restore()
    }

    private fun fillRound(cv: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, c: Int) {
        q.style = Paint.Style.FILL; q.color = c; rf.set(l, t, r, b); cv.drawRoundRect(rf, rad, rad, q)
    }

    private fun foldedPage(cv: Canvas, l: Float, t: Float, r: Float, b: Float, f: Float, fill: Int, foldC: Int, border: Int) {
        pa.reset(); pa.moveTo(l, t); pa.lineTo(r - f, t); pa.lineTo(r, t + f); pa.lineTo(r, b); pa.lineTo(l, b); pa.close()
        q.pathEffect = CornerPathEffect(2f)
        q.style = Paint.Style.FILL; q.color = fill; cv.drawPath(pa, q)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = border; cv.drawPath(pa, q)
        q.pathEffect = null
        pa.reset(); pa.moveTo(r - f, t); pa.lineTo(r - f, t + f); pa.lineTo(r, t + f); pa.close()
        q.style = Paint.Style.FILL; q.color = foldC; cv.drawPath(pa, q)
    }

    private fun letter(cv: Canvas, txt: String, cx: Float, cy: Float, sz: Float, c: Int) {
        q.style = Paint.Style.FILL; q.color = c; q.typeface = bold; q.textSize = sz; q.textAlign = Paint.Align.CENTER
        val fm = q.fontMetrics
        cv.drawText(txt, cx, cy - (fm.ascent + fm.descent) / 2f, q)
        q.textAlign = Paint.Align.LEFT
    }

    /** Office-style: white sheet with coloured detail on the right, big coloured square with the letter on the left. */
    private fun office(cv: Canvas, base: Int, light: Int, ch: String, deco: Int) {
        fillRound(cv, 14f, 4f, 45f, 44f, 3f, 0xFFFFFFFF.toInt())
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = BORDER; rf.set(14f, 4f, 45f, 44f); cv.drawRoundRect(rf, 5f, 5f, q)
        q.color = light; q.strokeWidth = 2.2f
        when (deco) {
            0 -> for (yy in floatArrayOf(11f, 17f, 23f, 29f, 35f)) cv.drawLine(31f, yy, 42f, yy, q)
            1 -> {
                q.strokeWidth = 1.6f
                for (yy in floatArrayOf(11f, 18f, 25f, 32f, 39f)) cv.drawLine(30f, yy, 42f, yy, q)
                cv.drawLine(36f, 11f, 36f, 39f, q)
            }
            else -> {
                q.style = Paint.Style.FILL; q.color = light; cv.drawCircle(36f, 20f, 6.5f, q)
                q.color = 0xFFFFD0C2.toInt(); rf.set(29.5f, 13.5f, 42.5f, 26.5f); cv.drawArc(rf, -90f, 90f, true, q)
                q.style = Paint.Style.STROKE; q.strokeWidth = 2.2f; q.color = light; cv.drawLine(31f, 34f, 42f, 34f, q)
            }
        }
        fillRound(cv, 2f, 12f, 29f, 39f, 3.5f, base)
        letter(cv, ch, 15.5f, 25.5f, 21f, Color.WHITE)
    }

    private fun pdf(cv: Canvas) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 10f, 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), BORDER)
        fillRound(cv, 3f, 21f, 45f, 35f, 2.5f, PDF)
        letter(cv, "PDF", 24f, 28.2f, 11.5f, Color.WHITE)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.6f; q.color = 0xFFB0B6BB.toInt()
        cv.drawLine(13f, 39f, 35f, 39f, q); cv.drawLine(13f, 42f, 28f, 42f, q)
        q.style = Paint.Style.FILL
    }

    private fun page(cv: Canvas, fill: Int, border: Int, lines: Boolean) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 11f, fill, 0xFFCFD8DC.toInt(), border)
        if (lines) {
            q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = 0xFF78909C.toInt()
            for (yy in floatArrayOf(22f, 28f, 34f, 40f)) cv.drawLine(14f, yy, if (yy == 40f) 26f else 34f, yy, q)
            q.style = Paint.Style.FILL
        }
    }

    private fun py(cv: Canvas) {
        fillRound(cv, 5f, 4f, 31f, 30f, 8f, 0xFF3776AB.toInt())
        fillRound(cv, 17f, 18f, 43f, 44f, 8f, 0xFFFFD43B.toInt())
        q.style = Paint.Style.FILL
        q.color = Color.WHITE; cv.drawCircle(13f, 12f, 2.4f, q)
        q.color = 0xFF3776AB.toInt(); cv.drawCircle(35f, 36f, 2.4f, q)
        q.color = 0xFF2B5B84.toInt(); rf.set(11f, 19f, 24f, 24f); cv.drawRoundRect(rf, 3.5f, 3.5f, q)
        q.color = 0xFFE0B52B.toInt(); rf.set(24f, 24f, 37f, 29f); cv.drawRoundRect(rf, 3.5f, 3.5f, q)
    }

    private fun json(cv: Canvas) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 11f, 0xFFFFFFFF.toInt(), 0xFFFFE0B2.toInt(), BORDER)
        letter(cv, "{ }", 24f, 29f, 16f, 0xFFF57C00.toInt())
        q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = 0xFFFFCC80.toInt()
        cv.drawLine(14f, 40f, 34f, 40f, q); q.style = Paint.Style.FILL
    }

    private fun log(cv: Canvas) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 11f, 0xFFFFFFFF.toInt(), 0xFFCFD8DC.toInt(), BORDER)
        val cols = intArrayOf(0xFF43A047.toInt(), 0xFFFB8C00.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt())
        val ys = floatArrayOf(21f, 27f, 33f, 39f)
        for (i in 0..3) {
            q.style = Paint.Style.FILL; q.color = cols[i]; rf.set(13f, ys[i] - 1.7f, 17f, ys[i] + 1.7f); cv.drawRoundRect(rf, 1f, 1f, q)
            q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = 0xFF78909C.toInt(); cv.drawLine(20f, ys[i], if (i == 3) 29f else 35f, ys[i], q)
        }
        q.style = Paint.Style.FILL
    }

    private fun zip(cv: Canvas) {
        fillRound(cv, 7f, 4f, 41f, 44f, 4f, 0xFFF5B82E.toInt())
        fillRound(cv, 7f, 4f, 41f, 12f, 4f, 0xFFE5A21A.toInt())
        var yy = 4f; var alt = false
        while (yy < 30f) { q.color = if (alt) 0xFF6D4C41.toInt() else 0xFFFFFFFF.toInt(); q.style = Paint.Style.FILL; cv.drawRect(if (alt) 24f else 20f, yy, if (alt) 28f else 24f, yy + 4f, q); alt = !alt; yy += 4f }
        fillRound(cv, 19f, 30f, 29f, 41f, 2.5f, 0xFF8D6E63.toInt())
        fillRound(cv, 22f, 33f, 26f, 38f, 1.5f, 0xFFF5B82E.toInt())
    }

    private fun apk(cv: Canvas) {
        fillRound(cv, 4f, 4f, 44f, 44f, 9f, 0xFF263238.toInt())
        NlIcons.path("apk")?.let { cv.save(); cv.translate(9f, 8f); cv.scale(1.25f, 1.25f); q.style = Paint.Style.FILL; q.color = 0xFF3DDC84.toInt(); cv.drawPath(it, q); cv.restore() }
    }

    private fun img(cv: Canvas) {
        clip.reset(); rf.set(4f, 8f, 44f, 40f); clip.addRoundRect(rf, 4f, 4f, Path.Direction.CW)
        cv.save(); cv.clipPath(clip)
        q.style = Paint.Style.FILL; q.color = 0xFF64B5F6.toInt(); cv.drawRect(rf, q)
        q.color = 0xFFFFD54F.toInt(); cv.drawCircle(34f, 17f, 4.5f, q)
        pa.reset(); pa.moveTo(4f, 40f); pa.lineTo(4f, 30f); pa.lineTo(14f, 20f); pa.lineTo(26f, 34f); pa.lineTo(32f, 28f); pa.lineTo(44f, 40f); pa.close()
        q.color = 0xFF43A047.toInt(); cv.drawPath(pa, q)
        pa.reset(); pa.moveTo(4f, 40f); pa.lineTo(4f, 35f); pa.lineTo(12f, 30f); pa.lineTo(22f, 40f); pa.close()
        q.color = 0xFF2E7D32.toInt(); cv.drawPath(pa, q)
        cv.restore()
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.6f; q.color = Color.WHITE; cv.drawRoundRect(rf, 5.5f, 5.5f, q)
        q.style = Paint.Style.FILL
    }

    private fun vid(cv: Canvas) {
        fillRound(cv, 4f, 8f, 44f, 40f, 4f, 0xFF7E57C2.toInt())
        q.color = 0xFFB39DDB.toInt()
        var yy = 11f; while (yy < 38f) { cv.drawRect(6f, yy, 9f, yy + 3f, q); cv.drawRect(39f, yy, 42f, yy + 3f, q); yy += 6f }
        pa.reset(); pa.moveTo(19f, 15f); pa.lineTo(33f, 24f); pa.lineTo(19f, 33f); pa.close()
        q.color = Color.WHITE; cv.drawPath(pa, q)
    }

    private fun aud(cv: Canvas) {
        fillRound(cv, 5f, 5f, 43f, 43f, 11f, 0xFFEC407A.toInt())
        NlIcons.path("aud")?.let { cv.save(); cv.translate(10f, 10f); cv.scale(28f / 24f, 28f / 24f); q.style = Paint.Style.FILL; q.color = Color.WHITE; cv.drawPath(it, q); cv.restore() }
    }

    // ======================= additional icons =======================

    /** White folded page with a coloured label band (HTML, CSS, JS, KT, SQL, XML ...). */
    private fun tag(cv: Canvas, t: Tag) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 10f, 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), BORDER)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.8f; q.color = 0xFFCFD8DC.toInt()
        cv.drawLine(13f, 10f, 26f, 10f, q); cv.drawLine(13f, 15f, 22f, 15f, q)
        fillRound(cv, 3f, 21f, 45f, 35f, 2.5f, t.bg)
        q.typeface = bold; q.textSize = 11.5f
        val tw = q.measureText(t.label)
        val sz = if (tw > 36f) 11.5f * 36f / tw else 11.5f
        letter(cv, t.label, 24f, 28.2f, sz, t.fg)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.6f; q.color = (t.bg and 0x00FFFFFF) or 0x66000000
        cv.drawLine(13f, 39f, 35f, 39f, q); cv.drawLine(13f, 42f, 28f, 42f, q)
        q.style = Paint.Style.FILL
    }

    /** Terminal window: sh / bash / zsh (green prompt), bat / cmd (black), ps1 (PowerShell blue). */
    private fun term(cv: Canvas, e: String) {
        val bg = when (e) { "ps1" -> 0xFF012456.toInt(); "bat", "cmd" -> 0xFF000000.toInt(); else -> 0xFF263238.toInt() }
        val txt = when (e) { "ps1" -> "PS>"; "bat", "cmd" -> "C:\\>"; else -> ">_" }
        val acc = when (e) { "ps1", "bat", "cmd" -> 0xFFFFFFFF.toInt(); else -> 0xFF69F0AE.toInt() }
        fillRound(cv, 4f, 7f, 44f, 41f, 4f, bg)
        q.style = Paint.Style.FILL
        q.color = 0xFFFF5F56.toInt(); cv.drawCircle(9.5f, 12f, 1.4f, q)
        q.color = 0xFFFFBD2E.toInt(); cv.drawCircle(14f, 12f, 1.4f, q)
        q.color = 0xFF27C93F.toInt(); cv.drawCircle(18.5f, 12f, 1.4f, q)
        letter(cv, txt, 24f, 27.5f, if (txt.length > 2) 12.5f else 16f, acc)
        q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = 0x55FFFFFF; q.strokeCap = Paint.Cap.ROUND
        cv.drawLine(11f, 35f, 26f, 35f, q)
        q.strokeCap = Paint.Cap.BUTT; q.style = Paint.Style.FILL
    }

    /** Subtitles / lyrics: dark screen with two caption lines. */
    private fun subtitle(cv: Canvas) {
        fillRound(cv, 4f, 9f, 44f, 39f, 4f, 0xFF37474F.toInt())
        fillRound(cv, 4f, 9f, 44f, 14f, 4f, 0xFF455A64.toInt()); cv.drawRect(4f, 11f, 44f, 14f, q)
        fillRound(cv, 9f, 22f, 22f, 26.5f, 2.2f, Color.WHITE)
        fillRound(cv, 24f, 22f, 39f, 26.5f, 2.2f, Color.WHITE)
        fillRound(cv, 13f, 29.5f, 35f, 34f, 2.2f, Color.WHITE)
    }

    /** Calendar page with red header (ics). */
    private fun calendar(cv: Canvas) {
        fillRound(cv, 6f, 6f, 42f, 44f, 4f, Color.WHITE)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = BORDER; rf.set(6f, 6f, 42f, 44f); cv.drawRoundRect(rf, 6f, 6f, q)
        fillRound(cv, 6f, 6f, 42f, 18f, 4f, 0xFFE53935.toInt())
        q.style = Paint.Style.FILL; q.color = 0xFFE53935.toInt(); cv.drawRect(6f, 13f, 42f, 18f, q)
        fillRound(cv, 14f, 3f, 17.5f, 10f, 1.7f, 0xFF9E9E9E.toInt())
        fillRound(cv, 30.5f, 3f, 34f, 10f, 1.7f, 0xFF9E9E9E.toInt())
        letter(cv, "31", 24f, 31.5f, 17f, 0xFF424242.toInt())
    }

    /** Contact card (vcf). */
    private fun contact(cv: Canvas) {
        fillRound(cv, 4f, 9f, 44f, 39f, 4f, 0xFF1E88E5.toInt())
        q.style = Paint.Style.FILL; q.color = Color.WHITE
        cv.drawCircle(15f, 20f, 4.5f, q)
        fillRound(cv, 9f, 26.5f, 21f, 33.5f, 3.5f, Color.WHITE)
        fillRound(cv, 27f, 17.5f, 39f, 20.5f, 1.5f, 0xDDFFFFFF.toInt())
        fillRound(cv, 27f, 23.5f, 39f, 26.5f, 1.5f, 0xDDFFFFFF.toInt())
        fillRound(cv, 27f, 29.5f, 35f, 32.5f, 1.5f, 0xDDFFFFFF.toInt())
    }

    /** Envelope (eml / msg). */
    private fun mail(cv: Canvas) {
        fillRound(cv, 4f, 10f, 44f, 38f, 3.5f, 0xFF42A5F5.toInt())
        pa.reset(); pa.moveTo(5.5f, 13f); pa.lineTo(24f, 27.5f); pa.lineTo(42.5f, 13f)
        q.style = Paint.Style.STROKE; q.strokeWidth = 2.2f; q.color = Color.WHITE; q.strokeJoin = Paint.Join.ROUND; q.strokeCap = Paint.Cap.ROUND
        cv.drawPath(pa, q)
        q.strokeJoin = Paint.Join.MITER; q.strokeCap = Paint.Cap.BUTT; q.style = Paint.Style.FILL
    }

    /** Playlist (m3u / m3u8 / pls). */
    private fun playlist(cv: Canvas) {
        fillRound(cv, 4f, 8f, 44f, 40f, 5f, 0xFF8E24AA.toInt())
        q.style = Paint.Style.STROKE; q.strokeWidth = 2.6f; q.color = Color.WHITE; q.strokeCap = Paint.Cap.ROUND
        cv.drawLine(10f, 16f, 29f, 16f, q); cv.drawLine(10f, 23f, 29f, 23f, q); cv.drawLine(10f, 30f, 21f, 30f, q)
        q.strokeCap = Paint.Cap.BUTT; q.style = Paint.Style.FILL
        pa.reset(); pa.moveTo(30f, 27f); pa.lineTo(40.5f, 32.5f); pa.lineTo(30f, 38f); pa.close()
        cv.drawPath(pa, q)
    }

    /** Optical disc image (iso / dmg). */
    private fun disc(cv: Canvas) {
        q.style = Paint.Style.FILL; q.color = 0xFFCFD8DC.toInt(); cv.drawCircle(24f, 24f, 19.5f, q)
        q.color = 0x66FFFFFF; rf.set(4.5f, 4.5f, 43.5f, 43.5f); cv.drawArc(rf, 200f, 55f, true, q); cv.drawArc(rf, 20f, 55f, true, q)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.2f; q.color = 0xFF90A4AE.toInt(); cv.drawCircle(24f, 24f, 19.5f, q)
        q.style = Paint.Style.FILL; q.color = 0xFFECEFF1.toInt(); cv.drawCircle(24f, 24f, 7.5f, q)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = 0xFF90A4AE.toInt(); cv.drawCircle(24f, 24f, 7.5f, q)
        q.style = Paint.Style.FILL; q.color = Color.WHITE; cv.drawCircle(24f, 24f, 3.2f, q)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; cv.drawCircle(24f, 24f, 3.2f, q)
        q.style = Paint.Style.FILL
    }

    /** Windows program window with the four-pane logo (exe / msi). */
    private fun exe(cv: Canvas) {
        fillRound(cv, 4f, 7f, 44f, 41f, 4f, Color.WHITE)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = BORDER; rf.set(4f, 7f, 44f, 41f); cv.drawRoundRect(rf, 6f, 6f, q)
        fillRound(cv, 4f, 7f, 44f, 17f, 4f, 0xFF1976D2.toInt())
        q.style = Paint.Style.FILL; q.color = 0xFF1976D2.toInt(); cv.drawRect(4f, 13f, 44f, 17f, q)
        q.color = 0xAAFFFFFF.toInt(); cv.drawCircle(9.5f, 12f, 1.3f, q); cv.drawCircle(13.5f, 12f, 1.3f, q); cv.drawCircle(17.5f, 12f, 1.3f, q)
        q.color = 0xFFF25022.toInt(); cv.drawRect(16f, 21f, 23.2f, 28f, q)
        q.color = 0xFF7FBA00.toInt(); cv.drawRect(24.8f, 21f, 32f, 28f, q)
        q.color = 0xFF00A4EF.toInt(); cv.drawRect(16f, 29.6f, 23.2f, 36.6f, q)
        q.color = 0xFFFFB900.toInt(); cv.drawRect(24.8f, 29.6f, 32f, 36.6f, q)
    }

    /** Font file: page with "Aa". */
    private fun font(cv: Canvas) {
        foldedPage(cv, 8f, 3f, 40f, 45f, 11f, 0xFFFFFFFF.toInt(), 0xFFFFCDD2.toInt(), BORDER)
        letter(cv, "Aa", 24f, 28f, 17f, 0xFFC62828.toInt())
        q.style = Paint.Style.STROKE; q.strokeWidth = 2f; q.color = 0xFFEF9A9A.toInt()
        cv.drawLine(14f, 39f, 34f, 39f, q); q.style = Paint.Style.FILL
    }

    /** E-book (epub / mobi). */
    private fun book(cv: Canvas) {
        fillRound(cv, 9f, 4f, 39f, 44f, 3f, 0xFF2E7D32.toInt())
        fillRound(cv, 9f, 4f, 16f, 44f, 3f, 0xFF1B5E20.toInt()); q.color = 0xFF1B5E20.toInt(); cv.drawRect(12f, 4f, 16f, 44f, q)
        fillRound(cv, 21f, 13f, 35f, 16.5f, 1.7f, 0xFFA5D6A7.toInt())
        fillRound(cv, 21f, 20f, 32f, 23.5f, 1.7f, 0xFFA5D6A7.toInt())
        fillRound(cv, 21f, 34f, 35f, 36f, 1f, 0x88FFFFFF.toInt())
    }

    /** Database cylinder (db / sqlite). */
    private fun database(cv: Canvas) {
        q.style = Paint.Style.FILL; q.color = 0xFF0288D1.toInt()
        cv.drawRect(9f, 12f, 39f, 36f, q)
        rf.set(9f, 31f, 39f, 41f); cv.drawOval(rf, q)
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.4f; q.color = 0xFF01579B.toInt()
        rf.set(9f, 17f, 39f, 27f); cv.drawArc(rf, 0f, 180f, false, q)
        rf.set(9f, 25f, 39f, 35f); cv.drawArc(rf, 0f, 180f, false, q)
        q.style = Paint.Style.FILL; q.color = 0xFF4FC3F7.toInt()
        rf.set(9f, 7f, 39f, 17f); cv.drawOval(rf, q)
    }
}
