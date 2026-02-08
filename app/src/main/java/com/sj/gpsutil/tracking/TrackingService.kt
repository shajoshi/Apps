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

        private const val NOTIFICATION_CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "GPS Tracking"
        private const val NOTIFICATION_ID = 1001

        private const val REJECTION_WINDOW_SAMPLES = 20
        private const val REJECTION_RATIO_THRESHOLD = 0.5
        private const val REJECTION_WARNING_COOLDOWN_MS = 60_000L
        private const val ACCEL_SAMPLING_PERIOD_US = 10_000 // 10 ms
        private const val TAG = "TrackingService"
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

    private data class FixMetrics(val rms: Float, val maxMagnitude: Float, val meanMagnitude: Float, val stdDev: Float, val peakRatio: Float)
    private val metricsHistory = ArrayDeque<FixMetrics>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: initializing service")
        applyDistanceAccuracyConfigFromManifest()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val accuracyText = if (location.hasAccuracy()) "${"%.1f".format(location.accuracy)} m" else "--"
                val speedText = if (location.hasSpeed()) "${"%.1f".format(location.speed * 3.6)} km/h" else "--"
                Log.d(TAG, "GPS fix received lat=${"%.5f".format(location.latitude)}, lon=${"%.5f".format(location.longitude)}, acc=$accuracyText, speed=$speedText")
                val accelMetrics = if (enableAccelerometer) computeAccelMetrics() else null
                val manualLabel = if (roadCalibrationMode) TrackingState.manualLabel.value else null
                val manualFeature = if (roadCalibrationMode) TrackingState.consumePendingFeatureLabel() else null
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
                    meanMagnitude = accelMetrics?.meanMagnitude,
                    accelRMS = accelMetrics?.rms,
                    roadQuality = accelMetrics?.roadQuality,
                    featureDetected = accelMetrics?.featureDetected,
                    peakRatio = accelMetrics?.peakRatio,
                    stdDev = accelMetrics?.stdDev,
                    avgRms = accelMetrics?.avgRms,
                    avgMaxMagnitude = accelMetrics?.avgMaxMagnitude,
                    avgMeanMagnitude = accelMetrics?.avgMeanMagnitude,
                    avgStdDev = accelMetrics?.avgStdDev,
                    avgPeakRatio = accelMetrics?.avgPeakRatio,
                    rawAccelData = if (roadCalibrationMode) accelMetrics?.rawData else null,
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
            try {
                sensorManager.registerListener(
                    accelerometerListener,
                    accelerometer,
                    ACCEL_SAMPLING_PERIOD_US
                )
                Log.d(TAG, "Accelerometer listener registered at ${ACCEL_SAMPLING_PERIOD_US}µs")
            } catch (se: SecurityException) {
                Log.e(TAG, "Missing permission for accelerometer sampling", se)
            }
        }
    }

    private fun unregisterAccelerometerListener() {
        sensorManager.unregisterListener(accelerometerListener)
        Log.d(TAG, "Accelerometer listener unregistered")
    }

    private fun computeAccelMetrics(): AccelMetrics? {
        synchronized(accelLock) {
            if (accelBuffer.isEmpty()) return null

            // Capture raw data (copy of current buffer)
            val rawData = accelBuffer.toList()

            // Step 1: Remove gravity/static offset from ALL axes (detrend)
            // Physics: The mean acceleration over a window approximates the gravity vector,
            // since transient vehicle motion averages out over many samples.
            // Subtracting this bias removes the static gravity component and mount-angle
            // dependency, so RMS/magnitude metrics reflect only dynamic road vibration.
            val biasX = accelBuffer.map { it[0] }.average().toFloat()
            val biasY = accelBuffer.map { it[1] }.average().toFloat()
            val biasZ = accelBuffer.map { it[2] }.average().toFloat()

            val detrended = accelBuffer.map {
                floatArrayOf(it[0] - biasX, it[1] - biasY, it[2] - biasZ)
            }

            // Compute gravity unit vector from per-window bias (estimated gravity direction)
            // Physics: bias ≈ gravity vector in device frame; normalizing gives vertical axis
            // This adapts automatically to any phone orientation without manual calibration
            val biasNorm = sqrt(biasX * biasX + biasY * biasY + biasZ * biasZ)
            val gUnit = if (biasNorm > 1e-3f) {
                floatArrayOf(biasX / biasNorm, biasY / biasNorm, biasZ / biasNorm)
            } else {
                null
            }

            // Log difference between per-window gravity estimate and calibration baseline
            val gCal = calibration.baseGravityVector
            if (gUnit != null && gCal != null && gCal.size >= 3) {
                val calNorm = sqrt(gCal[0] * gCal[0] + gCal[1] * gCal[1] + gCal[2] * gCal[2])
                if (calNorm > 1e-3f) {
                    val calUnit = floatArrayOf(gCal[0] / calNorm, gCal[1] / calNorm, gCal[2] / calNorm)
                    val dx = gUnit[0] - calUnit[0]
                    val dy = gUnit[1] - calUnit[1]
                    val dz = gUnit[2] - calUnit[2]
                    val angleDeg = Math.toDegrees(
                        kotlin.math.acos(
                            (gUnit[0] * calUnit[0] + gUnit[1] * calUnit[1] + gUnit[2] * calUnit[2])
                                .coerceIn(-1f, 1f).toDouble()
                        )
                    )
                    Log.d(TAG, "gVector diff: window=[%.3f,%.3f,%.3f] cal=[%.3f,%.3f,%.3f] delta=[%.4f,%.4f,%.4f] angle=%.2f°".format(
                        gUnit[0], gUnit[1], gUnit[2],
                        calUnit[0], calUnit[1], calUnit[2],
                        dx, dy, dz, angleDeg
                    ))
                }
            }

            // Step 2: Apply simple moving average filter (window size from calibration)
            val smoothed = applyMovingAverage(detrended, calibration.movingAverageWindow.coerceAtLeast(1))

            // Step 3: Calculate metrics from detrended accelerometer data
            // Physics: After detrending, readings ≈ dynamic acceleration only (gravity removed)
            // This makes RMS/magnitude metrics orientation-independent
            
            // Accumulator variables for statistical calculations
            var sumX = 0f          // Sum of X-axis accelerations (device frame)
            var sumY = 0f          // Sum of Y-axis accelerations (device frame)
            var sumZ = 0f          // Sum of Z-axis accelerations (device frame)
            var sumVert = 0f       // Sum of vertical (gravity-aligned) accelerations
            var maxMagnitude = 0f  // Peak 3D acceleration magnitude in window
            var sumSquares = 0f    // Sum of squared magnitudes (for RMS calculation)
            var aboveThresholdCount = 0  // Count of samples exceeding vertical threshold
            val magnitudes = mutableListOf<Float>()  // All magnitude values (for stdDev)

            // Process each smoothed acceleration sample in the window
            smoothed.forEach { values ->
                // Accumulate component-wise sums for mean calculations
                sumX += values[0]
                sumY += values[1]
                sumZ += values[2]

                // Vertical acceleration: Project 3D acceleration onto gravity direction
                // Physics: aVert = a · ĝ (dot product) isolates motion along vertical axis
                // This separates vertical bumps/dips from lateral/longitudinal motion
                val aVert = if (gUnit != null) {
                    // Dot product: a · ĝ = ax*ĝx + ay*ĝy + az*ĝz
                    values[0] * gUnit[0] + values[1] * gUnit[1] + values[2] * gUnit[2]
                } else {
                    // Fallback: Use Z-axis if no baseline (assumes device mounted vertically)
                    values[2]
                }
                sumVert += aVert

                // 3D acceleration magnitude: ||a|| = sqrt(ax² + ay² + az²)
                // Physics: Euclidean norm gives total acceleration intensity regardless of direction
                // Units: m/s² (same as components)
                val magnitude = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
                magnitudes.add(magnitude)
                if (magnitude > maxMagnitude) maxMagnitude = magnitude
                
                // Accumulate squared magnitudes for RMS calculation
                // RMS = sqrt(mean(magnitude²)) measures average energy/intensity
                sumSquares += magnitude * magnitude

                // Peak ratio: Count samples where vertical acceleration exceeds threshold
                // Physics: Sharp vertical jolts (bumps/potholes) produce high |aVert| spikes
                // Ratio = (spike count) / (total samples) is scale-invariant metric
                if (kotlin.math.abs(aVert) > calibration.peakThresholdZ) aboveThresholdCount++
            }

            // Calculate statistical metrics from accumulated values
            val count = smoothed.size
            
            // Mean accelerations: Average value over window (typically near zero for motion)
            val meanX = sumX / count
            val meanY = sumY / count
            val meanZ = sumZ / count
            val meanVert = sumVert / count
            
            // RMS (Root Mean Square): sqrt(mean(magnitude²))
            // Physics: Measures average acceleration intensity, analogous to AC voltage RMS
            // Higher RMS indicates rougher road with more sustained vibration energy
            val rms = kotlin.math.sqrt(sumSquares / count)
            
            // Peak ratio: Fraction of samples exceeding vertical threshold
            // Range: [0, 1], where higher values indicate more frequent sharp impacts
            val peakRatio = aboveThresholdCount.toFloat() / count.toFloat()

            // Standard deviation of magnitude: Measures variability/spread of acceleration
            // Physics: σ = sqrt(mean((x - μ)²)) quantifies consistency vs. spikiness
            // Low stdDev = smooth/uniform motion, High stdDev = erratic/bumpy motion
            val meanMagnitude = magnitudes.average().toFloat()
            val variance = magnitudes.map { (it - meanMagnitude) * (it - meanMagnitude) }.average().toFloat()
            val stdDev = kotlin.math.sqrt(variance)

            // Step 3b: Push instantaneous metrics to history ring buffer and compute averages
            // The moving average over recent GPS fixes smooths out noise for road quality
            metricsHistory.addLast(FixMetrics(rms, maxMagnitude, meanMagnitude, stdDev, peakRatio))
            val windowSize = calibration.qualityWindowSize.coerceAtLeast(1)
            while (metricsHistory.size > windowSize) metricsHistory.removeFirst()

            val avgRms = metricsHistory.map { it.rms }.average().toFloat()
            val avgMaxMagnitude = metricsHistory.map { it.maxMagnitude }.average().toFloat()
            val avgMeanMagnitude = metricsHistory.map { it.meanMagnitude }.average().toFloat()
            val avgStdDev = metricsHistory.map { it.stdDev }.average().toFloat()
            val avgPeakRatio = metricsHistory.map { it.peakRatio }.average().toFloat()

            // Step 4: Classify road quality using AVERAGED metrics (smooth/average/rough)
            // Averaging over recent fixes provides more consistent quality determinations
            val roadQuality = when {
                avgRms < calibration.rmsSmoothMax &&
                avgStdDev < calibration.stdDevSmoothMax -> "smooth"

                avgRms >= calibration.rmsRoughMin &&
                avgStdDev >= calibration.stdDevRoughMin -> "rough"

                else -> "average"
            }

            // Step 5: Detect features using INSTANTANEOUS metrics (single-fix sensitivity)
            // Features are transient events; averaging would mask them
            val feature = detectFeatureFromMetrics(
                rms = rms,
                magMax = maxMagnitude,
                peakRatio = peakRatio
            )

            accelBuffer.clear()

            return AccelMetrics(
                meanX, meanY, meanZ, meanVert, maxMagnitude, meanMagnitude, rms,
                peakRatio, stdDev,
                avgRms, avgMaxMagnitude, avgMeanMagnitude, avgStdDev, avgPeakRatio,
                roadQuality, feature, rawData
            )
        }
    }

    private fun detectFeatureFromMetrics(
        rms: Float,
        magMax: Float,
        peakRatio: Float
    ): String? {
        // Step 1: Gate on roughness to decide whether we even attempt feature detection.
        if (rms <= calibration.rmsRoughMin) return null

        // Step 2: Severity by MagMax (3D magnitude)
        if (magMax > calibration.magMaxSevereMin) {
            // Step 3: Severe refinement (pothole vs bump)
            return if (peakRatio < calibration.peakRatioRoughMin) "pothole" else "bump"
        }

        //if (magMax >= calibration.magMaxSpeedBumpMin && magMax <= calibration.magMaxSpeedBumpMax) {
        //    return "speed_bump"
        //}

        // No event
        return null
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

    private fun applyDistanceAccuracyConfigFromManifest() {
        val threshold = runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val value = appInfo.metaData?.getFloat("minAccuracyForDistanceCalc")
            value ?: 5f
        }.getOrDefault(5f)
        TrackingState.updateMinAccuracyForDistanceCalcMeters(threshold)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: tearing down service")
        stopLocationUpdates()
        trackWriter?.close(TrackingState.distanceMeters.value)
        trackWriter = null
        TrackingState.updateStatus(TrackingStatus.Idle)
        isForeground = false
        unregisterGnssCallback()
        unregisterAccelerometerListener()
    }

    private fun startRecording() {
        Log.d(TAG, "startRecording requested (currentStatus=$currentStatus)")
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
                Log.d(TAG, "Recording initialized: interval=${settings.intervalSeconds}s, accel=$enableAccelerometer, calibrationMode=$roadCalibrationMode, output=${settings.outputFormat}")
            }
            currentStatus = TrackingStatus.Recording
            TrackingState.updateStatus(currentStatus)
            TrackingState.onRecordingStarted()
            lastRecordedSample = null
            resetRejectionCounters()
            TrackingState.resetNotMovingTimer()
            TrackingState.resetSkippedPoints()
            metricsHistory.clear()
            registerAccelerometerListener()
            startLocationUpdates(currentIntervalSeconds)
            updateNotification("Recording")
            Log.d(TAG, "Recording started")
        }
    }

    private fun pauseRecording() {
        if (currentStatus != TrackingStatus.Recording) return
        Log.d(TAG, "Pausing recording")
        stopLocationUpdates()
        currentStatus = TrackingStatus.Paused
        TrackingState.updateStatus(currentStatus)
        TrackingState.onRecordingPaused()
        unregisterAccelerometerListener()
        updateNotification("Paused")
    }

    private fun stopRecording() {
        if (currentStatus == TrackingStatus.Idle) return
        Log.d(TAG, "Stopping recording (status=$currentStatus)")
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
        metricsHistory.clear()
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
            Log.w(TAG, "Cannot start location updates: location permission missing")
            updateNotification("Location permission missing")
            stopSelf()
            return
        }
        val intervalMillis = intervalSeconds.coerceAtLeast(1L) * 1000L
        Log.d(TAG, "Starting location updates every ${intervalMillis}ms")
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setMaxUpdateDelayMillis(intervalMillis)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates stopped")
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
            Log.w(TAG, "Failed to register GNSS status callback")
        } else {
            Log.d(TAG, "GNSS status callback registered")
        }
    }

    private fun unregisterGnssCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        gnssStatusCallback?.let { callback ->
            locationManager.unregisterGnssStatusCallback(callback)
            gnssStatusCallback = null
            Log.d(TAG, "GNSS status callback unregistered")
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
