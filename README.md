# SafestNotes

Offline Android notes app with AES-256-GCM per-note encryption, biometric unlock, and rich text editing

## Overview

Local-only notes application for Android. No network access, no cloud sync, no telemetry. Sensitive notes are encrypted with AES-256-GCM before being written to the database; plaintext never reaches SQLite. The master password is the encryption root — derived via PBKDF2-SHA256 (200,000 iterations); biometric unlock wraps the derived key in Android Keystore RSA-2048.

## Features

- Rich text editor: bold, italic, underline, strikethrough, superscript/subscript, headings H1–H3, lists (bullet, numbered, checklist), text color, highlight color, six font sizes, horizontal rule
- 11 note background color tints
- Per-note revision history — last 10 revisions auto-saved; cascade-deleted when the note is permanently removed
- Folders with up to 3 nesting levels; move/copy notes between folders; drag-to-reorder within a folder
- Multi-select with bulk operations: move, copy, delete, favorite, duplicate, secure
- Recycle Bin with 30-day auto-purge; expiry countdown shown for notes within 7 days of deletion
- Live full-text search across title and content (HTML-stripped)
- Sort by title, content length, date created, or date modified
- Text-to-speech with speed, pitch, and language controls; automatic Greek/English language detection by Unicode range analysis
- AES-256-GCM per-note encryption locked behind master password (PBKDF2-SHA256, 200k iterations, 16-byte salt)
- Biometric unlock via Android BiometricPrompt — Android Keystore RSA-2048 wraps the in-memory AES key
- Auto-lock on app background with configurable timeout
- Bottom navigation or drawer navigation — switchable in Settings

## Architecture

MVVM. A single `NotesViewModel` is shared across all fragments via `activityViewModels()`. The main `items` flow is a `combine()` of `allNotes`, `allFolders`, and a `UiState` snapshot; it re-emits on any change to search query, filter flags, folder scope, or sort order. Filters are set in `onStart()` and cleared in `onStop()` so they re-apply correctly after returning from child activities without a full fragment recreation.

Encryption runs on the IO dispatcher inside `saveNoteInternal()` before the repository write. If the vault locks between load and save, the existing ciphertext blob is kept unchanged.

### Components

| Component | Role |
|---|---|
| `data/` | Room entities, DAOs, NotesRepository, NotesDatabase v12 |
| `viewmodel/` | NotesViewModel — StateFlow-based; all CRUD and filter operations |
| `security/` | SecurityManager, AesGcm, KeyDerivation, BiometricKeyStore, SecureStorage, AutoLockManager |
| `ui/` | MainActivity, AddEditNoteActivity, all fragments and dialogs |
| `adapter/` | NotesAdapter, FoldersAdapter, DrawerFoldersAdapter, RecycleBinAdapter |

## Tech Stack

| Technology | Role |
|---|---|
| Kotlin | Primary language |
| Android SDK 34 (min 24) | Platform |
| Room | SQLite ORM with Flow support |
| Android Keystore | Biometric key wrapping (RSA-2048) |
| Android BiometricPrompt | Biometric authentication |
| EncryptedSharedPreferences | Secure preferences storage |
| kotlinx.coroutines | Async / IO dispatcher |
| richeditor-android | WebView-based HTML rich text editor |
| Material 3 | UI components and theming |
| ProcessLifecycleOwner | Auto-lock on background detection |
| KSP | Annotation processor for Room |

## Installation

Requires Android Studio Hedgehog (2023.1) or later, JDK 21, Kotlin 1.9+.

```bash
git clone https://github.com/wannabexaker/SafestNotes
cd SafestNotes

# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

On first launch the app prompts for a master password. This is the only key that can decrypt secure notes — it cannot be recovered if lost. Biometric unlock can be enabled in **Settings → Security** after the master password is set.

## Project Structure

```
SafestNotes/
└── app/src/main/java/com/tezas/safestnotes/
    ├── data/
    │   ├── Note.kt                  — Room entity (schema v12)
    │   ├── Folder.kt                — Room entity
    │   ├── NoteRevision.kt          — Room entity (last 10 revisions per note)
    │   ├── NoteDao.kt               — CRUD + purgeDeletedBefore()
    │   ├── FolderDao.kt             — CRUD + getFoldersByParentId()
    │   ├── NoteRevisionDao.kt       — insert, pruneOldRevisions, getRevisionsForNote
    │   ├── NotesRepository.kt       — business logic; folder depth enforcement
    │   └── NotesDatabase.kt         — RoomDatabase v12; all migrations
    ├── viewmodel/
    │   └── NotesViewModel.kt        — shared StateFlow hub; CRUD + filter ops
    ├── security/
    │   ├── SecurityManager.kt       — vault lifecycle: unlock, lock, encrypt, decrypt
    │   ├── AesGcm.kt                — AES-256-GCM primitives
    │   ├── KeyDerivation.kt         — PBKDF2-SHA256
    │   ├── BiometricKeyStore.kt     — Android Keystore RSA wrap/unwrap
    │   └── AutoLockManager.kt       — ProcessLifecycleOwner → auto-lock
    └── ui/
        ├── MainActivity.kt          — host activity; nav controller
        ├── AddEditNoteActivity.kt   — rich text editor; encrypt-on-save flow
        └── [fragments + adapters]
```

## Notes

`CustomRichEditor` subclasses `jp.wasabeef.richeditor.RichEditor` to expose its `protected exec()` method as public. This is the only way to issue JavaScript calls that lack a public API in the library.

The Note entity stores ciphertext in the `content` column when `isSecure = true`; the `secureMetadata` column holds a JSON object `{"salt":"...","iv":"..."}` needed for decryption. The two columns are always written together atomically — a note with `isSecure = true` and no `secureMetadata` indicates a write failure.

All 7 database migrations (v5 → v12) are additive. `fallbackToDestructiveMigration()` is not used anywhere in the codebase.

Auto-purge runs once per app launch via `NotesViewModel.purgeExpiredNotes()`. Notes that were soft-deleted before v12 (no `deletedAt` recorded) are excluded from the 30-day purge and remain in the Recycle Bin until explicitly deleted by the user.
