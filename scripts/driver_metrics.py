#!/usr/bin/env python3
"""
Driver Skill Metrics Analysis for Motorcycle Tracking Data.

Analyzes forward (longitudinal) and lateral accelerometer axes from
SJGpsUtil JSON track files to derive driver behavior metrics such as
hard braking, hard acceleration, swerving, cornering aggressiveness,
smoothness scores, and more.

Usage:
    python driver_metrics.py <track.json>

Outputs:
    - Per-fix CSV with classifications and metrics
    - Trip summary printed to console
    - KML file with classified events plotted on track
    - Matplotlib plots: time series, friction circle, speed vs events
"""

import json
import math
import os
import sys
import csv
import xml.sax.saxutils as saxutils
from collections import deque
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# THRESHOLDS — tune these with real data
# ---------------------------------------------------------------------------
HARD_BRAKE_FWD_MAX = 15      # m/s² — forward accel threshold for hard braking
HARD_ACCEL_FWD_MAX = 15     # m/s² — forward accel threshold for hard acceleration
REACTION_TIME_BRAKE_MAX = 15
REACTION_TIME_LAT_MAX = 4
SWERVE_LAT_MAX = 4          # m/s² — lateral accel threshold for swerve event
AGGRESSIVE_CORNER_LAT_MAX = 4 # m/s² — lateral threshold during cornering
AGGRESSIVE_CORNER_DCOURSE = 15.0 # degrees — minimum bearing change for cornering
MIN_SPEED_KMPH = 6.0            # km/h — ignore events below this speed
SAMPLING_RATE = 100.0           # Hz — raw accelerometer sampling rate

# Smoothness score parameters (maps RMS to 0-100 scale)
SMOOTHNESS_FWD_WEIGHT = 0.2
SMOOTHNESS_LAT_WEIGHT = 0.8
SMOOTHNESS_RMS_MAX = 10  # RMS at or above this → score 0

MOVING_AVG_WINDOW = 10  # Moving average window for smoothing raw accel data before fwd/lat projection
# ---------------------------------------------------------------------------
# Vehicle-frame basis computation (mirrors computeVehicleBasis in Kotlin)
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

    # Lateral = g x fwd (cross product)
    lat = [
        g[1] * fwd[2] - g[2] * fwd[1],
        g[2] * fwd[0] - g[0] * fwd[2],
        g[0] * fwd[1] - g[1] * fwd[0],
    ]
    return g, fwd, lat


# ---------------------------------------------------------------------------
# Moving average filter
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
# Compute fwd/lat metrics from raw data
# ---------------------------------------------------------------------------
def compute_fwd_lat_from_raw(
    raw_data: List[List[float]],
    g_unit: Optional[List[float]],
    fwd_unit: Optional[List[float]],
    lat_unit: Optional[List[float]],
    ma_window: int = MOVING_AVG_WINDOW,
) -> Dict[str, float]:
    """Project raw [x,y,z] samples onto fwd/lat axes and compute RMS/Max/Mean."""
    if not raw_data:
        return {}

    n = len(raw_data)
    # Detrend (remove gravity bias)
    bias = [sum(s[j] for s in raw_data) / n for j in range(3)]
    detrended = [[s[j] - bias[j] for j in range(3)] for s in raw_data]

    # Smooth
    smoothed = apply_moving_average(detrended, max(1, ma_window))

    fwd_sum_sq = 0.0
    fwd_max_mag = 0.0
    fwd_sum_val = 0.0
    lat_sum_sq = 0.0
    lat_max_mag = 0.0
    lat_sum_val = 0.0

    fwd_values = []
    lat_values = []

    for v in smoothed:
        if fwd_unit:
            a_fwd = v[0] * fwd_unit[0] + v[1] * fwd_unit[1] + v[2] * fwd_unit[2]
        else:
            a_fwd = v[1]  # fallback: device Y
        fwd_sum_val += a_fwd
        fwd_sum_sq += a_fwd * a_fwd
        fwd_values.append(a_fwd)
        abs_fwd = abs(a_fwd)
        if abs_fwd > fwd_max_mag:
            fwd_max_mag = abs_fwd

        if lat_unit:
            a_lat = v[0] * lat_unit[0] + v[1] * lat_unit[1] + v[2] * lat_unit[2]
        else:
            a_lat = v[0]  # fallback: device X
        lat_sum_val += a_lat
        lat_sum_sq += a_lat * a_lat
        lat_values.append(a_lat)
        abs_lat = abs(a_lat)
        if abs_lat > lat_max_mag:
            lat_max_mag = abs_lat

    count = len(smoothed)
    
    # Lean angle: rotation of per-window gravity away from baseline, in lateral plane
    # Uses atan2(lateral_component, vertical_component) of per-window gravity
    # projected onto baseline vehicle axes
    lean_angle_deg = 0.0
    bias_norm = math.sqrt(sum(b * b for b in bias))
    if bias_norm > 1e-3 and lat_unit and g_unit:
        wg_x = bias[0] / bias_norm
        wg_y = bias[1] / bias_norm
        wg_z = bias[2] / bias_norm
        lat_comp = wg_x * lat_unit[0] + wg_y * lat_unit[1] + wg_z * lat_unit[2]
        vert_comp = wg_x * g_unit[0] + wg_y * g_unit[1] + wg_z * g_unit[2]
        lean_angle_deg = math.degrees(math.atan2(lat_comp, vert_comp))
    
    return {
        "fwdRms": math.sqrt(fwd_sum_sq / count),
        "fwdMax": fwd_max_mag,
        "fwdMean": fwd_sum_val / count,
        "latRms": math.sqrt(lat_sum_sq / count),
        "latMax": lat_max_mag,
        "latMean": lat_sum_val / count,
        "leanAngleDeg": lean_angle_deg,
        "fwd_values": fwd_values,
        "lat_values": lat_values,
    }


