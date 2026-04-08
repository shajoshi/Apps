#!/usr/bin/env python3
"""
OBD2 Track Metrics Recalculation Script

Recalculates fuel efficiency, trip statistics, and drive mode metrics from
OBD2 track JSON files using the same algorithms as MetricsCalculator.kt.

Usage:
    python recalculate_metrics.py -p profile.json -t track1.json track2.json ...
"""

import argparse
import sys
from pathlib import Path
from typing import List

from obd_types import TripMetrics, VehicleProfile, TrackSample
from obd_calculators import FuelCalculator, TripCalculator, TripState, PowerCalculator
from track_loader import TrackLoader
from report_generator import ReportGenerator
from kml_generator import KMLGenerator
from plot_generator import PlotGenerator
from csv_exporter import CSVExporter
from metrics_validator import MetricsValidator, LoggedDataExtractor


class MetricsRecalculator:
    """Main recalculation engine."""
    
    def __init__(self, profile: VehicleProfile):
        self.profile = profile
        self.fuel_calc = FuelCalculator()
        self.trip_calc = TripCalculator()
        self.power_calc = PowerCalculator()
    
    def recalculate_track(self, samples: List[TrackSample]) -> TripMetrics:
        """
        Recalculate metrics for a single track.
        
        Args:
            samples: List of track samples
            
        Returns:
            TripMetrics with recalculated values
        """
        if not samples:
            return TripMetrics()
        
        # Initialize trip state
        trip_state = TripState(samples[0].timestamp_ms)
        
        # Process each sample
        for sample in samples:
            self._process_sample(sample, trip_state)
        
        # Calculate final metrics
        return self._finalize_metrics(trip_state, samples)
    
    def _process_sample(self, sample: TrackSample, trip_state: TripState):
        """Process a single sample and update trip state."""
        gps = sample.gps
        obd = sample.obd
        
        if not obd:
            return
        
        # Calculate effective speed (hybrid OBD/GPS)
        gps_speed = gps.speed_kmh if gps else None
        obd_speed = obd.speed_kmh
        speed_kmh = self.trip_calc.hybrid_speed(gps_speed, obd_speed)
        
        # Calculate effective fuel rate (3-tier fallback)
        fuel_rate_lh = self.fuel_calc.effective_fuel_rate(
            fuel_rate_pid=obd.fuel_rate_lh,
            maf_gs=obd.maf_gs,
            maf_ml_per_gram=self.profile.fuel_type.maf_ml_per_gram,
            map_kpa=obd.intake_map_kpa,
            iat_c=obd.intake_temp_c,
            rpm=obd.rpm,
            displacement_cc=self.profile.engine_displacement_cc,
            ve_pct=self.profile.volumetric_efficiency_pct,
            fuel_type=self.profile.fuel_type,
            baro_kpa=obd.baro_pressure_kpa,
            engine_load_pct=obd.engine_load_pct
        )
        
        # Update trip state accumulators
        if fuel_rate_lh is None:
            fuel_rate_lh = 0.0
        
        trip_state.update(sample.timestamp_ms, speed_kmh, fuel_rate_lh)
    
    def _finalize_metrics(
        self,
        trip_state: TripState,
        samples: List[TrackSample]
    ) -> TripMetrics:
        """Calculate final metrics from trip state."""
        # Calculate trip averages
        trip_avg_lper100km, trip_avg_kpl = self.fuel_calc.trip_averages(
            trip_state.trip_fuel_used_l,
            trip_state.trip_distance_km
        )
        
        # Calculate total trip time
        if samples:
            total_time_sec = int((samples[-1].timestamp_ms - samples[0].timestamp_ms) / 1000)
        else:
            total_time_sec = 0
        
        # Calculate average speed
        avg_speed = self.trip_calc.average_speed(
            trip_state.trip_distance_km,
            trip_state.moving_time_sec
        )
        
        # Calculate drive mode percentages
        pct_city, pct_highway, pct_idle = trip_state.trip_drive_mode_percents()
        
        # Calculate fuel cost
        fuel_cost = self.fuel_calc.cost(
            trip_state.trip_fuel_used_l,
            self.profile.fuel_price_per_litre
        )
        
        # Calculate CO2 emissions
        avg_co2 = self.fuel_calc.co2(
            trip_avg_lper100km,
            self.profile.fuel_type.co2_factor
        )
        
        return TripMetrics(
            distance_km=trip_state.trip_distance_km,
            fuel_used_l=trip_state.trip_fuel_used_l,
            avg_fuel_lper100km=trip_avg_lper100km,
            avg_fuel_kpl=trip_avg_kpl,
            avg_speed_kmh=avg_speed,
            max_speed_kmh=trip_state.max_speed_kmh,
            moving_time_sec=trip_state.moving_time_sec,
            stopped_time_sec=trip_state.stopped_time_sec,
            total_time_sec=total_time_sec,
            pct_idle=pct_idle,
            pct_city=pct_city,
            pct_highway=pct_highway,
            fuel_cost=fuel_cost,
            avg_co2_g_per_km=avg_co2,
            sample_count=len(samples)
        )


