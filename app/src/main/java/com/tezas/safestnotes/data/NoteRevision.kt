package com.tezas.safestnotes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "note_revisions",
    foreignKeys = [ForeignKey(
        entity = Note::class,
        parentColumns = ["id"],
        childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class NoteRevision(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val noteId: Int,
    val title: String,
    val content: String,
    val savedAt: Long = System.currentTimeMillis()
)
