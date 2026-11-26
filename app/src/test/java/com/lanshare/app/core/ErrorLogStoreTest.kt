package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ErrorLogStoreTest {
    @Test fun rotatesAndBoundsDiskUsage() {
        val dir = kotlin.io.path.createTempDirectory("error-logs").toFile()
        try {
            val store = ErrorLogStore(dir, "main", 100)
            repeat(100) { store.append("entry-$it-" + "x".repeat(18)) }
            val files = dir.listFiles()!!.filter { it.name.endsWith(".log") }
            assertTrue(files.size <= 2)
            assertTrue(files.sumOf { it.length() } <= 200)
            assertTrue(store.recent().contains("entry-99"))
            assertFalse(store.recent().contains("entry-0-"))
            store.append("x".repeat(200))
            assertTrue(files.sumOf { it.length() } <= 200)
        } finally { dir.deleteRecursively() }
    }

    @Test fun bothProcessesAndUtf8SurviveExport() {
        val dir = kotlin.io.path.createTempDirectory("error-logs").toFile()
        try {
            val main = ErrorLogStore(dir, "main")
            val pdf = ErrorLogStore(dir, "pdfbuild")
            main.append("main: fotoğraf")
            pdf.append("pdf: özet")
            main.append("main: second")
            val out = ByteArrayOutputStream()
            main.export(out)
            val exported = out.toString("UTF-8")
            assertTrue(exported.contains("main: fotoğraf"))
            assertTrue(exported.contains("pdf: özet"))
            assertTrue(main.recent().indexOf("main: second") < main.recent().indexOf("main: fotoğraf"))
            assertTrue(main.recent(12).contains("=== main ===").not() || !main.recent(12).contains("�"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun sensitiveValuesAreRedacted() {
        val s = ErrorLogPrivacy.clean("password=secret123; Authorization: Bearer ABC123; https://user:pass@host/path?token=qwerty")
        assertFalse(s.contains("secret123"))
        assertFalse(s.contains("ABC123"))
        assertFalse(s.contains("qwerty"))
        assertFalse(s.contains("user:pass"))
        assertEquals("[private details omitted]", ErrorLogPrivacy.clean("private.jpg", true))
        assertEquals("[private details omitted]", ErrorLogPrivacy.clean("cannot read /storage/.lsv/private.jpg"))
        assertTrue(ErrorLogPrivacy.sensitiveClass("com.lanshare.app.core.vault.VaultStore"))
        assertTrue(ErrorLogPrivacy.clean("disk full").contains("disk full"))
    }

    @Test fun emptyStoreAndUnavailableStorage() {
        val dir = kotlin.io.path.createTempDirectory("error-logs").toFile()
        try {
            val main = ErrorLogStore(dir, "main")
            assertEquals("", main.recent())
            val file = File(dir, "not-a-directory").apply { writeText("x") }
            ErrorLogStore(file, "main").append("ignored")
            assertEquals("x", file.readText())
        } finally { dir.deleteRecursively() }
    }
}
