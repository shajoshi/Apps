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

    # Conservative limits to prevent bad joins between consecutive points
    MAX_POINT_GAP_KM = 0.1
    MAX_IMPLIED_SPEED_KMH = 130.0
    HIGHWAY_SPEED_KMH = 60.0
    IDLE_SPEED_KMH = 2.0
    ALTITUDE_SEGMENT_KM = 25.0
    
    @staticmethod
    def create_combined_importable_kml(
        samples: List[TrackSample],
        track_name: str,
        output_dir: str = '.'
    ):
        """
        Create a Google Earth-friendly KML file with discontinuous GPS segments preserved.
        Each continuous segment is written as its own LineString so no long connector lines
        are drawn across track gaps.
        """
        gps_samples = [s for s in samples if s.gps and s.gps.lat is not None and s.gps.lon is not None]

        if len(gps_samples) < 2:
            print(f"Warning: Not enough GPS data for combined KML generation in {track_name}")
            return

        kml = ET.Element('kml', xmlns='http://www.opengis.net/kml/2.2')
        document = ET.SubElement(kml, 'Document')
        ET.SubElement(document, 'name').text = track_name
        ET.SubElement(document, 'description').text = 'Combined chronological track'

        segments = KMLGenerator._split_continuous_gps_segments(gps_samples)

        placemark = ET.SubElement(document, 'Placemark')
        ET.SubElement(placemark, 'name').text = track_name
        multi_geometry = ET.SubElement(placemark, 'MultiGeometry')

        valid_segment_count = 0
        for index, segment in enumerate(segments, start=1):
            if len(segment) < 2:
                continue

            line_string = ET.SubElement(multi_geometry, 'LineString')
            ET.SubElement(line_string, 'tessellate').text = '1'
            ET.SubElement(line_string, 'altitudeMode').text = 'clampToGround'

            coords = ET.SubElement(line_string, 'coordinates')
            coords.text = ' '.join(
                f"{sample.gps.lon:.6f},{sample.gps.lat:.6f},0"
                for sample in segment
                if sample.gps is not None
            )
            valid_segment_count += 1

        if valid_segment_count == 0:
            print(f"Warning: No valid GPS segments found for combined KML generation in {track_name}")
            return

        # Add event placemarks for notable points
        KMLGenerator._add_event_placemarks(document, gps_samples, track_name)
        KMLGenerator._add_max_speed_placemarks(document, gps_samples, track_name)
        KMLGenerator._add_max_altitude_placemarks(document, gps_samples, track_name)

        output_path = Path(output_dir) / f"{track_name}_combined.kml"
        tree = ET.ElementTree(kml)
        tree.write(output_path, encoding='utf-8', xml_declaration=True)
        print(f"✓ Generated combined importable KML: {output_path}")

    @staticmethod
    def _split_continuous_gps_segments(samples: List[TrackSample]) -> List[List[TrackSample]]:
        """Split a chronological GPS list into continuous segments without dropping points."""
        if not samples:
            return []

        def is_valid(sample: TrackSample) -> bool:
            return (
                sample.gps is not None
                and sample.gps.lat is not None
                and sample.gps.lon is not None
                and -90.0 <= sample.gps.lat <= 90.0
                and -180.0 <= sample.gps.lon <= 180.0
            )

        segments: List[List[TrackSample]] = []
        current_segment: List[TrackSample] = []
        last_kept: TrackSample | None = None

        for sample in samples[1:]:
            if not is_valid(sample):
                continue

            if last_kept is None:
                current_segment = [sample]
                segments.append(current_segment)
                last_kept = sample
                continue

            distance_km = KMLGenerator._haversine_km(
                last_kept.gps.lat,
                last_kept.gps.lon,
                sample.gps.lat,
                sample.gps.lon,
            )

            dt_hr = max(0.0, (sample.timestamp_ms - last_kept.timestamp_ms) / 3_600_000.0)
            implied_speed = distance_km / dt_hr if dt_hr > 0 else float('inf')

            if distance_km <= KMLGenerator.MAX_POINT_GAP_KM and implied_speed <= KMLGenerator.MAX_IMPLIED_SPEED_KMH:
                if not current_segment:
                    current_segment = [last_kept]
                    segments.append(current_segment)
                if current_segment[-1].gps and current_segment[-1].gps.lat == sample.gps.lat and current_segment[-1].gps.lon == sample.gps.lon:
                    continue
                current_segment.append(sample)
                last_kept = sample
            else:
                current_segment = [sample]
                segments.append(current_segment)
                last_kept = sample

        return [segment for segment in segments if len(segment) >= 2]

    @staticmethod
    def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        """Calculate distance between two coordinates in kilometers."""
        from math import radians, sin, cos, sqrt, atan2

        r = 6371.0
        dlat = radians(lat2 - lat1)
        dlon = radians(lon2 - lon1)
        a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
        c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c

    @staticmethod
    def _add_event_placemarks(document: ET.Element, samples: List[TrackSample], track_name: str):
        """Add placemarks for max speed, highway points, and stopped/idle points."""
        gps_samples = [
            s for s in samples
            if s.gps and s.gps.lat is not None and s.gps.lon is not None
        ]
        if not gps_samples:
            return

        def hybrid_speed(sample: TrackSample) -> float:
            gps_speed = sample.gps.speed_kmh if sample.gps else None
            obd_speed = sample.obd.speed_kmh if sample.obd else None
            if obd_speed is not None and obd_speed <= 20.0:
                return float(obd_speed)
            if gps_speed is not None:
                return float(gps_speed)
            if obd_speed is not None:
                return float(obd_speed)
            return 0.0

        def add_point_placemark(name: str, description: str, sample: TrackSample, style_url: str):
            placemark = ET.SubElement(document, 'Placemark')
            ET.SubElement(placemark, 'name').text = name
            ET.SubElement(placemark, 'description').text = description
            ET.SubElement(placemark, 'styleUrl').text = style_url
            point = ET.SubElement(placemark, 'Point')
            ET.SubElement(point, 'coordinates').text = f"{sample.gps.lon:.6f},{sample.gps.lat:.6f},0"

        max_speed_sample = None
        max_speed = -1.0
        highway_count = 0
        idle_count = 0

        for sample in gps_samples:
            speed = hybrid_speed(sample)
            if speed > max_speed:
                max_speed = speed
                max_speed_sample = sample
            if speed > KMLGenerator.HIGHWAY_SPEED_KMH:
                highway_count += 1
            if speed < KMLGenerator.IDLE_SPEED_KMH:
                idle_count += 1

        # Simple icon styles for event points
        for style_id, color, icon_href, scale in [
            ('maxSpeedStyle', 'ff00a5ff', 'http://maps.google.com/mapfiles/kml/paddle/wht-blank.png', '1.1'),
            ('highwayStyle', 'ff00ff00', 'http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png', '0.6'),
            ('idleStyle', 'ff0000ff', 'http://maps.google.com/mapfiles/kml/paddle/red-circle.png', '0.8'),
        ]:
            style = ET.SubElement(document, 'Style', id=style_id)
            icon_style = ET.SubElement(style, 'IconStyle')
            ET.SubElement(icon_style, 'color').text = color
            ET.SubElement(icon_style, 'scale').text = scale
            icon = ET.SubElement(icon_style, 'Icon')
            ET.SubElement(icon, 'href').text = icon_href

        if max_speed_sample is not None:
            add_point_placemark(
                f'Speed {max_speed:.0f} kmph',
                f'Max speed: {max_speed:.1f} km/h',
                max_speed_sample,
                '#maxSpeedStyle'
            )

        highway_points = [s for s in gps_samples if hybrid_speed(s) > KMLGenerator.HIGHWAY_SPEED_KMH]
        for idx, sample in enumerate(highway_points[:50], start=1):
            add_point_placemark(
                '',
                '',
                sample,
                '#highwayStyle'
            )

        idle_points = [s for s in gps_samples if hybrid_speed(s) < KMLGenerator.IDLE_SPEED_KMH]
        for idx, sample in enumerate(idle_points[:50], start=1):
            add_point_placemark(
                '',
                '',
                sample,
                '#idleStyle'
            )

    @staticmethod
    def _add_max_altitude_placemarks(document: ET.Element, samples: List[TrackSample], track_name: str):
        """Add placemarks for the highest GPS altitude point in each 25 km track segment."""
        gps_samples = [
            s for s in samples
            if s.gps
            and s.gps.lat is not None
            and s.gps.lon is not None
            and s.gps.alt_msl is not None
            and -90.0 <= s.gps.lat <= 90.0
            and -180.0 <= s.gps.lon <= 180.0
        ]
        if len(gps_samples) < 2:
            return

        # Build cumulative distance along the track and split into 25 km windows.
        segment_points: dict[int, list[TrackSample]] = {}
        cumulative_km = 0.0
        prev_sample = gps_samples[0]
        segment_points.setdefault(0, []).append(prev_sample)

        for sample in gps_samples[1:]:
            step_km = KMLGenerator._haversine_km(
                prev_sample.gps.lat,
                prev_sample.gps.lon,
                sample.gps.lat,
                sample.gps.lon,
            )
            cumulative_km += max(0.0, step_km)
            segment_index = int(cumulative_km // KMLGenerator.ALTITUDE_SEGMENT_KM)
            segment_points.setdefault(segment_index, []).append(sample)
            prev_sample = sample

        def add_point_placemark(name: str, description: str, sample: TrackSample):
            placemark = ET.SubElement(document, 'Placemark')
            ET.SubElement(placemark, 'name').text = name
            ET.SubElement(placemark, 'description').text = description
            style = ET.SubElement(document, 'Style', id='altitudeStyle')
            icon_style = ET.SubElement(style, 'IconStyle')
            ET.SubElement(icon_style, 'color').text = 'ff00ffff'
            ET.SubElement(icon_style, 'scale').text = '1.1'
            icon = ET.SubElement(icon_style, 'Icon')
            ET.SubElement(icon, 'href').text = 'http://maps.google.com/mapfiles/kml/paddle/ylw-stars.png'
            ET.SubElement(placemark, 'styleUrl').text = '#altitudeStyle'
            point = ET.SubElement(placemark, 'Point')
            ET.SubElement(point, 'coordinates').text = f"{sample.gps.lon:.6f},{sample.gps.lat:.6f},0"

        for segment_index in sorted(segment_points.keys()):
            segment = segment_points[segment_index]
            if not segment:
                continue

            max_alt_sample = max(segment, key=lambda s: s.gps.alt_msl if s.gps and s.gps.alt_msl is not None else float('-inf'))
            if max_alt_sample.gps is None or max_alt_sample.gps.alt_msl is None:
                continue

            start_km = segment_index * KMLGenerator.ALTITUDE_SEGMENT_KM
            add_point_placemark(
                f'Alt {max_alt_sample.gps.alt_msl:.0f} msl',
                f'Max altitude in this segment: {max_alt_sample.gps.alt_msl:.1f} m',
                max_alt_sample
            )

    @staticmethod
    def _add_max_speed_placemarks(document: ET.Element, samples: List[TrackSample], track_name: str):
        """Add placemarks for the highest speed point in each 25 km track segment."""
        gps_samples = [
            s for s in samples
            if s.gps
            and s.gps.lat is not None
            and s.gps.lon is not None
            and -90.0 <= s.gps.lat <= 90.0
            and -180.0 <= s.gps.lon <= 180.0
        ]
        if len(gps_samples) < 2:
            return

        segment_points: dict[int, list[TrackSample]] = {}
        cumulative_km = 0.0
        prev_sample = gps_samples[0]
        segment_points.setdefault(0, []).append(prev_sample)

        for sample in gps_samples[1:]:
            step_km = KMLGenerator._haversine_km(
                prev_sample.gps.lat,
                prev_sample.gps.lon,
                sample.gps.lat,
                sample.gps.lon,
            )
            cumulative_km += max(0.0, step_km)
            segment_index = int(cumulative_km // KMLGenerator.ALTITUDE_SEGMENT_KM)
            segment_points.setdefault(segment_index, []).append(sample)
            prev_sample = sample

        def hybrid_speed(sample: TrackSample) -> float:
            gps_speed = sample.gps.speed_kmh if sample.gps else None
            obd_speed = sample.obd.speed_kmh if sample.obd else None
            if obd_speed is not None and obd_speed <= 20.0:
                return float(obd_speed)
            if gps_speed is not None:
                return float(gps_speed)
            if obd_speed is not None:
                return float(obd_speed)
            return 0.0

        def add_point_placemark(name: str, description: str, sample: TrackSample):
            placemark = ET.SubElement(document, 'Placemark')
            ET.SubElement(placemark, 'name').text = name
            ET.SubElement(placemark, 'description').text = description
            style = ET.SubElement(document, 'Style', id='speedStyle')
            icon_style = ET.SubElement(style, 'IconStyle')
            ET.SubElement(icon_style, 'color').text = 'ff00a5ff'
            ET.SubElement(icon_style, 'scale').text = '1.1'
            icon = ET.SubElement(icon_style, 'Icon')
            ET.SubElement(icon, 'href').text = 'http://maps.google.com/mapfiles/kml/paddle/wht-blank.png'
            ET.SubElement(placemark, 'styleUrl').text = '#speedStyle'
            point = ET.SubElement(placemark, 'Point')
            ET.SubElement(point, 'coordinates').text = f"{sample.gps.lon:.6f},{sample.gps.lat:.6f},0"

        for segment_index in sorted(segment_points.keys()):
            segment = segment_points[segment_index]
            if not segment:
                continue

            max_speed_sample = max(segment, key=hybrid_speed)
            max_speed = hybrid_speed(max_speed_sample)
            if max_speed_sample.gps is None:
                continue

            add_point_placemark(
                f'Speed {max_speed:.0f} kmph',
                f'Max speed in this segment: {max_speed:.1f} km/h',
                max_speed_sample
            )

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
