package com.sj.obd2app.metrics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure computation engine for accelerometer metrics.
 * Adapted from SJGpsUtil MetricsEngine — classification/detection methods removed.
 * No Android dependencies; can be unit-tested on JVM.
 *
 * Call [computeAccelMetrics] once per OBD2 poll tick with the drained sensor buffer.
 */
class AccelEngine(private val calibration: AccelCalibration = AccelCalibration()) {

    data class VehicleBasis(val gUnit: FloatArray, val fwd: FloatArray, val lat: FloatArray)

    /**
     * Builds vehicle-frame orthonormal basis from a gravity vector captured at trip start.
     * ĝ = normalised gravity (vertical axis)
     * fwd = device-Y projected onto horizontal plane (forward axis)
     * lat = ĝ × fwd (lateral axis)
     * Returns null if gravity vector is too small or degenerate.
     */
    fun computeVehicleBasis(gravity: FloatArray): VehicleBasis? {
        val norm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (norm < 1e-3f) return null
        val g = floatArrayOf(gravity[0] / norm, gravity[1] / norm, gravity[2] / norm)

        val yDotG = g[1]
        var fwdX = 0f - yDotG * g[0]
        var fwdY = 1f - yDotG * g[1]
        var fwdZ = 0f - yDotG * g[2]
        var fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)

        if (fwdNorm < 1e-3f) {
            val xDotG = g[0]
            fwdX = 1f - xDotG * g[0]
            fwdY = 0f - xDotG * g[1]
            fwdZ = 0f - xDotG * g[2]
            fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
        }
        if (fwdNorm < 1e-3f) return null

