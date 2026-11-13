package com.lanshare.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.IntentCompat
import com.lanshare.app.core.vault.VaultGuard
import com.lanshare.app.core.vault.VaultIntents
import com.lanshare.app.core.vault.VaultNode
import com.lanshare.app.core.vault.VaultSession
import com.lanshare.app.core.vault.VaultStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Adds things from OTHER apps to the vault, in every way Android offers:
 *  - the share sheet ("Add to Vault": ACTION_SEND / ACTION_SEND_MULTIPLE, files, many files, shared text),
 *  - the system file picker for files (ACTION_OPEN_DOCUMENT, many at once; Drive, Downloads, USB, any provider),
 *  - the system folder picker (ACTION_OPEN_DOCUMENT_TREE: whole folder trees, merged by name, safe to repeat).
 * The picker modes are started by the file manager (Vault > "Add files / Add a folder from other apps"), extras `pick` = files | folder, `dir` = vault folder path.
 * Every file is encrypted, re-read (every tag + SHA-256) and only then counts as added. "Move" deletes an original only after that check
 * (and only where the other app allows deleting). The original path is recorded when the provider tells it (restore to the old place).
 * Needs the vault unlocked: the unlock / create screen opens first. FLAG_SECURE; nothing is written in plain text anywhere.
 */
class VaultImportActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val lm by lazy { Lumen.of(this) }
    private val d by lazy { resources.displayMetrics.density }
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button
    private val volume: File = Environment.getExternalStorageDirectory()
    private val mode: String? by lazy { if (intent?.action == null) intent?.getStringExtra("pick") else null }      // null = share sheet (a share intent always has an action)
    private var started = false
    private var after: (() -> Unit)? = null
    @Volatile private var cancel = false
    @Volatile private var running = false

    private fun dp(v: Int) = Math.round(v * d)
    override fun attachBaseContext(b: Context) { super.attachBaseContext(b); UiScale.attach(this, b) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        VaultMode.secure(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (mode == null && VaultIntents.refuse(intent)) { finish(); return }          // never take in something that points into the vault itself
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(lm.canvas); gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(72), dp(24), dp(24)) }
        root.addView(text(vt("Add to vault"), 26f, lm.ink, true))
        status = text(vt("Preparing..."), 15f, lm.inkMut).apply { setPadding(0, dp(12), 0, dp(12)) }; root.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }; root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        detail = text("", 13f, lm.inkMut).apply { setPadding(0, dp(12), 0, dp(12)) }
        root.addView(ScrollView(this).apply { addView(detail) }, LinearLayout.LayoutParams(-1, 0, 1f))
        action = btn(vt("Cancel")) { if (running) { cancel = true; action.isEnabled = false } else finish() }; root.addView(action)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        ensureUnlocked { go() }       // here, not in onCreate: the app-wide auto-lock check (VaultAppHooks) has already run
    }

    override fun onDestroy() { cancel = true; exec.shutdown(); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (running) cancel = true; super.onBackPressed() }

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun btn(t: String, fn: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(lm.onPrimary)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(lm.primary) }
        setPadding(dp(18), 0, dp(18), 0); setOnClickListener { fn() }
    }

    // ------------------------------------------------------------------ unlock + pickers

    /** Runs [then] with the vault open: opens the unlock / create screen first when it is locked (auto-lock "immediately" also locks while a picker is up). */
    @Suppress("DEPRECATION")
    private fun ensureUnlocked(then: () -> Unit) {
        if (VaultSession.unlocked) { then(); return }
        after = then
        status.text = vt("Unlock the vault to continue")
        startActivityForResult(VaultActivity.intent(this), REQ_UNLOCK)
    }

    @Suppress("DEPRECATION")
    private fun go() {
        when (mode) {
            "files" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_FILES)
            "folder" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION), REQ_TREE)
            else -> shared()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_UNLOCK -> { val a = after; after = null; if (VaultSession.unlocked && a != null) a() else finish() }
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                if (res == RESULT_OK && data != null) { data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris.add(it) } }; if (uris.isEmpty()) data.data?.let { uris.add(it) } }
                if (uris.isEmpty()) finish() else askMove { mv -> ensureUnlocked { start(uris.map { Item(it, false) }, null, null, mv) } }
            }
            REQ_TREE -> {
                val u = if (res == RESULT_OK) data?.data else null
                if (u == null) finish() else askMove { mv -> ensureUnlocked { start(listOf(Item(u, true)), null, null, mv) } }
            }
        }
    }

    private fun askMove(go: (Boolean) -> Unit) {
        AppPal.dlg(this).setTitle(vt("Add to vault"))
            .setMessage(vt("Copy keeps the originals. Move deletes each original only after its encrypted copy was checked."))
            .setCancelable(false)
            .setPositiveButton(vt("Move")) { _, _ -> go(true) }
            .setNeutralButton(vt("Copy")) { _, _ -> go(false) }
            .setNegativeButton(vt("Cancel")) { _, _ -> finish() }.show()
    }

    private fun shared() {
        val i = intent ?: run { finish(); return }
        val uris = LinkedHashSet<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        else IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
        i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris.add(it) } }
        val txt = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (uris.isEmpty() && txt == null) { finish(); return }
        start(uris.map { Item(it, false) }, txt, i.getStringExtra(Intent.EXTRA_SUBJECT), false)   // copy only: the sending app keeps its file
    }

    // ------------------------------------------------------------------ the import

    private class Item(val uri: Uri, val tree: Boolean)
    private class Kid(val id: String, val name: String, val mime: String, val size: Long, val mtime: Long)
    private class Meta(val name: String, val size: Long, val mtime: Long, val mime: String)
    private class Locked : Exception()

    private class Tally { var ok = 0; var same = 0; var fail = 0; var kept = 0; var locked = false; val problems = ArrayList<String>() }

    private fun start(items: List<Item>, sharedText: String?, subject: String?, move: Boolean) {
        running = true; cancel = false
        status.text = vt("Adding...")
        action.text = vt("Cancel"); action.isEnabled = true
        val t = Tally()
        exec.execute {
            try {
                val parent = destination()
                for (item in items) {
                    if (cancel) break
                    try { if (item.tree) addTree(t, item.uri, parent, move) else addOne(t, item.uri, parent, meta(item.uri), origOf(item.uri), move) }
                    catch (e: Locked) { throw e }
                    catch (e: Exception) { t.fail++; if (t.problems.size < 8) t.problems.add(meta(item.uri).name + " (" + (e.message ?: e.javaClass.simpleName) + ")") }
                    tick(t)
                }
                if (sharedText != null && !cancel) addText(t, sharedText, subject, parent)
            } catch (e: Locked) { t.locked = true }
            catch (e: Exception) { t.fail++; t.problems.add(e.message ?: e.javaClass.simpleName) }
            ui.post { finishUi(t, move) }
        }
    }

    private fun tick(t: Tally) = ui.post { status.text = vf("Added: %s", t.ok + t.same) + (if (t.fail > 0) "  " + vf("Problems: %s", t.fail) else "") }

    private fun store(): VaultStore = VaultSession.store?.takeIf { it.isOpen } ?: throw Locked()

    /** Share sheet -> folder "Imported" (root); pickers -> the vault folder that was open ("/" = root). */
    private fun destination(): String? {
        val st = store()
        if (mode == null) return synchronized(st) { st.list(null).firstOrNull { it.dir && it.name == IMPORTED }?.id ?: st.mkdir(null, IMPORTED).id }
        var p: String? = null
        for (seg in (intent?.getStringExtra("dir") ?: "/").split('/').filter { it.isNotEmpty() }) {
            val next = synchronized(st) { st.list(p).firstOrNull { it.dir && it.name == seg }?.id } ?: return null   // folder gone meanwhile: vault root
            p = next
        }
        return p
    }

    private fun safeName(n: String?): String {
        val c = (n ?: "").replace(Regex("[/\\\\\\u0000]"), "_").trim()
        return if (c.isEmpty() || c == "." || c == "..") "file" else c.take(200)
    }

    private fun meta(uri: Uri): Meta {
        var name: String? = null; var size = -1L; var mt = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { if (!c.isNull(it)) name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!c.isNull(it)) size = c.getLong(it) }
                    c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { if (!c.isNull(it)) mt = c.getLong(it) }
                }
            }
        } catch (_: Exception) { }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        return Meta(safeName(name), size, if (mt > 0) mt else System.currentTimeMillis(), try { contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" })
    }

    /** The path the provider says this document lives at (primary / SD storage, raw Downloads); "" when it cannot be known. */
    private fun origOf(uri: Uri): String = try {
        when {
            uri.scheme == "file" -> uri.path ?: ""
            !DocumentsContract.isDocumentUri(this, uri) -> ""
            uri.authority == "com.android.externalstorage.documents" -> {
                val id = DocumentsContract.getDocumentId(uri); val i = id.indexOf(':')
                if (i < 0) "" else { val vol = id.substring(0, i); File(if (vol == "primary") volume.path else "/storage/$vol", id.substring(i + 1)).path }
            }
            uri.authority == "com.android.providers.downloads.documents" -> DocumentsContract.getDocumentId(uri).let { if (it.startsWith("raw:")) it.substring(4) else "" }
            else -> ""
        }.takeIf { it.isNotEmpty() && !VaultGuard.isVaultName(it) } ?: ""
    } catch (_: Exception) { "" }

    private fun sha(i: InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
        while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) }
        return md.digest()
    }

    private fun sameAsSource(st: VaultStore, n: VaultNode, uri: Uri): Boolean = try {
        val a = (contentResolver.openInputStream(uri) ?: return false).use { sha(it) }
        MessageDigest.isEqual(a, st.open(n.id).use { sha(it) })
    } catch (e: IllegalStateException) { throw Locked() } catch (_: Exception) { false }

    private fun addOne(t: Tally, uri: Uri, parent: String?, m: Meta, orig: String, move: Boolean) {
        val st = store()
        try {
            val have = if (m.size >= 0) synchronized(st) { st.list(parent).firstOrNull { !it.dir && it.name == m.name && it.size == m.size } } else null
            if (have != null && sameAsSource(st, have, uri)) { t.same++; dropOriginal(t, uri, move); return }          // already there (repeat of an earlier import)
            val (n, sha) = (contentResolver.openInputStream(uri) ?: throw IOException("cannot open")).use { st.addStream(it, parent, m.name, m.mtime, m.mime, orig) }
            if ((m.size > 0 && n.size != m.size) || !st.verify(n.id, n.size, sha)) { st.delete(n.id); throw IOException("verification failed") }
            t.ok++
            dropOriginal(t, uri, move)
        } catch (e: IllegalStateException) { throw Locked() }        // the vault locked meanwhile (auto-lock): stop, nothing half-done stays
    }

    /** Only reached after the vault copy was verified. Not every provider lets us delete: then the original stays and is counted. */
    private fun dropOriginal(t: Tally, uri: Uri, move: Boolean) {
        if (!move) return
        try { if (!DocumentsContract.deleteDocument(contentResolver, uri)) t.kept++ } catch (_: Exception) { t.kept++ }
    }

    private fun addText(t: Tally, txt: String, subject: String?, parent: String?) {
        val st = store()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())
        val name = safeName((subject?.takeIf { it.isNotBlank() }?.take(60) ?: "Shared text") + " " + stamp) + ".txt"
        try {
            val bytes = txt.toByteArray(Charsets.UTF_8)
            val (n, sha) = st.addStream(ByteArrayInputStream(bytes), parent, name, System.currentTimeMillis(), "text/plain")
            if (n.size != bytes.size.toLong() || !st.verify(n.id, n.size, sha)) { st.delete(n.id); throw IOException("verification failed") }
            t.ok++
        } catch (e: IllegalStateException) { throw Locked() }
    }

    private fun children(tree: Uri, docId: String): List<Kid> {
        val out = ArrayList<Kid>()
        contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Kid(c.getString(0), safeName(c.getString(1)), c.getString(2) ?: "", if (c.isNull(3)) -1L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4)))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun ensureDir(parent: String?, name: String, orig: String): VaultNode {
        val st = store()
        return try { synchronized(st) {
            val e = st.list(parent).firstOrNull { it.dir && it.name == name }
            if (e == null) st.mkdir(parent, name, orig) else { if (e.orig.isEmpty() && orig.isNotEmpty()) st.setOrig(e.id, orig); e }
        } } catch (e: IllegalStateException) { throw Locked() }
    }

    private fun addTree(t: Tally, tree: Uri, parent: String?, move: Boolean) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val dir = ensureDir(parent, meta(rootUri).name, origOf(rootUri))
        val complete = walk(t, tree, rootId, dir.id, move)
        if (move && complete) emptyDirDelete(tree, rootId, rootUri)
    }

    /** @return true when every entry of this folder is in the vault. */
    private fun walk(t: Tally, tree: Uri, docId: String, vparent: String, move: Boolean): Boolean {
        var complete = true
        for (k in children(tree, docId)) {
            if (cancel) return false
            val u = DocumentsContract.buildDocumentUriUsingTree(tree, k.id)
            try {
                if (k.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val dn = ensureDir(vparent, k.name, origOf(u))
                    if (walk(t, tree, k.id, dn.id, move)) { if (move) emptyDirDelete(tree, k.id, u) } else complete = false
                } else addOne(t, u, vparent, Meta(k.name, k.size, if (k.mtime > 0) k.mtime else System.currentTimeMillis(), k.mime), origOf(u), move)
            } catch (e: Locked) { throw e }
            catch (e: Exception) { complete = false; t.fail++; if (t.problems.size < 8) t.problems.add(k.name + " (" + (e.message ?: e.javaClass.simpleName) + ")") }
            tick(t)
        }
        return complete
    }

    /** Moves leave an emptied source folder behind: remove it only when nothing is left in it (a folder delete is recursive on most providers). */
    private fun emptyDirDelete(tree: Uri, docId: String, uri: Uri) {
        try { if (children(tree, docId).isEmpty()) DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) { }
    }

    private fun finishUi(t: Tally, move: Boolean) {
        running = false
        VaultMode.dirty = true                                  // the file manager re-lists the vault when it comes back
        bar.isIndeterminate = false; bar.progress = 100
        val sb = StringBuilder()
        sb.append(vf("%s item(s) added to the vault", t.ok))
        if (t.same > 0) sb.append("\n").append(vf("%s already in the vault", t.same))
        if (t.fail > 0) sb.append("\n").append(vf("%s could not be added", t.fail))
        if (move && t.kept > 0) sb.append("\n").append(vf("%s original(s) could not be deleted by the other app", t.kept))
        if (t.locked) sb.append("\n").append(vt("Vault locked. The rest was not added."))
        else if (cancel) sb.append("\n").append(vt("Stopped."))
        status.text = if (t.fail == 0 && !t.locked && !cancel) vt("Done") else vt("Finished with problems")
        detail.text = sb.toString() + (if (t.problems.isEmpty()) "" else "\n\n" + t.problems.joinToString("\n"))
        action.text = vt("Done"); action.isEnabled = true
    }

    companion object {
        private const val REQ_UNLOCK = 81
        private const val REQ_FILES = 82
        private const val REQ_TREE = 83
        /** Folder (vault root) that receives what other apps share. Fixed name: it is data, not UI text. */
        const val IMPORTED = "Imported"

        /** File manager > Vault > "Add files / Add a folder from other apps". [dir] = the vault folder that is open. */
        fun pick(c: Context, folder: Boolean, dir: String): Intent =
            Intent(c, VaultImportActivity::class.java).putExtra("pick", if (folder) "folder" else "files").putExtra("dir", dir)
    }
}