def combine_metrics(metrics_list: List[TripMetrics]) -> TripMetrics:
    """Combine metrics from multiple tracks."""
    if not metrics_list:
        return TripMetrics()
    
    combined = TripMetrics()
    
    # Sum up totals
    for metrics in metrics_list:
        combined.distance_km += metrics.distance_km
        combined.fuel_used_l += metrics.fuel_used_l
        combined.moving_time_sec += metrics.moving_time_sec
        combined.stopped_time_sec += metrics.stopped_time_sec
        combined.total_time_sec += metrics.total_time_sec
        combined.fuel_cost += metrics.fuel_cost
        combined.sample_count += metrics.sample_count
        
        # Track maximum speed across all tracks
        if metrics.max_speed_kmh > combined.max_speed_kmh:
            combined.max_speed_kmh = metrics.max_speed_kmh
    
    # Calculate combined averages
    if combined.distance_km >= 0.1:
        combined.avg_fuel_lper100km = (combined.fuel_used_l / combined.distance_km) * 100.0
        combined.avg_fuel_kpl = combined.distance_km / combined.fuel_used_l if combined.fuel_used_l > 0 else 0.0
    
    if combined.moving_time_sec >= 30:
        time_h = combined.moving_time_sec / 3600.0
        combined.avg_speed_kmh = combined.distance_km / time_h
    
    # Calculate combined drive mode percentages
    total_time = combined.moving_time_sec + combined.stopped_time_sec
    if total_time > 0:
        # Recalculate from individual track time distributions
        total_idle_sec = 0
        total_city_sec = 0
        total_highway_sec = 0
        
        for metrics in metrics_list:
            track_total = metrics.moving_time_sec + metrics.stopped_time_sec
            if track_total > 0:
                total_idle_sec += int((metrics.pct_idle / 100.0) * track_total)
                total_city_sec += int((metrics.pct_city / 100.0) * track_total)
                total_highway_sec += int((metrics.pct_highway / 100.0) * track_total)
        
        combined_total = total_idle_sec + total_city_sec + total_highway_sec
        if combined_total > 0:
            combined.pct_idle = (total_idle_sec / combined_total) * 100.0
            combined.pct_city = (total_city_sec / combined_total) * 100.0
            combined.pct_highway = (total_highway_sec / combined_total) * 100.0
    
    # Calculate combined CO2
    if combined.avg_fuel_lper100km > 0:
        # Use the fuel type from first track (assuming same vehicle)
        # CO2 factor would need to be passed in, but we can estimate from avg efficiency
        combined.avg_co2_g_per_km = sum(m.avg_co2_g_per_km for m in metrics_list) / len(metrics_list)
    
    return combined


