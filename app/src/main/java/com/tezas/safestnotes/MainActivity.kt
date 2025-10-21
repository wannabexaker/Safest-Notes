package com.tezas.safestnotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.NotesDatabase
import com.tezas.safestnotes.data.NotesRepository
import com.tezas.safestnotes.viewmodel.NotesViewModel
import com.tezas.safestnotes.viewmodel.NotesViewModelFactory
import com.tezas.safestnotes.viewmodel.SortOrder

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private var currentFolderId: Int? = null
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
        val navView: NavigationView = findViewById(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        if (savedInstanceState == null) {
            openAllNotes()
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
        viewModel.setCurrentFolder(null)
        currentFolderId = null
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, NotesFragment()).commit()
    }

    fun openFolder(folder: Folder) {
        viewModel.setCurrentFolder(folder.id)
        currentFolderId = folder.id
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, NotesFragment())
            .addToBackStack(null) // Allow user to go back to the parent folder
            .commit()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view_mode -> viewModel.toggleViewMode()
            R.id.action_sort_by -> showSortDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showSortDialog() {
        val options = arrayOf("By Date", "By Name")
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.setSortOrder(SortOrder.DATE)
                    1 -> viewModel.setSortOrder(SortOrder.NAME)
                }
            }
            .show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        viewModel.setShowFavoritesOnly(false)
        viewModel.setShowDeleted(false)
        
        when (item.itemId) {
            R.id.nav_all_notes -> openAllNotes()
            R.id.nav_favorites -> {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, FavoritesFragment()).commit()
            }
            R.id.nav_recycle_bin -> {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, RecycleBinFragment()).commit()
            }
            R.id.nav_settings -> {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, SettingsFragment()).commit()
            }
            R.id.nav_safest_notes -> Toast.makeText(this, "Safest Notes Clicked", Toast.LENGTH_SHORT).show()
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            if(supportFragmentManager.backStackEntryCount > 0){
                supportFragmentManager.popBackStack()
            } else {
                super.onBackPressed()
            }
        }
    }
}