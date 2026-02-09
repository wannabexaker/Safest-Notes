package com.tezas.safestnotes.ui

// Responsibility: Shows favorites list in the main notes layout.
// Feature area: Read-only favorites browsing.

import com.tezas.safestnotes.R

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.ViewMode
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var notesAdapter: NotesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView)
        
        notesAdapter = NotesAdapter(emptyList(), emptyMap(),
            onNoteClick = { note, _ ->
                val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
                intent.putExtra("note_id", note.id)
                startActivity(intent)
            },
            onFolderClick = { /* No folders in favorites */ },
            onLongPress = { _, _ -> /* No multi-select in favorites */ },
            onDragHandleTouch = { /* Drag disabled in favorites */ }
        )
        recyclerView.adapter = notesAdapter

        view.findViewById<View>(R.id.fab).visibility = View.GONE
        view.findViewById<View>(R.id.fab_new_folder).visibility = View.GONE

        viewModel.setShowFavoritesOnly(true)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                val notes = items.filterIsInstance<Note>()
                notesAdapter.updateData(notes)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewMode.collect { viewMode ->
                recyclerView.layoutManager = when (viewMode) {
                    ViewMode.GRID -> GridLayoutManager(context, 2)
                    ViewMode.LIST -> LinearLayoutManager(context)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.setShowFavoritesOnly(false)
    }
}
