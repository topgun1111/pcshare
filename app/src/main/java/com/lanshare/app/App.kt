package com.lanshare.app

import android.app.Application

/** Process start: registers the screen counter of the clipboard sync before any activity exists (a service would be created too late to see the first one). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.lanshare.app.core.FileInfo.ctx = applicationContext
        try { ClipSync.install(this) } catch (_: Throwable) {}
    }
}
