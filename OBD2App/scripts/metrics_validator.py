"""
Metrics validation and comparison module.
Compares recalculated metrics against logged values in track files.
"""

import json
from typing import List, Dict, Any, Optional, Tuple
from pathlib import Path
from obd_types import TrackSample, TripMetrics
from report_generator import ReportGenerator


class MetricsValidator:
    """Validates recalculated metrics against logged values."""
    
    @staticmethod
    def validate_track(
        calculated_metrics: TripMetrics,
        logged_samples: List[TrackSample],
        track_name: str,
        tolerance_pct: float = 5.0
    ) -> Dict[str, Any]:
        """
        Validate recalculated metrics against logged values from track samples.
        
        Args:
            calculated_metrics: Recalculated metrics
            logged_samples: Original samples with logged values
            track_name: Name of the track
            tolerance_pct: Acceptable tolerance percentage for differences
            
        Returns:
            Dictionary with validation results
        """
        validation_results = {
            'track_name': track_name,
            'comparisons': {},
            'within_tolerance': True,
            'max_difference_pct': 0.0,
            'summary': {}
        }
        
        # Extract logged final values from the last sample
        if not logged_samples:
            validation_results['error'] = "No logged samples available"
            return validation_results
        
        last_sample = logged_samples[-1]
        
        # Compare key metrics
        comparisons = [
            ('distance_km', 'distanceKm', 'Distance (km)'),
            ('fuel_used_l', 'tripFuelUsedL', 'Fuel Used (L)'),
            ('avg_fuel_lper100km', 'tripAvgLper100km', 'Avg Fuel (L/100km)'),
            ('avg_fuel_kpl', 'tripAvgKpl', 'Avg Fuel (km/L)'),
            ('avg_speed_kmh', 'avgSpeedKmh', 'Avg Speed (km/h)'),
            ('max_speed_kmh', 'maxSpeedKmh', 'Max Speed (km/h)'),
            ('moving_time_sec', 'movingTimeSec', 'Moving Time (s)'),
            ('stopped_time_sec', 'stoppedTimeSec', 'Stopped Time (s)'),
            ('pct_idle', 'pctIdle', 'Idle (%)'),
            ('pct_city', 'pctCity', 'City (%)'),
            ('pct_highway', 'pctHighway', 'Highway (%)')
        ]
        
        max_diff_pct = 0.0
        
        for calc_attr, logged_key, display_name in comparisons:
            calc_value = getattr(calculated_metrics, calc_attr)
            logged_value = MetricsValidator._get_logged_value(last_sample, logged_key)
            
            if logged_value is not None and calc_value is not None:
                diff_pct = MetricsValidator._calculate_difference_pct(calc_value, logged_value)
                within_tolerance = abs(diff_pct) <= tolerance_pct
                
                if not within_tolerance:
                    validation_results['within_tolerance'] = False
                
                max_diff_pct = max(max_diff_pct, abs(diff_pct))
                
                comparison = {
                    'display_name': display_name,
                    'calculated': calc_value,
                    'logged': logged_value,
                    'difference_pct': diff_pct,
                    'within_tolerance': within_tolerance,
                    'status': '✓' if within_tolerance else '✗'
                }
                
                validation_results['comparisons'][calc_attr] = comparison
            else:
                validation_results['comparisons'][calc_attr] = {
                    'display_name': display_name,
                    'calculated': calc_value,
                    'logged': logged_value,
                    'difference_pct': None,
                    'within_tolerance': None,
                    'status': '?'  # Unknown/missing data
                }
        
        validation_results['max_difference_pct'] = max_diff_pct
        
        # Generate summary
        total_comparisons = len([c for c in validation_results['comparisons'].values() 
                                if c['within_tolerance'] is not None])
        within_tolerance_count = len([c for c in validation_results['comparisons'].values() 
                                    if c.get('within_tolerance') == True])
        
        validation_results['summary'] = {
            'total_comparisons': total_comparisons,
            'within_tolerance_count': within_tolerance_count,
            'within_tolerance_pct': (within_tolerance_count / total_comparisons * 100.0) if total_comparisons > 0 else 0.0,
            'tolerance_used': tolerance_pct,
            'overall_status': 'PASS' if validation_results['within_tolerance'] else 'FAIL'
        }
        
        return validation_results
    
    @staticmethod
    def _get_logged_value(sample: TrackSample, key: str) -> Optional[float]:
        """Extract logged value from sample data."""
        # Check in different sections of the sample
        if 'trip' in sample.__dict__ and sample.trip is not None:
            # This would require extending TrackSample to include trip data
            pass
        
        # For now, we'll need to parse the original JSON to get logged values
        # This is a simplified version - in practice, we'd need to store the original logged values
        return None
    
    @staticmethod
    def _calculate_difference_pct(calculated: float, logged: float) -> float:
        """Calculate percentage difference between calculated and logged values."""
        if logged == 0:
            return 0.0 if calculated == 0 else float('inf')
        
        return ((calculated - logged) / logged) * 100.0
    
    @staticmethod
    def print_validation_report(validation_results: Dict[str, Any]):
        """Print a formatted validation report."""
        print(f"\n{'='*70}")
        print(f"VALIDATION REPORT: {validation_results['track_name']}")
        print(f"{'='*70}")
        
        if 'error' in validation_results:
            print(f"❌ {validation_results['error']}")
            return
        
        summary = validation_results['summary']
        print(f"\n📊 Summary: {summary['overall_status']}")
        print(f"  Comparisons: {summary['within_tolerance_count']}/{summary['total_comparisons']} within tolerance")
        print(f"  Success rate: {summary['within_tolerance_pct']:.1f}%")
        print(f"  Tolerance used: ±{summary['tolerance_used']}%")
        print(f"  Max difference: {validation_results['max_difference_pct']:.2f}%")
        
        print(f"\n📋 Detailed Comparison:")
        print(f"{'Metric':<20} {'Calculated':<12} {'Logged':<12} {'Diff (%)':<10} {'Status':<6}")
        print(f"{'-'*70}")
        
        for comp in validation_results['comparisons'].values():
            calc_str = f"{comp['calculated']:.3f}" if comp['calculated'] is not None else "N/A"
            logged_str = f"{comp['logged']:.3f}" if comp['logged'] is not None else "N/A"
            diff_str = f"{comp['difference_pct']:+.2f}" if comp['difference_pct'] is not None else "N/A"
            
            print(f"{comp['display_name']:<20} {calc_str:<12} {logged_str:<12} {diff_str:<10} {comp['status']:<6}")
        
        print(f"\n{'='*70}\n")
    
    @staticmethod
    def validate_multiple_tracks(
        calculated_metrics_dict: Dict[str, TripMetrics],
        logged_samples_dict: Dict[str, List[TrackSample]],
        tolerance_pct: float = 5.0
    ) -> Dict[str, Dict[str, Any]]:
        """
        Validate multiple tracks and return combined results.
        
        Args:
            calculated_metrics_dict: Dictionary of track name -> calculated metrics
            logged_samples_dict: Dictionary of track name -> logged samples
            tolerance_pct: Acceptable tolerance percentage
            
        Returns:
            Dictionary of validation results for all tracks
        """
        all_results = {}
        
        for track_name in calculated_metrics_dict.keys():
            if track_name in logged_samples_dict:
                result = MetricsValidator.validate_track(
                    calculated_metrics_dict[track_name],
                    logged_samples_dict[track_name],
                    track_name,
                    tolerance_pct
                )
                all_results[track_name] = result
            else:
                all_results[track_name] = {
                    'track_name': track_name,
                    'error': 'No logged samples available for comparison'
                }
        
        return all_results
    
    @staticmethod
    def print_combined_validation_report(all_results: Dict[str, Dict[str, Any]]):
        """Print a combined validation report for all tracks."""
        print(f"\n{'='*70}")
        print(f"COMBINED VALIDATION REPORT - {len(all_results)} Tracks")
        print(f"{'='*70}")
        
        total_tracks = len(all_results)
        passed_tracks = len([r for r in all_results.values() if r.get('within_tolerance', False)])
        
        print(f"\n📊 Overall Summary:")
        print(f"  Total tracks: {total_tracks}")
        print(f"  Passed validation: {passed_tracks}")
        print(f"  Failed validation: {total_tracks - passed_tracks}")
        print(f"  Success rate: {(passed_tracks / total_tracks * 100.0):.1f}%")
        
        print(f"\n📋 Track-by-Track Results:")
        for track_name, result in all_results.items():
            if 'error' in result:
                print(f"  {track_name}: ❌ {result['error']}")
            else:
                status = result['summary']['overall_status']
                max_diff = result['max_difference_pct']
                print(f"  {track_name}: {status} (max diff: {max_diff:.2f}%)")
        
        print(f"\n{'='*70}\n")


