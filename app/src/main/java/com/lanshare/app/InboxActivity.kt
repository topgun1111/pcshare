package com.lanshare.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.Notes
import java.text.DateFormat
import java.util.Date

/**
 * Guesses whether a message that came WITHOUT ``` fences is code (a phone usually sends a pasted snippet), so the inbox can show it as a code card.
 * Port of `guess_lang` in lanshare_text.py - keep both in step. Returns a [CodeHl] key (json, html, sh, php, py, sql, c, java, js, yaml, css) or null for prose.
 */
internal object CodeGuess {
    private val CODE_START = Regex(
        """^\s*(def |class |import |from \S+ import |function |const |let |var |if\s*\(|for\s*\(|while\s*\(|return\b|public |private |protected |""" +
        """#include|#define|using |namespace |package |fun |val |@\w+|<\?php|SELECT |INSERT |UPDATE |DELETE |CREATE |async |await |try:|except|else:|elif |""" +
        """\$\w+\s*=|\w+\(.*\)\s*[{:;]?\s*$|[\w.\[\]]+\s*(=|:=|\+=|-=)\s*\S)"""
    )
    private val CODE_END = Regex("""[;{}]\s*$|\)\s*:?\s*$|:\s*$|,\s*$""")
    private val SYMBOLS = Regex("""[=(){}\[\]<>;]""")
    private val NOT_PROSE = Regex("""[{};=]""")
    private val WS = Regex("""\s+""")
    private val JSON_HINT = Regex("\"\\s*:|\\[\\s*[\\d\"{\\[]")
    private val HTML = Regex("""^\s*<(!doctype|html|\?xml|svg|div|span|body|head|script|style|p|a|ul|table)\b""", RegexOption.IGNORE_CASE)
    private val DOLLAR_VAR = Regex("""\$\w+\s*=""")
    private val PY = Regex("""^\s*(def |class \w+.*:|import \w+|from \w+ import|elif |print\()""", RegexOption.MULTILINE)
    private val SQL = Regex("""^\s*(select|insert into|update|delete from|create table)\b""", RegexOption.MULTILINE)
    private val C = Regex("""#include|std::|::|->|\bnullptr\b""")
    private val JAVA = Regex("""\bfun \w+|\bval \w+|\bvar \w+:|\bclass \w+\(|import (android|java|kotlin)""")
    private val JS = Regex("""\b(function|const|let|=>|console\.|document\.|require\()""")
    private val YAML = Regex("""^\s*[\w.-]+\s*:\s*\S""", RegexOption.MULTILINE)
    private val CSS_SEL = Regex("""^\s*[#.]?[\w-]+\s*\{""", RegexOption.MULTILINE)
    private val CSS_DECL = Regex("""[\w-]+\s*:\s*[^;]+;""")
    private val SHELL = arrayOf("$ ", "pip ", "pip3 ", "python ", "python3 ", "py ", "git ", "npm ", "npx ", "yarn ", "cd ", "dir ", "ls ", "curl ", "wget ", "sudo ", "apt ", "apt-get ",
        "winget ", "docker ", "adb ", "ssh ", "scp ", "chmod ", "mkdir ", "gradle ", "./gradlew", "PS ", "C:\\", "kubectl ", "cargo ", "go ", "pwsh ", "powershell ")

    private fun endsSentence(l: String): Boolean { val t = l.trimEnd(); return t.endsWith(".") || t.endsWith("?") || t.endsWith("!") }

