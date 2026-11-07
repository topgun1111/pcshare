package com.lanshare.app

import android.app.Activity
import android.os.Build

/**
 * Lumen (predictive back, session 7): lets an Activity that is opted in with `android:enableOnBackInvokedCallback="true"` take the Back gesture
 * ONLY while it really has something of its own to undo (selection, an open find bar ...). While [wants] is false no callback is registered, so
 * Android 13+ plays its own predictive animation (back to the previous screen / home) and finishes the Activity. Call [refresh] whenever the
 * state behind [wants] changes and [release] in onDestroy. Below API 33 it does nothing: the Activity's `onBackPressed()` override stays the path.
 * NOT compiled in the Lumen environment (owner compile).
 */
class BackGate(private val act: Activity, private val wants: () -> Boolean, private val handle: () -> Unit) {
    private var cb: Any? = null

    fun refresh() {
        if (Build.VERSION.SDK_INT < 33) return
        try { sync(wants()) } catch (_: Throwable) { }
    }

    fun release() {
        if (Build.VERSION.SDK_INT < 33) return
        try { sync(false) } catch (_: Throwable) { }
    }

    @android.annotation.TargetApi(33)
    private fun sync(want: Boolean) {
        val d = act.onBackInvokedDispatcher
        if (want && cb == null) {
            val c = android.window.OnBackInvokedCallback { handle() }
            d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, c)
            cb = c
        } else if (!want && cb != null) {
            d.unregisterOnBackInvokedCallback(cb as android.window.OnBackInvokedCallback)
            cb = null
        }
    }
}
