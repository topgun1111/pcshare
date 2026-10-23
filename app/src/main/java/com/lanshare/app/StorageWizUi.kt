package com.lanshare.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils

/*
 * Storage Analyzer look: own themes (picked in the screen's menu, kept in storage_ui / "wiztheme"; "app" = follow the app theme like before)
 * and the small custom views of the cards (type tile, usage ring, category bar, theme preview). NOT compiled on a device here.
 */

internal class WizTheme(
    val id: String, val name: String,
    val bg: Int, val cont: Int, val fg: Int, val mut: Int, val bd: Int, val ac: Int, val onac: Int,
    val pal: IntArray, val folder: Int
) {
    val cat: IntArray get() = intArrayOf(WizUtil.GRAY, pal[2], pal[3], pal[4], pal[5], pal[0], pal[1])   // SCat order: other, img, vid, aud, doc, arc, app
}

internal object WizThemes {
    private fun c(v: Long) = v.toInt()
    private fun pal(vararg b: Long): IntArray {
        val base = b.map { c(it) }
        return (base + base.map { WizUtil.shade(it, 0.72f) }).toIntArray()
    }

    val ALL: List<WizTheme> = listOf(
        WizTheme("midnight", "Midnight", c(0xFF0B1220), c(0xFF152038), c(0xFFE6EDF7), c(0xFF8FA0BD), c(0xFF26354F), c(0xFF4DA3FF), c(0xFF06101F),
            pal(0xFF4DA3FF, 0xFF38D6E8, 0xFF7C8CFF, 0xFFB18CFF, 0xFFFF7AC8, 0xFF5EE6B0, 0xFFFFC857, 0xFFFF8A65), c(0xFF5BB0FF)),
        WizTheme("ember", "Ember", c(0xFF17110E), c(0xFF261B16), c(0xFFF6EBE3), c(0xFFB49A8B), c(0xFF3D2C24), c(0xFFFF7A3C), c(0xFF1A0E08),
            pal(0xFFFF7A3C, 0xFFFFB347, 0xFFFF5A5F, 0xFFE86AA6, 0xFFC77DFF, 0xFFF4D35E, 0xFF8CC084, 0xFF5CC8D7), c(0xFFFFB347)),
        WizTheme("forest", "Forest", c(0xFF0E1713), c(0xFF17261E), c(0xFFE3F2E9), c(0xFF8FB09E), c(0xFF25392E), c(0xFF3DDC84), c(0xFF05160C),
            pal(0xFF3DDC84, 0xFFA3E635, 0xFF2DD4BF, 0xFF38BDF8, 0xFFFACC15, 0xFFFB923C, 0xFFF472B6, 0xFFA78BFA), c(0xFFA3E635)),
        WizTheme("neon", "Neon", c(0xFF0A0A12), c(0xFF16162B), c(0xFFF2F2FF), c(0xFF9A9AC8), c(0xFF2B2B4D), c(0xFFFF2BD6), c(0xFF12001A),
            pal(0xFFFF2BD6, 0xFF00E5FF, 0xFF7CFF4D, 0xFFFFEA00, 0xFF9D4DFF, 0xFFFF3D71, 0xFF00FFA3, 0xFF3D7BFF), c(0xFF00E5FF)),
        WizTheme("paper", "Paper", c(0xFFF7F3EA), c(0xFFEEE7D6), c(0xFF2B2620), c(0xFF6F6553), c(0xFFDDD3BD), c(0xFF1F6F5C), c(0xFFFFFFFF),
            pal(0xFF1F6F5C, 0xFFC2571A, 0xFF3B6EA8, 0xFF9A4D8F, 0xFFB8860B, 0xFF5B8C3E, 0xFFA8445A, 0xFF4E7F8C), c(0xFFB8860B)),
        WizTheme("terminal", "Terminal", c(0xFF050805), c(0xFF0F170F), c(0xFFC8FFD4), c(0xFF7FB48C), c(0xFF1F3A25), c(0xFF33FF66), c(0xFF021006),
            pal(0xFF33FF66, 0xFFB6FF3B, 0xFF19D3A2, 0xFF4DD8FF, 0xFFFFD93B, 0xFFFF8A3B, 0xFFFF5C8A, 0xFFB28DFF), c(0xFF33FF66))
    )

    private const val P = "storage_ui"
    fun pref(ctx: Context): String = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString("wiztheme", "app") ?: "app"
    fun save(ctx: Context, id: String) { ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("wiztheme", id).apply() }

