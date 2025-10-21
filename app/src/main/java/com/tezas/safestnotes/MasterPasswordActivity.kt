package com.tezas.safestnotes

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class MasterPasswordActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_master_password)

        sharedPreferences = getSharedPreferences("safest_notes_prefs", Context.MODE_PRIVATE)

        val passwordInput = findViewById<EditText>(R.id.password_input)
        val confirmButton = findViewById<Button>(R.id.confirm_button)

        val isPasswordSet = sharedPreferences.contains("master_password_hash")
        if (isPasswordSet) {
            passwordInput.hint = "Enter Master Password"
            confirmButton.text = "Unlock"
        } else {
            passwordInput.hint = "Set a new Master Password"
            confirmButton.text = "Set Password"
        }

        confirmButton.setOnClickListener {
            val password = passwordInput.text.toString()
            if (password.isNotBlank()) {
                if (isPasswordSet) {
                    verifyPassword(password)
                } else {
                    setPassword(password)
                }
            } else {
                Toast.makeText(this, "Password cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setPassword(password: String) {
        val hash = hashString(password)
        sharedPreferences.edit().putString("master_password_hash", hash).apply()
        Toast.makeText(this, "Master Password set successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun verifyPassword(password: String) {
        val storedHash = sharedPreferences.getString("master_password_hash", null)
        val inputHash = hashString(password)

        if (storedHash == inputHash) {
            // In a real app, you would pass the key back or unlock the content
            Toast.makeText(this, "Unlocked!", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}