package com.lanshare.app.core.vault

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Blob: [ver][fileId 16][baseNonce 4] + chunks of 64 KiB plaintext, each AES-256-GCM (+16 tag).
 * nonce = baseNonce || counter(8); AAD = fileId || counter(8) || isFinal(1)  -> truncation / reorder / swap fail authentication.
 * The last chunk (possibly empty) is the only one with isFinal=1, so a 0-byte file is one 16-byte chunk.
 */
private fun nonce(base: ByteArray, ctr: Long): ByteArray = ByteBuffer.allocate(12).put(base).putLong(ctr).array()
private fun aad(id: ByteArray, ctr: Long, fin: Boolean): ByteArray = ByteBuffer.allocate(25).put(id).putLong(ctr).put(if (fin) 1 else 0).array()

class VaultWriter(private val out: OutputStream, private val id: ByteArray, private val key: ByteArray) : OutputStream() {
    private val base = VaultCrypto.rand(4)
    private val buf = ByteArray(VaultFormat.CHUNK)
    private var n = 0
    private var ctr = 0L
    var total = 0L; private set

    init { out.write(VaultFormat.BLOB_VER); out.write(id); out.write(base) }

    private fun flush(fin: Boolean) {
        out.write(VaultCrypto.seal(key, nonce(base, ctr), aad(id, ctr, fin), buf, 0, n)); ctr++; n = 0
    }

    override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off; var l = len
        while (l > 0) {
            if (n == buf.size) flush(false)      // a full chunk is held back until more data proves it is not the last
            val c = minOf(l, buf.size - n); System.arraycopy(b, o, buf, n, c); n += c; o += c; l -= c; total += c
        }
    }
    override fun close() { if (ctr >= 0) { flush(true); ctr = -1; buf.fill(0); (out as? java.io.FileOutputStream)?.fd?.sync(); out.close() } }
}

/** Random-access decrypting reader over one blob. Not thread-safe. */
class VaultReader(file: File, private val key: ByteArray, expectId: ByteArray? = null) : InputStream() {
    private val raf = RandomAccessFile(file, "r")
    private val id = ByteArray(16)
    private val base = ByteArray(4)
    private val chunks: Long
    private val lastPlain: Int
    val length: Long
    private var pos = 0L
    private var cIdx = -1L
    private var cBuf = ByteArray(0)

    init {
        try {
            val hdr = ByteArray(VaultFormat.BLOB_HDR)
            val flen = raf.length()
            if (flen < VaultFormat.BLOB_HDR + VaultFormat.TAG) throw VaultCorruptException("blob too short")
            raf.readFully(hdr)
            if (hdr[0].toInt() != VaultFormat.BLOB_VER) throw VaultCorruptException("blob version")
            hdr.copyInto(id, 0, 1, 17); hdr.copyInto(base, 0, 17, 21)
            if (expectId != null && !expectId.contentEquals(id)) throw VaultCorruptException("blob id mismatch")
            val body = flen - VaultFormat.BLOB_HDR; val full = VaultFormat.CHUNK + VaultFormat.TAG
            val q = body / full; val rem = (body % full).toInt()
            if (rem != 0 && rem < VaultFormat.TAG) throw VaultCorruptException("blob truncated")
            chunks = if (rem == 0) q else q + 1
            lastPlain = if (rem == 0) VaultFormat.CHUNK else rem - VaultFormat.TAG
            length = (chunks - 1) * VaultFormat.CHUNK + lastPlain
        } catch (e: Throwable) { raf.close(); throw e }
    }

    private fun load(k: Long) {
        if (k == cIdx) return
        val full = VaultFormat.CHUNK + VaultFormat.TAG
        val fin = k == chunks - 1
        val clen = if (fin) lastPlain + VaultFormat.TAG else full
        val ct = ByteArray(clen)
        raf.seek(VaultFormat.BLOB_HDR + k * full); raf.readFully(ct)
        cBuf = try { VaultCrypto.open(key, nonce(base, k), aad(id, k, fin), ct) } catch (e: java.security.GeneralSecurityException) { throw VaultCorruptException("authentication failed (chunk $k)") }
        cIdx = k
    }

    /** True when the first and the last chunk authenticate (cheap proof that the blob is complete and belongs to this key). */
    fun authenticateEnds(): Boolean = try { load(0); load(chunks - 1); true } catch (e: VaultCorruptException) { false }

    fun seek(p: Long) { require(p in 0..length); pos = p }
    fun position() = pos
    override fun available(): Int = minOf(length - pos, Int.MAX_VALUE.toLong()).toInt()
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= length) return -1
        var done = 0
        while (done < len && pos < length) {
            load(pos / VaultFormat.CHUNK)
            val o = (pos % VaultFormat.CHUNK).toInt(); val c = minOf(len - done, cBuf.size - o)
            System.arraycopy(cBuf, o, b, off + done, c); done += c; pos += c
        }
        return done
    }
    override fun skip(n: Long): Long { val s = minOf(n.coerceAtLeast(0), length - pos); pos += s; return s }
    override fun close() { cBuf.fill(0); VaultCrypto.wipe(key); raf.close() }
}
