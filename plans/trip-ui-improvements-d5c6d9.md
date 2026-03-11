# Trip Screen UI Improvements (Full Plan)

Comprehensive improvements to the Trip and Details screens: currency symbol, new metrics, collapsible orientation, three-dot icon, ViewPager2 swipe navigation (Approach A), and freeze-last-values on Trip and Details screens.

## Changes

### 1. Fuel Cost Unit → ₹
- **`TripViewModel.kt`**: Replace `"— $"` / `"%.2f $"` with `"— ₹"` / `"₹%.2f"` in `buildUiState()`
- **`TripUiState`**: Update default `fuelCost = "— ₹"`
- **`DetailsFragment.kt`**: Update `tvFuelCostEstimate` binding to use `₹` symbol

### 2. Add Coolant Temp + Avg Fuel Consumption (kmpl) Above Fuel Cost
- **`TripUiState`**: Add `coolantTemp: String = "— °C"` and `avgFuelKmpl: String = "— kmpl"` fields
- **`TripViewModel.kt`**: Populate from `metrics.coolantTempC` and `metrics.tripAvgKpl`; freeze these too on IDLE (see item 7)
- **`fragment_trip.xml`**: Add new 2-column row (Coolant Temp + Avg kmpl) above the Fuel Cost row in the TRIP card
- **`TripFragment.kt`**: Bind new views in `applyState()`

### 3. Idle % Calculation — Confirmed Correct ✅
- `TripState.driveModePercents()`: Idle ≤ 2 km/h, City 2–60 km/h, Highway > 60 km/h (rolling 60-second window)
- **No code changes needed**

### 4. Collapsible Orientation Section
- **`fragment_trip.xml`**: Add chevron `ImageView` next to "ORIENTATION" header; wrap body in a named `LinearLayout` (`id/gravity_content`)
- **`TripFragment.kt`**: Toggle `gravityContent.visibility` + flip chevron on header click; default = expanded

### 5. Replace Gear Icon with Three Vertical Dots
- **`include_top_bar.xml`**: Change `android:src` to `@drawable/ic_more_vert`
- Add `ic_more_vert` vector drawable if not already present in `res/drawable`

### 6. Swipe Navigation — ViewPager2 as Top-Level Host (Approach A)
Replace `NavHostFragment` with a `ViewPager2` for 4 main screens. The Dashboards → Editor deep-link still uses a sub-`NavHostFragment` inside the Dashboards page.

- **`content_main.xml`**: Replace `FragmentContainerView` (NavHostFragment) with `ViewPager2`
- **New `MainPagerAdapter.kt`**: `FragmentStateAdapter` with 5 fixed pages — Trip (0), Connect (1), Dashboards/LayoutList (2), Details (3), Settings (4)
  - Page 2 (Dashboards) hosts its own `NavHostFragment` limited to `nav_layout_list → nav_editor` so the Editor deep-link still works
- **`MainActivity.kt`**:
  - Remove `findNavController` calls for top-level navigation; use `viewPager.setCurrentItem()` instead
  - Keep `onObd2Connected()` → swipe to Trip page (index 0)
  - Keep `navigateToConnect()` → swipe to Connect page (index 1)
  - Keep `navigateToLayoutList()` → swipe to Dashboards page (index 2)
- **`TopBarHelper.kt`**: Update `attachNavOverflow` to accept a `ViewPager2` reference and call `setCurrentItem()` instead of `NavController.navigate()`
  - Settings overflow item → swipe to Settings page (index 4)
- **`build.gradle` / `libs.versions.toml`**: `viewpager2` dependency already present (`1.0.0`) ✅

### 7. Freeze Last-Seen Values — Trip Screen
Currently `buildUiState()` resets distance/fuel cost to defaults on `TripPhase.IDLE`. Change so values persist until **Start** is pressed again.

- **`TripViewModel.kt`**:
  - Add `private var _frozenTripValues: TripFrozenValues? = null` (simple data class holding distance, fuelCost, idlePercent, coolantTemp, avgFuelKmpl)
  - On `TripPhase.IDLE` transition, snapshot the last non-IDLE values into `_frozenTripValues`
  - `buildUiState()` uses frozen values when phase is IDLE (instead of "— ₹" / "0.0 km" defaults)
  - `startTrip()` clears `_frozenTripValues` so fresh values show after Start

### 8. Freeze Last-Seen Values — Details Screen
Currently `DetailsViewModel` replaces `_vehicleMetrics` on every emission; when OBD disconnects `MetricsCalculator` emits an empty `VehicleMetrics()`, clearing the display.

- **`DetailsViewModel.kt`**:
  - Add `private var lastNonNullMetrics: VehicleMetrics? = null`
  - In the `calculator.metrics.collect` lambda, only update `_vehicleMetrics` if the incoming snapshot has at least one non-null OBD value; otherwise keep the last good snapshot
  - Similarly for `obd2Data`: keep last non-empty list when new list is empty

## Files to Modify
| File | What changes |
|------|--------------|
| `fragment_trip.xml` | New metric row, collapsible orientation header+chevron |
| `include_top_bar.xml` | Gear → three-dot icon |
| `res/drawable/ic_more_vert.xml` | Add vector (if missing) |
| `content_main.xml` | NavHostFragment → ViewPager2 |
| `TripUiState` / `TripViewModel.kt` | ₹, new fields, freeze-last-values logic |
| `TripFragment.kt` | Bind new views, collapse toggle |
| `DetailsFragment.kt` | ₹ in fuel cost |
| `DetailsViewModel.kt` | Freeze-last-values logic |
| `MainActivity.kt` | ViewPager2 wiring, remove NavController top-nav |
| `TopBarHelper.kt` | Use ViewPager2 setCurrentItem |
| **New** `MainPagerAdapter.kt` | FragmentStateAdapter for 4 pages |
