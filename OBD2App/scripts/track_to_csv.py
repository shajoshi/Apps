#!/usr/bin/env python3
"""
OBD2 Track to CSV Converter - Convert JSON trip logs to CSV format

Usage:
    python track_to_csv.py track_file.json
    python track_to_csv.py track_file.json -o output.csv
    python track_to_csv.py track_file.json --all-fields

The script flattens the nested JSON structure into a CSV with one row per sample.
All available fields are included, with empty cells for missing data.
"""

import argparse
import json
import os
import csv
from typing import List, Dict, Set
from datetime import datetime

def flatten_sample(sample: dict, all_fields: Set[str]) -> Dict[str, str]:
    """
    Flatten a single sample object into a flat dictionary with all fields.
    Missing fields are filled with empty strings.
    """
    flat = {}
    
    # Always present fields
    flat['timestampMs'] = str(sample.get('timestampMs', ''))
    flat['sampleNo'] = str(sample.get('sampleNo', ''))
    
    # Flatten GPS sub-object
    gps = sample.get('gps', {})
    flat['gps.lat'] = str(gps.get('lat', ''))
    flat['gps.lon'] = str(gps.get('lon', ''))
    flat['gps.speedKmh'] = str(gps.get('speedKmh', ''))
    flat['gps.altMsl'] = str(gps.get('altMsl', ''))
    flat['gps.altEllipsoid'] = str(gps.get('altEllipsoid', ''))
    flat['gps.geoidUndulation'] = str(gps.get('geoidUndulation', ''))
    flat['gps.bearingDeg'] = str(gps.get('bearingDeg', ''))
    flat['gps.accuracyM'] = str(gps.get('accuracyM', ''))
    flat['gps.vertAccuracyM'] = str(gps.get('vertAccuracyM', ''))
    flat['gps.satelliteCount'] = str(gps.get('satelliteCount', ''))
    
    # Flatten OBD sub-object
    obd = sample.get('obd', {})
    flat['obd.rpm'] = str(obd.get('rpm', ''))
    flat['obd.speedKmh'] = str(obd.get('speedKmh', ''))
    flat['obd.engineLoadPct'] = str(obd.get('engineLoadPct', ''))
    flat['obd.throttlePct'] = str(obd.get('throttlePct', ''))
    flat['obd.coolantTempC'] = str(obd.get('coolantTempC', ''))
    flat['obd.intakeTempC'] = str(obd.get('intakeTempC', ''))
    flat['obd.oilTempC'] = str(obd.get('oilTempC', ''))
    flat['obd.ambientTempC'] = str(obd.get('ambientTempC', ''))
    flat['obd.fuelLevelPct'] = str(obd.get('fuelLevelPct', ''))
    flat['obd.fuelPressureKpa'] = str(obd.get('fuelPressureKpa', ''))
    flat['obd.fuelRateLh'] = str(obd.get('fuelRateLh', ''))
    flat['obd.mafGs'] = str(obd.get('mafGs', ''))
    flat['obd.intakeMapKpa'] = str(obd.get('intakeMapKpa', ''))
    flat['obd.baroPressureKpa'] = str(obd.get('baroPressureKpa', ''))
    flat['obd.timingAdvanceDeg'] = str(obd.get('timingAdvanceDeg', ''))
    flat['obd.stftPct'] = str(obd.get('stftPct', ''))
    flat['obd.ltftPct'] = str(obd.get('ltftPct', ''))
    flat['obd.stftBank2Pct'] = str(obd.get('stftBank2Pct', ''))
    flat['obd.ltftBank2Pct'] = str(obd.get('ltftBank2Pct', ''))
    flat['obd.o2Voltage'] = str(obd.get('o2Voltage', ''))
    flat['obd.controlModuleVoltage'] = str(obd.get('controlModuleVoltage', ''))
    flat['obd.runTimeSec'] = str(obd.get('runTimeSec', ''))
    flat['obd.distanceMilOnKm'] = str(obd.get('distanceMilOnKm', ''))
    flat['obd.distanceSinceCleared'] = str(obd.get('distanceSinceCleared', ''))
    flat['obd.absoluteLoadPct'] = str(obd.get('absoluteLoadPct', ''))
    flat['obd.relativeThrottlePct'] = str(obd.get('relativeThrottlePct', ''))
    flat['obd.accelPedalDPct'] = str(obd.get('accelPedalDPct', ''))
    flat['obd.accelPedalEPct'] = str(obd.get('accelPedalEPct', ''))
    flat['obd.commandedThrottlePct'] = str(obd.get('commandedThrottlePct', ''))
    flat['obd.timeMilOnMin'] = str(obd.get('timeMilOnMin', ''))
    flat['obd.timeSinceClearedMin'] = str(obd.get('timeSinceClearedMin', ''))
    flat['obd.ethanolPct'] = str(obd.get('ethanolPct', ''))
    flat['obd.hybridBatteryPct'] = str(obd.get('hybridBatteryPct', ''))
    flat['obd.fuelInjectionTimingDeg'] = str(obd.get('fuelInjectionTimingDeg', ''))
    flat['obd.driverDemandTorquePct'] = str(obd.get('driverDemandTorquePct', ''))
    flat['obd.actualTorquePct'] = str(obd.get('actualTorquePct', ''))
    flat['obd.engineReferenceTorqueNm'] = str(obd.get('engineReferenceTorqueNm', ''))
    flat['obd.catalystTempB1S1C'] = str(obd.get('catalystTempB1S1C', ''))
    flat['obd.catalystTempB2S1C'] = str(obd.get('catalystTempB2S1C', ''))
    flat['obd.fuelSystemStatus'] = str(obd.get('fuelSystemStatus', ''))
    flat['obd.monitorStatus'] = str(obd.get('monitorStatus', ''))
    flat['obd.fuelTypeStr'] = str(obd.get('fuelTypeStr', ''))
    
    # Flatten fuel sub-object
    fuel = sample.get('fuel', {})
    flat['fuel.fuelRateEffectiveLh'] = str(fuel.get('fuelRateEffectiveLh', ''))
    flat['fuel.instantLper100km'] = str(fuel.get('instantLper100km', ''))
    flat['fuel.instantKpl'] = str(fuel.get('instantKpl', ''))
    flat['fuel.tripFuelUsedL'] = str(fuel.get('tripFuelUsedL', ''))
    flat['fuel.tripAvgLper100km'] = str(fuel.get('tripAvgLper100km', ''))
    flat['fuel.tripAvgKpl'] = str(fuel.get('tripAvgKpl', ''))
    flat['fuel.fuelFlowCcMin'] = str(fuel.get('fuelFlowCcMin', ''))
    flat['fuel.rangeRemainingKm'] = str(fuel.get('rangeRemainingKm', ''))
    flat['fuel.fuelCostEstimate'] = str(fuel.get('fuelCostEstimate', ''))
    flat['fuel.avgCo2gPerKm'] = str(fuel.get('avgCo2gPerKm', ''))
    flat['fuel.powerAccelKw'] = str(fuel.get('powerAccelKw', ''))
    flat['fuel.powerThermoKw'] = str(fuel.get('powerThermoKw', ''))
    flat['fuel.powerOBDKw'] = str(fuel.get('powerOBDKw', ''))
    
    # Flatten accel sub-object (only if present)
    accel = sample.get('accel', {})
    if accel:
        flat['accel.vertRms'] = str(accel.get('vertRms', ''))
        flat['accel.vertMax'] = str(accel.get('vertMax', ''))
        flat['accel.vertMean'] = str(accel.get('vertMean', ''))
        flat['accel.vertStdDev'] = str(accel.get('vertStdDev', ''))
        flat['accel.vertPeakRatio'] = str(accel.get('vertPeakRatio', ''))
        flat['accel.fwdRms'] = str(accel.get('fwdRms', ''))
        flat['accel.fwdMax'] = str(accel.get('fwdMax', ''))
        flat['accel.fwdMaxBrake'] = str(accel.get('fwdMaxBrake', ''))
        flat['accel.fwdMaxAccel'] = str(accel.get('fwdMaxAccel', ''))
        flat['accel.fwdMean'] = str(accel.get('fwdMean', ''))
        flat['accel.latRms'] = str(accel.get('latRms', ''))
        flat['accel.latMax'] = str(accel.get('latMax', ''))
        flat['accel.latMean'] = str(accel.get('latMean', ''))
        flat['accel.leanAngleDeg'] = str(accel.get('leanAngleDeg', ''))
        flat['accel.rawSampleCount'] = str(accel.get('rawSampleCount', ''))
    
    # Flatten trip sub-object
    trip = sample.get('trip', {})
    flat['trip.distanceKm'] = str(trip.get('distanceKm', ''))
    flat['trip.timeSec'] = str(trip.get('timeSec', ''))
    flat['trip.movingTimeSec'] = str(trip.get('movingTimeSec', ''))
    flat['trip.stoppedTimeSec'] = str(trip.get('stoppedTimeSec', ''))
    flat['trip.avgSpeedKmh'] = str(trip.get('avgSpeedKmh', ''))
    flat['trip.maxSpeedKmh'] = str(trip.get('maxSpeedKmh', ''))
    flat['trip.spdDiffKmh'] = str(trip.get('spdDiffKmh', ''))
    flat['trip.pctCity'] = str(trip.get('pctCity', ''))
    flat['trip.pctHighway'] = str(trip.get('pctHighway', ''))
    flat['trip.pctIdle'] = str(trip.get('pctIdle', ''))
    
    # Ensure all expected fields are present
    for field in all_fields:
        if field not in flat:
            flat[field] = ''
    
    return flat

