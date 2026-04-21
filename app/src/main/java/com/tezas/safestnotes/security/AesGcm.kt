package com.tezas.safestnotes.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM helpers.
 *
 * * [IV_SIZE] = 12 bytes (96-bit), the GCM recommended nonce length.
 * * [TAG_SIZE_BITS] = 128, the strongest supported tag.
 *
 * Every call to [encrypt] draws a fresh IV from [SecureRandom]; there is no
 * deterministic IV path. Never reuse an IV with the same key.
 */
object AesGcm {

    const val IV_SIZE = 12
    const val TAG_SIZE_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"

    private val secureRandom = SecureRandom()

    data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedBlob) return false
            return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        }
        override fun hashCode(): Int = iv.contentHashCode() * 31 + ciphertext.contentHashCode()
    }

    /**
     * Encrypt [plaintext] with a freshly generated 96-bit random IV.
     * The returned ciphertext includes the 16-byte GCM authentication tag.
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray): EncryptedBlob {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24, or 32 bytes"
        }
        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return EncryptedBlob(iv, ct)
    }

    fun decrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size == IV_SIZE) { "IV must be $IV_SIZE bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val keySpec = SecretKeySpec(key, ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
