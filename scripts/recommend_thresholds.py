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
# Feature detection (mirrors MetricsEngine.detectFeatureFromMetrics)
# ---------------------------------------------------------------------------
def detect_feature_from_metrics(rms, mag_max, peak_ratio, cal):
    if rms <= cal["rmsRoughMin"]:
        return None
    if mag_max > cal["magMaxSevereMin"]:
        return "pothole" if peak_ratio < cal["peakRatioRoughMin"] else "bump"
    return None


# ---------------------------------------------------------------------------
# Speed-hump pattern detection (mirrors MetricsEngine.detectSpeedHumpPattern)
# ---------------------------------------------------------------------------
def detect_speed_hump_pattern(raw_vert_accel, fwd_max, speed, sampling_rate=100.0):
    LOW_SPEED_THRESHOLD = 20.0
    LOW_SPEED_AMPLITUDE = 10.0
    LOW_SPEED_MIN_PEAKS = 8
    HIGH_SPEED_AMPLITUDE = 12.0
    HIGH_SPEED_MIN_PEAKS = 12
    MAX_DURATION = 8.0
    DECAY_RATIO_THRESHOLD = 0.7
    MIN_ZERO_CROSSINGS = 20

    if speed < LOW_SPEED_THRESHOLD:
        min_amplitude, min_peaks = LOW_SPEED_AMPLITUDE, LOW_SPEED_MIN_PEAKS
    else:
        min_amplitude, min_peaks = HIGH_SPEED_AMPLITUDE, HIGH_SPEED_MIN_PEAKS

    peaks = []
    for i in range(1, len(raw_vert_accel) - 1):
        cur = raw_vert_accel[i]
        if cur > raw_vert_accel[i - 1] and cur > raw_vert_accel[i + 1] and abs(cur) > 5.0:
            peaks.append(cur)
    if len(peaks) < min_peaks:
        return None

    zero_crossings = sum(
        1 for i in range(1, len(raw_vert_accel))
        if raw_vert_accel[i - 1] * raw_vert_accel[i] < 0
    )
    if zero_crossings < MIN_ZERO_CROSSINGS:
        return None

    duration = len(raw_vert_accel) / sampling_rate
    if duration > MAX_DURATION:
        return None

    if not peaks:
        return None
    p2p = max(peaks) - min(peaks)
    if p2p < min_amplitude:
        return None

    if len(peaks) >= 4:
        mid = len(peaks) // 2
        first_avg = sum(abs(p) for p in peaks[:mid]) / mid
        second_avg = sum(abs(p) for p in peaks[mid:]) / (len(peaks) - mid)
        decay = second_avg / first_avg if first_avg > 0 else 1.0
        if decay > DECAY_RATIO_THRESHOLD:
            return None

    return "speed_bump"


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
def extract_base_gravity_vector(track_data):
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    cal = meta.get("recordingSettings", {}).get("calibration", {})
    bgv = cal.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    return None


def extract_calibration(track_data):
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    rec = meta.get("recordingSettings", {})
    cal = rec.get("calibration", {})
    return {
        "rmsSmoothMax": cal.get("rmsSmoothMax", 1.0),
        "peakThresholdZ": cal.get("peakThresholdZ", 1.5),
        "movingAverageWindow": cal.get("movingAverageWindow", 5),
        "stdDevSmoothMax": cal.get("stdDevSmoothMax", 2.5),
        "rmsRoughMin": cal.get("rmsRoughMin", 4.5),
        "peakRatioRoughMin": cal.get("peakRatioRoughMin", 0.6),
        "stdDevRoughMin": cal.get("stdDevRoughMin", 3.0),
        "magMaxSevereMin": cal.get("magMaxSevereMin", 20.0),
        "qualityWindowSize": cal.get("qualityWindowSize", 3),
    }


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
    raw_data, speed_kmph, cal, dt, g_unit, fwd_unit, lat_unit, metrics_history
):
    """Compute road quality + driver metrics for a single GPS fix."""
    if not raw_data:
        return None

    SAMPLING_RATE = 100.0
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
    metrics_history.append(FixMetrics(rms_vert, max_magnitude, mean_mag_vert, std_dev_vert, peak_ratio))
    quality_window = max(1, int(cal.get("qualityWindowSize", 3)))
    while len(metrics_history) > quality_window:
        metrics_history.popleft()

    avg_rms = sum(m.rms_vert for m in metrics_history) / len(metrics_history)
    avg_std_dev = sum(m.std_dev_vert for m in metrics_history) / len(metrics_history)

    # Road quality + feature detection
    min_speed = dt.get("minSpeedKmph", 6.0)
    if speed_kmph < min_speed:
        road_quality = None
        feature = None
    else:
        # Classify road quality
        if avg_rms < cal["rmsSmoothMax"] and avg_std_dev < cal["stdDevSmoothMax"]:
            road_quality = "smooth"
        elif avg_rms >= cal["rmsRoughMin"] and avg_std_dev >= cal["stdDevRoughMin"]:
            road_quality = "rough"
        else:
            road_quality = "average"

        # Feature detection
        raw_vert_accel = []
        for v in smoothed_accel:
            a_vert = (v[0] * g_unit[0] + v[1] * g_unit[1] + v[2] * g_unit[2]) if g_unit else v[2]
            raw_vert_accel.append(a_vert)
        speed_hump = detect_speed_hump_pattern(raw_vert_accel, fwd_max, speed_kmph, SAMPLING_RATE)
        feature = speed_hump or detect_feature_from_metrics(rms_vert, max_magnitude, peak_ratio, cal)

    return {
        "rms_vert": rms_vert,
        "max_magnitude": max_magnitude,
        "std_dev_vert": std_dev_vert,
        "peak_ratio": peak_ratio,
        "avg_rms": avg_rms,
        "avg_std_dev": avg_std_dev,
        "fwd_rms": fwd_rms,
        "fwd_max": fwd_max,
        "fwd_mean": fwd_mean,
        "lat_rms": lat_rms,
        "lat_max": lat_max,
        "lean_angle_deg": lean_angle_deg,
        "road_quality": road_quality,
        "feature": feature,
    }


