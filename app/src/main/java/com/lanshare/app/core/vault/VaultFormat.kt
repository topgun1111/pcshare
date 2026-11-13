package com.lanshare.app.core.vault

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** On-disk constants and (de)serialisation of the vault container ("<root>/.lsv"). Pure JVM: no android.* imports (unit-testable). */
object VaultFormat {
    const val DIR = ".lsv"
    const val VERSION = 1
    const val HEADER_SIZE = 512
    const val CHUNK = 64 * 1024
    const val TAG = 16
    const val BLOB_VER = 1
    const val BLOB_HDR = 1 + 16 + 4            // ver | fileId | baseNonce
    const val INDEX_VER = 1
    const val MAX_LOGN = 18                     // reject hostile headers (scrypt memory = 128*r*2^logN)
    val MAGIC = byteArrayOf(0x9d.toByte(), 0x3a, 0xc1.toByte(), 0x57, 0xe2.toByte(), 0x08, 0xb4.toByte(), 0x6f)

    // header offsets
    const val O_VER = 8; const val O_LOGN = 10; const val O_R = 11; const val O_P = 12; const val O_FLAGS = 13
    const val O_SALT = 14; const val O_WN = 46; const val O_WK = 58; const val O_RN = 106; const val O_RK = 118; const val O_BS = 166; const val O_BN = 198; const val O_BK = 210; const val O_END = 258
    const val AAD_LEN = 46                      // magic..salt authenticate the wraps
    const val WRAP_LEN = 32 + TAG
    const val F_RECOVERY = 1
    const val F_BACKUP = 2                      // second password slot (own salt, same scrypt params)

    fun blobFile(root: File, idHex: String) = File(File(File(root, "d"), idHex.substring(0, 2)), idHex)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

class VaultAuthException(msg: String = "wrong password") : Exception(msg)
class VaultCorruptException(msg: String) : Exception(msg)

/** Fixed-size (512 B) header; unused space is random so nothing (e.g. "recovery enabled" padding) leaks. */
class VaultHeader(
    val logN: Int, val r: Int, val p: Int, var flags: Int, val salt: ByteArray,
    var wrapNonce: ByteArray, var wrapped: ByteArray, var recNonce: ByteArray, var recWrapped: ByteArray,
    var bSalt: ByteArray = VaultCrypto.rand(32), var bNonce: ByteArray = VaultCrypto.rand(12), var bWrapped: ByteArray = VaultCrypto.rand(VaultFormat.WRAP_LEN),
) {
    fun copyWith(flags: Int = this.flags, wn: ByteArray = wrapNonce, w: ByteArray = wrapped, rn: ByteArray = recNonce, rw: ByteArray = recWrapped, bs: ByteArray = bSalt, bn: ByteArray = bNonce, bw: ByteArray = bWrapped) =
        VaultHeader(logN, r, p, flags, salt, wn, w, rn, rw, bs, bn, bw)

    fun encode(): ByteArray {
        val b = VaultCrypto.rand(VaultFormat.HEADER_SIZE)
        VaultFormat.MAGIC.copyInto(b, 0)
        b[VaultFormat.O_VER] = (VaultFormat.VERSION shr 8).toByte(); b[VaultFormat.O_VER + 1] = VaultFormat.VERSION.toByte()
        b[VaultFormat.O_LOGN] = logN.toByte(); b[VaultFormat.O_R] = r.toByte(); b[VaultFormat.O_P] = p.toByte(); b[VaultFormat.O_FLAGS] = flags.toByte()
        salt.copyInto(b, VaultFormat.O_SALT); wrapNonce.copyInto(b, VaultFormat.O_WN); wrapped.copyInto(b, VaultFormat.O_WK)
        if (flags and VaultFormat.F_RECOVERY != 0) { recNonce.copyInto(b, VaultFormat.O_RN); recWrapped.copyInto(b, VaultFormat.O_RK) }
        if (flags and VaultFormat.F_BACKUP != 0) { bSalt.copyInto(b, VaultFormat.O_BS); bNonce.copyInto(b, VaultFormat.O_BN); bWrapped.copyInto(b, VaultFormat.O_BK) }
        return b
    }

    /** Bytes authenticated by both wraps (params + salt). */
    fun aad(): ByteArray = encode().copyOf(VaultFormat.AAD_LEN).also { it[VaultFormat.O_FLAGS] = 0 }   // flags excluded: enabling recovery must not invalidate the password wrap

    companion object {
        fun decode(b: ByteArray): VaultHeader {
            if (b.size != VaultFormat.HEADER_SIZE || !b.copyOf(8).contentEquals(VaultFormat.MAGIC)) throw VaultCorruptException("bad header")
            val ver = ((b[VaultFormat.O_VER].toInt() and 255) shl 8) or (b[VaultFormat.O_VER + 1].toInt() and 255)
            if (ver > VaultFormat.VERSION) throw VaultCorruptException("newer vault version $ver")
            val logN = b[VaultFormat.O_LOGN].toInt(); val r = b[VaultFormat.O_R].toInt() and 255; val p = b[VaultFormat.O_P].toInt() and 255
            if (logN !in 4..VaultFormat.MAX_LOGN || r !in 1..32 || p !in 1..8) throw VaultCorruptException("bad kdf params")
            fun s(o: Int, n: Int) = b.copyOfRange(o, o + n)
            return VaultHeader(logN, r, p, b[VaultFormat.O_FLAGS].toInt() and 255, s(VaultFormat.O_SALT, 32),
                s(VaultFormat.O_WN, 12), s(VaultFormat.O_WK, VaultFormat.WRAP_LEN), s(VaultFormat.O_RN, 12), s(VaultFormat.O_RK, VaultFormat.WRAP_LEN),
                s(VaultFormat.O_BS, 32), s(VaultFormat.O_BN, 12), s(VaultFormat.O_BK, VaultFormat.WRAP_LEN))
        }
    }
}

/** Index entry. [parent] null = vault root. */
class VaultNode(
    val id: String, val parent: String?, val name: String, val dir: Boolean,
    val size: Long, val mtime: Long, val mime: String,
) {
    fun copy(parent: String? = this.parent, name: String = this.name) = VaultNode(id, parent, name, dir, size, mtime, mime)

    fun write(o: DataOutputStream) {
        o.write(VaultFormat.unhex(id)); o.write(if (parent == null) ByteArray(16) else VaultFormat.unhex(parent))
        o.writeBoolean(dir); o.writeLong(size); o.writeLong(mtime); o.writeUTF(name); o.writeUTF(mime)
    }

    companion object {
        private val ZERO = ByteArray(16)
        fun read(i: DataInputStream): VaultNode {
            val id = ByteArray(16).also { i.readFully(it) }; val pa = ByteArray(16).also { i.readFully(it) }
            val dir = i.readBoolean(); val size = i.readLong(); val mt = i.readLong()
            return VaultNode(VaultFormat.hex(id), if (pa.contentEquals(ZERO)) null else VaultFormat.hex(pa), i.readUTF(), dir, size, mt, i.readUTF())
        }
    }
}
