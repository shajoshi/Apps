package com.sj.obd2app.metrics

/**
 * Mutable accumulator for trip-level statistics.
 * Reset by [MetricsCalculator.startTrip].
 */
internal class TripState {

    companion object {
        private const val MAX_WINDOW_SIZE = 2000  // Limit to prevent memory leaks in long trips
    }

    var tripStartMs: Long = System.currentTimeMillis()
    var lastUpdateMs: Long = tripStartMs

    var tripDistanceKm: Float = 0f
    var tripFuelUsedL: Float = 0f
    var movingTimeSec: Long = 0L
    var stoppedTimeSec: Long = 0L
    var maxSpeedKmh: Float = 0f

    // Trip-level drive mode time accumulators (in seconds)
    @Volatile var idleTimeSec: Long = 0L
    @Volatile var cityTimeSec: Long = 0L
    @Volatile var highwayTimeSec: Long = 0L

    // High-precision accumulators for small fuel rates
    private var preciseDistanceM: Double = 0.0
    private var preciseFuelUsedMl: Double = 0.0

    /** Circular buffer of (timestampMs, speedKmh) for the last 60 seconds */
    val speedWindow: ArrayDeque<Pair<Long, Float>> = ArrayDeque()

    fun reset() {
        tripStartMs = System.currentTimeMillis()
        lastUpdateMs = tripStartMs
        tripDistanceKm = 0f
        tripFuelUsedL = 0f
        movingTimeSec = 0L
        stoppedTimeSec = 0L
        maxSpeedKmh = 0f
        idleTimeSec = 0L
        cityTimeSec = 0L
        highwayTimeSec = 0L
        speedWindow.clear()
        preciseDistanceM = 0.0
        preciseFuelUsedMl = 0.0
    }

    /**
     * Advance accumulators by one update tick.
     * Uses high-precision Double arithmetic internally to prevent precision loss
     * with small fuel rates, then converts to Float for public API compatibility.
     * 
     * @param speedKmh   effective speed (hybrid OBD/GPS calculation)
     * @param fuelRateLh effective fuel rate (L/h)
     */
    fun update(speedKmh: Float, fuelRateLh: Float) {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastUpdateMs).coerceAtLeast(0L)
        lastUpdateMs = now

        val dtSec = dtMs / 1000.0
        val dtHr  = dtMs / 3_600_000.0

        // High-precision accumulation using Double
        // Distance: convert km/h to m/s for precision, accumulate in meters
        val speedMs = speedKmh / 3.6
        preciseDistanceM += speedMs * dtSec
        
        // Fuel: accumulate in millilitres for better precision with small rates
        // 1 L/h = 1000 mL/h = 1000/3600 mL/s
        val fuelRateMlPerSec = fuelRateLh * 1000.0 / 3600.0
        preciseFuelUsedMl += fuelRateMlPerSec * dtSec

        // Update public Float fields (convert from precise accumulators)
        tripDistanceKm = (preciseDistanceM / 1000.0).toFloat()
        tripFuelUsedL = (preciseFuelUsedMl / 1000.0).toFloat()

        // Time buckets for trip summary
        val dtSecLong = (dtSec).toLong()
        when {
            speedKmh > 2f -> {
                movingTimeSec += dtSecLong
            }
            speedKmh > 0f -> {
                // Idling: engine running but vehicle not moving (0 < speed <= 2 km/h)
                stoppedTimeSec += dtSecLong
            }
            else -> {
                // Completely stopped: speed = 0
                stoppedTimeSec += dtSecLong
            }
        }

        // Trip-level drive mode time accumulation (more detailed classification)
        when {
            speedKmh <= 2f  -> idleTimeSec += dtSecLong
            speedKmh <= 60f -> cityTimeSec += dtSecLong
            else            -> highwayTimeSec += dtSecLong
        }

        // Peak speed
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

        // Rolling 60-second window for drive-mode classification
        speedWindow.addLast(now to speedKmh)
        val cutoff = now - 60_000L
        while (speedWindow.isNotEmpty() && speedWindow.first().first < cutoff) {
            speedWindow.removeFirst()
        }
    }

    /** Returns (pctCity, pctHighway, pctIdle) from the rolling 60 s window. */
    fun driveModePercents(): Triple<Float, Float, Float> {
        val snapshot = speedWindow.toList()   // safe copy — avoids CME if update() runs concurrently
        if (snapshot.isEmpty()) return Triple(0f, 0f, 0f)
        val total = snapshot.size.toFloat()
        var city = 0; var highway = 0; var idle = 0
        for (pair in snapshot) {
            val spd = pair.second
            when {
                spd <= 2f         -> idle++
                spd <= 60f        -> city++
                else              -> highway++
            }
        }
        return Triple(city / total * 100f, highway / total * 100f, idle / total * 100f)
    }

    /** Returns (pctCity, pctHighway, pctIdle) from the entire trip duration. */
    fun tripDriveModePercents(): Triple<Float, Float, Float> {
        val totalSec = idleTimeSec + cityTimeSec + highwayTimeSec
        if (totalSec == 0L) return Triple(0f, 0f, 0f)
        val total = totalSec.toFloat()
        return Triple(
            cityTimeSec / total * 100f,
            highwayTimeSec / total * 100f,
            idleTimeSec / total * 100f
        )
    }
}
