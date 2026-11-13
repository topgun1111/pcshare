package com.lanshare.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import com.lanshare.app.core.vault.VaultGuard
import com.lanshare.app.core.vault.VaultSession

/**
 * Lockdown, app-wide hooks (lockdown.md Part 2 + 3), installed once from [App]:
 *  - screen off locks the vault no matter which screen is open (not only while VaultActivity lives);
 *  - "background" = no LANShare screen started (a rotation does not count) -> VaultSession.onBackground / onForeground;
 *  - on lock and on every process start the plaintext temp dir cacheDir/vt is wiped;
 *  - every original removed by "lock" is purged from MediaStore so Gallery drops it at once.
 */
object VaultAppHooks {
    private var installed = false
    private var started = 0

    @Synchronized fun install(app: Application) {
        if (installed) return
        installed = true
        wipeCache(app)                                          // leftovers of a killed process
        VaultSession.onLock { wipeCache(app) }
        VaultSession.onStarted = { s -> s.onOriginalRemoved = { f -> purge(app, f.path) } }

        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { VaultSession.onScreenOff() } }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        } catch (_: Throwable) { }

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                val first = synchronized(VaultAppHooks) { started++; started == 1 }
                if (first) VaultSession.onForeground()
            }
            override fun onActivityStopped(a: Activity) {
                val last = synchronized(VaultAppHooks) { if (started > 0) started--; started == 0 }
                if (last && !a.isChangingConfigurations) VaultSession.onBackground()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Deletes the decrypt cache (random-named temp files for Office / WebView viewers). */
    fun wipeCache(ctx: Context) {
        try { java.io.File(ctx.cacheDir, "vt").deleteRecursively() } catch (_: Throwable) { }
    }

    /** The original is already gone from disk; a scan of its old path makes MediaStore delete the stale row. Never logs the path. */
    private fun purge(ctx: Context, path: String) {
        if (VaultGuard.isVaultName(path)) return
        try { MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null) } catch (_: Throwable) { }
    }
}
