package com.tezas.safestnotes.adapter

import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RecycleBinAdapter(
    private var notes: List<Note>,
    private val onRestore: (Note) -> Unit,
    private val onDelete: (Note) -> Unit,
    private val onSelectionChanged: (count: Int) -> Unit = {}
) : RecyclerView.Adapter<RecycleBinAdapter.ViewHolder>() {

    var onEmptyStateChanged: ((isEmpty: Boolean) -> Unit)? = null

    val selectedNotes = mutableSetOf<Note>()
    var isSelectionMode = false
        private set

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.noteTitle)
        val timestampText: TextView = itemView.findViewById(R.id.noteTimestamp)
        val expiryText: TextView = itemView.findViewById(R.id.noteExpiry)
        val snippetText: TextView = itemView.findViewById(R.id.noteSnippet)
        val buttonsRow: View = itemView.findViewById(R.id.buttons_row)
        val restoreButton: MaterialButton = itemView.findViewById(R.id.button_restore)
        val deleteButton: MaterialButton = itemView.findViewById(R.id.button_delete_forever)
        val selectionScrim: View = itemView.findViewById(R.id.selection_scrim)
        val selectionCheck: ImageView = itemView.findViewById(R.id.selection_check)
        val cardView: MaterialCardView = itemView as MaterialCardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_deleted_note, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = notes.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = notes[position]

        holder.titleText.text = note.title.ifEmpty { "Untitled" }

        val sdf = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
        val deletedMs = note.deletedAt ?: note.timestamp
        holder.timestampText.text = "Deleted ${sdf.format(Date(deletedMs))}"

        // Show expiry countdown based on deletedAt
        val expiryMs = deletedMs + TimeUnit.DAYS.toMillis(30)
        val daysLeft = TimeUnit.MILLISECONDS.toDays(expiryMs - System.currentTimeMillis())
        when {
            daysLeft <= 0 -> {
                holder.expiryText.visibility = View.VISIBLE
                holder.expiryText.text = "Expires today"
                holder.expiryText.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.danger_red))
            }
            daysLeft <= 7 -> {
                holder.expiryText.visibility = View.VISIBLE
                holder.expiryText.text = "Expires in $daysLeft day${if (daysLeft == 1L) "" else "s"}"
                holder.expiryText.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, android.R.color.holo_orange_light))
            }
            else -> {
                holder.expiryText.visibility = View.GONE
            }
        }

        val rawSnippet = if (note.content.isNotBlank()) {
            Html.fromHtml(note.content, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        } else ""
        if (rawSnippet.isNotEmpty()) {
            holder.snippetText.visibility = View.VISIBLE
            holder.snippetText.text = rawSnippet
        } else {
            holder.snippetText.visibility = View.GONE
        }

        val isSelected = selectedNotes.contains(note)
        holder.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.cardView.strokeColor = if (isSelected)
            ContextCompat.getColor(holder.itemView.context, R.color.selection_stroke)
        else
            ContextCompat.getColor(holder.itemView.context, R.color.card_border)
        holder.cardView.strokeWidth = if (isSelected) 2 else 1

        // In selection mode, hide individual action buttons
        holder.buttonsRow.visibility = if (isSelectionMode) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                toggleSelection(note)
            }
            // Outside selection mode: single tap does nothing (long-press to select)
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                isSelectionMode = true
            }
            toggleSelection(note)
            true
        }

        holder.restoreButton.setOnClickListener { onRestore(note) }
        holder.deleteButton.setOnClickListener { onDelete(note) }
    }

    fun toggleSelection(note: Note) {
        val wasSelectionMode = isSelectionMode
        if (selectedNotes.contains(note)) selectedNotes.remove(note) else selectedNotes.add(note)
        if (selectedNotes.isEmpty()) isSelectionMode = false
        val pos = notes.indexOf(note)
        if (wasSelectionMode != isSelectionMode) {
            // Visibility of per-row action buttons depends on isSelectionMode
            notifyItemRangeChanged(0, notes.size)
        } else if (pos >= 0) {
            notifyItemChanged(pos)
        }
        onSelectionChanged(selectedNotes.size)
    }

    fun selectAll() {
        selectedNotes.addAll(notes)
        isSelectionMode = notes.isNotEmpty()
        notifyItemRangeChanged(0, notes.size)
        onSelectionChanged(selectedNotes.size)
    }

    fun clearSelection() {
        if (selectedNotes.isEmpty() && !isSelectionMode) return
        selectedNotes.clear()
        isSelectionMode = false
        notifyItemRangeChanged(0, notes.size)
        onSelectionChanged(0)
    }

    fun updateData(newNotes: List<Note>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = notes.size
            override fun getNewListSize() = newNotes.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                notes[oldPos].id == newNotes[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                notes[oldPos] == newNotes[newPos] && selectedNotes.contains(notes[oldPos]) == selectedNotes.contains(newNotes[newPos])
        })
        notes = newNotes
        selectedNotes.retainAll(notes.toSet())
        if (selectedNotes.isEmpty()) isSelectionMode = false
        diff.dispatchUpdatesTo(this)
        onEmptyStateChanged?.invoke(notes.isEmpty())
        onSelectionChanged(selectedNotes.size)
    }

    fun getSelectedNotes(): List<Note> = selectedNotes.toList()
    fun getAllNotes(): List<Note> = notes
}
