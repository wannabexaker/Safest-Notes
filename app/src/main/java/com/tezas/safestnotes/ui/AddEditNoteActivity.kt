// File responsibility: Add/edit note screen behavior and persistence.
// Feature area: New note crash fix, editor focus behavior, preferences.
package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.speech.RecognizerIntent
import android.graphics.Color
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.security.SecurityManager
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AddEditNoteActivity : AppCompatActivity() {

    private lateinit var titleEdit: EditText
    private lateinit var richEditor: CustomRichEditor
    private lateinit var savingOverlay: LinearLayout
    private lateinit var savingOverlayText: TextView
    private lateinit var wordCountBar: TextView
    private var currentNote: Note? = null
    private var initialNoteState: Note? = null
    private var allFolders: List<Folder> = emptyList()
    private var fixedFolderId: Int? = null
    private var isFormattingVisible: Boolean = true
    private var isReadMode: Boolean = false
    private var isSavingAndClosing = false

    // ── TTS ──────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPlaying = false
    private var ttsBar: View? = null
    private var ttsSpeedBtn: TextView? = null

    // Settings — persisted in SharedPrefs
    private val TTS_PREF_SPEED   = "tts_speed_index"
    private val TTS_PREF_PITCH   = "tts_pitch_index"
    private val TTS_PREF_LANG    = "tts_lang_mode"

    private val ttsSpeeds       = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val ttsSpeedLabels  = arrayOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×")
    private val ttsPitches      = floatArrayOf(0.7f, 1.0f, 1.3f)
    private val ttsPitchLabels  = arrayOf("Low", "Normal", "High")
    // Language mode: 0=Auto, 1=Greek, 2=English
    private val ttsLangLabels   = arrayOf("Auto-detect", "Ελληνικά", "English")
    private val ttsLangLocales  = arrayOf(null, Locale("el", "GR"), Locale.ENGLISH) // null = auto

    private var ttsSpeedIndex = 2  // default 1×
    private var ttsPitchIndex = 1  // default Normal
    private var ttsLangMode   = 0  // default Auto

    // Greek Unicode ranges for language detection
    private val greekRanges = listOf(0x0370..0x03FF, 0x1F00..0x1FFF)

    // ── STT (Whisper) ─────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var sttAudioFile: java.io.File? = null
    private var isRecording = false
    private var sttBar: View? = null
    private var sttStatusText: TextView? = null
    private var sttRecordDot: View? = null
    private var recordDotAnimator: ObjectAnimator? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission needed for dictation", Toast.LENGTH_SHORT).show()
    }

    // Fallback: Google built-in speech recogniser (used when no OpenAI key is configured)
    private val sttLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.trim() ?: return@registerForActivityResult
            if (text.isNotEmpty()) insertTextAtCursor(text)
        }
    }

    private val viewModel: NotesViewModel by viewModels {
        val database = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(database.noteDao(), database.folderDao(), database.noteRevisionDao()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_note)

        val toolbar: Toolbar = findViewById(R.id.editor_top_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        titleEdit = findViewById(R.id.titleEdit)
        richEditor = findViewById(R.id.richEditor)
        savingOverlay = findViewById(R.id.saving_overlay)
        savingOverlayText = findViewById(R.id.saving_overlay_text)
        wordCountBar = findViewById(R.id.word_count_bar)

        // Back press → async save then finish (never fire-and-forget for close)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                performSaveAndClose()
            }
        })

        richEditor.settings.javaScriptEnabled = true
        richEditor.settings.domStorageEnabled = true

        // Dark theme for the WebView editor
        richEditor.setOnInitialLoadListener { isReady ->
            if (isReady) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val fontSize = prefs.getString("pref_editor_font_size", "16")?.toIntOrNull() ?: 16
                val showWordCount = prefs.getBoolean("pref_show_word_count", true)

                richEditor.setEditorBackgroundColor(0xFF1E1E2E.toInt())
                richEditor.setEditorFontColor(0xFFE8E8F0.toInt())
                richEditor.setEditorFontSize(fontSize)

                // Live word/char counter (gated by preference)
                if (showWordCount) {
                    wordCountBar.visibility = View.VISIBLE
                    richEditor.setOnTextChangeListener { html ->
                        val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY)
                            .toString().trim()
                        if (plain.isEmpty()) {
                            wordCountBar.text = ""
                        } else {
                            val words = plain.split(Regex("\\s+")).count { it.isNotEmpty() }
                            val chars = plain.replace(Regex("\\s"), "").length
                            wordCountBar.text = "$words words · $chars chars"
                        }
                    }
                } else {
                    wordCountBar.visibility = View.GONE
                }

                // Inject CSS: padding, line-height, font, caret colour
                richEditor.exec("""javascript:(function(){
                    var s = document.createElement('style');
                    s.textContent = '#editor { padding: 20px; line-height: 1.7; font-family: "Roboto", sans-serif; caret-color: #A78BFA; word-break: break-word; }';
                    document.head.appendChild(s);
                })()""")
            }
        }

        richEditor.setOnTouchListener { v, event ->
            if (isReadMode) {
                return@setOnTouchListener false
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(richEditor, InputMethodManager.SHOW_IMPLICIT)
            }
            false
        }

        setupToolbarActions()
        setupFormattingPanel()
        setupTtsBar()
        setupSttBar()
        setupImeInsets()
        loadNoteData()
    }

    private fun setupToolbarActions() {
        // ── Group 0: Quick Actions ─────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_undo).setOnClickListener          { richEditor.undo() }
        findViewById<ImageButton>(R.id.action_redo).setOnClickListener          { richEditor.redo() }
        findViewById<ImageButton>(R.id.action_insert_location).apply {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@AddEditNoteActivity)
            visibility = if (prefs.getBoolean("pref_quick_gps_editor_btn", true)) View.VISIBLE else View.GONE
            setOnClickListener { insertGpsLocation() }
        }

        // ── Group 1: Dictation ─────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_dictate).setOnClickListener       { startDictation() }
        findViewById<ImageButton>(R.id.action_read_aloud_toolbar).setOnClickListener { toggleReadAloud() }

        // ── Group 1: Text Style ────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_bold).setOnClickListener          { richEditor.setBold() }
        findViewById<ImageButton>(R.id.action_italic).setOnClickListener        { richEditor.setItalic() }
        findViewById<ImageButton>(R.id.action_underline).setOnClickListener     { richEditor.setUnderline() }
        findViewById<ImageButton>(R.id.action_strikethrough).setOnClickListener { richEditor.setStrikeThrough() }
        findViewById<ImageButton>(R.id.action_superscript).setOnClickListener   { richEditor.setSuperscript() }
        findViewById<ImageButton>(R.id.action_subscript).setOnClickListener     { richEditor.setSubscript() }

        // ── Group 2: Alignment ────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_align_left).setOnClickListener    { richEditor.setAlignLeft() }
        findViewById<ImageButton>(R.id.action_align_center).setOnClickListener  { richEditor.setAlignCenter() }
        findViewById<ImageButton>(R.id.action_align_right).setOnClickListener   { richEditor.setAlignRight() }

        // ── Group 3: Lists ────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_bullet).setOnClickListener        { richEditor.setBullets() }
        findViewById<ImageButton>(R.id.action_numbered).setOnClickListener      { richEditor.setNumbers() }
        findViewById<ImageButton>(R.id.action_checkbox).setOnClickListener      { richEditor.exec("javascript:RE.setTodo()") }

        // ── Group 4: Headings (TextView, not ImageButton) ─────────────────
        findViewById<TextView>(R.id.action_h1).setOnClickListener               { richEditor.setHeading(1) }
        findViewById<TextView>(R.id.action_h2).setOnClickListener               { richEditor.setHeading(2) }
        findViewById<TextView>(R.id.action_h3).setOnClickListener               { richEditor.setHeading(3) }

        // ── Group 5: Indent ───────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_indent).setOnClickListener        { richEditor.setIndent() }
        findViewById<ImageButton>(R.id.action_outdent).setOnClickListener       { richEditor.setOutdent() }

        // ── Group 6: Color & Size ─────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_txt_color).setOnClickListener     { showColorSelectionDialog(isText = true) }
        findViewById<ImageButton>(R.id.action_bg_color).setOnClickListener      { showColorSelectionDialog(isText = false) }
        findViewById<ImageButton>(R.id.action_font_size).setOnClickListener     { showFontSizeDialog() }

        // ── Group 7: Misc ─────────────────────────────────────────────────
        findViewById<ImageButton>(R.id.action_hr).setOnClickListener            { richEditor.exec("javascript:RE.insertHTML('<hr style=\"border:none;border-top:1px solid #44445A;margin:12px 0;\"/>')") }
        findViewById<ImageButton>(R.id.action_clear_format).setOnClickListener  { richEditor.removeFormat() }
    }

    private fun loadNoteData(){
        // Treat non-positive IDs as "new note" to avoid invalid lookups from default extras.
        val noteId = intent.getIntExtra("note_id", -1).takeIf { it > 0 }
        val folderIdFromIntent = intent.getIntExtra(EXTRA_FOLDER_ID, -1).takeIf { it > 0 }
        fixedFolderId = folderIdFromIntent

        lifecycleScope.launch {
            allFolders = viewModel.folders.first()
            if (noteId != null) {
                currentNote = viewModel.getNoteById(noteId)
                if (currentNote == null) {
                    // Treat missing notes as new to avoid invalid IDs crashing the editor.
                    currentNote = null
                    initialNoteState = null
                    richEditor.html = ""
                    fixedFolderId = folderIdFromIntent
                    applyFocusBehavior(isExistingNote = false)
                } else {
                    val note = currentNote!!
                    if (note.isSecure) {
                        val sm = SecurityManager.get()
                        if (!sm.isUnlocked()) {
                            // Biometric-first unlock before showing content
                            UnlockDialog.showWithBiometricFirst(
                                this@AddEditNoteActivity,
                                sm,
                                onUnlocked = {
                                    loadDecryptedNote(note)
                                },
                                onCancel = { finish() }
                            )
                            return@launch
                        }
                        loadDecryptedNote(note)
                    } else {
                        initialNoteState = note.copy()
                        titleEdit.setText(note.title)
                        richEditor.html = note.content
                        fixedFolderId = note.folderId
                        invalidateOptionsMenu()
                        applyFocusBehavior(isExistingNote = true)
                    }
                }
            } else {
                currentNote = null
                initialNoteState = null
                fixedFolderId = folderIdFromIntent
                // Inject quick-capture metadata: timestamp and GPS go into the
                // title field so the body starts empty and typing is unobstructed.
                injectQuickCaptureTitle()
                // If opened from a secure folder, pre-mark note as secure
                if (intent.getBooleanExtra(EXTRA_IS_SECURE_FOLDER, false)) {
                    val sm = SecurityManager.get()
                    if (sm.isMasterPasswordSet()) {
                        if (!sm.isUnlocked()) {
                            UnlockDialog.showWithBiometricFirst(this@AddEditNoteActivity, sm, onUnlocked = {
                                currentNote = Note(isSecure = true)
                                invalidateOptionsMenu()
                            }, onCancel = { finish() })
                        } else {
                            currentNote = Note(isSecure = true)
                            invalidateOptionsMenu()
                        }
                    }
                }
                // Apply focus once views are ready for a clean new-note experience.
                applyFocusBehavior(isExistingNote = false)
            }
        }
    }

    private fun showColorSelectionDialog(isText: Boolean) {
        if (isText) {
            // Text colors — bright/vivid, readable on dark background
            val colors = arrayOf("White", "Light Gray", "Purple", "Cyan", "Lime", "Yellow", "Orange", "Coral")
            val colorValues = intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFCCCCCC.toInt(), 0xFFA78BFA.toInt(),
                0xFF67E8F9.toInt(), 0xFF86EFAC.toInt(), 0xFFFBBF24.toInt(),
                0xFFFB923C.toInt(), 0xFFFC8181.toInt()
            )
            AlertDialog.Builder(this)
                .setTitle("Text Color")
                .setItems(colors) { _, which -> richEditor.setTextColor(colorValues[which]) }
                .show()
        } else {
            // Highlight colors — semi-transparent tints that work on dark bg
            val colors = arrayOf("None", "Purple", "Blue", "Green", "Red", "Yellow", "Orange")
            val colorValues = intArrayOf(
                Color.TRANSPARENT, 0xAA7C3AED.toInt(), 0xAA2563EB.toInt(),
                0xAA16A34A.toInt(), 0xAADC2626.toInt(), 0xAACA8A04.toInt(), 0xAAEA580C.toInt()
            )
            AlertDialog.Builder(this)
                .setTitle("Highlight Color")
                .setItems(colors) { _, which -> richEditor.setTextBackgroundColor(colorValues[which]) }
                .show()
        }
    }

    private fun showFontSizeDialog() {
        // RichEditor uses HTML sizes 1–6; map to readable labels
        val sizes = arrayOf("Tiny", "Small", "Normal", "Large", "Larger", "Huge")
        AlertDialog.Builder(this)
            .setTitle("Inline Font Size")
            .setItems(sizes) { _, which -> richEditor.setFontSize(which + 1) }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.add_edit_note_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // TTS icon: filled/tinted when playing, outline when idle
        menu?.findItem(R.id.action_read_aloud)?.setIcon(
            if (ttsPlaying) R.drawable.ic_close else R.drawable.ic_volume_up
        )
        val favoriteItem = menu?.findItem(R.id.action_favorite)
        val readModeItem = menu?.findItem(R.id.action_read_mode)
        if (currentNote?.isFavorite == true) {
            favoriteItem?.setIcon(R.drawable.ic_star)
        } else {
            favoriteItem?.setIcon(R.drawable.ic_star_border)
        }
        val isExisting = currentNote != null && currentNote!!.id != 0
        readModeItem?.isVisible = isExisting
        readModeItem?.isChecked = isReadMode
        readModeItem?.setIcon(
            if (isReadMode) R.drawable.ic_edit else R.drawable.ic_visibility
        )
        readModeItem?.title = if (isReadMode) "Edit" else "Read"
        val isSecure = currentNote?.isSecure == true
        // Revisions don't store per-version secureMetadata (IV), so history is
        // unreadable for secure notes → hide it rather than show garbage.
        menu?.findItem(R.id.action_history)?.isVisible = isExisting && !isSecure
        menu?.findItem(R.id.action_delete)?.isVisible = isExisting
        menu?.findItem(R.id.action_secure)?.title = if (isSecure) "Remove Security" else "Make Secure"
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_favorite -> {
                currentNote?.let {
                    currentNote = it.copy(isFavorite = !it.isFavorite)
                    invalidateOptionsMenu()
                } ?: run {
                    // Create a temporary note to handle favorite toggle on a new note
                    currentNote = Note(title = "", content = "", timestamp = 0, isFavorite = true)
                    invalidateOptionsMenu()
                }
                return true
            }
            R.id.action_read_mode -> {
                isReadMode = !isReadMode
                applyReadMode()
                invalidateOptionsMenu()
                return true
            }
            R.id.action_secure -> {
                toggleSecure()
                return true
            }
            R.id.action_history -> {
                showRevisionHistoryDialog()
                return true
            }
            R.id.action_read_aloud -> {
                toggleReadAloud()
                return true
            }
            R.id.action_note_color -> {
                showNoteColorDialog()
                return true
            }
            R.id.action_move -> {
                showMoveDialog()
                return true
            }
            R.id.action_delete -> {
                showDeleteConfirmationDialog()
                return true
            }
            android.R.id.home -> {
                performSaveAndClose()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Are you sure you want to move this note to the Recycle Bin?")
            .setPositiveButton("Yes") { _, _ ->
                currentNote?.let { note ->
                    if (note.id != 0) { // Only delete if it exists in DB
                        viewModel.deleteNote(note)
                    }
                    finish() 
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // If user switches away while recording, stop and discard (don't transcribe silently).
        if (isRecording) {
            releaseRecorder()
            sttAudioFile?.delete()
            sttAudioFile = null
            hideSttBar()
        }
        // Background save (app switch, phone call, etc.) — fire-and-forget is fine here.
        if (!isSavingAndClosing) {
            val vaultLockedForSecureNote =
                currentNote?.isSecure == true && !SecurityManager.get().isUnlocked()
            if (!vaultLockedForSecureNote) {
                lifecycleScope.launch { saveNoteInternal() }
            }
        }
    }

    /** Intercept close: show overlay → await encrypt+save → finish. */
    private fun performSaveAndClose() {
        if (isSavingAndClosing) return
        isSavingAndClosing = true

        val isSecure = currentNote?.isSecure == true
        savingOverlayText.text = if (isSecure) "Encrypting & saving..." else "Saving..."
        savingOverlay.alpha = 0f
        savingOverlay.visibility = View.VISIBLE
        savingOverlay.animate().alpha(1f).setDuration(180).start()

        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(richEditor.windowToken, 0)

        lifecycleScope.launch {
            try {
                saveNoteInternal()
                finish()
            } catch (e: Exception) {
                savingOverlay.animate().alpha(0f).setDuration(120)
                    .withEndAction { savingOverlay.visibility = View.GONE }.start()
                isSavingAndClosing = false
                Toast.makeText(
                    this@AddEditNoteActivity,
                    "Save failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun saveNoteInternal() = withContext(Dispatchers.IO) {
        val title = withContext(Dispatchers.Main) { titleEdit.text.toString().trim() }
        val rawContent = withContext(Dispatchers.Main) { richEditor.html ?: "" }
        val folderId = fixedFolderId
        val isNewNote = currentNote == null
        val now = System.currentTimeMillis()

        // Encrypt if secure and vault is unlocked
        val sm = SecurityManager.get()
        val isSecure = currentNote?.isSecure == true
        val encryptResult: Pair<String, String?> = when {
            isSecure && sm.isUnlocked() -> {
                try {
                    val blob = sm.encryptNote(rawContent)
                    blob.first to (blob.second as String?)
                } catch (e: Exception) {
                    // Unexpected encryption error — keep existing ciphertext (no data loss)
                    (currentNote?.content ?: rawContent) to currentNote?.secureMetadata
                }
            }
            isSecure -> {
                // *** CRITICAL: vault is locked — NEVER write plaintext for a secure note. ***
                // Keep the existing encrypted blob exactly as loaded from DB.
                // Any edits made since the vault auto-locked will not be persisted this cycle,
                // but the note content remains intact and decryptable on next unlock.
                (currentNote?.content ?: "") to currentNote?.secureMetadata
            }
            else -> {
                // Regular (non-secure) note — store plaintext directly.
                rawContent to null
            }
        }
        val finalContent = encryptResult.first
        val finalMeta = encryptResult.second

        val noteToSave = currentNote?.copy(
            title = title,
            content = finalContent,
            secureMetadata = if (isSecure) finalMeta else null,
            folderId = folderId,
            isFavorite = currentNote?.isFavorite ?: false,
            timestamp = now,
            createdTimestamp = currentNote?.createdTimestamp ?: now,
            noteColor = currentNote?.noteColor ?: 0
        ) ?: Note(
            title = title,
            content = finalContent,
            secureMetadata = if (isSecure) finalMeta else null,
            timestamp = now,
            createdTimestamp = now,
            folderId = folderId,
            isSecure = isSecure,
            isFavorite = false
        )

        if (isNewNote && title.isEmpty() && (rawContent.isEmpty() || rawContent == "<br>")) {
            return@withContext // Don't save an empty new note
        }

        if (isNewNote) {
            // Insert and capture the DB-assigned ID so subsequent saves update instead of insert.
            val newId = viewModel.addNoteAwait(noteToSave)
            withContext(Dispatchers.Main) {
                val saved = noteToSave.copy(id = newId.toInt())
                currentNote     = saved
                initialNoteState = saved.copy()
            }
        } else if (hasContentChanged(initialNoteState, noteToSave)) {
            // Only persist when meaningful fields actually changed — timestamp alone doesn't count.
            initialNoteState?.let { prev -> viewModel.saveRevision(prev) }
            viewModel.updateNoteAwait(noteToSave)
            withContext(Dispatchers.Main) { initialNoteState = noteToSave.copy() }
        }
    }

    private fun showMoveDialog() {
        val folderNames = listOf("No Folder") + allFolders.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Move to folder")
            .setItems(folderNames.toTypedArray()) { _, which ->
                val selectedFolderId = if (which == 0) null else allFolders[which - 1].id
                fixedFolderId = selectedFolderId
                currentNote = currentNote?.copy(folderId = selectedFolderId)
            }
            .show()
    }

    private fun setupFormattingPanel() {
        val toggle = findViewById<ImageButton>(R.id.editor_toolbar_toggle)
        val handle = findViewById<View>(R.id.editor_toolbar_handle)
        val panel = findViewById<View>(R.id.editor_toolbar_panel)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isFormattingVisible = prefs.getBoolean("pref_toolbar_expanded", true)

        fun render() {
            panel.visibility = if (isFormattingVisible) View.VISIBLE else View.GONE
            toggle.setImageResource(
                if (isFormattingVisible) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
            )
        }
        render()

        val onToggle = View.OnClickListener {
            isFormattingVisible = !isFormattingVisible
            render()
        }
        toggle.setOnClickListener(onToggle)
        handle.setOnClickListener(onToggle)
    }

    private fun setupImeInsets() {
        val toolbarContainer = findViewById<View>(R.id.editor_toolbar_container)
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, ime.bottom)
            insets
        }
    }

    private fun applyFocusBehavior(isExistingNote: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val shouldAutoFocus = if (isExistingNote) {
            prefs.getBoolean(PREF_AUTOFOCUS_OPEN_NOTE, false)
        } else {
            prefs.getBoolean(PREF_AUTOFOCUS_NEW_NOTE, true)
        }
        if (shouldAutoFocus) {
            focusEditorAndShowKeyboard()
        } else {
            clearInitialFocus()
        }
    }

    private fun focusEditorAndShowKeyboard() {
        titleEdit.clearFocus()
        richEditor.requestFocus()
        // Place the text cursor inside the WebView's #editor div, then show keyboard.
        // Using a small delay so the WebView has time to complete its initial render.
        richEditor.postDelayed({
            richEditor.focusEditor()   // calls javascript:RE.focus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(richEditor, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun clearInitialFocus() {
        // Keep the screen passive for existing notes without inserting focusable placeholders.
        titleEdit.clearFocus()
        richEditor.clearFocus()
        currentFocus?.clearFocus()
        window.decorView.clearFocus()
    }

    private fun applyReadMode() {
        titleEdit.isEnabled = !isReadMode
        titleEdit.isFocusable = !isReadMode
        titleEdit.isFocusableInTouchMode = !isReadMode
        richEditor.isFocusable = !isReadMode
        richEditor.isFocusableInTouchMode = !isReadMode

        // Tell the WebView's DOM to flip contentEditable. Without this the
        // keyboard still surfaces when the caret lands inside #editor.
        richEditor.exec(
            "javascript:(function(){" +
                "var e=document.getElementById('editor');" +
                "if(e){e.setAttribute('contenteditable','" +
                (!isReadMode).toString() + "');}" +
                "})()"
        )

        // Hide the formatting panel entirely in read mode (no disabled buttons).
        findViewById<View>(R.id.editor_toolbar_container)
            ?.visibility = if (isReadMode) View.GONE else View.VISIBLE

        if (isReadMode) {
            titleEdit.clearFocus()
            richEditor.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(richEditor.windowToken, 0)
        }
    }

    private fun showRevisionHistoryDialog() {
        val noteId = currentNote?.id ?: return
        if (noteId == 0) {
            AlertDialog.Builder(this)
                .setMessage("Save the note first to access revision history.")
                .setPositiveButton("OK", null).show()
            return
        }
        lifecycleScope.launch {
            val revisions = viewModel.getRevisionsForNote(noteId)
            if (revisions.isEmpty()) {
                AlertDialog.Builder(this@AddEditNoteActivity)
                    .setTitle("History")
                    .setMessage("No revisions saved yet. Revisions are created automatically each time you save changes.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
            val labels = revisions.map { rev ->
                val snippet = Html.fromHtml(rev.content, Html.FROM_HTML_MODE_COMPACT)
                    .toString().take(60).trim().let { if (it.isEmpty()) "(empty)" else it }
                "${sdf.format(java.util.Date(rev.savedAt))}  —  $snippet"
            }.toTypedArray()

            AlertDialog.Builder(this@AddEditNoteActivity)
                .setTitle("Revision History")
                .setItems(labels) { _, which ->
                    val chosen = revisions[which]
                    AlertDialog.Builder(this@AddEditNoteActivity)
                        .setTitle("Restore this revision?")
                        .setMessage("From: ${sdf.format(java.util.Date(chosen.savedAt))}\n\n\"${chosen.title}\"")
                        .setPositiveButton("Restore") { _, _ ->
                            titleEdit.setText(chosen.title)
                            richEditor.html = chosen.content
                            currentNote = currentNote?.copy(title = chosen.title, content = chosen.content)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun showNoteColorDialog() {
        // Noticeably tinted card colors — 0 = default (surface_dark ~#1E1E2E)
        val colorLabels = arrayOf(
            "Default", "Midnight", "Forest", "Ocean", "Crimson",
            "Amber", "Lavender", "Rose", "Teal", "Navy", "Espresso"
        )
        val colorValues = intArrayOf(
            0,
            0xFF252545.toInt(), // Midnight — visible blue-purple tint
            0xFF1F3828.toInt(), // Forest — deep green
            0xFF1A2E42.toInt(), // Ocean — steel blue
            0xFF3E2020.toInt(), // Crimson — deep red
            0xFF3A3010.toInt(), // Amber — warm gold
            0xFF2E1F45.toInt(), // Lavender — rich purple
            0xFF3D1F30.toInt(), // Rose — deep pink
            0xFF1A3838.toInt(), // Teal — dark teal
            0xFF12224A.toInt(), // Navy — dark navy
            0xFF2E2010.toInt()  // Espresso — warm brown
        )

        val currentColor = currentNote?.noteColor ?: 0
        val checkedItem = colorValues.indexOfFirst { it == currentColor }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Note Color")
            .setSingleChoiceItems(colorLabels, checkedItem) { dialog, which ->
                val chosenColor = colorValues[which]
                currentNote = currentNote?.copy(noteColor = chosenColor)
                    ?: Note(
                        title = titleEdit.text.toString(),
                        content = richEditor.html ?: "",
                        timestamp = System.currentTimeMillis(),
                        createdTimestamp = System.currentTimeMillis(),
                        folderId = fixedFolderId,
                        noteColor = chosenColor
                    )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Called after biometric/password unlock — decrypts and loads note content. */
    private fun loadDecryptedNote(note: Note) {
        val sm = SecurityManager.get()
        var autoRecovered = false
        val plainContent = try {
            if (!note.secureMetadata.isNullOrBlank()) {
                sm.decryptNote(note.content, note.secureMetadata!!)
            } else {
                note.content // marked secure but never encrypted (e.g. just toggled)
            }
        } catch (e: Exception) {
            // ── Auto-recovery for corruption bug ───────────────────────────────
            // If the vault was locked during a background save, the editor's decrypted
            // HTML may have been written to note.content directly (plaintext stored instead
            // of ciphertext). Detect this: valid AES-GCM ciphertext is pure Base64;
            // HTML plaintext contains '<', '>', spaces, etc.
            val couldBePlaintext = note.content.any { it == '<' || it == '>' || it == ' ' || it == '\n' }
            if (couldBePlaintext && note.content.isNotBlank()) {
                autoRecovered = true
                Toast.makeText(
                    this,
                    "⚠️ Note recovered — encryption metadata was corrupted. It will re-encrypt on next save.",
                    Toast.LENGTH_LONG
                ).show()
                note.content // return the plaintext content directly
            } else {
                "[Decryption failed: ${e.message}]"
            }
        }

        // Store in memory. If auto-recovered, clear stale secureMetadata so next save re-encrypts,
        // AND immediately persist the cleared metadata to DB so re-opening doesn't hit decryption
        // failure again even if the user closes without editing.
        val noteInMemory = if (autoRecovered) note.copy(secureMetadata = null) else note.copy()
        currentNote = noteInMemory
        initialNoteState = noteInMemory.copy()
        if (autoRecovered) {
            lifecycleScope.launch { viewModel.updateNoteAwait(noteInMemory) }
        }

        titleEdit.setText(note.title)
        // Blur-to-sharp CSS reveal after unlock
        richEditor.html = plainContent
        richEditor.postDelayed({
            richEditor.exec("""javascript:(function(){
                var s = document.body.style;
                s.filter = 'blur(6px)';
                s.opacity = '0.4';
                s.transition = 'filter 0.45s ease, opacity 0.35s ease';
                requestAnimationFrame(function(){
                    requestAnimationFrame(function(){
                        s.filter = 'none';
                        s.opacity = '1';
                    });
                });
            })()""")
        }, 60)
        fixedFolderId = note.folderId
        invalidateOptionsMenu()
        applyFocusBehavior(isExistingNote = true)
    }

    private fun toggleSecure() {
        val sm = SecurityManager.get()
        val note = currentNote
        if (note == null) {
            // New unsaved note — mark as secure for when it gets saved
            AlertDialog.Builder(this)
                .setTitle("Secure this note?")
                .setMessage("The note will be encrypted when saved. Requires master password.")
                .setPositiveButton("Make Secure") { _, _ ->
                    if (!sm.isMasterPasswordSet()) {
                        AlertDialog.Builder(this)
                            .setMessage("Set a master password in Settings first.")
                            .setPositiveButton("OK", null).show()
                        return@setPositiveButton
                    }
                    currentNote = Note(
                        title = titleEdit.text.toString(),
                        content = richEditor.html ?: "",
                        timestamp = System.currentTimeMillis(),
                        createdTimestamp = System.currentTimeMillis(),
                        folderId = fixedFolderId,
                        isSecure = true
                    )
                    invalidateOptionsMenu()
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (note.isSecure) {
            // Remove security: decrypt and save as plain
            AlertDialog.Builder(this)
                .setTitle("Remove encryption?")
                .setMessage("The note will be saved as plain text.")
                .setPositiveButton("Remove") { _, _ ->
                    currentNote = note.copy(isSecure = false, secureMetadata = null)
                    invalidateOptionsMenu()
                }
                .setNegativeButton("Cancel", null).show()
        } else {
            // Add security
            if (!sm.isMasterPasswordSet()) {
                AlertDialog.Builder(this)
                    .setMessage("Set a master password in Settings first.")
                    .setPositiveButton("OK", null).show()
                return
            }
            if (!sm.isUnlocked()) {
                UnlockDialog.showWithBiometricFirst(this, sm, onUnlocked = {
                    currentNote = note.copy(isSecure = true)
                    invalidateOptionsMenu()
                })
            } else {
                currentNote = note.copy(isSecure = true)
                invalidateOptionsMenu()
            }
        }
    }

    // ── STT (Whisper API) ─────────────────────────────────────────────────────

    private fun setupSttBar() {
        sttBar        = findViewById(R.id.stt_bar)
        sttStatusText = findViewById(R.id.stt_status_text)
        sttRecordDot  = findViewById(R.id.stt_record_dot)
        findViewById<View>(R.id.stt_stop_btn).setOnClickListener {
            if (isRecording) stopRecordingAndTranscribe() else hideSttBar()
        }
    }

    private fun startDictation() {
        // If already recording with Whisper, stop and transcribe
        if (isRecording) { stopRecordingAndTranscribe(); return }

        val prefs  = PreferenceManager.getDefaultSharedPreferences(this)
        val apiKey = prefs.getString("pref_openai_api_key", "")?.trim() ?: ""

        if (apiKey.isNotEmpty()) {
            // ── Whisper path (high quality) ────────────────────────────────
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                startRecording()
            }
        } else {
            // ── Google built-in fallback ───────────────────────────────────
            val lang = when (ttsLangMode) { 1 -> "el-GR"; 2 -> "en-US"; else -> Locale.getDefault().toLanguageTag() }
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate your note…")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                sttLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        val file = java.io.File(cacheDir, "stt_audio.m4a")
        sttAudioFile = file
        try {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            sttBar?.visibility = View.VISIBLE
            sttStatusText?.text = "Recording…  tap ■ to stop"
            sttStatusText?.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.danger_red))
            // Pulse the red dot with infinite alpha animation
            recordDotAnimator = ObjectAnimator.ofFloat(sttRecordDot, "alpha", 1f, 0f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } catch (e: Exception) {
            releaseRecorder()
            Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndTranscribe() {
        releaseRecorder()
        val file = sttAudioFile ?: run { hideSttBar(); return }

        sttStatusText?.text = "Transcribing…"
        sttStatusText?.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.purple_light))
        recordDotAnimator?.cancel()
        recordDotAnimator = null
        sttRecordDot?.alpha = 0f

        val prefs   = PreferenceManager.getDefaultSharedPreferences(this)
        val apiKey  = prefs.getString("pref_openai_api_key", "")?.trim() ?: ""
        val lang    = when (ttsLangMode) { 1 -> "el"; 2 -> "en"; else -> null }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { callWhisperApi(file, apiKey, lang) }
            file.delete()
            withContext(Dispatchers.Main) {
                hideSttBar()
                result.fold(
                    onSuccess = { text ->
                        if (text.isNotBlank()) insertTextAtCursor(text)
                    },
                    onFailure = { e ->
                        Toast.makeText(this@AddEditNoteActivity,
                            "Dictation error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
    }

    private fun hideSttBar() {
        recordDotAnimator?.cancel()
        recordDotAnimator = null
        sttRecordDot?.alpha = 1f
        sttBar?.visibility = View.GONE
    }

    /** Sends audio to OpenAI Whisper API and returns plain-text transcription. */
    @Throws(Exception::class)
    private fun callWhisperApi(audioFile: java.io.File, apiKey: String, language: String?): String {
        val boundary = "Boundary${System.currentTimeMillis()}"
        val CRLF     = "\r\n"
        val cs       = Charsets.UTF_8

        val conn = (java.net.URL("https://api.openai.com/v1/audio/transcriptions")
            .openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30_000
            readTimeout    = 60_000
        }

        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary$CRLF".toByteArray(cs))
                    out.write("Content-Disposition: form-data; name=\"$name\"$CRLF$CRLF".toByteArray(cs))
                    out.write("$value$CRLF".toByteArray(cs))
                }
                field("model", "whisper-1")
                field("response_format", "text")
                if (!language.isNullOrBlank()) field("language", language)

                // Binary file part
                out.write("--$boundary$CRLF".toByteArray(cs))
                out.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"$CRLF".toByteArray(cs))
                out.write("Content-Type: audio/m4a$CRLF$CRLF".toByteArray(cs))
                audioFile.inputStream().use { it.copyTo(out) }
                out.write(CRLF.toByteArray(cs))
                out.write("--$boundary--$CRLF".toByteArray(cs))
            }

            val code = conn.responseCode
            val body = if (code == 200) {
                conn.inputStream.bufferedReader(cs).readText()
            } else {
                val err = conn.errorStream?.bufferedReader(cs)?.readText() ?: "HTTP $code"
                throw Exception("API $code: $err")
            }
            return body.trim()
        } finally {
            conn.disconnect()
        }
    }

    // ── Quick Capture metadata ────────────────────────────────────────────────

    /**
     * Fills the title field with timestamp (and optional GPS) so the editor
     * body stays completely empty and typing is not pushed by header content.
     */
    private fun injectQuickCaptureTitle() {
        val p = PreferenceManager.getDefaultSharedPreferences(this)
        val parts = mutableListOf<String>()
        if (p.getBoolean("pref_quick_timestamp", true)) {
            val fmt = p.getString("pref_quick_timestamp_format", "datetime") ?: "datetime"
            parts += QuickCaptureActivity.formatTimestamp(System.currentTimeMillis(), fmt)
        }
        if (p.getBoolean("pref_quick_gps", false)) {
            val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = try {
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (_: SecurityException) { null }
                if (loc != null) parts += "📍 ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}"
            }
        }
        if (parts.isNotEmpty()) {
            titleEdit.setText(parts.joinToString(" · "))
        }
    }

    // ── GPS Location Insert ───────────────────────────────────────────────────

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            doInsertGps()
        } else {
            Toast.makeText(this, "Location permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun insertGpsLocation() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            doInsertGps()
        } else {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun doInsertGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) { null }

        if (loc == null) {
            Toast.makeText(this, "Location not available — enable GPS and try again", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val fmt   = prefs.getString("pref_gps_insert_format", "decimal") ?: "decimal"

        when (fmt) {
            "dms" -> {
                val text = "📍 ${toDms(loc.latitude, true)}, ${toDms(loc.longitude, false)}"
                insertTextAtCursor(text)
            }
            "address" -> {
                // Geocoder is blocking — run off the main thread.
                lifecycleScope.launch {
                    val addr = withContext(Dispatchers.IO) {
                        try {
                            val gc = android.location.Geocoder(this@AddEditNoteActivity, Locale.getDefault())
                            @Suppress("DEPRECATION")
                            gc.getFromLocation(loc.latitude, loc.longitude, 1)
                                ?.firstOrNull()
                        } catch (_: Exception) { null }
                    }
                    val text = if (addr != null) {
                        val street  = listOfNotNull(addr.thoroughfare, addr.subThoroughfare).joinToString(" ")
                        val city    = listOfNotNull(addr.locality ?: addr.subAdminArea, addr.countryName).joinToString(", ")
                        val parts   = listOf(street, city).filter { it.isNotBlank() }
                        "📍 ${parts.joinToString(", ").ifBlank { "%.5f°, %.5f°".format(loc.latitude, loc.longitude) }}"
                    } else {
                        "📍 ${"%.5f".format(loc.latitude)}°, ${"%.5f".format(loc.longitude)}°"
                    }
                    insertTextAtCursor(text)
                }
            }
            else -> { // decimal (default)
                val text = "📍 ${"%.5f".format(loc.latitude)}°, ${"%.5f".format(loc.longitude)}°"
                insertTextAtCursor(text)
            }
        }
    }

    /** Converts a decimal degree value to a DMS string (e.g. 37°58'30\"N). */
    private fun toDms(decimal: Double, isLatitude: Boolean): String {
        val abs  = Math.abs(decimal)
        val deg  = abs.toInt()
        val minD = (abs - deg) * 60
        val min  = minD.toInt()
        val sec  = (minD - min) * 60
        val dir  = when {
            isLatitude  && decimal >= 0 -> "N"
            isLatitude               -> "S"
            !isLatitude && decimal >= 0 -> "E"
            else                     -> "W"
        }
        return "%d°%02d'%05.2f\"%s".format(deg, min, sec, dir)
    }

    /**
     * Appends [text] as a new paragraph at the end of the editor content.
     *
     * We deliberately do NOT try to insert at the caret position. After any
     * external activity (STT dialog, Whisper round-trip, GPS async) the
     * WebView has lost focus and the selection is gone, so execCommand /
     * RE.insertText both silently no-op. Reading and re-writing the HTML is
     * the only strategy that works reliably across all Android WebView
     * versions without requiring the view to be focused.
     */
    private fun insertTextAtCursor(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val escaped = android.text.Html.escapeHtml(trimmed)
        val current = richEditor.html?.trim() ?: ""
        val isBlank = current.isEmpty() || current == "<br>" || current == "<br/>"
        richEditor.html = if (isBlank) "<p>$escaped</p>" else "$current<p>$escaped</p>"
        // Move caret to end so the user can keep typing naturally.
        richEditor.exec(
            "javascript:(function(){" +
                "var ed=document.getElementById('editor');" +
                "if(!ed)return;" +
                "ed.focus();" +
                "var r=document.createRange();" +
                "r.selectNodeContents(ed);" +
                "r.collapse(false);" +
                "var s=window.getSelection();" +
                "s.removeAllRanges();" +
                "s.addRange(r);" +
                "})()"
        )
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    private fun setupTtsBar() {
        // Load persisted settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ttsSpeedIndex = prefs.getInt(TTS_PREF_SPEED, 2)
        ttsPitchIndex = prefs.getInt(TTS_PREF_PITCH, 1)
        ttsLangMode   = prefs.getInt(TTS_PREF_LANG, 0)

        ttsBar = findViewById(R.id.tts_bar)
        ttsSpeedBtn = findViewById(R.id.tts_speed_btn)
        ttsSpeedBtn?.text = ttsSpeedLabels[ttsSpeedIndex]

        // Speed cycle on tap
        ttsSpeedBtn?.setOnClickListener {
            ttsSpeedIndex = (ttsSpeedIndex + 1) % ttsSpeeds.size
            ttsSpeedBtn?.text = ttsSpeedLabels[ttsSpeedIndex]
            tts?.setSpeechRate(ttsSpeeds[ttsSpeedIndex])
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putInt(TTS_PREF_SPEED, ttsSpeedIndex).apply()
        }

        // Settings dialog
        findViewById<View>(R.id.tts_settings_btn).setOnClickListener { showTtsSettings() }

        // Stop
        findViewById<View>(R.id.tts_stop_btn).setOnClickListener { stopReadAloud() }
    }

    fun toggleReadAloud() {
        if (ttsPlaying) stopReadAloud() else startReadAloud()
    }

    private fun startReadAloud() {
        val html = richEditor.html ?: ""
        val body = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        val title = titleEdit.text.toString().trim()
        val fullText = when {
            title.isNotEmpty() && body.isNotEmpty() -> "$title. $body"
            title.isNotEmpty() -> title
            body.isNotEmpty()  -> body
            else -> { Toast.makeText(this, "Nothing to read", Toast.LENGTH_SHORT).show(); return }
        }

        if (tts == null) {
            // First-time init
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == TTS_LAST_ID) runOnUiThread { onTtsDone() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { runOnUiThread { onTtsDone() } }
                    })
                    runOnUiThread { dispatchSpeech(fullText) }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Text-to-speech is not available on this device",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            dispatchSpeech(fullText)
        }
    }

    /** Split text by language and queue utterances with correct locale per segment. */
    private fun dispatchSpeech(fullText: String) {
        val segments: List<Pair<String, Locale>> = when (ttsLangMode) {
            1    -> listOf(fullText to Locale("el", "GR"))
            2    -> listOf(fullText to Locale.ENGLISH)
            else -> splitByLanguage(fullText) // Auto
        }

        tts?.setPitch(ttsPitches[ttsPitchIndex])

        segments.forEachIndexed { i, (segText, locale) ->
            val available = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            }
            tts?.setSpeechRate(ttsSpeeds[ttsSpeedIndex])
            val uid  = if (i == segments.lastIndex) TTS_LAST_ID else "tts_seg_$i"
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(segText, mode, null, uid)
        }

        ttsPlaying = true
        ttsBar?.visibility = View.VISIBLE
        ttsSpeedBtn?.text = ttsSpeedLabels[ttsSpeedIndex]
        invalidateOptionsMenu()
    }

    /** Detect dominant language of a text segment. Returns Greek or English locale. */
    private fun detectLanguage(text: String): Locale {
        val greekChars = text.count { c -> greekRanges.any { r -> c.code in r } }
        val latinChars  = text.count { c -> c.code in 0x41..0x7A }
        return if (greekChars > 0 && greekChars >= latinChars * 0.25) {
            Locale("el", "GR")
        } else {
            Locale.ENGLISH
        }
    }

    /**
     * Split text into language-homogeneous segments.
     * Groups consecutive sentences with the same dominant language.
     * Short English words inside a Greek sentence stay with Greek (handled by
     * the 0.25 threshold in detectLanguage).
     */
    private fun splitByLanguage(text: String): List<Pair<String, Locale>> {
        // Split on sentence boundaries
        val sentences = text.split(Regex("(?<=[.!?…])\\s+|\\n+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return listOf(text to detectLanguage(text))

        val result = mutableListOf<Pair<String, Locale>>()
        val buf    = StringBuilder()
        var curLocale = detectLanguage(sentences.first())

        for (sentence in sentences) {
            val loc = detectLanguage(sentence)
            if (loc == curLocale) {
                buf.append(sentence).append(" ")
            } else {
                if (buf.isNotBlank()) result.add(buf.toString().trim() to curLocale)
                buf.clear().append(sentence).append(" ")
                curLocale = loc
            }
        }
        if (buf.isNotBlank()) result.add(buf.toString().trim() to curLocale)
        return result
    }

    private fun stopReadAloud() {
        tts?.stop()
        onTtsDone()
    }

    private fun onTtsDone() {
        ttsPlaying = false
        ttsBar?.visibility = View.GONE
        invalidateOptionsMenu()
    }

    private fun showTtsSettings() {
        val wasPlaying = ttsPlaying

        // Build inline radio-style layout programmatically for reliability
        val ctx = this
        val root = android.widget.ScrollView(ctx).apply {
            val inner = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(56, 32, 56, 16)

                // ── Speed ─────────────────────────────────────────────────
                addView(sectionLabel(ctx, "Speed"))
                val speedGroup = android.widget.RadioGroup(ctx).apply {
                    orientation = android.widget.RadioGroup.HORIZONTAL
                    isScrollbarFadingEnabled = false
                }
                ttsSpeedLabels.forEachIndexed { i, label ->
                    speedGroup.addView(android.widget.RadioButton(ctx).apply {
                        text = label
                        id = i + 100
                        isChecked = (i == ttsSpeedIndex)
                        textSize = 13f
                    })
                }
                addView(speedGroup)

                // ── Pitch ─────────────────────────────────────────────────
                addView(sectionLabel(ctx, "Pitch"))
                val pitchGroup = android.widget.RadioGroup(ctx).apply {
                    orientation = android.widget.RadioGroup.HORIZONTAL
                }
                ttsPitchLabels.forEachIndexed { i, label ->
                    pitchGroup.addView(android.widget.RadioButton(ctx).apply {
                        text = label
                        id = i + 200
                        isChecked = (i == ttsPitchIndex)
                        textSize = 13f
                    })
                }
                addView(pitchGroup)

                // ── Language ──────────────────────────────────────────────
                addView(sectionLabel(ctx, "Language"))
                val langGroup = android.widget.RadioGroup(ctx).apply {
                    orientation = android.widget.RadioGroup.VERTICAL
                }
                ttsLangLabels.forEachIndexed { i, label ->
                    langGroup.addView(android.widget.RadioButton(ctx).apply {
                        text = label
                        id = i + 300
                        isChecked = (i == ttsLangMode)
                        textSize = 13f
                    })
                }
                addView(langGroup)

                // Save on change for immediate feedback
                speedGroup.setOnCheckedChangeListener { _, id ->
                    ttsSpeedIndex = id - 100
                    ttsSpeedBtn?.text = ttsSpeedLabels[ttsSpeedIndex]
                    tts?.setSpeechRate(ttsSpeeds[ttsSpeedIndex])
                }
                pitchGroup.setOnCheckedChangeListener { _, id ->
                    ttsPitchIndex = id - 200
                    tts?.setPitch(ttsPitches[ttsPitchIndex])
                }
                langGroup.setOnCheckedChangeListener { _, id ->
                    ttsLangMode = id - 300
                }
            }
            addView(inner)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Read Aloud Settings")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                // Persist
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putInt(TTS_PREF_SPEED, ttsSpeedIndex)
                    .putInt(TTS_PREF_PITCH, ttsPitchIndex)
                    .putInt(TTS_PREF_LANG,  ttsLangMode)
                    .apply()
                // Restart if was playing so new settings take effect
                if (wasPlaying) { stopReadAloud(); startReadAloud() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Small bold section label helper for the settings dialog. */
    private fun sectionLabel(ctx: android.content.Context, text: String) =
        TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#A78BFA"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            val dp4 = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, dp8, 0, dp4)
        }

    /**
     * Compare only the meaningful content fields — intentionally excludes [Note.timestamp]
     * and [Note.createdTimestamp] so that a pure timestamp refresh doesn't trigger a save+revision.
     */
    private fun hasContentChanged(old: Note?, new: Note): Boolean {
        if (old == null) return true
        return old.title          != new.title          ||
               old.content        != new.content        ||
               old.secureMetadata != new.secureMetadata ||
               old.folderId       != new.folderId       ||
               old.isFavorite     != new.isFavorite     ||
               old.isSecure       != new.isSecure       ||
               old.noteColor      != new.noteColor      ||
               old.isPinned       != new.isPinned
    }

    override fun onDestroy() {
        // TTS cleanup
        tts?.stop()
        tts?.shutdown()
        tts = null
        // STT cleanup
        recordDotAnimator?.cancel()
        recordDotAnimator = null
        releaseRecorder()
        sttAudioFile?.delete()
        sttAudioFile = null
        // WebView cleanup — prevents memory leak on rotation / back-stack
        richEditor.stopLoading()
        richEditor.clearHistory()
        richEditor.clearCache(true)
        richEditor.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FROM_FOLDER_CONTEXT = "from_folder_context"
        const val EXTRA_IS_SECURE_FOLDER = "is_secure_folder"
        const val PREF_AUTOFOCUS_NEW_NOTE = "pref_autofocus_new_note"
        const val PREF_AUTOFOCUS_OPEN_NOTE = "pref_autofocus_open_note"
        private const val TTS_LAST_ID = "tts_last_segment"
    }
}
