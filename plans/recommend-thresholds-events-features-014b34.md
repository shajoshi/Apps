# Plan: Extend recommend_thresholds.py for driver + road feature targets
This plan adds CLI targets for swerves, aggressive cornering, bumps, speed humps, and potholes, recommends thresholds using raw data, and aborts with a clear error when raw data is missing for requested raw-only features.

## Steps
1. **CLI arguments**: Add `--bumps`, `--speedhumps`, `--potholes`, `--swerves`, `--aggcorner` to argparse with defaults and help text mirroring existing style.
2. **Raw data validation**: If any raw-only targets are requested and the input JSON lacks raw accel data, print a clear error and `sys.exit(1)` before processing (also when `--norawdata` is used with raw-only targets).
3. **Event/feature extraction**: Reuse existing `feature` and driver metrics fields to count bumps/potholes/speed_hump events and swerve/aggressive-corner events per fix above `minSpeedKmph`.
4. **Threshold recommendation logic**:
   - **Swerves**: Recommend `swerveLatMax` using percentile selection on `lat_max` values to hit target count.
   - **Aggressive corner**: Recommend both `aggressiveCornerLatMax` and `aggressiveCornerDCourse` (adjust one at a time if needed) to hit the target count.
   - **Bumps/Potholes**: Recommend `peakThresholdZ` and `peakRatioRoughMin` (using percentile selection on peak-related metrics) to match target bump/pothole counts.
   - **Speed humps**: Introduce a new calibration threshold for speed humps and recommend it to match target count.
5. **Output updates**: Print current vs recommended counts for all new targets and include updated thresholds in `rec_cal`/`rec_dt_out` plus JSON output.
6. **Documentation**: Update `scripts/README.md` with new CLI args and behavior, and note raw-data requirement for these targets.

## Notes
- Keep recommendations deterministic and avoid introducing new dependencies.
- Preserve existing behavior when no new targets are provided.
