# SafestNotes

A privacy-first, feature-rich notes app for Android. All notes are stored locally — no cloud, no telemetry. Sensitive notes are protected with AES-256-GCM encryption locked behind a master password and optional biometric authentication.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Security Model](#security-model)
- [Project Structure](#project-structure)
- [Data Layer](#data-layer)
- [UI Layer](#ui-layer)
- [Building & Running](#building--running)
- [Database Migrations](#database-migrations)
- [Dependencies](#dependencies)

---

## Features

### Notes
- Rich text editing: bold, italic, underline, strikethrough, superscript, subscript
- Text alignment: left, center, right
- Lists: bullet, numbered, checklist (checkbox)
- Headings H1 / H2 / H3
- Indent / outdent
- Text color and highlight color
- Font size: Tiny (10sp) → Small (13sp) → Medium (18sp) → Large (22sp) → Extra Large (26sp) → Huge (30sp)
- Horizontal rule
- Clear all formatting
- 11 note background color tints (Midnight, Forest, Ocean, Crimson, Amber, Lavender, Rose, Teal, Navy, Espresso, default)
- Pin notes to the top of any list
- Per-note revision history (last 10 revisions auto-saved)
- Live word / character count bar

### Organization
- Folders with up to 3 nesting levels
- Move / copy notes between folders
- Drag-to-reorder notes within a folder (drag handle)
- Multi-select with bulk: move, copy, delete, favorite, duplicate, secure

### Favorites
- Star any note from the main list, context menu, or editor toolbar
- Dedicated Favorites tab — filter is properly re-applied after returning from the editor
- Long-press context menu: open, toggle favorite, share, delete

### Recycle Bin
- Soft-delete with **30-day auto-purge** (hard-deleted silently on app launch)
- Shows exact deletion date; displays a warning countdown for notes expiring within 7 days
- Multi-select bulk restore / permanent delete
- Overflow menu: Select All, Restore All, Empty Bin

### Search
- Live full-text search across title and content (HTML-stripped)
- Works across all views including inside folders

### Sorting
- Title A→Z / Z→A
- Content length (characters or bytes) ascending / descending
- Date created ascending / descending
- Date modified ascending / descending (default)

### View Modes
- Grid (2 columns) or List — toggled from Settings, persisted in SharedPreferences

### Text-to-Speech (Read Aloud)
- Volume icon always visible in the note toolbar; one tap starts / stops reading
- TTS playback bar: speed cycle, settings (⚙), stop (✕)
- Configurable speed (0.5× – 2×), pitch (Low / Normal / High), language (Auto / Greek / English)
- Automatic language detection using Unicode ranges (U+0370–03FF, U+1F00–1FFF); threshold: ≥ 25 % Greek characters
- Mixed-language text split by sentence; each segment queued with the correct locale
- All TTS settings persisted in SharedPreferences

### Security
- Master password setup on first launch (PBKDF2-SHA256, 200 000 iterations, random 16-byte salt)
- AES-256-GCM per-note encryption (random 12-byte IV; IV + salt stored as JSON metadata alongside ciphertext)
- Biometric unlock (Android BiometricPrompt) as a password-free alternative
- Auto-lock when the app goes to background (configurable timeout in Settings)
- Encrypted notes open with a blur-to-sharp CSS reveal animation after successful unlock

### Navigation
- **Bottom navigation** (default): All Notes, Favorites, Folders, Recycle Bin, Settings
- **Drawer navigation** (optional, switchable in Settings): same sections plus nested folder tree
- Smooth crossfade between tabs / sections (200 ms)
- Slide-right animation for folder drill-down; slide-left on back
- Slide-up animation on note open; slide-down on note close
- No white flash on any transition (explicit `android:windowBackground` in theme)

---

## Architecture

```
UI Layer        — Activities, Fragments, Adapters
    ↓
ViewModel       — NotesViewModel (shared, activityViewModels)
    ↓
Repository      — NotesRepository (business logic + depth enforcement)
    ↓
Room DAOs       — NoteDao, FolderDao, NoteRevisionDao
    ↓
SQLite          — safest_notes_db (Room v12)
```

**Pattern: MVVM** with a single `NotesViewModel` shared across all fragments via `activityViewModels()`. Fragments communicate upward through the ViewModel only — no direct fragment-to-fragment references.

Data flows down as `StateFlow` observed with `viewLifecycleOwner.lifecycleScope.launch { collect { } }`. The main `items` flow is a `combine()` of `allNotes`, `allFolders`, and a `UiState` snapshot (search query, deleted flag, favorites flag, folder scope, sort order).

---

## Security Model

```
Master Password
    │
    ▼  PBKDF2-SHA256 (200 000 iterations, random 16-byte salt)
    │
    ▼  AES-256 key (in-memory only while vault is unlocked)
    │
    ├─ Encrypt note content → AES-256-GCM (random 12-byte IV per note)
    │   Ciphertext  → stored in note.content  (Base64)
    │   IV + salt   → stored in note.secureMetadata  (JSON)
    │
    └─ Biometric shortcut:
        Android Keystore RSA-2048 key wraps the AES key
        BiometricPrompt unwraps it at unlock — no password re-entry needed
```

- Plaintext **never reaches the database** — encryption runs on the IO dispatcher inside `saveNoteInternal()` before `repository.update()` is called.
- If the vault locks between load and save, the existing ciphertext blob is kept unchanged (no data loss).
- `SecurityManager` is a singleton initialized in the `Application` subclass (`SafestNotesApp`).
- `AutoLockManager` uses `ProcessLifecycleOwner` to detect backgrounding and start the lock timer.

---

## Project Structure

```
app/src/main/java/com/tezas/safestnotes/
│
├── data/
│   ├── Note.kt                   Room entity — notes table (schema v12)
│   ├── Folder.kt                 Room entity — folders table
│   ├── NoteRevision.kt           Room entity — note_revisions table
│   ├── NoteDao.kt                CRUD + purgeDeletedBefore()
│   ├── FolderDao.kt              CRUD + getFoldersByParentId()
│   ├── NoteRevisionDao.kt        insert, pruneOldRevisions, getRevisionsForNote
│   ├── NotesRepository.kt        Business gate: depth check, cascade delete, purge
│   └── NotesDatabase.kt          RoomDatabase v12 with all migrations
│
├── viewmodel/
│   ├── NotesViewModel.kt         Shared ViewModel; items StateFlow; all CRUD + filter ops
│   └── NotesViewModelFactory.kt  Manual factory for constructor DI
│
├── security/
│   ├── SecurityManager.kt        Vault lifecycle: unlock, lock, encrypt, decrypt
│   ├── AesGcm.kt                 AES-256-GCM encrypt / decrypt primitives
│   ├── KeyDerivation.kt          PBKDF2-SHA256 key derivation
│   ├── BiometricKeyStore.kt      Android Keystore RSA wrap / unwrap
│   ├── SecureStorage.kt          EncryptedSharedPreferences wrapper
│   └── AutoLockManager.kt        ProcessLifecycleOwner → auto-lock on background
│
├── ui/
│   ├── SafestNotesApp.kt         Application subclass; SecurityManager init
│   ├── MainActivity.kt           Host activity; bottom nav / drawer; navigateTo()
│   ├── AddEditNoteActivity.kt    Note editor; rich-text toolbar; TTS; save/encrypt flow
│   ├── MasterPasswordActivity.kt First-run master password setup
│   ├── NotesFragment.kt          Main list; swipe gestures; multi-select; drag-reorder
│   ├── FolderNotesFragment.kt    NotesFragment subclass scoped to a folder
│   ├── FoldersFragment.kt        Folder grid with create / rename / delete
│   ├── FavoritesFragment.kt      Favorites list; long-press context menu
│   ├── RecycleBinFragment.kt     Deleted notes; multi-select; 30-day expiry display
│   ├── SettingsFragment.kt       Preference screen (nav, view mode, security, font)
│   ├── CustomRichEditor.kt       RichEditor subclass with touch-passthrough patch
│   ├── UnlockDialog.kt           Biometric + password unlock bottom sheet
│   ├── MasterPasswordSetupDialog.kt
│   └── ChangePasswordDialog.kt
│
└── adapter/
    ├── NotesAdapter.kt           Notes + folders list; drag handle; selection mode
    ├── FoldersAdapter.kt         Folders-only grid adapter
    ├── DrawerFoldersAdapter.kt   Nested folder tree for drawer navigation
    └── RecycleBinAdapter.kt      Deleted notes; selection mode; expiry countdown
```

---

## Data Layer

### Note Entity (v12)

| Column | Type | Description |
|---|---|---|
| `id` | INTEGER PK | Auto-increment |
| `title` | TEXT | Plain-text title |
| `content` | TEXT | HTML rich text, or Base64 AES ciphertext for secure notes |
| `timestamp` | INTEGER | Last-modified epoch ms |
| `createdTimestamp` | INTEGER | Creation epoch ms |
| `isFavorite` | INTEGER (bool) | Starred by user — persists across all edits |
| `isDeleted` | INTEGER (bool) | Soft-deleted; note is in Recycle Bin |
| `deletedAt` | INTEGER? | Epoch ms when moved to Recycle Bin; `null` = not deleted |
| `folderId` | INTEGER? | FK → folders.id; `null` = root level |
| `isSecure` | INTEGER (bool) | Content is AES-256-GCM encrypted |
| `secureMetadata` | TEXT? | JSON `{"salt":"…","iv":"…"}` for decryption |
| `noteColor` | INTEGER | ARGB background tint (0 = default surface) |
| `isPinned` | INTEGER (bool) | Note floats to top of its list |

### 30-Day Auto-Purge

On every app launch `NotesViewModel.purgeExpiredNotes()` issues a single atomic DELETE:

```sql
DELETE FROM notes
WHERE  isDeleted = 1
  AND  deletedAt IS NOT NULL
  AND  deletedAt < :cutoff   -- cutoff = now − 30 days
```

Notes that were in the Recycle Bin before v12 (no `deletedAt` recorded) are excluded from auto-purge — they remain until the user explicitly deletes them.

### Revision History

Up to the last **10 revisions** per note are stored in `note_revisions`. Each save of an existing note snapshots the previous state before overwriting. Revisions are cascade-deleted when the note is permanently removed.

---

## UI Layer

### Filter State Machine

`NotesViewModel` exposes five independent `MutableStateFlow`s that `combine()` into a single `UiState` driving the `items` flow:

| Flow | Managed by | Effect |
|---|---|---|
| `_showDeleted` | `RecycleBinFragment` `onStart` / `onStop` | Show deleted vs. active notes |
| `_showFavoritesOnly` | `FavoritesFragment` `onStart` / `onStop` | Filter to `isFavorite = true` |
| `_currentFolderId` | `openFolder()` / `openAllNotes()` | Scope note list to a folder |
| `_searchQuery` | `SearchView` in toolbar | Live text search |
| `_sortOrder` | Options menu | Sort algorithm |

Filters are set in `onStart()` and cleared in `onStop()` — this ensures they are correctly re-applied after returning from a child `Activity` (e.g., editing a note from the Favorites list) without requiring a full fragment recreation.

### Animations Summary

| Transition | Specification |
|---|---|
| Note open — enter | Slide up from 55 %, fade in, 360 ms, decelerate cubic |
| Note open — exit (list) | Scale 1.0 → 0.96 + alpha dim, 320 ms |
| Note close — enter (list) | Scale 0.96 → 1.0 + alpha restore, 300 ms |
| Note close — exit | Slide down to 55 %, fade out, 300 ms, accelerate cubic |
| Tab / section switch | Crossfade 200 ms (in) / 140 ms (out) |
| Folder drill-down | Slide in from right 40 %, 280 ms |
| Folder back | Slide out to right 40 %, 280 ms |
| Saving overlay | Fade in 180 ms; fade out 120 ms on error |
| Secure note content reveal | CSS `filter: blur(6px) → none` + `opacity: 0.4 → 1.0`, 450 ms |

---

## Building & Running

### Requirements

| Tool | Version |
|---|---|
| Android Studio | Hedgehog (2023.1) or later |
| Compile / Target SDK | 34 |
| Min SDK | 24 (Android 7.0 Nougat) |
| JDK | 11+ |
| Kotlin | 1.9+ |

### Build

```bash
# Clone
git clone <repo-url>
cd SafestNotes

# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

### First Run

On first launch the app prompts for a **master password**. This is the key that encrypts all secure notes. It can be changed later in **Settings → Security → Change Password**. After setting the password, biometric unlock can be enabled in the same settings section.

---

## Database Migrations

All migrations are additive — no existing data is dropped.

| Versions | Change |
|---|---|
| 5 → 6 | Added `folders.parentFolderId` (INT?), `folders.accentColor` (INT) |
| 6 → 7 | Added `notes.createdTimestamp` (INT NOT NULL DEFAULT 0) |
| 7 → 8 | Added `notes.noteColor` (INT NOT NULL DEFAULT 0) |
| 8 → 9 | Created `note_revisions` table with CASCADE FK on `notes.id` |
| 9 → 10 | Added `folders.isSecure` (INT NOT NULL DEFAULT 0) |
| 10 → 11 | Added `notes.isPinned` (INT NOT NULL DEFAULT 0) |
| 11 → 12 | Added `notes.deletedAt` (INT, nullable) for 30-day Recycle Bin expiry |

---

## Dependencies

| Library | Purpose |
|---|---|
| `androidx.room:room-runtime` + `room-ktx` | Local SQLite ORM with Flow support |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | ViewModel + `viewModelScope` |
| `androidx.lifecycle:lifecycle-process` | `ProcessLifecycleOwner` for auto-lock |
| `androidx.preference:preference-ktx` | Settings UI (PreferenceFragmentCompat) |
| `androidx.biometric:biometric` | Fingerprint / face authentication |
| `androidx.security:security-crypto` | `EncryptedSharedPreferences` |
| `jp.wasabeef:richeditor-android` | WebView-based HTML rich text editor |
| `com.google.android.material` | Material 3 components, theming |
| `kotlinx.coroutines:kotlinx-coroutines-android` | Async / IO dispatcher |
| `androidx.activity:activity-ktx` | `OnBackPressedDispatcher`, result launchers |
| `androidx.fragment:fragment-ktx` | `activityViewModels()`, lifecycle-aware transactions |

---

## License

Private / proprietary. All rights reserved.
