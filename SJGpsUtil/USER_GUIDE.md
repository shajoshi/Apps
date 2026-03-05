# Tracker — User Guide

**Tracker** is an Android app for GPS tracking with real-time road quality monitoring. It records your route along with accelerometer data to classify road surface conditions (smooth, average, rough) and detect features like speed bumps and potholes. A live Driving View displays lean angle, speed, altitude, acceleration forces, and a driver smoothness score on a responsive gauge. Tracks can be saved as JSON, KML, or GPX files and reviewed in the built-in track history browser. JSON tracks with raw accelerometer data can be used to automatically calibrate detection thresholds for your specific vehicle and roads.

---

## Screens

The app has three screens — **Tracking**, **Tracks**, and **Settings** — accessible via the ⋮ dropdown menu on each screen.

### Driving View (Default)

The Driving View is the main screen shown on launch. It provides a real-time heads-up display designed for use while driving or riding.

- **Lean Angle Gauge** — Semi-circular gauge showing vehicle lean angle from the accelerometer, with forward/lateral acceleration chevrons.
- **Speed & Altitude** — Current GPS speed (km/h) and altitude (m).
- **Road Quality** — Live classification: Smooth, Average, or Rough.
- **Feature Detection** — Alerts for speed bumps and potholes.
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

When "Road calibration run" is enabled in Settings, the Tracking Details view shows ground-truth labelling buttons during recording:
- **Road Quality** — Tag sections as Smooth or Rough (30-second auto-timeout).
- **Features** — Tag Bump, Pothole, or Jolt (5-second delayed tagging window).

### Tracks

Browse and inspect saved track files:
- Lists all tracks in the configured save folder, sorted by date.
- Tap a track to view details including:

**General** — File name, distance, point count, tracking duration, start/end times.

**Tracking Metrics** (JSON tracks with accelerometer data):
- **Road Quality Distribution** — Percentage breakdown of smooth, average, rough, and below-speed points.
- **Feature Counts** — Number of detected bumps and potholes.
- **Metric Ranges** — Min, max, and average for RMS Z, Peak Z, StdDev Z, and Peak Ratio.

**Driver Metrics** (JSON tracks with driver data):
- **Event Counts** — Hard brake, hard acceleration, swerve, aggressive corner, normal, and low-speed event totals with percentages.
- **Smoothness Score** — Average driver smoothness (0–100, higher is smoother).
- **Forward / Lateral Accel** — Average RMS, average max, and peak values for both axes.
- **Friction Circle** — Maximum combined forward + lateral acceleration magnitude.
- **Lean Angle** — Average and maximum lean angle (degrees).

#### Calibrate Thresholds

JSON tracks recorded with **Record Acceleration** enabled show a **⚙ (Tune) icon** on the right side of the track card. Tapping it starts the automated threshold calibration flow for that track.

**Step 1 — Validation**

Tracker checks that the track contains raw accelerometer data. If the track was recorded without acceleration enabled, an error is shown and calibration cannot proceed.

**Step 2 — Calibration Parameters**

A dialog appears with configurable targets:

| Parameter | Default | Description |
|---|---|---|
| Smooth % | 60 | Target percentage of fixes to classify as smooth |
| Rough % | 10 | Target percentage of fixes to classify as rough |
| Bumps | 5 | Target number of bump events to detect |
| Potholes | 5 | Target number of pothole events to detect |
| Hard Brakes | 5 | Target number of hard braking events |
| Hard Accels | 5 | Target number of hard acceleration events |
| Swerve Events | 5 | Target number of swerve events |
| Min Speed (km/h) | 6 | Fixes below this speed are excluded from analysis |

Adjust these to reflect the road conditions and driving style in the recorded track, then tap **Analyze**.

**Step 3 — Analysis**

Tracker analyses the track in the background. It reads the gravity vector stored in the track file, reconstructs the vehicle coordinate frame, and processes every GPS fix one at a time — calling the same metrics engine used during live recording. This ensures calibration and runtime results are directly comparable.

