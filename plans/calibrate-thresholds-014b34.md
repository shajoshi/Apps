# Calibrate Thresholds from Saved Track

Port the `recommend_thresholds.py` analysis pipeline into the Android app as an in-app "Calibrate Thresholds" feature on the Saved Tracks screen.

---

## Clarifications (resolved)
- **Parsing**: Stream one GPS fix at a time from JSON (no full-file load into memory).
- **Apply**: Updates both the profile file on disk AND the live `SettingsRepository` DataStore.
- **Scope**: Recommend both road quality calibration thresholds AND driver event thresholds.
- **Gravity vector**: Read from `meta.recordingSettings.calibration.baseGravityVector` (`x/y/z`) in the track JSON → `MetricsEngine.computeVehicleBasis()` → `VehicleBasis` used for all fix computations. Falls back to device-Z if absent.

---

## MetricsEngine Reuse Analysis

`MetricsEngine.kt` is **already a pure Kotlin class with zero Android dependencies**, designed for reuse. The recommendation engine can directly instantiate and call it.

| What to reuse | How |
|---|---|
| `computeVehicleBasis(gravity)` | Call once at track parse start |
| `computeAccelMetrics(buffer, speed, basis, history)` | Call per GPS fix — returns `AccelMetrics` with rms, stdDev, magMax, meanVert, fwdMax, latMax, roadQuality, feature |
| `classifyRoadQuality(avgRms, avgStdDev)` | Used internally by `computeAccelMetrics` |
| `classifyDriverEvent(fwdMax, latMax, deltaSpeed, deltaCourse, speed)` | Called per fix for driver event classification |
| `applyMovingAverage()` | Used internally |

### ⚠️ Sync issue to fix first
`MetricsEngine.detectFeatureFromMetrics()` uses `peakRatio` for bump/pothole classification, but the Python scripts use **`meanVert` sign** (downward = bump, upward = pothole). These are **out of sync**.

**Fix as part of this work**: Update `MetricsEngine.detectFeatureFromMetrics()` to accept `meanVert` and use the sign convention, matching the Python scripts. This ensures runtime app and recommendation engine use identical logic.

---

## Scope Summary

| Area | New / Modified Files |
|---|---|
| UI | `TrackHistoryScreen.kt` (button + 2 dialogs) |
| ViewModel | `TrackHistoryViewModel.kt` |
| Engine | New `ThresholdRecommendationEngine.kt` (thin wrapper — delegates to `MetricsEngine`) |
| Existing | `MetricsEngine.kt` — fix `detectFeatureFromMetrics()` sign convention |
| Data | `VehicleProfileRepository.kt`, `SettingsRepository.kt` (existing `updateCalibration` + `updateDriverThresholds`) |

---

## Step 0 — Fix `MetricsEngine.detectFeatureFromMetrics()` (prerequisite)

Update signature to include `meanVert: Float` and use sign convention:
```kotlin
// downward impulse (meanVert < 0) → bump, upward (meanVert >= 0) → pothole
fun detectFeatureFromMetrics(rms, magMax, peakRatio, meanVert): String?
```
This aligns runtime detection with the Python scripts and the recommendation engine.

---

## Step 1 — "Calibrate" button on each track card

- In `TrackHistoryCard`, add a small `IconButton` (`Icons.Filled.Tune`) in the card row.
- Only `.json` files show the button (KML/GPX have no raw data).
- On click, stream-parse the JSON to check if **any** point has `accel.raw`:
  - Add `hasRawAccelData(info, settings)` helper to `TrackHistoryRepository`.
  - If no raw data → `AlertDialog` error "This track has no raw accelerometer data."
  - If valid → open **Dialog 1**.

---

## Step 2 — Dialog 1: Calibration Parameters (`CalibrateParametersDialog`)

| Field | Default |
|---|---|
| Target smooth % | 60 |
| Target rough % | 10 |
| Target bump count | 5 |
| Target pothole count | 5 |
| Min speed km/h | from current settings |
| Hard brake events | 5 |
| Hard accel events | 5 |
| Swerve events | 5 |

