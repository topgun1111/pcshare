package com.lanshare.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lanshare.app.core.vault.VaultSession
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Lockdown vault mode part 4: plain-text editor for ONE vault item (extra "id", <= 1 MB, valid UTF-8 only). Text is decrypted into memory, edited in
 * an EditText with copy / cut / paste / share / suggestions / personalised keyboard learning switched off, and saved BACK INTO THE VAULT (new item
 * first, then the old one is deleted and the new one takes its name: a failure never loses the original). No temp file, no recent-files entry,
 * FLAG_SECURE, closes when the vault locks (unsaved text is dropped on lock, by design). Not exported.
 */
class VaultTextActivity : Activity() {
    private lateinit var et: EditText
    private var id = ""
    private var base = ""
    private var saving = false
    private var lock: (() -> Unit)? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        VaultMode.secure(this)
        val st = VaultSession.store?.takeIf { it.isOpen }
        val node = intent.getStringExtra("id")?.let { st?.get(it) }
        if (st == null || node == null || node.dir || node.size > VaultViewActivity.MAX_TEXT) { finish(); return }
        id = node.id
        lock = VaultMode.bindLock(this)
        val txt = try {
            val raw = st.open(node.id).use { it.readBytes() }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Throwable) { Toast.makeText(this, vt("This file is not UTF-8 text"), Toast.LENGTH_LONG).show(); finish(); return }
        base = txt
        val pal = AppPal.pal(this)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pal.bg); setPadding(0, (24 * d).toInt(), 0, 0) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0) }
        val ttl = TextView(this).apply { text = vt("Edit text"); textSize = 16f; setTextColor(pal.fg) }
        val save = Button(this).apply { text = vt("Save"); setOnClickListener { save(false) } }
        bar.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(save)
        et = EditText(this).apply {
            setText(txt); typeface = Typeface.MONOSPACE; textSize = 14f; setTextColor(pal.fg); setBackgroundColor(Color.TRANSPARENT)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START; setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isLongClickable = false; setTextIsSelectable(false)
            val none = object : ActionMode.Callback {
                override fun onCreateActionMode(m: ActionMode?, menu: Menu?) = false
                override fun onPrepareActionMode(m: ActionMode?, menu: Menu?) = false
                override fun onActionItemClicked(m: ActionMode?, i: MenuItem?) = false
                override fun onDestroyActionMode(m: ActionMode?) {}
            }
            customSelectionActionModeCallback = none; customInsertionActionModeCallback = none
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2)); root.addView(et, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun changed() = ::et.isInitialized && et.text.toString() != base

    /** [thenClose] = leave after a successful save. */
    private fun save(thenClose: Boolean) {
        if (saving) return
        if (!changed()) { if (thenClose) finish() else Toast.makeText(this, vt("No changes to save"), Toast.LENGTH_SHORT).show(); return }
        val bytes = et.text.toString().toByteArray(Charsets.UTF_8)
        saving = true
        Thread {
            val ok = try {
                val st = VaultSession.store?.takeIf { it.isOpen } ?: throw IllegalStateException("locked")
                val old = st.get(id) ?: throw IllegalStateException("gone")
                val n = st.addStream(ByteArrayInputStream(bytes), old.parent, old.name, System.currentTimeMillis(), old.mime).first
                st.delete(old.id)
                id = try { st.rename(n.id, old.name).id } catch (_: Exception) { n.id }
                VaultMode.dirty = true; true
            } catch (_: Throwable) { false }
            runOnUiThread {
                saving = false
                if (ok) { base = String(bytes, Charsets.UTF_8); if (thenClose) finish() else Toast.makeText(this, vt("Saved"), Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, vt("Could not save"), Toast.LENGTH_LONG).show()
            }
        }.also { it.isDaemon = true }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!changed()) { super.onBackPressed(); return }
        AppPal.dlg(this).setTitle(vt("Unsaved changes")).setMessage(vt("Save before closing?"))
            .setPositiveButton(vt("Save")) { _, _ -> save(true) }
            .setNegativeButton(vt("Discard")) { _, _ -> base = et.text.toString(); finish() }
            .setNeutralButton(vt("Cancel"), null).show()
    }

    override fun onResume() { super.onResume(); if (!VaultSession.unlocked) finish() }
    override fun onDestroy() { lock?.let { VaultSession.removeOnLock(it) }; lock = null; if (::et.isInitialized) et.setText(""); super.onDestroy() }
}
