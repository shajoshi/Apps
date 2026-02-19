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
        # Road quality line styles (colored path)
        f.write('<Style id="smoothLineStyle"><LineStyle><color>ff00ff00</color><width>4</width></LineStyle></Style>\n')
        f.write('<Style id="averageLineStyle"><LineStyle><color>ff00aaff</color><width>4</width></LineStyle></Style>\n')
        f.write('<Style id="roughLineStyle"><LineStyle><color>ff0000cc</color><width>4</width></LineStyle></Style>\n')
        
        # Feature point styles (only for bumps/potholes)
        f.write('<Style id="bumpStyle"><IconStyle><color>ffffff00</color><scale>0.8</scale>'
                '<Icon><href>http://maps.google.com/mapfiles/kml/shapes/triangle.png</href>'
                '</Icon></IconStyle></Style>\n')
        f.write('<Style id="potholeStyle"><IconStyle><color>ff8b4513</color><scale>0.8</scale>'
                '<Icon><href>http://maps.google.com/mapfiles/kml/shapes/diamond.png</href>'
                '</Icon></IconStyle></Style>\n')

        # --- Process points and collect line segments ---
        track_when = []
        track_coords = []
        line_segments = []
        feature_points = []
        
        current_segment = []
        current_quality = None

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
            
            # Collect line segments by road quality
            coord_str = f"{lon},{lat},{alt}"
            if road_quality != current_quality:
                # End current segment if it exists
                if current_segment and current_quality:
                    line_segments.append({
                        "quality": current_quality,
                        "coords": current_segment.copy()
                    })
                # Start new segment
                current_segment = [coord_str]
                current_quality = road_quality
            else:
                current_segment.append(coord_str)
            
            # Only create placemarks for road features (bumps/potholes)
            if feature:
                feature_points.append({
                    "feature": feature,
                    "lat": lat,
                    "lon": lon,
                    "alt": alt,
                    "timestamp": timestamp,
                    "speed": speed,
                    "bearing": bearing,
                    "accel": accel
                })

        # Add the last segment
        if current_segment and current_quality:
            line_segments.append({
                "quality": current_quality,
                "coords": current_segment
            })
        
        # --- Write road quality line segments ---
        for segment in line_segments:
            quality = segment["quality"]
            coords = " ".join(segment["coords"])
            
            style_id = "averageLineStyle"  # default
            if quality == "smooth":
                style_id = "smoothLineStyle"
            elif quality == "rough":
                style_id = "roughLineStyle"
            
            f.write('<Placemark>\n')
            f.write(f'<name>Road Quality: {quality}</name>\n')
            f.write(f'<styleUrl>#{style_id}</styleUrl>\n')
            f.write('<LineString>\n')
            f.write('<tessellate>1</tessellate>\n')
            f.write(f'<coordinates>{coords}</coordinates>\n')
            f.write('</LineString>\n')
            f.write('</Placemark>\n')
        
        # --- Write feature placemarks (only bumps/potholes) ---
        for fp in feature_points:
            feature = fp["feature"]
            style_id = "bumpStyle" if feature == "bump" else "potholeStyle"
            
            f.write('<Placemark>\n')
            f.write(f'<name>{saxutils.escape(feature.capitalize())}</name>\n')
            f.write(f'<styleUrl>#{style_id}</styleUrl>\n')
            f.write(f'<TimeStamp><when>{fp["timestamp"]}</when></TimeStamp>\n')
            f.write('<ExtendedData>\n')
            write_data(f, "timestamp", fp["timestamp"])
            write_data(f, "speedKmph", fmt(fp["speed"], 2))
            write_data(f, "bearingDegrees", fmt(fp["bearing"], 1))
            write_data(f, "featureDetected", feature)
            
            # Add key accel metrics for features
            accel = fp["accel"]
            write_data(f, "accelMagnitudeMax", fmt(accel.get("magMax", 0)))
            write_data(f, "accelRMS", fmt(accel.get("rms", 0)))
            write_data(f, "accelVertMean", fmt(accel.get("vertMean", 0)))
            
            f.write('</ExtendedData>\n')
            f.write(f'<Point>\n<coordinates>{fp["lon"]},{fp["lat"]},{fp["alt"]}</coordinates>\n</Point>\n')
            f.write('</Placemark>\n')

        # --- gx:Track (optional, for timeline playback) ---
        f.write('<Placemark>\n')
        f.write('<name>Track Timeline</name>\n')
        f.write('<visibility>0</visibility>\n')  # Hidden by default
        f.write('<gx:Track>\n')
        for w in track_when:
            f.write(w)
        for c in track_coords:
            f.write(c)
        f.write('</gx:Track>\n')
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

    # Count total points processed
    total_points = len(data)
    feature_count = len(feature_points)
    segment_count = len(line_segments)
    
    print(f"Converted {total_points} points from JSON to KML")
    print(f"  Road quality segments: {segment_count}")
    print(f"  Features detected: {feature_count} ({len([fp for fp in feature_points if fp['feature'] == 'bump'])} bumps, {len([fp for fp in feature_points if fp['feature'] == 'pothole'])} potholes)")
    print(f"  Input:  {args.json_file}")
    print(f"  Output: {output_path}")


if __name__ == "__main__":
    main()
