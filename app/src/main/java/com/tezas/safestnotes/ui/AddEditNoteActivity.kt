// File responsibility: Add/edit note screen behavior and persistence.
// Feature area: New note crash fix, editor focus behavior, preferences.
package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AddEditNoteActivity : AppCompatActivity() {

    private lateinit var titleEdit: EditText
    private lateinit var richEditor: CustomRichEditor 
    private var currentNote: Note? = null
    private var initialNoteState: Note? = null
    private var allFolders: List<Folder> = emptyList()
    private var fixedFolderId: Int? = null
    private var isFormattingVisible: Boolean = true
    private var isReadMode: Boolean = false

    private val viewModel: NotesViewModel by viewModels {
        val database = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(database.noteDao(), database.folderDao()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_note)

        val toolbar: Toolbar = findViewById(R.id.editor_top_toolbar)
        setSupportActionBar(toolbar)

        titleEdit = findViewById(R.id.titleEdit)
        richEditor = findViewById(R.id.richEditor)

        richEditor.settings.javaScriptEnabled = true
        richEditor.settings.domStorageEnabled = true
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
        setupImeInsets()
        loadNoteData()
    }

    private fun setupToolbarActions(){
        findViewById<ImageButton>(R.id.action_bold).setOnClickListener { richEditor.setBold() }
        findViewById<ImageButton>(R.id.action_italic).setOnClickListener { richEditor.setItalic() }
        findViewById<ImageButton>(R.id.action_underline).setOnClickListener { richEditor.setUnderline() }
        findViewById<ImageButton>(R.id.action_strikethrough).setOnClickListener { richEditor.setStrikeThrough() }
        findViewById<ImageButton>(R.id.action_bullet).setOnClickListener { richEditor.setBullets() }
        findViewById<ImageButton>(R.id.action_checkbox).setOnClickListener { richEditor.exec("javascript:RE.setTodo()") }
        findViewById<ImageButton>(R.id.action_txt_color).setOnClickListener { showColorSelectionDialog(isText = true) }
        findViewById<ImageButton>(R.id.action_bg_color).setOnClickListener { showColorSelectionDialog(isText = false) }
        findViewById<ImageButton>(R.id.action_font_size).setOnClickListener { showFontSizeDialog() }
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
                    initialNoteState = currentNote?.copy()
                    currentNote?.let { note ->
                        titleEdit.setText(note.title)
                        richEditor.html = note.content
                        fixedFolderId = note.folderId
                        invalidateOptionsMenu()
                    }
                    // Apply focus after content is loaded to avoid focus/keyboard glitches.
                    applyFocusBehavior(isExistingNote = true)
                }
            } else {
                currentNote = null
                initialNoteState = null
                richEditor.html = ""
                fixedFolderId = folderIdFromIntent
                // Apply focus once views are ready for a clean new-note experience.
                applyFocusBehavior(isExistingNote = false)
            }
        }
    }

    private fun showColorSelectionDialog(isText: Boolean) {
        val colors = arrayOf("Black", "Red", "Blue", "Green", "Yellow", "White")
        val colorValues = intArrayOf(Color.BLACK, Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.WHITE)

        AlertDialog.Builder(this)
            .setTitle(if (isText) "Set Text Color" else "Set Background Color")
            .setItems(colors) { _, which ->
                if (isText) {
                    richEditor.setTextColor(colorValues[which])
                } else {
                    richEditor.setTextBackgroundColor(colorValues[which])
                }
            }
            .show()
    }

    private fun showFontSizeDialog() {
        val sizes = arrayOf("Smallest", "Small", "Normal", "Large", "Largest", "Huge")
        val sizeValues = 1..6 

        AlertDialog.Builder(this)
            .setTitle("Set Font Size")
            .setItems(sizes) { _, which ->
                richEditor.setFontSize(sizeValues.elementAt(which))
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.add_edit_note_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val favoriteItem = menu?.findItem(R.id.action_favorite)
        val readModeItem = menu?.findItem(R.id.action_read_mode)
        if (currentNote?.isFavorite == true) {
            favoriteItem?.setIcon(R.drawable.ic_star)
        } else {
            favoriteItem?.setIcon(R.drawable.ic_star_border)
        }
        readModeItem?.isVisible = currentNote != null
        readModeItem?.isChecked = isReadMode
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
            R.id.action_move -> {
                showMoveDialog()
                return true
            }
            R.id.action_delete -> {
                showDeleteConfirmationDialog()
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
        saveNote()
    }

    private fun saveNote() {
        val title = titleEdit.text.toString().trim()
        val content = richEditor.html ?: ""
        val folderId = fixedFolderId

        val isNewNote = currentNote == null
        
        val now = System.currentTimeMillis()
        val noteToSave = currentNote?.copy(
            title = title,
            content = content,
            folderId = folderId,
            isFavorite = currentNote?.isFavorite ?: false,
            timestamp = now,
            createdTimestamp = currentNote?.createdTimestamp ?: now
        ) ?: Note(
            title = title,
            content = content,
            timestamp = now,
            createdTimestamp = now,
            folderId = folderId,
            isFavorite = false
        )

        if (isNewNote && title.isEmpty() && (content.isEmpty() || content == "<br>")) {
            return // Don't save an empty new note
        }
        
        if (isNewNote || noteToSave != initialNoteState) {
             if (isNewNote) {
                viewModel.addNote(noteToSave)
            } else {
                viewModel.updateNote(noteToSave)
            }
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
        val panel = findViewById<View>(R.id.editor_toolbar_panel)
        toggle.setOnClickListener {
            isFormattingVisible = !isFormattingVisible
            panel.visibility = if (isFormattingVisible) View.VISIBLE else View.GONE
            toggle.setImageResource(if (isFormattingVisible) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
        }
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
        // Explicitly focus the editor so the title never steals focus.
        titleEdit.clearFocus()
        richEditor.requestFocus()
        richEditor.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(richEditor, InputMethodManager.SHOW_IMPLICIT)
        }
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
        if (isReadMode) {
            titleEdit.clearFocus()
            richEditor.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(richEditor.windowToken, 0)
        }
    }

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FROM_FOLDER_CONTEXT = "from_folder_context"
        const val PREF_AUTOFOCUS_NEW_NOTE = "pref_autofocus_new_note"
        const val PREF_AUTOFOCUS_OPEN_NOTE = "pref_autofocus_open_note"
    }
}
