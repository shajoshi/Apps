#!/usr/bin/env python3
"""
Test script to run speed hump detection on JSON tracking data
and extract lat/lon coordinates of detected speed humps.
"""

import json
import sys
from typing import List, Dict, Any, Tuple

def detect_speed_hump_pattern(raw_vert_accel: List[float], fwd_max: float, speed_kmph: float) -> str:
    """
    Port of the Kotlin speed hump detection function to Python for testing
    """
    # Calibration thresholds (matching the defaults)
    SPEED_HUMP_MIN_AMPLITUDE = 45.0
    SPEED_HUMP_MIN_PEAKS = 4
    SPEED_HUMP_MAX_DURATION = 2.0
    SPEED_HUMP_DECAY_RATIO = 0.8
    SPEED_HUMP_MIN_FWD_MAX = 40.0
    
    # Low speed adaptive thresholds
    SPEED_HUMP_LOW_SPEED_THRESHOLD = 20.0
    SPEED_HUMP_LOW_SPEED_AMPLITUDE = 20.0
    SPEED_HUMP_LOW_SPEED_FWD_MAX = 15.0
    
    # Default sampling rate (35 Hz based on provided data)
    SAMPLING_RATE = 35.0
    
    # Use adaptive thresholds based on speed
    if speed_kmph < SPEED_HUMP_LOW_SPEED_THRESHOLD:
        # Low speed thresholds (for 15 km/h case)
        min_amplitude = SPEED_HUMP_LOW_SPEED_AMPLITUDE
        min_fwd_max = SPEED_HUMP_LOW_SPEED_FWD_MAX
    else:
        # High speed thresholds (for 35 km/h case)
        min_amplitude = SPEED_HUMP_MIN_AMPLITUDE
        min_fwd_max = SPEED_HUMP_MIN_FWD_MAX
    
    print(f"  Speed: {speed_kmph:.1f} km/h -> Using thresholds: amplitude={min_amplitude:.1f}, fwdMax={min_fwd_max:.1f}")
    
    # Gate: Check basic thresholds for speed hump detection
    if fwd_max < min_fwd_max:
        print(f"  ❌ Forward max too low: {fwd_max:.3f} < {min_fwd_max:.1f}")
        return None
    
    # Find peaks in raw vertical acceleration
    peaks = []
    peak_indices = []
    
    for i in range(1, len(raw_vert_accel) - 1):
        current = raw_vert_accel[i]
        prev = raw_vert_accel[i - 1]
        next_val = raw_vert_accel[i + 1]
        
        # Peak detection: current > prev and current > next and above threshold
        if current > prev and current > next_val and abs(current) > 10.0:
            peaks.append(current)
            peak_indices.append(i)
    
    print(f"  Found {len(peaks)} peaks")
    
    # Need minimum number of peaks
    if len(peaks) < SPEED_HUMP_MIN_PEAKS:
        print(f"  ❌ Too few peaks: {len(peaks)} < {SPEED_HUMP_MIN_PEAKS}")
        return None
    
    # Check duration (convert samples to seconds)
    duration = len(raw_vert_accel) / SAMPLING_RATE
    if duration > SPEED_HUMP_MAX_DURATION:
        print(f"  ❌ Duration too long: {duration:.2f}s > {SPEED_HUMP_MAX_DURATION}s")
        return None
    
    # Calculate peak-to-peak amplitude
    max_peak = max(peaks)
    min_peak = min(peaks)
    peak_to_peak_amplitude = max_peak - min_peak
    
    print(f"  Peak-to-peak amplitude: {peak_to_peak_amplitude:.2f}")
    
    if peak_to_peak_amplitude < min_amplitude:
        print(f"  ❌ Amplitude too low: {peak_to_peak_amplitude:.2f} < {min_amplitude:.1f}")
        return None
    
    # Check for amplitude decay (characteristic of speed humps)
    mid_point = len(peaks) // 2
    first_half_peaks = peaks[:mid_point]
    second_half_peaks = peaks[mid_point:]
    
    if first_half_peaks and second_half_peaks:
        first_half_avg = sum(abs(p) for p in first_half_peaks) / len(first_half_peaks)
        second_half_avg = sum(abs(p) for p in second_half_peaks) / len(second_half_peaks)
        decay_ratio = second_half_avg / first_half_avg if first_half_avg > 0 else 1.0
        
        print(f"  Decay ratio: {decay_ratio:.3f}")
        
        if decay_ratio > SPEED_HUMP_DECAY_RATIO:
            print(f"  ❌ Decay ratio too high: {decay_ratio:.3f} > {SPEED_HUMP_DECAY_RATIO}")
            return None
    
    # All speed hump criteria met
    print(f"  ✅ SPEED HUMP DETECTED!")
    return "speed_hump"

