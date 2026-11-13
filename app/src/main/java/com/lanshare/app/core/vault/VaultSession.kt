package com.lanshare.app.core.vault

/** Holds the unlocked [VaultStore] in memory only. UI layer calls [touch]/[onBackground]/[onScreenOff]; [onLock] listeners close viewers + wipe cacheDir/vt. */
object VaultSession {
    @Volatile var store: VaultStore? = null; private set
    /** 0 = immediately, else seconds; -1 = never until app exit. Default 30. */
    @Volatile var autoLockSec = 30
    private var bgAt = 0L
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    var clock: () -> Long = System::currentTimeMillis

    fun onLock(l: () -> Unit) { listeners.add(l) }
    val unlocked get() = store?.isOpen == true
    @Synchronized fun start(s: VaultStore) { store?.lock(); store = s; bgAt = 0 }
    @Synchronized fun lock() { val s = store; store = null; try { s?.lock() } finally { listeners.forEach { try { it() } catch (_: Exception) {} } } }
    fun onBackground() { bgAt = clock(); if (autoLockSec == 0) lock() }
    /** Call from onStart/onResume: locks if the background time exceeded the delay. */
    fun onForeground() { if (unlocked && bgAt != 0L && autoLockSec >= 0 && clock() - bgAt > autoLockSec * 1000L) lock(); bgAt = 0 }
    fun onScreenOff() { lock() }
}
