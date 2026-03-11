# Calibration & Driver Threshold Recommender Script

Create a Python script `scripts/recommend_thresholds.py` that reads a recorded track JSON, recomputes all metrics from raw accelerometer data using the track's embedded calibration/gravity settings, then recommends calibration and driver threshold values to hit user-specified targets for smooth road percentage and event counts.

## Inputs

- **Positional:** `<track.json>` — path to a recorded SJGpsUtil JSON track file
- **Optional args:**
  - `--smooth=60` — target percentage of GPS fixes classified as "smooth" road (default 60)
  - `--hardbrake=5` — target number of hard braking events (default 5)
  - `--hardaccel=5` — target number of hard acceleration events (default 5)

## Processing Steps

1. **Load track JSON** — extract `meta.recordingSettings.calibration` (all 9 fields), `meta.recordingSettings.driverThresholds` (all fields), and `meta.recordingSettings.calibration.baseGravityVector`
2. **Compute vehicle basis** from gravity vector (reuse logic from existing scripts)
3. **Per-GPS-fix metrics computation** — for each data point with raw accel data:
   - Detrend, smooth (two windows: small for road quality, large for driver events)
   - Project onto vertical/fwd/lat axes
   - Compute: `rmsVert`, `maxMagnitude`, `stdDevVert`, `peakRatio`, `fwdRms`, `fwdMax`, `latRms`, `latMax`, `leanAngleDeg`
   - Windowed averaging for road quality (`qualityWindowSize`)
   - Classify road quality (`smooth`/`average`/`rough`) using calibration thresholds
   - Detect features (pothole, bump, speed_bump)
   - Classify driver events (hard_brake, hard_accel, swerve, aggressive_corner) using driver thresholds + delta speed/course
4. **Summary statistics** — print road quality distribution, event counts, metric ranges (percentiles)
5. **Threshold recommendation via binary search:**
   - **Road quality (`rmsSmoothMax`, `stdDevSmoothMax`):** Binary search these values so that the percentage of "smooth" fixes matches `--smooth` target. The search adjusts `rmsSmoothMax` (primary lever) while keeping `stdDevSmoothMax` proportional.
   - **Hard brake (`hardBrakeFwdMax`):** Sort all `fwdMax` values (where `deltaSpeed < 0`), pick the threshold that yields exactly `--hardbrake` events above it.
   - **Hard accel (`hardAccelFwdMax`):** Same approach for `fwdMax` where `deltaSpeed > 0`.
6. **Output graphs** (matplotlib):
   - **Distribution histograms:** `rmsVert`, `stdDevVert`, `fwdMax`, `latMax` with current thresholds and recommended thresholds marked as vertical lines
   - **Time series:** speed, fwdMax, latMax, road quality color-coded strip along bottom
   - **CDF plots** for fwdMax (braking) and fwdMax (accel) showing where the recommended threshold falls
7. **Print recommended settings** — full JSON block of recommended calibration + driver threshold values, ready to paste into a vehicle profile

## Key Design Decisions

- **Reuses** the exact same computation logic as `validate_calc_for_profile.py` (road quality) and `driver_metrics.py` (driver events) — no new algorithms, just combined into one script with the optimization loop
- **Reads all settings from the track file** — no separate calibration profile file needed
- **Binary search** is simple and robust for finding threshold values that hit target counts
- **No numpy/scipy dependency** — pure Python + optional matplotlib for graphs
- The script will be self-contained in `scripts/recommend_thresholds.py`

## Output Files

- `<track>_recommendations.png` — combined figure with histograms + CDFs
- `<track>_recommendations.json` — recommended settings as JSON
- Console: summary table + recommended values
