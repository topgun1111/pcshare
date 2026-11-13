package com.lanshare.app.core.vault

import java.security.MessageDigest
import java.security.SecureRandom

/** Recovery formats for the 32-byte recovery secret from [VaultStore.enableRecovery]. Pure JVM. */
object VaultRecovery {
    private const val ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // 32 symbols, no I/O/0/1

    // ---- 1. Recovery code: 52 Base32 chars + 4 check chars, 14 groups of 4 (typed or printed) ----
    fun toCode(secret: ByteArray): String {
        require(secret.size == 32)
        val body = b32(secret)                                  // 52 chars
        val chk = b32(MessageDigest.getInstance("SHA-256").digest(secret)).take(4)
        return (body + chk).chunked(4).joinToString("-")
    }

    /** null = typo / bad checksum. Tolerates case, spaces and dashes. */
    fun fromCode(code: String): ByteArray? {
        val c = code.uppercase().filter { !it.isWhitespace() && it != '-' }
        if (c.length != 56 || c.any { ALPHA.indexOf(it) < 0 }) return null
        val secret = unb32(c.take(52)).copyOf(32)
        return if (toCode(secret).replace("-", "") == c) secret else null
    }

    private fun b32(d: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in d) { buf = (buf shl 8) or (x.toInt() and 255); bits += 8; while (bits >= 5) { sb.append(ALPHA[(buf shr (bits - 5)) and 31]); bits -= 5 } }
        if (bits > 0) sb.append(ALPHA[(buf shl (5 - bits)) and 31]); return sb.toString()
    }
    private fun unb32(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (ch in s) { buf = (buf shl 5) or ALPHA.indexOf(ch); bits += 5; if (bits >= 8) { out.write((buf shr (bits - 8)) and 255); bits -= 8 } }
        return out.toByteArray()
    }

    // ---- 2. Key file (.lsrk): secret sealed under an optional password, safe to keep in cloud / USB / PC ----
    // format: "LSRK1" | flag(1) | salt(32) | nonce(12) | sealed(32+16)   (flag 0 = secret sealed under a fixed public key = obfuscation only; always ask for a file password)
    fun toKeyFile(secret: ByteArray, filePassword: CharArray, logN: Int = 15): ByteArray {
        val salt = VaultCrypto.rand(32); val nonce = VaultCrypto.rand(12)
        val kek = VaultCrypto.kek(filePassword, salt, logN, 8, 1)
        val head = "LSRK1".toByteArray() + byteArrayOf(logN.toByte()) + salt + nonce
        val sealed = VaultCrypto.seal(kek, nonce, head, secret); VaultCrypto.wipe(kek); return head + sealed
    }
    fun fromKeyFile(file: ByteArray, filePassword: CharArray): ByteArray? {
        return try {
            if (file.size != 5 + 1 + 32 + 12 + 48 || !String(file, 0, 5).equals("LSRK1")) null else {
                val logN = file[5].toInt(); if (logN !in 10..VaultFormat.MAX_LOGN) return null
                val head = file.copyOf(50); val kek = VaultCrypto.kek(filePassword, file.copyOfRange(6, 38), logN, 8, 1)
                try { VaultCrypto.open(kek, file.copyOfRange(38, 50), head, file, 50, 48) } finally { VaultCrypto.wipe(kek) }
            }
        } catch (e: Exception) { null }
    }

    // ---- 3. Shamir split: k-of-n shares of the secret (e.g. 2-of-3: family, cloud, safe) ----
    // Share = [index(1)][k(1)][32 bytes]  -> shown with the same Base32 code format via [shareToCode]
    fun split(secret: ByteArray, k: Int, n: Int, rnd: SecureRandom = SecureRandom()): List<ByteArray> {
        require(k in 2..n && n <= 10 && secret.size == 32)
        val coef = Array(32) { i -> ByteArray(k).also { rnd.nextBytes(it); it[0] = secret[i] } }
        return (1..n).map { x -> ByteArray(34).also { s -> s[0] = x.toByte(); s[1] = k.toByte(); for (i in 0 until 32) s[2 + i] = eval(coef[i], x) } }
    }
    fun combine(shares: List<ByteArray>): ByteArray? {
        if (shares.isEmpty() || shares.any { it.size != 34 }) return null
        val k = shares[0][1].toInt() and 255
        if (k !in 2..10 || shares.size < k) return null
        // Reject malformed, duplicate, or mixed-threshold shares before interpolation.
        if (shares.any { (it[1].toInt() and 255) != k || (it[0].toInt() and 255) !in 1..255 }) return null
        val indexes = shares.map { it[0].toInt() and 255 }
        if (indexes.distinct().size != indexes.size) return null
        val use = shares.take(k); val out = ByteArray(32)
        for (i in 0 until 32) {
            var acc = 0
            for (a in use) { var num = 1; var den = 1
                for (b in use) if (a !== b) { num = mul(num, b[0].toInt() and 255); den = mul(den, (a[0].toInt() and 255) xor (b[0].toInt() and 255)) }
                acc = acc xor mul(a[2 + i].toInt() and 255, mul(num, inv(den))) }
            out[i] = acc.toByte()
        }
        return out
    }
    fun shareToCode(s: ByteArray) = (VaultFormat.hex(s.copyOf(2)) + "-" + toCode(s.copyOfRange(2, 34)))
    fun shareFromCode(c: String): ByteArray? { val p = c.trim().split("-", limit = 2); if (p.size != 2 || p[0].length != 4) return null
        val idx = try { VaultFormat.unhex(p[0]) } catch (e: Exception) { return null }; val sec = fromCode(p[1]) ?: return null; return idx + sec }

    private fun mul(a: Int, b: Int): Int { var x = a; var y = b; var r = 0; while (y > 0) { if (y and 1 != 0) r = r xor x; x = x shl 1; if (x and 256 != 0) x = x xor 0x11b; y = y shr 1 }; return r }
    private fun inv(a: Int): Int { var r = 1; repeat(254) { r = mul(r, a) }; return r }
    private fun eval(c: ByteArray, x: Int): Byte { var r = 0; for (i in c.indices.reversed()) r = mul(r, x) xor (c[i].toInt() and 255); return r.toByte() }
}

/** Failed-unlock throttle. [store] persists (counter, lastFailMs) in app-private prefs (resets on reinstall; scrypt is the real offline defence). */
class VaultThrottle(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {
    interface Store { var fails: Int; var lastFail: Long }
    /** ms the user must still wait; 0 = may try. 1-4 fails: none, then 30 s doubling, cap 1 h. */
    fun waitMs(): Long { val f = store.fails; if (f < 5) return 0; val d = minOf(30_000L shl minOf(f - 5, 7), 3_600_000L); return maxOf(0, store.lastFail + d - now()) }
    fun onFail() { store.fails += 1; store.lastFail = now() }
    fun onSuccess() { store.fails = 0 }
}
