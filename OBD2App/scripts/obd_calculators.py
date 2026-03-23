"""
OBD2 calculation classes ported from Kotlin.
Replicates FuelCalculator.kt, TripCalculator.kt, and TripState.kt logic.
"""

import math
from typing import Optional, Tuple
from collections import deque
from obd_types import FuelType


class FuelCalculator:
    """
    Fuel-related calculations.
    Ported from FuelCalculator.kt
    """
    
    @staticmethod
    def effective_fuel_rate(
        fuel_rate_pid: Optional[float],
        maf_gs: Optional[float],
        maf_ml_per_gram: float,
        map_kpa: Optional[float],
        iat_c: Optional[float],
        rpm: Optional[float],
        displacement_cc: int,
        ve_pct: float,
        fuel_type: FuelType,
        baro_kpa: Optional[float],
        engine_load_pct: Optional[float]
    ) -> Optional[float]:
        """
        3-tier fuel rate calculation with fallback.
        
        Priority:
        1. Direct PID 015E (fuel rate L/h)
        2. MAF-based calculation with diesel AFR correction
        3. Speed-Density estimation
        
        Returns: Fuel rate in L/h or None
        """
        # Tier 1: Direct PID
        if fuel_rate_pid is not None and fuel_rate_pid > 0:
            return fuel_rate_pid
        
        # Tier 2: MAF-based
        if maf_gs is not None and maf_gs > 0:
            # Apply diesel AFR correction if applicable
            if fuel_type == FuelType.DIESEL and map_kpa is not None and baro_kpa is not None:
                fuel_rate = FuelCalculator._calculate_diesel_afr_correction(
                    maf_gs, engine_load_pct or 0.0, map_kpa, baro_kpa, fuel_type
                )
                return fuel_rate
            else:
                return (maf_gs * 3600) / (fuel_type.maf_ml_per_gram * 1000)
        
        # Tier 3: Speed-Density
        if (map_kpa is not None and iat_c is not None and rpm is not None 
            and displacement_cc > 0):
            sd_maf = FuelCalculator._speed_density_maf_gs(
                map_kpa, iat_c, rpm, displacement_cc, ve_pct
            )
            
            # Apply diesel AFR correction if applicable
            if fuel_type == FuelType.DIESEL and baro_kpa is not None:
                fuel_rate = FuelCalculator._calculate_diesel_afr_correction(
                    sd_maf, engine_load_pct or 0.0, map_kpa, baro_kpa, fuel_type
                )
                return fuel_rate
            
            return sd_maf * maf_ml_per_gram * 3.6
        
        return None
    
    @staticmethod
    def _calculate_diesel_afr_correction(
        maf_gs: float,
        engine_load_pct: float,
        map_kpa: float,
        baro_kpa: float,
        fuel_type
    ) -> float:
        """
        Simple diesel AFR correction based on load and boost.
        Returns fuel rate directly instead of correction factor.
        
        Args:
            maf_gs: Mass air flow in grams/second
            engine_load_pct: Engine load percentage (0-100)
            map_kpa: Manifold absolute pressure in kPa
            baro_kpa: Barometric pressure in kPa
            fuel_type: Fuel type (correction only applied for DIESEL)
            
        Returns:
            Fuel rate in L/h, or 1.0 for non-diesel fuels (no correction)
        """
        # Convert load percentage to decimal (0-1)
        load_decimal = engine_load_pct / 100.0
        
        # Determine AFR based on load - Torque Pro style for MJD 1.3L diesel
        if load_decimal < 0.35:
            afr = 100     # Extremely lean (typical diesel idle - Torque Pro conservative)
        elif load_decimal < 0.7:
            afr = 75      # Lean (highway cruise - realistic diesel)
        else:
            afr = 50      # Normal diesel load (heavy acceleration)
        
        # Calculate fuel rate directly (g/s)
        fuel_gps = maf_gs / afr
        
        # Convert to L/h
        fuel_lh = fuel_gps * 3.6 / fuel_type.maf_ml_per_gram
        
        # Apply simple correction factor for MJD 1.3L diesel calibration
        # This factor accounts for real-world vs theoretical differences
        correction_factor = 0.25  # Apply 40% reduction to match real-world efficiency
        fuel_lh = fuel_lh * correction_factor
        
        return fuel_lh
    
    @staticmethod
    def _calculate_boost_pressure(map_kpa: float, baro_kpa: float) -> float:
        """Calculate boost pressure (MAP - baro)."""
        return max(0.0, map_kpa - baro_kpa)
    
    @staticmethod
    def _speed_density_maf_gs(
        map_kpa: float,
        iat_c: float,
        rpm: float,
        displacement_cc: int,
        ve_pct: float
    ) -> float:
        """
        Speed-Density MAF estimation.
        Ported from FuelCalculator.speedDensityMafGs()
        
        Formula: MAF = (MAP × Displacement × RPM × VE) / (R × IAT × 120)
        """
        # Convert IAT to Kelvin
        iat_k = iat_c + 273.15
        
        # Gas constant for air (J/(kg·K))
        R = 287.05
        
        # Calculate volumetric flow rate (L/min)
        # displacement in cc, convert to L
        displacement_l = displacement_cc / 1000.0
        
        # Air mass flow (g/s)
        # Formula from Kotlin: (MAP × displacement × RPM × VE) / (R × IAT × 120)
        # MAP in kPa, displacement in L, RPM, VE as decimal
        ve_decimal = ve_pct / 100.0
        
        maf_gs = (map_kpa * 1000 * displacement_l * rpm * ve_decimal) / (R * iat_k * 120)
        
        return max(0.0, maf_gs)
    
    @staticmethod
    def instantaneous(
        fuel_rate_lh: Optional[float],
        speed_kmh: float
    ) -> Tuple[float, float]:
        """
        Calculate instantaneous fuel efficiency.
        
        Returns: (L/100km, km/L)
        Ported from FuelCalculator.instantaneous()
        """
        if fuel_rate_lh is None or fuel_rate_lh <= 0 or speed_kmh <= 0:
            return (0.0, 0.0)
        
        # L/100km = (fuel_rate_L/h / speed_km/h) × 100
        l_per_100km = (fuel_rate_lh / speed_kmh) * 100.0
        
        # km/L = 1 / (L/100km) × 100
        kpl = 100.0 / l_per_100km if l_per_100km > 0 else 0.0
        
        return (l_per_100km, kpl)
    
    @staticmethod
    def trip_averages(
        trip_fuel_l: float,
        trip_distance_km: float
    ) -> Tuple[float, float]:
        """
        Calculate trip average fuel efficiency.
        
        Returns: (L/100km, km/L)
        Ported from FuelCalculator.tripAverages()
        """
        # Require minimum 0.1 km to avoid division by zero
        if trip_distance_km < 0.1:
            return (0.0, 0.0)
        
        if trip_fuel_l <= 0:
            return (0.0, 0.0)
        
        # L/100km = (fuel_L / distance_km) × 100
        l_per_100km = (trip_fuel_l / trip_distance_km) * 100.0
        
        # km/L = distance_km / fuel_L
        kpl = trip_distance_km / trip_fuel_l
        
        return (l_per_100km, kpl)
    
    @staticmethod
    def fuel_flow_cc_min(fuel_rate_lh: Optional[float]) -> float:
        """
        Convert fuel rate from L/h to cc/min.
        Ported from FuelCalculator.fuelFlowCcMin()
        """
        if fuel_rate_lh is None or fuel_rate_lh <= 0:
            return 0.0
        
        # 1 L/h = 1000 cc/h = 1000/60 cc/min
        return fuel_rate_lh * 1000.0 / 60.0
    
    @staticmethod
    def cost(trip_fuel_l: float, fuel_price_per_litre: float) -> float:
        """
        Calculate fuel cost.
        Ported from FuelCalculator.cost()
        """
        return trip_fuel_l * fuel_price_per_litre
    
    @staticmethod
    def co2(trip_avg_lper100km: float, co2_factor: float) -> float:
        """
        Calculate average CO2 emissions.
        Ported from FuelCalculator.co2()
        
        Returns: g CO2 per km
        """
        if trip_avg_lper100km <= 0:
            return 0.0
        
        # co2_factor is g CO2 per L/100km
        # Convert to g/km: (L/100km × co2_factor) / 100
        return (trip_avg_lper100km * co2_factor) / 100.0


