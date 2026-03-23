# OBD2 Metrics Recalculation Script

Python script to recalculate and validate OBD2 track metrics from raw sensor data, replicating the calculations from the Android app's `MetricsCalculator.kt`.

## Features

- **Accurate Metric Recalculation**: Replicates all calculations from Kotlin codebase
- **3-Tier Fuel Rate Calculation**: PID 015E → MAF-based → Speed-Density estimation
- **Hybrid Speed Calculation**: OBD speed ≤20 km/h, GPS speed >20 km/h
- **High-Precision Accumulation**: Meters/milliliters to prevent rounding errors
- **Drive Mode Classification**: Idle (≤2 km/h), City (2-60 km/h), Highway (>60 km/h)
- **Multiple Track Support**: Process and combine statistics from multiple tracks
- **Comprehensive Reports**: Per-track and combined aggregate statistics

## Files

- `recalculate_metrics.py` - Main CLI script
- `obd_calculators.py` - FuelCalculator, TripCalculator, TripState classes
- `obd_types.py` - FuelType enum and data classes
- `track_loader.py` - JSON loading and parsing
- `report_generator.py` - Output formatting and reporting
- `ronin_profile.json` - Example vehicle profile

## Installation

No external dependencies required - uses only Python standard library.

```bash
# Python 3.10+ required for type hints
python --version
```

## Usage

### Basic Usage

```bash
# Single track
python recalculate_metrics.py -p vehicle_profile.json -t track.json

# Multiple tracks
python recalculate_metrics.py -p vehicle_profile.json -t track1.json track2.json track3.json
```

### Enhanced Features

```bash
# Generate all visualizations and exports
python recalculate_metrics.py -p vehicle_profile.json -t track.json --all

# Generate specific outputs
python recalculate_metrics.py -p vehicle_profile.json -t track.json --kml --plots --csv

# Validate with custom tolerance
python recalculate_metrics.py -p vehicle_profile.json -t track.json --validate --tolerance 2.0

# Custom output directory
python recalculate_metrics.py -p vehicle_profile.json -t track.json --all -o ./output
```

### Command Line Options

- `-p, --profile`: Vehicle profile JSON file (required)
- `-t, --tracks`: One or more track JSON files (required)
- `-o, --output`: Output directory for generated files (default: current directory)
- `--kml`: Generate KML files for track visualization
- `--plots`: Generate distribution plots and time series charts
- `--csv`: Export data to CSV files
- `--validate`: Validate recalculated metrics against logged values
- `--tolerance`: Tolerance percentage for validation (default: 5.0)
- `--power`: Include power calculations in output
- `--all`: Enable all optional features

### Example

```bash
cd OBD2App/scripts
python recalculate_metrics.py -p ronin_profile.json -t C:\path\to\Ronin_obdlog_2026-03-16_093507.json --all
```

## Vehicle Profile Format

Create a JSON file with your vehicle configuration:

```json
{
  "id": "vehicle-id",
  "name": "Vehicle Name",
  "fuelType": "E20",
  "tankCapacityL": 14.0,
  "fuelPricePerLitre": 105.0,
  "enginePowerBhp": 20.0,
  "vehicleMassKg": 240.0,
  "engineDisplacementCc": 0,
  "volumetricEfficiencyPct": 85.0
}
```

### Fuel Types

- `PETROL` - Regular petrol/gasoline
- `E20` - E20 petrol blend (20% ethanol)
- `DIESEL` - Diesel fuel
- `CNG` - Compressed Natural Gas

### Parameters

- `tankCapacityL`: Tank capacity in liters
- `fuelPricePerLitre`: Fuel price for cost calculation
- `engineDisplacementCc`: Engine displacement in cc (for Speed-Density calculation)
- `volumetricEfficiencyPct`: Volumetric efficiency % (default 85% for NA petrol)
- `vehicleMassKg`: Vehicle mass in kg (optional, for power calculations)

## Output

### Console Reports

#### Per-Track Summary

```
======================================================================
Track: Ronin_obdlog_2026-03-16_093507
======================================================================

📊 Trip Statistics:
  Samples processed:     544
  Distance traveled:     1.934 km
  Total time:            6m 4s
  Moving time:           5m 2s
  Stopped time:          1m 2s

⚡ Speed Metrics:
  Average speed:         23.1 km/h
  Max speed:             52.0 km/h

⛽ Fuel Metrics:
  Fuel consumed:         0.058 L
  Average efficiency:    2.98 L/100km
  Average efficiency:    33.52 km/L
  Fuel cost:             ₹6.06
  Avg CO₂ emissions:     0.7 g/km

🚦 Drive Mode Distribution:
  Idle (≤2 km/h):        17.0%
  City (2-60 km/h):      83.0%
  Highway (>60 km/h):    0.0%
```

#### Combined Summary (Multiple Tracks)

