#!/usr/bin/env python3
"""
Convert SJGpsUtil JSON track files to KML format.

Produces a KML file matching the structure written by KmlWriter.kt, including
document-level ExtendedData for recording settings, per-point Placemarks with
accel metrics, road-quality styled points, and a track line.

Usage:
    python json_to_kml.py <track.json> [--output <output.kml>]

If --output is not specified, writes to <track>.kml alongside the input file.
"""

import argparse
import json
import os
import sys
import xml.sax.saxutils as saxutils
from datetime import datetime, timezone


def fmt(val, decimals=3):
    """Format a float to N decimal places, or return empty string if None."""
    if val is None:
        return ""
    return f"{val:.{decimals}f}"


def iso_from_millis(epoch_ms):
    """Convert epoch millis to ISO 8601 UTC string."""
    if not epoch_ms:
        return ""
    try:
        dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{int(epoch_ms % 1000):03d}Z"
    except (ValueError, OSError):
        return ""


def write_data(f, name, value):
    """Write a single <Data name="..."><value>...</value></Data> line."""
    f.write(f'<Data name="{saxutils.escape(name)}"><value>{saxutils.escape(str(value))}</value></Data>\n')


def main():
    parser = argparse.ArgumentParser(
        description="Convert SJGpsUtil JSON track to KML format."
    )
    parser.add_argument("json_file", help="Path to SJGpsUtil JSON track file")
    parser.add_argument("--output", "-o", help="Output KML file path (default: <json>.kml)")
    args = parser.parse_args()

    if not os.path.exists(args.json_file):
        print(f"Error: File not found: {args.json_file}")
        sys.exit(1)

    output_path = args.output or os.path.splitext(args.json_file)[0] + ".kml"

    # Load JSON
    try:
        with open(args.json_file, "r") as jf:
            track_data = json.load(jf)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"Error loading '{args.json_file}': {e}")
        sys.exit(1)

    gps_root = track_data.get("gpslogger2path", track_data)
    meta = gps_root.get("meta", {})
    data = gps_root.get("data", [])
    summary = gps_root.get("summary", {})
    rec_settings = meta.get("recordingSettings", {})
    cal = rec_settings.get("calibration", {})
    dt = rec_settings.get("driverThresholds", {})

    if not data:
        print("Error: No data points found in JSON.")
        sys.exit(1)

    # Resolve absolute timestamps: meta.ts is the start epoch millis
    start_ts = meta.get("ts", 0)

    with open(output_path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">\n')
        f.write('<Document>\n')
        f.write('<name>SJGpsUtil Track</name>\n')

        # --- Document-level ExtendedData (recording settings) ---
        if rec_settings:
            f.write('<ExtendedData>\n')
            for key in ["intervalSeconds", "disablePointFiltering", "enableAccelerometer",
                        "roadCalibrationMode", "outputFormat", "profileName"]:
                val = rec_settings.get(key, "")
                if val is None:
                    val = ""
                write_data(f, key, val)

            # Calibration
            for key in ["rmsSmoothMax", "peakThresholdZ", "stdDevSmoothMax", "rmsRoughMin",
                        "peakRatioRoughMin", "stdDevRoughMin", "magMaxSevereMin", "movingAverageWindow"]:
                val = cal.get(key, "")
                write_data(f, f"calibration.{key}", fmt(val) if isinstance(val, float) else str(val))

            # Gravity vector
            bgv = cal.get("baseGravityVector", {})
            if bgv:
                write_data(f, "calibration.baseGravityVectorX", fmt(bgv.get("x", 0)))
                write_data(f, "calibration.baseGravityVectorY", fmt(bgv.get("y", 0)))
                write_data(f, "calibration.baseGravityVectorZ", fmt(bgv.get("z", 0)))

            # Driver thresholds
            for key in ["hardBrakeFwdMax", "hardAccelFwdMax", "swerveLatMax",
                        "aggressiveCornerLatMax", "aggressiveCornerDCourse", "minSpeedKmph",
                        "movingAvgWindow", "reactionTimeBrakeMax", "reactionTimeLatMax",
                        "smoothnessRmsMax", "fallLeanAngle"]:
                val = dt.get(key, "")
                write_data(f, f"driverThresholds.{key}",
                           fmt(val) if isinstance(val, float) else str(val))

            f.write('</ExtendedData>\n')

        # --- Styles ---
        f.write('<Style id="smoothStyle"><IconStyle><color>ff00ff00</color>'
                '<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href>'
                '</Icon></IconStyle></Style>\n')
        f.write('<Style id="roughStyle"><IconStyle><color>ff0000ff</color>'
                '<Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href>'
                '</Icon></IconStyle></Style>\n')

        # --- Per-point Placemarks + track entries ---
        track_when = []
        track_coords = []
        line_coords = []

        for point in data:
            if isinstance(point, str):
                try:
                    point = json.loads(point)
                except json.JSONDecodeError:
                    continue

            gps = point.get("gps", {})
            lat = gps.get("lat")
            lon = gps.get("lon")
            if lat is None or lon is None:
                continue

            alt = gps.get("alt", 0) or 0
            ts_offset = gps.get("ts", 0) or 0
            abs_ts = start_ts + ts_offset
            timestamp = iso_from_millis(abs_ts)

            speed = gps.get("speed", 0) or 0
            bearing = gps.get("course", 0) or 0
            acc = gps.get("acc", 0) or 0

            accel = point.get("accel", {})
            feature = accel.get("featureDetected", "")
            road_quality = accel.get("roadQuality")

            # Track entries for gx:Track
            track_when.append(f"<when>{timestamp}</when>\n")
            track_coords.append(f"<gx:coord>{lon} {lat} {alt}</gx:coord>\n")
            line_coords.append(f"{lon},{lat},{alt}")

            # Placemark
            f.write('<Placemark>\n')
            f.write(f'<name>{saxutils.escape(feature)}</name>\n')

            style_id = None
            if road_quality == "smooth":
                style_id = "smoothStyle"
            elif road_quality == "rough":
                style_id = "roughStyle"
            if style_id:
                f.write(f'<styleUrl>#{style_id}</styleUrl>\n')

            f.write(f'<TimeStamp><when>{timestamp}</when></TimeStamp>\n')
            f.write('<ExtendedData>\n')
            write_data(f, "timestamp", timestamp)
            write_data(f, "speedKmph", fmt(speed, 2))
            write_data(f, "bearingDegrees", fmt(bearing, 1))
            write_data(f, "accuracyMeters", fmt(acc, 1))

            # Accel metrics
            x_mean = accel.get("xMean")
            if x_mean is not None:
                write_data(f, "accelXMean", fmt(x_mean))
                write_data(f, "accelYMean", fmt(accel.get("yMean", 0)))
                write_data(f, "accelZMean", fmt(accel.get("zMean", 0)))

                vert_mean = accel.get("vertMean")
                if vert_mean is not None:
                    write_data(f, "accelVertMean", fmt(vert_mean))

                write_data(f, "accelMagnitudeMax", fmt(accel.get("magMax", 0)))
                write_data(f, "accelRMS", fmt(accel.get("rms", 0)))

                if road_quality:
                    write_data(f, "roadQuality", road_quality)
                if feature:
                    write_data(f, "featureDetected", feature)

                for field in ["peakRatio", "stdDev", "avgRms", "avgMaxMagnitude",
                              "avgMeanMagnitude", "avgStdDev", "avgPeakRatio",
                              "fwdRms", "fwdMax", "latRms", "latMax"]:
                    val = accel.get(field)
                    if val is not None:
                        write_data(f, field, fmt(val))

            f.write('</ExtendedData>\n')
            f.write(f'<Point>\n<coordinates>{lon},{lat},{alt}</coordinates>\n</Point>\n')
            f.write('</Placemark>\n')

        # --- gx:Track + LineString ---
        f.write('<Placemark>\n')
        f.write('<name>Track</name>\n')
        f.write('<MultiGeometry>\n')
        f.write('<gx:Track>\n')
        for w in track_when:
            f.write(w)
        for c in track_coords:
            f.write(c)
        f.write('</gx:Track>\n')
        f.write('<LineString>\n')
        f.write('<tessellate>1</tessellate>\n')
        f.write(f'<coordinates>{" ".join(line_coords)}</coordinates>\n')
        f.write('</LineString>\n')
        f.write('</MultiGeometry>\n')
        f.write('</Placemark>\n')

        # --- Summary ---
        if summary:
            f.write('<Placemark>\n')
            f.write('<name>Summary</name>\n')
            f.write('<ExtendedData>\n')
            dist_m = summary.get("totalDistanceMeters")
            dist_km = summary.get("totalDistanceKm")
            if dist_m is not None:
                write_data(f, "totalDistanceMeters", fmt(dist_m, 1))
            if dist_km is not None:
                write_data(f, "totalDistanceKm", fmt(dist_km, 3))
            f.write('</ExtendedData>\n')
            f.write('</Placemark>\n')

        f.write('</Document>\n')
        f.write('</kml>\n')

    point_count = len(line_coords)
    print(f"Converted {point_count} points from JSON to KML")
    print(f"  Input:  {args.json_file}")
    print(f"  Output: {output_path}")


if __name__ == "__main__":
    main()
