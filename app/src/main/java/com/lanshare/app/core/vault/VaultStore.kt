package com.lanshare.app.core.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * Encrypted vault in "<volume>/.lsv". Self-describing: any install can open it with the password (KEK = scrypt(password); the Keystore is never involved).
 * MK (random, never changes) is wrapped under the KEK, so a password change only rewrites the header. Single vault per volume.
 * Thread-safe (coarse lock). Pure JVM.
 */
class VaultStore private constructor(val root: File, private var mk: ByteArray?, private var header: VaultHeader) {
    private var kData: ByteArray? = VaultCrypto.hkdf(mk!!, "lsv-data")
    private var index: VaultIndex? = VaultIndex(root, VaultCrypto.hkdf(mk!!, "lsv-index")).also { it.load() }

    /** Called after an original file was removed by [lockFile] (UI layer: MediaScanner / MediaStore purge). */
    var onOriginalRemoved: ((File) -> Unit)? = null

    companion object {
        const val SMALL_WIPE = 16L shl 20
        /** Root folder that receives blobs found without an index entry. */
        const val RECOVERED = "Recovered files"

        fun exists(volumeRoot: File) = File(File(volumeRoot, VaultFormat.DIR), "h0").exists() || File(File(volumeRoot, VaultFormat.DIR), "h1").exists()

        /** Creates a new vault in [volumeRoot]/.lsv. [logN] = scrypt cost (default: calibrated). Refuses to overwrite. */
        @Synchronized fun create(volumeRoot: File, password: CharArray, logN: Int = VaultCrypto.calibrate()): VaultStore {
            val dir = File(volumeRoot, VaultFormat.DIR)
            if (exists(volumeRoot)) throw IOException("vault exists")
            if (File(dir, "i").exists() || File(dir, "i.bak").exists() || (File(dir, "d").walkTopDown().any { it.isFile }))
                throw IOException("a damaged vault is still in this folder (header lost): not overwriting it")   // new MK would orphan every file
            File(dir, "d").mkdirs(); File(dir, ".nomedia").createNewFile()
            val mk = VaultCrypto.rand(32); val salt = VaultCrypto.rand(32); val wn = VaultCrypto.rand(12)
            val h = VaultHeader(logN, 8, 1, 0, salt, wn, ByteArray(VaultFormat.WRAP_LEN), VaultCrypto.rand(12), VaultCrypto.rand(VaultFormat.WRAP_LEN))
            val kek = VaultCrypto.kek(password.copyOf(), salt, logN, 8, 1)   // kek() wipes its input: the caller still needs the password to open the new vault
            h.wrapped = VaultCrypto.seal(kek, wn, h.aad(), mk); VaultCrypto.wipe(kek)
            writeHeader(dir, h)
            return VaultStore(dir, mk, h).also { it.index!!.close() }
        }

        @Throws(VaultAuthException::class, VaultCorruptException::class)
        fun open(volumeRoot: File, password: CharArray): VaultStore {
            val dir = File(volumeRoot, VaultFormat.DIR)
            val pw = password.copyOf(); var last: Exception = VaultCorruptException("no header")
            try {
                for (n in listOf("h0", "h1")) {          // h1 is written first: after a crash during a password change either one may be current
                    val f = File(dir, n); if (!f.exists()) continue
                    val h = try { VaultHeader.decode(f.readBytes()) } catch (e: VaultCorruptException) { last = e; continue }
                    val kek = VaultCrypto.kek(pw.copyOf(), h.salt, h.logN, h.r, h.p)
                    try { return VaultStore(dir, VaultCrypto.open(kek, h.wrapNonce, h.aad(), h.wrapped), h) }
                    catch (e: GeneralSecurityException) { last = VaultAuthException() } finally { VaultCrypto.wipe(kek) }
                    if (h.flags and VaultFormat.F_BACKUP != 0) {      // second (backup) password slot
                        val k2 = VaultCrypto.kek(pw.copyOf(), h.bSalt, h.logN, h.r, h.p)
                        try { return VaultStore(dir, VaultCrypto.open(k2, h.bNonce, h.aad(), h.bWrapped), h) }
                        catch (e: GeneralSecurityException) { } finally { VaultCrypto.wipe(k2) }
                    }
                }
            } finally { pw.fill('\u0000'); password.fill('\u0000') }
            throw last
        }

        /** Opens with the recovery secret (32 random bytes shown to the user once as 24 words by Part 2). */
        fun openWithRecovery(volumeRoot: File, secret: ByteArray): VaultStore {
            val dir = File(volumeRoot, VaultFormat.DIR); var last: Exception = VaultCorruptException("no recovery key in vault")
            for (n in listOf("h0", "h1")) {
                val f = File(dir, n); if (!f.exists()) continue
                val h = try { VaultHeader.decode(f.readBytes()) } catch (e: VaultCorruptException) { last = e; continue }
                if (h.flags and VaultFormat.F_RECOVERY == 0) continue
                val kek = VaultCrypto.hkdf(secret, "lsv-rec")
                try { return VaultStore(dir, VaultCrypto.open(kek, h.recNonce, h.aad(), h.recWrapped), h) }
                catch (e: GeneralSecurityException) { last = VaultAuthException("wrong recovery key") } finally { VaultCrypto.wipe(kek) }
            }
            throw last
        }

        /** Opens with a master key already unwrapped by the biometric layer (verified by decrypting the index). */
        fun openWithMasterKey(volumeRoot: File, mk: ByteArray): VaultStore {
            val dir = File(volumeRoot, VaultFormat.DIR)
            for (n in listOf("h0", "h1")) {
                val f = File(dir, n); if (!f.exists()) continue
                val h = try { VaultHeader.decode(f.readBytes()) } catch (e: VaultCorruptException) { continue }
                return VaultStore(dir, mk.copyOf(), h)
            }
            throw VaultCorruptException("no header")
        }

        /** h1 first, then h0 (each fsync'd): a crash leaves at least one complete header. */
        private fun writeHeader(dir: File, h: VaultHeader) {
            val b = h.encode()
            for (n in listOf("h1", "h0")) FileOutputStream(File(dir, n)).use { it.write(b); it.fd.sync() }
        }

        private fun secureDelete(f: File) {
            try { if (f.length() in 1..SMALL_WIPE) java.io.RandomAccessFile(f, "rw").use { r -> val b = ByteArray(8192); var left = r.length(); while (left > 0) { java.security.SecureRandom().nextBytes(b); val c = minOf(left, b.size.toLong()).toInt(); r.write(b, 0, c); left -= c }; r.fd.sync() } } catch (_: IOException) {}
            f.delete()
        }
    }