def compute_accel_metrics_kotlin_equivalent(raw_data: List[List[float]], fwd_max: float, speed_kmph: float, base_gravity_vector: List[float] = None) -> Dict[str, float]:
    """
    Exact replica of Kotlin computeAccelMetrics() function calculations
    """
    if not raw_data:
        return {}
    
    # Calibration defaults (matching typical values)
    PEAK_THRESHOLD_Z = 2.5
    MOVING_AVERAGE_WINDOW = 1  # From your JSON recordingSettings
    SAMPLING_RATE = 100.0  # Hz (10ms intervals = 100 samples per second)
    
    # ==================== KOTLIN computeAccelMetrics() EXACT REPLICA ====================
    
    # Step 1: Remove gravity/static offset from ALL axes (detrend)
    # Physics: The mean acceleration over a window approximates the gravity vector
    bias_x = sum(sample[0] for sample in raw_data) / len(raw_data)
    bias_y = sum(sample[1] for sample in raw_data) / len(raw_data)
    bias_z = sum(sample[2] for sample in raw_data) / len(raw_data)
    
    detrended = [[sample[0] - bias_x, sample[1] - bias_y, sample[2] - bias_z] for sample in raw_data]
    
    # Step 2: Apply simple moving average filter
    # For window size 1, this is essentially a no-op (returns same data)
    smoothed = detrended  # Since MOVING_AVERAGE_WINDOW = 1
    
    # Step 3: Decompose into vehicle-frame axes and compute metrics
    # Use baseGravityVector to calculate vehicle frame vectors
    
    # Calculate gravity unit vector from baseGravityVector
    if base_gravity_vector and len(base_gravity_vector) >= 3:
        g_norm = (base_gravity_vector[0]**2 + base_gravity_vector[1]**2 + base_gravity_vector[2]**2) ** 0.5
        if g_norm > 1e-3:
            g_unit = [base_gravity_vector[0] / g_norm, base_gravity_vector[1] / g_norm, base_gravity_vector[2] / g_norm]
        else:
            g_unit = None
    else:
        g_unit = None
    
    # Accumulator variables
    sum_x = sum(sample[0] for sample in smoothed)
    sum_y = sum(sample[1] for sample in smoothed)
    sum_z = sum(sample[2] for sample in smoothed)
    sum_vert = 0.0
    vert_max_mag = 0.0
    vert_sum_squares = 0.0
    above_z_threshold_count = 0
    vert_magnitudes = []
    
    # Process each smoothed acceleration sample in the window
    for values in smoothed:
        # Vertical acceleration: Project 3D acceleration onto gravity direction
        # Physics: aVert = a · ĝ (dot product) isolates motion along vertical axis
        if g_unit is not None:
            a_vert = values[0] * g_unit[0] + values[1] * g_unit[1] + values[2] * g_unit[2]
        else:
            # Fallback to Z-axis if no gravity vector available
            a_vert = values[2]
        
        sum_vert += a_vert
        abs_vert = abs(a_vert)
        vert_magnitudes.append(abs_vert)
        if abs_vert > vert_max_mag:
            vert_max_mag = abs_vert
        vert_sum_squares += a_vert * a_vert
        
        # Peak ratio: Count samples where vertical acceleration exceeds threshold
        if abs_vert >= PEAK_THRESHOLD_Z:
            above_z_threshold_count += 1
    
    # Calculate statistical metrics (exact Kotlin calculations)
    count = len(smoothed)
    
    # Mean accelerations
    mean_x = sum_x / count
    mean_y = sum_y / count
    mean_z = sum_z / count
    mean_vert = sum_vert / count
    
    # Vertical RMS: sqrt(mean(aVert²))
    rms_vert = (vert_sum_squares / count) ** 0.5
    
    # maxMagnitude is vertical-only peak
    max_magnitude = vert_max_mag
    
    # Peak ratio: Fraction of samples exceeding vertical threshold
    peak_ratio = above_z_threshold_count / count
    
    # Standard deviation of vertical magnitude
    mean_magnitude_vert = sum(vert_magnitudes) / len(vert_magnitudes)
    variance = sum((mag - mean_magnitude_vert) ** 2 for mag in vert_magnitudes) / len(vert_magnitudes)
    std_dev_vert = variance ** 0.5
    
    # Calculate vertical acceleration values for all samples
    vert_accel_values = []
    for values in smoothed:
        if g_unit is not None:
            a_vert = values[0] * g_unit[0] + values[1] * g_unit[1] + values[2] * g_unit[2]
        else:
            a_vert = values[2]  # Fallback to Z-axis
        vert_accel_values.append(a_vert)
    
    # Peak detection on vertical acceleration
    peaks = []
    for i in range(1, len(vert_accel_values) - 1):
        current = vert_accel_values[i]
        prev = vert_accel_values[i - 1]
        next_val = vert_accel_values[i + 1]
        
        # Peak detection: current > prev and current > next and above threshold
        if current > prev and current > next_val and abs(current) > 5.0:
            peaks.append(current)
    
    # Oscillation analysis (zero crossings on vertical acceleration)
    zero_crossings = 0
    for i in range(1, len(vert_accel_values)):
        if (vert_accel_values[i-1] * vert_accel_values[i]) < 0:  # Sign change
            zero_crossings += 1
    
    # Peak-to-peak amplitude
    peak_to_peak_amplitude = max(peaks) - min(peaks) if peaks else 0
    
    return {
        # Raw data info
        'raw_count': len(raw_data),
        'duration': len(raw_data) / SAMPLING_RATE,  # 100 Hz = 10ms intervals
        
        # Kotlin exact metrics
        'rms_vert': rms_vert,
        'max_magnitude': max_magnitude,
        'peak_ratio': peak_ratio,
        'std_dev_vert': std_dev_vert,
        'mean_magnitude_vert': mean_magnitude_vert,
        'mean_x': mean_x,
        'mean_y': mean_y,
        'mean_z': mean_z,
        'mean_vert': mean_vert,
        
        # Speed hump pattern analysis
        'peak_to_peak': peak_to_peak_amplitude,
        'num_peaks': len(peaks),
        'zero_crossings': zero_crossings,
        'peak_density': len(peaks) / len(raw_data) if raw_data else 0,
        
        # Raw vertical acceleration for reference (properly calculated)
        'vert_accel_values': vert_accel_values,
        'peaks': peaks,
        'g_unit_used': g_unit is not None,
        'base_gravity_vector': base_gravity_vector
    }

