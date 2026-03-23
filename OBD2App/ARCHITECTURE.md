# OBD2App — Architecture Reference

> Agent-optimised technical reference. Covers design, data flow, key classes, constraints, and gotchas for the OBD2App Android vehicle diagnostics and trip computer.

---

## 1. Purpose & Tech Stack

OBD2App is a **Bluetooth OBD-II vehicle monitor and trip computer** that connects to ELM327-compatible adapters, reads ECU sensor data, computes fuel efficiency and power metrics, and displays them on a fully customisable dashboard.

| Concern | Technology |
|---------|-----------|
| UI | View Binding + Fragment (not Compose) |
| Navigation | `ViewPager2` tabs + Fragment back-stack |
| OBD2 communication | Bluetooth Classic RFCOMM (`BluetoothSocket`, SPP UUID) |
| Location | FusedLocationProviderClient (Google Play Services) |
| Accelerometer | Android `SensorManager` / `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` |
| Settings persistence | JSON files in `.obd/` directory (SAF `DocumentFile`), fallback `SharedPreferences` (`obd2_prefs`) |
| Data storage | SAF tree URI → `<selected_folder>/.obd/{profiles,layouts}/` with app-private fallback |
| Layout serialisation | Gson with custom `DashboardMetricAdapter` for sealed-class polymorphism |
| Async | Kotlin Coroutines + `StateFlow` |
| Background | `TripForegroundService` — Android Foreground Service (`START_STICKY`) |
| Mock/dev mode | `MockObd2Service` reads `assets/mock_obd2_data.json` + optional `mock_obd2_enhanced.json` for discovery testing |

---

## 2. Module / File Map

### Root (`com.sj.obd2app`)
| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Single activity. Initialises `Obd2ServiceProvider`, requests BT/location permissions, owns `ViewPager2` with `MainPagerAdapter`. |
| `MainPagerAdapter.kt` | ViewPager2 adapter — 5 tabs: Connect (0), Trip (1), Dashboards (2), Details (3), Settings (4). Page constants: `PAGE_CONNECT`, `PAGE_TRIP`, `PAGE_DASHBOARDS`, `PAGE_DETAILS`, `PAGE_SETTINGS`. |
| `DashboardsHostFragment.kt` | Host fragment for the Dashboard tab, switches between layout list and dashboard view. |

### `gps/`
| File | Responsibility |
|------|---------------|
| `GpsDataSource.kt` | Singleton. FusedLocation at 1 s / 500 ms min interval. Exposes `StateFlow<GpsDataItem?>`. Tracks satellite count via `GnssStatus.Callback`. |
| `GpsDataItem.kt` | Data class: speed, MSL altitude, ellipsoid altitude, geoid undulation, accuracy, bearing, satellite count. |
| `GeoidCorrection.kt` | Lookup table for WGS84 geoid undulation used to convert ellipsoid altitude → MSL. |

### `metrics/`
| File | Responsibility |
|------|---------------|
| `MetricsCalculator.kt` | **Central singleton.** Owns `DataOrchestrator`, trip phase state machine, all sub-calculators. `calculate()` returns `VehicleMetrics`. Exposes `StateFlow<VehicleMetrics>`, `StateFlow<TripPhase>`, and `StateFlow<Boolean>` (`dashboardEditMode`). |
| `VehicleMetrics.kt` | Immutable snapshot of all metrics for one calculation cycle. ~50 nullable fields. |
| `MetricsLogger.kt` | JSON trip log writer. One JSON file per trip. Header object + one sample object per line. Gated on `AppSettings.isLoggingEnabled`. |
| `TripPhase.kt` | Enum: `IDLE` / `RUNNING` / `PAUSED`. |
| `TripState.kt` | Accumulates trip distance, fuel, moving/stopped time, drive mode (city/hwy/idle) across the trip. |
| `AccelEngine.kt` | Pure JVM. Vehicle-frame basis computation + `computeAccelMetrics()`. Zero Android dependencies. |
| `AccelMetrics.kt` | Output of `AccelEngine.computeAccelMetrics()`. Vertical + fwd + lat axes, lean angle. |
| `AccelCalibration.kt` | Tuning parameters for `AccelEngine` (moving average window, peak threshold). |
| `PowerCalculations.kt` | Pure internal functions (`powerAccelKw`, `powerThermoKw`, `powerOBDKw`) extracted for JVM unit testing. No Android dependencies. |

### `metrics/calculator/`
| File | Responsibility |
|------|---------------|
| `FuelCalculator.kt` | Instantaneous and trip fuel efficiency. PID 015E (direct rate) preferred; MAF-based fallback with **diesel boost correction** (boost pressure + RPM + load-aware AFR adjustment for turbocharged diesel engines). |
| `PowerCalculator.kt` | Three power calculation methods: accelerometer-based, thermodynamic (fuel × efficiency), OBD torque-based. |
| `TripCalculator.kt` | Average speed, speed diff (GPS vs OBD cross-check). |

### `metrics/collector/`
| File | Responsibility |
|------|---------------|
| `DataOrchestrator.kt` | Combines `obd2Data` + `gpsData` flows via `combine(...).debounce(100ms)`. Calls `calculator.calculate()` on each emission. Started automatically when `MetricsCalculator` singleton is first created. |

