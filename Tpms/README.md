# TpmsApp — Tyre Pressure Monitoring System for Android

An Android app written in Kotlin that intercepts Bluetooth Low Energy (BLE) advertisements
broadcast by aftermarket TPMS (Tyre Pressure Monitoring System) sensors — the type that normally
only send data to a dedicated in-car display.

## Features

- **BLE Scan** — continuously scans for nearby BLE advertisements in the background via a foreground service
- **Auto-parse** — decodes manufacturer-specific BLE advertisement bytes into pressure (kPa/PSI/Bar), temperature (°C), battery level, and alarm flags
- **Dashboard** — car top-view layout showing all four tyres with live pressure and temperature
- **Alarm indicators** — highlights low pressure, high pressure, and high temperature conditions
- **Discovery screen** — shows all nearby BLE devices with raw hex data so you can identify your sensor's MAC address
- **Sensor pairing** — assign a discovered MAC address to a tyre position (FL/FR/RL/RR)
- **Settings** — choose pressure display unit (PSI / Bar / kPa), background scanning toggle
- **Foreground service** — scanning continues when the app is in the background; system notification with Stop button

## Supported Sensor Protocol

Most Chinese OEM aftermarket TPMS sensors (commonly sold as "Solar TPMS", "External TPMS", etc.)
broadcast BLE advertisements with manufacturer-specific data in the following format:

| Byte | Content |
|------|---------|
| 0–1  | Manufacturer ID (little-endian) |
| 2    | Position hint (0=FL, 1=FR, 2=RL, 3=RR) |
| 3–4  | Pressure × 0.1 kPa (big-endian uint16) |
| 5    | Temperature + 50 offset (uint8, so subtract 50 for °C) |
| 6    | Battery 0–100 |
| 7    | Alarm flags (bit0=low, bit1=high, bit2=highTemp) |

If your sensor uses a different byte layout, edit `TpmsPacketParser.kt`.

## How to identify your sensors

1. Install and launch the app
2. Go to the **Scan** tab
3. Make sure your car is nearby with wheels rolling or sensors active
4. Watch for BLE advertisements appearing — your TPMS sensors typically appear every 5–30 seconds
5. Tap **Assign as Sensor** on a device that shows non-empty manufacturer data
6. Assign it to the correct tyre position

## Build Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Minimum device: Android 8.0 (API 26)
- Bluetooth LE hardware on the test device

## Project Structure

```
TpmsApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/tpmsapp/
│       ├── ble/
│       │   ├── BleScanner.kt          # BLE scan + advertisement filtering
│       │   └── TpmsPacketParser.kt    # Byte-level protocol parser
│       ├── data/
│       │   └── SensorRepository.kt   # SharedPreferences persistence
│       ├── model/
│       │   ├── TyreData.kt            # Data class for one tyre reading
│       │   └── SensorConfig.kt        # MAC → tyre position mapping
│       ├── service/
│       │   └── TpmsScanService.kt     # Foreground BLE scan service
│       ├── ui/
│       │   ├── MainActivity.kt
│       │   ├── DashboardFragment.kt
│       │   ├── ScanFragment.kt
│       │   ├── SensorsFragment.kt
│       │   ├── SettingsActivity.kt
│       │   ├── SettingsFragment.kt
│       │   ├── AssignSensorDialogFragment.kt
│       │   ├── MainPagerAdapter.kt
│       │   ├── adapter/
│       │   │   ├── RawAdvertisementAdapter.kt
│       │   │   └── SensorConfigAdapter.kt
│       │   └── widget/
│       │       └── TyreCardView.kt    # Custom card for one tyre
│       └── viewmodel/
│           └── TpmsViewModel.kt
└── app/src/main/res/
    ├── layout/         # XML layouts
    ├── values/         # strings, colors, themes, arrays
    ├── drawable/       # vector icons
    ├── menu/           # toolbar menu
    └── xml/            # preferences
```

## Permissions

| Permission | Reason |
|---|---|
| `BLUETOOTH_SCAN` | Scan for BLE advertisements (Android 12+) |
| `BLUETOOTH_CONNECT` | Connect to Bluetooth devices (Android 12+) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Legacy BLE (Android < 12) |
| `ACCESS_FINE_LOCATION` | Required for BLE scan on Android < 12 |
| `FOREGROUND_SERVICE` | Background scanning service |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Service type for BLE (Android 14+) |
