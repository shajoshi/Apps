#!/usr/bin/env python3
"""
OBD2 Track Analyzer - Convert JSON trip logs to KML with metric-based color coding

Usage:
    python analyze_track.py -m speed track_file.json
    python analyze_track.py -m rpm track_file.json
    python analyze_track.py -m throttle track_file.json
    python analyze_track.py -m fuel track_file.json

Supported metrics:
- speed: GPS or OBD speed (km/h)
- rpm: Engine RPM
- throttle: Throttle position (%)
- fuel: Instant fuel consumption (L/100km or km/L depending on available data)
"""

import argparse
import json
import os
from typing import List, Tuple, Optional
import matplotlib.pyplot as plt
from datetime import datetime

def load_track_data(filename: str) -> Tuple[dict, List[dict]]:
    """Load JSON track file and return header and samples"""
    with open(filename, 'r') as f:
        data = json.load(f)

    header = data['header']
    samples = data['samples']

    return header, samples

def extract_metric_data(samples: List[dict], metric: str) -> List[Tuple[float, float, float, float]]:
    """
    Extract GPS coordinates and metric values from samples
    Returns: List of (lat, lon, metric_value, timestamp_ms) tuples
    """
    data_points = []

    for sample in samples:
        # Extract GPS coordinates
        gps = sample.get('gps', {})
        if not gps or 'lat' not in gps or 'lon' not in gps:
            continue

        lat = gps['lat']
        lon = gps['lon']
        timestamp = sample['timestampMs']

        # Extract metric value based on type
        value = None

        if metric == 'speed':
            # Prefer GPS speed, fall back to OBD speed
            value = gps.get('speedKmh') or sample.get('obd', {}).get('speedKmh')
        elif metric == 'rpm':
            value = sample.get('obd', {}).get('rpm')
        elif metric == 'throttle':
            value = sample.get('obd', {}).get('throttlePct')
        elif metric == 'fuel':
            # Prefer L/100km, fall back to km/L converted
            fuel = sample.get('fuel', {})
            value = fuel.get('instantLper100km')
            if value is None and fuel.get('instantKpl'):
                # Convert km/L to L/100km
                value = 100.0 / fuel['instantKpl']

        if value is not None and lat is not None and lon is not None:
            data_points.append((lat, lon, value, timestamp))

    return data_points

