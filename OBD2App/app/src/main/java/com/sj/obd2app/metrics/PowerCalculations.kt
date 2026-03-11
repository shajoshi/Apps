package com.sj.obd2app.metrics

import kotlin.math.PI

/**
 * Pure internal functions for vehicle power calculations.
 * Extracted from [MetricsCalculator.calculate] to enable JVM unit testing
 * and to serve as the bodies for the Phase-1 PowerCalculator component.
 *
 * Three independent power estimation methods are provided. Each can return null
 * when its required inputs are unavailable.
 */

/**
 * Acceleration-based power estimate (kW).
 *
 * Physics: P = F × v = m × a × v
 *
 * @param massKg     Vehicle kerb mass in kg (from vehicle profile); must be > 0
 * @param fwdMean    Mean forward acceleration (m/s²) from accelerometer
 * @param speedMs    Current speed in m/s
 * @return Power in kW, or null when mass is not configured or speed is zero
 */
internal fun powerAccelKw(massKg: Float, fwdMean: Float?, speedMs: Float): Float? =
    if (massKg > 0f && fwdMean != null && speedMs > 0f)
        (massKg * fwdMean * speedMs) / 1000f
    else null

/**
 * Thermodynamic power estimate (kW).
 *
 * Physics: P = (fuel_rate_L/s × energy_density_J/L × brake_thermal_efficiency) / 1000
 *
 * Assumes a 35% brake thermal efficiency for internal combustion engines.
 *
 * @param fuelRateLh         Effective fuel rate in L/h
 * @param energyDensityMJpL  Fuel lower heating value in MJ/L (from FuelType)
 * @return Power in kW, or null when fuel rate is unavailable or zero
 */
internal fun powerThermoKw(fuelRateLh: Float?, energyDensityMJpL: Double): Float? {
    if (fuelRateLh == null || fuelRateLh <= 0f || energyDensityMJpL <= 0.0) return null
    return ((fuelRateLh / 3600.0) * energyDensityMJpL * 1e6 * 0.35 / 1000.0).toFloat()
}

/**
 * OBD2 torque-based power estimate (kW).
 *
 * Physics: P = τ × ω = τ × (2π × rpm / 60)
 *
 * @param actualTorquePct   Actual engine torque as percentage of reference torque
 * @param refTorqueNm       Engine reference torque in Nm (PID 0163)
 * @param rpm               Engine speed in RPM
 * @return Power in kW, or null when any required PID is unavailable
 */
internal fun powerOBDKw(actualTorquePct: Float?, refTorqueNm: Int?, rpm: Float?): Float? {
    if (actualTorquePct == null || refTorqueNm == null || rpm == null || rpm <= 0f) return null
    return ((actualTorquePct / 100f) * refTorqueNm * rpm * 2.0 * PI / 60000.0).toFloat()
}
