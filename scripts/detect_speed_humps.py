#!/usr/bin/env python3
"""
Speed hump detection script using data-driven thresholds
Based on statistical analysis of actual tracking data.
"""

import json
import sys
from typing import List, Dict, Any, Tuple

def compute_accel_metrics_kotlin_equivalent(raw_data: List[List[float]], fwd_max: float, speed_kmph: float, base_gravity_vector: List[float] = None) -> Tuple[bool, Dict[str, Any]]:
    """
    Exact replica of Kotlin computeAccelMetrics() function calculations
    followed by speed hump detection using data-driven thresholds
    """
    # Redesigned speed hump detection algorithm - more selective
    LOW_SPEED_THRESHOLD = 20.0  # km/h
    
    # Speed hump characteristics - based on actual speed breaker physics
    # Speed humps create: high forward acceleration + oscillation pattern + amplitude decay
    
    # Low speed thresholds (< 20 km/h) - based on actual data analysis
    LOW_SPEED_FWD_MAX = 10.0  # From 75th percentile
    LOW_SPEED_AMPLITUDE = 10.0  # From analysis
    LOW_SPEED_MIN_PEAKS = 8   # From 75th percentile
    LOW_SPEED_MIN_RMS = 2.0   # Lower threshold
    
    # High speed thresholds (≥ 20 km/h) - based on actual data analysis
    HIGH_SPEED_FWD_MAX = 15.0  # From 75th percentile
    HIGH_SPEED_AMPLITUDE = 10.0  # From analysis
    HIGH_SPEED_MIN_PEAKS = 12   # From 75th percentile
    HIGH_SPEED_MIN_RMS = 3.0   # Lower threshold
    
    # Common thresholds (matching speed breaker physics)
    MAX_DURATION = 8.0  # seconds (for actual 6-7 second raw data windows)
    DECAY_RATIO_THRESHOLD = 0.7  # Stricter decay requirement
    SAMPLING_RATE = 100.0  # Hz (10ms intervals = 100 samples per second)
    PEAK_THRESHOLD_Z = 2.5  # Default calibration value
    MOVING_AVERAGE_WINDOW = 1  # From your JSON recordingSettings
    
    # Additional speed hump specific criteria
    MIN_ZERO_CROSSINGS = 20  # Must have oscillations (lowered from 8)
    MIN_PEAK_DENSITY = 0.05  # Minimum peaks per sample (lowered from 0.1)
    MAX_PEAK_SPACING = 0.3  # Maximum seconds between peaks (for continuous oscillation)
    
    # Choose appropriate thresholds based on speed
    if speed_kmph < LOW_SPEED_THRESHOLD:
        min_fwd_max = LOW_SPEED_FWD_MAX
        min_amplitude = LOW_SPEED_AMPLITUDE
        min_peaks = LOW_SPEED_MIN_PEAKS
        speed_category = "Low Speed"
    else:
        min_fwd_max = HIGH_SPEED_FWD_MAX
        min_amplitude = HIGH_SPEED_AMPLITUDE
        min_peaks = HIGH_SPEED_MIN_PEAKS
        speed_category = "High Speed"
    
    detection_info = {
        'speed_kmph': speed_kmph,
        'speed_category': speed_category,
        'fwd_max': fwd_max,
        'min_fwd_max_threshold': min_fwd_max,
        'min_amplitude_threshold': min_amplitude,
        'min_peaks_threshold': min_peaks,
        'gates_passed': []
    }
    
    # ==================== KOTLIN computeAccelMetrics() EXACT REPLICA ====================
    
    if not raw_data:
        detection_info['gates_passed'].append("❌ No raw data")
        return False, detection_info
    
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
    
    # Store computed metrics for reference
    detection_info.update({
        'rms_vert': rms_vert,
        'max_magnitude': max_magnitude,
        'peak_ratio': peak_ratio,
        'std_dev_vert': std_dev_vert,
        'mean_magnitude_vert': mean_magnitude_vert
    })
    
    # ==================== SPEED HUMP DETECTION ====================
    
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
    
    # Calculate zero crossings on vertical acceleration
    zero_crossings = 0
    for i in range(1, len(vert_accel_values)):
        if (vert_accel_values[i-1] * vert_accel_values[i]) < 0:  # Sign change
            zero_crossings += 1
    
    detection_info['num_peaks'] = len(peaks)
    detection_info['zero_crossings'] = zero_crossings
    
    # Gate 1: Minimum number of peaks (removed RMS and fwdMax gates)
    if len(peaks) < min_peaks:
        detection_info['gates_passed'].append(f"❌ peaks: {len(peaks)} < {min_peaks}")
        return False, detection_info
    detection_info['gates_passed'].append(f"✅ peaks: {len(peaks)} ≥ {min_peaks}")
    
    # Gate 2: Zero crossings (must have oscillations)
    if zero_crossings < MIN_ZERO_CROSSINGS:
        detection_info['gates_passed'].append(f"❌ zero_crossings: {zero_crossings} < {MIN_ZERO_CROSSINGS}")
        return False, detection_info
    detection_info['gates_passed'].append(f"✅ zero_crossings: {zero_crossings} ≥ {MIN_ZERO_CROSSINGS}")
    
    # Gate 3: Duration check
    duration = len(smoothed) / SAMPLING_RATE
    detection_info['duration'] = duration
    if duration > MAX_DURATION:
        detection_info['gates_passed'].append(f"❌ duration: {duration:.2f}s > {MAX_DURATION}s")
        return False, detection_info
    detection_info['gates_passed'].append(f"✅ duration: {duration:.2f}s ≤ {MAX_DURATION}s")
    
    # Gate 4: Peak-to-peak amplitude
    if peaks:
        max_peak = max(peaks)
        min_peak = min(peaks)
        peak_to_peak_amplitude = max_peak - min_peak
        detection_info['peak_to_peak_amplitude'] = peak_to_peak_amplitude
        
        if peak_to_peak_amplitude < min_amplitude:
            detection_info['gates_passed'].append(f"❌ amplitude: {peak_to_peak_amplitude:.2f} < {min_amplitude:.2f}")
            return False, detection_info
        detection_info['gates_passed'].append(f"✅ amplitude: {peak_to_peak_amplitude:.2f} ≥ {min_amplitude:.2f}")
    else:
        detection_info['peak_to_peak_amplitude'] = 0
        detection_info['gates_passed'].append("❌ amplitude: no peaks found")
        return False, detection_info
    
    # Gate 5: Amplitude decay (characteristic of speed humps)
    if len(peaks) >= 4:
        mid_point = len(peaks) // 2
        first_half_peaks = peaks[:mid_point]
        second_half_peaks = peaks[mid_point:]
        
        if first_half_peaks and second_half_peaks:
            first_half_avg = sum(abs(p) for p in first_half_peaks) / len(first_half_peaks)
            second_half_avg = sum(abs(p) for p in second_half_peaks) / len(second_half_peaks)
            decay_ratio = second_half_avg / first_half_avg if first_half_avg > 0 else 1.0
            detection_info['decay_ratio'] = decay_ratio
            
            if decay_ratio > DECAY_RATIO_THRESHOLD:
                detection_info['gates_passed'].append(f"❌ decay: {decay_ratio:.3f} > {DECAY_RATIO_THRESHOLD}")
                return False, detection_info
            detection_info['gates_passed'].append(f"✅ decay: {decay_ratio:.3f} ≤ {DECAY_RATIO_THRESHOLD}")
        else:
            detection_info['decay_ratio'] = None
            detection_info['gates_passed'].append("⚠️ decay: insufficient peaks for analysis")
    else:
        detection_info['decay_ratio'] = None
        detection_info['gates_passed'].append("⚠️ decay: insufficient peaks for analysis")
    
    # All gates passed - speed hump detected!
    detection_info['gates_passed'].append("🎯 SPEED HUMP DETECTED!")
    return True, detection_info