### `obd/`
| File | Responsibility |
|------|---------------|
| `Obd2Service.kt` | Interface: `connectionState`, `obd2Data`, `errorMessage`, `connectedDeviceName`, `connectionLog`, `connect(device?)`, `disconnect()`. `ConnectionState` enum: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `ERROR`. |
| `BluetoothObd2Service.kt` | Real hardware implementation. RFCOMM socket, ELM327 AT init sequence, PID bitmask discovery, **tiered polling loop** (fast + slow), **custom PID polling**, connection health monitoring, `sendCommandForDiscovery()` for PID discovery. Singleton. |
| `MockObd2Service.kt` | Test implementation. Loads baseline from `assets/mock_obd2_data.json` with ±5% jitter. Optionally loads `mock_obd2_enhanced.json` for discovery testing via `MockObd2CommandProcessor`. Provides `sendCommand()`, `setTestScenario()`, `getCurrentHeader()`, `getCurrentHeaderPids()`. |
| `MockObd2CommandProcessor.kt` | Processes OBD commands for the mock emulator: AT commands, ECU header switching (`AT SH`), PID queries with configurable failure rates and error simulation. |
| `MockDiscoveryScenario.kt` | Enum of test scenarios for PID discovery: `JAGUAR_XF`, `TOYOTA_HYBRID`, `MIXED_HEADERS`, `EMPTY_DISCOVERY`, `ERROR_HEAVY`. Also defines `DiscoveredPid` data class. |
| `Obd2ServiceProvider.kt` | Factory object. `useMock` flag selects real vs mock service. `initMock(context)` loads mock assets. Must be set before first `MetricsCalculator.getInstance()`. |
| `ObdStateManager.kt` | **Centralised OBD state singleton.** Single source of truth for mode (`MOCK`/`REAL`), `connectionState`, `autoConnect`, `connectedDeviceName`. Exposes `StateFlow`s. `initialize()` called at app startup; `switchMode()` called from Settings. Keeps `Obd2ServiceProvider.useMock` in sync. |
| `ObdConnectionManager.kt` | **Auto-reconnection manager.** Monitors OBD connection during active trips (RUNNING/PAUSED). Adaptive backoff: 5 attempts at 10 s, then 60 s intervals. Resets on success. `startMonitoring()` / `stopMonitoring()` driven by trip lifecycle. `markManualDisconnect()` suppresses auto-reconnect. |
| `BluetoothConnectionLogger.kt` | Singleton. Writes timestamped BT connection events to `obd_bt_connx.log` in the `.obd` directory. Gated on `AppSettings.isBtLoggingEnabled`. Logs connection attempts, success, failure, disconnection, polling errors, socket health failures, reconnection attempts. |
| `Obd2CommandRegistry.kt` | Defines all supported Mode 01 PIDs (~60 commands) with: `pid` (hex string), `name`, `unit`, `bytesReturned`, and a `parse: (IntArray) -> String` lambda. Formulas per SAE J1979 / ISO 15031-5. |
| `Obd2Command.kt` | Data class: `pid: String`, `name: String`, `unit: String`, `bytesReturned: Int`, `parse: (IntArray) -> String`. |
| `Obd2DataItem.kt` | Data class: `pid`, `name`, `value`, `unit` — one polled reading. |
| `CustomPid.kt` | Data class for user-defined extended PIDs. Fields: `id`, `name`, `header` (ECU target, e.g. "760"), `mode` (e.g. "22"), `pid`, `bytesReturned`, `unit`, `formula` (Torque Pro notation), `signed`, `enabled`. Computed: `commandString`, `responseHeader`, `cacheKey`. |
| `PidFormulaParser.kt` | Recursive-descent arithmetic evaluator for custom PID formulas. Variables A–H map to response bytes 0–7. Operators: `+`, `-`, `*`, `/`, `()`. Thread-safe, stateless. `evaluate(formula, bytes)` → `Double`, `format(value)` → display string. |
| `PidDiscoveryService.kt` | Brute-force PID scanner. Scans read-only modes (21, 22, 23) across common ECU headers (7E0, 7E1, 7E2, 760, 7E4). Exposes `StateFlow<DiscoveryState>`, `discoveryProgress`, `discoveredPids`, `consoleOutput`. Skips known actuator PID ranges for safety. Suggests formula/name/unit for discovered PIDs. |

### `sensors/`
| File | Responsibility |
|------|---------------|
| `AccelerometerSource.kt` | Singleton. Registers `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` sensors. Exposes `isAvailable`, `gravityVector`, `drainBuffer()`. Started/stopped with trip lifecycle. |

### `service/`
| File | Responsibility |
|------|---------------|
| `TripForegroundService.kt` | Foreground service (`START_STICKY`). Shows persistent notification with live trip status/duration/distance. Driven by `combine(tripPhase, metrics)`. Started/stopped by Trip UI. |

### `settings/`
| File | Responsibility |
|------|---------------|
| `AppSettings.kt` | **Singleton with pending-settings pattern.** Loads/saves from JSON in `.obd/` (SAF) or SharedPreferences fallback. `SettingsData` inner class holds all fields. `getPendingSettings()` / `updatePendingSettings()` / `savePendingSettings()` / `discardPendingSettings()` for transactional edits. Keys: `obdConnectionEnabled`, `autoConnect`, `loggingEnabled`, `autoShareLog`, `accelerometerEnabled`, `btLoggingEnabled`, `globalPollingDelayMs`, `globalCommandDelayMs`, `activeProfileId`, `defaultLayoutName`, `lastDeviceMac`, `lastDeviceName`. `log_folder_uri` always in SharedPreferences (bootstrap). |
| `VehicleProfile.kt` | `data class VehicleProfile(id, name, fuelType, tankCapacityL, fuelPricePerLitre, enginePowerBhp, vehicleMassKg, engineDisplacementCc, volumetricEfficiencyPct, availablePids, customPids)`. `FuelType` enum (Petrol, E20, Diesel, CNG) with `mafMlPerGram`, `co2Factor`, `energyDensityMJpL`. `sanitisedName` computed property for filesystem-safe filenames. |
| `VehicleProfileRepository.kt` | CRUD for profiles + PID management. Stores as individual JSON files (`vehicle_profile_<name>.json`) in `.obd/profiles/` via SAF or app-private fallback. Legacy SharedPreferences read for backward compat. `updatePids()` merges new PID values, `getKnownPids()`, `getLastPidValues()`, `hasDiscoveredPids()`. Custom PIDs serialised inside profile JSON. Auto-sets first profile as active. |
| `VehicleProfileEditSheet.kt` | BottomSheet for create/edit profile. |
| `CustomPidEditSheet.kt` | BottomSheet for creating/editing a single `CustomPid`. Fields: name, header, mode, hex, bytes returned, unit, formula, signed. Saves via `VehicleProfileRepository`. |
| `CustomPidListSheet.kt` | BottomSheet listing all custom PIDs for the active profile. Tap to edit (opens `CustomPidEditSheet`), add new, or launch PID discovery (`PidDiscoverySheet`). |

