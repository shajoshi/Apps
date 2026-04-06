package com.sj.obd2app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sj.obd2app.MainActivity
import com.sj.obd2app.R
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the app alive during trips
 * and shows persistent notification with live trip data.
 */
class TripForegroundService : Service() {

    companion object {
        private const val TAG = "TripForegroundService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "trip_tracking"

        fun start(context: Context) {
            val intent = Intent(context, TripForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var calculator: MetricsCalculator
    private var notificationManager: NotificationManager? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        calculator = MetricsCalculator.getInstance(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        ensureForeground("Starting trip...", "00:00", "0.0 km")
        observeTripState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY // Service will be restarted if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Trip Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active trip status while app is in background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun observeTripState() {
        // Combine trip phase, metrics, and OBD connection state for live updates
        serviceScope.launch {
            combine(
                calculator.tripPhase,
                calculator.metrics,
                Obd2ServiceProvider.getService().connectionState
            ) { phase, metrics, obdState ->
                updateNotification(phase, metrics, obdState)
            }.collect { /* just collect, updateNotification handles it */ }
        }
    }

    private fun ensureForeground(status: String, duration: String, distance: String, obdStatus: String = "") {
        val notification = createNotification(status, duration, distance, obdStatus)
        if (!isForeground) {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        } else {
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        phase: TripPhase,
        metrics: com.sj.obd2app.metrics.VehicleMetrics,
        obdState: Obd2Service.ConnectionState
    ) {
        if (phase == TripPhase.IDLE) {
            Log.d(TAG, "Trip ended, stopping foreground service")
            if (isForeground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
            }
            stopSelf()
            return
        }

        val statusText = when (phase) {
            TripPhase.RUNNING -> "Trip in progress"
            TripPhase.IDLE    -> "Trip stopped"
        }

        val obdStatusText = when (obdState) {
            Obd2Service.ConnectionState.CONNECTED -> ""
            Obd2Service.ConnectionState.CONNECTING -> "OBD Connecting..."
            Obd2Service.ConnectionState.DISCONNECTED -> "OBD Disconnected - Reconnecting..."
            Obd2Service.ConnectionState.ERROR -> "OBD Disconnected - Reconnecting..."
        }

        val duration = formatDuration(calculator.elapsedTripSec())
        val distance = "%.1f km".format(metrics.tripDistanceKm)

        Log.d(TAG, "Notification update: $statusText, $duration, $distance, OBD: $obdStatusText")
        ensureForeground(statusText, duration, distance, obdStatusText)
    }

    private fun createNotification(status: String, duration: String, distance: String, obdStatus: String = ""): Notification {
        // Intent to open app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (obdStatus.isNotEmpty()) {
            "$status • $duration • $distance\n$obdStatus"
        } else {
            "$status • $duration • $distance"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD2 Trip Tracker")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be swiped away
            .setSilent(true) // No sound/vibration
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}
