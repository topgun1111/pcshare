package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.Notes
import java.text.DateFormat
import java.util.Date

/**
 * Text inbox: a chat-like list of everything sent to / received from other devices, plus a box to type or paste text and send it.
 * Tap an entry = copy it, long-press = copy / open link / share / delete. The device line chooses the default target of text sharing
 * (share-sheet entry, Direct Share, Quick Settings tile, notification quick-reply); the menu holds the Auto-copy and Clipboard-sync switches.
 */
class InboxActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var toLine: TextView
    private var data: List<Notes.Note> = emptyList()
    private val onChange: () -> Unit = { ui.post { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        TextSend.ensureRunning(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // header: title + menu
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(8), dp(6)) }
        head.addView(TextView(this).apply { text = "Text inbox"; textSize = 20f; setTextColor(Color.BLACK); setTypeface(typeface, android.graphics.Typeface.BOLD) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "⋮"; textSize = 24f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4)); setOnClickListener { menu() }
            contentDescription = "Menu"
        })
        root.addView(head)

        // target line
        toLine = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF5F6368.toInt()); setBackgroundColor(0xFFF3F3F3.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { chooseTarget(null) }
        }
        root.addView(toLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // list (+ empty hint)
        list = ListView(this).apply { divider = null; dividerHeight = 0; isStackFromBottom = true; transcriptMode = android.widget.AbsListView.TRANSCRIPT_MODE_NORMAL; adapter = this@InboxActivity.adapter }
        val empty = TextView(this).apply {
            text = "Nothing yet.\nType below, or share any text to LANShare."; gravity = Gravity.CENTER; textSize = 15f; setTextColor(0xFF80868B.toInt()); setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val frame = FrameLayout(this)
        frame.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        list.emptyView = empty
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // input row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(8), dp(6), dp(8), dp(8)) }
        input = EditText(this).apply {
            hint = "Type or paste text"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Paste"; isAllCaps = false; setOnClickListener { paste() } })
        row.addView(Button(this).apply { text = "Send"; isAllCaps = false; setOnClickListener { sendTyped() } })
        root.addView(row)

        setContentView(root)
        com.lanshare.app.ops.JobOverlay.install(this)   // progress bubble for background jobs while this screen is open
        refresh()
    }

    override fun onResume() {
        super.onResume()
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> { Cfg.setAutoCopy(!Cfg.autoCopy()); toast(if (Cfg.autoCopy()) "Received text goes to the clipboard at once" else "Auto-copy off") }
                1 -> {
                    val on = !Cfg.clipSync()
                    if (on && Cfg.defTarget().isEmpty()) toast("Tip: pick a default device first - sync sends to it")
                    Cfg.setClipSync(on)
                    ClipSync.refresh(this)
                    toast(if (on) "Text you copy is sent to the default device while this app is open" else "Clipboard sync off")
                }
                2 -> AlertDialog.Builder(this).setTitle("Clear the inbox?").setMessage("Removes the list on this phone only.")
                    .setPositiveButton("Clear") { _, _ -> Notes.clear() }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    // ---- list
    private fun toast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    private fun copy(t: String) {
        ClipSync.mark(t)   // not an echo for clipboard sync
        ClipPush.copy(this, t); toast("Copied")
    }

    private fun whenText(ts: Long): String =
        if (DateUtils.isToday(ts)) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))

    private fun actions(n: Notes.Note) {
        val link = ClipPush.linkOf(n.text)
        val labels = ArrayList<String>(); labels.add("Copy")
        if (link != null) labels.add("Open link")
        labels.add("Share…"); labels.add("Delete")
        AlertDialog.Builder(this).setItems(labels.toTypedArray()) { _, w ->
            when (labels[w]) {
                "Copy" -> copy(n.text)
                "Open link" -> if (link != null && !ClipPush.open(this, link)) toast("No app can open this link")
                "Share…" -> try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Share text"))
                } catch (_: Throwable) {}
                "Delete" -> Notes.remove(n.id)
            }
        }.show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(p: Int) = data[p]
        override fun getItemId(p: Int) = data[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val n = data[p]
            val root = LinearLayout(this@InboxActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (n.out) Gravity.END else Gravity.START
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            val bubble = LinearLayout(this@InboxActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(if (n.out) 0xFFFFE0B2.toInt() else 0xFFEEEEEE.toInt()); cornerRadius = dp(14).toFloat() }
                setOnClickListener { copy(n.text) }
                setOnLongClickListener { actions(n); true }
            }
            bubble.addView(TextView(this@InboxActivity).apply {
                text = (if (n.out) "To " else "From ") + n.peer + " · " + whenText(n.ts)
                textSize = 12f; setTextColor(0xFF5F6368.toInt())
            })
            bubble.addView(TextView(this@InboxActivity).apply {
                text = n.text.trim().take(1500); textSize = 15f; setTextColor(Color.BLACK)
                maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
                setPadding(0, dp(2), 0, 0)
            })
            root.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return root
        }
    }
}
