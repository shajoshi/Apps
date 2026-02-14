# SJGpsUtil Analysis & Conversion Scripts

Python scripts for analyzing recorded track data and converting between export formats produced by the SJGpsUtil Android app.

All scripts use **Python 3.8+** with no external dependencies except `matplotlib` (optional, for plot generation).

---

## Format Converters

### `kml_to_json.py`

Converts SJGpsUtil KML track files to the app's native JSON format.

**Usage:**
```
python kml_to_json.py <track.kml> [--output <output.json>]
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.kml` | Yes | Path to SJGpsUtil KML track file |
| `--output`, `-o` | No | Output JSON path. Default: `<track>.json` |

**What it does:**
- Parses document-level `<ExtendedData>` to reconstruct `recordingSettings` (calibration, driver thresholds, gravity vector, profile name)
- Extracts each `<Placemark>` with `<Point>` coordinates and per-point accel metrics (speed, bearing, `avgRms`, `avgStdDev`, `fwdMax`, `latMax`, `roadQuality`, `featureDetected`, etc.)
- Produces the same JSON structure as the app's `JsonWriter` (`gpslogger2path.meta` + `gpslogger2path.data[]`)
- Marks output as `"imported": true`

---

### `gpx_to_json.py`

Converts SJGpsUtil GPX track files to the app's native JSON format.

**Usage:**
```
python gpx_to_json.py <track.gpx> [--output <output.json>]
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.gpx` | Yes | Path to SJGpsUtil GPX track file |
| `--output`, `-o` | No | Output JSON path. Default: `<gpx>.json` |

**What it does:**
- Parses GPX `<metadata><extensions><sj:recordingSettings>` for calibration, driver thresholds, gravity vector
- Extracts `<trkpt>` elements with `<extensions><sj:accel>` per-point metrics
- Reads track summary distance from `<trk><extensions>`
- Produces the same JSON structure as the app's `JsonWriter`

---

### `json_to_kml.py`

Converts SJGpsUtil JSON track files to KML format.

**Usage:**
```
python json_to_kml.py <track.json> [--output <output.kml>]
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.json` | Yes | Path to SJGpsUtil JSON track file |
| `--output`, `-o` | No | Output KML path. Default: `<track>.kml` |

**What it does:**
- Reads the app's JSON format (`gpslogger2path.meta` + `gpslogger2path.data[]`)
- Produces KML matching the app's `KmlWriter`: document-level `<ExtendedData>` for recording settings, per-point `<Placemark>` with road-quality styles (`smoothStyle`/`roughStyle`), `<gx:Track>`, `<LineString>`, and summary placemark
- Converts `ts` offsets back to absolute ISO timestamps using `meta.ts`

---

## Analysis & Recommendation

### `recommend_thresholds.py`

Analyzes a recorded track and recommends calibration and driver threshold settings to hit target road quality and event count goals.

**Usage:**
```
python recommend_thresholds.py <track.json> [--smooth 60] [--rough 10] [--hardbrake 5] [--hardaccel 5] [--norawdata]
```

**Arguments:**
| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `track.json` | Yes | — | Path to SJGpsUtil JSON track file |
| `--smooth` | No | `60` | Target % of moving fixes classified as "smooth" road |
| `--rough` | No | — | Target % of moving fixes classified as "rough" road. If specified, `rmsRoughMin` and `stdDevRoughMin` will also be recommended. |
| `--hardbrake` | No | `5` | Target number of hard braking events |
| `--hardaccel` | No | `5` | Target number of hard acceleration events |
| `--norawdata` | No | off | Use pre-computed accel metrics (no `accel.raw[]` needed). Only road quality calibration is recommended in this mode. |

**What it does:**
1. Reads calibration, driver thresholds, and gravity vector from the track file's `recordingSettings`
2. Recomputes per-fix metrics from raw accelerometer data (or reads pre-computed metrics with `--norawdata`)
3. Ignores fixes below `minSpeedKmph`
4. Uses binary search on `rmsSmoothMax`/`stdDevSmoothMax` to hit the target smooth %
5. If `--rough` is specified, uses binary search on `rmsRoughMin`/`stdDevRoughMin` to hit the target rough %
6. Uses percentile selection on `fwdMax` values to yield target hard brake/accel counts
7. Prints current vs recommended values with verification

**Outputs:**
- `<track>_recommendations.json` — recommended calibration + driver threshold settings
- `<track>_road_quality.kml` — Google Earth KML with colour-coded road quality (green=smooth, orange=average, red=rough) using recommended thresholds. Includes point placemarks with info balloons and colour-coded line segments.
- `<track>_recommendations.png` — 6-panel figure (or 4-panel with `--norawdata`): RMS histogram, StdDev histogram, road quality comparison, brake CDF, accel CDF, speed+quality timeline

---

### `validate_calc_for_profile.py`

Validates accelerometer computation against a recorded track using a given calibration profile. Replicates the exact `computeAccelMetrics()`, `detectFeatureFromMetrics()`, and `detectSpeedHumpPattern()` logic from the app.

