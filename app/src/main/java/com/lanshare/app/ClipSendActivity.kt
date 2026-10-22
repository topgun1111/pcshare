package com.lanshare.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lanshare.app.core.Peer

/**
 * Invisible one-shot sender. Started by
 *  - the Quick Settings tile, the notification "Clipboard" button and the launcher / Direct Share shortcuts (action [TextSend.ACT_SEND]): sends the CLIPBOARD,
 *  - the share sheet (ACTION_SEND text/plain): sends the shared text.
 * Target: the shortcut's device ("to" / EXTRA_SHORTCUT_ID), else the default device, else the only online device, else a short picker.
 * It is an activity (not a service) because Android 10+ lets only the focused app read the clipboard.
 */
class ClipSendActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var shared: String? = null
    private var to: String? = null
    private var started = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        if (i?.action == Intent.ACTION_SEND) shared = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        to = (i?.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.takeIf { it.startsWith("peer:") }?.removePrefix("peer:")
            ?: i?.getStringExtra("to"))?.takeIf { it.isNotEmpty() }
        TextSend.ensureRunning(this)   // process was killed: bring discovery back
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true
        val t = shared?.takeIf { it.isNotBlank() } ?: readClip()
        if (t == null) { say("Clipboard is empty"); finish(); return }
        go(t, 0)
    }

    private fun readClip(): String? = try {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    private fun say(m: String) { Toast.makeText(applicationContext, m, Toast.LENGTH_SHORT).show() }

    /** Waits up to ~8 s for discovery (cold start), then sends / asks. */
    private fun go(t: String, tries: Int) {
        if (isFinishing) return
        val explicit: Peer? = to?.let { TextSend.find(it) }
        val on = TextSend.online()
        val peer = explicit ?: if (to == null) TextSend.target() else null
        when {
            peer != null -> send(t, peer)
            to == null && on.size > 1 -> choose(t, on)
            tries < 20 -> ui.postDelayed({ go(t, tries + 1) }, 400)
            else -> { say(if (to != null) "That device is offline" else "No device found"); finish() }
        }
    }

    private fun send(t: String, p: Peer) {
        TextSend.send(t, p.id) { err, name ->
            say(err ?: "Sent to $name")
            finish()
        }
    }

    private fun choose(t: String, on: List<Peer>) {
        AppPal.dlg(this)
            .setTitle("Send to…")
            .setItems(on.map { it.name }.toTypedArray()) { _, w -> send(t, on[w]) }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }
}
