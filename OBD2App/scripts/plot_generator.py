"""
Distribution plots and visualizations for OBD2 metrics.
Generates histograms, time-series plots, and drive mode pie charts.
"""

from typing import List, Optional, Tuple
from pathlib import Path
from obd_types import TrackSample, TripMetrics, VehicleProfile
from obd_calculators import FuelCalculator, PowerCalculator

# Optional imports for plotting
try:
    import matplotlib.pyplot as plt
    import matplotlib.patches as mpatches
    PLOTTING_AVAILABLE = True
    
    # Try to import numpy, use fallback if not available
    try:
        import numpy as np
        NUMPY_AVAILABLE = True
    except ImportError:
        NUMPY_AVAILABLE = False
        # Simple fallback for basic statistics
        class SimpleStats:
            @staticmethod
            def mean(data):
                return sum(data) / len(data) if data else 0
            @staticmethod
            def median(data):
                sorted_data = sorted(data)
                n = len(sorted_data)
                if n == 0:
                    return 0
                mid = n // 2
                return sorted_data[mid] if n % 2 else (sorted_data[mid-1] + sorted_data[mid]) / 2
            @staticmethod
            def std(data):
                mean_val = SimpleStats.mean(data)
                variance = sum((x - mean_val) ** 2 for x in data) / len(data) if data else 0
                return variance ** 0.5
            @staticmethod
            def percentile(data, p):
                sorted_data = sorted(data)
                if not sorted_data:
                    return 0
                k = (len(sorted_data) - 1) * p / 100
                f = int(k)
                c = k - f
                if f + 1 < len(sorted_data):
                    return sorted_data[f] * (1 - c) + sorted_data[f + 1] * c
                return sorted_data[f]
        
        np = SimpleStats()
    
    # Set style for better looking plots
    try:
        import seaborn as sns
        plt.style.use('seaborn-v0_8' if 'seaborn-v0_8' in plt.style.available else 'default')
        sns.set_palette("husl")
    except ImportError:
        # Fallback if seaborn not available
        plt.style.use('default')
        
except ImportError:
    PLOTTING_AVAILABLE = False
    plt = None


