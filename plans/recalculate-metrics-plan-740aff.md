# OBD2 Track Metrics Recalculation & Validation Script

Create a Python script to recalculate and validate metrics from OBD2 track JSON files, replicating the calculations from the Kotlin MetricsCalculator for offline analysis and validation.

## Overview

The script will:
- Read multiple track JSON files and a vehicle profile JSON
- Recalculate all fuel, trip, and derived metrics using the same algorithms as the Android app
- Generate per-track and combined aggregate statistics
- Output analysis results, distribution plots, and KML visualizations

## Key Components to Replicate

### 1. **FuelCalculator** (from `FuelCalculator.kt`)
- **Effective Fuel Rate Calculation** (3-tier fallback):
  - Direct OBD PID 015E (fuel rate L/h)
  - MAF-based calculation with diesel AFR correction
  - Speed-Density estimation (MAP + IAT + RPM + displacement)
- **Diesel AFR Correction**: Boost-aware correction for turbocharged diesel engines
- **Instantaneous Fuel Efficiency**: L/100km and km/L calculations
- **Trip Average Efficiency**: Accumulated fuel/distance with 0.1km threshold
- **Range Estimation**: Based on fuel level % and tank capacity
- **CO2 Emissions**: Using fuel type CO2 factor
- **Fuel Cost**: Total fuel used × price per liter

### 2. **TripCalculator** (from `TripCalculator.kt`)
- **Hybrid Speed**: OBD speed ≤20 km/h, GPS speed >20 km/h
- **Average Speed**: Distance / moving time (min 30s, 50m threshold)
- **Speed Difference**: GPS - OBD speed comparison

### 3. **TripState** (from `TripState.kt`)
- **Distance Accumulation**: High-precision (meters → km)
- **Fuel Accumulation**: High-precision (milliliters → liters)
- **Time Tracking**: Moving (>2 km/h) vs stopped (≤2 km/h)
- **Drive Mode Classification**:
  - Idle: ≤2 km/h
  - City: 2-60 km/h
  - Highway: >60 km/h
- **Max Speed Tracking**

### 4. **Vehicle Profile Parameters**
From `VehicleProfile.kt`:
- `fuelType`: PETROL, E20, DIESEL, CNG (with mafMlPerGram, co2Factor, energyDensityMJpL)
- `tankCapacityL`: Tank capacity in liters
- `fuelPricePerLitre`: Fuel price for cost calculation
- `engineDisplacementCc`: For Speed-Density calculation
- `volumetricEfficiencyPct`: For Speed-Density (default 85%)
- `vehicleMassKg`: For acceleration-based power (optional)

## Script Features

### Input
- **Track Files**: Multiple JSON files (format: `vehicle_obdlog_YYYY-MM-DD_HHMMSS.json`)
- **Vehicle Profile**: JSON file with vehicle configuration
- **Command-line Arguments**:
  - `-p, --profile`: Vehicle profile JSON path (required)
  - `-t, --tracks`: Track file paths (multiple, required)
  - `-o, --output`: Output directory (default: current directory)
  - `--kml`: Generate KML files for visualization
  - `--plots`: Generate distribution plots

### Output

#### Per-Track Analysis
For each track file:
- **Recalculated Metrics**:
  - Trip distance (km)
  - Trip fuel used (L)
  - Average fuel efficiency (L/100km, km/L)
  - Average speed (km/h)
  - Max speed (km/h)
  - Moving time, stopped time, total time
  - % Idle, % City, % Highway
  - Fuel cost estimate
  - Average CO2 emissions (g/km)

- **Validation Report**:
  - Compare recalculated vs logged values
  - Identify discrepancies
  - Sample-by-sample comparison statistics

#### Combined Analysis
Across all tracks:
- **Aggregate Statistics**:
  - Total distance traveled
  - Total fuel consumed
  - Combined average efficiency
  - Combined average speed
  - Total moving/stopped time
  - Overall drive mode percentages
  - Total fuel cost
  - Average CO2 emissions

- **Distribution Analysis**:
  - Speed distribution histogram
  - RPM distribution histogram
  - Fuel rate distribution
  - Throttle position distribution
  - Engine load distribution

