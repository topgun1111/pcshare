package com.lanshare.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.Jobs
import com.lanshare.app.core.PrintLog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * "Print log" screen (HANDOVER 9.8, part 4). Shows the phone's print log (core/PrintLog.kt) as readable lines, newest at the bottom;
 * "Fetch PC log" adds the PC's diag.jsonl (pcprint.py 31+, GET /api/pcdiag) merged by time; Share / Copy hand the RAW json lines to another app
 * (that is what the developer reads); Clear empties the phone's file; Open folder shows <storage>/LANShare Logs in the file manager;
 * "Full detail" is the verbose switch (Cfg.printVerbose). Intent extras: [X_JIDS] job ids to show (comma list, empty = everything), [X_DEV] the PC for "Fetch PC log".
 * Never throws out of a button; all file / network work runs on one background thread.
 */
class PrintLogActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var pal: AppPal.P
    private lateinit var info: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var scopeBtn: TextView
    private var jids: List<String> = emptyList()
    private var dev = ""
    private var onlyJobs = false
    private var phone: List<Ln> = emptyList()
    private var pc: List<Ln> = emptyList()
    private var pcNote = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = AppPal.pal(this)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(pal.bg) > 0.5
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        jids = (intent.getStringExtra(X_JIDS) ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        dev = intent.getStringExtra(X_DEV) ?: ""
        onlyJobs = jids.isNotEmpty()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        root.addView(TextView(this).apply { text = "Print log"; textSize = 20f; setTextColor(pal.fg); setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(12), dp(16), dp(2)) })
        info = TextView(this).apply { textSize = 12f; setTextColor(pal.mut); setPadding(dp(16), 0, dp(16), dp(6)) }
        root.addView(info)

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(2), dp(12), dp(6)) }
        scopeBtn = chip("") { onlyJobs = !onlyJobs; render() }
        if (jids.isNotEmpty()) chips.addView(scopeBtn)
        chips.addView(chip("Refresh") { load() })
        chips.addView(chip("Fetch PC log") { fetchPc() })
        chips.addView(chip("Share") { share() })
        chips.addView(chip("Copy") { copy() })
        chips.addView(chip("Open folder") { openFolder() })
        chips.addView(chip("Clear") { clear() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val sw = Switch(this).apply {
            text = "Full detail (long texts and stack traces are kept in full)"; textSize = 13f; setTextColor(pal.fg)
            isChecked = try { Cfg.printVerbose() } catch (_: Throwable) { true }
            setPadding(dp(16), dp(4), dp(16), dp(8))
            setOnCheckedChangeListener { _, v -> try { Cfg.setPrintVerbose(v) } catch (_: Throwable) {} }
        }
        root.addView(sw)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(pal.fg); setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        scroll = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(pal.cont) }
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdownNow() }

    private fun chip(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(pal.fg); gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { setColor(pal.cont); cornerRadius = dp(18).toFloat(); setStroke(dp(1), pal.bd) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        setOnClickListener { try { f() } catch (e: Throwable) { toast("Failed: " + (e.message ?: e.javaClass.simpleName)) } }
    }

    private fun toast(t: String) = ui.post { try { Toast.makeText(this, t, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
    private fun bg(f: () -> Unit) { try { exec.execute { try { f() } catch (e: Throwable) { toast("Failed: " + (e.message ?: e.javaClass.simpleName)) } } } catch (_: Throwable) {} }

    // ------------------------------------------------------------ data

    /** The phone's log (the last ~300 KB) is read again; the PC part (if fetched before) stays. */
    private fun load() = bg {
        val t = PrintLog.tail()
        val l = parse(t, "app")
        ui.post { phone = l; render() }
    }

    private fun fetchPc() {
        if (dev.isEmpty()) { toast("No PC known yet: print once to a PC or open \"PC screen\" first, then open this screen again."); return }
        toast("Fetching the PC log...")
        bg {
            val r = pcText(dev)
            ui.post {
                if (r.first != null) { pc = parse(r.first!!, "pc"); pcNote = "PC log: " + pc.size + " lines"; render() }
                else { pcNote = ""; toast(r.second) }
            }
        }
    }

    private fun shown(): List<Ln> {
        val all = merge(phone, pc)
        return if (onlyJobs && jids.isNotEmpty()) all.filter { it.jid != null && it.jid in jids } else all
    }

    private fun render() {
        val f = File(try { PrintLog.file().absolutePath } catch (_: Throwable) { "" })
        val kb = try { PrintLog.size() / 1024 } catch (_: Throwable) { 0L }
        scopeBtn.text = if (onlyJobs) "Job " + jids.joinToString(" ") + " (tap: all)" else "All jobs (tap: this job)"
        val lines = shown()
        info.text = (if (f.path.isEmpty()) "" else f.name + " - " + kb + " KB - " + f.parent) + (if (pcNote.isEmpty()) "" else "\n" + pcNote) + "\nTimes of the PC lines are the PC's clock."
        val last = if (lines.size > MAXV) lines.subList(lines.size - MAXV, lines.size) else lines
        val sb = SpannableStringBuilder()
        if (lines.size > last.size) sb.append("... ").append((lines.size - last.size).toString()).append(" older lines not shown (Share / Copy give everything)\n\n")
        if (last.isEmpty()) sb.append(if (onlyJobs) "No lines for this job (it may be in the PC log: tap \"Fetch PC log\")." else "The log is empty. Print something and come back.")
        for (l in last) {
            val s = sb.length
            sb.append(pretty(l)).append('\n')
            val c = when (l.lvl) { "e" -> 0xFFD64545.toInt(); "w" -> 0xFFC58A00.toInt(); else -> if (l.src == "pc") 0xFF3B82F6.toInt() else pal.fg }
            if (c != pal.fg) sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ buttons

    private fun rawText(): String = merge(phone, pc).let { all -> (if (onlyJobs && jids.isNotEmpty()) all.filter { it.jid != null && it.jid in jids } else all).joinToString("\n") { it.raw } }

    private fun copy() = bg {
        val t = rawText().takeLast(COPY_MAX)
        ui.post {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LANShare print log", t))
            toast(if (t.isEmpty()) "Nothing to copy" else "Copied " + (t.length / 1024) + " KB")
        }
    }

    private fun share() = bg {
        val t = rawText()
        if (t.isEmpty()) { toast("Nothing to share"); return@bg }
        val f = File(File(cacheDir, "open").apply { mkdirs() }, "print-log.jsonl")
        f.writeText(t + "\n", Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        ui.post {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "LANShare print log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share print log"))
            } catch (e: Throwable) { toast("Cannot share: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun openFolder() {
        val dir = try { PrintLog.file().parentFile } catch (_: Throwable) { null }
        val shared = dir != null && dir.name == "LANShare Logs"
        if (!shared) { toast("The log is in the app's private folder (no storage access): " + (dir?.path ?: "") + ". Use Share instead."); return }
        StorageActions.revealDev(this, "", dir!!.absolutePath, null)   // dev "" = this phone (MainActivity.runShowPath -> lsGoLocal)
    }

    private fun clear() {
        AppPal.dlg(this).setTitle("Clear the print log?").setMessage("Only this phone's log file is emptied. The PC keeps its own log.")
            .setPositiveButton("Clear") { _, _ -> bg { PrintLog.clear(); ui.post { phone = emptyList(); render(); toast("Print log cleared") } } }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------ log lines

    class Ln(val t: String, val src: String, val jid: String?, val lvl: String, val ev: String, val raw: String, val o: JSONObject?)

    companion object {
        const val X_JIDS = "jids"
        const val X_DEV = "dev"
        private const val MAXV = 600
        private const val COPY_MAX = 200_000
        private val SKIP = setOf("t", "run", "n", "src", "jid", "th", "lvl", "ev")

        fun open(c: Context, jids: String?, dev: String?) {
            c.startActivity(Intent(c, PrintLogActivity::class.java).putExtra(X_JIDS, jids ?: "").putExtra(X_DEV, dev ?: ""))
        }

        /** Job ids ("A0005", "J0007") written as "[log X]" inside an error / note text. */
        fun idsIn(s: String?): List<String> = Regex("\\[log ([AJ]\\d{4})\\]").findAll(s ?: "").map { it.groupValues[1] }.toList()

        /** JSON lines -> lines; a line that is not JSON is kept as plain text (never lost). [def] = src for lines without one. */
        fun parse(text: String, def: String): List<Ln> {
            val out = ArrayList<Ln>()
            for (raw in text.split('\n')) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                try {
                    val o = JSONObject(s)
                    out.add(Ln(o.optString("t"), o.optString("src", def), o.optString("jid").ifEmpty { null }, o.optString("lvl", "i"), o.optString("ev"), s, o))
                } catch (_: Throwable) { out.add(Ln("", def, null, "i", "", s, null)) }
            }
            return out
        }

        /** Both logs by time (the format "yyyy-MM-dd HH:mm:ss.SSS" sorts as text; the sort is stable, so equal times keep their file order). */
        fun merge(a: List<Ln>, b: List<Ln>): List<Ln> = if (b.isEmpty()) a else (a + b).sortedBy { it.t }

        fun pretty(l: Ln): String {
            val o = l.o ?: return l.raw.take(300)
            val sb = StringBuilder()
            sb.append(l.t.substringAfter(' ', l.t)).append(' ').append(l.lvl.uppercase()).append(' ').append(if (l.src == "pc") "PC" else "AP")
            if (l.jid != null) sb.append(' ').append(l.jid)
            sb.append(' ').append(l.ev)
            val it = o.keys()
            var k = 0
            while (it.hasNext() && k < 12) {
                val key = it.next()
                if (key in SKIP) continue
                var v = o.opt(key)?.toString() ?: ""
                if (key == "exc") { v = (o.optJSONObject("exc")?.let { e -> e.optString("type") + ": " + e.optString("msg") } ?: v) }
                if (v.length > 160) v = v.take(160) + "..."
                sb.append("\n    ").append(key).append('=').append(v.replace('\n', ' '))
                k++
            }
            return sb.toString()
        }

        /** The PC's diag text through the print service (pcprint.py 31+): (text, null) or (null, reason). */
        fun pcText(dev: String): Pair<String?, String> {
            if (dev.isEmpty()) return null to "No PC chosen"
            return try {
                val r = Jobs.pcDiag(dev, -1L)
                when {
                    r.optBoolean("old") -> null to "The PC print file is older than v31 (no log): run the new pcprint.py once."
                    !r.optBoolean("ok") -> null to ("PC log not available: " + r.optString("why", "the PC did not answer"))
                    else -> r.optString("text") to ""
                }
            } catch (e: Throwable) { null to ("PC log not available: " + (e.message ?: e.javaClass.simpleName)) }
        }

        /** Job card "Copy log": raw lines of these job ids from the phone log, plus the PC's lines for J ids (when [dev] is known), on the clipboard. Runs on its own thread. */
        fun copyJobs(c: Context, idsCsv: String, dev: String) {
            val ids = idsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val main = Handler(Looper.getMainLooper())
            fun say(t: String) = main.post { try { Toast.makeText(c, t, Toast.LENGTH_LONG).show() } catch (_: Throwable) {} }
            if (ids.isEmpty()) { say("No log id for this job"); return }
            Thread({
                try {
                    val mine = parse(PrintLog.tail(1_500_000), "app").filter { it.jid != null && it.jid in ids }
                    var theirs: List<Ln> = emptyList()
                    var why = ""
                    if (ids.any { it.startsWith("J") }) {
                        val r = pcText(dev)
                        if (r.first != null) theirs = parse(r.first!!, "pc").filter { it.jid != null && it.jid in ids } else why = r.second
                    }
                    val t = merge(mine, theirs).joinToString("\n") { it.raw }.takeLast(COPY_MAX)
                    main.post {
                        try {
                            if (t.isNotEmpty()) (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LANShare print log", t))
                        } catch (_: Throwable) {}
                    }
                    say(if (t.isEmpty()) "No log lines found for " + ids.joinToString(" ") + (if (why.isEmpty()) "" else ". $why")
                        else "Log of " + ids.joinToString(" ") + " copied (" + (mine.size + theirs.size) + " lines)" + (if (why.isEmpty()) "" else ". $why"))
                } catch (e: Throwable) { say("Copy failed: " + (e.message ?: e.javaClass.simpleName)) }
            }, "printlog-copy").also { it.isDaemon = true }.start()
        }
    }
}
