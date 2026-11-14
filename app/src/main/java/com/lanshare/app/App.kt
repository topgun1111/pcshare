package com.lanshare.app

import android.app.Application
import android.app.Activity
import android.os.Bundle

/** Process start: registers the screen counter of the clipboard sync before any activity exists (a service would be created too late to see the first one). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isBuildProcess()) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { try { LumenNativeTheme.darkBase(activity) } catch (_: Throwable) { }; try { LumenNativeTheme.early(activity) } catch (_: Throwable) { } }   // window colour before the first frame (no light flash in dark themes)
            override fun onActivityStarted(activity: Activity) { }
            override fun onActivityResumed(activity: Activity) {
                // Post until each Activity has finished its first content/layout pass.
                activity.window.decorView.post { try { LumenNativeTheme.apply(activity) } catch (_: Throwable) { } }
            }
            override fun onActivityPaused(activity: Activity) { }
            override fun onActivityStopped(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
            override fun onActivityDestroyed(activity: Activity) { }
        })                       // :pdfbuild only renders PDF pages: no clipboard sync, no FileInfo
        com.lanshare.app.core.FileInfo.ctx = applicationContext
        com.lanshare.app.core.vault.VaultGuard.cacheRoot = cacheDir.path // initialize before any exported activity / intent guard can run
        Haptics.init(this)
        try { ClipSync.install(this) } catch (_: Throwable) {}
        try { VaultAppHooks.install(this) } catch (_: Throwable) {}   // Lockdown: app-wide auto-lock, cache sweep, MediaStore purge
        // a PDF page-cache build that was running when the app process died continues now (PdfPageCache keeps a small pending list)
        Thread({ try { Thread.sleep(4000); com.lanshare.app.core.PdfPageCache.resumePending(applicationContext) } catch (_: Throwable) { } }, "pdf-resume").also { it.isDaemon = true }.start()
    }

    private fun isBuildProcess(): Boolean = try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ').endsWith(":pdfbuild") } catch (_: Throwable) { false }
}
