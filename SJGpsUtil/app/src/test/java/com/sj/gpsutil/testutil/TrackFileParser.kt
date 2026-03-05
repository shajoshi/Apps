package com.sj.gpsutil.testutil

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sj.gpsutil.data.CalibrationSettings
import java.io.InputStream

data class GpsData(
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val sats: Int,
    val acc: Float,
    val course: Float,
    val speed: Double,
    val alt: Double
)

data class ExpectedAccelMetrics(
    val xMean: Float?,
    val yMean: Float?,
    val zMean: Float?,
    val vertMean: Float?,
    val magMax: Float?,
    val rms: Float?,
    val roadQuality: String?,
    val featureDetected: String?,
    val peakRatio: Float?,
    val stdDev: Float?,
    val avgRms: Float?,
    val avgMaxMagnitude: Float?,
    val avgMeanMagnitude: Float?,
    val avgStdDev: Float?,
    val avgPeakRatio: Float?,
    val fwdRms: Float?,
    val fwdMax: Float?,
    val latRms: Float?,
    val latMax: Float?,
    val signedFwdRms: Float?,
    val signedLatRms: Float?,
    val leanAngleDeg: Float?
)

data class ExpectedDriverMetrics(
    val events: List<String>,
    val primaryEvent: String,
    val smoothnessScore: Float?,
    val jerk: Float?,
    val reactionTimeMs: Float?
)

data class ParsedDataPoint(
    val index: Int,
    val gps: GpsData,
    val rawAccel: List<FloatArray>?,
    val expectedAccel: ExpectedAccelMetrics?,
    val expectedDriver: ExpectedDriverMetrics?
)

data class ParsedTrackFile(
    val calibration: CalibrationSettings,
    val baseGravityVector: FloatArray?,
    val dataPoints: List<ParsedDataPoint>
)

object TrackFileParser {

    fun parse(inputStream: InputStream): ParsedTrackFile {
        val json = JsonParser.parseReader(inputStream.reader()).asJsonObject
        val root = json.getAsJsonObject("gpslogger2path")
        val meta = root.getAsJsonObject("meta")

        // Parse calibration from recordingSettings
        val calibration = parseCalibration(meta)
        val gravityVector = parseGravityVector(meta)

        // Parse data points
        val dataArray = root.getAsJsonArray("data")
        val dataPoints = mutableListOf<ParsedDataPoint>()

        dataArray.forEachIndexed { index, element ->
            val obj = element.asJsonObject
            val gps = parseGps(obj.getAsJsonObject("gps"))
            val accelObj = obj.getAsJsonObject("accel")
            val driverObj = obj.getAsJsonObject("driver")

            val rawAccel = accelObj?.getAsJsonArray("raw")?.map { arr ->
                val a = arr.asJsonArray
                floatArrayOf(a[0].asFloat, a[1].asFloat, a[2].asFloat)
            }

            val expectedAccel = accelObj?.let { parseExpectedAccel(it) }
            val expectedDriver = driverObj?.let { parseExpectedDriver(it) }

            dataPoints.add(ParsedDataPoint(index, gps, rawAccel, expectedAccel, expectedDriver))
        }

        return ParsedTrackFile(calibration, gravityVector, dataPoints)
    }

    private fun parseCalibration(meta: JsonObject): CalibrationSettings {
        val rs = meta.getAsJsonObject("recordingSettings") ?: return CalibrationSettings()
        val cal = rs.getAsJsonObject("calibration") ?: return CalibrationSettings()
        return CalibrationSettings(
            rmsSmoothMax = cal.get("rmsSmoothMax")?.asFloat ?: 1.0f,
            peakThresholdZ = cal.get("peakThresholdZ")?.asFloat ?: 1.5f,
            movingAverageWindow = cal.get("movingAverageWindow")?.asInt ?: 5,
            stdDevSmoothMax = cal.get("stdDevSmoothMax")?.asFloat ?: 2.5f,
            rmsRoughMin = cal.get("rmsRoughMin")?.asFloat ?: 4.5f,
            peakRatioRoughMin = cal.get("peakRatioRoughMin")?.asFloat ?: 0.6f,
            stdDevRoughMin = cal.get("stdDevRoughMin")?.asFloat ?: 3.0f,
            magMaxSevereMin = cal.get("magMaxSevereMin")?.asFloat ?: 20.0f,
            qualityWindowSize = cal.get("qualityWindowSize")?.asInt ?: 3
        )
    }

    private fun parseGravityVector(meta: JsonObject): FloatArray? {
        val rs = meta.getAsJsonObject("recordingSettings") ?: return null
        val cal = rs.getAsJsonObject("calibration") ?: return null
        val gv = cal.getAsJsonObject("baseGravityVector") ?: return null
        return floatArrayOf(
            gv.get("x")?.asFloat ?: return null,
            gv.get("y")?.asFloat ?: return null,
            gv.get("z")?.asFloat ?: return null
        )
    }

    private fun parseGps(gps: JsonObject): GpsData {
        return GpsData(
            ts = gps.get("ts")?.asLong ?: 0L,
            lat = gps.get("lat")?.asDouble ?: 0.0,
            lon = gps.get("lon")?.asDouble ?: 0.0,
            sats = gps.get("sats")?.asInt ?: 0,
            acc = gps.get("acc")?.asFloat ?: 0f,
            course = gps.get("course")?.asFloat ?: 0f,
            speed = gps.get("speed")?.asDouble ?: 0.0,
            alt = gps.get("alt")?.asDouble ?: 0.0
        )
    }

    private fun parseExpectedAccel(accel: JsonObject): ExpectedAccelMetrics {
        return ExpectedAccelMetrics(
            xMean = accel.get("xMean")?.asFloat,
            yMean = accel.get("yMean")?.asFloat,
            zMean = accel.get("zMean")?.asFloat,
            vertMean = accel.get("vertMean")?.asFloat,
            magMax = accel.get("magMax")?.asFloat,
            rms = accel.get("rms")?.asFloat,
            roadQuality = accel.get("roadQuality")?.asString,
            featureDetected = accel.get("featureDetected")?.asString,
            peakRatio = accel.get("peakRatio")?.asFloat,
            stdDev = accel.get("stdDev")?.asFloat,
            avgRms = accel.get("avgRms")?.asFloat,
            avgMaxMagnitude = accel.get("avgMaxMagnitude")?.asFloat,
            avgMeanMagnitude = accel.get("avgMeanMagnitude")?.asFloat,
            avgStdDev = accel.get("avgStdDev")?.asFloat,
            avgPeakRatio = accel.get("avgPeakRatio")?.asFloat,
            fwdRms = accel.get("fwdRms")?.asFloat,
            fwdMax = accel.get("fwdMax")?.asFloat,
            latRms = accel.get("latRms")?.asFloat,
            latMax = accel.get("latMax")?.asFloat,
            signedFwdRms = accel.get("signedFwdRms")?.asFloat,
            signedLatRms = accel.get("signedLatRms")?.asFloat,
            leanAngleDeg = accel.get("leanAngleDeg")?.asFloat
        )
    }

    private fun parseExpectedDriver(driver: JsonObject): ExpectedDriverMetrics {
        val events = driver.getAsJsonArray("events")?.map { it.asString } ?: emptyList()
        return ExpectedDriverMetrics(
            events = events,
            primaryEvent = driver.get("primaryEvent")?.asString ?: "normal",
            smoothnessScore = driver.get("smoothnessScore")?.asFloat,
            jerk = driver.get("jerk")?.asFloat,
            reactionTimeMs = driver.get("reactionTimeMs")?.asFloat
        )
    }
}
