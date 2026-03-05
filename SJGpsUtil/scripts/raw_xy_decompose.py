#!/usr/bin/env python3
"""
Decompose raw accelerometer samples into horizontal X (lateral) and Y (forward)
components using the gravity vector, and output per-GPS-fix summary to CSV.

For each GPS fix interval, processes the accel.raw[] samples:
  1. Computes vehicle-frame basis from baseGravityVector (same as app)
  2. Removes gravity bias (detrend)
  3. Projects each raw sample onto forward (Y) and lateral (X) axes
  4. Outputs: mean_fwd, mean_lat, lean_angle (degrees, +right/-left)

Usage:
    python raw_xy_decompose.py <track.json>

Output:
    <track>_raw_xy.csv
"""

import json
import math
import os
import sys
import csv
from typing import List, Optional


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
# Extract helpers
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


def extract_data_points(track_data: dict) -> list:
    gps = track_data.get("gpslogger2path", track_data)
    if "data" in gps:
        return gps["data"]
    if "data" in track_data:
        return track_data["data"]
    if isinstance(track_data, list):
        return track_data
    return []


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    if len(sys.argv) != 2:
        print("Usage: python raw_xy_decompose.py <track.json>")
        sys.exit(1)

    track_path = sys.argv[1]

    try:
        with open(track_path, "r") as f:
            track_data = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error loading '{track_path}': {e}")
        sys.exit(1)

    # Extract gravity vector and compute vehicle basis
    base_gravity = extract_base_gravity_vector(track_data)
    if not base_gravity:
        print("ERROR: No baseGravityVector found in recording metadata.")
        sys.exit(1)

    g_mag = math.sqrt(sum(c * c for c in base_gravity))
    g_unit, fwd_unit, lat_unit = compute_vehicle_basis(base_gravity)

    if not g_unit or not fwd_unit or not lat_unit:
        print("ERROR: Could not compute vehicle basis from gravity vector.")
        sys.exit(1)

    print(f"Gravity vector: [{base_gravity[0]:.3f}, {base_gravity[1]:.3f}, {base_gravity[2]:.3f}] |g|={g_mag:.3f}")
    print(f"Vehicle basis:")
    print(f"  g_unit = [{g_unit[0]:.4f}, {g_unit[1]:.4f}, {g_unit[2]:.4f}]")
    print(f"  fwd    = [{fwd_unit[0]:.4f}, {fwd_unit[1]:.4f}, {fwd_unit[2]:.4f}]")
    print(f"  lat    = [{lat_unit[0]:.4f}, {lat_unit[1]:.4f}, {lat_unit[2]:.4f}]")

    points = extract_data_points(track_data)
    print(f"Total data points: {len(points)}")

    # Process each GPS fix
    rows = []
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

        accel = point.get("accel", {})
        raw_data = accel.get("raw", [])

        if not raw_data or lat is None or lon is None:
            skipped += 1
            continue

        n = len(raw_data)

        # Step 1: Compute bias (mean of raw samples ≈ gravity vector for this window)
        bias = [sum(s[j] for s in raw_data) / n for j in range(3)]

        # Step 2: Detrend — remove gravity bias
        detrended = [[s[j] - bias[j] for j in range(3)] for s in raw_data]

        # Step 3: Project each detrended sample onto fwd and lat axes
        fwd_vals = []
        lat_vals = []
        for d in detrended:
            a_fwd = d[0] * fwd_unit[0] + d[1] * fwd_unit[1] + d[2] * fwd_unit[2]
            a_lat = d[0] * lat_unit[0] + d[1] * lat_unit[1] + d[2] * lat_unit[2]
            fwd_vals.append(a_fwd)
            lat_vals.append(a_lat)

        # Step 4: Compute motorcycle lean angle
        # The per-window bias (mean of raw samples) approximates the gravity
        # direction during this interval. Lean angle = how much the gravity
        # vector has rotated away from the baseline, measured along the
        # lateral axis.  atan2(lateral_component, vertical_component) gives
        # the signed lean: positive = leaning right, negative = leaning left.
        window_g_mag = math.sqrt(sum(b * b for b in bias))
        if window_g_mag > 1e-3:
            wg = [b / window_g_mag for b in bias]
            # Project window gravity unit vector onto baseline lateral axis
            lat_component = wg[0] * lat_unit[0] + wg[1] * lat_unit[1] + wg[2] * lat_unit[2]
            # Project onto baseline vertical (gravity) axis
            vert_component = wg[0] * g_unit[0] + wg[1] * g_unit[1] + wg[2] * g_unit[2]
            lean_angle_deg = math.degrees(math.atan2(lat_component, vert_component))
        else:
            lean_angle_deg = 0.0

        # Step 5: Compute means
        mean_fwd = sum(fwd_vals) / len(fwd_vals)
        mean_lat = sum(lat_vals) / len(lat_vals)

        # Compute RMS and apply sign of mean to indicate dominant direction
        fwd_rms_unsigned = math.sqrt(sum(v * v for v in fwd_vals) / len(fwd_vals))
        lat_rms_unsigned = math.sqrt(sum(v * v for v in lat_vals) / len(lat_vals))
        fwd_rms = math.copysign(fwd_rms_unsigned, mean_fwd) if mean_fwd != 0 else fwd_rms_unsigned
        lat_rms = math.copysign(lat_rms_unsigned, mean_lat) if mean_lat != 0 else lat_rms_unsigned
        fwd_max = max(abs(v) for v in fwd_vals)
        lat_max = max(abs(v) for v in lat_vals)

        rows.append({
            "fix_index": i + 1,
            "ts_offset_ms": ts,
            "lat": lat,
            "lon": lon,
            "speed_kmph": speed,
            "course": course,
            "raw_samples": n,
            "mean_fwd": mean_fwd,
            "mean_lat": mean_lat,
            "fwd_rms": fwd_rms,
            "lat_rms": lat_rms,
            "fwd_max": fwd_max,
            "lat_max": lat_max,
            "lean_angle_deg": lean_angle_deg,
        })

    # Write CSV
    output_base = os.path.splitext(track_path)[0]
    csv_path = f"{output_base}_raw_xy.csv"

    headers = [
        "fix_index", "ts_offset_ms", "lat", "lon", "speed_kmph", "course",
        "raw_samples", "mean_fwd", "mean_lat", "fwd_rms", "lat_rms",
        "fwd_max", "lat_max", "lean_angle_deg",
    ]

    with open(csv_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            # Round floats for readability
            for k in headers:
                v = row.get(k)
                if isinstance(v, float):
                    row[k] = f"{v:.4f}"
            writer.writerow(row)

    print(f"\nProcessed {len(rows)} fixes ({skipped} skipped — no raw data or GPS)")
    print(f"CSV output: {csv_path}")

    # Print quick summary
    if rows:
        # Parse back to float for summary stats
        fwd_means = [float(r["mean_fwd"]) for r in rows]
        lat_means = [float(r["mean_lat"]) for r in rows]
        leans = [float(r["lean_angle_deg"]) for r in rows]
        fwd_maxes = [float(r["fwd_max"]) for r in rows]
        lat_maxes = [float(r["lat_max"]) for r in rows]

        print(f"\n--- Summary ---")
        print(f"  Mean fwd accel:   avg={sum(fwd_means)/len(fwd_means):.4f}  min={min(fwd_means):.4f}  max={max(fwd_means):.4f}")
        print(f"  Mean lat accel:   avg={sum(lat_means)/len(lat_means):.4f}  min={min(lat_means):.4f}  max={max(lat_means):.4f}")
        print(f"  Peak fwd (|max|): avg={sum(fwd_maxes)/len(fwd_maxes):.3f}  max={max(fwd_maxes):.3f}")
        print(f"  Peak lat (|max|): avg={sum(lat_maxes)/len(lat_maxes):.3f}  max={max(lat_maxes):.3f}")
        print(f"  Lean angle:       avg={sum(leans)/len(leans):.1f}°  min={min(leans):.1f}°  max={max(leans):.1f}°")


if __name__ == "__main__":
    main()