### `storage/`
| File | Responsibility |
|------|---------------|
| `AppDataDirectory.kt` | Manages `.obd` directory structure. Supports two backends: SAF (`DocumentFile`) from user-selected folder, and app-private (`File`) fallback. Sub-methods: `getProfileFileDocumentFile()`, `getLayoutFileDocumentFile()`, `getSettingsFileDocumentFile()`, `listProfileFilesDocumentFile()`, `listLayoutFilesDocumentFile()`, `deleteProfileFile()`, `deleteLayoutFile()`, and matching `*Private()` variants. `isUsingExternalStorage()` checks if SAF tree URI is persisted. |
| `DataMigration.kt` | On startup, checks if external storage has existing `.obd` data (profiles/layouts). Displays Toast confirming data preservation after reinstall. Called from `MainActivity.onCreate()`. |

### `ui/`
| File | Responsibility |
|------|---------------|
| `TopBarHelper.kt` | Extension function `Fragment.attachNavOverflow(anchor)` — popup menu for page navigation. **Disables Settings menu item during active trips** (RUNNING/PAUSED) with Toast feedback. |
| `UIUtils.kt` | `showToast(context, message)` utility. |

### `ui/connect/`
| File | Responsibility |
|------|---------------|
| `ConnectFragment.kt` | Lists paired BT devices (OBD-likely vs other), discovered devices from scan, connection log. |
| `ConnectViewModel.kt` | ViewModel. Splits paired devices into `obdDevices` / `otherDevices` using OBD keyword matching. Tracks `connectingDeviceMac`, `connectedDeviceMac`, `errorDeviceMac` for row tinting. `tryAutoConnect()` auto-connects to last device. `discoveryReceiver` BroadcastReceiver for BT scan. |

### `ui/dashboard/`
| File | Responsibility |
|------|---------------|
| `DashboardFragment.kt` | Displays the active layout. Observes `MetricsCalculator.metrics` and updates all widget views. |
| `DashboardViewModel.kt` | Provides `metrics` flow to `DashboardFragment`. |
| `DashboardEditorFragment.kt` | Free-form widget placement editor. Drag, resize, z-order, alpha, range config. |
| `DashboardEditorViewModel.kt` | Editor state, widget CRUD, save/load layout JSON. |
| `EditWidgetSheet.kt` | Bottom sheet: edit metric, widget type, range, decimals, warning threshold per widget. |
| `WidgetResizeHandler.kt` | Touch handler for widget resize affordance. |
| `WidgetTouchHandler.kt` | Touch handler for widget drag/move. |
| `LayoutListFragment.kt` | Shows saved layouts list. Tap to open, long-press to edit/delete. |
| `GridOverlayView.kt` | Canvas view that draws the grid snap overlay during editing. |
| `MetricListAdapter.kt` | Reusable `RecyclerView.Adapter` for metric selection. Groups by category, shows availability badges (live / previously seen / not seen) based on `VehicleProfileRepository` PID data and live `obd2Data`. |
| `model/DashboardLayout.kt` | `DashboardLayout(name, colorScheme, widgets, orientation)`. `DashboardOrientation` enum: `PORTRAIT`, `LANDSCAPE`. `ColorScheme` presets: `DEFAULT_DARK`, `NEON_RED`, `GREEN_LCD`. |
| `model/DashboardWidget.kt` | `DashboardWidget` — widget instance: type, metric, grid position/size, zOrder, alpha, range, warningThreshold, decimalPlaces, displayUnit. |
| `model/WidgetType.kt` | Enum: `DIAL`, `SEVEN_SEGMENT`, `BAR_GAUGE_H`, `BAR_GAUGE_V`, `NUMERIC_DISPLAY`, `TEMPERATURE_ARC`. Legacy aliases kept for JSON backward compat. |
| `model/DashboardMetric.kt` | **Sealed class** (not enum). Subclasses: `Obd2Pid(pid, name, unit)`, `GpsSpeed`, `GpsAltitude`, `DerivedMetric(key, name, unit)`. |
| `model/MetricDefaults.kt` | Default range min/max, major tick, unit, warning threshold per metric. Auto-populated when a metric is chosen in the editor. |
| `data/LayoutRepository.kt` | Saves/loads `DashboardLayout` as JSON via Gson. `DashboardMetricAdapter` handles sealed-class polymorphism. Dual storage: SAF `.obd/layouts/dashboard_<name>.json` or app-private fallback. `seedDefaultDashboards()` copies from `assets/seed_dashboards/` on first install. `getDefaultLayoutName()` / `setDefaultLayoutName()`. |
| `views/` | Custom `View` implementations for each `WidgetType` (dial canvas, 7-segment, bar, etc.). |
| `wizard/` | New-layout creation wizard fragments. |

### `ui/trip/`
| File | Responsibility |
|------|---------------|
| `TripFragment.kt` | Trip tab UI. Shows status indicators, gravity vector, trip stats. Start/pause/stop buttons. |
| `TripViewModel.kt` | Collects `metrics` + `tripPhase` flows, formats all display strings, computes indicator colours. |

### `ui/settings/`
| File | Responsibility |
|------|---------------|
| `SettingsFragment.kt` | Settings tab. Connection toggles (OBD on/off, auto-connect, BT logging, accelerometer), vehicle profile list with CRUD, data logging folder picker with **folder migration dialog**, debug section (mock scenario selector — only visible when enhanced mock data loaded). Pending-settings pattern: changes are staged and saved via Save button. `restartObdService()` switches mode via `ObdStateManager`. |
| `PidDiscoverySheet.kt` | BottomSheet UI for PID discovery. Header/mode selection, console output (real-time), discovered PID list with multi-select. "Add Selected" saves chosen PIDs as `CustomPid` entries via `VehicleProfileRepository`. Integrates with `PidDiscoveryService`. |
| `ConsoleAdapter.kt` | `ListAdapter` for console log messages. Strips timestamps, colour-codes by content: ERROR (red), NODATA (grey), VALID (green), Scanning/HEADER/Complete/Cancelled (various). |
| `DiscoveredPidAdapter.kt` | `ListAdapter` for `DiscoveredPid` items with checkbox multi-selection, visual selection state (background + checkbox). `getSelectedPids()`, `clearSelections()`. |

