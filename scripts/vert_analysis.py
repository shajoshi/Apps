import json
import csv
import math
import sys
import os

def main():
    # 1. Handle Command Line Arguments
    if len(sys.argv) < 2:
        print("Usage: python script.py <input_file.json>")
        return

    input_path = sys.argv[1]
    base_name = os.path.splitext(input_path)[0]
    output_path = f"{base_name}_filtered_feature.csv"  # Appending _filtered for clarity

    # 2. Read Input File
    try:
        with open(input_path, 'r') as f:
            data = json.load(f)
    except FileNotFoundError:
        print(f"Error: File '{input_path}' not found.")
        return
    except json.JSONDecodeError:
        print(f"Error: Failed to decode JSON from '{input_path}'.")
        return

    # 3. Extract Base Gravity Vector
    try:
        meta = data['gpslogger2path']['meta']
        calibration = meta['recordingSettings']['calibration']
        g_vec = calibration['baseGravityVector']
        
        print(f"Processing: {input_path}")
        print(f"Found Gravity Vector: {g_vec}")
    except KeyError as e:
        print(f"Error: JSON structure mismatch. Could not find key: {e}")
        return

    # 4. Calculate Gravity Magnitude and Unit Vector
    g_magnitude = math.sqrt(g_vec['x']**2 + g_vec['y']**2 + g_vec['z']**2)
    
    if g_magnitude == 0:
        print("Error: Gravity vector magnitude is zero.")
        return

    unit_g = {
        'x': g_vec['x'] / g_magnitude,
        'y': g_vec['y'] / g_magnitude,
        'z': g_vec['z'] / g_magnitude
    }

    # 5. Scan ALL segments to build a complete list of headers
    segments = data['gpslogger2path'].get("data", [])
    
    all_gps_keys = set()
    all_accel_keys = set()
    
    for segment in segments:
        gps_data = segment.get('gps', {})
        all_gps_keys.update(gps_data.keys())
        
        accel_data = segment.get('accel', {})
        all_accel_keys.update(accel_data.keys())
    
    if 'raw' in all_accel_keys:
        all_accel_keys.remove('raw')
        
    sorted_gps_keys = sorted(list(all_gps_keys))
    sorted_accel_keys = sorted(list(all_accel_keys))

    # 6. Process Data Segments (With Filtering)
    processed_rows = []
    skipped_count = 0

    for segment in segments:
        accel_data = segment.get('accel', {})
        
        # --- FILTERING LOGIC START ---
        # Get the manualFeatureLabel value
        manual_label = accel_data.get('manualFeatureLabel')
        
        # Skip if None or empty string (we use strip() to catch string with only spaces)
        if manual_label is None or str(manual_label).strip() == "":
            skipped_count += 1
            continue
        # --- FILTERING LOGIC END ---

        gps_data = segment.get('gps', {})
        raw_samples = accel_data.get('raw', [])
        
        # Extract values
        current_gps_values = [gps_data.get(k, "") for k in sorted_gps_keys]
        current_accel_meta_values = [accel_data.get(k, "") for k in sorted_accel_keys]

        for sample in raw_samples:
            if len(sample) < 3:
                continue
                
            sx, sy, sz = sample[0], sample[1], sample[2]

            # Vector Projection
            projection = (sx * unit_g['x']) + (sy * unit_g['y']) + (sz * unit_g['z'])
            vertical_accel = projection - g_magnitude

            row = current_gps_values + current_accel_meta_values + [vertical_accel]
            processed_rows.append(row)

    # 7. Write to CSV
    if not processed_rows:
        print("No rows matched the 'manualFeatureLabel' filter. CSV was not created.")
        return

    try:
        with open(output_path, 'w', newline='') as f:
            writer = csv.writer(f)
            
            # Write Header
            full_header = sorted_gps_keys + sorted_accel_keys + ["Z accel"]
            writer.writerow(full_header)
            
            # Write Data
            writer.writerows(processed_rows)
        
        print(f"Success! Processed {len(processed_rows)} samples.")
        print(f"Skipped {skipped_count} segments that were missing a manualFeatureLabel.")
        print(f"Output saved to: {output_path}")
        
    except IOError as e:
        print(f"Error writing output file: {e}")

if __name__ == "__main__":
    main()