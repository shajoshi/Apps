# Unified Ride Metrics Log

Extend OBD2App's trip log to include full GPS readings and computed accelerometer values per sample (adapted from SJGpsUtil's MetricsEngine), without road quality classification, feature detection, or driver event logic.

---

## Changes to VehicleProfile and FuelType

### New `FuelType` entry — E20 Petrol
E20 = 20% ethanol + 80% petrol. Constants derived from stoichiometry:

| Property | Petrol (E0) | E20 | Diesel | CNG |
|---|---|---|---|---|
| Stoich AFR | 14.7 | 13.8 | 14.5 | 17.2 |
| `mafLitreFactor` | 0.0000746 | 0.0000751 | 0.0000594 | 0.0000740 |
| `co2Factor` (g CO₂ per L/100km) | 23.1 | 22.3 | 26.4 | 16.0 |
| `energyDensityMJpL` | 34.2 | 27.4 | 38.6 | 23.0 |

*`energyDensityMJpL` is a new field added to `FuelType` — used for thermodynamic power calculation.*

### New `VehicleProfile` field
- `vehicleMassKg: Float = 0f` — used for acceleration-based power. If `0`, acceleration power is not computed.

---

## What we adapt from SJGpsUtil MetricsEngine

Only the **core acceleration computation path** — no classification, no event detection:

- `computeVehicleBasis()` — gravity vector → vehicle-frame axes (forward/lateral/vertical)
- Detrend (remove gravity/static bias) + moving-average smoothing
- Forward, lateral, vertical decomposition via dot product
- Statistical outputs per sample: RMS, max, mean, stdDev, peakRatio, leanAngleDeg

**Excluded:** `classifyRoadQuality`, `detectFeatureFromMetrics`, `detectSpeedHumpPattern`, `classifyDriverEvent`, `computeDriverMetrics`, `computeSmoothnessScore`

This means we only need `MetricsEngine.computeAccelMetrics()` up to line ~317, and only the `AccelMetrics` data class (minus `roadQuality`, `featureDetected` fields which become unused).

---

## Proposed unified log structure per sample

Each sample is one JSON object emitted at the OBD2 poll rate. Fields are nullable — only present when the source is available.

### `gps` sub-object — **full GPS readings**
| Field | Source | Unit |
|---|---|---|
| `lat`, `lon` | GPS | decimal degrees (WGS84) |
| `altMsl` | GPS − EGM96 geoid undulation | m (orthometric / MSL) |
| `altEllipsoid` | GPS raw WGS84 | m |
| `geoidUndulation` | EGM96 lookup | m |
| `speedKmh` | GPS | km/h |
| `bearingDeg` | GPS | ° (0–360) |
| `accuracyM` | GPS horizontal accuracy | m |
| `vertAccuracyM` | GPS vertical accuracy | m |
| `satelliteCount` | GPS | count |

*`altMsl` is already EGM96-corrected via `GeoidCorrection.getUndulation()` in `GpsDataSource` — `altEllipsoid` and `geoidUndulation` are also available in `GpsDataItem` and will be logged for full fidelity.*

*`bearingDeg`, `vertAccuracyM`, `satelliteCount` are new fields — need to be added to `GpsDataSource` and `GpsDataItem`.*

### `obd` sub-object (already logged, reorganised)
| Field | Source |
|---|---|
| `rpm`, `vehicleSpeedKmh`, `engineLoadPct`, `throttlePct` | OBD |
| `coolantTempC`, `intakeTempC`, `oilTempC`, `ambientTempC` | OBD |
| `fuelLevelPct`, `fuelPressureKpa`, `fuelRateLh`, `mafGs` | OBD |
| `timingAdvanceDeg`, `stftPct`, `ltftPct`, `o2Voltage` | OBD |
| `intakeMapKpa`, `baroPressureKpa`, `controlModuleVoltage` | OBD |

