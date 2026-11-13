package com.lanshare.app.core.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pw get() = "correct horse 1".toCharArray()

    @Test fun codeRoundTripAndTypo() {
        val s = VaultCrypto.rand(32); val c = VaultRecovery.toCode(s)
        assertArrayEquals(s, VaultRecovery.fromCode(c.lowercase().replace("-", " ")))
        val bad = (if (c[0] == 'A') 'B' else 'A') + c.substring(1)
        assertNull(VaultRecovery.fromCode(bad))
    }
    @Test fun shamir2of3() {
        val s = VaultCrypto.rand(32); val sh = VaultRecovery.split(s, 2, 3)
        for ((a, b) in listOf(0 to 1, 0 to 2, 1 to 2)) assertArrayEquals(s, VaultRecovery.combine(listOf(sh[a], sh[b])))
        assertNull(VaultRecovery.combine(listOf(sh[0])))
        assertNull(VaultRecovery.combine(listOf(byteArrayOf())))
        assertNull(VaultRecovery.combine(listOf(sh[0], sh[0])))
        assertNull(VaultRecovery.combine(listOf(sh[0], sh[1].copyOf().also { it[1] = 3 })))
        assertArrayEquals(sh[1], VaultRecovery.shareFromCode(VaultRecovery.shareToCode(sh[1])))
    }
    @Test fun keyFile() {
        val s = VaultCrypto.rand(32); val f = VaultRecovery.toKeyFile(s, "x1".toCharArray(), 12)
        assertArrayEquals(s, VaultRecovery.fromKeyFile(f, "x1".toCharArray())); assertNull(VaultRecovery.fromKeyFile(f, "x2".toCharArray()))
    }
    @Test fun allSlotsOpenSameVault() {
        val root = tmp.newFolder(); val v = VaultStore.create(root, pw, 10)
        val rec = v.enableRecovery(); v.setBackupPassword("backup pass 2".toCharArray())
        v.changePassword(pw, "new password 3".toCharArray()); v.lock()
        assertTrue(VaultStore.open(root, "new password 3".toCharArray()).also { it.lock() }.let { true })
        assertTrue(VaultStore.open(root, "backup pass 2".toCharArray()).also { it.lock() }.let { true })
        assertTrue(VaultStore.openWithRecovery(root, rec).also { it.lock() }.let { true })
        assertThrows(VaultAuthException::class.java) { VaultStore.open(root, pw) }
        VaultStore.open(root, "new password 3".toCharArray()).also { it.removeBackupPassword(); it.lock() }
        assertThrows(VaultAuthException::class.java) { VaultStore.open(root, "backup pass 2".toCharArray()) }
    }
    @Test fun recoveryReplacementIsTransactional() {
        val root = tmp.newFolder()
        val v = VaultStore.create(root, pw, 10)
        val old = v.enableRecovery()
        val candidate = v.newRecoverySecret() // generating a replacement must not invalidate the old one
        v.lock()
        VaultStore.openWithRecovery(root, old).lock()
        assertThrows(VaultAuthException::class.java) { VaultStore.openWithRecovery(root, candidate) }
        val open = VaultStore.open(root, pw)
        open.setRecovery(candidate)
        open.lock()
        VaultStore.openWithRecovery(root, candidate).lock()
        assertThrows(VaultAuthException::class.java) { VaultStore.openWithRecovery(root, old) }
    }

    @Test fun throttle() {
        var t = 0L; val st = object : VaultThrottle.Store { override var fails = 0; override var lastFail = 0L }
        val th = VaultThrottle(st) { t }; repeat(4) { th.onFail() }; assertEquals(0, th.waitMs())
        th.onFail(); assertEquals(30_000, th.waitMs()); t = 31_000; assertEquals(0, th.waitMs()); th.onSuccess(); assertEquals(0, st.fails)
    }
}
