package com.sj.gpsutil.tracking

import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.DriverThresholdSettings
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

data class CalibrateParams(
    val smoothTargetPct: Double = 60.0,
    val roughTargetPct: Double = 10.0,
    val bumpTarget: Int = 5,
    val potholeTarget: Int = 5,
    val minSpeedKmph: Float = 6f,
    val hardBrakeTarget: Int = 5,
    val hardAccelTarget: Int = 5,
    val swerveTarget: Int = 5
)

data class ThresholdRecommendation(
    val samplingRateHz: Double,
    val totalFixes: Int,
    val achievedSmoothPct: Double,
    val achievedRoughPct: Double,
    val bumpCount: Int,
    val potholeCount: Int,
    val recommended: CalibrationSettings,
    val recommendedDriver: DriverThresholdSettings,
    val recommendedPeakZ: Float,
    val totalVertSamples: Int
)

private data class FixResult(
    val avgRms: Float,
    val avgStdDev: Float,
    val rmsVert: Float,
    val stdDevVert: Float,
    val magMax: Float,
    val meanVert: Float,
    val fwdMax: Float,
    val latMax: Float,
    val deltaSpeed: Float,
    val deltaCourse: Float,
    val speed: Float
)

class ThresholdRecommendationEngine {

    fun analyze(jsonText: String, params: CalibrateParams): ThresholdRecommendation {
        val root = JSONObject(jsonText)
        val obj = root.optJSONObject("gpslogger2path")
            ?: throw IllegalArgumentException("Not a valid track file")
        val dataArray = obj.optJSONArray("data")
            ?: throw IllegalArgumentException("Track file has no data array")

        // Read stored gravity vector from meta
        val meta = obj.optJSONObject("meta")
        val recSettings = meta?.optJSONObject("recordingSettings")
        val calObj = recSettings?.optJSONObject("calibration")
        val gravObj = calObj?.optJSONObject("baseGravityVector")
        val gravityVector: FloatArray? = if (gravObj != null) {
            floatArrayOf(
                gravObj.optDouble("x", 0.0).toFloat(),
                gravObj.optDouble("y", 0.0).toFloat(),
                gravObj.optDouble("z", 0.0).toFloat()
            )
        } else null

        // Use a neutral (wide) calibration so all fixes pass through without early rejection
        val neutralCal = CalibrationSettings(
            rmsSmoothMax = Float.MAX_VALUE,
            peakThresholdZ = 0.1f,
            movingAverageWindow = 5,
            stdDevSmoothMax = Float.MAX_VALUE,
            rmsRoughMin = Float.MAX_VALUE,
            peakRatioRoughMin = 0f,
            stdDevRoughMin = Float.MAX_VALUE,
            magMaxSevereMin = Float.MAX_VALUE,
            qualityWindowSize = 3
        )
        val engine = MetricsEngine(neutralCal)

        // Compute vehicle basis from stored gravity
        val basis: MetricsEngine.VehicleBasis? = gravityVector?.let { engine.computeVehicleBasis(it) }

        // Infer sampling rate
        val samplingRateHz = inferSamplingRate(dataArray)

        // Stream fixes one at a time - First pass: collect all vertical samples
        val metricsHistory = ArrayDeque<MetricsEngine.FixMetrics>()
        val fixResults = mutableListOf<FixResult>()
        val allVertSamples = mutableListOf<Float>()
        var prevSpeed = 0f
        var prevCourse = 0f

        for (i in 0 until dataArray.length()) {
            val point = dataArray.optJSONObject(i) ?: continue
            val gps = point.optJSONObject("gps") ?: continue
            val speed = gps.optDouble("speed", 0.0).toFloat()
            val course = gps.optDouble("course", 0.0).toFloat()

            if (speed < params.minSpeedKmph) {
                prevSpeed = speed
                prevCourse = course
                continue
            }

            val rawArray = point.optJSONObject("accel")?.optJSONArray("raw") ?: continue
            if (rawArray.length() == 0) continue

            val accelBuffer = mutableListOf<FloatArray>()
            for (j in 0 until rawArray.length()) {
                val sample = rawArray.optJSONArray(j) ?: continue
                if (sample.length() >= 3) {
                    accelBuffer.add(
                        floatArrayOf(
                            sample.optDouble(0, 0.0).toFloat(),
                            sample.optDouble(1, 0.0).toFloat(),
                            sample.optDouble(2, 0.0).toFloat()
                        )
                    )
                }
            }
            if (accelBuffer.isEmpty()) continue

            val accelMetrics = engine.computeAccelMetrics(accelBuffer, speed, basis, metricsHistory)
                ?: continue

            // Collect vertical samples for PeakThresholdZ analysis
            if (basis != null) {
                for (sample in accelBuffer) {
                    val aVert = sample[0] * basis.gUnit[0] + sample[1] * basis.gUnit[1] + sample[2] * basis.gUnit[2]
                    allVertSamples.add(abs(aVert))
                }
            } else {
                // Fallback: use Z axis if no basis
                for (sample in accelBuffer) {
                    allVertSamples.add(abs(sample[2]))
                }
            }

            val deltaSpeed = speed - prevSpeed
            val deltaCourse = engine.bearingDiff(prevCourse, course)

            fixResults.add(
                FixResult(
                    avgRms = accelMetrics.avgRms,
                    avgStdDev = accelMetrics.avgStdDev,
                    rmsVert = accelMetrics.rms,
                    stdDevVert = accelMetrics.stdDev,
                    magMax = accelMetrics.maxMagnitude,
                    meanVert = accelMetrics.meanVert,
                    fwdMax = accelMetrics.fwdMax,
                    latMax = accelMetrics.latMax,
                    deltaSpeed = deltaSpeed,
                    deltaCourse = deltaCourse,
                    speed = speed
                )
            )

            prevSpeed = speed
            prevCourse = course
        }

        if (fixResults.isEmpty()) {
            throw IllegalArgumentException("No valid fixes with raw accelerometer data found at speed >= ${params.minSpeedKmph} km/h")
        }

        // Recommend PeakThresholdZ from all collected vertical samples
        val recommendedPeakZ = recommendPeakThresholdZ(allVertSamples)

        val recommended = recommendCalibration(fixResults, params, recommendedPeakZ)
        val recommendedDriver = recommendDriverThresholds(fixResults, params)

        // Compute achieved percentages with recommended thresholds
        val smoothCount = fixResults.count { f ->
            f.avgRms < recommended.rmsSmoothMax && f.avgStdDev < recommended.stdDevSmoothMax
        }
        val roughCount = fixResults.count { f ->
            f.avgRms >= recommended.rmsRoughMin && f.avgStdDev >= recommended.stdDevRoughMin
        }
        val bumpCount = fixResults.count { f ->
            f.rmsVert > recommended.rmsRoughMin &&
            f.magMax > recommended.magMaxSevereMin &&
            f.meanVert < 0f
        }
        val potholeCount = fixResults.count { f ->
            f.rmsVert > recommended.rmsRoughMin &&
            f.magMax > recommended.magMaxSevereMin &&
            f.meanVert >= 0f
        }
        return ThresholdRecommendation(
            samplingRateHz = samplingRateHz,
            totalFixes = fixResults.size,
            achievedSmoothPct = if (fixResults.isNotEmpty()) 100.0 * smoothCount / fixResults.size else 0.0,
            achievedRoughPct = if (fixResults.isNotEmpty()) 100.0 * roughCount / fixResults.size else 0.0,
            bumpCount = bumpCount,
            potholeCount = potholeCount,
            recommended = recommended,
            recommendedDriver = recommendedDriver,
            recommendedPeakZ = recommendedPeakZ,
            totalVertSamples = allVertSamples.size
        )
    }