    val isOpen get() = mk != null
    private fun idx() = index ?: throw IllegalStateException("vault locked")
    private fun fileKey(id: ByteArray) = VaultCrypto.hkdf(kData ?: throw IllegalStateException("vault locked"), byteArrayOf('f'.code.toByte()) + id)

    private fun isSymbolicLink(f: File): Boolean {
        return try {
            val parent = f.parentFile ?: return false
            val candidate = File(parent.canonicalFile, f.name)
            candidate.canonicalPath != candidate.absolutePath
        } catch (_: Exception) { true }
    }


    init { heal() }

    /** Zeroises all keys; the store cannot be used afterwards (re-open with the password). */
    @Synchronized fun lock() {
        try { index?.close() } finally {
            VaultCrypto.wipe(mk); VaultCrypto.wipe(kData); mk = null; kData = null; index = null
        }
    }

    // ---------- credentials ----------
    @Synchronized fun changePassword(old: CharArray, new: CharArray) {
        val h = header
        val kOld = VaultCrypto.kek(old, h.salt, h.logN, h.r, h.p)
        try {
            val verifiedMk = VaultCrypto.open(kOld, h.wrapNonce, h.aad(), h.wrapped)
            try { if (!MessageDigest.isEqual(verifiedMk, mk!!)) throw VaultCorruptException("vault header changed") }
            finally { VaultCrypto.wipe(verifiedMk) }
        } catch (e: GeneralSecurityException) { throw VaultAuthException() } finally { VaultCrypto.wipe(kOld) }
        // salt + params stay (they are part of the AAD of the recovery wrap, which therefore stays valid); only the password-derived KEK changes
        val nh = h.copyWith(wn = VaultCrypto.rand(12), w = ByteArray(0))
        val kek = VaultCrypto.kek(new, h.salt, h.logN, h.r, h.p)
        nh.wrapped = VaultCrypto.seal(kek, nh.wrapNonce, nh.aad(), mk!!); VaultCrypto.wipe(kek)
        writeHeader(root, nh); header = nh
    }

