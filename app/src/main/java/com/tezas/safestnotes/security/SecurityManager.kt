package com.tezas.safestnotes.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * Orchestrator for the two-tier key hierarchy that protects secure notes.
 *
 *   master password -> PBKDF2 -> KEK -> unwraps DEK -> AES-GCM over each note
 *
 * The DEK is held in memory only while the session is unlocked. Calling [lock]
 * zeroes the in-memory DEK. Never log or serialise the DEK or plaintext notes.
 *
 * All persisted bytes (salt, IVs, wrapped DEK copies, verification blob) are
 * Base64-encoded with [Base64.NO_WRAP] in EncryptedSharedPreferences.
 */
class SecurityManager private constructor(appContext: Context) {

    private val storage = SecureStorage(appContext)

    @Volatile
    private var dek: ByteArray? = null
    private val dekLock = Any()

    companion object {
        private const val VERIFICATION_PLAINTEXT = "SAFEST_NOTES_V1"
        private const val METADATA_VERSION = 1

        // Rate limiting ladder: 30s, 2m, 10m, 1h, then wipe.
        private val LOCKOUT_SCHEDULE_MS = longArrayOf(
            30_000L,
            2 * 60_000L,
            10 * 60_000L,
            60 * 60_000L
        )
        val MAX_ATTEMPTS_BEFORE_WIPE = LOCKOUT_SCHEDULE_MS.size + 5
        const val ATTEMPTS_BEFORE_FIRST_LOCKOUT = 5

        @Volatile
        private var INSTANCE: SecurityManager? = null

        fun init(context: Context): SecurityManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityManager(context.applicationContext).also { INSTANCE = it }
            }

