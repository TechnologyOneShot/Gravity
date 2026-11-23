package com.gravity.ide.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM key held in the Android Keystore (non-exportable). Only ciphertext is
 * written to disk; API keys never appear in logs, exports or backups.
 */
class KeystoreCipher(private val alias: String = "gravity_master_key") {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }
}

class SecretStore(context: Context, private val cipher: KeystoreCipher = KeystoreCipher()) {
    private val prefs = context.getSharedPreferences("gravity_secrets", Context.MODE_PRIVATE)

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val enc = Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(name, enc).apply()
    }

    fun get(name: String): String? {
        val enc = prefs.getString(name, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore key invalidated (e.g. device credential reset): the secret is unrecoverable.
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) = prefs.edit().remove(name).apply()

    /** Round-trip used by the self-test. */
    fun selfTest(): Boolean {
        val probe = "probe-" + System.nanoTime()
        put("__selftest", probe)
        val ok = get("__selftest") == probe
        remove("__selftest")
        return ok
    }

    companion object {
        fun providerKey(providerId: String) = "provider:$providerId"
        const val BRAVE_KEY = "search:brave"
        const val HF_TOKEN = "models:hf_token"
    }
}
