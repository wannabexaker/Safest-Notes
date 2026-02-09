<!-- File responsibility: Public project overview for GitHub. Feature area: Repo documentation. -->
# SafestNotes

SafestNotes is an Android notes app focused on speed, clarity, and reliable touch behavior. The UI follows familiar Samsung Notes patterns with fast note creation, strong folder organization, and predictable interactions.

## Highlights
- Fast note creation with consistent editor focus rules.
- Folder tree with expand/collapse and structured navigation.
- Notes grid/list views with readable previews.
- Offline-first local storage with Room.

## Tech Stack
- Kotlin, XML layouts
- MVVM + ViewModel + StateFlow
- Room (SQLite)
- Gradle / Android Studio

## Project Structure
- `app/src/main/java/com/tezas/safestnotes/ui` – Activities, Fragments, UI logic
- `app/src/main/java/com/tezas/safestnotes/data` – Room entities, DAOs, repository
- `app/src/main/java/com/tezas/safestnotes/viewmodel` – ViewModels, UI state
- `app/src/main/res` – Layouts, menus, drawables, themes

## Setup
1. Open the project in Android Studio.
2. Ensure SDK and build tools are installed.
3. Sync Gradle.

## Build
```powershell
.\gradlew :app:assembleDebug
```

## Install & Run (device/emulator)
```powershell
$env:Path = "C:\Users\Jiannis\AppData\Local\Android\Sdk\platform-tools;$env:Path"
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.tezas.safestnotes/com.tezas.safestnotes.ui.MainActivity
```

## UX Rules (Snapshot)
- Single tap: open note / open folder.
- Long press + release: context menu.
- Long press + move: drag & drop.
- Selection mode: entered from the options menu.

## Status
This repo is under active development. For detailed requirements and change history, see:
- `PRD.md`
- `note_progress.md`