class LoggedDataExtractor:
    """Extracts logged values from original JSON track files."""
    
    @staticmethod
    def extract_logged_metrics(track_path: str) -> Dict[str, Any]:
        """
        Extract logged metrics from the last sample in a track file.
        
        Args:
            track_path: Path to track JSON file
            
        Returns:
            Dictionary with logged metrics
        """
        with open(track_path, 'r') as f:
            data = json.load(f)
        
        samples = data.get('samples', [])
        if not samples:
            return {}
        
        last_sample = samples[-1]
        
        logged_metrics = {}
        
        # Extract trip metrics
        if 'trip' in last_sample:
            trip = last_sample['trip']
            logged_metrics.update({
                'distanceKm': trip.get('distanceKm'),
                'timeSec': trip.get('timeSec'),
                'movingTimeSec': trip.get('movingTimeSec'),
                'stoppedTimeSec': trip.get('stoppedTimeSec'),
                'avgSpeedKmh': trip.get('avgSpeedKmh'),
                'maxSpeedKmh': trip.get('maxSpeedKmh'),
                'pctIdle': trip.get('pctIdle'),
                'pctCity': trip.get('pctCity'),
                'pctHighway': trip.get('pctHighway')
            })
        
        # Extract fuel metrics
        if 'fuel' in last_sample:
            fuel = last_sample['fuel']
            logged_metrics.update({
                'tripFuelUsedL': fuel.get('tripFuelUsedL'),
                'tripAvgLper100km': fuel.get('tripAvgLper100km'),
                'tripAvgKpl': fuel.get('tripAvgKpl')
            })
        
        return logged_metrics
    
    @staticmethod
    def create_enhanced_samples(track_path: str) -> List[TrackSample]:
        """
        Create enhanced samples with logged values embedded.
        
        Args:
            track_path: Path to track JSON file
            
        Returns:
            List of TrackSample with embedded logged values
        """
        with open(track_path, 'r') as f:
            data = json.load(f)
        
        samples_data = data.get('samples', [])
        enhanced_samples = []
        
        for sample_data in samples_data:
            # Parse basic sample data (reuse track_loader logic)
            # This would need to be integrated with track_loader
            pass
        
        return enhanced_samples
