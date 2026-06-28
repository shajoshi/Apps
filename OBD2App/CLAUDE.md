# CLAUDE.md — OBD2App LLM Reference

> Concise orientation for LLMs working on this codebase.
> For full detail: `ARCHITECTURE.md` (deep reference) · `AGENTS.md` (protocol rules + workflows).

---

## What This App Does

**OBD2App** is a Kotlin Android vehicle diagnostics and trip-computer app.  
It connects to ELM327-compatible OBD-II adapters over Bluetooth (Classic or BLE), polls live ECU sensor data, computes fuel/power/trip metrics, renders fully configurable widget dashboards, and records GPS-tagged trip logs with optional CAN Bus capture.

---

## Tech Stack

| Concern | Choice |
|---------|--------|
| Language | Kotlin |
| UI | View Binding + Fragments — **no Compose** |
| Min/Target SDK | 26 / 36 |
| Async | Kotlin Coroutines + `StateFlow` |
| Bluetooth | Classic RFCOMM + BLE GATT (via `Elm327Transport` abstraction) |
| Maps | OSMDroid (OpenStreetMap) |
| Storage | SAF `DocumentFile` in `.obd/` directory + app-private fallback |
| Serialisation | Gson with custom `DashboardMetricAdapter` for sealed-class polymorphism |
| Background | `TripForegroundService` (`START_STICKY`) |

---

## Package Map

```
com.sj.obd2app/
├── bluetooth/          BluetoothBondLossReceiver
├── can/                CAN Bus engine (CanBusScanner, CanDataOrchestrator,
│                       CanFrameParser, CanDecoder, DbcParser/DbcDatabase,
│                       CanProfile, CanProfileRepository, RawCanTraceRecorder,
│                       MockCanFrameSource, DemoDbcDatabase, CanEncoder)
├── gps/                GpsDataSource (FusedLocation), GpsDataItem, GeoidCorrection
├── metrics/            MetricsCalculator (central singleton), TripLifecycleFacade,
│   │                   VehicleMetrics, TripPhase, TripState, MetricsLogger,
│   │                   AccelEngine, AccelMetrics, AccelCalibration, PowerCalculations,
│   │                   TrackFileParser
│   ├── calculator/     FuelCalculator, PowerCalculator, TripCalculator
│   └── collector/      ObdDataOrchestrator, DataOrchestrator (CAN-mode)
├── obd/                BluetoothObd2Service, Elm327Transport (interface),
│                       ClassicBluetoothTransport, BleTransport, TransportFactory,
│                       Obd2ServiceProvider, ObdStateManager, ObdConnectionManager,
│                       ManufacturerPidLibrary, PidDiscoveryService, PidFormulaParser,
│                       Obd2CommandRegistry, CustomPid, MockObd2Service,
│                       ConnectTarget, ConnectionSettingsSource, UserNotifier
├── sensors/            AccelerometerSource
├── service/            TripForegroundService
├── settings/           AppSettings, VehicleProfile, VehicleProfileRepository,
│                       PidCache, CustomPidDiff, CustomPidEditSheet,
│                       CustomPidListSheet, VehicleProfileEditSheet
├── storage/            AppDataDirectory, DataMigration
└── ui/
    ├── connect/        ConnectFragment, ConnectViewModel, device adapters
    ├── can/            CanReaderFragment, CanProfileEditSheet
    ├── dashboard/      DashboardFragment, DashboardEditorFragment,
    │   │               DashboardEditorViewModel, EditWidgetSheet,
    │   │               LayoutListFragment, MetricListAdapter, GridOverlayView,
    │   │               WidgetTouchHandler, WidgetResizeHandler
    │   ├── model/      DashboardLayout, DashboardWidget, DashboardMetric (sealed),
    │   │               WidgetType, MetricDefaults, CanMetricSource
    │   ├── data/       LayoutRepository (Gson + DashboardMetricAdapter)
    │   ├── views/      LiveMapView, DialView, BarGaugeView, SevenSegmentView,
    │   │               NumericDisplayView, TemperatureGaugeView, DashboardGaugeView
    │   └── wizard/     AddWidgetWizardSheet, Step1–3 pages, WizardState
    ├── details/        DetailsFragment (raw OBD2 PID dump)
    ├── mapview/        MapViewFragment, MapViewModel, SampleDetailsFragment
    ├── settings/       SettingsFragment, PidDiscoverySheet,
    │                   ConsoleAdapter, DiscoveredPidAdapter
    └── trip/           TripFragment, TripViewModel
    └── tripsummary/    TripSummaryFragment, TripSummaryViewModel, TripSelectionStore,
                        TrackFileItem, ParsedFile
```

