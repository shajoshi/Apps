package com.sj.obd2app.metrics.calculator

/**
 * Trip-level calculations extracted from MetricsCalculator.calculate().
 *
 * Handles average speed computation and speed differences between GPS and OBD.
 * All methods are stateless and pure.
 */
class TripCalculator {

    /**
     * Calculates effective hybrid speed using OBD speed up to 20 km/h and GPS speed above 20 km/h.
     * This provides more accurate speed data at idle where GPS may be unreliable.
     *
     * @param gpsSpeed GPS-reported speed in km/h
     * @param obdSpeed OBD-reported speed in km/h
     * @return Effective speed in km/h, or null if both readings are missing
     */
    fun hybridSpeed(gpsSpeed: Float?, obdSpeed: Float?): Float? {
        return when {
            obdSpeed != null && obdSpeed <= 20f -> obdSpeed
            gpsSpeed != null && gpsSpeed > 20f -> gpsSpeed
            obdSpeed != null -> obdSpeed
            gpsSpeed != null -> gpsSpeed
            else -> null
        }
    }

    /**
     * Calculates average trip speed based on distance traveled and moving time.
     * Returns 0 when vehicle is not moving or when trip duration is too short for reliable calculation.
     * Uses minimum threshold to prevent unrealistic values during very short trips.
     * Uses Double precision for better accuracy with small distances.
     *
     * @param tripDistanceKm Total distance traveled in the trip
     * @param movingTimeSec Total time spent moving (excludes stopped/idle time)
     * @return Average speed in km/h (0 when not moving or unreliable)
     */
    fun averageSpeed(tripDistanceKm: Float, movingTimeSec: Long): Float {
        return if (movingTimeSec > 30 && tripDistanceKm > 0.05f) {  // Minimum 30 seconds and 50 meters
            val movingTimeHours = movingTimeSec / 3600.0
            (tripDistanceKm.toDouble() / movingTimeHours).toFloat()
        } else 0f
    }

    /**
     * Calculates the difference between GPS and OBD speed readings.
     *
     * @param gpsSpeed GPS-reported speed in km/h
     * @param obdSpeed OBD-reported speed in km/h
     * @return Speed difference (GPS - OBD) in km/h, or null if either reading is missing
     */
    fun speedDiff(gpsSpeed: Float?, obdSpeed: Float?): Float? {
        return if (gpsSpeed != null && obdSpeed != null) {
            gpsSpeed - obdSpeed
        } else null
    }
}
