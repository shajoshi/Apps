package com.sj.obd2app.metrics

/**
 * Computed accelerometer metrics for one OBD2 poll window.
 * Derived from raw sensor samples via [AccelEngine].
 * All values in m/s² unless noted.
 */
data class AccelMetrics(
    val vertRms: Float,
    val vertMax: Float,
    val vertMean: Float,
    val vertStdDev: Float,
    val vertPeakRatio: Float,
    val fwdRms: Float,
    val fwdMax: Float,
    val fwdMaxBrake: Float,
    val fwdMaxAccel: Float,
    val fwdMean: Float,
    val latRms: Float,
    val latMax: Float,
    val latMean: Float,
    val leanAngleDeg: Float,
    val rawAccelSampleCount: Int
)
