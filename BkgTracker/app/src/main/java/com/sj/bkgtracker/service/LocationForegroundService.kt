package com.sj.bkgtracker.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sj.bkgtracker.R
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
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
        private const val TAG = "LocationFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bkg_tracker_location"

        /** Minimum distance (meters) from last saved point to save a new point */
        private const val MIN_DISTANCE_METERS = 20.0

        /** Accuracy threshold to detect indoor/cell locations vs real GPS (meters) */
        private const val INDOOR_ACCURACY_THRESHOLD = 100f

        /** Normal mode: GPS fix every 60 seconds */
        private const val NORMAL_INTERVAL_MS = 60_000L
        /** Express mode: GPS fix every 10 seconds */
        private const val EXPRESS_INTERVAL_MS = 10_000L

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
                    LocationCache.reportSkipped(loc.latitude, loc.longitude, reason)
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
                    val reason = "within ${MIN_DISTANCE_METERS.toInt()}m of last saved"
                    LocationCache.reportSkipped(loc.latitude, loc.longitude, reason)
                    Log.d(TAG, "Skipped: ${loc.latitude}, ${loc.longitude} ($reason)")
                    return
                }
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
            LocationCache.add(this@LocationForegroundService, record)
            updateNotification(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
            Log.d(TAG, "Saved: ${loc.latitude}, ${loc.longitude}  ±${loc.accuracy}m  ${speedKmh}km/h  alt=${altitudeM}m  cache=${LocationCache.cacheSize.value}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Acquiring GPS fix…", ""))
        startLocationUpdates()
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
        expressSyncJob?.cancel()
        serviceScope.cancel()
        TrackingStateHolder.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun observeExpressMode() {
        serviceScope.launch {
            ExpressSyncManager.isExpressMode.collect { isExpress ->
                // Switch GPS interval based on mode
                switchLocationInterval(isExpress)
                if (isExpress) {
                    startExpressSyncTimer()
                } else {
                    expressSyncJob?.cancel()
                    expressSyncJob = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun switchLocationInterval(express: Boolean) {
        val interval = if (express) EXPRESS_INTERVAL_MS else NORMAL_INTERVAL_MS
        val minInterval = if (express) 5_000L else 30_000L
        Log.d(TAG, "Switching GPS interval to ${interval / 1000}s (express=$express)")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
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
        val records = LocationCache.drainAll(this)
        if (records.isEmpty()) {
            Log.d(TAG, "Express sync: nothing to upload")
            return
        }
        Log.d(TAG, "Express sync: uploading ${records.size} records")
        LocationRepositoryImpl().uploadBatch(records).fold(
            onSuccess = {
                Log.d(TAG, "Express sync: upload success")
                SyncPrefs.updateLastSync(this, success = true)
            },
            onFailure = { e ->
                Log.e(TAG, "Express sync: upload failed: ${e.message}")
                LocationCache.requeue(this, records)
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val interval = if (ExpressSyncManager.isExpressMode.value) EXPRESS_INTERVAL_MS else NORMAL_INTERVAL_MS
        val minInterval = if (ExpressSyncManager.isExpressMode.value) 5_000L else 30_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
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
        val text = if (subtitle.isNotEmpty()) "$title  $subtitle" else title
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BkgTracker — GPS Active")
            .setContentText(text)
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
