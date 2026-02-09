package com.tezas.safestnotes.ui

// Responsibility: Notes grid interactions, context menus, and drag behavior.
// Feature area: Selection UX + long-press/drag logic.

import com.tezas.safestnotes.R

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.format.DateFormat
import android.view.*
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import android.view.GestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.PopupMenu
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.ViewMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

open class NotesFragment : Fragment() {

    private val viewModel: NotesViewModel by activityViewModels()
    private lateinit var notesAdapter: NotesAdapter
    private var itemsList: List<Any> = emptyList()
    private var allNotes: List<Note> = emptyList()
    private var folderNoteCounts: Map<Int, Int> = emptyMap()
    private lateinit var recyclerView: RecyclerView
    private var actionMode: androidx.appcompat.view.ActionMode? = null
    private var backCallback: OnBackPressedCallback? = null
    private var longPressTarget: RecyclerView.ViewHolder? = null
    private var longPressItem: Any? = null
    private var longPressActive = false
    private var dragStarted = false
    private var dragItems: List<Any> = emptyList()
    private var hoveredFolderId: Int? = null
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var gestureDetector: GestureDetector
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var touchSlop: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        setupRecyclerView()
        observeViewModel()
        setupFabs(view)
        setupDragAndSwipe()
        setupBackHandler()
        viewModel.setCurrentFolder(getFolderIdArg())
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter(itemsList, folderNoteCounts,
            onNoteClick = { note, position ->
                if (actionMode != null) {
                    toggleSelection(note, position)
                } else {
                    val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
                    intent.putExtra("note_id", note.id)
                    getFolderIdArg()?.let { id ->
                        intent.putExtra(AddEditNoteActivity.EXTRA_FOLDER_ID, id)
                        intent.putExtra(AddEditNoteActivity.EXTRA_FROM_FOLDER_CONTEXT, true)
                    }
                    startActivity(intent)
                }
            },
            onFolderClick = { folder ->
                if (actionMode != null) {
                    toggleSelection(folder, -1)
                } else {
                    (activity as? MainActivity)?.openFolder(folder)
                }
            },
            onLongPress = { _, _ -> },
            onDragHandleTouch = { holder ->
                startDragFromHandle(holder)
            }
        )
        recyclerView.adapter = notesAdapter
        setupGestureHandling()
    }

    private fun toggleSelection(item: Any, position: Int) {
        notesAdapter.toggleSelection(item)
        val count = notesAdapter.selectedItems.size
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = "$count selected"
            actionMode?.invalidate()
        }
        updateBackHandlerEnabled()
    }

    private val actionModeCallback = object : androidx.appcompat.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.contextual_action_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean {
            val hasFolders = notesAdapter.selectedItems.any { it is Folder }
            menu.findItem(R.id.action_change_folder_color)?.isVisible = hasFolders
            applyDisabledRedTitle(menu.findItem(R.id.action_move_secure), "Move to Secure Folder")
            applyDisabledRedTitle(menu.findItem(R.id.action_copy_secure), "Copy to Secure Folder")
            return false
        }

        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: MenuItem): Boolean {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            val selectedFolders = notesAdapter.selectedItems.filterIsInstance<Folder>()
            return when (item.itemId) {
                R.id.action_details -> {
                    showDetailsDialog()
                    true
                }
                R.id.action_duplicate -> {
                    if (selectedNotes.isNotEmpty()) {
                        viewModel.duplicateNotes(selectedNotes)
                        mode.finish()
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_delete_contextual -> {
                    if (selectedNotes.isNotEmpty()) {
                        viewModel.deleteMultiple(selectedNotes)
                        mode.finish()
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_move -> {
                    if (selectedNotes.isNotEmpty()) {
                        showMoveDialog(isCopy = false, notes = selectedNotes, onComplete = { folderId ->
                            notesAdapter.setRecentlyMovedFolder(folderId)
                            mode.finish()
                        })
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_copy -> {
                    if (selectedNotes.isNotEmpty()) {
                        showMoveDialog(isCopy = true, notes = selectedNotes, onComplete = { folderId ->
                            notesAdapter.setRecentlyMovedFolder(folderId)
                            mode.finish()
                        })
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_move_secure -> {
                    if (selectedNotes.isNotEmpty()) {
                        viewModel.moveToSecureFolder(selectedNotes)
                        mode.finish()
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_copy_secure -> {
                    if (selectedNotes.isNotEmpty()) {
                        viewModel.copyToSecureFolder(selectedNotes)
                        mode.finish()
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_share -> {
                    if (selectedNotes.isNotEmpty()) {
                        shareSelectedNotes(selectedNotes)
                        mode.finish()
                    } else {
                        showNoNotesSelectedMessage()
                    }
                    true
                }
                R.id.action_change_folder_color -> {
                    if (selectedFolders.isNotEmpty()) {
                        showFolderColorDialog(selectedFolders) { mode.finish() }
                    } else {
                        showNoFoldersSelectedMessage()
                    }
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            notesAdapter.clearSelections()
            actionMode = null
            updateBackHandlerEnabled()
        }
    }

    private fun shareSelectedNotes(selectedNotes: List<Note>) {
        if (selectedNotes.isNotEmpty()) {
            val shareText = selectedNotes.joinToString("\n\n---\n\n") { note ->
                "${note.title}\n\n${Html.fromHtml(note.content, Html.FROM_HTML_MODE_LEGACY)}"
            }
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, null))
        }
    }

    private fun showMoveDialog(isCopy: Boolean, notes: List<Note>, onComplete: (Int?) -> Unit) {
        lifecycleScope.launch {
            val folders = viewModel.folders.first()
            val folderNames = (listOf("No Folder") + folders.map { it.name }).toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(if (isCopy) "Copy to..." else "Move to...")
                .setItems(folderNames) { _, which ->
                    val selectedFolderId = if (which == 0) null else folders[which - 1].id
                    if (isCopy) {
                        viewModel.copyNotesToFolder(notes, selectedFolderId)
                    } else {
                        viewModel.moveNotesToFolder(notes, selectedFolderId)
                    }
                    onComplete(selectedFolderId)
                }
                .setOnCancelListener { /* keep selection */ }
                .show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.allNotes.collect { notes ->
                allNotes = notes
                updateFolderCounts()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.items.collect { items ->
                itemsList = items
                notesAdapter.updateData(items)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewMode.collect { viewMode ->
                val layoutManager = GridLayoutManager(context, 2)
                layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        if (position < itemsList.size) {
                            return when (notesAdapter.getItemViewType(position)) {
                                NotesAdapter.TYPE_FOLDER -> 1
                                NotesAdapter.TYPE_NOTE -> if (viewMode == ViewMode.LIST) 2 else 1
                                else -> 1
                            }
                        }
                        return 1
                    }
                }
                recyclerView.layoutManager = layoutManager
            }
        }
    }

    private fun setupFabs(view: View) {
        if (getFolderIdArg() != null) {
            view.findViewById<FloatingActionButton>(R.id.fab_new_folder).visibility = View.GONE
        }
        view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            val intent = Intent(requireContext(), AddEditNoteActivity::class.java)
            getFolderIdArg()?.let {
                intent.putExtra(AddEditNoteActivity.EXTRA_FOLDER_ID, it)
                intent.putExtra(AddEditNoteActivity.EXTRA_FROM_FOLDER_CONTEXT, true)
            }
            startActivity(intent)
        }
        view.findViewById<FloatingActionButton>(R.id.fab_new_folder).setOnClickListener {
            showNewFolderDialog()
        }
    }

    private fun setupDragAndSwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = if (viewHolder.itemViewType == NotesAdapter.TYPE_NOTE || viewHolder.itemViewType == NotesAdapter.TYPE_FOLDER) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    0
                }
                val swipeFlags = if (viewHolder.itemViewType == NotesAdapter.TYPE_NOTE) {
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    0
                }
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (target.itemViewType == NotesAdapter.TYPE_FOLDER) {
                    val folder = itemsList.getOrNull(target.adapterPosition) as? Folder
                    if (hoveredFolderId != folder?.id) {
                        hoveredFolderId = folder?.id
                        notesAdapter.setHoveredFolder(hoveredFolderId)
                    }
                } else {
                    if (hoveredFolderId != null) {
                        hoveredFolderId = null
                        notesAdapter.setHoveredFolder(null)
                    }
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = itemsList[position]
                    if (item is Note) {
                        viewModel.deleteNote(item)
                    }
                }
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return target.itemViewType == NotesAdapter.TYPE_FOLDER
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) return
                val position = viewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                val item = itemsList.getOrNull(position)
                val selected = notesAdapter.selectedItems.toList()
                dragItems = if (selected.isNotEmpty() && selected.contains(item)) selected else listOfNotNull(item)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // Subtle scale cue to clarify drag state without visual noise.
                    viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
                    viewHolder.itemView.alpha = 0.95f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                viewHolder.itemView.alpha = 1f
                val targetFolderId = hoveredFolderId
                if (targetFolderId != null && dragItems.isNotEmpty()) {
                    val notesToMove = dragItems.filterIsInstance<Note>()
                    val foldersToMove = dragItems.filterIsInstance<Folder>()
                    if (notesToMove.isNotEmpty()) {
                        viewModel.moveNotesToFolder(notesToMove, targetFolderId)
                    }
                    if (foldersToMove.isNotEmpty()) {
                        foldersToMove.forEach { folder ->
                            if (folder.id != targetFolderId) {
                                viewModel.updateFolder(folder.copy(parentFolderId = targetFolderId))
                            }
                        }
                    }
                    notesAdapter.setRecentlyMovedFolder(targetFolderId)
                }
                dragItems = emptyList()
                hoveredFolderId = null
                notesAdapter.setHoveredFolder(null)
                resetLongPressState()
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun setupGestureHandling() {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (actionMode != null) return
                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val holder = recyclerView.getChildViewHolder(child)
                val position = holder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                longPressTarget = holder
                longPressItem = itemsList[position]
                longPressActive = true
            }
        })

        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                if (e.action == MotionEvent.ACTION_DOWN) {
                    downX = e.x
                    downY = e.y
                }
                if (longPressActive && e.action == MotionEvent.ACTION_MOVE && !dragStarted) {
                    val dx = kotlin.math.abs(e.x - downX)
                    val dy = kotlin.math.abs(e.y - downY)
                    if (dx < touchSlop && dy < touchSlop) {
                        return false
                    }
                    val item = longPressItem
                    if (item is Note || item is Folder) {
                        dragStarted = true
                        longPressActive = false
                        itemTouchHelper.startDrag(longPressTarget!!)
                        return true
                    }
                }
                if (e.action == MotionEvent.ACTION_UP && longPressActive && !dragStarted) {
                    val item = longPressItem
                    val anchor = longPressTarget?.itemView
                    if (item != null && anchor != null) {
                        when (item) {
                            is Note -> showNoteContextMenu(anchor, item)
                            is Folder -> showFolderContextMenu(anchor, item)
                        }
                    }
                    resetLongPressState()
                }
                if (e.action == MotionEvent.ACTION_CANCEL) {
                    resetLongPressState()
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
        })
    }

    private fun resetLongPressState() {
        longPressTarget = null
        longPressItem = null
        longPressActive = false
        dragStarted = false
    }

    private fun startDragFromHandle(holder: RecyclerView.ViewHolder) {
        if (actionMode != null) {
            // Selection mode should not suppress handle-based drag gestures.
        }
        resetLongPressState()
        dragStarted = true
        itemTouchHelper.startDrag(holder)
    }

    fun showNewFolderDialog() {
        val editText = EditText(context)
        AlertDialog.Builder(requireContext())
            .setTitle("New Folder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val folderName = editText.text.toString()
                if (folderName.isNotBlank()) {
                    val parentId = getFolderIdArg()
                    viewModel.addFolderSafely(Folder(name = folderName, parentFolderId = parentId)) { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBackHandler() {
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (actionMode != null) {
                    actionMode?.finish()
                    return
                }
                if (getFolderIdArg() != null) {
                    parentFragmentManager.popBackStack()
                    return
                }
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback!!)
        updateBackHandlerEnabled()
    }

    private fun updateBackHandlerEnabled() {
        backCallback?.isEnabled = actionMode != null || getFolderIdArg() != null
    }

    fun enterSelectionMode() {
        if (actionMode == null) {
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(actionModeCallback)
            updateBackHandlerEnabled()
        }
    }

    private fun showNoteContextMenu(anchor: View, note: Note) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menuInflater.inflate(R.menu.note_context_menu, menu.menu)
        val favoriteItem = menu.menu.findItem(R.id.action_note_favorite)
        favoriteItem.title = if (note.isFavorite) "Remove Favorite" else "Add to Favorite"

        val saveFileItem = menu.menu.findItem(R.id.action_note_save_file)
        applyDisabledRedTitle(saveFileItem, "Save as File")
        applyDisabledRedTitle(menu.menu.findItem(R.id.action_note_move_secure), "Move to Secure Folder")

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_note_details -> {
                    showNoteDetailsDialog(note)
                    true
                }
                R.id.action_note_favorite -> {
                    viewModel.updateNote(note.copy(isFavorite = !note.isFavorite))
                    true
                }
                R.id.action_note_move -> {
                    showMoveDialog(isCopy = false, notes = listOf(note), onComplete = { folderId ->
                        notesAdapter.setRecentlyMovedFolder(folderId)
                    })
                    true
                }
                R.id.action_note_copy -> {
                    showMoveDialog(isCopy = true, notes = listOf(note), onComplete = { folderId ->
                        notesAdapter.setRecentlyMovedFolder(folderId)
                    })
                    true
                }
                R.id.action_note_duplicate -> {
                    viewModel.duplicateNotes(listOf(note))
                    true
                }
                R.id.action_note_share -> {
                    shareSelectedNotes(listOf(note))
                    true
                }
                R.id.action_note_move_secure -> {
                    true
                }
                R.id.action_note_delete -> {
                    viewModel.deleteNote(note)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showFolderContextMenu(anchor: View, folder: Folder) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menuInflater.inflate(R.menu.drawer_folder_context_menu, menu.menu)
        val reorderItem = menu.menu.findItem(R.id.action_reorder_folder)
        applyDisabledRedTitle(reorderItem, "Drag reorder")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_create_subfolder -> {
                    showCreateSubfolderDialog(folder)
                    true
                }
                R.id.action_rename_folder -> {
                    showRenameFolderDialog(folder)
                    true
                }
                R.id.action_delete_folder -> {
                    showDeleteFolderDialog(folder)
                    true
                }
                R.id.action_change_folder_color -> {
                    showSingleFolderColorDialog(folder)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showNoteDetailsDialog(note: Note) {
        val folderName = viewModel.folders.value.firstOrNull { it.id == note.folderId }?.name ?: "No Folder"
        val timestamp = DateFormat.format("MMM d, yyyy h:mm a", note.timestamp)
        val message = "Last edited: $timestamp\nFolder: $folderName"
        AlertDialog.Builder(requireContext())
            .setTitle(note.title.ifBlank { "Untitled" })
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showCreateSubfolderDialog(parent: Folder) {
        val editText = EditText(context)
        editText.hint = "Subfolder name"
        AlertDialog.Builder(requireContext())
            .setTitle("Create subfolder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.addFolderSafely(Folder(name = name, parentFolderId = parent.id)) { message ->
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val editText = EditText(context)
        editText.setText(folder.name)
        AlertDialog.Builder(requireContext())
            .setTitle("Rename folder")
            .setView(editText)
            .setPositiveButton("Rename") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.updateFolder(folder.copy(name = name))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteFolderDialog(folder: Folder) {
        val folders = viewModel.folders.value
        val notes = viewModel.allNotes.value
        val hasContents = hasFolderContents(folder.id, folders, notes)
        if (!hasContents) {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete folder")
                .setMessage("Delete this folder?")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteFolderById(folder.id)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val input = EditText(context)
        input.hint = "Type delete to confirm"
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Folder is not empty. Move or delete its contents first.")
            .setView(input)
            .setPositiveButton("Delete", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            button.setOnClickListener {
                viewModel.deleteFolderWithContents(folder.id)
                dialog.dismiss()
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    button.isEnabled = s?.toString() == "delete"
                }
            })
        }
        dialog.show()
    }

    private fun showSingleFolderColorDialog(folder: Folder) {
        val colorNames = arrayOf("Purple", "Blue", "Green", "Orange", "Red", "Gray")
        val colors = intArrayOf(
            requireContext().getColor(R.color.brand_purple),
            0xFF2962FF.toInt(),
            0xFF2E7D32.toInt(),
            0xFFF57C00.toInt(),
            0xFFD32F2F.toInt(),
            0xFF607D8B.toInt()
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Folder color")
            .setItems(colorNames) { _, which ->
                viewModel.updateFolder(folder.copy(accentColor = colors[which]))
            }
            .show()
    }

    private fun applyDisabledRedTitle(item: MenuItem?, title: String) {
        if (item == null) return
        item.isEnabled = false
        item.title = SpannableString(title).apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.RED), 0, length, 0)
        }
    }

    private fun hasFolderContents(folderId: Int, folders: List<Folder>, notes: List<Note>): Boolean {
        val childFolders = folders.filter { it.parentFolderId == folderId }
        val hasNotes = notes.any { it.folderId == folderId && !it.isDeleted }
        if (hasNotes) return true
        if (childFolders.isEmpty()) return false
        return childFolders.any { hasFolderContents(it.id, folders, notes) }
    }

    private fun getSelectedNotes(): List<Note> {
        return notesAdapter.selectedItems.filterIsInstance<Note>()
    }

    private fun showNoNotesSelectedMessage() {
        Toast.makeText(requireContext(), "Select at least one note", Toast.LENGTH_SHORT).show()
    }

    private fun showNoFoldersSelectedMessage() {
        Toast.makeText(requireContext(), "Select at least one folder", Toast.LENGTH_SHORT).show()
    }

    private fun showDetailsDialog() {
        val selectedNotes = getSelectedNotes()
        if (selectedNotes.size != 1) {
            showNoNotesSelectedMessage()
            return
        }
        val note = selectedNotes.first()
        val folderName = viewModel.folders.value.firstOrNull { it.id == note.folderId }?.name ?: "No Folder"
        val timestamp = DateFormat.format("MMM d, yyyy h:mm a", note.timestamp)
        val secureStatus = if (note.isSecure) "Yes" else "No"
        val message = "Last edited: $timestamp\nFolder: $folderName\nSecure: $secureStatus"
        AlertDialog.Builder(requireContext())
            .setTitle(note.title.ifBlank { "Untitled" })
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFolderColorDialog(folders: List<Folder>, onComplete: () -> Unit) {
        val colorNames = arrayOf("Purple", "Blue", "Green", "Orange", "Red", "Gray")
        val colors = intArrayOf(
            requireContext().getColor(R.color.brand_purple),
            0xFF2962FF.toInt(),
            0xFF2E7D32.toInt(),
            0xFFF57C00.toInt(),
            0xFFD32F2F.toInt(),
            0xFF607D8B.toInt()
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Folder color")
            .setItems(colorNames) { _, which ->
                val color = colors[which]
                folders.forEach { folder ->
                    viewModel.updateFolder(folder.copy(accentColor = color))
                }
                onComplete()
            }
            .setOnCancelListener { /* keep selection */ }
            .show()
    }

    private fun updateFolderCounts() {
        val counts = allNotes
            .filter { !it.isDeleted }
            .groupingBy { it.folderId }
            .eachCount()
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        folderNoteCounts = counts
        notesAdapter.updateFolderCounts(counts)
    }

    protected open fun getFolderIdArg(): Int? {
        val args = arguments ?: return null
        return if (args.containsKey(ARG_FOLDER_ID)) args.getInt(ARG_FOLDER_ID) else null
    }

    companion object {
        const val ARG_FOLDER_ID = "arg_folder_id"
        fun newInstance(folderId: Int?): NotesFragment {
            val fragment = NotesFragment()
            if (folderId != null) {
                val args = Bundle()
                args.putInt(ARG_FOLDER_ID, folderId)
                fragment.arguments = args
            }
            return fragment
        }
    }
}
