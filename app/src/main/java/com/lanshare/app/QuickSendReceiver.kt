package com.lanshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.lanshare.app.core.Cfg

/** The inline "Send text" box of the LANShare notification: whatever is typed there goes to the default device without opening the app. */
class QuickSendReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        if (i.action == ACT_CYCLE) { cycle(app); return }
        val t = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY)?.toString()?.trim()
        LanShareService.refresh()   // re-post the notification right away: that clears the "sending" state of the input box
        if (t.isNullOrEmpty()) return
        val peer = TextSend.target()
        if (peer == null) { Toast.makeText(app, "Tap \"To:\" in the notification to pick a device", Toast.LENGTH_LONG).show(); return }
        TextSend.send(t, peer.id) { err, name ->   // the foreground service keeps the process alive while this runs
            Toast.makeText(app, err ?: "Sent to $name", Toast.LENGTH_SHORT).show()
            LanShareService.refresh()
        }
    }

    /** Notification "To:" button: default device -> next online device -> ... -> ask every time -> first again. */
    private fun cycle(app: Context) {
        val ids = ArrayList<String>(); val names = ArrayList<String>()
        TextSend.online().forEach { ids.add(it.id); names.add(it.name) }
        if (ids.isEmpty()) { Toast.makeText(app, "No device found yet", Toast.LENGTH_SHORT).show(); return }
        ids.add(""); names.add("ask each time")
        val next = (ids.indexOf(Cfg.defTarget()) + 1) % ids.size   // unknown / offline default -> index -1 -> first device
        Cfg.setDefTarget(ids[next])
        Toast.makeText(app, "Text goes to: " + names[next], Toast.LENGTH_SHORT).show()
        LanShareService.refresh()
        Thread { Shortcuts.sync(app) }.also { it.isDaemon = true }.start()
    }

    companion object { const val KEY = "text"; const val ACT_CYCLE = "com.lanshare.app.CYCLE_TARGET" }
}
