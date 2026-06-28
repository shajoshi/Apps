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
| `MainActivity.kt` | Single activity. Initialises `Obd2ServiceProvider`, requests BT/location permissions, owns `ViewPager2` with `MainPagerAdapter`. Handles Android 15+ edge-to-edge insets. Keeps screen on (`FLAG_KEEP_SCREEN_ON`) during `RUNNING` trips. |
| `MainPagerAdapter.kt` | ViewPager2 adapter — **8 pages**: Connect (0), Trip (1), Dashboards (2), Details (3), Trip Summary (4), Map View (5), Settings (6), CAN Bus Reader (7). Pages 4–7 are swipe-blocked — accessible only from overflow menu. Constants: `PAGE_CONNECT`, `PAGE_TRIP`, `PAGE_DASHBOARDS`, `PAGE_DETAILS`, `PAGE_TRIP_SUMMARY`, `PAGE_MAP_VIEW`, `PAGE_SETTINGS`, `PAGE_CAN_READER`. |
| `DashboardsHostFragment.kt` | Host fragment for the Dashboard tab, switches between layout list and dashboard view. |
| `OBD2Application.kt` | `Application` subclass. Entry point for app-wide init (declared in `AndroidManifest.xml`). |

### `gps/`
| File | Responsibility |
|------|---------------|
| `GpsDataSource.kt` | Singleton. FusedLocation at 1 s / 500 ms min interval. Exposes `StateFlow<GpsDataItem?>`. Tracks satellite count via `GnssStatus.Callback`. |
| `GpsDataItem.kt` | Data class: speed, MSL altitude, ellipsoid altitude, geoid undulation, accuracy, bearing, satellite count. |
| `GeoidCorrection.kt` | Lookup table for WGS84 geoid undulation used to convert ellipsoid altitude → MSL. |

### `metrics/`
| File | Responsibility |
|------|---------------|
| `MetricsCalculator.kt` | **Central singleton.** Owns `ObdDataOrchestrator`, trip phase state machine, all sub-calculators. `calculate()` returns `VehicleMetrics`. Exposes `StateFlow<VehicleMetrics>`, `StateFlow<TripPhase>`, and `StateFlow<Boolean>` (`dashboardEditMode`). |
| `TripLifecycleFacade.kt` | **Singleton facade for trip lifecycle transitions.** All UI start/pause/stop/resume calls go here. Routes to either the OBD pipeline or `CanBusScanner` depending on `AppSettings.isCanBusLoggingEnabled`. Starts/stops `TripForegroundService` and `ObdConnectionManager` monitoring. |
| `VehicleMetrics.kt` | Immutable snapshot of all metrics for one calculation cycle. ~50 nullable fields. |
| `MetricsLogger.kt` | JSON trip log writer. One JSON file per trip. Header object + one sample object per line. Gated on `AppSettings.isLoggingEnabled`. |
| `TripPhase.kt` | Enum: `IDLE` / `RUNNING` / `PAUSED`. |
| `TripState.kt` | Accumulates trip distance, fuel, moving/stopped time, drive mode (city/hwy/idle) across the trip. |
| `AccelEngine.kt` | Pure JVM. Vehicle-frame basis computation + `computeAccelMetrics()`. Zero Android dependencies. |
| `AccelMetrics.kt` | Output of `AccelEngine.computeAccelMetrics()`. Vertical + fwd + lat axes, lean angle. |
| `AccelCalibration.kt` | Tuning parameters for `AccelEngine` (moving average window, peak threshold). |
| `PowerCalculations.kt` | Pure internal functions (`powerAccelKw`, `powerThermoKw`, `powerOBDKw`) extracted for JVM unit testing. No Android dependencies. |
| `TrackFileParser.kt` | Token-streams trip log files using `JsonReader`. Extracts only `vehicleProfile` from header and the last sample. Peak memory: 2 `JSONObject`s. Never calls `readText()`. |

### `metrics/calculator/`
| File | Responsibility |
|------|---------------|
| `FuelCalculator.kt` | Instantaneous and trip fuel efficiency. PID 015E (direct rate) preferred; MAF-based fallback with **diesel boost correction** (boost pressure + RPM + load-aware AFR adjustment for turbocharged diesel engines). |
| `PowerCalculator.kt` | Three power calculation methods: accelerometer-based, thermodynamic (fuel × efficiency), OBD torque-based. |
| `TripCalculator.kt` | Average speed, speed diff (GPS vs OBD cross-check). |

