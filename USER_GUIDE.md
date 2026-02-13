# SJ GPS Util — User Guide

SJ GPS Util is an Android app for GPS tracking with real-time road quality monitoring. It records your route along with accelerometer data to classify road surface conditions (smooth, average, rough) and detect features like speed bumps, potholes, and jolts. A live Driving View displays lean angle, speed, altitude, acceleration forces, and a driver smoothness score on a responsive gauge. Tracks are saved as JSON files and can be reviewed in the built-in track history browser.

---

## Screens

The app has three screens — **Tracking**, **Tracks**, and **Settings** — accessible via the ⋮ dropdown menu on each screen.

### Driving View (Default)

The Driving View is the main screen shown on launch. It provides a real-time heads-up display designed for use while driving or riding.

- **Lean Angle Gauge** — Semi-circular gauge showing vehicle lean angle from the accelerometer, with forward/lateral acceleration chevrons.
- **Speed & Altitude** — Current GPS speed (km/h) and altitude (m).
- **Road Quality** — Live classification: Smooth, Average, or Rough.
- **Feature Detection** — Alerts for speed bumps, potholes, and jolts.
- **Driver Events** — Flags hard braking, hard acceleration, swerving, and aggressive cornering.
- **Metrics** — Event count, elapsed time, smoothness score, Z-axis RMS, Z Peak, and StdDev Z.
- **Test Mode** — Accessible from the ⋮ menu; runs a demo animation through all gauge states.

The view adapts automatically to portrait and landscape orientations. Accelerometer axes are remapped based on screen rotation for consistent readings.

#### Control Bar

At the top of the Driving View:
- **⋮ Menu** (left) — Test, Show Details, Tracks, Settings
- **▶ Pause ■** (center) — Start, Pause, and Stop recording

### Tracking Details

Select "Show Details" from the Driving View menu to see detailed tracking information:

- **GPS Data** — Coordinates, altitude, speed, bearing (with cardinal direction), accuracy.
- **Acceleration Metrics** — RMS Z, Peak Z, StdDev Z, Peak ratio (current and running averages).
- **Road Quality & Features** — Current classification and detected features.
- **Session Stats** — Tracking time, not-moving time, distance, point count, skipped points, satellite count, current file name.

#### Calibration Mode

When "Road calibration run" is enabled in Settings, the Tracking Details view shows ground-truth labeling buttons during recording:
- **Road Quality** — Tag sections as Smooth or Rough (30-second auto-timeout).
- **Features** — Tag Bump, Pothole, or Jolt (5-second delayed tagging window).

### Tracks

Browse and inspect saved track files:
- Lists all tracks in the configured save folder, sorted by date.
- Tap a track to view details including:

**General** — File name, distance, point count, tracking duration, start/end times.

**Tracking Metrics** (JSON tracks with accelerometer data):
- **Road Quality Distribution** — Percentage breakdown of smooth, average, rough, and below-speed points.
- **Feature Counts** — Number of detected speed bumps, potholes, and jolts.
- **Metric Ranges** — Min, max, and average for RMS Z, Peak Z, StdDev Z, and Peak Ratio.

**Driver Metrics** (JSON tracks with driver data):
- **Event Counts** — Hard brake, hard acceleration, swerve, aggressive corner, normal, and low-speed event totals with percentages.
- **Smoothness Score** — Average driver smoothness (0–100, higher is smoother).
- **Forward / Lateral Accel** — Average RMS, average max, and peak values for both axes.
- **Friction Circle** — Maximum combined forward + lateral acceleration magnitude.
- **Lean Angle** — Average and maximum lean angle (degrees).

### Settings

- **Recording Interval** — Set GPS sampling interval in seconds (quick presets: 5s, 10s, 15s, 30s).
- **Disable Point Filtering** — Toggle to record all GPS points regardless of movement.
- **Record Acceleration** — Enable/disable accelerometer data collection.
- **Road Calibration Run** — Enable calibration mode for ground-truth labeling.
- **Capture Mount Baseline** — Capture the phone's stationary accelerometer baseline (must be stopped).
- **Calibration Profiles** — Create, load, save, and manage vehicle-specific calibration profiles with thresholds for RMS, peak, StdDev, and road quality classification.
- **Output Format** — JSON.
- **Save Folder** — Choose a custom folder or use the default Downloads directory.

---

## Quick Start

1. **Launch the app** — The Driving View appears immediately.
2. **Tap ▶ (Play)** to start recording. Grant location (and notification) permissions when prompted.
3. **Drive** — Watch real-time speed, lean angle, road quality, and acceleration metrics.
4. **Tap ■ (Stop)** to end the recording. The track is saved as a JSON file.
5. **Review tracks** — Open ⋮ → Tracks to browse saved recordings.

## Tips

- **Landscape mode** works well for dashboard mounting — the layout adapts with the gauge on the left and metrics on the right.
- **Settings is locked** while recording to prevent accidental changes. Stop recording first.
- **Mount the phone securely** before capturing the accelerometer baseline for best road quality accuracy.
- Use **Calibration Profiles** to tune detection thresholds for different vehicles (e.g., car vs. motorcycle).