    fun guess(text: String): String? {
        val s = text.trim()
        if (s.isEmpty() || s.length > 60_000) return null
        val first = s.substringBefore('\n')
        if ((s[0] == '{' || s[0] == '[') && (s.last() == '}' || s.last() == ']') && JSON_HINT.containsMatchIn(s)) return "json"
        if (HTML.containsMatchIn(s) && s.endsWith(">")) return "html"
        if (!s.contains('\n')) {
            if (SHELL.any { first.startsWith(it) } && !endsSentence(first)) return "sh"
            return null
        }
        val rows = s.split('\n').filter { it.isNotBlank() }
        if (rows.size < 2) return null
        var score = 0
        for (l in rows) {
            val codey = CODE_START.containsMatchIn(l) || CODE_END.containsMatchIn(l) ||
                ((l.startsWith("  ") || l.startsWith("\t")) && SYMBOLS.containsMatchIn(l))
            val prose = l.trim().split(WS).size >= 7 && endsSentence(l) && !NOT_PROSE.containsMatchIn(l)
            if (codey && !prose) score++
        }
        if (score.toDouble() / rows.size < 0.6) return null
        val low = s.lowercase()
        if (low.contains("<?php") || (DOLLAR_VAR.containsMatchIn(s) && s.contains(';'))) return "php"
        if (PY.containsMatchIn(s) && !s.take(200).contains('{')) return "py"
        if (SQL.containsMatchIn(low)) return "sql"
        if (C.containsMatchIn(s)) return "c"
        if (JAVA.containsMatchIn(s)) return "java"
        if (JS.containsMatchIn(s)) return "js"
        if (YAML.containsMatchIn(s) && !s.contains('{') && !s.contains(';')) return "yaml"
        if (CSS_SEL.containsMatchIn(s) && CSS_DECL.containsMatchIn(s)) return "css"
        return if (s.contains('{') || s.contains(';')) "js" else "py"
    }
}

/**
 * Text inbox: a transcript of everything sent to / received from other devices, plus a box to type or paste text and send it. Dressed like the PC window
 * (lanshare_text.py): every message is a full-width band with a coloured edge bar - received = the theme's primary colour, sent = the theme's blue - and
 * a slightly different tint on every other message; each message has its own Copy action; Markdown is rendered; code (fenced or pasted) is a card with
 * a language label, line numbers, syntax colours and its own Copy button; long code starts collapsed.
 * Tap a message = copy it, long-press = copy / copy code / open link / view as code or text / share / delete. The device line chooses the default target of
 * text sharing (share-sheet entry, Direct Share, Quick Settings tile, notification quick-reply); the menu holds the Auto-copy and Clipboard-sync switches.
 */
class InboxActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var toLine: TextView
    private lateinit var info: TextView
    private var attachmentTargetId: String? = null
    private var data: List<Notes.Note> = emptyList()
    private val onChange: () -> Unit = { ui.post { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var pal: AppPal.P
    private var palSig: String? = null
    private var night = false
    private var inCol = 0      // received messages: the theme's primary colour
    private var outCol = 0     // sent messages: the theme's blue (the code-highlighter's "type" colour)

    private val modes = HashMap<Long, String>()          // note id -> "code" | "text": the view the person forced from the long-press menu
    private val expanded = HashSet<String>()             // "noteId:blockIndex" of long code blocks shown in full
    private val cache = LruCache<Long, List<MdRender.Block>>(64)   // parsed messages (a note never changes; the mode and the theme reset it)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        TextSend.ensureRunning(this)
        pal = AppPal.pal(this); palSig = AppPal.json(this)
        val light = AppPal.isLight(pal.bg)
        night = !light
        inCol = pal.ac
        outCol = CodeHl.color(this, CodeHl.TYPE, night)
        window.statusBarColor = pal.bg; window.navigationBarColor = pal.bg
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg) }
        LumenNativeTheme.edgeToEdge(this, root)   // spec 9.4: transparent bars, drawn edge to edge

        // header: dot + title + device count + menu
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(8)) }
        head.addView(TextView(this).apply { text = "●"; LumenTypography.fit(this, 20f); setTextColor(pal.ac); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        head.addView(TextView(this).apply {
            NlA11y.heading(this); text = "  LANShare Text"; LumenTypography.fit(this, 20f); setTextColor(pal.fg); setTypeface(typeface, Typeface.BOLD)
        })
        info = TextView(this).apply { LumenTypography.fit(this, 12f); setTextColor(pal.mut); setPadding(dp(12), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        head.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "⋮"; LumenTypography.fit(this, 24f); setTextColor(pal.fg); gravity = Gravity.CENTER
            setPadding(dp(16), dp(4), dp(16), dp(4)); setOnClickListener { menu() }
            contentDescription = "More options"
        })
        root.addView(head)

        // target line
        toLine = TextView(this).apply {
            LumenTypography.fit(this, 14f); setTextColor(pal.mut); setBackgroundColor(pal.cont)
            setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { chooseTarget(null) }
        }
        root.addView(toLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // transcript (+ empty hint)
        list = ListView(this).apply { divider = null; dividerHeight = 0; isStackFromBottom = true; transcriptMode = android.widget.AbsListView.TRANSCRIPT_MODE_NORMAL; adapter = this@InboxActivity.adapter }
        val empty = TextView(this).apply {
            text = "Nothing yet.\nType below, or share any text to LANShare."; gravity = Gravity.CENTER; LumenTypography.fit(this, 15f); setTextColor(pal.mut); setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list.emptyView = empty
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // input row: prompt, flat box, Paste / Send
        root.addView(View(this).apply { setBackgroundColor(pal.bd) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(12), dp(8), dp(8), dp(8)) }
        row.addView(TextView(this).apply {
            text = "›"; typeface = Typeface.MONOSPACE; LumenTypography.fit(this, 20f); setTextColor(pal.ac); gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        input = EditText(this).apply {
            setTextColor(pal.fg); setHintTextColor(pal.mut); background = null
            typeface = Typeface.MONOSPACE; LumenTypography.fit(this, 14f)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            hint = "Type or paste text"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // File attachments use the same PCShare transfer protocol and the
        // currently selected messaging target (phone or Windows PC).
        row.addView(TextView(this).apply {
            text = "📎"
            LumenTypography.fit(this, 20f)
            setTextColor(pal.fg)
            contentDescription = "Attach and send files"
            gravity = Gravity.CENTER
            minimumWidth = dp(44); minimumHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(pal.cont); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { chooseAttachmentFiles() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(4) })
        row.addView(pill("Paste", false) { paste() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(4) })
        row.addView(pill("Send", true) { sendTyped() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(6) })
        root.addView(row)

        setContentView(root)
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs while this screen is open
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (AppPal.json(this) != palSig) { recreate(); return }
        Notes.visible = true
        Notes.listeners.add(onChange)
        refresh()
        // let the share sheet / tile see the current device list right away
        Thread { Shortcuts.sync(applicationContext) }.also { it.isDaemon = true }.start()
    }

    override fun onPause() {
        Notes.visible = false
        Notes.listeners.remove(onChange)
        super.onPause()
    }

    private fun refresh() {
        data = Notes.all()
        adapter.notifyDataSetChanged()
        if (data.isNotEmpty()) list.setSelection(data.size - 1)
        val n = TextSend.online().size
        info.text = "$n device" + (if (n != 1) "s" else "") + " online"
        val d = TextSend.defaultName()
        val id = Cfg.defTarget()
        toLine.text = when {
            id.isNotEmpty() && d != null -> "To: $d  ▾   (default device)"
            id.isNotEmpty() -> "To: a device that is offline now  ▾"
            else -> {
                val on = TextSend.online()
                if (on.size == 1) "To: ${on[0].name}  ▾   (the only device online)" else "To: ask every time  ▾"
            }
        }
    }

    // The native Android file picker grants read access only to user-selected
    // files. No all-files permission or separate network transport is needed.
    private fun chooseAttachmentFiles() {
        val target = TextSend.target()
        if (target != null) {
            openAttachmentPicker(target.id)
            return
        }
        val online = TextSend.online()
        if (online.isEmpty()) { toast("No device found yet"); return }
        chooseTarget { id -> openAttachmentPicker(id) }
    }

    private fun openAttachmentPicker(targetId: String) {
        attachmentTargetId = targetId
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, REQUEST_ATTACH_FILES)
        } catch (_: android.content.ActivityNotFoundException) {
            attachmentTargetId = null
            toast("No file picker available")
        }
    }

    @Deprecated("Uses the Activity result API supported by this native Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        if (requestCode != REQUEST_ATTACH_FILES) return
        val selectedPeerId = attachmentTargetId
        attachmentTargetId = null
        if (resultCode != RESULT_OK || selectedPeerId.isNullOrBlank()) return

        val uris = LinkedHashSet<Uri>()
        result?.data?.let { uris.add(it) }
        result?.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) uris.add(clips.getItemAt(index).uri)
        }
        if (uris.isEmpty()) { toast("No files selected"); return }
        val peer = TextSend.find(selectedPeerId)
        if (peer == null) { toast("Selected device is no longer available"); return }

        val count = uris.size
        toast("Sending $count attachment(s) to ${peer.name}")
        MessageFileAttachments.send(applicationContext, peer, uris.toList()) { error ->
            if (isFinishing || isDestroyed) return@send
            toast(error ?: "Sent $count attachment(s) to ${peer.name}")
        }
    }

    // ---- sending
    private fun paste() {
        val t = try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Throwable) { null }
        if (t.isNullOrEmpty()) { toast("Clipboard is empty"); return }
        input.text.insert(input.selectionStart.coerceAtLeast(0), t)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) { toast("Type something first"); return }
        val peer = TextSend.target()
        if (peer != null) { sendTo(t, peer.id); return }
        val on = TextSend.online()
        if (on.isEmpty()) { toast("No device found yet"); return }
        chooseTarget { id -> sendTo(t, id) }   // several devices and no default: ask once
    }

    private fun sendTo(t: String, id: String) {
        TextSend.send(t, id) { err, name ->
            if (err != null) toast(err) else { input.setText(""); toast("Sent to $name") }
        }
    }

    /** Device picker. [then] = send with the picked device (one-off, default unchanged); null = choose the DEFAULT device. */
    private fun chooseTarget(then: ((String) -> Unit)?) {
        val on = TextSend.online()
        val labels = ArrayList<String>()
        val ids = ArrayList<String>()
        if (then == null) { labels.add("Ask every time"); ids.add("") }
        on.forEach { labels.add(it.name + if (then == null && it.id == Cfg.defTarget()) "  ✓" else ""); ids.add(it.id) }
        if (ids.isEmpty()) { toast("No device found yet"); return }
        AppPal.dlg(this)
            .setTitle(if (then == null) "Send text to… (default)" else "Send to…")
            .setItems(labels.toTypedArray()) { _, w ->
                if (then != null) then(ids[w])
                else {
                    Cfg.setDefTarget(ids[w])
                    refresh()
                    Thread { Shortcuts.sync(applicationContext) }.also { it.isDaemon = true }.start()
                }
            }.show()
    }

    // ---- menu
    private fun menu() {
        val items = arrayOf(
            "Auto-copy received text: " + (if (Cfg.autoCopy()) "ON" else "off"),
            "Clipboard sync (while LANShare is open): " + (if (Cfg.clipSync()) "ON" else "off"),
            "Clear inbox"
        )
        AppPal.dlg(this).setItems(items) { _, w ->
            when (w) {
                0 -> { Cfg.setAutoCopy(!Cfg.autoCopy()); toast(if (Cfg.autoCopy()) "Received text goes to the clipboard at once" else "Auto-copy off") }
                1 -> {
                    val on = !Cfg.clipSync()
                    if (on && Cfg.defTarget().isEmpty()) toast("Tip: pick a default device first - sync sends to it")
                    Cfg.setClipSync(on)
                    ClipSync.refresh(this)
                    toast(if (on) "Text you copy is sent to the default device while this app is open" else "Clipboard sync off")
                }
                2 -> AppPal.dlg(this).kind("danger").setTitle("Clear the inbox?").setMessage("Removes the list on this phone only.")
                    .setPositiveButton("Clear") { _, _ -> Notes.clear() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    // ---- helpers
    private fun toast(m: String) { Lm.snack(this, m, Toast.LENGTH_SHORT) }

    private fun copy(t: String) {
        ClipSync.mark(t)   // not an echo for clipboard sync
        ClipPush.copy(this, t); toast("Copied")
    }

    private fun copyRaw(t: String, what: String) {
        ClipSync.mark(t)
        ClipPush.copy(this, t); toast(what)
    }

    private fun whenText(ts: Long): String =
        if (DateUtils.isToday(ts)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

    private fun openUrl(u: String) {
        try {
            val uri = android.net.Uri.parse(u.trim())
            val sc = uri.scheme?.lowercase()
            if (sc == "http" || sc == "https" || sc == "mailto" || sc == "tel") startActivity(Intent(Intent.ACTION_VIEW, uri)) else toast(u)
        } catch (_: Exception) { toast("Cannot open link") }
    }

    private fun tint(color: Int, a: Float): Int = ColorUtils.blendARGB(pal.bg, color, a)

    /** Flat button of the input row: Paste = surface, Send = the theme's primary. */
    private fun pill(label: String, primary: Boolean, click: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; LumenTypography.fit(this, 14f)
        setTextColor(if (primary) pal.onac else pal.fg)
        background = GradientDrawable().apply { setColor(if (primary) pal.ac else pal.cont); cornerRadius = dp(10).toFloat() }
        stateListAnimator = null
        minWidth = dp(64); minimumWidth = dp(64); minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { click() }
    }

    /** Small text action (Copy, Open link, the three-dot menu) with a 40 dp touch height. */
    private fun act(label: String, desc: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label; LumenTypography.fit(this, 12f); setTextColor(pal.mut); gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0); minimumHeight = dp(40); contentDescription = desc
        setOnClickListener { click() }
    }

    private fun setMode(id: Long, mode: String?) {
        if (mode == null) modes.remove(id) else modes[id] = mode
        cache.remove(id)
        adapter.notifyDataSetChanged()
    }

    // ---- one message -> blocks (Markdown, fenced code, or a pasted snippet guessed to be code)
    private fun blocksFor(n: Notes.Note): List<MdRender.Block> {
        cache.get(n.id)?.let { return it }
        val t = n.text
        val mode = modes[n.id]
        val fenced = t.contains("```") || t.contains("~~~")
        val guess = if (mode == "text" || fenced) null else (CodeGuess.guess(t) ?: if (mode == "code") "text" else null)
        val out: List<MdRender.Block> = when {
            guess != null -> listOf(MdRender.Block.Code(guess, t.trim('\n')))
            mode == "text" -> listOf(MdRender.Block.Text(t.trim('\n')))
            else -> MdRender.blocks(t, pal.fg, night, resources.displayMetrics.density) { u -> openUrl(u) }
        }
        cache.put(n.id, out)
        return out
    }

    private fun actions(n: Notes.Note) {
        val link = ClipPush.linkOf(n.text)
        val fenced = n.text.contains("```") || n.text.contains("~~~")
        val guess = if (fenced) null else CodeGuess.guess(n.text)
        val codes: List<String> =
            if (fenced) MdRender.blocks(n.text, pal.fg, night, resources.displayMetrics.density) { }.filterIsInstance<MdRender.Block.Code>().map { it.code }
            else if (guess != null) listOf(n.text.trim('\n')) else emptyList()
        val looksCode = codes.isNotEmpty()
        val mode = modes[n.id]
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels.add("Copy message"); acts.add { copy(n.text) }
        if (looksCode) { labels.add(if (codes.size > 1) "Copy all code" else "Copy code"); acts.add { copyRaw(codes.joinToString("\n\n"), "Code copied") } }
        if (link != null) { labels.add("Open link"); acts.add { if (!ClipPush.open(this, link)) toast("No app can open this link") } }
        if (mode != "code" && !looksCode) { labels.add("Show as code"); acts.add { setMode(n.id, "code") } }
        if (mode != "text" && looksCode) { labels.add("Show as plain text"); acts.add { setMode(n.id, "text") } }
        if (mode != null) { labels.add("Reset view"); acts.add { setMode(n.id, null) } }
        labels.add("Share…"); acts.add {
            try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Share text")) } catch (_: Throwable) {}
        }
        labels.add("Delete"); acts.add { Notes.remove(n.id) }
        AppPal.dlg(this).setItems(labels.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    // ---- views
    private fun headLine(n: Notes.Note, color: Int): CharSequence {
        val a = (if (n.out) "› To " else "● From ") + n.peer
        return SpannableStringBuilder(a).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val st = length
            append("  ·  " + whenText(n.ts))
            setSpan(ForegroundColorSpan(pal.mut), st, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun bodyText(t: CharSequence, out: Boolean, wire: (View) -> Unit): TextView = TextView(this).apply {
        text = t; LumenTypography.fit(this, 15f)
        setTextColor(if (out) ColorUtils.blendARGB(pal.fg, outCol, 0.15f) else pal.fg)
        movementMethod = LinkMovementMethod.getInstance()   // links in the text are tappable
        isFocusable = false
        wire(this)                                          // a tap on text that is not a link still copies the message
    }

    private fun codeCard(key: String, b: MdRender.Block.Code, wire: (View) -> Unit): View {
        val lines = b.code.split('\n')
        val total = lines.size
        val capped = total > CAP_LINES && !expanded.contains(key)
        val shown = if (capped) lines.subList(0, CAP_LINES) else lines
        val txt = shown.joinToString("\n")
        val sp = SpannableString(txt)
        val hk = if (b.lang.isEmpty() || b.lang == "text" || b.lang == "table") null else CodeHl.key(b.lang)
        if (hk != null && hk != "md" && b.code.length <= 60_000) {
            val arr = try { CodeHl.scan(txt, hk) } catch (_: Throwable) { null }
            if (arr != null) {
                var k = 0
                while (k + 2 < arr.size) {
                    val s = arr[k]; val e = arr[k + 1]
                    if (s >= 0 && e > s && e <= txt.length) sp.setSpan(ForegroundColorSpan(CodeHl.color(this, arr[k + 2], night)), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    k += 3
                }
            }
        }
        val codeBg = ColorUtils.blendARGB(pal.bg, pal.fg, 0.04f)

        val lbl = TextView(this).apply { text = b.lang.ifEmpty { "text" }; LumenTypography.fit(this, 12f); setTextColor(pal.mut); typeface = Fnt.semi(); setPadding(dp(10), 0, dp(4), 0) }
        val cpy = act("Copy", "Copy code") {}
        cpy.setOnClickListener {
            copyRaw(b.code, "Code copied")
            cpy.text = "Copied"; cpy.setTextColor(CodeHl.color(this, CodeHl.STRING, night))
            ui.postDelayed({ cpy.text = "Copy"; cpy.setTextColor(pal.mut) }, 1200)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(pal.cont)
            addView(lbl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (total > CAP_LINES) addView(act(if (capped) "▾ Show all $total lines" else "▴ Collapse", if (capped) "Show all lines" else "Collapse code") {
                if (!expanded.remove(key)) expanded.add(key)
                adapter.notifyDataSetChanged()
            }.also { it.setTextColor(outCol) })
            addView(cpy)
        }
        wire(head)

        val code = TextView(this).apply {
            typeface = Typeface.MONOSPACE; LumenTypography.fit(this, 12f); setTextColor(pal.fg)
            setPadding(dp(10), dp(6), dp(10), dp(6)); setHorizontallyScrolling(true)
            text = sp
        }
        val scroll = HorizontalScrollView(this).apply {
            isFillViewport = true; isHorizontalScrollBarEnabled = false
            addView(code, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (shown.size > 1) {
            body.addView(TextView(this).apply {
                typeface = Typeface.MONOSPACE; LumenTypography.fit(this, 12f); setTextColor(ColorUtils.setAlphaComponent(pal.mut, 170))
                gravity = Gravity.END; setBackgroundColor(pal.cont); setPadding(dp(8), dp(6), dp(6), dp(6))
                text = (1..shown.size).joinToString("\n")
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(codeBg); addView(head); addView(body) }
        return LinearLayout(this).apply { setBackgroundColor(pal.bd); setPadding(1, 1, 1, 1); addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun tableView(b: MdRender.Block.Table): View {
        val headBg = ColorUtils.blendARGB(pal.bg, pal.fg, 0.10f)
        val tl = TableLayout(this).apply { setBackgroundColor(pal.bd); setPadding(1, 1, 0, 0) }
        fun row(cells: List<CharSequence>, head: Boolean) {
            val tr = TableRow(this)
            for ((k, t) in cells.withIndex()) {
                val tv = TextView(this).apply {
                    text = t; LumenTypography.fit(this, 14f); setTextColor(pal.fg)
                    typeface = if (head) Fnt.semi() else Fnt.med()
                    setPadding(dp(10), dp(6), dp(10), dp(6)); maxWidth = dp(260)
                    gravity = when (b.align.getOrElse(k) { 0 }) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
                    setBackgroundColor(if (head) headBg else pal.bg)
                }
                val lp = TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                lp.setMargins(0, 0, 1, 1)
                tr.addView(tv, lp)
            }
            tl.addView(tr)
        }
        row(b.head, true)
        for (r in b.rows) row(r, false)
        return HorizontalScrollView(this).apply { addView(tl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(p: Int) = data[p]
        override fun getItemId(p: Int) = data[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val n = data[p]
            val color = if (n.out) outCol else inCol
            val alt = p % 2 == 1                                   // every other message a little stronger, so neighbours are easy to tell apart
            val wire: (View) -> Unit = { v -> v.setOnClickListener { copy(n.text) }; v.setOnLongClickListener { actions(n); true } }

            val content = LinearLayout(this@InboxActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(4), dp(10)) }
            val link = ClipPush.linkOf(n.text)
            val hdr = LinearLayout(this@InboxActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            hdr.addView(TextView(this@InboxActivity).apply {
                text = headLine(n, color); LumenTypography.fit(this, 12f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            hdr.addView(act("Copy", "Copy message") { copy(n.text) })
            if (link != null) hdr.addView(act("Open link", "Open link") { if (!ClipPush.open(this@InboxActivity, link)) toast("No app can open this link") })
            hdr.addView(act("⋮", "More actions for this message") { actions(n) })
            content.addView(hdr)

            for ((i, b) in blocksFor(n).withIndex()) {
                val v: View = when (b) {
                    is MdRender.Block.Text -> bodyText(b.text, n.out, wire)
                    is MdRender.Block.Code -> codeCard("${n.id}:$i", b, wire)
                    is MdRender.Block.Table -> tableView(b)
                }
                content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); leftMargin = dp(2) })
            }

            val band = LinearLayout(this@InboxActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(tint(color, if (alt) 0.12f else 0.08f))
                addView(View(this@InboxActivity).apply { setBackgroundColor(tint(color, 0.55f)) }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            wire(band)
            return FrameLayout(this@InboxActivity).apply {
                setPadding(0, dp(3), 0, dp(3))
                addView(band, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private companion object {
        const val REQUEST_ATTACH_FILES = 2917
        const val CAP_LINES = 30   // code blocks longer than this start collapsed ("Show all N lines")
    }
}