### `metrics/collector/`
| File | Responsibility |
|------|---------------|
| `ObdDataOrchestrator.kt` | Combines `obd2Data` + `gpsData` flows via `combine(...).debounce(100ms)`. Calls `calculator.calculate()` on each emission. Started automatically when `MetricsCalculator` singleton is first created. |
| `DataOrchestrator.kt` | Legacy/internal orchestrator kept for CAN-mode use. |

### `obd/`
| File | Responsibility |
|------|---------------|
| `Obd2Service.kt` | Interface: `connectionState`, `obd2Data`, `errorMessage`, `connectedDeviceName`, `connectionLog`, `connect(device?)`, `disconnect()`. `ConnectionState` enum: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `ERROR`. |
| `BluetoothObd2Service.kt` | Real hardware implementation. Uses `TransportFactory` + `Elm327Transport` abstraction. ELM327 AT init sequence, PID bitmask discovery, **tiered polling loop** (fast + slow), **custom PID polling** (manufacturer + user), connection health monitoring, `sendCommandForDiscovery()` for PID discovery. Singleton. |
| `Elm327Transport.kt` | Interface for ELM327 I/O: `sendCommand(cmd): String`, `close()`. Abstracts Classic vs BLE transport. |
| `ClassicBluetoothTransport.kt` | `Elm327Transport` implementation over RFCOMM socket (SPP UUID). |
| `BleTransport.kt` | `Elm327Transport` implementation over BLE GATT (Nordic UART Service). Handles characteristic write + notification-based response. |
| `TransportFactory.kt` | `fun interface` that creates an `Elm327Transport` for a given device. `DefaultTransportFactory` chooses Classic vs BLE based on `device.type` and `forceBle` user setting. |
| `ConnectTarget.kt` | Sealed type representing what to connect to: `BluetoothDeviceTarget(device)` or `MockTarget`. |
| `ConnectionSettingsSource.kt` | Interface providing connection settings (polling delay, command delay, force-BLE flag) to `BluetoothObd2Service`. |
| `MockObd2Service.kt` | Test implementation. Loads baseline from `assets/mock_obd2_data.json` with ±5% jitter. Optionally loads `mock_obd2_enhanced.json` for discovery testing via `MockObd2CommandProcessor`. |
| `MockObd2CommandProcessor.kt` | Processes OBD commands for the mock emulator: AT commands, ECU header switching (`AT SH`), PID queries with configurable failure rates and error simulation. |
| `MockDiscoveryScenario.kt` | Enum of test scenarios for PID discovery: `JAGUAR_XF`, `TOYOTA_HYBRID`, `MIXED_HEADERS`, `EMPTY_DISCOVERY`, `ERROR_HEAVY`. Also defines `DiscoveredPid` data class. |
| `Obd2ServiceProvider.kt` | Factory object. `useMock` flag selects real vs mock service. `initMock(context)` / `initBluetooth(context)` load the appropriate service. Must be called before first `MetricsCalculator.getInstance()`. |
| `ObdStateManager.kt` | **Centralised OBD state singleton.** Single source of truth for mode (`MOCK`/`REAL`), `connectionState`, `autoConnect`, `connectedDeviceName`. Exposes `StateFlow`s. `initialize()` called at app startup; `switchMode()` called from Settings. Keeps `Obd2ServiceProvider.useMock` in sync. |
| `ObdConnectionManager.kt` | **Auto-reconnection manager.** Monitors OBD connection during active trips (RUNNING/PAUSED). Adaptive backoff: 5 attempts at 10 s, then 60 s intervals. Resets on success. `startMonitoring()` / `stopMonitoring()` driven by trip lifecycle. `markManualDisconnect()` suppresses auto-reconnect. |
| `ManufacturerPidLibrary.kt` | Catalogue of manufacturer-specific preset `CustomPid` lists. Supported families: Suzuki, Fiat/Bosch, Ford, Jaguar (JLR), Bosch Generic. Accessed via `VehicleProfile.manufacturer` enum. |
| `UserNotifier.kt` | Small interface/helper for surfacing OBD connection events to the UI (Toasts, etc.) without tight coupling to `BluetoothObd2Service`. |
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
| `AppSettings.kt` | **Singleton with pending-settings pattern.** Loads/saves from SharedPreferences (primary) with JSON fallback. `SettingsData` inner class holds all fields. `getPendingSettings()` / `updatePendingSettings()` / `savePendingSettings()` / `discardPendingSettings()` for transactional edits. Fields include: `obdConnectionEnabled`, `autoConnect`, `loggingEnabled`, `autoShareLog`, `accelerometerEnabled`, `btLoggingEnabled`, `forceBleConnection`, `globalPollingDelayMs`, `globalCommandDelayMs`, `activeProfileId`, `defaultLayoutName`, `lastDeviceMac`, `lastDeviceName`, `pidCacheMap`, `lastTripSnapshot`, `useCanBusLogging`, `defaultCanProfileId`, `ignoreCachedPids`, `syncTickerHz`. `log_folder_uri` always in SharedPreferences (bootstrap). |
| `PidCache.kt` | Cached PID discovery results per BT MAC address. Fields: `macAddress`, `discoveredPids: Map<String, CachedPidEntry>`, `timestamp`, `protocolNumber: String?`. Protocol number cached so subsequent connections skip auto-detect and use `ATSP<N>` directly. |
| `VehicleProfile.kt` | `data class VehicleProfile(id, name, fuelType, tankCapacityL, fuelPricePerLitre, enginePowerBhp, vehicleMassKg, engineDisplacementCc, volumetricEfficiencyPct, availablePids, customPids, manufacturer)`. `manufacturer` field links to `ManufacturerPidLibrary.Manufacturer` enum. `effectiveCustomPids` computed property merges user-defined and manufacturer preset PIDs (user overrides preset on same ID). `FuelType` enum (Petrol, E20, Diesel, CNG) with `mafMlPerGram`, `co2Factor`, `energyDensityMJpL`. `sanitisedName` computed property for filesystem-safe filenames. |
| `VehicleProfileRepository.kt` | CRUD for profiles + PID management. Stores as individual JSON files (`vehicle_profile_<name>.json`) in `.obd/profiles/` via SAF or app-private fallback. Legacy SharedPreferences read for backward compat. `updatePids()` merges new PID values, `getKnownPids()`, `getLastPidValues()`, `hasDiscoveredPids()`. Custom PIDs serialised inside profile JSON. Auto-sets first profile as active. |
| `VehicleProfileEditSheet.kt` | BottomSheet for create/edit profile. Includes manufacturer selector. |
| `CustomPidEditSheet.kt` | BottomSheet for creating/editing a single `CustomPid`. Fields: name, header, mode, hex, bytes returned, unit, formula, signed. Saves via `VehicleProfileRepository`. |
| `CustomPidListSheet.kt` | BottomSheet listing all custom PIDs for the active profile. Tap to edit (opens `CustomPidEditSheet`), add new, or launch PID discovery (`PidDiscoverySheet`). |
| `CustomPidDiff.kt` | Utility for diffing two `CustomPid` lists — detects added, removed, and changed entries. Used when merging manufacturer presets with user-defined PIDs. |

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
| `DashboardEditorFragment.kt` | Free-form widget placement editor. Drag, resize, z-order, alpha, range config. Also drives `LiveMapView` with real-time GPS, corner metrics, and trip time formatting. |
| `DashboardEditorViewModel.kt` | Editor state, widget CRUD, save/load layout JSON. |
| `EditWidgetSheet.kt` | Bottom sheet: edit metric, widget type, range, decimals, warning threshold per widget. For `LIVE_MAP` type: includes selectors for TL/TR/BL/BR/ML/MR corner metrics. |
| `WidgetResizeHandler.kt` | Touch handler for widget resize affordance. |
| `WidgetTouchHandler.kt` | Touch handler for widget drag/move. |
| `LayoutListFragment.kt` | Shows saved layouts list. Tap to open, long-press to edit/delete. |
| `GridOverlayView.kt` | Canvas view that draws the grid snap overlay during editing. |
| `MetricListAdapter.kt` | Reusable `RecyclerView.Adapter` for metric selection. Groups by category, shows availability badges (live / previously seen / not seen) based on `VehicleProfileRepository` PID data and live `obd2Data`. Includes CAN signals from `CanBusScanner`. |
| `model/DashboardLayout.kt` | `DashboardLayout(name, colorScheme, widgets, orientation)`. `DashboardOrientation` enum: `PORTRAIT`, `LANDSCAPE`. `ColorScheme` presets: `DEFAULT_DARK`, `NEON_RED`, `GREEN_LCD`. |
| `model/DashboardWidget.kt` | `DashboardWidget` — widget instance: type, metric, grid position/size, zOrder, alpha, range, warningThreshold, decimalPlaces, displayUnit. For `LIVE_MAP`: `cornerMetricTL/TR/BL/BR/ML/MR` hold optional overlay metrics. |
| `model/WidgetType.kt` | Enum: `DIAL`, `SEVEN_SEGMENT`, `BAR_GAUGE_H`, `BAR_GAUGE_V`, `NUMERIC_DISPLAY`, `TEMPERATURE_ARC`, `LIVE_MAP`. Legacy aliases kept for JSON backward compat. |
| `model/DashboardMetric.kt` | **Sealed class** (not enum). Subclasses: `Obd2Pid(pid, name, unit)`, `GpsSpeed`, `GpsAltitude`, `DerivedMetric(key, name, unit)`, `CanSignal(messageId, signalName, name, unit)`. `CanSignal.latestKey()` returns `"<HEX_ID>:<signalName>"` for `CanBusScanner.latest` lookup. |
| `model/MetricDefaults.kt` | Default range min/max, major tick, unit, warning threshold per metric. Auto-populated when a metric is chosen in the editor. |
| `model/CanMetricSource.kt` | Helper that maps `CanSignal` metric keys to live values from `CanBusScanner`. |
| `data/LayoutRepository.kt` | Saves/loads `DashboardLayout` as JSON via Gson. `DashboardMetricAdapter` handles sealed-class polymorphism (including `CanSignal`). Dual storage: SAF `.obd/layouts/dashboard_<name>.json` or app-private fallback. `seedDefaultDashboards()` copies from `assets/seed_dashboards/` on first install. `getDefaultLayoutName()` / `setDefaultLayoutName()`. |
| `views/LiveMapView.kt` | Custom `View` for the `LIVE_MAP` widget. OSMDroid map with animated vehicle marker, bearing rotation, corner/mid-side metric overlays (6 positions: TL/TR/BL/BR/ML/MR), zoom-to-speed scaling, and north-up/heading-up toggle. |
| `views/` | Other custom `View` implementations for each `WidgetType` (dial canvas, 7-segment, bar, etc.). |
| `wizard/` | New-layout creation wizard fragments. |