    /** After a recovery / backup unlock (old password unknown): re-wraps MK under a new main password. */
    @Synchronized fun resetPassword(new: CharArray) {
        val h = header; val nh = h.copyWith(wn = VaultCrypto.rand(12), w = ByteArray(0))
        val kek = VaultCrypto.kek(new, h.salt, h.logN, h.r, h.p)
        nh.wrapped = VaultCrypto.seal(kek, nh.wrapNonce, nh.aad(), mk!!); VaultCrypto.wipe(kek)
        writeHeader(root, nh); header = nh
    }

    /** Generate a candidate secret without changing the vault. Commit it only after the user has safely stored it. */
    fun newRecoverySecret(): ByteArray = VaultCrypto.rand(32)

    /** Creates (or replaces) the recovery wrap for a previously generated 32-byte secret. */
    @Synchronized fun setRecovery(secret: ByteArray) {
        require(secret.size == 32) { "recovery secret must be 32 bytes" }
        val h = header; val kek = VaultCrypto.hkdf(secret, "lsv-rec")
        val nh = h.copyWith(flags = h.flags or VaultFormat.F_RECOVERY, rn = VaultCrypto.rand(12), rw = ByteArray(0))
        try { nh.recWrapped = VaultCrypto.seal(kek, nh.recNonce, nh.aad(), mk!!) } finally { VaultCrypto.wipe(kek) }
        writeHeader(root, nh); header = nh
    }

    /** Convenience API: creates and immediately commits a recovery secret. UI should prefer newRecoverySecret + setRecovery. */
    @Synchronized fun enableRecovery(): ByteArray = newRecoverySecret().also { setRecovery(it) }

    /** Sets / replaces the BACKUP password (independent second password, e.g. kept by a trusted person or in a password manager). */
    @Synchronized fun setBackupPassword(pw: CharArray) {
        val h = header; val salt = VaultCrypto.rand(32); val kek = VaultCrypto.kek(pw, salt, h.logN, h.r, h.p)
        val nh = h.copyWith(flags = h.flags or VaultFormat.F_BACKUP, bs = salt, bn = VaultCrypto.rand(12), bw = ByteArray(0))
        nh.bWrapped = VaultCrypto.seal(kek, nh.bNonce, nh.aad(), mk!!); VaultCrypto.wipe(kek)
        writeHeader(root, nh); header = nh
    }
    @Synchronized fun removeBackupPassword() { val h = header; val nh = h.copyWith(flags = h.flags and VaultFormat.F_BACKUP.inv()); writeHeader(root, nh); header = nh }
    @Synchronized fun removeRecovery() { val h = header; val nh = h.copyWith(flags = h.flags and VaultFormat.F_RECOVERY.inv()); writeHeader(root, nh); header = nh }
    val hasRecovery get() = header.flags and VaultFormat.F_RECOVERY != 0
    val hasBackupPassword get() = header.flags and VaultFormat.F_BACKUP != 0
    /** Master key copy for the biometric layer only (caller must wipe). */
    @Synchronized fun masterKeyCopy(): ByteArray = mk!!.copyOf()

    // ---------- tree ----------
    @Synchronized fun get(id: String): VaultNode? = idx()[id]
    @Synchronized fun list(parent: String?): List<VaultNode> = idx().all.filter { it.parent == parent }.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    @Synchronized fun totalSize(): Long = idx().all.sumOf { if (it.dir) 0L else it.size }

    /** Snapshot of every node (file manager view: search, walk, folder counts). */
    @Synchronized fun allNodes(): List<VaultNode> = idx().all.toList()

    /** Changes the stored modification time (ms) of one node; index only. */
    @Synchronized fun setMtime(id: String, ms: Long) {
        val n = idx()[id] ?: return
        idx().commit(listOf(VaultIndex.Op.Put(VaultNode(n.id, n.parent, n.name, n.dir, n.size, ms, n.mime, n.orig))))
    }

