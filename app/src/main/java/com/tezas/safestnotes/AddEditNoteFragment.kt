package com.tezas.safestnotes

import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import jp.wasabeef.richeditor.RichEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AddEditNoteFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var titleEdit: EditText
    private lateinit var richEditor: RichEditor
    private lateinit var folderSpinner: Spinner
    private var currentNote: Note? = null
    private var initialContent: String = ""
    private var allFolders: List<Folder> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.activity_add_edit_note, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleEdit = view.findViewById(R.id.titleEdit)
        richEditor = view.findViewById(R.id.richEditor)
        folderSpinner = view.findViewById(R.id.folder_spinner)

        setupToolbarActions(view)
        loadNoteData()
    }
    
    private fun setupToolbarActions(view: View){
        view.findViewById<ImageButton>(R.id.action_bold).setOnClickListener { richEditor.setBold() }
        view.findViewById<ImageButton>(R.id.action_italic).setOnClickListener { richEditor.setItalic() }
        // ... (rest of the toolbar setup)
    }

    private fun loadNoteData(){
        val noteId = arguments?.getInt("note_id", -1)?.takeIf { it != -1 }

        lifecycleScope.launch {
            allFolders = viewModel.folders.first()
            val folderNames = listOf("No Folder") + allFolders.map { it.name }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, folderNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            folderSpinner.adapter = adapter

            if (noteId != null) {
                currentNote = viewModel.getNoteById(noteId)
                currentNote?.let {
                    titleEdit.setText(it.title)
                    richEditor.html = it.content
                    initialContent = it.content ?: ""
                    val folderIndex = allFolders.indexOfFirst { f -> f.id == it.folderId }.let { if(it == -1) 0 else it + 1}
                    folderSpinner.setSelection(folderIndex)
                    activity?.invalidateOptionsMenu()
                }
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.add_edit_note_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        // ... (logic to show correct star)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // ... (logic to handle favorite and delete)
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
        super.onPause()
        saveNote()
    }

    private fun saveNote() {
        // ... (save logic)
    }

    companion object {
        fun newInstance(noteId: Int): AddEditNoteFragment {
            val fragment = AddEditNoteFragment()
            val args = Bundle()
            args.putInt("note_id", noteId)
            fragment.arguments = args
            return fragment
        }
    }
}