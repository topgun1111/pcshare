package com.lanshare.app.core.vault

import android.content.Context

/** App-private vault UI state (resets on reinstall by design): attempt throttle, auto-lock delay, "do not offer fingerprint again". */
class VaultPrefs(c: Context) : VaultThrottle.Store {
    private val p = c.applicationContext.getSharedPreferences("vault_ui", Context.MODE_PRIVATE)
    override var fails: Int
        get() = p.getInt("fails", 0)
        set(v) { p.edit().putInt("fails", v).apply() }
    override var lastFail: Long
        get() = p.getLong("lastFail", 0L)
        set(v) { p.edit().putLong("lastFail", v).apply() }
    /** 0 = immediately, seconds, -1 = never until app exit. */
    var autoLockSec: Int
        get() = p.getInt("autoLock", 30)
        set(v) { p.edit().putInt("autoLock", v).apply() }
    var bioDeclined: Boolean
        get() = p.getBoolean("bioDeclined", false)
        set(v) { p.edit().putBoolean("bioDeclined", v).apply() }
}