# ---------------------------------------------------------------------------
# JSON data extraction helpers
# ---------------------------------------------------------------------------
def extract_base_gravity_vector(track_data: dict) -> Optional[List[float]]:
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    cal = meta.get("recordingSettings", {}).get("calibration", {})
    bgv = cal.get("baseGravityVector")
    if bgv and isinstance(bgv, dict):
        return [bgv.get("x", 0), bgv.get("y", 0), bgv.get("z", 0)]
    if bgv and isinstance(bgv, list) and len(bgv) >= 3:
        return bgv[:3]
    return None


def extract_ma_window(track_data: dict) -> int:
    gps = track_data.get("gpslogger2path", track_data)
    meta = gps.get("meta", {})
    cal = meta.get("recordingSettings", {}).get("calibration", {})
    return cal.get("movingAverageWindow", 1)


def extract_data_points(track_data: dict) -> List[dict]:
    gps = track_data.get("gpslogger2path", track_data)
    if "data" in gps:
        return gps["data"]
    if "data" in track_data:
        return track_data["data"]
    if isinstance(track_data, list):
        return track_data
    return []


# ---------------------------------------------------------------------------
# Normalize bearing difference to [-180, 180]
# ---------------------------------------------------------------------------
def bearing_diff(c1: float, c2: float) -> float:
    d = c2 - c1
    while d > 180:
        d -= 360
    while d < -180:
        d += 360
    return d


# ---------------------------------------------------------------------------
# Event classification
# ---------------------------------------------------------------------------
def classify_event(
    fwd_max: float,
    lat_max: float,
    delta_speed: float,
    delta_course: float,
    speed: float,
) -> List[str]:
    """Return list of event labels for this fix."""
    events = []
    if speed < MIN_SPEED_KMPH:
        return ["low_speed"]

    # Longitudinal events
    if fwd_max > HARD_BRAKE_FWD_MAX and delta_speed < 0:
        events.append("hard_brake")
    if fwd_max > HARD_ACCEL_FWD_MAX and delta_speed > 0:
        events.append("hard_accel")

    # Lateral events
    if lat_max > SWERVE_LAT_MAX:
        events.append("swerve")
    if lat_max > AGGRESSIVE_CORNER_LAT_MAX and abs(delta_course) > AGGRESSIVE_CORNER_DCOURSE:
        events.append("aggressive_corner")

    if not events:
        events.append("normal")
    return events


# ---------------------------------------------------------------------------
# Smoothness score (0-100, higher = smoother)
# ---------------------------------------------------------------------------
def smoothness_score(fwd_rms: float, lat_rms: float) -> float:
    combined = SMOOTHNESS_FWD_WEIGHT * fwd_rms + SMOOTHNESS_LAT_WEIGHT * lat_rms
    score = max(0.0, 1.0 - combined / SMOOTHNESS_RMS_MAX) * 100.0
    return round(score, 1)


# ---------------------------------------------------------------------------
# Raw data deep metrics (jerk, weaving, FFT)
# ---------------------------------------------------------------------------
def compute_raw_deep_metrics(
    fwd_values: List[float], lat_values: List[float]
) -> Dict[str, float]:
    """Compute jerk, weaving index, and dominant lateral frequency from 100Hz raw data."""
    metrics = {}

    # Forward jerk: RMS of derivative of fwd acceleration
    if len(fwd_values) > 1:
        dt = 1.0 / SAMPLING_RATE
        jerk = [(fwd_values[i + 1] - fwd_values[i]) / dt for i in range(len(fwd_values) - 1)]
        jerk_rms = math.sqrt(sum(j * j for j in jerk) / len(jerk))
        metrics["fwd_jerk_rms"] = jerk_rms

    # Weaving index: sign changes in lateral acceleration
    if len(lat_values) > 1:
        sign_changes = sum(
            1 for i in range(1, len(lat_values))
            if lat_values[i - 1] * lat_values[i] < 0
        )
        metrics["weaving_index"] = sign_changes

    # Dominant lateral frequency via simple zero-crossing frequency estimate
    # (avoids numpy/scipy dependency for FFT)
    if len(lat_values) > 10:
        zero_crossings = sum(
            1 for i in range(1, len(lat_values))
            if lat_values[i - 1] * lat_values[i] < 0
        )
        duration = len(lat_values) / SAMPLING_RATE
        # Each full cycle has 2 zero crossings
        dominant_freq = (zero_crossings / 2.0) / duration if duration > 0 else 0
        metrics["lat_dominant_freq_hz"] = dominant_freq

    # Reaction time proxy: time between first fwd spike and first lat spike
    fwd_spike_idx = None
    lat_spike_idx = None
    for i, v in enumerate(fwd_values):
        if abs(v) > REACTION_TIME_BRAKE_MAX:
            fwd_spike_idx = i
            break
    for i, v in enumerate(lat_values):
        if abs(v) > REACTION_TIME_LAT_MAX:
            lat_spike_idx = i
            break
    if fwd_spike_idx is not None and lat_spike_idx is not None:
        reaction_samples = lat_spike_idx - fwd_spike_idx
        if reaction_samples >= 10:  # Ignore < 100ms as humanly impossible
            metrics["reaction_time_ms"] = (reaction_samples / SAMPLING_RATE) * 1000.0

    return metrics


