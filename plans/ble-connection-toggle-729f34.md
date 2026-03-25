# Add BLE Connection Toggle to Connect Screen

Add a toggle switch at the top of the Connect screen to allow users to force BLE protocol for connections, overriding the automatic device type detection that currently exists.

## Current Implementation Analysis

The app currently has **automatic BLE/Classic BT detection** in `BluetoothObd2Service.kt`:

```kotlin
private fun createTransport(device: BluetoothDevice): Elm327Transport {
    return when (device.type) {
        BluetoothDevice.DEVICE_TYPE_LE -> BleTransport(...)
        BluetoothDevice.DEVICE_TYPE_DUAL -> ClassicBluetoothTransport(...) // Prefers Classic
        else -> ClassicBluetoothTransport(...)
    }
}
```

**Key observations:**
- Dual-mode devices always use Classic BT (line 195: "prefer Classic BT as it's more reliable")
- No user control over protocol selection
- BLE support already fully implemented via `BleTransport` class

## User Requirements

1. **Toggle location**: Top of Connect screen
2. **Toggle label**: "BLE Connection" or similar
3. **Default state**: OFF (use current auto-detection behavior)
4. **When ON**: Force BLE protocol for selected device
5. **When OFF**: Use existing auto-detection (Classic BT preferred for dual-mode)

## Proposed Implementation

### 1. Add Setting to AppSettings

**File: `AppSettings.kt`**
- Add `forceBleConnection: Boolean = false` to `SettingsData`
- Add getter/setter methods: `isForceBleConnection()` / `setForceBleConnection()`
- Store in settings.json (or SharedPreferences fallback)

### 2. Update UI Layout

