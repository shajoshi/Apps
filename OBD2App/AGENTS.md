# OBD2App - Agent Context Guide

This document is the LLM-readable working guide for the OBD2App Android vehicle diagnostics app.

## Quick Overview

**OBD2App** is a Kotlin Android app for ELM327-compatible OBD-II adapters. It reads vehicle data, supports custom and manufacturer-specific enhanced PIDs, renders configurable dashboards, and records trip logs with GPS and accelerometer data.

**Primary goal**: reliable real-time diagnostics and trip computer functionality with strong connection handling and flexible vehicle-specific diagnostics.

## Current Architecture Snapshot

### Tech Stack
- **Language**: Kotlin, View Binding, no Compose
- **Min SDK**: 26
- **Target SDK**: 36
- **Concurrency**: Kotlin Coroutines + StateFlow/LiveData
- **Bluetooth**: Classic RFCOMM/SPP and BLE GATT (abstracted via `Elm327Transport`)
- **Maps**: OSMDroid (OpenStreetMap) — for `LIVE_MAP` dashboard widget and `MapViewFragment`
- **Storage**: SAF/DocumentFile plus app-private fallback
- **UI**: Single-activity app with ViewPager2 and 8 pages (4 swipe-blocked, overflow-menu only)

### Core Pattern Usage
- **Singletons**: service-style objects with double-check locking
- **Strategy**: real vs mock OBD services, Classic vs BLE transport (`TransportFactory`/`DefaultTransportFactory`)
- **Facade**: `TripLifecycleFacade` — single authorised entry point for all trip lifecycle transitions
- **Repository**: profile/settings/CAN profile persistence
- **Observer**: StateFlow for live UI state
- **Orchestrator**: `ObdDataOrchestrator` merges OBD + GPS; `CanDataOrchestrator` merges CAN + GPS
- **State machine**: trip phases IDLE → RUNNING ↔ PAUSED → IDLE

## Main Data Flows

**OBD mode (default):**
```text
Bluetooth adapter
  -> Elm327Transport (ClassicBluetoothTransport or BleTransport)
    -> BluetoothObd2Service
      -> supported PID discovery + tiered polling
      -> custom/manufacturer PID polling (effectiveCustomPids)
  -> ObdDataOrchestrator (combine OBD + GPS, debounce 100ms)
    -> MetricsCalculator.calculate()
      -> VehicleMetrics (StateFlow)
        -> UI fragments / MetricsLogger
```

**CAN Bus mode (`useCanBusLogging = true`):**
```text
Bluetooth adapter
  -> Elm327Transport
    -> CanBusScanner (ATMA monitor mode)
      -> CanFrameParser -> CanDecoder (DBC database)
        -> decoded signal values
  -> CanDataOrchestrator (syncTickerHz tick)
    -> VehicleMetrics (StateFlow)
      -> UI fragments / MetricsLogger
```

Trip logs are written as JSONL files. `TrackFileParser` reads headers/last sample for Trip Summary; `TrackFileMapParser` reads all GPS samples for Map View.

## Important Post-Pull Changes

### 1. Protocol caching and adapter initialization
- `PidCache` stores a per-MAC `protocolNumber` alongside discovered PIDs.
- `BluetoothObd2Service.connect()` reads the cached protocol before init.
- First connection uses auto-detect with `ATSTFF` + `ATSP0`.
- Subsequent connections use `ATSP<N>` directly to skip probing noise.
- After auto-detect, the detected protocol is locked and cached for next time.
- Setting `ignoreCachedPids = true` in settings forces full re-discovery on next connect.

### 2. Manufacturer PID presets
- New `ManufacturerPidLibrary.kt` provides preset `CustomPid` lists.
- Supported families currently include:
  - Suzuki
  - Fiat / Bosch
  - Ford
  - Jaguar (JLR)
  - Bosch Generic
- `VehicleProfile.manufacturer` selects the preset group.
- `VehicleProfile.effectiveCustomPids` merges user-defined PIDs with manufacturer presets.
- User-defined PIDs still override preset IDs.

### 3. More robust polling and disconnect handling
- `BluetoothObd2Service.startPolling()` uses:
  - fast tier PIDs every cycle
  - slow tier PIDs every 5th cycle
  - `effectiveCustomPids` (manufacturer presets + user PIDs) alongside slow tier
