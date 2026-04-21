package com.tezas.safestnotes.ui

// Responsibility: Shows favorites list — full note interactions via long-press context menu.
// Feature area: Favorites browsing with unfavorite/delete/share actions.

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.ViewMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class FavoritesFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var notesAdapter: NotesAdapter
    private lateinit var recyclerView: RecyclerView
    private var favoritesList: List<Note> = emptyList()
    private lateinit var gestureDetector: GestureDetector

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerView)

        notesAdapter = NotesAdapter(emptyList(), emptyMap(),
            onNoteClick = { note, _ ->
                val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
                intent.putExtra("note_id", note.id)
                startActivity(intent)
            },
            onFolderClick = { /* No folders in favorites */ },
            onLongPress = { _, _ -> /* handled via gesture detector */ },
            onDragHandleTouch = { /* Drag disabled in favorites */ }
        )
        recyclerView.adapter = notesAdapter

        view.findViewById<View>(R.id.fab).visibility = View.GONE
        view.findViewById<View>(R.id.fab_new_folder).visibility = View.GONE
        val emptyState = view.findViewById<View>(R.id.empty_state)

        setupLongPressContextMenu()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.items.collect { items ->
                        favoritesList = items.filterIsInstance<Note>()
                        notesAdapter.updateData(favoritesList)
                        emptyState?.visibility = if (favoritesList.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.viewMode.collect { viewMode ->
                        recyclerView.layoutManager = when (viewMode) {
                            ViewMode.GRID -> GridLayoutManager(context, 2)
                            ViewMode.LIST -> LinearLayoutManager(context)
                        }
                    }
                }
            }
        }
    }

    private fun setupLongPressContextMenu() {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val position = recyclerView.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION || position >= favoritesList.size) return
                val note = favoritesList[position]
                showNoteContextMenu(child, note)
            }
        })

        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun showNoteContextMenu(anchor: View, note: Note) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.favorites_context_menu, popup.menu)
        popup.menu.findItem(R.id.action_fav_favorite)?.title =
            if (note.isFavorite) "Remove from Favorites" else "Add to Favorites"

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_fav_open -> {
                    val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
                    intent.putExtra("note_id", note.id)
                    startActivity(intent)
                    true
                }
                R.id.action_fav_favorite -> {
                    viewModel.updateNote(note.copy(isFavorite = !note.isFavorite))
                    val msg = if (note.isFavorite) "Removed from Favorites" else "Added to Favorites"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_fav_share -> {
                    shareNote(note)
                    true
                }
                R.id.action_fav_delete -> {
                    viewModel.deleteNote(note)
                    Toast.makeText(requireContext(), "Moved to Recycle Bin", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareNote(note: Note) {
        val text = buildString {
            if (note.title.isNotBlank()) appendLine(note.title).appendLine()
            val body = android.text.Html.fromHtml(note.content, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().trim()
            append(body)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share note"))
    }

    override fun onStart() {
        super.onStart()
        // Full reset before applying our flag — prevents stale state from RecycleBin
        // or a previous folder view bleeding into the favorites list.
        viewModel.setShowDeleted(false)
        viewModel.setCurrentFolder(null)
        viewModel.setShowFavoritesOnly(true)
    }

    override fun onStop() {
        super.onStop()
        viewModel.setShowFavoritesOnly(false)
    }
}
