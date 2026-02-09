package com.tezas.safestnotes.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.R
import com.tezas.safestnotes.data.Folder

data class DrawerFolderItem(
    val folder: Folder,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean
)

class DrawerFoldersAdapter(
    private var items: List<DrawerFolderItem>,
    private val onToggleExpand: (Folder) -> Unit,
    private val onOpenFolder: (Folder) -> Unit,
    private val onLongPress: (View, Folder) -> Unit
) : RecyclerView.Adapter<DrawerFoldersAdapter.FolderViewHolder>() {

    private var lastTapFolderId: Int? = null
    private var lastTapAt: Long = 0L
    private val doubleTapWindowMs = 300L

    inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val chevron: ImageView = itemView.findViewById(R.id.folder_chevron)
        val name: TextView = itemView.findViewById(R.id.folder_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.drawer_folder_item, parent, false)
        return FolderViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = items[position]
        val folder = item.folder
        holder.name.text = folder.name

        val startPadding = 8 + (item.depth * 16)
        holder.itemView.setPaddingRelative(startPadding, holder.itemView.paddingTop, holder.itemView.paddingEnd, holder.itemView.paddingBottom)

        if (item.hasChildren) {
            holder.chevron.visibility = View.VISIBLE
            holder.chevron.setImageResource(
                if (item.isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            )
            holder.chevron.setOnClickListener { onToggleExpand(folder) }
        } else {
            holder.chevron.visibility = View.INVISIBLE
            holder.chevron.setOnClickListener(null)
        }

        val clickListener = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (lastTapFolderId == folder.id && now - lastTapAt <= doubleTapWindowMs) {
                lastTapFolderId = null
                lastTapAt = 0L
                onOpenFolder(folder)
            } else {
                lastTapFolderId = folder.id
                lastTapAt = now
                if (item.hasChildren) {
                    onToggleExpand(folder)
                }
            }
        }
        holder.name.setOnClickListener(clickListener)
        holder.itemView.setOnClickListener(clickListener)
        holder.itemView.setOnLongClickListener {
            onLongPress(it, folder)
            true
        }
    }

    fun submitItems(newItems: List<DrawerFolderItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
