# Test Implementation Plan for SJGpsUtil

Create a Python filter script to extract interesting GPS fix points from a real tracking file, then use the output as test data for the Kotlin test suite.

---

## Current Step: Python filter script

**File**: `scripts/extract_test_points.py`

**Input**: A recorded JSON tracking file (e.g. `homebound_20260211_172717.json`) with `roadCalibrationMode: true` containing `accel.raw[]` data per point.

**What it does**:
1. Parse the full JSON file
2. Preserve the complete `meta` header (including `recordingSettings`, `calibration`, `baseGravityVector`)
3. Scan all data points and select "interesting" ones based on these criteria:
   - **Driver events**: `primaryEvent` is NOT `"normal"` and NOT `"low_speed"` (i.e. hard_brake, hard_accel, swerve, aggressive_corner)
   - **Road quality**: `roadQuality` is `"rough"` or `"average"` (not just smooth)
   - **Features detected**: `featureDetected` is present (speed_bump, pothole, bump)
   - **Low speed**: a few points where speed < 6 km/h (for low_speed gating tests)
   - **Smooth baseline**: a few points with `roadQuality = "smooth"` and `primaryEvent = "normal"` (for baseline comparison)
4. For each category, select up to N points (configurable, default 3) to keep the file manageable
5. Also include the very first and very last data points for boundary testing
6. Write the filtered output as a valid JSON file with the same structure, preserving all fields including `accel.raw[]`

**CLI usage**:
```
python scripts/extract_test_points.py <input_file> <output_file> [--max-per-category N]
```

**Output**: A much smaller JSON file (~15-25 data points) that covers all interesting scenarios, ready to be placed at `app/src/test/resources/test_track.json`.

**Console summary**: Print a table showing how many points were selected per category:
```
Category                  Found    Selected
─────────────────────────────────────────────
hard_brake                   5          3
hard_accel                   2          2
swerve                       3          3
aggressive_corner            1          1
rough road                  12          3
average road                20          3
smooth + normal (baseline)  80          3
feature: speed_bump          4          3
feature: pothole             2          2
feature: bump                3          3
low speed (< 6 km/h)        8          3
first/last point             2          2
─────────────────────────────────────────────
Total selected: 25 / 180 data points
Output: test_track.json (1.2 MB)
```

---

## After you run the script

Once you provide the filtered `test_track.json`, I will proceed with the remaining implementation steps:

1. **Create `MetricsEngine.kt`** — Extract pure computation functions from `TrackingService` into a testable class
2. **Create `TrackFileParser.kt`** — Kotlin parser for the test JSON file
3. **Write unit tests** — `MetricsEngineTest.kt`, `JsonWriterTest.kt`, `EndToEndReplayTest.kt`
4. **Refactor `TrackingService`** — Delegate to `MetricsEngine`
5. **Update `build.gradle.kts`** — Add test dependencies
