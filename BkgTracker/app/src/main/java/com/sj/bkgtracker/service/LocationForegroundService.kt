package com.sj.bkgtracker.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
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
import com.sj.bkgtracker.data.local.ActivityStateHolder
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.GpsStateHolder
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.receiver.ActivityTransitionReceiver
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
        private const val ACTION_ACTIVITY_WAKE = "com.sj.bkgtracker.ACTION_ACTIVITY_WAKE"
        private const val ACTION_ACTIVITY_END = "com.sj.bkgtracker.ACTION_ACTIVITY_END"
        private const val EXTRA_ACTIVITY_TYPE = "extra_activity_type"
        private const val ACTIVITY_WAKE_REQUEST_CODE = 2001

        /** Minimum distance (meters) from last saved point to save a new point */
        private const val MIN_DISTANCE_METERS = 5.0

        /** Accuracy threshold to detect indoor/cell locations vs real GPS (meters).
         *  Tightened to 15m for all modes to reject WiFi/cell assists that place points
         *  off the road. Real GPS in open sky is typically 3-10m. */
        private const val INDOOR_ACCURACY_THRESHOLD = 15f

        /** Express mode uses a slightly looser accuracy threshold so high-frequency
         *  fixes are not discarded, while still rejecting cell-tower fallbacks. */
        private const val EXPRESS_INDOOR_ACCURACY_THRESHOLD = 35f


        /** Normal mode: GPS fix every 10 seconds */
        private const val NORMAL_INTERVAL_MS = 10_000L
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

        fun startForActivityWake(context: Context, activityType: Int = -1) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_ACTIVITY_WAKE
                putExtra(EXTRA_ACTIVITY_TYPE, activityType)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun notifyActivityEnded(context: Context, activityType: Int = -1) {
            val intent = Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_ACTIVITY_END
                putExtra(EXTRA_ACTIVITY_TYPE, activityType)
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient
    private var notificationManager: NotificationManager? = null
    private var lastSavedLocation: android.location.Location? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var expressSyncJob: Job? = null
    private var acquisitionTimeoutJob: Job? = null
    private var activityTransitionsRegistered = false

    /** GPS State Machine tracking */
    private var currentGpsState = GpsState.DEEP_IDLE
    private var acquisitionStartTime = 0L
    
    /** Consecutive distance-filter skips for stationary detection */
    private var consecutiveSkips = 0
    private var isIdleMode = false
    
    /** Whether we are currently in an activity (walking/running/driving) */
    private var isInActivity = false

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
            Log.d(TAG, "GPS engine stopped (normal between fixes)")
            // Don't enter deep idle here - let distance filter or timeout handle it
            // onStopped fires between every location fix when FLP pauses GNSS
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

            // Accuracy filter always applies, but Express mode uses a looser threshold
            // so high-frequency capture is not blocked by marginal fixes.
            val accuracy = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
            val accuracyThreshold = if (isExpress) EXPRESS_INDOOR_ACCURACY_THRESHOLD else INDOOR_ACCURACY_THRESHOLD
            if (accuracy > accuracyThreshold) {
                val reason = "poor accuracy ${accuracy.toInt()}m > ${accuracyThreshold.toInt()}m"
                UnifiedLocationCache.reportSkipped(loc.latitude, loc.longitude, reason)
                Log.d(TAG, "Cell/indoor location skipped: $reason")
                return
            }

            // In express mode, bypass only the distance filter (save every fix)
            if (!isExpress) {
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
                    // But do NOT go to deep idle if we're in an activity - stay in GPS mode
                    if (currentGpsState == GpsState.ACTIVE && consecutiveSkips >= STATIONARY_SKIP_THRESHOLD && !isInActivity) {
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
            // Don't update notification here - rely on state machine (enter*Mode functions) instead
            // This prevents notification from showing incorrect state (e.g., "GPS active" when in DEEP_IDLE)
            if (!availability.isLocationAvailable) {
                Log.w(TAG, "GPS unavailable (location off or airplane mode)")
            } else {
                Log.d(TAG, "GPS available")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)
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
        
        // Always register activity transitions at service start
        registerActivityTransitions()
        
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
        if (intent?.action == ACTION_ACTIVITY_WAKE) {
            val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
            val activityName = ActivityStateHolder.activityName(activityType)
            
            if (activityType == DetectedActivity.STILL) {
                // STILL activity detected - user is stationary, go to deep idle
                Log.d(TAG, "STILL activity detected - going to deep idle")
                isInActivity = false
                ActivityStateHolder.setStillActivity()
                if (currentGpsState != GpsState.EXPRESS) {
                    enterDeepIdleMode()
                }
            } else {
                // Movement activity detected (walking, driving, etc.)
                Log.d(TAG, "Activity started: $activityName")
                isInActivity = true
                ActivityStateHolder.setActivityStarted(activityType)
                notificationManager?.notify(NOTIFICATION_ID,
                    buildNotification("Active: $activityName", "GPS tracking"))
                if (currentGpsState == GpsState.DEEP_IDLE && !ExpressSyncManager.isExpressMode.value) {
                    enterAcquisitionMode()
                }
            }
        } else if (intent?.action == ACTION_ACTIVITY_END) {
            val activityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
            val activityName = ActivityStateHolder.activityName(activityType)
            Log.d(TAG, "Activity ended: $activityName")
            isInActivity = false
            ActivityStateHolder.setActivityEnded(activityType)
            if (currentGpsState != GpsState.EXPRESS) {
                enterDeepIdleMode()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        unregisterActivityTransitions()
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
        expressSyncJob?.cancel()
        acquisitionTimeoutJob?.cancel()
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

    private fun activityTransitionPendingIntent(): PendingIntent {
        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            this,
            ACTIVITY_WAKE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityTransitions() {
        Log.d(TAG, "registerActivityTransitions() called. alreadyRegistered=$activityTransitionsRegistered")
        if (activityTransitionsRegistered) return
        
        val hasPermission = hasActivityRecognitionPermission()
        Log.d(TAG, "Activity recognition permission granted: $hasPermission")
        if (!hasPermission) {
            Log.w(TAG, "Activity recognition permission missing; idle wake transitions not registered")
            android.widget.Toast.makeText(this, "[DEBUG] Activity perm MISSING", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val activityTypes = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL
        )
        val transitions = activityTypes.flatMap { activityType ->
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build()
            )
        }

        Log.d(TAG, "Requesting activity transition updates with ${transitions.size} transitions")
        activityRecognitionClient
            .requestActivityTransitionUpdates(
                ActivityTransitionRequest(transitions),
                activityTransitionPendingIntent()
            )
            .addOnSuccessListener {
                activityTransitionsRegistered = true
                Log.d(TAG, "Activity transitions REGISTERED successfully")
                android.widget.Toast.makeText(this, "[DEBUG] Activity transitions REGISTERED OK", android.widget.Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "FAILED to register activity transitions: ${e.message}", e)
                android.widget.Toast.makeText(this, "[DEBUG] Activity reg FAILED: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
    }

    private fun unregisterActivityTransitions() {
        if (!activityTransitionsRegistered) return
        if (!hasActivityRecognitionPermission()) {
            activityTransitionsRegistered = false
            return
        }

        activityRecognitionClient
            .removeActivityTransitionUpdates(activityTransitionPendingIntent())
            .addOnSuccessListener {
                activityTransitionsRegistered = false
                Log.d(TAG, "Activity transitions unregistered")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to unregister activity transitions: ${e.message}", e)
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
        
        // Start acquisition timeout - return to deep idle if no movement detected
        acquisitionTimeoutJob?.cancel()
        acquisitionTimeoutJob = serviceScope.launch {
            delay(ACQUISITION_TIMEOUT_MS)
            if (currentGpsState == GpsState.ACQUISITION) {
                if (isInActivity) {
                    // Activity still ongoing - move to active mode instead of deep idle
                    Log.d(TAG, "Acquisition timeout but activity ongoing - entering active mode")
                    enterActiveMode()
                } else {
                    Log.d(TAG, "Acquisition timeout - no movement detected, returning to deep idle")
                    enterDeepIdleMode()
                }
            }
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun enterActiveMode() {
        if (currentGpsState == GpsState.ACTIVE || currentGpsState == GpsState.EXPRESS) return
        
        Log.d(TAG, "Entering active mode - normal GPS tracking")
        currentGpsState = GpsState.ACTIVE
        GpsStateHolder.setGpsState(GpsStateHolder.GpsState.ACTIVE, NORMAL_INTERVAL_MS)
        consecutiveSkips = 0
        
        // Cancel acquisition timeout - we detected movement
        acquisitionTimeoutJob?.cancel()
        acquisitionTimeoutJob = null
        
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, NORMAL_INTERVAL_MS)
            .setMinUpdateIntervalMillis(5_000L)
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
