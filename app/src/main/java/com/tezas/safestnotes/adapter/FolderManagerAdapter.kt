package com.tezas.safestnotes.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Folder

/**
 * Grid adapter for the Folders Manager tab.
 * Each card shows an accent-colored bar, folder icon, name, note count, and a 3-dot menu.
 */
class FolderManagerAdapter(
    private var folders: List<Folder>,
    private var noteCounts: Map<Int, Int> = emptyMap(),
    private val onFolderClick: (Folder) -> Unit,
    private val onMoreClick: (View, Folder) -> Unit
) : RecyclerView.Adapter<FolderManagerAdapter.ViewHolder>() {

    inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val accentBar: View     = v.findViewById(R.id.folderAccentBar)
        val icon: ImageView     = v.findViewById(R.id.folderIconImg)
        val name: TextView      = v.findViewById(R.id.folderManagerName)
        val count: TextView     = v.findViewById(R.id.folderManagerCount)
        val lockIcon: ImageView = v.findViewById(R.id.folderManagerLock)
        val moreBtn: ImageButton= v.findViewById(R.id.folderManagerMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder_manager, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = folders.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]

        holder.name.text = folder.name

        val count = noteCounts[folder.id] ?: 0
        holder.count.text = if (count == 1) "1 note" else "$count notes"

        // Accent color
        val accent = if (folder.accentColor != 0) folder.accentColor
                     else holder.itemView.context.getColor(R.color.purple_primary)
        holder.accentBar.setBackgroundColor(accent)
        holder.icon.setColorFilter(accent)

        // Secure badge
        holder.lockIcon.visibility = if (folder.isSecure) View.VISIBLE else View.GONE

        // Clicks
        holder.itemView.setOnClickListener { onFolderClick(folder) }
        holder.moreBtn.setOnClickListener  { onMoreClick(holder.moreBtn, folder) }
    }

    fun updateData(newFolders: List<Folder>, newCounts: Map<Int, Int>) {
        val oldFolders = folders
        val oldCounts  = noteCounts
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldFolders.size
            override fun getNewListSize() = newFolders.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldFolders[oldPos].id == newFolders[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = oldFolders[oldPos]; val n = newFolders[newPos]
                return o == n && (oldCounts[o.id] ?: 0) == (newCounts[n.id] ?: 0)
            }
        })
        folders = newFolders
        noteCounts = newCounts
        diff.dispatchUpdatesTo(this)
    }
}
