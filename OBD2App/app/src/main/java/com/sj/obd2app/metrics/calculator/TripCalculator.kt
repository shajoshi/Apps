package com.sj.obd2app.metrics.calculator

/**
 * Trip-level calculations extracted from MetricsCalculator.calculate().
 *
 * Handles average speed computation and speed differences between GPS and OBD.
 * All methods are stateless and pure.
 */
class TripCalculator {

    /**
     * Calculates average trip speed based on distance traveled and moving time.
     *
     * @param tripDistanceKm Total distance traveled in the trip
     * @param movingTimeSec Total time spent moving (excludes stopped/idle time)
     * @return Average speed in km/h, or null if moving time is zero
     */
    fun averageSpeed(tripDistanceKm: Float, movingTimeSec: Long): Float? {
        return if (movingTimeSec > 0) {
            val movingTimeHours = movingTimeSec / 3600.0
            (tripDistanceKm / movingTimeHours).toFloat()
        } else null
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