# ---------------------------------------------------------------------------
# KML output
# ---------------------------------------------------------------------------
EVENT_COLORS = {
    "hard_brake": "ff0000ff",       # red
    "hard_accel": "ff00a5ff",       # orange
    "swerve": "ffff00ff",           # magenta
    "aggressive_corner": "ff00ffff", # yellow
    "normal": "ff00ff00",           # green
    "low_speed": "ff888888",        # grey
}

EVENT_ICONS = {
    "hard_brake": "http://maps.google.com/mapfiles/kml/shapes/forbidden.png",
    "hard_accel": "http://maps.google.com/mapfiles/kml/shapes/triangle.png",
    "swerve": "http://maps.google.com/mapfiles/kml/shapes/caution.png",
    "aggressive_corner": "http://maps.google.com/mapfiles/kml/shapes/lightning.png",
    "normal": "http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png",
    "low_speed": "http://maps.google.com/mapfiles/kml/shapes/shaded_dot.png",
}


def write_kml(output_path: str, fixes: List[dict]):
    """Write KML with classified events on the GPS track."""
    with open(output_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
        f.write("<Document>\n")
        f.write("  <name>Driver Metrics Analysis</name>\n")
        f.write("  <description>Event classifications along track</description>\n")

        # Styles
        for evt, color in EVENT_COLORS.items():
            icon = EVENT_ICONS.get(evt, EVENT_ICONS["normal"])
            scale = "1.2" if evt not in ("normal", "low_speed") else "0.4"
            f.write(f'  <Style id="evt_{evt}">\n')
            f.write("    <IconStyle>\n")
            f.write(f"      <color>{color}</color>\n")
            f.write(f"      <scale>{scale}</scale>\n")
            f.write(f"      <Icon><href>{icon}</href></Icon>\n")
            f.write("    </IconStyle>\n")
            f.write("    <LabelStyle><scale>0</scale></LabelStyle>\n")
            f.write("  </Style>\n")

        # Reaction time style
        f.write('  <Style id="reaction_time">\n')
        f.write("    <IconStyle>\n")
        f.write("      <color>ff00ff00</color>\n")  # green
        f.write("      <scale>1.4</scale>\n")
        f.write("      <Icon><href>http://maps.google.com/mapfiles/kml/shapes/star.png</href></Icon>\n")
        f.write("    </IconStyle>\n")
        f.write("    <LabelStyle><scale>0.7</scale></LabelStyle>\n")
        f.write("  </Style>\n")

        # --- Events folder (non-normal only) ---
        event_fixes = [fx for fx in fixes if fx["primary_event"] not in ("normal", "low_speed")]
        if event_fixes:
            f.write("  <Folder>\n")
            f.write("    <name>Driver Events</name>\n")
            for fx in event_fixes:
                lat, lon = fx["lat"], fx["lon"]
                evt = fx["primary_event"]
                style = f"evt_{evt}"
                lean_str = f"{fx['leanAngleDeg']:.1f}°" if fx['leanAngleDeg'] is not None else "N/A"
                desc = (
                    f"Event: {', '.join(fx['events'])}\\n"
                    f"Speed: {fx['speed']:.1f} km/h\\n"
                    f"FwdMax: {fx['fwdMax']:.2f} m/s²\\n"
                    f"LatMax: {fx['latMax']:.2f} m/s²\\n"
                    f"LeanAngle: {lean_str}\\n"
                    f"FrictionCircle: {fx['friction_circle']:.2f} m/s²\\n"
                    f"Smoothness: {fx['smoothness']:.0f}/100"
                )
                f.write("    <Placemark>\n")
                f.write(f"      <name>{evt}</name>\n")
                f.write(f"      <description>{desc}</description>\n")
                f.write(f"      <styleUrl>#{style}</styleUrl>\n")
                f.write(f"      <Point><coordinates>{lon},{lat},0</coordinates></Point>\n")
                f.write("    </Placemark>\n")
            f.write("  </Folder>\n")

        # --- All points folder ---
        f.write("  <Folder>\n")
        f.write("    <name>All Points</name>\n")
        f.write("    <visibility>0</visibility>\n")
        for fx in fixes:
            lat, lon = fx["lat"], fx["lon"]
            evt = fx["primary_event"]
            style = f"evt_{evt}"
            f.write("    <Placemark>\n")
            f.write(f"      <name>{evt}</name>\n")
            f.write(f"      <styleUrl>#{style}</styleUrl>\n")
            f.write(f"      <Point><coordinates>{lon},{lat},0</coordinates></Point>\n")
            f.write("    </Placemark>\n")
        f.write("  </Folder>\n")

        # --- Track line coloured by primary event ---
        f.write("  <Folder>\n")
        f.write("    <name>Track Line</name>\n")
        if fixes:
            segments = []
            cur_evt = fixes[0]["primary_event"]
            cur_coords = []
            for fx in fixes:
                evt = fx["primary_event"]
                if evt != cur_evt:
                    if cur_coords:
                        segments.append((cur_evt, list(cur_coords)))
                    cur_evt = evt
                    cur_coords = [cur_coords[-1]] if cur_coords else []
                cur_coords.append((fx["lon"], fx["lat"]))
            if cur_coords:
                segments.append((cur_evt, cur_coords))

            for evt, coords in segments:
                color = EVENT_COLORS.get(evt, "ff888888")
                f.write("    <Placemark>\n")
                f.write(f"      <name>{evt} segment</name>\n")
                f.write("      <Style><LineStyle>\n")
                f.write(f"        <color>{color}</color><width>4</width>\n")
                f.write("      </LineStyle></Style>\n")
                f.write("      <LineString><tessellate>1</tessellate><coordinates>\n")
                for lon, lat in coords:
                    f.write(f"        {lon},{lat},0\n")
                f.write("      </coordinates></LineString>\n")
                f.write("    </Placemark>\n")
        f.write("  </Folder>\n")

        # --- Reaction Time folder ---
        reaction_fixes = [fx for fx in fixes if fx.get("reaction_time_ms") is not None]
        if reaction_fixes:
            f.write("  <Folder>\n")
            f.write("    <name>Reaction Time</name>\n")
            for fx in reaction_fixes:
                lat, lon = fx["lat"], fx["lon"]
                rt = fx["reaction_time_ms"]
                desc = (
                    f"Reaction Time: {rt:.0f} ms\\n"
                    f"Speed: {fx['speed']:.1f} km/h\\n"
                    f"FwdMax: {fx['fwdMax']:.2f} m/s\u00b2\\n"
                    f"LatMax: {fx['latMax']:.2f} m/s\u00b2"
                )
                f.write("    <Placemark>\n")
                f.write(f"      <name>{rt:.0f}ms</name>\n")
                f.write(f"      <description>{desc}</description>\n")
                f.write("      <styleUrl>#reaction_time</styleUrl>\n")
                f.write(f"      <Point><coordinates>{lon},{lat},0</coordinates></Point>\n")
                f.write("    </Placemark>\n")
            f.write("  </Folder>\n")

        f.write("</Document>\n")
        f.write("</kml>\n")


# ---------------------------------------------------------------------------
# Matplotlib plots
# ---------------------------------------------------------------------------
def generate_plots(fixes: List[dict], output_base: str):
    """Generate matplotlib plots. Gracefully skip if matplotlib not installed."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("WARNING: matplotlib not installed, skipping plots.")
        return

    times = [fx["ts_seconds"] for fx in fixes]
    speeds = [fx["speed"] for fx in fixes]
    fwd_maxes = [fx["fwdMax"] for fx in fixes]
    lat_maxes = [fx["latMax"] for fx in fixes]
    fwd_rms_vals = [fx["fwdRms"] for fx in fixes]
    lat_rms_vals = [fx["latRms"] for fx in fixes]
    lean_angles = [fx["leanAngleDeg"] if fx["leanAngleDeg"] is not None else float('nan') for fx in fixes]

    # Event markers
    brake_t = [fx["ts_seconds"] for fx in fixes if "hard_brake" in fx["events"]]
    brake_v = [fx["fwdMax"] for fx in fixes if "hard_brake" in fx["events"]]
    accel_t = [fx["ts_seconds"] for fx in fixes if "hard_accel" in fx["events"]]
    accel_v = [fx["fwdMax"] for fx in fixes if "hard_accel" in fx["events"]]
    swerve_t = [fx["ts_seconds"] for fx in fixes if "swerve" in fx["events"]]
    swerve_v = [fx["latMax"] for fx in fixes if "swerve" in fx["events"]]
    corner_t = [fx["ts_seconds"] for fx in fixes if "aggressive_corner" in fx["events"]]
    corner_v = [fx["latMax"] for fx in fixes if "aggressive_corner" in fx["events"]]

    # --- Plot 1: Time series of fwd/lat/lean with events ---
    fig, axes = plt.subplots(4, 1, figsize=(14, 12), sharex=True)

    ax = axes[0]
    ax.plot(times, speeds, "b-", linewidth=0.8, label="Speed (km/h)")
    ax.set_ylabel("Speed (km/h)")
    ax.legend(loc="upper right")
    ax.set_title("Driver Metrics — Time Series")
    ax.grid(True, alpha=0.3)

    ax = axes[1]
    ax.plot(times, fwd_maxes, "r-", linewidth=0.5, alpha=0.6, label="fwdMax")
    ax.plot(times, fwd_rms_vals, "r--", linewidth=0.5, alpha=0.4, label="fwdRms")
    if brake_t:
        ax.scatter(brake_t, brake_v, c="red", marker="v", s=40, zorder=5, label="Hard Brake")
    if accel_t:
        ax.scatter(accel_t, accel_v, c="orange", marker="^", s=40, zorder=5, label="Hard Accel")
    ax.axhline(y=HARD_BRAKE_FWD_MAX, color="red", linestyle=":", alpha=0.3)
    ax.set_ylabel("Forward Accel (m/s²)")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    ax = axes[2]
    ax.plot(times, lat_maxes, "m-", linewidth=0.5, alpha=0.6, label="latMax")
    ax.plot(times, lat_rms_vals, "m--", linewidth=0.5, alpha=0.4, label="latRms")
    if swerve_t:
        ax.scatter(swerve_t, swerve_v, c="magenta", marker="x", s=40, zorder=5, label="Swerve")
    if corner_t:
        ax.scatter(corner_t, corner_v, c="gold", marker="D", s=30, zorder=5, label="Agg. Corner")
    ax.axhline(y=SWERVE_LAT_MAX, color="magenta", linestyle=":", alpha=0.3)
    ax.set_ylabel("Lateral Accel (m/s²)")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    ax = axes[3]
    valid_lean = [(t, a) for t, a in zip(times, lean_angles) if not math.isnan(a)]
    if valid_lean:
        lean_t, lean_a = zip(*valid_lean)
        ax.plot(lean_t, lean_a, "c-", linewidth=0.5, alpha=0.6, label="Lean Angle")
    ax.set_ylabel("Lean Angle (°)")
    ax.set_xlabel("Time (seconds)")
    ax.legend(loc="upper right", fontsize=8)
    ax.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(f"{output_base}_timeseries.png", dpi=150)
    plt.close()
    print(f"  Plot: {output_base}_timeseries.png")

    # --- Plot 2: Friction circle ---
    fig, ax = plt.subplots(1, 1, figsize=(8, 8))
    colors = []
    for fx in fixes:
        if "hard_brake" in fx["events"]:
            colors.append("red")
        elif "hard_accel" in fx["events"]:
            colors.append("orange")
        elif "swerve" in fx["events"]:
            colors.append("magenta")
        elif "aggressive_corner" in fx["events"]:
            colors.append("gold")
        elif fx["speed"] < MIN_SPEED_KMPH:
            colors.append("lightgrey")
        else:
            colors.append("green")

    # fwdMax with sign: negative for braking, positive for accel
    fwd_signed = []
    for fx in fixes:
        if fx["delta_speed"] < 0:
            fwd_signed.append(-fx["fwdMax"])
        else:
            fwd_signed.append(fx["fwdMax"])

    ax.scatter(
        [fx["latMax"] * (1 if fx.get("delta_course", 0) >= 0 else -1) for fx in fixes],
        fwd_signed,
        c=colors, s=10, alpha=0.6
    )
    # Draw friction circle reference
    theta = [i * math.pi / 180 for i in range(361)]
    for r in [3, 5, 8]:
        ax.plot([r * math.cos(t) for t in theta], [r * math.sin(t) for t in theta],
                "k--", alpha=0.15, linewidth=0.5)
        ax.text(r * 0.7, r * 0.7, f"{r}", fontsize=7, alpha=0.3)

    ax.set_xlabel("Lateral Accel (m/s²) ← Left | Right →")
    ax.set_ylabel("Longitudinal Accel (m/s²) ← Brake | Accel →")
    ax.set_title("Friction Circle (G-G Diagram)")
    ax.set_aspect("equal")
    ax.grid(True, alpha=0.3)
    ax.axhline(0, color="k", linewidth=0.5)
    ax.axvline(0, color="k", linewidth=0.5)
    plt.tight_layout()
    plt.savefig(f"{output_base}_friction_circle.png", dpi=150)
    plt.close()
    print(f"  Plot: {output_base}_friction_circle.png")

    # --- Plot 3: Speed vs event severity ---
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))

    moving = [fx for fx in fixes if fx["speed"] >= MIN_SPEED_KMPH]
    if moving:
        ax = axes[0]
        ax.scatter([fx["speed"] for fx in moving], [fx["fwdMax"] for fx in moving],
                   s=8, alpha=0.4, c="red")
        ax.set_xlabel("Speed (km/h)")
        ax.set_ylabel("fwdMax (m/s²)")
        ax.set_title("Speed vs Forward Force")
        ax.axhline(y=HARD_BRAKE_FWD_MAX, color="red", linestyle=":", alpha=0.5)
        ax.grid(True, alpha=0.3)

        ax = axes[1]
        ax.scatter([fx["speed"] for fx in moving], [fx["latMax"] for fx in moving],
                   s=8, alpha=0.4, c="magenta")
        ax.set_xlabel("Speed (km/h)")
        ax.set_ylabel("latMax (m/s²)")
        ax.set_title("Speed vs Lateral Force")
        ax.axhline(y=SWERVE_LAT_MAX, color="magenta", linestyle=":", alpha=0.5)
        ax.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(f"{output_base}_speed_vs_events.png", dpi=150)
    plt.close()
    print(f"  Plot: {output_base}_speed_vs_events.png")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    if len(sys.argv) != 2:
        print("Usage: python driver_metrics.py <track.json>")
        sys.exit(1)

    track_path = sys.argv[1]

    # Load JSON
    try:
        with open(track_path, "r") as f:
            track_data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error loading '{track_path}': {e}")
        sys.exit(1)

    # Extract gravity vector and compute vehicle basis
    base_gravity = extract_base_gravity_vector(track_data)
    ma_window = extract_ma_window(track_data)
    g_unit, fwd_unit, lat_unit = (None, None, None)
    if base_gravity:
        g_unit, fwd_unit, lat_unit = compute_vehicle_basis(base_gravity)
        print(f"Gravity vector: [{base_gravity[0]:.3f}, {base_gravity[1]:.3f}, {base_gravity[2]:.3f}]")
        if fwd_unit:
            print(f"Vehicle basis: fwd=[{fwd_unit[0]:.3f},{fwd_unit[1]:.3f},{fwd_unit[2]:.3f}]"
                  f" lat=[{lat_unit[0]:.3f},{lat_unit[1]:.3f},{lat_unit[2]:.3f}]")
    else:
        print("WARNING: No baseGravityVector found, using device axes as fallback")

    points = extract_data_points(track_data)
    print(f"Total data points: {len(points)}")

    # -----------------------------------------------------------------------
    # Pass 1: Extract per-fix data
    # -----------------------------------------------------------------------
    fixes = []
    for i, point in enumerate(points):
        if isinstance(point, str):
            try:
                point = json.loads(point)
            except json.JSONDecodeError:
                continue

        gps = point.get("gps", {})
        lat = gps.get("lat")
        lon = gps.get("lon")
        speed = gps.get("speed", 0) or 0
        course = gps.get("course", 0) or 0
        ts = gps.get("ts", 0) or 0

        if lat is None or lon is None:
            continue

        accel = point.get("accel", {})

        # Always recompute from raw data so threshold changes in this file take effect
        fwd_rms = None
        fwd_max = None
        fwd_mean = None
        lat_rms = None
        lat_max = None
        lat_mean = None
        lean_angle = None

        fwd_values = []
        lat_values = []
        has_raw = False

        raw_data = accel.get("raw", [])
        if raw_data:
            computed = compute_fwd_lat_from_raw(raw_data, g_unit, fwd_unit, lat_unit, ma_window)
            if computed:
                fwd_rms = computed.get("fwdRms", 0)
                fwd_max = computed.get("fwdMax", 0)
                fwd_mean = computed.get("fwdMean", 0)
                lat_rms = computed.get("latRms", 0)
                lat_max = computed.get("latMax", 0)
                lat_mean = computed.get("latMean", 0)
                lean_angle = computed.get("leanAngleDeg", 0)
                fwd_values = computed.get("fwd_values", [])
                lat_values = computed.get("lat_values", [])
                has_raw = True

        # Skip lean angle at low speeds to keep averages accurate
        if speed < MIN_SPEED_KMPH:
            lean_angle = None
        else:
            lean_angle = lean_angle or 0.0

        # Default to 0 if still None
        fwd_rms = fwd_rms or 0.0
        fwd_max = fwd_max or 0.0
        fwd_mean = fwd_mean or 0.0
        lat_rms = lat_rms or 0.0
        lat_max = lat_max or 0.0
        lat_mean = lat_mean or 0.0

        fix = {
            "index": i + 1,
            "lat": lat,
            "lon": lon,
            "speed": speed,
            "course": course,
            "ts_ms": ts,
            "ts_seconds": ts / 1000.0,
            "fwdRms": fwd_rms,
            "fwdMax": fwd_max,
            "fwdMean": fwd_mean,
            "latRms": lat_rms,
            "latMax": lat_max,
            "latMean": lat_mean,
            "leanAngleDeg": lean_angle,
            "fwd_values": fwd_values,
            "lat_values": lat_values,
            "has_raw": has_raw,
        }
        fixes.append(fix)

    if not fixes:
        print("No valid data points found.")
        sys.exit(1)

    print(f"Extracted {len(fixes)} fixes with accel data")

    # -----------------------------------------------------------------------
    # Pass 2: Compute deltas and classify events
    # -----------------------------------------------------------------------
    for i, fx in enumerate(fixes):
        if i == 0:
            fx["delta_speed"] = 0.0
            fx["delta_course"] = 0.0
            fx["jerk"] = 0.0
        else:
            prev = fixes[i - 1]
            fx["delta_speed"] = fx["speed"] - prev["speed"]
            fx["delta_course"] = bearing_diff(prev["course"], fx["course"])
            dt = (fx["ts_ms"] - prev["ts_ms"]) / 1000.0
            if dt > 0:
                # Use signed RMS for jerk to avoid noisy max values
                curr_signed = math.copysign(fx["fwdRms"], fx["fwdMean"]) if fx["fwdMean"] != 0 else fx["fwdRms"]
                prev_signed = math.copysign(prev["fwdRms"], prev["fwdMean"]) if prev["fwdMean"] != 0 else prev["fwdRms"]
                fx["jerk"] = abs(curr_signed - prev_signed) / dt
            else:
                fx["jerk"] = 0.0

        # Classify
        fx["events"] = classify_event(
            fx["fwdMax"], fx["latMax"], fx["delta_speed"], fx["delta_course"], fx["speed"]
        )
        # Primary event (most severe)
        priority = ["hard_brake", "swerve", "aggressive_corner", "hard_accel", "normal", "low_speed"]
        fx["primary_event"] = next((e for e in priority if e in fx["events"]), "normal")

        # Signed RMS: apply sign of mean to indicate direction
        signed_fwd_rms = math.copysign(fx["fwdRms"], fx["fwdMean"]) if fx["fwdMean"] != 0 else fx["fwdRms"]
        signed_lat_rms = math.copysign(fx["latRms"], fx["latMean"]) if fx["latMean"] != 0 else fx["latRms"]
        
        # Composite metrics
        fx["friction_circle"] = math.sqrt(fx["fwdMax"] ** 2 + fx["latMax"] ** 2)
        fx["lean_angle_deg"] = fx["leanAngleDeg"]
        fx["smoothness"] = smoothness_score(fx["fwdRms"], fx["latRms"])
        fx["speed_norm_fwd"] = fx["fwdMax"] / fx["speed"] if fx["speed"] > MIN_SPEED_KMPH else 0
        fx["speed_norm_lat"] = fx["latMax"] / fx["speed"] if fx["speed"] > MIN_SPEED_KMPH else 0
        fx["signedFwdRms"] = signed_fwd_rms
        fx["signedLatRms"] = signed_lat_rms

        # Raw deep metrics
        if fx["has_raw"] and fx["fwd_values"] and fx["lat_values"]:
            deep = compute_raw_deep_metrics(fx["fwd_values"], fx["lat_values"])
            fx.update(deep)

    # -----------------------------------------------------------------------
    # Pass 3: Braking distance proxy (consecutive deceleration duration)
    # -----------------------------------------------------------------------
    for i, fx in enumerate(fixes):
        fx["braking_duration_s"] = 0.0
    i = 0
    while i < len(fixes):
        if fixes[i]["delta_speed"] < -0.5 and fixes[i]["speed"] >= MIN_SPEED_KMPH:
            start = i
            while i < len(fixes) and fixes[i]["delta_speed"] < 0:
                i += 1
            duration_ms = fixes[min(i, len(fixes) - 1)]["ts_ms"] - fixes[start]["ts_ms"]
            for j in range(start, min(i, len(fixes))):
                fixes[j]["braking_duration_s"] = duration_ms / 1000.0
        else:
            i += 1

    # -----------------------------------------------------------------------
    # Output: CSV
    # -----------------------------------------------------------------------
    output_base = os.path.splitext(track_path)[0]
    csv_path = f"{output_base}_driver_metrics.csv"

    csv_headers = [
        "index", "lat", "lon", "ts_seconds", "speed", "course",
        "delta_speed", "delta_course", "jerk",
        "fwdRms", "fwdMax", "fwdMean", "latRms", "latMax", "latMean",
        "signedFwdRms", "signedLatRms",
        "friction_circle", "smoothness", "speed_norm_fwd", "speed_norm_lat",
        "lean_angle_deg", "braking_duration_s",
        "primary_event", "events",
        "fwd_jerk_rms", "weaving_index", "lat_dominant_freq_hz", "reaction_time_ms",
    ]

    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=csv_headers, extrasaction="ignore")
        writer.writeheader()
        for fx in fixes:
            row = {k: fx.get(k, "") for k in csv_headers}
            row["events"] = "|".join(fx["events"])
            # Round floats
            for k in csv_headers:
                v = row.get(k)
                if isinstance(v, float):
                    row[k] = f"{v:.3f}"
            writer.writerow(row)

    print(f"\nCSV: {csv_path}")

    # -----------------------------------------------------------------------
    # Output: KML
    # -----------------------------------------------------------------------
    kml_path = f"{output_base}_driver_metrics.kml"
    write_kml(kml_path, fixes)
    print(f"KML: {kml_path}")

    # -----------------------------------------------------------------------
    # Output: Plots
    # -----------------------------------------------------------------------
    print("\nGenerating plots...")
    generate_plots(fixes, output_base)

    # -----------------------------------------------------------------------
    # Trip Summary
    # -----------------------------------------------------------------------
    moving_fixes = [fx for fx in fixes if fx["speed"] >= MIN_SPEED_KMPH]
    total_time_s = (fixes[-1]["ts_ms"] - fixes[0]["ts_ms"]) / 1000.0 if len(fixes) > 1 else 0

    # Estimate distance from speed * time
    total_dist_km = 0
    for i in range(1, len(fixes)):
        dt_h = (fixes[i]["ts_ms"] - fixes[i - 1]["ts_ms"]) / 3600000.0
        avg_speed = (fixes[i]["speed"] + fixes[i - 1]["speed"]) / 2.0
        total_dist_km += avg_speed * dt_h

    # Event counts
    event_counts = {}
    for fx in fixes:
        for e in fx["events"]:
            event_counts[e] = event_counts.get(e, 0) + 1

    # Metric averages (moving only)
    if moving_fixes:
        avg_fwd_rms = sum(fx["fwdRms"] for fx in moving_fixes) / len(moving_fixes)
        avg_lat_rms = sum(fx["latRms"] for fx in moving_fixes) / len(moving_fixes)
        avg_fwd_max = sum(fx["fwdMax"] for fx in moving_fixes) / len(moving_fixes)
        avg_lat_max = sum(fx["latMax"] for fx in moving_fixes) / len(moving_fixes)
        avg_smoothness = sum(fx["smoothness"] for fx in moving_fixes) / len(moving_fixes)
        max_fwd_max = max(fx["fwdMax"] for fx in moving_fixes)
        max_lat_max = max(fx["latMax"] for fx in moving_fixes)
        max_friction = max(fx["friction_circle"] for fx in moving_fixes)
        avg_lean = sum(fx["leanAngleDeg"] for fx in moving_fixes if fx["leanAngleDeg"] is not None) / len(moving_fixes)
        max_lean = max((fx["leanAngleDeg"] for fx in moving_fixes if fx["leanAngleDeg"] is not None), default=0)
    else:
        avg_fwd_rms = avg_lat_rms = avg_fwd_max = avg_lat_max = 0
        avg_smoothness = max_fwd_max = max_lat_max = max_friction = 0
        avg_lean = max_lean = 0

    # Aggressive event total
    aggressive_events = sum(
        event_counts.get(e, 0) for e in ["hard_brake", "hard_accel", "swerve", "aggressive_corner"]
    )

    # Event density
    events_per_km = aggressive_events / total_dist_km if total_dist_km > 0 else 0
    events_per_min = aggressive_events / (total_time_s / 60.0) if total_time_s > 0 else 0

    # Deep metrics averages
    jerk_vals = [fx.get("fwd_jerk_rms") for fx in fixes if fx.get("fwd_jerk_rms") is not None]
    weave_vals = [fx.get("weaving_index") for fx in fixes if fx.get("weaving_index") is not None]
    reaction_vals = [fx.get("reaction_time_ms") for fx in fixes if fx.get("reaction_time_ms") is not None]

    print("\n" + "=" * 70)
    print("DRIVER METRICS SUMMARY")
    print("=" * 70)
    print(f"  Duration:          {total_time_s:.0f}s ({total_time_s/60:.1f} min)")
    print(f"  Distance:          {total_dist_km:.2f} km")
    print(f"  Total fixes:       {len(fixes)} ({len(moving_fixes)} moving)")

    print(f"\n--- Event Counts ---")
    for evt in ["hard_brake", "hard_accel", "swerve", "aggressive_corner", "normal", "low_speed"]:
        cnt = event_counts.get(evt, 0)
        pct = 100.0 * cnt / len(fixes) if fixes else 0
        print(f"  {evt:22s}: {cnt:5d} ({pct:.1f}%)")

    print(f"\n--- Aggregate Metrics (moving fixes only) ---")
    print(f"  Avg fwdRms:        {avg_fwd_rms:.3f} m/s²")
    print(f"  Avg fwdMax:        {avg_fwd_max:.3f} m/s²  (peak: {max_fwd_max:.3f})")
    print(f"  Avg latRms:        {avg_lat_rms:.3f} m/s²")
    print(f"  Avg latMax:        {avg_lat_max:.3f} m/s²  (peak: {max_lat_max:.3f})")
    print(f"  Max friction circle: {max_friction:.3f} m/s²")
    print(f"  Avg lean angle:    {avg_lean:.1f}°  (max: {max_lean:.1f}°)")

    print(f"\n--- Driver Score ---")
    print(f"  Smoothness Score:  {avg_smoothness:.1f} / 100  (higher = smoother)")
    print(f"  Aggressive Events: {aggressive_events}")
    print(f"  Event Density:     {events_per_km:.1f} events/km, {events_per_min:.2f} events/min")

    if jerk_vals:
        print(f"\n--- Raw Data Deep Metrics ---")
        print(f"  Avg Fwd Jerk RMS:  {sum(jerk_vals)/len(jerk_vals):.1f} m/s³  (from signed RMS)")
    if weave_vals:
        print(f"  Avg Weaving Index:  {sum(weave_vals)/len(weave_vals):.1f} sign changes/fix")
    if reaction_vals:
        print(f"  Avg Reaction Time: {sum(reaction_vals)/len(reaction_vals):.0f} ms  (n={len(reaction_vals)})")

    print("=" * 70)


if __name__ == "__main__":
    main()
