package com.tezas.safestnotes.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Note

class RecycleBinAdapter(
    private var notes: List<Note>,
    private val onRestore: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : RecyclerView.Adapter<RecycleBinAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.noteTitle)
        val restoreButton: Button = itemView.findViewById(R.id.button_restore)
        val deleteButton: Button = itemView.findViewById(R.id.button_delete_forever)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deleted_note, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = notes.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = notes[position]
        holder.titleText.text = note.title
        holder.restoreButton.setOnClickListener { onRestore(note) }
        holder.deleteButton.setOnClickListener { onDelete(note) }
    }

    fun updateData(newNotes: List<Note>) {
        notes = newNotes
        notifyDataSetChanged()
    }
}