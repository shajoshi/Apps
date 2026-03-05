#!/usr/bin/env python3
"""
Convert SJGpsUtil KML track files to the app's JSON format.

Parses the KML exported by SJGpsUtil (with ExtendedData for recording settings
and per-point accel metrics) and produces a JSON file matching the structure
written by JsonWriter.kt.

Usage:
    python kml_to_json.py <track.kml> [--output <output.json>]

If --output is not specified, writes to <track>.json alongside the input file.
"""

import argparse
import json
import os
import sys
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


KML_NS = "http://www.opengis.net/kml/2.2"
GX_NS = "http://www.google.com/kml/ext/2.2"


def ns(tag, namespace=KML_NS):
    return f"{{{namespace}}}{tag}"


def get_extended_data(element):
    """Extract ExtendedData name/value pairs from a KML element into a dict."""
    data = {}
    ed = element.find(ns("ExtendedData"))
    if ed is None:
        return data
    for d in ed.findall(ns("Data")):
        name = d.get("name", "")
        val_el = d.find(ns("value"))
        val = val_el.text if val_el is not None and val_el.text else ""
        data[name] = val
    return data


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
        # Handle various ISO formats
        ts_str = ts_str.strip()
        if ts_str.endswith("Z"):
            ts_str = ts_str[:-1] + "+00:00"
        dt = datetime.fromisoformat(ts_str)
        return int(dt.timestamp() * 1000)
    except (ValueError, TypeError):
        return 0


def build_recording_settings(doc_data):
    """Build recordingSettings JSON structure from document-level ExtendedData."""
    if not doc_data:
        return None

    # Check if we have any calibration data
    has_settings = any(k.startswith("calibration.") or k.startswith("driverThresholds.") or
                       k in ("intervalSeconds", "enableAccelerometer") for k in doc_data)
    if not has_settings:
        return None

    calibration = {
        "rmsSmoothMax": parse_float(doc_data.get("calibration.rmsSmoothMax"), 1.0),
        "peakThresholdZ": parse_float(doc_data.get("calibration.peakThresholdZ"), 1.5),
        "movingAverageWindow": parse_int(doc_data.get("calibration.movingAverageWindow"), 5),
        "stdDevSmoothMax": parse_float(doc_data.get("calibration.stdDevSmoothMax"), 2.5),
        "rmsRoughMin": parse_float(doc_data.get("calibration.rmsRoughMin"), 4.5),
        "peakRatioRoughMin": parse_float(doc_data.get("calibration.peakRatioRoughMin"), 0.6),
        "stdDevRoughMin": parse_float(doc_data.get("calibration.stdDevRoughMin"), 3.0),
        "magMaxSevereMin": parse_float(doc_data.get("calibration.magMaxSevereMin"), 20.0),
    }

    # Gravity vector
    gx = parse_float(doc_data.get("calibration.baseGravityVectorX"))
    gy = parse_float(doc_data.get("calibration.baseGravityVectorY"))
    gz = parse_float(doc_data.get("calibration.baseGravityVectorZ"))
    if gx is not None and gy is not None and gz is not None:
        calibration["baseGravityVector"] = {"x": gx, "y": gy, "z": gz}

    driver_thresholds = {
        "hardBrakeFwdMax": parse_float(doc_data.get("driverThresholds.hardBrakeFwdMax"), 15.0),
        "hardAccelFwdMax": parse_float(doc_data.get("driverThresholds.hardAccelFwdMax"), 15.0),
        "swerveLatMax": parse_float(doc_data.get("driverThresholds.swerveLatMax"), 4.0),
        "aggressiveCornerLatMax": parse_float(doc_data.get("driverThresholds.aggressiveCornerLatMax"), 4.0),
        "aggressiveCornerDCourse": parse_float(doc_data.get("driverThresholds.aggressiveCornerDCourse"), 15.0),
        "minSpeedKmph": parse_float(doc_data.get("driverThresholds.minSpeedKmph"), 6.0),
        "movingAvgWindow": parse_int(doc_data.get("driverThresholds.movingAvgWindow"), 10),
        "reactionTimeBrakeMax": parse_float(doc_data.get("driverThresholds.reactionTimeBrakeMax"), 15.0),
        "reactionTimeLatMax": parse_float(doc_data.get("driverThresholds.reactionTimeLatMax"), 15.0),
        "smoothnessRmsMax": parse_float(doc_data.get("driverThresholds.smoothnessRmsMax"), 10.0),
        "fallLeanAngle": parse_float(doc_data.get("driverThresholds.fallLeanAngle"), 40.0),
    }

    settings = {
        "intervalSeconds": parse_int(doc_data.get("intervalSeconds"), 1),
        "disablePointFiltering": parse_bool(doc_data.get("disablePointFiltering")),
        "enableAccelerometer": parse_bool(doc_data.get("enableAccelerometer"), True),
        "roadCalibrationMode": parse_bool(doc_data.get("roadCalibrationMode")),
        "outputFormat": doc_data.get("outputFormat", "KML"),
        "profileName": doc_data.get("profileName") or None,
        "calibration": calibration,
        "driverThresholds": driver_thresholds,
    }
    return settings


