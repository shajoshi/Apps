# TPMS Monitor - User Guide

## Overview

TPMS Monitor is an Android app that displays live tyre pressure and temperature data from aftermarket TPMS (Tyre Pressure Monitoring System) sensors. The app intercepts Bluetooth Low Energy (BLE) advertisements broadcast by your TPMS sensors and presents the data in an easy-to-read dashboard format.

### Key Features

- **Live Dashboard** - Real-time pressure and temperature for all four tyres
- **BLE Scanning** - Continuous background scanning for sensor data
- **Sensor Management** - Easy pairing and configuration of sensors to tyre positions
- **Alarm Indicators** - Visual warnings for low pressure, high pressure, and high temperature
- **Customizable Units** - Display pressure in PSI, Bar, or kPa
- **Background Operation** - Keeps monitoring when app is minimized

### Supported Sensors

The app supports most Chinese OEM aftermarket TPMS sensors (commonly sold as "Solar TPMS", "External TPMS", etc.) that broadcast BLE advertisements with manufacturer-specific data.

## Getting Started

### Installation & Permissions

1. Install the TPMS Monitor app on your Android device (Android 8.0+ required)
2. Launch the app - it will automatically request necessary permissions:
   - **Bluetooth permissions** - Required for scanning TPMS sensors
   - **Location permissions** - Required for BLE scanning on Android < 12
   - **Foreground service** - Allows background scanning

3. Enable Bluetooth if prompted

### First-Time Setup

1. **Start Scanning** - Tap the scan icon (▶️) in the toolbar to begin scanning for sensors
2. **Discover Sensors** - Go to the **Scan** tab to see nearby BLE devices
3. **Pair Sensors** - Assign discovered sensors to their correct tyre positions
4. **View Dashboard** - Switch to the **Dashboard** tab to see live data

## Main Interface

The app has three main tabs accessible from the bottom of the screen:

### 🚗 Dashboard Tab

The main view showing your vehicle's tyre status:

- **Four tyre cards** arranged in car layout (FL, FR, RL, RR)
- **Pressure display** in your selected unit
- **Temperature** in Celsius
- **Battery level** indicator
- **Alarm status** with color-coded warnings:
  - 🔴 Red - Critical alarm (low/high pressure, high temp)
  - 🟡 Yellow - Warning condition
  - 🟢 Green - Normal operation
- **"No signal"** shown when sensor data is not available

### 📡 Scan Tab

Discover and identify nearby TPMS sensors:

- **Live device list** showing all BLE advertisements
- **Device information** including MAC address and name
- **Raw hex data** for technical identification
- **"Assign as Sensor"** button for pairing
- **Real-time updates** as new devices are detected

### ⚙️ Sensors Tab

Manage your sensor configurations:

- **Tyre position list** (FL, FR, RL, RR)
- **Current assignment status** for each position
- **"Assign" button** to pair new sensors
- **"Remove sensor"** to unpair
- **"Reassign"** to change sensor position

## Sensor Setup Process

### Step 1: Discover Your Sensors

1. Make sure your car is nearby with wheels rolling or sensors active
2. Go to the **Scan** tab
3. Watch for devices appearing (sensors typically broadcast every 5-30 seconds)
4. Look for devices with non-empty manufacturer data

### Step 2: Identify Sensor Positions

TPMS sensors often include position hints in their data:
- Position 0 = Front Left (FL)
- Position 1 = Front Right (FR)  
- Position 2 = Rear Left (RL)
- Position 3 = Rear Right (RR)

### Step 3: Assign Sensors to Tyres

1. In the **Scan** tab, find your sensor in the device list
2. Tap **"Assign as Sensor"**
3. Select the correct tyre position from the dialog
4. Confirm the assignment

Alternative method using **Sensors** tab:
1. Go to **Sensors** tab
2. Tap **"Assign"** next to the desired tyre position
3. Select from discovered sensors or enter MAC address manually

