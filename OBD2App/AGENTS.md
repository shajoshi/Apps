# OBD2App - Agent Context Guide

This document provides comprehensive context for any LLM/agent working on the OBD2App Android vehicle diagnostics and trip computer application.

## Quick Overview

**OBD2App** is a sophisticated Kotlin Android application that connects to ELM327-compatible OBD-II Bluetooth adapters, reads vehicle diagnostic data, displays customizable dashboards, and records comprehensive trip logs with GPS and accelerometer integration.

**Core Purpose**: Vehicle diagnostics, performance monitoring, and trip computer functionality with real-time data visualization.

---

## Architecture at a Glance

### Tech Stack
- **Language**: Kotlin (View Binding, not Compose)
- **Min SDK**: 26 (Android 8.0), **Target SDK**: 36
- **Architecture**: MVVM with ViewModel + LiveData + StateFlow
- **Async**: Kotlin Coroutines
- **Bluetooth**: Classic RFCOMM/SPP + BLE GATT support
- **Storage**: SAF (DocumentFile) + SharedPreferences fallback
- **Navigation**: ViewPager2 with 7 tabs + Fragment back-stack

### Design Patterns Used
- **Singleton**: Thread-safe double-check locking for service objects
- **Strategy Pattern**: OBD2 service (real/mock) and transport (Classic/BLE)
- **Observer Pattern**: StateFlow/LiveData for reactive UI updates
- **Repository Pattern**: Data persistence abstraction
- **MVVM**: ViewModel + StateFlow for UI state management
- **Mediator/Orchestrator**: DataOrchestrator combines multiple data flows
- **State Machine**: Trip phase lifecycle (IDLE/RUNNING/PAUSED)
- **Template Method**: Fixed connection initialization sequence
- **Adapter Pattern**: ViewPager2 + RecyclerView implementations

### Key Components
```
MainActivity (single activity)
  ViewPager2 (7 tabs: Connect, Trip, Dashboards, Details, Trip Summary, Map View, Settings)
    MetricsCalculator (singleton) - central data processing
      DataOrchestrator - combines OBD2 + GPS + accelerometer
    Obd2ServiceProvider - factory for real/mock services
    BluetoothObd2Service - ELM327 communication
    GpsDataSource - location tracking
    AccelerometerSource - road quality analysis
    ObdStateManager - centralized OBD state management
    ObdConnectionManager - auto-reconnection logic
```

---

## Core Data Flow

### 1. Data Collection Pipeline
```
ELM327 Adapter (Bluetooth) 
  BluetoothObd2Service (tiered polling)
    Obd2DataItem[] (raw PID readings)
  DataOrchestrator (combine + debounce 100ms)
    MetricsCalculator.calculate()
      VehicleMetrics (50+ computed fields)
    UI Fragments (observe StateFlow)
```

### 2. Trip Recording Pipeline
```
Trip Start (RUNNING state)
  MetricsLogger (JSON file per trip)
    Header: vehicle profile, settings, timestamps
    Samples: OBD2 + GPS + accelerometer (1 Hz)
  MediaStore API (user-accessible storage)
```

---

## Key Business Logic

### OBD2 Communication
- **Protocol Caching**: Per-MAC address protocol detection prevents CAN bus errors
- **Tiered Polling**: Fast tier (RPM, Speed, MAF) every cycle, slow tier every 5th cycle
- **Custom PIDs**: User-defined extended PIDs with formula parsing
- **Auto-Reconnection**: Adaptive backoff during active trips (10s × 5, then 60s)
- **Health Monitoring**: Consecutive failure counter + socket health checks

### OBD-II Service Modes
**Read-Only Services (Safe):**
- **01**: Current data (live sensor values)
- **02**: Freeze frame (DTC snapshot)
- **03**: Stored DTCs
- **07**: Pending DTCs
- **09**: Vehicle info (VIN, calibration IDs)
- **21/22/23**: Extended/manufacturer data (PID discovery uses these)

