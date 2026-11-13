package com.lanshare.app.core.vault

import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultSessionTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private fun open(): VaultStore { VaultSession.clock = { now }; val s = VaultStore.create(tmp.newFolder(), "password1".toCharArray(), logN = 10); VaultSession.start(s); return s }
    @After fun reset() { VaultSession.lock(); VaultSession.clock = System::currentTimeMillis; VaultSession.autoLockSec = 30; VaultSession.onStarted = null }

    @Test fun locksOnlyAfterDelay() {
        VaultSession.autoLockSec = 30; open()
        VaultSession.onBackground(); now += 10_000; VaultSession.onForeground()
        assertTrue(VaultSession.unlocked)
        VaultSession.onBackground(); now += 31_000; VaultSession.onForeground()
        assertFalse(VaultSession.unlocked)
    }
    @Test fun immediateAndNever() {
        VaultSession.autoLockSec = 0; open(); VaultSession.onBackground(); assertFalse(VaultSession.unlocked)
        VaultSession.autoLockSec = -1; open(); VaultSession.onBackground(); now += 10_000_000; VaultSession.onForeground(); assertTrue(VaultSession.unlocked)
    }
    @Test fun screenOffLocksAndStartHookRuns() {
        var hooked = false; VaultSession.onStarted = { hooked = true }
        open(); assertTrue(hooked); VaultSession.onScreenOff(); assertFalse(VaultSession.unlocked)
    }
}
