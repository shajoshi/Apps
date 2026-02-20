package com.sj.gpsutil.tracking

import com.sj.gpsutil.data.CalibrationSettings
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure computation engine for accelerometer metrics, driver event classification,
 * road quality, and feature detection. Has zero Android dependencies so it can
 * be unit-tested on JVM without a device.
 *
 * TrackingService delegates to an instance of this class.
 */
class MetricsEngine(
    val calibration: CalibrationSettings,
    val thresholds: DriverThresholds = DriverThresholds()
) {
    data class VehicleBasis(val gUnit: FloatArray, val fwd: FloatArray, val lat: FloatArray)

    data class DriverThresholds(
        val hardBrakeFwdMax: Float = 15f,
        val hardAccelFwdMax: Float = 15f,
        val swerveLatMax: Float = 4f,
        val aggressiveCornerLatMax: Float = 4f,
        val aggressiveCornerDCourse: Float = 15f,
        val minSpeedKmph: Float = 6f,
        val movingAvgWindow: Int = 10,
        val reactionTimeBrakeMax: Float = 15f,
        val reactionTimeLatMax: Float = 15f,
        val smoothnessRmsMax: Float = 10f,
        val fallLeanAngle: Float = 40f
    )

    data class FixMetrics(
        val rmsVert: Float,
        val maxMagnitude: Float,
        val meanMagnitudeVert: Float,
        val stdDevVert: Float,
        val peakRatio: Float
    )

    /**
     * Build vehicle-frame orthonormal basis from a gravity vector.
     * ĝ = normalized gravity (vertical axis)
     * ŷ_fwd = device-Y [0,1,0] projected onto horizontal plane (⊥ ĝ), normalized (forward axis)
     * x̂_lat = ĝ × ŷ_fwd (lateral axis)
     *
     * If device-Y is nearly parallel to gravity (degenerate), falls back to device-X.
     * Returns null if gravity vector is too small or forward axis cannot be computed.
     */
    fun computeVehicleBasis(gravity: FloatArray): VehicleBasis? {
        val norm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (norm < 1e-3f) {
            return null
        }
        val g = floatArrayOf(gravity[0] / norm, gravity[1] / norm, gravity[2] / norm)

        // Project device-Y [0,1,0] onto horizontal plane: y_horiz = y - (y·ĝ)ĝ
        val yDotG = g[1] // dot([0,1,0], g) = g[1]
        var fwdX = 0f - yDotG * g[0]
        var fwdY = 1f - yDotG * g[1]
        var fwdZ = 0f - yDotG * g[2]
        var fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)

        // Degenerate case: device-Y nearly parallel to gravity → use device-X instead
        if (fwdNorm < 1e-3f) {
            val xDotG = g[0]
            fwdX = 1f - xDotG * g[0]
            fwdY = 0f - xDotG * g[1]
            fwdZ = 0f - xDotG * g[2]
            fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
        }

        if (fwdNorm < 1e-3f) {
            return null
        }

        val fwd = floatArrayOf(fwdX / fwdNorm, fwdY / fwdNorm, fwdZ / fwdNorm)

        // Lateral = ĝ × fwd (cross product)
        val lat = floatArrayOf(
            g[1] * fwd[2] - g[2] * fwd[1],
            g[2] * fwd[0] - g[0] * fwd[2],
            g[0] * fwd[1] - g[1] * fwd[0]
        )

        return VehicleBasis(g, fwd, lat)
    }

    fun applyMovingAverage(data: List<FloatArray>, windowSize: Int): List<FloatArray> {
        if (data.size < windowSize) return data
        val result = mutableListOf<FloatArray>()
        for (i in data.indices) {
            val start = maxOf(0, i - windowSize / 2)
            val end = minOf(data.size, i + windowSize / 2 + 1)
            val window = data.subList(start, end)
            val avgX = window.map { it[0] }.average().toFloat()
            val avgY = window.map { it[1] }.average().toFloat()
            val avgZ = window.map { it[2] }.average().toFloat()
            result.add(floatArrayOf(avgX, avgY, avgZ))
        }
        return result
    }

    /**
     * Compute accelerometer metrics from a raw sample buffer.
     *
     * @param accelBuffer Raw accelerometer samples [x, y, z] collected since last GPS fix
     * @param speedKmph Current speed in km/h (for low-speed gating)
     * @param basis Vehicle-frame basis computed at recording start (from gravity capture)
     * @param metricsHistory Ring buffer of recent FixMetrics for averaging (mutated in place)
     * @return AccelMetrics or null if buffer is empty
     */
    fun computeAccelMetrics(
        accelBuffer: List<FloatArray>,
        speedKmph: Float = 0f,
        basis: VehicleBasis?,
        metricsHistory: ArrayDeque<FixMetrics>
    ): AccelMetrics? {
        if (accelBuffer.isEmpty()) return null

        // Capture raw data (copy of current buffer)
        val rawData = accelBuffer.toList()

        // Step 1: Remove gravity/static offset from ALL axes (detrend)
        val biasX = accelBuffer.map { it[0] }.average().toFloat()
        val biasY = accelBuffer.map { it[1] }.average().toFloat()
        val biasZ = accelBuffer.map { it[2] }.average().toFloat()

        val detrended = accelBuffer.map {
            floatArrayOf(it[0] - biasX, it[1] - biasY, it[2] - biasZ)
        }

        // Compute gravity unit vector from per-window bias (estimated gravity direction)
        val biasNorm = sqrt(biasX * biasX + biasY * biasY + biasZ * biasZ)
        val gUnit = if (biasNorm > 1e-3f) {
            floatArrayOf(biasX / biasNorm, biasY / biasNorm, biasZ / biasNorm)
        } else {
            null
        }

        // Step 2: Apply two different moving average filters
        // Small window for accel metrics (road quality)
        val smoothedAccel = applyMovingAverage(detrended, calibration.movingAverageWindow.coerceAtLeast(1))
        // Large window for driver metrics (event detection)
        val smoothedDriver = applyMovingAverage(detrended, thresholds.movingAvgWindow)

        // Step 3: Decompose into vehicle-frame axes and compute metrics
        val useG = basis?.gUnit
        val useFwd = basis?.fwd
        val useLat = basis?.lat

        // Accumulator variables — vertical-only for classification metrics
        var sumX = 0f
        var sumY = 0f
        var sumZ = 0f
        var sumVert = 0f
        var vertSumSquares = 0f
        var vertMaxMag = 0f
        var aboveZThresholdCount = 0
        val vertMagnitudes = mutableListOf<Float>()

        // Forward/lateral accumulators
        var fwdSumSquares = 0f
        var fwdMaxMag = 0f
        var fwdSum = 0f
        var latSumSquares = 0f
        var latMaxMag = 0f
        var latSum = 0f

        // Process accel metrics with small window
        smoothedAccel.forEach { values ->
            sumX += values[0]
            sumY += values[1]
            sumZ += values[2]

            val aVert = if (useG != null) {
                values[0] * useG[0] + values[1] * useG[1] + values[2] * useG[2]
            } else {
                values[2]
            }
            sumVert += aVert

            val absVert = abs(aVert)
            vertMagnitudes.add(absVert)
            if (absVert > vertMaxMag) vertMaxMag = absVert
            vertSumSquares += aVert * aVert

            if (absVert >= calibration.peakThresholdZ) {
                aboveZThresholdCount++
            }
        }

        // Process driver metrics with large window
        val fwdValuesList = mutableListOf<Float>()
        val latValuesList = mutableListOf<Float>()
        smoothedDriver.forEach { values ->
            if (useFwd != null) {
                val aFwd = values[0] * useFwd[0] + values[1] * useFwd[1] + values[2] * useFwd[2]
                fwdSum += aFwd
                fwdSumSquares += aFwd * aFwd
                val absFwd = abs(aFwd)
                if (absFwd > fwdMaxMag) fwdMaxMag = absFwd
                fwdValuesList.add(aFwd)
            }

            if (useLat != null) {
                val aLat = values[0] * useLat[0] + values[1] * useLat[1] + values[2] * useLat[2]
                latSum += aLat
                latSumSquares += aLat * aLat
                val absLat = abs(aLat)
                if (absLat > latMaxMag) latMaxMag = absLat
                latValuesList.add(aLat)
            }
        }

        // Calculate statistical metrics from accumulated values
        val count = smoothedAccel.size
        val driverCount = smoothedDriver.size

        val meanX = sumX / count
        val meanY = sumY / count
        val meanZ = sumZ / count
        val meanVert = sumVert / count

        val rmsVert = sqrt(vertSumSquares / count)
        val maxMagnitude = vertMaxMag
        val peakRatio = aboveZThresholdCount.toFloat() / count.toFloat()

        val meanMagnitudeVert = vertMagnitudes.average().toFloat()
        val variance = vertMagnitudes.map { (it - meanMagnitudeVert) * (it - meanMagnitudeVert) }.average().toFloat()
        val stdDevVert = sqrt(variance)

        val fwdRms = if (useFwd != null && driverCount > 0) sqrt(fwdSumSquares / driverCount) else 0f
        val fwdMean = if (useFwd != null && driverCount > 0) fwdSum / driverCount else 0f
        val fwdMax = fwdMaxMag
        val latRms = if (useLat != null && driverCount > 0) sqrt(latSumSquares / driverCount) else 0f
        val latMean = if (useLat != null && driverCount > 0) latSum / driverCount else 0f
        val latMax = latMaxMag

        val signedFwdRms = if (fwdMean != 0f) Math.copySign(fwdRms, fwdMean) else fwdRms
        val signedLatRms = if (latMean != 0f) Math.copySign(latRms, latMean) else latRms

        // Lean angle
        val leanAngleDeg = run {
            if (biasNorm > 1e-3f && useLat != null && useG != null) {
                val wgX = biasX / biasNorm
                val wgY = biasY / biasNorm
                val wgZ = biasZ / biasNorm
                val latComp = wgX * useLat[0] + wgY * useLat[1] + wgZ * useLat[2]
                val vertComp = wgX * useG[0] + wgY * useG[1] + wgZ * useG[2]
                Math.toDegrees(kotlin.math.atan2(latComp.toDouble(), vertComp.toDouble())).toFloat()
            } else 0f
        }

        // Step 3b: Push instantaneous metrics to history ring buffer and compute averages
        metricsHistory.addLast(FixMetrics(rmsVert, maxMagnitude, meanMagnitudeVert, stdDevVert, peakRatio))
        val windowSize = calibration.qualityWindowSize.coerceAtLeast(1)
        while (metricsHistory.size > windowSize) metricsHistory.removeFirst()

        val avgRms = metricsHistory.map { it.rmsVert }.average().toFloat()
        val avgMaxMagnitude = metricsHistory.map { it.maxMagnitude }.average().toFloat()
        val avgMeanMagnitude = metricsHistory.map { it.meanMagnitudeVert }.average().toFloat()
        val avgStdDev = metricsHistory.map { it.stdDevVert }.average().toFloat()
        val avgPeakRatio = metricsHistory.map { it.peakRatio }.average().toFloat()

        // Step 4 & 5: Skip road quality classification and feature detection at very low speeds
        val MIN_SPEED_FOR_DETECTION = 6f

        val roadQuality: String?
        val feature: String?

        if (speedKmph < MIN_SPEED_FOR_DETECTION) {
            roadQuality = null
            feature = null
        } else {
            roadQuality = classifyRoadQuality(avgRms, avgStdDev)

            // Detect features using INSTANTANEOUS vertical metrics
            val speedHumpFeature = if (rawData.isNotEmpty()) {
                val rawVertAccel = mutableListOf<Float>()
                for (values in smoothedAccel) {
                    val aVert = if (useG != null) {
                        values[0] * useG[0] + values[1] * useG[1] + values[2] * useG[2]
                    } else {
                        values[2]
                    }
                    rawVertAccel.add(aVert)
                }
                detectSpeedHumpPattern(rawVertAccel, fwdMax, speedKmph, 100f)
            } else null

            feature = speedHumpFeature ?: detectFeatureFromMetrics(rmsVert, maxMagnitude, peakRatio, meanVert)
        }

        // Calculate deltas (will be overwritten in TrackingService with actual bearing data)
        val deltaSpeed = 0f
        val deltaCourse = 0f

        return AccelMetrics(
            meanX, meanY, meanZ, meanVert, maxMagnitude, meanMagnitudeVert, rmsVert,
            peakRatio, stdDevVert,
            avgRms, avgMaxMagnitude, avgMeanMagnitude, avgStdDev, avgPeakRatio,
            roadQuality, feature, rawData,
            fwdRms, fwdMax, fwdMean, latRms, latMax, latMean,
            signedFwdRms, signedLatRms, leanAngleDeg,
            deltaSpeed, deltaCourse,
            fwdValuesList, latValuesList
        )
    }

    fun classifyRoadQuality(avgRms: Float, avgStdDev: Float): String {
        return when {
            avgRms < calibration.rmsSmoothMax &&
            avgStdDev < calibration.stdDevSmoothMax -> "smooth"

            avgRms >= calibration.rmsRoughMin &&
            avgStdDev >= calibration.stdDevRoughMin -> "rough"

            else -> "average"
        }
    }

    fun detectFeatureFromMetrics(
        rms: Float,
        magMax: Float,
        peakRatio: Float,
        meanVert: Float = 0f
    ): String? {
        if (rms <= calibration.rmsRoughMin) return null
        if (magMax > calibration.magMaxSevereMin) {
            // downward impulse (meanVert < 0) → bump, upward (meanVert >= 0) → pothole
            return if (meanVert < 0f) "bump" else "pothole"
        }
        return null
    }

    fun detectSpeedHumpPattern(
        rawVertAccel: List<Float>,
        fwdMax: Float,
        speed: Float,
        samplingRate: Float = 100f
    ): String? {
        val LOW_SPEED_THRESHOLD = 20.0f
        val LOW_SPEED_AMPLITUDE = 10.0f
        val LOW_SPEED_MIN_PEAKS = 8
        val HIGH_SPEED_AMPLITUDE = 12.0f
        val HIGH_SPEED_MIN_PEAKS = 12
        val MAX_DURATION = 8.0f
        val DECAY_RATIO_THRESHOLD = 0.7f
        val MIN_ZERO_CROSSINGS = 20

        val (minAmplitude, minPeaks) = if (speed < LOW_SPEED_THRESHOLD) {
            Pair(LOW_SPEED_AMPLITUDE, LOW_SPEED_MIN_PEAKS)
        } else {
            Pair(HIGH_SPEED_AMPLITUDE, HIGH_SPEED_MIN_PEAKS)
        }

        // Gate 1: Minimum number of peaks
        val peaks = mutableListOf<Float>()
        for (i in 1 until rawVertAccel.size - 1) {
            val current = rawVertAccel[i]
            val prev = rawVertAccel[i - 1]
            val next = rawVertAccel[i + 1]
            if (current > prev && current > next && abs(current) > 5.0f) {
                peaks.add(current)
            }
        }
        if (peaks.size < minPeaks) return null

        // Gate 2: Zero crossings (must have oscillations)
        var zeroCrossings = 0
        for (i in 1 until rawVertAccel.size) {
            if (rawVertAccel[i - 1] * rawVertAccel[i] < 0) {
                zeroCrossings++
            }
        }
        if (zeroCrossings < MIN_ZERO_CROSSINGS) return null

        // Gate 3: Duration check
        val duration = (rawVertAccel.size / samplingRate)
        if (duration > MAX_DURATION) return null

        // Gate 4: Peak-to-peak amplitude
        if (peaks.isEmpty()) return null
        val maxPeak = peaks.maxOrNull() ?: return null
        val minPeak = peaks.minOrNull() ?: return null
        val peakToPeakAmplitude = maxPeak - minPeak
        if (peakToPeakAmplitude < minAmplitude) return null

        // Gate 5: Amplitude decay (characteristic of speed humps)
        if (peaks.size >= 4) {
            val midPoint = peaks.size / 2
            val firstHalfPeaks = peaks.take(midPoint)
            val secondHalfPeaks = peaks.drop(midPoint)
            if (firstHalfPeaks.isNotEmpty() && secondHalfPeaks.isNotEmpty()) {
                val firstHalfAvg = firstHalfPeaks.map { abs(it) }.average()
                val secondHalfAvg = secondHalfPeaks.map { abs(it) }.average()
                val decayRatio = if (firstHalfAvg > 0) secondHalfAvg / firstHalfAvg else 1.0
                if (decayRatio > DECAY_RATIO_THRESHOLD) return null
            }
        }

        return "speed_bump"
    }

    fun classifyDriverEvent(
        fwdMax: Float,
        latMax: Float,
        deltaSpeed: Float,
        deltaCourse: Float,
        speed: Float,
        leanAngleDeg: Float = 0f
    ): List<String> {
        val events = mutableListOf<String>()

        if (abs(leanAngleDeg) >= thresholds.fallLeanAngle) {
            events.add("fall")
            return events
        }

        if (speed < thresholds.minSpeedKmph) {
            return listOf("low_speed")
        }

        if (fwdMax > thresholds.hardBrakeFwdMax && deltaSpeed < 0) {
            events.add("hard_brake")
        }
        if (fwdMax > thresholds.hardAccelFwdMax && deltaSpeed > 0) {
            events.add("hard_accel")
        }
        if (latMax > thresholds.swerveLatMax) {
            events.add("swerve")
        }
        if (latMax > thresholds.aggressiveCornerLatMax && abs(deltaCourse) > thresholds.aggressiveCornerDCourse) {
            events.add("aggressive_corner")
        }

        if (events.isEmpty()) {
            events.add("normal")
        }

        return events
    }

    fun computeSmoothnessScore(fwdRms: Float, latRms: Float): Float {
        val combined = 0.2f * fwdRms + 0.8f * latRms
        val score = maxOf(0f, 1f - combined / thresholds.smoothnessRmsMax) * 100f
        return score
    }

    fun computeDriverMetrics(
        accelMetrics: AccelMetrics,
        speed: Float,
        previousDriverMetrics: DriverMetrics?
    ): DriverMetrics {
        val events = classifyDriverEvent(
            accelMetrics.fwdMax,
            accelMetrics.latMax,
            accelMetrics.deltaSpeed,
            accelMetrics.deltaCourse,
            speed,
            accelMetrics.leanAngleDeg
        )

        val priority = listOf("fall", "hard_brake", "swerve", "aggressive_corner", "hard_accel", "normal", "low_speed")
        val primaryEvent = priority.first { it in events }

        val smoothness = computeSmoothnessScore(accelMetrics.fwdRms, accelMetrics.latRms)

        // Calculate jerk from signed RMS change
        val jerk = previousDriverMetrics?.let { prev ->
            val currSigned = if (accelMetrics.fwdMean != 0f)
                Math.copySign(accelMetrics.fwdRms, accelMetrics.fwdMean) else accelMetrics.fwdRms
            val prevSigned = prev.jerk
            abs(currSigned - prevSigned)
        } ?: 0f

        // Reaction time: time between first fwd spike and first lat spike (lat must follow fwd)
        val reactionTimeMs: Float? = run {
            val fwdValues = accelMetrics.fwdValues
            val latValues = accelMetrics.latValues
            if (fwdValues.isEmpty() || latValues.isEmpty()) return@run null

            val fwdSpikeIdx = fwdValues.indexOfFirst { abs(it) > thresholds.reactionTimeBrakeMax }
            if (fwdSpikeIdx < 0) return@run null

            val latSpikeIdx = latValues.indexOfFirst { abs(it) > thresholds.reactionTimeLatMax }
            if (latSpikeIdx < 0) return@run null

            val reactionSamples = latSpikeIdx - fwdSpikeIdx
            val samplingRate = 100f
            val maxSamples = (3.0f * samplingRate).toInt()
            if (reactionSamples in 10..maxSamples) {
                (reactionSamples / samplingRate) * 1000f
            } else null
        }

        return DriverMetrics(events, primaryEvent, smoothness, jerk, reactionTimeMs)
    }

    fun bearingDiff(c1: Float, c2: Float): Float {
        var d = c2 - c1
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }
}