def analyze_json_file(json_file_path: str):
    """
    Analyze JSON tracking file for speed humps using data-driven thresholds
    """
    try:
        with open(json_file_path, 'r') as f:
            data = json.load(f)
    except Exception as e:
        print(f"Error reading JSON file: {e}")
        return
    
    print(f"🔍 Analyzing JSON file: {json_file_path}")
    print("=" * 80)
    
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
                print(f"📐 Found baseGravityVector: [{base_gravity_vector[0]:.3f}, {base_gravity_vector[1]:.3f}, {base_gravity_vector[2]:.3f}]")
    
    if base_gravity_vector is None:
        print("⚠️  Warning: No baseGravityVector found, using Z-axis as vertical")
    
    # Parse data structure
    if 'data' in data:
        points = data['data']
    elif 'gpslogger2path' in data:
        gpslogger_data = data['gpslogger2path']
        if 'data' in gpslogger_data:
            points = gpslogger_data['data']
        else:
            points = gpslogger_data
    elif isinstance(data, list):
        points = data
    else:
        print(f"Unknown data structure. Keys: {list(data.keys())}")
        return
    
    print(f"📊 Total data points: {len(points)}")
    print()
    
    speed_humps_found = []
    near_misses_count = 0
    max_near_misses = 10  # Limit debug output
    analysis_summary = {
        'total_points': len(points),
        'points_with_raw_data': 0,
        'speed_humps_detected': 0,
        'low_speed_detections': 0,
        'high_speed_detections': 0
    }
    
    for i, point in enumerate(points):
        # Handle string data points
        if isinstance(point, str):
            try:
                point = json.loads(point)
            except json.JSONDecodeError:
                continue
        
        # Extract GPS coordinates
        lat = point.get('gps', {}).get('lat') if 'gps' in point else point.get('lat')
        lon = point.get('gps', {}).get('lon') if 'gps' in point else point.get('lon')
        speed = point.get('gps', {}).get('speed') if 'gps' in point else point.get('speed', 0)
        
        # Extract acceleration data
        accel_data = point.get('accel', {})
        raw_data = accel_data.get('raw', [])
        fwd_max = accel_data.get('fwdMax', 0.0)
        
        # Skip if no raw data or GPS coordinates
        if not raw_data or lat is None or lon is None:
            continue
        
        analysis_summary['points_with_raw_data'] += 1
        
        # Extract vertical acceleration (Z-axis)
        raw_vert_accel = [sample[2] if len(sample) > 2 else 0.0 for sample in raw_data]
        
        # Run speed hump detection with exact Kotlin calculations
        is_speed_hump, detection_info = compute_accel_metrics_kotlin_equivalent(raw_data, fwd_max, speed, base_gravity_vector)
        
        # Debug: Show top candidates that almost passed
        if not is_speed_hump and detection_info.get('gates_passed') and near_misses_count < max_near_misses:
            # Check if this point has interesting metrics (potential speed hump)
            if (fwd_max > 8.0 or detection_info.get('rms_vert', 0) > 2.0 or detection_info.get('num_peaks', 0) > 3):
                near_misses_count += 1
                print(f"🔍 NEAR MISS #{near_misses_count} - Point {i+1}: lat={lat:.6f}, lon={lon:.6f}")
                print(f"   Speed: {speed:.1f} km/h, fwdMax: {fwd_max:.2f}")
                print(f"   Metrics: RMS={detection_info.get('rms_vert', 0):.2f}, peaks={detection_info.get('num_peaks', 0)}, zero_cross={detection_info.get('zero_crossings', 0)}")
                print(f"   Gates: {' | '.join(detection_info['gates_passed'][:3])}")  # Show first 3 gates
                print()
        
        if is_speed_hump:
            analysis_summary['speed_humps_detected'] += 1
            if speed < 20.0:
                analysis_summary['low_speed_detections'] += 1
            else:
                analysis_summary['high_speed_detections'] += 1
            
            speed_humps_found.append({
                'point_index': i + 1,
                'lat': lat,
                'lon': lon,
                'speed': speed,
                'detection_info': detection_info
            })
            
            # Print detection details
            print(f"🎯 SPEED HUMP #{len(speed_humps_found)} - Point {i+1}")
            print(f"   📍 Location: lat={lat:.6f}, lon={lon:.6f}")
            print(f"   🚗 Speed: {speed:.1f} km/h ({detection_info['speed_category']})")
            print(f"   📈 fwdMax: {fwd_max:.2f}, Amplitude: {detection_info['peak_to_peak_amplitude']:.2f}")
            print(f"   📊 Peaks: {detection_info['num_peaks']}, Duration: {detection_info['duration']:.2f}s")
            if detection_info['decay_ratio']:
                print(f"   📉 Decay ratio: {detection_info['decay_ratio']:.3f}")
            print(f"   ✅ Gates: {' | '.join(detection_info['gates_passed'])}")
            print()
    
    # Summary
    print("=" * 80)
    print("📈 SPEED HUMP DETECTION SUMMARY")
    print("=" * 80)
    print(f"📊 Total points analyzed: {analysis_summary['total_points']}")
    print(f"📊 Points with raw data: {analysis_summary['points_with_raw_data']}")
    print(f"🎯 Speed humps detected: {analysis_summary['speed_humps_detected']}")
    print(f"   - Low speed detections: {analysis_summary['low_speed_detections']}")
    print(f"   - High speed detections: {analysis_summary['high_speed_detections']}")
    print()
    
    if speed_humps_found:
        print("📍 SPEED HUMP LOCATIONS:")
        for i, hump in enumerate(speed_humps_found, 1):
            info = hump['detection_info']
            print(f"   {i}. Point {hump['point_index']}: lat={hump['lat']:.6f}, lon={hump['lon']:.6f}")
            print(f"      Speed: {hump['speed']:.1f} km/h, fwdMax: {info['fwd_max']:.2f}")
            print(f"      Amplitude: {info['peak_to_peak_amplitude']:.2f}, Peaks: {info['num_peaks']}")
        
        # Export to CSV
        csv_file = json_file_path.replace('.json', '_speed_humps_detected.csv')
        with open(csv_file, 'w') as f:
            f.write("detection_number,point_index,lat,lon,speed_kmph,fwd_max,peak_to_peak_amplitude,num_peaks,duration,speed_category\n")
            for i, hump in enumerate(speed_humps_found, 1):
                info = hump['detection_info']
                f.write(f"{i},{hump['point_index']},{hump['lat']:.6f},{hump['lon']:.6f},{hump['speed']:.2f},{info['fwd_max']:.3f},{info['peak_to_peak_amplitude']:.3f},{info['num_peaks']},{info['duration']:.2f},{info['speed_category']}\n")
        
        print(f"\n💾 Exported speed hump locations to: {csv_file}")
        
        # Export to GPX for mapping
        gpx_file = json_file_path.replace('.json', '_speed_humps.gpx')
        with open(gpx_file, 'w') as f:
            f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
            f.write('<gpx version="1.1" creator="Speed Hump Detector">\n')
            f.write('  <trk>\n')
            f.write('    <name>Speed Humps</name>\n')
            f.write('    <trkseg>\n')
            for hump in speed_humps_found:
                f.write(f'      <trkpt lat="{hump["lat"]:.6f}" lon="{hump["lon"]:.6f}">\n')
                f.write(f'        <name>Speed Hump - {hump["speed"]:.1f} km/h</name>\n')
                f.write('      </trkpt>\n')
            f.write('    </trkseg>\n')
            f.write('  </trk>\n')
            f.write('</gpx>\n')
        
        print(f"🗺️  Exported GPX file for mapping: {gpx_file}")
        
    else:
        print("❌ No speed humps detected in the data.")
        print("💡 Consider:")
        print("   - Adjusting thresholds if you expect speed humps")
        print("   - Checking if the data contains speed breaker events")
        print("   - Verifying raw acceleration data quality")

def main():
    if len(sys.argv) != 2:
        print("Usage: python detect_speed_humps.py <json_file>")
        print("Example: python detect_speed_humps.py track.json")
        print("\nRedesigned speed hump detection algorithm with 5 gates:")
        print("  Gate 1: Minimum peak count")
        print("  Gate 2: Zero crossings (oscillations)")
        print("  Gate 3: Duration check")
        print("  Gate 4: Peak-to-peak amplitude")
        print("  Gate 5: Amplitude decay pattern")
        print("\nData-driven pattern detection:")
        print("  Low speed (< 20 km/h): peaks ≥ 8, amplitude ≥ 10.0")
        print("  High speed (≥ 20 km/h): peaks ≥ 12, amplitude ≥ 10.0")
        print("  (Based on 75th percentile analysis of your actual data)")
        print("  No fwdMax or RMS gates - pure oscillation pattern detection")
        print("\nBased on speed breaker physics: high forward accel + oscillation + decay")
        sys.exit(1)
    
    json_file = sys.argv[1]
    analyze_json_file(json_file)

if __name__ == "__main__":
    main()
