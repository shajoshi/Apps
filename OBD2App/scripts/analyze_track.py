#!/usr/bin/env python3
"""
OBD2 Track Analyzer - Convert JSON trip logs to KML with metric-based color coding

Usage:
    python analyze_track.py -m obd.rpm track_file.json
    python analyze_track.py -m fuel.instantKpl track_file.json -s std_dev
    python analyze_track.py -m accel.vertMax track_file.json -s iqr
    python analyze_track.py -m gps.speedKmh track_file.json -s kmeans
    python analyze_track.py -m obd.throttlePct track_file.json -s natural
    python analyze_track.py -m obd.rpm,obd.mafGs,obd.speedKmh track_file.json

Supported metrics (any fully qualified name):
- OBD metrics: obd.rpm, obd.speedKmh, obd.throttlePct, etc.
- Fuel metrics: fuel.instantKpl, fuel.instantLper100km, etc.
- GPS metrics: gps.speedKmh, gps.altitude, etc.
- Acceleration metrics: accel.vertMax, accel.latMax, etc.
- Any nested JSON path using dot notation

Segmentation strategies:
- percentile: Equal 33% splits (default)
- std_dev: Mean ± standard deviation
- iqr: Interquartile range (Q1 and Q3)
- kmeans: K-means clustering (3 clusters)
- natural: Natural breaks (Jenks optimization)

Features:
- Multiple metrics support (comma-separated)
- Individual KML files for each metric
- Distribution plots for each metric
- Correlation analysis between multiple metrics (requires numpy, pandas, seaborn)
- Zero-value filtering for accurate statistics
"""

import argparse
import json
import os
from typing import List, Tuple, Optional
import matplotlib.pyplot as plt
from datetime import datetime
import re

def load_track_data(filename: str) -> Tuple[dict, List[dict]]:
    """Load JSON track file and return header and samples"""
    with open(filename, 'r') as f:
        data = json.load(f)

    header = data['header']
    samples = data['samples']

    return header, samples

def extract_metric_data(samples: List[dict], metrics: List[str]) -> dict:
    """
    Extract GPS coordinates and metric values from samples
    Returns: Dict mapping metric names to List of (lat, lon, metric_value, timestamp_ms) tuples
    
    Supports fully qualified metric names like:
    - obd.rpm, obd.speedKmh, obd.throttlePct
    - fuel.instantKpl, fuel.instantLper100km
    - gps.speedKmh, gps.altitude
    - accel.vertMax, accel.latMax
    - Any nested JSON path using dot notation
    """
    data_points = {metric: [] for metric in metrics}

    for sample in samples:
        # Extract GPS coordinates
        gps = sample.get('gps', {})
        if not gps or 'lat' not in gps or 'lon' not in gps:
            continue

        lat = gps['lat']
        lon = gps['lon']
        timestamp = sample['timestampMs']

        # Extract each metric value using dot notation
        for metric in metrics:
            value = get_nested_value(sample, metric)
            
            # Special handling for fuel unit conversion
            if value is None and metric == 'fuel.instantKpl':
                # Try L/100km and convert to km/L
                l_per_100km = get_nested_value(sample, 'fuel.instantLper100km')
                if l_per_100km is not None:
                    value = 100.0 / l_per_100km
            elif value is None and metric == 'fuel.instantLper100km':
                # Try km/L and convert to L/100km
                kpl = get_nested_value(sample, 'fuel.instantKpl')
                if kpl is not None:
                    value = 100.0 / kpl

            if value is not None and lat is not None and lon is not None:
                data_points[metric].append((lat, lon, value, timestamp))

    return data_points

def get_nested_value(data: dict, path: str) -> Optional[float]:
    """
    Get value from nested dictionary using dot notation
    Example: get_nested_value(sample, 'obd.rpm') returns sample['obd']['rpm']
    """
    keys = path.split('.')
    current = data
    
    try:
        for key in keys:
            if isinstance(current, dict):
                current = current[key]
            else:
                return None
        
        # Return the value if it's a number, otherwise None
        if isinstance(current, (int, float)):
            return float(current)
        return None
    except (KeyError, TypeError):
        return None