When processing multiple tracks, a combined summary is generated with:
- Total distance across all tracks
- Total fuel consumed
- Combined average efficiency
- Combined drive mode distribution
- Total fuel cost and CO₂ emissions

### Generated Files

#### KML Files (when `--kml` used)

- `{track}_speed.kml` - Track colored by speed (red/yellow/green)
- `{track}_fuel_efficiency.kml` - Track colored by fuel efficiency
- `{track}_rpm.kml` - Track colored by RPM
- `{track}_drive_mode.kml` - Track colored by drive mode (idle/city/highway)

#### Plot Files (when `--plots` used)

**Multiple tracks:**
- `combined_tracks_distributions.png` - Histograms of all metrics across all tracks
- `combined_tracks_drive_mode.png` - Combined drive mode distribution pie chart
- `combined_chronological_timeseries.png` - Chronological time series across all tracks (sorted by date, fuel efficiency in km/L)

**Single track:**
- `{track}_distributions.png` - Histograms of speed, RPM, fuel rate, power (BHP), throttle, engine load
- `{track}_timeseries.png` - Time series plots of metrics over time (including power in BHP)
- `{track}_drive_mode.png` - Pie chart of drive mode distribution

**Chronological Time Series:**
When processing multiple tracks, the script automatically:
- Extracts dates from track filenames (format: `Name_YYYY-MM-DD_HHMMSS`)
- Sorts tracks chronologically by date
- Adjusts timestamps to create a continuous timeline with no gaps
- Concatenates tracks seamlessly for continuous analysis
- Generates meaningful time series plots across multiple days
- Includes average lines for each metric (excluding zero values)
- Shows average values in legends and plot annotations
- Displays fuel efficiency in km/L (more intuitive than L/100km)
- Includes thermodynamic power calculations in BHP (more intuitive than kW) based on fuel consumption

#### CSV Files (when `--csv` used)

- `vehicle_profile.csv` - Vehicle configuration
- `track_metrics_summary.csv` - Summary metrics for all tracks
- `{track}_samples.csv` - Raw sample data
- `{track}_calculated.csv` - Calculated values (hybrid speed, fuel efficiency, drive mode)

#### Validation Report (when `--validate` used)

Console output showing comparison between recalculated and logged values with tolerance checking.

#### Power Summary (when `--power` used)

Console output showing thermodynamic power calculations from fuel rate.

## Calculation Details

### Fuel Rate Calculation (3-Tier Fallback)

1. **Direct PID**: Use PID 015E if available
2. **MAF-based**: Convert MAF (g/s) to fuel rate using fuel type constants
   - Diesel: Apply AFR correction for turbocharged engines
3. **Speed-Density**: Estimate from MAP, IAT, RPM, and engine displacement

### Speed Calculation

- **Hybrid**: OBD speed ≤20 km/h (more accurate at low speeds), GPS speed >20 km/h
- **Average**: Distance / moving time (requires min 30s and 50m)

### Drive Mode Classification

- **Idle**: Speed ≤2 km/h
- **City**: Speed 2-60 km/h
- **Highway**: Speed >60 km/h

### High-Precision Accumulation

- Distance: Accumulated in meters, converted to km
- Fuel: Accumulated in milliliters, converted to liters
- Time: Accumulated in fractional seconds to prevent truncation errors

## Troubleshooting

### "Track file not found"
Use absolute paths for track files on Windows:
```bash
python recalculate_metrics.py -p profile.json -t C:\full\path\to\track.json
```

### Missing OBD Sensors
The script handles missing sensors gracefully using the same fallback logic as MetricsCalculator:
- No fuel rate PID → falls back to MAF
- No MAF → falls back to Speed-Density
- No Speed-Density data → fuel rate = 0

### Incorrect Fuel Type
Check that `fuelType` in vehicle profile matches one of: `PETROL`, `E20`, `DIESEL`, `CNG`

### Corrupted JSON Files
The script automatically attempts to repair common JSON formatting issues:
- Trailing commas in objects/arrays
- Missing quotes around property names
- Control characters
- Duplicate commas
- Bracket mismatches

If repair succeeds, a backup is created (`file.backup`) and the original is fixed. If repair fails, the script attempts to extract partial data from the corrupted file.

## Comparison with App

The script recalculates metrics from scratch using raw OBD/GPS data, ignoring logged values in the JSON files. This allows validation of the calculation logic and identification of any discrepancies.

Key differences from the app:
- No accelerometer metrics (not needed for fuel/trip calculations)
- No power calculations (optional, not implemented yet)
- No real-time updates (batch processing only)

## Dependencies

### Core Requirements
- Python 3.10+ (for type hints)
- Standard library only (json, csv, xml, argparse, pathlib, typing, math)

### Optional Dependencies
- `matplotlib` - For generating plots and charts
- `numpy` - For statistical calculations (fallback implementations included)
- `seaborn` - For enhanced plot styling (optional)

The script gracefully handles missing optional dependencies and disables related features.
