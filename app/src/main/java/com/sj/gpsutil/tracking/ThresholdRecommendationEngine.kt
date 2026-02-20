package com.sj.gpsutil.tracking

import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.DriverThresholdSettings
import org.json.JSONObject

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
    val recommendedDriver: DriverThresholdSettings
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

        // Stream fixes one at a time
        val metricsHistory = ArrayDeque<MetricsEngine.FixMetrics>()
        val fixResults = mutableListOf<FixResult>()
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

        val recommended = recommendCalibration(fixResults, params)
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
            recommendedDriver = recommendedDriver
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

    private fun recommendCalibration(fixes: List<FixResult>, params: CalibrateParams): CalibrationSettings {
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
            peakThresholdZ = 1.5f,
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