def analyze_json_file(json_file_path: str):
    """
    Exploratory analysis of JSON tracking file to discover speed breaker patterns
    """
    try:
        with open(json_file_path, 'r') as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error reading JSON file: {e}")
        return
    
    print(f"Analyzing JSON file: {json_file_path}")
    
    # Extract baseGravityVector from calibration settings
    base_gravity_vector = None
    if 'gpslogger2path' in data:
        gpslogger_data = data['gpslogger2path']
        if 'meta' in gpslogger_data and 'recordingSettings' in gpslogger_data['meta']:
            calibration = gpslogger_data['meta']['recordingSettings'].get('calibration', {})
            if 'baseGravityVector' in calibration:
                base_gravity_vector = [
                    calibration['baseGravityVector'].get('x', 0),
                    calibration['baseGravityVector'].get('y', 0),
                    calibration['baseGravityVector'].get('z', 0)
                ]
                print(f"Found baseGravityVector: [{base_gravity_vector[0]:.3f}, {base_gravity_vector[1]:.3f}, {base_gravity_vector[2]:.3f}]")
    
    if base_gravity_vector is None:
        print("Warning: No baseGravityVector found, using Z-axis as vertical")
    
    # Try different data structures
    if 'data' in data:
        points = data['data']
    elif 'gpslogger2path' in data:
        # Handle gpslogger2path structure - look for data within it
        gpslogger_data = data['gpslogger2path']
        if 'data' in gpslogger_data:
            points = gpslogger_data['data']
        else:
            points = gpslogger_data  # fallback
    elif isinstance(data, list):
        points = data
    else:
        print(f"Unknown data structure. Keys: {list(data.keys())}")
        print("Trying to explore the structure...")
        
        # Try to find the actual data array
        def find_data_array(obj, path=""):
            if isinstance(obj, list):
                if len(obj) > 0 and isinstance(obj[0], dict):
                    print(f"Found data array at {path} with {len(obj)} items")
                    return obj
            elif isinstance(obj, dict):
                for key, value in obj.items():
                    result = find_data_array(value, f"{path}.{key}" if path else key)
                    if result:
                        return result
            return None
        
        points = find_data_array(data)
        if not points:
            print("Could not find data array in JSON structure")
            return
    
    print(f"Total data points: {len(points)}")
    print()
    
    # Analyze all points with raw data
    all_samples = []
    
    for i, point in enumerate(points):
        # Handle case where point is a string (JSON string that needs parsing)
        if isinstance(point, str):
            # Debug: Show what the string looks like
            if i < 3:
                print(f"Debug point {i+1} string (first 200 chars): {point[:200]}...")
            
            try:
                point = json.loads(point)
            except json.JSONDecodeError as e:
                print(f"Skipping point {i+1}: cannot parse JSON string - {e}")
                # Try to see if it's a different format
                if i < 3:
                    print(f"  String length: {len(point)}")
                    print(f"  First 50 chars: '{point[:50]}'")
                    print(f"  Last 50 chars: '{point[-50:]}'")
                continue
        
        # Extract GPS coordinates
        lat = point.get('gps', {}).get('lat') if 'gps' in point else point.get('lat')
        lon = point.get('gps', {}).get('lon') if 'gps' in point else point.get('lon')
        speed = point.get('gps', {}).get('speed') if 'gps' in point else point.get('speed', 0)
        
        # Extract acceleration data
        accel_data = point.get('accel', {})
        raw_data = accel_data.get('raw', [])
        
        # Debug: Show first few points structure
        if i < 3:
            print(f"Debug point {i+1}:")
            print(f"  Keys: {list(point.keys())}")
            if 'accel' in point:
                print(f"  Accel keys: {list(point['accel'].keys())}")
                print(f"  Raw data type: {type(raw_data)}, length: {len(raw_data) if raw_data else 'None'}")
                if raw_data and len(raw_data) > 0:
                    print(f"  First raw sample: {raw_data[0]}")
        
        # Extract metrics
        fwd_max = accel_data.get('fwdMax', 0.0)
        mag_max = accel_data.get('magMax', 0.0)
        rms = accel_data.get('rms', 0.0)
        peak_ratio = accel_data.get('peakRatio', 0.0)
        
        # Skip if no raw data
        if not raw_data:
            if i < 3:  # Only show this for first few points
                print(f"  Skipping: no raw data found")
            continue
        
        # Analyze the pattern using exact Kotlin calculations
        pattern_stats = compute_accel_metrics_kotlin_equivalent(raw_data, fwd_max, speed, base_gravity_vector)
        
        sample_info = {
            'point_index': i + 1,
            'lat': lat,
            'lon': lon,
            'speed': speed,
            'fwd_max': fwd_max,
            'mag_max': mag_max,
            'rms': rms,
            'peak_ratio': peak_ratio,
            **pattern_stats
        }
        
        all_samples.append(sample_info)
    
    print(f"Found {len(all_samples)} samples with raw acceleration data")
    print()
    
    # Statistical analysis of all samples
    print("=" * 80)
    print("STATISTICAL ANALYSIS OF ALL SAMPLES")
    print("=" * 80)
    
    metrics = ['speed', 'fwd_max', 'mag_max', 'rms', 'peak_ratio', 'rms_vert', 'max_magnitude', 'std_dev_vert', 'peak_to_peak', 'num_peaks', 'zero_crossings']
    
    for metric in metrics:
        values = [s[metric] for s in all_samples if metric in s and s[metric] is not None]
        if values:
            print(f"{metric:15}: min={min(values):7.2f}, max={max(values):7.2f}, mean={sum(values)/len(values):7.2f}, std={(sum((x-(sum(values)/len(values)))**2 for x in values)/len(values))**0.5:7.2f}")
    
    print()
    
    # Look for potential speed breaker candidates based on various criteria
    print("=" * 80)
    print("POTENTIAL SPEED BREAKER CANDIDATES")
    print("=" * 80)
    
    candidates = []
    
    # Different detection criteria
    criteria = [
        ("High peak-to-peak", lambda s: s.get('peak_to_peak', 0) > 15.0),
        ("High forward accel", lambda s: s.get('fwd_max', 0) > 10.0),
        ("High magnitude", lambda s: s.get('max_magnitude', 0) > 15.0),
        ("Many oscillations", lambda s: s.get('num_peaks', 0) > 5),
        ("High zero crossings", lambda s: s.get('zero_crossings', 0) > 10),
        ("High RMS (Kotlin)", lambda s: s.get('rms_vert', 0) > 5.0),
        ("High stdDev (Kotlin)", lambda s: s.get('std_dev_vert', 0) > 3.0),
    ]
    
    for criterion_name, criterion_func in criteria:
        print(f"\n{criterion_name}:")
        matching = [s for s in all_samples if criterion_func(s)]
        print(f"  Found {len(matching)} candidates")
        
        # Show top 5 examples
        for sample in sorted(matching, key=lambda s: s.get('peak_to_peak', 0), reverse=True)[:5]:
            print(f"    Point {sample['point_index']}: lat={sample['lat']:.6f}, lon={sample['lon']:.6f}")
            print(f"      Speed={sample['speed']:.1f}, fwdMax={sample['fwd_max']:.2f}, magMax={sample['mag_max']:.2f}")
            print(f"      Peak-to-peak={sample['peak_to_peak']:.2f}, peaks={sample['num_peaks']}, zero_cross={sample['zero_crossings']}")
    
    # Manual inspection of interesting samples
    print("\n" + "=" * 80)
    print("DETAILED ANALYSIS OF TOP 10 INTERESTING SAMPLES")
    print("=" * 80)
    
    # Sort by combined interesting score
    def interesting_score(sample):
        score = 0
        if sample.get('peak_to_peak', 0) > 10: score += 1
        if sample.get('fwd_max', 0) > 8: score += 1
        if sample.get('num_peaks', 0) > 4: score += 1
        if sample.get('zero_crossings', 0) > 8: score += 1
        return score
    
    interesting_samples = sorted(all_samples, key=interesting_score, reverse=True)[:10]
    
    for i, sample in enumerate(interesting_samples, 1):
        print(f"\n{i}. Point {sample['point_index']}: lat={sample['lat']:.6f}, lon={sample['lon']:.6f}")
        print(f"   Speed: {sample['speed']:.1f} km/h")
        print(f"   Original Metrics: fwdMax={sample['fwd_max']:.2f}, magMax={sample['mag_max']:.2f}, rms={sample['rms']:.2f}")
        print(f"   Kotlin Metrics: rmsVert={sample['rms_vert']:.2f}, maxMag={sample['max_magnitude']:.2f}, stdDev={sample['std_dev_vert']:.2f}")
        print(f"   Pattern: peak_to_peak={sample['peak_to_peak']:.2f}, peaks={sample['num_peaks']}, zero_cross={sample['zero_crossings']}")
        print(f"   Duration: {sample['duration']:.2f}s, peak_density: {sample['peak_density']:.3f}")
    
    # Recommendations
    print("\n" + "=" * 80)
    print("THRESHOLD RECOMMENDATIONS")
    print("=" * 80)
    
    if all_samples:
        speeds = [s['speed'] for s in all_samples if s.get('speed')]
        fwd_maxs = [s['fwd_max'] for s in all_samples if s.get('fwd_max')]
        peak_to_peaks = [s['peak_to_peak'] for s in all_samples if s.get('peak_to_peak')]
        num_peaks = [s['num_peaks'] for s in all_samples if s.get('num_peaks')]
        
        print("Based on data analysis, recommended thresholds:")
        print(f"  Speed ranges: {min(speeds):.1f} - {max(speeds):.1f} km/h")
        print(f"  Forward acceleration: 75th percentile = {sorted(fwd_maxs)[int(len(fwd_maxs)*0.75)]:.2f}")
        print(f"  Peak-to-peak amplitude: 75th percentile = {sorted(peak_to_peaks)[int(len(peak_to_peaks)*0.75)]:.2f}")
        print(f"  Number of peaks: 75th percentile = {sorted(num_peaks)[int(len(num_peaks)*0.75)]:.1f}")
        
        # Suggest adaptive thresholds
        low_speed_samples = [s for s in all_samples if s.get('speed', 0) < 20]
        high_speed_samples = [s for s in all_samples if s.get('speed', 0) >= 20]
        
        if low_speed_samples:
            low_fwd = [s['fwd_max'] for s in low_speed_samples if s.get('fwd_max')]
            low_amp = [s['peak_to_peak'] for s in low_speed_samples if s.get('peak_to_peak')]
            print(f"\n  Low speed (<20 km/h) recommendations:")
            print(f"    fwdMax threshold: {sorted(low_fwd)[int(len(low_fwd)*0.75)]:.2f}")
            print(f"    Amplitude threshold: {sorted(low_amp)[int(len(low_amp)*0.75)]:.2f}")
        
        if high_speed_samples:
            high_fwd = [s['fwd_max'] for s in high_speed_samples if s.get('fwd_max')]
            high_amp = [s['peak_to_peak'] for s in high_speed_samples if s.get('peak_to_peak')]
            print(f"\n  High speed (≥20 km/h) recommendations:")
            print(f"    fwdMax threshold: {sorted(high_fwd)[int(len(high_fwd)*0.75)]:.2f}")
            print(f"    Amplitude threshold: {sorted(high_amp)[int(len(high_amp)*0.75)]:.2f}")
    
    # Export all analysis
    csv_file = json_file_path.replace('.json', '_pattern_analysis.csv')
    with open(csv_file, 'w') as f:
        headers = ['point_index', 'lat', 'lon', 'speed', 'fwd_max', 'mag_max', 'rms', 'peak_ratio', 
                  'rms_vert', 'max_magnitude', 'std_dev_vert', 'peak_to_peak', 'num_peaks', 'zero_crossings', 'duration', 'peak_density']
        f.write(','.join(headers) + '\n')
        for sample in all_samples:
            row = [str(sample.get(h, '')) for h in headers]
            f.write(','.join(row) + '\n')
    
    print(f"\nExported detailed analysis to: {csv_file}")

def main():
    if len(sys.argv) != 2:
        print("Usage: python test_speed_hump_detection.py <json_file>")
        print("Example: python test_speed_hump_detection.py track.json")
        sys.exit(1)
    
    json_file = sys.argv[1]
    analyze_json_file(json_file)

if __name__ == "__main__":
    main()