**Usage:**
```
python validate_calc_for_profile.py <calibration_profile.json> <recording_track.json>
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `calibration_profile.json` | Yes | JSON file with a `calibration` key containing all 9 calibration fields |
| `recording_track.json` | Yes | SJGpsUtil JSON track file with raw accel data |

**What it does:**
- Loads a calibration profile and applies it to a recorded track
- Computes per-GPS-fix: `rmsVert`, `maxMagnitude`, `stdDevVert`, `peakRatio`, windowed averages, road quality, feature detection (pothole, bump, speed_bump)
- Projects raw accel onto vehicle-frame axes (vertical, forward, lateral) using the track's gravity vector

**Outputs:**
- `<track>_validated_<profile>.kml` — colour-coded KML (green=smooth, yellow=average, red=rough) with feature placemarks
- `<track>_validated_<profile>.csv` — per-point metrics CSV
- Console summary: road quality distribution, feature counts, metric ranges

---

### `driver_metrics.py`

Analyzes forward and lateral accelerometer axes to derive driver behavior metrics: hard braking, hard acceleration, swerving, cornering aggressiveness, smoothness scores, and more.

**Usage:**
```
python driver_metrics.py <track.json>
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.json` | Yes | SJGpsUtil JSON track file (with raw accel data for best results) |

**What it does:**
- Recomputes fwd/lat projections from raw accel data using the track's gravity vector
- Classifies per-fix events: `hard_brake`, `hard_accel`, `swerve`, `aggressive_corner`, `normal`, `low_speed`
- Computes: smoothness score (0-100), friction circle magnitude, lean angle, jerk, weaving index, reaction time proxy
- Calculates trip-level aggregates: event density (events/km, events/min), average smoothness, peak forces

**Outputs:**
- `<track>_driver_metrics.csv` — per-fix CSV with all classifications and metrics
- `<track>_driver_metrics.kml` — KML with classified events plotted on track, colour-coded line segments
- `<track>_timeseries.png` — time series: speed, fwd accel, lat accel, lean angle with event markers
- `<track>_friction_circle.png` — G-G diagram (friction circle) scatter plot
- `<track>_speed_vs_events.png` — speed vs forward/lateral force scatter plots
- Console: full trip summary with event counts, metric averages, driver score

---

## Data Extraction

### `raw_xy_decompose.py`

Decomposes raw accelerometer samples into forward (Y) and lateral (X) components using the gravity vector.

**Usage:**
```
python raw_xy_decompose.py <track.json>
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.json` | Yes | SJGpsUtil JSON track file with `accel.raw[]` data |

**What it does:**
- Computes vehicle-frame basis from `baseGravityVector`
- For each GPS fix: detrends raw samples, projects onto forward and lateral axes
- Calculates mean forward, mean lateral, and lean angle per fix

**Outputs:**
- `<track>_raw_xy.csv` — per-GPS-fix CSV with `mean_fwd`, `mean_lat`, `lean_angle` columns

---

### `vert_analysis.py`

Extracts all GPS and accel attributes from a tracking JSON file to a flat CSV. Optionally expands raw accel samples to one row per sample.

**Usage:**
```
python vert_analysis.py <input_file> [--filterby <column>] [--nodata]
```

**Arguments:**
| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `input_file` | Yes | — | Path to SJGpsUtil JSON track file |
| `--filterby` | No | None | Column name to filter by (e.g., `manualFeatureLabel`). Only rows with non-empty values in this field are kept. |
| `--nodata` | No | off | Exclude raw accel data rows. Only summary rows (one per GPS point) are output. |

**What it does:**
- Flattens the nested JSON structure into a tabular CSV
- With raw data: one row per raw accel sample, with GPS and accel attributes repeated
- With `--nodata`: one row per GPS fix (summary only)
- Supports filtering to extract only points matching a specific label

**Outputs:**
- `<input>_vert.csv` (default), `<input>_summary.csv` (with `--nodata`), or `<input>_filtered_<column>.csv` (with `--filterby`)

---

### `extract_test_points.py`

Extracts interesting GPS fix points from a recorded track to produce a smaller test file for automated testing.

**Usage:**
```
python extract_test_points.py <input_file> <output_file> [--max-per-category N]
```

**Arguments:**
| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `input_file` | Yes | — | Path to SJGpsUtil JSON track file |
| `output_file` | Yes | — | Path for the output JSON file |
| `--max-per-category` | No | — | Maximum number of points to keep per category |

**What it does:**
- Selects representative points from each category: driver events (`hard_brake`, `hard_accel`, `swerve`, `aggressive_corner`), road quality (`rough`, `average`), features (`speed_bump`, `pothole`, `bump`), low speed, smooth+normal baseline, first/last points
- Preserves the full JSON structure (meta + selected data points)

---

## Speed Hump Detection

### `detect_speed_humps.py`

Detects speed humps/breakers in track data using a 5-gate data-driven algorithm based on oscillation pattern analysis.

**Usage:**
```
python detect_speed_humps.py <track.json>
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.json` | Yes | SJGpsUtil JSON track file with `accel.raw[]` data |

**What it does:**
- Applies 5 detection gates per GPS fix: minimum peak count, zero crossings (oscillations), duration check, peak-to-peak amplitude, amplitude decay pattern
- Uses speed-adaptive thresholds: low speed (<20 km/h) vs high speed (>=20 km/h)
- Based on speed breaker physics: high forward accel + oscillation + decay

**Outputs:**
- Console: detection results with coordinates and metrics
- `<track>_speed_humps.gpx` — GPX file with detected speed hump locations for mapping

---

### `test_speed_hump_detection.py`

Test/analysis script for speed hump detection. Runs pattern analysis on all GPS fixes and exports detailed metrics for threshold tuning.

**Usage:**
```
python test_speed_hump_detection.py <track.json>
```

**Arguments:**
| Argument | Required | Description |
|----------|----------|-------------|
| `track.json` | Yes | SJGpsUtil JSON track file with `accel.raw[]` data |

**What it does:**
- Runs speed hump detection on every GPS fix and collects detailed pattern metrics
- Computes per-fix: peak count, zero crossings, peak-to-peak amplitude, duration, peak density
- Provides threshold recommendations based on 75th percentile analysis (low speed vs high speed)

**Outputs:**
- `<track>_pattern_analysis.csv` — per-fix CSV with all pattern metrics for threshold tuning
- Console: detection statistics and recommended thresholds
