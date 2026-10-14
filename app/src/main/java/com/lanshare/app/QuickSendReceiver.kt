package com.lanshare.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput

/** The inline "Send text" box of the LANShare notification: whatever is typed there goes to the default device without opening the app. */
class QuickSendReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val app = c.applicationContext
        val t = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY)?.toString()?.trim()
        LanShareService.refresh()   // re-post the notification right away: that clears the "sending" state of the input box
        if (t.isNullOrEmpty()) return
        val peer = TextSend.target()
        if (peer == null) { Toast.makeText(app, "Pick a default device in Text inbox first", Toast.LENGTH_LONG).show(); return }
        TextSend.send(t, peer.id) { err, name ->   // the foreground service keeps the process alive while this runs
            Toast.makeText(app, err ?: "Sent to $name", Toast.LENGTH_SHORT).show()
            LanShareService.refresh()
        }
    }

    companion object { const val KEY = "text" }
}