### `ui/details/`
Details screen — shows full raw OBD2 PID dump for diagnostics.

---

## 3. Key Data Models

```
VehicleMetrics (immutable, all fields nullable unless noted)
├── Primary OBD2: rpm, vehicleSpeedKmh, engineLoadPct, throttlePct,
│   coolantTempC, intakeTempC, oilTempC, fuelLevelPct, fuelRateLh,
│   mafGs, timingAdvanceDeg, stft/ltft, o2Voltage, torque fields, ...
├── Primary GPS: gpsLatitude, gpsLongitude, gpsSpeedKmh, altitudeMslM,
│   altitudeEllipsoidM, geoidUndulationM, gpsBearingDeg, gpsSatelliteCount
├── Derived Fuel: fuelRateEffectiveLh, instantLper100km, instantKpl,
│   tripFuelUsedL (non-null), tripAvgLper100km, rangeRemainingKm, fuelCostEstimate
├── Derived Trip: tripDistanceKm, tripTimeSec, movingTimeSec, tripAvgSpeedKmh,
│   tripMaxSpeedKmh, spdDiffKmh, pctCity, pctHighway, pctIdle
├── Derived Power: powerAccelKw, powerThermoKw, powerOBDKw
└── Accelerometer: accelVertRms, accelFwdRms, accelLatRms, accelFwdMean,
    accelFwdMaxBrake, accelFwdMaxAccel, accelLeanAngleDeg, accelRawSampleCount

DashboardWidget
├── id: String, type: WidgetType, metric: DashboardMetric
├── gridX, gridY, gridW, gridH, zOrder  (virtual grid, 1 unit = 24dp)
├── alpha: Float (0f–1f)
├── rangeMin, rangeMax, majorTickInterval, minorTickCount
├── warningThreshold: Float?
├── decimalPlaces: Int, displayUnit: String

DashboardLayout
├── name: String
├── colorScheme: ColorScheme  (background, surface, accent, text, warning as ARGB Int)
├── orientation: DashboardOrientation  (PORTRAIT | LANDSCAPE)
└── widgets: List<DashboardWidget>

DashboardMetric  (sealed class)
├── Obd2Pid(pid, name, unit)       — standard Mode 01 PID
├── GpsSpeed                        — GPS fused speed
├── GpsAltitude                     — GPS geoid-corrected altitude
└── DerivedMetric(key, name, unit)  — computed metric from MetricsCalculator

CustomPid  (stored inside VehicleProfile.customPids)
├── id, name, header, mode, pid, bytesReturned, unit, formula, signed, enabled
├── commandString  (computed: mode + pid hex)
├── responseHeader (computed: (mode+0x40) + pid)
└── cacheKey       (computed: header:mode:pid)

DiscoveredPid  (transient, from PidDiscoveryService)
├── header, mode, pid, response, byteCount
└── suggestedName, suggestedUnit, suggestedFormula

VehicleProfile
├── id, name, fuelType, tankCapacityL, fuelPricePerLitre, enginePowerBhp
├── vehicleMassKg, engineDisplacementCc, volumetricEfficiencyPct
├── availablePids: Map<String, String>   — PID name → last known value
├── customPids: List<CustomPid>
└── sanitisedName  (computed, filesystem-safe)
```

---

## 4. Navigation

```
MainActivity
└── ViewPager2 (MainPagerAdapter — swipe tabs)
    ├── Tab 0: ConnectFragment          (BT device list + connection)
    ├── Tab 1: TripFragment             (trip computer)
    ├── Tab 2: DashboardsHostFragment   → LayoutListFragment | DashboardFragment
    │                                   → DashboardEditorFragment (edit mode)
    ├── Tab 3: DetailsFragment          (raw OBD2 PID dump)
    └── Tab 4: SettingsFragment         (app settings + vehicle profiles)
                ├── VehicleProfileEditSheet  (create/edit profile)
                ├── CustomPidListSheet → CustomPidEditSheet
                └── PidDiscoverySheet       (scan for PIDs)
```

- `DashboardsHostFragment` uses `childFragmentManager` replace transactions to switch between layout list and live dashboard.
- `DashboardEditorFragment` launched from `LayoutListFragment` via fragment transaction.
- **Settings access blocked during active trips** — both swipe navigation and overflow menu are guarded. ViewPager swipe is also disabled during dashboard edit mode.
- `TopBarHelper` overflow menu available on all pages for quick navigation.

---

## 5. Data Pipeline Flow (step by step)

