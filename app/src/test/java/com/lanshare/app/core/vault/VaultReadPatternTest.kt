package com.lanshare.app.core.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.Random

/** Read patterns of the vault viewers: ExoPlayer (VaultDataSource: seek + bounded reads, several readers per item) and PdfRenderer (proxy fd: random offset reads). */
class VaultReadPatternTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = VaultStore.create(tmp.newFolder(), "pw-12345678".toCharArray(), logN = 10)

    @Test fun randomOffsetReadsMatchPlaintext() {
        val s = store(); val d = ByteArray(300_000).also { Random(1).nextBytes(it) }
        val n = s.addStream(ByteArrayInputStream(d), null, "a.pdf").first
        val rnd = Random(2)
        s.open(n.id).use { r ->
            repeat(300) {
                val off = rnd.nextInt(d.size + 1); val len = 1 + rnd.nextInt(100_000)
                r.seek(off.toLong()); val b = ByteArray(len); var got = 0
                while (got < len) { val c = r.read(b, got, len - got); if (c < 0) break; got += c }
                assertEquals(minOf(len, d.size - off), got)
                assertArrayEquals(d.copyOfRange(off, off + got), b.copyOf(got))
            }
        }
    }

    @Test fun twoReadersOfOneItemAreIndependent() {
        val s = store(); val d = ByteArray(150_000).also { Random(3).nextBytes(it) }
        val n = s.addStream(ByteArrayInputStream(d), null, "v.mp4").first
        val a = s.open(n.id); val b = s.open(n.id)
        a.seek(100_000); b.seek(10)
        val x = ByteArray(1000); val y = ByteArray(1000)
        assertEquals(1000, a.read(x)); assertEquals(1000, b.read(y))
        assertArrayEquals(d.copyOfRange(100_000, 101_000), x); assertArrayEquals(d.copyOfRange(10, 1010), y)
        a.close(); assertEquals(1000, b.read(y)); b.close()   // closing one reader must not break the other (own key copy)
    }

    @Test fun readAfterLockFails() {
        val s = store(); val n = s.addStream(ByteArrayInputStream(ByteArray(10)), null, "x").first
        s.lock()
        try { s.open(n.id).close(); fail("open after lock") } catch (_: Exception) {}
    }
}
