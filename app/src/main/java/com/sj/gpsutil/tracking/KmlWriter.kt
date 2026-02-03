package com.sj.gpsutil.tracking

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

class KmlWriter(outputStream: OutputStream) : TrackWriter {
    private val writer = BufferedWriter(OutputStreamWriter(outputStream))
    private var closed = false
    private val trackEntries = StringBuilder()
    private val lineStringCoordinates = StringBuilder()
    private var recordingSettings: RecordingSettingsSnapshot? = null

    override fun setRecordingSettings(settings: RecordingSettingsSnapshot) {
        recordingSettings = settings
    }

    override fun writeHeader() {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.newLine()
        writer.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:gx=\"http://www.google.com/kml/ext/2.2\">")
        writer.newLine()
        writer.write("<Document>")
        writer.newLine()
        writer.write("<name>SJGpsUtil Track</name>")
        writer.newLine()
        recordingSettings?.let { settings ->
            writer.write("<ExtendedData>\n")
            writer.write("<Data name=\"intervalSeconds\"><value>${settings.intervalSeconds}</value></Data>\n")
            writer.write("<Data name=\"disablePointFiltering\"><value>${settings.disablePointFiltering}</value></Data>\n")
            writer.write("<Data name=\"enableAccelerometer\"><value>${settings.enableAccelerometer}</value></Data>\n")
            writer.write("<Data name=\"roadCalibrationMode\"><value>${settings.roadCalibrationMode}</value></Data>\n")
            writer.write("<Data name=\"outputFormat\"><value>${settings.outputFormat}</value></Data>\n")
            writer.write("<Data name=\"profileName\"><value>${settings.profileName ?: ""}</value></Data>\n")
            val cal = settings.calibration
            writer.write("<Data name=\"calibration.rmsSmoothMax\"><value>${"%.3f".format(cal.rmsSmoothMax)}</value></Data>\n")
            writer.write("<Data name=\"calibration.peakThresholdZ\"><value>${"%.3f".format(cal.peakThresholdZ)}</value></Data>\n")
            writer.write("<Data name=\"calibration.symmetricBumpThreshold\"><value>${"%.3f".format(cal.symmetricBumpThreshold)}</value></Data>\n")
            writer.write("<Data name=\"calibration.potholeDipThreshold\"><value>${"%.3f".format(cal.potholeDipThreshold)}</value></Data>\n")
            writer.write("<Data name=\"calibration.bumpSpikeThreshold\"><value>${"%.3f".format(cal.bumpSpikeThreshold)}</value></Data>\n")
            writer.write("<Data name=\"calibration.peakCountSmoothMax\"><value>${cal.peakCountSmoothMax}</value></Data>\n")
            writer.write("<Data name=\"calibration.movingAverageWindow\"><value>${cal.movingAverageWindow}</value></Data>\n")
            cal.baseGravityVector?.let { g ->
                writer.write("<Data name=\"calibration.baseGravityVectorX\"><value>${"%.3f".format(g[0])}</value></Data>\n")
                writer.write("<Data name=\"calibration.baseGravityVectorY\"><value>${"%.3f".format(g[1])}</value></Data>\n")
                writer.write("<Data name=\"calibration.baseGravityVectorZ\"><value>${"%.3f".format(g[2])}</value></Data>\n")
            }
            writer.write("</ExtendedData>\n")
        }
        // Styles for point coloring by road quality
        writer.write("<Style id=\"smoothStyle\"><IconStyle><color>ff00ff00</color><Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>")
        writer.newLine()
        writer.write("<Style id=\"roughStyle\"><IconStyle><color>ff0000ff</color><Icon><href>http://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>")
        writer.newLine()
        writer.flush()
    }

    override fun appendSample(sample: TrackingSample) {
        val altitude = sample.altitudeMeters ?: 0.0
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sample.timestampMillis))

        val speed = sample.speedKmph?.let { "%.2f".format(it) } ?: ""
        val bearing = sample.bearingDegrees?.let { "%.1f".format(it) } ?: ""
        val accuracy = sample.accuracyMeters?.let { "%.1f".format(it) } ?: ""
        val verticalAccuracy = sample.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: ""
        val accelXMean = sample.accelXMean?.let { "%.3f".format(it) } ?: ""
        val accelYMean = sample.accelYMean?.let { "%.3f".format(it) } ?: ""
        val accelZMean = sample.accelZMean?.let { "%.3f".format(it) } ?: ""
        val accelVertMean = sample.accelVertMean?.let { "%.3f".format(it) } ?: ""
        val accelMagMax = sample.accelMagnitudeMax?.let { "%.3f".format(it) } ?: ""
        val accelRMS = sample.accelRMS?.let { "%.3f".format(it) } ?: ""

        trackEntries.append("<when>$timestamp</when>\n")
        trackEntries.append("<gx:coord>${sample.longitude} ${sample.latitude} $altitude</gx:coord>\n")

        writer.write("<Placemark>")
        writer.newLine()
        val featureName = sample.featureDetected ?: ""
        writer.write("<name>$featureName</name>")
        writer.newLine()
        val styleId = when (sample.roadQuality) {
            "smooth" -> "smoothStyle"
            "rough" -> "roughStyle"
            else -> null
        }
        styleId?.let {
            writer.write("<styleUrl>#$it</styleUrl>")
            writer.newLine()
        }
        writer.write("<TimeStamp><when>$timestamp</when></TimeStamp>")
        writer.newLine()
        writer.write("<ExtendedData>")
        writer.newLine()
        writer.write("<Data name=\"timestamp\"><value>$timestamp</value></Data>")
        writer.newLine()
        writer.write("<Data name=\"speedKmph\"><value>$speed</value></Data>")
        writer.newLine()
        writer.write("<Data name=\"bearingDegrees\"><value>$bearing</value></Data>")
        writer.newLine()
        writer.write("<Data name=\"accuracyMeters\"><value>$accuracy</value></Data>")
        writer.newLine()
        writer.write("<Data name=\"verticalAccuracyMeters\"><value>$verticalAccuracy</value></Data>")
        writer.newLine()
        if (sample.accelXMean != null) {
            val formattedStdDev = sample.stdDev?.let { "%.3f".format(it) }
            writer.write("<Data name=\"accelXMean\"><value>$accelXMean</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"accelYMean\"><value>$accelYMean</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"accelZMean\"><value>$accelZMean</value></Data>")
            writer.newLine()
            if (sample.accelVertMean != null) {
                writer.write("<Data name=\"accelVertMean\"><value>$accelVertMean</value></Data>")
                writer.newLine()
            }
            writer.write("<Data name=\"accelMagnitudeMax\"><value>$accelMagMax</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"accelRMS\"><value>$accelRMS</value></Data>")
            writer.newLine()
            sample.roadQuality?.let {
                writer.write("<Data name=\"roadQuality\"><value>$it</value></Data>")
                writer.newLine()
            }
            sample.featureDetected?.let {
                writer.write("<Data name=\"featureDetected\"><value>$it</value></Data>")
                writer.newLine()
            }
            sample.peakCount?.let {
                writer.write("<Data name=\"peakCount\"><value>$it</value></Data>")
                writer.newLine()
            }
            sample.stdDev?.let {
                writer.write("<Data name=\"stdDev\"><value>${formattedStdDev}</value></Data>")
                writer.newLine()
            }
        }
        writer.write("</ExtendedData>")
        writer.newLine()
        writer.write("<Point>")
        writer.newLine()
        writer.write("<coordinates>${sample.longitude},${sample.latitude},$altitude</coordinates>")
        writer.newLine()
        writer.write("</Point>")
        writer.newLine()
        writer.write("</Placemark>")
        writer.newLine()

        if (lineStringCoordinates.isNotEmpty()) {
            lineStringCoordinates.append(' ')
        }
        lineStringCoordinates.append("${sample.longitude},${sample.latitude},$altitude")
        writer.flush()
    }

    override fun close(totalDistanceMeters: Double?) {
        if (closed) return

        writer.write("<Placemark>")
        writer.newLine()
        writer.write("<name>Track</name>")
        writer.newLine()
        writer.write("<MultiGeometry>")
        writer.newLine()
        writer.write("<gx:Track>")
        writer.newLine()
        writer.write(trackEntries.toString())
        writer.write("</gx:Track>")
        writer.newLine()
        writer.write("<LineString>")
        writer.newLine()
        writer.write("<tessellate>1</tessellate>")
        writer.newLine()
        writer.write("<coordinates>${lineStringCoordinates}</coordinates>")
        writer.newLine()
        writer.write("</LineString>")
        writer.newLine()
        writer.write("</MultiGeometry>")
        writer.newLine()
        writer.write("</Placemark>")
        writer.newLine()

        totalDistanceMeters?.let { meters ->
            val km = meters / 1000.0
            val formattedMeters = "%.1f".format(meters)
            val formattedKm = "%.3f".format(km)
            writer.write("<Placemark>")
            writer.newLine()
            writer.write("<name>Summary</name>")
            writer.newLine()
            writer.write("<ExtendedData>")
            writer.newLine()
            writer.write("<Data name=\"totalDistanceMeters\"><value>${formattedMeters}</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"totalDistanceKm\"><value>${formattedKm}</value></Data>")
            writer.newLine()
            writer.write("</ExtendedData>")
            writer.newLine()
            writer.write("</Placemark>")
            writer.newLine()
        }

        writer.write("</Document>")
        writer.newLine()
        writer.write("</kml>")
        writer.newLine()
        writer.flush()
        writer.close()
        closed = true
    }
}