```
1. MainActivity.onCreate()
   a. DataMigration.checkExistingData(ctx)  — Toast if .obd data found
   b. ObdStateManager.initialize(autoConnect, obdEnabled)  — sets mode MOCK/REAL
   c. If mock mode: Obd2ServiceProvider.initMock(ctx)
   d. ViewPager2 + MainPagerAdapter created (5 tabs)
   e. Start GpsDataSource.getInstance(ctx).start()
   f. MetricsCalculator.getInstance(ctx)   ← creates singleton + calls startCollecting()

2. MetricsCalculator.startCollecting()
   → creates DataOrchestrator(context, scope, this)
   → DataOrchestrator.startCollecting()

3. DataOrchestrator.startCollecting()
   → combine(obdService.obd2Data, gpsSource.gpsData) { obdItems, gps → ... }
   → .flowOn(Dispatchers.Default)
   → .debounce(100ms)
   → collect { (obdItems, gps) →
       val snapshot = calculator.calculate(obdItems, gps)
       calculator.updateMetrics(snapshot)      ← pushes to _metrics StateFlow
       if (loggingEnabled) calculator.logMetrics(snapshot)
     }

4. MetricsCalculator.calculate(obdItems, gps)
   a. Parse all OBD2 PID values from obdItems list
   b. Extract GPS fields from gps: GpsDataItem?
   c. Effective speed = gpsSpeed ?: obdSpeed ?: 0f
   d. FuelCalculator.effectiveFuelRate(pid015E, maf, fuelFactor, mapKpa, iatC, rpm, displacement, vePct, fuelType, baroKpa, engineLoadPct)
      → For DIESEL: applies boost-aware AFR correction = f(boost, RPM, load)
      → For non-diesel: correction = 1.0 (no change)
   e. IF waitingForGravityCapture AND AccelerometerSource.gravityVector != null:
        capturedGravityVector = gravityVector.copyOf()
        vehicleBasis = accelEngine.computeVehicleBasis(gravityVector)
        waitingForGravityCapture = false
   f. IF !isTripPaused: tripState.update(speed, fuelRate)
   g. FuelCalculator → instantaneous + trip averages + range + cost + CO2
   h. TripCalculator → avgSpeed, speedDiff
   i. TripState.driveModePercents() → city/hwy/idle %
   j. IF isAccelerometerEnabled:
        basis = vehicleBasis ?: gravityVector?.let { computeVehicleBasis(it) }
        buffer = AccelerometerSource.drainBuffer()
        accelMetrics = if (buffer.isNotEmpty()) accelEngine.computeAccelMetrics(buffer, basis) else null
      ELSE: accelMetrics = null
   k. PowerCalculator → powerAccelKw (needs vehicleMassKg + accelFwdMean), powerThermoKw, powerOBDKw
   l. Return VehicleMetrics(all fields above)

5. UI fragments observe:
   MetricsCalculator.metrics.collect { vm -> update views }
   MetricsCalculator.tripPhase.collect { phase -> update controls }
```

---

## 6. Fuel Calculation — Diesel Boost Correction

### Overview
Turbocharged diesel engines operate at variable air-fuel ratios (AFR) ranging from ~35:1 (vacuum/light load) to ~15:1 (full boost/heavy load), unlike petrol engines which maintain near-stoichiometric AFR. The standard MAF-based fuel calculation assumes a fixed stoichiometric AFR of 14.5:1 for diesel, leading to **50-70% overestimation** of fuel consumption under boost conditions.

The diesel boost correction addresses this by dynamically adjusting the AFR assumption based on three factors:
1. **Boost pressure** (MAP - Baro) — primary air density measurement
2. **RPM** — turbo spool efficiency modifier
3. **Engine load** — driver demand and fuel injection context

### Implementation

**Helper functions in `FuelCalculator.kt`:**

```kotlin
fun calculateBoostPressure(mapKpa: Float, baroKpa: Float): Float
    → Returns boost pressure in kPa (positive = boost, negative = vacuum)

fun calculateDieselAfrCorrection(
    boostKpa: Float,
    rpm: Float, 
    engineLoadPct: Float,
    fuelType: FuelType
): Double
    → Returns AFR correction factor (0.35–1.0)
    → Returns 1.0 for non-diesel fuels (no correction)
```

**Correction formula:**
```
afrCorrection = boostCorrection × rpmModifier × loadModifier

Where:
  boostCorrection = {
    0.40  if boost < 0 kPa      (vacuum, very lean ~35:1)
    0.45  if boost < 5 kPa      (minimal boost, lean ~30:1)
    0.55  if boost < 15 kPa     (light boost, ~25:1)
    0.70  if boost < 30 kPa     (medium boost, ~20:1)
    0.85  if boost < 50 kPa     (heavy boost, ~17:1)
    0.95  if boost ≥ 50 kPa     (maximum boost, ~15:1)
  }

  rpmModifier = {
    0.90  if rpm < 1000         (turbo lag zone)
    0.95  if rpm < 1500         (below optimal)
    1.00  if rpm < 2500         (optimal turbo efficiency)
    1.02  if rpm < 3500         (high efficiency)
    1.05  if rpm ≥ 3500         (maximum efficiency)
  }

  loadModifier = {
    0.95  if load < 20%         (very light load, leaner)
    1.00  if load < 60%         (normal load)
    1.05  if load ≥ 60%         (heavy load, richer)
  }

  Final correction clamped to [0.35, 1.0]
```

**Applied in fuel rate calculation:**
```kotlin
effectiveFuelRate(..., fuelType, baroKpa, engineLoadPct)
    → If DIESEL and all parameters available:
        fuelRate = maf × mafMlPerGram × afrCorrection × 3600 / 1000
    → Else:
        fuelRate = maf × mafMlPerGram × 3600 / 1000  (standard)
```

### Validation Results

Based on real-world log data from turbocharged diesel vehicle (Maruti Suzuki Brezza):

| Scenario | RPM | Load% | Boost kPa | Before | After | Improvement |
|----------|-----|-------|-----------|--------|-------|-------------|
| Heavy boost | 1453 | 64.3 | +48 | 10.9 kmpl | 12.8 kmpl | +17% |
| Light load | 1007 | 27.1 | -1 (vacuum) | 8.9 kmpl | 25.6 kmpl | +187% |
| Medium boost | 1625 | 50.6 | +1 | 6.6 kmpl | 17.3 kmpl | +162% |

**Overall accuracy improvement: ~50-70%** — readings now match vehicle dashboard within 10-15%.

### Backward Compatibility

- **Petrol/E20/CNG vehicles:** Correction factor = 1.0 (no change)
- **Diesel without required parameters:** Falls back to standard calculation
- **Direct fuel rate PID (015E):** Bypasses correction (already accurate)
- **Default fuel type:** Changed to E20 when no profile is found

### Required OBD-II PIDs

- `010B` — Intake Manifold Absolute Pressure (MAP)
- `0133` — Barometric Pressure
- `010C` — Engine RPM
- `0104` — Calculated Engine Load
- `0110` — Mass Air Flow (MAF) sensor

