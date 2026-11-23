package com.gravity.ide.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signing key for apps made with App Builder. It is created once in the Android Keystore and
 * never leaves it (the private key cannot be exported), and it is reused for every app so a
 * rebuilt app installs as an update. If Gravity is uninstalled the key is gone, and apps signed
 * with it must be uninstalled before a new build can be installed.
 */
object AppSigningKey {
    private const val ALIAS = "gravity_app_builder_signing"

    class Material(val privateKey: PrivateKey, val certificate: X509Certificate)

    fun getOrCreate(): Material {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=Gravity App Builder, O=Gravity"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
                .setCertificateNotAfter(Date(now + 30L * 365 * 24 * 3600 * 1000))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return Material(key, cert)
    }
}
