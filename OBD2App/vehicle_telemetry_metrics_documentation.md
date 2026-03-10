# OBD2 Vehicle Telemetry Metrics Collection and Calculation System

## Overview

This document describes the comprehensive vehicle telemetry system implemented in the OBD2 Android application. The system collects data from multiple sources (OBD2, GPS, accelerometer) and computes both primary and derived metrics using vehicle profile data for accurate calculations.

## 1. Data Sources

### 1.1 OBD2 Data Collection
The system polls vehicle ECUs using standardized OBD2 PIDs (Parameter IDs) at configurable intervals. Data is collected via Bluetooth OBD2 adapters.

**Primary OBD2 Metrics:**

- **Engine RPM**: PID 010C - Engine revolutions per minute
- **Vehicle Speed**: PID 010D - Vehicle speed in km/h (OBD source)
- **Engine Load**: PID 0104 - Calculated engine load percentage
- **Throttle Position**: PID 0111 - Throttle valve opening percentage
- **Coolant Temperature**: PID 0105 - Engine coolant temperature in °C
- **Intake Air Temperature**: PID 010F - Intake manifold air temperature in °C
- **Oil Temperature**: PID 015C - Engine oil temperature in °C
- **Ambient Temperature**: PID 0146 - Ambient air temperature in °C
- **Fuel Level**: PID 012F - Fuel tank level percentage
- **Fuel Pressure**: PID 010A - Fuel rail pressure in kPa
- **Fuel Rate**: PID 015E - Engine fuel rate in L/h
- **Mass Air Flow (MAF)**: PID 0110 - Mass air flow rate in g/s
- **Manifold Absolute Pressure (MAP)**: PID 010B - Intake manifold pressure in kPa
- **Barometric Pressure**: PID 0133 - Barometric pressure in kPa
- **Timing Advance**: PID 010E - Ignition timing advance in degrees
- **Fuel Trim**: PIDs 0106, 0107, 0108, 0109 - Short/Long term fuel trim percentages
- **Oxygen Sensor Voltage**: PID 0114 - O2 sensor voltage
- **Control Module Voltage**: PID 0142 - Vehicle battery voltage
- **Engine Runtime**: PID 011F - Time since engine start in seconds
- **Distances**: PIDs 0121, 0131 - Distance traveled with MIL on/cleared in km
- **Torque**: PIDs 0161, 0162, 0163 - Driver demand torque, actual torque percentages, and reference torque in Nm

### 1.2 GPS Data Collection
GPS data provides location and motion information as a backup/alternative to OBD2 speed data.

**GPS Metrics:**

- **GPS Speed**: Ground speed in km/h
- **Position**: Latitude/Longitude coordinates
- **Altitude**: Mean Sea Level (MSL) and ellipsoid altitudes in meters
- **Accuracy**: Horizontal and vertical position accuracy in meters
- **Bearing**: Direction of travel in degrees
- **Satellite Count**: Number of satellites used for fix

### 1.3 Accelerometer Data Collection
Linear acceleration data is collected using device sensors when enabled, providing vehicle dynamics information.

**Accelerometer Setup:**

- Uses Android `TYPE_LINEAR_ACCELERATION` sensor (gravity-filtered)
- Data collected at sensor-native frequency (typically 50-100 Hz)
- Samples buffered between OBD2 poll cycles
- Vehicle coordinate system established at trip start using captured gravity vector

## 2. Vehicle Profile Integration

Vehicle profiles contain calibration data essential for accurate metric calculations:

```kotlin
data class VehicleProfile(
    val fuelType: FuelType,
    val tankCapacityL: Float,
    val fuelPricePerLitre: Float,
    val vehicleMassKg: Float,
    // ... polling configuration
)
```

**Fuel Type Properties:**

- **MAF Conversion Factor**: Converts MAF (g/s) to fuel flow rate (L/h)

  - Petrol: 0.0000746 L/g
  - Diesel: 0.0000594 L/g
  - E20: 0.0000751 L/g
  - CNG: 0.0000740 L/g

- **CO₂ Factor**: g CO₂ per L/100km for emissions calculation

  - Petrol: 23.1 g/L·100km
  - Diesel: 26.4 g/L·100km

