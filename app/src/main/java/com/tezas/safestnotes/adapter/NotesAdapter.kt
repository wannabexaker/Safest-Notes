package com.tezas.safestnotes.adapter

// Responsibility: Binds note/folder cards with selection/drag affordances.
// Feature area: List touch feedback + drag handle initiation.

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import java.util.Calendar

class NotesAdapter(
    private var items: List<Any>,
    private var folderNoteCounts: Map<Int, Int>,
    private val onNoteClick: (Note, Int) -> Unit,
    private val onFolderClick: (Folder) -> Unit,
    private val onLongPress: (Any, Int) -> Unit,
    private val onDragHandleTouch: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_NOTE = 0
        const val TYPE_FOLDER = 1
    }

    val selectedItems = mutableSetOf<Any>()
    private var hoveredFolderId: Int? = null
    private var hoveredFolderPosition: Int? = null
    private var recentlyMovedFolderId: Int? = null
    private var recentlyMovedAt: Long = 0L

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.noteTitle)
        val previewText: TextView = itemView.findViewById(R.id.notePreview)
        val dateText: TextView = itemView.findViewById(R.id.noteDate)
        val selectionScrim: View = itemView.findViewById(R.id.selection_scrim)
        val selectionCheck: ImageView = itemView.findViewById(R.id.selection_check)
        val dragHandle: ImageView = itemView.findViewById(R.id.note_drag_handle)
        val cardView: CardView = itemView as CardView
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderName: TextView = itemView.findViewById(R.id.folderName)
        val folderCount: TextView = itemView.findViewById(R.id.folderCount)
        val accentView: View = itemView.findViewById(R.id.folderAccent)
        val selectionScrim: View = itemView.findViewById(R.id.selection_scrim)
        val selectionCheck: ImageView = itemView.findViewById(R.id.selection_check)
        val dragHandle: ImageView = itemView.findViewById(R.id.folder_drag_handle)
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
                val snippet = HtmlCompat.fromHtml(note.content, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    .toString().trim()
                noteHolder.previewText.text = snippet
                noteHolder.dateText.text = formatTimestamp(note.timestamp)

                noteHolder.itemView.setOnClickListener { onNoteClick(note, position) }

                noteHolder.cardView.alpha = if (selectedItems.contains(note)) 0.65f else 1f
                val isSelected = selectedItems.contains(note)
                noteHolder.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
                noteHolder.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                noteHolder.dragHandle.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        onDragHandleTouch(noteHolder)
                        return@setOnTouchListener true
                    }
                    false
                }
            }
            TYPE_FOLDER -> {
                val folderHolder = holder as FolderViewHolder
                val folder = currentItem as Folder
                folderHolder.folderName.text = folder.name
                val count = folderNoteCounts[folder.id] ?: 0
                folderHolder.folderCount.text = if (count == 1) "1 note" else "$count notes"
                val accentColor = if (folder.accentColor != 0) {
                    folder.accentColor
                } else {
                    ContextCompat.getColor(folderHolder.itemView.context, R.color.brand_purple)
                }
                folderHolder.accentView.setBackgroundColor(accentColor)
                folderHolder.itemView.setOnClickListener { onFolderClick(folder) }
                folderHolder.cardView.isCheckable = true
                folderHolder.cardView.isChecked = selectedItems.contains(folder)
                val isSelected = selectedItems.contains(folder)
                folderHolder.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
                folderHolder.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                folderHolder.dragHandle.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        onDragHandleTouch(folderHolder)
                        return@setOnTouchListener true
                    }
                    false
                }
                val isHovered = hoveredFolderId == folder.id
                folderHolder.cardView.strokeWidth = if (isHovered) 3 else 1
                folderHolder.cardView.strokeColor = if (isHovered) {
                    ContextCompat.getColor(folderHolder.itemView.context, R.color.brand_purple)
                } else {
                    ContextCompat.getColor(folderHolder.itemView.context, R.color.folder_card_stroke)
                }
                val isRecentlyMoved = recentlyMovedFolderId == folder.id && System.currentTimeMillis() - recentlyMovedAt < 600
                if (isRecentlyMoved) {
                    folderHolder.itemView.animate().cancel()
                    folderHolder.itemView.alpha = 1f
                    folderHolder.itemView.animate()
                        .alpha(0.88f)
                        .setDuration(120)
                        .withEndAction {
                            folderHolder.itemView.animate().alpha(1f).setDuration(140).start()
                        }
                        .start()
                }
            }
        }
    }

    fun updateData(newItems: List<Any>) {
        items = newItems
        hoveredFolderPosition = if (hoveredFolderId == null) null else items.indexOfFirst { it is Folder && it.id == hoveredFolderId }
        notifyDataSetChanged()
    }

    fun updateFolderCounts(newCounts: Map<Int, Int>) {
        folderNoteCounts = newCounts
        notifyDataSetChanged()
    }

    fun setHoveredFolder(folderId: Int?) {
        val previousPos = hoveredFolderPosition
        hoveredFolderId = folderId
        hoveredFolderPosition = if (folderId == null) null else items.indexOfFirst { it is Folder && it.id == folderId }
        if (previousPos != null && previousPos != -1) {
            notifyItemChanged(previousPos)
        }
        val newPos = hoveredFolderPosition
        if (newPos != null && newPos != -1) {
            notifyItemChanged(newPos)
        }
    }

    fun setRecentlyMovedFolder(folderId: Int?) {
        recentlyMovedFolderId = folderId
        recentlyMovedAt = System.currentTimeMillis()
        notifyDataSetChanged()
    }

    fun getDragHandleView(holder: RecyclerView.ViewHolder): View? {
        return when (holder) {
            is NoteViewHolder -> holder.dragHandle
            is FolderViewHolder -> holder.dragHandle
            else -> null
        }
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

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val isToday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (isToday) {
            android.text.format.DateFormat.format("h:mm a", then).toString()
        } else {
            android.text.format.DateFormat.format("MMM d", then).toString()
        }
    }
}
