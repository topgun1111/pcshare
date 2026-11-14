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
import android.text.style.StyleSpan
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
 * "Logs" screen. Two tabs: Phone log (core/PrintLog.kt) and PC log (the PC's diag.jsonl, pcprint.py 31+, GET /api/pcdiag, fetched with Refresh).
 * Toolbar: Refresh (the open tab), Export (asks PC / Phone / Both, hands the RAW json lines to another app as a file) and the Advanced tick box.
 * Advanced off (default) = plain sentences (PlainLog.kt: problem count, "what to try" tips); on = the detailed technical lines plus
 * Copy, Open folder, Clear phone log and the "Full detail" switch (Cfg.printVerbose).
 * Intent extras: [X_JIDS] job ids to show (comma list, empty = everything; an "Only this job" tick box appears), [X_DEV] the PC for the PC tab.
 * Never throws out of a button; all file / network work runs on one background thread.
 */
class PrintLogActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var pal: AppPal.P
    private val lm by lazy { Lumen.of(this) }
    /** Warning colour: the theme's own amber on dark themes, a darker amber on light ones (the theme amber is a fill colour, too pale for text). */
    private fun amber(): Int = lm.warning   // Lumen slots (warning #a66300 light / #ffc15e dark both pass 4.5:1 as text): error / success / warning follow the app theme
    private lateinit var info: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var extra: LinearLayout      // Advanced-only tools (Full detail, Copy, Open folder, Clear)
    private val tabTxt = arrayOfNulls<TextView>(2)
    private val tabInd = arrayOfNulls<View>(2)
    private var tab = 0                           // 0 = Phone log, 1 = PC log
    private var simple = true
    private var jids: List<String> = emptyList()
    private var dev = ""
    private var onlyJobs = false
    private var phone: List<Ln> = emptyList()
    private var pc: List<Ln> = emptyList()
    private var pcNote = ""
    private var pcTried = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Fnt.init(applicationContext)   // the app font (Settings > Interface > Font) for the whole screen
        pal = AppPal.pal(this)
        val light = AppPal.isLight(pal.bg)
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        jids = (intent.getStringExtra(X_JIDS) ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        dev = intent.getStringExtra(X_DEV) ?: ""
        onlyJobs = jids.isNotEmpty()
        simple = try { getSharedPreferences("logs_ui", MODE_PRIVATE).getBoolean("simple", true) } catch (_: Throwable) { true }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        LumenNativeTheme.edgeToEdge(this, root)   // spec 9.4: transparent bars, drawn edge to edge
        root.addView(TextView(this).apply { NlA11y.heading(this); text = "Logs"; LumenTypography.fit(this, 20f); setTextColor(pal.fg); typeface = Fnt.semi(); setPadding(dp(16), dp(12), dp(16), dp(2)) })
        info = TextView(this).apply { typeface = Fnt.reg(); LumenTypography.fit(this, 12f); setTextColor(pal.mut); setPadding(dp(16), 0, dp(16), dp(8)) }
        root.addView(info)

        // --- tabs: Phone log | PC log
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        fun tabBtn(i: Int, label: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val t = TextView(this@PrintLogActivity).apply { text = label; LumenTypography.fit(this, 15f); gravity = Gravity.CENTER; typeface = Fnt.semi(); setPadding(0, dp(12), 0, dp(12)) }
            val ind = View(this@PrintLogActivity)
            tabTxt[i] = t; tabInd[i] = ind
            addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            setOnClickListener { selectTab(i) }
        }
        tabs.addView(tabBtn(0, "Phone log")); tabs.addView(tabBtn(1, "PC log"))
        root.addView(tabs)
        root.addView(View(this).apply { setBackgroundColor(pal.bd) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        info = TextView(this).apply { typeface = Fnt.reg(); LumenTypography.fit(this, 12f); setTextColor(pal.mut); setPadding(dp(16), dp(8), dp(16), dp(4)) }
        root.addView(info)

        // --- one row: Refresh, Export, [x] Advanced
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(2), dp(12), dp(8)) }
        bar.addView(chip("Refresh") { refresh() })
        bar.addView(chip("Export") { export() })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val adv = android.widget.CheckBox(this).apply {
            text = "Advanced"; typeface = Fnt.med(); LumenTypography.fit(this, 13f); setTextColor(pal.fg)
            isChecked = !simple
            setOnCheckedChangeListener { _, v ->
                simple = !v
                try { getSharedPreferences("logs_ui", MODE_PRIVATE).edit().putBoolean("simple", simple).apply() } catch (_: Throwable) {}
                render()
            }
        }
        bar.addView(adv)
        root.addView(bar)

        if (jids.isNotEmpty()) root.addView(android.widget.CheckBox(this).apply {
            text = "Only this job (" + jids.joinToString(" ") + ")"; typeface = Fnt.med(); LumenTypography.fit(this, 13f); setTextColor(pal.fg)
            isChecked = onlyJobs; setPadding(dp(12), 0, dp(12), dp(8))
            setOnCheckedChangeListener { _, v -> onlyJobs = v; render() }
        })

        // --- Advanced only: the less used tools
        extra = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val ex = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        ex.addView(chip("Copy") { copy() })
        ex.addView(chip("Open folder") { openFolder() })
        ex.addView(chip("Clear phone log") { clear() })
        extra.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(ex) })
        extra.addView(Switch(this).apply {
            text = "Full detail (long texts and stack traces are kept in full)"; typeface = Fnt.med(); LumenTypography.fit(this, 13f); setTextColor(pal.fg)
            isChecked = try { Cfg.printVerbose() } catch (_: Throwable) { true }
            setPadding(dp(16), dp(4), dp(16), dp(8))
            setOnCheckedChangeListener { _, v -> try { Cfg.setPrintVerbose(v) } catch (_: Throwable) {} }
        })
        root.addView(extra)
        paintTabs()

        body = TextView(this).apply {
            typeface = Fnt.med(); LumenTypography.fit(this, 11f); setTextColor(pal.fg); setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        scroll = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(pal.cont) }
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
        if (dev.isNotEmpty()) fetchPc()   // the PC tab is ready when it is opened
    }

    override fun onDestroy() { super.onDestroy(); exec.shutdownNow() }

    private fun chip(t: String, f: () -> Unit) = TextView(this).apply {
        text = t; typeface = Fnt.med(); LumenTypography.fit(this, 13f); setTextColor(pal.fg); gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply { setColor(pal.cont); cornerRadius = dp(16).toFloat(); setStroke(dp(1), pal.bd) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        setOnClickListener { try { f() } catch (e: Throwable) { toast("Failed: " + (e.message ?: e.javaClass.simpleName)) } }
    }

    private fun paintTabs() {
        for (i in 0..1) {
            tabTxt[i]?.setTextColor(if (i == tab) pal.fg else pal.mut)
            tabInd[i]?.setBackgroundColor(if (i == tab) pal.ac else Color.TRANSPARENT)
        }
    }

    private fun selectTab(i: Int) {
        if (tab == i) return
        tab = i; paintTabs(); render()
        if (i == 1 && pc.isEmpty() && dev.isNotEmpty() && !pcTried) fetchPc()
    }

    /** Refresh = reload the list that is on screen. */
    private fun refresh() { if (tab == 0) load() else fetchPc() }

    private fun toast(t: String) = ui.post { try { Lm.snack(this, t, Toast.LENGTH_LONG) } catch (_: Throwable) {} }
    private fun bg(f: () -> Unit) { try { exec.execute { try { f() } catch (e: Throwable) { toast("Failed: " + (e.message ?: e.javaClass.simpleName)) } } } catch (_: Throwable) {} }

    // ------------------------------------------------------------ data

    /** The phone's log (the last ~300 KB) is read again; the PC part (if fetched before) stays. */
    private fun load() = bg {
        val t = PrintLog.tail()
        val l = parse(t, "app")
        ui.post { phone = l; render() }
    }

    private fun fetchPc() {
        pcTried = true
        if (dev.isEmpty()) { toast("No PC known yet: print once to a PC or open \"PC screen\" first, then open this screen again."); return }
        toast("Fetching the PC log...")
        bg {
            val r = pcText(dev)
            ui.post {
                if (r.first != null) {
                    val before = pc.size
                    pc = parse(r.first!!, "pc"); pcNote = "PC log: " + pc.size + " lines"; render()
                    val d = pc.size - before
                    toast("PC log updated: " + pc.size + " lines" + (if (before == 0) "" else if (d > 0) " (+" + d + " new)" else " (no new lines)"))
                }
                else { pcNote = ""; toast(r.second) }
            }
        }
    }

    /** The lines of the open tab (each in time order); the job filter applies to both tabs. */
    private fun shown(): List<Ln> {
        fun f(l: List<Ln>) = if (onlyJobs && jids.isNotEmpty()) l.filter { it.jid != null && it.jid in jids } else l
        return if (tab == 1) f(pc) else f(phone)
    }

    /** Section title when the source changes (only if both logs have lines). */
    private fun section(sb: SpannableStringBuilder, prev: String?, l: Ln, both: Boolean) {
        if (!both || prev == l.src) return
        val s0 = sb.length
        sb.append(if (l.src == "pc") "PC log" else "Phone log").append('\n')
        sb.setSpan(StyleSpan(Typeface.BOLD), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(pal.mut), s0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun render() {
        val f = File(try { PrintLog.file().absolutePath } catch (_: Throwable) { "" })
        val kb = try { PrintLog.size() / 1024 } catch (_: Throwable) { 0L }
        extra.visibility = if (simple) View.GONE else View.VISIBLE
        val lines = shown()
        val sb = SpannableStringBuilder()
        if (simple) renderSimple(lines, sb) else renderTechnical(lines, f, kb, sb)
        body.text = sb
        // always end at the newest entry (PC log first, phone log last; the last line is the newest)
        val toEnd = { val c = scroll.getChildAt(0); if (c != null) scroll.scrollTo(0, maxOf(0, c.height - scroll.height)) }
        scroll.post { toEnd(); scroll.post { toEnd() } }
    }

    /** What an empty tab says (the PC tab also says why: no PC known / not loaded yet). */
    private fun emptyText(): String = when {
        tab == 1 && dev.isEmpty() -> "No PC known yet: print once to a PC, then open this screen again."
        tab == 1 && pc.isEmpty() -> "The PC log is not loaded. Tap Refresh to fetch it."
        onlyJobs -> "Nothing for this job in this log" + (if (tab == 0) " (the PC part is in the PC log tab)." else ".")
        else -> "Nothing to report yet. Print something and come back."
    }

    private fun renderTechnical(lines: List<Ln>, f: File, kb: Long, sb: SpannableStringBuilder) {
        body.typeface = Fnt.med(); LumenTypography.fit(body, 11f)
        info.text = if (tab == 0) (if (f.path.isEmpty()) "" else f.name + " - " + kb + " KB - " + f.parent) else (pcNote + (if (pcNote.isEmpty()) "" else "\n") + "Times are the PC's clock.")
        val last = if (lines.size > MAXV) lines.subList(lines.size - MAXV, lines.size) else lines
        if (lines.size > last.size) sb.append("... ").append((lines.size - last.size).toString()).append(" older lines not shown (Share / Copy give everything)\n\n")
        if (last.isEmpty()) sb.append(emptyText())
        var prev: String? = null
        val both = last.any { it.src == "pc" } && last.any { it.src != "pc" }
        for (l in last) {
            section(sb, prev, l, both); prev = l.src
            val s = sb.length
            sb.append(pretty(l)).append('\n')
            val c = when (l.lvl) { "e" -> lm.error; "w" -> amber(); else -> if (l.src == "pc") lm.info else pal.fg }
            if (c != pal.fg) sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Plain sentences (PlainLog): "21:30:05  PC  Printing finished (3.2 s)", a tip line under problems, a problem count on top. */
    private fun renderSimple(lines: List<Ln>, sb: SpannableStringBuilder) {
        body.typeface = Fnt.med(); LumenTypography.fit(body, 14f)
        val items = ArrayList<Pair<Ln, PlainLog.Item>>()
        for (l in lines) { val it = PlainLog.of(l); if (it != null) items.add(l to it) }
        val errs = items.count { it.second.kind == 'e' }
        val warns = items.count { it.second.kind == 'w' }
        val head = when {
            errs > 0 -> errs.toString() + (if (errs == 1) " problem" else " problems") + (if (warns > 0) ", $warns warning" + (if (warns == 1) "" else "s") else "") + " found - shown in red below."
            warns > 0 -> "$warns warning" + (if (warns == 1) "" else "s") + " - shown in orange below."
            items.isEmpty() -> "Nothing to report yet."
            else -> "No problems found."
        }
        info.text = head + (if (tab == 1) (if (pcNote.isEmpty()) "" else "\n" + pcNote) + "\nTimes are the PC's clock." else "") + "\nTick Advanced for all the details."
        val last = if (items.size > MAXS) items.subList(items.size - MAXS, items.size) else items
        if (items.size > last.size) sb.append("... ").append((items.size - last.size).toString()).append(" older entries not shown\n\n")
        if (last.isEmpty()) sb.append(emptyText())
        var prev: String? = null
        val both = last.any { it.first.src == "pc" } && last.any { it.first.src != "pc" }
        for ((l, it) in last) {
            section(sb, prev, l, both); prev = l.src
            val s = sb.length
            val time = l.t.substringAfter(' ', l.t).substringBefore('.')
            val mark = when (it.kind) { 'e' -> "\u2716"; 'w' -> "\u26A0"; 'o' -> "\u2714"; else -> "\u2022" }
            sb.append(time).append("  ").append(mark).append(' ').append(if (l.src == "pc") "PC: " else "Phone: ").append(it.text).append('\n')
            val c = when (it.kind) { 'e' -> lm.error; 'w' -> amber(); 'o' -> lm.success; else -> pal.fg }
            if (c != pal.fg) sb.setSpan(ForegroundColorSpan(c), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (it.kind == 'e') sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (it.tip != null) {
                val t0 = sb.length
                sb.append("      What to try: ").append(it.tip).append('\n')
                sb.setSpan(ForegroundColorSpan(pal.mut), t0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append('\n')
        }
    }

    // ------------------------------------------------------------ buttons

    private fun sel(l: List<Ln>): List<Ln> = if (onlyJobs && jids.isNotEmpty()) l.filter { it.jid != null && it.jid in jids } else l

    /** Raw json lines: kind 0 = PC log, 1 = phone log, 2 = both merged by time. */
    private fun rawOf(kind: Int, pcL: List<Ln> = pc): String =
        (when (kind) { 0 -> sel(pcL); 1 -> sel(phone); else -> sel(merge(phone, pcL)) }).joinToString("\n") { it.raw }

    private fun copy() = bg {
        val t = rawOf(if (tab == 1) 0 else 1).takeLast(COPY_MAX)
        ui.post {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("LANShare print log", t))
            toast(if (t.isEmpty()) "Nothing to copy" else "Copied " + (t.length / 1024) + " KB")
        }
    }

    /** Export: ask which log (PC / phone / both), then hand the raw lines as a file to another app. */
    private fun export() {
        AppPal.dlg(this).setTitle("Export which log?").setItems(arrayOf("PC log", "Phone log", "Both")) { _, w -> exportKind(w) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportKind(kind: Int) = bg {
        var pcL = pc
        if (kind != 1 && pcL.isEmpty()) {   // PC part wanted but not loaded yet: fetch it now
            if (dev.isEmpty()) { if (kind == 0) { toast("No PC known yet: print once to a PC first."); return@bg } }
            else {
                val r = pcText(dev)
                if (r.first != null) { pcL = parse(r.first!!, "pc"); val got = pcL; ui.post { pc = got; pcNote = "PC log: " + got.size + " lines"; render() } }
                else { toast(r.second); if (kind == 0) return@bg }
            }
        }
        val t = rawOf(kind, pcL)
        if (t.isEmpty()) { toast("Nothing to export"); return@bg }
        shareText(t, when (kind) { 0 -> "lanshare-pc-log.jsonl"; 1 -> "lanshare-phone-log.jsonl"; else -> "lanshare-print-log.jsonl" })
    }

    private fun shareText(t: String, fileName: String) {
        val f = File(File(cacheDir, "open").apply { mkdirs() }, fileName)
        f.writeText(t + "\n", Charsets.UTF_8)
        val uri = com.lanshare.app.core.vault.VaultIntents.uri(this, "$packageName.fileprovider", f)
        ui.post {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, "LANShare print log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export log"))
            } catch (e: Throwable) { toast("Cannot export: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    private fun openFolder() {
        val dir = try { PrintLog.file().parentFile } catch (_: Throwable) { null }
        val shared = dir != null && dir.name == "LANShare Logs"
        if (!shared) { toast("The log is in the app's private folder (no storage access): " + (dir?.path ?: "") + ". Use Export instead."); return }
        StorageActions.revealDev(this, "", dir!!.absolutePath, null)   // dev "" = this phone (MainActivity.runShowPath -> lsGoLocal)
    }

    private fun clear() {
        AppPal.dlg(this).kind("danger").setTitle("Clear the print log?").setMessage("Only this phone's log file is emptied. The PC keeps its own log.")
            .setPositiveButton("Clear") { _, _ -> bg { PrintLog.clear(); ui.post { phone = emptyList(); render(); toast("Print log cleared") } } }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------ log lines

    class Ln(val t: String, val src: String, val jid: String?, val lvl: String, val ev: String, val raw: String, val o: JSONObject?)

    companion object {
        const val X_JIDS = "jids"
        const val X_DEV = "dev"
        private const val MAXV = 600
        private const val MAXS = 300
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
            fun say(t: String) = main.post { try { Lm.snack(c, t, Toast.LENGTH_LONG) } catch (_: Throwable) {} }
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
