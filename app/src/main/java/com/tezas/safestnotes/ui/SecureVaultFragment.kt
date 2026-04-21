package com.tezas.safestnotes.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tezas.safestnotes.R
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.security.SecurityManager
import com.tezas.safestnotes.viewmodel.NotesViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Secure Vault — shows all isSecure folders and notes in one locked view.
 * Biometric/password required to create content.
 * Folders can be drilled into via the main openFolder() path.
 */
class SecureVaultFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var adapter: NotesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_secure_vault, container, false)

    // Reset ViewModel filter flags so RecycleBin/Favorites don't bleed their state.
    override fun onStart() {
        super.onStart()
        viewModel.setShowFavoritesOnly(false)
        viewModel.setShowDeleted(false)
        viewModel.setCurrentFolder(null)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler   = view.findViewById<RecyclerView>(R.id.vault_recycler)
        val emptyState = view.findViewById<View>(R.id.vault_empty_state)
        val fab        = view.findViewById<FloatingActionButton>(R.id.fab_new_secure)

        adapter = NotesAdapter(
            emptyList(),
            emptyMap(),
            onNoteClick = { note, _ ->
                startActivity(
                    Intent(requireContext(), AddEditNoteActivity::class.java)
                        .putExtra("note_id", note.id)
                )
            },
            onFolderClick = { folder ->
                (activity as? MainActivity)?.openFolder(folder)
            },
            onLongPress      = { _, _ -> },
            onDragHandleTouch = { }
        )
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(requireContext())

        // Combine secure folders + secure notes, both filtered by search query
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.allNotes,
                    viewModel.folders,
                    viewModel.searchQuery
                ) { notes, folders, query ->
                    val secureFolders: List<Any> = folders
                        .filter { it.isSecure }
                        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                        .sortedBy { it.name }
                    val secureNotes: List<Any> = notes
                        .filter { it.isSecure && !it.isDeleted }
                        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                        .sortedByDescending { it.timestamp }
                    secureFolders + secureNotes
                }.collect { items ->
                    adapter.updateData(items)
                    emptyState?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        fab.setOnClickListener { onFabClick(fab) }
    }

    // ── FAB — guard unlock, then show Note / Folder choice ───────────────────

    private fun onFabClick(anchor: View) {
        val sm = SecurityManager.get()
        if (!sm.isMasterPasswordSet()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Secure Vault")
                .setMessage("Set a master password in Settings → Security first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        if (!sm.isUnlocked()) {
            UnlockDialog.showWithBiometricFirst(
                activity as AppCompatActivity, sm,
                onUnlocked = { showCreateMenu(anchor) },
                onCancel   = {}
            )
        } else {
            showCreateMenu(anchor)
        }
    }

    private fun showCreateMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, 1, 0, "New secure note")
            menu.add(0, 2, 0, "New folder")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openNewSecureNote()
                    2 -> showCreateFolderDialog()
                }
                true
            }
        }.show()
    }

    private fun openNewSecureNote() {
        startActivity(
            Intent(requireContext(), AddEditNoteActivity::class.java)
                .putExtra(AddEditNoteActivity.EXTRA_IS_SECURE_FOLDER, true)
        )
    }

    private fun showCreateFolderDialog() {
        val et = EditText(requireContext()).apply {
            hint = "Folder name"
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("New secure folder")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.addFolderSafely(
                        Folder(name = name, isSecure = true)
                    ) { msg -> Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
