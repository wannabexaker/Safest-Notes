package com.tezas.safestnotes.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.tezas.safestnotes.R
import com.tezas.safestnotes.security.SecurityManager

/**
 * Two-field "set master password" dialog. Validates length >= 8 and match.
 *
 * On success calls [onSuccess] on the caller's thread. Passwords are handled
 * as CharArrays and zeroed immediately after use.
 */
object MasterPasswordSetupDialog {

    fun show(
        context: Context,
        securityManager: SecurityManager,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_master_password_setup, null)
        val etPw = view.findViewById<TextInputEditText>(R.id.etPassword)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etPasswordConfirm)
        val tvError = view.findViewById<TextView>(R.id.tvError)

        val dialog = MaterialAlertDialogBuilder(context, R.style.SafestNotes_AlertDialog)
            .setTitle("Set Master Password")
            .setView(view)
            .setPositiveButton("Set", null)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = etPw.text?.toString().orEmpty()
                val confirm = etConfirm.text?.toString().orEmpty()

                if (pw.length < 8) {
                    tvError.text = "Password must be at least 8 characters."
                    tvError.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }
                if (pw != confirm) {
                    tvError.text = "Passwords do not match."
                    tvError.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }

                val pwChars = pw.toCharArray()
                val result = securityManager.setupMasterPassword(pwChars)
                pwChars.fill('\u0000')
                // Best-effort wipe the EditText buffers.
                etPw.text?.clear()
                etConfirm.text?.clear()

                result.onSuccess {
                    dialog.dismiss()
                    onSuccess()
                }.onFailure { e ->
                    tvError.text = e.message ?: "Could not set password."
                    tvError.visibility = TextView.VISIBLE
                }
            }
        }
        dialog.show()
    }
}
