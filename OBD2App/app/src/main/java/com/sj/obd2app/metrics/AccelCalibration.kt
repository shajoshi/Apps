package com.sj.obd2app.metrics

/**
 * Tuning parameters for [AccelEngine].
 * Defaults are suitable for a phone mounted on a vehicle dashboard or handlebar.
 */
data class AccelCalibration(
    /** Moving-average window applied to detrended samples before metric computation */
    val movingAverageWindow: Int = 5,
    /** Vertical magnitude threshold used to compute [AccelMetrics.vertPeakRatio] */
    val peakThresholdZ: Float = 2.0f
)
