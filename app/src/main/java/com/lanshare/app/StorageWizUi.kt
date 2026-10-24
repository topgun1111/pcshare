package com.lanshare.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
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

/** Rounded tile: folder glyph (kind 0), a short label such as "JPG" (kind 1) in the colour of its type, or the real picture / video frame when [bmp] is set. */
internal class WizGlyph(ctx: Context) : View(ctx) {
    private var kind = 0
    private var col = Color.GRAY
    private var text = ""
    private var bmp: Bitmap? = null
    private var video = false
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val path = Path()
    private val src = Rect()

    fun set(kind: Int, col: Int, text: String = "", bmp: Bitmap? = null, video: Boolean = false) {
        this.kind = kind; this.col = col; this.text = text; this.bmp = bmp; this.video = video; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL
        val b = bmp
        if (b != null && !b.isRecycled) {
            // centre-crop the picture into a rounded tile
            r.set(0f, 0f, w, h)
            path.reset(); path.addRoundRect(r, 12f * d, 12f * d, Path.Direction.CW)
            val side = minOf(b.width, b.height)
            src.set((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
            c.save(); c.clipPath(path); c.drawBitmap(b, src, r, bp); c.restore()
            if (video) {
                p.color = Color.argb(150, 0, 0, 0); c.drawCircle(w / 2f, h / 2f, 11f * d, p)
                p.color = Color.WHITE
                path.reset(); path.moveTo(w / 2f - 3.5f * d, h / 2f - 5.5f * d); path.lineTo(w / 2f + 6f * d, h / 2f); path.lineTo(w / 2f - 3.5f * d, h / 2f + 5.5f * d); path.close()
                c.drawPath(path, p)
            }
            return
        }
        p.color = ColorUtils.setAlphaComponent(col, 56)
        r.set(0f, 0f, w, h); c.drawRoundRect(r, 12f * d, 12f * d, p)
        p.color = col
        if (kind == 0) {
            val l = w * 0.24f; val rt = w * 0.76f; val t = h * 0.30f; val bt = h * 0.72f
            path.reset(); path.addRoundRect(RectF(l, t, w * 0.46f, h * 0.44f), floatArrayOf(3f * d, 3f * d, 3f * d, 3f * d, 0f, 0f, 0f, 0f), Path.Direction.CW)
            c.drawPath(path, p)
            r.set(l, h * 0.38f, rt, bt); c.drawRoundRect(r, 3.5f * d, 3.5f * d, p)
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

/** Small picture / video thumbnails for the list rows: decoded in the background, kept in a memory cache, [done] runs on the main thread. */
internal object WizThumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())      // could not be decoded: never asked again
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())        // queued or running
    private val pending = java.util.concurrent.atomic.AtomicInteger()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = java.util.concurrent.ThreadPoolExecutor(2, 2, 5L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(), java.util.concurrent.ThreadFactory { r -> Thread(r, "wiz-rowthumb").also { it.isDaemon = true } })

    /** 0 none, 1 picture, 2 video. */
    fun kind(ext: String): Int = when (ext) {
        "jpg", "jpeg", "jfif", "png", "webp", "gif", "bmp", "heic", "heif" -> 1
        "mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp" -> 2
        else -> 0
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun request(key: String, kind: Int, file: () -> java.io.File?, done: () -> Unit) {
        if (key in missing || key in asked || pending.get() >= 48) return     // while scrolling fast the rest is asked again when the rows are bound again
        asked.add(key); pending.incrementAndGet()
        pool.execute {
            var bm: Bitmap? = null
            try {
                val f = file()
                if (f != null && f.isFile) bm = if (kind == 1) image(f, 160) else video(f, 160)
            } catch (_: Throwable) {}
            val res = bm
            ui.post {
                pending.decrementAndGet(); asked.remove(key)
                if (res == null) missing.add(key) else { cache.put(key, res); done() }
            }
        }
    }

    private fun image(f: java.io.File, side: Int): Bitmap? {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var ss = 1
        while (maxOf(o.outWidth, o.outHeight) / (ss * 2) >= side) ss *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = ss; o2.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }

    private fun video(f: java.io.File, side: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val fr = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.getFrameAtTime(0L) ?: return null
            val m = maxOf(fr.width, fr.height)
            if (m <= side) return fr
            val k = side.toFloat() / m
            return Bitmap.createScaledBitmap(fr, (fr.width * k).toInt().coerceAtLeast(1), (fr.height * k).toInt().coerceAtLeast(1), true)
        } finally { try { r.release() } catch (_: Throwable) {} }
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
