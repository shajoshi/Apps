# PID Discovery Feature Implementation Plan

This plan adds a brute force PID discovery feature to safely scan for custom PIDs supported by the vehicle's ECUs, with results displayed in a console UI and automatically added to the custom PID list.

## UI Placement

**Best Location**: Add a "Discover PIDs" button in the CustomPidListSheet alongside the "+ Add" button.

**Rationale**:
- Discovery is profile-specific (uses active vehicle profile)
- Users naturally think "discover" when managing custom PIDs
- Reuses existing CustomPidListSheet UI and context
- Minimal navigation changes required

## Implementation Components

### 1. UI Changes
- **CustomPidListSheet**: Add "Discover" button next to "+ Add"
- **Discovery Dialog**: Bottom sheet with:
  - Scan options (modes 21, 22, 23; common headers)
  - Start/Cancel/Stop buttons
  - Console output (reuse connection log styling)
  - Progress indicator
  - "Add All" button for discovered PIDs

### 2. Discovery Service
- **PidDiscoveryService**: New class extending BluetoothObd2Service functionality
- **Safe scanning**:
  - Limited to read-only modes (21, 22, 23)
  - Skip actuator/control PID ranges
  - 100ms delay between commands
  - User-cancelable at any time
- **Common headers**: 7E0, 7E1, 7E2, 760, 7E4 (configurable)

### 3. Scan Logic
```kotlin
// Pseudo-code for discovery flow
for (header in selectedHeaders) {
    sendCommand("AT SH $header")
    for (mode in selectedModes) {
        for (pid in modeRange) {
            if (isActuatorPid(pid)) continue // Skip dangerous PIDs
            val response = sendCommand("$mode$pid")
            if (isValidResponse(response)) {
                discoveredPids.add(DiscoveredPid(header, mode, pid, response))
            }
            delay(100) // Rate limiting
        }
    }
}
```

### 4. Results Processing
- **Parse responses** to extract byte count and data patterns
- **Suggest formulas** based on common patterns:
  - 1 byte: `A`, `A-40`, `A*0.5`
  - 2 bytes: `((A*256)+B)/100`, `((A*256)+B)-32768`
- **Auto-generate names** based on mode/PID
- **Allow user review** before adding to profile

### 5. Integration Points
- **BluetoothObd2Service**: Add discovery methods
- **VehicleProfileRepository**: Save discovered PIDs
- **CustomPidListSheet**: Launch discovery and handle results

## Safety Features

### Command Safety
- **Read-only modes only**: 21, 22, 23 (no write/modify commands)
- **Skip dangerous ranges**: Known actuator/control PID ranges
- **Rate limiting**: 100ms minimum between commands
- **Timeout handling**: 5 second timeout per command
- **Error recovery**: Continue scanning after individual failures

### User Safety
- **Explicit consent**: User must start discovery
- **Progress indication**: Show current PID being scanned
- **Easy cancellation**: Stop button always available
- **Connection check**: Verify stable connection before starting
- **Warning dialog**: Explain what discovery does

## Technical Details

### Discovery Data Structure
```kotlin
data class DiscoveredPid(
    val header: String,
    val mode: String, 
    val pid: String,
    val response: String,
    val byteCount: Int,
    val suggestedFormula: String,
    val suggestedName: String
)
```

### Console Output Format
```
[HEADER 7E0] MODE 22 PID 0456: VALID (2 bytes) -> 62 04 56 01 F4
[HEADER 7E0] MODE 22 PID 0457: VALID (2 bytes) -> 62 04 57 00 64  
[HEADER 7E0] MODE 22 PID 0458: NODATA
[HEADER 760] MODE 22 PID 0456: VALID (2 bytes) -> 62 04 56 01 F4
...
Discovery complete: 12 PIDs found
```

### Performance Considerations
- **Scan scope**: ~768 PIDs per mode × 3 modes × 5 headers = ~11,520 commands
- **Time estimate**: ~11,520 × 0.1s = ~19 minutes max
- **Background execution**: Use coroutines, non-blocking UI
- **Memory management**: Clear console periodically to avoid memory issues

## Files to Create/Modify

### New Files
- `PidDiscoveryService.kt` - Discovery logic
- `PidDiscoverySheet.kt` - Discovery UI
- `res/layout/sheet_pid_discovery.xml` - Discovery dialog layout

### Modified Files  
- `CustomPidListSheet.kt` - Add discover button
- `res/layout/sheet_custom_pid_list.xml` - Add discover button
- `BluetoothObd2Service.kt` - Add discovery methods
- `VehicleProfileRepository.kt` - Batch save discovered PIDs

## Testing Strategy

### Safety Tests
- Verify only read-only commands are sent
- Test rate limiting (100ms between commands)
- Test cancellation stops immediately
- Test connection failures handled gracefully

### Functional Tests  
- Test with real ELM327 adapter
- Verify discovered PIDs parse correctly
- Test "Add All" functionality
- Test duplicate PID detection

### UI Tests
- Test discovery dialog flow
- Verify console updates in real-time
- Test progress indication
- Test orientation changes during discovery

## Future Enhancements

### V2 Features
- Save/load discovery sessions
- Export discovered PIDs to share
- Community PID database integration
- Smart formula suggestions based on response patterns
- Background discovery with notifications

### Advanced Options
- Custom PID ranges
- Adjustable scan speed
- Specific ECU targeting
- Response pattern analysis
