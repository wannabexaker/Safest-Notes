package com.tezas.safestnotes.ui

// Responsibility: Notes grid interactions, context menus, and drag behavior.
// Feature area: Selection UX + long-press/drag logic.

import com.tezas.safestnotes.R

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.os.Bundle
import android.text.Html
import android.text.format.DateFormat
import android.view.*
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.tezas.safestnotes.adapter.NotesAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.viewmodel.NotesViewModel
import androidx.preference.PreferenceManager
import com.tezas.safestnotes.security.SecurityManager
import com.tezas.safestnotes.viewmodel.SortOrder
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
    private var isSelectionMode = false
    private lateinit var bottomSelectionBar: MaterialCardView
    private lateinit var selectionCountLabel: TextView
    private lateinit var emptyState: View
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
        bottomSelectionBar = view.findViewById(R.id.bottom_selection_bar)
        selectionCountLabel = view.findViewById(R.id.selection_count_label)
        emptyState = view.findViewById(R.id.empty_state)
        touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        applyUserPreferences()
        setupRecyclerView()
        observeViewModel()
        setupFabs(view)
        setupBottomSelectionBar(view)
        setupDragAndSwipe()
        setupBackHandler()
        viewModel.setCurrentFolder(getFolderIdArg())
    }

    private fun applyUserPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        // Default view mode
        val viewModePref = prefs.getString("default_view_mode", "grid")
        viewModel.setViewMode(if (viewModePref == "list") ViewMode.LIST else ViewMode.GRID)
        // Default sort order
        val sortPref = prefs.getString("default_sort_order", "DATE_MODIFIED_DESC")
        val sortOrder = runCatching { SortOrder.valueOf(sortPref ?: "DATE_MODIFIED_DESC") }
            .getOrDefault(SortOrder.DATE_MODIFIED_DESC)
        viewModel.setSortOrder(sortOrder)
    }

    private fun setupRecyclerView() {
        notesAdapter = NotesAdapter(itemsList, folderNoteCounts,
            onNoteClick = { note, position ->
                if (isSelectionMode) {
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
                if (isSelectionMode) {
                    toggleSelection(folder, -1)
                } else {
                    openFolderWithSecureCheck(folder)
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
            exitSelectionMode()
        } else {
            selectionCountLabel.text = "$count selected"
        }
        updateBackHandlerEnabled()
    }

    private fun setupBottomSelectionBar(view: View) {
        view.findViewById<View>(R.id.btn_exit_selection).setOnClickListener {
            exitSelectionMode()
        }
        view.findViewById<View>(R.id.btn_selection_star).setOnClickListener {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            if (selectedNotes.isNotEmpty()) {
                val allFavorite = selectedNotes.all { it.isFavorite }
                selectedNotes.forEach { viewModel.updateNote(it.copy(isFavorite = !allFavorite)) }
                exitSelectionMode()
            }
        }
        view.findViewById<View>(R.id.btn_selection_share).setOnClickListener {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            if (selectedNotes.isNotEmpty()) {
                shareSelectedNotes(selectedNotes)
                exitSelectionMode()
            } else showNoNotesSelectedMessage()
        }
        view.findViewById<View>(R.id.btn_selection_move).setOnClickListener {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            if (selectedNotes.isNotEmpty()) {
                showMoveDialog(isCopy = false, notes = selectedNotes, onComplete = { folderId ->
                    notesAdapter.setRecentlyMovedFolder(folderId)
                    exitSelectionMode()
                })
            } else showNoNotesSelectedMessage()
        }
        view.findViewById<View>(R.id.btn_selection_duplicate).setOnClickListener {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            if (selectedNotes.isNotEmpty()) {
                viewModel.duplicateNotes(selectedNotes)
                exitSelectionMode()
            } else showNoNotesSelectedMessage()
        }
        view.findViewById<View>(R.id.btn_selection_delete).setOnClickListener {
            val selectedNotes = notesAdapter.selectedItems.filterIsInstance<Note>()
            if (selectedNotes.isNotEmpty()) {
                viewModel.deleteMultiple(selectedNotes)
                exitSelectionMode()
            } else showNoNotesSelectedMessage()
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
                emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewMode.collect { viewMode ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val cols = prefs.getString("grid_columns", "2")?.toIntOrNull() ?: 2
                val layoutManager = GridLayoutManager(context, cols)
                layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        if (position < itemsList.size) {
                            return when (notesAdapter.getItemViewType(position)) {
                                NotesAdapter.TYPE_FOLDER -> 1
                                NotesAdapter.TYPE_NOTE -> if (viewMode == ViewMode.LIST) cols else 1
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
            val folderId = getFolderIdArg()
            folderId?.let {
                intent.putExtra(AddEditNoteActivity.EXTRA_FOLDER_ID, it)
                intent.putExtra(AddEditNoteActivity.EXTRA_FROM_FOLDER_CONTEXT, true)
                val folder = viewModel.folders.value.firstOrNull { f -> f.id == it }
                if (folder?.isSecure == true) {
                    intent.putExtra(AddEditNoteActivity.EXTRA_IS_SECURE_FOLDER, true)
                }
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
                        viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (direction == ItemTouchHelper.LEFT) {
                            viewModel.deleteNote(item)
                            Snackbar.make(recyclerView, "Note moved to Recycle Bin", Snackbar.LENGTH_LONG)
                                .setAction("Undo") { viewModel.restoreNote(item) }
                                .setActionTextColor(ContextCompat.getColor(requireContext(), R.color.purple_light))
                                .show()
                        } else {
                            val nowFav = !item.isFavorite
                            viewModel.updateNote(item.copy(isFavorite = nowFav))
                            val msg = if (nowFav) "Added to favorites ★" else "Removed from favorites"
                            Snackbar.make(recyclerView, msg, Snackbar.LENGTH_SHORT).show()
                            notesAdapter.notifyItemChanged(position)
                        }
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE
                    && viewHolder.itemViewType == NotesAdapter.TYPE_NOTE) {

                    val iv   = viewHolder.itemView
                    val ctx  = requireContext()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    val swipeRatio = (kotlin.math.abs(dX) / iv.width).coerceIn(0f, 1f)
                    // Background corners follow card corners (14dp)
                    val cornerRadius = 14f * resources.displayMetrics.density

                    if (dX < 0) {
                        // LEFT → delete (red)
                        paint.color = ContextCompat.getColor(ctx, R.color.danger_red)
                        // Fade bg from 60% → 100% alpha as swipe progresses
                        paint.alpha = (153 + (102 * swipeRatio)).toInt().coerceIn(0, 255)
                        val rect = android.graphics.RectF(
                            iv.right + dX, iv.top.toFloat(),
                            iv.right.toFloat(), iv.bottom.toFloat())
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

                        // Trash icon — grows from 0 to 26dp as swipe progresses
                        val iconSize = ((26 * swipeRatio) * resources.displayMetrics.density)
                            .toInt().coerceAtLeast(0)
                        if (iconSize > 8) {
                            ContextCompat.getDrawable(ctx, R.drawable.ic_delete)?.let { icon ->
                                icon.setTint(android.graphics.Color.WHITE)
                                val cx = (iv.right + dX / 2).toInt()
                                val cy = (iv.top + iv.height / 2)
                                icon.setBounds(cx - iconSize/2, cy - iconSize/2,
                                               cx + iconSize/2, cy + iconSize/2)
                                icon.alpha = (255 * swipeRatio).toInt().coerceIn(0, 255)
                                icon.draw(c)
                            }
                        }

                    } else if (dX > 0) {
                        // RIGHT → favorite (gold)
                        paint.color = ContextCompat.getColor(ctx, R.color.favorite_gold)
                        paint.alpha = (153 + (102 * swipeRatio)).toInt().coerceIn(0, 255)
                        val rect = android.graphics.RectF(
                            iv.left.toFloat(), iv.top.toFloat(),
                            iv.left + dX, iv.bottom.toFloat())
                        c.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

                        val iconSize = ((26 * swipeRatio) * resources.displayMetrics.density)
                            .toInt().coerceAtLeast(0)
                        if (iconSize > 8) {
                            ContextCompat.getDrawable(ctx, R.drawable.ic_star)?.let { icon ->
                                icon.setTint(android.graphics.Color.WHITE)
                                val cx = (iv.left + dX / 2).toInt()
                                val cy = (iv.top + iv.height / 2)
                                icon.setBounds(cx - iconSize/2, cy - iconSize/2,
                                               cx + iconSize/2, cy + iconSize/2)
                                icon.alpha = (255 * swipeRatio).toInt().coerceIn(0, 255)
                                icon.draw(c)
                            }
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
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
                if (isSelectionMode) return
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
                    return true // consume event — prevents click from firing alongside context menu
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
                if (isSelectionMode) {
                    exitSelectionMode()
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
        backCallback?.isEnabled = isSelectionMode || getFolderIdArg() != null
    }

    fun enterSelectionMode() {
        if (!isSelectionMode) {
            isSelectionMode = true
            bottomSelectionBar.visibility = View.VISIBLE
            selectionCountLabel.text = "0 selected"
            view?.findViewById<FloatingActionButton>(R.id.fab)?.hide()
            view?.findViewById<FloatingActionButton>(R.id.fab_new_folder)?.hide()
            updateBackHandlerEnabled()
        }
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        notesAdapter.clearSelections()
        bottomSelectionBar.visibility = View.GONE
        view?.findViewById<FloatingActionButton>(R.id.fab)?.show()
        view?.findViewById<FloatingActionButton>(R.id.fab_new_folder)?.show()
        updateBackHandlerEnabled()
    }

    private fun showNoteContextMenu(anchor: View, note: Note) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menuInflater.inflate(R.menu.note_context_menu, menu.menu)
        val favoriteItem = menu.menu.findItem(R.id.action_note_favorite)
        favoriteItem.title = if (note.isFavorite) "Remove Favorite" else "Add to Favorites"
        menu.menu.findItem(R.id.action_note_pin)?.title = if (note.isPinned) "Unpin" else "Pin to top"

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_note_select -> {
                    enterSelectionMode()
                    notesAdapter.toggleSelection(note)
                    selectionCountLabel.text = "1 selected"
                    updateBackHandlerEnabled()
                    true
                }
                R.id.action_note_pin -> {
                    viewModel.togglePin(note)
                    val msg = if (note.isPinned) "Unpinned" else "Pinned to top 📌"
                    Snackbar.make(recyclerView, msg, Snackbar.LENGTH_SHORT).show()
                    true
                }
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
                R.id.action_note_duplicate -> {
                    viewModel.duplicateNotes(listOf(note))
                    true
                }
                R.id.action_note_share -> {
                    shareSelectedNotes(listOf(note))
                    true
                }
                R.id.action_note_move_secure -> {
                    val sm = SecurityManager.get()
                    if (!sm.isMasterPasswordSet()) {
                        Toast.makeText(requireContext(), "Set up a master password in Settings first", Toast.LENGTH_SHORT).show()
                    } else if (!sm.isUnlocked()) {
                        UnlockDialog.showWithBiometricFirst(
                            activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                            securityManager = sm,
                            onUnlocked = { viewModel.moveToSecureFolder(listOf(note)) },
                            onCancel = {}
                        )
                    } else {
                        viewModel.moveToSecureFolder(listOf(note))
                        Snackbar.make(recyclerView, "Moved to Secure Folder 🔒", Snackbar.LENGTH_SHORT).show()
                    }
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
        val secureItem = menu.menu.findItem(R.id.action_toggle_secure_folder)
        secureItem?.title = if (folder.isSecure) "Remove folder security" else "Make secure folder"
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
                R.id.action_toggle_secure_folder -> {
                    toggleFolderSecurity(folder)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun openFolderWithSecureCheck(folder: Folder) {
        val sm = SecurityManager.get()
        if (folder.isSecure && sm.isMasterPasswordSet() && !sm.isUnlocked()) {
            UnlockDialog.showWithBiometricFirst(
                activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                securityManager = sm,
                onUnlocked = { (activity as? MainActivity)?.openFolder(folder) },
                onCancel = { /* stay on current screen */ }
            )
        } else {
            (activity as? MainActivity)?.openFolder(folder)
        }
    }

    private fun toggleFolderSecurity(folder: Folder) {
        val sm = SecurityManager.get()
        if (!sm.isMasterPasswordSet()) {
            Toast.makeText(requireContext(), "Set up a master password in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        if (folder.isSecure) {
            // Remove security — require unlock first
            if (!sm.isUnlocked()) {
                UnlockDialog.showWithBiometricFirst(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    securityManager = sm,
                    onUnlocked = { doRemoveFolderSecurity(folder) },
                    onCancel = {}
                )
            } else {
                doRemoveFolderSecurity(folder)
            }
        } else {
            // Make secure — require unlock first
            if (!sm.isUnlocked()) {
                UnlockDialog.showWithBiometricFirst(
                    activity = requireActivity() as androidx.fragment.app.FragmentActivity,
                    securityManager = sm,
                    onUnlocked = { doMakeFolderSecure(folder) },
                    onCancel = {}
                )
            } else {
                doMakeFolderSecure(folder)
            }
        }
    }

    private fun doMakeFolderSecure(folder: Folder) {
        viewModel.updateFolder(folder.copy(isSecure = true))
        // Encrypt all notes in this folder
        lifecycleScope.launch {
            val sm = SecurityManager.get()
            val notes = viewModel.allNotes.value.filter { it.folderId == folder.id && !it.isSecure }
            notes.forEach { note ->
                try {
                    val (ciphertext, meta) = sm.encryptNote(note.content)
                    viewModel.updateNote(note.copy(content = ciphertext, secureMetadata = meta, isSecure = true))
                } catch (_: Exception) { /* skip note if encryption fails */ }
            }
        }
        Toast.makeText(requireContext(), "Folder is now secure", Toast.LENGTH_SHORT).show()
    }

    private fun doRemoveFolderSecurity(folder: Folder) {
        viewModel.updateFolder(folder.copy(isSecure = false))
        // Decrypt all notes in this folder
        lifecycleScope.launch {
            val sm = SecurityManager.get()
            val notes = viewModel.allNotes.value.filter { it.folderId == folder.id && it.isSecure }
            notes.forEach { note ->
                try {
                    val plaintext = sm.decryptNote(note.content, note.secureMetadata ?: return@forEach)
                    viewModel.updateNote(note.copy(content = plaintext, secureMetadata = null, isSecure = false))
                } catch (_: Exception) { /* skip if decrypt fails */ }
            }
        }
        Toast.makeText(requireContext(), "Folder security removed", Toast.LENGTH_SHORT).show()
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