**Write/Control Services (Dangerous - avoided):**
- **04**: Clear DTCs (resets emissions readiness)
- **08**: Control systems (actuates components)
- **2E/2F/31**: UDS write operations (require security access)

### Response Format
```
Request: 010C (Mode 01, PID 0C - RPM)
CAN Frame: ID=7DF, Data=[02 01 0C 00 00 00 00 00]
Response: ID=7E8, Data=[04 41 0C 0B E0 00 00 00]
ELM327 Output: 410C0BE0
Parse: 41=01+0x40, 0C=PID echo, 0BE0=data bytes
Result: RPM = (0x0B×256 + 0xE0) / 4 = 760 RPM
```

### Fuel Calculation (Diesel Boost Correction)
**Critical Feature**: Turbocharged diesel engines need dynamic AFR correction
- Standard MAF calculation assumes 14.5:1 AFR (50-70% overestimation)
- **Correction factors**: Boost pressure + RPM + engine load
- **Result**: 50-70% accuracy improvement for diesel vehicles

### Dashboard System
- **Widget Types**: Dial, 7-Segment, Bar (H/V), Numeric, Temperature Arc
- **Configurable**: Range, ticks, warnings, decimals per widget
- **Metrics**: 21+ OBD PIDs + GPS + derived metrics
- **Layouts**: JSON serialization with color schemes

---

## Screen Navigation & Lifecycle

### ViewPager2 Layout (7 Pages)
```
Swipeable Pages (0-3): Connect, Trip, Dashboards, Details
Menu-Only Pages (4-6): Trip Summary, Map View, Settings
```

**Navigation Rules:**
- **Details page (3)**: Blocks leftward swipe to prevent accidental Trip Summary access
- **Trip Summary (4)**: Blocked during active trips, menu-only access
- **Map View (5)**: Only accessible from Trip Summary, blocked from other pages
- **Settings (6)**: Blocked during active trips, menu-only access
- **Dashboard Edit Mode**: Disables ViewPager swipe globally

### Screen-by-Screen Breakdown

**Page 0 - Connect**: 
- Paired/discovered Bluetooth devices
- Connection status and force BLE toggle
- Real-time connection log

**Page 1 - Trip**: 
- Start/Pause/Stop trip controls
- Live metrics display (OBD, GPS, accelerometer)
- Gravity vector capture for road quality

**Page 2 - Dashboards**: 
- Nested NavHost (LayoutList <-> DashboardEditor)
- Customizable gauge widgets with drag/resize
- Live data binding during editing

**Page 3 - Details**: 
- Full PID table with collapsible sections
- Vehicle metrics summary
- Cached data display when disconnected

**Page 4 - Trip Summary**: 
- Trip log list with parsed metrics
- Export/share functionality
- Map view navigation

**Page 5 - Map View**: 
- GPS trace visualization
- Only from Trip Summary

**Page 6 - Settings**: 
- Vehicle profile CRUD
- Connection toggles (OBD, auto-connect, logging)
- Mock scenario selector (debug mode)

## Critical Implementation Details

### Connection Management
```kotlin
// Protocol caching prevents U0009 CAN bus errors
firstConnection: ATSP0 + ATSTFF (max timeout)
subsequentConnections: ATSP<cachedProtocol>

// Health monitoring detects disconnects within 2-3 seconds
consecutiveFailures >= 10 -> state = ERROR
```

### App Lifecycle & Startup Sequence
```kotlin
MainActivity.onCreate()
  1. DataMigration.checkExistingData() - Toast if .obd data found
  2. ObdStateManager.initialize(autoConnect, obdEnabled)
  3. Obd2ServiceProvider.initMock(context) // if mock mode
  4. ViewPager2 setup with 7 pages, offscreenPageLimit = 3
  5. MetricsCalculator.getInstance() - triggers DataOrchestrator
  6. GpsDataSource.start() - location tracking
  7. Trip phase observer for screen wake lock
  8. Dashboard edit mode observer for swipe lock
```

**Foreground Service Integration:**
- `TripForegroundService` runs during active trips
- Shows persistent notification with trip status
- `START_STICKY` ensures service restart
- Automatically stops when trip phase = IDLE

