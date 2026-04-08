"""
Track file loading and parsing utilities.
Loads OBD2 track JSON files and vehicle profiles.
"""

import json
from typing import List, Dict, Any, Optional
from pathlib import Path
from obd_types import (
    VehicleProfile, FuelType, TrackSample, GpsData, ObdData
)
from json_repair import JSONRepair


class TrackLoader:
    """Loads and parses OBD2 track JSON files."""
    
    @staticmethod
    def load_vehicle_profile(profile_path: str) -> VehicleProfile:
        """
        Load vehicle profile from JSON file.
        
        Args:
            profile_path: Path to vehicle profile JSON
            
        Returns:
            VehicleProfile object
        """
        with open(profile_path, 'r') as f:
            data = json.load(f)
        
        # Parse fuel type
        fuel_type_str = data.get('fuelType', 'PETROL').upper()
        try:
            fuel_type = FuelType[fuel_type_str]
        except KeyError:
            print(f"Warning: Unknown fuel type '{fuel_type_str}', defaulting to PETROL")
            fuel_type = FuelType.PETROL
        
        return VehicleProfile(
            id=data.get('id', 'unknown'),
            name=data.get('name', 'Unknown Vehicle'),
            fuel_type=fuel_type,
            tank_capacity_l=float(data.get('tankCapacityL', 40.0)),
            fuel_price_per_litre=float(data.get('fuelPricePerLitre', 0.0)),
            engine_power_bhp=float(data.get('enginePowerBhp', 0.0)),
            vehicle_mass_kg=float(data.get('vehicleMassKg', 0.0)),
            engine_displacement_cc=int(data.get('engineDisplacementCc', 0)),
            volumetric_efficiency_pct=float(data.get('volumetricEfficiencyPct', 85.0)),
            available_pids=data.get('availablePids', {}),
            custom_pids=data.get('customPids', [])
        )
    
    @staticmethod
    def load_track_file(track_path: str) -> tuple[Dict[str, Any], List[TrackSample]]:
        """
        Load track file and parse samples.
        
        Args:
            track_path: Path to track JSON file
            
        Returns:
            Tuple of (header dict, list of TrackSample)
        """
        try:
            with open(track_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except json.JSONDecodeError as e:
            # Provide detailed error information for troubleshooting
            error_line = e.lineno
            error_col = e.colno
            error_pos = e.pos
            
            # Read the file to show context around the error
            try:
                with open(track_path, 'r', encoding='utf-8') as f:
                    content = f.read()
                
                # Show context around the error
                lines = content.split('\n')
                context_start = max(0, error_line - 3)
                context_end = min(len(lines), error_line + 2)
                
                print(f"\n❌ JSON Error in {track_path}:")
                print(f"   Error at line {error_line}, column {error_col}")
                print(f"   Error message: {e.msg}")
                print(f"\n   Context around error:")
                
                for i in range(context_start, context_end):
                    marker = " >>> " if i + 1 == error_line else "     "
                    print(f"   {marker}Line {i+1:4d}: {lines[i]}")
                
                # Show the problematic character
                if error_pos < len(content):
                    char = content[error_pos]
                    print(f"\n   Problematic character: '{char}' (code: {ord(char)})")
                
            except Exception as read_error:
                print(f"❌ Could not read file for error context: {read_error}")
            
            # Attempt JSON repair
            print(f"\n🔧 Attempting to repair corrupted JSON file...")
            success, message = JSONRepair.attempt_repair(track_path)
            
            if success:
                print(f"✅ {message}")
                print("🔄 Retrying with repaired file...")
                try:
                    # Try loading again after repair
                    with open(track_path, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                except Exception as retry_error:
                    print(f"❌ Repair failed: {retry_error}")
                    raise ValueError(f"Could not load track file even after repair: {retry_error}") from retry_error
            else:
                print(f"❌ Repair failed: {message}")
                
                # Try to extract partial data
                print("🔍 Attempting to extract partial data...")
                try:
                    with open(track_path, 'r', encoding='utf-8') as f:
                        content = f.read()
                    
                    partial_samples = JSONRepair.extract_partial_samples(content)
                    if partial_samples:
                        print(f"✅ Extracted {len(partial_samples)} partial samples")
                        # Create minimal header and return partial data
                        header = {"extracted": True, "partial": True, "original_error": str(e)}
                        samples = []
                        for sample_data in partial_samples:
                            sample = TrackLoader._parse_sample(sample_data)
                            if sample:
                                samples.append(sample)
                        return header, samples
                    else:
                        print("❌ Could not extract any valid data")
                except Exception as extract_error:
                    print(f"❌ Partial extraction failed: {extract_error}")
                
                raise ValueError(f"Invalid JSON format in {track_path}: {e.msg}") from e
        except UnicodeDecodeError as e:
            raise ValueError(f"File encoding error in {track_path}: {e}") from e
        except FileNotFoundError:
            raise FileNotFoundError(f"Track file not found: {track_path}")
        except Exception as e:
            raise ValueError(f"Error reading track file {track_path}: {e}") from e
        
        header = data.get('header', {})
        samples_data = data.get('samples', [])
        
        samples = []
        for sample_data in samples_data:
            sample = TrackLoader._parse_sample(sample_data)
            if sample:
                samples.append(sample)
        
        return header, samples
    
    @staticmethod
    def _parse_sample(sample_data: Dict[str, Any]) -> Optional[TrackSample]:
        """Parse a single sample from JSON."""
        try:
            timestamp_ms = sample_data.get('timestampMs')
            sample_no = sample_data.get('sampleNo')
            
            if timestamp_ms is None or sample_no is None:
                return None
            
            # Parse GPS data
            gps_data = None
            if 'gps' in sample_data:
                gps_dict = sample_data['gps']
                gps_data = GpsData(
                    lat=gps_dict.get('lat'),
                    lon=gps_dict.get('lon'),
                    speed_kmh=gps_dict.get('speedKmh'),
                    alt_msl=gps_dict.get('altMsl'),
                    alt_ellipsoid=gps_dict.get('altEllipsoid'),
                    geoid_undulation=gps_dict.get('geoidUndulation'),
                    accuracy_m=gps_dict.get('accuracyM'),
                    vert_accuracy_m=gps_dict.get('vertAccuracyM'),
                    bearing_deg=gps_dict.get('bearingDeg'),
                    satellite_count=gps_dict.get('satelliteCount')
                )
            
            # Parse OBD data
            obd_data = None
            if 'obd' in sample_data:
                obd_dict = sample_data['obd']
                obd_data = ObdData(
                    rpm=obd_dict.get('rpm'),
                    speed_kmh=obd_dict.get('speedKmh'),
                    engine_load_pct=obd_dict.get('engineLoadPct'),
                    throttle_pct=obd_dict.get('throttlePct'),
                    coolant_temp_c=obd_dict.get('coolantTempC'),
                    intake_temp_c=obd_dict.get('intakeTempC'),
                    oil_temp_c=obd_dict.get('oilTempC'),
                    ambient_temp_c=obd_dict.get('ambientTempC'),
                    fuel_level_pct=obd_dict.get('fuelLevelPct'),
                    fuel_pressure_kpa=obd_dict.get('fuelPressureKpa'),
                    fuel_rate_lh=obd_dict.get('fuelRateLh'),
                    maf_gs=obd_dict.get('mafGs'),
                    intake_map_kpa=obd_dict.get('intakeMapKpa'),
                    baro_pressure_kpa=obd_dict.get('baroPressureKpa'),
                    timing_advance_deg=obd_dict.get('timingAdvanceDeg'),
                    stft_pct=obd_dict.get('stftPct'),
                    ltft_pct=obd_dict.get('ltftPct'),
                    o2_voltage=obd_dict.get('o2Voltage'),
                    control_module_voltage=obd_dict.get('controlModuleVoltage'),
                    run_time_sec=obd_dict.get('runTimeSec'),
                    distance_mil_on_km=obd_dict.get('distanceMilOnKm'),
                    distance_since_cleared=obd_dict.get('distanceSinceCleared'),
                    absolute_load_pct=obd_dict.get('absoluteLoadPct'),
                    relative_throttle_pct=obd_dict.get('relativeThrottlePct'),
                    time_mil_on_min=obd_dict.get('timeMilOnMin'),
                    fuel_system_status=obd_dict.get('fuelSystemStatus'),
                    monitor_status=obd_dict.get('monitorStatus')
                )
            
            return TrackSample(
                timestamp_ms=timestamp_ms,
                sample_no=sample_no,
                gps=gps_data,
                obd=obd_data,
                fuel=sample_data.get('fuel')
            )
        
        except Exception as e:
            print(f"Warning: Failed to parse sample {sample_data.get('sampleNo', '?')}: {e}")
            return None
    
    @staticmethod
    def get_track_name(track_path: str) -> str:
        """Extract a friendly name from track file path."""
        return Path(track_path).stem