### Step 4: Verify on Dashboard

Switch to the **Dashboard** tab to confirm:
- Sensor data appears in the correct tyre position
- Pressure and temperature readings look reasonable
- No "No signal" messages

## Reading the Dashboard

### Pressure Units

Configure your preferred pressure unit in **Settings**:
- **PSI** - Pounds per square inch (common in US)
- **Bar** - Metric pressure unit (common in Europe)
- **kPa** - Kilopascals (standard SI unit)

### Normal vs. Alarm Conditions

**Normal Operation** (Green):
- Pressure within expected range
- Temperature normal
- Battery level adequate

**Warning Conditions** (Yellow/Red):
- 🔴 **LOW PRESSURE** - Pressure below safe threshold
- 🔴 **HIGH PRESSURE** - Pressure above safe threshold  
- 🔴 **HIGH TEMP** - Temperature exceeds safe limit
- 🔋 **Low Battery** - Sensor battery needs replacement

### Data Freshness

- Data updates automatically as sensors broadcast
- Stale data may show "No signal" if sensor hasn't broadcast recently
- Typical update interval: 5-30 seconds per sensor

## Settings & Customization

Access settings via the toolbar menu (⋮):

### Display Settings

- **Pressure Unit** - Choose PSI, Bar, or kPa
- Changes apply immediately to dashboard

### Scanning Settings

- **Run in Background** - Keep scanning when app is minimized
  - When enabled, shows persistent notification with stop button
  - When disabled, scanning stops when app closes
- **Parse known sensors only** - Filter mode:
  - **On** - Only show data from assigned sensors (recommended for daily use)
  - **Off** - Show all TPMS data (useful for testing new sensors)

## Troubleshooting

### No Sensors Detected

1. **Check Bluetooth** - Ensure Bluetooth is enabled on your device
2. **Check Permissions** - Verify all required permissions are granted
3. **Sensor Activity** - Make sure sensors are active (car nearby, wheels rolling)
4. **Distance** - Move closer to the vehicle (BLE range ~10-30 meters)
5. **Background Filter** - Try disabling "Parse known sensors only" in settings

### Incorrect Readings

1. **Wrong Assignment** - Verify sensors are assigned to correct tyre positions
2. **Sensor Protocol** - Your sensors may use a different BLE protocol
3. **Interference** - Other BLE devices may cause interference

### Battery Drain

- Background scanning uses minimal battery but can be disabled in settings
- Consider disabling background scanning if not needed continuously

### App Crashes or Freezes

1. **Restart App** - Close and reopen the app
2. **Restart Bluetooth** - Toggle Bluetooth off/on
3. **Check Android Version** - Requires Android 8.0+ (API 26)

## Technical Notes

### Sensor Protocol

The app expects TPMS sensors to broadcast BLE advertisements with this format:

| Byte | Content |
|------|---------|
| 0–1  | Manufacturer ID (little-endian) |
| 2    | Position hint (0=FL, 1=FR, 2=RL, 3=RR) |
| 3–4  | Pressure × 0.1 kPa (big-endian) |
| 5    | Temperature + 50 offset (subtract 50 for °C) |
| 6    | Battery 0–100% |
| 7    | Alarm flags (bit0=low, bit1=high, bit2=highTemp) |

### Android Compatibility

- **Minimum**: Android 8.0 (API 26)
- **Recommended**: Android 12+ for improved Bluetooth permissions
- **Hardware**: Bluetooth LE support required

### Privacy & Security

- No internet connectivity required
- No data transmitted to external servers
- All sensor data processed locally on device
- BLE scanning only - no connection to sensors required

## Support

For issues or questions:
- Check this user guide for common solutions
- Verify your sensors use the supported BLE protocol
- Ensure all Android permissions are properly granted

---

*TPMS Monitor is designed for aftermarket BLE TPMS sensors. OEM factory TPMS systems use different protocols and are not supported.*
