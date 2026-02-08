import json
import csv
import math
import sys
import os
import argparse


def main():
    # 1. Set up Argument Parser
    parser = argparse.ArgumentParser(
        description="Extract all GPS and accel attributes from a tracking JSON file to CSV. "
                    "If raw accel data is present, one row is created per raw sample with "
                    "GPS and accel attributes repeated; otherwise one row per data point."
    )
    parser.add_argument("input_file", help="Path to the input JSON file.")
    parser.add_argument("--filterby",
                        help="Column name to filter by (e.g., manualFeatureLabel). "
                             "Only rows with a non-empty value in this field are kept.",
                        default=None)

    args = parser.parse_args()
    input_path = args.input_file
    filter_label = args.filterby

    # Generate output filename
    base_name = os.path.splitext(input_path)[0]
    if filter_label:
        output_path = f"{base_name}_filtered_{filter_label}.csv"
    else:
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

    # 3. Extract metadata and gravity vector (if available)
    try:
        meta = data['gpslogger2path']['meta']
        print(f"Processing: {input_path}")
        print(f"Recording: {meta.get('name', '?')}")

        calibration = meta.get('recordingSettings', {}).get('calibration', {})
        g_vec = calibration.get('baseGravityVector')
        if g_vec:
            print(f"Gravity Vector: x={g_vec.get('x')}, y={g_vec.get('y')}, z={g_vec.get('z')}")
        else:
            print("No baseGravityVector found in calibration.")

        if filter_label:
            print(f"Filtering: keeping only rows where '{filter_label}' is present.")
        else:
            print("Filtering: disabled (all rows).")
    except KeyError as e:
        print(f"Error: JSON structure mismatch. Could not find key: {e}")
        return

    # 4. Calculate gravity unit vector (for vertical projection of raw samples)
    unit_g = None
    g_magnitude = 0.0
    if g_vec:
        gx, gy, gz = g_vec.get('x', 0), g_vec.get('y', 0), g_vec.get('z', 0)
        g_magnitude = math.sqrt(gx**2 + gy**2 + gz**2)
        if g_magnitude > 0:
            unit_g = {'x': gx / g_magnitude, 'y': gy / g_magnitude, 'z': gz / g_magnitude}

    # 5. Scan ALL data points to discover the full set of GPS and accel keys
    segments = data['gpslogger2path'].get("data", [])
    if not segments:
        print("No data points found.")
        return

    all_gps_keys = set()
    all_accel_keys = set()
    has_raw = False

    for segment in segments:
        gps_data = segment.get('gps', {})
        all_gps_keys.update(gps_data.keys())

        accel_data = segment.get('accel', {})
        for k in accel_data.keys():
            if k == 'raw':
                has_raw = True
            else:
                all_accel_keys.add(k)

    # Prefix keys for clarity in CSV: gps_* and accel_*
    sorted_gps_keys = sorted(list(all_gps_keys))
    sorted_accel_keys = sorted(list(all_accel_keys))

    # Build header
    header = [f"gps_{k}" for k in sorted_gps_keys] + \
             [f"accel_{k}" for k in sorted_accel_keys]

    if has_raw:
        header += ["raw_x", "raw_y", "raw_z"]
        if unit_g:
            header += ["raw_vert_accel"]

    # 6. Process data points
    processed_rows = []
    skipped_count = 0

    for segment in segments:
        accel_data = segment.get('accel', {})

        # --- FILTERING LOGIC ---
        if filter_label:
            label_value = accel_data.get(filter_label)
            if label_value is None or str(label_value).strip() == "":
                skipped_count += 1
                continue
        # -----------------------

        gps_data = segment.get('gps', {})
        raw_samples = accel_data.get('raw', [])

        gps_values = [gps_data.get(k, "") for k in sorted_gps_keys]
        accel_values = [accel_data.get(k, "") for k in sorted_accel_keys]
        base_row = gps_values + accel_values

        if raw_samples:
            # One row per raw sample, GPS + accel attributes repeated
            for sample in raw_samples:
                if len(sample) < 3:
                    continue
                sx, sy, sz = sample[0], sample[1], sample[2]
                raw_cols = [sx, sy, sz]
                if unit_g:
                    projection = (sx * unit_g['x']) + (sy * unit_g['y']) + (sz * unit_g['z'])
                    vert_accel = projection - g_magnitude
                    raw_cols.append(vert_accel)
                processed_rows.append(base_row + raw_cols)
        else:
            # No raw data — one row per data point
            if has_raw:
                # Pad raw columns with empty values for consistent CSV shape
                pad = ["", "", ""]
                if unit_g:
                    pad.append("")
                processed_rows.append(base_row + pad)
            else:
                processed_rows.append(base_row)

    # 7. Write to CSV
    if not processed_rows:
        if filter_label:
            print(f"No rows matched the '{filter_label}' filter. CSV was not created.")
        else:
            print("No data found in file.")
        return

    try:
        with open(output_path, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(header)
            writer.writerows(processed_rows)

        print(f"Success! Wrote {len(processed_rows)} rows.")
        if filter_label:
            print(f"Skipped {skipped_count} segments missing '{filter_label}'.")
        print(f"Output: {output_path}")

    except IOError as e:
        print(f"Error writing output file: {e}")


if __name__ == "__main__":
    main()