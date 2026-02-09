package com.tezas.safestnotes.data

import kotlinx.coroutines.flow.Flow

class NotesRepository(private val noteDao: NoteDao, private val folderDao: FolderDao) {

    val allNotes: Flow<List<Note>> = noteDao.getAllNotes()
    val allFolders: Flow<List<Folder>> = folderDao.getAllFolders()
    private val maxFolderDepth = 3
    private val maxDepthMessage = "Maximum folder nesting reached (3 levels)."

    suspend fun insert(note: Note) {
        noteDao.insert(note)
    }

    suspend fun update(note: Note) {
        noteDao.update(note)
    }

    suspend fun delete(note: Note) {
        noteDao.delete(note)
    }

    suspend fun getNoteById(id: Int): Note? {
        return noteDao.getNoteById(id)
    }

    suspend fun insertFolder(folder: Folder) {
        enforceMaxDepth(folder.parentFolderId)
        folderDao.insert(folder)
    }

    suspend fun insertFolderAndGetId(folder: Folder): Int {
        enforceMaxDepth(folder.parentFolderId)
        return folderDao.insert(folder).toInt()
    }

    suspend fun getFolderByName(name: String): Folder? {
        return folderDao.getFolderByName(name)
    }

    suspend fun updateFolder(folder: Folder) {
        folderDao.update(folder)
    }

    suspend fun deleteFolderById(id: Int) {
        folderDao.deleteById(id)
    }

    suspend fun deleteFolderWithContents(folderId: Int) {
        val notes = noteDao.getNotesByFolderId(folderId)
        notes.forEach { noteDao.update(it.copy(isDeleted = true)) }
        val children = folderDao.getFoldersByParentId(folderId)
        children.forEach { child ->
            deleteFolderWithContents(child.id)
        }
        folderDao.deleteById(folderId)
    }

    private suspend fun enforceMaxDepth(parentFolderId: Int?) {
        if (parentFolderId == null) return
        val depth = getFolderDepth(parentFolderId)
        require(depth < maxFolderDepth) { maxDepthMessage }
    }

    private suspend fun getFolderDepth(folderId: Int): Int {
        var depth = 0
        var currentId: Int? = folderId
        while (currentId != null) {
            val folder = folderDao.getFolderById(currentId)
                ?: throw IllegalArgumentException("Parent folder not found: $currentId")
            depth += 1
            if (depth >= maxFolderDepth) return depth
            currentId = folder.parentFolderId
        }
        return depth
    }
}
