# OBD2 Viewer

A responsive Kotlin Android app that connects to an ELM327-compatible OBD-II Bluetooth adapter, discovers which parameters the vehicle's ECU supports, displays live diagnostic data, and records comprehensive trip logs with GPS integration.

## Features

- **Bluetooth Connection** — Scan paired devices, tap to connect via RFCOMM/SPP
- **PID Auto-Discovery** — Queries standard bitmask PIDs (0100/0120/0140/0160) to determine which parameters the ECU supports before polling
- **Live Data Table** — Scrollable table showing all supported OBD-II values with parameter name, live value, and unit
- **Dashboard Gauges** — At-a-glance cards for RPM, Speed, Coolant Temp, Throttle, Engine Load, and Fuel Level
- **Trip Recording** — Start/pause/stop trips with comprehensive logging to JSON format including OBD-II data, GPS coordinates, and accelerometer readings
- **GPS Integration** — Real-time location tracking with speed and distance calculation during trips
- **Accelerometer Support** — Captures vehicle acceleration data for road quality analysis
- **Foreground Service** — Persistent background recording during trips with live notification
- **Screen Wake Lock** — Automatically keeps screen on during active trips
- **Runtime Permissions** — Requests Bluetooth and location permissions at app start; prompts to enable Bluetooth if off
- **Responsive Layout** — Adapts between bottom navigation (phone) and navigation drawer (tablet)
- **5-Screen Navigation** — ViewPager2-based navigation with Trip, Connect, Dashboards, Details, and Settings screens

## Screens

| Screen | Description |
|--------|-------------|
| **Trip** | Main trip management screen with start/pause/stop controls, real-time OBD-II/GPS/accelerometer indicators, trip duration, distance, fuel metrics, and gravity orientation display. |
| **Connect** | Lists paired Bluetooth devices. Tap a device to connect to the OBD-II adapter. |
| **Dashboards** | Customizable dashboard layouts with gauge cards showing key metrics with color-coded values. |
| **Details** | Full table of all supported OBD-II parameters with columns: Parameter, Value, Unit. |
| **Settings** | Configuration for vehicle profiles, units, and app preferences. |

## Supported OBD-II Parameters

21 standard Mode 01 PIDs with SAE J1979 formulas:

| PID | Parameter | Unit |
|------|-----------|------|
| 0104 | Calculated Engine Load | % |
| 0105 | Coolant Temperature | °C |
| 0106 | Short-term Fuel Trim (Bank 1) | % |
| 0107 | Long-term Fuel Trim (Bank 1) | % |
| 010A | Fuel Pressure | kPa |
| 010B | Intake Manifold Pressure | kPa |
| 010C | Engine RPM | rpm |
| 010D | Vehicle Speed | km/h |
| 010E | Timing Advance | ° before TDC |
| 010F | Intake Air Temperature | °C |
| 0110 | MAF Air Flow Rate | g/s |
| 0111 | Throttle Position | % |
| 0114 | O2 Sensor Voltage (B1S1) | V |
| 011F | Run Time Since Engine Start | sec |
| 0121 | Distance with MIL On | km |
| 012F | Fuel Tank Level | % |
| 0131 | Distance Since Codes Cleared | km |
| 0133 | Barometric Pressure | kPa |
| 0142 | Control Module Voltage | V |
| 0146 | Ambient Air Temperature | °C |
| 015C | Engine Oil Temperature | °C |
| 015E | Engine Fuel Rate | L/h |

> Only PIDs that the vehicle's ECU reports as supported (via bitmask discovery) are polled.

## Architecture

