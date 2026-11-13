package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.vault.VaultNode
import com.lanshare.app.core.vault.VaultSession
import com.lanshare.app.core.vault.VaultStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Vault (Lockdown) screen, opened from the main menu tile: create the vault, unlock it with the password, browse / add / export / rename / delete.
 * Screen is FLAG_SECURE (no screenshots, hidden in recents). Plain views, Lumen colours. Fingerprint is not wired yet (needs androidx.biometric, lockdown.md Part 2).
 */
class VaultActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val lm by lazy { Lumen.of(this) }
    private val d by lazy { resources.displayMetrics.density }
    private val volume: File = Environment.getExternalStorageDirectory()
    private lateinit var root: LinearLayout
    private var cur: String? = null                 // current folder id, null = vault root
    private var busy = false
    private val lockListener: () -> Unit = { ui.post { if (!isFinishing) { cur = null; render() } } }

    private fun dp(v: Int) = Math.round(v * d)
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(lm.canvas) }
        setContentView(root)
        VaultSession.onLock(lockListener)
        render()
    }

    override fun onStart() { super.onStart(); VaultSession.onForeground(); render() }
    override fun onStop() { super.onStop(); VaultSession.onBackground() }
    override fun onDestroy() { super.onDestroy(); exec.shutdown() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val st = VaultSession.store
        if (VaultSession.unlocked && cur != null && st != null) { cur = st.get(cur!!)?.parent; render() } else super.onBackPressed()
    }

    // ------------------------------------------------------------------ views

    private fun render() {
        root.removeAllViews()
        when {
            VaultSession.unlocked -> browser()
            VaultStore.exists(volume) -> credentials(false)
            else -> credentials(true)
        }
    }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun btn(t: String, primary: Boolean, fn: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setTextColor(if (primary) lm.onPrimary else lm.ink)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (primary) lm.primary else lm.surfaceHi) }
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { fn() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; setTextColor(lm.ink); setHintTextColor(lm.inkMut)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
    }

    private fun chars(e: EditText): CharArray { val c = CharArray(e.length()); e.text.getChars(0, c.size, c, 0); return c }

    private fun credentials(create: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(72), dp(24), dp(24)) }
        box.addView(text("Vault", 28f, lm.ink, true))
        box.addView(text(if (create) "Create a password. There is no way to recover files if you forget it." else "Enter your vault password.", 14f, lm.inkMut).apply { setPadding(0, dp(8), 0, dp(24)) })
        val p1 = field("Password"); box.addView(p1)
        val p2 = field("Repeat password"); if (create) box.addView(p2)
        val msg = text("", 13f, lm.error).apply { setPadding(0, dp(8), 0, dp(8)) }; box.addView(msg)
        box.addView(btn(if (create) "Create vault" else "Unlock", true) {
            if (busy) return@btn
            val a = chars(p1); val b = chars(p2)
            if (create && a.size < 6) { msg.text = "Use at least 6 characters"; return@btn }
            if (create && !a.contentEquals(b)) { msg.text = "Passwords do not match"; return@btn }
            msg.text = if (create) "Creating (a few seconds)..." else "Unlocking..."; busy = true
            exec.execute {
                var err: String? = null
                try {
                    if (create) { VaultStore.create(volume, a).lock() }
                    VaultSession.start(VaultStore.open(volume, a))
                } catch (e: Exception) { err = if (create) "Could not create the vault: ${e.message}" else "Wrong password" }
                finally { a.fill('\u0000'); b.fill('\u0000') }
                ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render() } }
            }
        })
        root.addView(box)
    }

    private fun browser() {
        val st = VaultSession.store ?: return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(40), dp(8), dp(8)); setBackgroundColor(lm.surface) }
        val title = text(cur?.let { st.get(it)?.name } ?: "Vault", 20f, lm.ink, true)
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("Lock", false) { VaultSession.lock() })
        root.addView(bar)
        val act = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        act.addView(btn("Add files", true) { pickFiles() })
        act.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        act.addView(btn("New folder", false) { ask("New folder", "") { n -> bg { st.mkdir(cur, n) } } })
        root.addView(act)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val items = st.list(cur)
        if (cur != null) list.addView(row("..", "Up", true) { cur = st.get(cur!!)?.parent; render() })
        if (items.isEmpty()) list.addView(text("Empty. Tap Add files.", 14f, lm.inkMut).apply { setPadding(dp(24), dp(32), dp(24), 0) })
        for (n in items) list.addView(row(n.name, if (n.dir) "Folder" else fmt(n.size), n.dir) { if (n.dir) { cur = n.id; render() } else fileMenu(n) }.also { v -> v.setOnLongClickListener { fileMenu(n); true } })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun row(name: String, sub: String, dir: Boolean, fn: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(text((if (dir) "\uD83D\uDCC1  " else "") + name, 16f, lm.ink))
        addView(text(sub, 12f, lm.inkMut))
        setOnClickListener { fn() }
    }

    private fun fmt(n: Long) = when { n < 1024 -> "$n B"; n < 1 shl 20 -> "${n / 1024} KB"; n < 1L shl 30 -> "%.1f MB".format(n / 1048576.0); else -> "%.2f GB".format(n / 1073741824.0) }

    // ------------------------------------------------------------------ actions

    private fun fileMenu(n: VaultNode) {
        val st = VaultSession.store ?: return
        val opts = if (n.dir) arrayOf("Rename", "Delete") else arrayOf("Export to Download", "Rename", "Delete")
        AlertDialog.Builder(this).setTitle(n.name).setItems(opts) { _, i ->
            when (opts[i]) {
                "Export to Download" -> bg {
                    val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); dl.mkdirs()
                    val base = n.name.substringBeforeLast('.', n.name); val ext = if (n.name.contains('.')) "." + n.name.substringAfterLast('.') else ""
                    var f = File(dl, n.name); var k = 1
                    while (f.exists()) { f = File(dl, "$base ($k)$ext"); k++ }
                    st.export(n.id, f); val saved = f.name; ui.post { toast("Saved to Download/$saved") }
                }
                "Rename" -> ask("Rename", n.name) { v -> bg { st.rename(n.id, v) } }
                "Delete" -> AlertDialog.Builder(this).setMessage("Delete \"${n.name}\" from the vault for good?").setPositiveButton("Delete") { _, _ -> bg { st.delete(n.id) } }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun ask(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(init); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("OK") { _, _ -> val v = e.text.toString().trim(); if (v.isNotEmpty()) ok(v) }.setNegativeButton("Cancel", null).show()
    }

    /** Runs a vault change off the UI thread, then redraws; any failure is shown as a toast. */
    private fun bg(job: () -> Unit) {
        exec.execute {
            val err = try { job(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            ui.post { if (err != null) toast(err); render() }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_ADD)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_ADD || res != RESULT_OK || data == null) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) } ?: data.data?.let { uris.add(it) }
        val st = VaultSession.store ?: return
        val parent = cur
        toast("Encrypting ${uris.size} file(s)...")
        bg {
            for (u in uris) {
                var name = "file"
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
                contentResolver.openInputStream(u)!!.use { st.addStream(it, parent, name, System.currentTimeMillis(), contentResolver.getType(u) ?: "") }
            }
        }
    }

    companion object {
        private const val REQ_ADD = 71
        fun intent(c: Context): Intent = Intent(c, VaultActivity::class.java)
    }
}