    private fun inferSamplingRate(dataArray: org.json.JSONArray): Double {
        val rates = mutableListOf<Double>()
        var prevTs: Long? = null
        for (i in 0 until dataArray.length()) {
            val point = dataArray.optJSONObject(i) ?: continue
            val gps = point.optJSONObject("gps") ?: continue
            val ts = gps.optLong("ts", -1L)
            if (ts < 0) continue
            val rawCount = point.optJSONObject("accel")?.optJSONArray("raw")?.length() ?: 0
            if (rawCount > 0 && prevTs != null) {
                val dtSec = (ts - prevTs) / 1000.0
                if (dtSec > 0) rates.add(rawCount / dtSec)
            }
            prevTs = ts
        }
        if (rates.isEmpty()) return 100.0
        rates.sort()
        return rates[rates.size / 2]
    }

    private fun recommendCalibration(fixes: List<FixResult>, params: CalibrateParams, recommendedPeakZ: Float): CalibrationSettings {
        val sortedRms = fixes.map { it.avgRms.toDouble() }.sorted()
        val sortedStdDev = fixes.map { it.avgStdDev.toDouble() }.sorted()
        val n = fixes.size

        // smooth threshold: top (smoothTargetPct)% of fixes should be below this
        val smoothIdx = ((params.smoothTargetPct / 100.0) * n).toInt().coerceIn(0, n - 1)
        val rmsSmoothMax = sortedRms[smoothIdx].toFloat()
        val stdDevSmoothMax = sortedStdDev[smoothIdx].toFloat()

        // rough threshold: bottom (roughTargetPct)% of fixes should be above this
        val roughIdx = (((100.0 - params.roughTargetPct) / 100.0) * n).toInt().coerceIn(0, n - 1)
        val rmsRoughMin = sortedRms[roughIdx].toFloat()
        val stdDevRoughMin = sortedStdDev[roughIdx].toFloat()

        // feature threshold: find magMaxSevereMin such that bump+pothole count ≈ target
        val featureTarget = params.bumpTarget + params.potholeTarget
        val sortedMagMax = fixes.map { it.magMax.toDouble() }.sorted()
        val magMaxSevereMin = if (featureTarget > 0 && featureTarget < n) {
            sortedMagMax[n - featureTarget].toFloat()
        } else {
            sortedMagMax.lastOrNull()?.toFloat() ?: 20f
        }

        return CalibrationSettings(
            rmsSmoothMax = rmsSmoothMax.coerceAtLeast(0.1f),
            peakThresholdZ = recommendedPeakZ,
            movingAverageWindow = 5,
            stdDevSmoothMax = stdDevSmoothMax.coerceAtLeast(0.1f),
            rmsRoughMin = rmsRoughMin.coerceAtLeast(rmsSmoothMax + 0.01f),
            peakRatioRoughMin = 0.6f,
            stdDevRoughMin = stdDevRoughMin.coerceAtLeast(stdDevSmoothMax + 0.01f),
            magMaxSevereMin = magMaxSevereMin.coerceAtLeast(1f),
            qualityWindowSize = 3
        )
    }