- **CAN Bus mode suppresses OBD polling entirely** — `startPolling()` returns immediately when `isCanBusLoggingEnabled`.
- Connection loss detection uses two signals:
  - consecutive failed cycles
  - socket health checks every ~10 cycles
- When failure thresholds are hit, state moves to `ERROR` and transport is closed.
- Discovery mode can bypass the failure cutoff during PID scanning.
- `BluetoothBondLossReceiver` detects bond loss and triggers cache clear + reconnection.

### 4. Vehicle profile and cache persistence updates
- `AppSettings.savePidCache()` now accepts an optional protocol number.
- `AppSettings.getCachedProtocol()` returns the cached protocol for a MAC address.
- `VehicleProfile` and `VehicleProfileRepository` both deserialize the new `manufacturer` field.
- `VehicleProfile` now exposes `effectiveCustomPids` for merged polling sources.

### 5. Expanded metrics / vehicle state model
- `VehicleMetrics.kt` is now a first-class immutable snapshot for computed telemetry.
- `MetricsCalculator`, `MetricsLogger`, `TripState`, and related flow/collection classes were expanded.
- `TripStateTest` was updated alongside the metrics refactor.

### 6. Trip Summary and Map View screens
- **Trip Summary** (PAGE_TRIP_SUMMARY, page 4 — overflow only): Lists JSONL track files from configured log folder. Uses `TrackFileParser` (reads only header + last sample) for efficient summary display. "View Map" opens `MapViewFragment`.
- **Map View** (PAGE_MAP_VIEW, page 5 — overflow only, **only navigable from Trip Summary**): GPS track on OpenStreetMap with `|◀ ◀ ▶ ▶|` navigation buttons, seekbar, cursor marker, speed/altitude at cursor.
- **Sample Details** (`SampleDetailsFragment`): Full-screen scrollable JSON view of a single sample. In-place prev/next navigation. Copy JSON button. Reads directly from `TripSelectionStore`.
- `TripSelectionStore` singleton holds selected track + sample list (avoids Bundle size limits).

### 7. CAN Bus subsystem
- **CAN Bus Reader** (PAGE_CAN_READER, page 7 — overflow only): Lists `CanProfile` entries. Start/Stop drives `CanBusScanner`. Requires ELM327 connected.
- `CanProfile` binds a DBC file to selected signals, sampling rate, and optional raw frame recording.
- `CanBusScanner` puts ELM327 into `ATMA` monitor mode, parses frames via `CanFrameParser`, decodes via `CanDecoder` + `DbcDatabase`.
- `CanDataOrchestrator` emits `VehicleMetrics` updates at `syncTickerHz` combining CAN signals + GPS.
- `RawCanTraceRecorder` writes `.raw.jsonl` files when `CanProfile.recordRawFrames == true`.
- Mock/demo mode: `MockCanFrameSource` replays a recorded `.jsonl` capture or generates frames from `DemoDbcDatabase`.
- **Dashboard integration**: `CanSignal` is a `DashboardMetric` subclass. Signal values appear in `MetricListAdapter` and can be mapped to any widget type.

### 8. LIVE_MAP widget
- `WidgetType.LIVE_MAP` renders an OSMDroid map within a dashboard widget via `LiveMapView`.
- Supports 6 corner/mid-side metric overlays: TL, TR, BL, BR, ML (mid-left edge), MR (mid-right edge).
- Trip time overlay uses compact format: `M:SS` (< 10 min) or `H:MM` (≥ 10 min).
- Overlay value TextViews have `maxLines=1` to prevent wrapping in the 65dp circular badge.
- Map orientation: north-up or heading-up toggle. Zoom scales automatically with speed.

## Navigation Rules (enforced in `MainActivity.navigateToPage`)

- Pages 4–7 (Trip Summary, Map View, Settings, CAN Reader) are **swipe-blocked**. Swipe gestures to these pages are detected and immediately reverted.
- **Settings** requires no active trip AND no active OBD connection.
- **Map View** requires the current page to be Trip Summary. Any other origin shows a Toast and aborts.
- **Trip Summary / CAN Reader / Settings** require no active trip.
- Navigation drawer enforces the same rules for tablet layouts.

---

## OBD2 Communication Rules

### Safe read-only modes
- **01** current data
- **02** freeze frame
- **03** stored DTCs
- **07** pending DTCs
- **09** vehicle info
- **21/22/23** extended/manufacturer data

### Avoid unless explicitly needed
- **04** clear DTCs
- **08** control systems
- **2E/2F/31** UDS write operations

