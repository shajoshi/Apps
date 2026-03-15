# SJGpsUtil — Architecture Reference

> Agent-optimised technical reference. Covers design, data flow, key classes, constraints, and gotchas for the SJGpsUtil Android GPS track-recording app.

---

## 1. Purpose & Tech Stack

SJGpsUtil is a **motorcycle/vehicle GPS track recorder** that captures location, road quality, and driver behaviour metrics. It writes tracks to GPX, KML, or JSON files and provides a full-screen "DrivingView" overlay with live gauges.

| Concern | Technology |
|---------|-----------|
| UI | Jetpack Compose (Material 3) |
| Navigation | Manual `navStack` (no Jetpack Nav) |
| Settings persistence | AndroidX DataStore (Preferences) |
| Location | FusedLocationProviderClient (Google Play Services) |
| Accelerometer | Android `SensorManager` / `TYPE_ACCELEROMETER` |
| File I/O | SAF (`DocumentFile`) + `Downloads` fallback |
| Async | Kotlin Coroutines (`Dispatchers.IO` / `Main`) |
| Background | `TrackingService` — Android Foreground Service |

---

## 2. Module / File Map

### `data/`
| File | Responsibility |
|------|---------------|
| `SettingsRepository.kt` | DataStore-backed settings CRUD. Exposes `settingsFlow: Flow<TrackingSettings>`. Owns all preference keys. |
| `VehicleProfile.kt` | Data class for a named calibration profile (name, `CalibrationSettings`, `DriverThresholdSettings`). |
| `VehicleProfileRepository.kt` | Loads/saves `VehicleProfile` JSON files from the SAF folder. Creates default profiles on first run. |

Data classes also declared in `SettingsRepository.kt`:
- `TrackingSettings` — full app settings snapshot
- `CalibrationSettings` — road quality thresholds (RMS, stdDev, peakRatio, etc.)
- `DriverThresholdSettings` — driver event thresholds (hard brake, swerve, lean angle, etc.)
- `OutputFormat` enum — `KML` / `GPX` / `JSON`

### `tracking/`
| File | Responsibility |
|------|---------------|
| `TrackingService.kt` | Foreground service. Owns the entire recording lifecycle, GPS listener, accelerometer listener, point-filtering, and `TrackWriter`. |
| `TrackingState.kt` | Global `StateFlow` singleton. UI and service communicate through this object. Holds status, latest sample, point count, distance, gravity vector, etc. |
| `TrackingSample.kt` (inside `TrackingState.kt`) | Immutable data class for one GPS fix with all computed metrics. All accel fields are `Float?`. |
| `MetricsEngine.kt` | **Pure JVM computation** — no Android deps. Vehicle-frame basis, road quality, feature detection, driver event classification, speed-hump pattern detection. |
| `AccelMetrics.kt` | Output of `MetricsEngine.computeAccelMetrics()`. Vertical + fwd + lat axes, lean angle, road quality, feature. |
| `DriverMetrics.kt` | Output of `MetricsEngine.computeDriverMetrics()`. Event list, primary event, smoothness score, jerk, reaction time. |
| `RecordingSettingsSnapshot.kt` | Frozen copy of settings taken at `startRecording()`. Passed to `TrackWriter`. Prevents mid-trip settings changes from affecting the open file. |
| `TrackWriter.kt` | Interface: `writeHeader()`, `appendSample(sample)`, `close(totalDistanceMeters?)`, `setRecordingSettings(snapshot)`. |
| `GpxWriter.kt` | `TrackWriter` → GPX 1.1 format. |
| `KmlWriter.kt` | `TrackWriter` → KML 2.2 format with extended data. |
| `JsonWriter.kt` | `TrackWriter` → newline-delimited JSON objects with full metrics. |
| `TrackingFileStore.kt` | Creates output files via SAF or `Downloads`. Resolves folder URI. Returns a `TrackWriter` for the configured format. |
| `TrackHistoryRepository.kt` | Lists track files (SAF + Downloads). Parses JSON for summary stats. Used by `TrackHistoryScreen`. |
| `ThresholdRecommendationEngine.kt` | Analyses existing JSON track files to recommend calibration threshold values. |