    /** "App theme": the page palette (AppPal) with the classic WizTree colours. */
    fun app(ctx: Context): WizTheme {
        val p = AppPal.pal(ctx)
        return WizTheme("app", "App theme", p.bg, p.cont, p.fg, p.mut, p.bd, p.ac, p.onac, WizUtil.PAL_DEFAULT, WizUtil.FOLDER_DEFAULT)
    }

    fun resolve(ctx: Context): WizTheme { val id = pref(ctx); return ALL.firstOrNull { it.id == id } ?: app(ctx) }
}

/** Rounded tile: folder glyph (kind 0) or a short label such as "JPG" (kind 1) in the colour of its type. */
internal class WizGlyph(ctx: Context) : View(ctx) {
    private var kind = 0
    private var col = Color.GRAY
    private var text = ""
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()

    fun set(kind: Int, col: Int, text: String = "") { this.kind = kind; this.col = col; this.text = text; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = ColorUtils.setAlphaComponent(col, 56)
        r.set(0f, 0f, w, h); c.drawRoundRect(r, 12f * d, 12f * d, p)
        p.color = col
        if (kind == 0) {
            val l = w * 0.24f; val rt = w * 0.76f; val t = h * 0.30f; val b = h * 0.72f
            path.reset(); path.addRoundRect(RectF(l, t, w * 0.46f, h * 0.44f), floatArrayOf(3f * d, 3f * d, 3f * d, 3f * d, 0f, 0f, 0f, 0f), Path.Direction.CW)
            c.drawPath(path, p)
            r.set(l, h * 0.38f, rt, b); c.drawRoundRect(r, 3.5f * d, 3.5f * d, p)
        } else {
            p.typeface = Typeface.DEFAULT_BOLD
            var ts = 12f * d * UiScale.tf
            p.textSize = ts
            val tw = p.measureText(text)
            if (tw > w * 0.84f) { ts *= w * 0.84f / tw; p.textSize = ts }
            p.textAlign = Paint.Align.CENTER
            c.drawText(text, w / 2f, h / 2f + ts * 0.35f, p)
        }
    }
}

/** Category bar: one rounded bar split into coloured parts (fractions of the whole bar). */
internal class WizSeg(ctx: Context) : View(ctx) {
    var track = Color.DKGRAY
    private var fr = FloatArray(0)
    private var cols = IntArray(0)
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = Path()

    fun set(f: FloatArray, c: IntArray) { fr = f; cols = c; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2f
        r.set(0f, 0f, w, h)
        p.color = track; c.drawRoundRect(r, rad, rad, p)
        clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        var x = 0f
        for (i in fr.indices) {
            val sw = fr[i].coerceIn(0f, 1f) * w
            if (sw < 0.5f) continue
            p.color = cols[i]
            c.drawRect(x, 0f, x + maxOf(sw - 1.5f * d, 1f), h, p)
            x += sw
        }
        c.restore()
    }
}

/** Donut: share of the volume that is used, percentage in the middle. */
internal class WizRing(ctx: Context) : View(ctx) {
    var frac = 0f
    var col = Color.BLUE
    var track = Color.DKGRAY
    var textCol = Color.WHITE
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val sw = 7f * d
        val pad = sw / 2f + 1f * d
        r.set(pad, pad, width - pad, height - pad)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND
        p.color = track; c.drawArc(r, 0f, 360f, false, p)
        p.color = col; c.drawArc(r, -90f, 360f * frac.coerceIn(0.01f, 1f), false, p)
        p.style = Paint.Style.FILL; p.color = textCol; p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 15f * d * UiScale.tf; p.textAlign = Paint.Align.CENTER
        c.drawText(Math.round(frac * 100f).toString() + "%", width / 2f, height / 2f + p.textSize * 0.35f, p)
    }
}

/** Miniature of a theme for the picker. */
internal class WizSwatch(ctx: Context, private val t: WizTheme) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = t.bg; r.set(0f, 0f, w, h); c.drawRoundRect(r, 10f * d, 10f * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = t.bd; r.set(0.75f * d, 0.75f * d, w - 0.75f * d, h - 0.75f * d); c.drawRoundRect(r, 10f * d, 10f * d, p)
        p.style = Paint.Style.FILL
        p.color = t.cont; r.set(6f * d, h * 0.5f, w - 6f * d, h - 6f * d); c.drawRoundRect(r, 5f * d, 5f * d, p)
        p.color = t.ac; r.set(10f * d, h * 0.5f + 5f * d, w * 0.55f, h * 0.5f + 9f * d); c.drawRoundRect(r, 2f * d, 2f * d, p)
        for (i in 0 until 4) { p.color = t.pal[i]; c.drawCircle(10f * d + i * 11f * d, h * 0.26f, 4f * d, p) }
    }
}
