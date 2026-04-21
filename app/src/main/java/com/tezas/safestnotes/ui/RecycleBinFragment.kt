package com.tezas.safestnotes.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tezas.safestnotes.R
import com.tezas.safestnotes.adapter.RecycleBinAdapter
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class RecycleBinFragment : Fragment() {

    companion object {
        const val PREF_RETENTION_DAYS = "pref_recycle_bin_days"
        const val DEFAULT_RETENTION_DAYS = 30
    }

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var adapter: RecycleBinAdapter

    // Views
    private lateinit var selectionBar: View
    private lateinit var selectionCountText: TextView
    private lateinit var btnClearSelection: View
    private lateinit var btnRestoreSelected: MaterialButton
    private lateinit var btnDeleteSelected: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_recycle_bin, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView_deleted)
        val emptyState: View = view.findViewById(R.id.recycle_bin_empty_state)
        selectionBar = view.findViewById(R.id.selection_bar)
        selectionCountText = view.findViewById(R.id.selection_count_text)
        btnClearSelection = view.findViewById(R.id.btn_clear_selection)
        btnRestoreSelected = view.findViewById(R.id.btn_restore_selected)
        btnDeleteSelected = view.findViewById(R.id.btn_delete_selected)

        adapter = RecycleBinAdapter(
            emptyList(),
            onRestore = { note -> viewModel.restoreNote(note) },
            onDelete  = { note -> viewModel.deleteNotePermanently(note) },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        adapter.onEmptyStateChanged = { isEmpty ->
            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        // ── Selection bar buttons ──────────────────────────────────────────
        btnClearSelection.setOnClickListener { adapter.clearSelection() }

        btnRestoreSelected.setOnClickListener {
            val selected = adapter.getSelectedNotes()
            selected.forEach { viewModel.restoreNote(it) }
            adapter.clearSelection()
            val msg = if (selected.size == 1) "Note restored" else "${selected.size} notes restored"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        btnDeleteSelected.setOnClickListener {
            val selected = adapter.getSelectedNotes()
            if (selected.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
                .setTitle("Delete ${selected.size} note(s)?")
                .setMessage("These notes will be permanently deleted. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    selected.forEach { viewModel.deleteNotePermanently(it) }
                    adapter.clearSelection()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ── 3-dot overflow ─────────────────────────────────────────────────
        view.findViewById<ImageButton>(R.id.btn_bin_overflow).setOnClickListener { anchor ->
            showBinOverflow(anchor)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.items.collect { items ->
                    val notes = items.filterIsInstance<Note>()
                    adapter.updateData(notes)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setShowDeleted(true)
    }

    override fun onStop() {
        super.onStop()
        viewModel.setShowDeleted(false)
    }

    // ── Overflow popup ─────────────────────────────────────────────────────

    private fun showBinOverflow(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.recycle_bin_overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.bin_select_all  -> { adapter.selectAll(); true }
                R.id.bin_restore_all -> { confirmRestoreAll(); true }
                R.id.bin_delete_all  -> { confirmDeleteAll(); true }
                R.id.bin_auto_delete -> { showAutoDeleteDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmRestoreAll() {
        val all = adapter.getAllNotes()
        if (all.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to restore", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Restore all ${all.size} note(s)?")
            .setPositiveButton("Restore All") { _, _ ->
                all.forEach { viewModel.restoreNote(it) }
                Toast.makeText(requireContext(), "All notes restored", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll() {
        val all = adapter.getAllNotes()
        if (all.isEmpty()) {
            Toast.makeText(requireContext(), "Recycle Bin is already empty", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Empty Recycle Bin?")
            .setMessage("All ${all.size} note(s) will be permanently deleted. This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                all.forEach { viewModel.deleteNotePermanently(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAutoDeleteDialog() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val currentDays = prefs.getInt(PREF_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

        val labels = arrayOf("7 days", "14 days", "30 days", "60 days", "90 days", "Never")
        val values  = intArrayOf(7, 14, 30, 60, 90, 0)

        val currentIndex = values.indexOfFirst { it == currentDays }.coerceAtLeast(2)

        MaterialAlertDialogBuilder(requireContext(), R.style.SafestNotes_AlertDialog)
            .setTitle("Auto-delete after")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val days = values[which]
                prefs.edit().putInt(PREF_RETENTION_DAYS, days).apply()
                // Re-run purge immediately with new setting
                viewModel.purgeExpiredNotes(days)
                dialog.dismiss()
                val msg = if (days == 0) "Auto-delete disabled" else "Notes older than $days days will be deleted"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Selection bar ──────────────────────────────────────────────────────

    private fun updateSelectionBar(count: Int) {
        if (count > 0) {
            selectionBar.visibility = View.VISIBLE
            selectionCountText.text = "$count selected"
        } else {
            selectionBar.visibility = View.GONE
        }
    }
}
