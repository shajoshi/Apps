package com.sj.obd2app.gps

/**
 * Represents a single reading from the GPS module, including both raw ellipsoid
 * altitude and the EGM96 geoid-corrected orthometric altitude (MSL).
 */
data class GpsDataItem(
    val speedKmh: Float,
    val altitudeMsl: Double,
    val altitudeEllipsoid: Double,
    val geoidUndulation: Double,
    val accuracyM: Float,
    val timestampMs: Long
)
