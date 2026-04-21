package com.tezas.safestnotes.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY timestamp DESC")
    suspend fun getAllNotesOnce(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Int): Note?

    @Query("SELECT * FROM notes WHERE folderId = :folderId")
    suspend fun getNotesByFolderId(folderId: Int): List<Note>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Delete
    suspend fun delete(note: Note)

    /** Hard-delete every note that has been in the bin for longer than cutoffMs. */
    @Query("DELETE FROM notes WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeDeletedBefore(cutoffMs: Long)
}