- **Energy Density**: MJ/L for thermodynamic power calculations

  - Petrol: 34.2 MJ/L
  - Diesel: 38.6 MJ/L

## 3. Primary Metric Calculations

### 3.1 Speed Selection Logic
The system uses GPS speed when available, falling back to OBD2 speed:

```
speedKmh = gpsSpeed ?: obdSpeedKmh ?: 0f
```

This provides more accurate speed data as GPS measures ground speed while OBD2 may report wheel speed.

### 3.2 Fuel Rate Calculation
Fuel consumption rate is determined hierarchically:

1. **Direct OBD2 Fuel Rate** (PID 015E): Used when available
2. **MAF-based Calculation**: `fuelRateLh = MAF × fuelType.mafLitreFactor × 3600`

The MAF-based calculation uses the fuel-specific conversion factor to estimate fuel flow from air mass flow.

## 4. Derived Metrics Calculations

### 4.1 Instantaneous Fuel Efficiency
**Units**: L/100km and km/L

**Calculation**:
```
instantLpk = (fuelRateEffectiveLh × 100) / speedKmh    (if speedKmh > 2 km/h)
instantKpl = 100 / instantLpk                           (if instantLpk > 0)
```

**Physics**: Fuel efficiency is calculated as the ratio of fuel consumption rate to distance traveled per unit time.

### 4.2 Trip Accumulation
Trip metrics are accumulated over time using discrete time integration:

**Distance Calculation**:
```
Δdistance = speedKmh × Δtime_hours
tripDistanceKm += Δdistance
```

**Fuel Used Calculation**:
```
Δfuel = fuelRateLh × Δtime_hours
tripFuelUsedL += Δfuel
```

**Time Categorization**:
- Moving time: speed > 2 km/h
- Stopped time: speed ≤ 2 km/h

### 4.3 Average Trip Metrics

**Average Fuel Efficiency**:
```
tripAvgLpk = (tripFuelUsedL × 100) / tripDistanceKm    (if tripDistanceKm > 0.1 km)
tripAvgKpl = 100 / tripAvgLpk                           (if tripAvgLpk > 0)
```

**Average Speed**:
```
avgSpeedKmh = tripDistanceKm / (movingTimeSec / 3600)
```

### 4.4 Range Estimation
**Calculation**:
```
remainingFuelL = fuelLevel% × tankCapacityL / 100
rangeKm = remainingFuelL / (tripAvgLpk / 100)
```

Uses current fuel level and historical average efficiency to estimate remaining range.

### 4.5 Fuel Cost Calculation
```
fuelCost = tripFuelUsedL × fuelPricePerLitre
```

### 4.6 CO₂ Emissions
```
co2gPerKm = tripAvgLpk × fuelType.co2Factor
```

Uses fuel-type specific CO₂ emission factors to calculate environmental impact.

## 5. Accelerometer Metrics and Vehicle Dynamics

### 5.1 Vehicle Coordinate System Establishment

At trip start, the system captures the gravity vector and establishes a vehicle-fixed coordinate system:

1. **Gravity Vector Capture**: First accelerometer reading after trip start
2. **Coordinate System**:
   - **Vertical (ĝ)**: Normalized gravity vector
   - **Forward**: Device Y-axis projected onto horizontal plane
   - **Lateral**: Cross product of vertical and forward vectors

### 5.2 Acceleration Metrics Calculation

**Raw Data Processing**:
1. **Bias Removal**: Subtract average acceleration over sample window
2. **Moving Average Filtering**: Smooth data using configurable window size
3. **Coordinate Transformation**: Project accelerations into vehicle frame

**Vertical Acceleration Metrics**:
- **RMS**: `√(Σa_vert²/N)` - Overall vertical vibration intensity
- **Peak**: Maximum absolute vertical acceleration
- **Mean**: Average vertical acceleration (should be ~0 after bias removal)
- **Standard Deviation**: Measure of vertical acceleration variability
- **Peak Ratio**: Fraction of samples exceeding threshold (road quality indicator)

**Longitudinal Acceleration Metrics**:
- **RMS**: Forward/backward acceleration intensity
- **Maximum Acceleration**: Peak positive acceleration (hard acceleration)
- **Maximum Braking**: Peak negative acceleration (hard braking)
- **Mean**: Average longitudinal acceleration

