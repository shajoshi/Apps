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
| Settings persistence | `SharedPreferences` (`obd2_prefs`) |
| Async | Kotlin Coroutines + `StateFlow` |
| Background | `TripForegroundService` — Android Foreground Service (`START_STICKY`) |
| Mock/dev mode | `MockObd2Service` reads `assets/mock_obd2_data.json` |

---

## 2. Module / File Map

### Root (`com.sj.obd2app`)
| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Single activity. Initialises `Obd2ServiceProvider`, requests BT/location permissions, owns `ViewPager2` with `MainPagerAdapter`. |
| `MainPagerAdapter.kt` | ViewPager2 adapter — tabs: Connect, Dashboard, Trip, Settings. |
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
| `MetricsCalculator.kt` | **Central singleton.** Owns `DataOrchestrator`, trip phase state machine, all sub-calculators. `calculate()` returns `VehicleMetrics`. Exposes `StateFlow<VehicleMetrics>` and `StateFlow<TripPhase>`. |
| `VehicleMetrics.kt` | Immutable snapshot of all metrics for one calculation cycle. ~50 nullable fields. |
| `MetricsLogger.kt` | JSON trip log writer. One JSON file per trip. Header object + one sample object per line. Gated on `AppSettings.isLoggingEnabled`. |
| `TripPhase.kt` | Enum: `IDLE` / `RUNNING` / `PAUSED`. |
| `TripState.kt` | Accumulates trip distance, fuel, moving/stopped time, drive mode (city/hwy/idle) across the trip. |
| `AccelEngine.kt` | Pure JVM. Vehicle-frame basis computation + `computeAccelMetrics()`. Zero Android dependencies. |
| `AccelMetrics.kt` | Output of `AccelEngine.computeAccelMetrics()`. Vertical + fwd + lat axes, lean angle. |
| `AccelCalibration.kt` | Tuning parameters for `AccelEngine` (moving average window, peak threshold). |
| `PidAvailabilityStore.kt` | Parses 0100/0120/0140/0160 bitmask responses to build the set of ECU-supported PIDs. |

### `metrics/calculator/`
| File | Responsibility |
|------|---------------|
| `FuelCalculator.kt` | Instantaneous and trip fuel efficiency. PID 015E (direct rate) preferred; MAF-based fallback. |
| `PowerCalculator.kt` | Three power calculation methods: accelerometer-based, thermodynamic (fuel × efficiency), OBD torque-based. |
| `TripCalculator.kt` | Average speed, speed diff (GPS vs OBD cross-check). |

### `metrics/collector/`
| File | Responsibility |
|------|---------------|
| `DataOrchestrator.kt` | Combines `obd2Data` + `gpsData` flows via `combine(...).debounce(100ms)`. Calls `calculator.calculate()` on each emission. Started automatically when `MetricsCalculator` singleton is first created. |

### `obd/`
| File | Responsibility |
|------|---------------|
| `Obd2Service.kt` | Interface: `connectionState`, `obd2Data`, `errorMessage`, `connectedDeviceName`, `connectionLog`, `connect(device?)`, `disconnect()`. |
| `BluetoothObd2Service.kt` | Real hardware implementation. RFCOMM socket, ELM327 AT init sequence, PID bitmask discovery, continuous polling loop. Singleton. |
| `MockObd2Service.kt` | Test implementation. Loops through `assets/mock_obd2_data.json`. Singleton. |
| `Obd2ServiceProvider.kt` | Factory object. `useMock` flag selects real vs mock service. Must be set before first `MetricsCalculator.getInstance()`. |
| `Obd2CommandRegistry.kt` | Defines all OBD-II commands (PID, name, unit, parse function). Source of truth for what the app can read. |
| `Obd2Command.kt` | Data class: `pid: String`, `name: String`, `unit: String`, `parse: (String) -> String?`. |
| `Obd2DataItem.kt` | Data class: `pid`, `name`, `value`, `unit` — one polled reading. |

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
| `AppSettings.kt` | `SharedPreferences("obd2_prefs")` singleton. Keys: polling delay, command delay, BT enable, auto-connect, logging, auto-share log, accelerometer enabled, active profile ID, log folder URI. |
| `VehicleProfile.kt` | Data class: name, fuel type, tank capacity, fuel price, engine power, vehicle mass, OBD polling/command delays. |
| `VehicleProfileRepository.kt` | In-memory + JSON-backed profile store. Serialised as SharedPreferences or internal storage. `setActive(id)` sets active profile. |
| `VehicleProfileEditSheet.kt` | Bottom sheet fragment for creating/editing vehicle profiles. |

