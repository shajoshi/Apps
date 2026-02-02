package com.sj.gpsutil.tracking

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class JsonWriter(outputStream: OutputStream) : TrackWriter {
    private val writer = BufferedWriter(OutputStreamWriter(outputStream))
    private var closed = false
    private var firstDataItem = true
    private var startMillis: Long? = null
    private var recordingSettings: RecordingSettingsSnapshot? = null

    private val uuid = UUID.randomUUID().toString()
    private val localZone = ZoneId.systemDefault()
    private val timezoneOffsetMillis: Int = java.util.TimeZone.getDefault().rawOffset

    override fun setRecordingSettings(settings: RecordingSettingsSnapshot) {
        recordingSettings = settings
    }

    override fun writeHeader() {
        // Header is deferred until first sample so we can set times based on first point.
    }

    override fun appendSample(sample: TrackingSample) {
        if (closed) return

        if (startMillis == null) {
            startMillis = sample.timestampMillis
            val name = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .withZone(localZone)
                .format(Instant.ofEpochMilli(sample.timestampMillis))

            val utcTime = DateTimeFormatter.ISO_INSTANT
                .format(Instant.ofEpochMilli(sample.timestampMillis))
            val localTime = DateTimeFormatter.ISO_INSTANT
                .format(Instant.ofEpochMilli(sample.timestampMillis).atZone(localZone).toInstant())

            writer.write("{\n")
            writer.write("  \"gpslogger2path\": {\n")
            writer.write("    \"meta\": {\n")
            writer.write("      \"roundtrip\": false,\n")
            writer.write("      \"imported\": false,\n")
            writer.write("      \"commute\": false,\n")
            writer.write("      \"uuid\": \"$uuid\",\n")
            writer.write("      \"name\": \"$name\",\n")
            writer.write("      \"utctime\": \"$utcTime\",\n")
            writer.write("      \"localtime\": \"$localTime\",\n")
            writer.write("      \"timezoneoffset\": $timezoneOffsetMillis,\n")
            writer.write("      \"ts\": ${sample.timestampMillis}")
            val settingsSnapshot = recordingSettings
            if (settingsSnapshot != null) {
                val cal = settingsSnapshot.calibration
                writer.write(",\n")
                writer.write("      \"recordingSettings\": {\n")
                writer.write("        \"intervalSeconds\": ${settingsSnapshot.intervalSeconds},\n")
                writer.write("        \"disablePointFiltering\": ${settingsSnapshot.disablePointFiltering},\n")
                writer.write("        \"enableAccelerometer\": ${settingsSnapshot.enableAccelerometer},\n")
                writer.write("        \"roadCalibrationMode\": ${settingsSnapshot.roadCalibrationMode},\n")
                writer.write("        \"outputFormat\": \"${settingsSnapshot.outputFormat}\",\n")
                writer.write("        \"profileName\": ${settingsSnapshot.profileName?.let { "\"$it\"" } ?: "null"},\n")
                writer.write("        \"calibration\": {\n")
                writer.write("          \"rmsSmoothMax\": ${"%.3f".format(cal.rmsSmoothMax)},\n")
                writer.write("          \"rmsAverageMax\": ${"%.3f".format(cal.rmsAverageMax)},\n")
                writer.write("          \"peakThresholdZ\": ${"%.3f".format(cal.peakThresholdZ)},\n")
                writer.write("          \"symmetricBumpThreshold\": ${"%.3f".format(cal.symmetricBumpThreshold)},\n")
                writer.write("          \"potholeDipThreshold\": ${"%.3f".format(cal.potholeDipThreshold)},\n")
                writer.write("          \"bumpSpikeThreshold\": ${"%.3f".format(cal.bumpSpikeThreshold)},\n")
                writer.write("          \"peakCountSmoothMax\": ${cal.peakCountSmoothMax},\n")
                writer.write("          \"peakCountAverageMax\": ${cal.peakCountAverageMax},\n")
                writer.write("          \"movingAverageWindow\": ${cal.movingAverageWindow}")
                cal.baseGravityVector?.let { g ->
                    writer.write(",\n")
                    writer.write("          \"baseGravityVector\": { \"x\": ${"%.3f".format(g[0])}, \"y\": ${"%.3f".format(g[1])}, \"z\": ${"%.3f".format(g[2])} }")
                }
                writer.write("\n")
                writer.write("        }\n")
                writer.write("      }\n")
            } else {
                writer.write("\n")
            }
            writer.write("    },\n")
            writer.write("    \"data\": [\n")
            firstDataItem = true
        }

        val base = startMillis ?: sample.timestampMillis
        val tsOffset = (sample.timestampMillis - base).coerceAtLeast(0L)

        val sats = sample.satelliteCount ?: 0
        val acc = sample.accuracyMeters ?: 0f
        val course = sample.bearingDegrees ?: 0f
        val speed = sample.speedKmph ?: 0.0
        val alt = sample.altitudeMeters ?: 0.0

        if (!firstDataItem) {
            writer.write(",\n")
        }
        firstDataItem = false

        writer.write("      {\n")
        writer.write("        \"gps\": {\n")
        writer.write("          \"ts\": $tsOffset,\n")
        writer.write("          \"lat\": ${sample.latitude},\n")
        writer.write("          \"lon\": ${sample.longitude},\n")
        writer.write("          \"sats\": $sats,\n")
        writer.write("          \"acc\": $acc,\n")
        writer.write("          \"course\": $course,\n")
        writer.write("          \"speed\": $speed,\n")
        writer.write("          \"climbPPM\": 0,\n")
        writer.write("          \"climb\": 0,\n")
        writer.write("          \"salt\": $alt,\n")
        writer.write("          \"alt\": $alt\n")
        writer.write("        }")
        if (sample.accelXMean != null) {
            writer.write(",\n")
            writer.write("        \"accel\": {\n")
            writer.write("          \"xMean\": ${"%.3f".format(sample.accelXMean)},\n")
            writer.write("          \"yMean\": ${"%.3f".format(sample.accelYMean)},\n")
            writer.write("          \"zMean\": ${"%.3f".format(sample.accelZMean)},\n")
            sample.accelVertMean?.let { v ->
                writer.write("          \"vertMean\": ${"%.3f".format(v)},\n")
            }
            writer.write("          \"magMax\": ${"%.3f".format(sample.accelMagnitudeMax)},\n")
            writer.write("          \"rms\": ${"%.3f".format(sample.accelRMS)}")
            sample.roadQuality?.let {
                writer.write(",\n")
                writer.write("          \"roadQuality\": \"$it\"")
            }
            sample.featureDetected?.let {
                writer.write(",\n")
                writer.write("          \"featureDetected\": \"$it\"")
            }
            sample.manualLabel?.let {
                writer.write(",\n")
                writer.write("          \"manualLabel\": \"$it\"")
            }
            sample.manualFeatureLabel?.let {
                writer.write(",\n")
                writer.write("          \"manualFeatureLabel\": \"$it\"")
            }
            sample.gravityVector?.let { g ->
                writer.write(",\n")
                writer.write("          \"gravityVector\": { \"x\": ${"%.3f".format(g[0])}, \"y\": ${"%.3f".format(g[1])}, \"z\": ${"%.3f".format(g[2])} }")
            }
            sample.rawAccelData?.let { raw ->
                writer.write(",\n")
                writer.write("          \"raw\": [\n")
                raw.forEachIndexed { index, values ->
                    writer.write("            [${"%.3f".format(values[0])}, ${"%.3f".format(values[1])}, ${"%.3f".format(values[2])}]")
                    if (index < raw.size - 1) writer.write(",")
                    writer.write("\n")
                }
                writer.write("          ]")
            }
            // Simple color mapping for downstream styling
            when (sample.roadQuality) {
                "smooth" -> {
                    writer.write(",\n")
                    writer.write("          \"styleId\": \"smoothStyle\",\n")
                    writer.write("          \"color\": \"#00FF00\"")
                }
                "average" -> {
                    writer.write(",\n")
                    writer.write("          \"styleId\": \"averageStyle\",\n")
                    writer.write("          \"color\": \"#FFA500\"")
                }
                "rough" -> {
                    writer.write(",\n")
                    writer.write("          \"styleId\": \"roughStyle\",\n")
                    writer.write("          \"color\": \"#FF0000\"")
                }
            }
            sample.peakCount?.let {
                writer.write(",\n")
                writer.write("          \"peakCount\": $it")
            }
            sample.stdDev?.let {
                writer.write(",\n")
                writer.write("          \"stdDev\": ${"%.3f".format(it)}")
            }
            writer.write("\n")
            writer.write("        }")
        } else {
            writer.write("\n")
        }
        writer.write("\n      }")
        writer.flush()
    }

    override fun close(totalDistanceMeters: Double?) {
        if (closed) return

        if (startMillis == null) {
            writer.write("{\n  \"gpslogger2path\": {\n    \"meta\": {\n      \"uuid\": \"$uuid\"\n    },\n    \"data\": []\n  }\n}\n")
            writer.flush()
            writer.close()
            closed = true
            return
        }

        writer.write("\n    ]")
        totalDistanceMeters?.let { meters ->
            val km = meters / 1000.0
            writer.write(",\n    \"summary\": {\n")
            writer.write("      \"totalDistanceMeters\": ${"%.1f".format(meters)},\n")
            writer.write("      \"totalDistanceKm\": ${"%.3f".format(km)}\n")
            writer.write("    }")
        }
        writer.write("\n  }\n")
        writer.write("}\n")
        writer.flush()
        writer.close()
        closed = true
    }
}