### `ui/trip/`
| File | Responsibility |
|------|---------------|
| `TripFragment.kt` | Trip tab UI. Shows status indicators, gravity vector, trip stats. Start/pause/stop buttons. Delegates lifecycle calls to `TripLifecycleFacade`. |
| `TripViewModel.kt` | Collects `metrics` + `tripPhase` flows, formats all display strings, computes indicator colours. |

### `ui/tripsummary/`
| File | Responsibility |
|------|---------------|
| `TripSummaryFragment.kt` | Lists recorded trip log files from the configured log folder. Shows trip summary metrics (fuel, speed, distance) parsed via `TrackFileParser`. GPS track visualisation via "View Map" button → `MapViewFragment`. Multi-select up to 5 tracks for combined analysis. |
| `TripSummaryViewModel.kt` | Scans log folder for `*.json` trip files. `scanFileForStats()` token-streams each file via `JsonReader` to extract vehicleProfile + last sample only. `saveCombinedFile()` streams samples token-by-token from source files to output — never builds a full in-memory sample list. |
| `TripSelectionStore.kt` | Singleton. Holds the currently selected track (`fileName`, `uri`, `lastSample`). **No samples list** — samples are lazy-loaded by `MapViewModel`. Shared between `TripSummaryFragment` and `MapViewFragment`. |
| `TrackFileItem` | Data class: `name`, `uri`, `size`, `date`. |
| `ParsedFile` | ViewModel-private data class: `item: TrackFileItem`, `header: JSONObject` (vehicleProfile), `lastSample: JSONObject`. |

