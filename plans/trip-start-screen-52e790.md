# Trip Control Screen + Wake Lock Fix

Add a dedicated `TripFragment` shown after OBD connects, centralising trip control (Start / Pause / Stop) and sensor readiness status; simultaneously fix `FLAG_KEEP_SCREEN_ON` which currently never works because it lives in the wrong fragment.

---

## Wake lock bug (root cause)

**Current code sets `FLAG_KEEP_SCREEN_ON` in `DashboardFragment.onCreateView()` — but `onObd2Connected()` navigates to `DashboardEditorFragment`, which has no wake lock code at all.** `DashboardFragment` also clears the flag in `onDestroyView()`, which fires as soon as navigation leaves that fragment even mid-trip.

**Fix:** Move wake lock management to `MainActivity` — it observes `MetricsCalculator.tripPhase` in `onResume`/`onCreate` and adds/clears `FLAG_KEEP_SCREEN_ON` on the Activity window directly. This is screen-navigation-agnostic. Remove the flag management from `DashboardFragment.onDestroyView()`.

---

## Navigation flow (after this change)

```
App launch
  → ConnectFragment (auto-connect or manual)
       ↓ connected
  → TripFragment  ← NEW, replaces nav to dashboard/layout-list on connect
       ↓ accessible from overflow menu at any time
  → Dashboard / Details / Settings (unchanged)
```

- `MainActivity.onObd2Connected()` navigates to `nav_trip` instead of dashboard/layout-list.
- `nav_trip` added to `mobile_navigation.xml` and `overflow.xml`.
- `startDestination` remains `nav_layout_list` (for mock / offline mode). Only the post-connect target changes.

---

## Trip screen layout (scrollable, dark theme)

```
┌──────────────────────────────────┐
│  [overflow ⋮]   Trip Control     │
├──────────────────────────────────┤
│  ── Sensor Readiness ──          │
│  🟢 OBD2   CONNECTED · 2100 RPM  │
│  🟢 GPS    Fix · 18.98°N 72.83°E │
│             9 sats · ±4 m        │
│  🟡 Accel  Enabled · awaiting    │
├──────────────────────────────────┤
│  ── Gravity Vector ──            │
│  X: −0.02  Y: 9.78  Z: 0.15     │
│  (shown only after startTrip)    │
├──────────────────────────────────┤
│  ── Trip ──                      │
│  Phase    IDLE                   │
│  Samples  0                      │
│  Duration 00:00                  │
│  Distance 0.0 km                 │
├──────────────────────────────────┤
│  [  START  ]  [ PAUSE ]  [ STOP ]│
└──────────────────────────────────┘
```

---

## Sensor status rules

| Source | Indicator colour | Detail shown |
|---|---|---|
| **OBD2** | 🟢 CONNECTED / 🟡 CONNECTING / 🔴 ERROR | connection state label |
| **GPS** | 🟢 fix / 🟡 no fix / 🔴 no permission | **Speed** km/h · **Alt** m MSL · **Accuracy** ±m |
| **Accel** | 🟢 vector captured / 🟡 waiting / ⚫ disabled | **Accel power** kW (sign reflects direction: + accel, − braking) |

GPS replaces lat/lon/satellites with operationally useful values: speed, altitude, and horizontal accuracy.

Accel power = `vehicleMassKg × accelFwdMean × gpsSpeedMs / 1000` kW, sign preserved (positive = driving force, negative = braking/decel). Shown as e.g. `+18.4 kW` or `−6.2 kW`. Falls back to `powerAccelKw` already computed in `VehicleMetrics`.

---

## Gravity vector panel

- Visible only when accelerometer is **enabled in Settings**.
- Values show `AccelerometerSource.gravityVector` (X/Y/Z m/s²).
- Label: "Awaiting trip start…" before `startTrip()`; "Captured — basis locked" after.

---

## Button states

| Phase | START label | PAUSE label | STOP |
|---|---|---|---|
| IDLE | **Start** ✅ | — (gone) | — (gone) |
| RUNNING | — (gone) | **Pause** ✅ | **Stop** ✅ |
| PAUSED | **Resume** ✅ | — (gone) | **Stop** ✅ |

STOP triggers `stopTrip()` + auto-share dialog if `AppSettings.isAutoShareLogEnabled()`.

---

## Trip status fields (live, 1 s ticker)

| Field | Source |
|---|---|
| Phase | `MetricsCalculator.tripPhase` |
| Samples logged | new `MetricsLogger.currentSampleNo` getter |
| Duration | ticker from `tripState.tripStartMs` |
| Distance | `VehicleMetrics.tripDistanceKm` |
| **Avg Fuel Consumption** | `VehicleMetrics.tripAvgLper100km` (L/100 km) |
| **Coolant Temp** | `VehicleMetrics.coolantTempC` (°C) |

---

## Files to create / modify

| Action | File |
|---|---|
| **Create** | `ui/trip/TripFragment.kt` |
| **Create** | `ui/trip/TripViewModel.kt` |
| **Create** | `res/layout/fragment_trip.xml` |
| **Modify** | `mobile_navigation.xml` — add `nav_trip` |
| **Modify** | `overflow.xml` — add Trip menu item |
| **Modify** | `MainActivity.kt` — navigate to `nav_trip` after connect; add wake lock observer |
| **Modify** | `DashboardFragment.kt` — remove wake lock code (moved to Activity) |
| **Modify** | `MetricsLogger.kt` — expose `val currentSampleNo: Int` |
| **Modify** | `MetricsCalculator.kt` — expose `capturedGravityVector: FloatArray?` readable field |
