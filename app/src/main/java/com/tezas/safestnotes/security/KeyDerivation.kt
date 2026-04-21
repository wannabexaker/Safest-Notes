package com.tezas.safestnotes.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure key-derivation and randomness primitives.
 *
 * PBKDF2-HMAC-SHA256 with 600,000 iterations follows OWASP 2023 guidance.
 * Derived key material is returned as a raw ByteArray so callers can zero it
 * with [java.util.Arrays.fill] once it is no longer needed.
 */
object KeyDerivation {

    const val PBKDF2_ITERATIONS = 600_000
    const val KEY_SIZE_BITS = 256
    const val SALT_SIZE_BYTES = 32
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DEK_SIZE_BYTES = 32

    private val secureRandom = SecureRandom()

    /**
     * Derive a 256-bit Key-Encryption-Key from the user's master password.
     *
     * The [password] CharArray is consumed (handed to PBEKeySpec, which copies
     * it internally). Callers should still zero their own copy after calling.
     */
    fun deriveKek(password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == SALT_SIZE_BYTES) { "Salt must be $SALT_SIZE_BYTES bytes" }
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val key = factory.generateSecret(spec)
            return key.encoded
        } finally {
            // PBEKeySpec.clearPassword() zeroes the internal CharArray copy.
            spec.clearPassword()
        }
    }

    /** Generate a fresh random salt for PBKDF2. Store plaintext. */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    /** Generate a fresh random 256-bit Data-Encryption-Key. */
    fun generateDek(): ByteArray {
        val dek = ByteArray(DEK_SIZE_BYTES)
        secureRandom.nextBytes(dek)
        return dek
    }
}
