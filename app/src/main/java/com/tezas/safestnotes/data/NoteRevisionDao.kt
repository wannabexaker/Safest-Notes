package com.tezas.safestnotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NoteRevisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(revision: NoteRevision)

    @Query("SELECT * FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC")
    suspend fun getRevisionsForNote(noteId: Int): List<NoteRevision>

    @Query("SELECT COUNT(*) FROM note_revisions WHERE noteId = :noteId")
    suspend fun countForNote(noteId: Int): Int

    /** Delete oldest revisions keeping only the latest :keep for a given note */
    @Query("""
        DELETE FROM note_revisions
        WHERE noteId = :noteId
        AND id NOT IN (
            SELECT id FROM note_revisions WHERE noteId = :noteId ORDER BY savedAt DESC LIMIT :keep
        )
    """)
    suspend fun pruneOldRevisions(noteId: Int, keep: Int = 10)

    @Query("DELETE FROM note_revisions WHERE noteId = :noteId")
    suspend fun deleteAllForNote(noteId: Int)
}
