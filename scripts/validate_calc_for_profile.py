#!/usr/bin/env python3
"""
Validate accelerometer computation against a recorded track using a given calibration profile.

Replicates the exact computeAccelMetrics(), detectFeatureFromMetrics(), and
detectSpeedHumpPattern() logic from TrackingService.kt in Python, then writes
a colour-coded KML file that can be loaded into Google Earth or GPSLoggerII.

Usage:
    python validate_calc_for_profile.py <calibration_profile.json> <recording_track.json>

Calibration profile JSON format (same keys as CalibrationSettings):
{
    "rmsSmoothMax": 3.3,
    "peakThresholdZ": 2.0,
    "movingAverageWindow": 7,
    "stdDevSmoothMax": 2.0,
    "rmsRoughMin": 3.3,
    "peakRatioRoughMin": 0.60,
    "stdDevRoughMin": 2.0,
    "magMaxSevereMin": 20.0,
    "qualityWindowSize": 3
}

Recording track JSON is the standard SJGpsUtil JSON export with gpslogger2path
structure containing meta.recordingSettings.calibration.baseGravityVector and
data[] array of sample points.
"""

import json
import math
import os
import sys
import xml.sax.saxutils as saxutils
from collections import deque
from typing import Dict, List, Optional, Tuple


# ---------------------------------------------------------------------------
# Calibration profile (mirrors CalibrationSettings data class)
# ---------------------------------------------------------------------------
DEFAULT_CALIBRATION = {
    "rmsSmoothMax": 1.0,
    "peakThresholdZ": 1.5,
    "movingAverageWindow": 5,
    "stdDevSmoothMax": 2.5,
    "rmsRoughMin": 4.5,
    "peakRatioRoughMin": 0.6,
    "stdDevRoughMin": 3.0,
    "magMaxSevereMin": 20.0,
    "qualityWindowSize": 3,
}


# ---------------------------------------------------------------------------
# Vehicle-frame basis computation (mirrors computeVehicleBasis)
# ---------------------------------------------------------------------------
def compute_vehicle_basis(gravity: List[float]):
    """Return (g_unit, fwd_unit, lat_unit) or (None, None, None)."""
    norm = math.sqrt(sum(c * c for c in gravity))
    if norm < 1e-3:
        return None, None, None
    g = [c / norm for c in gravity]

    # Project device-Y [0,1,0] onto horizontal plane
    y_dot_g = g[1]
    fwd = [-y_dot_g * g[0], 1.0 - y_dot_g * g[1], -y_dot_g * g[2]]
    fwd_norm = math.sqrt(sum(c * c for c in fwd))

    # Degenerate: device-Y nearly parallel to gravity -> use device-X
    if fwd_norm < 1e-3:
        x_dot_g = g[0]
        fwd = [1.0 - x_dot_g * g[0], -x_dot_g * g[1], -x_dot_g * g[2]]
        fwd_norm = math.sqrt(sum(c * c for c in fwd))

    if fwd_norm < 1e-3:
        return None, None, None

    fwd = [c / fwd_norm for c in fwd]

    # Lateral = g x fwd
    lat = [
        g[1] * fwd[2] - g[2] * fwd[1],
        g[2] * fwd[0] - g[0] * fwd[2],
        g[0] * fwd[1] - g[1] * fwd[0],
    ]
    return g, fwd, lat


# ---------------------------------------------------------------------------
# Moving average filter (mirrors applyMovingAverage)
# ---------------------------------------------------------------------------
def apply_moving_average(data: List[List[float]], window_size: int) -> List[List[float]]:
    if len(data) < window_size:
        return data
    result = []
    half = window_size // 2
    for i in range(len(data)):
        start = max(0, i - half)
        end = min(len(data), i + half + 1)
        window = data[start:end]
        avg_x = sum(s[0] for s in window) / len(window)
        avg_y = sum(s[1] for s in window) / len(window)
        avg_z = sum(s[2] for s in window) / len(window)
        result.append([avg_x, avg_y, avg_z])
    return result


# ---------------------------------------------------------------------------
# Feature detection (mirrors detectFeatureFromMetrics)
# ---------------------------------------------------------------------------
def detect_feature_from_metrics(
    rms: float, mag_max: float, peak_ratio: float, cal: dict
) -> Optional[str]:
    if rms <= cal["rmsRoughMin"]:
        return None
    if mag_max > cal["magMaxSevereMin"]:
        return "pothole" if peak_ratio < cal["peakRatioRoughMin"] else "bump"
    return None


