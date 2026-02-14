# SJ GPS Util — User Guide

SJ GPS Util is an Android app for GPS tracking with real-time road quality monitoring. It records your route along with accelerometer data to classify road surface conditions (smooth, average, rough) and detect features like speed bumps, potholes, and jolts. A live Driving View displays lean angle, speed, altitude, acceleration forces, and a driver smoothness score on a responsive gauge. Tracks can be saved as JSON, KML, or GPX files and reviewed in the built-in track history browser.

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

Select "Show Details" from the Driving View menu to see detailed tracking information. Press the **Back** button to return to the Driving View.

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

- **Disable Point Filtering** — Toggle to record all GPS points regardless of movement.
- **Record Acceleration** — Enable/disable accelerometer data collection.
- **Road Calibration Run** — Enable calibration mode for ground-truth labeling.
- **Capture Mount Baseline** — Capture the phone's stationary accelerometer baseline (must be stopped).
- **Allow Profile Save** — Toggle (default: off). When off, the Save, Save As, and Reset buttons in the Calibration screen are disabled to prevent accidental profile changes. Only Load is available. Turn on to enable saving and resetting profiles.
- **Calibration (Profile: _name_)** — Opens the Calibration dialog to view and edit thresholds for the active profile.
- **Output Format** — Choose between KML, GPX, or JSON output for recorded tracks.
- **Save Folder** — Choose a custom folder or use the default Downloads directory.

#### Calibration Dialog

The Calibration dialog contains all tunable thresholds organized into sections:

**Road Quality Thresholds:**
- RMS smooth max, StdDev smooth max, RMS rough min, StdDev rough min
- Peak threshold, Peak ratio rough min (%), MagMax severe min

**Other Settings:**
- Moving average window, Quality window size

**Driver Metric Thresholds:**
- Hard brake fwd max (m/s²), Hard accel fwd max (m/s²)
- Swerve lat max (m/s²), Aggressive corner lat max (m/s²), Aggressive corner Δcourse (°)
- Min speed (km/h), Smoothness RMS max, Fall lean angle (°)

**Buttons:**
- **Save** — Save current values to the active profile file (requires Allow Profile Save = on and a named profile).
- **Save As** — Save current values as a new named profile (requires Allow Profile Save = on).
- **Load** — Load a profile from the save folder. Always available.
- **Reset** — Reset all values to app defaults (requires Allow Profile Save = on).
- **Back** — Close the dialog without saving.

#### Default Profiles

On first launch, the app creates three default profiles in the save folder if none exist: **Motorcycle**, **Car**, and **Bicycle**. Each has vehicle-appropriate calibration thresholds. These can be loaded, modified, and saved as needed.

---

## Quick Start

1. **Launch the app** — The Driving View appears immediately.
2. **Tap ▶ (Play)** to start recording. Grant location (and notification) permissions when prompted.
3. **Drive** — Watch real-time speed, lean angle, road quality, and acceleration metrics.
4. **Tap ■ (Stop)** to end the recording. The track is saved in the selected output format.
5. **Review tracks** — Open ⋮ → Tracks to browse saved recordings.

## Tips

- **Landscape mode** works well for dashboard mounting — the layout adapts with the gauge on the left and metrics on the right.
- **Settings is locked** while recording to prevent accidental changes. Stop recording first.
- **Mount the phone securely** before capturing the accelerometer baseline for best road quality accuracy.
- Use **Calibration Profiles** to tune detection thresholds for different vehicles (e.g., car vs. motorcycle).
- **Enable "Allow profile save"** only when you intend to modify profiles — this prevents accidental overwrites during normal use.
