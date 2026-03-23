# OBD Emulator Enhancement for PID Discovery Testing

This plan enhances the MockObd2Service to simulate realistic PID discovery scenarios with fixed custom PIDs, header-specific responses, proper timing, and error simulation for comprehensive testing before real vehicle deployment.

## Enhanced Mock Data Structure

### Extended JSON Format
```json
{
  "standardPids": [...], // Existing Mode 01 PIDs
  "customPids": {
    "760": {
      "220456": {
        "name": "Yaw Rate",
        "response": "62 04 56 01 F4",
        "bytes": 2,
        "unit": "°/s",
        "formula": "((A*256)+B)/100"
      },
      "220457": {
        "name": "Lateral Acceleration", 
        "response": "62 04 57 00 64",
        "bytes": 2,
        "unit": "g",
        "formula": "((A*256)+B)/100"
      }
    },
    "7E4": {
      "2201DB": {
        "name": "Hybrid Battery SOC",
        "response": "62 01 DB B4",
        "bytes": 1,
        "unit": "%",
        "formula": "A*0.5"
      }
    }
  },
  "errorSimulation": {
    "failureRate": 0.1,
    "nodataPids": ["220100", "220101", "220102"],
    "errorPids": ["220200"]
  }
}
```

## Implementation Components

### 1. Enhanced MockObd2Service
- **Command parsing**: Handle AT SH, Mode 21/22/23 queries
- **Header state tracking**: Current active header for response selection
- **Response generation**: Header-specific PID responses
- **Timing simulation**: 100ms delays per command
- **Error simulation**: Random failures and NODATA responses

### 2. Mock Command Processor
```kotlin
class MockCommandProcessor {
    private var currentHeader = "7DF"
    
    fun processCommand(command: String): String {
        delay(100) // Realistic timing
        
        return when {
            command.startsWith("AT SH") -> {
                currentHeader = command.substring(6).uppercase()
                "OK"
            }
            command.matches(Regex("[0-9A-Fa-f]{4}")) -> {
                processPidQuery(command)
            }
            else -> "?"
        }
    }
    
    private fun processPidQuery(query: String): String {
        val header = currentHeader
        val mode = query.substring(0, 2)
        val pid = query.substring(2)
        
        // Check for simulated errors
        if (shouldSimulateError(pid)) return "ERROR"
        if (shouldSimulateNoData(pid)) return "NODATA"
        
        // Return header-specific response
        return customPids[header]?.get("$mode$pid")?.response ?: "NODATA"
    }
}
```

### 3. Discovery Test Scenarios

#### Jaguar XF Simulation (Header 760)
- **220456**: Yaw Rate → "62 04 56 01 F4"
- **220457**: Lateral Acceleration → "62 04 57 00 64"  
- **220458**: Steering Angle → "62 04 58 80 00"
- **Other PIDs**: NODATA

#### Toyota Hybrid Simulation (Header 7E4)
- **2201DB**: Hybrid Battery SOC → "62 01 DB B4"
- **2201DC**: Inverter Temperature → "62 01 DC 50"
- **Other PIDs**: NODATA

#### Mixed Header Responses
- **7E0 (Engine)**: Standard Mode 01 PIDs only
- **7E1 (Transmission)**: Some Mode 21 PIDs
- **760 (Instrument)**: Mode 22 yaw/steering PIDs
- **7E4 (Hybrid)**: Mode 22 battery PIDs

#### Empty Discovery
- All custom PID queries return NODATA
- Tests discovery failure handling

### 4. Error Simulation Features

#### Random Failures (10% rate)
```kotlin
private fun shouldSimulateError(pid: String): Boolean {
    return Random.nextDouble() < errorSimulation.failureRate
}
```

#### Specific NODATA PIDs
- Always return NODATA for test PIDs like 220100, 220101, 220102
- Tests discovery resilience

#### Specific Error PIDs  
- Always return ERROR for test PIDs like 220200
- Tests error handling

### 5. Integration with Discovery Service

#### Mock Discovery Interface
```kotlin
interface MockDiscoveryInterface {
    fun setTestScenario(scenario: DiscoveryScenario)
    fun getCurrentHeader(): String
    fun getDiscoveryResults(): List<DiscoveredPid>
}

enum class DiscoveryScenario {
    JAGUAR_XF,
    TOYOTA_HYBRID, 
    MIXED_HEADERS,
    EMPTY_DISCOVERY,
    ERROR_HEAVY
}
```

#### Test Controls
- **Settings option** to select discovery scenario
- **Real-time switching** between test scenarios
- **Discovery statistics** (success rate, timing)

### 6. Files to Create/Modify

#### New Files
- `MockObd2CommandProcessor.kt` - Command parsing and response generation
- `MockDiscoveryScenario.kt` - Test scenario definitions
- `assets/mock_obd2_enhanced.json` - Extended mock data structure

#### Modified Files
- `MockObd2Service.kt` - Add discovery support and command processing
- `MainActivity.kt` - Add scenario selection in debug mode
- `SettingsFragment.kt` - Add mock scenario selector

### 7. Testing Features

#### Discovery Validation
- **Verify discovered PIDs match expected set**
- **Test header switching works correctly**
- **Validate timing (100ms delays)**
- **Test error handling**

#### UI Testing
- **Console output formatting**
- **Progress indication accuracy**
- **Cancellation responsiveness**
- **Results display correctness**

#### Performance Testing
- **Memory usage during long scans**
- **UI responsiveness during discovery**
- **Background thread behavior**

### 8. Debug Features

#### Discovery Logging
```
[MOCK] AT SH 760 -> OK
[MOCK] 220456 -> 62 04 56 01 F4 (100ms)
[MOCK] 220457 -> 62 04 57 00 64 (100ms)  
[MOCK] 220458 -> NODATA (100ms)
[MOCK] Discovery complete: 2/3 PIDs found
```

#### Scenario Information
- Current test scenario display
- Expected results for validation
- Performance metrics (time, success rate)

#### Real-time Controls
- Start/stop discovery testing
- Switch scenarios without restart
- Inject specific errors for testing

### 9. Implementation Steps

1. **Extend mock data structure** with custom PIDs and scenarios
2. **Create command processor** for AT and PID queries
3. **Enhance MockObd2Service** with discovery support
4. **Add test scenario selection** in debug settings
5. **Implement error simulation** logic
6. **Add discovery validation** and logging
7. **Create comprehensive test cases** for all scenarios

### 10. Benefits

#### Before Real Vehicle Testing
- **Validate discovery algorithm** works correctly
- **Test UI responsiveness** during long operations
- **Verify error handling** for various failure modes
- **Check timing** and rate limiting
- **Test header switching** logic

#### Development Efficiency
- **No need for real vehicle** during initial development
- **Repeatable test scenarios** for consistent debugging
- **Fast iteration** without hardware dependencies
- **Edge case testing** with controlled failures

This enhanced emulator will provide comprehensive testing capability for the PID discovery feature, ensuring it works correctly before connecting to real vehicles.
