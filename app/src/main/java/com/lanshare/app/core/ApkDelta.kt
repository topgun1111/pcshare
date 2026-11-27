package com.lanshare.app.core

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** PCShare delta v1: gzip, magic, source/target SHA-256, target length, copy/literal records, end. */
object ApkDelta {
    /** Unknown/non-Wi-Fi transport uses delta unless the user explicitly allows a full download. */
    fun preferDelta(onWifi: Boolean, allowMobileFull: Boolean = false): Boolean = !onWifi && !allowMobileFull

    private val magic = "PCDELTA1".toByteArray(Charsets.US_ASCII)
    private const val MAX_APK = 512L * 1024 * 1024

    fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancelled()) throw IOException("cancel")
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun apply(base: File, patch: File, output: File, baseHash: String, targetHash: String,
              targetSize: Long, cancelled: () -> Boolean = { false }) {
        require(base.canonicalFile != output.canonicalFile && patch.canonicalFile != output.canonicalFile)
        try {
            if (targetSize !in 1..MAX_APK || sha256(base, cancelled) != baseHash) throw IOException("APK base mismatch")
            DataInputStream(GZIPInputStream(patch.inputStream().buffered())).use { input ->
                fun bytes(n: Int) = ByteArray(n).also { input.readFully(it) }
                if (!bytes(8).contentEquals(magic) || hex(bytes(32)) != baseHash ||
                    hex(bytes(32)) != targetHash || input.readLong() != targetSize) throw IOException("Invalid delta header")
                RandomAccessFile(base, "r").use { old ->
                    output.outputStream().buffered().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            if (cancelled()) throw IOException("cancel")
                            val op = input.readUnsignedByte()
                            if (op == 2) break
                            if (op != 0 && op != 1) throw IOException("Invalid delta operation")
                            val offset = if (op == 0) input.readLong() else 0L
                            val size = input.readInt()
                            if (size <= 0 || size.toLong() > targetSize - written ||
                                (op == 0 && (offset < 0 || offset > old.length() || size.toLong() > old.length() - offset))) {
                                throw IOException("Invalid delta bounds")
                            }
                            if (op == 0) old.seek(offset)
                            var left = size
                            while (left > 0) {
                                if (cancelled()) throw IOException("cancel")
                                val n = minOf(left, buffer.size)
                                if (op == 0) old.readFully(buffer, 0, n) else input.readFully(buffer, 0, n)
                                out.write(buffer, 0, n)
                                left -= n
                            }
                            written += size
                        }
                        if (written != targetSize || input.read() != -1) throw IOException("Incomplete or trailing delta data")
                    }
                }
            }
            if (sha256(output, cancelled) != targetHash) throw IOException("Reconstructed APK checksum mismatch")
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }
}