### `fuel` sub-object (derived, already present + new power fields)
| Field | Note |
|---|---|
| `fuelRateEffectiveLh` | PID 015E or MAF-derived |
| `instantLper100km`, `instantKpl` | speed-gated |
| `tripFuelUsedL`, `tripAvgLper100km`, `tripAvgKpl` | accumulated |
| `fuelFlowCcMin`, `rangeRemainingKm` | derived |
| `fuelCostEstimate`, `avgCo2gPerKm` | derived |
| `powerAccelKw` *(new)* | `mass × fwdAccel × speed` — needs `vehicleMassKg` + accel |
| `powerThermoKw` *(new)* | `fuelRate × energyDensity × thermalEfficiency` |
| `powerOBDKw` *(new)* | `(actualTorquePct/100 × refTorqueNm × RPM × 2π) / 60000` — OBD PIDs 0162+0163+010C |

**Power formulas:**

```
// Acceleration-based (requires vehicleMassKg > 0 and accel data)
powerAccelKw = (vehicleMassKg × accel.fwdMean_m_s² × gpsSpeedKmh / 3.6) / 1000

// Thermodynamic (fuel energy path, assumes ~35% brake thermal efficiency)
powerThermoKw = (fuelRateEffectiveLh / 3600) × energyDensityMJpL × 1e6 × 0.35 / 1000

// OBD torque path (most accurate when PIDs 0162 + 0163 available)
powerOBDKw = (actualTorquePct/100 × engineReferenceTorqueNm × rpm × 2π) / 60000
```

*All three are nullable — only computed when required inputs are available.*

### `accel` sub-object — **NEW, computed values only**
| Field | Description | Unit |
|---|---|---|
| `vertRms` | Vertical axis RMS (road vibration) | m/s² |
| `vertMax` | Peak vertical magnitude | m/s² |
| `vertMean` | Mean vertical acceleration | m/s² |
| `vertStdDev` | Std dev of vertical | m/s² |
| `vertPeakRatio` | Fraction of samples above threshold | 0–1 |
| `fwdRms` | Forward axis RMS (braking/acceleration energy) | m/s² |
| `fwdMax` | Peak forward magnitude | m/s² |
| `fwdMaxBrake` | Peak deceleration (signed negative) | m/s² |
| `fwdMaxAccel` | Peak acceleration (signed positive) | m/s² |
| `fwdMean` | Mean forward acceleration | m/s² |
| `latRms` | Lateral axis RMS (cornering energy) | m/s² |
| `latMax` | Peak lateral magnitude | m/s² |
| `latMean` | Mean lateral acceleration | m/s² |
| `leanAngleDeg` | Estimated lean/tilt angle | ° |
| `rawAccelSampleCount` | Number of raw accel samples in this window | count |

### `trip` sub-object (already present, keep)
| Field |
|---|
| `distanceKm`, `timeSec`, `movingTimeSec`, `stoppedTimeSec` |
| `avgSpeedKmh`, `maxSpeedKmh`, `spdDiffKmh` |
| `pctCity`, `pctHighway`, `pctIdle` |

---

## Implementation plan

### Phase 1 — FuelType and VehicleProfile changes
- Add `E20` entry to `FuelType` enum: `mafLitreFactor=0.0000751`, `co2Factor=22.3`.
- Add `energyDensityMJpL: Double` to `FuelType` for all entries: Petrol=34.2, E20=27.4, Diesel=38.6, CNG=23.0.
- Add `vehicleMassKg: Float = 0f` to `VehicleProfile` data class.
- Update `VehicleProfileRepository` serialisation/deserialisation for both new fields.
- Update `VehicleProfileEditSheet` UI to show mass input field and E20 radio button.

### Phase 2 — Copy and trim MetricsEngine
- Copy `MetricsEngine.kt` and `AccelMetrics.kt` from SJGpsUtil into `com.sj.obd2app.metrics`.
- Strip all classification/detection methods: remove `classifyRoadQuality`, `detectFeatureFromMetrics`, `detectSpeedHumpPattern`, `classifyDriverEvent`, `computeDriverMetrics`, `computeSmoothnessScore`, `DriverThresholds`.
- Trim `AccelMetrics`: remove `roadQuality`, `featureDetected`, `rawData`, `fwdValues`, `latValues`.
- Add minimal `AccelCalibration` data class: `movingAverageWindow: Int = 5`, `peakThresholdZ: Float = 2.0f`.