# ---------------------------------------------------------------------------
# Speed-hump pattern detection (mirrors detectSpeedHumpPattern)
# ---------------------------------------------------------------------------
def detect_speed_hump_pattern(
    raw_vert_accel: List[float],
    fwd_max: float,
    speed: float,
    sampling_rate: float = 100.0,
) -> Optional[str]:
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

    # Gate 1: peaks
    peaks = []
    for i in range(1, len(raw_vert_accel) - 1):
        cur = raw_vert_accel[i]
        if cur > raw_vert_accel[i - 1] and cur > raw_vert_accel[i + 1] and abs(cur) > 5.0:
            peaks.append(cur)
    if len(peaks) < min_peaks:
        return None

    # Gate 2: zero crossings
    zero_crossings = sum(
        1
        for i in range(1, len(raw_vert_accel))
        if raw_vert_accel[i - 1] * raw_vert_accel[i] < 0
    )
    if zero_crossings < MIN_ZERO_CROSSINGS:
        return None

    # Gate 3: duration
    duration = len(raw_vert_accel) / sampling_rate
    if duration > MAX_DURATION:
        return None

    # Gate 4: peak-to-peak amplitude
    if not peaks:
        return None
    p2p = max(peaks) - min(peaks)
    if p2p < min_amplitude:
        return None

    # Gate 5: amplitude decay
    if len(peaks) >= 4:
        mid = len(peaks) // 2
        first_avg = sum(abs(p) for p in peaks[:mid]) / mid
        second_avg = sum(abs(p) for p in peaks[mid:]) / (len(peaks) - mid)
        decay = second_avg / first_avg if first_avg > 0 else 1.0
        if decay > DECAY_RATIO_THRESHOLD:
            return None

    return "speed_bump"


# ---------------------------------------------------------------------------
# Core: replicate computeAccelMetrics per sample
# ---------------------------------------------------------------------------
class FixMetrics:
    __slots__ = ("rms_vert", "max_magnitude", "mean_magnitude_vert", "std_dev_vert", "peak_ratio")

    def __init__(self, rms_vert, max_magnitude, mean_magnitude_vert, std_dev_vert, peak_ratio):
        self.rms_vert = rms_vert
        self.max_magnitude = max_magnitude
        self.mean_magnitude_vert = mean_magnitude_vert
        self.std_dev_vert = std_dev_vert
        self.peak_ratio = peak_ratio


