package com.tezas.safestnotes.data

import kotlinx.coroutines.flow.Flow

class NotesRepository(private val noteDao: NoteDao, private val folderDao: FolderDao) {

    val allNotes: Flow<List<Note>> = noteDao.getAllNotes()
    val allFolders: Flow<List<Folder>> = folderDao.getAllFolders()

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
        folderDao.insert(folder)
    }

    suspend fun insertFolderAndGetId(folder: Folder): Int {
        return folderDao.insert(folder).toInt()
    }

    suspend fun getFolderByName(name: String): Folder? {
        return folderDao.getFolderByName(name)
    }
}
