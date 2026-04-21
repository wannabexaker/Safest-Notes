package com.tezas.safestnotes.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tezas.safestnotes.R
import com.tezas.safestnotes.adapter.DrawerFolderItem
import com.tezas.safestnotes.adapter.DrawerFoldersAdapter
import com.tezas.safestnotes.adapter.FolderManagerAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Folders Manager tab — two views:
 *   - Grid (default): FolderManagerAdapter with 2-column GridLayoutManager, big cards.
 *   - Tree: DrawerFoldersAdapter with LinearLayoutManager, compact nested list.
 * Toggle via the ⋮ button at the top.
 */
class FoldersFragment : Fragment() {

    companion object {
        private const val PREF_FOLDERS_VIEW = "pref_folders_view_mode"
        private const val VIEW_GRID = "grid"
        private const val VIEW_TREE = "tree"
    }

    private val viewModel: NotesViewModel by activityViewModels {
        val db = NotesDatabase.getDatabase(requireContext())
        NotesViewModelFactory(NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao()))
    }

    private lateinit var recycler: RecyclerView
    private var gridAdapter: FolderManagerAdapter? = null
    private var treeAdapter: DrawerFoldersAdapter? = null
    private val expandedFolderIds = mutableSetOf<Int>()
    private var viewMode = VIEW_GRID   // default

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_folders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore persisted view preference
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        viewMode = prefs.getString(PREF_FOLDERS_VIEW, VIEW_GRID) ?: VIEW_GRID

        recycler = view.findViewById(R.id.folders_recycler)

        view.findViewById<FloatingActionButton>(R.id.fab_add_folder).setOnClickListener {
            showCreateRootFolderDialog()
        }

        // ⋮ overflow button (reuse bin pattern — we'll add it to the layout header)
        view.findViewById<ImageButton?>(R.id.btn_folders_overflow)?.setOnClickListener { anchor ->
            showFoldersOverflow(anchor)
        }

        setupAdapters()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.folders.collectLatest { refreshList() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allNotes.collectLatest { refreshList() }
        }
    }

    private fun setupAdapters() {
        if (viewMode == VIEW_GRID) {
            val adapter = FolderManagerAdapter(
                emptyList(),
                emptyMap(),
                onFolderClick = { folder -> (activity as? MainActivity)?.openFolder(folder) },
                onMoreClick   = { anchor, folder -> showFolderContextMenu(anchor, folder) }
            )
            gridAdapter = adapter
            treeAdapter = null
            recycler.layoutManager = GridLayoutManager(requireContext(), 2)
            recycler.adapter = adapter
        } else {
            val adapter = DrawerFoldersAdapter(
                emptyList(),
                onToggleExpand = { folder ->
                    if (expandedFolderIds.contains(folder.id)) expandedFolderIds.remove(folder.id)
                    else expandedFolderIds.add(folder.id)
                    rebuildTree()
                },
                onOpenFolder = { folder -> (activity as? MainActivity)?.openFolder(folder) },
                onLongPress  = { anchor, folder -> showFolderContextMenu(anchor, folder) }
            )
            treeAdapter = adapter
            gridAdapter = null
            recycler.layoutManager = LinearLayoutManager(requireContext())
            recycler.adapter = adapter
        }
    }

    private fun refreshList() {
        if (viewMode == VIEW_GRID) rebuildGrid() else rebuildTree()
    }

    private fun rebuildGrid() {
        val folders = viewModel.folders.value
        val notes   = viewModel.allNotes.value
        val counts  = folders.associate { f ->
            f.id to notes.count { it.folderId == f.id && !it.isDeleted }
        }
        // Show all root folders (parentFolderId == null) — subfolders shown via drill-down
        val rootFolders = folders.filter { it.parentFolderId == null }
        gridAdapter?.updateData(rootFolders, counts)
    }

    private fun rebuildTree() {
        val folders = viewModel.folders.value
        val items   = buildTreeItems(folders, null, 0)
        treeAdapter?.submitItems(items)
    }

    private fun buildTreeItems(folders: List<Folder>, parentId: Int?, depth: Int): List<DrawerFolderItem> {
        if (depth >= 3) return emptyList()
        return folders.filter { it.parentFolderId == parentId }.flatMap { folder ->
            val hasChildren = folders.any { it.parentFolderId == folder.id }
            val expanded = expandedFolderIds.contains(folder.id)
            listOf(DrawerFolderItem(folder, depth, hasChildren, expanded)) +
                if (expanded) buildTreeItems(folders, folder.id, depth + 1) else emptyList()
        }
    }

    // ── Overflow menu ──────────────────────────────────────────────────────

    private fun showFoldersOverflow(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, if (viewMode == VIEW_GRID) "Switch to Tree view" else "Switch to Grid view")
        popup.setOnMenuItemClickListener {
            toggleViewMode(); true
        }
        popup.show()
    }

    private fun toggleViewMode() {
        viewMode = if (viewMode == VIEW_GRID) VIEW_TREE else VIEW_GRID
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit().putString(PREF_FOLDERS_VIEW, viewMode).apply()
        setupAdapters()
        refreshList()
    }

    // ── Create folder ──────────────────────────────────────────────────────

    private fun showCreateRootFolderDialog() {
        val editText = EditText(requireContext()).apply { hint = "Folder name" }
        AlertDialog.Builder(requireContext())
            .setTitle("New folder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.addFolderSafely(Folder(name = name)) { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Folder context menu ────────────────────────────────────────────────

    private fun showFolderContextMenu(anchor: View, folder: Folder) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menuInflater.inflate(R.menu.drawer_folder_context_menu, menu.menu)
        menu.setOnMenuItemClickListener { item ->
            val activity = activity as? MainActivity ?: return@setOnMenuItemClickListener false
            when (item.itemId) {
                R.id.action_create_subfolder    -> { activity.showCreateSubfolderDialog(folder); true }
                R.id.action_rename_folder       -> { activity.showRenameFolderDialog(folder); true }
                R.id.action_delete_folder       -> { activity.showDeleteFolderDialog(folder); true }
                R.id.action_change_folder_color -> { activity.showFolderColorDialog(folder); true }
                R.id.action_toggle_secure_folder -> {
                    viewModel.updateFolder(folder.copy(isSecure = !folder.isSecure))
                    true
                }
                else -> false
            }
        }
        menu.show()
    }
}
