# Driver Metrics Implementation Plan

This plan implements driver event detection in the Android tracking app based on the algorithms and constants from driver_metrics.py, with UI display in the Driving View dialog.

## 1. Data Model Updates

### 1.1 Create DriverMetrics data class
- Create `DriverMetrics.kt` with fields for events, jerk, smoothness, reaction time
- Include event list, primary event, smoothness score, jerk value, reaction time

### 1.2 Update TrackingSample
- Add `driverMetrics: DriverMetrics?` field to TrackingSample data class
- Update TrackingState to include driver event count

### 1.3 Update AccelMetrics
- Add `fwdMean` and `latMean` fields (already computed but not stored)
- Add `deltaSpeed` and `deltaCourse` for jerk calculation

## 2. Driver Event Detection Algorithm

### 2.1 Constants (from driver_metrics.py)
```kotlin
private val HARD_BRAKE_FWD_MAX = 35f        // m/s²
private val HARD_ACCEL_FWD_MAX = 35f        // m/s²
private val SWERVE_LAT_MAX = 8f             // m/s²
private val AGGRESSIVE_CORNER_LAT_MAX = 8f  // m/s²
private val AGGRESSIVE_CORNER_DCOURSE = 15f // degrees
private val MIN_SPEED_KMPH = 6f             // km/h
private val MOVING_AVG_WINDOW = 20           // samples
```

### 2.2 Event Classification Function
- Create `classifyDriverEvent()` function in TrackingService
- Implement logic identical to driver_metrics.py:
  - Check speed < MIN_SPEED_KMPH → "low_speed"
  - Check fwdMax > HARD_BRAKE_FWD_MAX && deltaSpeed < 0 → "hard_brake"
  - Check fwdMax > HARD_ACCEL_FWD_MAX && deltaSpeed > 0 → "hard_accel"
  - Check latMax > SWERVE_LAT_MAX → "swerve"
  - Check latMax > AGGRESSIVE_CORNER_LAT_MAX && abs(deltaCourse) > AGGRESSIVE_CORNER_DCOURSE → "aggressive_corner"
  - Default → "normal"

### 2.3 Additional Metrics
- **Smoothness Score**: `max(0, 1 - (0.2*fwdRms + 0.8*latRms) / 9.0) * 100`
- **Jerk**: Rate of change of signed RMS between consecutive fixes
- **Reaction Time**: Time between fwd and lat spikes in raw data (≥100ms)

## 3. TrackingService Implementation

### 3.1 Store Previous Sample
- Add `previousSample: TrackingSample?` field to track deltas
- Store previous speed and bearing for delta calculations

### 3.2 Update computeAccelMetrics()
- Apply moving average smoothing with MOVING_AVG_WINDOW = 20
- Compute and store fwdMean, latMean in AccelMetrics
- Compute deltaSpeed and deltaCourse from previous sample

### 3.3 Create computeDriverMetrics()
- Call classifyDriverEvent() for event detection
- Calculate smoothness score
- Calculate jerk from signed RMS change
- Analyze raw data for reaction time (if available)
- Return DriverMetrics object

### 3.4 Update Sample Creation
- After creating TrackingSample, call computeDriverMetrics()
- Add driverMetrics to the sample
- Update TrackingState with event count

## 4. JSON Export

### 4.1 Update JsonWriter
- Add "driver" section after "accel" in JSON output
- Export all driver metrics fields
- Include events list, primary event, smoothness, jerk, reaction time

## 5. UI Implementation - Driving View Dialog

### 5.1 Event Display
- Add event icon next to speed display
- Implement event icon mapping (same as KML icons)
- Show event for 2 seconds, then fade over 10 seconds

### 5.2 Event Icons
- hard_brake: forbidden icon (red)
- hard_accel: triangle (orange)
- swerve: caution (magenta)
- aggressive_corner: lightning (yellow)
- normal: shaded dot (green)
- low_speed: shaded dot (grey)

### 5.3 Fading Animation
- Use LaunchedEffect to handle timing
- Alpha animation: 1.0 for 2s, then fade to 0.0 over 10s
- New event resets animation

### 5.4 Event Counter
- Add event count display above Fwd RMS at bottom
- Track total events detected during current session
- Format: "Events: 42"

### 5.5 Tracking Time and Smoothness
- Display elapsed time and smoothness score next to event count
- Format: "Time: 5:23 | Smooth: 85"
- Use TrackingState.elapsedMillis for time
- Use latestSample's driverMetrics.smoothnessScore
- Color code smoothness: Green (>70), Yellow (40-70), Red (<40)

## 6. State Management

### 6.1 TrackingState Updates
- Add `driverEventCount: MutableStateFlow<Int>`
- Add `currentDriverEvent: MutableStateFlow<DriverEvent?>`
- Update event count when new events detected

### 6.2 Event Persistence
- Store event count in SharedPreferences
- Load on app start, reset on new recording

## Implementation Order

1. Create DriverMetrics data class
2. Update TrackingSample and TrackingState
3. Implement event classification in TrackingService
4. Update computeAccelMetrics with smoothing and delta calculations
5. Create computeDriverMetrics function
6. Update JsonWriter for driver metrics export
7. Update DrivingViewDialog with event display and animations
8. Add event counter and tracking time display
9. Test with sample data

## Testing Considerations

- Test event detection with various driving scenarios
- Verify smoothness score calculation matches Python implementation
- Test event icon display and fading animation
- Validate JSON export format matches expectations
- Test reaction time detection with simulated data