### `ui/`
| File | Responsibility |
|------|---------------|
| `TrackingScreen.kt` | Main screen composable. Shows live GPS data, status, controls. Launches `DrivingViewDialog`. |
| `DrivingViewDialog.kt` | Full-screen overlay dialog with live gauges. Handles portrait/landscape layouts. Runs its own independent accelerometer listener for lean angle. |
| `SemiCircularGauge.kt` | Reusable Canvas-based gauge widget (`GaugeConfig` data class). Used inside `DrivingViewDialog`. |
| `SettingsScreen.kt` | Compose settings UI. DataStore-bound. Contains calibration dialog, baseline capture, profile management. |
| `TrackHistoryScreen.kt` | Lists recorded track files. Supports share/delete. Uses `TrackHistoryViewModel`. |
| `TrackHistoryViewModel.kt` | ViewModel for `TrackHistoryScreen`. Calls `TrackHistoryRepository`. |
| `ThresholdRecommendationDialog.kt` | Shows auto-recommended calibration thresholds from `ThresholdRecommendationEngine`. |
| `CalibrateParametersDialog.kt` | Sub-dialog inside Settings for editing all calibration values. |
| `theme/` | Material 3 theme colours, typography. |

### Root
| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Single activity. Hosts `SJGpsUtilApp` composable. Manages permission requests. |
| `SJGpsUtilApp` (in `MainActivity.kt`) | Top-level composable. Owns `navStack`, reads `TrackingState.status` to auto-eject from Settings during recording. |

---

## 3. Key Data Models

```
TrackingSettings
├── intervalSeconds: Long          (GPS fix interval, default 1 s)
├── folderUri: String?             (SAF URI; null → Downloads)
├── outputFormat: OutputFormat     (KML / GPX / JSON)
├── enableAccelerometer: Boolean   (default true)
├── roadCalibrationMode: Boolean   (includes raw accel data in JSON export)
├── calibration: CalibrationSettings
├── driverThresholds: DriverThresholdSettings
└── currentProfileName: String?

TrackingSample                     (all accel fields nullable)
├── latitude, longitude, altitudeMeters, speedKmph, bearingDegrees
├── accuracyMeters, verticalAccuracyMeters, satelliteCount, timestampMillis
├── accelXMean, accelYMean, accelZMean, accelVertMean
├── accelRMS, accelMagnitudeMax, peakRatio, stdDev, roadQuality, featureDetected
├── avgRms, avgStdDev, avgPeakRatio, avgMaxMagnitude, avgMeanMagnitude
├── accelFwdRms, accelFwdMax, accelLatRms, accelLatMax
├── accelSignedFwdRms, accelSignedLatRms, accelLeanAngleDeg
├── driverMetrics: DriverMetrics?
├── gravityVector: FloatArray?
├── rawAccelData: List<FloatArray>? (only when roadCalibrationMode=true)
└── manualLabel, manualFeatureLabel (road calibration annotations)
```

---

## 4. Navigation

```
AppDestinations enum: TRACKING | HISTORY | SETTINGS

SJGpsUtilApp:
  navStack = mutableStateListOf(TRACKING)   // back-stack list

  Rules:
  - Settings only reachable when TrackingStatus == Idle
  - LaunchedEffect(canOpenSettings): auto-pops SETTINGS from stack if recording starts
  - BackHandler pops stack; never empties below TRACKING
```

No Jetpack Navigation is used. Screen routing is purely by `navStack.last()` in a `when` block.

---

## 5. Recording Flow (step by step)

```
1. User taps "Start" in TrackingScreen
   → sendTrackingAction(context, ACTION_START, trackName)
   → Intent to TrackingService with EXTRA_TRACK_NAME

2. TrackingService.onStartCommand(ACTION_START)
   → pendingTrackName = intent.extra
   → scope.launch { startRecording() }

3. startRecording()
   a. Read settings from DataStore (settingsRepository.settingsFlow.first())
   b. Build CalibrationSettings + DriverThresholds from settings
   c. Create MetricsEngine with those settings
   d. Open TrackWriter via TrackingFileStore (SAF or Downloads)
   e. Freeze RecordingSettingsSnapshot
   f. Call TrackWriter.writeHeader()
   g. IF enableAccelerometer:
        - registerAccelerometerListener()
        - clear accelBuffer
        - delay(500 ms)  ← collect ~50 gravity samples
        - unregisterAccelerometerListener()
        - average buffer → capturedGravityVector
        - computeVehicleBasis(capturedGravityVector)  ← sets vehicleBasis
      ELSE:
        - capturedGravityVector = null
   h. Re-register accelerometer listener for ongoing recording
   i. TrackingState.updateStatus(Recording)
   j. Start FusedLocation updates (LocationRequest at intervalSeconds)

4. Per GPS fix (LocationCallback.onLocationResult):
   a. Drain accelBuffer (synchronized) → computeAccelMetrics(speed, vehicleBasis)
      - Returns null if buffer empty or enableAccelerometer=false
   b. Compute deltaSpeed + deltaCourse vs previousSample
   c. computeDriverMetrics(accelMetrics, speed) → DriverMetrics?
   d. Build TrackingSample
   e. TrackingState.updateSample(sample)       ← UI updates
   f. shouldRecordSample(sample)?
      - YES → trackWriter.appendSample(sample), increment counters
      - NO  → incrementSkippedPoints(), maybeWarnOnHighRejectionRate()
   g. previousSample = sample

5. User taps "Stop"
   → ACTION_STOP → stopRecording()
   → trackWriter.close(totalDistanceMeters)
   → unregisterAccelerometerListener()
   → stopForeground() + TrackingState.updateStatus(Idle)
```

