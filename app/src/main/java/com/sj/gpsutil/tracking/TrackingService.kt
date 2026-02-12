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
import android.widget.Toast
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

class TrackingService : Service() {
    companion object {
        const val ACTION_START = "com.sj.gpsutil.tracking.action.START"
        const val ACTION_PAUSE = "com.sj.gpsutil.tracking.action.PAUSE"
        const val ACTION_STOP = "com.sj.gpsutil.tracking.action.STOP"
        const val EXTRA_TRACK_NAME = "com.sj.gpsutil.tracking.extra.TRACK_NAME"

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
    private var previousSample: TrackingSample? = null
    private var totalSamplesSinceWindowReset = 0
    private var rejectedSamplesSinceWindowReset = 0
    private var lastRejectionWarningAtMillis = 0L
    private var disablePointFiltering: Boolean = false
    private var enableAccelerometer: Boolean = true
    private var roadCalibrationMode: Boolean = false
    private var calibration = CalibrationSettings()
    private var pendingTrackName: String? = null
    private val accelBuffer = mutableListOf<FloatArray>()
    private val accelLock = Any()

    // MetricsEngine for pure computation (created at recording start)
    private var metricsEngine: MetricsEngine? = null
    private var vehicleBasis: MetricsEngine.VehicleBasis? = null
    
    // Raw gravity vector captured at recording start (for export to tracking files)
    private var capturedGravityVector: FloatArray? = null

    private val metricsHistory = ArrayDeque<MetricsEngine.FixMetrics>()
    
    fun getCapturedGravityVector(): FloatArray? = capturedGravityVector?.copyOf()

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
                val currentSpeed = if (location.hasSpeed()) (location.speed * 3.6).toFloat() else 0f
                val accelMetrics = if (enableAccelerometer) computeAccelMetrics(currentSpeed) else null
                val manualLabel = if (roadCalibrationMode) TrackingState.manualLabel.value else null
                val manualFeature = if (roadCalibrationMode) TrackingState.consumePendingFeatureLabel() else null
                
                // Update delta calculations with actual bearing
                val updatedAccelMetrics = accelMetrics?.let { metrics ->
                    val deltaSpeed = previousSample?.speedKmph?.let { prev -> 
                        currentSpeed - prev.toFloat()
                    } ?: 0f
                    
                    val deltaCourse = previousSample?.bearingDegrees?.let { prev ->
                        val current = if (location.hasBearing()) location.bearing else 0f
                        bearingDiff(prev, current)
                    } ?: 0f
                    
                    metrics.copy(deltaSpeed = deltaSpeed, deltaCourse = deltaCourse)
                }
                
                val driverMetrics = updatedAccelMetrics?.let { 
                    computeDriverMetrics(it, currentSpeed) 
                }
                
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
                    accelXMean = updatedAccelMetrics?.meanX,
                    accelYMean = updatedAccelMetrics?.meanY,
                    accelZMean = updatedAccelMetrics?.meanZ,
                    accelVertMean = updatedAccelMetrics?.meanVert,
                    accelMagnitudeMax = updatedAccelMetrics?.maxMagnitude,
                    meanMagnitude = updatedAccelMetrics?.meanMagnitude,
                    accelRMS = updatedAccelMetrics?.rms,
                    roadQuality = updatedAccelMetrics?.roadQuality,
                    featureDetected = updatedAccelMetrics?.featureDetected,
                    peakRatio = updatedAccelMetrics?.peakRatio,
                    stdDev = updatedAccelMetrics?.stdDev,
                    avgRms = updatedAccelMetrics?.avgRms,
                    avgMaxMagnitude = updatedAccelMetrics?.avgMaxMagnitude,
                    avgMeanMagnitude = updatedAccelMetrics?.avgMeanMagnitude,
                    avgStdDev = updatedAccelMetrics?.avgStdDev,
                    avgPeakRatio = updatedAccelMetrics?.avgPeakRatio,
                    rawAccelData = if (roadCalibrationMode) updatedAccelMetrics?.rawData else null,
                    manualLabel = manualLabel,
                    manualFeatureLabel = manualFeature,
                    accelFwdRms = updatedAccelMetrics?.fwdRms,
                    accelFwdMax = updatedAccelMetrics?.fwdMax,
                    accelLatRms = updatedAccelMetrics?.latRms,
                    accelLatMax = updatedAccelMetrics?.latMax,
                    accelSignedFwdRms = updatedAccelMetrics?.signedFwdRms,
                    accelSignedLatRms = updatedAccelMetrics?.signedLatRms,
                    accelLeanAngleDeg = updatedAccelMetrics?.leanAngleDeg,
                    driverMetrics = driverMetrics
                )
                sample.verticalAccuracyMeters?.let {
                    Log.d("TrackingService", "Vertical accuracy: ${String.format("%.1f", it)} m")
                }
                TrackingState.updateSample(sample)
                
                // Update driver event count
                driverMetrics?.let { metrics ->
                    if (metrics.primaryEvent != "normal" && metrics.primaryEvent != "low_speed") {
                        TrackingState.incrementDriverEventCount()
                    }
                }
                