def build_data_point(placemark_data, coords, ts_millis, start_millis):
    """Build a single data[] entry from a Placemark's ExtendedData and coordinates."""
    lon, lat, alt = coords

    ts_offset = max(0, ts_millis - start_millis)
    speed = parse_float(placemark_data.get("speedKmph"), 0)
    course = parse_float(placemark_data.get("bearingDegrees"), 0)
    acc = parse_float(placemark_data.get("accuracyMeters"), 0)

    point = {
        "gps": {
            "ts": ts_offset,
            "lat": lat,
            "lon": lon,
            "sats": 0,
            "acc": acc,
            "course": course,
            "speed": speed,
            "climbPPM": 0,
            "climb": 0,
            "salt": alt,
            "alt": alt,
        }
    }

    # Accel metrics (only if present)
    x_mean = parse_float(placemark_data.get("accelXMean"))
    if x_mean is not None:
        accel = {
            "xMean": x_mean,
            "yMean": parse_float(placemark_data.get("accelYMean"), 0),
            "zMean": parse_float(placemark_data.get("accelZMean"), 0),
            "magMax": parse_float(placemark_data.get("accelMagnitudeMax"), 0),
            "rms": parse_float(placemark_data.get("accelRMS"), 0),
        }

        vert_mean = parse_float(placemark_data.get("accelVertMean"))
        if vert_mean is not None:
            accel["vertMean"] = vert_mean

        rq = placemark_data.get("roadQuality")
        if rq:
            accel["roadQuality"] = rq

        feat = placemark_data.get("featureDetected")
        if feat:
            accel["featureDetected"] = feat

        peak_ratio = parse_float(placemark_data.get("peakRatio"))
        if peak_ratio is not None:
            accel["peakRatio"] = peak_ratio

        std_dev = parse_float(placemark_data.get("stdDev"))
        if std_dev is not None:
            accel["stdDev"] = std_dev

        avg_rms = parse_float(placemark_data.get("avgRms"))
        if avg_rms is not None:
            accel["avgRms"] = avg_rms

        avg_max_mag = parse_float(placemark_data.get("avgMaxMagnitude"))
        if avg_max_mag is not None:
            accel["avgMaxMagnitude"] = avg_max_mag

        avg_mean_mag = parse_float(placemark_data.get("avgMeanMagnitude"))
        if avg_mean_mag is not None:
            accel["avgMeanMagnitude"] = avg_mean_mag

        avg_std_dev = parse_float(placemark_data.get("avgStdDev"))
        if avg_std_dev is not None:
            accel["avgStdDev"] = avg_std_dev

        avg_peak_ratio = parse_float(placemark_data.get("avgPeakRatio"))
        if avg_peak_ratio is not None:
            accel["avgPeakRatio"] = avg_peak_ratio

        fwd_rms = parse_float(placemark_data.get("fwdRms"))
        if fwd_rms is not None:
            accel["fwdRms"] = fwd_rms

        fwd_max = parse_float(placemark_data.get("fwdMax"))
        if fwd_max is not None:
            accel["fwdMax"] = fwd_max

        lat_rms = parse_float(placemark_data.get("latRms"))
        if lat_rms is not None:
            accel["latRms"] = lat_rms

        lat_max = parse_float(placemark_data.get("latMax"))
        if lat_max is not None:
            accel["latMax"] = lat_max

        # Road quality color mapping
        if rq == "smooth":
            accel["styleId"] = "smoothStyle"
            accel["color"] = "#00FF00"
        elif rq == "average":
            accel["styleId"] = "averageStyle"
            accel["color"] = "#FFA500"
        elif rq == "rough":
            accel["styleId"] = "roughStyle"
            accel["color"] = "#FF0000"

        point["accel"] = accel

    return point


