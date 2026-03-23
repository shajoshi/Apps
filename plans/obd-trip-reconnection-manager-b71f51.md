# OBD Trip Reconnection Manager

Implement an OBD connection manager that monitors and maintains OBD connectivity during active trips (RUNNING or PAUSED), with automatic reconnection attempts when the connection drops due to engine shutdown or distance from adapter.

## Problem Analysis

**Current Behavior:**
- When a trip is paused and the bike is shut off, the OBD connection breaks
- When the trip is resumed and bike restarted, OBD does not auto-reconnect
- Trip continues but records no OBD values, leading to incomplete trip data

**Root Cause:**
- No active monitoring of OBD connection state during trips
- No automatic reconnection logic when connection drops during active trips
- BluetoothObd2Service only connects on manual user action

## Solution Design

### 1. Create OBD Connection Manager Service

**New Component:** `ObdConnectionManager`
- Singleton service that monitors OBD connection state
- Only active when trip is RUNNING or PAUSED
- Stops monitoring when trip is IDLE/stopped

**Responsibilities:**
- Monitor `BluetoothObd2Service.connectionState` flow
- Detect disconnections during active trips
- Trigger immediate reconnection attempt on disconnect
- Fall back to 10-second polling if immediate reconnection fails
- Notify user of connection status changes

### 2. Reconnection Strategy

**Adaptive Backoff Strategy:**
- On disconnect detection, start reconnection attempts immediately
- **First 5 attempts**: 10-second intervals between attempts
- **After 5 attempts**: Back off to 60-second intervals
- **On successful reconnection**: Reset attempt counter (next disconnect starts fresh with 5x10s)
- Continue until connection succeeds or trip is stopped

**Reconnection Logic:**
```
Disconnect detected
  ↓
Attempt 1 (immediate)
  ↓ wait 10s
Attempt 2
  ↓ wait 10s
Attempt 3
  ↓ wait 10s
Attempt 4
  ↓ wait 10s
Attempt 5
  ↓ wait 60s
Attempt 6
  ↓ wait 60s
... (continue at 60s intervals)
```

**Connection Attempt:**
- Reuse existing `BluetoothObd2Service.connect()` method
- Use last connected device from `AppSettings.getLastDeviceMac()`
- Handle connection failures gracefully without crashing
- Track attempt count to determine interval (10s vs 60s)

### 3. User Notifications

**Visual Indicators:**
- Trip screen OBD status already shows connection state (GREEN/YELLOW/RED)
- Red indicator automatically shown when `connectionState = DISCONNECTED/ERROR`

**Toast Notifications:**
- Show toast when OBD disconnects during active trip: "OBD disconnected - attempting reconnection..."
- Show toast when reconnection succeeds: "OBD reconnected"

**Foreground Notification:**
- Update `TripForegroundService` notification to include OBD status
- Show "OBD Disconnected - Reconnecting..." when disconnected
- Revert to normal status when reconnected

## Implementation Plan

### Phase 1: Create ObdConnectionManager

**File:** `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\obd\ObdConnectionManager.kt`

```kotlin
class ObdConnectionManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: ObdConnectionManager? = null
        fun getInstance(context: Context): ObdConnectionManager { ... }
        
        private const val FAST_RETRY_INTERVAL_MS = 10_000L  // 10 seconds
        private const val SLOW_RETRY_INTERVAL_MS = 60_000L  // 60 seconds
        private const val FAST_RETRY_COUNT = 5  // First 5 attempts at 10s
    }
    
    private val metricsCalculator = MetricsCalculator.getInstance(context)
    private val obdService = Obd2ServiceProvider.getService()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var monitoringJob: Job? = null
    private var reconnectionJob: Job? = null
    private var lastKnownDeviceMac: String? = null
    private var attemptCount = 0  // Track reconnection attempts for backoff
    private var manualDisconnect = false  // Don't auto-reconnect if user manually disconnected
    
    fun startMonitoring() { ... }
    fun stopMonitoring() { ... }
    private fun observeConnectionState() { ... }
    private fun attemptReconnection() { ... }
    private fun getRetryInterval(): Long { ... }
    private fun resetAttemptCounter() { ... }
}
```

