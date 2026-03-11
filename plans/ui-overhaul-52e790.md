# UI Overhaul Plan

Replaces the drawer/bottom-nav shell with a minimal toolbar + overflow menu, tightens the BT startup flow, and reworks the dashboard list and editor UX.

---

## 1. Shell — remove drawer & bottom nav bar

- **`activity_main.xml`** — replace `DrawerLayout` + `NavigationView` with a plain `FrameLayout` containing `AppBarLayout` (slim toolbar, no hamburger) + `NavHostFragment`.
- **`app_bar_main.xml`** — keep the toolbar but remove `DrawerLayout` tie-in; add a `⋮` overflow menu icon (3-dot) only.
- **`content_main.xml`** — remove `BottomNavigationView`.
- **`MainActivity`** — remove `setupWithNavController` wiring for drawer and bottom-nav; inflate a simple overflow menu (`overflow.xml`) with items: **Settings**, **Saved Dashboards**, **Connect**, **Details**. Remove `AppBarConfiguration` drawer support.
- Delete `side_nav_bar.xml` / `nav_header_main.xml` references (keep files, just stop using them).

---

## 2. Startup / BT flow

Modify **`MainActivity.onCreate`**:

| Step | Action |
|---|---|
| a | Check BT permissions → request if missing |
| b | Check if BT enabled → `ACTION_REQUEST_ENABLE` dialog |
| c | On BT result: if **denied** → navigate to `nav_layout_list` (Saved Dashboards) with a banner "BT off — limited mode"; Settings still accessible via ⋮ menu |
| d | If BT **enabled** + auto-connect setting is YES → call `viewModel.tryAutoConnect()` and navigate to `nav_connect` |
| e | If BT **enabled** + no auto-connect → navigate to `nav_connect` (device list) |
| f | On successful OBD connection event (observe `isConnected` LiveData) → auto-navigate to default/last dashboard in `nav_layout_list` → open it |

**OBD device filter (point d):** `ConnectViewModel.loadPairedDevices` already loads paired devices. We add a filter: show only devices whose name contains common OBD keywords (`OBD`, `ELM`, `OBDII`, `Vgate`, `iCar`, `VEEPEAK`, `Konnwei`). Unrecognised devices are shown in a collapsible "Other devices" section.

---

## 3. Saved Dashboards screen (`LayoutListFragment`)

- **Double-tap to view** — replace single `setOnClickListener` with a double-tap detector that opens the dashboard in **view mode** (no edit).
- **Single tap** — show a contextual action row (inline) with: `Open`, `Edit`, `Share`, `Delete`.
- **"Create New Dashboard"** — show a `MaterialAlertDialog` asking for the dashboard name, then navigate to editor with `mode=create` and the name as arg.
- Layout list item: show name, widget count, last-saved date.

---

## 4. Dashboard Editor (`DashboardEditorFragment`)

### Top bar (in-screen, not the activity toolbar)
- Replace bottom toolbar buttons with a **top strip** (8 dp high, dark):
  - Left: **← Back** icon (navigate up)
  - Centre: dashboard name label
  - Right icons: **▶ Play** (start trip), **⏸ Pause**, **⏹ Stop** — these control `MetricsCalculator`
  - Far right: **⋮** menu → items: `Edit Layout`, `Save`, `Add Widget` (only when in edit mode)

### Remove
- Bottom `editor_toolbar` (`LinearLayout`) entirely from the XML.
- `btn_toggle_edit`, `btn_start_trip`, `btn_toggle_log`, `btn_add_widget`, `btn_save` — all removed from layout and Fragment code.
- Trip logging toggling (logging follows the Setting automatically — no manual toggle needed).

### Edit mode
- Toggled via ⋮ menu "Edit Layout". Grid overlay appears. Add Widget button appears in ⋮ menu.
- "Save" available in ⋮ menu when in edit mode.
- On **back press while unsaved edits exist** → show `MaterialAlertDialog` "Save changes?" Yes / Discard / Cancel.

