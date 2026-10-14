package com.lanshare.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile "Send clipboard": one tap from the notification shade sends the clipboard to the default device (see [ClipSendActivity]). */
class SendClipTile : TileService() {
    override fun onStartListening() { refresh() }

    private fun refresh() {
        val t = qsTile ?: return
        t.label = "Send clipboard"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = TextSend.defaultName() ?: "Pick a device"
        t.state = Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, ClipSendActivity::class.java).setAction(TextSend.ACT_SEND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
