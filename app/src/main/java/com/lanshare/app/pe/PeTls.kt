package com.lanshare.app.pe

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Certificate for the FTPS option of Phone Explorer: one self-signed RSA-2048 certificate (10 years) is made on first use and kept in
 * filesDir (pe_tls_key.pk8 + pe_tls_cert.der), so the fingerprint a client has accepted once stays the same.
 */
object PeTls {
    private const val KEY = "pe_tls_key.pk8"
    private const val CERT = "pe_tls_cert.der"

    private fun load(dir: File): Pair<PrivateKey, X509Certificate>? = try {
        val kf = File(dir, KEY); val cf = File(dir, CERT)
        if (!kf.isFile || !cf.isFile) null else {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            val cert = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) } as X509Certificate
            cert.checkValidity()
            key to cert
        }
    } catch (_: Exception) { null }

    private fun generate(dir: File) {
        val bc = BouncyCastleProvider()
        val kp = KeyPairGenerator.getInstance("RSA", bc).also { it.initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Phone Explorer")
        val now = System.currentTimeMillis()
        val b = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L), name, kp.public)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(b.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(kp.private)))
        dir.mkdirs()
        File(dir, KEY).writeBytes(kp.private.encoded)
        File(dir, CERT).writeBytes(cert.encoded)
    }

    /** TLS server context with the stored certificate (made first if there is none). Takes a second or two the first time. */
    @Synchronized fun context(dir: File): SSLContext {
        val (key, cert) = load(dir) ?: run { generate(dir); load(dir) ?: throw java.io.IOException("could not create the FTPS certificate") }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).also { it.load(null, null) }
        ks.setKeyEntry("pe", key, charArrayOf(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(ks, charArrayOf()) }
        return SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, null) }
    }

    /** SHA-256 fingerprint of the certificate (what a client shows when it asks to trust it); "" until one exists. */
    fun fingerprint(dir: File): String = load(dir)?.second?.let { c ->
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }
    } ?: ""
}
