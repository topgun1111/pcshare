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
    private val TXT_X = setOf("txt", "md", "log", "ini", "cfg", "conf")

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
            e in TXT_X -> page(cv, 0xFFFFFFFF.toInt(), 0xFFCFD8DC.toInt(), true)
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
        q.style = Paint.Style.STROKE; q.strokeWidth = 1f; q.color = BORDER; rf.set(14f, 4f, 45f, 44f); cv.drawRoundRect(rf, 3f, 3f, q)
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
        q.style = Paint.Style.STROKE; q.strokeWidth = 1.6f; q.color = Color.WHITE; cv.drawRoundRect(rf, 4f, 4f, q)
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
}
