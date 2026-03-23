# Extended / Custom PID Support

Add a general-purpose framework for user-defined custom PIDs (any OBD mode, any ECU header) so manufacturer-specific extended diagnostics can be configured per vehicle profile.

## Background

Standard Mode 01 PIDs are auto-discovered via bitmask queries. But many vehicles expose additional data through:
- **Mode 22** (Enhanced Diagnostics) — e.g. Jaguar ABS/DSC module via header `760`
- **Mode 21** (Manufacturer-specific) — various OEMs
- Custom headers targeting non-engine ECUs (ABS, TCM, BCM, etc.)

These PIDs require:
1. Switching the ELM327 header (`AT SH XXX`) before sending
2. Sending a different mode byte (e.g. `22` instead of `01`)
3. Parsing a different response format (`62XXXX` instead of `41XX`)

## Data Model

### `CustomPid` data class
```kotlin
data class CustomPid(
    val id: String = UUID.randomUUID().toString(),
    val name: String,           // e.g. "Yaw Rate"
    val header: String,         // ECU header, e.g. "760" (empty = default 7DF)
    val mode: String,           // OBD mode hex, e.g. "22"
    val pid: String,            // PID hex bytes, e.g. "0456"
    val bytesReturned: Int,     // Data bytes expected in response
    val unit: String,           // Display unit, e.g. "°/s"
    val formula: String,        // Parse expression, e.g. "((A*256)+B)/100"
    val signed: Boolean = false // Whether result is signed (two's complement)
)
```

The `formula` field uses the standard Torque Pro / Car Scanner notation:
- `A`, `B`, `C`, `D` = response data bytes (0-indexed)
- Standard arithmetic operators: `+`, `-`, `*`, `/`, `(`, `)`
- Constants as decimal numbers

### Storage

Custom PIDs are stored as a list in `VehicleProfile`:
```kotlin
val customPids: List<CustomPid> = emptyList()
```

Serialized to JSON within the profile file.

## Implementation Plan

### Step 1: Create `CustomPid.kt` data class
New file: `obd/CustomPid.kt`

### Step 2: Add `customPids` field to `VehicleProfile`
Add `val customPids: List<CustomPid> = emptyList()`

### Step 3: Serialize/deserialize in `VehicleProfileRepository`
- `toJson()`: serialize customPids as JSONArray
- `toProfile()`: deserialize customPids from JSONArray

### Step 4: Add formula parser
New file: `obd/PidFormulaParser.kt`
- Parses expressions like `((A*256)+B)/100`
- Substitutes A,B,C,D with actual byte values
- Evaluates arithmetic expression
- Handles signed values (two's complement)

### Step 5: Update `BluetoothObd2Service` polling
- After standard Mode 01 polling, poll custom PIDs in slow tier
- Group custom PIDs by header to minimize AT SH switches
- Before each header group: `AT SH <header>`
- Send command: `<mode><pid>` (e.g. `220456`)
- Parse response: expect `<mode+40><pid><data>` (e.g. `620456XXXX`)
- After custom PIDs: restore default header `AT SH 7DF`
- Emit results into the same `cachedResults` map with a composite key

### Step 6: Custom PID management UI
New bottom sheet: `CustomPidEditSheet.kt`
- Fields: Name, Header, Mode, PID, Bytes, Unit, Formula, Signed toggle
- Pre-filled examples for common extended PIDs
- Accessible from vehicle profile edit screen

### Step 7: Add "Custom PIDs" button to VehicleProfileEditSheet
- Button opens the custom PID list/management screen

## Files Changed

| File | Change |
|------|--------|
| `obd/CustomPid.kt` | New — data class |
| `obd/PidFormulaParser.kt` | New — arithmetic expression evaluator |
| `settings/VehicleProfile.kt` | Add `customPids` field |
| `settings/VehicleProfileRepository.kt` | Serialize/deserialize customPids |
| `obd/BluetoothObd2Service.kt` | Custom PID polling with header switching |
| `settings/CustomPidEditSheet.kt` | New — UI for adding/editing custom PIDs |
| `settings/CustomPidListSheet.kt` | New — UI for listing/managing custom PIDs |
| `res/layout/sheet_custom_pid_edit.xml` | New — layout for custom PID editor |
| `res/layout/sheet_custom_pid_list.xml` | New — layout for custom PID list |
| `settings/VehicleProfileEditSheet.kt` | Add "Custom PIDs" button |
| `res/layout/sheet_vehicle_profile_edit.xml` | Add "Custom PIDs" button |

## Testing

- Add unit tests for `PidFormulaParser` with the Jaguar XF examples
- Verify header switching doesn't break standard PID polling
- Verify custom PID values appear in Details page