**Lateral Acceleration Metrics**:
- **RMS**: Side-to-side acceleration intensity
- **Maximum**: Peak lateral acceleration
- **Mean**: Average lateral acceleration

### 5.3 Lean Angle Calculation
```
leanAngle° = atan2(lateral_gravity_component, vertical_gravity_component) × 180/π
```

Calculates vehicle lean angle from gravity vector components in vehicle coordinates.

## 6. Power Calculations

The system provides three independent power estimates:

### 6.1 Accelerometer-based Power (P_accel)
**Physics**: Power = Force × Velocity = (Mass × Acceleration) × Velocity

```
P_accel_kW = (vehicleMassKg × a_forward_m/s² × speed_m/s) / 1000
```

**Limitations**: Only captures acceleration power, ignores aerodynamic/rolling resistance, requires accurate mass input.

### 6.2 Thermodynamic Power (P_thermo)
**Physics**: Power = Energy Flow Rate × Efficiency

```
P_thermo_kW = (fuelRate_L/h × energyDensity_MJ/L × 3.6e6_J/h × 0.35_efficiency) / 1e6 / 3600
```

Where:
- Energy flow rate = fuelRate × energyDensity × 1000 (J/s)
- 0.35 = assumed thermal efficiency for internal combustion engines
- Result converted to kW

### 6.3 OBD2-based Power (P_OBD)
**Physics**: Power = Torque × Angular Velocity

```
P_OBD_kW = (actualTorque% × referenceTorque_Nm × rpm × 2π) / (100 × 60)
```

Uses ECU-reported torque and engine speed to calculate mechanical power output.

## 7. Trip Statistics and Drive Mode Classification

### 7.1 Drive Mode Classification
Classifies driving style using 60-second rolling window of speed data:

- **City Driving**: 2 < speed ≤ 60 km/h
- **Highway Driving**: speed > 60 km/h
- **Idle**: speed ≤ 2 km/h

**Percentages**:
```
pctCity = (city_samples / total_samples) × 100
pctHighway = (highway_samples / total_samples) × 100
pctIdle = (idle_samples / total_samples) × 100
```

### 7.2 Trip Time Management
**Active Trip Time**: Total elapsed time minus paused periods
**Moving vs Stopped Time**: Categorized based on speed thresholds
**Maximum Speed**: Peak speed recorded during trip

## 8. Data Processing Architecture

### 8.1 Collection Pipeline
1. **OBD2 Polling**: Asynchronous collection via Bluetooth
2. **GPS Updates**: Continuous location updates
3. **Accelerometer Buffering**: High-frequency samples between poll cycles
4. **Metric Calculation**: Synchronous computation on main thread
5. **Trip Accumulation**: State updates for running totals
6. **Logging**: Optional JSON logging to device storage

### 8.2 Threading and Synchronization
- **Collection**: Background coroutines for I/O operations
- **Calculation**: Main thread for UI responsiveness
- **State Management**: MutableStateFlow for reactive UI updates

### 8.3 Error Handling and Fallbacks
- **Speed**: GPS → OBD2 → 0 fallback
- **Fuel Rate**: Direct OBD2 → MAF calculation → null
- **Coordinate System**: Vehicle basis → device-Z fallback
- **Power Calculations**: Independent estimates with null handling

## 9. Calibration and Configuration

### 9.1 Accelerometer Calibration
- **Moving Average Window**: Configurable smoothing window size
- **Peak Threshold**: Minimum acceleration for peak detection
- **Bias Estimation**: Automatic bias removal from sample windows

### 9.2 Vehicle Profile Validation
- **Mass**: Must be > 0 for acceleration-based power
- **Fuel Price**: Optional for cost calculations
- **Tank Capacity**: Required for range estimation

### 9.3 OBD2 Protocol Configuration
- **Polling Intervals**: Configurable delays between PID requests
- **Command Timeouts**: Timeout handling for slow ECU responses
- **PID Availability**: Automatic detection of supported parameters

## Conclusion

This telemetry system provides comprehensive vehicle monitoring through the integration of multiple data sources with physics-based calculations. The use of vehicle profiles ensures accuracy across different vehicle configurations, while the multi-source approach provides redundancy and enhanced measurement capabilities. The accelerometer integration adds valuable vehicle dynamics information that complements traditional OBD2 metrics.
