#!/usr/bin/env python3
"""
Calibration & Driver Threshold Recommender for SJGpsUtil track files.

Reads a recorded track JSON, recomputes all metrics from raw accelerometer data
using the track's embedded calibration/gravity settings, then recommends
calibration and driver threshold values to hit user-specified targets for
smooth road percentage and hard brake/accel event counts.

Usage:
    python recommend_thresholds.py <track.json> [--smooth=60] [--hardbrake=5] [--hardaccel=5]

Outputs:
    - Console summary with current vs recommended thresholds
    - <track>_recommendations.json — recommended settings
    - <track>_recommendations.png — distribution plots with threshold lines
"""

import argparse
import json
import math
import os
import sys
from collections import deque
from typing import Dict, List, Optional, Tuple

try:
    import numpy as np
except ImportError:
    np = None


# ---------------------------------------------------------------------------
# Vehicle-frame basis computation (mirrors MetricsEngine.computeVehicleBasis)
# ---------------------------------------------------------------------------
def compute_vehicle_basis(gravity: List[float]):
    """Return (g_unit, fwd_unit, lat_unit) or (None, None, None)."""
    norm = math.sqrt(sum(c * c for c in gravity))
    if norm < 1e-3:
        return None, None, None
    g = [c / norm for c in gravity]

    y_dot_g = g[1]
    fwd = [-y_dot_g * g[0], 1.0 - y_dot_g * g[1], -y_dot_g * g[2]]
    fwd_norm = math.sqrt(sum(c * c for c in fwd))

    if fwd_norm < 1e-3:
        x_dot_g = g[0]
        fwd = [1.0 - x_dot_g * g[0], -x_dot_g * g[1], -x_dot_g * g[2]]
        fwd_norm = math.sqrt(sum(c * c for c in fwd))

    if fwd_norm < 1e-3:
        return None, None, None

    fwd = [c / fwd_norm for c in fwd]

    lat = [
        g[1] * fwd[2] - g[2] * fwd[1],
        g[2] * fwd[0] - g[0] * fwd[2],
        g[0] * fwd[1] - g[1] * fwd[0],
    ]
    return g, fwd, lat


# ---------------------------------------------------------------------------
# Moving average filter (mirrors MetricsEngine.applyMovingAverage)
# ---------------------------------------------------------------------------
def apply_moving_average(data: List[List[float]], window_size: int) -> List[List[float]]:
    if len(data) < window_size or window_size <= 1:
        return data
    result = []
    half = window_size // 2
    for i in range(len(data)):
        start = max(0, i - half)
        end = min(len(data), i + half + 1)
        window = data[start:end]
        avg = [sum(s[j] for s in window) / len(window) for j in range(3)]
        result.append(avg)
    return result


