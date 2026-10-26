package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.ImgSearch
import com.lanshare.app.core.IndexManager
import java.util.concurrent.Executors

/**
 * "Search index" screen (replaces the old text dialog).
 * Opening it STARTS every indexing job at once (names, text inside files, picture meaning, picture text) and then shows the four jobs live:
 * a ring + bar per job, what it is doing right now, and how much it has finished. Polls every second; nothing here blocks the UI thread.
 */
class UsIndexActivity : Activity() {
    private lateinit var p: AppPal.P
    private var night = false
    private var d = 1f
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val cards = ArrayList<Card>()
    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroBar: Bar
    private lateinit var detailBox: LinearLayout
    private var detailSig = ""
    @Volatile private var polling = false

    private val green = 0xFF2E9E5B.toInt()
    private val amber = 0xFFE0A000.toInt()
    private val red = 0xFFD64545.toInt()

    private fun dp(v: Float) = Math.round(v * d)
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    // ------------------------------------------------------------------ data

    private class Row(val mode: String, val frac: Float, val count: String, val line: String)   // mode: run | done | wait | off | err ; frac -1 = unknown
    private class Snap(val rows: List<Row>, val details: List<Pair<String, String>>)

    private fun n(v: Int) = String.format(java.util.Locale.US, "%,d", v.coerceAtLeast(0))
    private fun clean(s: String?) = if (s == null || s == "null") "" else s

