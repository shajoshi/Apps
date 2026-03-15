package com.sj.obd2app.metrics.calculator

import com.sj.obd2app.settings.FuelType
import kotlin.math.PI

/**
 * Vehicle power calculations extracted from MetricsCalculator.calculate().
 *
 * Three independent estimation methods: accelerometer-based, thermodynamic, and OBD torque.
 * All methods are stateless and pure.
 */
class PowerCalculator {

    /**
     * Estimates power from accelerometer data using F = ma and P = Fv.
     *
     * Assumes vehicle mass and forward acceleration to calculate force, then power as force × speed.
     * Returns power in kilowatts (kW).
     */
    fun fromAccelerometer(vehicleMassKg: Float, fwdMeanAccel: Float?, speedMs: Float): Float? {
        return fwdMeanAccel?.let { accel ->
            if (vehicleMassKg <= 0f || speedMs <= 0f) return null
            val forceN = vehicleMassKg * accel  // N = kg × m/s²
            val powerW = forceN * speedMs  // W = N × m/s
            powerW / 1000f  // Convert to kW
        }
    }

    /**
     * Estimates power thermodynamically from fuel burn rate and energy density.
     *
     * Power = fuel rate (L/h) × energy density (MJ/L) × conversion factor to kW.
     * Accounts for engine efficiency losses (assumes ~35% thermal efficiency).
     * Uses Double precision for better accuracy with small fuel rates.
     */
    fun thermodynamic(fuelRateLh: Float?, energyDensityMJpL: Double): Float? {
        return fuelRateLh?.let { rate ->
            if (rate <= 0f || energyDensityMJpL <= 0.0) return null
            val energyRateMJpH = rate * energyDensityMJpL
            val energyRateKw = energyRateMJpH * 1000.0 / 3600.0  // MJ/h to kW
            val thermalEfficiency = 0.35  // Typical brake thermal efficiency for gasoline engines
            (energyRateKw * thermalEfficiency).toFloat()
        }
    }

    /**
     * Estimates power from OBD torque and RPM data.
     *
     * Power = torque (Nm) × angular velocity (rad/s).
     * Angular velocity = 2π × RPM / 60.
     * Returns power in kilowatts (kW).
     */
    fun fromObd(actualTorquePct: Float?, refTorqueNm: Int?, rpm: Float?): Float? {
        return if (actualTorquePct != null && refTorqueNm != null && rpm != null && rpm > 0f) {
            val torqueNm = (actualTorquePct / 100f) * refTorqueNm
            val angularVelocityRads = 2 * PI.toFloat() * rpm / 60f
            val powerW = torqueNm * angularVelocityRads  // W = Nm × rad/s
            powerW / 1000f  // Convert to kW
        } else null
    }
}