class PlotGenerator:
    """Generates various plots and visualizations for OBD2 metrics."""
    
    @staticmethod
    def create_distribution_plots(
        samples: List[TrackSample],
        metrics: TripMetrics,
        track_name: str,
        output_dir: str = '.',
        profile: Optional[VehicleProfile] = None
    ):
        """
        Create distribution plots for key metrics.
        
        Args:
            samples: List of track samples
            metrics: Trip metrics
            track_name: Name for plot files
            output_dir: Output directory for plot files
            profile: Vehicle profile for fuel calculations
        """
        if not PLOTTING_AVAILABLE:
            print(f"Warning: Matplotlib not available, skipping distribution plots for {track_name}")
            return
        
        # Create figure with subplots
        fig, axes = plt.subplots(4, 3, figsize=(18, 20))
        fig.suptitle(f'{track_name} - Metric Distributions', fontsize=16, fontweight='bold')
        
        # Extract data
        speeds = PlotGenerator._extract_speeds(samples)
        rpms = PlotGenerator._extract_rpms(samples)
        fuel_rates = PlotGenerator._extract_fuel_rates(samples, profile)
        throttles = PlotGenerator._extract_throttles(samples)
        engine_loads = PlotGenerator._extract_engine_loads(samples)
        fuel_efficiencies = PlotGenerator._extract_fuel_efficiencies(samples, profile)
        power_values = PlotGenerator._extract_power(samples, profile)
        maf_values = PlotGenerator._extract_maf(samples)
        
        # Speed distribution
        if speeds:
            PlotGenerator._create_histogram(axes[0, 0], speeds, 'Speed (km/h)', 'Speed Distribution', 'blue')
            PlotGenerator._add_stats_text(axes[0, 0], speeds, 'km/h')
        else:
            axes[0, 0].text(0.5, 0.5, 'No speed data', ha='center', va='center', transform=axes[0, 0].transAxes)
            axes[0, 0].set_title('Speed Distribution')
        
        # RPM distribution
        if rpms:
            PlotGenerator._create_histogram(axes[0, 1], rpms, 'RPM', 'RPM Distribution', 'red')
            PlotGenerator._add_stats_text(axes[0, 1], rpms, 'RPM')
        else:
            axes[0, 1].text(0.5, 0.5, 'No RPM data', ha='center', va='center', transform=axes[0, 1].transAxes)
            axes[0, 1].set_title('RPM Distribution')
        
        # Fuel rate distribution
        if fuel_rates:
            PlotGenerator._create_histogram(axes[0, 2], fuel_rates, 'Fuel Rate (L/h)', 'Fuel Rate Distribution', 'green')
            PlotGenerator._add_stats_text(axes[0, 2], fuel_rates, 'L/h')
        else:
            axes[0, 2].text(0.5, 0.5, 'No fuel rate data', ha='center', va='center', transform=axes[0, 2].transAxes)
            axes[0, 2].set_title('Fuel Rate Distribution')
        
        # Power distribution (convert to BHP)
        if power_values:
            # Convert kW to BHP (1 kW = 1.341 BHP)
            power_bhp = [power_kw * 1.341 for power_kw in power_values]
            PlotGenerator._create_histogram(axes[1, 0], power_bhp, 'Power (BHP)', 'Power Distribution', 'orange')
            PlotGenerator._add_stats_text(axes[1, 0], power_bhp, 'BHP')
        else:
            axes[1, 0].text(0.5, 0.5, 'No power data', ha='center', va='center', transform=axes[1, 0].transAxes)
            axes[1, 0].set_title('Power Distribution')
        
        # Fuel efficiency distribution (convert to kmpl)
        if fuel_efficiencies:
            # Convert L/100km to kmpl (kmpl = 100 / L/100km)
            fuel_efficiencies_kmpl = [100.0 / l_per_100km if l_per_100km > 0 else 0 for l_per_100km in fuel_efficiencies]
            PlotGenerator._create_histogram(axes[1, 1], fuel_efficiencies_kmpl, 'Fuel Efficiency (km/L)', 'Fuel Efficiency Distribution', 'darkgreen')
            PlotGenerator._add_stats_text(axes[1, 1], fuel_efficiencies_kmpl, 'km/L')
        else:
            axes[1, 1].text(0.5, 0.5, 'No fuel efficiency data', ha='center', va='center', transform=axes[1, 1].transAxes)
            axes[1, 1].set_title('Fuel Efficiency Distribution')
        
        # Throttle distribution
        if throttles:
            PlotGenerator._create_histogram(axes[2, 0], throttles, 'Throttle (%)', 'Throttle Distribution', 'brown')
            PlotGenerator._add_stats_text(axes[2, 0], throttles, '%')
        else:
            axes[2, 0].text(0.5, 0.5, 'No throttle data', ha='center', va='center', transform=axes[2, 0].transAxes)
            axes[2, 0].set_title('Throttle Distribution')
        
        # Engine load distribution
        if engine_loads:
            PlotGenerator._create_histogram(axes[2, 1], engine_loads, 'Engine Load (%)', 'Engine Load Distribution', 'purple')
            PlotGenerator._add_stats_text(axes[2, 1], engine_loads, '%')
        else:
            axes[2, 1].text(0.5, 0.5, 'No engine load data', ha='center', va='center', transform=axes[2, 1].transAxes)
            axes[2, 1].set_title('Engine Load Distribution')
        
        # MAF distribution
        if maf_values:
            PlotGenerator._create_histogram(axes[3, 0], maf_values, 'MAF (g/s)', 'MAF Distribution', 'cyan')
            PlotGenerator._add_stats_text(axes[3, 0], maf_values, 'g/s')
        else:
            axes[3, 0].text(0.5, 0.5, 'No MAF data', ha='center', va='center', transform=axes[3, 0].transAxes)
            axes[3, 0].set_title('MAF Distribution')
        
        # Empty subplot for balance
        axes[3, 1].text(0.5, 0.5, 'Additional metrics can be added here', ha='center', va='center', transform=axes[3, 1].transAxes, style='italic', alpha=0.5)
        axes[3, 1].set_title('Reserved')
        
        # Empty subplot for balance
        axes[3, 2].text(0.5, 0.5, 'Additional metrics can be added here', ha='center', va='center', transform=axes[3, 2].transAxes, style='italic', alpha=0.5)
        axes[3, 2].set_title('Reserved')
        
        plt.tight_layout()
        
        # Save plot
        output_path = Path(output_dir) / f"{track_name}_distributions.png"
        plt.savefig(output_path, dpi=300, bbox_inches='tight')
        plt.close()
        print(f"✓ Generated distribution plots: {output_path}")
    
    @staticmethod
    def create_time_series_plots(
        samples: List[TrackSample],
        metrics: TripMetrics,
        track_name: str,
        output_dir: str = '.',
        profile: Optional[VehicleProfile] = None
    ):
        """
        Create time-series plots for key metrics.
        
        Args:
            samples: List of track samples
            metrics: Trip metrics
            track_name: Name for plot files
            output_dir: Output directory for plot files
            profile: Vehicle profile for fuel calculations
        """
        if not PLOTTING_AVAILABLE:
            print(f"Warning: Matplotlib not available, skipping time series plots for {track_name}")
            return
        
        # Create figure with subplots
        fig, axes = plt.subplots(3, 2, figsize=(15, 15))
        fig.suptitle(f'{track_name} - Time Series', fontsize=16, fontweight='bold')
        
        # Extract data with timestamps
        timestamps, speeds = PlotGenerator._extract_time_series_speeds(samples)
        _, rpms = PlotGenerator._extract_time_series_rpms(samples)
        _, fuel_rates = PlotGenerator._extract_time_series_fuel_rates(samples, profile)
        _, fuel_efficiencies = PlotGenerator._extract_time_series_fuel_efficiencies(samples, profile)
        _, power_values = PlotGenerator._extract_time_series_power(samples, profile)
        
        # Speed over time
        if timestamps and speeds:
            PlotGenerator._create_time_plot(axes[0, 0], timestamps, speeds, 'Time', 'Speed (km/h)', 'Speed Over Time', 'blue')
        else:
            axes[0, 0].text(0.5, 0.5, 'No speed data', ha='center', va='center', transform=axes[0, 0].transAxes)
            axes[0, 0].set_title('Speed Over Time')
        
        # RPM over time
        if timestamps and rpms:
            PlotGenerator._create_time_plot(axes[0, 1], timestamps, rpms, 'Time', 'RPM', 'RPM Over Time', 'red')
        else:
            axes[0, 1].text(0.5, 0.5, 'No RPM data', ha='center', va='center', transform=axes[0, 1].transAxes)
            axes[0, 1].set_title('RPM Over Time')
        
        # Fuel rate over time
        if timestamps and fuel_rates:
            PlotGenerator._create_time_plot(axes[1, 0], timestamps, fuel_rates, 'Time', 'Fuel Rate (L/h)', 'Fuel Rate Over Time', 'green')
        else:
            axes[1, 0].text(0.5, 0.5, 'No fuel rate data', ha='center', va='center', transform=axes[1, 0].transAxes)
            axes[1, 0].set_title('Fuel Rate Over Time')
        
        # Fuel efficiency over time (convert to kmpl)
        if timestamps and fuel_efficiencies:
            # Convert L/100km to kmpl (kmpl = 100 / L/100km)
            fuel_efficiencies_kmpl = [100.0 / l_per_100km if l_per_100km > 0 else 0 for l_per_100km in fuel_efficiencies]
            PlotGenerator._create_time_plot(axes[1, 1], timestamps, fuel_efficiencies_kmpl, 'Time', 'Fuel Efficiency (km/L)', 'Fuel Efficiency Over Time', 'darkgreen')
        else:
            axes[1, 1].text(0.5, 0.5, 'No fuel efficiency data', ha='center', va='center', transform=axes[1, 1].transAxes)
            axes[1, 1].set_title('Fuel Efficiency Over Time')
        
        # Power over time (convert to BHP)
        if timestamps and power_values:
            # Convert kW to BHP (1 kW = 1.341 BHP)
            power_bhp = [power_kw * 1.341 for power_kw in power_values]
            PlotGenerator._create_time_plot(axes[2, 0], timestamps, power_bhp, 'Time', 'Power (BHP)', 'Power Over Time', 'orange')
        else:
            axes[2, 0].text(0.5, 0.5, 'No power data', ha='center', va='center', transform=axes[2, 0].transAxes)
            axes[2, 0].set_title('Power Over Time')
        
        # Empty subplot for balance
        axes[2, 1].text(0.5, 0.5, 'Additional metrics can be added here', ha='center', va='center', transform=axes[2, 1].transAxes, style='italic', alpha=0.5)
        axes[2, 1].set_title('Reserved')
        
        plt.tight_layout()
        
        # Save plot
        output_path = Path(output_dir) / f"{track_name}_timeseries.png"
        plt.savefig(output_path, dpi=300, bbox_inches='tight')
        plt.close()
        print(f"✓ Generated time series plots: {output_path}")
    
    @staticmethod
    def create_drive_mode_pie_chart(
        metrics: TripMetrics,
        track_name: str,
        output_dir: str = '.'
    ):
        """
        Create pie chart for drive mode distribution.
        
        Args:
            metrics: Trip metrics with drive mode percentages
            track_name: Name for plot file
            output_dir: Output directory for plot file
        """
        if not PLOTTING_AVAILABLE:
            print(f"Warning: Matplotlib not available, skipping pie chart for {track_name}")
            return
        
        fig, ax = plt.subplots(figsize=(10, 8))
        
        # Data for pie chart
        labels = ['Idle', 'City', 'Highway']
        sizes = [metrics.pct_idle, metrics.pct_city, metrics.pct_highway]
        colors = ['#ff4444', '#ffaa44', '#44ff44']
        explode = (0.05, 0.05, 0.05)  # Slightly separate all slices
        
        # Create pie chart
        wedges, texts, autotexts = ax.pie(sizes, explode=explode, labels=labels, colors=colors,
                                         autopct='%1.1f%%', shadow=True, startangle=90)
        
        # Equal aspect ratio ensures that pie is drawn as a circle
        ax.axis('equal')
        plt.title(f'{track_name} - Drive Mode Distribution', fontsize=14, fontweight='bold')
        
        # Add legend
        plt.legend(wedges, [f'{label}: {size:.1f}%' for label, size in zip(labels, sizes)],
                  title="Drive Modes", loc="center left", bbox_to_anchor=(1, 0, 0.5, 1))
        
        plt.tight_layout()
        
        # Save plot
        output_path = Path(output_dir) / f"{track_name}_drive_mode.png"
        plt.savefig(output_path, dpi=300, bbox_inches='tight')
        plt.close()
        print(f"✓ Generated drive mode pie chart: {output_path}")
    
    @staticmethod
    def _extract_speeds(samples: List[TrackSample]) -> List[float]:
        """Extract hybrid speeds from samples."""
        speeds = []
        for sample in samples:
            gps_speed = sample.gps.speed_kmh if sample.gps else None
            obd_speed = sample.obd.speed_kmh if sample.obd else None
            
            # Hybrid speed calculation
            if obd_speed is not None and obd_speed <= 20.0:
                speeds.append(obd_speed)
            elif gps_speed is not None:
                speeds.append(gps_speed)
            elif obd_speed is not None:
                speeds.append(obd_speed)
        
        return speeds
    
    @staticmethod
    def _extract_rpms(samples: List[TrackSample]) -> List[float]:
        """Extract RPM values from samples."""
        return [s.obd.rpm for s in samples if s.obd and s.obd.rpm is not None]
    
    @staticmethod
    def _extract_fuel_rates(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> List[float]:
        """Extract calculated fuel rate values from samples."""
        if not profile:
            return []
        
        fuel_calc = FuelCalculator()
        fuel_rates = []
        
        for sample in samples:
            if sample.obd:
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                if calculated_rate is not None:
                    fuel_rates.append(calculated_rate)
        
        return fuel_rates
    
    @staticmethod
    def _extract_throttles(samples: List[TrackSample]) -> List[float]:
        """Extract throttle position values from samples."""
        return [s.obd.throttle_pct for s in samples if s.obd and s.obd.throttle_pct is not None]
    
    @staticmethod
    def _extract_engine_loads(samples: List[TrackSample]) -> List[float]:
        """Extract engine load values from samples."""
        return [s.obd.engine_load_pct for s in samples if s.obd and s.obd.engine_load_pct is not None]
    
    @staticmethod
    def _extract_fuel_efficiencies(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> List[float]:
        """Calculate instantaneous fuel efficiencies from samples using calculated fuel rates."""
        if not profile:
            return []
        
        efficiencies = []
        fuel_calc = FuelCalculator()
        
        for sample in samples:
            if sample.obd:
                # Calculate fuel rate using 3-tier fallback
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                
                # Get hybrid speed
                gps_speed = sample.gps.speed_kmh if sample.gps else None
                obd_speed = sample.obd.speed_kmh if sample.obd else None
                
                if obd_speed is not None and obd_speed <= 20.0:
                    speed = obd_speed
                elif gps_speed is not None:
                    speed = gps_speed
                elif obd_speed is not None:
                    speed = obd_speed
                else:
                    speed = 0.0
                
                if calculated_rate is not None and speed > 0:
                    # L/100km = (fuel_rate_L/h / speed_km/h) × 100
                    l_per_100km = (calculated_rate / speed) * 100.0
                    efficiencies.append(l_per_100km)
        
        return efficiencies
    
    @staticmethod
    def _extract_time_series_speeds(samples: List[TrackSample]) -> Tuple[List[str], List[float]]:
        """Extract time series data for speed."""
        timestamps = []
        speeds = []
        
        for sample in samples:
            timestamps.append(sample.timestamp_ms)
            
            # Hybrid speed calculation
            gps_speed = sample.gps.speed_kmh if sample.gps else None
            obd_speed = sample.obd.speed_kmh if sample.obd else None
            
            if obd_speed is not None and obd_speed <= 20.0:
                speeds.append(obd_speed)
            elif gps_speed is not None:
                speeds.append(gps_speed)
            elif obd_speed is not None:
                speeds.append(obd_speed)
            else:
                speeds.append(0.0)
        
        return timestamps, speeds
    
    @staticmethod
    def _extract_time_series_rpms(samples: List[TrackSample]) -> Tuple[List[str], List[float]]:
        """Extract time series data for RPM."""
        timestamps = []
        rpms = []
        
        for sample in samples:
            timestamps.append(sample.timestamp_ms)
            rpms.append(sample.obd.rpm if sample.obd and sample.obd.rpm is not None else 0.0)
        
        return timestamps, rpms
    
    @staticmethod
    def _extract_time_series_fuel_rates(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> Tuple[List[str], List[float]]:
        """Extract time series data for fuel rate using calculated values."""
        timestamps = []
        fuel_rates = []
        fuel_calc = FuelCalculator()
        
        for sample in samples:
            timestamps.append(sample.timestamp_ms)
            
            # Calculate fuel rate using 3-tier fallback
            if sample.obd and profile:
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                fuel_rates.append(calculated_rate if calculated_rate is not None else 0.0)
            else:
                fuel_rates.append(0.0)
        
        return timestamps, fuel_rates
    
    @staticmethod
    def _extract_time_series_fuel_efficiencies(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> Tuple[List[str], List[float]]:
        """Extract time series data for fuel efficiency using calculated fuel rates."""
        timestamps = []
        efficiencies = []
        fuel_calc = FuelCalculator()
        
        for sample in samples:
            timestamps.append(sample.timestamp_ms)
            
            # Calculate fuel rate using 3-tier fallback
            if sample.obd and profile:
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                
                # Get hybrid speed
                gps_speed = sample.gps.speed_kmh if sample.gps else None
                obd_speed = sample.obd.speed_kmh if sample.obd else None
                
                if obd_speed is not None and obd_speed <= 20.0:
                    speed = obd_speed
                elif gps_speed is not None:
                    speed = gps_speed
                elif obd_speed is not None:
                    speed = obd_speed
                else:
                    speed = 0.0
                
                if calculated_rate is not None and speed > 0:
                    # L/100km = (fuel_rate_L/h / speed_km/h) × 100
                    l_per_100km = (calculated_rate / speed) * 100.0
                    efficiencies.append(l_per_100km)
                else:
                    efficiencies.append(0.0)
            else:
                efficiencies.append(0.0)
        
        return timestamps, efficiencies
    
    @staticmethod
    def _create_histogram(ax, data: List[float], xlabel: str, title: str, color: str):
        """Create a histogram with statistics."""
        ax.hist(data, bins=30, alpha=0.7, color=color, edgecolor='black')
        ax.set_xlabel(xlabel)
        ax.set_ylabel('Frequency')
        ax.set_title(title)
        ax.grid(True, alpha=0.3)
    
    @staticmethod
    def _add_stats_text(ax, data: List[float], unit: str):
        """Add statistics text to plot."""
        mean_val = np.mean(data)
        median_val = np.median(data)
        std_val = np.std(data)
        
        stats_text = f'Mean: {mean_val:.2f} {unit}\nMedian: {median_val:.2f} {unit}\nStd: {std_val:.2f} {unit}'
        ax.text(0.02, 0.98, stats_text, transform=ax.transAxes, 
                verticalalignment='top', bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.5))
    
    @staticmethod
    def create_chronological_time_series(
        samples_dict: dict,
        track_names: List[str],
        output_dir: str,
        profile: VehicleProfile
    ) -> List[TrackSample]:
        """
        Create chronological combined time series by sorting tracks by date.
        
        Args:
            samples_dict: Dictionary of track name -> samples
            track_names: List of track names
            output_dir: Output directory
            profile: Vehicle profile for calculations
            
        Returns:
            List of chronologically ordered samples
        """
        if not PLOTTING_AVAILABLE:
            print("Warning: Matplotlib not available, skipping chronological time series")
            return []
        
        # Extract dates from track names and sort chronologically
        track_info = []
        for track_name in track_names:
            samples = samples_dict[track_name]
            if samples:
                # Extract date from track name (assuming format: Name_YYYY-MM-DD_HHMMSS)
                import re
                date_match = re.search(r'(\d{4}-\d{2}-\d{2})', track_name)
                if date_match:
                    date_str = date_match.group(1)
                    # Sort by date, then by track name for same date
                    track_info.append((date_str, track_name, samples))
                else:
                    # If no date found, use track name as fallback
                    track_info.append(('9999-12-31', track_name, samples))
        
        # Sort by date, then by track name
        track_info.sort(key=lambda x: (x[0], x[1]))
        
        # Create chronological samples list
        chronological_samples = []
        time_offset_ms = 0  # Track time offset between tracks
        
        for i, (date_str, track_name, samples) in enumerate(track_info):
            if not samples:
                continue
            
            # For the first track, use original timestamps
            if i == 0:
                chronological_samples.extend(samples)
                # Set time_offset to the end of current track for next track
                time_offset_ms = samples[-1].timestamp_ms
            else:
                # For subsequent tracks, adjust timestamps to be chronological with no gaps
                track_start_offset = time_offset_ms - samples[0].timestamp_ms
                
                adjusted_samples = []
                for sample in samples:
                    # Create a new sample with adjusted timestamp (no gaps)
                    adjusted_sample = TrackSample(
                        timestamp_ms=sample.timestamp_ms + track_start_offset,
                        sample_no=sample.sample_no,
                        gps=sample.gps,
                        obd=sample.obd
                    )
                    adjusted_samples.append(adjusted_sample)
                
                chronological_samples.extend(adjusted_samples)
                
                # Update time_offset to the end of current track (no gaps)
                time_offset_ms = adjusted_samples[-1].timestamp_ms
        
        # Create the chronological time series plot
        if chronological_samples:
            # Create a dummy metrics object for plotting
            from obd_types import TripMetrics
            dummy_metrics = TripMetrics(
                distance_km=sum(s.gps.speed_kmh if s.gps else 0 for s in chronological_samples) / 3600,  # Rough estimate
                fuel_used_l=0.0,
                avg_fuel_lper100km=0.0,
                avg_fuel_kpl=0.0,
                avg_speed_kmh=0.0,
                max_speed_kmh=0.0,
                moving_time_sec=0,
                stopped_time_sec=0,
                total_time_sec=0,
                pct_idle=0.0,
                pct_city=0.0,
                pct_highway=0.0,
                fuel_cost=0.0,
                avg_co2_g_per_km=0.0,
                sample_count=len(chronological_samples)
            )
            
            PlotGenerator.create_time_series_plots(
                chronological_samples, 
                dummy_metrics, 
                "combined_chronological", 
                output_dir, 
                profile
            )
            
            print(f"✓ Generated chronological time series plot with {len(track_info)} tracks")
        
        return chronological_samples
    
    @staticmethod
    def _create_time_plot(ax, timestamps: List[str], values: List[float], xlabel: str, ylabel: str, title: str, color: str):
        """Create a time series plot with average line."""
        # Convert timestamps to relative time in minutes
        if timestamps:
            start_time = timestamps[0]
            time_minutes = [(t - start_time) / 60000.0 for t in timestamps]  # Convert ms to minutes
            
            # Plot the main data
            ax.plot(time_minutes, values, color=color, linewidth=1.5, alpha=0.7, label='Data')
            
            # Calculate and plot average (excluding zeros)
            non_zero_values = [v for v in values if v > 0]
            if non_zero_values:
                avg_value = sum(non_zero_values) / len(non_zero_values)
                ax.axhline(y=avg_value, color='red', linestyle='--', linewidth=2, alpha=0.8, 
                          label=f'Avg: {avg_value:.2f}')
                
                # Add average value text
                ax.text(0.02, 0.98, f'Avg (excl. zeros): {avg_value:.2f}', 
                       transform=ax.transAxes, verticalalignment='top',
                       bbox=dict(boxstyle='round', facecolor='red', alpha=0.2))
            
            ax.set_xlabel('Time (minutes)')
            ax.set_ylabel(ylabel)
            ax.set_title(title)
            ax.grid(True, alpha=0.3)
            ax.legend(loc='upper right')
    
    @staticmethod
    def _extract_power(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> List[float]:
        """Extract thermodynamic power values for distribution plotting."""
        if not profile:
            return []
        
        power_values = []
        fuel_calc = FuelCalculator()
        
        for sample in samples:
            if sample.obd:
                # Calculate fuel rate using 3-tier fallback
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                
                # Calculate thermodynamic power
                if calculated_rate is not None:
                    power_kw = PowerCalculator.thermodynamic(
                        calculated_rate,
                        profile.fuel_type.energy_density_mjpl
                    )
                    if power_kw is not None:
                        power_values.append(power_kw)
        
        return power_values
    
    @staticmethod
    def _extract_maf(samples: List[TrackSample]) -> List[float]:
        """Extract MAF values for distribution plotting."""
        maf_values = []
        
        for sample in samples:
            if sample.obd and sample.obd.maf_gs is not None:
                maf_values.append(sample.obd.maf_gs)
        
        # Debug MAF analysis
        if maf_values:
            print(f"\n🔍 MAF Analysis:")
            print(f"   Raw MAF range: {min(maf_values):.2f} - {max(maf_values):.2f} g/s")
            print(f"   Raw MAF mean: {np.mean(maf_values):.2f} g/s")
            print(f"   Raw MAF median: {np.median(maf_values):.2f} g/s")
            
            # Test different unit conversions
            max_maf = max(maf_values)
            
            # Test if it's kg/h instead of g/s
            maf_as_kgh = max_maf * 3.6  # g/s → kg/h
            print(f"   If kg/h: {maf_as_kgh:.2f} kg/h (should be 50-300 kg/h for diesel)")
            
            # Test if it's lb/min instead of g/s
            maf_as_lbmin = max_maf / 7.56  # g/s → lb/min
            print(f"   If lb/min: {maf_as_lbmin:.2f} lb/min (should be 0.5-20 lb/min for diesel)")
            
            # Test scaling factors
            print(f"   With 2x scaling: {max_maf * 2:.2f} g/s")
            print(f"   With 3x scaling: {max_maf * 3:.2f} g/s")
            print(f"   With 4x scaling: {max_maf * 4:.2f} g/s")
            print(f"   Expected for 1.3L diesel: 15-50 g/s")
            
            # Recommendation
            if max_maf < 10:
                print(f"   💡 RECOMMENDATION: MAF values too low, try 3-4x scaling factor")
            elif max_maf > 100:
                print(f"   💡 RECOMMENDATION: MAF values too high, might be in kg/h")
            else:
                print(f"   💡 MAF range looks reasonable for g/s")
        
        return maf_values
    
    @staticmethod
    def _extract_time_series_power(samples: List[TrackSample], profile: Optional[VehicleProfile] = None) -> Tuple[List[str], List[float]]:
        """Extract thermodynamic power values for time series plotting."""
        if not profile:
            return [], []
        
        timestamps = []
        power_values = []
        fuel_calc = FuelCalculator()
        
        for sample in samples:
            timestamps.append(sample.timestamp_ms)
            
            if sample.obd:
                # Calculate fuel rate using 3-tier fallback
                calculated_rate = fuel_calc.effective_fuel_rate(
                    sample.obd.fuel_rate_lh,
                    sample.obd.maf_gs,
                    profile.fuel_type.maf_ml_per_gram,
                    sample.obd.intake_map_kpa,
                    sample.obd.intake_temp_c,
                    sample.obd.rpm,
                    profile.engine_displacement_cc,
                    profile.volumetric_efficiency_pct,
                    profile.fuel_type,
                    sample.obd.baro_pressure_kpa,
                    sample.obd.engine_load_pct
                )
                
                # Calculate thermodynamic power
                if calculated_rate is not None:
                    power_kw = PowerCalculator.thermodynamic(
                        calculated_rate,
                        profile.fuel_type.energy_density_mjpl
                    )
                    if power_kw is not None:
                        power_values.append(power_kw)
                    else:
                        power_values.append(0.0)
                else:
                    power_values.append(0.0)
            else:
                # No OBD data, add 0 power to maintain dimension consistency
                power_values.append(0.0)
        
        return timestamps, power_values
