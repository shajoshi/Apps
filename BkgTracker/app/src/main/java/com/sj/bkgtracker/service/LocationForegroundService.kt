package com.sj.bkgtracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.location.GnssStatus
import android.location.LocationManager
import com.sj.bkgtracker.R
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.GpsStateHolder
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocationForegroundService : Service() {

    companion object {
        /** GPS State Machine for Battery Optimization */
        enum class GpsState {
            DEEP_IDLE,      // GPS completely off, minimal battery drain
            ACQUISITION,    // Fast GPS startup when satellites detected
            ACTIVE,         // Normal GPS tracking with movement
            EXPRESS         // High-frequency tracking (overrides all)
        }
        private const val TAG = "LocationFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bkg_tracker_location"

        /** Minimum distance (meters) from last saved point to save a new point */
        private const val MIN_DISTANCE_METERS = 20.0

        /** Accuracy threshold to detect indoor/cell locations vs real GPS (meters) */
        private const val INDOOR_ACCURACY_THRESHOLD = 100f

        /** Normal mode: GPS fix every 15 seconds */
        private const val NORMAL_INTERVAL_MS = 15_000L
        /** Express mode: GPS fix every 10 seconds */
        private const val EXPRESS_INTERVAL_MS = 10_000L
        /** Idle/stationary mode: GPS fix every 5 minutes */
        private const val IDLE_INTERVAL_MS = 300_000L

        /** Acquisition mode: Fast GPS fix every 5 seconds when satellites detected */
        private const val ACQUISITION_INTERVAL_MS = 5_000L
        /** Acquisition timeout: Return to deep idle if no movement in 60 seconds */
        private const val ACQUISITION_TIMEOUT_MS = 60_000L

        /** Consecutive distance-skips before entering idle mode */
        private const val STATIONARY_SKIP_THRESHOLD = 6

        /** Minimum interval between notification updates in normal mode (5 min) */
        private const val NOTIFICATION_THROTTLE_MS = 300_000L

        fun start(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var notificationManager: NotificationManager? = null
    private var lastSavedLocation: android.location.Location? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var expressSyncJob: Job? = null

    /** GPS State Machine tracking */
    private var currentGpsState = GpsState.DEEP_IDLE
    private var acquisitionStartTime = 0L
    
    /** Consecutive distance-filter skips for stationary detection */
    private var consecutiveSkips = 0
    private var isIdleMode = false

    /** Timestamp of last notification update for throttling */
    private var lastNotificationUpdateMs = 0L

    /** GnssCallback for battery-efficient GPS detection */
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            Log.d(TAG, "GPS satellites detected - waking from deep idle")
            if (currentGpsState == GpsState.DEEP_IDLE && !ExpressSyncManager.isExpressMode.value) {
                enterAcquisitionMode()
            }
        }
        
        override fun onFirstFix(ttffMillis: Int) {
            Log.d(TAG, "First GPS fix acquired in ${ttffMillis}ms")
            if (currentGpsState == GpsState.ACQUISITION) {
                // Always enter active mode on first fix to allow movement detection
                // The location callback will determine if we should return to idle based on actual movement
                Log.d(TAG, "Entering active mode to detect movement")
                enterActiveMode()
            }
        }
        
        override fun onStopped() {
            Log.d(TAG, "GPS satellites lost - entering deep idle")
            if (currentGpsState != GpsState.EXPRESS) {
                enterDeepIdleMode()
            }
        }
        
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val satelliteCount = status.satelliteCount
            var usedInFix = 0
            for (i in 0 until satelliteCount) {
                if (status.usedInFix(i)) {
                    usedInFix++
                }
            }
            Log.d(TAG, "Satellites: $usedInFix/$satelliteCount used in fix")
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val isExpress = ExpressSyncManager.isExpressMode.value

            // In express mode, bypass accuracy and distance filters
            if (!isExpress) {
                // Indoor detection: skip if poor accuracy (indoor/cell/wifi instead of real GPS)
                val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
                if (accuracy > INDOOR_ACCURACY_THRESHOLD) {
                    val reason = "indoor accuracy ${accuracy.toInt()}m > ${INDOOR_ACCURACY_THRESHOLD.toInt()}m"
                    UnifiedLocationCache.reportSkipped(loc.latitude, loc.longitude, reason)
                    Log.d(TAG, "Indoor/cell location skipped: $reason")
                    return
                }

                // Distance filter: only save if moved > MIN_DISTANCE_METERS from last saved point
                val shouldSave = if (lastSavedLocation == null) {
                    true // Always save first point
                } else {
                    val distance = calculateDistance(
                        lastSavedLocation!!.latitude, lastSavedLocation!!.longitude,
                        loc.latitude, loc.longitude
                    )
                    distance >= MIN_DISTANCE_METERS
                }

                if (!shouldSave) {
                    consecutiveSkips++
                    val reason = "within ${MIN_DISTANCE_METERS.toInt()}m of last saved"
                    UnifiedLocationCache.reportSkipped(loc.latitude, loc.longitude, reason)
                    Log.d(TAG, "Skipped: ${loc.latitude}, ${loc.longitude} ($reason) [skip $consecutiveSkips]")
                    
                    // In active mode, consecutive skips indicate potential stationary behavior
                    if (currentGpsState == GpsState.ACTIVE && consecutiveSkips >= STATIONARY_SKIP_THRESHOLD) {
                        Log.d(TAG, "Stationary behavior detected, returning to deep idle")
                        enterDeepIdleMode()
                    }
                    return
                }

                // Movement detected - ensure we're in appropriate active state
                if (currentGpsState == GpsState.ACQUISITION) {
                    enterActiveMode()
                } else if (currentGpsState == GpsState.DEEP_IDLE) {
                    // Shouldn't happen in deep idle, but handle gracefully
                    enterAcquisitionMode()
                }
                consecutiveSkips = 0
            }

            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val altitudeM = if (loc.hasAltitude()) loc.altitude else 0.0
            val bearingDeg = if (loc.hasBearing()) loc.bearing else null
            val record = LocationRecord(
                latitude    = loc.latitude,
                longitude   = loc.longitude,
                timestampMs = loc.time,
                accuracyM   = if (loc.hasAccuracy()) loc.accuracy else 0f,
                speedKmh    = speedKmh,
                altitudeM   = altitudeM,
                bearingDeg  = bearingDeg
            )

            lastSavedLocation = loc
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId != null) {
                UnifiedLocationCache.addPoint(this@LocationForegroundService, userId, record)
            }
            throttledNotificationUpdate(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
            Log.d(TAG, "Saved: ${loc.latitude}, ${loc.longitude}  ±${loc.accuracy}m  ${speedKmh}km/h  alt=${altitudeM}m")
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) {
                Log.w(TAG, "GPS unavailable (location off or airplane mode)")
                notificationManager?.notify(NOTIFICATION_ID,
                    buildNotification("GPS unavailable", "Waiting for location…"))
            } else {
                Log.d(TAG, "GPS available again")
                notificationManager?.notify(NOTIFICATION_ID,
                    buildNotification("GPS active", ""))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing", "Starting GPS service"))
        
        // Initialize UnifiedLocationCache if user is signed in
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            UnifiedLocationCache.setCurrentUser(currentUser.uid)
            UnifiedLocationCache.initialise(this, currentUser.uid)
        }
        
        // Register GnssCallback for battery-efficient GPS detection
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssStatusCallback)
        
        // Initialize GPS state machine
        if (ExpressSyncManager.isExpressMode.value) {
            enterExpressMode()
        } else {
            enterDeepIdleMode()
        }
        
        TrackingStateHolder.setTracking(true)
        observeExpressMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        expressSyncJob?.cancel()
        serviceScope.cancel()
        TrackingStateHolder.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun observeExpressMode() {
        serviceScope.launch {
            ExpressSyncManager.isExpressMode.collect { isExpress ->
                // Express mode overrides all GPS states
                if (isExpress) {
                    isIdleMode = false
                    consecutiveSkips = 0
                    enterExpressMode()
                    startExpressSyncTimer()
                } else {
                    expressSyncJob?.cancel()
                    expressSyncJob = null
                    // Clear express status message when returning to normal mode
                    ExpressSyncManager.clearStatusMessage()
                    // Return to appropriate non-express state
                    if (currentGpsState == GpsState.EXPRESS) {
                        enterDeepIdleMode()
                    }
                }
            }
        }
    }

    
    private fun throttledNotificationUpdate(lat: Double, lon: Double, acc: Float, speedKmh: Float) {
        val isExpress = ExpressSyncManager.isExpressMode.value
        val now = System.currentTimeMillis()
        // Always update in express mode; throttle in normal mode
        if (isExpress || now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS) {
            lastNotificationUpdateMs = now
            updateNotification(lat, lon, acc, speedKmh)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    
    private fun startExpressSyncTimer() {
        expressSyncJob?.cancel()
        expressSyncJob = serviceScope.launch {
            Log.d(TAG, "Express sync timer started (60s interval)")
            while (true) {
                delay(60_000L)
                if (!ExpressSyncManager.checkExpiry(this@LocationForegroundService)) {
                    Log.d(TAG, "Express mode expired, stopping timer")
                    break
                }
                performSync()
            }
        }
    }

    private suspend fun performSync() {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "Express sync: no network — skipping upload, data stays cached")
            return
        }
        val records = UnifiedLocationCache.drainUnsavedPoints(this)
        if (records.isEmpty()) {
            Log.d(TAG, "Express sync: nothing to upload")
            return
        }
        Log.d(TAG, "Express sync: uploading ${records.size} records")
        LocationRepositoryImpl(this@LocationForegroundService).uploadBatch(records).fold(
            onSuccess = {
                Log.d(TAG, "Express sync: upload success")
                SyncPrefs.updateLastSync(this, success = true)
            },
            onFailure = { e ->
                Log.e(TAG, "Express sync: upload failed: ${e.message}")
                UnifiedLocationCache.requeueUnsavedPoints(this, records)
                SyncPrefs.updateLastSync(this, success = false)
            }
        )
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula.
     * @return Distance in meters.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    /** GPS State Machine Methods for Battery Optimization */
    
    private fun hasRecentMovement(): Boolean {
        // Check if we have recent movement data that justifies active tracking
        return lastSavedLocation != null
    }
    
    @SuppressLint("MissingPermission")
    private fun enterDeepIdleMode() {
        if (currentGpsState == GpsState.DEEP_IDLE) return
        
        Log.d(TAG, "Entering deep idle mode - GPS completely off")
        currentGpsState = GpsState.DEEP_IDLE
        GpsStateHolder.setGpsState(GpsStateHolder.GpsState.DEEP_IDLE, 0L)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        
        notificationManager?.notify(NOTIFICATION_ID,
            buildNotification("Deep Idle", "Battery saving - GPS off"))
    }
    
    @SuppressLint("MissingPermission")
    private fun enterAcquisitionMode() {
        if (currentGpsState == GpsState.ACQUISITION || currentGpsState == GpsState.EXPRESS) return
        
        Log.d(TAG, "Entering acquisition mode - fast GPS startup")
        currentGpsState = GpsState.ACQUISITION
        GpsStateHolder.setGpsState(GpsStateHolder.GpsState.ACQUISITION, ACQUISITION_INTERVAL_MS)
        acquisitionStartTime = System.currentTimeMillis()
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ACQUISITION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(ACQUISITION_INTERVAL_MS / 2)
            .setMaxUpdateDelayMillis(ACQUISITION_INTERVAL_MS * 2)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        
        notificationManager?.notify(NOTIFICATION_ID,
            buildNotification("Acquiring GPS", "Waiting for first fix"))
    }
    
    @SuppressLint("MissingPermission")
    private fun enterActiveMode() {
        if (currentGpsState == GpsState.ACTIVE || currentGpsState == GpsState.EXPRESS) return
        
        Log.d(TAG, "Entering active mode - normal GPS tracking")
        currentGpsState = GpsState.ACTIVE
        GpsStateHolder.setGpsState(GpsStateHolder.GpsState.ACTIVE, NORMAL_INTERVAL_MS)
        consecutiveSkips = 0
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, NORMAL_INTERVAL_MS)
            .setMinUpdateIntervalMillis(30_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        
        notificationManager?.notify(NOTIFICATION_ID,
            buildNotification("GPS Active", "Tracking movement"))
    }
    
    @SuppressLint("MissingPermission")
    private fun enterExpressMode() {
        if (currentGpsState == GpsState.EXPRESS) return
        
        Log.d(TAG, "Entering express mode - high frequency tracking")
        currentGpsState = GpsState.EXPRESS
        GpsStateHolder.setGpsState(GpsStateHolder.GpsState.EXPRESS, EXPRESS_INTERVAL_MS)
        consecutiveSkips = 0
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, EXPRESS_INTERVAL_MS)
            .setMinUpdateIntervalMillis(5_000L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        
        notificationManager?.notify(NOTIFICATION_ID,
            buildNotification("Express Mode", "High frequency tracking"))
    }

    
    private fun updateNotification(lat: Double, lon: Double, acc: Float, speedKmh: Float) {
        val coordText = "%.5f, %.5f".format(lat, lon)
        val accText   = "±%.0f m  %.1f km/h".format(acc, speedKmh)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(coordText, accText))
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Get cache stats for notification
        val cacheStats = UnifiedLocationCache.cacheStats.value
        val cacheInfo = "Cache: ${cacheStats.unsavedPointsCount} unsaved, ${cacheStats.totalCachedPoints} total"
        
        val text = if (subtitle.isNotEmpty()) "$title  $subtitle" else title
        val notificationTitle = "BkgTracker — $title"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$text\n$cacheInfo"))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active GPS tracking status"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
