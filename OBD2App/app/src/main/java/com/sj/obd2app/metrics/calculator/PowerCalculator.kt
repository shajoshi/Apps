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
     */
    fun fromAccelerometer(vehicleMassKg: Float, fwdMeanAccel: Float?, speedMs: Float): Float? {
        return fwdMeanAccel?.let { accel ->
            val forceN = vehicleMassKg * accel
            forceN * speedMs
        }
    }

    /**
     * Estimates power thermodynamically from fuel burn rate and energy density.
     *
     * Power = fuel rate (L/h) × energy density (MJ/L) × conversion factor to Watts.
     * Accounts for engine efficiency losses (assumes ~25% thermal efficiency).
     */
    fun thermodynamic(fuelRateLh: Float?, energyDensityMJpL: Double): Float? {
        return fuelRateLh?.let { rate ->
            val energyRateMJpH = rate * energyDensityMJpL
            val energyRateWpH = energyRateMJpH * 1_000_000 / 3600  // MJ/h to W/h, then to W
            val thermalEfficiency = 0.25  // Typical gasoline engine efficiency
            (energyRateWpH / thermalEfficiency).toFloat()
        }
    }

    /**
     * Estimates power from OBD torque and RPM data.
     *
     * Power = torque (Nm) × angular velocity (rad/s).
     * Angular velocity = 2π × RPM / 60.
     */
    fun fromObd(actualTorquePct: Float?, refTorqueNm: Int?, rpm: Float?): Float? {
        return if (actualTorquePct != null && refTorqueNm != null && rpm != null && rpm > 0f) {
            val torqueNm = (actualTorquePct / 100f) * refTorqueNm
            val angularVelocityRads = 2 * PI.toFloat() * rpm / 60f
            torqueNm * angularVelocityRads
        } else null
    }
}