**Key Methods:**
- `startMonitoring()`: Begin monitoring OBD connection during trip
- `stopMonitoring()`: Stop monitoring when trip ends, reset attempt counter
- `observeConnectionState()`: Watch for disconnections, trigger reconnection
- `attemptReconnection()`: Handle reconnection logic with adaptive backoff
- `getRetryInterval()`: Returns 10s for first 5 attempts, 60s thereafter
- `resetAttemptCounter()`: Reset to 0 on successful reconnection

### Phase 2: Integrate with Trip Lifecycle

**Files to Modify:**
- `MetricsCalculator.kt` - Call manager on startTrip/stopTrip
- `TripViewModel.kt` - Ensure manager starts/stops with trip lifecycle

**Changes:**
```kotlin
// In MetricsCalculator.startTrip()
fun startTrip() {
    tripState.reset()
    // ... existing code ...
    ObdConnectionManager.getInstance(context).startMonitoring()
}

// In MetricsCalculator.stopTrip()
fun stopTrip() {
    // ... existing code ...
    ObdConnectionManager.getInstance(context).stopMonitoring()
}
```

### Phase 3: Update TripForegroundService

**File:** `TripForegroundService.kt`

**Changes:**
- Observe OBD connection state in addition to trip phase
- Update notification text to include OBD status
- Show "OBD Disconnected - Reconnecting..." when disconnected

```kotlin
private fun observeTripState() {
    serviceScope.launch {
        combine(
            calculator.tripPhase, 
            calculator.metrics,
            Obd2ServiceProvider.getService().connectionState
        ) { phase, metrics, obdState ->
            updateNotification(phase, metrics, obdState)
        }.collect { }
    }
}
```

### Phase 4: Handle Edge Cases

**Scenarios to Handle:**
1. **User manually disconnects OBD** - Don't auto-reconnect if user explicitly disconnected
2. **Bluetooth disabled** - Detect and show appropriate message
3. **Device unpaired** - Handle gracefully, show error
4. **Multiple rapid disconnects** - Avoid reconnection spam
5. **Trip paused for extended time** - Continue monitoring but reduce battery impact

**Solution:**
- Track manual disconnect flag in ObdConnectionManager
- Check Bluetooth adapter state before reconnection attempts
- Add debouncing to prevent rapid reconnection attempts (min 2 seconds between attempts)

## Testing Checklist

- [ ] Start trip with OBD connected → Manager starts monitoring
- [ ] Disconnect OBD during running trip → Starts reconnection attempts immediately
- [ ] First 5 attempts use 10-second intervals
- [ ] After 5 failed attempts → Backs off to 60-second intervals
- [ ] OBD becomes available during fast retry phase → Successfully reconnects
- [ ] OBD becomes available during slow retry phase → Successfully reconnects
- [ ] Successful reconnection → Attempt counter resets to 0
- [ ] Second disconnect after successful reconnect → Starts fresh with 5x10s attempts
- [ ] Pause trip with OBD disconnected → Manager continues monitoring and reconnecting
- [ ] Resume trip → OBD reconnects if available
- [ ] Stop trip → Manager stops monitoring, resets attempt counter
- [ ] Manual disconnect → No auto-reconnect (if implemented)
- [ ] Bluetooth disabled → Graceful handling
- [ ] Toast notifications appear on disconnect/reconnect
- [ ] Foreground notification updates with OBD status
- [ ] Verify no battery drain during 60s interval polling

## Files to Create

1. `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\obd\ObdConnectionManager.kt` - New connection manager service

## Files to Modify

1. `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\metrics\MetricsCalculator.kt` - Integrate manager with trip lifecycle
2. `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\service\TripForegroundService.kt` - Update notification with OBD status
3. `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\obd\BluetoothObd2Service.kt` - Add manual disconnect tracking (optional)

## Benefits

- **Seamless trip recording** - OBD data continues recording even if connection temporarily lost
- **User awareness** - Clear notifications when OBD disconnects/reconnects
- **Battery efficient** - Only monitors during active trips, not all the time
- **Robust** - Handles edge cases like Bluetooth disabled, device unpaired, etc.
- **Non-intrusive** - Automatic reconnection happens in background without user intervention