---

## Navigation (8 ViewPager2 Pages)

| Page | Fragment | Access |
|------|----------|--------|
| 0 | `ConnectFragment` | Swipe / overflow |
| 1 | `TripFragment` | Swipe / overflow |
| 2 | `DashboardsHostFragment` | Swipe / overflow |
| 3 | `DetailsFragment` | Swipe / overflow |
| 4 | `TripSummaryFragment` | **Overflow only** |
| 5 | `MapViewFragment` | **Overflow only + must come from page 4** |
| 6 | `SettingsFragment` | **Overflow only + no active trip + OBD disconnected** |
| 7 | `CanReaderFragment` | **Overflow only** |

Pages 4–7 are **swipe-blocked** — `MainActivity` intercepts swipe attempts and reverts them.

---

## Core Singletons (creation order matters)

1. **`ObdStateManager`** — init first. Sets MOCK vs REAL mode. Must be called before `MetricsCalculator` is constructed.
2. **`Obd2ServiceProvider`** — wraps the active `Obd2Service`. `initMock()` or `initBluetooth()` select implementation.
3. **`GpsDataSource`** — started in `MainActivity.onCreate()`, never stopped.
4. **`MetricsCalculator`** — created on first `getInstance()`. Starts `ObdDataOrchestrator` immediately.
5. **`TripLifecycleFacade`** — the **only authorised entry point** for trip start/pause/resume/stop.
6. **`AccelerometerSource`** — started/stopped with trip lifecycle (opt-in via settings).

---

## Two Operating Modes

### OBD Mode (default, `useCanBusLogging = false`)
```
Elm327Transport → BluetoothObd2Service → ObdDataOrchestrator
  → MetricsCalculator.calculate() → VehicleMetrics StateFlow → UI
```

### CAN Bus Mode (`useCanBusLogging = true`)
```
Elm327Transport → CanBusScanner (ATMA) → CanFrameParser → CanDecoder
  → CanDataOrchestrator (tick at syncTickerHz) → VehicleMetrics StateFlow → UI
```

OBD polling is **entirely suppressed** in CAN mode. Both modes share the same `VehicleMetrics` output and `TripForegroundService`.

---

## Key Data Models

### `VehicleMetrics` (immutable, ~50 nullable fields)
Single snapshot of all computed data. Groups:
- **OBD2**: `rpm`, `vehicleSpeedKmh`, `engineLoadPct`, `coolantTempC`, `fuelLevelPct`, `mafGs`, ...
- **GPS**: `gpsLatitude`, `gpsLongitude`, `gpsSpeedKmh`, `altitudeMslM`, `gpsBearingDeg`, ...
- **Derived fuel**: `instantLper100km`, `tripFuelUsedL`, `tripAvgLper100km`, `rangeRemainingKm`, ...
- **Derived trip**: `tripDistanceKm`, `tripTimeSec` (0 when IDLE), `tripAvgSpeedKmh`, ...
- **Power**: `powerAccelKw`, `powerThermoKw`, `powerOBDKw`
- **Accel**: `accelVertRms`, `accelFwdMean`, `accelLeanAngleDeg`, ...