def compute_accel_metrics(
    raw_data: List[List[float]],
    speed_kmph: float,
    cal: dict,
    g_unit_basis,
    fwd_unit,
    lat_unit,
    metrics_history: deque,
) -> Optional[dict]:
    if not raw_data:
        return None

    SAMPLING_RATE = 100.0
    MIN_SPEED_FOR_DETECTION = 6.0

    # Step 1: detrend
    n = len(raw_data)
    bias_x = sum(s[0] for s in raw_data) / n
    bias_y = sum(s[1] for s in raw_data) / n
    bias_z = sum(s[2] for s in raw_data) / n
    detrended = [[s[0] - bias_x, s[1] - bias_y, s[2] - bias_z] for s in raw_data]

    # Step 2: moving average
    ma_window = max(1, cal.get("movingAverageWindow", 5))
    smoothed = apply_moving_average(detrended, ma_window)

    use_g = g_unit_basis
    use_fwd = fwd_unit
    use_lat = lat_unit

    # Accumulators
    sum_x = sum_y = sum_z = sum_vert = 0.0
    vert_max_mag = 0.0
    vert_sum_sq = 0.0
    above_z_count = 0
    vert_magnitudes = []
    fwd_sum_sq = fwd_max_mag = fwd_sum_val = 0.0
    lat_sum_sq = lat_max_mag = lat_sum_val = 0.0

    for v in smoothed:
        sum_x += v[0]
        sum_y += v[1]
        sum_z += v[2]

        if use_g:
            a_vert = v[0] * use_g[0] + v[1] * use_g[1] + v[2] * use_g[2]
        else:
            a_vert = v[2]
        sum_vert += a_vert
        abs_vert = abs(a_vert)
        vert_magnitudes.append(abs_vert)
        if abs_vert > vert_max_mag:
            vert_max_mag = abs_vert
        vert_sum_sq += a_vert * a_vert
        if abs_vert >= cal.get("peakThresholdZ", 1.5):
            above_z_count += 1

        if use_fwd:
            a_fwd = v[0] * use_fwd[0] + v[1] * use_fwd[1] + v[2] * use_fwd[2]
            fwd_sum_val += a_fwd
            fwd_sum_sq += a_fwd * a_fwd
            if abs(a_fwd) > fwd_max_mag:
                fwd_max_mag = abs(a_fwd)

        if use_lat:
            a_lat = v[0] * use_lat[0] + v[1] * use_lat[1] + v[2] * use_lat[2]
            lat_sum_val += a_lat
            lat_sum_sq += a_lat * a_lat
            if abs(a_lat) > lat_max_mag:
                lat_max_mag = abs(a_lat)

    count = len(smoothed)
    mean_x = sum_x / count
    mean_y = sum_y / count
    mean_z = sum_z / count
    mean_vert = sum_vert / count
    rms_vert = math.sqrt(vert_sum_sq / count)
    max_magnitude = vert_max_mag
    peak_ratio = above_z_count / count
    mean_magnitude_vert = sum(vert_magnitudes) / len(vert_magnitudes)
    variance = sum((m - mean_magnitude_vert) ** 2 for m in vert_magnitudes) / len(vert_magnitudes)
    std_dev_vert = math.sqrt(variance)

    fwd_rms = math.sqrt(fwd_sum_sq / count) if use_fwd else 0.0
    fwd_mean = fwd_sum_val / count if use_fwd else 0.0
    fwd_max = fwd_max_mag
    lat_rms = math.sqrt(lat_sum_sq / count) if use_lat else 0.0
    lat_mean = lat_sum_val / count if use_lat else 0.0
    lat_max = lat_max_mag

    # Push to history ring buffer
    metrics_history.append(FixMetrics(rms_vert, max_magnitude, mean_magnitude_vert, std_dev_vert, peak_ratio))
    quality_window = max(1, cal.get("qualityWindowSize", 3))
    while len(metrics_history) > quality_window:
        metrics_history.popleft()

    avg_rms = sum(m.rms_vert for m in metrics_history) / len(metrics_history)
    avg_max_mag = sum(m.max_magnitude for m in metrics_history) / len(metrics_history)
    avg_mean_mag = sum(m.mean_magnitude_vert for m in metrics_history) / len(metrics_history)
    avg_std_dev = sum(m.std_dev_vert for m in metrics_history) / len(metrics_history)
    avg_peak_ratio = sum(m.peak_ratio for m in metrics_history) / len(metrics_history)

    # Road quality & feature detection
    if speed_kmph < MIN_SPEED_FOR_DETECTION:
        road_quality = None
        feature = None
    else:
        # Road quality from averaged metrics
        if avg_rms < cal["rmsSmoothMax"] and avg_std_dev < cal["stdDevSmoothMax"]:
            road_quality = "smooth"
        elif avg_rms >= cal["rmsRoughMin"] and avg_std_dev >= cal["stdDevRoughMin"]:
            road_quality = "rough"
        else:
            road_quality = "average"

        # Speed hump pattern on vertical acceleration
        raw_vert_accel = []
        for v in smoothed:
            if use_g:
                a_vert = v[0] * use_g[0] + v[1] * use_g[1] + v[2] * use_g[2]
            else:
                a_vert = v[2]
            raw_vert_accel.append(a_vert)

        speed_hump = detect_speed_hump_pattern(raw_vert_accel, fwd_max, speed_kmph, SAMPLING_RATE)
        if speed_hump:
            feature = speed_hump
        else:
            feature = detect_feature_from_metrics(rms_vert, max_magnitude, peak_ratio, cal)

    return {
        "rms_vert": rms_vert,
        "max_magnitude": max_magnitude,
        "mean_magnitude_vert": mean_magnitude_vert,
        "std_dev_vert": std_dev_vert,
        "peak_ratio": peak_ratio,
        "avg_rms": avg_rms,
        "avg_max_mag": avg_max_mag,
        "avg_std_dev": avg_std_dev,
        "avg_peak_ratio": avg_peak_ratio,
        "fwd_rms": fwd_rms,
        "fwd_max": fwd_max,
        "lat_rms": lat_rms,
        "lat_max": lat_max,
        "road_quality": road_quality,
        "feature": feature,
    }


# ---------------------------------------------------------------------------
# JSON data extraction helpers
# ---------------------------------------------------------------------------
def extract_base_gravity_vector(track_data: dict) -> Optional[List[float]]:
    """Pull baseGravityVector from the recording's meta/calibration."""
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    cal = meta.get("recordingSettings", {}).get("calibration", {})
    bgv = cal.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    return None


