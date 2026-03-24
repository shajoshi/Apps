"""
CSV export functionality for OBD2 metrics analysis.
Exports raw sample data and calculated metrics to CSV files.
"""

import csv
import json
import logging
from typing import List, Dict, Any, Set
from pathlib import Path
from obd_types import TrackSample, TripMetrics, VehicleProfile


class CSVExporter:
    """Exports OBD2 data to CSV format for further analysis."""
    
    def __init__(self, verbose: bool = False):
        """
        Initialize CSV exporter with optional verbose logging.
        
        Args:
            verbose: Enable detailed logging output
        """
        self.verbose = verbose
        if verbose:
            logging.basicConfig(
                level=logging.INFO,
                format='%(asctime)s - %(levelname)s - %(message)s',
                datefmt='%H:%M:%S'
            )
            self.logger = logging.getLogger(__name__)
        else:
            self.logger = logging.getLogger(__name__)
            self.logger.disabled = True
    
    @staticmethod
    def discover_fields_from_json(json_file_path: str) -> Dict[str, Set[str]]:
        """
        Discover all available GPS, OBD, fuel, and other fields from a trip JSON file.
        
        Args:
            json_file_path: Path to trip JSON file
            
        Returns:
            Dictionary with field sets for different data categories
        """
        gps_fields = set()
        obd_fields = set()
        fuel_fields = set()
        accel_fields = set()
        trip_fields = set()
        other_fields = set()
        
        try:
            with open(json_file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            if 'samples' in data:
                for sample in data['samples']:
                    # Discover GPS fields
                    if 'gps' in sample and sample['gps']:
                        gps_fields.update(sample['gps'].keys())
                    
                    # Discover OBD fields
                    if 'obd' in sample and sample['obd']:
                        obd_fields.update(sample['obd'].keys())
                    
                    # Discover fuel fields
                    if 'fuel' in sample and sample['fuel']:
                        fuel_fields.update(sample['fuel'].keys())
                    
                    # Discover accelerometer fields
                    if 'accel' in sample and sample['accel']:
                        accel_fields.update(sample['accel'].keys())
                    
                    # Discover trip fields
                    if 'trip' in sample and sample['trip']:
                        trip_fields.update(sample['trip'].keys())
                    
                    # Discover other top-level fields
                    for key, value in sample.items():
                        if key not in ['timestampMs', 'sampleNo', 'gps', 'obd', 'fuel', 'accel', 'trip']:
                            if isinstance(value, (str, int, float, bool)):
                                other_fields.add(key)
                        
        except Exception as e:
            print(f"Error reading JSON file: {e}")
            
        return {
            'gps': gps_fields, 
            'obd': obd_fields, 
            'fuel': fuel_fields,
            'accel': accel_fields,
            'trip': trip_fields,
            'other': other_fields
        }
    
    @staticmethod
    def export_samples_csv(
        samples: List[TrackSample],
        track_name: str,
        output_dir: str = '.',
        verbose: bool = False,
        custom_fields: Dict[str, Set[str]] = None
    ):
        """
        Export raw sample data to CSV with generic field detection.
        
        Args:
            samples: List of track samples
            track_name: Name for CSV file
            output_dir: Output directory for CSV file
            verbose: Enable verbose logging
            custom_fields: Custom GPS and OBD fields to include
        """
        logger = logging.getLogger(__name__) if verbose else None
        if verbose and logger:
            logger.info(f"Starting CSV export for track: {track_name}")
            logger.info(f"Processing {len(samples)} samples")
        
        output_path = Path(output_dir) / f"{track_name}_samples.csv"
        
        # Discover fields from samples if custom fields not provided
        if custom_fields is None:
            gps_fields = set()
            obd_fields = set()
            
            if verbose and logger:
                logger.info("Discovering fields from samples...")
            
            for sample in samples:
                if sample.gps:
                    gps_fields.update([attr for attr in dir(sample.gps) 
                                     if not attr.startswith('_') and getattr(sample.gps, attr) is not None])
                if sample.obd:
                    obd_fields.update([attr for attr in dir(sample.obd) 
                                     if not attr.startswith('_') and getattr(sample.obd, attr) is not None])
        else:
            gps_fields = custom_fields.get('gps', set())
            obd_fields = custom_fields.get('obd', set())
        
        if verbose and logger:
            logger.info(f"Found GPS fields: {sorted(gps_fields)}")
            logger.info(f"Found OBD fields: {sorted(obd_fields)}")
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            # Build dynamic fieldnames
            fieldnames = ['timestamp_ms', 'sample_no']
            
            # Add GPS fields with prefix
            for field in sorted(gps_fields):
                fieldnames.append(f'gps_{field}')
            
            # Add OBD fields with prefix
            for field in sorted(obd_fields):
                fieldnames.append(f'obd_{field}')
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            if verbose and logger:
                logger.info(f"CSV headers: {fieldnames}")
            
            # Write sample data
            for i, sample in enumerate(samples):
                if verbose and logger and i % 100 == 0 and i > 0:
                    logger.info(f"Processed {i}/{len(samples)} samples")
                
                row = {
                    'timestamp_ms': sample.timestamp_ms,
                    'sample_no': sample.sample_no
                }
                
                # Add GPS data
                if sample.gps:
                    for field in gps_fields:
                        value = getattr(sample.gps, field, None)
                        row[f'gps_{field}'] = value
                else:
                    for field in gps_fields:
                        row[f'gps_{field}'] = None
                
                # Add OBD data
                if sample.obd:
                    for field in obd_fields:
                        value = getattr(sample.obd, field, None)
                        row[f'obd_{field}'] = value
                else:
                    for field in obd_fields:
                        row[f'obd_{field}'] = None
                
                writer.writerow(row)
        
        print(f"✓ Exported samples CSV: {output_path}")
        if verbose and logger:
            logger.info(f"Successfully exported {len(samples)} samples to {output_path}")
    
    @staticmethod
    def export_metrics_csv(
        track_metrics: Dict[str, TripMetrics],
        profile: VehicleProfile,
        output_dir: str = '.'
    ):
        """
        Export calculated metrics to CSV.
        
        Args:
            track_metrics: Dictionary of track name -> TripMetrics
            profile: Vehicle profile
            output_dir: Output directory for CSV file
        """
        output_path = Path(output_dir) / "track_metrics_summary.csv"
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = [
                'track_name', 'distance_km', 'fuel_used_l', 'avg_fuel_lper100km', 'avg_fuel_kpl',
                'avg_speed_kmh', 'max_speed_kmh', 'moving_time_sec', 'stopped_time_sec', 'total_time_sec',
                'pct_idle', 'pct_city', 'pct_highway', 'fuel_cost', 'avg_co2_g_per_km', 'sample_count'
            ]
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            # Write metrics for each track
            for track_name, metrics in track_metrics.items():
                row = {
                    'track_name': track_name,
                    'distance_km': metrics.distance_km,
                    'fuel_used_l': metrics.fuel_used_l,
                    'avg_fuel_lper100km': metrics.avg_fuel_lper100km,
                    'avg_fuel_kpl': metrics.avg_fuel_kpl,
                    'avg_speed_kmh': metrics.avg_speed_kmh,
                    'max_speed_kmh': metrics.max_speed_kmh,
                    'moving_time_sec': metrics.moving_time_sec,
                    'stopped_time_sec': metrics.stopped_time_sec,
                    'total_time_sec': metrics.total_time_sec,
                    'pct_idle': metrics.pct_idle,
                    'pct_city': metrics.pct_city,
                    'pct_highway': metrics.pct_highway,
                    'fuel_cost': metrics.fuel_cost,
                    'avg_co2_g_per_km': metrics.avg_co2_g_per_km,
                    'sample_count': metrics.sample_count
                }
                writer.writerow(row)
        
        print(f"✓ Exported metrics summary CSV: {output_path}")
    
    @staticmethod
    def export_calculated_samples_csv(
        samples: List[TrackSample],
        track_name: str,
        profile: VehicleProfile,
        output_dir: str = '.'
    ):
        """
        Export samples with calculated values (hybrid speed, fuel efficiency, drive mode).
        
        Args:
            samples: List of track samples
            track_name: Name for CSV file
            profile: Vehicle profile
            output_dir: Output directory for CSV file
        """
        output_path = Path(output_dir) / f"{track_name}_calculated.csv"
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = [
                'timestamp_ms', 'sample_no',
                'hybrid_speed_kmh', 'instant_fuel_lper100km', 'instant_fuel_kpl',
                'drive_mode', 'gps_obd_speed_diff_kmh'
            ]
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            # Write calculated values
            for sample in samples:
                # Calculate hybrid speed
                gps_speed = sample.gps.speed_kmh if sample.gps else None
                obd_speed = sample.obd.speed_kmh if sample.obd else None
                
                if obd_speed is not None and obd_speed <= 20.0:
                    hybrid_speed = obd_speed
                elif gps_speed is not None:
                    hybrid_speed = gps_speed
                elif obd_speed is not None:
                    hybrid_speed = obd_speed
                else:
                    hybrid_speed = 0.0
                
                # Calculate instantaneous fuel efficiency
                if sample.obd and sample.obd.fuel_rate_lh and hybrid_speed > 0:
                    l_per_100km = (sample.obd.fuel_rate_lh / hybrid_speed) * 100.0
                    kpl = 100.0 / l_per_100km if l_per_100km > 0 else 0.0
                else:
                    l_per_100km = 0.0
                    kpl = 0.0
                
                # Determine drive mode
                if hybrid_speed <= 2.0:
                    drive_mode = 'idle'
                elif hybrid_speed <= 60.0:
                    drive_mode = 'city'
                else:
                    drive_mode = 'highway'
                
                # Calculate speed difference
                speed_diff = None
                if gps_speed is not None and obd_speed is not None:
                    speed_diff = gps_speed - obd_speed
                
                row = {
                    'timestamp_ms': sample.timestamp_ms,
                    'sample_no': sample.sample_no,
                    'hybrid_speed_kmh': hybrid_speed,
                    'instant_fuel_lper100km': l_per_100km,
                    'instant_fuel_kpl': kpl,
                    'drive_mode': drive_mode,
                    'gps_obd_speed_diff_kmh': speed_diff
                }
                writer.writerow(row)
        
        print(f"✓ Exported calculated values CSV: {output_path}")
    
    @staticmethod
    def export_vehicle_profile_csv(
        profile: VehicleProfile,
        output_dir: str = '.'
    ):
        """
        Export vehicle profile to CSV.
        
        Args:
            profile: Vehicle profile
            output_dir: Output directory for CSV file
        """
        output_path = Path(output_dir) / "vehicle_profile.csv"
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            fieldnames = [
                'id', 'name', 'fuel_type', 'tank_capacity_l', 'fuel_price_per_litre',
                'engine_power_bhp', 'vehicle_mass_kg', 'engine_displacement_cc',
                'volumetric_efficiency_pct', 'maf_ml_per_gram', 'co2_factor', 'energy_density_mjpl'
            ]
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            row = {
                'id': profile.id,
                'name': profile.name,
                'fuel_type': profile.fuel_type.display_name,
                'tank_capacity_l': profile.tank_capacity_l,
                'fuel_price_per_litre': profile.fuel_price_per_litre,
                'engine_power_bhp': profile.engine_power_bhp,
                'vehicle_mass_kg': profile.vehicle_mass_kg,
                'engine_displacement_cc': profile.engine_displacement_cc,
                'volumetric_efficiency_pct': profile.volumetric_efficiency_pct,
                'maf_ml_per_gram': profile.fuel_type.maf_ml_per_gram,
                'co2_factor': profile.fuel_type.co2_factor,
                'energy_density_mjpl': profile.fuel_type.energy_density_mjpl
            }
            writer.writerow(row)
        
        print(f"✓ Exported vehicle profile CSV: {output_path}")
    
    @staticmethod
    def export_from_json(
        json_file_path: str,
        output_dir: str = '.',
        verbose: bool = False
    ):
        """
        Export data directly from a trip JSON file to CSV.
        This is a generic method that works with any GPS, OBD, fuel, and other metrics.
        
        Args:
            json_file_path: Path to trip JSON file
            output_dir: Output directory for CSV files
            verbose: Enable verbose logging
        """
        logger = logging.getLogger(__name__) if verbose else None
        
        if verbose and logger:
            logger.info(f"Processing JSON file: {json_file_path}")
        
        # Load JSON data
        try:
            with open(json_file_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception as e:
            print(f"Error loading JSON file: {e}")
            return
        
        if 'samples' not in data:
            print("No samples found in JSON file")
            return
        
        samples = data['samples']
        track_name = Path(json_file_path).stem
        
        if verbose and logger:
            logger.info(f"Found {len(samples)} samples in JSON")
        
        # Discover all fields using enhanced method
        all_fields = CSVExporter.discover_fields_from_json(json_file_path)
        gps_fields = all_fields['gps']
        obd_fields = all_fields['obd']
        fuel_fields = all_fields['fuel']
        accel_fields = all_fields['accel']
        trip_fields = all_fields['trip']
        other_fields = all_fields['other']
        
        if verbose and logger:
            logger.info(f"Discovered fields summary:")
            logger.info(f"  GPS: {len(gps_fields)} fields")
            logger.info(f"  OBD: {len(obd_fields)} fields")
            logger.info(f"  Fuel: {len(fuel_fields)} fields")
            logger.info(f"  Accelerometer: {len(accel_fields)} fields")
            logger.info(f"  Trip: {len(trip_fields)} fields")
            logger.info(f"  Other: {len(other_fields)} fields")
            
            if fuel_fields:
                logger.info(f"Fuel metrics detected: {sorted(fuel_fields)}")
            if accel_fields:
                logger.info(f"Accelerometer metrics detected: {sorted(accel_fields)}")
        
        # Export comprehensive CSV
        output_path = Path(output_dir) / f"{track_name}_comprehensive.csv"
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            # Build comprehensive fieldnames
            fieldnames = ['timestampMs', 'sampleNo']
            
            # Add GPS fields
            for field in sorted(gps_fields):
                fieldnames.append(f'gps_{field}')
            
            # Add OBD fields
            for field in sorted(obd_fields):
                fieldnames.append(f'obd_{field}')
            
            # Add Fuel fields
            for field in sorted(fuel_fields):
                fieldnames.append(f'fuel_{field}')
            
            # Add Accelerometer fields
            for field in sorted(accel_fields):
                fieldnames.append(f'accel_{field}')
            
            # Add Trip fields
            for field in sorted(trip_fields):
                fieldnames.append(f'trip_{field}')
            
            # Add other fields
            for field in sorted(other_fields):
                fieldnames.append(field)
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            if verbose and logger:
                logger.info(f"Total CSV columns: {len(fieldnames)}")
                logger.info(f"CSV structure: {fieldnames[:10]}... (showing first 10)")
            
            # Write data with enhanced fuel handling
            for i, sample in enumerate(samples):
                if verbose and logger and i % 100 == 0 and i > 0:
                    logger.info(f"Processed {i}/{len(samples)} samples")
                
                row = {
                    'timestampMs': sample.get('timestampMs'),
                    'sampleNo': sample.get('sampleNo')
                }
                
                # Add GPS data
                gps_data = sample.get('gps', {})
                for field in gps_fields:
                    row[f'gps_{field}'] = gps_data.get(field)
                
                # Add OBD data
                obd_data = sample.get('obd', {})
                for field in obd_fields:
                    row[f'obd_{field}'] = obd_data.get(field)
                
                # Add Fuel data with special handling
                fuel_data = sample.get('fuel', {})
                for field in fuel_fields:
                    value = fuel_data.get(field)
                    # Handle scientific notation and very small values
                    if value is not None and isinstance(value, (int, float)):
                        if abs(value) < 1e-6 and value != 0:
                            # Very small values, keep precision
                            row[f'fuel_{field}'] = value
                        else:
                            row[f'fuel_{field}'] = value
                    else:
                        row[f'fuel_{field}'] = value
                
                # Add Accelerometer data
                accel_data = sample.get('accel', {})
                for field in accel_fields:
                    row[f'accel_{field}'] = accel_data.get(field)
                
                # Add Trip data
                trip_data = sample.get('trip', {})
                for field in trip_fields:
                    row[f'trip_{field}'] = trip_data.get(field)
                
                # Add other data
                for field in other_fields:
                    row[field] = sample.get(field)
                
                writer.writerow(row)
        
        print(f"✓ Exported comprehensive CSV: {output_path}")
        if verbose and logger:
            logger.info(f"Successfully exported comprehensive data to {output_path}")
            
            # Export fuel-specific summary if fuel data exists
            if fuel_fields:
                CSVExporter._export_fuel_summary(samples, track_name, output_dir, logger)
    
    @staticmethod
    def _export_fuel_summary(samples: List[Dict], track_name: str, output_dir: str, logger):
        """
        Export a fuel-specific summary CSV with key fuel metrics.
        
        Args:
            samples: List of sample dictionaries
            track_name: Name for the CSV files
            output_dir: Output directory for CSV files
            logger: Logger instance for verbose output
        """
        try:
            output_path = Path(output_dir) / f"{track_name}_fuel_summary.csv"
            
            with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
                fieldnames = ['timestampMs', 'sampleNo']
                
                # Add fuel-related fields
                fuel_sample = samples[0].get('fuel', {})
                fuel_fields = sorted(fuel_sample.keys())
                fieldnames.extend([f'fuel_{field}' for field in fuel_fields])
                
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
                writer.writeheader()
                
                for sample in samples:
                    row = {
                        'timestampMs': sample.get('timestampMs'),
                        'sampleNo': sample.get('sampleNo')
                    }
                    
                    fuel_data = sample.get('fuel', {})
                    for field in fuel_fields:
                        row[f'fuel_{field}'] = fuel_data.get(field)
                    
                    writer.writerow(row)
            
            print(f"✓ Exported fuel summary CSV: {output_path}")
            if logger:
                logger.info(f"Exported fuel-specific summary with {len(fuel_fields)} fuel metrics")
                
        except Exception as e:
            print(f"Error exporting fuel summary: {e}")
            if logger:
                logger.error(f"Failed to export fuel summary: {e}")
    
    @staticmethod
    def export_all_data(
        samples_dict: Dict[str, List[TrackSample]],
        metrics_dict: Dict[str, TripMetrics],
        profile: VehicleProfile,
        output_dir: str = '.',
        verbose: bool = False
    ):
        """
        Export all data to multiple CSV files.
        
        Args:
            samples_dict: Dictionary of track name -> List[TrackSample]
            metrics_dict: Dictionary of track name -> TripMetrics
            profile: Vehicle profile
            output_dir: Output directory for CSV files
            verbose: Enable verbose logging
        """
        logger = logging.getLogger(__name__) if verbose else None
        
        print(f"\n📊 Exporting CSV data to {output_dir}...")
        if verbose and logger:
            logger.info("Starting comprehensive CSV export")
        
        # Export vehicle profile
        CSVExporter.export_vehicle_profile_csv(profile, output_dir)
        if verbose and logger:
            logger.info("Exported vehicle profile")
        
        # Export metrics summary
        CSVExporter.export_metrics_csv(metrics_dict, profile, output_dir)
        if verbose and logger:
            logger.info("Exported metrics summary")
        
        # Export data for each track
        for track_name, samples in samples_dict.items():
            if verbose and logger:
                logger.info(f"Exporting track: {track_name} with {len(samples)} samples")
            CSVExporter.export_samples_csv(samples, track_name, output_dir, verbose)
            CSVExporter.export_calculated_samples_csv(samples, track_name, profile, output_dir)
        
        print("✓ All CSV exports complete!\n")
        if verbose and logger:
            logger.info("All CSV exports completed successfully")
