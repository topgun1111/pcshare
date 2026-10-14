package com.lanshare.app

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.lanshare.app.core.Cfg

/**
 * Clipboard sync (opt-in, Text inbox menu): while any LANShare screen is on screen, text you copy is also sent - silently - to the default device.
 * Android 10+ lets only the app in the foreground read the clipboard, so this cannot run in the background; the receiving side copies it
 * without a notification only when ITS owner turned "Auto-copy received text" on (otherwise it shows up like any other text).
 * Copies flagged as sensitive (password managers), blank / huge texts and texts that just arrived from another device are never sent.
 */
object ClipSync {
    @Volatile private var lastHash = 0
    private var cm: ClipboardManager? = null
    private var lis: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var started = 0
    private var installed = false

    /** Remember [t] as "already handled" (it came from another device, or was just sent). */
    fun mark(t: String) { lastHash = t.hashCode() }

    /** Counts the started LANShare screens: the listener lives while at least one is visible. Called once, from the service. */
    @Synchronized fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { synchronized(ClipSync) { started++ }; refresh(a) }
            override fun onActivityStopped(a: Activity) { synchronized(ClipSync) { if (started > 0) started-- }; refresh(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** (Re)applies the setting: listener on when enabled and a screen is visible, else off. */
    @Synchronized fun refresh(ctx: Context) {
        try { lis?.let { cm?.removePrimaryClipChangedListener(it) } } catch (_: Throwable) {}
        lis = null; cm = null
        if (started <= 0 || !Cfg.clipSync()) return
        val m = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val app = ctx.applicationContext
        val l = ClipboardManager.OnPrimaryClipChangedListener { onChange(app, m) }
        m.addPrimaryClipChangedListener(l)
        cm = m; lis = l
    }

    private fun onChange(ctx: Context, m: ClipboardManager) {
        val c = try { m.primaryClip } catch (_: Throwable) { null } ?: return
        if (c.itemCount == 0) return
        val sensitive = try {
            Build.VERSION.SDK_INT >= 28 && c.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        } catch (_: Throwable) { false }
        if (sensitive) return
        val t = try { c.getItemAt(0).coerceToText(ctx)?.toString() } catch (_: Throwable) { null }
        if (t.isNullOrBlank() || t.length > 100_000) return
        if (t.hashCode() == lastHash) return
        lastHash = t.hashCode()
        val peer = TextSend.target() ?: return
        TextSend.send(t, peer.id, quiet = true) { _, _ -> }
    }
}