    private fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val m = (System.currentTimeMillis() - ms) / 60000
        return if (m < 1) "just now" else if (m < 60) "$m min ago" else if (m < 2880) (m / 60).toString() + " h ago" else (m / 1440).toString() + " days ago"
    }

    private fun tail(p0: String): String {
        if (p0.isEmpty()) return ""
        val s = p0.trimEnd('/').substringAfterLast('/')
        return if (s.isEmpty()) "" else "\n" + s
    }

    private fun snapshot(): Snap {
        val st = IndexManager.status()
        val c = st.optJSONObject("content")
        val enabled = st.optBoolean("enabled")
        val state = st.optString("state"); val ph = st.optString("phase"); val complete = st.optBoolean("complete")
        val ents = st.optInt("entries") - st.optInt("dead")
        val paused = state == "paused"
        val why = clean(st.optString("wait")).ifBlank { "by you" }

        val names = when {
            !enabled -> Row("off", 0f, "Off", "Turn it on in Settings \u203A Search index")
            paused -> Row("wait", 0f, n(ents), "Paused: $why")
            state == "scanning" && ph != "content" -> {
                val pct = st.optInt("pct", -1)
                Row("run", if (pct >= 0) pct / 100f else -1f, n(ents), (if (!complete) "Building the first index" else "Checking folders for changes") + tail(clean(st.optString("current"))))
            }
            !complete -> Row("wait", 0f, n(ents), "Starting\u2026")
            else -> Row("done", 1f, n(ents), "Up to date \u00B7 checked " + ago(st.optLong("lastDelta")))
        }

        val tOn = c != null && c.optBoolean("on")
        val text = when {
            !enabled || !tOn -> Row("off", 0f, "Off", "Turn it on in Settings \u203A Search index")
            ph == "content" -> {
                val todo = c!!.optInt("todo"); val done = c.optInt("done")
                Row("run", if (todo > 0) (done.toFloat() / todo).coerceIn(0f, 1f) else -1f, n(c.optInt("docs")), "Reading files: " + n(done) + " of " + n(todo) + tail(clean(st.optString("current"))))
            }
            paused -> Row("wait", 0f, n(c!!.optInt("docs")), "Paused: $why")
            !complete -> Row("wait", 0f, n(c!!.optInt("docs")), "Starts as soon as the names are done")
            c!!.optBoolean("budgetHit") -> Row("wait", 0f, n(c.optInt("docs")), "Storage limit of " + c.optInt("budgetMb") + " MB reached \u2013 raise it in Settings")
            c.optBoolean("want") -> Row("wait", 0f, n(c.optInt("docs")), "Queued \u2013 starting")
            else -> Row("done", 1f, n(c.optInt("docs")), "Up to date \u00B7 text and Office files up to " + c.optInt("maxMb") + " MB")
        }

        var ai: Row; var ocr: Row
        val im = try { ImgSearch.status() } catch (_: Throwable) { null }
        if (im == null) { ai = Row("off", 0f, "\u2013", "Not available"); ocr = ai }
        else {
            val noModel = im.isNull("engine")
            val mdl = im.optJSONObject("model")
            val running = im.optString("state") == "running"
            val total = im.optInt("total"); val done = im.optInt("done")
            val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
            val err = clean(im.optString("error"))
            val failed = im.optInt("failed")
            when {
                noModel && mdl != null && mdl.optBoolean("installing") -> { ai = Row("run", -1f, "\u2013", "Downloading the picture model\u2026"); ocr = Row("wait", 0f, "\u2013", "Waits for the picture model") }
                noModel -> { ai = Row("off", 0f, "\u2013", "Picture model not installed \u2013 get it in Settings"); ocr = Row("off", 0f, "\u2013", "Needs the picture model first") }
                running -> {
                    val m = clean(im.optString("msg"))
                    ai = Row("run", frac, n(im.optInt("indexed")), if (total > 0) "Analysing pictures: " + n(done) + " of " + n(total) else m.ifBlank { "Looking for pictures\u2026" })
                    ocr = if (im.optBoolean("ocr")) Row("run", frac, n(im.optInt("withText")), if (total > 0) "Reading text in the same pass: " + n(done) + " of " + n(total) else "Starts with the picture pass")
                          else Row("off", 0f, n(im.optInt("withText")), "Text reading is not available on this phone")
                }
                err.isNotEmpty() -> { ai = Row("err", 0f, n(im.optInt("indexed")), err); ocr = Row("err", 0f, n(im.optInt("withText")), "Stopped \u2013 see the picture meaning card") }
                else -> {
                    ai = Row("done", 1f, n(im.optInt("indexed")), "Up to date" + (if (failed > 0) " \u00B7 " + n(failed) + " unreadable" else ""))
                    ocr = if (im.optBoolean("ocr")) Row("done", 1f, n(im.optInt("withText")), "Up to date \u00B7 pictures without text are normal")
                          else Row("off", 0f, n(im.optInt("withText")), "Text reading is not available on this phone")
                }
            }
        }

        val det = ArrayList<Pair<String, String>>()
        if (enabled) {
            val inc = st.optJSONArray("included"); val exc = st.optJSONArray("excluded")
            det.add("Folders" to (if (inc == null || inc.length() == 0) "Whole phone" else (0 until inc.length()).joinToString(", ") { inc.optString(it) }))
            if (exc != null && exc.length() > 0) det.add("Left out" to (0 until exc.length()).joinToString(", ") { exc.optString(it) })
            det.add("Last full scan" to ago(st.optLong("lastFull")))
            det.add("Last check" to ago(st.optLong("lastDelta")))
            if (st.optInt("pending") > 0) det.add("Changed folders waiting" to n(st.optInt("pending")))
            if (st.optInt("unreadable") > 0) det.add("Folders not readable" to n(st.optInt("unreadable")))
            if (st.optInt("dirs") > 0) det.add("Folders indexed" to n(st.optInt("dirs")))
            if (c != null && tOn) det.add("Text storage limit" to (c.optInt("budgetMb").toString() + " MB"))
            val e = clean(st.optString("error")); if (e.isNotEmpty()) det.add("Last error" to e)
        }
        return Snap(listOf(names, text, ai, ocr), det)
    }

    // ------------------------------------------------------------------ views

    private fun colorOf(mode: String) = when (mode) { "run" -> p.ac; "done" -> green; "wait" -> amber; "err" -> red; else -> p.mut }

    private class Ring(c: Context) : View(c) {
        var mode = "off"; var frac = 0f; var tint = 0; var pct = ""
        private val pa = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val s = w * 0.09f
            val r = RectF(s, s, w - s, w - s)
            pa.strokeWidth = s
            pa.color = (tint and 0x00FFFFFF) or 0x30000000; cv.drawArc(r, 0f, 360f, false, pa)
            pa.color = tint
            if (mode == "run") {
                if (frac < 0f) { val a = (SystemClock.uptimeMillis() % 1100) / 1100f * 360f; cv.drawArc(r, a, 100f, false, pa); postInvalidateOnAnimation() }
                else cv.drawArc(r, -90f, 360f * frac, false, pa)
            } else if (mode != "off") cv.drawArc(r, 0f, 360f, false, pa)
            pa.strokeWidth = s * 0.9f
            val m = w / 2f
            when (mode) {
                "done" -> { val ph = Path(); ph.moveTo(w * 0.31f, m); ph.lineTo(w * 0.45f, w * 0.63f); ph.lineTo(w * 0.70f, w * 0.37f); cv.drawPath(ph, pa) }
                "wait" -> { cv.drawLine(w * 0.40f, w * 0.34f, w * 0.40f, w * 0.66f, pa); cv.drawLine(w * 0.60f, w * 0.34f, w * 0.60f, w * 0.66f, pa) }
                "off" -> cv.drawLine(w * 0.36f, m, w * 0.64f, m, pa)
                "err" -> { cv.drawLine(m, w * 0.30f, m, w * 0.56f, pa); cv.drawPoint(m, w * 0.70f, pa) }
                else -> if (pct.isNotEmpty()) { pf.color = tint; pf.typeface = Fnt.semi(); pf.textSize = w * 0.27f; cv.drawText(pct, m, m - (pf.ascent() + pf.descent()) / 2f, pf) }
            }
        }
    }

    private class Bar(c: Context) : View(c) {
        var frac = 0f; var tint = 0; var track = 0
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            pt.color = track; cv.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, pt)
            pt.color = tint
            if (frac < 0f) {
                val t = (SystemClock.uptimeMillis() % 1400) / 1400f; val seg = w * 0.3f; val x = -seg + (w + seg) * t
                cv.save(); cv.clipRect(0f, 0f, w, h); cv.drawRoundRect(x, 0f, x + seg, h, h / 2, h / 2, pt); cv.restore(); postInvalidateOnAnimation()
            } else if (frac > 0f) cv.drawRoundRect(0f, 0f, w * frac.coerceAtMost(1f), h, h / 2, h / 2, pt)
        }
    }

    private fun tv(size: Float, col: Int, bold: Boolean = false, lines: Int = 1): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(col); typeface = if (bold) Fnt.semi() else Fnt.med()
        if (lines == 1) isSingleLine = true else maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun card(bg: Int, r: Float = 20f) = GradientDrawable().apply { setColor(bg); cornerRadius = dp(r).toFloat() }

    private inner class Card(val title: String, val sub: String) {
        val root = LinearLayout(this@UsIndexActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(p.cont); setPadding(dp(14f), dp(14f), dp(16f), dp(14f)) }
        val ring = Ring(this@UsIndexActivity)
        val count = tv(17f, p.fg, true)
        val line = tv(12.5f, p.mut, false, 2)
        val bar = Bar(this@UsIndexActivity).apply { track = (p.mut and 0x00FFFFFF) or 0x30000000 }
        init {
            root.addView(ring, LinearLayout.LayoutParams(dp(54f), dp(54f)))
            val col = LinearLayout(this@UsIndexActivity).apply { orientation = LinearLayout.VERTICAL }
            val top = LinearLayout(this@UsIndexActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = LinearLayout(this@UsIndexActivity).apply { orientation = LinearLayout.VERTICAL }
            t.addView(tv(15f, p.fg, true).also { it.text = title }); t.addView(tv(11.5f, p.mut).also { it.text = sub })
            top.addView(t, LinearLayout.LayoutParams(0, -2, 1f)); count.gravity = Gravity.END; top.addView(count)
            col.addView(top)
            line.setPadding(0, dp(6f), 0, 0); col.addView(line)
            col.addView(bar, LinearLayout.LayoutParams(-1, dp(5f)).apply { topMargin = dp(8f) })
            root.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14f) })
        }
        fun set(r: Row) {
            val c = colorOf(r.mode)
            ring.mode = r.mode; ring.frac = r.frac; ring.tint = c; ring.pct = if (r.mode == "run" && r.frac >= 0f) (r.frac * 100).toInt().toString() + "%" else ""; ring.invalidate()
            count.text = r.count; line.text = r.line
            bar.visibility = if (r.mode == "run") View.VISIBLE else View.GONE
            bar.frac = r.frac; bar.tint = c; bar.invalidate()
        }
    }

    private fun button(label: String, filled: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); typeface = Fnt.semi(); isSingleLine = true
        setTextColor(if (filled) p.onac else p.ac)
        background = if (filled) card(p.ac, 24f) else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(24f).toFloat(); setStroke(dp(1.5f), p.ac) }
        setOnClickListener { click() }
    }

    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg); fitsSystemWindows = true }
        // top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), dp(4f), dp(16f), dp(4f)) }
        val back = object : View(this) {
            val pa = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = p.fg }
            override fun onDraw(cv: Canvas) { val cx = width / 2f; val cy = height / 2f; val a = dp(9f).toFloat(); cv.drawLine(cx + a, cy, cx - a, cy, pa); cv.drawLine(cx - a, cy, cx - a * 0.1f, cy - a * 0.9f, pa); cv.drawLine(cx - a, cy, cx - a * 0.1f, cy + a * 0.9f, pa) }
        }
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        bar.addView(tv(20f, p.fg, true).also { it.text = "Search index" }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4f) })
        root.addView(bar)

        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(4f), dp(14f), dp(16f)) }
        // hero
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(p.ac, 28f); setPadding(dp(20f), dp(18f), dp(20f), dp(18f)) }
        heroTitle = tv(21f, p.onac, true); heroSub = tv(13f, p.onac, false, 3).apply { setPadding(0, dp(4f), 0, 0) }
        heroBar = Bar(this).apply { tint = p.onac; track = (p.onac and 0x00FFFFFF) or 0x40000000 }
        hero.addView(heroTitle); hero.addView(heroSub); hero.addView(heroBar, LinearLayout.LayoutParams(-1, dp(6f)).apply { topMargin = dp(14f) })
        col.addView(hero)
        // the four jobs
        val defs = listOf("Names" to "Files and folders", "Text inside files" to "Text, code, Word, Excel, PDF\u2026", "Picture meaning" to "AI: find pictures by what they show", "Text in pictures" to "OCR: find pictures by the words in them")
        for ((t, s) in defs) { val c = Card(t, s); cards.add(c); col.addView(c.root, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) }) }
        // details
        col.addView(tv(12f, p.mut, true).also { it.text = "DETAILS"; it.setPadding(dp(6f), dp(20f), 0, dp(6f)) })
        detailBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(p.cont); setPadding(dp(16f), dp(8f), dp(16f), dp(8f)) }
        col.addView(detailBox)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        // bottom actions
        val act = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14f), dp(8f), dp(14f), dp(10f)); setBackgroundColor(p.bg) }
        act.addView(button("Run everything now", true) { kick(true) }, LinearLayout.LayoutParams(0, dp(48f), 1.3f))
        act.addView(button("Full rescan", false) {
            AppPal.dlg(this).setTitle("Rescan the whole phone?").setMessage("Re-reads every folder. It can take a few minutes; searching keeps working meanwhile.")
                .setPositiveButton("Rescan") { _, _ -> IndexManager.rescan(); kick(false); toast("Rescanning the phone") }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { leftMargin = dp(10f) })
        root.addView(act)
        setContentView(root)
    }

    private fun render(s: Snap) {
        for (i in cards.indices) cards[i].set(s.rows[i])
        val run = s.rows.filter { it.mode == "run" }
        val wait = s.rows.firstOrNull { it.mode == "wait" }
        val err = s.rows.firstOrNull { it.mode == "err" }
        val live = s.rows.filter { it.mode != "off" }
        val overall = if (live.isEmpty()) 0f else live.map { when (it.mode) { "done" -> 1f; "run" -> maxOf(it.frac, 0f); else -> 0f } }.average().toFloat()
        when {
            run.isNotEmpty() -> {
                heroTitle.text = "Indexing " + run.size + (if (run.size == 1) " job" else " jobs") + " now"
                heroSub.text = "You can search while it runs \u2013 results get better as it goes."
                heroBar.frac = if (run.all { it.frac < 0f }) -1f else overall
            }
            err != null -> { heroTitle.text = "Something stopped"; heroSub.text = err.line; heroBar.frac = overall }
            wait != null -> { heroTitle.text = "Waiting"; heroSub.text = wait.line; heroBar.frac = overall }
            live.isEmpty() -> { heroTitle.text = "Search index is off"; heroSub.text = "Turn it on in Settings \u203A Search index."; heroBar.frac = 0f }
            else -> { heroTitle.text = "Everything is up to date"; heroSub.text = "Names, texts and pictures are all searchable."; heroBar.frac = 1f }
        }
        heroBar.invalidate()
        val sig = s.details.joinToString("|") { it.first + "=" + it.second }
        if (sig != detailSig) {
            detailSig = sig; detailBox.removeAllViews()
            if (s.details.isEmpty()) detailBox.addView(tv(13f, p.mut).also { it.text = "Index is off" })
            for ((k, v) in s.details) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7f), 0, dp(7f)) }
                r.addView(tv(13f, p.mut).also { it.text = k }, LinearLayout.LayoutParams(0, -2, 0.9f))
                r.addView(tv(13f, p.fg, false, 3).also { it.text = v; it.gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1.4f))
                detailBox.addView(r)
            }
        }
    }

    // ------------------------------------------------------------------ life cycle

    /** Starts every job. Safe to call again and again: running jobs are left alone, finished ones only do a cheap check. */
    private fun kick(manual: Boolean) {
        exec.execute {
            try { IndexManager.kickAll() } catch (_: Throwable) {}
            try { ImgSearch.kickAll(manual) } catch (_: Throwable) {}
            if (manual) ui.post { toast("Started: names, texts, picture meaning and picture text") }
            ui.post { poll() }
        }
    }

    private val tick = object : Runnable { override fun run() { poll(); ui.postDelayed(this, 1000) } }

    private fun poll() {
        if (polling) return
        polling = true
        exec.execute {
            val s = try { snapshot() } catch (_: Throwable) { null }
            polling = false
            if (s != null) ui.post { if (!isFinishing) render(s) }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        d = resources.displayMetrics.density
        Fnt.init(applicationContext)
        p = AppPal.pal(this); night = AppPal.night(this)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(p.bg))
        window.statusBarColor = p.bg; window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility
            f = if (!night) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) f = if (!night) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = f
        }
        build()
        if (b == null) kick(false)   // the moment the screen opens: start names + texts + picture meaning + picture text
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); exec.shutdown() }

    companion object {
        fun intent(c: Context): android.content.Intent = android.content.Intent(c, UsIndexActivity::class.java)
    }
}