def calculate_color_ranges(values: List[float], strategy: str = "percentile") -> Tuple[float, float]:
    """
    Calculate color threshold values using different statistical strategies
    Returns: (low_threshold, high_threshold)
    
    Strategies:
    - percentile: Equal 33% splits (current method)
    - std_dev: Mean ± standard deviation
    - iqr: Interquartile range (Q1 and Q3)
    - kmeans: K-means clustering (3 clusters)
    - natural: Natural breaks (Jenks)
    """
    if not values:
        return 0.0, 0.0

    # Filter out zero values for statistical calculations
    filtered_values = [v for v in values if v != 0]
    
    if not filtered_values:
        return 0.0, 0.0

    sorted_values = sorted(filtered_values)
    n = len(sorted_values)
    
    if strategy == "percentile":
        # Equal 33% splits
        low_threshold = sorted_values[n // 3]  # 33rd percentile
        high_threshold = sorted_values[2 * n // 3]  # 67th percentile
        
    elif strategy == "std_dev":
        # Mean ± standard deviation
        import statistics
        mean_val = statistics.mean(filtered_values)
        std_val = statistics.stdev(filtered_values)
        low_threshold = mean_val - std_val
        high_threshold = mean_val + std_val
        
    elif strategy == "iqr":
        # Interquartile range (Q1 and Q3)
        import statistics
        q1_idx = n // 4
        q3_idx = 3 * n // 4
        low_threshold = sorted_values[q1_idx]  # 25th percentile
        high_threshold = sorted_values[q3_idx]  # 75th percentile
        
    elif strategy == "kmeans":
        # K-means clustering with 3 clusters
        try:
            import numpy as np
            from sklearn.cluster import KMeans
            
            # Reshape data for k-means
            data = np.array(filtered_values).reshape(-1, 1)
            
            # Apply k-means with 3 clusters
            kmeans = KMeans(n_clusters=3, random_state=42, n_init=10)
            clusters = kmeans.fit_predict(data)
            
            # Get cluster centers and sort them
            centers = sorted(kmeans.cluster_centers_.flatten())
            
            # Calculate thresholds as midpoints between cluster centers
            low_threshold = (centers[0] + centers[1]) / 2
            high_threshold = (centers[1] + centers[2]) / 2
            
        except ImportError:
            # Fallback to percentile if sklearn not available
            print("Warning: sklearn not available, falling back to percentile strategy")
            low_threshold = sorted_values[n // 3]
            high_threshold = sorted_values[2 * n // 3]
            
    elif strategy == "natural":
        # Natural breaks (Jenks) - simplified version
        try:
            import numpy as np
            
            # Find natural breaks by minimizing variance within groups
            best_variance = float('inf')
            best_low, best_high = sorted_values[n // 3], sorted_values[2 * n // 3]
            
            # Try different break points
            for i in range(n // 4, n // 2):  # Low threshold range
                for j in range(n // 2, 3 * n // 4):  # High threshold range
                    low = sorted_values[i]
                    high = sorted_values[j]
                    
                    if low >= high:
                        continue
                    
                    # Calculate within-group variance
                    group1 = [v for v in filtered_values if v <= low]
                    group2 = [v for v in filtered_values if low < v <= high]
                    group3 = [v for v in filtered_values if v > high]
                    
                    if len(group1) == 0 or len(group2) == 0 or len(group3) == 0:
                        continue
                    
                    import statistics
                    var1 = statistics.variance(group1) if len(group1) > 1 else 0
                    var2 = statistics.variance(group2) if len(group2) > 1 else 0
                    var3 = statistics.variance(group3) if len(group3) > 1 else 0
                    
                    total_variance = var1 + var2 + var3
                    
                    if total_variance < best_variance:
                        best_variance = total_variance
                        best_low, best_high = low, high
            
            low_threshold, high_threshold = best_low, best_high
            
        except Exception:
            # Fallback to percentile
            low_threshold = sorted_values[n // 3]
            high_threshold = sorted_values[2 * n // 3]
    
    else:
        # Default to percentile
        low_threshold = sorted_values[n // 3]
        high_threshold = sorted_values[2 * n // 3]

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
            f.write(f'<Style id="{style_id}Style">\n')
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
            
            # Determine style ID based on color
            if point_color == "ff0000ff":
                style_id = "lowStyle"
            elif point_color == "ff00ffff":
                style_id = "mediumStyle"
            else:  # ff00ff00
                style_id = "highStyle"

            if point_color != current_color:
                # Finish previous segment
                if segment_points:
                    f.write('<Placemark>\n')
                    f.write(f'<styleUrl>#{style_id}</styleUrl>\n')
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
            # Determine final style ID
            if current_color == "ff0000ff":
                final_style_id = "lowStyle"
            elif current_color == "ff00ffff":
                final_style_id = "mediumStyle"
            else:
                final_style_id = "highStyle"
                
            f.write('<Placemark>\n')
            f.write(f'<styleUrl>#{final_style_id}</styleUrl>\n')
            f.write('<LineString>\n')
            f.write('<coordinates>\n')
            for lat, lon in segment_points:
                f.write(f'{lon},{lat},0\n')
            f.write('</coordinates>\n')
            f.write('</LineString>\n')
            f.write('</Placemark>\n')

        f.write('</Document>\n')
        f.write('</kml>\n')

def create_distribution_plot(values: List[float], metric: str, output_file: str, 
                            low_threshold: float = None, high_threshold: float = None):
    """Create histogram showing distribution of metric values with RYG cutoffs"""
    plt.figure(figsize=(12, 6))
    
    # Filter out zero values for distribution plot
    filtered_values = [v for v in values if v != 0]
    
    if not filtered_values:
        print("Warning: No non-zero values found for distribution plot")
        return
    
    # Create histogram
    n, bins, patches = plt.hist(filtered_values, bins=50, edgecolor='black', alpha=0.7)
    
    # Color the bars according to RYG ranges
    if low_threshold is not None and high_threshold is not None:
        for i, patch in enumerate(patches):
            bin_center = (bins[i] + bins[i+1]) / 2
            if bin_center <= low_threshold:
                patch.set_facecolor('red')
                patch.set_alpha(0.7)
            elif bin_center >= high_threshold:
                patch.set_facecolor('green')
                patch.set_alpha(0.7)
            else:
                patch.set_facecolor('yellow')
                patch.set_alpha(0.7)
    
    plt.title(f'Distribution of {metric.title()} Values with RYG Ranges')
    plt.xlabel(f'{metric.title()}')
    plt.ylabel('Frequency')
    plt.grid(True, alpha=0.3)

    # Add statistics (using filtered values)
    mean_val = sum(filtered_values) / len(filtered_values)
    min_val = min(filtered_values)
    max_val = max(filtered_values)

    plt.axvline(mean_val, color='black', linestyle='--', linewidth=2, label=f'Mean: {mean_val:.1f}')
    plt.axvline(min_val, color='blue', linestyle=':', linewidth=2, label=f'Min: {min_val:.1f}')
    plt.axvline(max_val, color='blue', linestyle=':', linewidth=2, label=f'Max: {max_val:.1f}')
    
    # Add RYG cutoff lines with shaded regions
    if low_threshold is not None and high_threshold is not None:
        # Red region (0 to low_threshold)
        plt.axvspan(min_val, low_threshold, alpha=0.2, color='red', label='Red Zone (Low 33%)')
        
        # Yellow region (low_threshold to high_threshold)
        plt.axvspan(low_threshold, high_threshold, alpha=0.2, color='yellow', label='Yellow Zone (Middle 33%)')
        
        # Green region (high_threshold to max)
        plt.axvspan(high_threshold, max_val, alpha=0.2, color='green', label='Green Zone (High 33%)')
        
        # Cutoff lines
        plt.axvline(low_threshold, color='red', linestyle='-', linewidth=3, label=f'Red/Yellow Cutoff: {low_threshold:.1f}')
        plt.axvline(high_threshold, color='green', linestyle='-', linewidth=3, label=f'Yellow/Green Cutoff: {high_threshold:.1f}')

    plt.legend(bbox_to_anchor=(1.05, 1), loc='upper left')

    # Save plot
    plot_file = output_file.replace('.kml', '_distribution.png')
    plt.tight_layout()  # Adjust layout to make room for legend
    plt.savefig(plot_file, dpi=150, bbox_inches='tight')
    plt.close()

    print(f"Distribution plot saved: {plot_file}")

def create_correlation_plots(data_points: dict, metrics: List[str], output_file: str):
    """Create correlation plots between multiple metrics"""
    if len(metrics) < 2:
        print("Need at least 2 metrics for correlation analysis")
        return
    
    # Filter out zero values and align data points by timestamp
    aligned_data = {}
    
    for metric in metrics:
        # Filter out zero values
        filtered_points = [(lat, lon, value, timestamp) for lat, lon, value, timestamp in data_points[metric] if value != 0]
        aligned_data[metric] = {timestamp: value for lat, lon, value, timestamp in filtered_points}
    
    # Find common timestamps across all metrics
    common_timestamps = set(aligned_data[metrics[0]].keys())
    for metric in metrics[1:]:
        common_timestamps &= set(aligned_data[metric].keys())
    
    if not common_timestamps:
        print("No common timestamps found across all metrics")
        return
    
    print(f"Found {len(common_timestamps)} aligned data points for correlation analysis")
    
    # Create correlation matrix plot
    try:
        import numpy as np
        import pandas as pd
        import seaborn as sns
        
        # Create aligned data arrays
        correlation_data = {}
        for metric in metrics:
            correlation_data[metric] = [aligned_data[metric][ts] for ts in sorted(common_timestamps)]
        
        # Create correlation matrix
        df = pd.DataFrame(correlation_data)
        correlation_matrix = df.corr()
        
        # Create heatmap
        plt.figure(figsize=(10, 8))
        sns.heatmap(correlation_matrix, annot=True, cmap='coolwarm', center=0, 
                   square=True, fmt='.3f', cbar_kws={'label': 'Correlation Coefficient'})
        plt.title('Correlation Matrix Between Metrics')
        plt.tight_layout()
        
        # Save correlation matrix plot
        corr_matrix_file = output_file.replace('.kml', '_correlation_matrix.png')
        plt.savefig(corr_matrix_file, dpi=150, bbox_inches='tight')
        plt.close()
        print(f"Correlation matrix plot saved: {corr_matrix_file}")
        
        # Create scatter plots for each pair
        n_metrics = len(metrics)
        fig, axes = plt.subplots(n_metrics, n_metrics, figsize=(4*n_metrics, 4*n_metrics))
        if n_metrics == 1:
            axes = [[axes]]
        elif n_metrics == 2:
            axes = axes.reshape(2, 2)
        
        for i, metric1 in enumerate(metrics):
            for j, metric2 in enumerate(metrics):
                ax = axes[i, j]
                
                if i == j:
                    # Diagonal: histogram
                    values = correlation_data[metric1]
                    ax.hist(values, bins=30, alpha=0.7, color='skyblue', edgecolor='black')
                    ax.set_title(f'{metric1}')
                    ax.set_xlabel(metric1)
                    ax.set_ylabel('Frequency')
                else:
                    # Off-diagonal: scatter plot
                    x_vals = correlation_data[metric2]
                    y_vals = correlation_data[metric1]
                    
                    # Calculate correlation coefficient
                    corr_coef = np.corrcoef(x_vals, y_vals)[0, 1]
                    
                    ax.scatter(x_vals, y_vals, alpha=0.6, s=20)
                    ax.set_xlabel(metric2)
                    ax.set_ylabel(metric1)
                    ax.set_title(f'r = {corr_coef:.3f}')
                    
                    # Add trend line
                    z = np.polyfit(x_vals, y_vals, 1)
                    p = np.poly1d(z)
                    ax.plot(x_vals, p(x_vals), "r--", alpha=0.8)
        
        plt.tight_layout()
        
        # Save scatter plots
        scatter_file = output_file.replace('.kml', '_correlation_scatter.png')
        plt.savefig(scatter_file, dpi=150, bbox_inches='tight')
        plt.close()
        print(f"Correlation scatter plots saved: {scatter_file}")
        
    except ImportError:
        print("Warning: numpy, pandas, or seaborn not available. Skipping correlation plots.")
        print("Install with: pip install numpy pandas seaborn matplotlib")

def main():
    parser = argparse.ArgumentParser(description='Analyze OBD2 track data and generate KML')
    parser.add_argument('-m', '--metric', required=True,
                       help='Metric(s) to analyze (comma-separated fully qualified names, e.g., obd.rpm,obd.mafGs)')
    parser.add_argument('-s', '--strategy', default='percentile',
                       choices=['percentile', 'std_dev', 'iqr', 'kmeans', 'natural'],
                       help='Color segmentation strategy (default: percentile)')
    parser.add_argument('filename', help='JSON track file to analyze')

    args = parser.parse_args()

    if not os.path.exists(args.filename):
        print(f"Error: File '{args.filename}' not found")
        return

    # Parse comma-separated metrics
    metrics = [m.strip() for m in args.metric.split(',') if m.strip()]
    
    if not metrics:
        print("Error: No valid metrics specified")
        return

    try:
        print(f"Loading track data from {args.filename}...")
        header, samples = load_track_data(args.filename)

        print(f"Extracting data for metrics: {', '.join(metrics)}...")
        data_points = extract_metric_data(samples, metrics)

        # Check if we have data for any metrics
        valid_metrics = [metric for metric in metrics if data_points[metric]]
        if not valid_metrics:
            print(f"Error: No data found for any specified metrics")
            return

        print(f"Found data for metrics: {', '.join(valid_metrics)}")

        # Generate KML files for each metric
        base_name = os.path.splitext(args.filename)[0]
        
        for metric in valid_metrics:
            values = [point[2] for point in data_points[metric]]
            print(f"\nProcessing {metric}: {len(data_points[metric])} data points")

            # Calculate color thresholds
            low_threshold, high_threshold = calculate_color_ranges(values, args.strategy)
            print(f"Color thresholds ({args.strategy} strategy) - Low: {low_threshold:.1f}, High: {high_threshold:.1f}")

            # Generate KML file
            metric_safe = metric.replace('.', '_')
            kml_file = f"{base_name}_{metric_safe}.kml"

            print(f"Generating KML file: {kml_file}")
            create_kml(data_points[metric], low_threshold, high_threshold, metric, kml_file, header)

            # Generate distribution plot
            print("Generating distribution plot...")
            create_distribution_plot(values, metric, kml_file, low_threshold, high_threshold)

        # Generate correlation plots if we have multiple metrics
        if len(valid_metrics) >= 2:
            print(f"\nGenerating correlation plots for {len(valid_metrics)} metrics...")
            create_correlation_plots(data_points, valid_metrics, base_name + "_correlation.kml")

        print(f"\nAnalysis complete!")
        for metric in valid_metrics:
            metric_safe = metric.replace('.', '_')
            kml_file = f"{base_name}_{metric_safe}.kml"
            print(f"KML file for {metric}: {kml_file}")
        print("Open in Google Earth or any KML viewer to see the colored tracks")

    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    main()
