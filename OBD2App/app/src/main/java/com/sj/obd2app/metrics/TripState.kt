package com.sj.obd2app.metrics

/**
 * Mutable accumulator for trip-level statistics.
 * Reset by [MetricsCalculator.startTrip].
 */
internal class TripState {

    var tripStartMs: Long = System.currentTimeMillis()
    var lastUpdateMs: Long = tripStartMs

    var tripDistanceKm: Float = 0f
    var tripFuelUsedL: Float = 0f
    var movingTimeSec: Long = 0L
    var stoppedTimeSec: Long = 0L
    var maxSpeedKmh: Float = 0f

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
        speedWindow.clear()
    }

    /**
     * Advance accumulators by one update tick.
     * @param speedKmh   effective speed (GPS preferred, OBD fallback)
     * @param fuelRateLh effective fuel rate (L/h)
     */
    fun update(speedKmh: Float, fuelRateLh: Float) {
        val now = System.currentTimeMillis()
        val dtMs = (now - lastUpdateMs).coerceAtLeast(0L)
        lastUpdateMs = now

        val dtSec = dtMs / 1000.0
        val dtHr  = dtMs / 3_600_000.0

        // Distance
        tripDistanceKm += (speedKmh * dtHr).toFloat()

        // Fuel
        tripFuelUsedL += (fuelRateLh * dtHr).toFloat()

        // Time buckets (moving = speed > 2 km/h)
        if (speedKmh > 2f) {
            movingTimeSec += (dtSec).toLong()
        } else {
            stoppedTimeSec += (dtSec).toLong()
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
            if (pair == null) continue // Defensive: skip null entries
            val spd = pair.second
            when {
                spd <= 2f         -> idle++
                spd <= 60f        -> city++
                else              -> highway++
            }
        }
        return Triple(city / total * 100f, highway / total * 100f, idle / total * 100f)
    }
}
