package com.sj.gpsutil.tracking

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: String,
    val speed: Double,
    val bearing: Double,
    val accuracy: Double,
    val verticalAccuracy: Double,
    val featureDetected: String?,
    val roadQuality: String?,
    val accelXMean: Double?,
    val accelYMean: Double?,
    val accelZMean: Double?,
    val accelVertMean: Double?,
    val accelMagMax: Double?,
    val accelRMS: Double?,
    val stdDev: Double?,
    val avgRms: Double?,
    val avgMaxMagnitude: Double?,
    val avgMeanMagnitude: Double?,
    val avgStdDev: Double?,
    val avgPeakRatio: Double?,
    val peakRatio: Double?,
    val accelFwdRms: Double?,
    val accelFwdMax: Double?,
    val accelLatRms: Double?,
    val accelLatMax: Double?
)

data class LineSegment(
    val quality: String,
    val coordinates: MutableList<String>
)

class KmlWriter(outputStream: OutputStream) : TrackWriter {
    private val writer = BufferedWriter(OutputStreamWriter(outputStream))
    private var closed = false
    private val trackEntries = StringBuilder()
    private val trackPoints = mutableListOf<TrackPoint>()
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
            writer.write("<Data name=\"calibration.stdDevSmoothMax\"><value>${"%.3f".format(cal.stdDevSmoothMax)}</value></Data>\n")
            writer.write("<Data name=\"calibration.rmsRoughMin\"><value>${"%.3f".format(cal.rmsRoughMin)}</value></Data>\n")
            writer.write("<Data name=\"calibration.peakRatioRoughMin\"><value>${"%.3f".format(cal.peakRatioRoughMin)}</value></Data>\n")
            writer.write("<Data name=\"calibration.stdDevRoughMin\"><value>${"%.3f".format(cal.stdDevRoughMin)}</value></Data>\n")
            writer.write("<Data name=\"calibration.magMaxSevereMin\"><value>${"%.3f".format(cal.magMaxSevereMin)}</value></Data>\n")
            writer.write("<Data name=\"calibration.movingAverageWindow\"><value>${cal.movingAverageWindow}</value></Data>\n")
            // Add captured gravity vector from recording start
            recordingSettings?.baseGravityVector?.let { g ->
                writer.write("<Data name=\"calibration.baseGravityVectorX\"><value>${"%.3f".format(g[0])}</value></Data>\n")
                writer.write("<Data name=\"calibration.baseGravityVectorY\"><value>${"%.3f".format(g[1])}</value></Data>\n")
                writer.write("<Data name=\"calibration.baseGravityVectorZ\"><value>${"%.3f".format(g[2])}</value></Data>\n")
            }
            val dt = settings.driverThresholds
            writer.write("<Data name=\"driverThresholds.hardBrakeFwdMax\"><value>${"%.3f".format(dt.hardBrakeFwdMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.hardAccelFwdMax\"><value>${"%.3f".format(dt.hardAccelFwdMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.swerveLatMax\"><value>${"%.3f".format(dt.swerveLatMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.aggressiveCornerLatMax\"><value>${"%.3f".format(dt.aggressiveCornerLatMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.aggressiveCornerDCourse\"><value>${"%.3f".format(dt.aggressiveCornerDCourse)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.minSpeedKmph\"><value>${"%.3f".format(dt.minSpeedKmph)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.movingAvgWindow\"><value>${dt.movingAvgWindow}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.reactionTimeBrakeMax\"><value>${"%.3f".format(dt.reactionTimeBrakeMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.reactionTimeLatMax\"><value>${"%.3f".format(dt.reactionTimeLatMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.smoothnessRmsMax\"><value>${"%.3f".format(dt.smoothnessRmsMax)}</value></Data>\n")
            writer.write("<Data name=\"driverThresholds.fallLeanAngle\"><value>${"%.3f".format(dt.fallLeanAngle)}</value></Data>\n")
            writer.write("</ExtendedData>\n")
        }
        // Styles for road quality lines and feature points
        writer.write("<Style id=\"smoothLineStyle\"><LineStyle><color>ff00ff00</color><width>4</width></LineStyle></Style>")
        writer.newLine()
        writer.write("<Style id=\"averageLineStyle\"><LineStyle><color>ff00aaff</color><width>4</width></LineStyle></Style>")
        writer.newLine()
        writer.write("<Style id=\"roughLineStyle\"><LineStyle><color>ff0000cc</color><width>4</width></LineStyle></Style>")
        writer.newLine()
        
        // Feature point styles (only for bumps/potholes)
        writer.write("<Style id=\"bumpStyle\"><IconStyle><color>ffffff00</color><scale>0.8</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/triangle.png</href></Icon></IconStyle></Style>")
        writer.newLine()
        writer.write("<Style id=\"potholeStyle\"><IconStyle><color>ff8b4513</color><scale>0.8</scale><Icon><href>http://maps.google.com/mapfiles/kml/shapes/diamond.png</href></Icon></IconStyle></Style>")
        writer.newLine()
        writer.flush()
    }

    override fun appendSample(sample: TrackingSample) {
        val altitude = sample.altitudeMeters ?: 0.0
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sample.timestampMillis))

        // Collect track point data for later processing
        val trackPoint = TrackPoint(
            latitude = sample.latitude,
            longitude = sample.longitude,
            altitude = altitude,
            timestamp = timestamp,
            speed = sample.speedKmph?.toDouble() ?: 0.0,
            bearing = sample.bearingDegrees?.toDouble() ?: 0.0,
            accuracy = sample.accuracyMeters?.toDouble() ?: 0.0,
            verticalAccuracy = sample.verticalAccuracyMeters?.toDouble() ?: 0.0,
            featureDetected = sample.featureDetected,
            roadQuality = sample.roadQuality,
            accelXMean = sample.accelXMean?.toDouble(),
            accelYMean = sample.accelYMean?.toDouble(),
            accelZMean = sample.accelZMean?.toDouble(),
            accelVertMean = sample.accelVertMean?.toDouble(),
            accelMagMax = sample.accelMagnitudeMax?.toDouble(),
            accelRMS = sample.accelRMS?.toDouble(),
            stdDev = sample.stdDev?.toDouble(),
            avgRms = sample.avgRms?.toDouble(),
            avgMaxMagnitude = sample.avgMaxMagnitude?.toDouble(),
            avgMeanMagnitude = sample.avgMeanMagnitude?.toDouble(),
            avgStdDev = sample.avgStdDev?.toDouble(),
            avgPeakRatio = sample.avgPeakRatio?.toDouble(),
            peakRatio = sample.peakRatio?.toDouble(),
            accelFwdRms = sample.accelFwdRms?.toDouble(),
            accelFwdMax = sample.accelFwdMax?.toDouble(),
            accelLatRms = sample.accelLatRms?.toDouble(),
            accelLatMax = sample.accelLatMax?.toDouble()
        )
        
        trackPoints.add(trackPoint)
        
        // Track entries for gx:Track
        trackEntries.append("<when>$timestamp</when>\n")
        trackEntries.append("<gx:coord>${sample.longitude} ${sample.latitude} $altitude</gx:coord>\n")
        
        writer.flush()
    }

    override fun close(totalDistanceMeters: Double?) {
        if (closed) return

        // Process points and create line segments
        val lineSegments = mutableListOf<LineSegment>()
        val featurePoints = mutableListOf<TrackPoint>()
        
        var currentSegment = mutableListOf<String>()
        var currentQuality: String? = null
        
        for (point in trackPoints) {
            val coordStr = "${point.longitude},${point.latitude},${point.altitude}"
            
            // Collect line segments by road quality
            if (point.roadQuality != currentQuality) {
                // End current segment if it exists
                if (currentSegment.isNotEmpty() && currentQuality != null) {
                    lineSegments.add(LineSegment(currentQuality!!, currentSegment.toMutableList()))
                }
                // Start new segment
                currentSegment = mutableListOf(coordStr)
                currentQuality = point.roadQuality
            } else {
                currentSegment.add(coordStr)
            }
            
            // Only collect feature points (bumps/potholes)
            if (!point.featureDetected.isNullOrEmpty()) {
                featurePoints.add(point)
            }
        }
        
        // Add the last segment
        if (currentSegment.isNotEmpty() && currentQuality != null) {
            lineSegments.add(LineSegment(currentQuality!!, currentSegment))
        }
        
        // --- Write road quality line segments ---
        for (segment in lineSegments) {
            val styleId = when (segment.quality) {
                "smooth" -> "smoothLineStyle"
                "rough" -> "roughLineStyle"
                else -> "averageLineStyle"
            }
            
            writer.write("<Placemark>")
            writer.newLine()
            writer.write("<name>Road Quality: ${segment.quality}</name>")
            writer.newLine()
            writer.write("<styleUrl>#$styleId</styleUrl>")
            writer.newLine()
            writer.write("<LineString>")
            writer.newLine()
            writer.write("<tessellate>1</tessellate>")
            writer.newLine()
            writer.write("<coordinates>${segment.coordinates.joinToString(" ")}</coordinates>")
            writer.newLine()
            writer.write("</LineString>")
            writer.newLine()
            writer.write("</Placemark>")
            writer.newLine()
        }
        
        // --- Write feature placemarks (only bumps/potholes) ---
        for (point in featurePoints) {
            val styleId = when (point.featureDetected) {
                "bump" -> "bumpStyle"
                "pothole" -> "potholeStyle"
                else -> null
            }
            
            writer.write("<Placemark>")
            writer.newLine()
            writer.write("<name>${point.featureDetected?.replaceFirstChar { it.uppercase() } ?: "Feature"}</name>")
            writer.newLine()
            styleId?.let {
                writer.write("<styleUrl>#$it</styleUrl>")
                writer.newLine()
            }
            writer.write("<TimeStamp><when>${point.timestamp}</when></TimeStamp>")
            writer.newLine()
            writer.write("<ExtendedData>")
            writer.newLine()
            writer.write("<Data name=\"timestamp\"><value>${point.timestamp}</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"speedKmph\"><value>${"%.2f".format(point.speed)}</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"bearingDegrees\"><value>${"%.1f".format(point.bearing)}</value></Data>")
            writer.newLine()
            writer.write("<Data name=\"featureDetected\"><value>${point.featureDetected}</value></Data>")
            writer.newLine()
            
            // Add key accel metrics for features
            point.accelMagMax?.let {
                writer.write("<Data name=\"accelMagnitudeMax\"><value>${"%.3f".format(it)}</value></Data>")
                writer.newLine()
            }
            point.accelRMS?.let {
                writer.write("<Data name=\"accelRMS\"><value>${"%.3f".format(it)}</value></Data>")
                writer.newLine()
            }
            point.accelVertMean?.let {
                writer.write("<Data name=\"accelVertMean\"><value>${"%.3f".format(it)}</value></Data>")
                writer.newLine()
            }
            
            writer.write("</ExtendedData>")
            writer.newLine()
            writer.write("<Point>")
            writer.newLine()
            writer.write("<coordinates>${point.longitude},${point.latitude},${point.altitude}</coordinates>")
            writer.newLine()
            writer.write("</Point>")
            writer.newLine()
            writer.write("</Placemark>")
            writer.newLine()
        }
        
        // --- gx:Track (optional, for timeline playback) ---
        writer.write("<Placemark>")
        writer.newLine()
        writer.write("<name>Track Timeline</name>")
        writer.newLine()
        writer.write("<visibility>0</visibility>")  // Hidden by default
        writer.write("<gx:Track>")
        writer.newLine()
        writer.write(trackEntries.toString())
        writer.write("</gx:Track>")
        writer.newLine()
        writer.write("</Placemark>")
        writer.newLine()

        // --- Summary ---
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