### Custom PID System
```kotlin
// Formula parser supports Torque Pro notation
formula: "((A*256)+B)/100"
variables: A-H = data bytes 0-7
operations: +, -, *, /, ()

// PID discovery scans safely
modes: 21, 22, 23 (read-only)
headers: 7E0, 7E1, 7E2, 760, 7E4
safety: skips actuator PID ranges
```

### Vehicle Profiles
```kotlin
VehicleProfile(
  fuelType: Petrol/E20/Diesel/CNG,
  tankCapacityL, fuelPricePerLitre,
  enginePowerBhp, vehicleMassKg,
  customPids: List<CustomPid>
)

// Storage: JSON files in .obd/profiles/
// Active profile tracked in AppSettings
```

---

## File Structure Deep Dive

### Core Services (`obd/`)
- `BluetoothObd2Service.kt` - Main ELM327 communication with tiered polling
- `Obd2CommandRegistry.kt` - 113 standard Mode 01 PIDs with parse lambdas
- `PidDiscoveryService.kt` - Brute force PID scanner (modes 21/22/23)
- `ObdConnectionManager.kt` - Auto-reconnection with adaptive backoff
- `ObdStateManager.kt` - Centralized OBD state (object singleton)
- `Elm327Transport.kt` - Transport interface (Classic/BLE abstraction)
- `ClassicBluetoothTransport.kt` - RFCOMM/SPP implementation
- `BleTransport.kt` - GATT implementation with service discovery
- `PidFormulaParser.kt` - Recursive-descent arithmetic evaluator
- `MockObd2Service.kt` - Test implementation with scenarios

### Data Processing (`metrics/`)
- `MetricsCalculator.kt` - Central singleton, trip state machine, orchestrator
- `DataOrchestrator.kt` - Combines OBD+GPS+accel flows with 100ms debounce
- `FuelCalculator.kt` - Diesel boost correction, efficiency calculations
- `PowerCalculator.kt` - Accelerometer, thermodynamic, OBD torque methods
- `AccelEngine.kt` - Pure JVM vehicle-frame calculations (no Android deps)
- `TripState.kt` - Mutable trip accumulators (distance, fuel, time)
- `VehicleMetrics.kt` - Immutable snapshot (50+ fields)

### Storage & Settings (`settings/`, `storage/`)
- `AppSettings.kt` - Global settings with pending-settings pattern
- `VehicleProfileRepository.kt` - CRUD for profiles + PID management
- `AppDataDirectory.kt` - SAF vs internal storage abstraction
- `LayoutRepository.kt` - Dashboard layout JSON serialization

### UI Layer (`ui/`)
- `trip/TripFragment.kt` - Main trip computer interface
- `dashboard/` - Customizable gauge layouts with nested NavHost
- `settings/` - Vehicle profiles, custom PIDs, mock scenarios
- `connect/ConnectFragment.kt` - Bluetooth device management
- `details/DetailsFragment.kt` - Full PID table with sections

### Data Models
- `VehicleMetrics.kt` - Immutable snapshot (50+ fields)
- `DashboardWidget.kt` - Widget instance with range config
- `CustomPid.kt` - User-defined extended PID
- `VehicleProfile.kt` - Vehicle configuration
- `DashboardLayout.kt` - Layout with color schemes and orientation

### Singleton Implementation Pattern
All major services use thread-safe double-check locking:
```kotlin
companion object {
    @Volatile private var INSTANCE: T? = null
    fun getInstance(context: Context): T =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: T(context.applicationContext).also { INSTANCE = it }
        }
}
```

**Singletons:**
- `MetricsCalculator` - Central data processing
- `BluetoothObd2Service` - ELM327 communication
- `GpsDataSource` - Location tracking
- `VehicleProfileRepository` - Profile management
- `AccelerometerSource` - Sensor data
- `ObdConnectionManager` - Auto-reconnection
- `PidDiscoveryService` - PID scanning

