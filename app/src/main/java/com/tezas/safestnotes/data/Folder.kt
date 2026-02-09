package com.tezas.safestnotes.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String = "",
    val parentFolderId: Int? = null,
    val accentColor: Int = 0
)
