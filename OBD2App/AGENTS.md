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
- **Bluetooth**: Classic RFCOMM/SPP and BLE GATT
- **Storage**: SAF/DocumentFile plus app-private fallback
- **UI**: Single-activity app with ViewPager2 and nested fragments

### Core Pattern Usage
- **Singletons**: service-style objects with double-check locking
- **Strategy**: real vs mock OBD services, Classic vs BLE transport
- **Repository**: profile/settings persistence
- **Observer**: StateFlow for live UI state
- **Orchestrator**: `DataOrchestrator` merges OBD, GPS, and accelerometer input
- **State machine**: trip phases such as IDLE, RUNNING, PAUSED

## Main Data Flow

```text
Bluetooth adapter
  -> BluetoothObd2Service
    -> supported PID discovery + tiered polling
    -> custom/manufacturer PID polling
  -> DataOrchestrator
    -> MetricsCalculator
      -> VehicleMetrics
        -> UI fragments / trip logger
```

Trip logs are still written as JSON files and shared/exported through the storage layer.

## Important Post-Pull Changes

### 1. Protocol caching and adapter initialization
- `PidCache` now stores a per-MAC `protocolNumber`.
- `BluetoothObd2Service.connect()` reads the cached protocol before init.
- First connection still uses auto-detect with `ATSTFF` + `ATSP0`.
- Subsequent connections use `ATSP<N>` directly to skip probing noise.
- After auto-detect, the detected protocol is locked and cached for next time.

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
- `BluetoothObd2Service.startPolling()` now uses:
  - fast tier PIDs every cycle
  - slow tier PIDs every 5th cycle
  - manufacturer/custom PIDs alongside slow tier polling
- Connection loss detection uses two signals:
  - consecutive failed cycles
  - socket health checks every ~10 cycles
- When failure thresholds are hit, state moves to `ERROR` and transport is closed.
- Discovery mode can bypass the failure cutoff during PID scanning.

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
- **Trip Summary** (PAGE_TRIP_SUMMARY): Lists recorded track files from configured log folder, displays trip summary metrics (fuel, speed, distance), and provides GPS track visualization via Map View button.
- Log folder selection centralized in Settings (`AppSettings.getLogFolderUri`/`setLogFolderUri`). Trip Summary reuses this folder and provides a Reload button to refresh the file list.
- **Map View** (PAGE_MAP_VIEW): GPS track visualization on OpenStreetMap with `|<`, `<`, `>`, `>|` navigation buttons to step through samples, cursor marker, seekbar, and speed/altitude display in cursor info.
- **Sample Details**: Full-screen fragment (`SampleDetailsFragment`) with scrollable monospace JSON view, in-place navigation buttons, and Copy JSON button. Reads samples directly from `TripSelectionStore` to avoid Bundle size limits.
- `TripSelectionStore.selectedTrack` holds the currently selected track with samples list, shared between Trip Summary and Map View.

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
- `BluetoothObd2Service.kt` - connection, init, polling, custom PID handling
- `ManufacturerPidLibrary.kt` - manufacturer preset PIDs
- `Obd2CommandRegistry.kt` - standard Mode 01 PID registry
- `PidDiscoveryService.kt` - safe PID discovery
- `ObdConnectionManager.kt` - reconnect/backoff policy
- `BleTransport.kt`, `ClassicBluetoothTransport.kt`, `Elm327Transport.kt`

### Settings / persistence
- `AppSettings.kt` - settings and PID cache
- `PidCache.kt` - cached PIDs + protocol number
- `VehicleProfile.kt` - profile model and merged custom PIDs
- `VehicleProfileRepository.kt` - profile storage and JSON handling
- `TripSelectionStore.kt` - shared selected track state between Trip Summary and Map View

### Metrics / trips
- `MetricsCalculator.kt`
- `MetricsLogger.kt`
- `TripState.kt`
- `VehicleMetrics.kt`
- `DataOrchestrator.kt`

### UI
- `ConnectFragment.kt`
- `DetailsFragment.kt`
- `TripSummaryFragment.kt` - Trip log listing with GPS track visualization and sample inspection
- `MapViewFragment.kt` - GPS track on OpenStreetMap with cursor navigation and sample details overlay
- `SampleDetailsFragment.kt` - Full-screen sample JSON viewer with in-place navigation
- dashboard widget views such as `DialView`, `BarGaugeView`, `TemperatureGaugeView`, `SevenSegmentView`

## Key Workflows

### Adding or adjusting manufacturer PIDs
1. Add or edit the preset in `ManufacturerPidLibrary.kt`.
2. Ensure the `CustomPid` has the correct header, mode, PID, byte count, and formula.
3. Verify the profile’s `manufacturer` field points to the right enum value.

### Debugging connection issues
1. Check whether a cached protocol exists for the device MAC.
2. Verify adapter init commands and command delays.
3. Watch for repeated failed cycles or `isSocketHealthy()` failures.
4. Confirm the transport type matches the hardware.

### Working with vehicle profiles
1. Profiles persist as JSON.
2. The `manufacturer` field is optional and backward compatible.
3. `effectiveCustomPids` is the list used by polling, not just `customPids`.

### Navigation and screen access
1. Trip Summary and Map View are accessed via overflow menu from the top bar.
2. Map View can only be accessed from Trip Summary (enforced in `MainActivity.navigateToPage`).
3. Bluetooth rejection or permission denial navigates to Trip Summary instead of Dashboards.
4. Map View back button navigates to Trip Summary.
5. Sample Details is a child fragment of Map View, uses back stack for navigation.

## Safety / Stability Notes

- **Connection reliability matters most**: protocol caching and health checks were added specifically to avoid bad CAN initialization and stale UI state.
- **Do not assume `customPids` alone is sufficient**: manufacturer presets may be merged in automatically.
- **Keep read-only discovery safe**: PID discovery is limited to safe modes and should not wander into write/control services.
- **Storage behavior is important**: persistence code changes should preserve existing JSON and cache compatibility.

## Practical Guidance For Future Changes

- Prefer small, localized edits around the owning layer.
- If you touch OBD init or polling, check protocol caching, cached PID handling, and disconnect detection together.
- If you touch vehicle profile serialization, update both repository and settings paths.
- If you touch dashboard widgets or metrics, make sure `VehicleMetrics` and the UI consumers stay in sync.

## Quick Reference

```text
First connection: ATZ -> ATE0 -> ATL0 -> ATS0 -> ATH0 -> ATAT1 -> ATSTFF -> ATSP0
Later connections: same init flow, but use ATSP<cachedProtocol> when available
```

```kotlin
VehicleProfile(
    manufacturer = ManufacturerPidLibrary.Manufacturer.SUZUKI,
    customPids = listOf(...)
)
```

## Summary

When working in this codebase, prioritize:
1. **Connection reliability**
2. **Correct protocol caching**
3. **Safe PID discovery and polling**
4. **Profile/persistence compatibility**
5. **Keeping metrics and UI consumers aligned**