**Object Singletons (stateless):**
- `AppSettings` - Settings access
- `ObdStateManager` - OBD mode/connection state
- `Obd2ServiceProvider` - Service factory

---

## Key Constraints & Gotchas

### Android-Specific
- **Permissions**: BT_CONNECT, BT_SCAN, ACCESS_FINE_LOCATION required
- **Foreground Service**: Required for background trip recording
- **SAF Storage**: Uses DocumentFile for user-accessible storage
- **Wake Lock**: Keeps screen on during active trips
- **ViewPager2 Limits**: offscreenPageLimit = 3 for memory management
- **Fragment Lifecycle**: StateFlow collection in viewLifecycleOwner.lifecycleScope

### OBD2 Protocol
- **ELM327 Timing**: ATAT1 adaptive timing, no explicit delays needed
- **Protocol Detection**: ATSP0 auto-detect can trigger U0009 on some ECUs
- **Custom PID Headers**: Must use AT SH to target specific ECUs
- **Response Format**: Mode+0x40 + PID + data bytes
- **AT Commands**: ATE0/ATL0/ATS0 critical for clean PID discovery
- **Multi-frame**: ELM327 handles ISO-TP automatically

### Performance
- **Polling Frequency**: Fast tier every cycle, slow tier every 5th
- **Debounce**: 100ms combine debounce to prevent UI spam
- **Memory**: Trip logs streamed to JSON, not held in memory
- **Coroutines**: All heavy work on Dispatchers.Default/IO
- **StateFlow**: Hot flows with replay=1 for new subscribers

### Data Accuracy
- **GPS vs OBD Speed**: Cross-check for validation (spdDiffKmh)
- **Temperature Conversion**: Standard A-40 for most sensors
- **MAF Fuel Calculation**: Requires diesel boost correction for accuracy
- **Accelerometer**: Gravity vector capture for vehicle-frame basis
- **BLE vs Classic**: BLE may have higher latency

### Navigation Constraints
- **Settings Access**: Blocked during active trips (Toast warning)
- **Details Swipe**: Leftward swipe blocked to prevent Trip Summary access
- **Dashboard Edit**: Global ViewPager swipe disabled during edit
- **Menu Dependencies**: Map View only from Trip Summary

### Storage Architecture
- **Dual Backend**: SAF (DocumentFile) vs app-private fallback
- **Migration**: Automatic data preservation on reinstall
- **JSON Format**: All persisted data uses Gson serialization
- **Trip Logs**: MediaStore API for user-accessible sharing

## Common Workflows

### Adding New Custom PIDs
1. Research vehicle-specific PIDs (forums, service manuals)
2. Test with terminal app (Serial Bluetooth Terminal)
3. Add via Settings -> Vehicle Profile -> Manage Custom PIDs
4. Configure: header, mode, PID, bytes, formula, unit
5. Test live data and adjust formula as needed

### Creating Dashboard Widgets
1. Dashboards tab -> Create Layout
2. Add Widget -> Choose type -> Bind metric
3. Configure range, ticks, warnings
4. Position and size on grid
5. Save layout

### Trip Recording Best Practices
1. Start trip after OBD connection established
2. Wait for GPS lock (satellite count > 4)
3. Monitor connection health during trip
4. Review trip log JSON for data completeness

---

## Critical Business Rules

### Fuel Calculation Priority
1. **PID 015E** (direct fuel rate) - most accurate
2. **MAF-based with diesel correction** - for turbo diesel
3. **Standard MAF calculation** - fallback for petrol

### Connection State Management
- **IDLE**: No active trip
- **RUNNING**: Recording trip, auto-reconnect enabled
- **PAUSED**: Trip paused, reconnection monitoring active
- **ERROR**: Connection lost, trigger reconnection

### Settings Persistence
- **Vehicle Profiles**: JSON files in `.obd/profiles/`
- **Dashboard Layouts**: JSON files in `.obd/layouts/`
- **App Settings**: JSON in `.obd/` or SharedPreferences fallback

---

## Performance Optimizations

