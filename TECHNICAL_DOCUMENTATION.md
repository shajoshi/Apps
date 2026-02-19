# Technical Documentation: Road Quality and Driver Metrics Analysis

This document explains the physics, formulas, and algorithms used in SJ GPS Util to compute road quality metrics, detect road features, and evaluate driver behavior using smartphone accelerometer and GPS data.

---

## Table of Contents

1. [Sensor Data Acquisition](#sensor-data-acquisition)
2. [Coordinate Systems and Transformations](#coordinate-systems-and-transformations)
3. [Accelerometer Data Processing](#accelerometer-data-processing)
4. [Road Quality Classification](#road-quality-classification)
5. [Road Feature Detection](#road-feature-detection)
6. [Driver Event Detection](#driver-event-detection)
7. [Driver Smoothness Scoring](#driver-smoothness-scoring)
8. [Lean Angle Calculation](#lean-angle-calculation)
9. [Threshold Calibration](#threshold-calibration)

---

## Sensor Data Acquisition

### GPS Data
- **Sampling Rate**: Configurable (default 1 Hz)
- **Data Points**: Latitude, longitude, altitude, speed, bearing, accuracy, timestamp
- **Coordinate System**: WGS84 geographic coordinates
- **Speed Calculation**: GPS-provided speed in m/s, converted to km/h for display

### Accelerometer Data
- **Sampling Rate**: Typically 50-100 Hz (device-dependent)
- **Data Points**: X, Y, Z acceleration values in m/s²
- **Units**: m/s² (including gravity)
- **Coordinate System**: Device frame (varies with phone orientation)

---

## Coordinate Systems and Transformations

### Device Frame to Vehicle Frame
The accelerometer data is transformed from the device coordinate system to the vehicle frame to ensure consistent analysis regardless of phone mounting orientation.

#### Gravity Vector Removal
First, the gravity component is removed from raw accelerometer readings:

```
a_linear = a_raw - g_vector
```

Where:
- `a_raw` = Raw accelerometer reading [x, y, z]
- `g_vector` = Gravity vector (captured during baseline calibration)
- `a_linear` = Linear acceleration (vehicle motion only)

#### Vehicle Frame Projection
The linear acceleration is projected onto vehicle axes:

```
a_fwd = a_linear · unit_vector_forward
a_lat = a_linear · unit_vector_lateral
a_vert = a_linear · unit_vector_vertical
```

Where the unit vectors are derived from the gravity vector to align with:
- Forward: Direction of vehicle travel
- Lateral: Perpendicular to forward (left-right)
- Vertical: Up-down relative to vehicle

### Screen Rotation Compensation
The accelerometer axes are remapped based on screen rotation:

```
if (rotation == ROTATION_90):
    swap(x, y); x = -x
elif (rotation == ROTATION_270):
    swap(x, y); y = -y
```

---

## Accelerometer Data Processing

### Detrending
A moving average is used to remove low-frequency drift:

```
avg_window = 5 samples
detrended = a_linear - moving_average(a_linear, avg_window)
```

### Statistical Metrics
For each GPS fix interval (typically 1 second), the following metrics are computed from the high-frequency accelerometer samples:

#### RMS (Root Mean Square)
```
rms = sqrt(mean(a_linear²))
```

#### Standard Deviation
```
std_dev = sqrt(variance(a_linear))
```

#### Peak Values
```
peak = max(|a_linear|)
```

#### Peak Ratio
```
peak_ratio = peak / rms
```

These metrics are computed separately for each axis (forward, lateral, vertical) and for the magnitude vector.

---

## Road Quality Classification

Road quality is classified based on the statistical properties of vertical acceleration, which correlates with road surface roughness.

### Classification Algorithm
For each GPS point, the road quality is determined by comparing RMS and standard deviation thresholds:

```
if (rms_z <= rms_smooth_max && std_dev_z <= std_dev_smooth_max):
    quality = "smooth"
elif (rms_z >= rms_rough_min && std_dev_z >= std_dev_rough_min):
    quality = "rough"
else:
    quality = "average"
```

### Physics Basis
- **Smooth Roads**: Low vibration amplitude and variability
- **Rough Roads**: High vibration amplitude and variability
- **Average Roads**: Intermediate characteristics

### Threshold Parameters
- `rms_smooth_max`: Maximum RMS for smooth classification (default: 1.0 m/s²)
- `std_dev_smooth_max`: Maximum std dev for smooth classification (default: 2.5 m/s²)
- `rms_rough_min`: Minimum RMS for rough classification (default: 4.5 m/s²)
- `std_dev_rough_min`: Minimum std dev for rough classification (default: 3.0 m/s²)

---

## Road Feature Detection

Road features are detected using pattern recognition on the vertical acceleration signal.

### Detection Algorithm
Features are identified by specific acceleration patterns:

#### Speed Bumps
- Characteristic: Sharp upward peak followed by downward peak
- Algorithm: Look for peak-to-peak patterns within a short time window
- Threshold: `peak_threshold_z` (default: 1.5 m/s²)

#### Potholes
- Characteristic: Sharp downward acceleration (drop) followed by recovery
- Algorithm: Detect negative peaks exceeding threshold
- Threshold: Same as speed bumps, but negative direction

#### Jolts/Severe Impacts
- Characteristic: Very high magnitude peaks
- Algorithm: Peaks exceeding `mag_max_severe_min` (default: 20.0 m/s²)

### Pattern Recognition
```
for each peak in vertical_acceleration:
    if peak > peak_threshold_z:
        if is_speed_bump_pattern(peak):
            feature = "speed_bump"
        elif is_pothole_pattern(peak):
            feature = "pothole"
    elif abs(peak) > mag_max_severe_min:
        feature = "jolt"
```

---

## Driver Event Detection

Driver events are detected from forward and lateral acceleration patterns.

### Hard Braking
- **Physics**: Rapid deceleration causes negative forward acceleration
- **Detection**: `a_fwd < -hard_brake_fwd_max` (default: -15 m/s²)
- **Conditions**: Speed > `min_speed_kmph` (default: 6 km/h)

### Hard Acceleration
- **Physics**: Rapid acceleration causes positive forward acceleration
- **Detection**: `a_fwd > hard_accel_fwd_max` (default: 15 m/s²)
- **Conditions**: Speed > minimum threshold

### Swerving
- **Physics**: Lateral acceleration during sudden lane changes
- **Detection**: `|a_lat| > swerve_lat_max` (default: 4 m/s²)
- **Conditions**: Speed > minimum threshold

### Aggressive Cornering
- **Physics**: High lateral acceleration during turns
- **Detection**: `|a_lat| > aggressive_corner_lat_max` (default: 4 m/s²)
- **Additional**: Course change rate > `aggressive_corner_dcourse` (default: 15°/s)

### Course Change Calculation
```
course_change = abs(current_bearing - previous_bearing)
if course_change > 180:
    course_change = 360 - course_change
course_change_rate = course_change / time_interval
```

---

## Driver Smoothness Scoring

The smoothness score evaluates driving quality based on acceleration variability.

### Scoring Algorithm
For each GPS point above minimum speed:

```
smoothness_score = max(0, 100 - (rms_fwd * smoothness_rms_max_factor))
```

Where:
- `smoothness_rms_max_factor` converts RMS to penalty points (default: 10)
- Higher RMS acceleration = lower smoothness score
- Score range: 0-100 (100 = perfectly smooth)

### Overall Smoothness
The final smoothness score is the average of all point scores during the trip:

```
overall_smoothness = mean(all_smoothness_scores)
```

---

## Lean Angle Calculation

Lean angle is computed from the lateral and vertical acceleration components.

### Physics Principle
When a vehicle leans, the gravity vector shifts relative to the vehicle frame. The lean angle can be derived from the ratio of lateral to vertical acceleration.

### Calculation Formula
```
lean_angle = atan2(a_lat, a_vert) * (180 / π)
```

Where:
- `a_lat` = Lateral acceleration (m/s²)
- `a_vert` = Vertical acceleration including gravity (m/s²)
- Result in degrees (positive = right lean, negative = left lean)

### Calibration Considerations
The calculation assumes the phone is mounted vertically. For horizontal mounting, the formula would use different axis combinations.

---

## Threshold Calibration

The detection thresholds can be calibrated for different vehicle types and road conditions.

### Calibration Process
1. Collect data on known road conditions
2. Compute statistical distributions for smooth/rough roads
3. Set thresholds at appropriate percentiles:
   - Smooth thresholds: 75th percentile of smooth road data
   - Rough thresholds: 25th percentile of rough road data

### Vehicle-Specific Profiles
Different vehicles have different vibration characteristics:

#### Motorcycle
- Higher sensitivity to road irregularities
- Lower smooth thresholds due to direct road feedback
- Higher lateral acceleration during cornering

#### Car
- Suspension dampens road vibrations
- Higher smooth thresholds
- More stable lateral acceleration

#### Bicycle
- Very sensitive to small road features
- Lowest thresholds
- High variability in acceleration patterns

### Recommended Threshold Values

| Vehicle | RMS Smooth Max | StdDev Smooth Max | RMS Rough Min | StdDev Rough Min |
|---------|----------------|-------------------|---------------|------------------|
| Motorcycle | 1.0 | 2.5 | 4.5 | 3.0 |
| Car | 1.5 | 3.0 | 5.0 | 3.5 |
| Bicycle | 0.8 | 2.0 | 3.5 | 2.5 |

---

## Data Quality Considerations

### GPS Accuracy
- Minimum satellite count for reliable data: 4 satellites
- Horizontal accuracy threshold: 20 meters
- Speed accuracy degrades at low speeds (< 5 km/h)

### Accelerometer Limitations
- Device-specific sampling rates affect detection sensitivity
- Phone mounting orientation impacts measurement accuracy
- Temperature can affect sensor calibration

### Filtering Strategies
- Points with GPS accuracy > 20m are excluded from analysis
- Low-speed points (< min_speed_kmph) are excluded from driver metrics
- Outlier removal using median filtering for acceleration data

---

## Implementation Notes

### Real-time Processing
- Metrics are computed in real-time during recording
- Moving averages maintain running statistics
- Event detection uses sliding window approach

### Memory Efficiency
- Only statistical summaries are stored per GPS point
- Raw accelerometer samples are processed and discarded
- Circular buffers maintain recent history for pattern detection

### Performance Optimization
- Vectorized operations for statistical calculations
- Early termination for obvious non-events
- Adaptive sampling based on detection requirements

---

## References

1. **Vehicle Dynamics**: Rajamani, R. "Vehicle Dynamics and Control"
2. **Road Roughness**: ISO 8608:2016 "Mechanical vibration — Road surface profiles"
3. **Accelerometer Theory**: IEEE Standard Specification Format Guide and Test Procedure for Linear Accelerometers
4. **GPS Accuracy**: Kaplan, E.D. "Understanding GPS/GNSS"

---

*This documentation describes the current implementation as of version 1.1-Beta. Algorithms and thresholds may be refined in future versions based on field testing and user feedback.*