    private fun uniqueName(parent: String?, name: String, except: String? = null): String {
        val taken = idx().all.filter { it.parent == parent && it.id != except }.map { it.name }.toHashSet()
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var i = 2; while (true) { val c = name.substring(0, dot) + " ($i)" + name.substring(dot); if (c !in taken) return c; i++ }
    }

    /** Records where an item came from (restore target). Index only. */
    @Synchronized fun setOrig(id: String, orig: String) {
        val n = idx()[id] ?: return
        if (n.orig != orig) idx().commit(listOf(VaultIndex.Op.Put(n.copy(orig = orig))))
    }

    @Synchronized fun mkdir(parent: String?, name: String, orig: String = ""): VaultNode {
        parent?.let { require(idx()[it]?.dir == true) { "parent is not a folder" } }
        val n = VaultNode(VaultFormat.hex(VaultCrypto.rand(16)), parent, uniqueName(parent, name), true, 0, System.currentTimeMillis(), "", orig)
        idx().commit(listOf(VaultIndex.Op.Put(n))); return n
    }

    @Synchronized fun rename(id: String, name: String): VaultNode {
        val n = idx()[id] ?: throw IOException("no such item"); val r = n.copy(name = uniqueName(n.parent, name, id))
        idx().commit(listOf(VaultIndex.Op.Put(r))); return r
    }

    /** Index-only move; refuses to move a folder into itself. */
    @Synchronized fun move(id: String, newParent: String?): VaultNode {
        val n = idx()[id] ?: throw IOException("no such item")
        var p = newParent; while (p != null) { require(p != id) { "cannot move into itself" }; p = idx()[p]?.parent }
        newParent?.let { require(idx()[it]?.dir == true) { "target is not a folder" } }
        val r = n.copy(parent = newParent, name = uniqueName(newParent, n.name, id))
        idx().commit(listOf(VaultIndex.Op.Put(r))); return r
    }

    @Synchronized fun delete(id: String) {
        val ix = idx(); val del = ArrayList<VaultNode>(); val q = ArrayDeque<String>().apply { add(id) }
        ix[id] ?: return
        while (q.isNotEmpty()) { val c = q.removeFirst(); ix[c]?.let { del.add(it); if (it.dir) ix.all.filter { k -> k.parent == c }.forEach { k -> q.add(k.id) } } }
        ix.commit(del.map { VaultIndex.Op.Del(it.id) })      // index first: a crash leaves only orphan blobs (swept by gc)
        del.filter { !it.dir }.forEach { VaultFormat.blobFile(root, it.id).delete() }
    }

    // ---------- data ----------
    /** Encrypts [input] into a new vault file. Returns the node and the SHA-256 of the plaintext as written. */
    @Synchronized fun addStream(input: InputStream, parent: String?, name: String, mtime: Long = System.currentTimeMillis(), mime: String = "", orig: String = ""): Pair<VaultNode, ByteArray> {
        parent?.let { require(idx()[it]?.dir == true) { "parent is not a folder" } }
        val id = VaultCrypto.rand(16); val hex = VaultFormat.hex(id); val bf = VaultFormat.blobFile(root, hex)
        bf.parentFile!!.mkdirs()
        val key = fileKey(id); val md = MessageDigest.getInstance("SHA-256")
        try {
            val w = VaultWriter(FileOutputStream(bf), id, key)
            val buf = ByteArray(64 * 1024)
            while (true) { val r = input.read(buf); if (r < 0) break; w.write(buf, 0, r); md.update(buf, 0, r) }
            w.close()
            val n = VaultNode(hex, parent, uniqueName(parent, name), false, w.total, mtime, mime, orig)
            idx().commit(listOf(VaultIndex.Op.Put(n)))
            return n to md.digest()
        } catch (e: Throwable) { bf.delete(); throw e } finally { VaultCrypto.wipe(key) }
    }