All PIDs are standard Mode 01 and widely supported on diesel vehicles.

---

## 7. OBD2 Layer

### BluetoothObd2Service (real hardware)
1. `connect(device)` → creates RFCOMM socket via SPP UUID `00001101-...`
2. Sends ELM327 init sequence: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP0`
   - `ATAT1` = adaptive timing — ELM learns ECU latency, tightens timeout
3. Queries `0100`, `0120`, `0140`, `0160` → parses 32-bit bitmasks → `supportedPids: Set<Int>`
4. Locks auto-detected protocol: `ATDPN` → `ATSP<N>` to skip renegotiation per command
5. **Tiered polling loop** (no explicit inter-PID delay — ATAT1 handles pacing):
   - **Fast tier** (every cycle): RPM `010C`, Speed `010D`, MAF `0110`, Throttle `0111`, Fuel Rate `015E`
   - **Slow tier** (every `SLOW_TIER_MODULO=5` cycles): all other supported PIDs
   - **Custom PIDs** (same cadence as slow tier): loaded from active `VehicleProfile.customPids`. Grouped by header to minimise `AT SH` switches. Default header `7DF` restored after custom polling.
   - **Warm-up phase**: UI emission delayed until first slow-tier cycle completes for stable, complete PID list
   - Results cached in `mutableMapOf<String, Obd2DataItem>` — slow-tier values persist between cycles
6. **Connection health monitoring**:
   - Socket health check every 10 cycles (~2 s) via `isSocketHealthy()`
   - Consecutive failure counter: after `MAX_CONSECUTIVE_FAILURES=10` (~2-3 s) with zero successful reads → state → ERROR
   - `BluetoothConnectionLogger` logs all connection events (when BT logging enabled)
7. `disconnect()` cancels polling coroutine, closes socket, resets failure counter
8. `sendCommandForDiscovery(command)` — public suspend method for `PidDiscoveryService` to use the live BT connection

### Custom PID Response Parsing
- Extended responses use format: `(mode+0x40) + pid + data_bytes`
  - e.g. Mode 22, PID 0456 → response header `"620456"`
- `PidFormulaParser.evaluate(formula, bytes, signed)` converts raw data using Torque Pro notation (variables A-H)
- Result emitted as `Obd2DataItem` with `cacheKey` as PID identifier

### ObdConnectionManager (auto-reconnection)
- Monitors connection during active trips (RUNNING/PAUSED phases)
- On connection loss (ERROR state): adaptive backoff reconnection
  - First 5 attempts: 10 s interval
  - After 5 attempts: 60 s interval
  - Resets attempt counter on successful reconnect
- `markManualDisconnect()` suppresses auto-reconnect
- Logs all reconnection attempts/successes via `BluetoothConnectionLogger`

### ObdStateManager (centralised state)
- Single source of truth: `Mode` (MOCK/REAL), `ConnectionState`, `connectedDeviceName`, `autoConnect`
- `initialize()` at app startup from `AppSettings`
- `switchMode()` from Settings — updates `Obd2ServiceProvider.useMock` atomically
- All components observe `stateFlow` / `modeFlow` instead of directly checking `Obd2ServiceProvider`

### MockObd2Service (dev/testing)
- Reads `assets/mock_obd2_data.json` on init → baseline values with ±5% jitter at 1 Hz
- Optionally loads `assets/mock_obd2_enhanced.json` → `MockObd2CommandProcessor` for PID discovery testing
- `MockObd2CommandProcessor`: simulates AT commands, header switching, PID queries with configurable error rates
- `setTestScenario(MockDiscoveryScenario)` — switches between vehicle scenarios (Jaguar XF, Toyota Hybrid, Mixed Headers, Empty Discovery, Error Heavy)
- Debug settings in `SettingsFragment` (only visible when enhanced mock data loaded): scenario selector dialog

### PID Discovery
- `PidDiscoveryService` — brute-force scanner for custom/extended PIDs
- Scans read-only modes `21`, `22`, `23` across common ECU headers: `7E0`, `7E1`, `7E2`, `760`, `7E4`
- Skips known actuator PID ranges for safety
- Suggests formula, name, unit for discovered PIDs based on response patterns
- UI: `PidDiscoverySheet` → header/mode selection → console output → discovered PID list → multi-select → "Add Selected" saves as `CustomPid`

### Obd2CommandRegistry
- Defines ~60 Mode 01 PIDs with: `pid` (hex string), `name`, `unit`, `bytesReturned`, and a `parse: (IntArray) -> String` lambda
- Categories: Engine, Fuel, Air/Intake, O2 Sensors, Distance/Status, Catalyst Temperatures, Voltage/Load/Throttle, Torque
- Bitmask discovery filters this registry to only poll ECU-supported PIDs

---

## 7. MetricsCalculator — Trip Phase State Machine

```
IDLE ──[startTrip()]──► RUNNING ──[pauseTrip()]──► PAUSED
  ▲                         │                          │
  └────[stopTrip()]──────────┴──────[resumeTrip()]─────┘
```

**`startTrip()`:**
- Resets `TripState`, timers, pause accumulators
- Sets `_tripPhase = RUNNING`
- If `isAccelerometerEnabled && accelSource.isAvailable`: calls `accelSource.start()`, sets `waitingForGravityCapture = true`
- If `isLoggingEnabled`: opens `MetricsLogger` file

**`stopTrip()`:**
- Resets all trip state
- Sets `_tripPhase = IDLE`
- Calls `accelSource.stop()` (unconditional — safe no-op if not started)
- Calls `logger.close()`

**`pauseTrip()` / `resumeTrip()`:**
- Track `pauseStartMs` to accumulate `pausedAccumMs`
- `tripState.update()` is skipped while `isTripPaused = true`

---

## 8. GPS Layer

`GpsDataSource` singleton:
- `FusedLocationProviderClient` with `PRIORITY_HIGH_ACCURACY`, 1 s interval, 500 ms min
- `GnssStatus.Callback` (API 24+) tracks `usedInFix` satellite count
- Geoid correction: `GeoidCorrection.getUndulation(lat, lon)` → `mslAltitude = ellipsoidAltitude - undulation`
- Started in `MainActivity.onCreate()`, never stopped (runs for app lifetime)

---

## 9. Accelerometer Layer

```
AccelerometerSource (singleton)
├── linearAccelSensor = TYPE_LINEAR_ACCELERATION (null if not present)
├── gravitySensor     = TYPE_GRAVITY             (null if not present)
├── isAvailable       = linearAccelSensor != null
├── start()           → registers both sensors (?.let guard — safe if null)
├── stop()            → unregisterListener (no-op if not registered)
├── drainBuffer()     → atomically swaps buffer, returns drained list
└── gravityVector     → latest GRAVITY event values (FloatArray?)

