package com.lanshare.app

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
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
    private var forgotMsg: TextView? = null
    private var pendingKey: ByteArray? = null          // .lsrk bytes waiting for the save dialog
    @Volatile private var pendingRecoverySecret: ByteArray? = null // candidate is committed only after the user saves/confirms it
    private val lockListener: () -> Unit = { ui.post {
        pendingKey?.fill(0); pendingKey = null
        pendingRecoverySecret?.fill(0); pendingRecoverySecret = null
        if (!isFinishing) { cur = null; render() }
    } }

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

    override fun onStart() { super.onStart(); render() }   // auto-lock timing + screen-off are app-wide now (VaultAppHooks)
    override fun onDestroy() {
        VaultSession.removeOnLock(lockListener)
        pendingKey?.fill(0); pendingKey = null; pendingRecoverySecret?.fill(0); pendingRecoverySecret = null
        exec.shutdown(); super.onDestroy()
    }

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
        box.addView(text(vt("Vault"), 28f, lm.ink, true))
        box.addView(text(if (create) vt("Create a password. There is no way to recover files if you forget it.") else vt("Enter your vault password."), 14f, lm.inkMut).apply { setPadding(0, dp(8), 0, dp(24)) })
        val p1 = field(vt("Password")); box.addView(p1)
        val p2 = field(vt("Repeat password")); if (create) box.addView(p2)
        val msg = text("", 13f, lm.error).apply { setPadding(0, dp(8), 0, dp(8)) }; box.addView(msg)
        box.addView(btn(if (create) vt("Create vault") else vt("Unlock"), true) {
            if (busy) return@btn
            val a = chars(p1); val b = chars(p2)
            if (create && a.size < 8) { msg.text = vt("Use at least 8 characters"); return@btn }
            if (create && !a.contentEquals(b)) { msg.text = vt("Passwords do not match"); return@btn }
            if (!create) { val w = throttle.waitMs(); if (w > 0) { msg.text = vf("Too many attempts. Wait %s s", (w + 999) / 1000); a.fill('\u0000'); return@btn } }
            msg.text = if (create) vt("Creating (a few seconds)...") else vt("Unlocking..."); busy = true
            exec.execute {
                var err: String? = null
                try {
                    if (create) { VaultStore.create(volume, a).lock() }
                    VaultSession.start(VaultStore.open(volume, a))
                    if (!create) throttle.onSuccess()
                } catch (e: Exception) {
                    if (!create && e is VaultAuthException) throttle.onFail()
                    err = if (create) vf("Could not create the vault: %s", e.message) else if (e is VaultAuthException) vt("Wrong password") else vf("Could not open the vault: %s", e.message)
                }
                finally { a.fill('\u0000'); b.fill('\u0000') }
                ui.post {
                    busy = false
                    if (err != null) msg.text = err else {
                        cur = null; render()
                        if (create) offerRecovery() else if (bioOk() && !bioOn() && !prefs.bioDeclined) offerBio()
                    }
                }
            }
        })
        if (!create) {
            if (bioOk() && bioOn()) {
                box.addView(btn(vt("Use fingerprint"), false) { unlockBio(msg) })
                if (!bioAsked) { bioAsked = true; ui.post { unlockBio(msg) } }
            }
            box.addView(btn(vt("Forgot password"), false) { forgot(msg) })
        }
        root.addView(box)
    }

    private fun browser() {
        val st = VaultSession.store ?: return
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(40), dp(8), dp(8)); setBackgroundColor(lm.surface) }
        val title = text(cur?.let { st.get(it)?.name } ?: vt("Vault"), 20f, lm.ink, true)
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn(vt("Settings"), false) { settings() })
        bar.addView(btn(vt("Lock"), false) { VaultSession.lock() })
        root.addView(bar)
        val act = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        act.addView(btn(vt("Add files"), true) { pickFiles() })
        act.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        act.addView(btn(vt("New folder"), false) { ask(vt("New folder"), "") { n -> bg { st.mkdir(cur, n) } } })
        root.addView(act)
        // Gallery: photos and videos are square thumbnails (decoded in memory, VaultThumbs), folders / other files are full-width rows.
        val items = st.list(cur)
        val scr = resources.displayMetrics.widthPixels
        val span = if (scr > dp(640)) 5 else 3
        val cell = scr / span
        val data = ArrayList<Any>()
        if (cur != null) data.add("up")
        if (items.isEmpty()) data.add("empty")
        data.addAll(items)
        fun tile(x: Any) = x is VaultNode && !x.dir && (VaultViewActivity.isImage(x.mime, x.name) || VaultMode.isVideo(x.mime, x.name))
        class VH(v: View, val iv: android.widget.ImageView?, val tag: TextView?) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) { var id: String? = null }
        val rv = androidx.recyclerview.widget.RecyclerView(this)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, span).also { g ->
            g.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(p: Int) = if (tile(data[p])) 1 else span }
        }
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<VH>() {
            override fun getItemCount() = data.size
            override fun getItemViewType(p: Int) = if (tile(data[p])) 1 else 0
            override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH {
                val box = android.widget.FrameLayout(this@VaultActivity)
                if (t == 0) { box.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2); return VH(box, null, null) }
                box.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, cell)
                box.setPadding(1, 1, 1, 1)
                val iv = android.widget.ImageView(this@VaultActivity).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF222222.toInt()) }
                box.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
                val tg = TextView(this@VaultActivity).apply { text = "\u25B6"; textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()); setPadding(dp(6), 0, 0, dp(4)) }
                box.addView(tg, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                return VH(box, iv, tg)
            }
            override fun onBindViewHolder(h: VH, p: Int) {
                val d = data[p]
                if (!tile(d)) {
                    val box = h.itemView as android.widget.FrameLayout; box.removeAllViews()
                    box.addView(when {
                        d == "up" -> row("..", vt("Up"), true) { cur = st.get(cur!!)?.parent; scrollPos = 0; render() }
                        d == "empty" -> text(vt("Empty. Tap Add files."), 14f, lm.inkMut).apply { setPadding(dp(24), dp(32), dp(24), 0) }
                        else -> { val n = d as VaultNode; row(n.name, if (n.dir) vt("Folder") else fmt(n.size), n.dir) { openNode(n, items) }.also { v -> v.setOnLongClickListener { fileMenu(n); true } } }
                    })
                    return
                }
                val n = d as VaultNode
                h.id = n.id
                h.tag!!.visibility = if (VaultMode.isVideo(n.mime, n.name)) View.VISIBLE else View.GONE
                val have = VaultThumbs.get(n.id)
                h.iv!!.setImageBitmap(have)
                if (have == null) VaultThumbs.load(n, cell) { bm -> if (h.id == n.id && bm != null) h.iv.setImageBitmap(bm) }
                h.itemView.setOnClickListener { openNode(n, items) }
                h.itemView.setOnLongClickListener { fileMenu(n); true }
            }
            override fun onViewRecycled(h: VH) { h.id = null; h.iv?.setImageBitmap(null) }
        }
        gallery = rv
        if (scrollPos > 0 && scrollPos < data.size) rv.scrollToPosition(scrollPos)
        root.addView(rv, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun row(name: String, sub: String, dir: Boolean, fn: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(text((if (dir) "\uD83D\uDCC1  " else "") + name, 16f, lm.ink))
        addView(text(sub, 12f, lm.inkMut))
        setOnClickListener { fn() }
    }

    private var gallery: androidx.recyclerview.widget.RecyclerView? = null
    private var scrollPos = 0

    /** Photos / videos open in the app's OWN viewer and player in vault mode (VaultMode.kt); audio, PDF and small text have their own vault screens. */
    private fun openNode(n: VaultNode, sib: List<VaultNode>) {
        when {
            n.dir -> { cur = n.id; scrollPos = 0; render() }
            VaultViewActivity.isImage(n.mime, n.name) -> {
                val l = sib.filter { !it.dir && VaultViewActivity.isImage(it.mime, it.name) }
                remember(); ImageViewerActivity.pending = VaultMode.imagesJson(l, l.indexOfFirst { it.id == n.id })
                startActivity(Intent(this, ImageViewerActivity::class.java))
            }
            VaultMode.isVideo(n.mime, n.name) -> {
                val l = sib.filter { !it.dir && VaultMode.isVideo(it.mime, it.name) }
                remember(); PlayerActivity.pending = VaultMode.videosJson(sib, l, l.indexOfFirst { it.id == n.id })
                startActivity(Intent(this, PlayerActivity::class.java))
            }
            VaultMode.isAudio(n.mime, n.name) -> startActivity(Intent(this, VaultPlayActivity::class.java).putExtra("id", n.id))
            android.os.Build.VERSION.SDK_INT >= 26 && VaultViewActivity.isPdf(n.mime, n.name) -> startActivity(Intent(this, VaultPdfActivity::class.java).putExtra("id", n.id))
            viewable(n) -> startActivity(Intent(this, VaultViewActivity::class.java).putExtra("id", n.id))
            else -> fileMenu(n)
        }
    }

    /** The gallery is rebuilt in onStart; keep the scroll position across a viewer. */
    private fun remember() { (gallery?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.let { scrollPos = it.findFirstVisibleItemPosition().coerceAtLeast(0) } }

    /** Lockdown viewers: images and small text open in memory (VaultViewActivity), audio / video stream via VaultPlayActivity; the rest keeps the menu (export). */
    private fun viewable(n: VaultNode): Boolean {
        val ext = n.name.substringAfterLast('.', "").lowercase()
        return VaultViewActivity.isImage(n.mime, n.name) ||
            (n.mime.startsWith("text/") || ext in setOf("txt", "md", "json", "csv", "log", "xml", "ini", "yml", "yaml")) && n.size <= VaultViewActivity.MAX_TEXT
    }

    private fun fmt(n: Long) = when { n < 1024 -> "$n B"; n < 1 shl 20 -> "${n / 1024} KB"; n < 1L shl 30 -> "%.1f MB".format(n / 1048576.0); else -> "%.2f GB".format(n / 1073741824.0) }

    // ------------------------------------------------------------------ actions

    private fun fileMenu(n: VaultNode) {
        val st = VaultSession.store ?: return
        val opts = if (n.dir) arrayOf(vt("Rename"), vt("Delete")) else arrayOf(vt("Export to Download"), vt("Rename"), vt("Delete"))
        AppPal.dlg(this).setTitle(n.name).setItems(opts) { _, i ->
            when (opts[i]) {
                vt("Export to Download") -> bg {
                    val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); dl.mkdirs()
                    val base = n.name.substringBeforeLast('.', n.name); val ext = if (n.name.contains('.')) "." + n.name.substringAfterLast('.') else ""
                    var f = File(dl, n.name); var k = 1
                    while (f.exists()) { f = File(dl, "$base ($k)$ext"); k++ }
                    st.export(n.id, f); val saved = f.name; ui.post { toast(vf("Saved to Download/%s", saved)) }
                }
                vt("Rename") -> ask(vt("Rename"), n.name) { v -> bg { st.rename(n.id, v) } }
                vt("Delete") -> AppPal.dlg(this).kind("danger").setMessage(vf("Delete %s from the vault for good?", n.name)).setPositiveButton(vt("Delete")) { _, _ -> bg { st.delete(n.id) } }.setNegativeButton(vt("Cancel"), null).show()
            }
        }.show()
    }

    private fun ask(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { setText(init); setSingleLine(true) }
        AppPal.dlg(this).setTitle(title).setView(e).setPositiveButton(vt("OK")) { _, _ -> val v = e.text.toString().trim(); if (v.isNotEmpty()) ok(v) }.setNegativeButton(vt("Cancel"), null).show()
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
        if (req == REQ_SAVEKEY && (res != RESULT_OK || data?.data == null)) {
            pendingKey?.fill(0); pendingKey = null
            pendingRecoverySecret?.fill(0); pendingRecoverySecret = null
            return
        }
        if (res != RESULT_OK || data == null) return
        if (req == REQ_KEYFILE) { keyFilePicked(data.data); return }
        if (req == REQ_SAVEKEY) { saveKeyTo(data.data); return }
        if (req != REQ_ADD) return
        val uris = ArrayList<android.net.Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) } ?: data.data?.let { uris.add(it) }
        val st = VaultSession.store ?: return
        val parent = cur
        toast(vf("Encrypting %s file(s)...", uris.size))
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
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { val c = r.cryptoObject?.cipher; if (c != null) onOk(c) else onErr(vt("Fingerprint failed")) }
            override fun onAuthenticationError(code: Int, s: CharSequence) { if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) onErr(s.toString()) }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText(vt("Cancel"))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), cb).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun unlockBio(msg: TextView) {
        if (busy) return
        val c = VaultBio.cipherForUnlock(this)
        if (c == null) { msg.text = vt("Fingerprint changed. Use your password, then turn fingerprint on again."); return }
        bioPrompt(vt("Unlock vault"), c, { ac ->
            val mk = VaultBio.unwrap(this, ac)
            if (mk == null) { msg.text = vt("Fingerprint is no longer valid. Use your password.") }
            else {
                busy = true
                exec.execute {
                    var err: String? = null
                    try { VaultSession.start(VaultStore.openWithMasterKey(volume, mk)) } catch (e: Exception) { err = vf("Could not unlock: %s", e.message) } finally { mk.fill(0) }
                    ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render() } }
                }
            }
        }, { msg.text = it })
    }


    private fun offerRecovery() {
        AppPal.dlg(this).kind("perm").setTitle(vt("Protect your recovery options"))
            .setMessage(vt("Create a recovery code now. It can open the vault if you forget your password. Store it away from this phone."))
            .setPositiveButton(vt("Create recovery code")) { _, _ ->
                bg { val sec = (VaultSession.store ?: return@bg).newRecoverySecret(); val code = VaultRecovery.toCode(sec); ui.post { showCode(code, sec) } }
            }
            .setNegativeButton(vt("Not now"), null).show()
    }

    private fun offerBio() {
        AppPal.dlg(this).kind("perm").setTitle(vt("Unlock with fingerprint?"))
            .setMessage(vt("Skip typing the password next time. The password stays the real key: after a reinstall you enter it once and turn fingerprint on again."))
            .setPositiveButton(vt("Turn on")) { _, _ -> enableBio() }
            .setNegativeButton(vt("Not now"), null)
            .setNeutralButton(vt("Don't ask again")) { _, _ -> prefs.bioDeclined = true }.show()
    }

    private fun enableBio() {
        val st = VaultSession.store ?: return
        val c = try { VaultBio.cipherForEnable() } catch (e: Exception) { toast(vf("Fingerprint not available: %s", e.message)); return }
        bioPrompt(vt("Turn on fingerprint"), c, { ac ->
            val mk = st.masterKeyCopy()
            try { VaultBio.finishEnable(this, ac, mk); prefs.bioDeclined = false; toast(vt("Fingerprint is on")) }
            catch (e: Exception) { VaultBio.disable(this); toast(vf("Could not turn it on: %s", e.message)) }
            finally { mk.fill(0) }
        }, { toast(it) })
    }

    // ------------------------------------------------------------------ forgot password / settings

    private fun forgot(msg: TextView) {
        forgotMsg = msg
        val o = arrayOf(vt("Recovery code"), vt("Key file (.lsrk)"), vt("Shares (2 of 3)"))
        AppPal.dlg(this).setTitle(vt("Forgot password"))
            .setMessage(vt("If you set a backup password, cancel and type it in the password field instead."))
            .setItems(o) { _, i -> when (i) { 0 -> forgotCode(msg); 1 -> forgotKeyFile(); else -> forgotShares(msg) } }
            .setNegativeButton(vt("Cancel"), null).show()
    }

    /** Shared by code, key file and shares: open with the 32-byte recovery secret, then force a new password. Wipes [secret]. */
    private fun unlockSecret(secret: ByteArray, msg: TextView) {
        busy = true; msg.text = vt("Unlocking...")
        exec.execute {
            var err: String? = null
            try { VaultSession.start(VaultStore.openWithRecovery(volume, secret)) }
            catch (x: Exception) { err = if (x is VaultAuthException) vt("Wrong recovery secret") else vf("Could not unlock: %s", x.message) }
            finally { secret.fill(0) }
            ui.post { busy = false; if (err != null) msg.text = err else { cur = null; render(); forceNewPassword() } }
        }
    }

    private fun forgotCode(msg: TextView) {
        val e = EditText(this).apply { hint = vt("Recovery code"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        AppPal.dlg(this).setTitle(vt("Recovery code")).setView(e)
            .setPositiveButton(vt("Unlock")) { _, _ ->
                val secret = VaultRecovery.fromCode(e.text.toString())
                if (secret == null) msg.text = vt("That recovery code is not valid. Check the typing.") else unlockSecret(secret, msg)
            }.setNegativeButton(vt("Cancel"), null).show()
    }

    private fun forgotKeyFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_KEYFILE)
    }

    private fun keyFilePicked(uri: android.net.Uri?) {
        val msg = forgotMsg ?: return
        if (uri == null) return
        val bytes = try { contentResolver.openInputStream(uri)!!.use { it.readBytes() } } catch (e: Exception) { msg.text = vt("Could not read the file"); return }
        if (bytes.size > 4096) { msg.text = vt("That is not a recovery key file"); return }
        pwDialog(vt("Key file password"), listOf(vt("File password")), false, true) { v ->
            val w = throttle.waitMs()
            if (w > 0) { msg.text = vf("Too many attempts. Wait %s s", (w + 999) / 1000); v.forEach { it.fill('\u0000') }; return@pwDialog }
            busy = true; msg.text = vt("Opening key file...")
            exec.execute {
                val secret = try { VaultRecovery.fromKeyFile(bytes, v[0]) } finally { v.forEach { it.fill('\u0000') } }
                if (secret == null) { throttle.onFail(); ui.post { busy = false; msg.text = vt("Wrong file password or not a key file") } }
                else ui.post { busy = false; unlockSecret(secret, msg) }
            }
        }
    }

    private fun forgotShares(msg: TextView) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fs = (1..3).map { n -> EditText(this).apply { hint = vf("Share %s", n) + (if (n == 3) " (" + vt("optional") + ")" else ""); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }.also { box.addView(it) } }
        AppPal.dlg(this).setTitle(vt("Enter any 2 shares")).setView(box)
            .setPositiveButton(vt("Unlock")) { _, _ ->
                val shares = fs.map { it.text.toString() }.filter { it.isNotBlank() }.map { VaultRecovery.shareFromCode(it) }
                val ok = shares.filterNotNull()
                val secret = if (shares.size < 2 || ok.size != shares.size) null else VaultRecovery.combine(ok)
                if (secret == null) msg.text = vt("Shares are not valid. Check the typing and use 2 different shares.") else unlockSecret(secret, msg)
            }.setNegativeButton(vt("Cancel"), null).show()
    }

    /** Settings: new recovery secret sealed in a .lsrk file (replaces the current recovery code). */
    private fun makeKeyFile(st: VaultStore) {
        pwDialog(vt("Key file password"), listOf(vt("File password"), vt("Repeat file password")), true, true) { v ->
            bg {
                val sec = st.newRecoverySecret()
                try { pendingRecoverySecret?.fill(0); pendingRecoverySecret = sec.copyOf(); pendingKey = VaultRecovery.toKeyFile(sec, v[0]) }
                finally { sec.fill(0); v.forEach { it.fill('\u0000') } }
                ui.post {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "vault-recovery.lsrk"), REQ_SAVEKEY)
                }
            }
        }
    }

    private fun saveKeyTo(uri: android.net.Uri?) {
        val k = pendingKey ?: return
        pendingKey = null
        if (uri == null) { k.fill(0); pendingRecoverySecret?.fill(0); pendingRecoverySecret = null; return }
        bg {
            val secret = pendingRecoverySecret
            try {
                contentResolver.openOutputStream(uri)!!.use { it.write(k) }
                try {
                    (VaultSession.store ?: throw IllegalStateException("Vault locked before recovery setup finished")).setRecovery(secret ?: throw IllegalStateException("Recovery setup expired"))
                } catch (e: Exception) {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    throw e
                }
                ui.post { toast(vt("Key file saved. Keep it away from this phone, and remember its password.")) }
            } finally {
                k.fill(0); secret?.fill(0)
                if (pendingRecoverySecret === secret) pendingRecoverySecret = null
            }
        }
    }

    /** Settings: new recovery secret split 2-of-3 (replaces the current recovery code). */
    private fun makeShares(st: VaultStore) {
        bg {
            val sec = st.newRecoverySecret()
            val codes = try { VaultRecovery.split(sec, 2, 3).map { VaultRecovery.shareToCode(it) } } catch (e: Throwable) { sec.fill(0); throw e }
            ui.post {
                pendingRecoverySecret?.fill(0); pendingRecoverySecret = sec
                AppPal.dlg(this).setTitle(vt("Your 3 shares"))
                    .setMessage(codes.mapIndexed { i, c -> vt("Share %s").format(i + 1) + ":\n$c" }.joinToString("\n\n") + "\n\n" + vt("Any 2 shares open the vault. One alone reveals nothing. Give them to different people or places, away from this phone. Shown only once."))
                    .setCancelable(false)
                    .setPositiveButton(vt("I saved all 3")) { _, _ ->
                        val secret = pendingRecoverySecret ?: return@setPositiveButton
                        bg { try { st.setRecovery(secret); ui.post { toast(vt("Recovery shares confirmed")) } } finally { secret.fill(0); pendingRecoverySecret = null } }
                    }
                    .setNegativeButton(vt("Cancel")) { _, _ -> pendingRecoverySecret?.fill(0); pendingRecoverySecret = null }
                    .show()
            }
        }
    }

    /** Multi-field password dialog. checkNew: the last two fields are "new" and "repeat" (min 8, must match). ok gets the arrays and must wipe them. */
    private fun pwDialog(title: String, hints: List<String>, checkNew: Boolean, cancelable: Boolean, ok: (List<CharArray>) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fs = hints.map { field(it).also { f -> box.addView(f) } }
        val err = text("", 13f, lm.error); box.addView(err)
        val dlg = AppPal.dlg(this).setTitle(title).setView(box).setCancelable(cancelable).setPositiveButton(vt("OK"), null)
            .apply { if (cancelable) setNegativeButton(vt("Cancel"), null) }.create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = fs.map { chars(it) }
            if (checkNew) {
                val n = v[v.size - 2]; val r = v[v.size - 1]
                if (n.size < 8) { err.text = vt("Use at least 8 characters"); v.forEach { it.fill('\u0000') }; return@setOnClickListener }
                if (!n.contentEquals(r)) { err.text = vt("Passwords do not match"); v.forEach { it.fill('\u0000') }; return@setOnClickListener }
            }
            dlg.dismiss(); ok(v)
        }
    }

    private fun forceNewPassword() {
        val st = VaultSession.store ?: return
        pwDialog(vt("Set a new password"), listOf(vt("New password"), vt("Repeat new password")), true, false) { v ->
            bg { try { st.resetPassword(v[0]); throttle.onSuccess() } finally { v.forEach { it.fill('\u0000') } } }
        }
    }

    private fun settings() {
        val st = VaultSession.store ?: return
        val acts = ArrayList<Pair<String, () -> Unit>>()
        fun add(t: String, f: () -> Unit) { acts.add(t to f) }
        add(vt("Change password")) {
            pwDialog(vt("Change password"), listOf(vt("Current password"), vt("New password"), vt("Repeat new password")), true, true) { v ->
                val w = throttle.waitMs()
                if (w > 0) { toast(vf("Too many attempts. Wait %s s", (w + 999) / 1000)); v.forEach { it.fill('\u0000') } }
                else bg {
                    try { st.changePassword(v[0], v[1]); throttle.onSuccess(); ui.post { toast(vt("Password changed")) } }
                    catch (e: VaultAuthException) { throttle.onFail(); throw Exception(vt("Current password is wrong")) }
                    finally { v.forEach { it.fill('\u0000') } }
                }
            }
        }
        if (bioOk()) add((if (bioOn()) vt("Turn off fingerprint") else vt("Turn on fingerprint"))) { if (bioOn()) { VaultBio.disable(this); toast(vt("Fingerprint is off")) } else enableBio() }
        add(vf("Auto-lock: %s", lockLabel(prefs.autoLockSec))) { autoLockMenu() }
        add((if (st.hasRecovery) vt("New recovery code (replaces the old one)") else vt("Create recovery code"))) {
            bg { val sec = st.newRecoverySecret(); val code = VaultRecovery.toCode(sec); ui.post { showCode(code, sec) } }
        }
        if (st.hasRecovery) add(vt("Remove recovery code")) {
            AppPal.dlg(this).kind("danger").setMessage(vt("Remove the recovery code? Your password still opens the vault.")).setPositiveButton(vt("Remove")) { _, _ -> bg { st.removeRecovery() } }.setNegativeButton(vt("Cancel"), null).show()
        }
        add(vt("Save recovery key file (.lsrk) (replaces the recovery code)")) { makeKeyFile(st) }
        add(vt("Split recovery into 3 shares (replaces the recovery code)")) { makeShares(st) }
        add((if (st.hasBackupPassword) vt("Change backup password") else vt("Set backup password"))) {
            pwDialog(vt("Backup password"), listOf(vt("Backup password"), vt("Repeat backup password")), true, true) { v ->
                bg { try { st.setBackupPassword(v[0]); ui.post { toast(vt("Backup password saved")) } } finally { v.forEach { it.fill('\u0000') } } }
            }
        }
        if (st.hasBackupPassword) add(vt("Remove backup password")) {
            AppPal.dlg(this).kind("danger").setMessage(vt("Remove the backup password?")).setPositiveButton(vt("Remove")) { _, _ -> bg { st.removeBackupPassword() } }.setNegativeButton(vt("Cancel"), null).show()
        }
        AppPal.dlg(this).setTitle(vt("Vault settings")).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun showCode(code: String, secret: ByteArray) {
        pendingRecoverySecret?.fill(0); pendingRecoverySecret = secret
        AppPal.dlg(this).setTitle(vt("Your recovery code"))
            .setMessage("$code\n\n" + vt("Write it down and keep it away from this phone. It is shown only once. Anyone who has it can open your vault."))
            .setCancelable(false)
            .setPositiveButton(vt("I wrote it down")) { _, _ -> checkCode(code, secret) }
            .setNegativeButton(vt("Cancel")) { _, _ -> secret.fill(0); if (pendingRecoverySecret === secret) pendingRecoverySecret = null }
            .show()
    }

    /** Re-enter 3 random groups so a typo while writing it down is caught now, not on the day it is needed. */
    private fun checkCode(code: String, secret: ByteArray) {
        val groups = code.split("-")
        val idx = groups.indices.shuffled().take(3).sorted()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fs = idx.map { n -> EditText(this).apply { hint = "Group ${n + 1}"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }.also { box.addView(it) } }
        val err = text("", 13f, lm.error); box.addView(err)
        val dlg = AppPal.dlg(this).setTitle(vt("Check your copy")).setMessage(vt("Type the groups you wrote down.")).setView(box).setCancelable(false)
            .setPositiveButton(vt("Check"), null).setNegativeButton(vt("Cancel"), null).setNeutralButton(vt("Show again"), null).create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ok = fs.indices.all { fs[it].text.toString().trim().uppercase() == groups[idx[it]] }
            if (ok) {
                dlg.dismiss()
                bg { try { (VaultSession.store ?: throw IllegalStateException("Vault locked")).setRecovery(secret); toast(vt("Recovery code confirmed")) }
                    finally { secret.fill(0); if (pendingRecoverySecret === secret) pendingRecoverySecret = null } }
            } else err.text = vt("That does not match. Compare with what you wrote.")
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dlg.dismiss(); secret.fill(0); if (pendingRecoverySecret === secret) pendingRecoverySecret = null }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dlg.dismiss(); showCode(code, secret) }
    }

    private fun lockLabel(s: Int) = when (s) { 0 -> vt("immediately"); 30 -> vt("30 seconds"); 60 -> vt("1 minute"); 300 -> vt("5 minutes"); -1 -> vt("never until app exit"); else -> "$s s" }

    private fun autoLockMenu() {
        val v = intArrayOf(0, 30, 60, 300, -1)
        AppPal.dlg(this).setTitle(vt("Auto-lock")).setItems(v.map { lockLabel(it) }.toTypedArray()) { _, i -> prefs.autoLockSec = v[i]; VaultSession.autoLockSec = v[i] }.show()
    }

    companion object {
        private const val REQ_ADD = 71
        private const val REQ_KEYFILE = 72
        private const val REQ_SAVEKEY = 73
        fun intent(c: Context): Intent = Intent(c, VaultActivity::class.java)
    }
}
