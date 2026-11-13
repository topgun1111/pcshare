package com.lanshare.app.core.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

/** Restore to original location, original-path records, index v1 -> v2 upgrade, orphan healing, integrity check. */
class VaultRestoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pw get() = "parola-şğıİ-123".toCharArray()
    private fun bytes(n: Int, seed: Long = n.toLong()) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun newVault(): Pair<File, VaultStore> { val v = tmp.newFolder(); return v to VaultStore.create(v, pw, logN = 10) }
    private fun reopen(v: File, s: VaultStore): VaultStore { s.lock(); return VaultStore.open(v, pw) }

    @Test fun lockThenRestoreReturnsToOriginalPath() {
        val (_, s) = newVault(); val home = tmp.newFolder("phone")
        val dir = File(home, "Docs/School").apply { mkdirs() }
        val a = File(dir, "a.txt").apply { writeBytes(bytes(100_000, 1)); setLastModified(1_600_000_000_000L) }
        val b = File(dir, "sub/b.bin").apply { parentFile.mkdirs(); writeBytes(bytes(5, 2)) }
        val n = s.lockFolder(dir, null, true)
        assertFalse(dir.exists()); assertEquals(dir.absolutePath, n.orig)
        val r = VaultRestore.run(s, listOf(n.id), File(home, "fallback"))
        assertEquals(2, r.restored); assertEquals(0, r.failed)
        assertArrayEquals(bytes(100_000, 1), a.readBytes()); assertArrayEquals(bytes(5, 2), b.readBytes())
        assertEquals(1_600_000_000_000L, a.lastModified())
        assertTrue("vault is empty again", s.allNodes().isEmpty())
        assertFalse("no temp leftovers", dir.walkTopDown().any { it.name.endsWith(".lsvpart") })
    }

    @Test fun neverOverwritesAnExistingFile() {
        val (_, s) = newVault(); val home = tmp.newFolder("p")
        val f = File(home, "x.txt").apply { writeText("old original") }
        val n = s.lockFile(f, null, false)               // copy: the original stays
        f.writeText("newer file with the same name")
        val r = VaultRestore.run(s, listOf(n.id), File(home, "fb"))
        assertEquals(1, r.restored)
        assertEquals("newer file with the same name", f.readText())
        assertEquals("old original", File(home, "x (2).txt").readText())
    }

    @Test fun missingOriginFolderIsRecreated() {
        val (_, s) = newVault(); val home = tmp.newFolder("p")
        val f = File(home, "gone/deep/y.bin").apply { parentFile.mkdirs(); writeBytes(bytes(10)) }
        val n = s.lockFile(f, null, true); File(home, "gone").deleteRecursively()
        assertEquals(1, VaultRestore.run(s, listOf(n.id), File(home, "fb")).restored)
        assertArrayEquals(bytes(10), f.readBytes())
    }

    @Test fun unknownOriginUsesFallbackWithVaultFolderStructure() {
        val (_, s) = newVault(); val fb = File(tmp.newFolder("p"), "Restored")
        val d = s.mkdir(null, "Trip"); val n = s.addStream(ByteArrayInputStream(bytes(33)), d.id, "pic.jpg").first
        assertEquals("", n.orig)
        val r = VaultRestore.run(s, listOf(d.id), fb)
        assertEquals(1, r.restored); assertArrayEquals(bytes(33), File(fb, "Trip/pic.jpg").readBytes())
    }

    @Test fun hostileOriginsNeverEscapeOrTouchTheVault() {
        val (v, s) = newVault(); val fb = File(tmp.newFolder("p"), "fb")
        for (bad in listOf("relative/path.txt", "/tmp/../etc/x.txt", File(v, ".lsv/d/zz").path)) {
            val n = s.addStream(ByteArrayInputStream(bytes(4)), null, "f.txt", 0, "", bad).first
            val dst = VaultRestore.destOf(s, n, fb)
            assertTrue("$bad -> $dst", dst.path.startsWith(fb.path))
        }
    }

    @Test fun failedItemStaysInVault() {
        val (_, s) = newVault(); val home = tmp.newFolder("p")
        val f = File(home, "z.bin").apply { writeBytes(bytes(50)) }
        val n = s.lockFile(f, null, true)
        val blob = VaultFormat.blobFile(s.root, n.id)
        java.io.RandomAccessFile(blob, "rw").use { it.seek(30); it.write(0x55); it.write(0x66) }   // corrupt the ciphertext
        val r = VaultRestore.run(s, listOf(n.id), File(home, "fb"))
        assertEquals(0, r.restored); assertEquals(1, r.failed)
        assertNotNull("still listed", s.get(n.id)); assertTrue(blob.exists()); assertFalse(f.exists())
        assertFalse(home.walkTopDown().any { it.name.endsWith(".lsvpart") })
    }

    @Test fun originSurvivesReopenAndMtimeChange() {
        val (v, s0) = newVault(); val f = File(tmp.newFolder("p"), "k.txt").apply { writeText("hi") }
        val n = s0.lockFile(f, null, true); s0.setMtime(n.id, 123_000)
        val s = reopen(v, s0)
        assertEquals(f.absolutePath, s.get(n.id)!!.orig); assertEquals(123_000, s.get(n.id)!!.mtime)
        val m = s.rename(n.id, "k2.txt"); assertEquals(f.absolutePath, m.orig)
    }

    // ---- index v1 (before this version) must open unchanged ----
    @Test fun legacyV1IndexAndJournalStillOpen() {
        val (v, s0) = newVault()
        val a = s0.addStream(ByteArrayInputStream(bytes(10)), null, "legacy.bin").first
        val mk = s0.masterKeyCopy(); s0.lock()
        val key = VaultCrypto.hkdf(mk, "lsv-index"); val dir = File(v, ".lsv")
        // rewrite i as a v1 snapshot (old node layout) and add a v1 journal record (op PUT = 1)
        fun v1(n: VaultNode, o: DataOutputStream) {
            o.write(VaultFormat.unhex(n.id)); o.write(ByteArray(16)); o.writeBoolean(n.dir); o.writeLong(n.size); o.writeLong(n.mtime); o.writeUTF(n.name); o.writeUTF(n.mime)
        }
        val bo = ByteArrayOutputStream(); DataOutputStream(bo).also { it.writeInt(1); v1(a, it) }
        val nonce = VaultCrypto.rand(12)
        val aad = ByteBuffer.allocate(9).put(1).putLong(1L).array()
        File(dir, "i").writeBytes(byteArrayOf(1) + ByteBuffer.allocate(8).putLong(1L).array() + nonce + VaultCrypto.seal(key, nonce, aad, bo.toByteArray()))
        File(dir, "i.bak").delete()
        val b = s0.let { VaultNode("b".repeat(32), null, "journaled.bin", false, 0, 1, "") }
        val pb = ByteArrayOutputStream(); DataOutputStream(pb).also { it.writeByte(1); v1(b, it) }
        val jn = VaultCrypto.rand(12); val jaad = ByteBuffer.allocate(9).put('j'.code.toByte()).putLong(2L).array()
        val ct = VaultCrypto.seal(key, jn, jaad, pb.toByteArray())
        File(dir, "j").writeBytes(ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(ct.size); write(jn); writeLong(2L); write(ct) } }.toByteArray())
        val s = VaultStore.open(v, pw)
        assertEquals("legacy.bin", s.get(a.id)!!.name); assertEquals("", s.get(a.id)!!.orig)
        assertEquals("journaled.bin", s.get(b.id)!!.name)
        s.setOrig(a.id, "/x/y"); val s2 = reopen(v, s)         // now writes v2 and reads it back
        assertEquals("/x/y", s2.get(a.id)!!.orig); assertEquals("journaled.bin", s2.get(b.id)!!.name)
    }

    // ---- no blob is ever deleted just because the index does not know it ----
    @Test fun orphanBlobIsRecoveredNotDeleted() {
        val (v, s0) = newVault(); val data = bytes(70_000, 9)
        val n = s0.addStream(ByteArrayInputStream(data), null, "photo.jpg").first
        val keep = s0.addStream(ByteArrayInputStream(bytes(3)), null, "keep.txt").first
        val dir = File(v, ".lsv")
        s0.lock(); File(dir, "i").delete(); File(dir, "i.bak").delete(); File(dir, "j").delete()   // index wiped by a storage fault
        val s = VaultStore.open(v, pw)
        val rec = s.list(null).single { it.name == VaultStore.RECOVERED }
        val files = s.list(rec.id); assertEquals(2, files.size)
        val got = files.single { it.size == 70_000L }
        assertArrayEquals(data, s.open(got.id).use { it.readBytes() })
        assertNotNull(keep); assertNotNull(n)
    }

    @Test fun brokenBlobGoesToQuarantineNotTrash() {
        val (v, s0) = newVault(); val n = s0.addStream(ByteArrayInputStream(bytes(200_000)), null, "t.bin").first
        val blob = VaultFormat.blobFile(s0.root, n.id)
        s0.delete(n.id); // index entry gone, blob deleted ...
        blob.parentFile.mkdirs(); blob.writeBytes(bytes(40))   // ... a torn leftover with the same name
        val s = reopen(v, s0)
        assertTrue(s.list(null).none { it.name == VaultStore.RECOVERED })
        assertTrue(File(File(v, ".lsv"), "q").listFiles()!!.any { it.name == n.id })
    }

    @Test fun integrityCheckFindsDamageAndMissing() {
        val (_, s) = newVault()
        val a = s.addStream(ByteArrayInputStream(bytes(150_000, 1)), null, "a").first
        val b = s.addStream(ByteArrayInputStream(bytes(150_000, 2)), null, "b").first
        val c = s.addStream(ByteArrayInputStream(bytes(10, 3)), null, "c").first
        assertTrue(s.checkAll().ok)
        java.io.RandomAccessFile(VaultFormat.blobFile(s.root, b.id), "rw").use { it.seek(70_000); it.write(1); it.write(2); it.write(3) }
        VaultFormat.blobFile(s.root, c.id).delete()
        val r = s.checkAll()
        assertEquals(3, r.files); assertEquals(listOf(b.id), r.damaged.map { it.id }); assertEquals(listOf(c.id), r.missing.map { it.id })
        assertNotNull(a)
    }

    @Test fun relockSkipsOnlyIdenticalContent() {
        val (_, s) = newVault(); val dir = File(tmp.newFolder("p"), "D").apply { mkdirs() }
        File(dir, "f.bin").writeBytes(bytes(1000, 1))
        s.lockFolder(dir, null, false)
        File(dir, "f.bin").writeBytes(bytes(1000, 2))        // same name, same size, different bytes
        s.lockFolder(dir, null, true)
        assertFalse("original removed only after it is safe in the vault", File(dir, "f.bin").exists())
        val d = s.list(null).single { it.name == "D" }
        val all = s.list(d.id).map { s.open(it.id).use { r -> r.readBytes() }.contentHashCode() }.toSet()
        assertEquals(setOf(bytes(1000, 1).contentHashCode(), bytes(1000, 2).contentHashCode()), all)
    }

    @Test fun createRefusesToOverwriteADamagedVault() {
        val (v, s) = newVault(); s.addStream(ByteArrayInputStream(bytes(10)), null, "x")
        s.lock(); File(v, ".lsv/h0").delete(); File(v, ".lsv/h1").delete()
        assertFalse(VaultStore.exists(v))
        try { VaultStore.create(v, pw, logN = 10); fail("must refuse") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("damaged")) }
    }
}
