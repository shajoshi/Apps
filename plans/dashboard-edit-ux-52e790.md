# Dashboard Edit Mode UX Improvements

Addresses 7 concrete usability problems identified in the current edit-mode interaction model.

---

## Issues Found

### 1. No visual indication of edit mode
`updateEditModeVisuals()` only toggles the grid overlay. There is no clear signal to the user that they are in edit mode — no banner, no top-strip colour change, no label.

**Fix:** Change `top_strip` background to a distinct amber/orange tint in edit mode and show a small "EDITING" badge next to the dashboard name.

---

### 2. "Edit Layout" / "Save" buried in overflow menu
Entering edit mode and saving both require tapping the settings icon → overflow menu. This is 2 taps for the most common edit-mode actions and the icon gives no hint.

**Fix:** Replace the overflow `⚙` icon with a dedicated **Edit pencil** button that toggles edit mode directly (1 tap). Add a visible **Save** `✓` button in the top strip that appears only when `isEditMode && hasUnsavedChanges`.

---

### 3. Context menu requires two taps to reach
To act on a widget (Edit, Delete, etc.) the user must: tap once to select → tap again to get the context menu. First-time users won't know to tap a second time.

**Fix:** Show a small **floating action bar** (horizontal strip) anchored just above/below the selected widget with icon buttons: ✏ Edit · ↑ Front · ↓ Back · 🗑 Delete. This replaces the invisible second-tap discovery. The `PopupMenu` can be kept as a fallback but the inline toolbar makes actions immediately visible.

---

### 4. No drag threshold — any touch moves the widget
`WidgetTouchHandler` starts tracking drag on `ACTION_DOWN` with zero threshold. Even a short accidental movement registers as a drag, teleporting the widget slightly.

**Fix:** Add a minimum drag threshold (~12dp in pixels) before committing to a drag. Only enter drag mode once `sqrt(dX² + dY²) > threshold`.

---

### 5. No "Add Widget" shortcut in edit mode
Adding a widget requires overflow menu → "Add Widget". There is no quick-access button visible on the canvas.

**Fix:** Show a FAB (`+`) button in the bottom-right corner of the canvas area when in edit mode. Tapping it launches `AddWidgetWizardSheet`. FAB hides in view mode.

---

### 6. No undo for accidental moves/deletes
Dragging a widget to the wrong position or accidentally deleting has no recovery path. `hasUnsavedChanges` is set but the only option is "Discard all" on back-press.

**Fix:** Add a simple single-level undo in `DashboardEditorViewModel` — snapshot the layout before each mutating operation. Show an **Undo** `↩` button in the top strip after a change; it reverts to the snapshot and disappears after the next save or a new change.

---

### 7. Drag does not account for canvas zoom/scroll offset
`WidgetTouchHandler` uses raw screen coordinates (`event.rawX/Y`) but `canvasContainer` is scaled by `canvasScale` and offset by scroll. At any zoom ≠ 1× or after scrolling, drag positions are wrong.

**Fix:** In `WidgetTouchHandler`, divide the raw coordinate deltas by the current `canvasScale` so drag distance matches visual distance. Pass `canvasScale` as a parameter.

---

## Implementation Plan

| # | Change | Files |
|---|--------|-------|
| 1 | Edit mode indicator (top strip tint + "EDITING" label) | `DashboardEditorFragment.kt`, `fragment_dashboard_editor.xml` |
| 2 | Edit toggle button + Save button in top strip | `fragment_dashboard_editor.xml`, `DashboardEditorFragment.kt` |
| 3 | Inline widget action toolbar (replaces context menu discovery) | New `layout_widget_action_bar.xml`, `DashboardEditorFragment.kt` |
| 4 | Drag threshold in `WidgetTouchHandler` | `WidgetTouchHandler.kt` |
| 5 | FAB "Add Widget" button visible in edit mode | `fragment_dashboard_editor.xml`, `DashboardEditorFragment.kt` |
| 6 | Single-level undo in ViewModel + Undo button | `DashboardEditorViewModel.kt`, `DashboardEditorFragment.kt`, `fragment_dashboard_editor.xml` |
| 7 | Scale-aware drag in `WidgetTouchHandler` | `WidgetTouchHandler.kt`, `DashboardEditorFragment.kt` |

---

## Out of scope
- Multi-level undo history
- Widget duplication / copy-paste
- Alignment guides / snap-to-widget
