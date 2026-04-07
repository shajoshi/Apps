package com.sj.obd2app.metrics

import com.sj.obd2app.obd.Obd2DataItem

/**
 * Immutable snapshot of all primary and derived OBD2/GPS metrics for one calculation cycle.
 * All nullable fields are null when the ECU does not support that PID.
 */
data class VehicleMetrics(

    val timestampMs: Long = System.currentTimeMillis(),

    // ── Primary OBD2 ─────────────────────────────────────────────────────────

    val rpm: Float? = null,
    val vehicleSpeedKmh: Float? = null,
    val engineLoadPct: Float? = null,
    val throttlePct: Float? = null,
    val coolantTempC: Float? = null,
    val intakeTempC: Float? = null,
    val oilTempC: Float? = null,
    val ambientTempC: Float? = null,
    val fuelLevelPct: Float? = null,
    val fuelPressureKpa: Float? = null,
    val fuelRateLh: Float? = null,
    val mafGs: Float? = null,
    val intakeMapKpa: Float? = null,
    val baroPressureKpa: Float? = null,
    val timingAdvanceDeg: Float? = null,
    val stftPct: Float? = null,
    val ltftPct: Float? = null,
    val stftBank2Pct: Float? = null,
    val ltftBank2Pct: Float? = null,
    val o2Voltage: Float? = null,
    val controlModuleVoltage: Float? = null,
    val runTimeSec: Int? = null,
    val distanceMilOnKm: Int? = null,
    val distanceSinceCleared: Int? = null,
    val absoluteLoadPct: Float? = null,
    val relativeThrottlePct: Float? = null,
    val accelPedalDPct: Float? = null,
    val accelPedalEPct: Float? = null,
    val commandedThrottlePct: Float? = null,
    val timeMilOnMin: Int? = null,
    val timeSinceClearedMin: Int? = null,
    val ethanolPct: Float? = null,
    val hybridBatteryPct: Float? = null,
    val fuelInjectionTimingDeg: Float? = null,
    val driverDemandTorquePct: Float? = null,
    val actualTorquePct: Float? = null,
    val engineReferenceTorqueNm: Int? = null,
    val catalystTempB1S1C: Float? = null,
    val catalystTempB2S1C: Float? = null,
    val fuelSystemStatus: String? = null,
    val monitorStatus: String? = null,
    val fuelTypeStr: String? = null,

    // Missing Standard PIDs from Obd2CommandRegistry
    val freezeDtc: String? = null,
    val obdStandard: String? = null,
    val obdStandards: String? = null,
    val fuelRailPressureVacuum: Float? = null,
    val fuelRailPressureDirect: Int? = null,
    val commandedEgr: Float? = null,
    val egrError: Float? = null,
    val commandedEvapPurge: Float? = null,
    val warmupsSinceCleared: Int? = null,
    val evapSystemVapourPressure: String? = null,
    val absoluteBarometricPressure: Float? = null,
    val o2Sensor1: String? = null,
    val o2Sensor2: String? = null,
    val o2Sensor3: String? = null,
    val o2Sensor4: String? = null,
    val o2Sensor5: String? = null,
    val o2Sensor6: String? = null,
    val o2Sensor7: String? = null,
    val monitorStatusThisDriveCycle: String? = null,
    val throttlePositionB: Float? = null,
    val throttlePositionC: Float? = null,
    val maximumValues: String? = null,
    val maximumMaf: Float? = null,
    val absoluteEvapSystemVapourPressure: Float? = null,
    val evapSystemVapourPressure2: String? = null,
    val shortTermO2TrimBank13: String? = null,
    val longTermO2TrimBank13: String? = null,
    val shortTermO2TrimBank24: String? = null,
    val longTermO2TrimBank24: String? = null,
    val fuelRailAbsolutePressure: Float? = null,
    val relativeAcceleratorPedalPosition: Float? = null,
    val emissionRequirements: String? = null,

    // Extended PIDs (0x64-0x7F) - Turbo, DPF, EGT, Multi-sensor
    val enginePercentTorqueData: String? = null,
    val mafSensorMulti: String? = null,
    val coolantTempMulti: String? = null,
    val intakeAirTempMulti: String? = null,
    val egrTemperature: String? = null,
    val fuelPressureControl: String? = null,
    val injectionPressureControl: String? = null,
    val turboCompressorInletPressure: String? = null,
    val boostPressureControl: String? = null,
    val exhaustPressure: String? = null,
    val turbochargerRpm: String? = null,
    val turbochargerTempA: String? = null,
    val turbochargerTempB: String? = null,
    val egtBank1: String? = null,
    val egtBank2: String? = null,
    val dpfDifferentialPressure: String? = null,
    val dpfTemperature: String? = null,
    val pmFilterTemp: String? = null,
    val acceleratorPedalPositionF: Float? = null,

    /** Raw OBD data items including unknown and manufacturer-specific PIDs */
    val rawObdData: List<Obd2DataItem> = emptyList(),

    // ── Primary GPS ───────────────────────────────────────────────────────────

    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsSpeedKmh: Float? = null,
    val altitudeMslM: Double? = null,
    val altitudeEllipsoidM: Double? = null,
    val geoidUndulationM: Double? = null,
    val gpsAccuracyM: Float? = null,
    val gpsBearingDeg: Float? = null,
    val gpsVerticalAccuracyM: Float? = null,
    val gpsSatelliteCount: Int? = null,

    // ── Derived — Fuel Efficiency ─────────────────────────────────────────────

    /** Effective fuel rate (L/h): PID 015E if present, else MAF-based fallback */
    val fuelRateEffectiveLh: Float? = null,

    /** Effective fuel rate (ml/min): primary internal unit for better precision */
    val fuelRateEffectiveMlMin: Float? = null,

    /** Instantaneous consumption (L/100km); null when speed = 0 */
    val instantLper100km: Float? = null,

    /** Instantaneous efficiency (km/L); null when speed = 0 */
    val instantKpl: Float? = null,

    /** Instantaneous consumption (ml/km); null when speed = 0 */
    val instantMlPerKm: Float? = null,

    /** Instantaneous efficiency (km/ml); null when speed = 0 */
    val instantKmPerMl: Float? = null,

    /** Trip cumulative fuel used (L) */
    val tripFuelUsedL: Float = 0f,

    /** Trip cumulative fuel used (ml); primary internal unit */
    val tripFuelUsedMl: Double = 0.0,

    /** Trip average consumption (L/100km) */
    val tripAvgLper100km: Float? = null,

    /** Trip average efficiency (km/L) */
    val tripAvgKpl: Float? = null,

    /** Trip average consumption (ml/km) */
    val tripAvgMlPerKm: Float? = null,

    /** Trip average efficiency (km/ml) */
    val tripAvgKmPerMl: Float? = null,

    /** Fuel flow rate (cc/min) - alias for ml/min */
    val fuelFlowCcMin: Float? = null,

    /** Estimated remaining range (km) based on fuel level + trip average */
    val rangeRemainingKm: Float? = null,

    /** Estimated fuel cost for this trip */
    val fuelCostEstimate: Float? = null,

    /** Average CO₂ emissions for this trip (g/km) */
    val avgCo2gPerKm: Float? = null,

    // ── Derived — Trip Computer ───────────────────────────────────────────────

    val tripDistanceKm: Float = 0f,
    val tripTimeSec: Long = 0L,
    val movingTimeSec: Long = 0L,
    val stoppedTimeSec: Long = 0L,
    val tripAvgSpeedKmh: Float = 0f,
    val tripMaxSpeedKmh: Float = 0f,

    /** GPS speed minus OBD speed (sensor cross-check, km/h) */
    val spdDiffKmh: Float? = null,

    // ── Derived — Drive Mode (rolling 60 s) ──────────────────────────────────

    val pctCity: Float = 0f,
    val pctHighway: Float = 0f,
    val pctIdle: Float = 0f,

    // ── Derived — Power ───────────────────────────────────────────────────────

    /** Acceleration-based power: mass × fwdMean × speed. Requires vehicleMassKg > 0 + accel. */
    val powerAccelKw: Float? = null,

    /** Thermodynamic power: fuelRate × energyDensity × 0.35 brake thermal efficiency. */
    val powerThermoKw: Float? = null,

    /** OBD torque-based power: (actualTorquePct/100 × refTorqueNm × rpm × 2π) / 60000. */
    val powerOBDKw: Float? = null,

    // ── Accelerometer ─────────────────────────────────────────────────────────

    val accelVertRms: Float? = null,
    val accelVertMax: Float? = null,
    val accelVertMean: Float? = null,
    val accelVertStdDev: Float? = null,
    val accelVertPeakRatio: Float? = null,
    val accelFwdRms: Float? = null,
    val accelFwdMax: Float? = null,
    val accelFwdMaxBrake: Float? = null,
    val accelFwdMaxAccel: Float? = null,
    val accelFwdMean: Float? = null,
    val accelLatRms: Float? = null,
    val accelLatMax: Float? = null,
    val accelLatMean: Float? = null,
    val accelLeanAngleDeg: Float? = null,
    val accelRawSampleCount: Int? = null,

    // ── Outlier Detection ─────────────────────────────────────────────────────

    /** True if any outliers were detected in this sample */
    val outlierDetected: Boolean = false,

    /** Comma-separated list of outlier parameter names */
    val outlierNames: String? = null
)
