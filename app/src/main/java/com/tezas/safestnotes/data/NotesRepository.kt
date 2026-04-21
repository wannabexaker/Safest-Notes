package com.tezas.safestnotes.data

import kotlinx.coroutines.flow.Flow

class NotesRepository(
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val revisionDao: NoteRevisionDao? = null
) {

    val allNotes: Flow<List<Note>> = noteDao.getAllNotes()
    val allFolders: Flow<List<Folder>> = folderDao.getAllFolders()
    private val maxFolderDepth = 3
    private val maxDepthMessage = "Maximum folder nesting reached (3 levels)."

    suspend fun insert(note: Note): Long {
        return noteDao.insert(note)
    }

    suspend fun getAllNotesOnce(): List<Note> = noteDao.getAllNotesOnce()

    suspend fun getAllFoldersOnce(): List<Folder> = folderDao.getAllFoldersOnce()

    suspend fun update(note: Note) {
        noteDao.update(note)
    }

    suspend fun saveRevision(note: Note) {
        val dao = revisionDao ?: return
        if (note.id == 0) return
        dao.insert(NoteRevision(noteId = note.id, title = note.title, content = note.content))
        dao.pruneOldRevisions(note.id, keep = 10)
    }

    suspend fun getRevisionsForNote(noteId: Int): List<NoteRevision> =
        revisionDao?.getRevisionsForNote(noteId) ?: emptyList()

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

    suspend fun purgeDeletedBefore(cutoffMs: Long) {
        noteDao.purgeDeletedBefore(cutoffMs)
    }

    suspend fun deleteFolderWithContents(folderId: Int) {
        val now = System.currentTimeMillis()
        val notes = noteDao.getNotesByFolderId(folderId)
        notes.forEach { noteDao.update(it.copy(isDeleted = true, deletedAt = now)) }
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
