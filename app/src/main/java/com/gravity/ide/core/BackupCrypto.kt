package com.gravity.ide.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based backup encryption: PBKDF2-HMAC-SHA256 key derivation, AES-256-CTR for
 * streaming encryption and HMAC-SHA256 over header + ciphertext (encrypt-then-MAC).
 * The MAC is verified over the whole file before any plaintext is released.
 *
 * Layout: "GRVBK1" | salt(16) | iv(16) | ciphertext | mac(32)
 */
object BackupCrypto {
    private val MAGIC = "GRVBK1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val MAC_LEN = 32

    class BadPasswordOrCorruptException : IOException("Wrong password or the backup file is corrupt")

    private fun deriveKeys(password: CharArray, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(PBEKeySpec(password, salt, ITERATIONS, 512)).encoded
        return SecretKeySpec(bytes.copyOfRange(0, 32), "AES") to SecretKeySpec(bytes.copyOfRange(32, 64), "HmacSHA256")
    }

    fun isEncrypted(file: File): Boolean = FileInputStream(file).use { input ->
        val head = ByteArray(MAGIC.size)
        input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val (encKey, macKey) = deriveKeys(password, salt)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv)) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
        output.write(MAGIC); output.write(salt); output.write(iv)
        mac.update(MAGIC); mac.update(salt); mac.update(iv)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            val ct = cipher.update(buf, 0, r)
            if (ct != null && ct.isNotEmpty()) {
                output.write(ct)
                mac.update(ct)
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            output.write(tail)
            mac.update(tail)
        }
        output.write(mac.doFinal())
        output.flush()
    }

    /** Verifies the MAC over [encrypted] first, then streams plaintext into [output]. */
    fun decrypt(encrypted: File, output: OutputStream, password: CharArray) {
        val total = encrypted.length()
        val headerLen = MAGIC.size + 32
        if (total < headerLen + MAC_LEN) throw BadPasswordOrCorruptException()
        val salt = ByteArray(16)
        val iv = ByteArray(16)
        FileInputStream(encrypted).use { input ->
            val magic = ByteArray(MAGIC.size)
            readFully(input, magic); readFully(input, salt); readFully(input, iv)
            if (!magic.contentEquals(MAGIC)) throw BadPasswordOrCorruptException()
        }
        val (encKey, macKey) = deriveKeys(password, salt)
        val bodyLen = total - headerLen - MAC_LEN

        // Pass 1: authenticate.
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
        val expected = ByteArray(MAC_LEN)
        FileInputStream(encrypted).use { input ->
            val header = ByteArray(headerLen)
            readFully(input, header)
            mac.update(header)
            copyBounded(input, bodyLen) { b, n -> mac.update(b, 0, n) }
            readFully(input, expected)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), expected)) throw BadPasswordOrCorruptException()

        // Pass 2: decrypt.
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, encKey, IvParameterSpec(iv)) }
        FileInputStream(encrypted).use { input ->
            input.skip(headerLen.toLong())
            copyBounded(input, bodyLen) { b, n ->
                val pt = cipher.update(b, 0, n)
                if (pt != null) output.write(pt)
            }
            output.write(cipher.doFinal())
        }
        output.flush()
    }

    fun encryptFile(src: File, dest: File, password: CharArray) {
        FileInputStream(src).use { i -> FileOutputStream(dest).use { o -> encrypt(i, o, password) } }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val r = input.read(target, off, target.size - off)
            if (r < 0) throw BadPasswordOrCorruptException()
            off += r
        }
    }

    private inline fun copyBounded(input: InputStream, length: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) throw BadPasswordOrCorruptException()
            sink(buf, r)
            remaining -= r
        }
    }
}