### Phase 3 — AccelerometerSource
- New singleton `AccelerometerSource` in `com.sj.obd2app.sensors`.
- Registers `TYPE_LINEAR_ACCELERATION` + `TYPE_GRAVITY` via `SensorManager`.
- Thread-safe `accelBuffer` — appended on each sensor event.
- `drainBuffer(): List<FloatArray>` — atomically swaps and returns samples.
- `gravityVector: FloatArray?` — updated from `TYPE_GRAVITY` sensor.
- `start()` / `stop()` called at trip start/stop.

### Phase 4 — Extend GpsDataItem and GpsDataSource
- Add `bearingDeg: Float?`, `verticalAccuracyM: Float?`, `satelliteCount: Int?` to `GpsDataItem`.
- Populate from `Location` in `GpsDataSource`: `location.bearing`, `location.verticalAccuracyMeters` (API 26+), satellite count via `GnssStatus` callback.
- `altitudeMsl`, `altitudeEllipsoid`, `geoidUndulation` already present — no change needed.

### Phase 5 — Extend MetricsCalculator
- Hold a `MetricsEngine` instance, `vehicleBasis: VehicleBasis?`, and `accelMetricsHistory: ArrayDeque<FixMetrics>`.
- On `startTrip()`: capture `vehicleBasis` from `AccelerometerSource.gravityVector`.
- On each OBD2 poll tick: drain buffer, call `computeAccelMetrics()`, store result.
- Compute three power fields per sample:
  - `powerAccelKw`: `(mass × accel.fwdMean × speed_m_s) / 1000` — only when `vehicleMassKg > 0` and accel available.
  - `powerThermoKw`: `(fuelRateEffectiveLh / 3600) × energyDensityMJpL × 1e6 × 0.35 / 1000` — only when fuel rate available.
  - `powerOBDKw`: `(actualTorquePct/100 × engineReferenceTorqueNm × rpm × 2π) / 60000` — only when PIDs 0162+0163+010C available.
- Extend `VehicleMetrics` with all new `accel` fields (flat nullables), new GPS fields, and three power fields.

### Phase 6 — Extend MetricsLogger
- Restructure `toJson()` into nested sub-objects: `gps {}`, `obd {}`, `fuel {}` (including power fields), `accel {}`, `trip {}`.
- `accel {}` block only emitted when accel fields are non-null.
- Add `AccelCalibration` snapshot and `vehicleMassKg` to log file header.

### Phase 7 — Settings UI
- Add "Enable accelerometer" toggle in Settings (default off).
- `AccelerometerSource.start()` / `stop()` gated by this toggle at trip start.
- Add `vehicleMassKg` input to `VehicleProfileEditSheet` (numeric, kg, optional).
- Add E20 radio button alongside Petrol/Diesel/CNG in profile edit sheet.

---

## Post-ride analyses enabled by this log

| Analysis | Requires |
|---|---|
| Speed-binned L/100km efficiency curve | `fuel.instantLper100km` + `gps.speedKmh` |
| Route map with GPS track | `gps.lat/lon/alt/bearing` |
| Fuel burn heat-map on route | `fuel.fuelRateEffectiveLh` + GPS |
| Braking/acceleration G-force timeline | `accel.fwdMaxBrake/fwdMaxAccel` |
| Cornering G-force profile | `accel.latMax` + `gps.bearingDeg` |
| Lean angle along route | `accel.leanAngleDeg` + GPS |
| Vibration / road roughness timeline | `accel.vertRms` + GPS |
| Engine warm-up curve | `obd.coolantTempC` vs `trip.timeSec` |
| STFT/LTFT fuel-trim drift | `obd.stftPct/ltftPct` over time |
| OBD vs GPS speed accuracy | `trip.spdDiffKmh` |
| Multi-ride KPI comparison | trip-level aggregates |
