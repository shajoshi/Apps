# OBD Disconnect Detection Fix

Improve OBD connection health monitoring to reliably detect when the adapter loses connection (e.g., engine shutdown, adapter unplugged) by implementing data staleness detection and exception tracking, since the current implementation silently catches exceptions without updating connection state.

## Problem Analysis

### Current Behavior (Issue)
Based on testing observations:
1. **Connection state never changes to DISCONNECTED** - `BluetoothObd2Service.connectionState` stays as `CONNECTED` even when bike is shut off
2. **Data stops flowing but state shows CONNECTED** - OBD data freezes at last values but connection appears healthy
3. **Data freezes at last values** - Trip screen shows stale RPM, speed, etc.
4. **Polling loop continues silently** - Exceptions are caught but don't trigger disconnect detection

### Root Cause

**In `BluetoothObd2Service.startPolling()` (lines 271-289):**
```kotlin
for (cmd in fastCommands) {
    if (!isActive) break
    try {
        val raw = sendCommand(cmd.pid)
        val parsed = parseResponse(cmd, raw)
        if (parsed != null) cachedResults[cmd.pid] = parsed
    } catch (_: Exception) {}  // ← SILENTLY CATCHES ALL EXCEPTIONS
    // No explicit delay — ATAT1 handles ECU pacing
}
```

**The Problem:**
- When the engine shuts off or adapter is unplugged, `sendCommand()` throws `IOException`
- The `catch (_: Exception) {}` silently swallows the exception
- The polling loop continues running
- Connection state remains `CONNECTED`
- No new data is published, so UI shows stale values
- `ObdConnectionManager` never detects a disconnect, so no reconnection attempts occur

**Why This Happens:**
- Bluetooth socket doesn't immediately detect physical disconnection
- `sendCommand()` may timeout (5 seconds) but exception is caught
- The polling loop condition `_connectionState.value == Obd2Service.ConnectionState.CONNECTED` remains true
- No mechanism to detect that data has gone stale

## Proposed Solutions

### Solution 1: Exception Counter with Threshold (Recommended)

**Approach:** Track consecutive failed PID reads. If failures exceed a threshold, mark connection as unhealthy and trigger disconnect.

**Implementation:**
```kotlin
// In BluetoothObd2Service
private var consecutiveFailures = 0
private val MAX_CONSECUTIVE_FAILURES = 10  // ~2-3 seconds of failures

private fun startPolling() {
    // ... existing code ...
    
    pollingJob = CoroutineScope(Dispatchers.IO).launch {
        var cycleCount = 0
        while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
            var cycleSuccessCount = 0
            
            // Poll fast-tier PIDs every cycle
            for (cmd in fastCommands) {
                if (!isActive) break
                try {
                    val raw = sendCommand(cmd.pid)
                    val parsed = parseResponse(cmd, raw)
                    if (parsed != null) {
                        cachedResults[cmd.pid] = parsed
                        cycleSuccessCount++
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "PID ${cmd.pid} failed: ${e.message}")
                    // Don't break, try other PIDs
                } catch (e: Exception) {
                    Log.w(TAG, "PID ${cmd.pid} error: ${e.message}")
                }
            }
            
            // Check if we got any successful reads this cycle
            if (cycleSuccessCount == 0) {
                consecutiveFailures++
                Log.w(TAG, "Polling cycle failed, consecutive failures: $consecutiveFailures")
                
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.e(TAG, "Too many consecutive failures, marking connection as lost")
                    _connectionState.value = Obd2Service.ConnectionState.ERROR
                    _errorMessage.value = "Connection lost - no data received"
                    break  // Exit polling loop
                }
            } else {
                // Reset counter on successful read
                consecutiveFailures = 0
            }
            
            // ... rest of polling logic ...
        }
        
        // Cleanup after polling stops
        if (_connectionState.value == Obd2Service.ConnectionState.ERROR) {
            closeSocket()
        }
    }
}
```