class TripCalculator:
    """
    Trip-related calculations.
    Ported from TripCalculator.kt
    """
    
    @staticmethod
    def hybrid_speed(
        gps_speed_kmh: Optional[float],
        obd_speed_kmh: Optional[float]
    ) -> float:
        """
        Hybrid speed calculation: OBD ≤20 km/h, GPS >20 km/h.
        Ported from TripCalculator.hybridSpeed()
        """
        # Use OBD speed up to 20 km/h (more accurate at low speeds)
        if obd_speed_kmh is not None and obd_speed_kmh <= 20.0:
            return obd_speed_kmh
        
        # Use GPS speed above 20 km/h
        if gps_speed_kmh is not None:
            return gps_speed_kmh
        
        # Fallback to OBD if GPS unavailable
        if obd_speed_kmh is not None:
            return obd_speed_kmh
        
        return 0.0
    
    @staticmethod
    def average_speed(trip_distance_km: float, trip_time_sec: int) -> float:
        """
        Calculate average speed.
        Ported from TripCalculator.averageSpeed()
        
        Requires minimum 30 seconds and 50 meters to return non-zero.
        """
        if trip_time_sec < 30 or trip_distance_km < 0.05:
            return 0.0
        
        # km/h = (distance_km / time_h)
        time_h = trip_time_sec / 3600.0
        return trip_distance_km / time_h
    
    @staticmethod
    def speed_diff(
        gps_speed_kmh: Optional[float],
        obd_speed_kmh: Optional[float]
    ) -> Optional[float]:
        """
        Calculate speed difference (GPS - OBD).
        Ported from TripCalculator.speedDiff()
        """
        if gps_speed_kmh is None or obd_speed_kmh is None:
            return None
        
        return gps_speed_kmh - obd_speed_kmh


