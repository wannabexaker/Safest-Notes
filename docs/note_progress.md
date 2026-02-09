<!-- Responsibility: Change log for implemented requirements. Feature area: Touch + context actions. -->
## Progress Log

### Brand & Theme System
- Set brand purple to `#8046D9` across themes.
- Enforced black/white text for light/dark themes.
- Allowed gray for secondary text only.
- Updated editor, note cards, drawer, and toolbar visuals to use theme attributes.
- Updated icon tinting and chevrons to brand purple.
- Switched to `android:colorBackground` to fix resource linking.

### Notes List & Preview
- Note cards show title, plain-text snippet, and time/date based on recency.
- HTML content is converted to safe plain text for preview.

### Editor Behavior
- Title uses IME “Next” to move focus to content editor.
- Title is single-line; enter does not insert new line.
- Added Read Mode toggle in editor (next to Favorite).

### Folders & Navigation
- Drawer “Folders” row toggles tree visibility.
- Folder rows: single tap expand/collapse, double tap opens.
- Move actions now briefly highlight the target folder.

### Selection & Touch
- Selection is now entered via the options menu.
- Long press in notes list no longer toggles selection.
- Hover highlight updates are more efficient to reduce lag.
- Long press + move starts drag; long press + release shows context menu.
- Drag handles initiate drag for notes and folders.
- Secure actions are visible but disabled (red) until secure storage exists.