**Pros:**
- Detects connection loss within 2-3 seconds
- Doesn't require timeout changes
- Works even if Bluetooth socket doesn't detect disconnect
- Resets on successful reads (handles temporary glitches)

**Cons:**
- Adds slight complexity to polling loop

### Solution 2: Data Staleness Detection

**Approach:** Track timestamp of last successful data update. If no updates for N seconds, mark connection as stale.

**Implementation:**
```kotlin
// In BluetoothObd2Service
private var lastDataUpdateMs = 0L
private val DATA_STALENESS_THRESHOLD_MS = 5000L  // 5 seconds

private fun startPolling() {
    // ... existing code ...
    
    pollingJob = CoroutineScope(Dispatchers.IO).launch {
        var cycleCount = 0
        lastDataUpdateMs = System.currentTimeMillis()
        
        while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
            // ... polling logic ...
            
            if (cachedResults.isNotEmpty()) {
                _obd2Data.value = cachedResults.values.toList()
                lastDataUpdateMs = System.currentTimeMillis()  // Update timestamp
            }
            
            // Check for stale data
            val timeSinceLastUpdate = System.currentTimeMillis() - lastDataUpdateMs
            if (timeSinceLastUpdate > DATA_STALENESS_THRESHOLD_MS) {
                Log.e(TAG, "Data stale for ${timeSinceLastUpdate}ms, marking connection as lost")
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                _errorMessage.value = "Connection lost - data stale"
                break
            }
            
            cycleCount++
            delay(10L)
        }
        
        if (_connectionState.value == Obd2Service.ConnectionState.ERROR) {
            closeSocket()
        }
    }
}
```

**Pros:**
- Simple time-based detection
- Catches any scenario where data stops flowing
- Easy to understand and maintain

**Cons:**
- 5-second delay before detection (slower than Solution 1)
- May trigger false positives if ECU is legitimately slow

### Solution 3: Socket Health Check (Complementary)

**Approach:** Periodically check if the Bluetooth socket is still connected at the OS level.

**Implementation:**
```kotlin
private fun isSocketHealthy(): Boolean {
    val sock = socket ?: return false
    return try {
        sock.isConnected && !sock.isClosed
    } catch (e: Exception) {
        false
    }
}

private fun startPolling() {
    pollingJob = CoroutineScope(Dispatchers.IO).launch {
        var cycleCount = 0
        while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
            // Check socket health every 10 cycles (~2 seconds)
            if (cycleCount % 10 == 0 && !isSocketHealthy()) {
                Log.e(TAG, "Socket health check failed")
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                _errorMessage.value = "Bluetooth socket disconnected"
                break
            }
            
            // ... existing polling logic ...
        }
    }
}
```

**Pros:**
- Detects socket-level disconnections
- Low overhead (only checks every 2 seconds)

**Cons:**
- Bluetooth socket may report "connected" even when adapter is unplugged
- Not sufficient on its own

### Solution 4: Heartbeat PID (Advanced)

**Approach:** Designate a "heartbeat" PID (e.g., RPM) that must succeed regularly. If heartbeat fails repeatedly, trigger disconnect.

**Implementation:**
```kotlin
private val HEARTBEAT_PID = "010C"  // RPM - always available when engine running
private var heartbeatFailures = 0
private val MAX_HEARTBEAT_FAILURES = 5

private fun startPolling() {
    pollingJob = CoroutineScope(Dispatchers.IO).launch {
        while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
            var heartbeatSuccess = false
            
            // Poll fast-tier PIDs
            for (cmd in fastCommands) {
                try {
                    val raw = sendCommand(cmd.pid)
                    val parsed = parseResponse(cmd, raw)
                    if (parsed != null) {
                        cachedResults[cmd.pid] = parsed
                        if (cmd.pid == HEARTBEAT_PID) {
                            heartbeatSuccess = true
                        }
                    }
                } catch (_: Exception) {}
            }
            
            // Check heartbeat
            if (!heartbeatSuccess) {
                heartbeatFailures++
                if (heartbeatFailures >= MAX_HEARTBEAT_FAILURES) {
                    Log.e(TAG, "Heartbeat PID failed $heartbeatFailures times")
                    _connectionState.value = Obd2Service.ConnectionState.ERROR
                    break
                }
            } else {
                heartbeatFailures = 0
            }
            
            // ... rest of polling ...
        }
    }
}
```

