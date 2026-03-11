# Horizontal (Forward + Lateral) Driver Skill Metrics Analysis

Build a Python script to analyze forward and lateral accelerometer data from motorcycle tracking JSON files to derive driver skill/behavior metrics.

## Confirmed Data Format

Each JSON file has structure `gpslogger2path.data[]` with per-GPS-fix entries (1s interval):

```
gps: { ts, lat, lon, speed (km/h), course (bearing°), acc, sats, alt }
accel: {
  xMean, yMean, zMean, vertMean, magMax, rms,
  fwdRms, fwdMax, latRms, latMax,          ← summary horizontal metrics
  roadQuality, featureDetected, peakRatio, stdDev, ...
  raw: [[x,y,z], [x,y,z], ...]            ← ~100 samples at 100Hz (calibration mode)
}
```

- **`baseGravityVector`** in `meta.recordingSettings.calibration` defines the vehicle frame
- Vehicle basis: `computeVehicleBasis(gravity)` → ĝ (vertical), fwd (forward/longitudinal), lat (lateral)
- Raw data is in **device frame** — must be projected onto fwd/lat axes using the gravity vector
- `fwdRms`/`fwdMax`/`latRms`/`latMax` are pre-computed summary metrics (when available)
- Speed is in **km/h** in the JSON

## Driver Skill Metrics

### A. Longitudinal (Forward Axis) — Throttle & Braking

| # | Metric | What it measures | How to compute |
|---|--------|-----------------|----------------|
| 1 | **Hard Braking Events** | Sudden decelerations | `fwdMax > threshold` AND Δspeed < 0 |
| 2 | **Hard Acceleration Events** | Aggressive throttle | `fwdMax > threshold` AND Δspeed > 0 |
| 3 | **Longitudinal Smoothness** | Throttle/brake smoothness | Mean & std of `fwdRms` — lower = smoother |
| 4 | **Jerk** | Abruptness of accel changes | Δ(fwdMean) / Δt between fixes |
| 5 | **Speed-Normalized Fwd Force** | Severity relative to speed | `fwdMax / speed` |
| 6 | **Braking Distance Proxy** | Early/late braking | Duration of consecutive deceleration |

### B. Lateral Axis — Swerving, Leaning & Cornering

| # | Metric | What it measures | How to compute |
|---|--------|-----------------|----------------|
| 7 | **Swerve Events** | Sudden lateral movements | `latMax > threshold` |
| 8 | **Lateral Smoothness** | Lateral stability | Mean & std of `latRms` |
| 9 | **Cornering G-Force** | Aggressive cornering | `latMax` when `|Δcourse| > 10°` |
| 10 | **Weaving Index** | Lateral oscillation frequency | Sign changes in lateral accel over time |
| 11 | **Speed-Normalized Lat Force** | Swerve severity at speed | `latMax / speed` |
| 12 | **Lean Angle Proxy** | Motorcycle lean | `atan(latMax / 9.81)` in degrees |

### C. Combined Metrics

| # | Metric | How to compute |
|---|--------|----------------|
| 13 | **Smoothness Score (0-100)** | Weighted combo of fwdRms + latRms |
| 14 | **Aggressive Maneuver Count** | Sum of hard brake + hard accel + swerve events |
| 15 | **Friction Circle** | `sqrt(fwdMax² + latMax²)` per fix |
| 16 | **Event Density** | Events per km or per minute |

### D. Raw Data Metrics (100Hz, when `raw[]` available)

| # | Metric | How to compute |
|---|--------|----------------|
| 17 | **Fwd Jerk (100Hz)** | Derivative of fwd-projected raw samples |
| 18 | **Lateral Oscillation FFT** | Frequency peaks in lat-projected raw |
| 19 | **Reaction Time Proxy** | Time gap between fwd spike and lat spike |

## Implementation Plan

### Single Python script: `scripts/driver_metrics.py`

1. **Load & parse** JSON track file → extract `baseGravityVector`, compute vehicle basis (reuse `compute_vehicle_basis()` from existing `validate_calc_for_profile.py`)
2. **Per-fix extraction** — for each data point, get `speed`, `course`, `ts`, and either use pre-computed `fwdRms`/`fwdMax`/`latRms`/`latMax` OR compute from `raw[]` by projecting onto fwd/lat axes
3. **Compute Δspeed, Δcourse** between consecutive fixes
4. **Classify events** per fix: `hard_brake`, `hard_accel`, `swerve`, `aggressive_corner`, `normal`
5. **Compute all metrics** from tables A–D
6. **Output**:
   - Per-fix CSV with classifications and metrics
   - Trip summary printed to console (event counts, smoothness scores, density)
   - Matplotlib plots: fwd/lat time series with events, speed vs events, friction circle
   - KML file with "Classify events" plotted on track of gps fix locations

### Initial Thresholds (to tune with real data) - make these constants at the top of the script
- Hard braking/accel: `fwdMax > 3.0 m/s²`
- Swerve: `latMax > 3.0 m/s²`
- Aggressive cornering: `latMax > 2.0 m/s²` AND `|Δcourse| > 10°`
- Min speed: 6 km/h

### Usage
```
python scripts/driver_metrics.py <track.json>
```
