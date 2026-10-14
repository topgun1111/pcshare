package com.lanshare.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Direct Share: every online device is published as a share-sheet target for plain text ("Share -> Pixel 7" in any app, no picker, no LANShare screen).
 * The share-target declaration is res/xml/shortcuts.xml (target class = [ClipSendActivity]); the default device is ranked first.
 * The same shortcuts show on a long-press of the launcher icon and send the clipboard to that device.
 */
object Shortcuts {
    const val CATEGORY = "com.lanshare.app.category.SEND_TEXT"
    private var last = ""

    /** Re-publishes the shortcuts when the set of online devices (or the default) changed. Cheap when nothing changed; call from any thread. */
    @Synchronized fun sync(ctx: Context) {
        try {
            val on = TextSend.online()
            val def = com.lanshare.app.core.Cfg.defTarget()
            val key = on.joinToString("|") { it.id + ":" + it.name } + "#" + def
            if (key == last) return
            last = key
            val max = minOf(ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx), 6)
            val list = on.sortedByDescending { it.id == def }.take(max).mapIndexed { n, p ->
                ShortcutInfoCompat.Builder(ctx, "peer:" + p.id)
                    .setShortLabel(p.name.take(24).ifBlank { "Device" })
                    .setLongLabel("Send to " + p.name)
                    .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                    .setIntent(Intent(ctx, ClipSendActivity::class.java).setAction(TextSend.ACT_SEND).putExtra("to", p.id))
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setRank(n)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        } catch (_: Throwable) { last = "" }   // rate-limited or not supported: try again at the next change
    }
}
