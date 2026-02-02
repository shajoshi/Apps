package com.sj.gpsutil.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.PackageManager
import com.sj.gpsutil.data.CalibrationSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sj.gpsutil.MainActivity
import com.sj.gpsutil.R
import com.sj.gpsutil.data.OutputFormat
import com.sj.gpsutil.tracking.RecordingSettingsSnapshot
import com.sj.gpsutil.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "com.sj.gpsutil.tracking.action.START"
        const val ACTION_PAUSE = "com.sj.gpsutil.tracking.action.PAUSE"
        const val ACTION_STOP = "com.sj.gpsutil.tracking.action.STOP"
        const val ACTION_CAPTURE_BASELINE = "com.sj.gpsutil.tracking.action.CAPTURE_BASELINE"

        private const val NOTIFICATION_CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "GPS Tracking"
        private const val NOTIFICATION_ID = 1001

        private const val REJECTION_WINDOW_SAMPLES = 20
        private const val REJECTION_RATIO_THRESHOLD = 0.5
        private const val REJECTION_WARNING_COOLDOWN_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private lateinit var locationCallback: LocationCallback
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private val settingsRepository by lazy { SettingsRepository(applicationContext) }
    private val fileStore by lazy { TrackingFileStore(applicationContext) }

    private var trackWriter: TrackWriter? = null
    private var currentStatus: TrackingStatus = TrackingStatus.Idle
    private var isForeground = false
    private var currentIntervalSeconds: Long = 5L
    private var lastRecordedSample: TrackingSample? = null
    private var totalSamplesSinceWindowReset = 0
    private var rejectedSamplesSinceWindowReset = 0
    private var lastRejectionWarningAtMillis = 0L
    private var disablePointFiltering: Boolean = false
    private var enableAccelerometer: Boolean = true
    private var roadCalibrationMode: Boolean = false
    private var calibration = CalibrationSettings()
    private val accelBuffer = mutableListOf<FloatArray>()
    private val accelLock = Any()

    override fun onCreate() {
        super.onCreate()
        applyDistanceAccuracyConfigFromManifest()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val accelMetrics = if (enableAccelerometer) computeAccelMetrics() else null
                val manualLabel = if (roadCalibrationMode) TrackingState.manualLabel.value else null
                val manualFeature = if (roadCalibrationMode) TrackingState.consumePendingFeatureLabel() else null
                val gravityVector = if (roadCalibrationMode) TrackingState.gravityVector.value else null
                val sample = TrackingSample(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                    speedKmph = if (location.hasSpeed()) location.speed * 3.6 else null,
                    bearingDegrees = if (location.hasBearing()) location.bearing else null,
                    verticalAccuracyMeters = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                    satelliteCount = TrackingState.satelliteCount.value,
                    timestampMillis = location.time,
                    accelXMean = accelMetrics?.meanX,
                    accelYMean = accelMetrics?.meanY,
                    accelZMean = accelMetrics?.meanZ,
                    accelVertMean = accelMetrics?.meanVert,
                    accelMagnitudeMax = accelMetrics?.maxMagnitude,
                    accelRMS = accelMetrics?.rms,
                    roadQuality = accelMetrics?.roadQuality,
                    featureDetected = accelMetrics?.featureDetected,
                    peakCount = accelMetrics?.peakCount,
                    stdDev = accelMetrics?.stdDev,
                    rawAccelData = if (roadCalibrationMode) accelMetrics?.rawData else null,
                    gravityVector = gravityVector,
                    manualLabel = manualLabel,
                    manualFeatureLabel = manualFeature
                )
                sample.verticalAccuracyMeters?.let {
                    Log.d("TrackingService", "Vertical accuracy: ${String.format("%.1f", it)} m")
                }
                TrackingState.updateSample(sample)
                if (currentStatus == TrackingStatus.Recording) {
                    scope.launch {
                        totalSamplesSinceWindowReset++
                        if (shouldRecordSample(sample)) {
                            trackWriter?.appendSample(sample)
                            TrackingState.incrementPointCount()
                            TrackingState.onSampleRecorded(sample)
                            TrackingState.markMovement(sample.timestampMillis)
                            lastRecordedSample = sample
                        } else {
                            rejectedSamplesSinceWindowReset++
                            TrackingState.incrementSkippedPoints()
                            updateNotMoving(sample)
                            maybeWarnOnHighRejectionRate()
                        }
                    }
                }
            }
        }
        registerGnssCallback()
    }

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && enableAccelerometer) {
                synchronized(accelLock) {
                    accelBuffer.add(floatArrayOf(event.values[0], event.values[1], event.values[2]))
                    if (accelBuffer.size > 1000) {
                        accelBuffer.removeAt(0)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerAccelerometerListener() {
        if (!enableAccelerometer) return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(
                accelerometerListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    private fun unregisterAccelerometerListener() {
        sensorManager.unregisterListener(accelerometerListener)
    }

    private fun computeAccelMetrics(): AccelMetrics? {
        synchronized(accelLock) {
            if (accelBuffer.isEmpty()) return null

            // Capture raw data (copy of current buffer)
            val rawData = accelBuffer.toList()

            // Step 1: Remove gravity/static offset from ALL axes (detrend)
            // This makes RMS/magnitude metrics less sensitive to mount angle.
            val biasX = accelBuffer.map { it[0] }.average().toFloat()
            val biasY = accelBuffer.map { it[1] }.average().toFloat()
            val biasZ = accelBuffer.map { it[2] }.average().toFloat()

            val detrended = accelBuffer.map {
                floatArrayOf(it[0] - biasX, it[1] - biasY, it[2] - biasZ)
            }

            // Step 2: Apply simple moving average filter (window size from calibration)
            val smoothed = applyMovingAverage(detrended, calibration.movingAverageWindow.coerceAtLeast(1))

            // Step 3: Calculate metrics
            var sumX = 0f
            var sumY = 0f
            var sumZ = 0f
            var sumVert = 0f
            var maxMagnitude = 0f
            var sumSquares = 0f
            var peakCount = 0
            val magnitudes = mutableListOf<Float>()

            val g = calibration.baseGravityVector
            val gUnit = if (g != null && g.size >= 3) {
                val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (norm > 1e-3f) floatArrayOf(g[0] / norm, g[1] / norm, g[2] / norm) else null
            } else {
                null
            }

            smoothed.forEach { values ->
                sumX += values[0]
                sumY += values[1]
                sumZ += values[2]

                val aVert = if (gUnit != null) {
                    values[0] * gUnit[0] + values[1] * gUnit[1] + values[2] * gUnit[2]
                } else {
                    values[2]
                }
                sumVert += aVert

                val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
                magnitudes.add(magnitude)
                if (magnitude > maxMagnitude) maxMagnitude = magnitude
                sumSquares += magnitude * magnitude

                // Peak detection using calibration threshold
                if (kotlin.math.abs(aVert) > calibration.peakThresholdZ) peakCount++
            }

            val count = smoothed.size
            val meanX = sumX / count
            val meanY = sumY / count
            val meanZ = sumZ / count
            val meanVert = sumVert / count
            val rms = kotlin.math.sqrt(sumSquares / count)

            // Calculate standard deviation
            val meanMagnitude = magnitudes.average().toFloat()
            val variance = magnitudes.map { (it - meanMagnitude) * (it - meanMagnitude) }.average().toFloat()
            val stdDev = kotlin.math.sqrt(variance)

            // Step 4: Classify road quality (motorcycle-adjusted thresholds)
            val roadQuality = when {
                rms < calibration.rmsSmoothMax && peakCount < calibration.peakCountSmoothMax -> "smooth"
                rms < calibration.rmsAverageMax && peakCount < calibration.peakCountAverageMax -> "average"
                else -> "rough"
            }

            // Step 5: Detect features (use vertical component projected onto gravity)
            val aVertSeries = if (gUnit != null) {
                smoothed.map { it[0] * gUnit[0] + it[1] * gUnit[1] + it[2] * gUnit[2] }
            } else {
                smoothed.map { it[2] }
            }
            val feature = detectFeatureVert(
                aVertSeries,
                symmetricThreshold = calibration.symmetricBumpThreshold,
                potholeThreshold = calibration.potholeDipThreshold,
                bumpSpikeThreshold = calibration.bumpSpikeThreshold
            )

            accelBuffer.clear()

            return AccelMetrics(
                meanX, meanY, meanZ, meanVert, maxMagnitude, rms,
                peakCount, stdDev, roadQuality, feature, rawData
            )
        }
    }

    private fun applyMovingAverage(data: List<FloatArray>, windowSize: Int): List<FloatArray> {
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

    private fun detectFeatureVert(
        vertValues: List<Float>,
        symmetricThreshold: Float,
        potholeThreshold: Float,
        bumpSpikeThreshold: Float
    ): String? {
        if (vertValues.size < 10) return null

        // Speed bump: symmetric rise and fall pattern
        val hasSymmetricBump = detectSymmetricPattern(vertValues, symmetricThreshold)
        if (hasSymmetricBump) return "speed_bump"

        // Pothole: sharp drop and recovery (asymmetric dip)
        val hasAsymmetricDip = detectAsymmetricDip(vertValues, potholeThreshold)
        if (hasAsymmetricDip) return "pothole"

        // Single bump
        if (vertValues.any { kotlin.math.abs(it) > bumpSpikeThreshold }) return "bump"

        return null
    }

    private fun detectSymmetricPattern(zValues: List<Float>, threshold: Float): Boolean {
        // Look for rise followed by fall pattern
        for (i in 0 until zValues.size - 4) {
            val segment = zValues.subList(i, minOf(i + 5, zValues.size))
            val maxVal = segment.maxOrNull() ?: continue
            val maxIdx = segment.indexOf(maxVal)

            // Check if peak is in middle and exceeds threshold
            if (maxVal > threshold && maxIdx in 1..3) {
                val before = segment.subList(0, maxIdx).average().toFloat()
                val after = segment.subList(maxIdx + 1, segment.size).average().toFloat()
                // Symmetric if both sides are similar and below peak
                if (kotlin.math.abs(before - after) < 1.0f && before < maxVal * 0.7f) {
                    return true
                }
            }
        }
        return false
    }

    private fun detectAsymmetricDip(zValues: List<Float>, threshold: Float): Boolean {
        // Look for sharp drop pattern
        for (i in 0 until zValues.size - 4) {
            val segment = zValues.subList(i, minOf(i + 5, zValues.size))
            val minVal = segment.minOrNull() ?: continue
            val minIdx = segment.indexOf(minVal)

            // Check if dip is sharp and exceeds threshold
            if (minVal < threshold && minIdx in 1..3) {
                val before = segment.subList(0, minIdx).average().toFloat()
                val after = segment.subList(minIdx + 1, segment.size).average().toFloat()
                // Asymmetric dip: sharp drop, gradual recovery
                if (before > minVal && after > minVal) {
                    return true
                }
            }
        }
        return false
    }

    private fun applyDistanceAccuracyConfigFromManifest() {
        val threshold = runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val value = appInfo.metaData?.getFloat("minAccuracyForDistanceCalc")
            value ?: 5f
        }.getOrDefault(5f)
        TrackingState.updateMinAccuracyForDistanceCalcMeters(threshold)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_STOP -> stopRecording()
            ACTION_CAPTURE_BASELINE -> captureBaseline()
        }
        return START_STICKY
    }

    private fun captureBaseline() {
        synchronized(accelLock) {
            if (accelBuffer.isNotEmpty()) {
                val count = accelBuffer.size
                var sumX = 0f
                var sumY = 0f
                var sumZ = 0f
                accelBuffer.forEach {
                    sumX += it[0]
                    sumY += it[1]
                    sumZ += it[2]
                }
                val gravity = floatArrayOf(sumX / count, sumY / count, sumZ / count)
                TrackingState.setGravityVector(gravity)
                scope.launch {
                    val settings = settingsRepository.settingsFlow.first()
                    val updatedCalibration = settings.calibration.copy(baseGravityVector = gravity)
                    settingsRepository.updateCalibration(updatedCalibration)
                }
                updateNotification("Baseline Captured")
                
                // Revert notification after short delay if we are recording
                if (currentStatus == TrackingStatus.Recording) {
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        if (currentStatus == TrackingStatus.Recording) {
                            updateNotification("Recording")
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        trackWriter?.close(TrackingState.distanceMeters.value)
        trackWriter = null
        TrackingState.updateStatus(TrackingStatus.Idle)
        isForeground = false
        unregisterGnssCallback()
        unregisterAccelerometerListener()
    }

    private fun startRecording() {
        if (currentStatus == TrackingStatus.Recording) return
        ensureForeground("Starting tracking")
        scope.launch {
            if (trackWriter == null) {
                val settings = settingsRepository.settingsFlow.first()
                disablePointFiltering = settings.disablePointFiltering
                enableAccelerometer = settings.enableAccelerometer
                roadCalibrationMode = settings.roadCalibrationMode
                currentIntervalSeconds = settings.intervalSeconds
                calibration = settings.calibration
                TrackingState.setGravityVector(settings.calibration.baseGravityVector)
                val recordingSettingsSnapshot = RecordingSettingsSnapshot(
                    intervalSeconds = settings.intervalSeconds,
                    disablePointFiltering = settings.disablePointFiltering,
                    enableAccelerometer = settings.enableAccelerometer,
                    roadCalibrationMode = settings.roadCalibrationMode,
                    outputFormat = settings.outputFormat,
                    calibration = settings.calibration,
                    profileName = settings.currentProfileName
                )
                val handle = runCatching { fileStore.createTrackOutputStream(settings) }.getOrNull()
                    ?: run {
                        updateNotification("Failed to create file")
                        stopSelf()
                        return@launch
                    }
                val writer = when (settings.outputFormat) {
                    OutputFormat.GPX -> GpxWriter(handle.outputStream)
                    OutputFormat.KML -> KmlWriter(handle.outputStream)
                    OutputFormat.JSON -> JsonWriter(handle.outputStream)
                }
                writer.setRecordingSettings(recordingSettingsSnapshot)
                writer.writeHeader()
                trackWriter = writer
                TrackingState.resetPointCount()
                TrackingState.updateCurrentFileName(handle.filename)
            }
            currentStatus = TrackingStatus.Recording
            TrackingState.updateStatus(currentStatus)
            TrackingState.onRecordingStarted()
            lastRecordedSample = null
            resetRejectionCounters()
            TrackingState.resetNotMovingTimer()
            TrackingState.resetSkippedPoints()
            registerAccelerometerListener()
            startLocationUpdates(currentIntervalSeconds)
            updateNotification("Recording")
        }
    }

    private fun pauseRecording() {
        if (currentStatus != TrackingStatus.Recording) return
        stopLocationUpdates()
        currentStatus = TrackingStatus.Paused
        TrackingState.updateStatus(currentStatus)
        TrackingState.onRecordingPaused()
        unregisterAccelerometerListener()
        updateNotification("Paused")
    }

    private fun stopRecording() {
        if (currentStatus == TrackingStatus.Idle) return
        stopLocationUpdates()
        trackWriter?.close(TrackingState.distanceMeters.value)
        trackWriter = null
        currentStatus = TrackingStatus.Idle
        TrackingState.updateStatus(currentStatus)
        TrackingState.onRecordingStopped()
        lastRecordedSample = null
        resetRejectionCounters()
        TrackingState.resetNotMovingTimer()
        TrackingState.resetSkippedPoints()
        unregisterAccelerometerListener()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    private fun shouldRecordSample(sample: TrackingSample): Boolean {
        if (disablePointFiltering) return true
        if (lastRecordedSample == null) return true // Always accept the first sample
        val accuracy = sample.accuracyMeters
        val minAccuracy = TrackingState.minAccuracyForDistanceCalcMeters.value
        if (accuracy != null && accuracy > minAccuracy) return false

        val previous = lastRecordedSample ?: return true
        val result = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            sample.latitude,
            sample.longitude,
            result
        )
        val distance = result[0].toDouble()
        if (distance >= minAccuracy.toDouble()) {
            TrackingState.markMovement(sample.timestampMillis)
            return true
        }
        updateNotMoving(sample)
        return false
    }

    private fun updateNotMoving(sample: TrackingSample) {
        if (!disablePointFiltering) {
            TrackingState.updateNotMoving(sample.timestampMillis)
        }
    }

    private fun maybeWarnOnHighRejectionRate() {
        if (totalSamplesSinceWindowReset < REJECTION_WINDOW_SAMPLES) return
        val ratio = rejectedSamplesSinceWindowReset.toDouble() / totalSamplesSinceWindowReset.toDouble()
        if (ratio < REJECTION_RATIO_THRESHOLD) return

        val now = System.currentTimeMillis()
        if (now - lastRejectionWarningAtMillis < REJECTION_WARNING_COOLDOWN_MS) return

        updateNotification("Low accuracy: many points skipped")
        lastRejectionWarningAtMillis = now
        resetRejectionCounters()
    }

    private fun resetRejectionCounters() {
        totalSamplesSinceWindowReset = 0
        rejectedSamplesSinceWindowReset = 0
    }

    private fun startLocationUpdates(intervalSeconds: Long) {
        if (!hasLocationPermission()) {
            updateNotification("Location permission missing")
            stopSelf()
            return
        }
        val intervalMillis = intervalSeconds.coerceAtLeast(1L) * 1000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setMaxUpdateDelayMillis(intervalMillis)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun registerGnssCallback() {
        if (!hasLocationPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (gnssStatusCallback != null) return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                TrackingState.updateSatelliteCount(status.satelliteCount)
            }
        }
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                locationManager.registerGnssStatusCallback(mainExecutor, callback)
            }.isSuccess
        } else {
            val handler = Handler(Looper.getMainLooper())
            runCatching {
                locationManager.registerGnssStatusCallback(callback, handler)
            }.isSuccess
        }
        if (!success) {
            gnssStatusCallback = null
        }
    }

    private fun unregisterGnssCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        gnssStatusCallback?.let { callback ->
            locationManager.unregisterGnssStatusCallback(callback)
            gnssStatusCallback = null
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun ensureForeground(status: String) {
        val notification = buildNotification(status)
        if (!isForeground) {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        if (!isForeground) {
            ensureForeground(status)
            return
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GPS Tracking")
            .setContentText(status)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .build()

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}
