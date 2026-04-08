"""
Data types and enums for OBD2 metrics calculation.
Replicates types from VehicleProfile.kt and FuelType enum.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Optional, Dict, List


class FuelType(Enum):
    """
    Fuel type with associated constants for calculations.
    
    Constants from VehicleProfile.kt:
    - mafMlPerGram: ml of fuel per gram of air (1000 / (stoichAFR × fuelDensityGperL))
    - co2Factor: g CO₂ per L/100km
    - energyDensityMJpL: Lower heating value (MJ/L) for thermodynamic power
    """
    PETROL = ("Petrol", 0.09195, 23.1, 34.2)
    E20 = ("E20 Petrol", 0.09166, 22.3, 27.4)
    DIESEL = ("Diesel", 0.08210, 26.4, 38.6)
    CNG = ("CNG", 0.13740, 16.0, 23.0)
    
    def __init__(self, display_name: str, maf_ml_per_gram: float, 
                 co2_factor: float, energy_density_mjpl: float):
        self.display_name = display_name
        self.maf_ml_per_gram = maf_ml_per_gram
        self.co2_factor = co2_factor
        self.energy_density_mjpl = energy_density_mjpl


@dataclass
class VehicleProfile:
    """
    Vehicle-specific configuration profile.
    Replicates VehicleProfile.kt data class.
    """
    id: str
    name: str
    fuel_type: FuelType
    tank_capacity_l: float = 40.0
    fuel_price_per_litre: float = 0.0
    engine_power_bhp: float = 0.0
    vehicle_mass_kg: float = 0.0
    engine_displacement_cc: int = 0
    volumetric_efficiency_pct: float = 85.0
    available_pids: Dict[str, str] = None
    custom_pids: List[Dict] = None
    
    def __post_init__(self):
        if self.available_pids is None:
            self.available_pids = {}
        if self.custom_pids is None:
            self.custom_pids = []


@dataclass
class GpsData:
    """GPS data from a single sample."""
    lat: Optional[float] = None
    lon: Optional[float] = None
    speed_kmh: Optional[float] = None
    alt_msl: Optional[float] = None
    alt_ellipsoid: Optional[float] = None
    geoid_undulation: Optional[float] = None
    accuracy_m: Optional[float] = None
    vert_accuracy_m: Optional[float] = None
    bearing_deg: Optional[float] = None
    satellite_count: Optional[int] = None


@dataclass
class ObdData:
    """OBD2 data from a single sample."""
    rpm: Optional[float] = None
    speed_kmh: Optional[float] = None
    engine_load_pct: Optional[float] = None
    throttle_pct: Optional[float] = None
    coolant_temp_c: Optional[float] = None
    intake_temp_c: Optional[float] = None
    oil_temp_c: Optional[float] = None
    ambient_temp_c: Optional[float] = None
    fuel_level_pct: Optional[float] = None
    fuel_pressure_kpa: Optional[float] = None
    fuel_rate_lh: Optional[float] = None
    maf_gs: Optional[float] = None
    intake_map_kpa: Optional[float] = None
    baro_pressure_kpa: Optional[float] = None
    timing_advance_deg: Optional[float] = None
    stft_pct: Optional[float] = None
    ltft_pct: Optional[float] = None
    o2_voltage: Optional[float] = None
    control_module_voltage: Optional[float] = None
    run_time_sec: Optional[int] = None
    distance_mil_on_km: Optional[int] = None
    distance_since_cleared: Optional[int] = None
    absolute_load_pct: Optional[float] = None
    relative_throttle_pct: Optional[float] = None
    time_mil_on_min: Optional[int] = None
    fuel_system_status: Optional[str] = None
    monitor_status: Optional[str] = None


@dataclass
class TrackSample:
    """A single sample from a track log."""
    timestamp_ms: int
    sample_no: int
    gps: Optional[GpsData] = None
    obd: Optional[ObdData] = None
    fuel: Optional[Dict[str, float]] = None


@dataclass
class TripMetrics:
    """Calculated trip metrics for a track."""
    distance_km: float = 0.0
    fuel_used_l: float = 0.0
    avg_fuel_lper100km: float = 0.0
    avg_fuel_kpl: float = 0.0
    avg_speed_kmh: float = 0.0
    max_speed_kmh: float = 0.0
    moving_time_sec: int = 0
    stopped_time_sec: int = 0
    total_time_sec: int = 0
    pct_idle: float = 0.0
    pct_city: float = 0.0
    pct_highway: float = 0.0
    fuel_cost: float = 0.0
    avg_co2_g_per_km: float = 0.0
    sample_count: int = 0
