#!/usr/bin/env python3
"""
Convert SJGpsUtil GPX track files to the app's JSON format.

Parses the GPX exported by SJGpsUtil (with sj: namespace extensions for
recording settings and per-point accel metrics) and produces a JSON file
matching the structure written by JsonWriter.kt.

Usage:
    python gpx_to_json.py <track.gpx> [--output <output.json>]

If --output is not specified, writes to <track>.json alongside the input file.
"""

import argparse
import json
import os
import sys
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


GPX_NS = "http://www.topografix.com/GPX/1/1"
SJ_NS = "http://sj.gpsutil"


def ns(tag, namespace=GPX_NS):
    return f"{{{namespace}}}{tag}"


def sj(tag):
    return f"{{{SJ_NS}}}{tag}"


def get_text(element, tag, namespace=GPX_NS, default=None):
    """Get text content of a child element."""
    el = element.find(f"{{{namespace}}}{tag}")
    if el is not None and el.text:
        return el.text.strip()
    return default


def get_sj_text(element, tag, default=None):
    return get_text(element, tag, SJ_NS, default)


def parse_float(val, default=None):
    try:
        return float(val)
    except (ValueError, TypeError):
        return default


def parse_int(val, default=None):
    try:
        return int(val)
    except (ValueError, TypeError):
        return default


def parse_bool(val, default=False):
    if isinstance(val, str):
        return val.lower() == "true"
    return default


def parse_timestamp_to_millis(ts_str):
    """Parse ISO timestamp string to epoch millis."""
    if not ts_str:
        return 0
    try:
        ts_str = ts_str.strip()
        if ts_str.endswith("Z"):
            ts_str = ts_str[:-1] + "+00:00"
        dt = datetime.fromisoformat(ts_str)
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError):
        return 0


def build_recording_settings(metadata_el):
    """Build recordingSettings from GPX <metadata><extensions><sj:recordingSettings>."""
    if metadata_el is None:
        return None

    ext = metadata_el.find(ns("extensions"))
    if ext is None:
        return None

    rs = ext.find(sj("recordingSettings"))
    if rs is None:
        return None

    # Calibration
    cal_el = rs.find(sj("calibration"))
    calibration = {}
    if cal_el is not None:
        calibration = {
            "rmsSmoothMax": parse_float(get_sj_text(cal_el, "rmsSmoothMax"), 1.0),
            "peakThresholdZ": parse_float(get_sj_text(cal_el, "peakThresholdZ"), 1.5),
            "movingAverageWindow": parse_int(get_sj_text(cal_el, "movingAverageWindow"), 5),
            "stdDevSmoothMax": parse_float(get_sj_text(cal_el, "stdDevSmoothMax"), 2.5),
            "rmsRoughMin": parse_float(get_sj_text(cal_el, "rmsRoughMin"), 4.5),
            "peakRatioRoughMin": parse_float(get_sj_text(cal_el, "peakRatioRoughMin"), 0.6),
            "stdDevRoughMin": parse_float(get_sj_text(cal_el, "stdDevRoughMin"), 3.0),
            "magMaxSevereMin": parse_float(get_sj_text(cal_el, "magMaxSevereMin"), 20.0),
        }
        gx = parse_float(get_sj_text(cal_el, "baseGravityVectorX"))
        gy = parse_float(get_sj_text(cal_el, "baseGravityVectorY"))
        gz = parse_float(get_sj_text(cal_el, "baseGravityVectorZ"))
        if gx is not None and gy is not None and gz is not None:
            calibration["baseGravityVector"] = {"x": gx, "y": gy, "z": gz}

    # Driver thresholds
    dt_el = rs.find(sj("driverThresholds"))
    driver_thresholds = {}
    if dt_el is not None:
        driver_thresholds = {
            "hardBrakeFwdMax": parse_float(get_sj_text(dt_el, "hardBrakeFwdMax"), 15.0),
            "hardAccelFwdMax": parse_float(get_sj_text(dt_el, "hardAccelFwdMax"), 15.0),
            "swerveLatMax": parse_float(get_sj_text(dt_el, "swerveLatMax"), 4.0),
            "aggressiveCornerLatMax": parse_float(get_sj_text(dt_el, "aggressiveCornerLatMax"), 4.0),
            "aggressiveCornerDCourse": parse_float(get_sj_text(dt_el, "aggressiveCornerDCourse"), 15.0),
            "minSpeedKmph": parse_float(get_sj_text(dt_el, "minSpeedKmph"), 6.0),
            "movingAvgWindow": parse_int(get_sj_text(dt_el, "movingAvgWindow"), 10),
            "reactionTimeBrakeMax": parse_float(get_sj_text(dt_el, "reactionTimeBrakeMax"), 15.0),
            "reactionTimeLatMax": parse_float(get_sj_text(dt_el, "reactionTimeLatMax"), 15.0),
            "smoothnessRmsMax": parse_float(get_sj_text(dt_el, "smoothnessRmsMax"), 10.0),
            "fallLeanAngle": parse_float(get_sj_text(dt_el, "fallLeanAngle"), 40.0),
        }

    settings = {
        "intervalSeconds": parse_int(get_sj_text(rs, "intervalSeconds"), 1),
        "disablePointFiltering": parse_bool(get_sj_text(rs, "disablePointFiltering")),
        "enableAccelerometer": parse_bool(get_sj_text(rs, "enableAccelerometer"), True),
        "roadCalibrationMode": parse_bool(get_sj_text(rs, "roadCalibrationMode")),
        "outputFormat": get_sj_text(rs, "outputFormat") or "GPX",
        "profileName": get_sj_text(rs, "profileName") or None,
        "calibration": calibration,
        "driverThresholds": driver_thresholds,
    }
    return settings


