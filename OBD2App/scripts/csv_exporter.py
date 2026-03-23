"""
CSV export functionality for OBD2 metrics analysis.
Exports raw sample data and calculated metrics to CSV files.
"""

import csv
from typing import List, Dict, Any
from pathlib import Path
from obd_types import TrackSample, TripMetrics, VehicleProfile


class CSVExporter:
    """Exports OBD2 data to CSV format for further analysis."""
    
    @staticmethod
    def export_samples_csv(
        samples: List[TrackSample],
        track_name: str,
        output_dir: str = '.'
    ):
        """
        Export raw sample data to CSV.
        
        Args:
            samples: List of track samples
            track_name: Name for CSV file
            output_dir: Output directory for CSV file
        """
        output_path = Path(output_dir) / f"{track_name}_samples.csv"
        
        with open(output_path, 'w', newline='', encoding='utf-8') as csvfile:
            # Define CSV headers
            fieldnames = [
                'timestamp_ms', 'sample_no',
                'gps_lat', 'gps_lon', 'gps_speed_kmh', 'gps_alt_msl', 'gps_accuracy_m', 'gps_satellite_count',
                'obd_rpm', 'obd_speed_kmh', 'obd_engine_load_pct', 'obd_throttle_pct', 'obd_coolant_temp_c',
                'obd_intake_temp_c', 'obd_maf_gs', 'obd_intake_map_kpa', 'obd_baro_pressure_kpa',
                'obd_fuel_rate_lh', 'obd_fuel_level_pct', 'obd_o2_voltage', 'obd_control_module_voltage',
                'obd_run_time_sec', 'obd_fuel_system_status', 'obd_monitor_status'
            ]
            
            writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
            writer.writeheader()
            
            # Write sample data
            for sample in samples:
                row = {
                    'timestamp_ms': sample.timestamp_ms,
                    'sample_no': sample.sample_no,
                    'gps_lat': sample.gps.lat if sample.gps else None,
                    'gps_lon': sample.gps.lon if sample.gps else None,
                    'gps_speed_kmh': sample.gps.speed_kmh if sample.gps else None,
                    'gps_alt_msl': sample.gps.alt_msl if sample.gps else None,
                    'gps_accuracy_m': sample.gps.accuracy_m if sample.gps else None,
                    'gps_satellite_count': sample.gps.satellite_count if sample.gps else None,
                    'obd_rpm': sample.obd.rpm if sample.obd else None,
                    'obd_speed_kmh': sample.obd.speed_kmh if sample.obd else None,
                    'obd_engine_load_pct': sample.obd.engine_load_pct if sample.obd else None,
                    'obd_throttle_pct': sample.obd.throttle_pct if sample.obd else None,
                    'obd_coolant_temp_c': sample.obd.coolant_temp_c if sample.obd else None,
                    'obd_intake_temp_c': sample.obd.intake_temp_c if sample.obd else None,
                    'obd_maf_gs': sample.obd.maf_gs if sample.obd else None,
                    'obd_intake_map_kpa': sample.obd.intake_map_kpa if sample.obd else None,
                    'obd_baro_pressure_kpa': sample.obd.baro_pressure_kpa if sample.obd else None,
                    'obd_fuel_rate_lh': sample.obd.fuel_rate_lh if sample.obd else None,
                    'obd_fuel_level_pct': sample.obd.fuel_level_pct if sample.obd else None,
                    'obd_o2_voltage': sample.obd.o2_voltage if sample.obd else None,
                    'obd_control_module_voltage': sample.obd.control_module_voltage if sample.obd else None,
                    'obd_run_time_sec': sample.obd.run_time_sec if sample.obd else None,
                    'obd_fuel_system_status': sample.obd.fuel_system_status if sample.obd else None,
                    'obd_monitor_status': sample.obd.monitor_status if sample.obd else None
                }
                writer.writerow(row)
        
        print(f"✓ Exported samples CSV: {output_path}")
    
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
    def export_all_data(
        samples_dict: Dict[str, List[TrackSample]],
        metrics_dict: Dict[str, TripMetrics],
        profile: VehicleProfile,
        output_dir: str = '.'
    ):
        """
        Export all data to multiple CSV files.
        
        Args:
            samples_dict: Dictionary of track name -> List[TrackSample]
            metrics_dict: Dictionary of track name -> TripMetrics
            profile: Vehicle profile
            output_dir: Output directory for CSV files
        """
        print(f"\n📊 Exporting CSV data to {output_dir}...")
        
        # Export vehicle profile
        CSVExporter.export_vehicle_profile_csv(profile, output_dir)
        
        # Export metrics summary
        CSVExporter.export_metrics_csv(metrics_dict, profile, output_dir)
        
        # Export data for each track
        for track_name, samples in samples_dict.items():
            CSVExporter.export_samples_csv(samples, track_name, output_dir)
            CSVExporter.export_calculated_samples_csv(samples, track_name, profile, output_dir)
        
        print("✓ All CSV exports complete!\n")
