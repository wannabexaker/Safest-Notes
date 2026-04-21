package com.tezas.safestnotes.ui

import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tezas.safestnotes.R
import com.tezas.safestnotes.security.SecurityManager

/**
 * Single-password unlock dialog with optional biometric path.
 *
 * Shows lockout state (read from [SecurityManager.lockoutRemainingMs]) and
 * disables input while counting down. On success invokes [onUnlocked].
 */
object UnlockDialog {

    /**
     * If biometric is enrolled and the hardware is ready, immediately launches the
     * biometric prompt without showing the password dialog first.
     *   - On success → [onUnlocked]
     *   - On "Use password" tap or any error → falls back to [show]
     *   - If biometric not available → goes straight to [show]
     */
    fun showWithBiometricFirst(
        activity: FragmentActivity,
        securityManager: SecurityManager,
        onUnlocked: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val biometricAvailable = securityManager.isBiometricEnabled() &&
            BiometricManager.from(activity).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!biometricAvailable) {
            show(activity, securityManager, onUnlocked, onCancel)
            return
        }

        val cipher = try {
            securityManager.getBiometricUnlockCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            securityManager.onBiometricKeyInvalidated()
            show(activity, securityManager, onUnlocked, onCancel)
            return
        } catch (e: Exception) {
            show(activity, securityManager, onUnlocked, onCancel)
            return
        }

        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: run {
                        show(activity, securityManager, onUnlocked, onCancel)
                        return
                    }
                    securityManager.unlockWithBiometric(c)
                        .onSuccess { onUnlocked() }
                        .onFailure { show(activity, securityManager, onUnlocked, onCancel) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Any error or explicit cancellation: fall back to password dialog
                    // so the user always has a way in.
                    if (errorCode == BiometricPrompt.ERROR_CANCELED) {
                        // System-level cancel (e.g. screen-off) — don't open dialog on top
                        onCancel()
                    } else {
                        show(activity, securityManager, onUnlocked, onCancel)
                    }
                }
            }
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Secure Notes")
                .setSubtitle("Use your biometric to access the vault")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    fun show(
        activity: FragmentActivity,
        securityManager: SecurityManager,
        onUnlocked: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_unlock, null)
        val etPw = view.findViewById<TextInputEditText>(R.id.etPassword)
        val tvError = view.findViewById<TextView>(R.id.tvError)
        val tvMessage = view.findViewById<TextView>(R.id.tvMessage)
        val btnFingerprint = view.findViewById<MaterialButton>(R.id.btnFingerprint)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.SafestNotes_AlertDialog)
            .setTitle("Unlock Secure Notes")
            .setView(view)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        val biometricAvailable = securityManager.isBiometricEnabled() &&
            BiometricManager.from(activity).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS

        if (biometricAvailable) {
            btnFingerprint.visibility = MaterialButton.VISIBLE
            btnFingerprint.setOnClickListener {
                launchBiometric(activity, securityManager, tvError) {
                    dialog.dismiss()
                    onUnlocked()
                }
            }
        }

        val handler = Handler(Looper.getMainLooper())
        lateinit var tick: Runnable
        var countdownActive = false

        fun refreshLockout() {
            val remaining = securityManager.lockoutRemainingMs()
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (remaining > 0) {
                countdownActive = true
                val seconds = (remaining + 999) / 1000
                tvError.text = "Too many failed attempts. Try again in ${formatDuration(seconds)}."
                tvError.visibility = TextView.VISIBLE
                etPw.isEnabled = false
                positiveBtn?.isEnabled = false
                btnFingerprint.isEnabled = false
                handler.postDelayed(tick, 1000)
            } else if (countdownActive) {
                countdownActive = false
                tvError.visibility = TextView.GONE
                etPw.isEnabled = true
                positiveBtn?.isEnabled = true
                btnFingerprint.isEnabled = true
            }
        }
        tick = Runnable { refreshLockout() }

        dialog.setOnShowListener {
            refreshLockout()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (securityManager.lockoutRemainingMs() > 0) {
                    refreshLockout()
                    return@setOnClickListener
                }
                val pw = etPw.text?.toString().orEmpty()
                if (pw.isEmpty()) {
                    tvError.text = "Enter your password."
                    tvError.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }
                val pwChars = pw.toCharArray()
                val result = securityManager.unlock(pwChars)
                pwChars.fill('\u0000')
                etPw.text?.clear()

                result.onSuccess {
                    dialog.dismiss()
                    onUnlocked()
                }.onFailure { e ->
                    when (e) {
                        is SecurityManager.LockedOutException -> refreshLockout()
                        is SecurityManager.WipedException -> {
                            tvError.text = "Too many failed attempts. Secure notes have been wiped."
                            tvError.visibility = TextView.VISIBLE
                            etPw.isEnabled = false
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        }
                        is SecurityManager.IncorrectPasswordException -> {
                            tvError.text = "Incorrect password. Attempts: ${securityManager.failedAttemptCount()}"
                            tvError.visibility = TextView.VISIBLE
                            refreshLockout()
                        }
                        else -> {
                            tvError.text = e.message ?: "Unlock failed."
                            tvError.visibility = TextView.VISIBLE
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
    }

    private fun launchBiometric(
        activity: FragmentActivity,
        securityManager: SecurityManager,
        tvError: TextView,
        onUnlocked: () -> Unit
    ) {
        val cipher = try {
            securityManager.getBiometricUnlockCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            securityManager.onBiometricKeyInvalidated()
            tvError.text = "Biometrics changed. Please unlock with your password."
            tvError.visibility = TextView.VISIBLE
            return
        } catch (e: Exception) {
            securityManager.onBiometricKeyInvalidated()
            tvError.text = "Biometric unavailable. Please use your password."
            tvError.visibility = TextView.VISIBLE
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c == null) {
                        tvError.text = "Biometric auth returned no cipher."
                        tvError.visibility = TextView.VISIBLE
                        return
                    }
                    securityManager.unlockWithBiometric(c)
                        .onSuccess { onUnlocked() }
                        .onFailure { e ->
                            tvError.text = e.message ?: "Unlock failed."
                            tvError.visibility = TextView.VISIBLE
                        }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) return
                    tvError.text = errString.toString()
                    tvError.visibility = TextView.VISIBLE
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Secure Notes")
            .setSubtitle("Authenticate to decrypt your notes")
            .setNegativeButtonText("Use password")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m >= 60 -> "${m / 60}h ${m % 60}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}
