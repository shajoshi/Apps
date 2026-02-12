package com.sj.gpsutil

import com.google.gson.JsonParser
import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.data.OutputFormat
import com.sj.gpsutil.tracking.DriverMetrics
import com.sj.gpsutil.tracking.JsonWriter
import com.sj.gpsutil.tracking.RecordingSettingsSnapshot
import com.sj.gpsutil.tracking.TrackingSample
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class JsonWriterTest {

    private fun createSample(
        ts: Long = 1000L,
        lat: Double = 18.5,
        lon: Double = 73.7,
        speed: Double = 30.0,
        alt: Double = 500.0,
        rms: Float? = 2.0f,
        roadQuality: String? = "smooth",
        featureDetected: String? = null,
        driverMetrics: DriverMetrics? = DriverMetrics(
            events = listOf("normal"),
            primaryEvent = "normal",
            smoothnessScore = 85f,
            jerk = 0.5f,
            reactionTimeMs = null
        )
    ): TrackingSample {
        return TrackingSample(
            latitude = lat,
            longitude = lon,
            altitudeMeters = alt,
            speedKmph = speed,
            bearingDegrees = 90f,
            verticalAccuracyMeters = 2f,
            accuracyMeters = 5f,
            satelliteCount = 12,
            timestampMillis = ts,
            accelXMean = 0.1f,
            accelYMean = 0.2f,
            accelZMean = 0.3f,
            accelVertMean = 0.1f,
            accelMagnitudeMax = 5f,
            meanMagnitude = 3f,
            accelRMS = rms,
            roadQuality = roadQuality,
            featureDetected = featureDetected,
            peakRatio = 0.1f,
            stdDev = 1.0f,
            accelFwdRms = 1.5f,
            accelLatRms = 2.0f,
            accelSignedFwdRms = -1.5f,
            accelSignedLatRms = 2.0f,
            accelLeanAngleDeg = 3.0f,
            driverMetrics = driverMetrics
        )
    }

    private fun createSettings(
        gravityVector: FloatArray? = floatArrayOf(0.281f, 6.335f, 7.659f)
    ): RecordingSettingsSnapshot {
        return RecordingSettingsSnapshot(
            intervalSeconds = 1L,
            disablePointFiltering = true,
            enableAccelerometer = true,
            roadCalibrationMode = true,
            outputFormat = OutputFormat.JSON,
            calibration = CalibrationSettings(),
            profileName = "TestProfile",
            baseGravityVector = gravityVector
        )
    }

    @Test
    fun testJsonOutputValidStructure() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.setRecordingSettings(createSettings())
        writer.writeHeader()
        writer.appendSample(createSample(ts = 1000L))
        writer.appendSample(createSample(ts = 2000L))
        writer.close(1500.0)

        val jsonStr = baos.toString("UTF-8")

        // Should parse without exceptions
        val json = JsonParser.parseString(jsonStr).asJsonObject
        assertNotNull("Root should be a JSON object", json)

        val root = json.getAsJsonObject("gpslogger2path")
        assertNotNull("Should have gpslogger2path", root)

        val meta = root.getAsJsonObject("meta")
        assertNotNull("Should have meta", meta)

        val data = root.getAsJsonArray("data")
        assertNotNull("Should have data array", data)
        assertEquals("Should have 2 data points", 2, data.size())

        val summary = root.getAsJsonObject("summary")
        assertNotNull("Should have summary", summary)
    }

    @Test
    fun testJsonHeaderWithGravity() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.setRecordingSettings(createSettings(gravityVector = floatArrayOf(0.1f, 6.0f, 7.5f)))
        writer.writeHeader()
        writer.appendSample(createSample())
        writer.close()

        val jsonStr = baos.toString("UTF-8")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val cal = json.getAsJsonObject("gpslogger2path")
            .getAsJsonObject("meta")
            .getAsJsonObject("recordingSettings")
            .getAsJsonObject("calibration")

        val gv = cal.getAsJsonObject("baseGravityVector")
        assertNotNull("Should have baseGravityVector", gv)
        assertNotNull("Should have x", gv.get("x"))
        assertNotNull("Should have y", gv.get("y"))
        assertNotNull("Should have z", gv.get("z"))
    }

    @Test
    fun testJsonHeaderWithoutGravity() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.setRecordingSettings(createSettings(gravityVector = null))
        writer.writeHeader()
        writer.appendSample(createSample())
        writer.close()

        val jsonStr = baos.toString("UTF-8")
        // Should still be valid JSON
        val json = JsonParser.parseString(jsonStr).asJsonObject
        assertNotNull("Should parse without errors", json)

        val cal = json.getAsJsonObject("gpslogger2path")
            .getAsJsonObject("meta")
            .getAsJsonObject("recordingSettings")
            .getAsJsonObject("calibration")

        assertFalse("Should NOT have baseGravityVector", cal.has("baseGravityVector"))
    }

    @Test
    fun testJsonEventArrayFormat() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.setRecordingSettings(createSettings())
        writer.writeHeader()

        val dm = DriverMetrics(
            events = listOf("hard_brake", "swerve"),
            primaryEvent = "hard_brake",
            smoothnessScore = 30f,
            jerk = 5f,
            reactionTimeMs = 250f
        )
        writer.appendSample(createSample(driverMetrics = dm))
        writer.close()

        val jsonStr = baos.toString("UTF-8")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val data = json.getAsJsonObject("gpslogger2path").getAsJsonArray("data")
        val driver = data[0].asJsonObject.getAsJsonObject("driver")

        val events = driver.getAsJsonArray("events")
        assertNotNull("Should have events array", events)
        assertEquals(2, events.size())
        assertEquals("hard_brake", events[0].asString)
        assertEquals("swerve", events[1].asString)
        assertEquals("hard_brake", driver.get("primaryEvent").asString)
        assertNotNull("Should have reactionTimeMs", driver.get("reactionTimeMs"))
    }

    @Test
    fun testJsonEmptyTrack() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.writeHeader()
        writer.close()

        val jsonStr = baos.toString("UTF-8")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        assertNotNull("Empty track should still be valid JSON", json)
        val root = json.getAsJsonObject("gpslogger2path")
        assertNotNull("Should have gpslogger2path", root)
        val data = root.getAsJsonArray("data")
        assertEquals("Empty track should have 0 data points", 0, data.size())
    }

    @Test
    fun testJsonRoadQualityColors() {
        val baos = ByteArrayOutputStream()
        val writer = JsonWriter(baos)
        writer.setRecordingSettings(createSettings())
        writer.writeHeader()

        writer.appendSample(createSample(ts = 1000L, roadQuality = "smooth"))
        writer.appendSample(createSample(ts = 2000L, roadQuality = "average"))
        writer.appendSample(createSample(ts = 3000L, roadQuality = "rough"))
        writer.close()

        val jsonStr = baos.toString("UTF-8")
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val data = json.getAsJsonObject("gpslogger2path").getAsJsonArray("data")

        val accel0 = data[0].asJsonObject.getAsJsonObject("accel")
        assertEquals("#00FF00", accel0.get("color").asString)

        val accel1 = data[1].asJsonObject.getAsJsonObject("accel")
        assertEquals("#FFA500", accel1.get("color").asString)

        val accel2 = data[2].asJsonObject.getAsJsonObject("accel")
        assertEquals("#FF0000", accel2.get("color").asString)
    }
}