### `ui/mapview/`
| File | Responsibility |
|------|---------------|
| `MapViewFragment.kt` | OSMDroid GPS track visualisation. Observes `MapViewModel.pathPoints` (`List<GeoPoint>`) for the polyline. Calls `viewModel.fetchSample(index)` on seek/nav interactions — never holds a samples list. |
| `MapViewModel.kt` | `loadPathPoints()` — token-streams the track file extracting only `gps.lat/lon` per sample into `List<GeoPoint>`; zero `JSONObject` during path load. `fetchSample(index)` — re-opens file, skips to sample N, emits one `JSONObject` via `fetchedSample: StateFlow<JSONObject?>`. |
| `SampleDetailsFragment.kt` | Full-screen scrollable JSON view of a single trip sample. Observes `MapViewModel.fetchedSample`; calls `mapViewModel.fetchSample()` for prev/next navigation. `newInstance(index, total)` — requires total count arg. |

### `ui/can/`
| File | Responsibility |
|------|---------------|
| `CanReaderFragment.kt` | CAN Bus Reader screen (page 7). Lists user-created `CanProfile` entries. Start/Stop button drives `CanBusScanner`. Requires ELM327 adapter connected. |
| `CanProfileEditSheet.kt` | BottomSheet for creating/editing a `CanProfile`. Fields: name, objective, DBC file selection, signal picker, sampling rate, trip-attribute mapping. |

