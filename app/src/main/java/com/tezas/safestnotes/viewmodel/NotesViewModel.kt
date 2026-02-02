package com.tezas.safestnotes.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tezas.safestnotes.data.Folder
import com.tezas.safestnotes.data.Note
import com.tezas.safestnotes.data.NotesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortOrder { DATE, NAME }
enum class ViewMode { GRID, LIST }

private data class UiState( 
    val searchQuery: String = "",
    val showDeleted: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val currentFolderId: Int? = null,
    val sortOrder: SortOrder = SortOrder.DATE
)

class NotesViewModel(private val repository: NotesRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _showDeleted = MutableStateFlow(false)
    private val _showFavoritesOnly = MutableStateFlow(false)
    private val _currentFolderId = MutableStateFlow<Int?>(null)
    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val viewMode = MutableStateFlow(ViewMode.GRID)

    private val uiState = combine(
        _searchQuery, _showDeleted, _showFavoritesOnly, _currentFolderId, _sortOrder
    ) { query, deleted, favorites, folderId, sort ->
        UiState(query, deleted, favorites, folderId, sort)
    }

    val items = combine(repository.allFolders, repository.allNotes, uiState) { folders, notes, state ->
        val activeNotes = notes.filter { it.isDeleted == state.showDeleted }
        val favoriteNotes = if (state.showFavoritesOnly) activeNotes.filter { it.isFavorite } else activeNotes

        val itemsToShow: List<Any> = if (state.currentFolderId == null) {
            val subFolders = if (state.showFavoritesOnly) emptyList() else folders.sortedBy { it.name }
            val rootNotes = favoriteNotes.filter { it.folderId == null }
            val sortedNotes = when (state.sortOrder) {
                SortOrder.DATE -> rootNotes.sortedByDescending { it.timestamp }
                SortOrder.NAME -> rootNotes.sortedBy { it.title }
            }
            subFolders + sortedNotes
        } else {
            val notesInFolder = favoriteNotes.filter { it.folderId == state.currentFolderId }
            when (state.sortOrder) {
                SortOrder.DATE -> notesInFolder.sortedByDescending { it.timestamp }
                SortOrder.NAME -> notesInFolder.sortedBy { it.title }
            }
        }

        if (state.searchQuery.isBlank()) {
            itemsToShow
        } else {
            itemsToShow.filter {
                (it is Note && (it.title.contains(state.searchQuery, true) || it.content.contains(state.searchQuery, true))) ||
                (it is Folder && it.name.contains(state.searchQuery, true))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val folders = repository.allFolders.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setShowDeleted(show: Boolean) { _showDeleted.value = show }
    fun setShowFavoritesOnly(show: Boolean) { _showFavoritesOnly.value = show }
    fun setCurrentFolder(folderId: Int?) { _currentFolderId.value = folderId }
    fun setSortOrder(sortOrder: SortOrder) { _sortOrder.value = sortOrder }
    fun toggleViewMode() { viewMode.value = if(viewMode.value == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }

    fun addNote(note: Note) { viewModelScope.launch { repository.insert(note) } }
    fun updateNote(note: Note) { viewModelScope.launch { repository.update(note) } }
    fun deleteNote(note: Note) { viewModelScope.launch { repository.update(note.copy(isDeleted = true)) } }
    fun restoreNote(note: Note) { viewModelScope.launch { repository.update(note.copy(isDeleted = false)) } }
    fun deleteNotePermanently(note: Note) { viewModelScope.launch { repository.delete(note) } }
    fun addFolder(folder: Folder) { viewModelScope.launch { repository.insertFolder(folder) } }
    fun deleteMultiple(items: List<Any>) { viewModelScope.launch { items.forEach { if (it is Note) { repository.update(it.copy(isDeleted = true)) } } } }
    fun moveMultiple(items: List<Any>, folderId: Int) { viewModelScope.launch { items.forEach { if (it is Note) { repository.update(it.copy(folderId = folderId)) } } } }

    fun moveNotesToFolder(notes: List<Note>, folderId: Int?) {
        viewModelScope.launch {
            notes.forEach { repository.update(it.copy(folderId = folderId)) }
        }
    }

    fun copyNotesToFolder(notes: List<Note>, folderId: Int?) {
        viewModelScope.launch {
            notes.forEach { repository.insert(it.copy(id = 0, folderId = folderId)) }
        }
    }

    fun duplicateNotes(notes: List<Note>) {
        viewModelScope.launch {
            notes.forEach { repository.insert(it.copy(id = 0)) }
        }
    }

    fun moveToSecureFolder(notes: List<Note>) {
        viewModelScope.launch {
            val secureFolder = getOrCreateSecureFolder()
            notes.forEach { repository.update(it.copy(folderId = secureFolder.id, isSecure = true)) }
        }
    }

    fun copyToSecureFolder(notes: List<Note>) {
        viewModelScope.launch {
            val secureFolder = getOrCreateSecureFolder()
            notes.forEach { repository.insert(it.copy(id = 0, folderId = secureFolder.id, isSecure = true)) }
        }
    }

    suspend fun getNoteById(id: Int): Note? = repository.getNoteById(id)

    private suspend fun getOrCreateSecureFolder(): Folder {
        val existing = repository.getFolderByName("Secure")
        if (existing != null) return existing
        val id = repository.insertFolderAndGetId(Folder(name = "Secure"))
        return Folder(id = id, name = "Secure")
    }
}
