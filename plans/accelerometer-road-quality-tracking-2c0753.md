# Add Accelerometer Data for Road Quality Tracking

Integrate accelerometer sensor data collection synchronized with GPS tracking to enable road quality assessment based on vibration patterns.

## Overview

The app currently tracks GPS location, speed, bearing, and accuracy. To assess road quality, we need to capture accelerometer data (X, Y, Z axis vibrations) at the same frequency as GPS samples and store them together in the track files.

## Recommended Approach

### 1. Data Collection Strategy

**Sync with GPS samples (Recommended)**
- Register accelerometer listener when tracking starts
- Buffer accelerometer readings between GPS samples
- Calculate aggregate metrics (mean, max, RMS) for each GPS interval
- Attach aggregated accelerometer data to each GPS point
- Benefits: Compact storage, synchronized timeline, easier analysis
- Tradeoff: Loses fine-grained vibration detail but captures overall road roughness

**Alternative: Independent high-frequency logging**
- Log raw accelerometer at high rate (50-100 Hz)
- Store separately with timestamps
- Benefits: Full vibration detail for advanced analysis
- Tradeoff: Much larger file sizes, complex synchronization

### 2. Implementation Plan

#### Phase 1: Data Model Changes
- Extend `TrackingSample` data class to include accelerometer fields:
  - `accelXMean`, `accelYMean`, `accelZMean` (average acceleration)
  - `accelMagnitudeMax` (peak vibration magnitude)
  - `accelRMS` (root mean square - good roughness indicator)
- Update `TrackingState` to hold accelerometer buffer

#### Phase 2: Sensor Integration
- Add accelerometer listener in `TrackingService`
- Buffer accelerometer readings between GPS samples
- Calculate aggregates when GPS sample arrives
- Attach to `TrackingSample` before writing

#### Phase 3: File Format Updates
- **KML**: Add accelerometer data to ExtendedData fields
- **GPX**: Add custom namespace extension for accelerometer values
- **JSON**: Add `accel` object alongside `gps` object in data array

#### Phase 4: UI Enhancements (Optional)
- Display current vibration level on Tracking screen
- Add road quality visualization in track history
- Color-code tracks by roughness level

### 3. Technical Considerations

**Sensor Registration**
- Use `SensorManager.SENSOR_TYPE_ACCELEROMETER`
- Sample rate: `SENSOR_DELAY_NORMAL` or `SENSOR_DELAY_UI` (sufficient for road quality)
- Register/unregister with tracking lifecycle

**Coordinate System**
- Android accelerometer uses device coordinates
- Consider device orientation (portrait/landscape/mount angle)
- May need to transform to world coordinates or just use magnitude

**Filtering**
- Apply high-pass filter to remove gravity component
- Focus on vibrations (typically 1-20 Hz for road roughness)
- Low-pass filter to remove noise

**Battery Impact**
- Accelerometer is low-power sensor
- Minimal impact compared to GPS
- Can disable via settings if needed

### 4. File Format Examples

**KML ExtendedData addition:**
```xml
<Data name="accelXMean"><value>0.15</value></Data>
<Data name="accelYMean"><value>-0.08</value></Data>
<Data name="accelZMean"><value>9.82</value></Data>
<Data name="accelMagnitudeMax"><value>2.34</value></Data>
<Data name="accelRMS"><value>0.45</value></Data>
```

**GPX extension addition:**
```xml
<extensions>
  <sj:accel>
    <sj:xMean>0.15</sj:xMean>
    <sj:yMean>-0.08</sj:yMean>
    <sj:zMean>9.82</sj:zMean>
    <sj:magMax>2.34</sj:magMax>
    <sj:rms>0.45</sj:rms>
  </sj:accel>
</extensions>
```

**JSON addition:**
```json
{
  "gps": { ... },
  "accel": {
    "xMean": 0.15,
    "yMean": -0.08,
    "zMean": 9.82,
    "magMax": 2.34,
    "rms": 0.45
  }
}
```

### 5. Settings Integration

Add new settings option:
- "Enable accelerometer tracking" toggle
- Default: enabled
- Allows users to disable if not needed

## Files to Modify

1. **TrackingState.kt** - Add accelerometer fields to TrackingSample
2. **TrackingService.kt** - Register sensor, buffer readings, calculate aggregates
3. **KmlWriter.kt** - Write accelerometer ExtendedData
4. **GpxWriter.kt** - Write accelerometer extensions
5. **JsonWriter.kt** - Write accelerometer object
6. **SettingsRepository.kt** - Add accelerometer enable/disable setting
7. **SettingsScreen.kt** - Add accelerometer toggle UI
8. **TrackingScreen.kt** (optional) - Display current vibration level

## Next Steps

1. Confirm this approach meets your road quality tracking needs
2. Decide on aggregation metrics (mean, max, RMS, or others)
3. Determine if you want UI visualization or just data collection
4. Implement in phases starting with data model changes
