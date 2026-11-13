package com.lanshare.app

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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lanshare.app.core.vault.VaultAuthException
import com.lanshare.app.core.vault.VaultBio
import com.lanshare.app.core.vault.VaultNode
import com.lanshare.app.core.vault.VaultPrefs
import com.lanshare.app.core.vault.VaultRecovery
import com.lanshare.app.core.vault.VaultThrottle
import com.lanshare.app.core.vault.VaultSession
import com.lanshare.app.core.vault.VaultStore
import java.io.File
import java.util.concurrent.Executors

/**
 * Vault (Lockdown) screen, opened from the main menu tile: create the vault, unlock it with the password, browse / add / export / rename / delete.
 * Screen is FLAG_SECURE (no screenshots, hidden in recents). Plain views, Lumen colours. Fingerprint (androidx.biometric), throttle, forgot-password (recovery code) and the Settings menu are in; see lockdown.md Part 2 status.
 */
class VaultActivity : FragmentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val lm by lazy { Lumen.of(this) }
    private val d by lazy { resources.displayMetrics.density }
    private val volume: File = Environment.getExternalStorageDirectory()
    private lateinit var root: LinearLayout
    private var cur: String? = null                 // current folder id, null = vault root
    private var busy = false
    private var bioAsked = false
    private val prefs by lazy { VaultPrefs(this) }
    private val throttle by lazy { VaultThrottle(prefs) }
    private val lockListener: () -> Unit = { ui.post { if (!isFinishing) { cur = null; render() } } }

    private fun dp(v: Int) = Math.round(v * d)
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(lm.canvas) }
        setContentView(root)
        VaultSession.autoLockSec = prefs.autoLockSec
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
            if (create && a.size < 8) { msg.text = "Use at least 8 characters"; return@btn }
            if (create && !a.contentEquals(b)) { msg.text = "Passwords do not match"; return@btn }
            if (!create) { val w = throttle.waitMs(); if (w > 0) { msg.text = "Too many attempts. Wait ${(w + 999) / 1000} s"; a.fill('\u0000'); return@btn } }
            msg.text = if (create) "Creating (a few seconds)..." else "Unlocking..."; busy = true
            exec.execute {
                var err: String? = null
                try {
                    if (create) { VaultStore.create(volume, a).lock() }
                    VaultSession.start(VaultStore.open(volume, a))
                    if (!create) throttle.onSuccess()
                } catch (e: Exception) {
                    if (!create && e is VaultAuthException) throttle.onFail()
                    err = if (create) "Could not create the vault: ${e.message}" else if (e is VaultAuthException) "Wrong password" else "Could not open the vault: ${e.message}"
                }
                finally { a.fill('\u0000'); b.fill('\u0000') }
                ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render(); if (bioOk() && !bioOn() && !prefs.bioDeclined) offerBio() } }
            }
        })
        if (!create) {
            if (bioOk() && bioOn()) {
                box.addView(btn("Use fingerprint", false) { unlockBio(msg) })
                if (!bioAsked) { bioAsked = true; ui.post { unlockBio(msg) } }
            }
            box.addView(btn("Forgot password", false) { forgot(msg) })
        }
        root.addView(box)
    }

    private fun browser() {
        val st = VaultSession.store ?: return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(40), dp(8), dp(8)); setBackgroundColor(lm.surface) }
        val title = text(cur?.let { st.get(it)?.name } ?: "Vault", 20f, lm.ink, true)
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn("Settings", false) { settings() })
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

    // ------------------------------------------------------------------ fingerprint

    private fun bioOk() = try { BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS } catch (e: Exception) { false }
    private fun bioOn() = try { VaultBio.isEnabled(this) } catch (e: Exception) { false }

    private fun bioPrompt(title: String, cipher: javax.crypto.Cipher, onOk: (javax.crypto.Cipher) -> Unit, onErr: (String) -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { val c = r.cryptoObject?.cipher; if (c != null) onOk(c) else onErr("Fingerprint failed") }
            override fun onAuthenticationError(code: Int, s: CharSequence) { if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) onErr(s.toString()) }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), cb).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun unlockBio(msg: TextView) {
        if (busy) return
        val c = VaultBio.cipherForUnlock(this)
        if (c == null) { msg.text = "Fingerprint changed. Use your password, then turn fingerprint on again."; return }
        bioPrompt("Unlock vault", c, { ac ->
            val mk = VaultBio.unwrap(this, ac)
            if (mk == null) { msg.text = "Fingerprint is no longer valid. Use your password." }
            else {
                busy = true
                exec.execute {
                    var err: String? = null
                    try { VaultSession.start(VaultStore.openWithMasterKey(volume, mk)) } catch (e: Exception) { err = "Could not unlock: ${e.message}" } finally { mk.fill(0) }
                    ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render() } }
                }
            }
        }, { msg.text = it })
    }

    private fun offerBio() {
        AlertDialog.Builder(this).setTitle("Unlock with fingerprint?")
            .setMessage("Skip typing the password next time. The password stays the real key: after a reinstall you enter it once and turn fingerprint on again.")
            .setPositiveButton("Turn on") { _, _ -> enableBio() }
            .setNeutralButton("Not now", null)
            .setNegativeButton("Don't ask again") { _, _ -> prefs.bioDeclined = true }.show()
    }

    private fun enableBio() {
        val st = VaultSession.store ?: return
        val c = try { VaultBio.cipherForEnable() } catch (e: Exception) { toast("Fingerprint not available: ${e.message}"); return }
        bioPrompt("Turn on fingerprint", c, { ac ->
            val mk = st.masterKeyCopy()
            try { VaultBio.finishEnable(this, ac, mk); prefs.bioDeclined = false; toast("Fingerprint is on") }
            catch (e: Exception) { VaultBio.disable(this); toast("Could not turn it on: ${e.message}") }
            finally { mk.fill(0) }
        }, { toast(it) })
    }

    // ------------------------------------------------------------------ forgot password / settings

    private fun forgot(msg: TextView) {
        val e = EditText(this).apply { hint = "Recovery code"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        AlertDialog.Builder(this).setTitle("Forgot password").setMessage("Enter your recovery code. If you set a backup password, type it in the password field instead.").setView(e)
            .setPositiveButton("Unlock") { _, _ ->
                val secret = VaultRecovery.fromCode(e.text.toString())
                if (secret == null) { msg.text = "That recovery code is not valid. Check the typing." }
                else {
                    busy = true; msg.text = "Unlocking..."
                    exec.execute {
                        var err: String? = null
                        try { VaultSession.start(VaultStore.openWithRecovery(volume, secret)) }
                        catch (x: Exception) { err = if (x is VaultAuthException) "Wrong recovery code" else "Could not unlock: ${x.message}" }
                        finally { secret.fill(0) }
                        ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render(); forceNewPassword() } }
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Multi-field password dialog. checkNew: the last two fields are "new" and "repeat" (min 8, must match). ok gets the arrays and must wipe them. */
    private fun pwDialog(title: String, hints: List<String>, checkNew: Boolean, cancelable: Boolean, ok: (List<CharArray>) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fs = hints.map { field(it).also { f -> box.addView(f) } }
        val err = text("", 13f, lm.error); box.addView(err)
        val dlg = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(cancelable).setPositiveButton("OK", null)
            .apply { if (cancelable) setNegativeButton("Cancel", null) }.create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = fs.map { chars(it) }
            if (checkNew) {
                val n = v[v.size - 2]; val r = v[v.size - 1]
                if (n.size < 8) { err.text = "Use at least 8 characters"; v.forEach { it.fill('\u0000') }; return@setOnClickListener }
                if (!n.contentEquals(r)) { err.text = "Passwords do not match"; v.forEach { it.fill('\u0000') }; return@setOnClickListener }
            }
            dlg.dismiss(); ok(v)
        }
    }

    private fun forceNewPassword() {
        val st = VaultSession.store ?: return
        pwDialog("Set a new password", listOf("New password", "Repeat new password"), true, false) { v ->
            bg { try { st.resetPassword(v[0]); throttle.onSuccess() } finally { v.forEach { it.fill('\u0000') } } }
        }
    }

    private fun settings() {
        val st = VaultSession.store ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, f: () -> Unit) { acts.add(t to f) }
        add("Change password") {
            pwDialog("Change password", listOf("Current password", "New password", "Repeat new password"), true, true) { v ->
                val w = throttle.waitMs()
                if (w > 0) { toast("Too many attempts. Wait ${(w + 999) / 1000} s"); v.forEach { it.fill('\u0000') } }
                else bg {
                    try { st.changePassword(v[0], v[1]); throttle.onSuccess(); ui.post { toast("Password changed") } }
                    catch (e: VaultAuthException) { throttle.onFail(); throw Exception("Current password is wrong") }
                    finally { v.forEach { it.fill('\u0000') } }
                }
            }
        }
        if (bioOk()) add((if (bioOn()) "Turn off fingerprint" else "Turn on fingerprint")) { if (bioOn()) { VaultBio.disable(this); toast("Fingerprint is off") } else enableBio() }
        add("Auto-lock: ${lockLabel(prefs.autoLockSec)}") { autoLockMenu() }
        add((if (st.hasRecovery) "New recovery code (replaces the old one)" else "Create recovery code")) {
            bg { val sec = st.enableRecovery(); val code = VaultRecovery.toCode(sec); sec.fill(0); ui.post { showCode(code) } }
        }
        if (st.hasRecovery) add("Remove recovery code") {
            AlertDialog.Builder(this).setMessage("Remove the recovery code? Your password still opens the vault.").setPositiveButton("Remove") { _, _ -> bg { st.removeRecovery() } }.setNegativeButton("Cancel", null).show()
        }
        add((if (st.hasBackupPassword) "Change backup password" else "Set backup password")) {
            pwDialog("Backup password", listOf("Backup password", "Repeat backup password"), true, true) { v ->
                bg { try { st.setBackupPassword(v[0]); ui.post { toast("Backup password saved") } } finally { v.forEach { it.fill('\u0000') } } }
            }
        }
        if (st.hasBackupPassword) add("Remove backup password") {
            AlertDialog.Builder(this).setMessage("Remove the backup password?").setPositiveButton("Remove") { _, _ -> bg { st.removeBackupPassword() } }.setNegativeButton("Cancel", null).show()
        }
        AlertDialog.Builder(this).setTitle("Vault settings").setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun showCode(code: String) {
        AlertDialog.Builder(this).setTitle("Your recovery code")
            .setMessage("$code\n\nWrite it down and keep it away from this phone. It is shown only once. Anyone who has it can open your vault.")
            .setCancelable(false).setPositiveButton("I wrote it down", null).show()
    }

    private fun lockLabel(s: Int) = when (s) { 0 -> "immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; 300 -> "5 minutes"; -1 -> "never until app exit"; else -> "$s s" }

    private fun autoLockMenu() {
        val v = intArrayOf(0, 30, 60, 300, -1)
        AlertDialog.Builder(this).setTitle("Auto-lock").setItems(v.map { lockLabel(it) }.toTypedArray()) { _, i -> prefs.autoLockSec = v[i]; VaultSession.autoLockSec = v[i] }.show()
    }

    companion object {
        private const val REQ_ADD = 71
        fun intent(c: Context): Intent = Intent(c, VaultActivity::class.java)
    }
}
