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

enum class SortOrder {
    TITLE_ASC,
    TITLE_DESC,
    SIZE_LETTERS_ASC,
    SIZE_LETTERS_DESC,
    SIZE_KB_ASC,
    SIZE_KB_DESC,
    DATE_CREATED_ASC,
    DATE_CREATED_DESC,
    DATE_MODIFIED_ASC,
    DATE_MODIFIED_DESC
}
enum class ViewMode { GRID, LIST }

private data class UiState( 
    val searchQuery: String = "",
    val showDeleted: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val currentFolderId: Int? = null,
    val sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
)

class NotesViewModel(private val repository: NotesRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _showDeleted = MutableStateFlow(false)
    private val _showFavoritesOnly = MutableStateFlow(false)
    private val _currentFolderId = MutableStateFlow<Int?>(null)
    private val _sortOrder = MutableStateFlow(SortOrder.DATE_MODIFIED_DESC)
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
            val sortedNotes = sortNotes(rootNotes, state.sortOrder)
            subFolders + sortedNotes
        } else {
            val notesInFolder = favoriteNotes.filter { it.folderId == state.currentFolderId }
            sortNotes(notesInFolder, state.sortOrder)
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

    val allNotes = repository.allNotes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val folders = repository.allFolders.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setShowDeleted(show: Boolean) { _showDeleted.value = show }
    fun setShowFavoritesOnly(show: Boolean) { _showFavoritesOnly.value = show }
    fun setCurrentFolder(folderId: Int?) { _currentFolderId.value = folderId }
    fun setSortOrder(sortOrder: SortOrder) { _sortOrder.value = sortOrder }
    fun toggleViewMode() { viewMode.value = if(viewMode.value == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID }
    fun setViewMode(mode: ViewMode) { viewMode.value = mode }

    fun addNote(note: Note) { viewModelScope.launch { repository.insert(note) } }
    fun updateNote(note: Note) { viewModelScope.launch { repository.update(note) } }
    fun deleteNote(note: Note) { viewModelScope.launch { repository.update(note.copy(isDeleted = true)) } }
    fun restoreNote(note: Note) { viewModelScope.launch { repository.update(note.copy(isDeleted = false)) } }
    fun deleteNotePermanently(note: Note) { viewModelScope.launch { repository.delete(note) } }
    fun addFolder(folder: Folder) { viewModelScope.launch { repository.insertFolder(folder) } }
    fun addFolderSafely(folder: Folder, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repository.insertFolder(folder)
            } catch (e: IllegalArgumentException) {
                onError(e.message ?: "Maximum folder nesting reached (3 levels).")
            }
        }
    }
    fun updateFolder(folder: Folder) { viewModelScope.launch { repository.updateFolder(folder) } }
    fun deleteFolderById(id: Int) { viewModelScope.launch { repository.deleteFolderById(id) } }
    fun deleteFolderWithContents(id: Int) { viewModelScope.launch { repository.deleteFolderWithContents(id) } }
    fun deleteMultiple(items: List<Any>) { viewModelScope.launch { items.forEach { if (it is Note) { repository.update(it.copy(isDeleted = true)) } } } }
    fun moveMultiple(items: List<Any>, folderId: Int) { viewModelScope.launch { items.forEach { if (it is Note) { repository.update(it.copy(folderId = folderId)) } } } }

    fun moveNotesToFolder(notes: List<Note>, folderId: Int?) {
        viewModelScope.launch {
            notes.forEach { repository.update(it.copy(folderId = folderId)) }
        }
    }

    fun copyNotesToFolder(notes: List<Note>, folderId: Int?) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            notes.forEach {
                repository.insert(
                    it.copy(
                        id = 0,
                        folderId = folderId,
                        timestamp = now,
                        createdTimestamp = now
                    )
                )
            }
        }
    }

    fun duplicateNotes(notes: List<Note>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            notes.forEach {
                repository.insert(
                    it.copy(
                        id = 0,
                        timestamp = now,
                        createdTimestamp = now
                    )
                )
            }
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
            val now = System.currentTimeMillis()
            notes.forEach {
                repository.insert(
                    it.copy(
                        id = 0,
                        folderId = secureFolder.id,
                        isSecure = true,
                        timestamp = now,
                        createdTimestamp = now
                    )
                )
            }
        }
    }

    suspend fun getNoteById(id: Int): Note? = repository.getNoteById(id)

    private fun sortNotes(notes: List<Note>, sortOrder: SortOrder): List<Note> {
        val plainTextLength: (Note) -> Int = { note ->
            note.content.replace(Regex("<[^>]*>"), "").length
        }
        val byteSize: (Note) -> Int = { note ->
            note.content.toByteArray().size
        }
        val createdTime: (Note) -> Long = { note ->
            if (note.createdTimestamp > 0L) note.createdTimestamp else note.timestamp
        }
        return when (sortOrder) {
            SortOrder.TITLE_ASC -> notes.sortedBy { it.title.lowercase() }
            SortOrder.TITLE_DESC -> notes.sortedByDescending { it.title.lowercase() }
            SortOrder.SIZE_LETTERS_ASC -> notes.sortedBy { plainTextLength(it) }
            SortOrder.SIZE_LETTERS_DESC -> notes.sortedByDescending { plainTextLength(it) }
            SortOrder.SIZE_KB_ASC -> notes.sortedBy { byteSize(it) }
            SortOrder.SIZE_KB_DESC -> notes.sortedByDescending { byteSize(it) }
            SortOrder.DATE_CREATED_ASC -> notes.sortedBy { createdTime(it) }
            SortOrder.DATE_CREATED_DESC -> notes.sortedByDescending { createdTime(it) }
            SortOrder.DATE_MODIFIED_ASC -> notes.sortedBy { it.timestamp }
            SortOrder.DATE_MODIFIED_DESC -> notes.sortedByDescending { it.timestamp }
        }
    }

    private suspend fun getOrCreateSecureFolder(): Folder {
        val existing = repository.getFolderByName("Secure")
        if (existing != null) return existing
        val id = repository.insertFolderAndGetId(Folder(name = "Secure"))
        return Folder(id = id, name = "Secure")
    }
}