def get_all_possible_fields(samples: List[dict]) -> List[str]:
    """
    Scan all samples and return a list of all possible fields.
    This ensures consistent column order even if some samples have missing data.
    """
    all_fields = set()
    
    # Base fields
    all_fields.update(['timestampMs', 'sampleNo'])
    
    # GPS fields
    gps_fields = [
        'gps.lat', 'gps.lon', 'gps.speedKmh', 'gps.altMsl', 'gps.altEllipsoid',
        'gps.geoidUndulation', 'gps.bearingDeg', 'gps.accuracyM',
        'gps.vertAccuracyM', 'gps.satelliteCount'
    ]
    all_fields.update(gps_fields)
    
    # OBD fields
    obd_fields = [
        'obd.rpm', 'obd.speedKmh', 'obd.engineLoadPct', 'obd.throttlePct',
        'obd.coolantTempC', 'obd.intakeTempC', 'obd.oilTempC', 'obd.ambientTempC',
        'obd.fuelLevelPct', 'obd.fuelPressureKpa', 'obd.fuelRateLh', 'obd.mafGs',
        'obd.intakeMapKpa', 'obd.baroPressureKpa', 'obd.timingAdvanceDeg',
        'obd.stftPct', 'obd.ltftPct', 'obd.stftBank2Pct', 'obd.ltftBank2Pct',
        'obd.o2Voltage', 'obd.controlModuleVoltage', 'obd.runTimeSec',
        'obd.distanceMilOnKm', 'obd.distanceSinceCleared', 'obd.absoluteLoadPct',
        'obd.relativeThrottlePct', 'obd.accelPedalDPct', 'obd.accelPedalEPct',
        'obd.commandedThrottlePct', 'obd.timeMilOnMin', 'obd.timeSinceClearedMin',
        'obd.ethanolPct', 'obd.hybridBatteryPct', 'obd.fuelInjectionTimingDeg',
        'obd.driverDemandTorquePct', 'obd.actualTorquePct',
        'obd.engineReferenceTorqueNm', 'obd.catalystTempB1S1C',
        'obd.catalystTempB2S1C', 'obd.fuelSystemStatus', 'obd.monitorStatus',
        'obd.fuelTypeStr'
    ]
    all_fields.update(obd_fields)
    
    # Fuel fields
    fuel_fields = [
        'fuel.fuelRateEffectiveLh', 'fuel.instantLper100km', 'fuel.instantKpl',
        'fuel.tripFuelUsedL', 'fuel.tripAvgLper100km', 'fuel.tripAvgKpl',
        'fuel.fuelFlowCcMin', 'fuel.rangeRemainingKm', 'fuel.fuelCostEstimate',
        'fuel.avgCo2gPerKm', 'fuel.powerAccelKw', 'fuel.powerThermoKw',
        'fuel.powerOBDKw'
    ]
    all_fields.update(fuel_fields)
    
    # Accel fields
    accel_fields = [
        'accel.vertRms', 'accel.vertMax', 'accel.vertMean', 'accel.vertStdDev',
        'accel.vertPeakRatio', 'accel.fwdRms', 'accel.fwdMax', 'accel.fwdMaxBrake',
        'accel.fwdMaxAccel', 'accel.fwdMean', 'accel.latRms', 'accel.latMax',
        'accel.latMean', 'accel.leanAngleDeg', 'accel.rawSampleCount'
    ]
    all_fields.update(accel_fields)
    
    # Trip fields
    trip_fields = [
        'trip.distanceKm', 'trip.timeSec', 'trip.movingTimeSec',
        'trip.stoppedTimeSec', 'trip.avgSpeedKmh', 'trip.maxSpeedKmh',
        'trip.spdDiffKmh', 'trip.pctCity', 'trip.pctHighway', 'trip.pctIdle'
    ]
    all_fields.update(trip_fields)
    
    return sorted(all_fields)

