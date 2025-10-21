package com.tezas.safestnotes

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.ViewMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotesFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var notesAdapter: NotesAdapter
    private var itemsList: List<Any> = emptyList()
    private lateinit var recyclerView: RecyclerView
    private var actionMode: androidx.appcompat.view.ActionMode? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        setupRecyclerView()
        observeViewModel()
        setupFabs(view)
        setupSwipeToDelete()
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter(itemsList,
            onNoteClick = { note, position ->
                if (actionMode != null) {
                    toggleSelection(note, position)
                } else {
                    val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
                    intent.putExtra("note_id", note.id)
                    startActivity(intent)
                }
            },
            onFolderClick = { folder ->
                if (actionMode != null) {
                    toggleSelection(folder, -1)
                } else {
                    (activity as? MainActivity)?.openFolder(folder)
                }
            },
            onLongPress = { item, position ->
                if (actionMode == null) {
                    actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
                }
                toggleSelection(item, position)
            }
        )
        recyclerView.adapter = notesAdapter
    }

    private fun toggleSelection(item: Any, position: Int) {
        notesAdapter.toggleSelection(item)
        val count = notesAdapter.selectedItems.size
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "$count selected"
            actionMode?.invalidate()
        }
    }

    private val actionModeCallback = object : androidx.appcompat.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.contextual_action_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_delete_contextual -> {
                    viewModel.deleteMultiple(notesAdapter.selectedItems.toList())
                    mode.finish()
                    true
                }
                R.id.action_move -> {
                    showMoveDialog()
                    mode.finish()
                    true
                }
                R.id.action_share -> {
                    shareSelectedNotes()
                    mode.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            notesAdapter.clearSelections()
            actionMode = null
        }
    }

    private fun shareSelectedNotes() {
        val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
        if (selectedNotes.isNotEmpty()) {
            val shareText = selectedNotes.joinToString("\n\n---\n\n") { note ->
                "${note.title}\n\n${Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY)}"
            }
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, null))
        }
    }

    private fun showMoveDialog() {
        lifecycleScope.launch {
            val folders = viewModel.folders.first()
            val folderNames = folders.map { it.name }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("Move to...")
                .setItems(folderNames) { _, which ->
                    val selectedFolder = folders[which]
                    viewModel.moveMultiple(notesAdapter.selectedItems.toList(), selectedFolder.id)
                }
                .show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                itemsList = items
                notesAdapter.updateData(items)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewMode.collect { viewMode ->
                val layoutManager = when (viewMode) {
                    ViewMode.GRID -> GridLayoutManager(context, 2)
                    ViewMode.LIST -> LinearLayoutManager(context)
                }
                if (layoutManager is GridLayoutManager) {
                    layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            if (position < itemsList.size) {
                                return when (notesAdapter.getItemViewType(position)) {
                                    NotesAdapter.TYPE_FOLDER -> 2
                                    NotesAdapter.TYPE_NOTE -> 1
                                    else -> 1
                                }
                            }
                            return 1
                        }
                    }
                }
                recyclerView.layoutManager = layoutManager
            }
        }
    }

    private fun setupFabs(view: View) {
        view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
            (activity as? MainActivity)?.getCurrentFolderId()?.let {
                intent.putExtra("folder_id", it)
            }
            startActivity(intent)
        }
        view.findViewById<FloatingActionButton>(R.id.fab_new_folder).setOnClickListener {
            showNewFolderDialog()
        }
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = itemsList[position]
                    if (item is Note) {
                        viewModel.deleteNote(item)
                    }
                }
            }
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (viewHolder.itemViewType == NotesAdapter.TYPE_FOLDER) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(recyclerView)
    }

    private fun showNewFolderDialog() {
        val editText = EditText(context)
        AlertDialog.Builder(requireContext())
            .setTitle("New Folder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val folderName = editText.text.toString()
                if (folderName.isNotBlank()) {
                    viewModel.addFolder(Folder(name = folderName))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}