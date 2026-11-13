package com.lanshare.app.core.vault

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Random

class VaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pw get() = "pässwörd-şğıİ-123".toCharArray()
    private fun create(vol: File = tmp.newFolder()) = vol to VaultStore.create(vol, pw, logN = 10)
    private fun bytes(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }
    private fun put(s: VaultStore, data: ByteArray, parent: String? = null, name: String = "f.bin") = s.addStream(ByteArrayInputStream(data), parent, name).first
    private fun read(s: VaultStore, id: String) = s.open(id).use { it.readBytes() }

    @Test fun roundTripSizes() {
        val (_, s) = create(); val c = VaultFormat.CHUNK
        for (n in intArrayOf(0, 1, c - 1, c, c + 1, 2 * c, 2 * c + 1, 300_000)) { val d = bytes(n); assertArrayEquals("size $n", d, read(s, put(s, d).id)) }
    }

    @Test fun largeFile2GB() {   // sparse-free: streamed through generator, constant memory
        val (_, s) = create(); val total = 2L shl 30; val md = MessageDigest.getInstance("SHA-256")
        val gen = object : java.io.InputStream() {
            var left = total; val blk = ByteArray(1 shl 20).also { Random(7).nextBytes(it) }
            override fun read() = throw UnsupportedOperationException()
            override fun read(b: ByteArray, o: Int, l: Int): Int { if (left == 0L) return -1; val c = minOf(l.toLong(), left, blk.size.toLong()).toInt(); System.arraycopy(blk, 0, b, o, c); left -= c; return c }
        }
        val (n, sha) = s.addStream(gen, null, "big")
        assertEquals(total, n.size); assertTrue(s.verify(n.id, total, sha))
        s.open(n.id).use { r -> r.seek(total - 5); assertEquals(5, r.read(ByteArray(10))) }
    }

    @Test fun seekRandomAccess() {
        val (_, s) = create(); val d = bytes(200_000); val n = put(s, d)
        s.open(n.id).use { r -> for (p in intArrayOf(0, 65535, 65536, 131072, 199_999)) { r.seek(p.toLong()); assertEquals(d[p], r.read().toByte()) }; assertEquals(d.size.toLong(), r.length) }
    }

    @Test fun tamperAnyByteFails() {
        val (vol, s) = create(); val d = bytes(70_000); val n = put(s, d); val f = VaultFormat.blobFile(s.root, n.id)
        val len = f.length().toInt()
        for (pos in intArrayOf(0, 5, 20, 21, 100, 65_556, 65_560, len - 1)) {
            RandomAccessFile(f, "rw").use { it.seek(pos.toLong()); val b = it.read(); it.seek(pos.toLong()); it.write(b xor 1) }
            val ok = try { read(s, n.id); true } catch (e: Exception) { false }
            assertFalse("pos $pos", ok)
            RandomAccessFile(f, "rw").use { it.seek(pos.toLong()); val b = it.read(); it.seek(pos.toLong()); it.write(b xor 1) }
        }
        assertArrayEquals(d, read(s, n.id))
    }

    @Test fun truncationAtChunkBoundaryFails() {
        val (_, s) = create(); val n = put(s, bytes(3 * VaultFormat.CHUNK + 10)); val f = VaultFormat.blobFile(s.root, n.id)
        RandomAccessFile(f, "rw").use { it.setLength(VaultFormat.BLOB_HDR + 2L * (VaultFormat.CHUNK + 16)) }
        try { read(s, n.id); fail() } catch (e: VaultCorruptException) {}
    }

    @Test fun wrongPassword() {
        val (vol, s) = create(); s.lock()
        try { VaultStore.open(vol, "nope-nope".toCharArray()); fail() } catch (e: VaultAuthException) {}
    }

    @Test fun reinstallSimulation_passwordReopen() {
        val (vol, s) = create(); val d = bytes(100_000); val dir = s.mkdir(null, "İş Çğ"); val n = put(s, d, dir.id, "şeker.pdf"); s.lock()
        val s2 = VaultStore.open(vol, pw)            // new process: only .lsv + password
        assertEquals("şeker.pdf", s2.get(n.id)!!.name); assertEquals("İş Çğ", s2.get(dir.id)!!.name); assertArrayEquals(d, read(s2, n.id))
    }

    @Test fun passwordChangeAndRecovery() {
        val (vol, s) = create(); val n = put(s, bytes(1000)); val rec = s.enableRecovery()
        s.changePassword(pw, "new-password-1".toCharArray()); s.lock()
        try { VaultStore.open(vol, pw); fail() } catch (e: VaultAuthException) {}
        assertEquals(1000, read(VaultStore.open(vol, "new-password-1".toCharArray()), n.id).size)
        assertEquals(1000, read(VaultStore.openWithRecovery(vol, rec), n.id).size)
    }

    @Test fun crashRecoveryJournalAndOrphans() {
        val (vol, s) = create(); val ids = (1..10).map { put(s, bytes(it * 100), null, "f$it").id }   // < CHECKPOINT: only journal, no snapshot update
        // simulate kill: no lock()/close(); open again
        val s2 = VaultStore.open(vol, pw); assertEquals(10, s2.list(null).size); ids.forEach { assertTrue(read(s2, it).isNotEmpty()) }
        // torn journal tail
        File(s2.root, "j").let { f -> val b = f.readBytes(); f.writeBytes(b + byteArrayOf(0, 0, 1, 0, 9, 9, 9)) }
        assertEquals(10, VaultStore.open(vol, pw).list(null).size)
        // orphan blob (crash before journal record) is swept
        val orphan = VaultFormat.blobFile(s2.root, "ab".repeat(16)).also { it.parentFile.mkdirs(); it.writeBytes(ByteArray(40)) }
        VaultStore.open(vol, pw); assertFalse(orphan.exists())
    }

    @Test fun crashBetweenSnapshotSteps() {
        val (vol, s) = create(); repeat(70) { put(s, bytes(10), null, "g$it") }   // forces a checkpoint at 64
        val i = File(s.root, "i"); File(s.root, "i.bak").let { it.delete(); i.renameTo(it) }   // i missing, i.bak present
        assertEquals(70, VaultStore.open(vol, pw).list(null).size)
        File(s.root, "i").writeBytes(ByteArray(50))                                            // corrupt i, good bak
        assertEquals(70, VaultStore.open(vol, pw).list(null).size)
    }

    @Test fun headerFallbackH1() {
        val (vol, s) = create(); s.lock(); File(s.root, "h0").writeBytes(ByteArray(512)); VaultStore.open(vol, pw)
    }

    @Test fun lockFolderRemovesOriginalsAndResumes() {
        val (_, s) = create(); val src = tmp.newFolder("photos"); File(src, "a.jpg").writeBytes(bytes(5000)); File(src, "sub").mkdir(); File(src, "sub/b.txt").writeBytes(bytes(70_000))
        val removed = ArrayList<File>(); s.onOriginalRemoved = { removed.add(it) }
        val top = s.lockFolder(src, null)
        assertFalse(src.exists()); assertEquals(4, removed.size)
        val sub = s.list(top.id).first { it.dir }; assertEquals(70_000L, s.list(sub.id)[0].size); assertEquals(75_000L, s.totalSize())
    }

    @Test fun treeOps() {
        val (_, s) = create(); val a = s.mkdir(null, "a"); val b = s.mkdir(a.id, "b"); val f = put(s, bytes(10), b.id)
        assertEquals("f (2).bin", put(s, bytes(11), b.id).name)
        try { s.move(a.id, b.id); fail() } catch (e: IllegalArgumentException) {}
        s.move(f.id, null); assertEquals(null, s.get(f.id)!!.parent)
        val blob = VaultFormat.blobFile(s.root, f.id); s.delete(a.id); assertEquals(1, s.list(null).size); assertTrue(blob.exists())
        s.delete(f.id); assertFalse(blob.exists())
    }

    @Test fun noPlaintextNamesOnDisk() {
        val (_, s) = create(); put(s, "SECRET-CONTENT".toByteArray(), null, "tax-return-2026.pdf"); s.lock()
        s.root.walkTopDown().filter { it.isFile }.forEach { val t = it.readBytes().toString(Charsets.ISO_8859_1); assertFalse(t.contains("SECRET")); assertFalse(t.contains("tax-return")); assertFalse(t.contains("LANSHARE", true)) }
        assertTrue(File(s.root, ".nomedia").exists())
    }
}