def convert_json_to_csv(input_file: str, output_file: str, all_fields_flag: bool = False):
    """Convert JSON track file to CSV format"""
    
    print(f"Loading JSON file: {input_file}")
    
    with open(input_file, 'r') as f:
        data = json.load(f)
    
    header = data.get('header', {})
    samples = data.get('samples', [])
    
    if not samples:
        print("No samples found in JSON file")
        return
    
    print(f"Found {len(samples)} samples")
    
    # Get all possible fields for consistent column order
    if all_fields_flag:
        field_list = get_all_possible_fields(samples)
        print(f"Using all {len(field_list)} possible fields")
    else:
        # Only include fields that actually appear in the data
        field_set = set()
        for sample in samples:
            flat_sample = flatten_sample(sample, set())
            field_set.update(flat_sample.keys())
        field_list = sorted(field_set)
        print(f"Using {len(field_list)} fields found in data")
    
    # Write CSV
    print(f"Writing CSV file: {output_file}")
    
    with open(output_file, 'w', newline='', encoding='utf-8') as csvfile:
        writer = csv.DictWriter(csvfile, fieldnames=field_list)
        writer.writeheader()
        
        for i, sample in enumerate(samples):
            flat_sample = flatten_sample(sample, set(field_list))
            
            # Only include fields we want in the output
            row = {field: flat_sample.get(field, '') for field in field_list}
            writer.writerow(row)
            
            if (i + 1) % 1000 == 0:
                print(f"Processed {i + 1}/{len(samples)} samples")
    
    print(f"Conversion complete!")
    print(f"CSV file: {output_file}")
    print(f"Columns: {len(field_list)}")
    print(f"Rows: {len(samples) + 1} (including header)")
    
    # Add metadata comment at the top of CSV
    metadata_file = output_file.replace('.csv', '_metadata.txt')
    with open(metadata_file, 'w') as f:
        f.write(f"OBD2 Track Conversion Metadata\n")
        f.write(f"===============================\n\n")
        f.write(f"Source file: {input_file}\n")
        f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write(f"Samples: {len(samples)}\n")
        f.write(f"Columns: {len(field_list)}\n\n")
        
        if header.get('vehicleProfile', {}).get('name'):
            f.write(f"Vehicle: {header['vehicleProfile']['name']}\n")
        if header.get('appVersion'):
            f.write(f"App Version: {header['appVersion']}\n")
        if header.get('logStartedAt'):
            f.write(f"Log Started: {header['logStartedAt']}\n")
        
        f.write(f"\nColumns included:\n")
        for field in field_list:
            f.write(f"  - {field}\n")
    
    print(f"Metadata saved: {metadata_file}")

def main():
    parser = argparse.ArgumentParser(description='Convert OBD2 JSON track files to CSV format')
    parser.add_argument('input_file', help='Input JSON track file')
    parser.add_argument('-o', '--output', help='Output CSV file (default: input_file.csv)')
    parser.add_argument('--all-fields', action='store_true',
                       help='Include all possible fields, even if not present in data')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input_file):
        print(f"Error: Input file '{args.input_file}' not found")
        return
    
    # Determine output file
    if args.output:
        output_file = args.output
    else:
        base_name = os.path.splitext(args.input_file)[0]
        output_file = f"{base_name}.csv"
    
    try:
        convert_json_to_csv(args.input_file, output_file, args.all_fields)
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == '__main__':
    main()
