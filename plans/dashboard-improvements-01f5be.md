# Dashboard Improvements

Fixes and UX improvements across the dashboard editing flow, navigation, and trip state management.

## Issues & Changes

### 1. Save button vanishes during edit
**Root cause:** `updateActionButtons()` only shows `btnSave` when `hasUnsavedChanges = true`, but `hasUnsavedChanges` is set to `true` inside `observeViewModel()` only after `layoutLoadedOnce` is already set — on first layout load (including new dashboards), the flag races and save never appears.
**Fix:** In `DashboardEditorFragment`, ensure `hasUnsavedChanges = true` is set reliably when entering edit mode (especially for new dashboards), and call `updateActionButtons()` after any mode change.

### 2. New widgets should appear at canvas center
**Root cause:** New widgets are added at position `(0, 0)` (top-left).
**Fix:** In `DashboardEditorViewModel.addWidget()`, calculate the center of the canvas and place widgets there. Canvas grid dimensions need to be passed from the fragment to the ViewModel.

### 3. Move/Resize should be an explicit context menu action
**Root cause:** Currently, drag-to-move and corner-handle resize are always active when a widget is selected in edit mode.
**Fix:** Add a "Move / Resize" toggle state per widget. Move/resize handles are only activated after explicitly choosing "Move / Resize" from the widget's context menu. Tapping elsewhere or re-tapping the widget exits move/resize mode.

### 4. Trip state is duplicated in DashboardEditorFragment
**Root cause:** `DashboardEditorFragment` has its own local `tripPhase` enum and calls `calculator.startTrip()` / `stopTrip()` directly, independent of `TripViewModel`. This means the Trip screen and Dashboard screen can have conflicting trip states.
**Fix:** Remove the local trip controls from `DashboardEditorFragment`. Trip start/pause/stop buttons on the dashboard top bar should delegate to the shared `TripViewModel` (same singleton used by `TripFragment`), so trip state is unified app-wide.

### 5. Default dashboard shown directly when Dashboards page visited
**Root cause:** `DashboardsHostFragment` always starts at `LayoutListFragment` (the nav graph's `startDestination`).
**Fix:** Change `DashboardsHostFragment` to check if a default dashboard is set (via `LayoutRepository.getDefaultLayoutName()`). If yes, navigate directly to `DashboardEditorFragment` in view mode. If no default is set, fall through to `LayoutListFragment`.

### 6. Edit dashboards and Add new dashboard via context menu on Dashboards page
**Root cause:** Currently, the Dashboards page always goes to `LayoutListFragment`. With fix #5, the list is bypassed.
**Fix:** Add an overflow/context menu to the dashboard view screen with:
- **Edit Layout** — switches to edit mode
- **Manage Dashboards** — navigates to `LayoutListFragment`
- **Add New Dashboard** — shows the create dialog inline

---

## Files to Modify

| File | Change |
|------|--------|
| `DashboardEditorFragment.kt` | Fix save button visibility; move/resize as explicit menu; delegate trip to TripViewModel |
| `DashboardEditorViewModel.kt` | Center placement for new widgets; expose canvas size setter |
| `DashboardsHostFragment.kt` | Auto-navigate to default dashboard or LayoutList |
| `LayoutListFragment.kt` | Minor (context menu entry points) |
| `dashboards_navigation.xml` | Add direct `nav_editor` as possible start destination |

---

## Out of Scope
- Dashboard widget types, metrics, or visual styling changes
- LayoutRepository persistence format changes
