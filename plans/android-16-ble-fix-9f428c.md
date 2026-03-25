# Fix BLE Scanning for Android 16 (Pixel 7) and Add Raw Advertisement Logging

This plan addresses BLE scanning failures on Android 16 where unnamed/MAC-only devices and custom OBD BLE adapters are not detected, plus adds comprehensive raw payload logging for manual reverse-engineering.

## Root Cause Analysis

After analyzing the TPMS app's BLE implementation, I've identified several Android 16 compatibility issues:

1. **Missing Android 16 Permissions**: The app targets SDK 34 but Android 16 (API 35) introduced stricter BLE scanning requirements
2. **ScanSettings Configuration**: Android 16 filters out unnamed devices more aggressively with default scan settings
3. **Permission Flag Issues**: The `neverForLocation` flag may conflict with Android 16's privacy model when scanning for unnamed devices
4. **Insufficient Raw Logging**: Current implementation logs manufacturer data but not the complete raw advertisement payload

## Technical Changes Required

### Part 1: Android 16 BLE Scanning Fixes

#### 1.1 Update Build Configuration
**File**: `app/build.gradle`
- Update `compileSdk` from 34 to 35 (Android 16)
- Update `targetSdk` from 34 to 35
- This ensures the app compiles against Android 16 APIs

#### 1.2 Update Manifest Permissions
**File**: `app/src/main/AndroidManifest.xml`
- Remove `android:usesPermissionFlags="neverForLocation"` from `BLUETOOTH_SCAN` permission
  - This flag prevents scanning unnamed devices on Android 16
  - We'll request location permission explicitly instead
- Add `ACCESS_BACKGROUND_LOCATION` for Android 10+ (needed for foreground service scanning)
- Ensure all permissions are properly declared for Android 16

#### 1.3 Fix Permission Request Logic
**File**: `app/src/main/java/com/tpmsapp/ui/MainActivity.kt`
- Update `requestPermissionsAndBind()` to request `ACCESS_FINE_LOCATION` even on Android 12+
  - Android 16 requires location permission for unnamed device scanning
- Add runtime check for location services being enabled
- Add better error messaging when permissions are denied

#### 1.4 Enhanced ScanSettings for Unnamed Devices
**File**: `app/src/main/java/com/tpmsapp/ble/BleScanner.kt`
- Update `startScan()` method with Android 16-compatible settings:
  - Add `.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)` to ensure all devices are reported
  - Add `.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)` for better unnamed device detection
  - Add `.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)` to capture more advertisements
  - Add `.setLegacy(false)` to support extended advertisements (Android 8+)
  - Keep `SCAN_MODE_LOW_LATENCY` for real-time detection

### Part 2: Raw Advertisement Payload Logging

#### 2.1 Enhanced ScanCallback with Complete Raw Logging
**File**: `app/src/main/java/com/tpmsapp/ble/BleScanner.kt`

Add comprehensive logging in `handleScanResult()`:
- Log complete `ScanRecord.getBytes()` array as hex dump
- Log all manufacturer-specific data entries (not just first one)
- Log service UUIDs (16-bit, 32-bit, 128-bit)
- Log service data payloads
- Log TX power level
- Log device name from advertisement
- Log advertisement flags
- Log timestamp and RSSI
- Format output for easy copy-paste to hex analyzers

#### 2.2 Update RawAdvertisement Data Model
**File**: `app/src/main/java/com/tpmsapp/ble/BleScanner.kt`

Enhance `RawAdvertisement` data class:
- Add `completeRawBytes: ByteArray` field for full ScanRecord payload
- Add `serviceUuids: List<String>` field
- Add `serviceData: Map<String, ByteArray>` field
- Add `txPowerLevel: Int?` field
- Add `advertisementFlags: Int?` field
- Add helper methods for hex formatting and detailed logging

#### 2.3 Enhanced UI Display
**File**: `app/src/main/java/com/tpmsapp/ui/adapter/RawAdvertisementAdapter.kt`

Update the adapter to display:
- Complete raw byte array in expandable/collapsible view
- Formatted hex dump with byte offsets
- Parsed advertisement structure breakdown
- Copy-to-clipboard functionality for hex data

#### 2.4 Logcat Output Enhancement
Add detailed Logcat output in `BleScanner.kt`:
```
=== BLE Advertisement Detected ===
MAC: XX:XX:XX:XX:XX:XX
Name: [device name or "UNNAMED"]
RSSI: -XX dBm
Timestamp: [ms]
--- Complete Raw Bytes (XX bytes) ---
[Hex dump with offsets]
--- Manufacturer Data ---
Company ID: 0xXXXX
Data: [hex bytes]
--- Service UUIDs ---
[list of UUIDs]
--- Service Data ---
UUID: [uuid] -> [hex bytes]
================================
```

## Testing Strategy

1. **Permission Testing**: Verify all permissions are granted on Android 16
2. **Unnamed Device Detection**: Test with MAC-only BLE beacons
3. **Custom OBD Adapter**: Test with 2015 Jaguar XF's OBD BLE adapter
4. **Raw Logging**: Verify complete payloads are logged to Logcat
5. **Backward Compatibility**: Test on Android 13 to ensure no regressions

## Files to Modify

1. `app/build.gradle` - Update SDK versions
2. `app/src/main/AndroidManifest.xml` - Fix permissions
3. `app/src/main/java/com/tpmsapp/ui/MainActivity.kt` - Update permission logic
4. `app/src/main/java/com/tpmsapp/ble/BleScanner.kt` - Enhanced scanning and logging
5. `app/src/main/java/com/tpmsapp/ui/adapter/RawAdvertisementAdapter.kt` - Enhanced UI display

## Expected Outcome

After implementation:
- ✅ Android 16 (Pixel 7) will detect unnamed BLE devices
- ✅ Custom OBD BLE adapter will be visible in scan results
- ✅ Complete raw advertisement payloads logged to Logcat
- ✅ Hex data available for manual reverse-engineering
- ✅ Backward compatible with Android 13 and earlier versions