### Adapter initialization expectations
- Send `ATE0`, `ATL0`, and `ATS0` before scanning to avoid echoed commands and formatting artifacts.
- `ATZ` may need a longer delay on some clones.
- Custom PID headers still require `ATSH` switching.

## Important Files To Know

### OBD / transport layer
- `BluetoothObd2Service.kt` — connection, init, polling, custom PID handling
- `Elm327Transport.kt`, `ClassicBluetoothTransport.kt`, `BleTransport.kt` — transport abstraction
- `TransportFactory.kt` — picks Classic vs BLE; injection seam for tests
- `ManufacturerPidLibrary.kt` — manufacturer preset PIDs
- `Obd2CommandRegistry.kt` — standard Mode 01 PID registry
- `PidDiscoveryService.kt` — safe PID discovery
- `ObdConnectionManager.kt` — reconnect/backoff policy
- `BluetoothBondLossReceiver.kt` — bond loss detection
- `UserNotifier.kt` — surfaces connection events to UI

### CAN Bus layer
- `CanBusScanner.kt` — ELM327 CAN monitor mode, frame decode
- `CanDataOrchestrator.kt` — CAN + GPS → VehicleMetrics
- `CanFrameParser.kt`, `CanDecoder.kt` — frame parsing and DBC decoding
- `DbcParser.kt`, `DbcDatabase.kt` — DBC file parsing
- `CanProfile.kt`, `CanProfileRepository.kt` — CAN profile model + persistence
- `RawCanTraceRecorder.kt` — raw frame recording
- `MockCanFrameSource.kt`, `DemoDbcDatabase.kt` — mock/demo mode

### Settings / persistence
- `AppSettings.kt` — settings and PID cache
- `PidCache.kt` — cached PIDs + protocol number per MAC
- `VehicleProfile.kt` — profile model, `effectiveCustomPids`, manufacturer field
- `VehicleProfileRepository.kt` — profile storage and JSON handling
- `TripSelectionStore.kt` — shared selected track state between Trip Summary and Map View

### Metrics / trips
- `MetricsCalculator.kt` — central singleton, calculate(), StateFlow
- `TripLifecycleFacade.kt` — **the only authorised trip lifecycle entry point**
- `ObdDataOrchestrator.kt` — OBD + GPS flow combiner
- `MetricsLogger.kt` — JSONL trip log writer
- `TrackFileParser.kt`, `TrackFileMapParser.kt` — trip log readers for Trip Summary / Map View
- `TripState.kt`, `VehicleMetrics.kt`

### UI
- `ConnectFragment.kt`
- `DetailsFragment.kt`
- `TripFragment.kt` — uses `TripLifecycleFacade` for start/pause/stop
- `TripSummaryFragment.kt` — trip log listing with GPS track visualization
- `MapViewFragment.kt` — GPS track on OpenStreetMap with cursor navigation
- `SampleDetailsFragment.kt` — full-screen sample JSON viewer with in-place navigation
- `CanReaderFragment.kt`, `CanProfileEditSheet.kt` — CAN Bus Reader screen
- `DashboardEditorFragment.kt` — LIVE_MAP corner metrics, trip time formatting
- `LiveMapView.kt` — LIVE_MAP widget view (6 overlay positions, orientation toggle)
- dashboard widget views: `DialView`, `BarGaugeView`, `TemperatureGaugeView`, `SevenSegmentView`

## Key Workflows

### Adding or adjusting manufacturer PIDs
1. Add or edit the preset in `ManufacturerPidLibrary.kt`.
2. Ensure the `CustomPid` has the correct header, mode, PID, byte count, and formula.
3. Verify the profile's `manufacturer` field points to the right enum value.
4. Always use `VehicleProfile.effectiveCustomPids` when setting up the polling loop — not `customPids` directly.

### Debugging connection issues
1. Check whether a cached protocol exists for the device MAC in `AppSettings.pidCacheMap`.
2. Verify adapter init commands and command delays.
3. Watch for repeated failed cycles or `isSocketHealthy()` failures.
4. Confirm the transport type matches the hardware (`device.type` vs `forceBleConnection`).
5. Use `ignoreCachedPids = true` to force a clean PID re-discovery.

### Working with vehicle profiles
1. Profiles persist as JSON in `.obd/profiles/`.
2. The `manufacturer` field is optional and backward compatible.
3. `effectiveCustomPids` merges user-defined + manufacturer presets (user overrides by ID).

