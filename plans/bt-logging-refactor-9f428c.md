# BT Logging Refactor Plan

Remove file-based BT connection logging and use the existing "BT Connection logging" setting to control BleTransport logcat verbosity instead.

## Changes Required

### 1. Remove BluetoothConnectionLogger.kt
- Delete the entire file - file logging to `obd_bt_connx.log` is no longer needed since logcat is sufficient
- Remove all usages of `BluetoothConnectionLogger.getInstance()` across the codebase

### 2. Modify BleTransport.kt
- Add a `verboseLogging` flag controlled by `AppSettings.isBtLoggingEnabled(context)`
- Wrap detailed connection/service discovery/characteristic logging with the flag
- Keep essential error logging always enabled
- Logs that will be conditional:
  - Service discovery details
  - Characteristic enumeration
  - Connection state change details
  - TX/RX data (hex dumps, buffer contents)
- Logs that remain always on:
  - Connection errors
  - Critical failures
  - High-level connection success/failure

### 3. Files to Modify
- `app/src/main/java/com/sj/obd2app/obd/BleTransport.kt` - Add conditional logging
- `app/src/main/java/com/sj/obd2app/obd/BluetoothConnectionLogger.kt` - Delete
- Other files that use BluetoothConnectionLogger - remove those calls

### 4. Setting Behavior
- "BT Connection logging" toggle in Settings controls BleTransport log verbosity
- When OFF: Only critical errors and high-level connection events logged
- When ON: Full detailed logging including service discovery, characteristics, data dumps