def build_data_point(trkpt, start_millis):
    """Build a single data[] entry from a <trkpt> element."""
    lat = parse_float(trkpt.get("lat"), 0)
    lon = parse_float(trkpt.get("lon"), 0)
    ele = parse_float(get_text(trkpt, "ele"), 0)

    time_str = get_text(trkpt, "time") or ""
    ts_millis = parse_timestamp_to_millis(time_str)
    ts_offset = max(0, ts_millis - start_millis)

    point = {
        "gps": {
            "ts": ts_offset,
            "lat": lat,
            "lon": lon,
            "sats": 0,
            "acc": 0,
            "course": 0,
            "speed": 0,
            "climbPPM": 0,
            "climb": 0,
            "salt": ele,
            "alt": ele,
        }
    }

    # Parse sj:accel extensions
    ext = trkpt.find(ns("extensions"))
    if ext is not None:
        accel_el = ext.find(sj("accel"))
        if accel_el is not None:
            x_mean = parse_float(get_sj_text(accel_el, "xMean"))
            if x_mean is not None:
                accel = {
                    "xMean": x_mean,
                    "yMean": parse_float(get_sj_text(accel_el, "yMean"), 0),
                    "zMean": parse_float(get_sj_text(accel_el, "zMean"), 0),
                    "magMax": parse_float(get_sj_text(accel_el, "magMax"), 0),
                    "rms": parse_float(get_sj_text(accel_el, "rms"), 0),
                }

                vert_mean = parse_float(get_sj_text(accel_el, "vertMean"))
                if vert_mean is not None:
                    accel["vertMean"] = vert_mean

                rq = get_sj_text(accel_el, "roadQuality")
                if rq:
                    accel["roadQuality"] = rq

                feat = get_sj_text(accel_el, "featureDetected")
                if feat:
                    accel["featureDetected"] = feat

                for field in ["peakRatio", "stdDev", "avgRms", "avgMaxMagnitude",
                              "avgMeanMagnitude", "avgStdDev", "avgPeakRatio",
                              "fwdRms", "fwdMax", "latRms", "latMax"]:
                    val = parse_float(get_sj_text(accel_el, field))
                    if val is not None:
                        accel[field] = val

                style_id = get_sj_text(accel_el, "styleId")
                if style_id:
                    accel["styleId"] = style_id

                color = get_sj_text(accel_el, "color")
                if color:
                    accel["color"] = color

                point["accel"] = accel

    return point, ts_millis, time_str


