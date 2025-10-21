package com.tezas.safestnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Note::class, Folder::class], version = 5, exportSchema = false) // Version updated
abstract class NotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        fun getDatabase(context: Context): NotesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "safest_notes_db"
                )
                .fallbackToDestructiveMigration() // Wipes and rebuilds instead of migrating 
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}