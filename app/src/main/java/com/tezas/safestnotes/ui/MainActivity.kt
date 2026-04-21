package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
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
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.tezas.safestnotes.adapter.DrawerFolderItem
import com.tezas.safestnotes.adapter.DrawerFoldersAdapter
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import com.tezas.safestnotes.viewmodel.SortOrder
import com.tezas.safestnotes.viewmodel.ViewMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.tezas.safestnotes.security.SecurityManager
import com.tezas.safestnotes.ui.RecycleBinFragment.Companion.DEFAULT_RETENTION_DAYS
import com.tezas.safestnotes.ui.RecycleBinFragment.Companion.PREF_RETENTION_DAYS

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_NAV_STYLE = "nav_style"
        const val NAV_BOTTOM  = "bottom"
        const val NAV_DRAWER  = "drawer"
    }

    // ── shared ────────────────────────────────────────────────
    private var navMode = NAV_BOTTOM
    private var currentFolderId: Int? = null
    private var lastBackPressedAt: Long = 0L
    private var selectedTabId: Int = R.id.nav_all_notes

    val viewModel: NotesViewModel by viewModels {
        val db = NotesDatabase.getDatabase(application)
        NotesViewModelFactory(NotesRepository(db.noteDao(), db.folderDao(), db.noteRevisionDao()))
    }

    // ── drawer-only ──────────────────────────────────────────
    private var drawerLayout: DrawerLayout? = null
    private var drawerFoldersRecycler: RecyclerView? = null
    private var drawerFoldersAdapter: DrawerFoldersAdapter? = null
    private val expandedFolderIds = mutableSetOf<Int>()
    private var isFoldersExpanded = true

    // ── bottom-nav-only ──────────────────────────────────────
    private var bottomNav: BottomNavigationView? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) Toast.makeText(this, "Notifications permission denied", Toast.LENGTH_SHORT).show()
    }

    // ────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        navMode = prefs.getString(PREF_NAV_STYLE, NAV_BOTTOM) ?: NAV_BOTTOM

        if (navMode == NAV_DRAWER) {
            setContentView(R.layout.activity_main_drawer)
        } else {
            setContentView(R.layout.activity_main)
        }

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        askNotificationPermission()

        if (navMode == NAV_DRAWER) {
            setupDrawerNavigation(toolbar)
        } else {
            setupBottomNavigation()
        }

        // Restore persisted view mode
        val savedMode = prefs.getString("view_mode", "grid")
        viewModel.setViewMode(if (savedMode == "list") ViewMode.LIST else ViewMode.GRID)

        if (savedInstanceState == null) openAllNotes()
        setupBackPressedHandler()

        // Silently purge notes older than the user-configured retention period
        val retentionDays = prefs.getInt(PREF_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        viewModel.purgeExpiredNotes(retentionDays)
    }

    // ── Lifecycle — re-lock after background ─────────────────────────────
    override fun onStart() {
        super.onStart()
        // AutoLockManager may have called sm.lock() while we were in background.
        // If the Secure Vault was visible when the app went away, re-authenticate
        // or navigate back to All Notes.
        val sm = SecurityManager.get()
        if (!sm.isUnlocked() && sm.isMasterPasswordSet()) {
            val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (frag is SecureVaultFragment) {
                UnlockDialog.showWithBiometricFirst(this, sm,
                    onUnlocked = { invalidateOptionsMenu() },
                    onCancel   = {
                        selectedTabId = R.id.nav_all_notes
                        bottomNav?.selectedItemId = R.id.nav_all_notes
                        openAllNotes()
                    }
                )
            }
        }
    }

    // ── DRAWER navigation ────────────────────────────────────
    private fun setupDrawerNavigation(toolbar: Toolbar) {
        drawerLayout = findViewById(R.id.drawer_layout)
        val dl = drawerLayout!!

        val toggle = ActionBarDrawerToggle(
            this, dl, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        dl.addDrawerListener(toggle)
        toggle.syncState()

        setupDrawerItems(dl)

        drawerFoldersRecycler = findViewById(R.id.drawer_folders_recycler)
        drawerFoldersAdapter = DrawerFoldersAdapter(
            emptyList(),
            onToggleExpand = { folder ->
                if (expandedFolderIds.contains(folder.id)) expandedFolderIds.remove(folder.id)
                else expandedFolderIds.add(folder.id)
                refreshDrawerFolders()
            },
            onOpenFolder = { folder ->
                viewModel.setShowFavoritesOnly(false)
                viewModel.setShowDeleted(false)
                openFolder(folder)
                dl.closeDrawer(GravityCompat.START)
            },
            onLongPress = { anchor, folder -> showFolderContextMenu(anchor, folder) }
        )
        drawerFoldersRecycler!!.layoutManager = LinearLayoutManager(this)
        drawerFoldersRecycler!!.adapter = drawerFoldersAdapter
        updateDrawerFolderTreeVisibility()

        lifecycleScope.launch {
            viewModel.folders.collectLatest { refreshDrawerFolders() }
        }
    }

    private fun setupDrawerItems(dl: DrawerLayout) {
        fun closeAndRun(block: () -> Unit) { block(); dl.closeDrawer(GravityCompat.START) }

        findViewById<View>(R.id.drawer_all_notes).setOnClickListener {
            closeAndRun {
                viewModel.setShowFavoritesOnly(false)
                viewModel.setShowDeleted(false)
                openAllNotes()
            }
        }
        findViewById<View>(R.id.drawer_favorites).setOnClickListener {
            closeAndRun {
                viewModel.setShowFavoritesOnly(true)
                viewModel.setShowDeleted(false)
                viewModel.setCurrentFolder(null)
                currentFolderId = null
                navigateTo(FavoritesFragment())
            }
        }
        findViewById<View>(R.id.drawer_recycle_bin).setOnClickListener {
            closeAndRun {
                viewModel.setShowFavoritesOnly(false)
                viewModel.setShowDeleted(true)
                viewModel.setCurrentFolder(null)
                currentFolderId = null
                navigateTo(RecycleBinFragment())
            }
        }
        findViewById<View>(R.id.drawer_settings).setOnClickListener {
            closeAndRun {
                viewModel.setShowFavoritesOnly(false)
                viewModel.setShowDeleted(false)
                viewModel.setCurrentFolder(null)
                currentFolderId = null
                navigateTo(SettingsFragment())
            }
        }
        findViewById<View>(R.id.drawer_folders_toggle).setOnClickListener {
            isFoldersExpanded = !isFoldersExpanded
            updateDrawerFolderTreeVisibility()
        }
    }

    private fun refreshDrawerFolders() {
        val folders = viewModel.folders.value
        drawerFoldersAdapter?.submitItems(buildDrawerItems(folders, null, 0))
    }

    private fun updateDrawerFolderTreeVisibility() {
        drawerFoldersRecycler?.visibility = if (isFoldersExpanded) View.VISIBLE else View.GONE
    }

    private fun buildDrawerItems(folders: List<Folder>, parentId: Int?, depth: Int): List<DrawerFolderItem> {
        if (depth >= 3) return emptyList()
        return folders.filter { it.parentFolderId == parentId }.flatMap { folder ->
            val hasChildren = folders.any { it.parentFolderId == folder.id }
            val expanded = expandedFolderIds.contains(folder.id)
            listOf(DrawerFolderItem(folder, depth, hasChildren, expanded)) +
                if (expanded) buildDrawerItems(folders, folder.id, depth + 1) else emptyList()
        }
    }

    // ── BOTTOM NAV navigation ────────────────────────────────
    private fun setupBottomNavigation() {
        bottomNav = findViewById(R.id.bottom_nav)
        // Sync internal state with the actual selected item (could differ after restore)
        selectedTabId = bottomNav!!.selectedItemId
        bottomNav!!.setOnItemSelectedListener { item ->
            if (item.itemId == selectedTabId) return@setOnItemSelectedListener false
            selectedTabId = item.itemId
            when (item.itemId) {
                R.id.nav_all_notes -> {
                    viewModel.setShowFavoritesOnly(false)
                    viewModel.setShowDeleted(false)
                    openAllNotes()
                }
                R.id.nav_favorites -> {
                    viewModel.setShowFavoritesOnly(true)
                    viewModel.setShowDeleted(false)
                    viewModel.setCurrentFolder(null)
                    currentFolderId = null
                    navigateTo(FavoritesFragment())
                }
                R.id.nav_secure_vault -> {
                    val sm = SecurityManager.get()
                    if (!sm.isMasterPasswordSet()) {
                        AlertDialog.Builder(this)
                            .setTitle("Secure Vault")
                            .setMessage("Set a master password in Settings → Security to enable Secure Vault.")
                            .setPositiveButton("OK", null).show()
                        // Revert selection to previous tab
                        selectedTabId = item.itemId  // accept — it stays
                        return@setOnItemSelectedListener false
                    }
                    viewModel.setShowFavoritesOnly(false)
                    viewModel.setShowDeleted(false)
                    viewModel.setCurrentFolder(null)
                    currentFolderId = null
                    if (!sm.isUnlocked()) {
                        UnlockDialog.showWithBiometricFirst(this, sm,
                            onUnlocked = {
                                selectedTabId = R.id.nav_secure_vault
                                navigateTo(SecureVaultFragment())
                                invalidateOptionsMenu()
                            },
                            onCancel = {
                                // Revert bottom nav to the previous tab
                                selectedTabId = R.id.nav_all_notes
                                bottomNav?.selectedItemId = R.id.nav_all_notes
                            }
                        )
                        return@setOnItemSelectedListener false
                    }
                    navigateTo(SecureVaultFragment())
                }
                R.id.nav_recycle_bin -> {
                    viewModel.setShowFavoritesOnly(false)
                    viewModel.setShowDeleted(true)
                    viewModel.setCurrentFolder(null)
                    currentFolderId = null
                    navigateTo(RecycleBinFragment())
                }
                R.id.nav_settings -> {
                    viewModel.setShowFavoritesOnly(false)
                    viewModel.setShowDeleted(false)
                    viewModel.setCurrentFolder(null)
                    currentFolderId = null
                    navigateTo(SettingsFragment())
                }
            }
            true
        }
    }

    // ── shared navigation ────────────────────────────────────
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun openAllNotes() {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        viewModel.setCurrentFolder(null)
        currentFolderId = null
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            .replace(R.id.fragment_container, NotesFragment()).commit()
        invalidateOptionsMenu()
    }

    fun openFolder(folder: Folder) {
        viewModel.setCurrentFolder(folder.id)
        currentFolderId = folder.id
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_left,  R.anim.slide_out_right
            )
            .replace(R.id.fragment_container, FolderNotesFragment.newInstance(folder.id))
            .addToBackStack(null)
            .commit()
        invalidateOptionsMenu()
    }

    private fun navigateTo(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            .replace(R.id.fragment_container, fragment)
            .commit()
        invalidateOptionsMenu()
    }

    fun getCurrentFolderId(): Int? = currentFolderId
    fun isAtRoot(): Boolean = currentFolderId == null

    // ── toolbar menu ─────────────────────────────────────────
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty()); return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isSettings   = frag is SettingsFragment
        val isRecycleBin = frag is RecycleBinFragment
        val showToolbar  = !isSettings && !isRecycleBin
        menu.findItem(R.id.action_options)?.isVisible = showToolbar
        menu.findItem(R.id.action_search)?.isVisible  = showToolbar
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        when (item.itemId) {
            R.id.action_view_grid  -> { viewModel.setViewMode(ViewMode.GRID); prefs.edit().putString("view_mode","grid").apply() }
            R.id.action_view_list  -> { viewModel.setViewMode(ViewMode.LIST); prefs.edit().putString("view_mode","list").apply() }
            R.id.action_sort_title_asc         -> viewModel.setSortOrder(SortOrder.TITLE_ASC)
            R.id.action_sort_title_desc        -> viewModel.setSortOrder(SortOrder.TITLE_DESC)
            R.id.action_sort_size_letters_asc  -> viewModel.setSortOrder(SortOrder.SIZE_LETTERS_ASC)
            R.id.action_sort_size_letters_desc -> viewModel.setSortOrder(SortOrder.SIZE_LETTERS_DESC)
            R.id.action_sort_size_kb_asc       -> viewModel.setSortOrder(SortOrder.SIZE_KB_ASC)
            R.id.action_sort_size_kb_desc      -> viewModel.setSortOrder(SortOrder.SIZE_KB_DESC)
            R.id.action_sort_date_created_asc  -> viewModel.setSortOrder(SortOrder.DATE_CREATED_ASC)
            R.id.action_sort_date_created_desc -> viewModel.setSortOrder(SortOrder.DATE_CREATED_DESC)
            R.id.action_sort_date_modified_asc  -> viewModel.setSortOrder(SortOrder.DATE_MODIFIED_ASC)
            R.id.action_sort_date_modified_desc -> viewModel.setSortOrder(SortOrder.DATE_MODIFIED_DESC)
            R.id.action_select_mode -> {
                (supportFragmentManager.findFragmentById(R.id.fragment_container) as? NotesFragment)
                    ?.enterSelectionMode()
            }
            R.id.action_create_folder -> {
                (supportFragmentManager.findFragmentById(R.id.fragment_container) as? NotesFragment)
                    ?.showNewFolderDialog()
            }
            R.id.action_manage_folders -> {
                navigateTo(FoldersFragment())
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ── folder management dialogs (public — called from fragments) ──
    fun showCreateSubfolderDialog(parent: Folder) {
        val et = EditText(this).apply { hint = "Subfolder name" }
        AlertDialog.Builder(this).setTitle("Create subfolder").setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) viewModel.addFolderSafely(Folder(name = name, parentFolderId = parent.id)) {
                    Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun showRenameFolderDialog(folder: Folder) {
        val et = EditText(this).apply { setText(folder.name) }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) viewModel.updateFolder(folder.copy(name = name))
            }.setNegativeButton("Cancel", null).show()
    }

    fun showDeleteFolderDialog(folder: Folder) {
        val folders = viewModel.folders.value
        val notes = viewModel.allNotes.value
        if (!hasFolderContents(folder.id, folders, notes)) {
            AlertDialog.Builder(this).setTitle("Delete folder").setMessage("Delete this folder?")
                .setPositiveButton("Delete") { _, _ -> viewModel.deleteFolderById(folder.id) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val input = EditText(this).apply { hint = "Type delete to confirm" }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Folder is not empty. Move or delete its contents first.")
            .setView(input).setPositiveButton("Delete", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
            btn.setOnClickListener { viewModel.deleteFolderWithContents(folder.id); dialog.dismiss() }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { btn.isEnabled = s?.toString() == "delete" }
            })
        }
        dialog.show()
    }

    fun showFolderColorDialog(folder: Folder) {
        val names  = arrayOf("Purple", "Blue", "Green", "Orange", "Red", "Gray")
        val colors = intArrayOf(
            getColor(R.color.purple_primary), 0xFF2962FF.toInt(), 0xFF2E7D32.toInt(),
            0xFFF57C00.toInt(), 0xFFD32F2F.toInt(), 0xFF607D8B.toInt()
        )
        AlertDialog.Builder(this).setTitle("Folder color")
            .setItems(names) { _, i -> viewModel.updateFolder(folder.copy(accentColor = colors[i])) }
            .show()
    }

    private fun showFolderContextMenu(anchor: View, folder: Folder) {
        PopupMenu(this, anchor).also { menu ->
            menu.menuInflater.inflate(R.menu.drawer_folder_context_menu, menu.menu)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_create_subfolder    -> { showCreateSubfolderDialog(folder); true }
                    R.id.action_rename_folder       -> { showRenameFolderDialog(folder); true }
                    R.id.action_delete_folder       -> { showDeleteFolderDialog(folder); true }
                    R.id.action_change_folder_color -> { showFolderColorDialog(folder); true }
                    else -> false
                }
            }
        }.show()
    }

    private fun hasFolderContents(id: Int, folders: List<Folder>, notes: List<com.tezas.safestnotes.data.Note>): Boolean {
        if (notes.any { it.folderId == id && !it.isDeleted }) return true
        return folders.filter { it.parentFolderId == id }.any { hasFolderContents(it.id, folders, notes) }
    }

    // ── back press ───────────────────────────────────────────
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Drawer: close drawer first
                drawerLayout?.let { dl ->
                    if (dl.isDrawerOpen(GravityCompat.START)) {
                        dl.closeDrawer(GravityCompat.START); return
                    }
                }
                // Back stack (folder drill-down)
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    viewModel.setCurrentFolder(null)
                    viewModel.setShowDeleted(false)
                    viewModel.setShowFavoritesOnly(false)
                    currentFolderId = null
                    return
                }
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                // In bottom nav mode, non-home tab → go home
                if (fragment !is NotesFragment && navMode == NAV_BOTTOM) {
                    bottomNav?.selectedItemId = R.id.nav_all_notes
                    selectedTabId = R.id.nav_all_notes
                    return
                }
                if (fragment !is NotesFragment && navMode == NAV_DRAWER) {
                    openAllNotes(); return
                }
                // Double back to exit
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