def main():
    parser = argparse.ArgumentParser(
        description="Convert SJGpsUtil GPX track to JSON format."
    )
    parser.add_argument("gpx", help="Path to SJGpsUtil GPX track file")
    parser.add_argument("--output", "-o", help="Output JSON file path (default: <gpx>.json)")
    args = parser.parse_args()

    if not os.path.exists(args.gpx):
        print(f"Error: File not found: {args.gpx}")
        sys.exit(1)

    output_path = args.output or os.path.splitext(args.gpx)[0] + ".json"

    # Parse GPX
    try:
        tree = ET.parse(args.gpx)
    except ET.ParseError as e:
        print(f"Error parsing GPX: {e}")
        sys.exit(1)

    root = tree.getroot()

    # Extract recording settings from <metadata>
    metadata = root.find(ns("metadata"))
    recording_settings = build_recording_settings(metadata)

    # Find all <trkpt> elements
    trkpts = []
    for trk in root.findall(ns("trk")):
        for trkseg in trk.findall(ns("trkseg")):
            for trkpt in trkseg.findall(ns("trkpt")):
                trkpts.append(trkpt)

    if not trkpts:
        print("Error: No track points found in GPX.")
        sys.exit(1)

    # First pass: get all timestamps to find start
    points_raw = []
    for trkpt in trkpts:
        time_str = get_text(trkpt, "time") or ""
        ts_millis = parse_timestamp_to_millis(time_str)
        points_raw.append((trkpt, ts_millis, time_str))

    # Sort by timestamp
    points_raw.sort(key=lambda x: x[1])
    start_millis = points_raw[0][1]
    first_ts_str = points_raw[0][2]

    # Build data points
    data = []
    for trkpt, ts_millis, time_str in points_raw:
        point, _, _ = build_data_point(trkpt, start_millis)
        data.append(point)

    # Build meta
    file_uuid = str(uuid.uuid4())
    name = os.path.splitext(os.path.basename(args.gpx))[0]

    meta = {
        "roundtrip": False,
        "imported": True,
        "commute": False,
        "uuid": file_uuid,
        "name": name,
        "utctime": first_ts_str,
        "localtime": first_ts_str,
        "timezoneoffset": 0,
        "ts": start_millis,
    }

    if recording_settings:
        meta["recordingSettings"] = recording_settings

    # Check for summary distance in trk extensions
    for trk in root.findall(ns("trk")):
        trk_ext = trk.find(ns("extensions"))
        if trk_ext is not None:
            dist_m = parse_float(get_sj_text(trk_ext, "totalDistanceMeters"))
            dist_km = parse_float(get_sj_text(trk_ext, "totalDistanceKm"))
            if dist_m is not None or dist_km is not None:
                summary = {}
                if dist_m is not None:
                    summary["totalDistanceMeters"] = dist_m
                if dist_km is not None:
                    summary["totalDistanceKm"] = dist_km
                break

    # Build final JSON structure
    output = {
        "gpslogger2path": {
            "meta": meta,
            "data": data,
        }
    }

    # Add summary if found
    if 'summary' in dir() or 'summary' in locals():
        if summary:
            output["gpslogger2path"]["summary"] = summary

    # Write JSON
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2)

    print(f"Converted {len(data)} track points from GPX to JSON")
    print(f"  Input:  {args.gpx}")
    print(f"  Output: {output_path}")
    if recording_settings:
        print(f"  Profile: {recording_settings.get('profileName', 'N/A')}")
        print(f"  Accel enabled: {recording_settings.get('enableAccelerometer', False)}")


if __name__ == "__main__":
    main()