AccelEngine (pure JVM, no Android deps)
├── computeVehicleBasis(gravity): VehicleBasis?
│   → ĝ (vertical), fwd (device-Y ⊥ ĝ), lat (ĝ × fwd)
│   → null if gravity too small or degenerate
└── computeAccelMetrics(buffer, basis?): AccelMetrics?
    → null if buffer empty
    → detrend → moving average → project onto vehicle frame
    → vertical RMS, stdDev, peakRatio
    → fwd/lat RMS, max, mean, signed
    → lean angle = atan2(latComp, vertComp)
```

Gravity is captured lazily: `waitingForGravityCapture` flag in `MetricsCalculator` is set at trip start. The first non-null `gravityVector` from `AccelerometerSource` during a `calculate()` call locks in `vehicleBasis`.

---

## 10. Dashboard Editor

**Widget model (`DashboardWidget`):**
- Position: `gridX`, `gridY` in virtual grid units (1 unit = 24 dp)
- Size: `gridW`, `gridH`
- Rendering order: `zOrder` (higher = drawn on top)
- Transparency: `alpha` (0f invisible → 1f opaque)
- Range: `rangeMin`, `rangeMax`, `majorTickInterval`, `minorTickCount`
- `warningThreshold: Float?` — arc/zone turns warning colour above this value
- `decimalPlaces`, `displayUnit`

**Widget types:**
| Type | Description |
|------|-------------|
| `DIAL` | Circular dial gauge with needle |
| `SEVEN_SEGMENT` | Digital 7-segment numeric display |
| `BAR_GAUGE_H` | Horizontal filled bar |
| `BAR_GAUGE_V` | Vertical filled bar |
| `NUMERIC_DISPLAY` | Large plain numeric readout |
| `TEMPERATURE_ARC` | 180° arc sweep (good for temp metrics) |

Legacy aliases `REV_COUNTER`, `SPEEDOMETER_7SEG`, `FUEL_BAR`, `IFC_BAR` are kept as deprecated enum values with `canonical()` for JSON backward compatibility.

**Color schemes:** `DEFAULT_DARK`, `NEON_RED`, `GREEN_LCD` — applied globally across all widgets on a layout.

**Persistence:** Layouts serialised to JSON via `LayoutRepository` (Gson + `DashboardMetricAdapter`). Stored in `.obd/layouts/` (SAF) or app-private fallback. `seedDefaultDashboards()` copies from `assets/seed_dashboards/` on first install. `DashboardEditorViewModel` handles save/load. `MetricDefaults` auto-populates range/unit/threshold when user picks a metric.

---

## 11. Trip Foreground Service

`TripForegroundService`:
- `START_STICKY` — restarted by OS if killed
- Started/stopped by `TripFragment` via `TripForegroundService.start(context)` / `stop(context)`
- Observes `combine(calculator.tripPhase, calculator.metrics)` and updates notification
- Notification content: `"$status • $duration • $distance"` (e.g. `"Trip in progress • 12:34 • 7.3 km"`)
- Notification channel `"trip_tracking"`, `IMPORTANCE_LOW`, no sound, no vibration, not dismissable

---

## 12. Settings & Storage

### AppSettings (singleton, pending-settings pattern)

**Storage backend:** If SAF external storage is available (`.obd` directory exists), settings are persisted as `settings.json` in the `.obd` directory. Otherwise, falls back to `SharedPreferences ("obd2_prefs")`. Exception: `log_folder_uri` always stays in SharedPreferences (bootstrap requirement — needed before `.obd` dir is known).

**Pending-settings workflow:** UI changes are staged via `updatePendingSettings()`. Only committed to disk when user taps Save → `savePendingSettings()`. `discardPendingSettings()` reverts. `hasPendingChanges()` drives Save button visibility.

**Settings fields (`SettingsData`):**
| Field | Default | Description |
|-------|---------|-------------|
| `obdConnectionEnabled` | true | false → mock/simulate mode |
| `autoConnect` | true | Auto-connect to last device |
| `loggingEnabled` | false | JSON trip log |
| `autoShareLog` | false | Share log after trip |
| `accelerometerEnabled` | false | Opt-in accelerometer recording |
| `btLoggingEnabled` | false | BT connection event logging to `obd_bt_connx.log` |
| `globalPollingDelayMs` | 500 ms | OBD2 poll interval |
| `globalCommandDelayMs` | 50 ms | Delay between AT commands |
| `activeProfileId` | null | Active vehicle profile UUID |
| `defaultLayoutName` | null | Default dashboard layout name |
| `lastDeviceMac` | null | Last connected BT device MAC |
| `lastDeviceName` | null | Last connected BT device name |

**`log_folder_uri`** (SharedPreferences only): SAF URI for the user-selected tracks/data folder.

### Storage Architecture

```
<user-selected-folder>/
└── .obd/
    ├── profiles/
    │   ├── vehicle_profile_My_Vehicle.json
    │   └── vehicle_profile_Jaguar_XF.json
    ├── layouts/
    │   ├── dashboard_Default.json
    │   └── dashboard_Night_Mode.json
    ├── settings.json
    └── obd_bt_connx.log
