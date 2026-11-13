package com.lanshare.app.core.vault

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Vault mode part 5: Turkish coverage, no-leak source rules, plaintext cache path rules. Pure JVM (sources are read as text; Gradle runs tests in the module dir). */
class VaultModeHardeningTest {
    private val kt = File("src/main/java/com/lanshare/app")
    private fun text(n: String) = File(kt, n).readText()
    @After fun reset() { VaultGuard.cacheRoot = "" }

    @Test fun everyVaultStringHasTurkish() {
        val tr = text("VaultTr.kt")
        val lit = Regex("""\bv[tf]\("((?:[^"\\]|\\.)*)"""")
        val missing = sortedSetOf<String>()
        kt.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "VaultTr.kt" }.forEach { f ->
            lit.findAll(f.readText()).forEach { m -> if (!tr.contains("\"" + m.groupValues[1] + "\" to")) missing.add(f.name + ": " + m.groupValues[1]) }
        }
        assertTrue("no Turkish entry for: $missing", missing.isEmpty())
    }

    @Test fun vaultCacheNamesStayInsideVt() {
        VaultGuard.cacheRoot = "/data/user/0/app/cache"
        assertTrue(VaultGuard.isVaultPath("/data/user/0/app/cache/vt/ofc-1234abcd/d.docx"))
        assertTrue(VaultGuard.isVaultPath("/data/user/0/app/cache/vt/edit-1.jpg"))
        assertFalse(VaultGuard.isVaultPath("/data/user/0/app/cache/vtfoo/x"))
    }

    @Test fun officeViewerWritesNothingElsewhere() {
        val o = text("VaultOffice.kt")
        for (bad in listOf("PrintLog", "OfficeExact", "Recent.add", "office-undo", "FileProvider", "getExternalStorage", "Environment."))
            assertFalse("VaultOffice must not use $bad", o.contains(bad))
        assertTrue(o.contains("VaultMode.secure(this)"))
        assertTrue(o.contains("deleteRecursively"))
    }

    @Test fun textEditorHasNoClipboardOrTempFile() {
        val t = text("VaultText.kt")
        for (bad in listOf("ClipboardManager", "File(", "createTempFile", "Intent.ACTION_SEND", "cacheDir", "filesDir"))
            assertFalse("VaultText must not use $bad", t.contains(bad))
        assertTrue(t.contains("IME_FLAG_NO_PERSONALIZED_LEARNING") && t.contains("customSelectionActionModeCallback"))
    }

    @Test fun vaultRowsNeverUseDiskThumbnailCaches() {
        val nl = text("NativeList.kt")
        val i = nl.indexOf("if (r.dev == \"vault\")"); assertTrue(i > 0)
        val branch = nl.substring(i, nl.indexOf("else if (r.dev != \"local\"", i))
        for (bad in listOf("RemoteThumbs", "Thumbs.make", "VideoThumbs")) assertFalse(branch.contains(bad))
        assertTrue(branch.contains("VaultThumbs.make"))
    }

    @Test fun webSideDoesNotPersistVaultFolders() {
        val ui = File("src/main/assets/ui.html").readText()
        assertTrue(ui.contains("if(S.dev==='vault'){const k=pfKey();delete VPF[k]"))          // view prefs stay in memory
        assertTrue(ui.contains("Vault folders cannot be added to the list"))                    // quick folders are plain text
    }
}