Buttons: **Analyze** / **Cancel**.

---

## Step 3 — New `ThresholdRecommendationEngine.kt`

Thin orchestration layer — **delegates all per-fix computation to `MetricsEngine`**.

```kotlin
class ThresholdRecommendationEngine {
    fun analyze(jsonText: String, params: CalibrateParams): ThresholdRecommendation
}
```

### Internal flow:
1. Parse `meta.recordingSettings.calibration.baseGravityVector` → call `MetricsEngine.computeVehicleBasis()` once.
2. Infer sampling rate from GPS timestamps + sample counts (median across fixes).
3. Stream `data[]` array one fix at a time:
   - Parse `gps` (ts, speed, course) and `accel.raw`.
   - Instantiate `MetricsEngine` with a **neutral calibration** (wide thresholds) so all fixes pass through.
   - Call `engine.computeAccelMetrics(rawSamples, speed, basis, history)`.
   - Store `FixResult(avgRms, stdDev, magMax, meanVert, fwdMax, latMax, deltaSpeed, deltaCourse, speed)`.
4. After all fixes, run percentile searches:
   - `recommendCalibration(fixes, params)` → `CalibrationSettings`
   - `recommendDriverThresholds(fixes, params)` → `DriverThresholdSettings`

### Output data classes:
```kotlin
data class CalibrateParams(
    val smoothTargetPct: Double = 60.0,
    val roughTargetPct: Double = 10.0,
    val bumpTarget: Int = 5,
    val potholeTarget: Int = 5,
    val minSpeedKmph: Float = 6f,
    val hardBrakeTarget: Int = 5,
    val hardAccelTarget: Int = 5,
    val swerveTarget: Int = 5
)

data class ThresholdRecommendation(
    val samplingRateHz: Double,
    val totalFixes: Int,
    val achievedSmoothPct: Double,
    val achievedRoughPct: Double,
    val bumpCount: Int,
    val potholeCount: Int,
    val recommended: CalibrationSettings,
    val recommendedDriver: DriverThresholdSettings
)
```

---

## Step 4 — Dialog 2: Recommendations (`ThresholdRecommendationDialog`)

Title: **"Threshold Recommendations — [TrackName]"**

Content (scrollable):
- Detected sampling rate
- Achieved: smooth %, rough %, bump count, pothole count
- **Road Quality Thresholds** table: current vs recommended
- **Driver Thresholds** table: current vs recommended

**Bottom buttons:**
- **"Apply to [currentProfileName]"** → `saveProfile()` + `updateCalibration()` + `updateDriverThresholds()`
- **"Apply to…"** → profile picker → same save sequence

---

## Step 5 — ViewModel additions (`TrackHistoryViewModel`)

```kotlin
// New state
val calibrateTarget: TrackFileInfo?
val calibrateParams: CalibrateParams
val isCalibrating: Boolean
val calibrationResult: ThresholdRecommendation?
val calibrationError: String?
val isApplyingProfile: Boolean

// New actions
fun validateAndStartCalibrate(info, settings)
fun runCalibration(info, params, settings)
fun applyToProfile(profileName, rec, settings)  // file + DataStore
fun dismissCalibration()
```

---

## Implementation Order

1. Fix `MetricsEngine.detectFeatureFromMetrics()` — add `meanVert`, use sign convention.
2. `CalibrateParams` + `ThresholdRecommendation` data classes.
3. `ThresholdRecommendationEngine.kt` — delegates to `MetricsEngine`.
4. `hasRawAccelData()` helper in `TrackHistoryRepository`.
5. ViewModel state + actions.
6. `CalibrateParametersDialog` composable.
7. `ThresholdRecommendationDialog` composable.
8. Wire Calibrate button + dialogs into `TrackHistoryScreen` / `TrackHistoryCard`.