def parse_coordinates(coord_text):
    """Parse 'lon,lat,alt' coordinate string."""
    parts = coord_text.strip().split(",")
    if len(parts) >= 3:
        return float(parts[0]), float(parts[1]), float(parts[2])
    elif len(parts) == 2:
        return float(parts[0]), float(parts[1]), 0.0
    return 0.0, 0.0, 0.0


def main():
    parser = argparse.ArgumentParser(
        description="Convert SJGpsUtil KML track to JSON format."
    )
    parser.add_argument("kml", help="Path to SJGpsUtil KML track file")
    parser.add_argument("--output", "-o", help="Output JSON file path (default: <kml>.json)")
    args = parser.parse_args()

    if not os.path.exists(args.kml):
        print(f"Error: File not found: {args.kml}")
        sys.exit(1)

    output_path = args.output or os.path.splitext(args.kml)[0] + ".json"

    # Parse KML
    try:
        tree = ET.parse(args.kml)
    except ET.ParseError as e:
        print(f"Error parsing KML: {e}")
        sys.exit(1)

    root = tree.getroot()
    doc = root.find(ns("Document"))
    if doc is None:
        print("Error: No <Document> element found in KML.")
        sys.exit(1)

    # Extract document-level recording settings
    doc_data = get_extended_data(doc)
    recording_settings = build_recording_settings(doc_data)

    # Find all Placemarks with Point coordinates (data points, not track line)
    placemarks = []
    for pm in doc.findall(ns("Placemark")):
        point_el = pm.find(ns("Point"))
        if point_el is None:
            continue
        coord_el = point_el.find(ns("coordinates"))
        if coord_el is None or not coord_el.text:
            continue

        coords = parse_coordinates(coord_el.text)
        pm_data = get_extended_data(pm)

        # Get timestamp
        ts_el = pm.find(f"{ns('TimeStamp')}/{ns('when')}")
        ts_str = ts_el.text if ts_el is not None else pm_data.get("timestamp", "")
        ts_millis = parse_timestamp_to_millis(ts_str)

        placemarks.append((ts_millis, coords, pm_data, ts_str))

    if not placemarks:
        print("Error: No data point Placemarks found in KML.")
        sys.exit(1)

    # Sort by timestamp
    placemarks.sort(key=lambda x: x[0])

    start_millis = placemarks[0][0]
    first_ts_str = placemarks[0][3]

    # Build meta
    file_uuid = str(uuid.uuid4())
    name = os.path.splitext(os.path.basename(args.kml))[0]

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

    # Build data points
    data = []
    for ts_millis, coords, pm_data, _ in placemarks:
        point = build_data_point(pm_data, coords, ts_millis, start_millis)
        data.append(point)

    # Build final JSON structure
    output = {
        "gpslogger2path": {
            "meta": meta,
            "data": data,
        }
    }

    # Write JSON
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output, f, indent=2)

    print(f"Converted {len(data)} points from KML to JSON")
    print(f"  Input:  {args.kml}")
    print(f"  Output: {output_path}")
    if recording_settings:
        print(f"  Profile: {recording_settings.get('profileName', 'N/A')}")
        print(f"  Accel enabled: {recording_settings.get('enableAccelerometer', False)}")


if __name__ == "__main__":
    main()