**Pros:**
- Focuses on critical PID (RPM)
- Fast detection if RPM is polled frequently

**Cons:**
- Assumes RPM is always supported
- Engine-off scenarios will always trigger (may be desired behavior)

## Recommended Implementation Plan

**Hybrid Approach: Solution 1 + Solution 3**

Combine exception counter with socket health checks for robust detection:

1. **Primary Detection:** Exception counter (detects within 2-3 seconds)
2. **Secondary Detection:** Socket health check (catches socket-level issues)
3. **Logging:** Log all exceptions instead of silently catching them

### Implementation Steps

#### Phase 1: Add Exception Tracking to BluetoothObd2Service

**File:** `BluetoothObd2Service.kt`

1. Add fields for failure tracking:
   ```kotlin
   private var consecutiveFailures = 0
   private val MAX_CONSECUTIVE_FAILURES = 10
   ```

2. Modify `startPolling()`:
   - Track successful reads per cycle
   - Increment `consecutiveFailures` when cycle has zero successes
   - Set `_connectionState` to `ERROR` when threshold exceeded
   - Log exceptions instead of silently catching them

3. Add socket health check:
   - Create `isSocketHealthy()` method
   - Check every 10 cycles in polling loop

#### Phase 2: Improve Exception Handling

**File:** `BluetoothObd2Service.kt`

1. Replace `catch (_: Exception) {}` with specific exception handling:
   ```kotlin
   } catch (e: IOException) {
       Log.w(TAG, "PID ${cmd.pid} IOException: ${e.message}")
   } catch (e: Exception) {
       Log.w(TAG, "PID ${cmd.pid} error: ${e.message}")
   }
   ```

2. Don't break on individual PID failures - try all PIDs in cycle

#### Phase 3: Reset Failure Counter on Success

Ensure `consecutiveFailures` resets to 0 when any PID succeeds, preventing false positives from temporary glitches.

#### Phase 4: Cleanup on Error State

When connection state changes to `ERROR`, ensure:
- Polling loop exits cleanly
- Socket is closed via `closeSocket()`
- `ObdConnectionManager` detects the state change and triggers reconnection

## Testing Plan

1. **Test 1: Engine Shutdown**
   - Start trip with OBD connected
   - Shut off engine
   - Verify connection state changes to ERROR within 3 seconds
   - Verify ObdConnectionManager starts reconnection attempts
   - Restart engine
   - Verify reconnection succeeds

2. **Test 2: Adapter Unplugged**
   - Start trip with OBD connected
   - Physically unplug OBD adapter
   - Verify connection state changes to ERROR within 3 seconds
   - Verify reconnection attempts start
   - Plug adapter back in
   - Verify reconnection succeeds

3. **Test 3: Temporary Glitch**
   - Simulate 1-2 failed PID reads (not enough to trigger threshold)
   - Verify connection remains CONNECTED
   - Verify failure counter resets on next successful read

4. **Test 4: Bluetooth Disabled**
   - Start trip with OBD connected
   - Disable Bluetooth on phone
   - Verify connection state changes to ERROR
   - Re-enable Bluetooth
   - Verify reconnection succeeds

## Files to Modify

1. `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\obd\BluetoothObd2Service.kt`
   - Add failure tracking fields
   - Modify `startPolling()` to track consecutive failures
   - Add `isSocketHealthy()` method
   - Improve exception logging
   - Trigger disconnect on failure threshold

## Expected Outcome

After implementation:
- OBD connection loss detected within 2-3 seconds
- Connection state properly changes to ERROR/DISCONNECTED
- ObdConnectionManager triggers reconnection attempts
- Trip data recording resumes seamlessly after reconnection
- No more "zombie connections" where state shows CONNECTED but data is stale