### OBD2 Polling
- **Fast Tier**: RPM, Speed, MAF, Throttle, Fuel Rate
- **Slow Tier**: All other supported PIDs
- **Custom PIDs**: Grouped by header to minimize AT SH switches

### UI Updates
- **Debounce**: 100ms to prevent excessive redraws
- **StateFlow**: Reactive updates for all UI components
- **Background Processing**: All calculations on Dispatchers.Default

### Memory Management
- **Trip Logs**: Streamed directly to JSON files
- **Data Buffers**: Accelerometer buffer drained each cycle
- **Singleton Pattern**: Shared instances for heavy objects

---

## Integration Points

### Bluetooth Stack
- **Classic**: RFCOMM SPP UUID (00001101-0000-1000-8000-00805F9B34FB)
- **BLE**: Generic ELM327 service or Nordic UART Service
- **Auto-Detection**: Based on BluetoothDevice.type

### GPS Integration
- **Provider**: FusedLocationProviderClient
- **Accuracy**: PRIORITY_HIGH_ACCURACY, 1s interval
- **Geoid Correction**: WGS84 ellipsoid to MSL altitude

### Storage Framework
- **Primary**: SAF DocumentFile (user-selected folder)
- **Fallback**: App-private internal storage
- **Migration**: Automatic data preservation on reinstall

---

## Security & Privacy

### Data Handling
- **Local Storage**: All data stored locally on device
- **User Access**: Trip logs shared via MediaStore
- **No Network**: No cloud connectivity or data transmission

### Permissions
- **Bluetooth**: Required for OBD2 communication
- **Location**: Required for GPS tracking and BLE scanning
- **Storage**: Required for trip log export

---

## Future Development Context

### Extensibility Points
- **New Widget Types**: Extend DashboardWidget system
- **Additional Metrics**: Add to VehicleMetrics and MetricsCalculator
- **Manufacturer PIDs**: Extend ManufacturerPidLibrary
- **Export Formats**: Add new trip log exporters

### Architecture Evolution
- **Compose Migration**: Consider for future UI updates
- **Dependency Injection**: Consider manual singletons replacement
- **Database**: Consider Room for complex data relationships

---

## Quick Reference Commands

### OBD2 Terminal Commands
```bash
ATZ          # Reset adapter
AT E0        # Echo off
AT SP 0      # Auto protocol detect
AT SH 7E0    # Set ECU header
010C         # Engine RPM
220456       # Custom PID (Mode 22, PID 0456)
```

### Custom PID Formula Examples
```kotlin
"A-40"                    # Temperature conversion
"((A*256)+B)/100"         # 16-bit scaled value
"A*0.5"                   # Simple percentage
"((A*256)+B)-32768"       # Signed 16-bit with offset
```

### Dashboard Configuration
```kotlin
DashboardWidget(
  type = WidgetType.DIAL,
  metric = DashboardMetric.Obd2Pid("010C", "Engine RPM", "rpm"),
  rangeMin = 0f, rangeMax = 8000f,
  majorTickInterval = 1000f,
  warningThreshold = 6000f
)
```

---

## Conclusion

This guide provides comprehensive context for understanding and working with the OBD2App codebase. The application combines sophisticated vehicle diagnostics with user-friendly interface design, emphasizing data accuracy, connection reliability, and extensibility.

**Key strengths**: Diesel boost correction accuracy, robust connection management, customizable dashboards, comprehensive trip logging, ELM327 protocol expertise, extensive design pattern implementation.

**When working on this codebase**, prioritize:
1. **Connection reliability** - Protocol caching, health monitoring, auto-reconnection
2. **Data accuracy** - Especially fuel calculations and sensor validation
3. **Clean architecture** - Maintain separation between data processing (MetricsCalculator) and UI presentation
4. **ELM327 expertise** - Understanding the 3-layer architecture and OBD-II protocol nuances
5. **Design patterns** - Follow established singleton, strategy, and observer patterns

The sophisticated architecture with proper separation of concerns, comprehensive error handling, and extensive testing infrastructure makes this a robust foundation for vehicle diagnostics development.