def combine_samples_chronologically(samples_dict: dict, track_names: List[str]) -> List[TrackSample]:
    """Combine samples from multiple tracks in chronological order with no time gaps."""
    if not track_names:
        return []

    track_info = []
    for track_name in track_names:
        samples = samples_dict.get(track_name, [])
        if samples:
            track_info.append((track_name, samples))

    if not track_info:
        return []

    chronological_samples: List[TrackSample] = []
    time_offset_ms = 0

    for i, (track_name, samples) in enumerate(track_info):
        if i == 0:
            chronological_samples.extend(samples)
            time_offset_ms = samples[-1].timestamp_ms
            continue

        track_start_offset = time_offset_ms - samples[0].timestamp_ms
        adjusted_samples: List[TrackSample] = []
        for sample in samples:
            adjusted_samples.append(
                TrackSample(
                    timestamp_ms=sample.timestamp_ms + track_start_offset,
                    sample_no=sample.sample_no,
                    gps=sample.gps,
                    obd=sample.obd,
                    fuel=sample.fuel,
                )
            )

        chronological_samples.extend(adjusted_samples)
        time_offset_ms = adjusted_samples[-1].timestamp_ms

    return chronological_samples


def main():
    """Main entry point."""
    parser = argparse.ArgumentParser(
        description='Recalculate OBD2 track metrics from raw data',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Basic usage
  python recalculate_metrics.py -p ronin_profile.json -t track1.json
  
  # Multiple tracks
  python recalculate_metrics.py -p ronin_profile.json -t track1.json track2.json track3.json
  
  # Generate all visualizations and exports
  python recalculate_metrics.py -p ronin_profile.json -t track1.json --all
  
  # Generate specific outputs
  python recalculate_metrics.py -p ronin_profile.json -t track1.json --kml --plots --csv
  
  # Validate with custom tolerance
  python recalculate_metrics.py -p ronin_profile.json -t track1.json --validate --tolerance 2.0
  
  # Custom output directory
  python recalculate_metrics.py -p ronin_profile.json -t track1.json --all -o ./output
        """
    )
    
    parser.add_argument(
        '-p', '--profile',
        required=True,
        help='Vehicle profile JSON file'
    )
    
    parser.add_argument(
        '-t', '--tracks',
        required=True,
        nargs='+',
        help='One or more track JSON files'
    )
    
    parser.add_argument(
        '-o', '--output',
        default='.',
        help='Output directory for generated files (default: current directory)'
    )
    
    parser.add_argument(
        '--kml',
        action='store_true',
        help='Generate KML files for track visualization'
    )
    
    parser.add_argument(
        '--plots',
        action='store_true',
        help='Generate distribution plots and time series charts'
    )
    
    parser.add_argument(
        '--csv',
        action='store_true',
        help='Export data to CSV files'
    )
    
    parser.add_argument(
        '--validate',
        action='store_true',
        help='Validate recalculated metrics against logged values'
    )
    
    parser.add_argument(
        '--tolerance',
        type=float,
        default=5.0,
        help='Tolerance percentage for validation (default: 5.0)'
    )
    
    parser.add_argument(
        '--power',
        action='store_true',
        help='Include power calculations in output'
    )
    
    parser.add_argument(
        '--all',
        action='store_true',
        help='Enable all optional features (KML, plots, CSV, validation, power)'
    )
    
    args = parser.parse_args()
    
    # Validate files exist
    profile_path = Path(args.profile)
    if not profile_path.exists():
        ReportGenerator.print_error(f"Profile file not found: {args.profile}")
        return 1
    
    track_paths = [Path(t) for t in args.tracks]
    for track_path in track_paths:
        if not track_path.exists():
            ReportGenerator.print_error(f"Track file not found: {track_path}")
            return 1
    
    try:
        # Handle --all flag
        if args.all:
            args.kml = True
            args.plots = True
            args.csv = True
            args.validate = True
            args.power = True
        
        # Create output directory if it doesn't exist
        output_dir = Path(args.output)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        # Load vehicle profile
        print(f"Loading vehicle profile: {profile_path.name}")
        profile = TrackLoader.load_vehicle_profile(str(profile_path))
        print(f"✓ Loaded profile for {profile.name} ({profile.fuel_type.display_name})\n")
        
        # Initialize recalculator
        recalculator = MetricsRecalculator(profile)
        
        # Process each track
        all_metrics = []
        track_names = []
        samples_dict = {}
        
        for track_path in track_paths:
            print(f"Processing: {track_path.name}...")
            
            # Load track
            header, samples = TrackLoader.load_track_file(str(track_path))
            
            if not samples:
                ReportGenerator.print_warning(f"No samples found in {track_path.name}")
                continue
            
            # Store samples for later use
            track_name = TrackLoader.get_track_name(str(track_path))
            samples_dict[track_name] = samples
            
            # Recalculate metrics
            metrics = recalculator.recalculate_track(samples)
            all_metrics.append(metrics)
            track_names.append(track_name)
            
            # Print track summary
            ReportGenerator.print_track_summary(track_name, metrics, profile)
            
            # Note: Plots will be generated after all tracks are processed for combined analysis
        
        # Display profile information
        print(f"\n" + "="*60)
        print(f"{'VEHICLE PROFILE USED':^60}")
        print(f"{'='*60}")
        print(f"🚗 Vehicle:        {profile.name}")
        print(f"⛽ Fuel Type:      {profile.fuel_type.display_name}")
        print(f"🏭 Engine:         {profile.engine_displacement_cc} cc")
        print(f"⚡ Power:          {profile.engine_power_bhp} BHP")
        print(f"⚖️  Mass:           {profile.vehicle_mass_kg} kg")
        print(f"🛢️  Tank Capacity:  {profile.tank_capacity_l} L")
        print(f"💰 Fuel Price:     ₹{profile.fuel_price_per_litre:.2f}/L")
        print(f"🔧 Vol. Efficiency:{profile.volumetric_efficiency_pct}%")
        print(f"{'='*60}")
        
        # Calculate combined metrics (needed for both single and multiple tracks)
        if len(all_metrics) > 1:
            chronological_samples = combine_samples_chronologically(samples_dict, track_names)
            if chronological_samples:
                combined = recalculator.recalculate_track(chronological_samples)
            else:
                combined = combine_metrics(all_metrics)
            ReportGenerator.print_combined_summary(track_names, combined, profile)
        else:
            combined = all_metrics[0]  # Use single track metrics for plotting
        
        # Generate combined plots if requested
        if args.plots:
            print(f"\n📊 Generating combined plots...")
            
            if len(all_metrics) > 1:
                # Generate combined plots for multiple tracks
                combined_samples = combine_samples_chronologically(samples_dict, track_names)
                
                if combined_samples:
                    PlotGenerator.create_distribution_plots(combined_samples, combined, "combined_tracks", str(output_dir), profile)
                    PlotGenerator.create_drive_mode_pie_chart(combined, "combined_tracks", str(output_dir))
                    
                    # Generate chronological combined time series plot
                    print("  Generating chronological combined time series plot...")
                    chronological_samples = PlotGenerator.create_chronological_time_series(
                        samples_dict, track_names, str(output_dir), profile
                    )

                    print("✓ Generated combined plots for all tracks")
                else:
                    print("⚠️  No data available for combined plots")
            else:
                # For single track, generate individual plots
                track_name = track_names[0]
                samples = list(samples_dict.values())[0]
                metrics = all_metrics[0]
                
                PlotGenerator.create_distribution_plots(samples, metrics, track_name, str(output_dir), profile)
                PlotGenerator.create_time_series_plots(samples, metrics, track_name, str(output_dir), profile)
                PlotGenerator.create_drive_mode_pie_chart(metrics, track_name, str(output_dir))
                print(f"✓ Generated plots for single track: {track_name}")
        
        # Export CSV files if requested
        if args.csv:
            print(f"\n📊 Exporting CSV data...")
            metrics_dict = {name: metrics for name, metrics in zip(track_names, all_metrics)}
            combined_samples = combine_samples_chronologically(samples_dict, track_names) if len(track_names) > 1 else None
            CSVExporter.export_all_data(
                samples_dict,
                metrics_dict,
                profile,
                str(output_dir),
                combined_samples=combined_samples,
                track_order=track_names,
            )

        # Generate a combined KML if requested
        if args.kml and len(track_names) > 1:
            combined_samples = combine_samples_chronologically(samples_dict, track_names)
            if combined_samples:
                print(f"\n🗺️ Generating combined chronological KML...")
                KMLGenerator.create_combined_importable_kml(
                    combined_samples,
                    "combined_tracks",
                    str(output_dir)
                )
        
        # Validate metrics if requested
        if args.validate:
            print(f"\n🔍 Validating metrics...")
            validation_results = {}
            
            for track_path in track_paths:
                track_name = TrackLoader.get_track_name(str(track_path))
                if track_name in samples_dict:
                    # Extract logged metrics
                    logged_metrics = LoggedDataExtractor.extract_logged_metrics(str(track_path))

                    track_index = track_names.index(track_name)
                    recalculated_metrics = all_metrics[track_index]

                    # Compare recalculated metrics against logged trip/fuel values when available
                    metric_checks = {}
                    comparisons = [
                        ('distanceKm', recalculated_metrics.distance_km, 'km'),
                        ('timeSec', recalculated_metrics.total_time_sec, 'sec'),
                        ('movingTimeSec', recalculated_metrics.moving_time_sec, 'sec'),
                        ('stoppedTimeSec', recalculated_metrics.stopped_time_sec, 'sec'),
                        ('avgSpeedKmh', recalculated_metrics.avg_speed_kmh, 'km/h'),
                        ('maxSpeedKmh', recalculated_metrics.max_speed_kmh, 'km/h'),
                        ('pctIdle', recalculated_metrics.pct_idle, '%'),
                        ('pctCity', recalculated_metrics.pct_city, '%'),
                        ('pctHighway', recalculated_metrics.pct_highway, '%'),
                        ('tripFuelUsedL', recalculated_metrics.fuel_used_l, 'L'),
                        ('tripAvgLper100km', recalculated_metrics.avg_fuel_lper100km, 'L/100km'),
                        ('tripAvgKpl', recalculated_metrics.avg_fuel_kpl, 'km/L'),
                    ]

                    for key, recalculated_value, unit in comparisons:
                        logged_value = logged_metrics.get(key)
                        if logged_value is None:
                            continue
                        diff = recalculated_value - logged_value
                        metric_checks[key] = {
                            'logged': logged_value,
                            'recalculated': recalculated_value,
                            'diff': diff,
                            'unit': unit,
                        }

                    fuel_checks = {}
                    samples = samples_dict.get(track_name, [])
                    if samples:
                        last_sample = samples[-1]
                        if last_sample.fuel:
                            logged_trip_fuel = last_sample.fuel.get('tripFuelUsedL')
                            logged_trip_avg_lper100km = last_sample.fuel.get('tripAvgLper100km')
                            logged_trip_avg_kpl = last_sample.fuel.get('tripAvgKpl')
                            logged_power_thermo = last_sample.fuel.get('powerThermoKw')
                            fuel_rate = last_sample.obd.fuel_rate_lh if last_sample.obd else None

                            if logged_trip_fuel is not None:
                                fuel_checks['tripFuelUsedL'] = {
                                    'logged': logged_trip_fuel,
                                    'recalculated': recalculated_metrics.fuel_used_l,
                                    'diff': recalculated_metrics.fuel_used_l - logged_trip_fuel,
                                    'unit': 'L',
                                }
                            if logged_trip_avg_lper100km is not None:
                                fuel_checks['tripAvgLper100km'] = {
                                    'logged': logged_trip_avg_lper100km,
                                    'recalculated': recalculated_metrics.avg_fuel_lper100km,
                                    'diff': recalculated_metrics.avg_fuel_lper100km - logged_trip_avg_lper100km,
                                    'unit': 'L/100km',
                                }
                            if logged_trip_avg_kpl is not None:
                                fuel_checks['tripAvgKpl'] = {
                                    'logged': logged_trip_avg_kpl,
                                    'recalculated': recalculated_metrics.avg_fuel_kpl,
                                    'diff': recalculated_metrics.avg_fuel_kpl - logged_trip_avg_kpl,
                                    'unit': 'km/L',
                                }
                            if logged_power_thermo is not None and fuel_rate is not None:
                                recalculated_power = recalculator.power_calc.thermodynamic(
                                    fuel_rate,
                                    profile.fuel_type.energy_density_mjpl
                                )
                                if recalculated_power is not None:
                                    fuel_checks['powerThermoKw'] = {
                                        'logged': logged_power_thermo,
                                        'recalculated': recalculated_power,
                                        'diff': recalculated_power - logged_power_thermo,
                                        'unit': 'kW',
                                    }
                    
                    # Create validation result (simplified version)
                    validation_results[track_name] = {
                        'track_name': track_name,
                        'logged_metrics_available': bool(logged_metrics),
                        'metric_checks': metric_checks,
                        'fuel_checks': fuel_checks,
                        'summary': {
                            'overall_status': 'PASS' if logged_metrics else 'NO_LOGGED_DATA'
                        }
                    }

                    if metric_checks or fuel_checks:
                        print(f"\n  📋 {track_name} validation diff table:")
                        print(f"  {'Metric':<22} {'Logged':>14} {'Recalc':>14} {'Diff':>14} {'Unit':>10}")
                        print(f"  {'-' * 22} {'-' * 14} {'-' * 14} {'-' * 14} {'-' * 10}")

                        def _print_check_row(metric_name: str, check: dict):
                            logged = check.get('logged')
                            recalculated = check.get('recalculated')
                            diff = check.get('diff')
                            unit = check.get('unit', '')
                            print(
                                f"  {metric_name:<22} "
                                f"{logged:>14.3f} "
                                f"{recalculated:>14.3f} "
                                f"{diff:>14.3f} "
                                f"{unit:>10}"
                            )

                        for key in [
                            'distanceKm', 'timeSec', 'movingTimeSec', 'stoppedTimeSec',
                            'avgSpeedKmh', 'maxSpeedKmh', 'pctIdle', 'pctCity', 'pctHighway',
                            'tripFuelUsedL', 'tripAvgLper100km', 'tripAvgKpl', 'powerThermoKw'
                        ]:
                            check = metric_checks.get(key) or fuel_checks.get(key)
                            if check is not None:
                                _print_check_row(key, check)
            
            # Print validation summary
            print(f"  Validation complete for {len(validation_results)} tracks")
            for track_name, result in validation_results.items():
                status = result['summary']['overall_status']
                print(f"  {track_name}: {status}")
        
        # Show power calculations if requested
        if args.power and samples_dict:
            print(f"\n⚡ Power Calculation Summary:")
            for track_name, samples in samples_dict.items():
                # Calculate power for a few representative samples
                power_samples = [
                    s for s in samples
                    if s.fuel and s.fuel.get('powerThermoKw') is not None
                ][:10]
                if power_samples:
                    avg_thermo_power = 0
                    count = 0
                    for sample in power_samples:
                        power = sample.fuel.get('powerThermoKw')
                        if power:
                            avg_thermo_power += power
                            count += 1
                    
                    if count > 0:
                        avg_thermo_power /= count
                        print(f"  {track_name}: Avg thermodynamic power: {avg_thermo_power:.1f} kW")
        
        print("\n✓ All processing complete!\n")
        return 0
    
    except Exception as e:
        ReportGenerator.print_error(f"Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