---

## 6. TrackingService Details

**Service lifecycle actions:**
| Intent action | Effect |
|--------------|--------|
| `ACTION_START` | Calls `startRecording()`. Requires `EXTRA_TRACK_NAME`. |
| `ACTION_PAUSE` | Pauses GPS updates and accel listener. Notification updates. |
| `ACTION_STOP` | Closes writer, stops listeners, removes foreground. |

**Point filtering (`shouldRecordSample`):**
- Skips samples below accuracy threshold (configurable in manifest metadata)
- Skips samples with zero movement when `disablePointFiltering=false`
- **Rejection watchdog**: tracks `rejectedSamplesSinceWindowReset / totalSamplesSinceWindowReset` over a 20-sample window. If ratio exceeds 50 %, logs a warning toast at most once per 60 s.

**Accelerometer listener:**
- `TYPE_ACCELEROMETER` at 10 ms period (`ACCEL_SAMPLING_PERIOD_US = 10_000`)
- Buffer capped at 1 000 samples (rolling drop)
- Guarded by `enableAccelerometer` flag AND `sensor != null` check
- `unregisterListener` is always safe (no-op if not registered)

---

## 7. MetricsEngine (Pure Computation)

Location: `tracking/MetricsEngine.kt`. Zero Android imports — fully unit-testable on JVM.

**`computeVehicleBasis(gravity: FloatArray): VehicleBasis?`**
- Normalises gravity vector → `ĝ` (vertical axis)
- Projects device-Y onto horizontal plane → `fwd` (forward axis)
- Fallback to device-X if device-Y is parallel to gravity
- Returns `null` if degenerate

**`computeAccelMetrics(accelBuffer, speedKmph, basis, metricsHistory): AccelMetrics?`**
- Returns `null` if buffer empty
- Step 1: Detrend (remove per-window bias from all axes)
- Step 2: Apply moving average (small window for quality, large window for driver metrics)
- Step 3: Project onto vehicle frame (ĝ / fwd / lat) — falls back to device-Z if `basis=null`
- Step 4: Compute vertical RMS, stdDev, peakRatio, maxMagnitude
- Step 5: Compute fwd/lat RMS, max, mean, signedRms
- Step 6: Compute lean angle (atan2 of lat vs vert gravity component)
- Step 7: Push to `metricsHistory` ring buffer, compute windowed averages
- Step 8: Road quality classification + feature detection (gated on `speedKmph >= 6`)

**Road quality classes:** `"smooth"` / `"average"` / `"rough"` (based on `avgRms` + `avgStdDev` vs thresholds)

**Feature detection:** `"bump"` / `"pothole"` / `"speed_bump"` (via `detectSpeedHumpPattern` using peak count, zero crossings, amplitude decay)

**Driver events:** `"fall"` / `"hard_brake"` / `"hard_accel"` / `"swerve"` / `"aggressive_corner"` / `"normal"` / `"low_speed"`

---

## 8. Output Formats

All writers implement `TrackWriter`. File naming: `<trackName>_<timestamp>.<ext>`.

| Format | Writer | Notes |
|--------|--------|-------|
| GPX | `GpxWriter` | GPX 1.1, extensions for accel/quality fields |
| KML | `KmlWriter` | KML 2.2, `<ExtendedData>` for all metrics |
| JSON | `JsonWriter` | Newline-delimited JSON objects. Header object + one object per sample. Full metrics including raw accel in `roadCalibrationMode`. |

**File location resolution order:**
1. SAF folder URI from `settings.folderUri` (user-picked via `ACTION_OPEN_DOCUMENT_TREE`)
2. Fallback: `Environment.DIRECTORY_DOWNLOADS`

---

