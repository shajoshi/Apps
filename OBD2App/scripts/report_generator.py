"""
Report generation and output formatting for metrics recalculation.
"""

from typing import List
from obd_types import TripMetrics, VehicleProfile


class ReportGenerator:
    """Generates formatted reports for trip metrics."""
    
    @staticmethod
    def print_track_summary(
        track_name: str,
        metrics: TripMetrics,
        profile: VehicleProfile
    ):
        """Print summary statistics for a single track."""
        print(f"\n{'='*70}")
        print(f"Track: {track_name}")
        print(f"{'='*70}")
        
        print(f"\n📊 Trip Statistics:")
        print(f"  Samples processed:     {metrics.sample_count:,}")
        print(f"  Distance traveled:     {metrics.distance_km:.3f} km")
        print(f"  Total time:            {ReportGenerator._format_time(metrics.total_time_sec)}")
        print(f"  Moving time:           {ReportGenerator._format_time(metrics.moving_time_sec)}")
        print(f"  Stopped time:          {ReportGenerator._format_time(metrics.stopped_time_sec)}")
        
        print(f"\n⚡ Speed Metrics:")
        print(f"  Average speed:         {metrics.avg_speed_kmh:.1f} km/h")
        print(f"  Max speed:             {metrics.max_speed_kmh:.1f} km/h")
        
        print(f"\n⛽ Fuel Metrics:")
        print(f"  Fuel consumed:         {metrics.fuel_used_l:.3f} L")
        print(f"  Average efficiency:    {metrics.avg_fuel_lper100km:.2f} L/100km")
        print(f"  Average efficiency:    {metrics.avg_fuel_kpl:.2f} km/L")
        
        if metrics.fuel_cost > 0:
            print(f"  Fuel cost:             ₹{metrics.fuel_cost:.2f}")
        
        if metrics.avg_co2_g_per_km > 0:
            print(f"  Avg CO₂ emissions:     {metrics.avg_co2_g_per_km:.1f} g/km")
        
        print(f"\n🚦 Drive Mode Distribution:")
        print(f"  Idle (≤2 km/h):        {metrics.pct_idle:.1f}%")
        print(f"  City (2-60 km/h):      {metrics.pct_city:.1f}%")
        print(f"  Highway (>60 km/h):    {metrics.pct_highway:.1f}%")
        
        print(f"\n🔧 Vehicle Profile:")
        print(f"  Name:                  {profile.name}")
        print(f"  Fuel type:             {profile.fuel_type.display_name}")
        if profile.engine_displacement_cc > 0:
            print(f"  Engine displacement:   {profile.engine_displacement_cc} cc")
        if profile.fuel_price_per_litre > 0:
            print(f"  Fuel price:            ₹{profile.fuel_price_per_litre:.2f}/L")
    
    @staticmethod
    def print_combined_summary(
        track_names: List[str],
        combined_metrics: TripMetrics,
        profile: VehicleProfile
    ):
        """Print combined summary statistics across all tracks."""
        print(f"\n{'='*70}")
        print(f"COMBINED SUMMARY - {len(track_names)} Tracks")
        print(f"{'='*70}")
        
        print(f"\n📁 Tracks processed:")
        for i, name in enumerate(track_names, 1):
            print(f"  {i}. {name}")
        
        print(f"\n📊 Combined Trip Statistics:")
        print(f"  Total samples:         {combined_metrics.sample_count:,}")
        print(f"  Total distance:        {combined_metrics.distance_km:.3f} km")
        print(f"  Total time:            {ReportGenerator._format_time(combined_metrics.total_time_sec)}")
        print(f"  Total moving time:     {ReportGenerator._format_time(combined_metrics.moving_time_sec)}")
        print(f"  Total stopped time:    {ReportGenerator._format_time(combined_metrics.stopped_time_sec)}")
        
        print(f"\n⚡ Combined Speed Metrics:")
        print(f"  Average speed:         {combined_metrics.avg_speed_kmh:.1f} km/h")
        print(f"  Max speed (overall):   {combined_metrics.max_speed_kmh:.1f} km/h")
        
        print(f"\n⛽ Combined Fuel Metrics:")
        print(f"  Total fuel consumed:   {combined_metrics.fuel_used_l:.3f} L")
        print(f"  Average efficiency:    {combined_metrics.avg_fuel_lper100km:.2f} L/100km")
        print(f"  Average efficiency:    {combined_metrics.avg_fuel_kpl:.2f} km/L")
        
        if combined_metrics.fuel_cost > 0:
            print(f"  Total fuel cost:       ₹{combined_metrics.fuel_cost:.2f}")
        
        if combined_metrics.avg_co2_g_per_km > 0:
            print(f"  Avg CO₂ emissions:     {combined_metrics.avg_co2_g_per_km:.1f} g/km")
            total_co2_kg = (combined_metrics.avg_co2_g_per_km * combined_metrics.distance_km) / 1000.0
            print(f"  Total CO₂ emissions:   {total_co2_kg:.2f} kg")
        
        print(f"\n🚦 Combined Drive Mode Distribution:")
        print(f"  Idle (≤2 km/h):        {combined_metrics.pct_idle:.1f}%")
        print(f"  City (2-60 km/h):      {combined_metrics.pct_city:.1f}%")
        print(f"  Highway (>60 km/h):    {combined_metrics.pct_highway:.1f}%")
        
        print(f"\n{'='*70}\n")
    
    @staticmethod
    def _format_time(seconds: int) -> str:
        """Format seconds as HH:MM:SS."""
        hours = seconds // 3600
        minutes = (seconds % 3600) // 60
        secs = seconds % 60
        
        if hours > 0:
            return f"{hours}h {minutes}m {secs}s"
        elif minutes > 0:
            return f"{minutes}m {secs}s"
        else:
            return f"{secs}s"
    
    @staticmethod
    def print_error(message: str):
        """Print an error message."""
        print(f"\n❌ ERROR: {message}\n")
    
    @staticmethod
    def print_warning(message: str):
        """Print a warning message."""
        print(f"\n⚠️  WARNING: {message}")
