package com.tezas.safestnotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    var title: String = "",
    var content: String = "", // Can now be encrypted content
    val timestamp: Long = 0L,
    val createdTimestamp: Long = 0L,
    var isFavorite: Boolean = false,
    var isDeleted: Boolean = false,
    var folderId: Int? = null,
    var isSecure: Boolean = false,
    var secureMetadata: String? = null // Will store JSON like { "salt": "...", "iv": "..." }
) 