#### Visualization Outputs
- **KML Files** (optional):
  - Color-coded tracks by speed
  - Color-coded tracks by fuel efficiency
  - Color-coded tracks by RPM
  - Drive mode visualization (idle/city/highway)

- **Distribution Plots** (optional):
  - Speed vs time
  - Fuel efficiency vs time
  - RPM vs time
  - Drive mode pie chart
  - Fuel rate histogram

### Data Structure

#### Track JSON Format (from sample)
```json
{
  "header": {
    "appVersion": "1.0",
    "logStartedAt": "ISO timestamp",
    "logStartedAtMs": timestamp,
    "vehicleProfile": { vehicle config },
    "supportedPids": [],
    "accelerometer": { config }
  },
  "samples": [
    {
      "timestampMs": long,
      "sampleNo": int,
      "gps": { lat, lon, speedKmh, altMsl, accuracyM, bearingDeg, satelliteCount },
      "obd": { rpm, speedKmh, engineLoadPct, throttlePct, coolantTempC, mafGs, intakeMapKpa, ... },
      "fuel": { fuelRateEffectiveLh, instantLper100km, instantKpl, tripFuelUsedL, tripAvgLper100km, ... },
      "accel": { vertRms, fwdMax, latMax, ... },
      "trip": { distanceKm, timeSec, movingTimeSec, avgSpeedKmh, maxSpeedKmh, pctCity, pctHighway, pctIdle }
    }
  ]
}
```

#### Vehicle Profile JSON Format
```json
{
  "id": "uuid",
  "name": "Vehicle Name",
  "fuelType": "E20|PETROL|DIESEL|CNG",
  "tankCapacityL": 40.0,
  "fuelPricePerLitre": 105.0,
  "engineDisplacementCc": 1200,
  "volumetricEfficiencyPct": 85.0,
  "vehicleMassKg": 1200.0
}
```

## Implementation Plan

### Phase 1: Core Calculation Classes
1. Create `FuelCalculator` class with all methods
2. Create `TripCalculator` class with hybrid speed logic
3. Create `TripState` class for accumulation
4. Create `FuelType` enum with constants

### Phase 2: Data Loading & Parsing
1. JSON track file loader
2. Vehicle profile loader
3. Sample data extractor

### Phase 3: Metric Recalculation Engine
1. Sample-by-sample processing loop
2. Metric calculation per sample
3. Trip state accumulation
4. Validation against logged values

### Phase 4: Analysis & Reporting
1. Per-track statistics calculator
2. Combined aggregate calculator
3. Distribution analysis
4. Console output formatter
5. CSV/JSON export

### Phase 5: Visualization (Optional)
1. KML generator (reuse existing `analyze_track.py` logic)
2. Distribution plots (matplotlib)
3. Time-series plots
4. Drive mode pie charts

## Files to Create

1. **`recalculate_metrics.py`**: Main script with CLI
2. **`obd_calculators.py`**: FuelCalculator, TripCalculator, TripState classes
3. **`obd_types.py`**: FuelType enum, data classes
4. **`track_loader.py`**: JSON loading and parsing
5. **`metrics_validator.py`**: Comparison and validation logic
6. **`report_generator.py`**: Output formatting and reporting

## Dependencies
- `argparse`: Command-line argument parsing
- `json`: JSON file handling
- `dataclasses`: Data structure definitions
- `enum`: Enum types
- `typing`: Type hints
- `matplotlib` (optional): Plotting
- `numpy` (optional): Statistical analysis
- Existing `analyze_track.py`: KML generation (reuse)

## Success Criteria
- Successfully recalculates all metrics from scratch using raw OBD/GPS data
- Handles missing OBD sensors gracefully (same fallback logic as MetricsCalculator)
- Processes multiple track files with single vehicle profile
- Generates accurate per-track and combined statistics
- Produces clear summary reports at end of each track
- Optional visualizations work correctly

## User Requirements (Confirmed)
1. ✅ **Recalculate from scratch** - Ignore logged values, recalculate from raw OBD/GPS data
2. ✅ **No validation needed** - Don't compare against logged values
3. ✅ **Summary stats at end** - Output summary statistics at end of each track
4. ✅ **Handle missing data** - Use same fallback behavior as MetricsCalculator (3-tier fuel rate, etc.)
5. ✅ **Separate profile** - Vehicle profile always provided as separate JSON file