    /** Seekable decrypting stream (video seek, PDF paging). Caller closes. Tamper -> VaultCorruptException on read. */
    @Synchronized fun open(id: String): VaultReader {
        val n = idx()[id]; if (n == null || n.dir) throw IOException("no such file")
        val raw = VaultFormat.unhex(id); val key = fileKey(raw)
        return try { VaultReader(VaultFormat.blobFile(root, id), key, raw) } catch (e: Throwable) { VaultCrypto.wipe(key); throw e }
    }

    @Synchronized fun export(id: String, dst: File) {
        val n = get(id) ?: throw IOException("no such file")
        FileOutputStream(dst).use { o -> open(id).use { it.copyTo(o) }; o.fd.sync() }
        if (n.mtime > 0) dst.setLastModified(n.mtime)
    }

    /** Re-reads the blob, checks every GCM tag and compares length + SHA-256 with what was written. */
    @Synchronized fun verify(id: String, size: Long, sha256: ByteArray): Boolean = try {
        val md = MessageDigest.getInstance("SHA-256"); var total = 0L
        open(id).use { r -> val b = ByteArray(64 * 1024); while (true) { val c = r.read(b); if (c < 0) break; md.update(b, 0, c); total += c } }
        total == size && MessageDigest.isEqual(md.digest(), sha256)
    } catch (e: Exception) { false }

    /**
     * Encrypts [src] into the vault; with [removeOriginal]: verify (tags + hash) -> single-pass overwrite (<=16 MiB, best effort on flash) -> delete -> [onOriginalRemoved].
     * If verification fails the vault copy is dropped and the original is left untouched.
     */
    fun lockFile(src: File, parent: String?, removeOriginal: Boolean = true): VaultNode {
        if (isSymbolicLink(src)) throw IOException("symbolic links cannot be locked")
        val (n, sha) = src.inputStream().use { addStream(it, parent, src.name, src.lastModified(), "", src.absolutePath) }
        if (!verify(n.id, n.size, sha) || n.size != src.length()) { delete(n.id); throw IOException("verification failed") }
        if (removeOriginal) { secureDelete(src); onOriginalRemoved?.invoke(src) }
        return n
    }

    /** Recursive and resumable: folders are merged by name, files already present (same name+size) are skipped. [cancel] is polled between files. */
    fun lockFolder(src: File, parent: String?, removeOriginal: Boolean = true, cancel: () -> Boolean = { false }, progress: (File) -> Unit = {}): VaultNode {
        if (isSymbolicLink(src)) throw IOException("symbolic links cannot be locked")
        val dir = synchronized(this) {
            val e = list(parent).firstOrNull { it.dir && it.name == src.name }
            if (e == null) mkdir(parent, src.name, src.absolutePath) else { if (e.orig.isEmpty()) setOrig(e.id, src.absolutePath); e }
        }
        for (c in src.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (cancel()) break
            if (isSymbolicLink(c)) continue // leave links untouched; never follow a link outside the selected tree
            if (c.isDirectory) { lockFolder(c, dir.id, removeOriginal, cancel, progress); continue }
            progress(c)
            val have = synchronized(this) { list(dir.id).firstOrNull { !it.dir && it.name == c.name && it.size == c.length() } }
            if (have != null && sameContent(have, c)) { if (removeOriginal) { secureDelete(c); onOriginalRemoved?.invoke(c) }; continue }   // same name + size is not enough: the bytes must match before the original may go
            lockFile(c, dir.id, removeOriginal)
        }
        if (removeOriginal && src.listFiles().isNullOrEmpty()) { src.delete(); onOriginalRemoved?.invoke(src) }
        return dir
    }

