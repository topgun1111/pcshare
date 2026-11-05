package com.lanshare.app

import android.app.Application

/** Process start: registers the screen counter of the clipboard sync before any activity exists (a service would be created too late to see the first one). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isBuildProcess()) return                       // :pdfbuild only renders PDF pages: no clipboard sync, no FileInfo
        com.lanshare.app.core.FileInfo.ctx = applicationContext
        Haptics.init(this)
        try { ClipSync.install(this) } catch (_: Throwable) {}
        // a PDF page-cache build that was running when the app process died continues now (PdfPageCache keeps a small pending list)
        Thread({ try { Thread.sleep(4000); com.lanshare.app.core.PdfPageCache.resumePending(applicationContext) } catch (_: Throwable) { } }, "pdf-resume").also { it.isDaemon = true }.start()
    }

    private fun isBuildProcess(): Boolean = try { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ').endsWith(":pdfbuild") } catch (_: Throwable) { false }
}