### `ui/settings/`
| File | Responsibility |
|------|---------------|
| `SettingsFragment.kt` | Settings tab (page 6 — swipe-blocked, overflow menu only). Connection toggles (OBD on/off, auto-connect, BT logging, accelerometer, Force BLE, Ignore Cached PIDs), vehicle profile list with CRUD, CAN Bus logging toggle, data logging folder picker with **folder migration dialog**, debug section (mock scenario selector — only visible when enhanced mock data loaded). Pending-settings pattern: changes are staged and saved via Save button. `restartObdService()` switches mode via `ObdStateManager`. |
| `PidDiscoverySheet.kt` | BottomSheet UI for PID discovery. Header/mode selection, console output (real-time), discovered PID list with multi-select. "Add Selected" saves chosen PIDs as `CustomPid` entries via `VehicleProfileRepository`. Integrates with `PidDiscoveryService`. |
| `ConsoleAdapter.kt` | `ListAdapter` for console log messages. Strips timestamps, colour-codes by content: ERROR (red), NODATA (grey), VALID (green), Scanning/HEADER/Complete/Cancelled (various). |
| `DiscoveredPidAdapter.kt` | `ListAdapter` for `DiscoveredPid` items with checkbox multi-selection, visual selection state (background + checkbox). `getSelectedPids()`, `clearSelections()`. |

### `ui/details/`
Details screen — shows full raw OBD2 PID dump for diagnostics.

### `bluetooth/`
| File | Responsibility |
|------|---------------|
| `BluetoothBondLossReceiver.kt` | `BroadcastReceiver` for `ACTION_BOND_STATE_CHANGED`. Detects when a paired device loses its bond (e.g., after factory reset of the adapter) and notifies `BluetoothObd2Service` to clear cached protocol and trigger reconnection. |

