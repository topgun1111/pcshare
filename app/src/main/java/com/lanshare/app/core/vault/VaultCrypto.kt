package com.lanshare.app.core.vault

import org.bouncycastle.crypto.generators.SCrypt
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Primitives: scrypt KEK, HKDF-SHA256, AES-256-GCM. Pure JVM + BouncyCastle (already bundled; SCrypt works on API 24). */
object VaultCrypto {
    private val rng = SecureRandom()
    fun rand(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun wipe(b: ByteArray?) { b?.fill(0) }

    /** Password -> NFC UTF-8 (Turkish ş ğ ı İ stay identical across keyboards) -> scrypt. The password array is zeroised. */
    fun kek(password: CharArray, salt: ByteArray, logN: Int, r: Int, p: Int): ByteArray {
        val pw = Normalizer.normalize(String(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        try { return SCrypt.generate(pw, salt, 1 shl logN, r, p, 32) } finally { wipe(pw); password.fill('\u0000') }
    }

    /** 2^15 by default; 2^16 (64 MiB) when 2^15 takes < half of [targetMs] on this device. */
    fun calibrate(targetMs: Long = 800): Int {
        val t = System.nanoTime(); SCrypt.generate(ByteArray(8), ByteArray(32), 1 shl 15, 8, 1, 32)
        return if ((System.nanoTime() - t) / 1_000_000 < targetMs / 2) 16 else 15
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256, empty salt, single 32-byte output block. */
    fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        try { return hmac(prk, info + 1) } finally { wipe(prk) }
    }
    fun hkdf(ikm: ByteArray, info: String) = hkdf(ikm, info.toByteArray(Charsets.UTF_8))

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad) }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, pt: ByteArray, off: Int = 0, len: Int = pt.size): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(pt, off, len)

    /** @throws GeneralSecurityException (AEADBadTagException) on any tamper / wrong key. */
    @Throws(GeneralSecurityException::class)
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ct: ByteArray, off: Int = 0, len: Int = ct.size): ByteArray =
        gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ct, off, len)
}
