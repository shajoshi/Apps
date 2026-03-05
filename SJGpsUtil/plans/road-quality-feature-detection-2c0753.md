# Road Quality and Feature Detection Implementation Plan

Implement automated detection and classification of road features (potholes, speed bumps) and road quality (smooth/average/rough) using accelerometer data collected during bike rides with phone mounted on handlebars.

## Research Findings

### Proven Methods from Literature

**1. Peak Detection with Thresholds (Most Common)**
- Remove gravity component by subtracting mean acceleration
- Apply moving average filter to smooth signal
- Detect peaks above/below threshold values
- Count peaks to assess road quality
- Used successfully in multiple studies (Gonzalez 2017, Road_Damage_Detection project)

**2. Z-Axis Pattern Recognition**
- **Speed Bumps**: Characteristic pattern of Z-axis increase followed by decrease (symmetric bump)
- **Potholes**: Sharp Z-axis drop followed by recovery (asymmetric dip)
- **Regular Bumps**: Single Z-axis spike
- Pattern duration and shape distinguish features

**3. Feature Extraction Approaches**
- **RMS (Root Mean Square)**: Best overall roughness indicator
- **Peak Magnitude**: Detects severe anomalies (potholes, large bumps)
- **Peak Count**: Number of threshold exceedances per distance/time
- **Standard Deviation**: Measures vibration variability
- **Z-DIFF**: Difference between consecutive Z values (detects rapid changes)

**4. Classification Thresholds (from research)**
- **Smooth Road**: RMS < 1.5 m/s², few peaks
- **Average Road**: RMS 1.5-3.0 m/s², moderate peaks
- **Rough Road**: RMS > 3.0 m/s², many peaks
- **Pothole**: Z-axis drop > 15 m/s² magnitude, asymmetric pattern
- **Speed Bump**: Z-axis change > 10 m/s², symmetric rise-fall pattern

### Dataset Reference
- Gonzalez 2017 dataset: 500 samples at 50 Hz, 5 categories (asphalt bumps, metal bumps, potholes, regular road, worn-out road)
- Your current setup: ~50-100 Hz (SENSOR_DELAY_GAME), 250-500 samples per 5s GPS interval

### Important: Motorcycle Suspension Damping Effect

**Challenge**: Motorcycle suspension will filter/dampen road surface inputs before they reach the handlebars, reducing signal amplitude and potentially masking smaller road defects.

**Impact on Detection**:
1. **Reduced Amplitude**: Bumps/potholes will show smaller acceleration peaks than on rigid-mounted systems
2. **Frequency Filtering**: Suspension acts as low-pass filter, smoothing high-frequency vibrations
3. **Speed Dependency**: At higher speeds, suspension compression increases, further damping signals
4. **Handlebar Isolation**: Modern bikes have rubber-mounted handlebars for comfort, adding another damping layer

**Mitigation Strategies**:
1. **Lower Thresholds**: Adjust detection thresholds downward from research values (which often use car dashboards or rigid mounts)
2. **Relative Detection**: Focus on relative changes rather than absolute values - compare current segment to baseline
3. **Pattern Recognition Over Magnitude**: Emphasize pattern shape (symmetric bump, asymmetric dip) over peak magnitude
4. **Calibration Mode**: Add user calibration to establish baseline for their specific bike/mount setup
5. **Speed Compensation**: Adjust thresholds based on current speed (higher speed = lower thresholds)
6. **Multi-Axis Analysis**: Use X and Y axes too - lateral movements may be less damped than vertical

**Recommended Threshold Adjustments for Motorcycle**:
- Smooth road: RMS < 1.0 m/s² (reduced from 1.5)
- Average road: RMS 1.0-2.0 m/s² (reduced from 1.5-3.0)
- Rough road: RMS > 2.0 m/s² (reduced from 3.0)
- Peak threshold: 1.5 m/s² (reduced from 2.5)
- Speed bump: Z-change > 2.0 m/s² (reduced from 3.0)
- Pothole: Z-drop > 2.5 m/s² (reduced from 4.0)

**Testing Priority**: Real-world calibration on known road conditions with your specific motorcycle will be critical for accurate detection.

## Implementation Plan

### Phase 1: Real-Time Feature Detection (On-Device)

**1.1 Enhance `computeAccelMetrics()` in TrackingService**
- Add peak detection logic
- Count peaks above/below thresholds
- Detect Z-axis patterns for potholes/speed bumps
- Calculate standard deviation
- Return extended metrics array

**1.2 Add Detection Results to TrackingSample**
- `roadQuality: String?` ("smooth", "average", "rough")
- `featureDetected: String?` ("pothole", "speed_bump", "bump", null)
- `peakCount: Int?`
- `stdDev: Float?`

**1.3 Update File Writers**
- Add new fields to KML ExtendedData
- Add to GPX extensions
- Add to JSON accel object

**1.4 Real-Time UI Feedback**
- Show current road quality on Tracking screen
- Alert/vibrate on pothole/speed bump detection
- Display feature count in session

### Phase 2: Post-Processing Analysis (Track History)

**2.1 Create Analysis Module**
- `RoadQualityAnalyzer.kt`: Analyze saved tracks
- Parse accelerometer data from files
- Apply filtering and peak detection
- Generate road quality report

**2.2 Track Statistics**
- Overall road quality score (0-10)
- Feature counts (potholes, speed bumps, bumps)
- Smooth/average/rough segment percentages
- Worst sections (by GPS coordinates)

**2.3 Visualization in Track History**
- Color-code track segments by quality (green/yellow/red)
- Mark detected features on map
- Show quality histogram/chart

### Phase 3: Machine Learning Enhancement (Optional Future)

