# OBD2 Viewer

A responsive Kotlin Android app that connects to an ELM327-compatible OBD-II Bluetooth adapter, discovers which parameters the vehicle's ECU supports, and displays live diagnostic data.

## Features

- **Bluetooth Connection** — Scan paired devices, tap to connect via RFCOMM/SPP
- **PID Auto-Discovery** — Queries standard bitmask PIDs (0100/0120/0140/0160) to determine which parameters the ECU supports before polling
- **Live Data Table** — Scrollable table showing all supported OBD-II values with parameter name, live value, and unit
- **Dashboard Gauges** — At-a-glance cards for RPM, Speed, Coolant Temp, Throttle, Engine Load, and Fuel Level
- **Runtime Permissions** — Requests Bluetooth and location permissions at app start; prompts to enable Bluetooth if off
- **Responsive Layout** — Adapts between bottom navigation (phone) and navigation drawer (tablet)

## Screens

| Screen | Description |
|--------|-------------|
| **Connect** | Lists paired Bluetooth devices. Tap a device to connect to the OBD-II adapter. |
| **Dashboard** | Six gauge cards showing key metrics with color-coded values. |
| **Details** | Full table of all supported OBD-II parameters with columns: Parameter, Value, Unit. |
| **Settings** | Placeholder for future configuration (polling interval, units, auto-connect). |

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
├── obd/
│   ├── Obd2Command.kt            # Data class: PID definition + parse lambda
│   ├── Obd2CommandRegistry.kt     # Registry of 21 standard PIDs
│   ├── Obd2DataItem.kt            # Data class: single reading (name, value, unit)
│   └── BluetoothObd2Service.kt    # Singleton: BT connection, ELM327 init,
│                                  #   PID discovery, continuous polling, StateFlow
├── ui/
│   ├── connect/                   # Connect screen (paired BT devices list)
│   ├── dashboard/                 # Dashboard screen (6 gauge cards)
│   ├── details/                   # Details screen (full data table)
│   └── settings/                  # Settings screen (placeholder)
└── MainActivity.kt               # Navigation, permissions, BT enable prompt
```

## Tech Stack

- **Language**: Kotlin (via AGP 9.0.1 built-in support)
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36
- **UI**: View Binding, RecyclerView, Material Design Components
- **Architecture**: MVVM (ViewModel + LiveData + StateFlow)
- **Async**: Kotlin Coroutines
- **Bluetooth**: Classic BT (RFCOMM/SPP) — no third-party OBD library

## Prerequisites

- Android device running Android 8.0+
- ELM327-compatible OBD-II Bluetooth adapter (Classic BT, not BLE)
- Adapter paired with the Android device via system Bluetooth settings

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
| `ACCESS_FINE_LOCATION` | Required for BT scanning (Android < 12) |
| `ACCESS_COARSE_LOCATION` | Required for BT scanning (Android < 12) |

## License

Private — All rights reserved.