class TripState:
    """
    Mutable accumulator for trip-level statistics.
    Ported from TripState.kt
    """
    
    def __init__(self, trip_start_ms: int):
        self.trip_start_ms = trip_start_ms
        self.last_update_ms = trip_start_ms
        
        # Public accumulators
        self.trip_distance_km = 0.0
        self.trip_fuel_used_l = 0.0
        self.moving_time_sec = 0
        self.stopped_time_sec = 0
        self.max_speed_kmh = 0.0
        
        # Drive mode time accumulators (seconds)
        self.idle_time_sec = 0
        self.city_time_sec = 0
        self.highway_time_sec = 0
        
        # High-precision accumulators
        self._precise_distance_m = 0.0
        self._precise_fuel_used_ml = 0.0
        self._precise_moving_time_sec = 0.0
        self._precise_stopped_time_sec = 0.0
        self._precise_idle_time_sec = 0.0
        self._precise_city_time_sec = 0.0
        self._precise_highway_time_sec = 0.0
        
        # Rolling 60-second window for drive mode classification
        self.speed_window = deque()  # (timestamp_ms, speed_kmh)
    
    def reset(self, trip_start_ms: int):
        """Reset all accumulators."""
        self.trip_start_ms = trip_start_ms
        self.last_update_ms = trip_start_ms
        self.trip_distance_km = 0.0
        self.trip_fuel_used_l = 0.0
        self.moving_time_sec = 0
        self.stopped_time_sec = 0
        self.max_speed_kmh = 0.0
        self.idle_time_sec = 0
        self.city_time_sec = 0
        self.highway_time_sec = 0
        self._precise_distance_m = 0.0
        self._precise_fuel_used_ml = 0.0
        self._precise_moving_time_sec = 0.0
        self._precise_stopped_time_sec = 0.0
        self._precise_idle_time_sec = 0.0
        self._precise_city_time_sec = 0.0
        self._precise_highway_time_sec = 0.0
        self.speed_window.clear()
    
    def update(self, timestamp_ms: int, speed_kmh: float, fuel_rate_lh: float):
        """
        Advance accumulators by one update tick.
        Ported from TripState.update()
        """
        dt_ms = max(0, timestamp_ms - self.last_update_ms)
        self.last_update_ms = timestamp_ms
        
        dt_sec = dt_ms / 1000.0
        dt_hr = dt_ms / 3_600_000.0
        
        # High-precision accumulation
        # Distance: convert km/h to m/s, accumulate in meters
        speed_ms = speed_kmh / 3.6
        self._precise_distance_m += speed_ms * dt_sec
        
        # Fuel: accumulate in milliliters
        # fuel_rate_lh → ml/s = (L/h × 1000) / 3600
        fuel_rate_ml_per_sec = fuel_rate_lh * 1000.0 / 3600.0
        self._precise_fuel_used_ml += fuel_rate_ml_per_sec * dt_sec
        
        # Update public fields (convert from precise accumulators)
        self.trip_distance_km = self._precise_distance_m / 1000.0
        self.trip_fuel_used_l = self._precise_fuel_used_ml / 1000.0
        
        # Time buckets (moving = speed > 2 km/h) - use high precision
        if speed_kmh > 2.0:
            self._precise_moving_time_sec += dt_sec
        else:
            self._precise_stopped_time_sec += dt_sec
        
        # Update public integer fields
        self.moving_time_sec = int(self._precise_moving_time_sec)
        self.stopped_time_sec = int(self._precise_stopped_time_sec)
        
        # Trip-level drive mode time accumulation - use high precision
        if speed_kmh <= 2.0:
            self._precise_idle_time_sec += dt_sec
        elif speed_kmh <= 60.0:
            self._precise_city_time_sec += dt_sec
        else:
            self._precise_highway_time_sec += dt_sec
        
        # Update public integer fields
        self.idle_time_sec = int(self._precise_idle_time_sec)
        self.city_time_sec = int(self._precise_city_time_sec)
        self.highway_time_sec = int(self._precise_highway_time_sec)
        
        # Peak speed
        if speed_kmh > self.max_speed_kmh:
            self.max_speed_kmh = speed_kmh
        
        # Rolling 60-second window for drive mode classification
        self.speed_window.append((timestamp_ms, speed_kmh))
        cutoff = timestamp_ms - 60_000
        while self.speed_window and self.speed_window[0][0] < cutoff:
            self.speed_window.popleft()
    
    def trip_drive_mode_percents(self) -> Tuple[float, float, float]:
        """
        Returns (pct_city, pct_highway, pct_idle) from entire trip duration.
        Ported from TripState.tripDriveModePercents()
        """
        total_sec = self.idle_time_sec + self.city_time_sec + self.highway_time_sec
        if total_sec == 0:
            return (0.0, 0.0, 0.0)
        
        total = float(total_sec)
        pct_city = (self.city_time_sec / total) * 100.0
        pct_highway = (self.highway_time_sec / total) * 100.0
        pct_idle = (self.idle_time_sec / total) * 100.0
        
        return (pct_city, pct_highway, pct_idle)