def calculate_color_ranges(values: List[float]) -> Tuple[float, float]:
    """
    Calculate color threshold values based on percentiles
    Returns: (low_threshold, high_threshold)
    Low values (bottom 33%) = Red
    Medium values (33%-66%) = Yellow
    High values (top 33%) = Green
    """
    if not values:
        return 0.0, 0.0

    sorted_values = sorted(values)
    n = len(sorted_values)

    # Use percentiles for thresholds
    low_threshold = sorted_values[n // 3]  # Bottom 33%
    high_threshold = sorted_values[2 * n // 3]  # Top 33% (so middle 33% is yellow)

    return low_threshold, high_threshold

def get_color_for_value(value: float, low_threshold: float, high_threshold: float) -> str:
    """Return KML color string (AABBGGRR format) for the value"""
    if value <= low_threshold:
        # Red for low values
        return "ff0000ff"
    elif value >= high_threshold:
        # Green for high values
        return "ff00ff00"
    else:
        # Yellow for medium values
        return "ff00ffff"

def create_kml(data_points: List[Tuple[float, float, float, float]],
               low_threshold: float, high_threshold: float,
               metric: str, output_file: str, header: dict):
    """Create KML file with colored track"""

    # Create color ranges for KML style
    colors = [
        ("low", "ff0000ff", f"Low {metric} (≤{low_threshold:.1f})"),
        ("medium", "ff00ffff", f"Medium {metric} ({low_threshold:.1f}-{high_threshold:.1f})"),
        ("high", "ff00ff00", f"High {metric} (≥{high_threshold:.1f})")
    ]

    with open(output_file, 'w') as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<kml xmlns="http://www.opengis.net/kml/2.2">\n')
        f.write('<Document>\n')
        f.write(f'<name>OBD2 Track - {metric.title()}</name>\n')
        f.write('<description>\n')
        f.write(f'Generated from OBD2 trip log\n')
        f.write(f'Metric: {metric.title()}\n')
        f.write(f'Low threshold: {low_threshold:.1f}\n')
        f.write(f'High threshold: {high_threshold:.1f}\n')
        f.write(f'Points: {len(data_points)}\n')
        if header.get('vehicleProfile', {}).get('name'):
            f.write(f'Vehicle: {header["vehicleProfile"]["name"]}\n')
        f.write('</description>\n')

        # Define styles for each color range
        for style_id, color, description in colors:
            f.write('<Style id="trackStyle">\n')
            f.write('<LineStyle>\n')
            f.write(f'<color>{color}</color>\n')
            f.write('<width>4</width>\n')
            f.write('</LineStyle>\n')
            f.write('</Style>\n')

        # Create placemarks for each segment
        current_color = None
        segment_points = []

        for lat, lon, value, timestamp in data_points:
            point_color = get_color_for_value(value, low_threshold, high_threshold)

            if point_color != current_color:
                # Finish previous segment
                if segment_points:
                    f.write('<Placemark>\n')
                    f.write('<styleUrl>#trackStyle</styleUrl>\n')
                    f.write('<LineString>\n')
                    f.write('<coordinates>\n')
                    for seg_lat, seg_lon in segment_points:
                        f.write(f'{seg_lon},{seg_lat},0\n')
                    f.write('</coordinates>\n')
                    f.write('</LineString>\n')
                    f.write('</Placemark>\n')

                # Start new segment
                segment_points = []
                current_color = point_color

            segment_points.append((lat, lon))

        # Finish last segment
        if segment_points:
            f.write('<Placemark>\n')
            f.write('<styleUrl>#trackStyle</styleUrl>\n')
            f.write('<LineString>\n')
            f.write('<coordinates>\n')
            for lat, lon in segment_points:
                f.write(f'{lon},{lat},0\n')
            f.write('</coordinates>\n')
            f.write('</LineString>\n')
            f.write('</Placemark>\n')

        f.write('</Document>\n')
        f.write('</kml>\n')

def create_distribution_plot(values: List[float], metric: str, output_file: str):
    """Create histogram showing distribution of metric values"""
    plt.figure(figsize=(10, 6))
    plt.hist(values, bins=50, edgecolor='black', alpha=0.7)
    plt.title(f'Distribution of {metric.title()} Values')
    plt.xlabel(f'{metric.title()}')
    plt.ylabel('Frequency')
    plt.grid(True, alpha=0.3)

    # Add statistics
    mean_val = sum(values) / len(values)
    min_val = min(values)
    max_val = max(values)

    plt.axvline(mean_val, color='red', linestyle='--', linewidth=2, label=f'Mean: {mean_val:.1f}')
    plt.axvline(min_val, color='blue', linestyle=':', linewidth=2, label=f'Min: {min_val:.1f}')
    plt.axvline(max_val, color='green', linestyle=':', linewidth=2, label=f'Max: {max_val:.1f}')

    plt.legend()

    # Save plot
    plot_file = output_file.replace('.kml', '_distribution.png')
    plt.savefig(plot_file, dpi=150, bbox_inches='tight')
    plt.close()

    print(f"Distribution plot saved: {plot_file}")

def main():
    parser = argparse.ArgumentParser(description='Analyze OBD2 track data and generate KML')
    parser.add_argument('-m', '--metric', required=True,
                       choices=['speed', 'rpm', 'throttle', 'fuel'],
                       help='Metric to analyze')
    parser.add_argument('filename', help='JSON track file to analyze')

    args = parser.parse_args()

    if not os.path.exists(args.filename):
        print(f"Error: File '{args.filename}' not found")
        return

    try:
        print(f"Loading track data from {args.filename}...")
        header, samples = load_track_data(args.filename)

        print(f"Extracting {args.metric} data...")
        data_points = extract_metric_data(samples, args.metric)

        if not data_points:
            print(f"Error: No {args.metric} data found in track file")
            return

        values = [point[2] for point in data_points]
        print(f"Found {len(data_points)} data points")

        # Calculate color thresholds
        low_threshold, high_threshold = calculate_color_ranges(values)
        print(f"Color thresholds - Low: {low_threshold:.1f}, High: {high_threshold:.1f}")

        # Generate KML file
        base_name = os.path.splitext(args.filename)[0]
        kml_file = f"{base_name}_{args.metric}.kml"

        print(f"Generating KML file: {kml_file}")
        create_kml(data_points, low_threshold, high_threshold, args.metric, kml_file, header)

        # Generate distribution plot
        print("Generating distribution plot...")
        create_distribution_plot(values, args.metric, kml_file)

        print(f"Analysis complete!")
        print(f"KML file: {kml_file}")
        print(f"Open in Google Earth or any KML viewer to see the colored track")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    main()
