# Road Quality Calibration Test Plan

This plan outlines the changes required to enable raw accelerometer data collection and manual ground truth labeling to calibrate road quality detection algorithms.

## Objective
To collect a high-fidelity dataset containing raw accelerometer readings synchronized with GPS locations, tagged with manual "Ground Truth" labels (Smooth, Average, Rough). This data will be used offline to tune thresholds and validate detection logic.

## 1. Data Model Changes
**`TrackingSample.kt`**
- Add `rawAccelData: List<FloatArray>?` to store the sequence of X,Y,Z readings captured during the GPS interval (Device Coordinate System, Uncompensated).
- Add `gravityVector: FloatArray?` (x, y, z) to store the reference gravity vector determined during mount calibration.
- Add `manualLabel: String?` to store the user's ground truth assessment (e.g., "smooth", "average", "rough", "baseline") at the time of recording.
- Add `manualFeatureLabel: String?` to store specific feature events triggered by the user (e.g., "speed_bump", "pothole", "bump").

## 2. User Interface Enhancements
**`TrackingScreen.kt`**
- Add a "Calibration Mode" section visible during recording.
- **Mount Calibration**:
  - Add a **"Capture Mount Baseline"** button.
  - User should press this when the vehicle is stationary on level ground.
  - This establishes the "Down" vector relative to the phone.
- **Road Quality (State)**: Implement three toggle buttons (or a segmented control) for Ground Truth:
  - **Smooth** (Green)
  - **Average** (Yellow)
  - **Rough** (Red)
- **Road Features (Events)**: Add momentary buttons for specific features:
  - **⚠️ Speed Bump**
  - **🕳️ Pothole**
  - **⚡ Bump**

## 3. Service & Logic Updates
**`TrackingService.kt`**
- Maintain `currentManualLabel`, `pendingFeatureLabel`, and `activeGravityVector`.
- **Mount Calibration Logic**:
  - When "Capture Mount Baseline" is triggered, average the accelerometer readings over a short stable window (e.g., 1-2 seconds).
  - Store this mean vector as `activeGravityVector`.
- **Sample Processing**:
  - In `computeAccelMetrics`:
    - Create a copy of the **raw** `accelBuffer` (do not remove gravity or filter yet).
    - Attach the copy, `currentManualLabel`, `pendingFeatureLabel`, and `activeGravityVector` to the new `TrackingSample`.
    - *Note: Existing metrics (RMS, etc.) currently assume Z-vertical or simple detrending. We will leave them as-is for now, but the offline analysis will use the raw data + gravity vector for correct orientation.*

## 4. File Storage Updates
**`JsonWriter.kt`**
- Update the writer to serialize the new fields.
- Format:
  ```json
  "accel": {
    "manualLabel": "rough",
    "manualFeatureLabel": "pothole",
    "gravityVector": {"x": 0.1, "y": 9.7, "z": 1.2},
    "raw": [
      [0.1, -0.2, 9.8],
      ...
    ]
  }
  ```

## 5. Execution Strategy
1. **Setup**: Mount the phone in the target vehicle/orientation.
2. **Baseline**: While stationary, press **"Capture Mount Baseline"**. This tags the orientation.
3. **Drive**: Start driving.
4. **Label**: Use Quality and Feature buttons to tag the data.
5. **Analyze**: Use offline script:
   - Use `gravityVector` to compute the Rotation Matrix.
   - Rotate `raw` data into World Coordinates (Z = Vertical).
   - Analyze Vertical Z for bumps/potholes and Lateral X/Y for sways, independent of mount angle.

## 6. Verification
- Verify that `raw` data arrays usually contain ~50-100 samples (depending on update rate).
- Verify that `manualLabel` changes in the file correspond to the times buttons were pressed.
