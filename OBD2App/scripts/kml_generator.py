"""
KML generation for track visualization.
Reuses logic from analyze_track.py with enhanced features.
"""

import xml.etree.ElementTree as ET
from typing import List, Tuple, Optional
from pathlib import Path
from obd_types import TrackSample, TripMetrics

# Optional numpy import
try:
    import numpy as np
    NUMPY_AVAILABLE = True
except ImportError:
    NUMPY_AVAILABLE = False
    # Simple fallback for percentile calculation
    def percentile(data, p):
        sorted_data = sorted(data)
        if not sorted_data:
            return 0.0
        k = (len(sorted_data) - 1) * p / 100
        f = int(k)
        c = k - f
        if f + 1 < len(sorted_data):
            return sorted_data[f] * (1 - c) + sorted_data[f + 1] * c
        return sorted_data[f]
    
    class np:
        @staticmethod
        def percentile(data, p):
            return percentile(data, p)


class KMLGenerator:
    """Generates KML files for track visualization."""
    
    @staticmethod
    def create_track_kml(
        samples: List[TrackSample],
        metrics: TripMetrics,
        track_name: str,
        metric: str = 'speed',
        output_dir: str = '.'
    ):
        """
        Create KML file with color-coded track segments.
        
        Args:
            samples: List of track samples with GPS data
            metrics: Trip metrics for the track
            track_name: Name for the KML file
            metric: Metric to color by ('speed', 'fuel_efficiency', 'rpm')
            output_dir: Output directory for KML file
        """
        # Filter samples with GPS data
        gps_samples = [s for s in samples if s.gps and s.gps.lat and s.gps.lon]
        
        if len(gps_samples) < 2:
            print(f"Warning: Not enough GPS data for KML generation in {track_name}")
            return
        
        # Extract metric values
        metric_values = KMLGenerator._extract_metric_values(gps_samples, metric)
        
        if not metric_values or all(v is None for v in metric_values):
            print(f"Warning: No {metric} data available for KML generation in {track_name}")
            return
        
        # Calculate color ranges
        low_threshold, high_threshold = KMLGenerator._calculate_percentile_ranges(metric_values)
        
        # Create KML structure
        kml = ET.Element('kml', xmlns='http://www.opengis.net/kml/2.2')
        document = ET.SubElement(kml, 'Document')
        ET.SubElement(document, 'name').text = f"{track_name}_{metric}"
        
        # Define styles for colors
        KMLGenerator._create_styles(document, low_threshold, high_threshold, metric)
        
        # Create track segments
        KMLGenerator._create_track_segments(document, gps_samples, metric_values, 
                                          low_threshold, high_threshold, metric)
        
        # Write KML file
        output_path = Path(output_dir) / f"{track_name}_{metric}.kml"
        tree = ET.ElementTree(kml)
        tree.write(output_path, encoding='utf-8', xml_declaration=True)
        print(f"✓ Generated KML: {output_path}")
    
    @staticmethod
    def _extract_metric_values(samples: List[TrackSample], metric: str) -> List[Optional[float]]:
        """Extract metric values from samples."""
        values = []
        
        for sample in samples:
            if metric == 'speed':
                # Use hybrid speed calculation
                gps_speed = sample.gps.speed_kmh if sample.gps else None
                obd_speed = sample.obd.speed_kmh if sample.obd else None
                
                # Hybrid speed: OBD ≤20 km/h, GPS >20 km/h
                if obd_speed is not None and obd_speed <= 20.0:
                    values.append(obd_speed)
                elif gps_speed is not None:
                    values.append(gps_speed)
                elif obd_speed is not None:
                    values.append(obd_speed)
                else:
                    values.append(None)
                    
            elif metric == 'fuel_efficiency':
                # Calculate instantaneous fuel efficiency
                if sample.obd and sample.obd.fuel_rate_lh:
                    speed = KMLGenerator._get_hybrid_speed(sample)
                    if speed > 0:
                        # L/100km = (fuel_rate_L/h / speed_km/h) × 100
                        l_per_100km = (sample.obd.fuel_rate_lh / speed) * 100.0
                        values.append(l_per_100km)
                    else:
                        values.append(None)
                else:
                    values.append(None)
                    
            elif metric == 'rpm':
                values.append(sample.obd.rpm if sample.obd else None)
                
            else:
                values.append(None)
        
        return values
    
    @staticmethod
    def _get_hybrid_speed(sample: TrackSample) -> float:
        """Get hybrid speed for a sample."""
        gps_speed = sample.gps.speed_kmh if sample.gps else None
        obd_speed = sample.obd.speed_kmh if sample.obd else None
        
        if obd_speed is not None and obd_speed <= 20.0:
            return obd_speed
        elif gps_speed is not None:
            return gps_speed
        elif obd_speed is not None:
            return obd_speed
        else:
            return 0.0
    
    @staticmethod
    def _calculate_percentile_ranges(values: List[Optional[float]]) -> Tuple[float, float]:
        """Calculate 33rd and 67th percentiles for color ranges."""
        valid_values = [v for v in values if v is not None]
        
        if not valid_values:
            return 0.0, 0.0
        
        low_threshold = np.percentile(valid_values, 33)
        high_threshold = np.percentile(valid_values, 67)
        
        return low_threshold, high_threshold
    
    @staticmethod
    def _create_styles(document: ET.Element, low_threshold: float, high_threshold: float, metric: str):
        """Create color styles for KML."""
        # Red style (low values)
        red_style = ET.SubElement(document, 'Style', id='redStyle')
        red_line_style = ET.SubElement(red_style, 'LineStyle')
        ET.SubElement(red_line_style, 'color').text = 'ff0000ff'  # Red
        ET.SubElement(red_line_style, 'width').text = '4'
        
        # Yellow style (medium values)
        yellow_style = ET.SubElement(document, 'Style', id='yellowStyle')
        yellow_line_style = ET.SubElement(yellow_style, 'LineStyle')
        ET.SubElement(yellow_line_style, 'color').text = 'ff00ffff'  # Yellow
        ET.SubElement(yellow_line_style, 'width').text = '4'
        
        # Green style (high values)
        green_style = ET.SubElement(document, 'Style', id='greenStyle')
        green_line_style = ET.SubElement(green_style, 'LineStyle')
        ET.SubElement(green_line_style, 'color').text = 'ff00ff00'  # Green
        ET.SubElement(green_line_style, 'width').text = '4'
        
        # Add description of thresholds
        desc = ET.SubElement(document, 'description')
        desc.text = f"{metric.capitalize()} ranges: Red ≤ {low_threshold:.2f}, Yellow {low_threshold:.2f}-{high_threshold:.2f}, Green ≥ {high_threshold:.2f}"
    
    @staticmethod
    def _create_track_segments(
        document: ET.Element,
        samples: List[TrackSample],
        metric_values: List[Optional[float]],
        low_threshold: float,
        high_threshold: float,
        metric: str
    ):
        """Create colored track segments."""
        folder = ET.SubElement(document, 'Folder')
        ET.SubElement(folder, 'name').text = 'Track Segments'
        
        # Group consecutive samples with same color
        current_color = None
        current_coords = []
        current_value = None
        
        for i, (sample, value) in enumerate(zip(samples, metric_values)):
            if value is None:
                continue
            
            # Determine color
            if value <= low_threshold:
                color = 'red'
                style_id = '#redStyle'
            elif value <= high_threshold:
                color = 'yellow'
                style_id = '#yellowStyle'
            else:
                color = 'green'
                style_id = '#greenStyle'
            
            # Start new segment if color changes
            if color != current_color or i == len(samples) - 1:
                # Save previous segment
                if current_coords and current_color:
                    KMLGenerator._create_placemark(folder, current_coords, 
                                                 f"{current_color} ({current_value:.2f})", 
                                                 style_id, metric)
                
                # Start new segment
                current_color = color
                current_value = value
                current_coords = []
            
            # Add coordinate
            coord = f"{sample.gps.lon:.6f},{sample.gps.lat:.6f}"
            current_coords.append(coord)
        
        # Save last segment
        if current_coords and current_color:
            style_id = f"#{current_color}Style"
            KMLGenerator._create_placemark(folder, current_coords, 
                                         f"{current_color} ({current_value:.2f})", 
                                         style_id, metric)
    
    @staticmethod
    def _create_placemark(folder: ET.Element, coords: List[str], name: str, style_id: str, metric: str):
        """Create a placemark for a track segment."""
        placemark = ET.SubElement(folder, 'Placemark')
        ET.SubElement(placemark, 'name').text = name
        ET.SubElement(placemark, 'styleUrl').text = style_id
        
        line_string = ET.SubElement(placemark, 'LineString')
        ET.SubElement(line_string, 'coordinates').text = ' '.join(coords)
        ET.SubElement(line_string, 'tessellate').text = '1'  # Follow terrain
    
    @staticmethod
    def create_drive_mode_kml(
        samples: List[TrackSample],
        track_name: str,
        output_dir: str = '.'
    ):
        """
        Create KML file colored by drive mode (idle/city/highway).
        
        Args:
            samples: List of track samples with GPS data
            track_name: Name for the KML file
            output_dir: Output directory for KML file
        """
        # Filter samples with GPS data
        gps_samples = [s for s in samples if s.gps and s.gps.lat and s.gps.lon]
        
        if len(gps_samples) < 2:
            print(f"Warning: Not enough GPS data for drive mode KML in {track_name}")
            return
        
        # Create KML structure
        kml = ET.Element('kml', xmlns='http://www.opengis.net/kml/2.2')
        document = ET.SubElement(kml, 'Document')
        ET.SubElement(document, 'name').text = f"{track_name}_drive_mode"
        
        # Define styles for drive modes
        KMLGenerator._create_drive_mode_styles(document)
        
        # Create track segments by drive mode
        KMLGenerator._create_drive_mode_segments(document, gps_samples)
        
        # Write KML file
        output_path = Path(output_dir) / f"{track_name}_drive_mode.kml"
        tree = ET.ElementTree(kml)
        tree.write(output_path, encoding='utf-8', xml_declaration=True)
        print(f"✓ Generated drive mode KML: {output_path}")
    
    @staticmethod
    def _create_drive_mode_styles(document: ET.Element):
        """Create color styles for drive modes."""
        # Red style (idle)
        idle_style = ET.SubElement(document, 'Style', id='idleStyle')
        idle_line_style = ET.SubElement(idle_style, 'LineStyle')
        ET.SubElement(idle_line_style, 'color').text = 'ff0000ff'  # Red
        ET.SubElement(idle_line_style, 'width').text = '4'
        
        # Yellow style (city)
        city_style = ET.SubElement(document, 'Style', id='cityStyle')
        city_line_style = ET.SubElement(city_style, 'LineStyle')
        ET.SubElement(city_line_style, 'color').text = 'ff00ffff'  # Yellow
        ET.SubElement(city_line_style, 'width').text = '4'
        
        # Green style (highway)
        highway_style = ET.SubElement(document, 'Style', id='highwayStyle')
        highway_line_style = ET.SubElement(highway_style, 'LineStyle')
        ET.SubElement(highway_line_style, 'color').text = 'ff00ff00'  # Green
        ET.SubElement(highway_line_style, 'width').text = '4'
        
        # Add description
        desc = ET.SubElement(document, 'description')
        desc.text = "Drive modes: Red = Idle (≤2 km/h), Yellow = City (2-60 km/h), Green = Highway (>60 km/h)"
    
    @staticmethod
    def _create_drive_mode_segments(document: ET.Element, samples: List[TrackSample]):
        """Create track segments colored by drive mode."""
        folder = ET.SubElement(document, 'Folder')
        ET.SubElement(folder, 'name').text = 'Drive Mode Segments'
        
        # Group consecutive samples with same drive mode
        current_mode = None
        current_coords = []
        
        for i, sample in enumerate(samples):
            # Calculate hybrid speed
            speed = KMLGenerator._get_hybrid_speed(sample)
            
            # Determine drive mode
            if speed <= 2.0:
                mode = 'idle'
                style_id = '#idleStyle'
            elif speed <= 60.0:
                mode = 'city'
                style_id = '#cityStyle'
            else:
                mode = 'highway'
                style_id = '#highwayStyle'
            
            # Start new segment if mode changes
            if mode != current_mode or i == len(samples) - 1:
                # Save previous segment
                if current_coords and current_mode:
                    KMLGenerator._create_placemark(folder, current_coords, 
                                                 f"{current_mode.capitalize()}", 
                                                 style_id, 'drive_mode')
                
                # Start new segment
                current_mode = mode
                current_coords = []
            
            # Add coordinate
            coord = f"{sample.gps.lon:.6f},{sample.gps.lat:.6f}"
            current_coords.append(coord)
        
        # Save last segment
        if current_coords and current_mode:
            style_id = f"#{current_mode}Style"
            KMLGenerator._create_placemark(folder, current_coords, 
                                         f"{current_mode.capitalize()}", 
                                         style_id, 'drive_mode')
