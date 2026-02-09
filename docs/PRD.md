<!-- Responsibility: Product requirements record. Feature area: Notes UX + touch behavior. -->
## SafestNotes PRD (Brand & Theme System)

### Brand Color
- Single source of truth: `#8046D9`.
- Applied to:
  - Light theme
  - Dark theme
  - Toolbar
  - Icons
  - Folder accent strips
  - Selection highlights

### Theme Readability
- Light theme:
  - Background: light
  - Text: black
- Dark theme:
  - Background: dark
  - Text: white
- Gray text is permitted only for secondary/disabled text.
- Background color is set via `android:colorBackground` for build compatibility.

### Notes List & Preview
- Each note preview shows:
  - Title (always visible; falls back to “Untitled”).
  - Plain-text content snippet extracted from rich text/HTML.
  - Date/time: show time for today, date for older notes.

### Editor Behavior
- Title IME “Next” moves focus directly to content editor and keeps keyboard open.
- Title is single-line; enter does not insert new line.
- New note auto-focuses content and opens keyboard.
- Existing note opens in passive state; no auto-focus and no keyboard until tap.
- Read Mode toggle is shown next to Favorite for existing notes.
- Read Mode ON: title/content not editable, keyboard closed; user can scroll to read.
- Read Mode OFF: normal editing.

### Folders & Navigation
- Folder tree in drawer supports single-tap expand/collapse.
- Double-tap (<= 300ms) opens folder notes.
- Long press on folder shows context menu actions.
- Folder depth is capped at 3 with UI messaging.
- Move animations include purple highlight on target folder for selection and drag/drop.

### Selection & Touch Rules
- Selection mode is entered from the options menu.
- Long press no longer triggers selection in the notes list.
- Drag hover updates are optimized to avoid UI lag.
- Long press + move starts drag; long press + release opens context menu.
- Drag handle also initiates drag for notes and folders.
- Secure actions remain visible but disabled until real secure storage exists.

### Current Implementation Notes
- Theme colors are defined via `colors.xml` and applied in `themes.xml` / `values-night/themes.xml`.
- UI elements reference theme attributes instead of hardcoded colors.
- Context menus are used for note and folder actions to avoid touch ambiguity.
