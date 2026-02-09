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

    // Vehicle-frame orthonormal basis (computed once at recording start from calibration baseline)
    private var gUnitBasis: FloatArray? = null   // Vertical axis (gravity direction)
    private var fwdUnit: FloatArray? = null       // Forward/longitudinal axis (device-Y projected horizontal)
    private var latUnit: FloatArray? = null        // Lateral axis (cross product of ĝ × fwd)

    private data class FixMetrics(val rmsVert: Float, val maxMagnitude: Float, val meanMagnitudeVert: Float, val stdDevVert: Float, val peakRatio: Float)
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
                    manualFeatureLabel = manualFeature,
                    accelFwdRms = accelMetrics?.fwdRms,
                    accelFwdMax = accelMetrics?.fwdMax,
                    accelLatRms = accelMetrics?.latRms,
                    accelLatMax = accelMetrics?.latMax
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

    /**
     * Build vehicle-frame orthonormal basis from a gravity vector.
     * ĝ = normalized gravity (vertical axis)
     * ŷ_fwd = device-Y [0,1,0] projected onto horizontal plane (⊥ ĝ), normalized (forward axis)
     * x̂_lat = ĝ × ŷ_fwd (lateral axis)
     *
     * If device-Y is nearly parallel to gravity (degenerate), falls back to device-X.
     */
    private fun computeVehicleBasis(gravity: FloatArray) {
        val norm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (norm < 1e-3f) {
            Log.w(TAG, "computeVehicleBasis: gravity vector too small, skipping")
            gUnitBasis = null; fwdUnit = null; latUnit = null
            return
        }
        val g = floatArrayOf(gravity[0] / norm, gravity[1] / norm, gravity[2] / norm)

        // Project device-Y [0,1,0] onto horizontal plane: y_horiz = y - (y·ĝ)ĝ
        val yDotG = g[1] // dot([0,1,0], g) = g[1]
        var fwdX = 0f - yDotG * g[0]
        var fwdY = 1f - yDotG * g[1]
        var fwdZ = 0f - yDotG * g[2]
        var fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)

        // Degenerate case: device-Y nearly parallel to gravity → use device-X instead
        if (fwdNorm < 1e-3f) {
            Log.d(TAG, "computeVehicleBasis: device-Y parallel to gravity, using device-X as forward")
            val xDotG = g[0]
            fwdX = 1f - xDotG * g[0]
            fwdY = 0f - xDotG * g[1]
            fwdZ = 0f - xDotG * g[2]
            fwdNorm = sqrt(fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ)
        }

        if (fwdNorm < 1e-3f) {
            Log.w(TAG, "computeVehicleBasis: could not compute forward axis")
            gUnitBasis = null; fwdUnit = null; latUnit = null
            return
        }

        val fwd = floatArrayOf(fwdX / fwdNorm, fwdY / fwdNorm, fwdZ / fwdNorm)

        // Lateral = ĝ × fwd (cross product)
        val lat = floatArrayOf(
            g[1] * fwd[2] - g[2] * fwd[1],
            g[2] * fwd[0] - g[0] * fwd[2],
            g[0] * fwd[1] - g[1] * fwd[0]
        )

        gUnitBasis = g
        fwdUnit = fwd
        latUnit = lat
        Log.d(TAG, "Vehicle basis: ĝ=[%.3f,%.3f,%.3f] fwd=[%.3f,%.3f,%.3f] lat=[%.3f,%.3f,%.3f]".format(
            g[0], g[1], g[2], fwd[0], fwd[1], fwd[2], lat[0], lat[1], lat[2]
        ))
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

            // Step 3: Decompose into vehicle-frame axes and compute metrics
            // Vehicle basis is always computed in startRecording() from live gravity capture
            val useG = gUnitBasis
            val useFwd = fwdUnit
            val useLat = latUnit

            // Accumulator variables — vertical-only for classification metrics
            var sumX = 0f          // Sum of X-axis accelerations (device frame)
            var sumY = 0f          // Sum of Y-axis accelerations (device frame)
            var sumZ = 0f          // Sum of Z-axis accelerations (device frame)
            var sumVert = 0f       // Sum of vertical (gravity-aligned) accelerations
            var vertMaxMag = 0f    // Peak |aVert| in window
            var vertSumSquares = 0f // Sum of aVert² (for vertical RMS)
            var aboveZThresholdCount = 0  // Count of samples exceeding vertical threshold
            val vertMagnitudes = mutableListOf<Float>()  // |aVert| values (for stdDev)

            // Forward/lateral accumulators
            var fwdSumSquares = 0f
            var fwdMaxMag = 0f
            var fwdSum = 0f
            var latSumSquares = 0f
            var latMaxMag = 0f
            var latSum = 0f

            // Process each smoothed acceleration sample in the window
            smoothed.forEach { values ->
                // Accumulate component-wise sums for mean calculations
                sumX += values[0]
                sumY += values[1]
                sumZ += values[2]

                // Vertical acceleration: Project 3D acceleration onto gravity direction
                // Physics: aVert = a · ĝ (dot product) isolates motion along vertical axis
                val aVert = if (useG != null) {
                    values[0] * useG[0] + values[1] * useG[1] + values[2] * useG[2]
                } else {
                    values[2]
                }
                sumVert += aVert

                // Vertical magnitude for RMS/max/stdDev (replaces 3D magnitude)
                val absVert = kotlin.math.abs(aVert)
                vertMagnitudes.add(absVert)
                if (absVert > vertMaxMag) vertMaxMag = absVert
                vertSumSquares += aVert * aVert

                // Peak ratio: Count samples where vertical acceleration exceeds threshold
                if (absVert >= calibration.peakThresholdZ) {
                    aboveZThresholdCount++
                }

                // Forward acceleration: a · ŷ_fwd
                if (useFwd != null) {
                    val aFwd = values[0] * useFwd[0] + values[1] * useFwd[1] + values[2] * useFwd[2]
                    fwdSum += aFwd
                    fwdSumSquares += aFwd * aFwd
                    val absFwd = kotlin.math.abs(aFwd)
                    if (absFwd > fwdMaxMag) fwdMaxMag = absFwd
                }

                // Lateral acceleration: a · x̂_lat
                if (useLat != null) {
                    val aLat = values[0] * useLat[0] + values[1] * useLat[1] + values[2] * useLat[2]
                    latSum += aLat
                    latSumSquares += aLat * aLat
                    val absLat = kotlin.math.abs(aLat)
                    if (absLat > latMaxMag) latMaxMag = absLat
                }
            }

            // Calculate statistical metrics from accumulated values
            val count = smoothed.size

            // Mean accelerations
            val meanX = sumX / count
            val meanY = sumY / count
            val meanZ = sumZ / count
            val meanVert = sumVert / count

            // Vertical RMS (replaces 3D RMS): sqrt(mean(aVert²))
            // Now immune to longitudinal accel/decel
            val rmsVert = kotlin.math.sqrt(vertSumSquares / count)

            // maxMagnitude is now vertical-only peak
            val maxMagnitude = vertMaxMag

            // Peak ratio: Fraction of samples exceeding vertical threshold
            val peakRatio = aboveZThresholdCount.toFloat() / count.toFloat()

            // Standard deviation of vertical magnitude
            val meanMagnitudeVert = vertMagnitudes.average().toFloat()
            val variance = vertMagnitudes.map { (it - meanMagnitudeVert) * (it - meanMagnitudeVert) }.average().toFloat()
            val stdDevVert = kotlin.math.sqrt(variance)

            // Forward/lateral metrics
            val fwdRms = if (useFwd != null) kotlin.math.sqrt(fwdSumSquares / count) else 0f
            val fwdMean = if (useFwd != null) fwdSum / count else 0f
            val fwdMax = fwdMaxMag
            val latRms = if (useLat != null) kotlin.math.sqrt(latSumSquares / count) else 0f
            val latMean = if (useLat != null) latSum / count else 0f
            val latMax = latMaxMag

            // Step 3b: Push instantaneous metrics to history ring buffer and compute averages
            // The moving average over recent GPS fixes smooths out noise for road quality
            metricsHistory.addLast(FixMetrics(rmsVert, maxMagnitude, meanMagnitudeVert, stdDevVert, peakRatio))
            val windowSize = calibration.qualityWindowSize.coerceAtLeast(1)
            while (metricsHistory.size > windowSize) metricsHistory.removeFirst()

            val avgRms = metricsHistory.map { it.rmsVert }.average().toFloat()
            val avgMaxMagnitude = metricsHistory.map { it.maxMagnitude }.average().toFloat()
            val avgMeanMagnitude = metricsHistory.map { it.meanMagnitudeVert }.average().toFloat()
            val avgStdDev = metricsHistory.map { it.stdDevVert }.average().toFloat()
            val avgPeakRatio = metricsHistory.map { it.peakRatio }.average().toFloat()

            // Step 4: Classify road quality using AVERAGED vertical metrics (smooth/average/rough)
            val roadQuality = when {
                avgRms < calibration.rmsSmoothMax &&
                avgStdDev < calibration.stdDevSmoothMax -> "smooth"

                avgRms >= calibration.rmsRoughMin &&
                avgStdDev >= calibration.stdDevRoughMin -> "rough"

                else -> "average"
            }

            // Step 5: Detect features using INSTANTANEOUS vertical metrics
            val feature = detectFeatureFromMetrics(
                rms = rmsVert,
                magMax = maxMagnitude,
                peakRatio = peakRatio
            )

            accelBuffer.clear()

            return AccelMetrics(
                meanX, meanY, meanZ, meanVert, maxMagnitude, meanMagnitudeVert, rmsVert,
                peakRatio, stdDevVert,
                avgRms, avgMaxMagnitude, avgMeanMagnitude, avgStdDev, avgPeakRatio,
                roadQuality, feature, rawData,
                fwdRms, fwdMax, fwdMean, latRms, latMax, latMean
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
                    // Update calibration with fresh gravity and persist to settings
                    calibration = calibration.copy(baseGravityVector = capturedGravity)
                    settingsRepository.updateCalibration(calibration)
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
                    profileName = settings.currentProfileName
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
