package com.tezas.safestnotes.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Thin wrapper over EncryptedSharedPreferences that persists all key-management
 * material: the PBKDF2 salt, the KEK-wrapped DEK, the biometric-wrapped DEK,
 * and counters used by the rate-limiter.
 *
 * Values are stored Base64-encoded (NO_WRAP) — see [SecurityManager] for the
 * encode/decode call sites.
 */
class SecureStorage(context: Context) {

    companion object {
        private const val PREFS_NAME = "safestnotes_secure_prefs"

        const val KEY_KEK_SALT = "kek_salt"
        const val KEY_VERIFICATION_BLOB = "verification_blob"
        const val KEY_VERIFICATION_IV = "verification_iv"
        const val KEY_DEK_WRAPPED_BY_KEK = "dek_wrapped_by_kek"
        const val KEY_DEK_WRAPPED_IV = "dek_wrapped_iv"
        const val KEY_DEK_BIOMETRIC_WRAPPED = "dek_biometric_wrapped"
        const val KEY_DEK_BIOMETRIC_IV = "dek_biometric_iv"
        const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        const val KEY_LOCKOUT_UNTIL = "lockout_until"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long = prefs.getLong(key, default)
    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(vararg keys: String) {
        val e = prefs.edit()
        keys.forEach { e.remove(it) }
        e.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
