package com.lanshare.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.lanshare.app.core.Jobs
import org.json.JSONObject

/**
 * Phone twin of the pcprint.py popup window (`--popup`): the same screen, same colours, same words. It polls the PC's GET /live
 * (Jobs.pcLive) twice a second and shows the job step by step: Received -> Preparing pages -> Sending to printer -> Printer is printing -> Finished,
 * pages printed, printer problems, the waiting queue and the live log. Never closes itself; the Close button / Back ends it.
 * Opened automatically when a print is sent to a PC (Jobs.startPrint; the "Don't open automatically" tick box turns that off), by the PC screen's "Job" tab
 * and by the bridge call openPrintLive(dev). Needs pcprint.py v37+ on the PC (older = 404 -> a one-line notice).
 */
class PrintLiveActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var run = false
    @Volatile private var box: JSONObject? = null
    @Volatile private var old = false
    @Volatile private var why = ""
    private var dev = ""
    private var lastRev = -1L
    private var lastState: String? = null
    private var barKey = ""

    private lateinit var icon: TextView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var nameL: TextView
    private lateinit var meta: TextView
    private lateinit var bar: ProgressBar
    private lateinit var prog: TextView
    private lateinit var pline: TextView
    private lateinit var waitL: TextView
    private lateinit var result: TextView
    private lateinit var logTv: TextView
    private lateinit var logSv: ScrollView
    private val srows = ArrayList<Triple<TextView, TextView, TextView>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fnt.init(applicationContext)
        dev = intent.getStringExtra(X_DEV) ?: ""
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }

        // header: big icon + title + sub line
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(2)) }
        icon = tv(ICON["idle"]!!, 30f, DIM, Typeface.DEFAULT)
        val hb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        title = tv("...", 20f, FG, Fnt.semi())
        sub = tv("", 12f, DIM, Fnt.med())
        hb.addView(title); hb.addView(sub)
        head.addView(icon); head.addView(hb, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)

        nameL = tv("", 15f, FG, Fnt.semi()).also { it.setPadding(dp(14), dp(8), dp(14), 0) }
        meta = tv("", 12f, DIM, Fnt.med()).also { it.setPadding(dp(14), 0, dp(14), 0) }
        root.addView(nameL); root.addView(meta)

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true; progressBackgroundTintList = ColorStateList.valueOf(CARD) }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(8)).apply { setMargins(dp(14), dp(10), dp(14), dp(2)) })
        prog = tv("", 12f, FG, Fnt.med()).also { it.setPadding(dp(14), 0, dp(14), 0) }
        root.addView(prog)

        // the five steps in a card
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(8).toFloat() } }
        for (lab in LABELS) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            val sym = tv("\u25cb", 14f, DIM, Typeface.DEFAULT).also { it.minWidth = dp(26) }
            val l = tv(lab, 14f, FG, Fnt.med())
            val d = tv("", 12f, DIM, Fnt.med()).also { it.setPadding(dp(8), 0, 0, 0) }
            r.addView(sym); r.addView(l); r.addView(d, LinearLayout.LayoutParams(0, -2, 1f))
            steps.addView(r); srows.add(Triple(sym, l, d))
        }
        root.addView(steps, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(8), dp(14), dp(8)) })

        pline = tv("", 12f, DIM, Fnt.med()).also { it.setPadding(dp(14), 0, dp(14), 0) }
        waitL = tv("", 12f, DIM, Fnt.med()).also { it.setPadding(dp(14), 0, dp(14), 0) }
        result = tv("", 14f, FG, Fnt.semi()).also { it.setPadding(dp(14), dp(4), dp(14), 0) }
        root.addView(pline); root.addView(waitL); root.addView(result)

        // live log (black box, monospace)
        logTv = tv("", 11f, FG, Typeface.MONOSPACE).also { it.setPadding(dp(8), dp(6), dp(8), dp(6)); it.setTextIsSelectable(true) }
        logSv = ScrollView(this).apply { setBackgroundColor(LOGBG); addView(logTv) }
        root.addView(logSv, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(14), dp(6), dp(14), dp(4)) })

        // bottom row: tick box left, buttons right
        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), dp(10)) }
        val cb = CheckBox(this).apply {
            text = "Don't open automatically"; setTextColor(FG); LumenTypography.fit(this, 12f); typeface = Fnt.med(); buttonTintList = ColorStateList.valueOf(DIM)
            isChecked = noAuto(this@PrintLiveActivity)
            setOnCheckedChangeListener { _, v -> try { getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("noauto", v).apply() } catch (_: Throwable) {} }
        }
        bot.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
        bot.addView(btn("Copy") { copyDetails() })
        bot.addView(btn("Log") { try { PrintLogActivity.open(this, "", dev) } catch (_: Throwable) {} })
        bot.addView(btn("Close") { finish() })
        root.addView(bot)
        setContentView(root)
    }

    private fun tv(t: String, sp: Float, c: Int, tf: Typeface) = TextView(this).apply { text = t; LumenTypography.fit(this, sp); setTextColor(c); typeface = tf }
    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; LumenTypography.fit(this, 12f); setTextColor(FG); typeface = Fnt.med(); minWidth = 0; minimumWidth = 0
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(6).toFloat() }
        setPadding(dp(12), 0, dp(12), 0); setOnClickListener { try { f() } catch (_: Throwable) {} }
    }.also { it.layoutParams = LinearLayout.LayoutParams(-2, dp(36)).apply { leftMargin = dp(6) } }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); i.getStringExtra(X_DEV)?.takeIf { it.isNotEmpty() }?.let { dev = it } }

    override fun onResume() {
        super.onResume()
        cur = this
        run = true
        Thread({
            while (run) {
                try {
                    val r = Jobs.pcLive(dev)
                    old = r.optBoolean("old", false); why = r.optString("why", "")
                    box = if (r.optBoolean("ok", false) && !old) r else null
                } catch (e: Throwable) { box = null; old = false; why = e.message ?: "" }
                ui.post { try { render() } catch (_: Throwable) {} }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }, "print-live").also { it.isDaemon = true }.start()
    }

    override fun onPause() { run = false; if (cur === this) cur = null; super.onPause() }

    private fun size(n0: Double): String {
        var n = n0
        for (u in arrayOf("B", "KB", "MB", "GB")) { if (n < 1024 || u == "GB") return if (u == "B") "${n.toLong()} B" else String.format("%.1f %s", n, u); n /= 1024 }
        return ""
    }

    private fun bar(mode: String, color: Int, v: Int = 0) {
        val key = "$mode/$color"
        if (barKey != key) {
            barKey = key
            bar.isIndeterminate = mode == "indeterminate"
            bar.progressTintList = ColorStateList.valueOf(color); bar.indeterminateTintList = ColorStateList.valueOf(color)
        }
        if (mode == "determinate") bar.progress = v
    }

    private fun render() {
        val s = box
        if (s == null) {   // PC not reachable / pcprint.py too old: stays open until the user closes it
            title.text = if (old) "pcprint.py is too old" else "Print service not reachable"; title.setTextColor(COL["failed"]!!)
            icon.text = ICON["failed"]; icon.setTextColor(COL["failed"]!!)
            sub.text = if (old) "replace it with v37 or newer on the PC" else "waiting for it to come back..." + (if (why.isNotEmpty()) "  \u00b7  $why" else "")
            return
        }
        val st = s.optString("state", "idle")
        val stChanged = st != lastState
        if (stChanged) lastState = st
        val col = COL[st] ?: DIM
        icon.text = ICON[st] ?: "\u25cf"; icon.setTextColor(col)
        title.text = TITLE[st] ?: st; title.setTextColor(col)
        val t0 = s.optDouble("t0", 0.0); val t1 = s.optDouble("t1", 0.0); val now = s.optDouble("now", 0.0)
        val el = if (t0 > 0) (if (t1 > 0) t1 else now) - t0 else 0.0
        if (stChanged && st == "failed" && (t1 <= 0 || now - t1 < 20)) Haptics.play("fail")   // a fresh failure only, not an old one when the screen is opened
        val jid = if (s.isNull("jid")) "" else s.optString("jid")
        sub.text = (if (t0 > 0) "${el.toInt()} s" else "") + (if (jid.isNotEmpty()) "  \u00b7  job $jid" else "")
        val files = s.optJSONArray("files")
        val nf = files?.length() ?: 0
        if (st == "idle") { nameL.text = "No job yet"; meta.text = "" } else {
            var bytes = 0.0; for (i in 0 until nf) bytes += files!!.optJSONObject(i)?.optDouble("bytes", 0.0) ?: 0.0
            val extra = if (nf > 0) "  \u00b7  $nf file${if (nf != 1) "s" else ""}, ${size(bytes)}" else ""
            val opts = s.optString("opts"); val copies = s.optInt("copies", 1)
            nameL.text = s.optString("name")
            meta.text = "Printer: " + s.optString("printer").ifEmpty { s.optString("default_printer").ifEmpty { "default printer" } } +
                (if (opts.isNotEmpty()) "  \u00b7  $opts" else "") + (if (copies > 1 && !opts.contains("copies")) "  \u00b7  $copies copies" else "") + extra
        }
        val pd = if (s.isNull("pages_done")) null else s.optInt("pages_done")
        val pt = if (s.isNull("pages_total")) null else s.optInt("pages_total")
        when {
            st == "done" -> { bar("determinate", COL["done"]!!, 100); prog.text = (if (pt != null && pt > 0) "$pt of $pt pages  \u00b7  " else "") + "finished in ${el.toInt()} s" }
            st == "failed" -> { bar("determinate", COL["failed"]!!, 100); prog.text = "stopped after ${el.toInt()} s" }
            st == "warning" -> { bar("determinate", COL["warning"]!!, 100); prog.text = "handed over after ${el.toInt()} s - check the printer" }
            st == "running" && pt != null && pt > 0 && pd != null -> { bar("determinate", col, minOf(99.0, 100.0 * pd / pt).toInt()); prog.text = "$pd of $pt pages  \u00b7  ${el.toInt()} s" }
            st == "queued" || st == "running" -> { bar("indeterminate", col); prog.text = (if (pd != null && pd > 0) "$pd pages printed  \u00b7  " else "") + "${el.toInt()} s" }
            else -> { bar("determinate", DIM, 0); prog.text = "" }
        }
        val steps = s.optJSONArray("steps")
        for ((i, row) in srows.withIndex()) {
            val o = steps?.optJSONObject(i) ?: continue
            val sst = o.optString("st", "pending")
            val (ch, c) = STEP[sst] ?: STEP["pending"]!!
            row.first.text = ch; row.first.setTextColor(c)
            row.second.setTextColor(if (sst != "pending") FG else DIM)
            row.third.text = o.optString("detail"); row.third.setTextColor(if (sst == "fail") COL["failed"]!! else if (sst == "warn") COL["warning"]!! else DIM)
        }
        val bits = ArrayList<String>()
        val pst = s.optString("pstatus")
        if (pst.isNotEmpty() && st == "running") bits.add("Printer status: $pst")
        val wj = s.optJSONArray("wjobs")
        if (wj != null && wj.length() > 0 && st == "running") bits.add("Windows queue: " + (0 until minOf(3, wj.length())).joinToString(", ") { wj.optString(it) })
        val issue = s.optString("issue").ifEmpty { if (st == "running" || st == "warning") s.optString("problem").let { if (it == "null") "" else it } else "" }
        pline.text = bits.joinToString("  \u00b7  ") + (if (issue.isNotEmpty()) (if (bits.isNotEmpty()) "\n" else "") + "\u26a0 printer: $issue" else "")
        pline.setTextColor(if (issue.isNotEmpty()) COL["warning"]!! else DIM); pline.visibility = if (pline.text.isEmpty()) View.GONE else View.VISIBLE
        val w = s.optJSONArray("waiting")
        waitL.text = if (w != null && w.length() > 0) "Waiting in queue (${w.length()}): " + (0 until minOf(3, w.length())).joinToString(", ") { w.optJSONObject(it)?.optString("name").orEmpty().ifEmpty { "job" } } + (if (w.length() > 3) " ..." else "") else ""
        waitL.visibility = if (waitL.text.isEmpty()) View.GONE else View.VISIBLE
        var msg = ""; var mc = FG
        when {
            st == "failed" -> { msg = s.optString("error").ifEmpty { "unknown error" }; mc = COL["failed"]!! }
            st == "warning" -> { msg = s.optString("warning"); mc = COL["warning"]!! }
            st == "done" && s.optString("note").isNotEmpty() -> { msg = "Note: " + s.optString("note"); mc = COL["warning"]!! }
        }
        result.text = msg; result.setTextColor(mc); result.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        val rev = s.optLong("rev", -1)
        if (rev != lastRev) {
            lastRev = rev
            val sb = SpannableStringBuilder()
            val ls = s.optJSONArray("lines")
            for (i in 0 until (ls?.length() ?: 0)) {
                val a = ls!!.optJSONArray(i) ?: continue
                val start = sb.length
                sb.append(a.optString(0)).append(' ').append(a.optString(2)).append('\n')
                val c = when (a.optString(1)) { "e" -> COL["failed"]!!; "w" -> COL["warning"]!!; else -> FG }
                sb.setSpan(ForegroundColorSpan(c), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            logTv.text = sb
            logSv.post { logSv.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyDetails() {
        val s = box ?: JSONObject()
        val out = ArrayList<String>()
        out.add("${TITLE[s.optString("state")] ?: ""}  ${s.optString("name")}  (${s.optString("jid").ifEmpty { "-" }})")
        out.add("printer: " + s.optString("printer").ifEmpty { s.optString("default_printer") })
        out.add("options: " + s.optString("opts").ifEmpty { "-" })
        val steps = s.optJSONArray("steps"); val labs = LABELS
        for (i in 0 until (steps?.length() ?: 0)) { val o = steps!!.getJSONObject(i); out.add("${labs.getOrElse(i) { o.optString("label") }}: ${o.optString("st")} ${o.optString("detail")}") }
        for (k in arrayOf("error", "warning", "note")) s.optString(k).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        out.add("")
        val ls = s.optJSONArray("lines")
        for (i in 0 until (ls?.length() ?: 0)) { val a = ls!!.optJSONArray(i) ?: continue; out.add("${a.optString(0)} ${a.optString(2)}") }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("print job", out.joinToString("\n")))
        Lm.snack(this, "Copied", android.widget.Toast.LENGTH_SHORT)
    }

    companion object {
        const val X_DEV = "dev"
        private const val PREFS = "print_live"
        @Volatile private var cur: PrintLiveActivity? = null
        // same palette / words as the Tk popup in pcprint.py
        private val BG = Color.parseColor("#10151f"); private val CARD = Color.parseColor("#1b2433"); private val FG = Color.parseColor("#d7dde8")
        private val DIM = Color.parseColor("#8b97ab"); private val LOGBG = Color.parseColor("#0b0f17")
        private val COL = mapOf("queued" to Color.parseColor("#fbbf24"), "running" to Color.parseColor("#60a5fa"), "done" to Color.parseColor("#4ade80"),
            "warning" to Color.parseColor("#fbbf24"), "failed" to Color.parseColor("#f87171"), "idle" to Color.parseColor("#8b97ab"))
        private val ICON = mapOf("queued" to "\u23f3", "running" to "\u25b6", "done" to "\u2714", "warning" to "\u26a0", "failed" to "\u2716", "idle" to "\u25cf")
        private val TITLE = mapOf("queued" to "Print job received", "running" to "Printing...", "done" to "Printed successfully", "warning" to "Sent - printer needs attention",
            "failed" to "Print FAILED", "idle" to "Waiting for a print job")
        private val STEP = mapOf("pending" to ("\u25cb" to DIM), "active" to ("\u25cf" to COL["running"]!!), "done" to ("\u2714" to COL["done"]!!),
            "fail" to ("\u2716" to COL["failed"]!!), "warn" to ("\u26a0" to COL["warning"]!!))
        private val LABELS = listOf("Received", "Preparing pages", "Sending to printer", "Printer is printing", "Finished")

        fun noAuto(c: Context) = try { c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("noauto", false) } catch (_: Throwable) { false }

        /** Opens (or re-targets) the live screen for the PC [dev]. */
        fun open(c: Context, dev: String) {
            val i = Intent(c, PrintLiveActivity::class.java).putExtra(X_DEV, dev)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        }

        /** Called when a print is sent to a PC: opens the screen unless it is already showing or the user turned this off. Never throws. */
        fun auto(c: Context?, dev: String) {
            try {
                if (c == null || noAuto(c)) return
                val a = cur
                if (a != null) { a.dev = dev; return }
                open(c, dev)
            } catch (_: Throwable) {}
        }
    }
}