# ---------------------------------------------------------------------------
# Classify road quality with arbitrary thresholds (for recommendation search)
# ---------------------------------------------------------------------------
def classify_road_quality(avg_rms, avg_std_dev, rms_smooth_max, std_dev_smooth_max,
                          rms_rough_min, std_dev_rough_min):
    if avg_rms < rms_smooth_max and avg_std_dev < std_dev_smooth_max:
        return "smooth"
    elif avg_rms >= rms_rough_min and avg_std_dev >= std_dev_rough_min:
        return "rough"
    return "average"


# ---------------------------------------------------------------------------
# Count driver events with arbitrary thresholds
# ---------------------------------------------------------------------------
def count_events_with_thresholds(fixes, hard_brake_thresh, hard_accel_thresh, min_speed):
    hard_brake = 0
    hard_accel = 0
    for fx in fixes:
        if fx["speed"] < min_speed:
            continue
        if fx["fwd_max"] > hard_brake_thresh and fx["delta_speed"] < 0:
            hard_brake += 1
        if fx["fwd_max"] > hard_accel_thresh and fx["delta_speed"] > 0:
            hard_accel += 1
    return hard_brake, hard_accel


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
def generate_road_quality_kml(fixes, rec_cal, output_path, min_speed, start_ts_ms=0):
    """Write a KML file with road quality colour-coded placemarks and line segments.

    Uses the recommended calibration thresholds to reclassify each fix.
    Colours: green=smooth, orange/yellow=average, red=rough.
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

        # --- Legend folder ---
        f.write('<Folder>\n')
        f.write('<name>Legend</name>\n')
        f.write('<description>Green = Smooth, Orange = Average, Red = Rough</description>\n')
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

    if norawdata:
        fig, axes = plt.subplots(2, 2, figsize=(14, 10))
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
        ax = axes[1, 1]
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

    if not norawdata:
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
        ax.set_xlabel("fwdMax (m/s²) — Braking Fixes")
        ax.set_ylabel("Cumulative %")
        ax.set_title("Hard Brake Threshold CDF")
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
        ax.set_xlabel("fwdMax (m/s²) — Acceleration Fixes")
        ax.set_ylabel("Cumulative %")
        ax.set_title("Hard Accel Threshold CDF")
        ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

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
    parser.add_argument("--norawdata", action="store_true",
                        help="Use pre-computed accel metrics from JSON (no raw data needed). "
                             "Only road quality calibration will be recommended.")
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

    metrics_history = deque()
    fixes = []
    skipped = 0

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
                raw_data, speed, cal, dt, g_unit, fwd_unit, lat_unit, metrics_history
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

    # Current road quality distribution
    cur_quality = {"smooth": 0, "average": 0, "rough": 0}
    for fx in moving:
        rq = fx.get("road_quality")
        if rq in cur_quality:
            cur_quality[rq] += 1

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

    # Generate road quality KML
    gps_root = track_data.get("gpslogger2path", track_data)
    start_ts_ms = gps_root.get("meta", {}).get("ts", 0) or 0
    kml_path = f"{output_base}_road_quality.kml"
    print("\nGenerating road quality KML...")
    generate_road_quality_kml(fixes, rec_cal, kml_path, min_speed, start_ts_ms)
    print(f"KML:  {kml_path}")

    # Generate plots
    print("\nGenerating plots...")
    plot_path = f"{output_base}_recommendations.png"
    generate_plots(fixes, cal, dt, rec_cal, rec_dt_out, plot_path, min_speed, args.norawdata)

    print(f"\n{'=' * 70}")
    print("Done.")


if __name__ == "__main__":
    main()