    private fun sha(i: InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
        while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) }
        return md.digest()
    }

    /** True when the vault copy [n] and the file [f] hold identical bytes. */
    private fun sameContent(n: VaultNode, f: File): Boolean = try {
        n.size == f.length() && MessageDigest.isEqual(f.inputStream().use { sha(it) }, open(n.id).use { sha(it) })
    } catch (_: Exception) { false }

    // ---------- self-healing ----------
    private fun isId(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sniffExt(h: ByteArray, n: Int): String {
        fun st(vararg b: Int) = n >= b.size && b.indices.all { (h[it].toInt() and 255) == b[it] }
        return when {
            st(0xFF, 0xD8, 0xFF) -> ".jpg"; st(0x89, 0x50, 0x4E, 0x47) -> ".png"; st(0x47, 0x49, 0x46, 0x38) -> ".gif"
            st(0x25, 0x50, 0x44, 0x46) -> ".pdf"; st(0x50, 0x4B, 0x03, 0x04) -> ".zip"; st(0x4F, 0x67, 0x67, 0x53) -> ".ogg"
            st(0x49, 0x44, 0x33) -> ".mp3"; n >= 12 && h[4].toInt() == 0x66 && h[5].toInt() == 0x74 && h[6].toInt() == 0x79 && h[7].toInt() == 0x70 -> ".mp4"
            n >= 12 && String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> ".webp"
            else -> ""
        }
    }

    /**
     * Runs when the vault opens. A blob that is not in the index (crash between blob and journal, or an older index snapshot after a storage fault) is NEVER deleted:
     * a complete blob that authenticates is put back into the folder "[RECOVERED]" (name lost, bytes kept); anything else is moved to ".lsv/q" (dropped after 30 days).
     */
    @Synchronized private fun heal() {
        val ix = index ?: return; val kd = kData ?: return
        val q = File(root, "q"); var rec: VaultNode? = null
        File(root, "d").listFiles()?.forEach { sh ->
            sh.listFiles()?.forEach { f ->
                if (!isId(f.name) || ix[f.name] != null) return@forEach
                var ext: String? = null; var size = 0L
                try {
                    val id = VaultFormat.unhex(f.name)
                    VaultReader(f, VaultCrypto.hkdf(kd, byteArrayOf('f'.code.toByte()) + id), id).use { r ->
                        if (r.authenticateEnds()) { val h = ByteArray(12); val c = maxOf(0, r.read(h, 0, 12)); ext = sniffExt(h, c); size = r.length }
                    }
                } catch (_: Exception) { ext = null }
                if (ext != null) {
                    val dir = rec ?: ix.all.firstOrNull { it.parent == null && it.dir && it.name == RECOVERED } ?: VaultNode(VaultFormat.hex(VaultCrypto.rand(16)), null, RECOVERED, true, 0, System.currentTimeMillis(), "")
                    if (ix[dir.id] == null) ix.commit(listOf(VaultIndex.Op.Put(dir)))
                    rec = dir
                    ix.commit(listOf(VaultIndex.Op.Put(VaultNode(f.name, dir.id, "recovered-" + f.name.take(8) + ext, false, size, f.lastModified(), ""))))
                } else {
                    q.mkdirs(); if (!f.renameTo(File(q, f.name))) f.renameTo(File(q, f.name + "." + System.nanoTime()))
                }
            }
            if (sh.list().isNullOrEmpty()) sh.delete()
        }
        val old = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        q.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
        if (q.exists() && q.list().isNullOrEmpty()) q.delete()
    }

    class IntegrityReport(val files: Int, val bytes: Long, val damaged: List<VaultNode>, val missing: List<VaultNode>) {
        val ok get() = damaged.isEmpty() && missing.isEmpty()
    }

    /** Reads every file end to end (every GCM tag) and compares the length with the index. Never changes anything. [progress] = (done, total). */
    fun checkAll(cancel: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): IntegrityReport {
        val files = synchronized(this) { idx().all.filter { !it.dir } }
        val bad = ArrayList<VaultNode>(); val gone = ArrayList<VaultNode>(); var bytes = 0L
        for ((i, n) in files.withIndex()) {
            if (cancel()) break
            progress(i, files.size)
            if (!VaultFormat.blobFile(root, n.id).exists()) { gone.add(n); continue }
            try {
                var total = 0L
                open(n.id).use { r -> val b = ByteArray(64 * 1024); while (true) { val c = r.read(b); if (c < 0) break; total += c } }
                if (total != n.size) bad.add(n) else bytes += total
            } catch (e: IllegalStateException) { throw e }   // vault locked meanwhile: the caller stops
            catch (e: Exception) { bad.add(n) }
        }
        progress(files.size, files.size)
        return IntegrityReport(files.size, bytes, bad, gone)
    }

    /** Irreversibly removes the whole vault directory. */
    @Synchronized fun wipe() { lock(); root.deleteRecursively() }
}