    private fun recommendDriverThresholds(fixes: List<FixResult>, params: CalibrateParams): DriverThresholdSettings {
        val n = fixes.size

        // fwdMax distribution for brake/accel thresholds
        val sortedFwdMax = fixes.map { it.fwdMax.toDouble() }.sorted()
        val brakeTarget = params.hardBrakeTarget + params.hardAccelTarget
        val fwdThreshold = if (brakeTarget > 0 && brakeTarget < n) {
            sortedFwdMax[n - brakeTarget].toFloat()
        } else {
            sortedFwdMax.lastOrNull()?.toFloat() ?: 15f
        }

        // latMax distribution for swerve threshold
        val sortedLatMax = fixes.map { it.latMax.toDouble() }.sorted()
        val swerveThreshold = if (params.swerveTarget > 0 && params.swerveTarget < n) {
            sortedLatMax[n - params.swerveTarget].toFloat()
        } else {
            sortedLatMax.lastOrNull()?.toFloat() ?: 4f
        }

        return DriverThresholdSettings(
            hardBrakeFwdMax = fwdThreshold.coerceAtLeast(1f),
            hardAccelFwdMax = fwdThreshold.coerceAtLeast(1f),
            swerveLatMax = swerveThreshold.coerceAtLeast(0.5f),
            aggressiveCornerLatMax = (swerveThreshold * 0.8f).coerceAtLeast(0.5f),
            aggressiveCornerDCourse = 15f,
            minSpeedKmph = params.minSpeedKmph,
            smoothnessRmsMax = 10f,
            fallLeanAngle = 40f
        )
    }

    /**
     * Analyzes all raw acceleration samples to recommend optimal PeakThresholdZ.
     * This mirrors the Python script's --recommendPeakZ functionality.
     */
    private fun recommendPeakThresholdZ(allVertSamples: List<Float>): Float {
        if (allVertSamples.isEmpty()) return 1.5f

        // Calculate statistics
        val mean = allVertSamples.average().toFloat()
        val median = allVertSamples.sorted()[allVertSamples.size / 2]
        val std = sqrt(allVertSamples.map { (it - mean) * (it - mean) }.average()).toFloat()

        // Calculate percentiles
        val sorted = allVertSamples.sorted()
        val p75 = sorted[(sorted.size * 0.75).toInt()]
        val p90 = sorted[(sorted.size * 0.90).toInt()]
        val p95 = sorted[(sorted.size * 0.95).toInt()]
        val p99 = sorted[(sorted.size * 0.99).toInt()]

        // Candidate thresholds
        val candidates = mapOf(
            "mean_plus_2std" to (mean + 2 * std),
            "mean_plus_3std" to (mean + 3 * std),
            "p75" to p75,
            "p90" to p90,
            "p95" to p95,
            "p99" to p99,
            "median_plus_2std" to (median + 2 * std)
        )

        // Evaluate each candidate
        var bestThreshold = 1.5f
        var bestScore = -1.0

        for ((_, threshold) in candidates) {
            if (threshold <= 0f) continue

            // Count peaks above threshold
            val peaksAbove = allVertSamples.count { it >= threshold }
            val peakRatio = peaksAbove.toDouble() / allVertSamples.size

            // Score based on having reasonable peak density (5-20% peaks)
            // Ideal is 10% peaks
            val score = when {
                peakRatio in 0.05..0.20 -> 1.0 - abs(peakRatio - 0.10)
                peakRatio < 0.05 -> peakRatio / 0.05
                else -> 0.20 / peakRatio
            }

            // Prefer higher thresholds for better signal separation
            val adjustedScore = score * (threshold / candidates.values.maxOrNull()!!)

            if (adjustedScore > bestScore) {
                bestScore = adjustedScore
                bestThreshold = threshold
            }
        }

        return bestThreshold.coerceAtLeast(0.1f)
    }

    companion object {
        fun hasRawAccelData(jsonText: String): Boolean {
            return runCatching {
                val root = JSONObject(jsonText)
                val obj = root.optJSONObject("gpslogger2path") ?: return@runCatching false
                val dataArray = obj.optJSONArray("data") ?: return@runCatching false
                for (i in 0 until dataArray.length()) {
                    val point = dataArray.optJSONObject(i) ?: continue
                    val raw = point.optJSONObject("accel")?.optJSONArray("raw")
                    if (raw != null && raw.length() > 0) return@runCatching true
                }
                false
            }.getOrElse { false }
        }
    }
}
