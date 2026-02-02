package com.sj.gpsutil.tracking

data class AccelMetrics(
    val meanX: Float,
    val meanY: Float,
    val meanZ: Float,
    val meanVert: Float,
    val maxMagnitude: Float,
    val rms: Float,
    val peakCount: Int,
    val stdDev: Float,
    val roadQuality: String,
    val featureDetected: String?,
    val rawData: List<FloatArray>
)