### `can/`
| File | Responsibility |
|------|---------------|
| `CanBusScanner.kt` | **CAN Bus scanning engine.** Connects to ELM327 in CAN monitor mode (`ATMA`). Decodes frames via attached `DbcDatabase`. Emits decoded signal values to `latest: Map<String, Double>` and `canSignalValues` flow. Drives `VehicleMetrics` fields via `CanDataOrchestrator`. |
| `CanDataOrchestrator.kt` | Orchestrates CAN scanning lifecycle. Starts/stops `CanBusScanner`. When a trip is active in CAN mode, combines CAN signal values with GPS to produce `VehicleMetrics` updates. Uses `syncTickerHz` from the active `CanProfile`. |
| `CanDecoder.kt` | Decodes a raw CAN frame (ID + data bytes) into a map of signal name → physical value using a `DbcDatabase`. |
| `CanEncoder.kt` | Encodes signal values back into CAN frames (used for testing / mock replay). |
| `CanFrameParser.kt` | Parses raw ELM327 CAN output lines into structured `CanFrame(id, data)` objects. Handles various ELM327 CAN output formats. |
| `CanProfile.kt` | User-created CAN logging profile. Fields: `id`, `name`, `objective`, `dbcFileName`, `selectedSignals`, `samplingMs`, `syncTickerHz`, `canIdFilter`, `recordRawFrames`, `playbackCaptureFileName`, `isDefault`, `useDemoData`, `metricMapping`. One profile marked `isDefault` drives `CanDataOrchestrator`. |
| `CanProfileRepository.kt` | CRUD for `CanProfile` objects. Persists as JSON in app-private storage. Manages DBC file copies in `files/can_dbc/<id>.dbc`. |
| `DbcDatabase.kt` | In-memory representation of a parsed DBC file. Maps message IDs to signal definitions. |
| `DbcParser.kt` | Parses DBC file text into a `DbcDatabase`. Handles messages, signals, bit positions, scale/offset, value tables. |
| `DemoDbcDatabase.kt` | Synthetic `DbcDatabase` with realistic demo signals (RPM, speed, throttle, etc.) for mock CAN scanning without a real DBC file. |
| `MockCanFrameSource.kt` | Generates synthetic CAN frames for mock mode. Either replays a recorded `.jsonl` capture file or generates frames from `DemoDbcDatabase`. |
| `RawCanTraceRecorder.kt` | Records raw CAN frames to a `.raw.jsonl` file during live scanning (when `CanProfile.recordRawFrames == true`). |
| `DataOrchestrator.kt` | Internal data combiner for CAN + GPS flows (separate from OBD's `ObdDataOrchestrator`). |

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
└── ViewPager2 (MainPagerAdapter — 8 pages)
    ├── Page 0: ConnectFragment          (BT device list + connection)
    ├── Page 1: TripFragment             (trip computer)
    ├── Page 2: DashboardsHostFragment   → LayoutListFragment | DashboardFragment
    │                                    → DashboardEditorFragment (edit mode)
    ├── Page 3: DetailsFragment          (raw OBD2 PID dump)
    ├── Page 4: TripSummaryFragment      (overflow menu only)
    │           └── → MapViewFragment   (page 5, only from Trip Summary)
    │                   └── SampleDetailsFragment (child fragment, back-stack)
    ├── Page 5: MapViewFragment          (overflow menu only, only from Trip Summary)
    ├── Page 6: SettingsFragment         (overflow menu only)
    │           ├── VehicleProfileEditSheet  (create/edit profile, manufacturer picker)
    │           ├── CustomPidListSheet → CustomPidEditSheet
    │           └── PidDiscoverySheet       (scan for PIDs)
    └── Page 7: CanReaderFragment        (overflow menu only)
                └── CanProfileEditSheet  (create/edit CAN profile)
```

- **Pages 4–7 are swipe-blocked** — `ViewPager2.isUserInputEnabled = false` while on those pages. Swipe attempts are intercepted and reverted. Users must use the overflow menu.
- `DashboardsHostFragment` uses `childFragmentManager` replace transactions to switch between layout list and live dashboard.
- `DashboardEditorFragment` launched from `LayoutListFragment` via fragment transaction.
- **Settings access blocked during active trips AND when OBD is connected** — both swipe and overflow menu are guarded.
- **Map View** is only accessible from Trip Summary (`navigateToPage` enforces this).
- `TopBarHelper` overflow menu available on all pages for quick navigation.
- ViewPager swipe disabled globally during dashboard edit mode (`dashboardEditMode` StateFlow).

---

## 5. Data Pipeline Flow (step by step)

```
1. MainActivity.onCreate()
   a. AppDataDirectory.ensureUriPermissions(ctx)  — re-take SAF permissions after cold start
   b. DataMigration.checkExistingData(ctx)  — Toast if .obd data found
   c. ObdStateManager.initialize(autoConnect, obdEnabled)  — sets mode MOCK/REAL
   d. If mock mode: Obd2ServiceProvider.initMock(ctx), else Obd2ServiceProvider.initBluetooth(ctx)
   e. ViewPager2 + MainPagerAdapter created (8 pages)
   f. Start GpsDataSource.getInstance(ctx).start()
   g. MetricsCalculator.getInstance(ctx)   ← creates singleton + calls startCollecting()

2. MetricsCalculator.startCollecting()
   → creates ObdDataOrchestrator(context, scope, this)
   → ObdDataOrchestrator.startCollecting()

3. ObdDataOrchestrator.startCollecting()
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

All transitions go through **`TripLifecycleFacade`** (not called directly on `MetricsCalculator`).

**OBD mode (`isCanBusLoggingEnabled == false`):**
- `startTrip()` → `MetricsCalculator.startTripInternal()` → resets `TripState`, starts accelerometer, opens logger, starts `ObdConnectionManager.monitoring`
- `stopTrip()` → `MetricsCalculator.stopTripInternal()` → resets state, stops accel, closes logger, stops monitoring
- `pauseTrip()` / `resumeTrip()` → `tripState.update()` skipped while paused

**CAN mode (`isCanBusLoggingEnabled == true`):**
- `startTrip()` → starts `CanBusScanner` + `CanDataOrchestrator`, sets `TripPhase.RUNNING`
- `stopTrip()` → stops scanner, sets `TripPhase.IDLE`
- OBD polling is entirely suppressed (enforced by `BluetoothObd2Service.startPolling`)

**`tripTimeSec` gating:** Trip time is only counted when `_tripPhase.value == TripPhase.RUNNING`. Returns 0L when `IDLE`.

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
| `forceBleConnection` | false | Force BLE transport even for Classic-capable devices |
| `globalPollingDelayMs` | 500 ms | OBD2 poll interval |
| `globalCommandDelayMs` | 50 ms | Delay between AT commands |
| `activeProfileId` | null | Active vehicle profile UUID |
| `defaultLayoutName` | null | Default dashboard layout name |
| `lastDeviceMac` | null | Last connected BT device MAC |
| `lastDeviceName` | null | Last connected BT device name |
| `pidCacheMap` | empty | Per-MAC PID cache (keyed by MAC address, includes `protocolNumber`) |
| `useCanBusLogging` | false | Route trip to CAN Bus scanner instead of OBD polling |
| `defaultCanProfileId` | null | Active `CanProfile` UUID |
| `ignoreCachedPids` | false | Skip PID cache on next connect (force re-discovery) |
| `syncTickerHz` | 50 | CAN data orchestrator tick rate in Hz (1–200) |
| `lastTripSnapshot` | null | Summary of the most recent completed trip |

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

## 14. Memory Strategy — JsonReader Streaming

All track file I/O uses `android.util.JsonReader` token streaming. **Never call `readText()` on a track file or load it into a `JSONObject`/`JSONArray`.**

### Peak memory by operation

| Operation | Peak memory |
|-----------|-------------|
| `TrackFileParser.parseTrackFile()` | 2 small `JSONObject`s (vehicleProfile + lastSample) |
| `TripSummaryViewModel.scanFileForStats()` | Same — 2 objects per source file, discarded after |
| `TripSummaryViewModel.saveCombinedFile()` | 1 sample `String` at a time, written to `BufferedWriter` immediately |
| `MapViewModel.loadPathPoints()` | `GeoPoint` only (~40 bytes/sample); zero `JSONObject` during path load |
| `MapViewModel.fetchSample(index)` | 1 `JSONObject` on demand per user interaction; GC-able immediately |

### Shared helpers

- **`readGeoPoint(reader)`** — in `MapViewModel`. Reads one sample object, extracts only `gps.lat/lon`, allocates only a `GeoPoint`. All other tokens skipped.
- **`readJsonValue(reader)`** — in both `MapViewModel` and `TripSummaryViewModel`. Reconstructs one complete JSON value token-by-token into a `String`. Used by `fetchSample` and `saveCombinedFile`.
- **`fetchSample(index)`** — re-opens the SAF URI, navigates the `JsonReader` to the `samples` array, skips entries 0..N-1 via `skipValue()`, reads entry N with `readJsonValue`, emits via `fetchedSample: StateFlow<JSONObject?>`.

### StateFlow sharing

`MapViewModel.fetchedSample` is a single `StateFlow<JSONObject?>` observed by both `MapViewFragment` (cursor info bar) and `SampleDetailsFragment` (detail view). Only one `JSONObject` is ever in memory at a time.

### Multi-track analysis flow

```
analyzeSelectedFiles(items)
  for each item → scanFileForStats()    ← JsonReader, 2 objects, discarded per file
  merge vehicleProfiles, pick lastSample
  saveCombinedFile(vehicleProfile, sourceFiles)
    write header JSON
    for each sourceFile → JsonReader → stream samples token-by-token → BufferedWriter
    write closing bracket
```

---

## 15. Constraints & Gotchas

- **`ObdStateManager.initialize()` must be called before ViewPager creation.** It sets `Obd2ServiceProvider.useMock` which `ObdDataOrchestrator` captures at construction time. Calling after singleton creation has no effect on the active service.
- **`DataOrchestrator.debounce(100 ms)`** means the maximum effective UI refresh rate is ~10 Hz. If OBD2 and GPS emit simultaneously within 100 ms, only one `calculate()` call fires.
- **`accelerometer_enabled` defaults to `false`.** Unlike SJGpsUtil (which defaults to `true`), accel recording is opt-in here.
- **`MetricsCalculator` is created and `startCollecting()` is called lazily on first `getInstance()`.** Any fragment that calls `getInstance()` before `ObdStateManager.initialize()` will lock in the wrong service.
- **Tiered polling has no explicit inter-PID delay** — `ATAT1` adaptive timing handles ECU pacing. Adding `Thread.sleep()` between PIDs will degrade throughput.
- **Custom PIDs group by header** to minimise `AT SH` switches. After polling custom PIDs, the default header `7DF` is always restored. Failing to restore the header will break standard Mode 01 polling.
- **`effectiveCustomPids` is the list used for polling** — not `customPids` alone. Manufacturer presets are merged in automatically. Always use `VehicleProfile.effectiveCustomPids` when setting up the polling loop.
- **Protocol caching** (`PidCache.protocolNumber`): first connection uses `ATSTFF`+`ATSP0` auto-detect, then locks and caches the protocol. Subsequent connections use `ATSP<N>` directly to skip renegotiation. Set `ignoreCachedPids = true` in settings to force re-discovery.
- **PID discovery scans read-only modes only** (21, 22, 23). Modes 01–0A are standard and already handled. Actuator/control PIDs are skipped for safety.
- **CAN Bus mode disables OBD polling entirely** (`BluetoothObd2Service.startPolling` checks `isCanBusLoggingEnabled`). Trip start in CAN mode uses `CanBusScanner` — not `MetricsCalculator.startTripInternal`.
- **`TripLifecycleFacade` is the only authorised trip transition entry point** — UI must not call `MetricsCalculator.startTripInternal/stopTripInternal` directly.
- **⚠ Android 10+ `openOutputStream` truncation**: Always use mode `"wt"` (write + truncate). Mode `"w"` writes from the beginning but does NOT truncate, leaving stale trailing bytes if new content is shorter — this corrupts JSON files. This applies to all 4 write locations: `VehicleProfileRepository`, `LayoutRepository`, `AppSettings`, `SettingsFragment` (migration copy).
- **Settings access is blocked during active trips AND when OBD is connected** — both swipe and overflow menu are guarded. The navigation drawer also disables Settings and Trip Summary items when a trip is active.
- **Map View is only accessible from Trip Summary** — `MainActivity.navigateToPage()` enforces this guard. Attempting to navigate to `PAGE_MAP_VIEW` from any other page shows a Toast and aborts.
- **Never load a full track file into memory** — all track file reads use `JsonReader` token streaming (see §14). `readText()` on a large combined file causes `OutOfMemoryError`. `TrackFileParser`, `TripSummaryViewModel`, and `MapViewModel` all enforce this.
- **`MapViewModel.fetchSample()` re-opens the file on every call** — this is intentional. The cost is one sequential file seek (fast on local storage), and it keeps the memory profile at 1 `JSONObject` regardless of file size. Do not cache the result beyond the current interaction.
- **ViewPager swipe is disabled during dashboard edit mode** via `MetricsCalculator.dashboardEditMode` StateFlow observed in `MainActivity`.
- **Legacy `WidgetType` aliases** (`REV_COUNTER`, `SPEEDOMETER_7SEG`, `FUEL_BAR`, `IFC_BAR`) are deprecated enum values. Always call `.canonical()` before rendering to get the current equivalent type. Layouts saved with old names load correctly via `canonical()`.
- **`TripForegroundService` is `START_STICKY`** — Android will restart it if killed. The service re-attaches to the existing `MetricsCalculator` singleton on restart, so trip state is not lost if the service process is killed and recreated.
- **`DashboardMetric` is a sealed class, not an enum.** Gson serialisation requires the custom `DashboardMetricAdapter` to handle polymorphism correctly. Adding new subclasses requires updating the adapter's `serialize`/`deserialize` methods.
- **First vehicle profile is auto-set as active.** `VehicleProfileRepository.save()` checks if no profiles exist before saving and auto-assigns `activeProfileId`. Deleting the active profile falls back to the first remaining profile.
- **`BluetoothConnectionLogger` writes to `.obd/obd_bt_connx.log`** using `"wa"` (write-append) mode. This file grows indefinitely — no rotation is implemented.
- **Geoid correction** (`GeoidCorrection`) uses a static lookup table, not a live service. Accuracy is suitable for display purposes but not survey-grade.
- **`GpsDataSource` is started in `MainActivity.onCreate()` and never stopped** — GPS runs for the full app lifetime. This is intentional: GPS warm-up time means stopping on navigation is not worthwhile.
- **Power calculation via accelerometer** (`powerAccelKw`) requires `vehicleMassKg > 0` from the active vehicle profile. If no profile is set or mass is 0, `powerAccelKw` will be `null`.
