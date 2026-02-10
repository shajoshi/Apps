package com.sj.gpsutil.tracking

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

class GpxWriter(outputStream: OutputStream) : TrackWriter {
    private val writer = BufferedWriter(OutputStreamWriter(outputStream))
    private var closed = false
    private var recordingSettings: RecordingSettingsSnapshot? = null

    override fun setRecordingSettings(settings: RecordingSettingsSnapshot) {
        recordingSettings = settings
    }

    override fun writeHeader() {
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        writer.newLine()
        writer.write("<gpx version=\"1.1\" creator=\"Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:sj=\"http://sj.gpsutil\">")
        writer.newLine()
        recordingSettings?.let { settings ->
            writer.write("<metadata>\n")
            writer.write("<extensions>\n")
            writer.write("<sj:recordingSettings>\n")
            writer.write("<sj:intervalSeconds>${settings.intervalSeconds}</sj:intervalSeconds>\n")
            writer.write("<sj:disablePointFiltering>${settings.disablePointFiltering}</sj:disablePointFiltering>\n")
            writer.write("<sj:enableAccelerometer>${settings.enableAccelerometer}</sj:enableAccelerometer>\n")
            writer.write("<sj:roadCalibrationMode>${settings.roadCalibrationMode}</sj:roadCalibrationMode>\n")
            writer.write("<sj:outputFormat>${settings.outputFormat}</sj:outputFormat>\n")
            writer.write("<sj:profileName>${settings.profileName ?: ""}</sj:profileName>\n")
            val cal = settings.calibration
            writer.write("<sj:calibration>\n")
            writer.write("<sj:rmsSmoothMax>${"%.3f".format(cal.rmsSmoothMax)}</sj:rmsSmoothMax>\n")
            writer.write("<sj:peakThresholdZ>${"%.3f".format(cal.peakThresholdZ)}</sj:peakThresholdZ>\n")
            writer.write("<sj:stdDevSmoothMax>${"%.3f".format(cal.stdDevSmoothMax)}</sj:stdDevSmoothMax>\n")
            writer.write("<sj:rmsRoughMin>${"%.3f".format(cal.rmsRoughMin)}</sj:rmsRoughMin>\n")
            writer.write("<sj:peakRatioRoughMin>${"%.3f".format(cal.peakRatioRoughMin)}</sj:peakRatioRoughMin>\n")
            writer.write("<sj:stdDevRoughMin>${"%.3f".format(cal.stdDevRoughMin)}</sj:stdDevRoughMin>\n")
            writer.write("<sj:magMaxSevereMin>${"%.3f".format(cal.magMaxSevereMin)}</sj:magMaxSevereMin>\n")
            writer.write("<sj:movingAverageWindow>${cal.movingAverageWindow}</sj:movingAverageWindow>\n")
            // Add captured gravity vector from recording start
            recordingSettings?.baseGravityVector?.let { g ->
                writer.write("<sj:baseGravityVectorX>${"%.3f".format(g[0])}</sj:baseGravityVectorX>\n")
                writer.write("<sj:baseGravityVectorY>${"%.3f".format(g[1])}</sj:baseGravityVectorY>\n")
                writer.write("<sj:baseGravityVectorZ>${"%.3f".format(g[2])}</sj:baseGravityVectorZ>\n")
            }
            writer.write("</sj:calibration>\n")
            writer.write("</sj:recordingSettings>\n")
            writer.write("</extensions>\n")
            writer.write("</metadata>\n")
        }
        writer.write("<trk>")
        writer.newLine()
        writer.write("<name>Track</name>")
        writer.newLine()
        writer.write("<trkseg>")
        writer.newLine()
        writer.flush()
    }

