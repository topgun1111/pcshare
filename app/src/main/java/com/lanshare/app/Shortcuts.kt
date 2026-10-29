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
            val recent = OfficeShortcutActivity.Recent.list(ctx)
            val key = on.joinToString("|") { it.id + ":" + it.name } + "#" + def + "#" + recent.joinToString("|") { it.dev + ":" + it.path }
            if (key == last) return
            last = key
            val cap = ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx)
            val max = (cap - 2).coerceIn(0, 6)   // two slots stay free for "New Word document" / "New Excel workbook"
            val list = on.sortedByDescending { it.id == def }.take(max).mapIndexed { n, p ->
                ShortcutInfoCompat.Builder(ctx, "peer:" + p.id)
                    .setShortLabel(p.name.take(24).ifBlank { "Device" })
                    .setLongLabel("Send to " + p.name)
                    .setIcon(IconCompat.createWithResource(ctx, (if (BuildConfig.LITE) R.mipmap.ic_launcher_lite else R.mipmap.ic_launcher)))
                    .setIntent(Intent(ctx, ClipSendActivity::class.java).setAction(TextSend.ACT_SEND).putExtra("to", p.id))
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setRank(n)
                    .build()
            }
            // office: new Word / Excel file (always), then the most recently opened office files while there is room (rank after the devices)
            fun office(id: String, short: String, long: String, i: Intent, rank: Int) = ShortcutInfoCompat.Builder(ctx, id)
                .setShortLabel(short.take(24)).setLongLabel(long).setIcon(IconCompat.createWithResource(ctx, (if (BuildConfig.LITE) R.mipmap.ic_launcher_lite else R.mipmap.ic_launcher))).setIntent(i).setRank(rank).build()
            val more = ArrayList<ShortcutInfoCompat>()
            more.add(office("office:new:docx", "New Word doc", "New Word document", Intent(ctx, OfficeShortcutActivity::class.java).setAction(OfficeShortcutActivity.ACT_NEW).putExtra("kind", "docx"), 50))
            more.add(office("office:new:xlsx", "New Excel", "New Excel workbook", Intent(ctx, OfficeShortcutActivity::class.java).setAction(OfficeShortcutActivity.ACT_NEW).putExtra("kind", "xlsx"), 51))
            more.add(office("us:search", "Search", "Search everything", UsActivity.intent(ctx).setAction(Intent.ACTION_VIEW), 52))
            for ((n, r) in recent.withIndex()) more.add(office("office:recent:" + n, r.name, "Open " + r.name,
                Intent(ctx, OfficeShortcutActivity::class.java).setAction(OfficeShortcutActivity.ACT_OPEN).putExtra("dev", r.dev).putExtra("rpath", r.path).putExtra("name", r.name), 60 + n))
            ShortcutManagerCompat.setDynamicShortcuts(ctx, (list + more).take(cap.coerceAtLeast(1)))
        } catch (_: Throwable) { last = "" }   // rate-limited or not supported: try again at the next change
    }
}
