# OBD2App - Cascade Work Summary



## Major Features Implemented

### 1. CAN Bus Protocol Caching
Protocol caching per MAC address in PidCache
- First connection: ATSP0 with ATSTFF (max timeout)
- Subsequent connections: Use cached protocol directly (ATSP<N>), skipping probing

**Key Files Modified**:
- `BluetoothObd2Service.kt`: `buildInitCommands()` selects init sequence based on cached protocol
  - ATZ gets 2000ms delay for clones
  - CAN bus settle delay: 1500ms (auto-detect) or 500ms (cached protocol)
- `PidCache.kt`: Added `protocolNumber` field
- `AppSettings.kt`: Added `getCachedProtocol()` and updated `savePidCache()` to accept protocolNumber

### 2. Manufacturer PID Library
**Feature**: Created comprehensive manufacturer-specific PID presets for enhanced vehicle diagnostics.

**Implementation**: `ManufacturerPidLibrary.kt` at `obd/ManufacturerPidLibrary.kt`

**Supported Manufacturers**:
- **SUZUKI** (11 PIDs): Gear, Tilt, Side Stand, Clutch, Sub-Throttle, ISC, Sec Injector, IAP, Atmos Pressure, Battery Voltage, Tip-over
- **FIAT** (12 PIDs): Boost actual/target, DPF soot/regen/temp, Injector corrections 1-4, Oil degradation, EGR, Swirl
- **FORD** (8 PIDs): Trans fluid temp, Boost actual/target, DPF soot/regen, Rail pressure target, Injection qty, Battery SoC
- **JAGUAR** (10 PIDs): Air suspension FL/FR/RL/RR, SC Boost, Damper mode, Yaw rate (header 760), Lat accel (header 760), Trans temp, Turbo boost
- **BOSCH_GENERIC** (14 PIDs): Lambda actual/target, Knock retard 1-4, Misfire 1-4, Ignition advance, Injection time, Boost actual/target

**Architecture**:
- Mode 22 PIDs leverage existing CustomPid infrastructure
- VehicleProfile has `manufacturer` field and `effectiveCustomPids` property that merges user custom PIDs with manufacturer presets
- `BluetoothObd2Service.startPolling()` uses effectiveCustomPids

### 3. OBD Disconnect Detection & Reconnection
**Problem**: Connection state never changes to DISCONNECTED when adapter loses connection (engine shutdown, adapter unplugged), causing UI to show stale data.

**Solution**: Hybrid approach with exception counter and socket health checks

**Implementation**:
- Track consecutive failed PID reads with threshold (10 failures ~2-3 seconds)
- Socket health checks every 10 cycles
- Proper exception logging instead of silent catching
- Automatic connection state change to ERROR, triggering reconnection

**Key Files**:
- `BluetoothObd2Service.kt`: Enhanced `startPolling()` with failure tracking
- Added `consecutiveFailures` counter and `MAX_CONSECUTIVE_FAILURES` threshold
- Socket health check method `isSocketHealthy()`

### 4. Android 16 BLE Scanning Fixes
**Problem**: BLE scanning failures on Android 16 (Pixel 7) where unnamed/MAC-only devices and custom OBD BLE adapters not detected.

**Solution**: Updated permissions, scan settings, and added comprehensive raw logging

**Implementation**:
- Updated `compileSdk` and `targetSdk` to 35 (Android 16)
- Removed `neverForLocation` flag from BLUETOOTH_SCAN permission
- Enhanced ScanSettings for unnamed device detection:
  - `CALLBACK_TYPE_ALL_MATCHES`
  - `MATCH_MODE_AGGRESSIVE`
  - `MATCH_NUM_MAX_ADVERTISEMENT`
- Complete raw advertisement payload logging for reverse-engineering

### 5. Enhanced OBD2 Command Registry
**Update**: `Obd2CommandRegistry.kt` expanded to ~113 standard Mode 01 PIDs (0x01-0x7C)

**Features**:
- Extended PIDs 0x64-0x7C for turbo, DPF, EGT, multi-sensor support
- Comprehensive coverage of standard OBD2 parameters

### 6. PID Discovery Feature
**Feature**: Brute force PID discovery to safely scan for custom PIDs supported by vehicle ECUs

**Implementation**:
- Safe scanning limited to read-only modes (21, 22, 23)
- Skip actuator/control PID ranges for safety
- 100ms delay between commands with user cancellation
- Console UI with real-time progress and results
- Auto-suggest formulas based on response patterns
- "Add All" functionality for discovered PIDs

**Key Files**:
- `PidDiscoveryService.kt`: Discovery logic
- `PidDiscoverySheet.kt`: Discovery UI
- Integration with `CustomPidListSheet.kt`

### 7. Dashboard Redesign & Widget Library
**Problem**: Hard-coded widget ranges, limited metric selection, poor add-widget UX

**Solution**: Reusable widget library with multi-step configuration wizard

**Features**:
- Metric-agnostic widgets with configurable ranges/ticks
- Per-widget gauge range, warning thresholds, decimal places
- 21+ OBD PIDs + GPS metrics available (filtered by ECU support)
- Multi-step wizard: Style selection, Metric binding, Scale configuration
- Visual improvements: digital fonts, animations, tick marks, warning zones

**Key Components**:
- `DashboardWidget` model extended with range/tick/unit fields
- `MetricDefaults.kt`: Per-metric sensible defaults
- `AddWidgetWizardSheet`: 3-step ViewPager2 wizard
- Enhanced widget views: `DialView`, `SevenSegmentView`, `BarGaugeView`, `TemperatureGaugeView`


## Key Components

### Core Services
- `BluetoothObd2Service.kt`: Main OBD2 communication service with disconnect detection
- `PidCache.kt`: Protocol and data caching
- `AppSettings.kt`: Settings and persistence
- `PidDiscoveryService.kt`: Safe PID discovery functionality

### PID Management
- `ManufacturerPidLibrary.kt`: Manufacturer-specific PID definitions
- `Obd2CommandRegistry.kt`: Standard OBD2 command registry (113 PIDs)
- `VehicleProfile.kt`: Vehicle configuration and PID merging
- `MetricDefaults.kt`: Per-metric range and display defaults

### Dashboard & UI
- `DashboardWidget.kt`: Enhanced widget model with configurable ranges
- `AddWidgetWizardSheet.kt`: Multi-step widget configuration wizard
- Enhanced widget views: `DialView`, `SevenSegmentView`, `BarGaugeView`, `TemperatureGaugeView`
- `PidDiscoverySheet.kt`: PID discovery UI


- Comprehensive permission handling for modern Android versions

