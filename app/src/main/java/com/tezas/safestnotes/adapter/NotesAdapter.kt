package com.tezas.safestnotes.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note

class NotesAdapter(
    private var items: List<Any>,
    private val onNoteClick: (Note, Int) -> Unit,
    private val onFolderClick: (Folder) -> Unit,
    private val onLongPress: (Any, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_NOTE = 0
        const val TYPE_FOLDER = 1
    }

    val selectedItems = mutableSetOf<Any>()

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.noteTitle)
        val snippetText: TextView = itemView.findViewById(R.id.noteSnippet)
        val favoriteIcon: ImageView = itemView.findViewById(R.id.favorite_icon)
        val cardView: CardView = itemView as CardView
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderName: TextView = itemView.findViewById(R.id.folderName)
        val cardView: MaterialCardView = itemView as MaterialCardView
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is Note -> TYPE_NOTE
            is Folder -> TYPE_FOLDER
            else -> throw IllegalArgumentException("Invalid type of data at position $position")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_NOTE -> NoteViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false))
            TYPE_FOLDER -> FolderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val currentItem = items[position]

        when (holder.itemViewType) {
            TYPE_NOTE -> {
                val noteHolder = holder as NoteViewHolder
                val note = currentItem as Note
                noteHolder.titleText.text = if (note.title.isNotBlank()) note.title else "Untitled"
                noteHolder.favoriteIcon.visibility = if (note.isFavorite) View.VISIBLE else View.GONE

                val snippet = HtmlCompat.fromHtml(note.content, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    .toString().trim()
                if (snippet.isNotEmpty()) {
                    noteHolder.snippetText.visibility = View.VISIBLE
                    noteHolder.snippetText.text = snippet
                } else {
                    noteHolder.snippetText.visibility = View.GONE
                }

                noteHolder.itemView.setOnClickListener { onNoteClick(note, position) }
                noteHolder.itemView.setOnLongClickListener {
                    onLongPress(note, position)
                    true
                }

                noteHolder.cardView.alpha = if (selectedItems.contains(note)) 0.65f else 1f
            }
            TYPE_FOLDER -> {
                val folderHolder = holder as FolderViewHolder
                val folder = currentItem as Folder
                folderHolder.folderName.text = folder.name
                folderHolder.itemView.setOnClickListener { onFolderClick(folder) }
                folderHolder.itemView.setOnLongClickListener {
                    onLongPress(folder, position)
                    true
                }
                folderHolder.cardView.isCheckable = true
                folderHolder.cardView.isChecked = selectedItems.contains(folder)
            }
        }
    }

    fun updateData(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun toggleSelection(item: Any) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item)
        } else {
            selectedItems.add(item)
        }
        notifyDataSetChanged()
    }

    fun clearSelections() {
        selectedItems.clear()
        notifyDataSetChanged()
    }
}