# ---------------------------------------------------------------------------
# Calculate actual sampling rate from GPS timestamps and accelerometer data
# ---------------------------------------------------------------------------
def calculate_actual_sampling_rate(points):
    """Calculate the actual accelerometer sampling rate from data."""
    valid_intervals = []
    prev_ts = None
    prev_sample_count = None
    
    for i, point in enumerate(points):
        if isinstance(point, str):
            try:
                point = json.loads(point)
            except json.JSONDecodeError:
                continue
        
        gps = point.get("gps", {})
        ts = gps.get("ts", 0)
        accel = point.get("accel", {})
        raw_data = accel.get("raw", [])
        
        if ts and raw_data and prev_ts is not None:
            # Calculate time difference in seconds
            time_diff = (ts - prev_ts) / 1000.0  # Convert ms to seconds
            
            # Only consider reasonable intervals (0.5 to 10 seconds)
            if 0.5 <= time_diff <= 10.0:
                sample_count = len(raw_data)
                if prev_sample_count is not None and sample_count > 0 and prev_sample_count > 0:
                    # Average sample count between two points
                    avg_samples = (sample_count + prev_sample_count) / 2
                    # Calculate sampling rate
                    sampling_rate = avg_samples / time_diff
                    # Only consider reasonable rates (50-500 Hz)
                    if 50 <= sampling_rate <= 500:
                        valid_intervals.append(sampling_rate)
        
        prev_ts = ts
        prev_sample_count = len(raw_data) if raw_data else None
    
    if valid_intervals:
        # Return median to avoid outliers
        valid_intervals.sort()
        return valid_intervals[len(valid_intervals) // 2]
    
    return None


# ---------------------------------------------------------------------------
# Feature detection (bump vs pothole)
# ---------------------------------------------------------------------------
def detect_feature_from_metrics(rms, mag_max, peak_ratio, mean_vert, cal):
    if rms <= cal["rmsRoughMin"]:
        return None
    if mag_max > cal["magMaxSevereMin"]:
        # Use net vertical direction: downward impulse -> bump, upward -> pothole.
        return "pothole" if mean_vert >= 0 else "bump"
    return None


# ---------------------------------------------------------------------------
# FixMetrics for road quality windowed averaging
# ---------------------------------------------------------------------------
class FixMetrics:
    __slots__ = ("rms_vert", "max_magnitude", "mean_magnitude_vert", "std_dev_vert", "peak_ratio")

    def __init__(self, rms_vert, max_magnitude, mean_magnitude_vert, std_dev_vert, peak_ratio):
        self.rms_vert = rms_vert
        self.max_magnitude = max_magnitude
        self.mean_magnitude_vert = mean_magnitude_vert
        self.std_dev_vert = std_dev_vert
        self.peak_ratio = peak_ratio


# ---------------------------------------------------------------------------
# Bearing difference
# ---------------------------------------------------------------------------
def bearing_diff(c1, c2):
    d = c2 - c1
    while d > 180:
        d -= 360
    while d < -180:
        d += 360
    return d


# ---------------------------------------------------------------------------
# JSON data extraction helpers
# ---------------------------------------------------------------------------
DEFAULT_CALIBRATION = {
    "rmsSmoothMax": 4.0,
    "peakThresholdZ": 5.0,
    "movingAverageWindow": 1,
    "stdDevSmoothMax": 3.5,
    "rmsRoughMin": 2.5,
    "peakRatioRoughMin": 0.4,
    "peakRatioFeatureMin": 0.4,  # Legacy field (not used in scripts)
    "stdDevRoughMin": 3.0,
    "magMaxSevereMin": 25.0,
    "qualityWindowSize": 3,
}

def extract_base_gravity_vector(track_data):
    """Extract base gravity vector from track data meta information."""
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    
    # Look for baseGravityVector in calibration (where it's actually stored)
    rec = meta.get("recordingSettings", {})
    cal = rec.get("calibration", {})
    bgv = cal.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    
    # Look for baseGravityVector in meta directly (legacy support)
    bgv = meta.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    
    # Look in recording settings meta (legacy support)
    bgv = rec.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    
    return None


def extract_calibration(meta):
    rec = meta.get("recordingSettings", {})
    cal = rec.get("calibration", DEFAULT_CALIBRATION.copy())
    # Ensure required fields exist
    cal.setdefault("peakRatioFeatureMin", 0.4)  # Separate threshold for feature detection
    return cal


def extract_driver_thresholds(track_data):
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    rec = meta.get("recordingSettings", {})
    dt = rec.get("driverThresholds", {})
    return {
        "hardBrakeFwdMax": dt.get("hardBrakeFwdMax", 15.0),
        "hardAccelFwdMax": dt.get("hardAccelFwdMax", 15.0),
        "swerveLatMax": dt.get("swerveLatMax", 4.0),
        "aggressiveCornerLatMax": dt.get("aggressiveCornerLatMax", 4.0),
        "aggressiveCornerDCourse": dt.get("aggressiveCornerDCourse", 15.0),
        "minSpeedKmph": dt.get("minSpeedKmph", 6.0),
        "movingAvgWindow": dt.get("movingAvgWindow", 10),
        "smoothnessRmsMax": dt.get("smoothnessRmsMax", 10.0),
        "fallLeanAngle": dt.get("fallLeanAngle", 40.0),
    }


def extract_data_points(track_data):
    gps = track_data.get("gpslogger2path", track_data)
    if "data" in gps:
        return gps["data"]
    if "data" in track_data:
        return track_data["data"]
    if isinstance(track_data, list):
        return track_data
    return []


# ---------------------------------------------------------------------------
# Compute per-fix metrics from raw accel data
# ---------------------------------------------------------------------------
def compute_fix_metrics(
    raw_data,
    speed_kmph,
    cal,
    dt,
    g_unit,
    fwd_unit,
    lat_unit,
    metrics_history,
    store_peak_samples=False,
    actual_sampling_rate=None,
):
    """Compute road quality + driver metrics for a single GPS fix."""
    if not raw_data:
        return None

    # Use actual sampling rate if provided, otherwise default to 100Hz
    SAMPLING_RATE = actual_sampling_rate if actual_sampling_rate is not None else 100.0
    n = len(raw_data)

    # Detrend
    bias = [sum(s[j] for s in raw_data) / n for j in range(3)]
    detrended = [[s[j] - bias[j] for j in range(3)] for s in raw_data]

    # Two smoothing windows
    ma_accel = max(1, int(cal.get("movingAverageWindow", 5)))
    ma_driver = max(1, int(dt.get("movingAvgWindow", 10)))
    smoothed_accel = apply_moving_average(detrended, ma_accel)
    smoothed_driver = apply_moving_average(detrended, ma_driver)

    # --- Road quality metrics (small window) ---
    sum_vert = 0.0
    vert_sum_sq = 0.0
    vert_max_mag = 0.0
    above_z_count = 0
    vert_magnitudes = []

    for v in smoothed_accel:
        a_vert = (v[0] * g_unit[0] + v[1] * g_unit[1] + v[2] * g_unit[2]) if g_unit else v[2]
        sum_vert += a_vert
        abs_vert = abs(a_vert)
        vert_magnitudes.append(abs_vert)
        if abs_vert > vert_max_mag:
            vert_max_mag = abs_vert
        vert_sum_sq += a_vert * a_vert
        if abs_vert >= cal.get("peakThresholdZ", 1.5):
            above_z_count += 1

    count = len(smoothed_accel)
    rms_vert = math.sqrt(vert_sum_sq / count)
    max_magnitude = vert_max_mag
    peak_ratio = above_z_count / count
    mean_mag_vert = sum(vert_magnitudes) / len(vert_magnitudes)
    variance = sum((m - mean_mag_vert) ** 2 for m in vert_magnitudes) / len(vert_magnitudes)
    std_dev_vert = math.sqrt(variance)

    # --- Driver metrics (large window) ---
    fwd_sum_sq = fwd_max_mag = fwd_sum_val = 0.0
    lat_sum_sq = lat_max_mag = lat_sum_val = 0.0

    for v in smoothed_driver:
        if fwd_unit:
            a_fwd = v[0] * fwd_unit[0] + v[1] * fwd_unit[1] + v[2] * fwd_unit[2]
            fwd_sum_val += a_fwd
            fwd_sum_sq += a_fwd * a_fwd
            if abs(a_fwd) > fwd_max_mag:
                fwd_max_mag = abs(a_fwd)
        if lat_unit:
            a_lat = v[0] * lat_unit[0] + v[1] * lat_unit[1] + v[2] * lat_unit[2]
            lat_sum_val += a_lat
            lat_sum_sq += a_lat * a_lat
            if abs(a_lat) > lat_max_mag:
                lat_max_mag = abs(a_lat)

    d_count = len(smoothed_driver)
    fwd_rms = math.sqrt(fwd_sum_sq / d_count) if fwd_unit and d_count > 0 else 0.0
    fwd_max = fwd_max_mag
    fwd_mean = fwd_sum_val / d_count if fwd_unit and d_count > 0 else 0.0
    lat_rms = math.sqrt(lat_sum_sq / d_count) if lat_unit and d_count > 0 else 0.0
    lat_max = lat_max_mag

    # Lean angle
    bias_norm = math.sqrt(sum(b * b for b in bias))
    lean_angle_deg = 0.0
    if bias_norm > 1e-3 and lat_unit and g_unit:
        wg = [b / bias_norm for b in bias]
        lat_comp = wg[0] * lat_unit[0] + wg[1] * lat_unit[1] + wg[2] * lat_unit[2]
        vert_comp = wg[0] * g_unit[0] + wg[1] * g_unit[1] + wg[2] * g_unit[2]
        lean_angle_deg = math.degrees(math.atan2(lat_comp, vert_comp))

    # Windowed averaging for road quality
    # Calculate mean_vert first (needed for feature detection)
    mean_vert = sum_vert / count if count > 0 else 0.0
    
    # Only add to history if no feature was detected (to prevent contamination)
    feature = detect_feature_from_metrics(rms_vert, max_magnitude, peak_ratio, mean_vert, cal)
    should_add_to_history = feature is None
    
    if should_add_to_history:
        metrics_history.append(FixMetrics(rms_vert, max_magnitude, mean_mag_vert, std_dev_vert, peak_ratio))
    
    quality_window = max(1, int(cal.get("qualityWindowSize", 3)))
    while len(metrics_history) > quality_window:
        metrics_history.popleft()

    avg_rms = sum(m.rms_vert for m in metrics_history) / len(metrics_history) if metrics_history else 0.0
    avg_std_dev = sum(m.std_dev_vert for m in metrics_history) / len(metrics_history) if metrics_history else 0.0
    
    # Track last known road quality for use when features are detected
    last_known_road_quality = None
    if metrics_history:
        # Get road quality from the most recent history entry
        last_avg_rms = metrics_history[-1].rms_vert
        last_avg_std_dev = metrics_history[-1].std_dev_vert
        if last_avg_rms < cal["rmsSmoothMax"] and last_avg_std_dev < cal["stdDevSmoothMax"]:
            last_known_road_quality = "smooth"
        elif last_avg_rms >= cal["rmsRoughMin"] and last_avg_std_dev >= cal["stdDevRoughMin"]:
            last_known_road_quality = "rough"
        else:
            last_known_road_quality = "average"

    # Road quality + feature detection
    min_speed = dt.get("minSpeedKmph", 6.0)
    if speed_kmph < min_speed:
        road_quality = None
        # Keep feature detection even below min speed for consistency
        # feature already calculated above
    else:
        # Feature detection already done above
        # If we detected a feature, use last known road quality
        if feature:
            road_quality = last_known_road_quality if last_known_road_quality else "average"
        else:
            # Classify road quality normally (no feature detected)
            if avg_rms < cal["rmsSmoothMax"] and avg_std_dev < cal["stdDevSmoothMax"]:
                road_quality = "smooth"
            elif avg_rms >= cal["rmsRoughMin"] and avg_std_dev >= cal["stdDevRoughMin"]:
                road_quality = "rough"
            else:
                road_quality = "average"

    result = {
        "rms_vert": rms_vert,
        "max_magnitude": max_magnitude,
        "std_dev_vert": std_dev_vert,
        "peak_ratio": peak_ratio,
        "mean_vert": mean_vert,
        "avg_rms": avg_rms,
        "avg_std_dev": avg_std_dev,
        "avg_peak_ratio": peak_ratio,  # Add avg_peak_ratio for enhanced classification
        "fwd_rms": fwd_rms,
        "fwd_max": fwd_max,
        "fwd_mean": fwd_mean,
        "lat_rms": lat_rms,
        "lat_max": lat_max,
        "lean_angle_deg": lean_angle_deg,
        "road_quality": road_quality,
        "feature": feature,
    }
    if store_peak_samples:
        result["vert_abs_samples"] = vert_magnitudes
    return result


# ---------------------------------------------------------------------------
# Classify road quality with enhanced peak ratio logic
# ---------------------------------------------------------------------------
def classify_road_quality(avg_rms, avg_std_dev, rms_smooth_max, std_dev_smooth_max,
                          rms_rough_min, std_dev_rough_min):
    """Original road quality classification (dual-threshold only)."""
    if avg_rms < rms_smooth_max and avg_std_dev < std_dev_smooth_max:
        return "smooth"
    elif avg_rms >= rms_rough_min and avg_std_dev >= std_dev_rough_min:
        return "rough"
    return "average"


def classify_road_quality_adaptive(avg_rms, avg_std_dev, avg_peak_ratio,
                                rms_smooth_max, std_dev_smooth_max, 
                                rms_rough_min, std_dev_rough_min,
                                peak_ratio_stats=None):
    """
    Adaptive road quality classification that adjusts peak ratio thresholds
    based on the overall peak ratio distribution of the data.
    
    Args:
        avg_rms: Average RMS vertical acceleration
        avg_std_dev: Average standard deviation of vertical acceleration
        avg_peak_ratio: Average ratio of samples above peak threshold
        rms_smooth_max/std_dev_smooth_max: Thresholds for smooth road
        rms_rough_min/std_dev_rough_min: Thresholds for rough road
        peak_ratio_stats: Dict with 'mean', 'std_dev', 'p25', 'p75' of peak ratios
    
    Returns:
        "smooth", "average", or "rough"
    """
    # Base classification on RMS/StdDev (primary logic)
    if avg_rms < rms_smooth_max and avg_std_dev < std_dev_smooth_max:
        base = "smooth"
    elif avg_rms >= rms_rough_min and avg_std_dev >= std_dev_rough_min:
        base = "rough"
    else:
        base = "average"
    
    # If no peak ratio stats provided, use conservative defaults
    if peak_ratio_stats is None:
        peak_ratio_smooth_min = 0.05
        peak_ratio_rough_max = 0.4
    else:
        # Adaptive thresholds based on data distribution
        pr_mean = peak_ratio_stats.get('mean', 0.1)
        pr_std = peak_ratio_stats.get('std_dev', 0.05)
        
        # For very low peak ratio data (like smooth roads), be more lenient
        if pr_mean < 0.05:
            peak_ratio_smooth_min = 0.01  # Almost no peaks expected
            peak_ratio_rough_max = 0.02   # Very low threshold for rough
        # For typical mixed road data
        elif pr_mean < 0.15:
            peak_ratio_smooth_min = max(0.05, pr_mean - 0.05)
            peak_ratio_rough_max = min(0.4, pr_mean + 0.15)
        # For high peak ratio data (rough roads)
        else:
            peak_ratio_smooth_min = max(0.1, pr_mean - 0.1)
            peak_ratio_rough_max = min(0.5, pr_mean + 0.1)
    
    # Use peak ratio to add confidence or adjust borderline cases
    if base == "smooth" and avg_peak_ratio < peak_ratio_smooth_min:
        # Too many peaks for truly smooth road - demote to average
        return "average"
    elif base == "rough" and avg_peak_ratio > peak_ratio_rough_max:
        # Too few peaks for truly rough road - promote to average
        return "average"
    
    return base


# ---------------------------------------------------------------------------
# Count driver events with arbitrary thresholds
# ---------------------------------------------------------------------------
def count_events_with_thresholds(fixes, hard_brake_thresh, hard_accel_thresh, min_speed):
    hard_brake = 0
    hard_accel = 0
    for fx in fixes:
        if fx["speed"] < min_speed:
            continue
        
        # Skip driver event counting if a road feature was detected
        feature = fx.get("feature")
        if feature:
            continue
            
        if fx["fwd_max"] > hard_brake_thresh and fx["delta_speed"] < 0:
            hard_brake += 1
        if fx["fwd_max"] > hard_accel_thresh and fx["delta_speed"] > 0:
            hard_accel += 1
    return hard_brake, hard_accel


def count_swerve_events(fixes, lat_thresh, min_speed):
    return sum(1 for fx in fixes 
               if fx["speed"] >= min_speed 
               and fx.get("feature") is None  # Skip if feature detected
               and fx["lat_max"] > lat_thresh)


def count_aggressive_corner_events(fixes, lat_thresh, dcourse_thresh, min_speed):
    return sum(
        1
        for fx in fixes
        if fx["speed"] >= min_speed 
        and fx.get("feature") is None  # Skip if feature detected
        and fx["lat_max"] > lat_thresh 
        and abs(fx["delta_course"]) > dcourse_thresh
    )


def compute_peak_ratio_from_samples(vert_abs_samples, peak_threshold):
    if not vert_abs_samples:
        return 0.0
    above = sum(1 for v in vert_abs_samples if v >= peak_threshold)
    return above / len(vert_abs_samples)


def analyze_peak_threshold_distribution(raw_samples, sampling_rate=100.0):
    """
    Analyze raw acceleration samples to recommend optimal peakThresholdZ.
    
    This function examines the distribution of acceleration values to find
    a threshold that provides good separation between normal vibration
    and significant peaks.
    
    Args:
        raw_samples: List of raw vertical acceleration samples
        sampling_rate: Sampling rate in Hz
    
    Returns:
        Dict with analysis results and recommended threshold
    """
    if not raw_samples:
        return {"error": "No raw samples provided"}
    
    import statistics
    
    # Basic statistics
    samples = np.array(raw_samples) if 'np' in globals() else raw_samples
    mean_val = statistics.mean(samples)
    median_val = statistics.median(samples)
    std_val = statistics.stdev(samples) if len(samples) > 1 else 0.0
    
    # Percentile-based analysis
    sorted_samples = sorted(samples)
    p75 = sorted_samples[int(len(sorted_samples) * 0.75)]
    p90 = sorted_samples[int(len(sorted_samples) * 0.90)]
    p95 = sorted_samples[int(len(sorted_samples) * 0.95)]
    p99 = sorted_samples[int(len(sorted_samples) * 0.99)]
    
    # Calculate different threshold candidates
    candidates = {
        "mean_plus_2std": mean_val + 2 * std_val,
        "mean_plus_3std": mean_val + 3 * std_val,
        "p75": p75,
        "p90": p90,
        "p95": p95,
        "p99": p99,
        "median_plus_2std": median_val + 2 * std_val,
    }
    
    # Evaluate each candidate by looking at peak density
    best_threshold = None
    best_score = -1
    
    for name, threshold in candidates.items():
        if threshold <= 0:
            continue
            
        # Count peaks above threshold
        peaks_above = sum(1 for s in samples if s > threshold)
        peak_ratio = peaks_above / len(samples)
        
        # Score based on having reasonable peak density (5-20% peaks)
        # Too few peaks = threshold too high
        # Too many peaks = threshold too low
        if 0.05 <= peak_ratio <= 0.20:
            score = 1.0 - abs(peak_ratio - 0.10)  # Ideal is 10% peaks
        elif peak_ratio < 0.05:
            score = peak_ratio / 0.05  # Penalize low density
        else:
            score = 0.20 / peak_ratio  # Penalize high density
        
        # Prefer higher thresholds for better signal separation
        score *= (threshold / max(candidates.values()))
        
        if score > best_score:
            best_score = score
            best_threshold = threshold
    
    return {
        "recommended_threshold": best_threshold,
        "best_score": best_score,
        "candidates": candidates,
        "statistics": {
            "mean": mean_val,
            "median": median_val,
            "std_dev": std_val,
            "p75": p75,
            "p90": p90,
            "p95": p95,
            "p99": p99,
            "sample_count": len(samples)
        },
        "peak_analysis": {
            "peaks_above_recommended": sum(1 for s in samples if s > best_threshold),
            "peak_ratio": sum(1 for s in samples if s > best_threshold) / len(samples)
        }
    }


def recommend_peak_threshold_z(fixes, min_speed):
    """
    Analyze all raw acceleration data across fixes to recommend optimal peakThresholdZ.
    
    Args:
        fixes: List of fix dictionaries with raw acceleration data
        min_speed: Minimum speed threshold for analysis
    
    Returns:
        Recommended peakThresholdZ value
    """
    all_samples = []
    
    # Collect all raw samples from moving fixes
    for fx in fixes:
        if fx["speed"] >= min_speed and "vert_abs_samples" in fx:
            all_samples.extend(fx["vert_abs_samples"])
    
    if not all_samples:
        print("    WARNING: No raw samples found for peak threshold analysis")
        return 5.0  # Default fallback
    
    # Analyze the distribution
    analysis = analyze_peak_threshold_distribution(all_samples)
    
    if "error" in analysis:
        print(f"    ERROR in peak threshold analysis: {analysis['error']}")
        return 5.0
    
    recommended = analysis["recommended_threshold"]
    peak_ratio = analysis["peak_analysis"]["peak_ratio"]
    
    print(f"\n--- Peak Threshold Z Analysis ---")
    print(f"  Total samples analyzed: {len(all_samples):,}")
    print(f"  Sample statistics: mean={analysis['statistics']['mean']:.2f}, std={analysis['statistics']['std_dev']:.2f}")
    print(f"  Percentiles: p75={analysis['statistics']['p75']:.2f}, p90={analysis['statistics']['p90']:.2f}, p95={analysis['statistics']['p95']:.2f}")
    print(f"  Recommended peakThresholdZ: {recommended:.3f}")
    print(f"  Expected peak ratio: {peak_ratio:.1%} ({analysis['peak_analysis']['peaks_above_recommended']} peaks)")
    
    return recommended
def plot_peak_threshold_analysis(all_samples, analysis, output_path):
    """
    Generate visualization plots to help users select optimal peakThresholdZ.
    
    Args:
        all_samples: List of all raw acceleration samples
        analysis: Dictionary from analyze_peak_threshold_distribution
        output_path: Base path for saving plots
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import numpy as np
    except ImportError:
        print("WARNING: matplotlib not installed, skipping peak threshold plots.")
        return
    
    # Create figure with subplots
    fig, axes = plt.subplots(2, 2, figsize=(15, 10))
    fig.suptitle('Peak Threshold Z Analysis - Select Optimal Threshold', fontsize=16, fontweight='bold')
    
    samples = np.array(all_samples)
    candidates = analysis["candidates"]
    recommended = analysis["recommended_threshold"]
    
    # 1. Histogram with candidate thresholds
    ax1 = axes[0, 0]
    ax1.hist(samples, bins=100, alpha=0.7, color='skyblue', edgecolor='black', density=True)
    
    # Show candidate thresholds
    colors = ['red', 'orange', 'yellow', 'green', 'blue', 'purple', 'brown']
    for i, (name, thresh) in enumerate(candidates.items()):
        if thresh > 0:
            color = colors[i % len(colors)]
            ax1.axvline(thresh, color=color, linestyle='--', linewidth=2, 
                       label=f'{name}: {thresh:.2f}')
    
    # Highlight recommended
    ax1.axvline(recommended, color='red', linestyle='-', linewidth=3, 
               label=f'RECOMMENDED: {recommended:.2f}', alpha=0.8)
    
    ax1.set_xlabel('Acceleration (m/s²)')
    ax1.set_ylabel('Density')
    ax1.set_title('Acceleration Distribution with Threshold Candidates')
    ax1.legend(fontsize=8, loc='upper right')
    ax1.grid(True, alpha=0.3)
    
    # 2. Peak density vs threshold curve
    ax2 = axes[0, 1]
    threshold_range = np.linspace(max(0.1, samples.min()), samples.max(), 100)
    peak_ratios = []
    
    for thresh in threshold_range:
        peaks_above = sum(1 for s in samples if s > thresh)
        peak_ratios.append(peaks_above / len(samples))
    
    ax2.plot(threshold_range, peak_ratios, 'b-', linewidth=2, label='Peak Ratio')
    ax2.axhline(y=0.10, color='green', linestyle='--', alpha=0.7, label='Ideal (10%)')
    ax2.axhline(y=0.05, color='orange', linestyle='--', alpha=0.7, label='Min (5%)')
    ax2.axhline(y=0.20, color='red', linestyle='--', alpha=0.7, label='Max (20%)')
    
    # Show recommended point
    rec_peak_ratio = analysis["peak_analysis"]["peak_ratio"]
    ax2.plot(recommended, rec_peak_ratio, 'ro', markersize=10, 
            label=f'Recommended: ({recommended:.2f}, {rec_peak_ratio:.1%})')
    
    ax2.set_xlabel('Peak Threshold Z')
    ax2.set_ylabel('Peak Ratio (samples above threshold)')
    ax2.set_title('Peak Density vs Threshold')
    ax2.legend(fontsize=8)
    ax2.grid(True, alpha=0.3)
    ax2.set_ylim(0, max(peak_ratios) * 1.1)
    
    # 3. Candidate evaluation scores
    ax3 = axes[1, 0]
    candidate_names = []
    candidate_scores = []
    candidate_thresholds = []
    
    # Calculate scores for all candidates
    for name, thresh in candidates.items():
        if thresh <= 0:
            continue
        
        peaks_above = sum(1 for s in samples if s > thresh)
        peak_ratio = peaks_above / len(samples)
        
        if 0.05 <= peak_ratio <= 0.20:
            score = 1.0 - abs(peak_ratio - 0.10)
        elif peak_ratio < 0.05:
            score = peak_ratio / 0.05
        else:
            score = 0.20 / peak_ratio
        
        score *= (thresh / max(candidates.values()))
        
        candidate_names.append(name.replace('_', ' ').title())
        candidate_scores.append(score)
        candidate_thresholds.append(thresh)
    
    bars = ax3.bar(range(len(candidate_names)), candidate_scores, 
                   color=['lightcoral' if t == recommended else 'lightblue' for t in candidate_thresholds])
    ax3.set_xticks(range(len(candidate_names)))
    ax3.set_xticklabels(candidate_names, rotation=45, ha='right')
    ax3.set_ylabel('Score')
    ax3.set_title('Candidate Threshold Evaluation')
    ax3.grid(True, alpha=0.3, axis='y')
    
    # Add value labels on bars
    for i, (bar, score, thresh) in enumerate(zip(bars, candidate_scores, candidate_thresholds)):
        height = bar.get_height()
        ax3.text(bar.get_x() + bar.get_width()/2., height + 0.01,
                f'{score:.3f}\n({thresh:.2f})', 
                ha='center', va='bottom', fontsize=8)
    
    # 4. Statistics summary
    ax4 = axes[1, 1]
    ax4.axis('off')
    
    stats_text = f"""
    Sample Statistics:
    ──────────────────────
    Total Samples: {len(all_samples):,}
    Mean: {analysis['statistics']['mean']:.3f}
    Median: {analysis['statistics']['median']:.3f}
    Std Dev: {analysis['statistics']['std_dev']:.3f}
    
    Percentiles:
    ──────────────────────
    P75: {analysis['statistics']['p75']:.3f}
    P90: {analysis['statistics']['p90']:.3f}
    P95: {analysis['statistics']['p95']:.3f}
    P99: {analysis['statistics']['p99']:.3f}
    
    Recommended Threshold:
    ──────────────────────
    Peak Threshold Z: {recommended:.3f}
    Expected Peak Ratio: {analysis['peak_analysis']['peak_ratio']:.1%}
    Peaks Above: {analysis['peak_analysis']['peaks_above_recommended']:,}
    
    Selection Criteria:
    ──────────────────────
    • Target peak ratio: 5-20%
    • Ideal: 10% peaks
    • Higher threshold preferred
    • Better signal separation
    """
    
    ax4.text(0.05, 0.95, stats_text, transform=ax4.transAxes, 
            fontsize=10, verticalalignment='top', fontfamily='monospace')
    
    plt.tight_layout()
    
    # Save plot
    plot_path = f"{output_path}_peak_threshold_analysis.png"
    plt.savefig(plot_path, dpi=150, bbox_inches='tight')
    print(f"\nPeak threshold analysis plot saved to: {plot_path}")
    
    return plot_path
def recommend_peak_ratio_threshold(peak_ratios, target_potholes):
    if not peak_ratios:
        return None
    if target_potholes <= 0:
        return 0.0
    sorted_vals = sorted(peak_ratios)
    if target_potholes >= len(sorted_vals):
        return min(sorted_vals[-1] + 0.001, 1.0)
    idx = max(target_potholes - 1, 0)
    return min(sorted_vals[idx] + 0.001, 1.0)




# ---------------------------------------------------------------------------
# Recommend smooth threshold via binary search
# ---------------------------------------------------------------------------
def recommend_smooth_thresholds(fixes, target_pct, cal, min_speed):
    """Find rmsSmoothMax and stdDevSmoothMax that yield ~target_pct% smooth fixes."""
    moving_fixes = [fx for fx in fixes if fx["speed"] >= min_speed]
    if not moving_fixes:
        return cal["rmsSmoothMax"], cal["stdDevSmoothMax"]

    # The ratio between rmsSmoothMax and stdDevSmoothMax from current calibration
    current_rms = cal["rmsSmoothMax"]
    current_std = cal["stdDevSmoothMax"]
    ratio = current_std / current_rms if current_rms > 0 else 2.5

    # Collect all avg_rms values for moving fixes
    rms_values = sorted([fx["avg_rms"] for fx in moving_fixes])
    std_values = sorted([fx["avg_std_dev"] for fx in moving_fixes])

    # Binary search on rmsSmoothMax
    lo, hi = 0.1, max(rms_values) * 2.0
    best_rms = current_rms
    for _ in range(100):
        mid_rms = (lo + hi) / 2.0
        mid_std = mid_rms * ratio
        smooth_count = sum(
            1 for fx in moving_fixes
            if fx["avg_rms"] < mid_rms and fx["avg_std_dev"] < mid_std
        )
        pct = 100.0 * smooth_count / len(moving_fixes)
        if abs(pct - target_pct) < 0.5:
            best_rms = mid_rms
            break
        if pct < target_pct:
            lo = mid_rms
        else:
            hi = mid_rms
        best_rms = mid_rms

    return round(best_rms, 3), round(best_rms * ratio, 3)


# ---------------------------------------------------------------------------
# Recommend rough threshold via binary search
# ---------------------------------------------------------------------------
def recommend_rough_thresholds(fixes, target_pct, cal, min_speed):
    """Find rmsRoughMin and stdDevRoughMin that yield ~target_pct% rough fixes."""
    moving_fixes = [fx for fx in fixes if fx["speed"] >= min_speed]
    if not moving_fixes:
        return cal["rmsRoughMin"], cal["stdDevRoughMin"]

    # Keep the ratio between rmsRoughMin and stdDevRoughMin from current calibration
    current_rms = cal["rmsRoughMin"]
    current_std = cal["stdDevRoughMin"]
    ratio = current_std / current_rms if current_rms > 0 else 0.667

    rms_values = sorted([fx["avg_rms"] for fx in moving_fixes])

    # Binary search on rmsRoughMin
    # Rough = fixes where avg_rms >= threshold AND avg_std_dev >= threshold
    # Lowering the threshold increases rough %, raising it decreases rough %
    lo, hi = 0.1, max(rms_values) * 2.0
    best_rms = current_rms
    for _ in range(100):
        mid_rms = (lo + hi) / 2.0
        mid_std = mid_rms * ratio
        rough_count = sum(
            1 for fx in moving_fixes
            if fx["avg_rms"] >= mid_rms and fx["avg_std_dev"] >= mid_std
        )
        pct = 100.0 * rough_count / len(moving_fixes)
        if abs(pct - target_pct) < 0.5:
            best_rms = mid_rms
            break
        if pct < target_pct:
            # Too few rough — lower the threshold
            hi = mid_rms
        else:
            # Too many rough — raise the threshold
            lo = mid_rms
        best_rms = mid_rms

    return round(best_rms, 3), round(best_rms * ratio, 3)


# ---------------------------------------------------------------------------
# Recommend hard brake/accel threshold via percentile
# ---------------------------------------------------------------------------
def recommend_event_threshold(fwd_max_values, target_count):
    """Find threshold that yields exactly target_count events above it."""
    if not fwd_max_values or target_count <= 0:
        return 15.0  # default
    sorted_vals = sorted(fwd_max_values, reverse=True)
    if target_count >= len(sorted_vals):
        return round(sorted_vals[-1] * 0.9, 3)
    # The threshold should be just below the target_count-th highest value
    threshold = sorted_vals[target_count - 1] - 0.001
    return round(max(threshold, 0.5), 3)


# ---------------------------------------------------------------------------
# Generate road quality KML for Google Earth
# ---------------------------------------------------------------------------


def generate_road_quality_kml(fixes, rec_cal, output_path, min_speed, start_ts_ms=0, rec_dt=None, norawdata=False):
    """Write a KML file with road quality colour-coded line segments and driver event placemarks.

    Uses the recommended calibration thresholds to reclassify each fix and detect driver events.
    Road quality: green=smooth, orange/yellow=average, red=rough.
    Driver events: red=hard brake, green=hard accel, blue=swerve, yellow=aggressive corner.
    Google Earth KML colours are AABBGGRR format.
    """
    from datetime import datetime, timezone

    def iso_from_offset(offset_ms):
        abs_ms = start_ts_ms + offset_ms
        if abs_ms <= 0:
            return ""
        try:
            dt = datetime.fromtimestamp(abs_ms / 1000.0, tz=timezone.utc)
            return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
        except (ValueError, OSError):
            return ""

    # KML colour constants (AABBGGRR)
    SMOOTH_COLOR = "ff00ff00"   # green
    AVERAGE_COLOR = "ff00aaff"  # orange-ish (BGR)
    ROUGH_COLOR = "ff0000ff"    # red

    SMOOTH_LINE = "ff00cc00"
    AVERAGE_LINE = "ff00aaff"
    ROUGH_LINE = "ff0000cc"

    # Driver event colours
    HARD_BRAKE_COLOR = "ffff0000"    # red
    HARD_ACCEL_COLOR = "ff00ff00"    # green
    SWERVE_COLOR = "ff0000ff"      # blue
    AGGRESSIVE_CORNER_COLOR = "ffffff00"  # yellow

    # Reclassify all fixes with recommended thresholds
    classified = []
    for fx in fixes:
        rq = classify_road_quality(
            fx["avg_rms"], fx["avg_std_dev"],
            rec_cal["rmsSmoothMax"], rec_cal["stdDevSmoothMax"],
            rec_cal["rmsRoughMin"], rec_cal["stdDevRoughMin"]
        )
        classified.append((fx, rq))

    with open(output_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
        f.write('<Document>\n')
        f.write('<name>Road Quality (Recommended Thresholds)</name>\n')
        f.write('<description>Colour-coded road quality using recommended calibration.\n')
        f.write(f'  rmsSmoothMax={rec_cal["rmsSmoothMax"]:.3f}, '
                f'stdDevSmoothMax={rec_cal["stdDevSmoothMax"]:.3f}\n')
        f.write(f'  rmsRoughMin={rec_cal["rmsRoughMin"]:.3f}, '
                f'stdDevRoughMin={rec_cal["stdDevRoughMin"]:.3f}\n')
        f.write('</description>\n')

        # --- Styles for line segments ---
        for style_id, color, width in [
            ("smoothLine", SMOOTH_LINE, 4),
            ("averageLine", AVERAGE_LINE, 4),
            ("roughLine", ROUGH_LINE, 5),
        ]:
            f.write(f'<Style id="{style_id}">\n')
            f.write(f'  <LineStyle>\n')
            f.write(f'    <color>{color}</color>\n')
            f.write(f'    <width>{width}</width>\n')
            f.write(f'  </LineStyle>\n')
            f.write(f'</Style>\n')

        # --- Colour-coded line segments ---
        f.write('<Folder>\n')
        f.write('<name>Road Quality Route</name>\n')

        # Group consecutive fixes with the same quality into segments
        if classified:
            seg_start = 0
            for i in range(1, len(classified)):
                if classified[i][1] != classified[seg_start][1] or i == len(classified) - 1:
                    # Close current segment
                    end = i if classified[i][1] != classified[seg_start][1] else i + 1
                    seg_rq = classified[seg_start][1]
                    style = {"smooth": "smoothLine", "average": "averageLine", "rough": "roughLine"}.get(seg_rq, "averageLine")

                    coords = []
                    for j in range(seg_start, min(end + 1, len(classified))):
                        fx_j = classified[j][0]
                        coords.append(f'{fx_j["lon"]},{fx_j["lat"]},0')

                    if len(coords) >= 2:
                        f.write('<Placemark>\n')
                        f.write(f'<name>{seg_rq} segment</name>\n')
                        f.write(f'<styleUrl>#{style}</styleUrl>\n')
                        f.write('<LineString>\n')
                        f.write('<tessellate>1</tessellate>\n')
                        f.write(f'<coordinates>{" ".join(coords)}</coordinates>\n')
                        f.write('</LineString>\n')
                        f.write('</Placemark>\n')

                    if classified[i][1] != classified[seg_start][1]:
                        seg_start = i

        f.write('</Folder>\n')

        # --- Driver events folder (only if rec_dt provided) ---
        if rec_dt is not None:
            # Detect driver events using recommended thresholds
            events = []
            for i, fx in enumerate(fixes):
                if fx["speed"] < min_speed:
                    continue
                
                # Skip driver event detection if a road feature was detected
                # (bumps/potholes can contaminate driver event metrics)
                feature = fx.get("feature")
                if feature:
                    continue
                
                # Hard brake: negative delta_speed + high fwd_max
                if fx["delta_speed"] < 0 and fx["fwd_max"] > rec_dt["hardBrakeFwdMax"]:
                    events.append({
                        "type": "Hard Brake",
                        "color": HARD_BRAKE_COLOR,
                        "icon": "http://maps.google.com/mapfiles/kml/pal4/icon57.png",
                        "fx": fx
                    })
                
                # Hard accel: positive delta_speed + high fwd_max
                elif fx["delta_speed"] > 0 and fx["fwd_max"] > rec_dt["hardAccelFwdMax"]:
                    events.append({
                        "type": "Hard Accel",
                        "color": HARD_ACCEL_COLOR,
                        "icon": "http://maps.google.com/mapfiles/kml/pal4/icon56.png",
                        "fx": fx
                    })
                
                # Swerve: high lateral acceleration
                if fx["lat_max"] > rec_dt["swerveLatMax"]:
                    events.append({
                        "type": "Swerve",
                        "color": SWERVE_COLOR,
                        "icon": "http://maps.google.com/mapfiles/kml/pal4/icon13.png",
                        "fx": fx
                    })
                
                # Aggressive cornering: high lat accel + course change
                if (fx["lat_max"] > rec_dt["aggressiveCornerLatMax"] and 
                    abs(fx["delta_course"]) > rec_dt["aggressiveCornerDCourse"]):
                    events.append({
                        "type": "Aggressive Corner",
                        "color": AGGRESSIVE_CORNER_COLOR,
                        "icon": "http://maps.google.com/mapfiles/kml/pal4/icon21.png",
                        "fx": fx
                    })

            # Write driver events placemarks
            if events:
                f.write('<Folder>\n')
                f.write('<name>Driver Events</name>\n')
                f.write(f'<description>Events detected using: '
                        f'hardBrakeFwdMax={rec_dt["hardBrakeFwdMax"]:.3f}, '
                        f'hardAccelFwdMax={rec_dt["hardAccelFwdMax"]:.3f}\n')
                f.write(f'  swerveLatMax={rec_dt["swerveLatMax"]:.3f}, '
                        f'aggressiveCornerLatMax={rec_dt["aggressiveCornerLatMax"]:.3f}\n')
                f.write(f'  aggressiveCornerDCourse={rec_dt["aggressiveCornerDCourse"]:.1f}</description>\n')
                
                for event in events:
                    fx = event["fx"]
                    ts_str = iso_from_offset(fx.get("ts_ms", 0))
                    speed = fx.get("speed", 0)
                    fwd_max = fx.get("fwd_max", 0)
                    lat_max = fx.get("lat_max", 0)
                    
                    f.write('<Placemark>\n')
                    f.write(f'<name>{event["type"]}</name>\n')
                    f.write(f'<styleUrl>#eventStyle</styleUrl>\n')
                    if ts_str:
                        f.write(f'<TimeStamp><when>{ts_str}</when></TimeStamp>\n')
                    f.write('<description><![CDATA[')
                    f.write(f'Speed: {speed:.1f} km/h<br/>')
                    f.write(f'Forward accel: {fwd_max:.2f} m/s²<br/>')
                    f.write(f'Lateral accel: {lat_max:.2f} m/s²')
                    f.write(']]></description>\n')
                    f.write(f'<Point><coordinates>{fx["lon"]},{fx["lat"]},0</coordinates></Point>\n')
                    f.write('</Placemark>\n')
                f.write('</Folder>\n')

        # --- Road Features folder ---
        if not norawdata:
            # Collect road features
            features = []
            for fx in fixes:
                if fx["speed"] >= min_speed:
                    feature = fx.get("feature")
                    if feature == "bump":
                        features.append({
                            "type": "Bump",
                            "color": "ff00ffff",  # Yellow (BGR format)
                            "icon": "http://maps.google.com/mapfiles/kml/pal4/icon27.png",
                            "fx": fx
                        })
                    elif feature == "pothole":
                        features.append({
                            "type": "Pothole", 
                            "color": "ff8B4513",  # Brown (BGR format)
                            "icon": "http://maps.google.com/mapfiles/kml/pal4/icon31.png",
                            "fx": fx
                        })
            
            # Write road features placemarks
            if features:
                f.write('<Folder>\n')
                f.write('<name>Road Features</name>\n')
                f.write(f'<description>Features detected using: '
                        f'peakThresholdZ={rec_cal.get("peakThresholdZ", 5.0):.3f}, '
                        f'peakRatioFeatureMin={rec_cal.get("peakRatioFeatureMin", 0.4):.3f}</description>\n')
                
                # Define feature styles
                f.write('<Style id="bumpStyle"><IconStyle><color>ff00ffff</color><scale>1.2</scale><Icon><href>http://maps.google.com/mapfiles/kml/pal4/icon27.png</href></Icon></IconStyle></Style>\n')
                f.write('<Style id="potholeStyle"><IconStyle><color>ff8B4513</color><scale>1.2</scale><Icon><href>http://maps.google.com/mapfiles/kml/pal4/icon31.png</href></Icon></IconStyle></Style>\n')
                
                for feature in features:
                    fx = feature["fx"]
                    ts_str = iso_from_offset(fx.get("ts_ms", 0))
                    speed = fx.get("speed", 0)
                    rms_vert = fx.get("rms_vert", 0)
                    peak_ratio = fx.get("peak_ratio", 0)
                    
                    f.write('<Placemark>\n')
                    f.write(f'<name>{feature["type"]}</name>\n')
                    f.write(f'<styleUrl>#{feature["type"].lower().replace(" ", "_")}Style</styleUrl>\n')
                    if ts_str:
                        f.write(f'<TimeStamp><when>{ts_str}</when></TimeStamp>\n')
                    f.write('<description><![CDATA[')
                    f.write(f'Speed: {speed:.1f} km/h<br/>')
                    f.write(f'RMS Vertical: {rms_vert:.2f} m/s²<br/>')
                    f.write(f'Peak Ratio: {peak_ratio:.3f}')
                    f.write(']]></description>\n')
                    f.write(f'<Point><coordinates>{fx["lon"]},{fx["lat"]},0</coordinates></Point>\n')
                    f.write('</Placemark>\n')
                f.write('</Folder>\n')

        # --- Legend folder ---
        f.write('<Folder>\n')
        f.write('<name>Legend</name>\n')
        f.write('<description>Road Quality: Green=Smooth, Orange=Average, Red= Rough')
        if rec_dt is not None:
            f.write('<br/>Driver Events: Red=Hard Brake, Green=Hard Accel, Blue=Swerve, Yellow=Aggressive Corner')
        if not norawdata:
            f.write('<br/>Road Features: Yellow=Bumps, Brown=Potholes')
        f.write('</description>\n')
        f.write('</Folder>\n')

        f.write('</Document>\n')
        f.write('</kml>\n')


# ---------------------------------------------------------------------------
# Generate plots
# ---------------------------------------------------------------------------
def generate_plots(fixes, cal, dt, rec_cal, rec_dt, output_path, min_speed, norawdata=False):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("WARNING: matplotlib not installed, skipping plots.")
        return

    moving = [fx for fx in fixes if fx["speed"] >= min_speed]
    if not moving:
        print("WARNING: No moving fixes, skipping plots.")
        return

    # Determine subplot layout based on data availability
    if norawdata:
        fig, axes = plt.subplots(3, 2, figsize=(14, 15))
    else:
        # Check if we have new metrics to plot
        has_new_metrics = any(fx.get("lat_max", 0) > 0 for fx in moving) or \
                        any(fx.get("delta_course", 0) != 0 for fx in moving) or \
                        any(fx.get("vert_abs_samples") for fx in moving)
        
        if has_new_metrics:
            fig, axes = plt.subplots(4, 3, figsize=(18, 20))
        else:
            fig, axes = plt.subplots(2, 3, figsize=(18, 10))

    # --- 1. RMS Vert distribution ---
    ax = axes[0, 0]
    rms_vals = [fx["avg_rms"] for fx in moving]
    ax.hist(rms_vals, bins=50, color="steelblue", alpha=0.7, edgecolor="black", linewidth=0.5)
    ax.axvline(cal["rmsSmoothMax"], color="red", linestyle="--", linewidth=1.5,
               label=f'Current rmsSmoothMax={cal["rmsSmoothMax"]:.2f}')
    ax.axvline(rec_cal["rmsSmoothMax"], color="green", linestyle="-", linewidth=2,
               label=f'Recommended={rec_cal["rmsSmoothMax"]:.2f}')
    ax.axvline(cal["rmsRoughMin"], color="orange", linestyle=":", linewidth=1.5,
               label=f'Current rmsRoughMin={cal["rmsRoughMin"]:.2f}')
    if rec_cal.get("rmsRoughMin") != cal.get("rmsRoughMin"):
        ax.axvline(rec_cal["rmsRoughMin"], color="darkgreen", linestyle="-.", linewidth=2,
                   label=f'Rec rmsRoughMin={rec_cal["rmsRoughMin"]:.2f}')
    ax.set_xlabel("Avg RMS (Vertical)")
    ax.set_ylabel("Fix Count")
    ax.set_title("Road Quality: RMS Distribution")
    ax.legend(fontsize=7)
    ax.grid(True, alpha=0.3)

    # --- 2. StdDev Vert distribution ---
    ax = axes[0, 1]
    std_vals = [fx["avg_std_dev"] for fx in moving]
    ax.hist(std_vals, bins=50, color="steelblue", alpha=0.7, edgecolor="black", linewidth=0.5)
    ax.axvline(cal["stdDevSmoothMax"], color="red", linestyle="--", linewidth=1.5,
               label=f'Current stdDevSmoothMax={cal["stdDevSmoothMax"]:.2f}')
    ax.axvline(rec_cal["stdDevSmoothMax"], color="green", linestyle="-", linewidth=2,
               label=f'Recommended={rec_cal["stdDevSmoothMax"]:.2f}')
    if rec_cal.get("stdDevRoughMin") != cal.get("stdDevRoughMin"):
        ax.axvline(cal["stdDevRoughMin"], color="orange", linestyle=":", linewidth=1.5,
                   label=f'Current stdDevRoughMin={cal["stdDevRoughMin"]:.2f}')
        ax.axvline(rec_cal["stdDevRoughMin"], color="darkgreen", linestyle="-.", linewidth=2,
                   label=f'Rec stdDevRoughMin={rec_cal["stdDevRoughMin"]:.2f}')
    ax.set_xlabel("Avg StdDev (Vertical)")
    ax.set_ylabel("Fix Count")
    ax.set_title("Road Quality: StdDev Distribution")
    ax.legend(fontsize=7)
    ax.grid(True, alpha=0.3)

    # --- 3. Road quality bar chart (current vs recommended) ---
    if norawdata:
        ax = axes[1, 0]
    else:
        ax = axes[0, 2]
    cur_counts = {"smooth": 0, "average": 0, "rough": 0}
    rec_counts = {"smooth": 0, "average": 0, "rough": 0}
    for fx in moving:
        rq = fx["road_quality"]
        if rq in cur_counts:
            cur_counts[rq] += 1
        rq_rec = classify_road_quality(
            fx["avg_rms"], fx["avg_std_dev"],
            rec_cal["rmsSmoothMax"], rec_cal["stdDevSmoothMax"],
            rec_cal["rmsRoughMin"], rec_cal["stdDevRoughMin"]
        )
        if rq_rec in rec_counts:
            rec_counts[rq_rec] += 1

    labels = ["smooth", "average", "rough"]
    colors_pie = ["#4CAF50", "#FF9800", "#F44336"]
    cur_vals = [cur_counts[l] for l in labels]
    rec_vals = [rec_counts[l] for l in labels]

    bar_x = range(len(labels))
    width = 0.35
    ax.bar([x - width / 2 for x in bar_x], cur_vals, width, label="Current", color=colors_pie, alpha=0.5, edgecolor="black")
    ax.bar([x + width / 2 for x in bar_x], rec_vals, width, label="Recommended", color=colors_pie, alpha=0.9, edgecolor="black")
    ax.set_xticks(list(bar_x))
    ax.set_xticklabels(labels)
    ax.set_ylabel("Fix Count")
    ax.set_title("Road Quality: Current vs Recommended")
    ax.legend(fontsize=8)
    ax.grid(True, alpha=0.3, axis="y")

    # --- Time series: speed + road quality strip ---
    if norawdata:
        ax = axes[2, 0]
    else:
        ax = axes[1, 2]
    times = [(fx["ts_ms"] - fixes[0]["ts_ms"]) / 1000.0 for fx in fixes]
    speeds = [fx["speed"] for fx in fixes]
    ax.plot(times, speeds, "b-", linewidth=0.6, alpha=0.7)
    ax.set_ylabel("Speed (km/h)")
    ax.set_xlabel("Time (s)")
    ax.set_title("Speed + Road Quality")
    ax.grid(True, alpha=0.3)

    rq_colors = {"smooth": "#4CAF50", "average": "#FF9800", "rough": "#F44336", None: "#CCCCCC"}
    for i in range(len(fixes) - 1):
        rq = fixes[i].get("road_quality")
        c = rq_colors.get(rq, "#CCCCCC")
        ax.axvspan(times[i], times[i + 1], ymin=0, ymax=0.05, color=c, alpha=0.8)

    # --- Feature detection summary ---
    if norawdata:
        ax = axes[2, 1]
    else:
        # Skip if we have new metrics (will be plotted below)
        if not has_new_metrics:
            ax = axes[1, 0]
    
    if norawdata or not has_new_metrics:
        feature_counts = {"pothole": 0, "bump": 0, None: 0}
        for fx in moving:
            feature = fx.get("feature")
            if feature in feature_counts:
                feature_counts[feature] += 1
        
        labels = ["Potholes", "Bumps", "None"]
        colors = ["#8B4513", "#FFD700", "#E0E0E0"]
        counts = [feature_counts["pothole"], feature_counts["bump"], feature_counts[None]]
        
        wedges, texts, autotexts = ax.pie(counts, labels=labels, colors=colors, autopct='%1.1f%%',
                                          startangle=90, textprops={'fontsize': 8})
        ax.set_title("Feature Detection\n(Current Thresholds)", fontsize=10)
        
        # Add count annotations
        for i, (wedge, count) in enumerate(zip(wedges, counts)):
            if count > 0:
                angle = (wedge.theta1 + wedge.theta2) / 2
                if np is not None:
                    x = 0.7 * np.cos(np.radians(angle))
                    y = 0.7 * np.sin(np.radians(angle))
                else:
                    # Fallback without numpy
                    import math
                    x = 0.7 * math.cos(math.radians(angle))
                    y = 0.7 * math.sin(math.radians(angle))
                ax.text(x, y, f"({count})", ha='center', va='center', fontsize=7, fontweight='bold')

    # --- Metrics inference explanation (for norawdata case) ---
    if norawdata:
        ax = axes[2, 1]
        ax.axis('off')
        explanation = """METRICS INFERENCE METHOD:

Road Quality:
  • RMS: Root Mean Square of vertical accel
  • StdDev: Standard deviation of vertical accel
  • Classification: Dual-threshold (RMS & StdDev)

Driver Events:
  • Hard Brake/Accel: fwdMax percentile selection
  • Swerves: lat_max percentile selection
  • Aggressive Corner: lat_max + |delta_course|

Road Features:
  • Bumps/Potholes: magMaxSevereMin + net vertical sign

All thresholds use percentile-based
selection to match target event counts.

Note: Feature detection requires
raw accelerometer data."""
        ax.text(0.05, 0.95, explanation, transform=ax.transAxes, va='top', ha='left',
               fontsize=8, family='monospace',
               bbox=dict(boxstyle='round', facecolor='lightgray', alpha=0.8))

    if not norawdata and has_new_metrics:
        # --- FwdMax CDF for braking (delta_speed < 0) ---
        ax = axes[1, 0]
        brake_fwd = sorted([fx["fwd_max"] for fx in moving if fx["delta_speed"] < 0])
        if brake_fwd:
            cdf_y = [(i + 1) / len(brake_fwd) * 100.0 for i in range(len(brake_fwd))]
            ax.plot(brake_fwd, cdf_y, "r-", linewidth=1)
            ax.axvline(dt["hardBrakeFwdMax"], color="red", linestyle="--", linewidth=1.5,
                       label=f'Current={dt["hardBrakeFwdMax"]:.1f}')
            ax.axvline(rec_dt["hardBrakeFwdMax"], color="green", linestyle="-", linewidth=2,
                       label=f'Recommended={rec_dt["hardBrakeFwdMax"]:.1f}')
            # Add annotation showing percentile
            current_pct = sum(1 for v in brake_fwd if v >= dt["hardBrakeFwdMax"]) / len(brake_fwd) * 100
            rec_pct = sum(1 for v in brake_fwd if v >= rec_dt["hardBrakeFwdMax"]) / len(brake_fwd) * 100
            ax.text(0.02, 0.98, f'Current: {current_pct:.1f}% ({len(brake_fwd)} events)\nRecommended: {rec_pct:.1f}% ({sum(1 for v in brake_fwd if v >= rec_dt["hardBrakeFwdMax"])} events)',
                   transform=ax.transAxes, va='top', ha='left', fontsize=7,
                   bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
        ax.set_xlabel("fwdMax (m/s²) — Braking Fixes")
        ax.set_ylabel("Cumulative %")
        ax.set_title("Hard Brake Threshold CDF")
        # Only add legend if there are labeled artists
        if brake_fwd:
            ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

        # --- FwdMax CDF for acceleration (delta_speed > 0) ---
        ax = axes[1, 1]
        accel_fwd = sorted([fx["fwd_max"] for fx in moving if fx["delta_speed"] > 0])
        if accel_fwd:
            cdf_y = [(i + 1) / len(accel_fwd) * 100.0 for i in range(len(accel_fwd))]
            ax.plot(accel_fwd, cdf_y, "orange", linewidth=1)
            ax.axvline(dt["hardAccelFwdMax"], color="red", linestyle="--", linewidth=1.5,
                       label=f'Current={dt["hardAccelFwdMax"]:.1f}')
            ax.axvline(rec_dt["hardAccelFwdMax"], color="green", linestyle="-", linewidth=2,
                       label=f'Recommended={rec_dt["hardAccelFwdMax"]:.1f}')
            # Add annotation showing percentile
            current_pct = sum(1 for v in accel_fwd if v >= dt["hardAccelFwdMax"]) / len(accel_fwd) * 100
            rec_pct = sum(1 for v in accel_fwd if v >= rec_dt["hardAccelFwdMax"]) / len(accel_fwd) * 100
            ax.text(0.02, 0.98, f'Current: {current_pct:.1f}% ({len(accel_fwd)} events)\nRecommended: {rec_pct:.1f}% ({sum(1 for v in accel_fwd if v >= rec_dt["hardAccelFwdMax"])} events)',
                   transform=ax.transAxes, va='top', ha='left', fontsize=7,
                   bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
        ax.set_xlabel("fwdMax (m/s²) — Acceleration Fixes")
        ax.set_ylabel("Cumulative %")
        ax.set_title("Hard Accel Threshold CDF")
        # Only add legend if there are labeled artists
        if accel_fwd:
            ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

        # --- Swerve detection: lat_max distribution ---
        ax = axes[2, 0]
        lat_vals = [fx["lat_max"] for fx in moving if fx["lat_max"] > 0]
        if lat_vals:
            ax.hist(lat_vals, bins=40, color="blue", alpha=0.6, edgecolor="black", linewidth=0.5)
            ax.axvline(dt["swerveLatMax"], color="red", linestyle="--", linewidth=1.5,
                       label=f'Current={dt["swerveLatMax"]:.1f}')
            if rec_dt.get("swerveLatMax") != dt.get("swerveLatMax"):
                ax.axvline(rec_dt["swerveLatMax"], color="green", linestyle="-", linewidth=2,
                           label=f'Recommended={rec_dt["swerveLatMax"]:.1f}')
                # Add annotation
                current_count = sum(1 for v in lat_vals if v >= dt["swerveLatMax"])
                rec_count = sum(1 for v in lat_vals if v >= rec_dt["swerveLatMax"])
                ax.text(0.98, 0.98, f'Current: {current_count} swerves\nRecommended: {rec_count} swerves',
                       transform=ax.transAxes, va='top', ha='right', fontsize=7,
                       bbox=dict(boxstyle='round', facecolor='lightblue', alpha=0.5))
        ax.set_xlabel("lat_max (m/s²)")
        ax.set_ylabel("Fix Count")
        ax.set_title("Swerve Detection: Lateral Acceleration")
        # Only add legend if there are labeled artists
        if lat_vals and (rec_dt.get("swerveLatMax") != dt.get("swerveLatMax")):
            ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

        # --- Aggressive cornering: lat_max vs delta_course scatter ---
        ax = axes[2, 1]
        lat_vals = [fx["lat_max"] for fx in moving if fx["lat_max"] > 0]
        dcourse_vals = [abs(fx["delta_course"]) for fx in moving if fx["lat_max"] > 0]
        if lat_vals and dcourse_vals:
            scatter = ax.scatter(dcourse_vals, lat_vals, alpha=0.5, s=10, c='purple')
            # Current thresholds
            ax.axhline(dt["aggressiveCornerLatMax"], color="red", linestyle="--", linewidth=1.5,
                       label=f'Current lat_max={dt["aggressiveCornerLatMax"]:.1f}')
            ax.axvline(dt["aggressiveCornerDCourse"], color="red", linestyle="--", linewidth=1.5,
                       label=f'Current d_course={dt["aggressiveCornerDCourse"]:.1f}')
            # Recommended thresholds
            if rec_dt.get("aggressiveCornerLatMax") != dt.get("aggressiveCornerLatMax"):
                ax.axhline(rec_dt["aggressiveCornerLatMax"], color="green", linestyle="-", linewidth=2,
                           label=f'Rec lat_max={rec_dt["aggressiveCornerLatMax"]:.1f}')
            if rec_dt.get("aggressiveCornerDCourse") != dt.get("aggressiveCornerDCourse"):
                ax.axvline(rec_dt["aggressiveCornerDCourse"], color="green", linestyle="-", linewidth=2,
                           label=f'Rec d_course={rec_dt["aggressiveCornerDCourse"]:.1f}')
            # Count aggressive corners
            current_agg = sum(1 for fx in moving 
                            if fx["lat_max"] >= dt["aggressiveCornerLatMax"] and 
                               abs(fx["delta_course"]) >= dt["aggressiveCornerDCourse"])
            rec_agg = sum(1 for fx in moving 
                        if fx["lat_max"] >= rec_dt["aggressiveCornerLatMax"] and 
                           abs(fx["delta_course"]) >= rec_dt["aggressiveCornerDCourse"])
            ax.text(0.98, 0.02, f'Current: {current_agg} events\nRecommended: {rec_agg} events',
                   transform=ax.transAxes, va='bottom', ha='right', fontsize=7,
                   bbox=dict(boxstyle='round', facecolor='lightyellow', alpha=0.5))
        ax.set_xlabel("|delta_course| (degrees)")
        ax.set_ylabel("lat_max (m/s²)")
        ax.set_title("Aggressive Cornering Detection")
        # Only add legend if there are labeled artists
        if lat_vals and dcourse_vals:
            ax.legend(fontsize=7, loc='upper left')
        ax.grid(True, alpha=0.3)

        # --- Bumps/Potholes: peak ratio distribution ---
        ax = axes[2, 2]
        peak_ratios = [fx["peak_ratio"] for fx in moving if fx["peak_ratio"] > 0]
        if peak_ratios:
            ax.hist(peak_ratios, bins=40, color="brown", alpha=0.6, edgecolor="black", linewidth=0.5)
            # Current threshold
            current_ratio = cal.get("peakRatioRoughMin", 1.5)
            ax.axvline(current_ratio, color="red", linestyle="--", linewidth=1.5,
                       label=f'Current={current_ratio:.2f}')
            # Recommended threshold
            if rec_cal.get("peakRatioRoughMin") != current_ratio:
                ax.axvline(rec_cal["peakRatioRoughMin"], color="green", linestyle="-", linewidth=2,
                           label=f'Recommended={rec_cal["peakRatioRoughMin"]:.2f}')
                # Count bumps and potholes
                current_bumps = sum(1 for fx in moving if fx.get("feature") == "bump")
                current_potholes = sum(1 for fx in moving if fx.get("feature") == "pothole")
                # Estimate with recommended threshold
                rec_bumps = sum(
                    1 for fx in moving
                    if fx.get("feature") == "bump"
                )
                rec_potholes = sum(
                    1 for fx in moving
                    if fx.get("feature") == "pothole"
                )
                ax.text(0.98, 0.98, f'Current: {current_bumps} bumps, {current_potholes} potholes\nRecommended: {rec_bumps} bumps, {rec_potholes} potholes',
                       transform=ax.transAxes, va='top', ha='right', fontsize=7,
                       bbox=dict(boxstyle='round', facecolor='lightgreen', alpha=0.5))
        ax.set_xlabel("Peak Ratio")
        ax.set_ylabel("Fix Count")
        ax.set_title("Bump/Pothole Detection: Peak Ratio")
        # Only add legend if there are labeled artists
        if peak_ratios and (rec_cal.get("peakRatioRoughMin") != current_ratio):
            ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

        # --- Feature detection summary ---
        ax = axes[3, 0]
        feature_counts = {"pothole": 0, "bump": 0, None: 0}
        for fx in moving:
            feature = fx.get("feature")
            if feature in feature_counts:
                feature_counts[feature] += 1
        
        labels = ["Potholes", "Bumps", "None"]
        colors = ["#8B4513", "#FFD700", "#E0E0E0"]
        counts = [feature_counts["pothole"], feature_counts["bump"], feature_counts[None]]
        
        wedges, texts, autotexts = ax.pie(counts, labels=labels, colors=colors, autopct='%1.1f%%',
                                          startangle=90, textprops={'fontsize': 8})
        ax.set_title("Feature Detection\n(Current Thresholds)", fontsize=10)
        
        # Add count annotations
        for i, (wedge, count) in enumerate(zip(wedges, counts)):
            if count > 0:
                angle = (wedge.theta1 + wedge.theta2) / 2
                if np is not None:
                    x = 0.7 * np.cos(np.radians(angle))
                    y = 0.7 * np.sin(np.radians(angle))
                else:
                    # Fallback without numpy
                    import math
                    x = 0.7 * math.cos(math.radians(angle))
                    y = 0.7 * math.sin(math.radians(angle))
                ax.text(x, y, f"({count})", ha='center', va='center', fontsize=7, fontweight='bold')

        # --- Metrics inference explanation ---
        ax = axes[3, 1]
        ax.axis('off')
        explanation = """METRICS INFERENCE METHOD:

Road Quality:
  • RMS: Root Mean Square of vertical accel
  • StdDev: Standard deviation of vertical accel
  • Classification: Dual-threshold (RMS & StdDev)

Driver Events:
  • Hard Brake/Accel: fwdMax percentile selection
  • Swerves: lat_max percentile selection
  • Aggressive Corner: lat_max + |delta_course|

Road Features:
  • Bumps/Potholes: magMaxSevereMin + net vertical sign

All thresholds use percentile-based
selection to match target event counts."""
        ax.text(0.05, 0.95, explanation, transform=ax.transAxes, va='top', ha='left',
               fontsize=8, family='monospace',
               bbox=dict(boxstyle='round', facecolor='lightgray', alpha=0.8))

    plt.tight_layout()
    plt.savefig(output_path, dpi=150)
    plt.close()
    print(f"  Plot: {output_path}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="Recommend calibration & driver thresholds from a recorded track."
    )
    parser.add_argument("track", help="Path to SJGpsUtil JSON track file")
    parser.add_argument("--smooth", type=float, default=60.0,
                        help="Target %% of moving fixes classified as smooth road (default: 60)")
    parser.add_argument("--rough", type=float, default=None,
                        help="Target %% of moving fixes classified as rough road (e.g. --rough=10). "
                             "If specified, rmsRoughMin and stdDevRoughMin will also be recommended.")
    parser.add_argument("--hardbrake", type=int, default=5,
                        help="Target number of hard braking events (default: 5)")
    parser.add_argument("--hardaccel", type=int, default=5,
                        help="Target number of hard acceleration events (default: 5)")
    parser.add_argument("--swerves", type=int, default=None,
                        help="Target number of swerve events (requires raw data)")
    parser.add_argument("--aggcorner", type=int, default=None,
                        help="Target number of aggressive corner events (requires raw data)")
    parser.add_argument("--bumps", type=int, default=None,
                        help="Target number of bump features (requires raw data)")
    parser.add_argument("--potholes", type=int, default=None,
                        help="Target number of pothole features (requires raw data)")
    parser.add_argument("--norawdata", action="store_true",
                        help="Use pre-computed accel metrics from JSON (no raw data needed). "
                             "Only road quality calibration will be recommended.")
    parser.add_argument("--recommendPeakZ", action="store_true",
                        help="Analyze raw acceleration data and recommend optimal peakThresholdZ "
                             "with visualization plots to support the decision.")
    parser.add_argument("--peakThresholdZ", type=float, default=None,
                        help="Override peakThresholdZ value for feature detection "
                             "(e.g., --peakThresholdZ 6.9). Use with --recommendPeakZ output.")
    args = parser.parse_args()

    # Load track
    try:
        with open(args.track, "r") as f:
            track_data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error loading '{args.track}': {e}")
        sys.exit(1)

    # Extract settings from track file
    cal = extract_calibration(track_data)
    dt = extract_driver_thresholds(track_data)
    base_gravity = extract_base_gravity_vector(track_data)
    min_speed = dt["minSpeedKmph"]
    
    # Apply peakThresholdZ override if provided
    if args.peakThresholdZ is not None:
        original_peak_z = cal.get("peakThresholdZ", 5.0)
        cal["peakThresholdZ"] = args.peakThresholdZ
        print(f"Overriding peakThresholdZ: {original_peak_z:.3f} -> {args.peakThresholdZ:.3f}")

    print("=" * 70)
    print("CALIBRATION & THRESHOLD RECOMMENDER")
    print("=" * 70)
    print(f"Track: {args.track}")
    rough_str = f", rough={args.rough}%" if args.rough is not None else ""
    print(f"Targets: smooth={args.smooth}%{rough_str}, hardBrake={args.hardbrake}, hardAccel={args.hardaccel}")
    print(f"Min speed for detection: {min_speed} km/h")

    # Compute vehicle basis
    g_unit, fwd_unit, lat_unit = (None, None, None)
    if base_gravity:
        g_unit, fwd_unit, lat_unit = compute_vehicle_basis(base_gravity)
        print(f"Gravity vector: [{base_gravity[0]:.3f}, {base_gravity[1]:.3f}, {base_gravity[2]:.3f}]")
    else:
        print("WARNING: No baseGravityVector found, using device axes as fallback")

    print(f"\nCurrent calibration: {json.dumps(cal, indent=2)}")
    if not args.norawdata:
        print(f"\nCurrent driver thresholds: {json.dumps(dt, indent=2)}")
    else:
        print("\nMode: --norawdata (using pre-computed metrics, road quality only)")

    # Extract and process data points
    points = extract_data_points(track_data)
    print(f"\nTotal data points: {len(points)}")

    # Handle --recommendPeakZ mode
    if args.recommendPeakZ:
        print("\n" + "=" * 70)
        print("PEAK THRESHOLD Z RECOMMENDATION MODE")
        print("=" * 70)
        
        # Validate raw data availability
        has_raw_data = False
        for point in points:
            if isinstance(point, str):
                try:
                    point = json.loads(point)
                except json.JSONDecodeError:
                    continue
            accel = point.get("accel", {}) if isinstance(point, dict) else {}
            raw_data = accel.get("raw")
            if raw_data:
                has_raw_data = True
                break
        
        if not has_raw_data:
            print("ERROR: Raw accelerometer data is required for peak threshold analysis.")
            print("       Please use a track file with raw acceleration data.")
            sys.exit(1)
        
        # Calculate actual sampling rate from data
        actual_sampling_rate = calculate_actual_sampling_rate(points)
        if actual_sampling_rate:
            print(f"\nDetected sampling rate: {actual_sampling_rate:.1f} Hz")
        else:
            print("\nWarning: Could not determine sampling rate, using default 100 Hz")
            actual_sampling_rate = 100.0
        
        # Process data to get fixes with raw samples
        metrics_history = deque()
        fixes = []
        skipped = 0
        
        # Compute vehicle basis from recorded track gravity
        g_unit, fwd_unit, lat_unit = (None, None, None)
        if base_gravity:
            g_unit, fwd_unit, lat_unit = compute_vehicle_basis(base_gravity)
            print(f"Using recorded track gravity: [{base_gravity[0]:.3f}, {base_gravity[1]:.3f}, {base_gravity[2]:.3f}]")
        else:
            print("WARNING: No baseGravityVector found, using device axes as fallback")
            g_unit, fwd_unit, lat_unit = [0, 0, 1], [0, 1, 0], [1, 0, 0]
        
        for i, point in enumerate(points):
            if isinstance(point, str):
                try:
                    point = json.loads(point)
                except json.JSONDecodeError:
                    skipped += 1
                    continue
            
            gps = point.get("gps", {})
            lat = gps.get("lat")
            lon = gps.get("lon")
            speed = gps.get("speed", 0) or 0
            course = gps.get("course", 0) or 0
            ts = gps.get("ts", 0) or 0
            
            if lat is None or lon is None:
                skipped += 1
                continue
            
            accel = point.get("accel", {})
            raw_data = accel.get("raw")
            
            if not raw_data:
                skipped += 1
                continue
            
            # Process raw data to get samples
            fix_metrics = compute_fix_metrics(
                raw_data=raw_data,
                speed_kmph=speed,
                cal=cal,
                dt=dt,
                g_unit=g_unit,
                fwd_unit=fwd_unit,
                lat_unit=lat_unit,
                metrics_history=metrics_history,
                store_peak_samples=True,
                actual_sampling_rate=actual_sampling_rate,
            )
            
            fix = {
                "lat": lat,
                "lon": lon,
                "speed": speed,
                "course": course,
                "ts_ms": ts,
                "vert_abs_samples": fix_metrics.get("vert_abs_samples", [])
            }
            fixes.append(fix)
        
        print(f"\nProcessed: {len(fixes)} fixes with raw data, skipped: {skipped}")
        
        # Analyze and recommend peak threshold
        recommended_peak_z = recommend_peak_threshold_z(fixes, min_speed)
        
        # Generate visualization
        all_samples = []
        for fx in fixes:
            if fx["speed"] >= min_speed and "vert_abs_samples" in fx:
                all_samples.extend(fx["vert_abs_samples"])
        
        if all_samples:
            analysis = analyze_peak_threshold_distribution(all_samples)
            output_base = args.track.rsplit('.', 1)[0]
            plot_path = plot_peak_threshold_analysis(all_samples, analysis, output_base)
            
            print(f"\n" + "=" * 70)
            print("PEAK THRESHOLD Z RECOMMENDATION SUMMARY")
            print("=" * 70)
            print(f"\nRecommended peakThresholdZ: {recommended_peak_z:.3f}")
            print(f"\nTo use this recommendation in your vehicle profile:")
            print(f"  \"calibration\": {{")
            print(f"    \"peakThresholdZ\": {recommended_peak_z:.3f},")
            print(f"    // ... other calibration settings")
            print(f"  }}")
            print(f"\nOr run with full analysis:")
            print(f"  python recommend_thresholds.py {args.track} --bumps 5 --potholes 5")
        
        return
    
    raw_targets = [args.swerves, args.aggcorner, args.bumps, args.potholes]
    raw_targets_requested = any(t is not None for t in raw_targets)
    if args.norawdata and raw_targets_requested:
        print("ERROR: Raw-data targets were provided, but --norawdata was enabled.")
        print("       Remove --norawdata or omit raw-data targets (swerves/aggcorner/bumps/potholes).")
        sys.exit(1)

    has_raw_data = False
    if not args.norawdata:
        for point in points:
            if isinstance(point, str):
                try:
                    point = json.loads(point)
                except json.JSONDecodeError:
                    continue
            accel = point.get("accel", {}) if isinstance(point, dict) else {}
            raw_data = accel.get("raw")
            if raw_data:
                has_raw_data = True
                break
        if not has_raw_data:
            print("ERROR: Raw accelerometer data is required but not found in the track JSON.")
            print("       Re-run with --norawdata for road quality only, or use a track with raw accel data.")
            sys.exit(1)

    # Calculate actual sampling rate from data (only if we have raw data)
    actual_sampling_rate = None
    if not args.norawdata and has_raw_data:
        actual_sampling_rate = calculate_actual_sampling_rate(points)
        if actual_sampling_rate:
            print(f"\nDetected sampling rate: {actual_sampling_rate:.1f} Hz")
        else:
            print("\nWarning: Could not determine sampling rate, using default 100 Hz")
            actual_sampling_rate = 100.0

    metrics_history = deque()
    fixes = []
    skipped = 0

    store_peak_samples = args.bumps is not None or args.potholes is not None

    for i, point in enumerate(points):
        if isinstance(point, str):
            try:
                point = json.loads(point)
            except json.JSONDecodeError:
                skipped += 1
                continue

        gps = point.get("gps", {})
        lat = gps.get("lat")
        lon = gps.get("lon")
        speed = gps.get("speed", 0) or 0
        course = gps.get("course", 0) or 0
        ts = gps.get("ts", 0) or 0

        if lat is None or lon is None:
            skipped += 1
            continue

        accel = point.get("accel", {})

        if args.norawdata:
            # Read pre-computed metrics from JSON accel block
            avg_rms = accel.get("avgRms")
            avg_std_dev = accel.get("avgStdDev")
            rms_vert = accel.get("rms")
            std_dev_vert = accel.get("stdDev")
            road_quality = accel.get("roadQuality")

            if avg_rms is None and rms_vert is None:
                skipped += 1
                continue

            # Use avgRms/avgStdDev if available, fall back to instantaneous
            fix = {
                "avg_rms": avg_rms if avg_rms is not None else (rms_vert or 0.0),
                "avg_std_dev": avg_std_dev if avg_std_dev is not None else (std_dev_vert or 0.0),
                "avg_peak_ratio": accel.get("peakRatio", 0.0),  # Add for norawdata mode
                "rms_vert": rms_vert or (avg_rms or 0.0),
                "std_dev_vert": std_dev_vert or (avg_std_dev or 0.0),
                "max_magnitude": accel.get("magMax", 0.0),
                "peak_ratio": accel.get("peakRatio", 0.0),
                "fwd_max": accel.get("fwdMax", 0.0),
                "fwd_rms": accel.get("fwdRms", 0.0),
                "fwd_mean": 0.0,
                "lat_max": accel.get("latMax", 0.0),
                "lat_rms": accel.get("latRms", 0.0),
                "lean_angle_deg": accel.get("leanAngleDeg", 0.0),
                "road_quality": road_quality,
                "feature": accel.get("featureDetected"),
                "lat": lat,
                "lon": lon,
                "speed": speed,
                "course": course,
                "ts_ms": ts,
                "point_index": i + 1,
            }
            fixes.append(fix)
        else:
            raw_data = accel.get("raw", [])

            if not raw_data:
                skipped += 1
                continue

            # Ensure each sample is [x, y, z]
            if isinstance(raw_data[0], dict):
                raw_data = [[s.get("x", 0), s.get("y", 0), s.get("z", 0)] for s in raw_data]

            metrics = compute_fix_metrics(
                raw_data,
                speed,
                cal,
                dt,
                g_unit,
                fwd_unit,
                lat_unit,
                metrics_history,
                store_peak_samples=store_peak_samples,
                actual_sampling_rate=actual_sampling_rate,
            )
            if metrics is None:
                skipped += 1
                continue

            metrics["lat"] = lat
            metrics["lon"] = lon
            metrics["speed"] = speed
            metrics["course"] = course
            metrics["ts_ms"] = ts
            metrics["point_index"] = i + 1
            fixes.append(metrics)

    # Compute deltas for driver event classification
    for i, fx in enumerate(fixes):
        if i == 0:
            fx["delta_speed"] = 0.0
            fx["delta_course"] = 0.0
        else:
            prev = fixes[i - 1]
            fx["delta_speed"] = fx["speed"] - prev["speed"]
            fx["delta_course"] = bearing_diff(prev["course"], fx["course"])

    print(f"Processed: {len(fixes)} fixes, skipped: {skipped}")

    # --- Current metrics summary ---
    moving = [fx for fx in fixes if fx["speed"] >= min_speed]
    if not moving:
        print("ERROR: No moving fixes found above min speed threshold.")
        sys.exit(1)

    # Current road quality distribution (original method)
    cur_quality = {"smooth": 0, "average": 0, "rough": 0}
    # Adaptive road quality distribution (with peak ratio modifier)
    adaptive_quality = {"smooth": 0, "average": 0, "rough": 0}
    
    # Calculate peak ratio statistics for adaptive thresholds
    peak_ratios = [fx.get("avg_peak_ratio", 0.0) for fx in moving]
    peak_ratio_stats = None
    if peak_ratios:
        import statistics
        pr_mean = statistics.mean(peak_ratios)
        pr_median = statistics.median(peak_ratios)
        pr_stdev = statistics.stdev(peak_ratios) if len(peak_ratios) > 1 else 0.0
        pr_sorted = sorted(peak_ratios)
        pr_p25 = pr_sorted[int(len(pr_sorted) * 0.25)] if len(pr_sorted) > 0 else 0.0
        pr_p75 = pr_sorted[int(len(pr_sorted) * 0.75)] if len(pr_sorted) > 0 else 0.0
        
        peak_ratio_stats = {
            'mean': pr_mean,
            'median': pr_median,
            'std_dev': pr_stdev,
            'p25': pr_p25,
            'p75': pr_p75
        }
    
    for fx in moving:
        rq = fx.get("road_quality")
        if rq in cur_quality:
            cur_quality[rq] += 1
        
        # Calculate adaptive classification
        adaptive_rq = classify_road_quality_adaptive(
            fx["avg_rms"], fx["avg_std_dev"], fx.get("avg_peak_ratio", 0.0),
            cal["rmsSmoothMax"], cal["stdDevSmoothMax"], 
            cal["rmsRoughMin"], cal["stdDevRoughMin"],
            peak_ratio_stats
        )
        adaptive_quality[adaptive_rq] += 1

    print("\n--- Road Quality Comparison ---")
    print("  Original method (RMS + StdDev):")
    for quality, count in cur_quality.items():
        pct = 100.0 * count / len(moving)
        print(f"    {quality:8}: {count:4} ({pct:5.1f}%)")
    
    print("  Adaptive method (RMS + StdDev + Adaptive Peak Ratio):")
    for quality, count in adaptive_quality.items():
        pct = 100.0 * count / len(moving)
        print(f"    {quality:8}: {count:4} ({pct:5.1f}%)")
    
    # Show differences
    print("  Differences:")
    for quality in ["smooth", "average", "rough"]:
        diff = adaptive_quality[quality] - cur_quality[quality]
        if diff != 0:
            pct_diff = 100.0 * diff / len(moving)
            print(f"    {quality:8}: {diff:+4d} ({pct_diff:+5.1f}%)")
    
    # Analyze peak ratio distribution
    if peak_ratio_stats:
        print(f"\n--- Peak Ratio Statistics ---")
        print(f"  Mean: {peak_ratio_stats['mean']:.3f}")
        print(f"  Median: {peak_ratio_stats['median']:.3f}")
        print(f"  StdDev: {peak_ratio_stats['std_dev']:.3f}")
        print(f"  Range: {min(peak_ratios):.3f} - {max(peak_ratios):.3f}")
        
        # Show adaptive thresholds being used
        pr_mean = peak_ratio_stats['mean']
        if pr_mean < 0.05:
            smooth_min, rough_max = 0.01, 0.02
            reason = "Very low peak ratio data (smooth roads)"
        elif pr_mean < 0.15:
            smooth_min = max(0.05, pr_mean - 0.05)
            rough_max = min(0.4, pr_mean + 0.15)
            reason = "Typical mixed road data"
        else:
            smooth_min = max(0.1, pr_mean - 0.1)
            rough_max = min(0.5, pr_mean + 0.1)
            reason = "High peak ratio data (rough roads)"
        
        print(f"\n--- Adaptive Thresholds ---")
        print(f"  Data type: {reason}")
        print(f"  Smooth min peak ratio: {smooth_min:.3f}")
        print(f"  Rough max peak ratio: {rough_max:.3f}")
        
        # Show peak ratio by road quality
        print(f"\n--- Peak Ratio by Road Quality (Original) ---")
        for quality in ["smooth", "average", "rough"]:
            quality_prs = [fx.get("avg_peak_ratio", 0.0) for fx in moving 
                          if fx.get("road_quality") == quality]
            if quality_prs:
                pr_mean = statistics.mean(quality_prs)
                pr_median = statistics.median(quality_prs)
                print(f"  {quality:8}: mean={pr_mean:.3f}, median={pr_median:.3f}, count={len(quality_prs)}")
        
        # Show reclassifications
        print(f"\n--- Reclassification Analysis ---")
        reclassified = {"smooth_to_avg": 0, "rough_to_avg": 0, "no_change": 0}
        for fx in moving:
            orig = fx.get("road_quality")
            adaptive_rq = classify_road_quality_adaptive(
                fx["avg_rms"], fx["avg_std_dev"], fx.get("avg_peak_ratio", 0.0),
                cal["rmsSmoothMax"], cal["stdDevSmoothMax"], 
                cal["rmsRoughMin"], cal["stdDevRoughMin"],
                peak_ratio_stats
            )
            if orig != adaptive_rq:
                if orig == "smooth" and adaptive_rq == "average":
                    reclassified["smooth_to_avg"] += 1
                elif orig == "rough" and adaptive_rq == "average":
                    reclassified["rough_to_avg"] += 1
            else:
                reclassified["no_change"] += 1
        
        print(f"  Smooth → Average: {reclassified['smooth_to_avg']} ({100.0*reclassified['smooth_to_avg']/len(moving):.1f}%)")
        print(f"  Rough → Average:  {reclassified['rough_to_avg']} ({100.0*reclassified['rough_to_avg']/len(moving):.1f}%)")
        print(f"  Unchanged:       {reclassified['no_change']} ({100.0*reclassified['no_change']/len(moving):.1f}%)")

    cur_smooth_pct = 100.0 * cur_quality["smooth"] / len(moving) if moving else 0

    # Current event counts (only meaningful with raw data)
    if not args.norawdata:
        cur_hard_brake, cur_hard_accel = count_events_with_thresholds(
            fixes, dt["hardBrakeFwdMax"], dt["hardAccelFwdMax"], min_speed
        )
    else:
        cur_hard_brake = cur_hard_accel = 0

    print(f"\n--- Current Results ({len(moving)} moving fixes) ---")
    for q in ["smooth", "average", "rough"]:
        pct = 100.0 * cur_quality[q] / len(moving)
        print(f"  {q:10s}: {cur_quality[q]:5d} ({pct:.1f}%)")
    if not args.norawdata:
        print(f"  Hard brakes:  {cur_hard_brake}")
        print(f"  Hard accels:  {cur_hard_accel}")
        if args.swerves is not None:
            cur_swerves = count_swerve_events(moving, dt["swerveLatMax"], min_speed)
            print(f"  Swerves:      {cur_swerves}")
        if args.aggcorner is not None:
            cur_aggcorner = count_aggressive_corner_events(
                moving, dt["aggressiveCornerLatMax"], dt["aggressiveCornerDCourse"], min_speed
            )
            print(f"  Agg corners:  {cur_aggcorner}")

    # Feature counts
    feature_counts = {}
    for fx in moving:
        feat = fx.get("feature")
        if feat:
            feature_counts[feat] = feature_counts.get(feat, 0) + 1
    if feature_counts:
        print(f"\n  Features detected:")
        for feat, cnt in sorted(feature_counts.items()):
            print(f"    {feat:15s}: {cnt}")

    # Metric ranges (percentiles)
    range_keys = ["avg_rms", "avg_std_dev", "rms_vert", "std_dev_vert"]
    if not args.norawdata:
        range_keys += ["fwd_max", "lat_max"]
    print(f"\n--- Metric Ranges (moving fixes, percentiles) ---")
    for key in range_keys:
        vals = sorted([fx[key] for fx in moving])
        if vals:
            p25 = vals[int(len(vals) * 0.25)]
            p50 = vals[int(len(vals) * 0.50)]
            p75 = vals[int(len(vals) * 0.75)]
            p90 = vals[int(len(vals) * 0.90)]
            p99 = vals[min(int(len(vals) * 0.99), len(vals) - 1)]
            print(f"  {key:15s}: p25={p25:7.3f}  p50={p50:7.3f}  p75={p75:7.3f}  p90={p90:7.3f}  p99={p99:7.3f}")

    # --- Recommendations ---
    print(f"\n{'=' * 70}")
    print("RECOMMENDATIONS")
    print("=" * 70)

    # 1. Smooth road thresholds
    rec_rms_smooth, rec_std_smooth = recommend_smooth_thresholds(fixes, args.smooth, cal, min_speed)

    # Verify
    rec_smooth_count = sum(
        1 for fx in moving
        if fx["avg_rms"] < rec_rms_smooth and fx["avg_std_dev"] < rec_std_smooth
    )
    rec_smooth_pct = 100.0 * rec_smooth_count / len(moving)

    print(f"\n  Road Quality — Smooth (target: {args.smooth}%):")
    print(f"    rmsSmoothMax:    {cal['rmsSmoothMax']:.3f} -> {rec_rms_smooth:.3f}")
    print(f"    stdDevSmoothMax: {cal['stdDevSmoothMax']:.3f} -> {rec_std_smooth:.3f}")
    print(f"    Result: {rec_smooth_pct:.1f}% smooth (was {cur_smooth_pct:.1f}%)")

    # Build recommended calibration
    rec_cal = dict(cal)
    rec_cal["rmsSmoothMax"] = rec_rms_smooth
    rec_cal["stdDevSmoothMax"] = rec_std_smooth

    # Rough thresholds (if --rough specified)
    if args.rough is not None:
        rec_rms_rough, rec_std_rough = recommend_rough_thresholds(fixes, args.rough, cal, min_speed)

        rec_rough_count = sum(
            1 for fx in moving
            if fx["avg_rms"] >= rec_rms_rough and fx["avg_std_dev"] >= rec_std_rough
        )
        rec_rough_pct = 100.0 * rec_rough_count / len(moving)

        cur_rough_pct = 100.0 * cur_quality["rough"] / len(moving) if moving else 0

        print(f"\n  Road Quality — Rough (target: {args.rough}%):")
        print(f"    rmsRoughMin:    {cal['rmsRoughMin']:.3f} -> {rec_rms_rough:.3f}")
        print(f"    stdDevRoughMin: {cal['stdDevRoughMin']:.3f} -> {rec_std_rough:.3f}")
        print(f"    Result: {rec_rough_pct:.1f}% rough (was {cur_rough_pct:.1f}%)")

        rec_cal["rmsRoughMin"] = rec_rms_rough
        rec_cal["stdDevRoughMin"] = rec_std_rough

    rec_dt_out = dict(dt)

    if not args.norawdata:
        # 2. Hard brake threshold
        brake_fwd_values = [fx["fwd_max"] for fx in moving if fx["delta_speed"] < 0]
        rec_hard_brake_thresh = recommend_event_threshold(brake_fwd_values, args.hardbrake)
        rec_brake_count = sum(1 for v in brake_fwd_values if v > rec_hard_brake_thresh)

        print(f"\n  Hard Brake (target: {args.hardbrake} events):")
        print(f"    hardBrakeFwdMax: {dt['hardBrakeFwdMax']:.3f} -> {rec_hard_brake_thresh:.3f}")
        print(f"    Result: {rec_brake_count} events (was {cur_hard_brake})")

        # 3. Hard accel threshold
        accel_fwd_values = [fx["fwd_max"] for fx in moving if fx["delta_speed"] > 0]
        rec_hard_accel_thresh = recommend_event_threshold(accel_fwd_values, args.hardaccel)
        rec_accel_count = sum(1 for v in accel_fwd_values if v > rec_hard_accel_thresh)

        print(f"\n  Hard Accel (target: {args.hardaccel} events):")
        print(f"    hardAccelFwdMax: {dt['hardAccelFwdMax']:.3f} -> {rec_hard_accel_thresh:.3f}")
        print(f"    Result: {rec_accel_count} events (was {cur_hard_accel})")

        rec_dt_out["hardBrakeFwdMax"] = rec_hard_brake_thresh
        rec_dt_out["hardAccelFwdMax"] = rec_hard_accel_thresh

        if args.swerves is not None:
            swerve_values = [fx["lat_max"] for fx in moving]
            rec_swerve_lat = recommend_event_threshold(swerve_values, args.swerves)
            rec_swerve_count = count_swerve_events(moving, rec_swerve_lat, min_speed)

            print(f"\n  Swerves (target: {args.swerves} events):")
            print(f"    swerveLatMax: {dt['swerveLatMax']:.3f} -> {rec_swerve_lat:.3f}")
            print(f"    Result: {rec_swerve_count} events")

            rec_dt_out["swerveLatMax"] = rec_swerve_lat

        if args.aggcorner is not None:
            dcourse_values = [
                abs(fx["delta_course"]) for fx in moving if fx["lat_max"] > dt["aggressiveCornerLatMax"]
            ]
            rec_dcourse = recommend_event_threshold(dcourse_values, args.aggcorner)
            lat_values = [
                fx["lat_max"] for fx in moving if abs(fx["delta_course"]) > rec_dcourse
            ]
            rec_agg_lat = recommend_event_threshold(lat_values, args.aggcorner)
            rec_agg_count = count_aggressive_corner_events(moving, rec_agg_lat, rec_dcourse, min_speed)

            print(f"\n  Aggressive Corner (target: {args.aggcorner} events):")
            print(f"    aggressiveCornerDCourse: {dt['aggressiveCornerDCourse']:.3f} -> {rec_dcourse:.3f}")
            print(f"    aggressiveCornerLatMax:  {dt['aggressiveCornerLatMax']:.3f} -> {rec_agg_lat:.3f}")
            print(f"    Result: {rec_agg_count} events")

            rec_dt_out["aggressiveCornerDCourse"] = rec_dcourse
            rec_dt_out["aggressiveCornerLatMax"] = rec_agg_lat

        if args.bumps is not None or args.potholes is not None:
            bump_target = args.bumps or 0
            pothole_target = args.potholes or 0
            target_total = bump_target + pothole_target

            print(f"\n  Road Features — Bumps/Potholes:")

            # Candidate fixes based on vertical roughness
            candidate_fixes = [
                fx for fx in moving
                if fx["rms_vert"] > cal["rmsRoughMin"]
            ]

            if not candidate_fixes:
                print("    WARNING: No candidate bump/pothole fixes found (rmsVert too low).")
            else:
                mag_values = [fx["max_magnitude"] for fx in candidate_fixes]
                if target_total > 0:
                    rec_mag_severe = recommend_event_threshold(mag_values, target_total)
                else:
                    rec_mag_severe = cal["magMaxSevereMin"]

                bumps = sum(
                    1 for fx in candidate_fixes
                    if fx["max_magnitude"] >= rec_mag_severe and fx.get("mean_vert", 0.0) >= 0
                )
                potholes = sum(
                    1 for fx in candidate_fixes
                    if fx["max_magnitude"] >= rec_mag_severe and fx.get("mean_vert", 0.0) < 0
                )

                print(f"    magMaxSevereMin: {cal['magMaxSevereMin']:.3f} -> {rec_mag_severe:.3f}")
                if args.bumps is not None:
                    print(f"    Bumps result:    {bumps} events (target {args.bumps})")
                if args.potholes is not None:
                    print(f"    Potholes result: {potholes} events (target {args.potholes})")

                rec_cal["magMaxSevereMin"] = rec_mag_severe

    # Print full recommended JSON
    print(f"\n{'=' * 70}")
    print("RECOMMENDED SETTINGS (copy to vehicle profile)")
    print("=" * 70)
    recommended = {"calibration": rec_cal}
    if not args.norawdata:
        recommended["driverThresholds"] = rec_dt_out
    print(json.dumps(recommended, indent=2))

    # Write JSON file
    output_base = os.path.splitext(args.track)[0]
    json_path = f"{output_base}_recommendations.json"
    with open(json_path, "w") as f:
        json.dump(recommended, f, indent=2)
    print(f"\nJSON: {json_path}")

    # Generate road quality KML with driver events overlay
    gps_root = track_data.get("gpslogger2path", track_data)
    start_ts_ms = gps_root.get("meta", {}).get("ts", 0) or 0
    kml_path = f"{output_base}_road_quality.kml"
    print("\nGenerating road quality KML with driver events...")
    # Pass rec_dt only if we have raw data (not norawdata mode)
    rec_dt_for_kml = rec_dt_out if not args.norawdata else None
    generate_road_quality_kml(fixes, rec_cal, kml_path, min_speed, start_ts_ms, rec_dt_for_kml, args.norawdata)
    print(f"KML:  {kml_path}")

    # Generate plots
    print("\nGenerating plots...")
    plot_path = f"{output_base}_recommendations.png"
    generate_plots(fixes, cal, dt, rec_cal, rec_dt_out, plot_path, min_speed, args.norawdata)

    print(f"\n{'=' * 70}")
    print("Done.")


if __name__ == "__main__":
    main()