### `DashboardMetric` (sealed class — not enum)
```kotlin
sealed class DashboardMetric {
    data class Obd2Pid(val pid: String, val name: String, val unit: String)
    object GpsSpeed
    object GpsAltitude
    data class DerivedMetric(val key: String, val name: String, val unit: String)
    data class CanSignal(val messageId: Int, val signalName: String, val name: String, val unit: String)
        // latestKey() = "<HEX_ID>:<signalName>" — used to look up CanBusScanner.latest
}
```
**Adding a new subclass requires updating `DashboardMetricAdapter`** in `LayoutRepository.kt` or Gson will silently drop widgets.

### `DashboardWidget`
```
id, type (WidgetType), metric (DashboardMetric)
gridX, gridY, gridW, gridH   — virtual grid (1 unit = 24 dp)
zOrder, alpha
rangeMin, rangeMax, majorTickInterval, minorTickCount
warningThreshold, decimalPlaces, displayUnit
cornerMetricTL/TR/BL/BR/ML/MR  — LIVE_MAP overlays only
```

### `VehicleProfile`
```
id, name, fuelType (Petrol/E20/Diesel/CNG), tankCapacityL, fuelPricePerLitre
enginePowerBhp, vehicleMassKg, engineDisplacementCc, volumetricEfficiencyPct
manufacturer → ManufacturerPidLibrary.Manufacturer (optional, backward-compatible)
customPids: List<CustomPid>           — user-defined
effectiveCustomPids (computed)         — user + manufacturer presets (user overrides by ID)
```

### `PidCache`
```
macAddress, discoveredPids: Map<String, CachedPidEntry>, timestamp
protocolNumber: String?  — cached from first connection; used for ATSP<N> on reconnect
```

---

## Trip Lifecycle

```
IDLE ──[startTrip()]──► RUNNING ──[pauseTrip()]──► PAUSED
  ▲                         │                          │
  └────[stopTrip()]──────────┴──────[resumeTrip()]─────┘
```

**Always use `TripLifecycleFacade`** — never call `MetricsCalculator.startTripInternal()` directly.  
The facade routes to OBD or CAN mode internally, manages `TripForegroundService`, and starts/stops `ObdConnectionManager` monitoring.

---

## LIVE_MAP Widget

`WidgetType.LIVE_MAP` renders an OSMDroid map inside a dashboard widget (`LiveMapView`).

- **6 metric overlay positions**: TL, TR, BL, BR, ML (mid-left edge), MR (mid-right edge)
- **Trip time format**: `M:SS` when < 10 min, `H:MM` when ≥ 10 min (set in `DashboardEditorFragment.formatTripTime()`)
- **Overlay TextViews**: `maxLines=1`, `textSize=28sp`, fixed 65dp circular badge — prevents wrapping
- **Map orientation**: north-up / heading-up toggle
- **Zoom**: auto-scales with GPS speed

---

## Storage Layout

```
<user-selected-folder>/
└── .obd/
    ├── profiles/   vehicle_profile_<name>.json    (one per profile)
    ├── layouts/    dashboard_<name>.json           (one per layout)
    ├── settings.json
    └── obd_bt_connx.log                           (BT event log, append-only)

app-private (context.filesDir)/
├── can_dbc/<profileId>.dbc                        (DBC file copies)
└── can_captures/<profileId>.jsonl                 (imported CAN capture replays)
```

**⚠ Always use `"wt"` mode** for `ContentResolver.openOutputStream`. Mode `"w"` does NOT truncate on Android 10+ — stale trailing bytes corrupt JSON.

---

## Critical Rules

| Rule | Why |
|------|-----|
| `ObdStateManager.initialize()` before ViewPager creation | Locks in MOCK/REAL service; too late to change after `MetricsCalculator` is constructed |
| Use `TripLifecycleFacade` for all trip transitions | Bypassing it skips CAN routing, foreground service, and connection monitoring |
| Use `VehicleProfile.effectiveCustomPids` for polling | `customPids` alone misses manufacturer presets |
| Update `DashboardMetricAdapter` when adding `DashboardMetric` subclass | Gson polymorphism requires explicit type registration |
| `"wt"` mode for all `openOutputStream` calls | Prevents JSON corruption on Android 10+ |
| OBD polling is suppressed in CAN mode | Do not start both simultaneously |
| Pages 4–7 are overflow-menu only | Swipe gestures to them are intercepted and reverted |
| Map View only accessible from Trip Summary | `MainActivity.navigateToPage()` enforces this guard |
| Settings blocked when OBD is connected | In addition to trip-active check |

