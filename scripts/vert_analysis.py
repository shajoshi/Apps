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
    
    # Generate output filename: input_name.json -> input_name.csv
    base_name = os.path.splitext(input_path)[0]
    output_path = f"{base_name}.csv"

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
    # This ensures fields like "manualLabel" appearing late in the file are included.
    segments = data['gpslogger2path'].get("data", [])
    
    # Use a set to collect unique keys from the 'accel' object across all segments
    all_metadata_keys = set()
    
    for segment in segments:
        accel_keys = segment.get('accel', {}).keys()
        all_metadata_keys.update(accel_keys)
    
    # Remove 'raw' from the metadata columns as it contains the samples we will process separately
    if 'raw' in all_metadata_keys:
        all_metadata_keys.remove('raw')
        
    # Sort keys for consistent column order in CSV
    sorted_metadata_keys = sorted(list(all_metadata_keys))

    # 6. Process Data Segments
    processed_rows = []

    for segment in segments:
        accel_data = segment.get('accel', {})
        raw_samples = accel_data.get('raw', [])
        
        # Extract values for the metadata columns for the current segment
        # We ensure a value exists for every key in the header, defaulting to empty string
        current_metadata_values = [accel_data.get(k, "") for k in sorted_metadata_keys]

        for sample in raw_samples:
            # Parse raw sample list: [x, y, z]
            if len(sample) < 3:
                continue
                
            sx, sy, sz = sample[0], sample[1], sample[2]

            # Vector Projection: Dot Product
            projection = (sx * unit_g['x']) + (sy * unit_g['y']) + (sz * unit_g['z'])

            # Compensate: Subtract static gravity
            vertical_accel = projection - g_magnitude

            # Combine metadata with the calculated value
            row = current_metadata_values + [vertical_accel]
            processed_rows.append(row)

    # 7. Write to CSV
    if not processed_rows:
        print("No raw samples found to process.")
        return

    try:
        with open(output_path, 'w', newline='') as f:
            writer = csv.writer(f)
            
            # Write Header (Metadata keys + new column)
            full_header = sorted_metadata_keys + ["Z accel"]
            writer.writerow(full_header)
            
            # Write Data
            writer.writerows(processed_rows)
        
        print(f"Success! Processed {len(processed_rows)} samples.")
        print(f"Columns included: {sorted_metadata_keys + ['Z accel']}")
        print(f"Output saved to: {output_path}")
        
    except IOError as e:
        print(f"Error writing output file: {e}")

if __name__ == "__main__":
    main()