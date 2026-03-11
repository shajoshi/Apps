# Bump/Pothole Detection Reset Plan
We will remove all speed hump/speed bump calibration tooling from scripts, then refocus detection and calibration only on bumps and potholes with a clear vertical-sign heuristic.

## Scope
- Scripts folder only (no app code changes yet).
- Remove speed hump calibration/detection scripts and related docs.
- Define a simpler bump vs pothole detection approach using vertical-Z sign and magnitude/ratio thresholds.

## Proposed Steps
1. **Inventory & remove speed hump tooling**
   - Delete speed hump/speed bump scripts and docs in `scripts/` (e.g., `speed_hump_threshold_analyzer.py`, `speedbump_*`, `detect_speed_humps.py`, `test_speed_hump_detection.py`, and `SPEED_HUMP_THRESHOLD_ANALYSIS.md`).
   - Remove speed hump sections from `scripts/recommend_thresholds.py` (arguments, analysis, reporting, thresholds).

2. **Refocus scripts on bumps/potholes**
   - Keep and/or simplify the bump/pothole calibration path in `recommend_thresholds.py` (peakThresholdZ + peakRatioFeatureMin).
   - Ensure outputs and JSON recommendations only mention bump/pothole calibration.

3. **Implement vertical-sign heuristic in scripts**
   - Define a clear, testable rule (draft):
     - Bump: net vertical Z impulse is upward (positive) beyond a threshold.
     - Pothole: net vertical Z impulse is downward (negative) beyond a threshold.
   - Identify where in script logic to compute “net vertical impulse” from raw data (e.g., integrate sign-adjusted vertical accel over the window) and use it to label bump vs pothole.

4. **Validation on sample tracks**
   - Run on known car/bike tracks to ensure bump/pothole detection outputs make sense.
   - Report counts and example locations for quick verification.

## Open Questions (need your confirmation)
1. Should we **delete** the speed hump scripts or just **archive/disable** them?
2. Which script(s) should own the **bump/pothole detection heuristic**: only `recommend_thresholds.py`, or also a standalone analysis script?
3. Do you want the net vertical-Z rule to be **strictly sign-based**, or sign + minimum magnitude and duration gating?
4. Do you want to keep `peakThresholdZ`/`peakRatioFeatureMin` calibration, or replace it fully with the new sign-based method?
