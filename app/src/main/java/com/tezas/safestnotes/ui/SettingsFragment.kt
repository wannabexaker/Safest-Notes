// File responsibility: Settings UI wiring and preference side effects.
package com.tezas.safestnotes.ui

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.export.Mp3ExportCodec
import com.tezas.safestnotes.export.Mp3ExportFlow
import com.tezas.safestnotes.security.SecurityManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.EditTextPreference

class SettingsFragment : PreferenceFragmentCompat() {

    private val securityManager by lazy { SecurityManager.get() }
    private val exportFlow = Mp3ExportFlow(this).also { it.register() }

    private val coverAudioPicker: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* SAF provider may not support persistence */ }
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                .putString(Mp3ExportCodec.PREF_COVER_AUDIO, Mp3ExportCodec.COVER_CUSTOM)
                .putString(Mp3ExportCodec.PREF_COVER_AUDIO_URI, uri.toString())
                .apply()
            refreshCoverAudioSummary()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // Dark mode toggle
        findPreference<SwitchPreferenceCompat>("dark_mode")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, v ->
            AppCompatDelegate.setDefaultNightMode(
                if (v as Boolean) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            true
        }

        // Navigation / grid changes → recreate
        findPreference<ListPreference>("nav_style")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            activity?.recreate(); true
        }
        findPreference<ListPreference>("grid_columns")
            ?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            activity?.recreate(); true
        }

        // Clear Recycle Bin
        findPreference<Preference>("action_clear_recycle_bin")
            ?.setOnPreferenceClickListener {
                confirmClearRecycleBin()
                true
            }

        // MP3 export / import
        findPreference<Preference>("action_export_mp3")?.setOnPreferenceClickListener {
            exportFlow.startExport(); true
        }
        findPreference<Preference>("action_import_mp3")?.setOnPreferenceClickListener {
            exportFlow.startImport(); true
        }
        findPreference<Preference>(Mp3ExportCodec.PREF_COVER_AUDIO)?.setOnPreferenceClickListener {
            showCoverAudioPicker(); true
        }
        refreshCoverAudioSummary()

        // About
        findPreference<Preference>("action_about")?.apply {
            val version = try {
                requireContext().packageManager
                    .getPackageInfo(requireContext().packageName, 0).versionName
            } catch (_: PackageManager.NameNotFoundException) { "?" }
            summary = "Version $version"
            setOnPreferenceClickListener {
                showAboutDialog(version)
                true
            }
        }

        wireMasterPasswordPref()
        wireBiometricPref()
        wireApiKeyPref()
    }

    override fun onResume() {
        super.onResume()
        refreshMasterPasswordSummary()
        refreshBiometricState()
    }

    // ─── Cover Audio ─────────────────────────────────────────────────────────

    private fun showCoverAudioPicker() {
        val labels = arrayOf(
            "Silence (default, 12 KB)",
            "Ambient pink noise (72 KB)",
            "Custom audio file…"
        )
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Cover audio for MP3 export")
            .setItems(labels) { _, which ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                when (which) {
                    0 -> {
                        prefs.edit()
                            .putString(Mp3ExportCodec.PREF_COVER_AUDIO, Mp3ExportCodec.COVER_SILENCE)
                            .apply()
                        refreshCoverAudioSummary()
                    }
                    1 -> {
                        prefs.edit()
                            .putString(Mp3ExportCodec.PREF_COVER_AUDIO, Mp3ExportCodec.COVER_PINK)
                            .apply()
                        refreshCoverAudioSummary()
                    }
                    2 -> coverAudioPicker.launch(arrayOf("audio/mpeg", "audio/*"))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshCoverAudioSummary() {
        val pref = findPreference<Preference>(Mp3ExportCodec.PREF_COVER_AUDIO) ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val choice = prefs.getString(Mp3ExportCodec.PREF_COVER_AUDIO, Mp3ExportCodec.COVER_SILENCE)
        pref.summary = when (choice) {
            Mp3ExportCodec.COVER_PINK -> "Ambient pink noise"
            Mp3ExportCodec.COVER_CUSTOM -> {
                val uri = prefs.getString(Mp3ExportCodec.PREF_COVER_AUDIO_URI, null)
                "Custom: ${uri?.substringAfterLast('/')?.take(40) ?: "(missing)"}"
            }
            else -> "Silence (default)"
        }
    }

    // ─── Recycle Bin ─────────────────────────────────────────────────────────

    private fun confirmClearRecycleBin() {
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Empty Recycle Bin?")
            .setMessage("All trashed notes will be permanently deleted. This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ -> emptyRecycleBin() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun emptyRecycleBin() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = NotesDatabase.getDatabase(appContext)
                val repo = NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao())
                val deleted = db.noteDao().getAllNotes().first().filter { it.isDeleted }
                deleted.forEach { repo.delete(it) }
            }
            val count = withContext(Dispatchers.IO) {
                NotesDatabase.getDatabase(appContext).noteDao().getAllNotes().first().count { it.isDeleted }
            }
            Toast.makeText(requireContext(), "Recycle Bin emptied", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Master password ──────────────────────────────────────────────────────

    private fun wireMasterPasswordPref() {
        val pref = findPreference<Preference>("master_password") ?: return
        pref.setOnPreferenceClickListener {
            if (!securityManager.isMasterPasswordSet()) {
                MasterPasswordSetupDialog.show(requireContext(), securityManager,
                    onSuccess = {
                        Toast.makeText(requireContext(), "Master password set", Toast.LENGTH_SHORT).show()
                        refreshMasterPasswordSummary()
                        refreshBiometricState()
                    }
                )
            } else {
                showMasterPasswordOptions()
            }
            true
        }
    }

    private fun refreshMasterPasswordSummary() {
        val pref = findPreference<Preference>("master_password") ?: return
        if (securityManager.isMasterPasswordSet()) {
            pref.title = "Master Password"
            pref.summary = "Tap to change or disable"
        } else {
            pref.title = "Set Master Password"
            pref.summary = "Protect your secure notes"
        }
    }

    private fun showMasterPasswordOptions() {
        val options = arrayOf("Change password", "Disable secure notes")
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Master Password")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> ChangePasswordDialog.show(requireContext(), securityManager) {
                        Toast.makeText(requireContext(), "Password changed", Toast.LENGTH_SHORT).show()
                    }
                    1 -> confirmDisableSecureNotes()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDisableSecureNotes() {
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Disable secure notes?")
            .setMessage(
                "This will decrypt all secure notes back to plaintext and remove your " +
                    "master password. You must be unlocked to do this."
            )
            .setPositiveButton("Disable") { _, _ ->
                if (!securityManager.isUnlocked()) {
                    val act = activity as? FragmentActivity ?: return@setPositiveButton
                    UnlockDialog.show(act, securityManager, onUnlocked = { performDisable() })
                } else {
                    performDisable()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDisable() {
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val db = NotesDatabase.getDatabase(appContext)
                val repo = NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao())
                val allNotes = db.noteDao().getAllNotes().first()
                allNotes.filter { it.isSecure }.forEach { note ->
                    val meta = note.secureMetadata
                    if (!meta.isNullOrBlank()) {
                        try {
                            val plaintext = securityManager.decryptNote(note.content, meta)
                            repo.update(note.copy(isSecure = false, secureMetadata = null, content = plaintext))
                        } catch (_: Exception) {
                            // If decryption fails, strip the isSecure flag but leave content as-is —
                            // the note is already lost but we don't want to orphan the flag.
                            repo.update(note.copy(isSecure = false, secureMetadata = null))
                        }
                    } else {
                        repo.update(note.copy(isSecure = false))
                    }
                }
            }
            securityManager.disableMasterPassword()
            findPreference<SwitchPreferenceCompat>("biometric_unlock")?.isChecked = false
            refreshMasterPasswordSummary()
            refreshBiometricState()
            Toast.makeText(requireContext(), "Secure notes disabled", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Biometric ────────────────────────────────────────────────────────────

    private fun wireBiometricPref() {
        val pref = findPreference<SwitchPreferenceCompat>("biometric_unlock") ?: return
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            if (enable) {
                onEnableBiometric(pref)
                false // wait for async result to flip the toggle
            } else {
                securityManager.disableBiometric()
                true
            }
        }
    }

    private fun refreshBiometricState() {
        val pref = findPreference<SwitchPreferenceCompat>("biometric_unlock") ?: return
        val pwSet = securityManager.isMasterPasswordSet()
        val canAuth = BiometricManager.from(requireContext())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        pref.isEnabled = pwSet && canAuth
        pref.isChecked = securityManager.isBiometricEnabled()
        pref.summary = when {
            !pwSet -> "Set a master password first"
            !canAuth -> "No biometrics enrolled on this device"
            pref.isChecked -> "Biometric unlock is enabled"
            else -> "Requires master password set and biometrics enrolled"
        }
    }

    private fun onEnableBiometric(pref: SwitchPreferenceCompat) {
        if (!securityManager.isMasterPasswordSet()) {
            Toast.makeText(requireContext(), "Set a master password first", Toast.LENGTH_SHORT).show()
            return
        }
        val act = activity as? FragmentActivity ?: return
        if (!securityManager.isUnlocked()) {
            UnlockDialog.show(act, securityManager, onUnlocked = { enrollBiometric(act, pref) })
        } else {
            enrollBiometric(act, pref)
        }
    }

    private fun enrollBiometric(activity: FragmentActivity, pref: SwitchPreferenceCompat) {
        val cipher = try {
            securityManager.getBiometricEnrollCipher()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not create biometric key: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return
                    try {
                        securityManager.enableBiometric(c)
                        pref.isChecked = true
                        refreshBiometricState()
                        Toast.makeText(requireContext(), "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                    } catch (e: KeyPermanentlyInvalidatedException) {
                        securityManager.onBiometricKeyInvalidated()
                        Toast.makeText(requireContext(), "Biometric key invalidated", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Enroll failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Clean up the half-created key so the toggle can be re-tried.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(requireContext(), "Auth error: $errString", Toast.LENGTH_SHORT).show()
                    }
                    securityManager.disableBiometric()
                    pref.isChecked = false
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable biometric unlock")
            .setSubtitle("Confirm your identity to bind biometrics to your vault")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // ─── OpenAI API Key ───────────────────────────────────────────────────────

    private fun wireApiKeyPref() {
        val pref = findPreference<EditTextPreference>("pref_openai_api_key") ?: return

        // Mask the stored key in the summary — show only the last 4 chars
        fun updateSummary() {
            val key = pref.text?.trim() ?: ""
            pref.summary = when {
                key.isBlank() -> "Not set — tap to enter your OpenAI API key"
                key.length <= 8 -> "sk-…${key.takeLast(4)}"
                else -> "sk-…${key.takeLast(4)}  (configured)"
            }
        }
        updateSummary()

        // Mask input as password but keep it retrievable
        pref.setOnBindEditTextListener { editText ->
            editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            requireView().post { updateSummary() }
            true
        }

        findPreference<Preference>("action_openai_key_help")?.setOnPreferenceClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://platform.openai.com/api-keys")
            )
            startActivity(intent)
            true
        }
    }

    // ─── About ────────────────────────────────────────────────────────────────

    private fun showAboutDialog(version: String) {
        val message = buildString {
            appendLine("🔒  End-to-end encrypted note-taking")
            appendLine()
            appendLine("SafestNotes keeps your thoughts private with AES-256-GCM encryption. Notes in the Secure Vault are never stored in plaintext.")
            appendLine()
            appendLine("Features")
            appendLine("• Rich-text editor with formatting toolbar")
            appendLine("• Secure Vault with biometric unlock")
            appendLine("• Folders with color labels and nesting")
            appendLine("• Favorites & 30-day Recycle Bin")
            appendLine("• Auto-lock on background")
            appendLine("• Text-to-speech reader")
            appendLine("• Note history & revisions")
            appendLine()
            append("Version $version  ·  Built with ❤️")
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("SafestNotes")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }
}