class PowerCalculator:
    """
    Vehicle power calculations.
    Ported from PowerCalculator.kt
    
    Three independent estimation methods: accelerometer-based, thermodynamic, and OBD torque.
    """
    
    @staticmethod
    def from_accelerometer(
        vehicle_mass_kg: float,
        fwd_mean_accel: Optional[float],
        speed_ms: float
    ) -> Optional[float]:
        """
        Estimates power from accelerometer data using F = ma and P = Fv.
        
        Returns power in kilowatts (kW).
        Ported from PowerCalculator.fromAccelerometer()
        """
        if vehicle_mass_kg <= 0 or speed_ms <= 0 or fwd_mean_accel is None:
            return None
        
        # Force = mass × acceleration (N = kg × m/s²)
        force_n = vehicle_mass_kg * fwd_mean_accel
        
        # Power = force × velocity (W = N × m/s)
        power_w = force_n * speed_ms
        
        # Convert to kW
        return power_w / 1000.0
    
    @staticmethod
    def thermodynamic(
        fuel_rate_lh: Optional[float],
        energy_density_mjpl: float
    ) -> Optional[float]:
        """
        Estimates power thermodynamically from fuel burn rate and energy density.
        
        Power = fuel rate (L/h) × energy density (MJ/L) × conversion factor to kW.
        Accounts for engine efficiency losses (assumes ~35% thermal efficiency).
        Returns power in kilowatts (kW).
        Ported from PowerCalculator.thermodynamic()
        """
        if fuel_rate_lh is None or fuel_rate_lh <= 0 or energy_density_mjpl <= 0:
            return None
        
        # Energy rate = fuel rate × energy density (MJ/h)
        energy_rate_mj_ph = fuel_rate_lh * energy_density_mjpl
        
        # Convert MJ/h to kW: 1 MJ/h = 1000 kJ/h = 1000/3600 kJ/s = 1000/3600 kW
        energy_rate_kw = energy_rate_mj_ph * 1000.0 / 3600.0
        
        # Apply thermal efficiency (typical brake thermal efficiency for gasoline engines)
        thermal_efficiency = 0.35
        
        return energy_rate_kw * thermal_efficiency
    
    @staticmethod
    def from_obd(
        actual_torque_pct: Optional[float],
        ref_torque_nm: Optional[int],
        rpm: Optional[float]
    ) -> Optional[float]:
        """
        Estimates power from OBD torque and RPM data.
        
        Power = torque (Nm) × angular velocity (rad/s).
        Angular velocity = 2π × RPM / 60.
        Returns power in kilowatts (kW).
        Ported from PowerCalculator.fromObd()
        """
        if (actual_torque_pct is None or ref_torque_nm is None or 
            rpm is None or rpm <= 0 or ref_torque_nm <= 0):
            return None
        
        # Calculate actual torque (Nm)
        torque_nm = (actual_torque_pct / 100.0) * ref_torque_nm
        
        # Angular velocity (rad/s) = 2π × RPM / 60
        angular_velocity_rads = 2 * math.pi * rpm / 60.0
        
        # Power = torque × angular velocity (W = Nm × rad/s)
        power_w = torque_nm * angular_velocity_rads
        
        # Convert to kW
        return power_w / 1000.0
