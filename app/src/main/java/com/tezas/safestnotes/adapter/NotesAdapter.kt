package com.tezas.safestnotes.adapter

// Responsibility: Binds note/folder cards with selection/drag affordances.
// Feature area: List touch feedback + drag handle initiation.

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
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

    // ─── Cached display prefs (refreshed via refreshPrefs()) ───────────────
    private var prefShowPreview   = true
    private var prefPreviewLines  = 2
    private var prefShowDate      = true
    private var prefCompactCards  = false
    private var prefsLoaded       = false

    // Cached plain-text snippet per note id — avoids HtmlCompat.fromHtml per bind.
    private val snippetCache = HashMap<Int, String>()

    private fun ensurePrefs(ctx: android.content.Context) {
        if (prefsLoaded) return
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        prefShowPreview  = p.getBoolean("pref_show_preview", true)
        prefPreviewLines = p.getString("pref_preview_lines", "2")?.toIntOrNull() ?: 2
        prefShowDate     = p.getBoolean("pref_show_date", true)
        prefCompactCards = p.getBoolean("pref_compact_cards", false)
        prefsLoaded      = true
    }

    /** Call from the host fragment when settings change to re-read + refresh. */
    fun refreshPrefs(ctx: android.content.Context) {
        prefsLoaded = false
        ensurePrefs(ctx)
        snippetCache.clear()
        notifyDataSetChanged()
    }

    inner class NoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.noteTitle)
        val previewText: TextView = itemView.findViewById(R.id.notePreview)
        val dateText: TextView = itemView.findViewById(R.id.noteDate)
        val favoriteAccent: View = itemView.findViewById(R.id.favorite_accent)
        val noteStar: ImageView = itemView.findViewById(R.id.note_star)
        val lockIcon: ImageView = itemView.findViewById(R.id.note_lock_icon)
        val pinBadge: ImageView = itemView.findViewById(R.id.note_pin_badge)
        val selectionScrim: View = itemView.findViewById(R.id.selection_scrim)
        val selectionCheck: ImageView = itemView.findViewById(R.id.selection_check)
        val dragHandle: ImageView = itemView.findViewById(R.id.note_drag_handle)
        val cardView: MaterialCardView = itemView as MaterialCardView
    }

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val folderName: TextView = itemView.findViewById(R.id.folderName)
        val folderCount: TextView = itemView.findViewById(R.id.folderCount)
        val accentView: View = itemView.findViewById(R.id.folderAccent)
        val folderCardIcon: ImageView = itemView.findViewById(R.id.folderCardIcon)
        val selectionScrim: View = itemView.findViewById(R.id.selection_scrim)
        val selectionCheck: ImageView = itemView.findViewById(R.id.selection_check)
        val dragHandle: ImageView = itemView.findViewById(R.id.folder_drag_handle)
        val lockIcon: ImageView = itemView.findViewById(R.id.folder_lock_icon)
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
                val ctx = noteHolder.itemView.context
                ensurePrefs(ctx)

                if (note.title.isNotBlank()) {
                    noteHolder.titleText.visibility = View.VISIBLE
                    noteHolder.titleText.text = note.title
                } else {
                    noteHolder.titleText.visibility = View.GONE
                }

                // Preview: encrypted notes show a placeholder, plain notes strip HTML (cached)
                if (note.isSecure) {
                    noteHolder.previewText.text = "Encrypted"
                    noteHolder.previewText.setTextColor(
                        ContextCompat.getColor(ctx, R.color.purple_light)
                    )
                } else {
                    val snippet = snippetCache.getOrPut(note.id) {
                        HtmlCompat.fromHtml(note.content, HtmlCompat.FROM_HTML_MODE_LEGACY)
                            .toString()
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }
                    noteHolder.previewText.text = snippet
                    noteHolder.previewText.setTextColor(
                        ContextCompat.getColor(ctx, R.color.text_secondary)
                    )
                }

                // Apply preview preferences (cached)
                noteHolder.previewText.visibility = if (prefShowPreview) View.VISIBLE else View.GONE
                noteHolder.previewText.maxLines = prefPreviewLines + (if (note.title.isBlank()) 1 else 0)

                // Date visibility
                noteHolder.dateText.visibility = if (prefShowDate) View.VISIBLE else View.GONE
                noteHolder.dateText.text = formatTimestamp(note.timestamp)

                // Compact mode: reduce min height
                val density = ctx.resources.displayMetrics.density
                val minH = if (prefCompactCards) (72 * density).toInt() else (120 * density).toInt()
                noteHolder.itemView.minimumHeight = minH

                // Note card color (0 = default surface_dark)
                if (note.noteColor != 0) {
                    noteHolder.cardView.setCardBackgroundColor(note.noteColor)
                } else {
                    noteHolder.cardView.setCardBackgroundColor(
                        ContextCompat.getColor(noteHolder.itemView.context, R.color.surface_dark)
                    )
                }

                // Favorite visuals
                val favVisible = if (note.isFavorite) View.VISIBLE else View.GONE
                noteHolder.favoriteAccent.visibility = favVisible
                noteHolder.noteStar.visibility = favVisible

                // Secure lock icon
                noteHolder.lockIcon.visibility = if (note.isSecure) View.VISIBLE else View.GONE

                // Pin badge
                noteHolder.pinBadge.visibility = if (note.isPinned) View.VISIBLE else View.GONE

                // Pinned cards get a subtle top border highlight
                noteHolder.cardView.strokeColor = ContextCompat.getColor(
                    noteHolder.itemView.context,
                    when {
                        selectedItems.contains(note) -> R.color.selection_stroke
                        note.isPinned -> R.color.purple_dim
                        else -> R.color.card_border
                    }
                )

                noteHolder.itemView.setOnClickListener { onNoteClick(note, position) }

                val isSelected = selectedItems.contains(note)
                noteHolder.selectionScrim.visibility = if (isSelected) View.VISIBLE else View.GONE
                noteHolder.selectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                noteHolder.cardView.strokeWidth = if (isSelected || note.isPinned) 2 else 1
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
                folderHolder.folderCardIcon.setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
                folderHolder.lockIcon.visibility = if (folder.isSecure) View.VISIBLE else View.GONE
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
                val isFolderSelected = selectedItems.contains(folder)
                folderHolder.cardView.strokeWidth = when {
                    isHovered -> 3
                    isFolderSelected -> 2
                    else -> 1
                }
                folderHolder.cardView.strokeColor = when {
                    isHovered -> ContextCompat.getColor(folderHolder.itemView.context, R.color.purple_primary)
                    isFolderSelected -> ContextCompat.getColor(folderHolder.itemView.context, R.color.selection_stroke)
                    else -> ContextCompat.getColor(folderHolder.itemView.context, R.color.card_border)
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
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = items[oldPos]; val n = newItems[newPos]
                return when {
                    o is Note   && n is Note   -> o.id == n.id
                    o is Folder && n is Folder -> o.id == n.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        // Prune snippet cache for removed notes
        val keepIds = newItems.asSequence().filterIsInstance<Note>().map { it.id }.toHashSet()
        snippetCache.keys.retainAll(keepIds)
        // Invalidate cached snippets whose content changed
        newItems.forEach { n ->
            if (n is Note) {
                val old = items.firstOrNull { it is Note && it.id == n.id } as? Note
                if (old != null && old.content != n.content) snippetCache.remove(n.id)
            }
        }
        items = newItems
        hoveredFolderPosition = if (hoveredFolderId == null) null
            else items.indexOfFirst { it is Folder && it.id == hoveredFolderId }
        diff.dispatchUpdatesTo(this)
    }

    fun updateFolderCounts(newCounts: Map<Int, Int>) {
        if (folderNoteCounts == newCounts) return
        folderNoteCounts = newCounts
        // Only the folder rows need rebind.
        items.forEachIndexed { i, it -> if (it is Folder) notifyItemChanged(i) }
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
        if (folderId != null) {
            val pos = items.indexOfFirst { it is Folder && it.id == folderId }
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    fun getDragHandleView(holder: RecyclerView.ViewHolder): View? {
        return when (holder) {
            is NoteViewHolder -> holder.dragHandle
            is FolderViewHolder -> holder.dragHandle
            else -> null
        }
    }

    fun toggleSelection(item: Any) {
        if (selectedItems.contains(item)) selectedItems.remove(item) else selectedItems.add(item)
        val pos = items.indexOf(item)
        if (pos >= 0) notifyItemChanged(pos)
    }

    fun clearSelections() {
        if (selectedItems.isEmpty()) return
        val snapshot = selectedItems.toList()
        selectedItems.clear()
        snapshot.forEach { sel ->
            val pos = items.indexOf(sel)
            if (pos >= 0) notifyItemChanged(pos)
        }
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
