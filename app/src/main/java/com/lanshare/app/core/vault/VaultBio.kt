package com.lanshare.app.core.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint convenience layer. Keystore key + wrapped MK live in app-private storage and die with an uninstall BY DESIGN
 * (password / backup password / recovery code reopen the vault; then [enable] again). Biometric never creates or replaces credentials.
 * UI: BiometricPrompt(CryptoObject(cipherForUnlock())) -> [unwrap]. Add androidx.biometric:biometric:1.1.0 + USE_BIOMETRIC.
 */
object VaultBio {
    private const val ALIAS = "vault_bio"
    private fun file(c: Context) = File(c.filesDir, "vbio")
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    fun isEnabled(c: Context) = file(c).exists() && ks().containsAlias(ALIAS)

    /** Cipher to hand to BiometricPrompt for enabling (ENCRYPT). Call [finishEnable] with the authenticated cipher. */
    fun cipherForEnable(): Cipher {
        ks().deleteEntry(ALIAS)
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (android.os.Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(true)
        if (android.os.Build.VERSION.SDK_INT >= 28) try { b.setIsStrongBoxBacked(true); g.init(b.build()) } catch (e: Exception) { b.setIsStrongBoxBacked(false); g.init(b.build()) } else g.init(b.build())
        g.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, ks().getKey(ALIAS, null) as SecretKey) }
    }
    fun finishEnable(c: Context, authedCipher: Cipher, mk: ByteArray) {
        val ct = authedCipher.doFinal(mk); file(c).writeBytes(byteArrayOf(authedCipher.iv.size.toByte()) + authedCipher.iv + ct)
    }
    /** null = key invalidated (new fingerprint enrolled / data cleared): caller falls back to password and offers re-enable. */
    fun cipherForUnlock(c: Context): Cipher? = try {
        val d = file(c).readBytes(); val n = d[0].toInt()
        Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, ks().getKey(ALIAS, null) as SecretKey, GCMParameterSpec(128, d.copyOfRange(1, 1 + n))) }
    } catch (e: Exception) { disable(c); null }
    fun unwrap(c: Context, authedCipher: Cipher): ByteArray? = try { val d = file(c).readBytes(); authedCipher.doFinal(d, 1 + d[0].toInt(), d.size - 1 - d[0].toInt()) } catch (e: Exception) { disable(c); null }
    fun disable(c: Context) { file(c).delete(); try { ks().deleteEntry(ALIAS) } catch (_: Exception) {} }
}