**File: `fragment_connect.xml`**
- Add a `MaterialSwitch` below the top bar with info icon
- Style to match app theme (#1A1A2E background, #4FC3F7 accent)
- Layout structure:
  ```xml
  <LinearLayout (horizontal, padding 16dp, background="#0D0D1A")
      <TextView "Force BLE"
      <ImageButton id="btn_ble_info" (small info icon)
      <Space (weight=1)
      <MaterialSwitch id="switch_force_ble"
  </LinearLayout>
  ```
- Info icon: Use material icon `ic_info_outline` or similar, tinted #AAAAAA

### 3. Update ConnectFragment

**File: `ConnectFragment.kt`**
- Bind switch to setting value in `onCreateView()`
- Add switch listener to save setting changes
- Show/hide switch based on mock mode (hide in mock mode)
- Add info icon click listener to show tooltip/dialog:
  ```
  "Force BLE enables Bluetooth Low Energy protocol for connections.
   Use this for BLE-only OBD2 adapters. Note: Classic Bluetooth devices
   may fail to connect when this is enabled."
  ```
- Add warning dialog method `showBleWarningDialog()`:
  - Check device type before connection attempt
  - If device is `DEVICE_TYPE_CLASSIC` and force BLE is ON → show warning
  - Warning message: "This device appears to be Classic Bluetooth only. Forcing BLE connection may fail. Continue anyway?"
  - Buttons: "Cancel" / "Connect Anyway"

### 4. Update ConnectViewModel

**File: `ConnectViewModel.kt`**
- Add `forceBleConnection` LiveData/StateFlow
- Pass this flag when connecting to device
- Modify `connectToDevice()` signature or add parameter

### 5. Update BluetoothObd2Service

**File: `BluetoothObd2Service.kt`**
- Modify `connect()` to accept optional `forceBle: Boolean` parameter
- Update `createTransport()` to check force flag:
  ```kotlin
  private fun createTransport(device: BluetoothDevice, forceBle: Boolean = false): Elm327Transport {
      if (forceBle) {
          log("Forcing BLE connection (user preference)")
          return BleTransport(context!!, device)
      }
      // Existing auto-detection logic...
  }
  ```
- Log when BLE is forced vs auto-detected

### 6. Update Obd2Service Interface (if needed)

**File: `Obd2Service.kt`**
- Check if `connect()` signature needs updating
- May need to add optional parameter to interface

## Implementation Steps

1. **Add setting storage** (`AppSettings.kt`):
   - Add `forceBleConnection: Boolean = false` to `SettingsData`
   - Add `isForceBleConnection()` and `setForceBleConnection()` methods
   - Add constant `KEY_FORCE_BLE_CONNECTION`

2. **Create UI layout** (`fragment_connect.xml`):
   - Add horizontal LinearLayout below top bar
   - Add "Force BLE" TextView
   - Add info icon ImageButton (24dp, #AAAAAA tint)
   - Add MaterialSwitch with #4FC3F7 accent color

3. **Implement UI logic** (`ConnectFragment.kt`):
   - Bind switch to setting value in `setupConnectUI()`
   - Add switch change listener to persist setting
   - Add info icon click listener to show explanation dialog
   - Add `showBleWarningDialog(deviceInfo, onConfirm)` method
   - Check device type before connection and show warning if needed
   - Hide toggle panel in mock mode

4. **Update ViewModel** (`ConnectViewModel.kt`):
   - Modify `connectToDevice()` to accept `forceBle: Boolean` parameter
   - Pass device info and force flag to service

5. **Update Service** (`BluetoothObd2Service.kt`):
   - Add `forceBle: Boolean = false` parameter to `connect()` method
   - Pass `forceBle` to `createTransport()`
   - Update `createTransport()` to check force flag first:
     - If `forceBle == true` → always use `BleTransport`
     - Otherwise → use existing auto-detection logic
   - Add log message when BLE is forced

6. **Update Interface** (`Obd2Service.kt`):
   - Add optional `forceBle: Boolean = false` parameter to `connect()` signature
   - Update MockObd2Service to match signature

7. **Testing**:
   - Test toggle persistence across app restarts
   - Test info icon shows explanation
   - Test warning dialog on Classic-only device with toggle ON
   - Test successful BLE connection on dual-mode device with toggle ON
   - Test Classic connection on dual-mode device with toggle OFF
   - Test auto-connect respects toggle setting

## Edge Cases & Considerations

**Device Compatibility:**
- Classic-only device + Force BLE ON → Show warning dialog before attempting
- BLE-only device → Works regardless of toggle state
- Dual-mode device + Force BLE ON → Uses BLE instead of Classic
- Dual-mode device + Force BLE OFF → Uses Classic (current behavior)

**UI/UX:**
- Toggle visible only in non-mock mode
- Info icon shows explanation tooltip
- Toggle state persists across app restarts
- Warning dialog prevents accidental incompatible connections

**Connection Flow:**
- Auto-connect feature reads and respects the BLE toggle setting
- Reconnection attempts use the same protocol as initial connection
- Connection log clearly indicates when BLE is forced vs auto-detected

## User Confirmed Preferences

1. **Toggle Label**: "Force BLE"
2. **Error Handling**: Show warning dialog before attempting BLE on incompatible devices
3. **Info Icon**: Yes, add info icon with tooltip
4. **Implementation**: Option A (explicit parameter passing)

## Files to Modify

1. `AppSettings.kt` - Add setting storage
2. `fragment_connect.xml` - Add toggle UI with info icon
3. `ConnectFragment.kt` - Bind toggle, add warning dialog, add info tooltip
4. `ConnectViewModel.kt` - Pass force BLE flag to service
5. `BluetoothObd2Service.kt` - Honor force BLE flag in transport creation
6. `Obd2Service.kt` - Update interface signature
7. `MockObd2Service.kt` - Update to match interface signature

## Summary

This implementation adds user control over BLE vs Classic Bluetooth protocol selection while maintaining backward compatibility. The default behavior (auto-detection preferring Classic BT) remains unchanged unless the user explicitly enables "Force BLE". The warning dialog and info icon ensure users understand when and why to use this feature, preventing connection failures due to protocol mismatch.
