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
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.ui.MainActivity

class LocationForegroundService : Service() {

    companion object {
        private const val TAG = "LocationFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bkg_tracker_location"

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

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
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
            LocationCache.add(this@LocationForegroundService, record)
            updateNotification(loc.latitude, loc.longitude, loc.accuracy, speedKmh)
            Log.d(TAG, "Fix: ${loc.latitude}, ${loc.longitude}  ±${loc.accuracy}m  ${speedKmh}km/h  alt=${altitudeM}m  cache=${LocationCache.cacheSize.value}")
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
        TrackingStateHolder.setTracking(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
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