```
com.sj.obd2app
├── metrics/
│   ├── MetricsCalculator.kt        # Core metrics engine with trip state management
│   ├── MetricsLogger.kt            # JSON trip logging with MediaStore integration
│   ├── VehicleMetrics.kt           # Data class for OBD-II sensor readings
│   ├── TripPhase.kt                # Trip lifecycle (IDLE/RUNNING/PAUSED)
│   ├── AccelEngine.kt              # Accelerometer data processing
│   └── FuelCalculations.kt         # Fuel efficiency and cost calculations
├── obd/
│   ├── Obd2Command.kt              # Data class: PID definition + parse lambda
│   ├── Obd2CommandRegistry.kt     # Registry of 21 standard PIDs
│   ├── Obd2Service.kt              # Bluetooth connection and polling
│   └── Obd2ServiceProvider.kt     # Service provider with mock mode support
├── gps/
│   └── GpsDataSource.kt            # GPS tracking with distance calculation
├── service/
│   └── TripForegroundService.kt    # Background service for trip recording
├── ui/
│   ├── trip/                       # Trip screen with recording controls
│   ├── connect/                    # Connect screen (paired BT devices list)
│   ├── dashboard/                  # Customizable dashboard layouts
│   ├── details/                    # Details screen (full data table)
│   └── settings/                   # Settings screen (vehicle profiles, preferences)
└── MainActivity.kt                 # ViewPager2 navigation, permissions, BT enable
```

## Tech Stack

- **Language**: Kotlin (via AGP 9.0.1 built-in support)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **UI**: View Binding, RecyclerView, ViewPager2, Material Design Components
- **Architecture**: MVVM (ViewModel + LiveData + StateFlow)
- **Async**: Kotlin Coroutines
- **Bluetooth**: Classic BT (RFCOMM/SPP) — no third-party OBD library
- **GPS**: FusedLocationProviderClient for location tracking
- **Sensors**: Accelerometer integration for road quality analysis
- **Storage**: MediaStore API for JSON trip log sharing
- **Services**: Foreground service for background trip recording

## Prerequisites

- Android device running Android 8.0+
- ELM327-compatible OBD-II Bluetooth adapter (Classic BT, not BLE)
- Adapter paired with the Android device via system Bluetooth settings
- Location permissions for GPS tracking during trips

## Trip Recording

The app records comprehensive trip data to JSON files with the following structure:

- **File Format**: `<ProfileName>_obdlog_<YYYY-MM-DD_HHmmss>.json`
- **Data Captured**:
  - OBD-II sensor readings (RPM, speed, temperature, fuel levels, etc.)
  - GPS coordinates, speed, and distance calculations
  - Accelerometer data for road quality analysis
  - Trip timestamps and duration
  - Vehicle profile and calibration settings
- **Storage**: Saved via MediaStore API for easy sharing
- **Background Recording**: Foreground service ensures continuous data collection

## Navigation

The app uses ViewPager2 with 5 main screens:

1. **Trip** (index 0) - Main recording interface with live indicators
2. **Connect** (index 1) - Bluetooth device pairing and connection
3. **Dashboards** (index 2) - Customizable gauge layouts
4. **Details** (index 3) - Complete OBD-II parameter table
5. **Settings** (index 4) - Vehicle profiles and app configuration

Navigation between screens is handled through:
- Bottom navigation bar on phones
- Navigation drawer on tablets
- Overflow menu in the top bar for quick access
- Automatic navigation after successful OBD-II connection

## Build

Open in Android Studio and build, or from the command line:

```bash
./gradlew assembleDebug
```

The output APK is named `OBD2Viewer-debug.apk`.

## Permissions

| Permission | Purpose |
|------------|---------|
| `BLUETOOTH` | Legacy BT access |
| `BLUETOOTH_ADMIN` | Legacy BT management |
| `BLUETOOTH_CONNECT` | Connect to paired devices (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for devices (Android 12+) |
| `ACCESS_FINE_LOCATION` | Required for BT scanning and GPS tracking (Android < 12) |
| `ACCESS_COARSE_LOCATION` | Required for BT scanning (Android < 12) |
| `FOREGROUND_SERVICE` | Background trip recording service |

## Mock Mode

For development and testing, the app includes a mock mode that simulates OBD-II data from `assets/mock_obd2_data.json`. Enable by setting `USE_MOCK_OBD2 = true` in `MainActivity.kt`. This bypasses Bluetooth requirements and allows testing of trip recording and UI functionality.

## License

Private — All rights reserved.