## 9. ThresholdRecommendationEngine

- Scans all JSON track files in the configured folder
- Parses `accelRMS`, `stdDev`, `peakRatio` distributions from samples
- Computes percentile-based recommendations for `CalibrationSettings` thresholds
- Exposed via `ThresholdRecommendationDialog` in `SettingsScreen`

---

## 10. DrivingView / GaugePanel

`DrivingViewDialog.kt` — opened as a full-window `Dialog` from `TrackingScreen`.

**Live lean angle (independent of recording):**
- Registers its own `TYPE_ACCELEROMETER` listener inside a `DisposableEffect`
- Listener only registered if `sensorManager.getDefaultSensor(TYPE_ACCELEROMETER) != null`
- Corrects for display rotation via `displayRotation()` helper (API 30+ safe)
- `liveLeanAngle` stays `0f` if no sensor — gauge shows upright, no crash

**Layouts:**
- Wide (landscape): `Row { GaugePanel (55%) | InfoPanel (45%) }`
- Tall (portrait): `Column { speed | GaugePanel | StatusRow | metrics row }`
- Scale factor applied to all font/stroke sizes for different screen densities

**`SemiCircularGauge`** — Canvas-based, configured via `GaugeConfig`:
- `value`, `minValue`, `maxValue`, `label`, `unit`, `leftColor`, `rightColor`
- `centerIsZero` mode for lean angle (0 = center, sweeps ±)
- Warning zone arc above `warningThreshold`

---

## 11. Settings & Profiles

**`SettingsRepository`** — DataStore-backed, every field has a typed key. `settingsFlow` is a `Flow<TrackingSettings>` observed by UI composables via `collectAsState()`.

**`VehicleProfileRepository`** — saves/loads JSON files named `<profileName>.json` in the SAF folder. `createDefaultProfiles()` called on first run when folder is empty.

**Baseline capture flow (`captureBaselineFromAccelerometer`):**
1. Check `hasAccelerometer` (button disabled if false)
2. Register `TYPE_ACCELEROMETER` listener
3. Collect 3 s of samples
4. Average → gravity vector
5. Persist as `baseGravityVector` in the active profile
6. Button disabled while recording (`trackingStatus == Recording`)

---

## 12. Accelerometer Safety Rules

| Scenario | Behaviour |
|----------|-----------|
| `enableAccelerometer = false` | Listener never registered. `accelMetrics = null`. No gravity capture at start. |
| `enableAccelerometer = true`, no hardware sensor | `getDefaultSensor()` returns null → listener registration skipped. Buffer empty → `capturedGravityVector = null`. Recording proceeds GPS-only. |
| `MetricsEngine.computeAccelMetrics(emptyBuffer)` | Returns `null` immediately. |
| `vehicleBasis = null` | `computeAccelMetrics` falls back to device-Z axis for vertical — still produces useful road quality. |
| `DrivingView` no sensor | `liveLeanAngle = 0f`. Gauge shows centered needle. No crash. |
| Settings baseline button | Disabled + labelled `"No accelerometer — baseline not available"` when `hasAccelerometer = false`. |

---

## 13. Constraints & Gotchas

- **`enableAccelerometer` defaults to `true`** in `TrackingSettings`. A device with no accelerometer hardware will silently record GPS-only (no crash post-fix).
- **Settings auto-closes** when `TrackingStatus` changes to `Recording` (via `LaunchedEffect(canOpenSettings)` in `SJGpsUtilApp`).
- **`RecordingSettingsSnapshot`** is frozen at `startRecording()`. Changing settings mid-trip has no effect on the open file.
- **`TrackWriter` is an interface** — add new formats by implementing it and registering in `TrackingFileStore`.
- **Gravity capture window is 500 ms** (delay in `startRecording()`). If the device is moving during this window, `capturedGravityVector` will be inaccurate. The baseline capture in Settings is the recommended alternative for accurate mounting calibration.
- **`roadCalibrationMode`** includes raw `List<FloatArray>` accel data in JSON output — can significantly increase file size.
- **Deprecated `windowManager.defaultDisplay.rotation`** replaced with `context.display?.rotation` on API 30+ in `DrivingViewDialog`.
- **`VehicleProfileRepository.listProfiles()`** is called inside a `LaunchedEffect(folderUri)` in `SettingsScreen`. The effect re-runs when the folder URI changes to ensure profiles from the correct folder are shown.
- **Satellite count** is tracked via `GnssStatus.Callback` registered separately from FusedLocation; count is stored in `TrackingState._satelliteCount`.