        fun get(): SecurityManager =
            INSTANCE ?: throw IllegalStateException(
                "SecurityManager.init(context) must be called from Application.onCreate()"
            )
    }

    // ─── Public state ─────────────────────────────────────────────────────────

    fun isMasterPasswordSet(): Boolean =
        storage.contains(SecureStorage.KEY_KEK_SALT) &&
            storage.contains(SecureStorage.KEY_DEK_WRAPPED_BY_KEK) &&
            storage.contains(SecureStorage.KEY_VERIFICATION_BLOB)

    fun isUnlocked(): Boolean = dek != null

    fun isBiometricEnabled(): Boolean =
        storage.getBoolean(SecureStorage.KEY_BIOMETRIC_ENABLED) &&
            BiometricKeyStore.hasKey() &&
            storage.contains(SecureStorage.KEY_DEK_BIOMETRIC_WRAPPED)

    // ─── Setup / change password ──────────────────────────────────────────────

    fun setupMasterPassword(password: CharArray): Result<Unit> = runCatching {
        require(password.size >= 8) { "Master password must be at least 8 characters" }
        check(!isMasterPasswordSet()) { "Master password already set" }

        val salt = KeyDerivation.generateSalt()
        val kek = KeyDerivation.deriveKek(password, salt)
        try {
            val freshDek = KeyDerivation.generateDek()
            try {
                // Wrap DEK under KEK.
                val wrappedDek = AesGcm.encrypt(kek, freshDek)
                // Known-plaintext verification blob.
                val verification = AesGcm.encrypt(kek, VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))

                storage.putString(SecureStorage.KEY_KEK_SALT, b64(salt))
                storage.putString(SecureStorage.KEY_VERIFICATION_BLOB, b64(verification.ciphertext))
                storage.putString(SecureStorage.KEY_VERIFICATION_IV, b64(verification.iv))
                storage.putString(SecureStorage.KEY_DEK_WRAPPED_BY_KEK, b64(wrappedDek.ciphertext))
                storage.putString(SecureStorage.KEY_DEK_WRAPPED_IV, b64(wrappedDek.iv))
                storage.putInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0)
                storage.putLong(SecureStorage.KEY_LOCKOUT_UNTIL, 0L)

                // Session is now unlocked — hold DEK.
                dek = freshDek.copyOf()
            } finally {
                freshDek.fill(0)
            }
        } finally {
            kek.fill(0)
        }
    }

    fun changeMasterPassword(old: CharArray, new: CharArray): Result<Unit> = runCatching {
        require(new.size >= 8) { "New master password must be at least 8 characters" }
        check(isMasterPasswordSet()) { "No master password is set" }

        val salt = b64d(storage.getString(SecureStorage.KEY_KEK_SALT)!!)
        val oldKek = KeyDerivation.deriveKek(old, salt)
        try {
            val currentDek = unwrapDekWithKek(oldKek) // throws on wrong password
            try {
                val newSalt = KeyDerivation.generateSalt()
                val newKek = KeyDerivation.deriveKek(new, newSalt)
                try {
                    val wrapped = AesGcm.encrypt(newKek, currentDek)
                    val verification = AesGcm.encrypt(newKek, VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))

                    storage.putString(SecureStorage.KEY_KEK_SALT, b64(newSalt))
                    storage.putString(SecureStorage.KEY_VERIFICATION_BLOB, b64(verification.ciphertext))
                    storage.putString(SecureStorage.KEY_VERIFICATION_IV, b64(verification.iv))
                    storage.putString(SecureStorage.KEY_DEK_WRAPPED_BY_KEK, b64(wrapped.ciphertext))
                    storage.putString(SecureStorage.KEY_DEK_WRAPPED_IV, b64(wrapped.iv))
                    storage.putInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0)
                    storage.putLong(SecureStorage.KEY_LOCKOUT_UNTIL, 0L)

                    // Biometric wrap is keyed to the DEK (not the KEK) so it
                    // does not need to be re-wrapped on password change.
                    // But we invalidate the failure counters.
                } finally {
                    newKek.fill(0)
                }
                // Update in-memory DEK just in case it differs.
                dek?.fill(0)
                dek = currentDek.copyOf()
            } finally {
                currentDek.fill(0)
            }
        } finally {
            oldKek.fill(0)
        }
    }

    /**
     * Disables secure notes completely: wipes all key material. Caller is
     * responsible for iterating existing secure notes and re-saving them as
     * plaintext *before* calling this.
     */
    fun disableMasterPassword() {
        lock()
        BiometricKeyStore.deleteKey()
        storage.clearAll()
    }

    // ─── Unlock ───────────────────────────────────────────────────────────────

    /** Milliseconds until next allowed unlock attempt, or 0 if unlocked. */
    fun lockoutRemainingMs(): Long {
        val until = storage.getLong(SecureStorage.KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) until - now else 0L
    }

    fun failedAttemptCount(): Int = storage.getInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0)

    fun unlock(password: CharArray): Result<Unit> = runCatching {
        check(isMasterPasswordSet()) { "No master password is set" }
        val remaining = lockoutRemainingMs()
        if (remaining > 0) {
            throw LockedOutException(remaining)
        }

        val salt = b64d(storage.getString(SecureStorage.KEY_KEK_SALT)!!)
        val kek = KeyDerivation.deriveKek(password, salt)
        try {
            // Constant-time verification.
            val ok = verifyKek(kek)
            if (!ok) {
                recordFailedAttempt()
                throw IncorrectPasswordException()
            }
            val unwrapped = unwrapDekWithKek(kek)
            dek?.fill(0)
            dek = unwrapped
            storage.putInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0)
            storage.putLong(SecureStorage.KEY_LOCKOUT_UNTIL, 0L)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * Unlock using a Cipher already authenticated by BiometricPrompt. Caller
     * passes the [Cipher] from [CryptoObject.getCipher].
     */
    fun unlockWithBiometric(cipher: Cipher): Result<Unit> = runCatching {
        check(isBiometricEnabled()) { "Biometric unlock not enabled" }
        val wrappedDek = b64d(storage.getString(SecureStorage.KEY_DEK_BIOMETRIC_WRAPPED)!!)
        val unwrapped = cipher.doFinal(wrappedDek)
        dek?.fill(0)
        dek = unwrapped
        storage.putInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0)
        storage.putLong(SecureStorage.KEY_LOCKOUT_UNTIL, 0L)
    }

    fun lock() {
        synchronized(dekLock) {
            dek?.fill(0)
            dek = null
        }
    }

    // ─── Biometric enroll ─────────────────────────────────────────────────────

    /**
     * Cipher for enrolling biometric protection. Must be authenticated by
     * BiometricPrompt before calling [enableBiometric].
     *
     * Enrolling requires the session to already be unlocked (we need plaintext
     * DEK to wrap).
     */
    fun getBiometricEnrollCipher(): Cipher {
        check(isUnlocked()) { "Session must be unlocked to enroll biometric" }
        BiometricKeyStore.createBiometricKey()
        return BiometricKeyStore.getEncryptCipher()
    }

    fun enableBiometric(cipher: Cipher) {
        val currentDek = dek ?: throw IllegalStateException("Not unlocked")
        val wrapped = cipher.doFinal(currentDek)
        val iv = cipher.iv ?: throw IllegalStateException("Cipher produced no IV")
        storage.putString(SecureStorage.KEY_DEK_BIOMETRIC_WRAPPED, b64(wrapped))
        storage.putString(SecureStorage.KEY_DEK_BIOMETRIC_IV, b64(iv))
        storage.putBoolean(SecureStorage.KEY_BIOMETRIC_ENABLED, true)
    }

    fun disableBiometric() {
        BiometricKeyStore.deleteKey()
        storage.remove(
            SecureStorage.KEY_DEK_BIOMETRIC_WRAPPED,
            SecureStorage.KEY_DEK_BIOMETRIC_IV
        )
        storage.putBoolean(SecureStorage.KEY_BIOMETRIC_ENABLED, false)
    }

    /** Cipher for unlocking. Hand to BiometricPrompt, then call [unlockWithBiometric]. */
    fun getBiometricUnlockCipher(): Cipher {
        check(isBiometricEnabled()) { "Biometric unlock not enabled" }
        val iv = b64d(storage.getString(SecureStorage.KEY_DEK_BIOMETRIC_IV)!!)
        return BiometricKeyStore.getDecryptCipher(iv)
    }

    /**
     * Called when BiometricPrompt reports KeyPermanentlyInvalidatedException —
     * user changed their biometrics. Clear the wrap so the app falls back to
     * password and the user can re-enroll.
     */
    fun onBiometricKeyInvalidated() {
        disableBiometric()
    }

    // ─── Note encryption ──────────────────────────────────────────────────────

    /**
     * Returns (ciphertext_b64, metadata_json). The metadata is intended to be
     * stored in [com.tezas.safestnotes.data.Note.secureMetadata]; the
     * ciphertext replaces the note's content.
     */
    fun encryptNote(plaintext: String): Pair<String, String> {
        // Copy DEK under lock so lock() can't zero it mid-operation
        val key = synchronized(dekLock) { dek?.copyOf() }
            ?: throw IllegalStateException("Vault is locked")
        return try {
            val blob = AesGcm.encrypt(key, plaintext.toByteArray(Charsets.UTF_8))
            val ctB64 = b64(blob.ciphertext)
            val meta = JSONObject()
                .put("v", METADATA_VERSION)
                .put("iv", b64(blob.iv))
                .toString()
            ctB64 to meta
        } finally {
            key.fill(0)
        }
    }

    fun decryptNote(ciphertextB64: String, metadataJson: String): String {
        val key = synchronized(dekLock) { dek?.copyOf() }
            ?: throw IllegalStateException("Vault is locked")
        return try {
            val meta = JSONObject(metadataJson)
            val iv = b64d(meta.getString("iv"))
            val ct = b64d(ciphertextB64)
            val pt = AesGcm.decrypt(key, iv, ct)
            String(pt, Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private fun unwrapDekWithKek(kek: ByteArray): ByteArray {
        val iv = b64d(storage.getString(SecureStorage.KEY_DEK_WRAPPED_IV)!!)
        val ct = b64d(storage.getString(SecureStorage.KEY_DEK_WRAPPED_BY_KEK)!!)
        return AesGcm.decrypt(kek, iv, ct)
    }

    /**
     * Decrypts the verification blob with the candidate KEK and checks the
     * plaintext against the expected marker in constant time. GCM already
     * authenticates, but the extra equality test is belt-and-braces and
     * uses [MessageDigest.isEqual] so no short-circuit compare leaks timing.
     */
    private fun verifyKek(kek: ByteArray): Boolean {
        return try {
            val iv = b64d(storage.getString(SecureStorage.KEY_VERIFICATION_IV)!!)
            val ct = b64d(storage.getString(SecureStorage.KEY_VERIFICATION_BLOB)!!)
            val pt = AesGcm.decrypt(kek, iv, ct)
            MessageDigest.isEqual(pt, VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            false
        }
    }

    private fun recordFailedAttempt() {
        val attempts = storage.getInt(SecureStorage.KEY_FAILED_ATTEMPTS, 0) + 1
        storage.putInt(SecureStorage.KEY_FAILED_ATTEMPTS, attempts)

        if (attempts >= MAX_ATTEMPTS_BEFORE_WIPE) {
            // Wipe all key material. Existing secure notes become
            // permanently unrecoverable — this is intended behavior.
            disableMasterPassword()
            throw WipedException()
        }

        if (attempts > ATTEMPTS_BEFORE_FIRST_LOCKOUT) {
            val idx = (attempts - ATTEMPTS_BEFORE_FIRST_LOCKOUT - 1)
                .coerceAtMost(LOCKOUT_SCHEDULE_MS.lastIndex)
            val penalty = LOCKOUT_SCHEDULE_MS[idx]
            storage.putLong(SecureStorage.KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + penalty)
        }
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    class IncorrectPasswordException : Exception("Incorrect password")
    class LockedOutException(val remainingMs: Long) : Exception("Locked out for $remainingMs ms")
    class WipedException : Exception("Too many failed attempts; vault wiped")
}