**3.1 Data Collection Mode**
- Manual labeling interface for training data
- Export labeled dataset for ML training
- Collect diverse road conditions

**3.2 ML Model Integration**
- Train classifier (Linear Regression or Random Forest)
- Integrate TensorFlow Lite model
- Improve detection accuracy over time

## Technical Implementation Details

### Detection Algorithm (Phase 1)

```kotlin
// In computeAccelMetrics()
private fun computeAccelMetrics(): AccelMetrics? {
    synchronized(accelLock) {
        if (accelBuffer.isEmpty()) return null
        
        // 1. Remove gravity (subtract mean)
        val meanZ = accelBuffer.map { it[2] }.average().toFloat()
        val detrended = accelBuffer.map { 
            floatArrayOf(it[0], it[1], it[2] - meanZ) 
        }
        
        // 2. Apply moving average filter (window size 5)
        val smoothed = applyMovingAverage(detrended, windowSize = 5)
        
        // 3. Calculate metrics
        var sumX = 0f; var sumY = 0f; var sumZ = 0f
        var maxMagnitude = 0f
        var sumSquares = 0f
        var peakCount = 0
        val magnitudes = mutableListOf<Float>()
        
        smoothed.forEach { values ->
            sumX += values[0]; sumY += values[1]; sumZ += values[2]
            val magnitude = sqrt(values[0]² + values[1]² + values[2]²)
            magnitudes.add(magnitude)
            if (magnitude > maxMagnitude) maxMagnitude = magnitude
            sumSquares += magnitude * magnitude
            
            // Peak detection (threshold = 2.5 m/s² for bike)
            if (abs(values[2]) > 2.5f) peakCount++
        }
        
        val count = smoothed.size
        val meanX = sumX / count
        val meanY = sumY / count
        val meanZ = sumZ / count
        val rms = sqrt(sumSquares / count)
        val stdDev = calculateStdDev(magnitudes)
        
        // 4. Classify road quality
        val roadQuality = when {
            rms < 1.5f && peakCount < 5 -> "smooth"
            rms < 3.0f && peakCount < 15 -> "average"
            else -> "rough"
        }
        
        // 5. Detect features (analyze Z-axis pattern)
        val feature = detectFeature(smoothed)
        
        accelBuffer.clear()
        
        return AccelMetrics(
            meanX, meanY, meanZ, maxMagnitude, rms,
            peakCount, stdDev, roadQuality, feature
        )
    }
}

private fun detectFeature(data: List<FloatArray>): String? {
    // Look for characteristic patterns in Z-axis
    val zValues = data.map { it[2] }
    
    // Speed bump: symmetric rise and fall
    val hasSymmetricBump = detectSymmetricPattern(zValues, threshold = 3.0f)
    if (hasSymmetricBump) return "speed_bump"
    
    // Pothole: sharp drop and recovery
    val hasAsymmetricDip = detectAsymmetricDip(zValues, threshold = -4.0f)
    if (hasAsymmetricDip) return "pothole"
    
    // Single bump
    if (zValues.any { abs(it) > 3.5f }) return "bump"
    
    return null
}
```

### Threshold Calibration

**Initial Values (for bike on handlebars)**
- Smooth road: RMS < 1.5 m/s²
- Average road: RMS 1.5-3.0 m/s²
- Rough road: RMS > 3.0 m/s²
- Peak threshold: 2.5 m/s² (Z-axis deviation)
- Speed bump: Z-change > 3.0 m/s², symmetric
- Pothole: Z-drop > 4.0 m/s², asymmetric

**Adjustment Strategy**
- Add settings UI for threshold tuning
- Collect real-world data and adjust
- Consider speed factor (faster = higher thresholds)

### File Structure Changes

**New Files**
- `RoadQualityAnalyzer.kt` - Analysis logic
- `AccelMetrics.kt` - Extended metrics data class
- `FeatureDetector.kt` - Pattern recognition algorithms
- `RoadQualitySettings.kt` - Threshold configuration

**Modified Files**
- `TrackingService.kt` - Enhanced detection
- `TrackingSample.kt` - Add quality/feature fields
- All writers (KML, GPX, JSON) - Write new fields
- `TrackingScreen.kt` - Show quality/features
- `TrackHistoryScreen.kt` - Visualize quality

## Benefits of This Approach

1. **Real-time feedback**: Immediate alerts during ride
2. **No ML required initially**: Rule-based detection works well
3. **Low overhead**: Processing happens once per GPS sample
4. **Proven methods**: Based on published research
5. **Extensible**: Can add ML later for better accuracy
6. **Practical**: Designed for bike-mounted phone use case

## Testing Strategy

1. **Controlled Testing**
   - Test on known smooth road
   - Test on known rough road
   - Test over speed bumps
   - Test over potholes
   - Verify detection accuracy

2. **Threshold Tuning**
   - Collect data from various conditions
   - Adjust thresholds based on false positives/negatives
   - Consider bike speed impact

3. **Validation**
   - Compare detected features with manual observations
   - Check road quality scores against subjective assessment
   - Iterate on algorithm parameters

## Next Steps

1. Implement Phase 1 core detection in `TrackingService`
2. Add UI display of road quality and features
3. Test on real roads and tune thresholds
4. Implement Phase 2 post-processing analysis
5. Add visualization to Track History
6. (Optional) Collect labeled data for ML training

## References

- Gonzalez 2017: "Learning Roadway Surface Disruption Patterns Using Bag of Words"
- Road_Damage_Detection project (GitHub)
- Z-THRESH and Z-DIFF algorithms for pothole detection
- Speed bump detection using Z-axis pattern recognition
