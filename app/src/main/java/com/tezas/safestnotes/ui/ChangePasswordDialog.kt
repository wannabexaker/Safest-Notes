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
 * Change master password. Takes the old password (for verification + DEK
 * unwrap) and a new password. On success the DEK is re-wrapped under the new
 * KEK; note ciphertext is untouched.
 */
object ChangePasswordDialog {

    fun show(
        context: Context,
        securityManager: SecurityManager,
        onSuccess: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_change_password, null)
        val etOld = view.findViewById<TextInputEditText>(R.id.etOld)
        val etNew = view.findViewById<TextInputEditText>(R.id.etNew)
        val etConfirm = view.findViewById<TextInputEditText>(R.id.etConfirm)
        val tvError = view.findViewById<TextView>(R.id.tvError)

        val dialog = MaterialAlertDialogBuilder(context, R.style.SafestNotes_AlertDialog)
            .setTitle("Change Master Password")
            .setView(view)
            .setPositiveButton("Change", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val oldPw = etOld.text?.toString().orEmpty()
                val newPw = etNew.text?.toString().orEmpty()
                val confirm = etConfirm.text?.toString().orEmpty()

                if (newPw.length < 8) {
                    tvError.text = "New password must be at least 8 characters."
                    tvError.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }
                if (newPw != confirm) {
                    tvError.text = "New passwords do not match."
                    tvError.visibility = TextView.VISIBLE
                    return@setOnClickListener
                }

                val oldChars = oldPw.toCharArray()
                val newChars = newPw.toCharArray()
                val result = securityManager.changeMasterPassword(oldChars, newChars)
                oldChars.fill('\u0000')
                newChars.fill('\u0000')
                etOld.text?.clear()
                etNew.text?.clear()
                etConfirm.text?.clear()

                result.onSuccess {
                    dialog.dismiss()
                    onSuccess()
                }.onFailure { e ->
                    tvError.text = e.message ?: "Could not change password."
                    tvError.visibility = TextView.VISIBLE
                }
            }
        }
        dialog.show()
    }
}