def extract_data_points(track_data: dict) -> List[dict]:
    """Return the data[] array from the recording."""
    gps = track_data.get("gpslogger2path", track_data)
    if "data" in gps:
        return gps["data"]
    if "data" in track_data:
        return track_data["data"]
    if isinstance(track_data, list):
        return track_data
    return []


# ---------------------------------------------------------------------------
# KML generation
# ---------------------------------------------------------------------------
ROAD_QUALITY_COLORS = {
    "smooth": "ff00ff00",   # green
    "average": "ff00ffff",  # yellow
    "rough": "ff0000ff",    # red
    None: "ff888888",       # grey (below speed threshold)
}

FEATURE_ICONS = {
    "speed_bump": "http://maps.google.com/mapfiles/kml/shapes/caution.png",
    "pothole": "http://maps.google.com/mapfiles/kml/shapes/forbidden.png",
    "bump": "http://maps.google.com/mapfiles/kml/shapes/earthquake.png",
}


def write_kml(output_path: str, results: List[dict], cal: dict, profile_name: str):
    """Write a KML file with road-quality coloured points and feature placemarks."""
    with open(output_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
        f.write("<Document>\n")
        f.write(f"  <name>Validation: {saxutils.escape(profile_name)}</name>\n")
        f.write("  <description>Road quality and feature detection validation</description>\n")

        # Styles for road quality
        for quality, color in ROAD_QUALITY_COLORS.items():
            style_id = f"rq_{quality}" if quality else "rq_none"
            f.write(f'  <Style id="{style_id}">\n')
            f.write("    <IconStyle>\n")
            f.write(f"      <color>{color}</color>\n")
            f.write("      <scale>0.5</scale>\n")
            f.write("      <Icon><href>http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png</href></Icon>\n")
            f.write("    </IconStyle>\n")
            f.write("    <LabelStyle><scale>0</scale></LabelStyle>\n")
            f.write("  </Style>\n")

        # Styles for features
        for feat, icon in FEATURE_ICONS.items():
            f.write(f'  <Style id="feat_{feat}">\n')
            f.write("    <IconStyle>\n")
            f.write("      <scale>1.2</scale>\n")
            f.write(f"      <Icon><href>{icon}</href></Icon>\n")
            f.write("    </IconStyle>\n")
            f.write("  </Style>\n")

        # --- Road quality folder ---
        f.write("  <Folder>\n")
        f.write("    <name>Road Quality</name>\n")
        for r in results:
            lat, lon = r["lat"], r["lon"]
            rq = r.get("road_quality")
            style = f"rq_{rq}" if rq else "rq_none"
            speed = r.get("speed", 0)
            rms = r.get("rms_vert", 0)
            std = r.get("avg_std_dev", 0)
            f.write("    <Placemark>\n")
            f.write(f"      <name>{rq or 'n/a'}</name>\n")
            desc = (
                f"Speed: {speed:.1f} km/h\\n"
                f"RMS(vert): {rms:.2f}\\n"
                f"AvgRMS: {r.get('avg_rms', 0):.2f}\\n"
                f"AvgStdDev: {std:.2f}\\n"
                f"MaxMag: {r.get('max_magnitude', 0):.2f}\\n"
                f"PeakRatio: {r.get('peak_ratio', 0):.3f}\\n"
                f"FwdMax: {r.get('fwd_max', 0):.2f}\\n"
                f"LatMax: {r.get('lat_max', 0):.2f}"
            )
            f.write(f"      <description>{desc}</description>\n")
            f.write(f"      <styleUrl>#{style}</styleUrl>\n")
            f.write(f"      <Point><coordinates>{lon},{lat},0</coordinates></Point>\n")
            f.write("    </Placemark>\n")
        f.write("  </Folder>\n")

        # --- Features folder ---
        features = [r for r in results if r.get("feature")]
        if features:
            f.write("  <Folder>\n")
            f.write("    <name>Detected Features</name>\n")
            for r in features:
                lat, lon = r["lat"], r["lon"]
                feat = r["feature"]
                style = f"feat_{feat}" if feat in FEATURE_ICONS else "rq_rough"
                speed = r.get("speed", 0)
                f.write("    <Placemark>\n")
                f.write(f"      <name>{feat}</name>\n")
                desc = (
                    f"Feature: {feat}\\n"
                    f"Speed: {speed:.1f} km/h\\n"
                    f"RMS(vert): {r.get('rms_vert', 0):.2f}\\n"
                    f"MaxMag: {r.get('max_magnitude', 0):.2f}\\n"
                    f"PeakRatio: {r.get('peak_ratio', 0):.3f}\\n"
                    f"FwdMax: {r.get('fwd_max', 0):.2f}"
                )
                f.write(f"      <description>{desc}</description>\n")
                f.write(f"      <styleUrl>#{style}</styleUrl>\n")
                f.write(f"      <Point><coordinates>{lon},{lat},0</coordinates></Point>\n")
                f.write("    </Placemark>\n")
            f.write("  </Folder>\n")

        # --- Track line coloured by quality ---
        f.write("  <Folder>\n")
        f.write("    <name>Track Line</name>\n")
        # Group consecutive points by road quality for line segments
        if results:
            segments = []
            cur_quality = results[0].get("road_quality")
            cur_coords = []
            for r in results:
                rq = r.get("road_quality")
                if rq != cur_quality:
                    if cur_coords:
                        segments.append((cur_quality, list(cur_coords)))
                    cur_quality = rq
                    # overlap last point for continuity
                    cur_coords = [cur_coords[-1]] if cur_coords else []
                cur_coords.append((r["lon"], r["lat"]))
            if cur_coords:
                segments.append((cur_quality, cur_coords))

            for quality, coords in segments:
                color = ROAD_QUALITY_COLORS.get(quality, "ff888888")
                f.write("    <Placemark>\n")
                f.write(f"      <name>{quality or 'n/a'} segment</name>\n")
                f.write("      <Style>\n")
                f.write("        <LineStyle>\n")
                f.write(f"          <color>{color}</color>\n")
                f.write("          <width>4</width>\n")
                f.write("        </LineStyle>\n")
                f.write("      </Style>\n")
                f.write("      <LineString>\n")
                f.write("        <tessellate>1</tessellate>\n")
                f.write("        <coordinates>\n")
                for lon, lat in coords:
                    f.write(f"          {lon},{lat},0\n")
                f.write("        </coordinates>\n")
                f.write("      </LineString>\n")
                f.write("    </Placemark>\n")
        f.write("  </Folder>\n")

        f.write("</Document>\n")
        f.write("</kml>\n")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    if len(sys.argv) != 3:
        print("Usage: python validate_calc_for_profile.py <calibration_profile.json> <recording_track.json>")
        sys.exit(1)

    profile_path = sys.argv[1]
    track_path = sys.argv[2]

    # Load calibration profile
    try:
        with open(profile_path, "r") as f:
            profile_data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error: Failed to load calibration profile '{profile_path}': {e}")
        sys.exit(1)
    
    # Extract calibration settings from nested structure
    if "calibration" not in profile_data:
        print("Error: Calibration profile missing 'calibration' key")
        sys.exit(1)
    
    cal = profile_data["calibration"]
    
    # Validate that all required calibration fields are present
    missing_fields = []
    for field in DEFAULT_CALIBRATION.keys():
        if field not in cal:
            missing_fields.append(field)
    
    if missing_fields:
        print(f"Error: Calibration profile missing required fields: {', '.join(missing_fields)}")
        print("Required fields:", ", ".join(DEFAULT_CALIBRATION.keys()))
        sys.exit(1)
    
    # Extract profile name from the profile data if available
    profile_name = profile_data.get("name", os.path.splitext(os.path.basename(profile_path))[0])

    print(f"Calibration profile: {profile_name}")
    for k, v in cal.items():
        print(f"  {k}: {v}")

    # Load recording track
    try:
        with open(track_path, "r") as f:
            track_data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error: Failed to load recording track '{track_path}': {e}")
        sys.exit(1)

    # Extract base gravity vector from the recording
    base_gravity = extract_base_gravity_vector(track_data)
    if base_gravity:
        print(f"\nBase gravity vector from recording: [{base_gravity[0]:.3f}, {base_gravity[1]:.3f}, {base_gravity[2]:.3f}]")
    else:
        print("\nWARNING: No baseGravityVector found in recording, will use Z-axis as vertical")

    # Compute vehicle basis from the recording's gravity vector
    g_unit_basis, fwd_unit, lat_unit = (None, None, None)
    if base_gravity:
        g_unit_basis, fwd_unit, lat_unit = compute_vehicle_basis(base_gravity)
        if g_unit_basis:
            print(f"Vehicle basis: g=[{g_unit_basis[0]:.3f},{g_unit_basis[1]:.3f},{g_unit_basis[2]:.3f}]"
                  f" fwd=[{fwd_unit[0]:.3f},{fwd_unit[1]:.3f},{fwd_unit[2]:.3f}]"
                  f" lat=[{lat_unit[0]:.3f},{lat_unit[1]:.3f},{lat_unit[2]:.3f}]")

    # Extract data points
    points = extract_data_points(track_data)
    print(f"\nTotal data points in recording: {len(points)}")

    # Process each point
    metrics_history = deque()
    results = []
    skipped = 0

    for i, point in enumerate(points):
        if isinstance(point, str):
            try:
                point = json.loads(point)
            except json.JSONDecodeError:
                skipped += 1
                continue

        # GPS coordinates
        gps = point.get("gps", {})
        lat = gps.get("lat") if gps else point.get("lat")
        lon = gps.get("lon") if gps else point.get("lon")
        speed = gps.get("speed", 0) if gps else point.get("speed", 0)
        if speed is None:
            speed = 0

        if lat is None or lon is None:
            skipped += 1
            continue

        # Raw acceleration data
        accel = point.get("accel", {})
        raw_data = accel.get("raw", [])

        if not raw_data or len(raw_data) == 0:
            skipped += 1
            continue

        # Ensure each sample is a list of 3 floats
        if isinstance(raw_data[0], dict):
            raw_data = [[s.get("x", 0), s.get("y", 0), s.get("z", 0)] for s in raw_data]

        metrics = compute_accel_metrics(
            raw_data, speed, cal, g_unit_basis, fwd_unit, lat_unit, metrics_history
        )
        if metrics is None:
            skipped += 1
            continue

        metrics["lat"] = lat
        metrics["lon"] = lon
        metrics["speed"] = speed
        metrics["point_index"] = i + 1
        results.append(metrics)

    print(f"Processed: {len(results)} points, skipped: {skipped}")

    # Summary statistics
    if results:
        print("\n" + "=" * 70)
        print("SUMMARY")
        print("=" * 70)

        quality_counts = {}
        feature_counts = {}
        for r in results:
            rq = r.get("road_quality") or "below_speed"
            quality_counts[rq] = quality_counts.get(rq, 0) + 1
            feat = r.get("feature")
            if feat:
                feature_counts[feat] = feature_counts.get(feat, 0) + 1

        print("\nRoad quality distribution:")
        for q in ["smooth", "average", "rough", "below_speed"]:
            if q in quality_counts:
                pct = 100.0 * quality_counts[q] / len(results)
                print(f"  {q:15s}: {quality_counts[q]:5d} ({pct:.1f}%)")

        if feature_counts:
            print("\nDetected features:")
            for feat, cnt in sorted(feature_counts.items()):
                print(f"  {feat:15s}: {cnt}")
        else:
            print("\nNo features detected.")

        # Metric ranges
        print("\nMetric ranges:")
        for key in ["rms_vert", "max_magnitude", "std_dev_vert", "peak_ratio", "avg_rms", "avg_std_dev", "fwd_max", "lat_max"]:
            vals = [r[key] for r in results if key in r]
            if vals:
                print(f"  {key:20s}: min={min(vals):7.3f}  max={max(vals):7.3f}  mean={sum(vals)/len(vals):7.3f}")

    # Write KML
    output_kml = os.path.splitext(track_path)[0] + f"_validated_{profile_name}.kml"
    write_kml(output_kml, results, cal, profile_name)
    print(f"\nKML output written to: {output_kml}")

    # Also write a CSV for further analysis
    output_csv = os.path.splitext(track_path)[0] + f"_validated_{profile_name}.csv"
    with open(output_csv, "w") as f:
        headers = [
            "point_index", "lat", "lon", "speed",
            "rms_vert", "max_magnitude", "mean_magnitude_vert", "std_dev_vert", "peak_ratio",
            "avg_rms", "avg_max_mag", "avg_std_dev", "avg_peak_ratio",
            "fwd_rms", "fwd_max", "lat_rms", "lat_max",
            "road_quality", "feature",
        ]
        f.write(",".join(headers) + "\n")
        for r in results:
            row = [str(r.get(h, "")) for h in headers]
            f.write(",".join(row) + "\n")
    print(f"CSV output written to: {output_csv}")


if __name__ == "__main__":
    main()