### `ui/connect/`
Connect fragment — lists paired BT devices, shows connection log, triggers `BluetoothObd2Service.connect(device)`.

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
| `model/DashboardLayout.kt` | `DashboardLayout(name, colorScheme, widgets)` — top-level saveable dashboard state. |
| `model/DashboardWidget.kt` | `DashboardWidget` — widget instance: type, metric, grid position/size, zOrder, alpha, range, warningThreshold, decimalPlaces, displayUnit. |
| `model/WidgetType.kt` | Enum: `DIAL`, `SEVEN_SEGMENT`, `BAR_GAUGE_H`, `BAR_GAUGE_V`, `NUMERIC_DISPLAY`, `TEMPERATURE_ARC`. Legacy aliases kept for JSON backward compat. |
| `model/DashboardMetric.kt` | Enum of all bindable metrics (rpm, speed, coolant, fuel, power, accel, etc.). |
| `model/MetricDefaults.kt` | Default range min/max, major tick, unit, warning threshold per metric. Auto-populated when a metric is chosen in the editor. |
| `data/` | Layout persistence (JSON serialisation/deserialisation to internal storage). |
| `views/` | Custom `View` implementations for each `WidgetType` (dial canvas, 7-segment, bar, etc.). |
| `wizard/` | New-layout creation wizard fragments. |

### `ui/trip/`
| File | Responsibility |
|------|---------------|
| `TripFragment.kt` | Trip tab UI. Shows status indicators, gravity vector, trip stats. Start/pause/stop buttons. |
| `TripViewModel.kt` | Collects `metrics` + `tripPhase` flows, formats all display strings, computes indicator colours. |

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
└── widgets: List<DashboardWidget>
```

---

## 4. Navigation

```
MainActivity
└── ViewPager2 (MainPagerAdapter — swipe tabs)
    ├── Tab 0: ConnectFragment        (BT device list + connection)
    ├── Tab 1: DashboardsHostFragment  → LayoutListFragment | DashboardFragment
    │                                  → DashboardEditorFragment (edit mode)
    ├── Tab 2: TripFragment            (trip computer)
    └── Tab 3: SettingsFragment        (app settings + vehicle profiles)
```

- `DashboardsHostFragment` uses `childFragmentManager` replace transactions to switch between layout list and live dashboard.
- `DashboardEditorFragment` launched from `LayoutListFragment` via fragment transaction.

---

## 5. Data Pipeline Flow (step by step)

```
1. MainActivity.onCreate()
   a. Set Obd2ServiceProvider.useMock (from USE_MOCK_OBD2 constant or settings)
   b. Start GpsDataSource.getInstance(ctx).start()
   c. MetricsCalculator.getInstance(ctx)   ← creates singleton + calls startCollecting()

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
   d. FuelCalculator.effectiveFuelRate(pid015E, maf, fuelFactor)
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

## 6. OBD2 Layer

### BluetoothObd2Service (real hardware)
1. `connect(device)` → creates RFCOMM socket via SPP UUID `00001101-...`
2. Sends ELM327 init sequence: `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATSP0`
3. Queries `0100`, `0120`, `0140`, `0160` → parses 32-bit bitmasks → `supportedPids: Set<Int>`
4. Polling loop: for each command in `Obd2CommandRegistry.commands` where PID is in `supportedPids`:
   - Send `<PID>\r`, read response, call `cmd.parse(response)`
   - Accumulate `List<Obd2DataItem>` → emit to `_obd2Data` StateFlow
5. Polling delay = `AppSettings.effectivePollingDelayMs()` (profile override or global setting)
6. `disconnect()` cancels polling coroutine, closes socket

### MockObd2Service (dev/testing)
- Reads `assets/mock_obd2_data.json` on init
- Loops through entries at the configured polling delay
- Emits same `StateFlow<List<Obd2DataItem>>` interface

### Obd2CommandRegistry
- Defines every supported PID with: `pid` (hex string), `name`, `unit`, and a `parse` lambda
- `PidAvailabilityStore` cross-references this registry when filtering the polling set

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

**Persistence:** Layouts serialised to JSON, stored in internal app storage. `DashboardEditorViewModel` handles save/load. `MetricDefaults` auto-populates range/unit/threshold when user picks a metric.