### View mode (opened via double-tap from list)
- No edit handles, no grid, no property panel visible.
- Trip control icons visible in top strip.

---

## 5. Navigation graph changes (`mobile_navigation.xml`)

- Change `startDestination` from `nav_connect` → `nav_layout_list`.
- Add argument to `nav_editor`: `layoutName: String?` (nullable = create new), `mode: String` (`"view"` | `"edit"`).
- Add action from `nav_connect` → `nav_layout_list` (after connect success).

---

## Files to change

| File | Change |
|---|---|
| `activity_main.xml` | Remove DrawerLayout/NavigationView |
| `app_bar_main.xml` | Slim toolbar only |
| `content_main.xml` | Remove BottomNavigationView |
| `MainActivity.kt` | BT flow, overflow menu, remove drawer wiring |
| `mobile_navigation.xml` | startDestination, add args, new actions |
| `fragment_layout_list.xml` | Item layout with name/count/date; Create New dialog |
| `LayoutListFragment.kt` | Double-tap, contextual actions, name dialog |
| `fragment_dashboard_editor.xml` | Remove bottom toolbar; add top strip with trip icons |
| `DashboardEditorFragment.kt` | Wire top strip; edit/view mode toggle; unsaved-changes guard |
| `ConnectViewModel.kt` | OBD device name filter |
| `ConnectFragment.kt` | "Other devices" collapsible section |

---

## 6. Default Dashboard

- **`LayoutRepository`** — add a `defaultLayoutName: String?` field persisted in SharedPreferences.
- **`LayoutListFragment`** — each list item gets a ☆ star icon (tap to set as default, filled ★ = current default). Only one can be default at a time.
- **On OBD connect** → auto-open the dashboard marked as default. If none is marked default, open `nav_layout_list` so the user can pick.
- `DashboardLayout` model gains no new fields; default is tracked separately in prefs (just the name/id).

---

## 7. Details screen

- Move **Details** to the ⋮ overflow menu (alongside Settings, Saved Dashboards, Connect).
- Remove from bottom nav / drawer entirely.
- `nav_details` stays in navigation graph; just no longer a top-level nav destination.

---

## 8. Trip controls — Pause / Stop semantics

| Button | Action |
|---|---|
| ▶ **Start** | `MetricsCalculator.startTrip()`; opens log file if logging enabled in Settings |
| ⏸ **Pause** | Suspend accumulation (`TripState` stops receiving updates); log file stays open |
| ▶ **Resume** | Re-enable accumulation |
| ⏹ **Stop** | End trip; close log file if open (`MetricsLogger.close()`); reset `TripState` |

- Start/Pause/Resume/Stop state machine managed in `DashboardEditorFragment` with a simple enum `TripPhase { IDLE, RUNNING, PAUSED }`.
- Icon set: `ic_play`, `ic_pause`, `ic_stop` (standard Material icons, already available or add as vectors).

---

## Files to change (updated)

| File | Change |
|---|---|
| `activity_main.xml` | Remove DrawerLayout/NavigationView |
| `app_bar_main.xml` | Slim toolbar only |
| `content_main.xml` | Remove BottomNavigationView |
| `MainActivity.kt` | BT flow, overflow menu (Settings/Dashboards/Connect/Details), remove drawer wiring |
| `mobile_navigation.xml` | startDestination→layout_list, add args to nav_editor, new actions |
| `LayoutRepository.kt` | Add getDefaultLayout / setDefaultLayout persisted in SharedPreferences |
| `fragment_layout_list.xml` | Item layout: name + widget count + date + ★ default star |
| `LayoutListFragment.kt` | Star toggle, double-tap→view, single-tap→action row, Create New name dialog |
| `fragment_dashboard_editor.xml` | Remove bottom toolbar; add in-screen top strip with trip icons + ⋮ |
| `DashboardEditorFragment.kt` | Top strip wiring; TripPhase state machine; unsaved-changes guard on back |
| `ConnectViewModel.kt` | OBD device name filter |
| `ConnectFragment.kt` | "Other devices" collapsible section |
