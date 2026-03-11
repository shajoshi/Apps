# Vertical-Only Metrics, Horizontal Decomposition & Custom Track File Naming

Replace 3D-magnitude-based road-quality metrics with vertical-only (gravity-projected) metrics, record two horizontal components for calibration export, and add optional custom track file naming during calibration runs.

---

## Request 1 — Vertical metrics for classification + horizontal components for calibration

### Problem
`computeAccelMetrics()` computes RMS, maxMagnitude, stdDev, peakRatio from **3D magnitude** (`sqrt(x²+y²+z²)`). Bike accel/decel inflates these, causing false "rough" on smooth roads.

### Approach: Vehicle-frame decomposition using calibration baseline + device-Y axis
The **calibration baseline gravity vector** (captured stationary in Settings) defines a stable vertical axis for the entire ride. Combined with the device-Y axis assumption (phone mounted with screen facing rider), this yields a physically meaningful vehicle-frame basis:

1. **ĝ** = unit vector from **calibration baseline** `baseGravityVector` → **vertical axis** (stable, captured while stationary). Fallback: per-window bias if no baseline exists.
2. **ŷ_fwd** = device-Y unit vector `[0,1,0]` projected onto the plane ⊥ ĝ, then normalized → **forward/longitudinal axis**
3. **x̂_lat** = `ĝ × ŷ_fwd` (cross product) → **lateral axis**

The basis `{ĝ, ŷ_fwd, x̂_lat}` is computed **once at recording start** (not per-window), ensuring a consistent frame for the entire ride.

For each detrended sample `a`:
- **aVert** = `a · ĝ` (vertical — bumps/potholes)
- **aFwd** = `a · ŷ_fwd` (longitudinal — accel/decel)
- **aLat** = `a · x̂_lat` (lateral — leaning/swerving)

> **Why calibration baseline?** The per-window bias shifts slightly during hard accel/decel. The calibration baseline is captured stationary, giving a clean gravity reference. This makes the vertical/forward/lateral decomposition stable across the entire ride.
> 
> **Mount assumption:** Device-Y ≈ bike forward. If mounted differently, fwd/lat labels swap but vertical is always correct. Labels are best-effort.

### What changes

**Replace** existing 3D metrics with vertical-only metrics for classification/UI/averaging. **Add** horizontal component values per-sample for calibration export only.

| File | Change |
|---|---|
| **`TrackingService.kt`** — `startRecording()` | After loading calibration, compute `{ĝ, ŷ_fwd, x̂_lat}` basis from `baseGravityVector` (or per-window bias fallback) and store as service fields `gUnitBasis`, `fwdUnit`, `latUnit`. |
| **`TrackingService.kt`** — `computeAccelMetrics()` | Use stored basis. Per sample: compute `aVert`, `aFwd`, `aLat`. Replace 3D accumulators: `sumSquares`, `maxMagnitude`, `magnitudes` now use `abs(aVert)` instead of 3D magnitude. `peakRatio` already uses aVert ✓. Accumulate `fwdSumSquares`, `fwdMax`, `latSumSquares`, `latMax` for horizontal metrics. Compute `fwdRms`, `fwdMean`, `latRms`, `latMean` at end. If no basis stored (no baseline), fall back to per-window bias to build basis. |
| **`TrackingService.kt`** — `FixMetrics` | Fields become vertical-only: `rms`, `maxMag`, `meanMag`, `stdDev`, `peakRatio` (all from `aVert`). |
| **`TrackingService.kt`** — road quality (Step 4) | No change to thresholds/logic — just the input values are now vertical-only. |
| **`TrackingService.kt`** — `detectFeatureFromMetrics()` | Same — inputs are now vertical-only. |
| **`AccelMetrics.kt`** | Existing fields (`rms`, `maxMagnitude`, `meanMagnitude`, `stdDev`, `peakRatio`, `avg*`) become vertical-only (semantics change, no rename needed). Add: `fwdRms`, `fwdMax`, `fwdMean`, `latRms`, `latMax`, `latMean`. |
| **`TrackingState.kt`** — `TrackingSample` | Existing accel fields become vertical-only. Add: `accelFwdRms`, `accelFwdMax`, `accelLatRms`, `accelLatMax` (exported in all files for calibration analysis). |
| **`TrackingScreen.kt`** | Existing metric labels stay (they now show vertical values). Optionally add "(vert)" suffix to labels for clarity. |
| **`JsonWriter.kt`** / **`GpxWriter.kt`** / **`KmlWriter.kt`** | Export existing fields (now vertical). Add `fwdRms`, `fwdMax`, `latRms`, `latMax` per sample. |

---

## Request 2 — Optional custom track file name during calibration runs

### Problem
Track files are always auto-named `track_YYYYMMDD_HHmmss.ext`. During calibration runs it's useful to give them descriptive names (e.g., "highway_smooth_run").

### Solution
When the user presses **Start** and `roadCalibrationMode` is enabled, show a dialog asking for a track name. If the user enters a name, use `<name>_YYYYMMDD_HHmmss.ext`; if they dismiss/skip, use the default name. The name is passed to `TrackingService` via an intent extra.

### Files & changes

| File | Change |
|---|---|
| `TrackingScreen.kt` | When Start is pressed and `roadCalibrationMode == true`, show an `AlertDialog` with a `TextField` for the track name and two buttons: **"Start"** (uses entered name) and **"Start with default"** (skips). Both send `ACTION_START` with an optional `EXTRA_TRACK_NAME` string extra. |
| `TrackingService.kt` | Add `const val EXTRA_TRACK_NAME = "..."` in companion. In `onStartCommand`, extract the extra and store it. In `startRecording()`, pass the custom name to `fileStore.createTrackOutputStream()`. |
| `TrackingFileStore.kt` | Add optional `customName: String?` parameter to `createTrackOutputStream()`. When non-null, use `<customName>_YYYYMMDD_HHmmss.ext` instead of `track_YYYYMMDD_HHmmss.ext`. |
| `TrackHistoryRepository.kt` | Update `isTrackFileName()` — currently requires `track_` prefix, which would exclude custom-named files. Change to accept any `.kml`/`.gpx`/`.json` file (or match `*_YYYYMMDD_HHmmss.ext` pattern). |

---

## Execution order
1. Implement Request 1 (vertical metrics) — service → data models → UI → writers
2. Implement Request 2 (custom naming) — UI dialog → service extra → file store
3. Build & verify no compile errors