**Step 4 — Review Recommendations**

A results dialog shows:
- **Analysis summary** — Inferred accelerometer sampling rate, number of fixes analysed, achieved smooth%, rough%, bump count, and pothole count.
- **Road Quality Thresholds** — Current value → recommended value for each threshold. Values that change are highlighted in the app's primary colour.
- **Driver Thresholds** — Current value → recommended value for hard brake, hard accel, swerve, and corner thresholds.

**Step 5 — Apply**

- **Apply to [current profile]** — Saves the recommended thresholds to the active profile file and updates the live settings immediately. Road quality and driver event detection change at once without restarting.
- **Apply to…** — Opens a profile picker to apply the recommendations to any other saved profile instead.

> **Tip**: Record a representative drive on your usual roads before calibrating. The quality of the recommendations depends on the variety of road conditions captured in the track.

### Settings

- **Disable Point Filtering** — Toggle to record all GPS points regardless of movement.
- **Record Acceleration** — Enable/disable accelerometer data collection. Must be on to use Calibrate Thresholds.
- **Road Calibration Run** — Enable calibration mode for ground-truth labelling.
- **Capture Mount Baseline** — Capture the phone's stationary accelerometer baseline (must be stopped). This gravity vector is stored in every subsequent recording and is used by the calibration engine.
- **Allow Profile Save** — Toggle (default: off). When off, the Save, Save As, and Reset buttons in the Calibration screen are disabled to prevent accidental profile changes. Only Load is available. Turn on to enable saving and resetting profiles.
- **Calibration (Profile: _name_)** — Opens the Calibration dialog to view and edit thresholds for the active profile.
- **Output Format** — Choose between KML, GPX, or JSON output for recorded tracks. JSON is required for Calibrate Thresholds.
- **Save Folder** — Choose a custom folder or use the default Downloads directory.

#### Calibration Dialog

The Calibration dialog contains all tunable thresholds organised into sections:

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

On first launch, Tracker creates three default profiles in the save folder if none exist: **Motorcycle**, **Car**, and **Bicycle**. Each has vehicle-appropriate calibration thresholds. These can be loaded, modified, saved, or replaced with values from the Calibrate Thresholds feature.

---

## Quick Start

1. **Launch the app** — The Driving View appears immediately.
2. **Capture baseline** — In Settings, tap **Capture Mount Baseline** with the phone mounted and stationary. This captures the gravity vector used for road quality analysis and calibration.
3. **Set output format to JSON** — Required for Calibrate Thresholds and full metrics in the Tracks view.
4. **Tap ▶ (Play)** to start recording. Grant location (and notification) permissions when prompted.
5. **Drive** — Watch real-time speed, lean angle, road quality, and acceleration metrics.
6. **Tap ■ (Stop)** to end the recording. The track is saved in the selected output format.
7. **Review tracks** — Open ⋮ → Tracks to browse saved recordings.
8. **Calibrate** — Tap the ⚙ icon on a JSON track to calibrate thresholds from that recording.

## Tips

- **Landscape mode** works well for dashboard mounting — the layout adapts with the gauge on the left and metrics on the right.
- **Settings is locked** while recording to prevent accidental changes. Stop recording first.
- **Mount the phone securely** before capturing the accelerometer baseline for best road quality accuracy.
- Use **Calibration Profiles** to tune detection thresholds for different vehicles (e.g., car vs. motorcycle).
- **Enable "Allow profile save"** only when you intend to modify profiles — this prevents accidental overwrites during normal use.
- **Calibrate Thresholds** works best on a track recorded over a mix of road conditions — some smooth sections, some rough, and a few bumps or potholes. A short drive of 10–15 minutes is usually sufficient.
- The **⚙ icon** only appears on JSON track cards. KML and GPX files do not contain raw accelerometer data and cannot be used for calibration.
