package com.lanshare.app

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat

/**
 * Launcher long-press shortcuts (and Direct Share targets) are disabled. [sync] is kept so existing callers still compile; it only clears
 * whatever dynamic shortcuts an earlier version published, once per process.
 */
object Shortcuts {
    const val CATEGORY = "com.lanshare.app.category.SEND_TEXT"
    private var cleared = false

    @Synchronized fun sync(ctx: Context) {
        if (cleared) return
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(ctx)
            cleared = true
        } catch (_: Throwable) { }   // try again at the next call
    }
}