    override fun appendSample(sample: TrackingSample) {
        val lat = sample.latitude
        val lon = sample.longitude
        val time = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sample.timestampMillis))
        val ele = sample.altitudeMeters

        writer.write("<trkpt lat=\"$lat\" lon=\"$lon\">\n")
        if (ele != null) {
            writer.write("<ele>$ele</ele>\n")
        }
        writer.write("<time>$time</time>\n")
        sample.featureDetected?.let { feature ->
            writer.write("<name>$feature</name>\n")
        }
        if (sample.accelXMean != null) {
            writer.write("<extensions>\n")
            writer.write("<sj:accel>\n")
            writer.write("<sj:xMean>${"%.3f".format(sample.accelXMean)}</sj:xMean>\n")
            writer.write("<sj:yMean>${"%.3f".format(sample.accelYMean)}</sj:yMean>\n")
            writer.write("<sj:zMean>${"%.3f".format(sample.accelZMean)}</sj:zMean>\n")
            sample.accelVertMean?.let { v ->
                writer.write("<sj:vertMean>${"%.3f".format(v)}</sj:vertMean>\n")
            }
            writer.write("<sj:magMax>${"%.3f".format(sample.accelMagnitudeMax)}</sj:magMax>\n")
            writer.write("<sj:rms>${"%.3f".format(sample.accelRMS)}</sj:rms>\n")
            val styleId = when (sample.roadQuality) {
                "smooth" -> "smoothStyle"
                "rough" -> "roughStyle"
                else -> null
            }
            val styleColor = when (sample.roadQuality) {
                "smooth" -> "#00FF00"
                "rough" -> "#FF0000"
                else -> null
            }
            styleId?.let { writer.write("<sj:styleId>$it</sj:styleId>\n") }
            styleColor?.let { writer.write("<sj:color>$it</sj:color>\n") }
            sample.roadQuality?.let {
                writer.write("<sj:roadQuality>$it</sj:roadQuality>\n")
            }
            sample.featureDetected?.let {
                writer.write("<sj:featureDetected>$it</sj:featureDetected>\n")
            }
            sample.peakRatio?.let {
                writer.write("<sj:peakRatio>${"%.3f".format(it)}</sj:peakRatio>\n")
            }
            sample.stdDev?.let {
                writer.write("<sj:stdDev>${"%.3f".format(it)}</sj:stdDev>\n")
            }
            sample.avgRms?.let {
                writer.write("<sj:avgRms>${"%.3f".format(it)}</sj:avgRms>\n")
            }
            sample.avgMaxMagnitude?.let {
                writer.write("<sj:avgMaxMagnitude>${"%.3f".format(it)}</sj:avgMaxMagnitude>\n")
            }
            sample.avgMeanMagnitude?.let {
                writer.write("<sj:avgMeanMagnitude>${"%.3f".format(it)}</sj:avgMeanMagnitude>\n")
            }
            sample.avgStdDev?.let {
                writer.write("<sj:avgStdDev>${"%.3f".format(it)}</sj:avgStdDev>\n")
            }
            sample.avgPeakRatio?.let {
                writer.write("<sj:avgPeakRatio>${"%.3f".format(it)}</sj:avgPeakRatio>\n")
            }
            sample.accelFwdRms?.let {
                writer.write("<sj:fwdRms>${"%.3f".format(it)}</sj:fwdRms>\n")
            }
            sample.accelFwdMax?.let {
                writer.write("<sj:fwdMax>${"%.3f".format(it)}</sj:fwdMax>\n")
            }
            sample.accelLatRms?.let {
                writer.write("<sj:latRms>${"%.3f".format(it)}</sj:latRms>\n")
            }
            sample.accelLatMax?.let {
                writer.write("<sj:latMax>${"%.3f".format(it)}</sj:latMax>\n")
            }
            writer.write("</sj:accel>\n")
            writer.write("</extensions>\n")
        }
        writer.write("</trkpt>")
        writer.newLine()
        writer.flush()
    }

    override fun close(totalDistanceMeters: Double?) {
        if (closed) return
        writer.write("</trkseg>")
        writer.newLine()

        totalDistanceMeters?.let { meters ->
            val km = meters / 1000.0
            writer.write("<extensions>\n")
            writer.write("<sj:totalDistanceMeters>${"%.1f".format(meters)}</sj:totalDistanceMeters>\n")
            writer.write("<sj:totalDistanceKm>${"%.3f".format(km)}</sj:totalDistanceKm>\n")
            writer.write("</extensions>")
            writer.newLine()
        }

        writer.write("</trk>")
        writer.newLine()
        writer.write("</gpx>")
        writer.newLine()
        writer.flush()
        writer.close()
        closed = true
    }
}
