package com.tezas.safestnotes.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore wrapper used to wrap (encrypt) a copy of the DEK so
 * biometrics can unlock it without the master password being in memory.
 *
 * The key is generated with `setUserAuthenticationRequired(true)` — the
 * Cipher must be passed through [androidx.biometric.BiometricPrompt] before
 * doFinal() will succeed. `setInvalidatedByBiometricEnrollment(true)` means
 * adding a new fingerprint permanently invalidates the key; the caller must
 * fall back to password and re-enroll biometrics.
 */
object BiometricKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEY_ALIAS = "safestnotes_biometric_key"
    private const val TAG_SIZE_BITS = 128

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasKey(): Boolean = try {
        keyStore.containsAlias(KEY_ALIAS)
    } catch (_: Exception) {
        false
    }

    fun deleteKey() {
        try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {
            // best effort
        }
    }

    /** Create (or overwrite) the biometric key. */
    fun createBiometricKey() {
        deleteKey()
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }

    private fun loadKey(): SecretKey {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Biometric key missing")
        return entry.secretKey
    }

    /**
     * Cipher that will wrap the DEK under the Keystore biometric key.
     * Caller should hand this to BiometricPrompt.authenticate(...) before
     * calling doFinal(dek).
     */
    fun getEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, loadKey())
        return cipher
    }

    fun getDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return cipher
    }
}