        val fwd = floatArrayOf(fwdX / fwdNorm, fwdY / fwdNorm, fwdZ / fwdNorm)
        val lat = floatArrayOf(
            g[1] * fwd[2] - g[2] * fwd[1],
            g[2] * fwd[0] - g[0] * fwd[2],
            g[0] * fwd[1] - g[1] * fwd[0]
        )
        return VehicleBasis(g, fwd, lat)
    }

    private fun applyMovingAverage(data: List<FloatArray>, windowSize: Int): List<FloatArray> {
        if (data.size < windowSize) return data
        return data.indices.map { i ->
            val start = maxOf(0, i - windowSize / 2)
            val end   = minOf(data.size, i + windowSize / 2 + 1)
            val window = data.subList(start, end)
            floatArrayOf(
                window.map { it[0] }.average().toFloat(),
                window.map { it[1] }.average().toFloat(),
                window.map { it[2] }.average().toFloat()
            )
        }
    }

    /**
     * Compute [AccelMetrics] from a raw sample buffer collected since the last poll tick.
     *
     * @param accelBuffer  Raw LINEAR_ACCELERATION samples [x, y, z]
     * @param basis        Vehicle-frame basis captured at trip start; null → fallback to device-Z
     * @return AccelMetrics, or null if [accelBuffer] is empty
     */
    fun computeAccelMetrics(
        accelBuffer: List<FloatArray>,
        basis: VehicleBasis?
    ): AccelMetrics? {
        if (accelBuffer.isEmpty()) return null

        val biasX = accelBuffer.map { it[0] }.average().toFloat()
        val biasY = accelBuffer.map { it[1] }.average().toFloat()
        val biasZ = accelBuffer.map { it[2] }.average().toFloat()

        val detrended = accelBuffer.map {
            floatArrayOf(it[0] - biasX, it[1] - biasY, it[2] - biasZ)
        }

        val biasNorm = sqrt(biasX * biasX + biasY * biasY + biasZ * biasZ)
        val gUnit = if (biasNorm > 1e-3f)
            floatArrayOf(biasX / biasNorm, biasY / biasNorm, biasZ / biasNorm)
        else null

        val smoothed = applyMovingAverage(detrended, calibration.movingAverageWindow.coerceAtLeast(1))

        val useG   = basis?.gUnit
        val useFwd = basis?.fwd
        val useLat = basis?.lat

        var sumVert = 0f
        var vertSumSq = 0f
        var vertMaxMag = 0f
        var aboveThreshCount = 0
        val signedVertValues = mutableListOf<Float>()
        val vertMagnitudes = mutableListOf<Float>()

        var fwdSumSq = 0f; var fwdMaxMag = 0f
        var fwdMaxPositive = 0f; var fwdMaxNegative = 0f; var fwdSum = 0f
        var latSumSq = 0f; var latMaxMag = 0f; var latSum = 0f

        for (v in smoothed) {
            val aVert = if (useG != null)
                v[0] * useG[0] + v[1] * useG[1] + v[2] * useG[2]
            else v[2]

            sumVert += aVert
            val absVert = abs(aVert)
            vertMagnitudes.add(absVert)
            signedVertValues.add(aVert)
            if (absVert > vertMaxMag) vertMaxMag = absVert
            vertSumSq += aVert * aVert
            if (absVert >= calibration.peakThresholdZ) aboveThreshCount++

            if (useFwd != null) {
                val aFwd = v[0] * useFwd[0] + v[1] * useFwd[1] + v[2] * useFwd[2]
                fwdSum += aFwd
                fwdSumSq += aFwd * aFwd
                val absFwd = abs(aFwd)
                if (absFwd > fwdMaxMag) fwdMaxMag = absFwd
                if (aFwd > fwdMaxPositive) fwdMaxPositive = aFwd
                if (-aFwd > fwdMaxNegative) fwdMaxNegative = -aFwd
            }

            if (useLat != null) {
                val aLat = v[0] * useLat[0] + v[1] * useLat[1] + v[2] * useLat[2]
                latSum += aLat
                latSumSq += aLat * aLat
                val absLat = abs(aLat)
                if (absLat > latMaxMag) latMaxMag = absLat
            }
        }

        val count = smoothed.size
        val meanVert = sumVert / count
        val rmsVert = sqrt(vertSumSq / count)
        val variance = signedVertValues.map { (it - meanVert) * (it - meanVert) }.average().toFloat()
        val stdDevVert = sqrt(variance)
        val peakRatio = aboveThreshCount.toFloat() / count.toFloat()

        val fwdRms  = if (useFwd != null && count > 0) sqrt(fwdSumSq / count) else 0f
        val fwdMean = if (useFwd != null && count > 0) fwdSum / count else 0f
        val latRms  = if (useLat != null && count > 0) sqrt(latSumSq / count) else 0f
        val latMean = if (useLat != null && count > 0) latSum / count else 0f

        val leanAngleDeg = if (biasNorm > 1e-3f && useLat != null && useG != null) {
            val wgX = biasX / biasNorm
            val wgY = biasY / biasNorm
            val wgZ = biasZ / biasNorm
            val latComp  = wgX * useLat[0] + wgY * useLat[1] + wgZ * useLat[2]
            val vertComp = wgX * useG[0]   + wgY * useG[1]   + wgZ * useG[2]
            Math.toDegrees(kotlin.math.atan2(latComp.toDouble(), vertComp.toDouble())).toFloat()
        } else 0f

        return AccelMetrics(
            vertRms           = rmsVert,
            vertMax           = vertMaxMag,
            vertMean          = meanVert,
            vertStdDev        = stdDevVert,
            vertPeakRatio     = peakRatio,
            fwdRms            = fwdRms,
            fwdMax            = fwdMaxMag,
            fwdMaxBrake       = fwdMaxNegative,
            fwdMaxAccel       = fwdMaxPositive,
            fwdMean           = fwdMean,
            latRms            = latRms,
            latMax            = latMaxMag,
            latMean           = latMean,
            leanAngleDeg      = leanAngleDeg,
            rawAccelSampleCount = accelBuffer.size
        )
    }
}
