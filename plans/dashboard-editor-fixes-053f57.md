# Dashboard Editor Bug Fixes

Fix four related bugs in `DashboardEditorFragment` and `DashboardEditorViewModel`: position reset on widget add/edit, missing toolbar icons in edit mode, play button visible in edit mode, and hard canvas-bound clamping.

---

## Bug Analysis

### 1. Widget positions reset when adding/editing a widget

**Root cause — two places:**

**A) `updateWidgetProperties` / `addWidget` in ViewModel** — when a widget's size (`gridW`/`gridH`) changes via the Edit sheet, `updateWidgetProperties` does NOT update `gridX`/`gridY`. However the real culprit is in the Fragment:

**B) `clampWidgetsToBounds()` in Fragment** — called every time `onScrollViewLayout()` fires (which is any global layout change, including when a bottom sheet opens/closes or an FAB appears). It calls `viewModel.loadLayout(...)` which resets `previousLayout = null` and on the next `currentLayout.collect`, triggers a **full `renderCanvas`**, clobbering all view `x`/`y` positions from the grid positions stored in the model.

The drag updates `view.x`/`view.y` live and calls `updateSelectedWidgetPosition` only on `ACTION_UP`. If anything triggers `clampWidgetsToBounds` → `loadLayout` → `renderCanvas` before the user lifts their finger, positions are lost. More visibly: opening the Add Widget sheet or Edit Widget sheet causes a global layout change → `onScrollViewLayout` fires → `clampWidgetsToBounds` fires → `loadLayout` triggers full re-render at saved (old) positions.

**Fix:**
- Guard `clampWidgetsToBounds` so it only fires on true orientation/resize changes, not on every layout pass (compare canvas grid size, not raw pixel size).
- `clampWidgetsToBounds` should NOT call `loadLayout` (which nukes `previousLayout`); instead call a new `viewModel.clampWidgets(...)` that updates positions without triggering a full re-render path.
- Remove the bounds clamping from `WidgetTouchHandler.ACTION_UP` (`coerceIn(0, maxGridX/Y)`) as per the user's requirement to allow off-canvas positions.

---

### 2. Save/Undo icons not always visible in edit mode

**Root cause:** `updateActionButtons()` hides `btnSave` when `!hasUnsavedChanges`. After a full `renderCanvas` triggered by `clampWidgetsToBounds`/`loadLayout`, `hasUnsavedChanges` can be `false` initially (new layout just loaded), so Save stays hidden. Also `btnUndo` is hidden when `canUndo = false`.

**Fix:**
- In edit mode, `btnSave` should **always** be visible (not gated on `hasUnsavedChanges`) — the user should always be able to save in edit mode.
- `btnUndo` behaviour is fine but ensure `updateActionButtons` is called after `renderCanvas`.

---

### 3. Play button shown during edit mode

**Root cause:** `updateTripControls` shows `btnTripPlay` whenever trip phase is not RUNNING. It has no awareness of edit mode. `updateEditModeVisuals` does not touch the trip control buttons.

**Fix:** In `updateEditModeVisuals` / `updateTripControls`, hide all trip control buttons (`btnTripPlay`, `btnTripPause`, `btnTripStop`) when `isEditMode = true`.

---

### 4. Widget positions clamped/rejected when outside canvas

**Root cause:** Two places enforce `coerceIn(0, maxGridX/Y)`:
- `WidgetTouchHandler.ACTION_UP` (line 90–92)
- `viewModel.updateSelectedWidgetPosition` (line 180)
- `viewModel.updateWidgetBounds` (lines 192–193)
- `clampWidgetsToBounds` in Fragment

**Fix:** Remove the upper-bound `coerceIn` clamp from `WidgetTouchHandler` and the ViewModel update methods. Keep the lower bound at 0 (can't go to negative grid coordinates). Remove `clampWidgetsToBounds` entirely or make it opt-in only for orientation changes where the canvas shrinks significantly.

---

## Files to Change

| File | Changes |
|------|---------|
| `DashboardEditorFragment.kt` | Fix `clampWidgetsToBounds` to not call `loadLayout`; hide trip buttons in edit mode; always show Save in edit mode; guard `onScrollViewLayout` |
| `DashboardEditorViewModel.kt` | Remove upper-bound coercion in `updateSelectedWidgetPosition` and `updateWidgetBounds`; add lightweight `clampWidgets` helper |
| `WidgetTouchHandler.kt` | Remove `coerceIn(0, maxGridX/Y)` upper bound on snap |

---

## Position Save Flow (for clarity)

```
Drag ends (ACTION_UP)
  → gridX = round(view.x / gridSizePx)   [no upper clamp]
  → gridY = round(view.y / gridSizePx)   [no upper clamp]
  → viewModel.updateSelectedWidgetPosition(gridX, gridY)
      → updates _currentLayout StateFlow
          → observeViewModel detects only this widget changed
              → updateSingleWidget() (no full re-render)
                  → sets wrapper.x = gridX * gridSizePx  ← position preserved
Save layout
  → gson.toJson(layout) persists gridX, gridY to JSON file
Load layout
  → gson.fromJson → DashboardWidget.gridX/gridY restored
  → renderCanvas places wrapper.x = gridX * gridSizePx
```
