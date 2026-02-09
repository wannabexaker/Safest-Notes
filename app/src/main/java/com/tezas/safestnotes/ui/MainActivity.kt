package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tezas.safestnotes.adapter.DrawerFolderItem
import com.tezas.safestnotes.adapter.DrawerFoldersAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import com.tezas.safestnotes.viewmodel.SortOrder
import com.tezas.safestnotes.viewmodel.ViewMode
import androidx.appcompat.widget.PopupMenu

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerFoldersRecycler: RecyclerView
    private lateinit var drawerFoldersAdapter: DrawerFoldersAdapter
    private var expandedFolderIds = mutableSetOf<Int>()
    private var currentFolderId: Int? = null
    private var lastBackPressedAt: Long = 0L
    private var isFoldersExpanded: Boolean = true
    private val viewModel: NotesViewModel by viewModels { 
        val database = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(database.noteDao(), database.folderDao()))
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notifications permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        askNotificationPermission()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        setupDrawerContent()

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        if (savedInstanceState == null) {
            openAllNotes()
        }

        setupBackPressedHandler()
    }

    private fun setupDrawerContent() {
        findViewById<View>(R.id.drawer_all_notes).setOnClickListener {
            viewModel.setShowFavoritesOnly(false)
            viewModel.setShowDeleted(false)
            openAllNotes()
            invalidateOptionsMenu()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.drawer_favorites).setOnClickListener {
            viewModel.setShowFavoritesOnly(true)
            viewModel.setShowDeleted(false)
            viewModel.setCurrentFolder(null)
            currentFolderId = null
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FavoritesFragment()).commit()
            invalidateOptionsMenu()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.drawer_recycle_bin).setOnClickListener {
            viewModel.setShowFavoritesOnly(false)
            viewModel.setShowDeleted(true)
            viewModel.setCurrentFolder(null)
            currentFolderId = null
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RecycleBinFragment()).commit()
            invalidateOptionsMenu()
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.drawer_settings).setOnClickListener {
            viewModel.setShowFavoritesOnly(false)
            viewModel.setShowDeleted(false)
            viewModel.setCurrentFolder(null)
            currentFolderId = null
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SettingsFragment()).commit()
            invalidateOptionsMenu()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.drawer_folders_toggle).setOnClickListener {
            isFoldersExpanded = !isFoldersExpanded
            updateFolderTreeVisibility()
        }

        drawerFoldersRecycler = findViewById(R.id.drawer_folders_recycler)
        drawerFoldersAdapter = DrawerFoldersAdapter(emptyList(),
            onToggleExpand = { folder ->
                if (expandedFolderIds.contains(folder.id)) {
                    expandedFolderIds.remove(folder.id)
                } else {
                    expandedFolderIds.add(folder.id)
                }
                refreshDrawerFolders()
            },
            onOpenFolder = { folder ->
                viewModel.setShowFavoritesOnly(false)
                viewModel.setShowDeleted(false)
                openFolder(folder)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onLongPress = { anchor, folder ->
                showFolderContextMenu(anchor, folder)
            }
        )
        drawerFoldersRecycler.layoutManager = LinearLayoutManager(this)
        drawerFoldersRecycler.adapter = drawerFoldersAdapter
        updateFolderTreeVisibility()

        lifecycleScope.launch {
            viewModel.folders.collectLatest {
                refreshDrawerFolders()
            }
        }
    }
    
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    fun openAllNotes(){
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        viewModel.setCurrentFolder(null)
        currentFolderId = null
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, NotesFragment()).commit()
        invalidateOptionsMenu()
    }

    fun openFolder(folder: Folder) {
        viewModel.setCurrentFolder(folder.id)
        currentFolderId = folder.id
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, FolderNotesFragment.newInstance(folder.id))
            .addToBackStack(null) // Allow user to go back to the parent folder
            .commit()
        invalidateOptionsMenu()
    }
    
    fun getCurrentFolderId(): Int? = currentFolderId
    fun isAtRoot(): Boolean = currentFolderId == null

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isSettings = supportFragmentManager.findFragmentById(R.id.fragment_container) is SettingsFragment
        menu.findItem(R.id.action_options)?.isVisible = !isSettings
        menu.findItem(R.id.action_search)?.isVisible = !isSettings
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view_grid -> viewModel.setViewMode(ViewMode.GRID)
            R.id.action_view_list -> viewModel.setViewMode(ViewMode.LIST)
            R.id.action_sort_title_asc -> viewModel.setSortOrder(SortOrder.TITLE_ASC)
            R.id.action_sort_title_desc -> viewModel.setSortOrder(SortOrder.TITLE_DESC)
            R.id.action_sort_size_letters_asc -> viewModel.setSortOrder(SortOrder.SIZE_LETTERS_ASC)
            R.id.action_sort_size_letters_desc -> viewModel.setSortOrder(SortOrder.SIZE_LETTERS_DESC)
            R.id.action_sort_size_kb_asc -> viewModel.setSortOrder(SortOrder.SIZE_KB_ASC)
            R.id.action_sort_size_kb_desc -> viewModel.setSortOrder(SortOrder.SIZE_KB_DESC)
            R.id.action_sort_date_created_asc -> viewModel.setSortOrder(SortOrder.DATE_CREATED_ASC)
            R.id.action_sort_date_created_desc -> viewModel.setSortOrder(SortOrder.DATE_CREATED_DESC)
            R.id.action_sort_date_modified_asc -> viewModel.setSortOrder(SortOrder.DATE_MODIFIED_ASC)
            R.id.action_sort_date_modified_desc -> viewModel.setSortOrder(SortOrder.DATE_MODIFIED_DESC)
            R.id.action_select_mode -> {
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (fragment is NotesFragment) {
                    fragment.enterSelectionMode()
                }
            }
            R.id.action_create_folder -> {
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (fragment is NotesFragment) {
                    fragment.showNewFolderDialog()
                }
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun refreshDrawerFolders() {
        val folders = viewModel.folders.value
        val items = buildDrawerItems(folders, null, 0)
        drawerFoldersAdapter.submitItems(items)
    }

    private fun updateFolderTreeVisibility() {
        drawerFoldersRecycler.visibility = if (isFoldersExpanded) View.VISIBLE else View.GONE
    }

    private fun buildDrawerItems(
        folders: List<Folder>,
        parentId: Int?,
        depth: Int
    ): List<DrawerFolderItem> {
        if (depth >= 3) return emptyList()
        val children = folders.filter { it.parentFolderId == parentId }
        val items = mutableListOf<DrawerFolderItem>()
        for (folder in children) {
            val hasChildren = folders.any { it.parentFolderId == folder.id }
            val expanded = expandedFolderIds.contains(folder.id)
            items.add(DrawerFolderItem(folder, depth, hasChildren, expanded))
            if (expanded) {
                items.addAll(buildDrawerItems(folders, folder.id, depth + 1))
            }
        }
        return items
    }

    private fun showFolderContextMenu(anchor: View, folder: Folder) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.drawer_folder_context_menu, menu.menu)
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
                    showFolderColorDialog(folder)
                    true
                }
                R.id.action_reorder_folder -> {
                    Toast.makeText(this, "Use drag handle to reorder (coming next step).", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun showCreateSubfolderDialog(parent: Folder) {
        val editText = EditText(this)
        editText.hint = "Subfolder name"
        AlertDialog.Builder(this)
            .setTitle("Create subfolder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotBlank()) {
                    viewModel.addFolderSafely(Folder(name = name, parentFolderId = parent.id)) { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val editText = EditText(this)
        editText.setText(folder.name)
        AlertDialog.Builder(this)
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
            AlertDialog.Builder(this)
                .setTitle("Delete folder")
                .setMessage("Delete this folder?")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.deleteFolderById(folder.id)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val input = EditText(this)
        input.hint = "Type delete to confirm"
        val dialog = AlertDialog.Builder(this)
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
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    button.isEnabled = s?.toString() == "delete"
                }
            })
        }
        dialog.show()
    }

    private fun showFolderColorDialog(folder: Folder) {
        val colorNames = arrayOf("Purple", "Blue", "Green", "Orange", "Red", "Gray")
        val colors = intArrayOf(
            getColor(R.color.brand_purple),
            0xFF2962FF.toInt(),
            0xFF2E7D32.toInt(),
            0xFFF57C00.toInt(),
            0xFFD32F2F.toInt(),
            0xFF607D8B.toInt()
        )
        AlertDialog.Builder(this)
            .setTitle("Folder color")
            .setItems(colorNames) { _, which ->
                viewModel.updateFolder(folder.copy(accentColor = colors[which]))
            }
            .show()
    }

    private fun hasFolderContents(folderId: Int, folders: List<Folder>, notes: List<com.tezas.safestnotes.data.Note>): Boolean {
        val childFolders = folders.filter { it.parentFolderId == folderId }
        val hasNotes = notes.any { it.folderId == folderId && !it.isDeleted }
        if (hasNotes) return true
        if (childFolders.isEmpty()) return false
        return childFolders.any { hasFolderContents(it.id, folders, notes) }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return
                }
                val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    viewModel.setCurrentFolder(null)
                    currentFolderId = null
                    return
                }
                if (currentFragment !is NotesFragment) {
                    openAllNotes()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt <= 2000) {
                    finish()
                } else {
                    lastBackPressedAt = now
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
