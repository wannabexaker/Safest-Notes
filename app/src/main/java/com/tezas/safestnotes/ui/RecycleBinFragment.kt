package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.adapter.RecycleBinAdapter
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import kotlinx.coroutines.launch

class RecycleBinFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_recycle_bin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView: RecyclerView = view.findViewById(R.id.recyclerView_deleted)
        val adapter = RecycleBinAdapter(emptyList(),
            onRestore = { note -> viewModel.restoreNote(note) },
            onDelete = { note -> viewModel.deleteNotePermanently(note) }
        )
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(context)

        viewModel.setShowDeleted(true)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                // Filter for Note items only, as Recycle Bin doesn't show folders
                val notes = items.filterIsInstance<Note>()
                adapter.updateData(notes)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.setShowDeleted(false) // Reset the filter when leaving the fragment
    }
}