                // Store previous sample for next iteration
                previousSample = sample
                
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

    private fun computeVehicleBasis(gravity: FloatArray) {
        val engine = metricsEngine ?: return
        val basis = engine.computeVehicleBasis(gravity)
        vehicleBasis = basis
        if (basis != null) {
            val g = basis.gUnit; val fwd = basis.fwd; val lat = basis.lat
            Log.d(TAG, "Vehicle basis: ĝ=[%.3f,%.3f,%.3f] fwd=[%.3f,%.3f,%.3f] lat=[%.3f,%.3f,%.3f]".format(
                g[0], g[1], g[2], fwd[0], fwd[1], fwd[2], lat[0], lat[1], lat[2]
            ))
        } else {
            Log.w(TAG, "computeVehicleBasis: could not compute basis from gravity")
        }
    }

    private fun computeAccelMetrics(speedKmph: Float = 0f): AccelMetrics? {
        val engine = metricsEngine ?: return null
        synchronized(accelLock) {
            if (accelBuffer.isEmpty()) return null
            val bufferCopy = accelBuffer.toList()
            accelBuffer.clear()
            val result = engine.computeAccelMetrics(bufferCopy, speedKmph, vehicleBasis, metricsHistory)
            return result
        }
    }

    private fun computeDriverMetrics(accelMetrics: AccelMetrics, speed: Float): DriverMetrics {
        val engine = metricsEngine ?: return DriverMetrics(listOf("normal"), "normal", 100f, 0f, null)
        return engine.computeDriverMetrics(accelMetrics, speed, previousSample?.driverMetrics)
    }

    private fun bearingDiff(c1: Float, c2: Float): Float {
        val engine = metricsEngine ?: return c2 - c1
        return engine.bearingDiff(c1, c2)
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
            ACTION_START -> {
                pendingTrackName = intent.getStringExtra(EXTRA_TRACK_NAME)
                startRecording()
            }
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
                metricsEngine = MetricsEngine(calibration)

                // Force-capture gravity vector from accelerometer (assumes stationary at start)
                // Temporarily register listener, collect samples, then unregister.
                // The normal registerAccelerometerListener() call later handles ongoing recording.
                registerAccelerometerListener()
                synchronized(accelLock) { accelBuffer.clear() }
                delay(500L) // collect ~50 samples at 10ms period
                unregisterAccelerometerListener()
                val capturedGravity: FloatArray? = synchronized(accelLock) {
                    if (accelBuffer.isEmpty()) null
                    else {
                        var sx = 0f; var sy = 0f; var sz = 0f
                        accelBuffer.forEach { sx += it[0]; sy += it[1]; sz += it[2] }
                        val n = accelBuffer.size
                        floatArrayOf(sx / n, sy / n, sz / n)
                    }
                }
                if (capturedGravity != null) {
                    val gMag = sqrt(capturedGravity[0] * capturedGravity[0] + capturedGravity[1] * capturedGravity[1] + capturedGravity[2] * capturedGravity[2])
                    Log.d(TAG, "Gravity captured at start: [%.3f, %.3f, %.3f] |g|=%.3f".format(
                        capturedGravity[0], capturedGravity[1], capturedGravity[2], gMag))
                    capturedGravityVector = capturedGravity.copyOf()
                    TrackingState.setGravityVector(capturedGravity)
                    computeVehicleBasis(capturedGravity)
                    // Show toast on main thread
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Gravity: [%.2f, %.2f, %.2f] |g|=%.2f".format(
                                capturedGravity[0], capturedGravity[1], capturedGravity[2], gMag),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Log.e(TAG, "No accel samples — accelerometer not available; aborting recording")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "Accelerometer not available — cannot start recording",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    stopSelf()
                    return@launch
                }
                val recordingSettingsSnapshot = RecordingSettingsSnapshot(
                    intervalSeconds = settings.intervalSeconds,
                    disablePointFiltering = settings.disablePointFiltering,
                    enableAccelerometer = settings.enableAccelerometer,
                    roadCalibrationMode = settings.roadCalibrationMode,
                    outputFormat = settings.outputFormat,
                    calibration = calibration,  // Use updated calibration with newly captured gravity
                    profileName = settings.currentProfileName,
                    baseGravityVector = capturedGravityVector?.copyOf(),
                    driverThresholds = metricsEngine?.thresholds ?: MetricsEngine.DriverThresholds()
                )
                val handle = runCatching { fileStore.createTrackOutputStream(settings, pendingTrackName) }.getOrNull()
                    ?: run {
                        updateNotification("Failed to create file")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(applicationContext, "Failed to create track file", Toast.LENGTH_LONG).show()
                        }
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
            previousSample = null
            resetRejectionCounters()
            TrackingState.resetNotMovingTimer()
            TrackingState.resetSkippedPoints()
            TrackingState.resetDriverEventCount()
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
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "Low accuracy: many points skipped", Toast.LENGTH_LONG).show()
        }
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
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Location permission missing — cannot record", Toast.LENGTH_LONG).show()
            }
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
