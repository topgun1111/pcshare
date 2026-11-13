package com.lanshare.app.core.vault

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VaultGuardTest {
    @Test fun names() {
        for (p in listOf("/s/.lsv", "/s/.LSV/d/ab/x", "/s/./.lsv", "/s/a/../.lsv/h0", "/s/%2elsv/h0",
            "/s/%252elsv", "\\s\\.lsv\\h0", "/s/.lsv./h0", "/s/.lsv /h0", "/s/\uFF0Elsv/h0"))
            assertTrue(p, VaultGuard.isVaultName(p))
        for (p in listOf("/s/lsv", "/s/a.lsv", "/s/.lsvx", "/s/x/.lsv2/y", "", "/"))
            assertFalse(p, VaultGuard.isVaultName(p))
    }
    @Test fun symlinkAndCache() {
        val t = File.createTempFile("vg", "").also { it.delete(); it.mkdirs() }
        File(t, ".lsv").mkdirs()
        val link = File(t, "ln")
        try { java.nio.file.Files.createSymbolicLink(link.toPath(), File(t, ".lsv").toPath()); assertTrue(VaultGuard.isVaultPath(link.path)) } catch (_: UnsupportedOperationException) {}
        VaultGuard.cacheRoot = "/data/cache"
        assertTrue(VaultGuard.isVaultName("/data/cache/vt/ab"))
        assertFalse(VaultGuard.isVaultName("/data/cache/other"))
        assertFalse(VaultGuard.visible(".LSV")); assertTrue(VaultGuard.visible("Docs"))
        VaultGuard.cacheRoot = ""; t.deleteRecursively()
    }
}