---

## 11. Trip Foreground Service

`TripForegroundService`:
- `START_STICKY` — restarted by OS if killed
- Started/stopped by `TripFragment` via `TripForegroundService.start(context)` / `stop(context)`
- Observes `combine(calculator.tripPhase, calculator.metrics)` and updates notification
- Notification content: `"$status • $duration • $distance"` (e.g. `"Trip in progress • 12:34 • 7.3 km"`)
- Notification channel `"trip_tracking"`, `IMPORTANCE_LOW`, no sound, no vibration, not dismissable

---

## 12. Settings

**`AppSettings` keys (SharedPreferences `"obd2_prefs"`):**
| Key | Default | Description |
|-----|---------|-------------|
| `global_polling_delay_ms` | 500 ms | OBD2 poll interval |
| `global_command_delay_ms` | 50 ms | Delay between individual AT commands |
| `obd_connection_enabled` | true | false → mock mode |
| `auto_connect_last_device` | true | Auto-connect on Connect screen open |
| `logging_enabled` | false | JSON trip log |
| `auto_share_log` | false | Share latest log after trip |
| `accelerometer_enabled` | **false** | Opt-in accelerometer recording |
| `active_profile_id` | null | Active vehicle profile |
| `log_folder_uri` | null | SAF URI for log output folder |

**SettingsFragment** disables the accelerometer switch and updates its label to `"Log accelerometer data (no sensor)"` when `AccelerometerSource.isAvailable == false`.

---

## 13. Mock Mode

- Toggle: `MainActivity.USE_MOCK_OBD2` constant (compile-time, default `false`)
- Also active when `AppSettings.isObdConnectionEnabled == false`
- `Obd2ServiceProvider.useMock = true` must be set **before** any fragment accesses `MetricsCalculator`; it is set in `MainActivity.onCreate()` before `setContentView`
- `MockObd2Service.init(context)` called once; reads `assets/mock_obd2_data.json`
- Loops entries at polling delay, emitting real-looking `List<Obd2DataItem>` on `obd2Data`

---

## 14. Constraints & Gotchas

- **`Obd2ServiceProvider.useMock` must be set before the first `MetricsCalculator.getInstance()` call.** `DataOrchestrator` captures `Obd2ServiceProvider.getService()` at construction time. Setting `useMock` after the singleton is created has no effect.
- **`DataOrchestrator.debounce(100 ms)`** means the maximum effective UI refresh rate is ~10 Hz. If OBD2 and GPS emit simultaneously within 100 ms, only one `calculate()` call fires.
- **`accelerometer_enabled` defaults to `false`.** Unlike SJGpsUtil (which defaults to `true`), accel recording is opt-in here.
- **`MetricsCalculator` is created and `startCollecting()` is called lazily on first `getInstance()`.** Any fragment that calls `getInstance()` before `MainActivity` sets `useMock` will lock in the wrong service.
- **Effective polling delay** is resolved from the active `VehicleProfile.obdPollingDelayMs` (if set) or `AppSettings.getGlobalPollingDelayMs()`. Per-profile overrides allow different rates for different vehicles.
- **`PidAvailabilityStore`** ensures the polling loop never sends commands for unsupported PIDs. If a PID is not in the bitmask response, it is permanently skipped for the session — no dynamic re-discovery mid-trip.
- **Legacy `WidgetType` aliases** (`REV_COUNTER`, `SPEEDOMETER_7SEG`, `FUEL_BAR`, `IFC_BAR`) are deprecated enum values. Always call `.canonical()` before rendering to get the current equivalent type. Layouts saved with old names load correctly via `canonical()`.
- **`TripForegroundService` is `START_STICKY`** — Android will restart it if killed. The service re-attaches to the existing `MetricsCalculator` singleton on restart, so trip state is not lost if the service process is killed and recreated.
- **Geoid correction** (`GeoidCorrection`) uses a static lookup table, not a live service. Accuracy is suitable for display purposes but not survey-grade.
- **`GpsDataSource` is started in `MainActivity.onCreate()` and never stopped** — GPS runs for the full app lifetime. This is intentional: GPS warm-up time means stopping on navigation is not worthwhile.
- **Power calculation via accelerometer** (`powerAccelKw`) requires `vehicleMassKg > 0` from the active vehicle profile. If no profile is set or mass is 0, `powerAccelKw` will be `null`.