---

## OBD Connection Sequence

```
First connection:
  ATZ → ATE0 → ATL0 → ATS0 → ATH0 → ATAT1 → ATSTFF → ATSP0 (auto-detect)
  → detect protocol → lock with ATSP<N> → cache protocolNumber in PidCache

Later connections (cached):
  ATZ → ATE0 → ATL0 → ATS0 → ATH0 → ATAT1 → ATSP<N>  (skip auto-detect)

PID discovery:
  ATE0 → ATL0 → ATS0  (no ATZ — preserves active connection state)
```

Read-only discovery modes only: **01, 02, 03, 07, 09, 21, 22, 23**.  
Never use modes 04, 08, 2E, 2F, 31 (actuator / write / clear DTC).

---

## Memory Strategy — JsonReader Streaming

All file reads use `android.util.JsonReader` token streaming. **Never call `readText()` or load a full JSON file into a `JSONObject`/`JSONArray`.**

| Operation | Peak memory |
|-----------|-------------|
| `TrackFileParser.parseTrackFile()` | 2 small `JSONObject`s (vehicleProfile + lastSample) |
| `TripSummaryViewModel.scanFileForStats()` | Same — 2 objects per file, discarded after |
| `TripSummaryViewModel.saveCombinedFile()` | 1 sample `String` at a time written to `BufferedWriter` |
| `MapViewModel.loadPathPoints()` | `GeoPoint` only per sample (~40 bytes); zero `JSONObject` during path load |
| `MapViewModel.fetchSample(index)` | 1 `JSONObject` on demand (seek/nav/details); GC-able immediately |

**Key helpers** (in `MapViewModel` and `TripSummaryViewModel`):
- `readGeoPoint(reader)` — skips all tokens except `gps.lat/lon`, allocates only a `GeoPoint`
- `readJsonValue(reader)` — reconstructs one complete JSON value token-by-token into a `String`
- `fetchSample(index)` — re-opens the file, skips to sample `N`, reads exactly one `JSONObject`

`fetchedSample: StateFlow<JSONObject?>` in `MapViewModel` is the single shared sample; both `MapViewFragment` (cursor info bar) and `SampleDetailsFragment` observe it — no duplication.

---

## Common Workflows

### Add a new `DashboardMetric` subclass
1. Add to `DashboardMetric.kt`
2. Update `DashboardMetricAdapter.serialize()` + `deserialize()` in `LayoutRepository.kt`
3. Handle in `DashboardEditorFragment.updateCorner()` and `MetricListAdapter`
4. Add defaults in `MetricDefaults.kt`

### Add LIVE_MAP corner overlay position
1. `DashboardWidget.kt` — add field
2. `LiveMapView.kt` — add `Corner` enum value, bind view, handle in `updateCornerValue()`
3. `DashboardEditorFragment.kt` — add to both `updateLiveMapCornerMetrics()` and live-data collection
4. `EditWidgetSheet.kt` — add selector row
5. `DashboardEditorViewModel.kt` — add param to `addWidgetWithProperties()` + `updateWidgetProperties()`
6. `widget_properties_editor.xml` — add UI row

### Start a trip
```kotlin
TripLifecycleFacade.getInstance(context).startTrip()
// Automatically routes to OBD or CAN mode based on AppSettings.isCanBusLoggingEnabled
```

### Force PID re-discovery
```kotlin
AppSettings.updatePendingSettings(context) { it.copy(ignoreCachedPids = true) }
AppSettings.savePendingSettings(context)
// Next connect() call will ignore cached PIDs and re-run discovery
```
