package com.tezas.safestnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class, Folder::class, NoteRevision::class], version = 12, exportSchema = false)
abstract class NotesDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun noteRevisionDao(): NoteRevisionDao

    companion object {
        @Volatile
        private var INSTANCE: NotesDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN parentFolderId INTEGER")
                db.execSQL("ALTER TABLE folders ADD COLUMN accentColor INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN createdTimestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN noteColor INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isSecure INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable INTEGER — notes that were already deleted get null (no expiry countdown)
                db.execSQL("ALTER TABLE notes ADD COLUMN deletedAt INTEGER")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_revisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        savedAt INTEGER NOT NULL,
                        FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_revisions_noteId ON note_revisions(noteId)")
            }
        }

        fun getDatabase(context: Context): NotesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NotesDatabase::class.java,
                    "safest_notes_db"
                )
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