### Starting / stopping a trip
1. Always call `TripLifecycleFacade.getInstance(context).startTrip()` — never call `MetricsCalculator.startTripInternal()` directly.
2. CAN mode vs OBD mode is detected inside the facade via `AppSettings.isCanBusLoggingEnabled`.
3. Trip time (`tripTimeSec`) is 0 when `TripPhase == IDLE`.

### Adding a new DashboardMetric subclass
1. Add the subclass to `DashboardMetric.kt`.
2. Update `DashboardMetricAdapter.serialize()` and `deserialize()` in `LayoutRepository.kt`.
3. Handle the new type in `DashboardEditorFragment.updateCorner()` and `MetricListAdapter`.
4. Add default range/unit/threshold in `MetricDefaults.kt`.

### Navigation and screen access
1. Trip Summary (page 4), Map View (5), Settings (6), CAN Reader (7) are accessed via overflow menu only.
2. Map View can only be accessed from Trip Summary (`MainActivity.navigateToPage` enforces this).
3. Bluetooth rejection or permission denial navigates to Trip Summary instead of Dashboards.
4. Map View back button navigates to Trip Summary.
5. Sample Details is a child fragment of Map View, uses back stack for navigation.

## Safety / Stability Notes

- **Connection reliability matters most**: protocol caching and health checks were added specifically to avoid bad CAN initialization and stale UI state.
- **Do not assume `customPids` alone is sufficient**: always use `effectiveCustomPids` — manufacturer presets are merged in automatically.
- **Keep read-only discovery safe**: PID discovery is limited to safe modes and should not wander into write/control services.
- **Storage behavior is important**: persistence code changes must preserve existing JSON and cache compatibility.
- **CAN mode and OBD mode are mutually exclusive**: OBD polling is suppressed in CAN mode. Never start both simultaneously.
- **`TripLifecycleFacade` is the only trip transition entry point**: direct calls to `MetricsCalculator.startTripInternal()` bypass CAN mode routing, foreground service management, and connection monitoring.
- **⚠ Android 10+ `openOutputStream` truncation**: Always use mode `"wt"` (write + truncate). `"w"` leaves stale trailing bytes and corrupts JSON.

## Practical Guidance For Future Changes

- Prefer small, localized edits around the owning layer.
- If you touch OBD init or polling, check protocol caching, `effectiveCustomPids`, and disconnect detection together.
- If you touch vehicle profile serialization, update both repository and settings paths.
- If you touch `DashboardMetric` (add/remove subclasses), update `DashboardMetricAdapter` in `LayoutRepository.kt` or Gson deserialization will silently drop widgets.
- If you touch dashboard widgets or metrics, make sure `VehicleMetrics` and the UI consumers stay in sync.
- If you touch CAN scanning, verify both live-hardware path (`CanBusScanner`) and mock path (`MockCanFrameSource`) work correctly.
- If you touch the `LIVE_MAP` widget corner overlays, update `DashboardWidget`, `LiveMapView`, `DashboardEditorFragment`, `EditWidgetSheet`, and `DashboardEditorViewModel` together — they are tightly coupled.

## Quick Reference

```text
First connection:  ATZ → ATE0 → ATL0 → ATS0 → ATH0 → ATAT1 → ATSTFF → ATSP0 (auto-detect)
Later connections: ATZ → ATE0 → ATL0 → ATS0 → ATH0 → ATAT1 → ATSP<N> (cached protocol)
PID discovery init: ATE0 → ATL0 → ATS0 (no ATZ — preserves connection state)
```

```kotlin
VehicleProfile(
    manufacturer = ManufacturerPidLibrary.Manufacturer.SUZUKI,
    customPids = listOf(...)
)
```

## Summary

When working in this codebase, prioritize:
1. **Connection reliability** — transport abstraction, protocol caching, health checks
2. **Correct protocol caching** — `PidCache.protocolNumber` per MAC
3. **Safe PID discovery and polling** — read-only modes only, `effectiveCustomPids` for polling
4. **Profile/persistence compatibility** — JSON backward compat, `"wt"` mode for writes
5. **Keeping metrics and UI consumers aligned** — `VehicleMetrics` is the single source of truth
6. **Trip lifecycle correctness** — always use `TripLifecycleFacade`, respect CAN vs OBD mode
7. **Navigation guards** — pages 4–7 are restricted; Map View only from Trip Summary