```

- **SAF backend** (`DocumentFile`): user picks folder via `OpenDocumentTree`. Persisted URI taken with `takePersistableUriPermission`.
- **Private fallback** (`File`): `context.filesDir` used when no SAF URI is set.
- **Folder migration**: `SettingsFragment` offers to copy `.obd` contents when user changes folder (copies without deleting old data).
- **Data migration check**: `DataMigration.checkExistingData()` on startup — Toast confirms existing profiles/layouts are preserved after reinstall.
- **⚠ Android 10+ truncation bug**: All `ContentResolver.openOutputStream` calls use `"wt"` mode (write + truncate). Plain `"w"` does NOT truncate, leaving stale trailing bytes that corrupt JSON.

### SettingsFragment behaviour
- Disables accelerometer switch with label `"Log accelerometer data (no sensor)"` when `AccelerometerSource.isAvailable == false`
- **OBD mode toggle**: changing `obdConnectionEnabled` triggers `restartObdService()` → disconnects current service, calls `ObdStateManager.switchMode()`, reinitialises mock if needed
- **Debug section** (mock only): scenario selector dialog for PID discovery testing, only visible when `MockObd2Service.isEnhancedModeAvailable()`

---

## 13. Mock Mode

- **Compile-time toggle**: `MainActivity.USE_MOCK_OBD2` constant (default `false`) — forces mock + auto-connect + jumps to Dashboards tab
- **Runtime toggle**: `AppSettings.obdConnectionEnabled == false` → `ObdStateManager` sets mode to MOCK
- `ObdStateManager.initialize()` in `MainActivity.onCreate()` sets mode before ViewPager/fragments are created
- `Obd2ServiceProvider.initMock(ctx)` loads mock assets; `MockObd2Service.init(context)` reads baseline JSON
- **Basic mode**: `assets/mock_obd2_data.json` → baseline values with ±5% random jitter at 1 Hz
- **Enhanced mode** (if `assets/mock_obd2_enhanced.json` exists): enables `MockObd2CommandProcessor` for PID discovery testing
  - AT command simulation (echo off, header switching, etc.)
  - Configurable error rates and failure simulation
  - Test scenarios: `MockDiscoveryScenario` enum (Jaguar XF, Toyota Hybrid, Mixed Headers, Empty Discovery, Error Heavy)
  - Scenario selector available in Settings debug section
- Mock mode respects `autoConnect` setting — if enabled, auto-connects on startup and navigates to Trip tab

---

## 14. Constraints & Gotchas

- **`ObdStateManager.initialize()` must be called before ViewPager creation.** It sets `Obd2ServiceProvider.useMock` which `DataOrchestrator` captures at construction time. Calling after singleton creation has no effect on the active service.
- **`DataOrchestrator.debounce(100 ms)`** means the maximum effective UI refresh rate is ~10 Hz. If OBD2 and GPS emit simultaneously within 100 ms, only one `calculate()` call fires.
- **`accelerometer_enabled` defaults to `false`.** Unlike SJGpsUtil (which defaults to `true`), accel recording is opt-in here.
- **`MetricsCalculator` is created and `startCollecting()` is called lazily on first `getInstance()`.** Any fragment that calls `getInstance()` before `ObdStateManager.initialize()` will lock in the wrong service.
- **Tiered polling has no explicit inter-PID delay** — `ATAT1` adaptive timing handles ECU pacing. Adding `Thread.sleep()` between PIDs will degrade throughput.
- **Custom PIDs group by header** to minimise `AT SH` switches. After polling custom PIDs, the default header `7DF` is always restored. Failing to restore the header will break standard Mode 01 polling.
- **PID discovery scans read-only modes only** (21, 22, 23). Modes 01–0A are standard and already handled. Actuator/control PIDs are skipped for safety.
- **⚠ Android 10+ `openOutputStream` truncation**: Always use mode `"wt"` (write + truncate). Mode `"w"` writes from the beginning but does NOT truncate, leaving stale trailing bytes if new content is shorter — this corrupts JSON files. This applies to all 4 write locations: `VehicleProfileRepository`, `LayoutRepository`, `AppSettings`, `SettingsFragment` (migration copy).
- **Settings access is blocked during active trips** via both ViewPager swipe guard and `TopBarHelper` overflow menu. `TripPhase != IDLE` → Settings navigation is rejected with Toast.
- **ViewPager swipe is disabled during dashboard edit mode** via `MetricsCalculator.dashboardEditMode` StateFlow observed in `MainActivity`.
- **Legacy `WidgetType` aliases** (`REV_COUNTER`, `SPEEDOMETER_7SEG`, `FUEL_BAR`, `IFC_BAR`) are deprecated enum values. Always call `.canonical()` before rendering to get the current equivalent type. Layouts saved with old names load correctly via `canonical()`.
- **`TripForegroundService` is `START_STICKY`** — Android will restart it if killed. The service re-attaches to the existing `MetricsCalculator` singleton on restart, so trip state is not lost if the service process is killed and recreated.
- **`DashboardMetric` is a sealed class, not an enum.** Gson serialisation requires the custom `DashboardMetricAdapter` to handle polymorphism correctly. Adding new subclasses requires updating the adapter's `serialize`/`deserialize` methods.
- **First vehicle profile is auto-set as active.** `VehicleProfileRepository.save()` checks if no profiles exist before saving and auto-assigns `activeProfileId`. Deleting the active profile falls back to the first remaining profile.
- **`BluetoothConnectionLogger` writes to `.obd/obd_bt_connx.log`** using `"wa"` (write-append) mode. This file grows indefinitely — no rotation is implemented.
- **Geoid correction** (`GeoidCorrection`) uses a static lookup table, not a live service. Accuracy is suitable for display purposes but not survey-grade.
- **`GpsDataSource` is started in `MainActivity.onCreate()` and never stopped** — GPS runs for the full app lifetime. This is intentional: GPS warm-up time means stopping on navigation is not worthwhile.
- **Power calculation via accelerometer** (`powerAccelKw`) requires `vehicleMassKg > 0` from the active vehicle profile. If no profile is set or mass is 0, `powerAccelKw` will be `null`.
