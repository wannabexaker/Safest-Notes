package com.tezas.safestnotes.ui

// Responsibility: Handles widget/shortcut quick-capture actions.
// Modes: ACTION_NEW_NOTE (opens editor), ACTION_DICTATE (STT → new note, no UI).

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transparent trampoline activity used by the Quick Capture widget and tile.
 *
 * Modes (set via intent action):
 *   ACTION_NEW_NOTE  → opens AddEditNoteActivity with auto-metadata injected
 *   ACTION_DICTATE   → launches STT, saves result as new note, then closes
 */
class QuickCaptureActivity : AppCompatActivity() {

    private val viewModel: NotesViewModel by viewModels {
        val db = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao()))
    }

    private val sttLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim() ?: run { finish(); return@registerForActivityResult }
            if (text.isNotEmpty()) {
                saveQuickNote(text)
            } else {
                finish()
            }
        } else {
            finish()
        }
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchStt()
        else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No layout — fully transparent trampoline.
        when (intent.action) {
            ACTION_DICTATE  -> handleDictate()
            ACTION_NEW_NOTE -> handleNewNote()
            else            -> handleNewNote()
        }
    }

    // ── New Note ──────────────────────────────────────────────────────────────

    private fun handleNewNote() {
        val editorIntent = Intent(this, AddEditNoteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_QUICK_CAPTURE, true)
        }
        startActivity(editorIntent)
        finish()
    }

    // ── Dictate → save silently ───────────────────────────────────────────────

    private fun handleDictate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchStt()
        }
    }

    private fun launchStt() {
        val lang = Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate your note…")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            sttLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveQuickNote(rawText: String) {
        lifecycleScope.launch {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@QuickCaptureActivity)
            val content = buildContent(rawText, prefs)
            val now = System.currentTimeMillis()
            val note = Note(
                title  = buildTitle(prefs, now),
                content = content,
                timestamp = now,
                createdTimestamp = now
            )
            withContext(Dispatchers.IO) { viewModel.addNoteAwait(note) }
            Toast.makeText(this@QuickCaptureActivity, "Note saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildTitle(prefs: android.content.SharedPreferences, now: Long): String {
        val addTs = prefs.getBoolean("pref_quick_timestamp", true)
        if (!addTs) return ""
        val fmt = prefs.getString("pref_quick_timestamp_format", "datetime") ?: "datetime"
        return formatTimestamp(now, fmt)
    }

    private fun buildContent(
        text: String,
        prefs: android.content.SharedPreferences
    ): String {
        val sb = StringBuilder()
        if (prefs.getBoolean("pref_quick_gps", false)) {
            val gps = getLastKnownLocation()
            if (gps != null) {
                sb.append("📍 ${"%.5f".format(gps.latitude)}, ${"%.5f".format(gps.longitude)}<br>")
            }
        }
        // Wrap plain text in a paragraph for the rich editor
        sb.append("<p>${android.text.Html.escapeHtml(text)}</p>")
        return sb.toString()
    }

    private fun getLastKnownLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    }

    companion object {
        const val ACTION_NEW_NOTE    = "com.tezas.safestnotes.ACTION_QUICK_NOTE"
        const val ACTION_DICTATE     = "com.tezas.safestnotes.ACTION_QUICK_DICTATE"
        const val EXTRA_QUICK_CAPTURE = "quick_capture"

        fun formatTimestamp(ms: Long, format: String): String {
            val sdf = when (format) {
                "date"     -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                "time"     -> SimpleDateFormat("HH:mm", Locale.getDefault())
                else       -> SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
            }
            return sdf.format(Date(ms))
        }
    }
}
