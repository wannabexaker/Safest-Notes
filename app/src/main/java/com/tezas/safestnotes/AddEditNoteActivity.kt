package com.tezas.safestnotes

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    private lateinit var folderSpinner: Spinner
    private var currentNote: Note? = null
    private var initialNoteState: Note? = null
    private var allFolders: List<Folder> = emptyList()

    private val viewModel: NotesViewModel by viewModels {
        val database = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(database.noteDao(), database.folderDao()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit_note)

        titleEdit = findViewById(R.id.titleEdit)
        richEditor = findViewById(R.id.richEditor)
        folderSpinner = findViewById(R.id.folder_spinner)

        setupToolbarActions()
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
        val noteId = intent.getIntExtra("note_id", -1).takeIf { it != -1 }
        val folderIdFromIntent = intent.getIntExtra("folder_id", -1).takeIf { it != -1 }

        lifecycleScope.launch {
            allFolders = viewModel.folders.first()
            val folderNames = listOf("No Folder") + allFolders.map { it.name }
            val adapter = ArrayAdapter(this@AddEditNoteActivity, android.R.layout.simple_spinner_item, folderNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            folderSpinner.adapter = adapter

            if (noteId != null) {
                currentNote = viewModel.getNoteById(noteId)
                initialNoteState = currentNote?.copy()
                currentNote?.let { note ->
                    titleEdit.setText(note.title)
                    richEditor.html = note.content
                    val folderIndex = allFolders.indexOfFirst { f -> f.id == note.folderId }.let { index -> if(index == -1) 0 else index + 1}
                    folderSpinner.setSelection(folderIndex)
                    invalidateOptionsMenu()
                }
            } else {
                folderIdFromIntent?.let { id ->
                    val folderIndex = allFolders.indexOfFirst { f -> f.id == id }.let { index -> if(index == -1) 0 else index + 1}
                    folderSpinner.setSelection(folderIndex)
                }
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
        if (currentNote?.isFavorite == true) {
            favoriteItem?.setIcon(R.drawable.ic_star)
        } else {
            favoriteItem?.setIcon(R.drawable.ic_star_border)
        }
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
        val selectedFolderPosition = folderSpinner.selectedItemPosition
        val folderId = if (selectedFolderPosition > 0) allFolders[selectedFolderPosition - 1].id else null

        val isNewNote = currentNote == null
        
        val noteToSave = currentNote?.copy(
            title = title,
            content = content,
            folderId = folderId,
            isFavorite = currentNote?.isFavorite ?: false,
            timestamp = System.currentTimeMillis()
        ) ?: Note(
            title = title,
            content = content,
            timestamp = System.currentTimeMillis(),
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
}