package com.tpmsapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.tpmsapp.R
import com.tpmsapp.ble.BleScanner
import com.tpmsapp.ble.RawAdvertisement
import com.tpmsapp.data.SensorRepository
import com.tpmsapp.model.TyreData
import com.tpmsapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class TpmsScanService : Service() {

    companion object {
        private const val TAG = "TpmsScanService"
        const val CHANNEL_ID = "tpms_scan_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.tpmsapp.action.START_SCAN"
        const val ACTION_STOP  = "com.tpmsapp.action.STOP_SCAN"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bleScanner: BleScanner
    private lateinit var sensorRepository: SensorRepository

    inner class LocalBinder : Binder() {
        fun getService(): TpmsScanService = this@TpmsScanService
    }

    private val binder = LocalBinder()

    val tyreDataFlow: SharedFlow<TyreData> get() = bleScanner.tyreDataFlow
    val rawAdvertisementFlow: SharedFlow<RawAdvertisement> get() = bleScanner.rawAdvertisementFlow

    val isScanning: Boolean get() = bleScanner.isScanning

    override fun onCreate() {
        super.onCreate()
        bleScanner = BleScanner(applicationContext)
        sensorRepository = SensorRepository(applicationContext)
        createNotificationChannel()
        refreshKnownSensors()
        refreshParsePreference()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                bleScanner.stopScan()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                Log.d(TAG, "Service started — waiting for user to initiate scan")
            }
        }
        return START_STICKY
    }

    fun startScan() {
        refreshParsePreference()
        bleScanner.startScan()
        Log.d(TAG, "Scan started by user")
    }

    fun stopScan() {
        bleScanner.stopScan()
        Log.d(TAG, "Scan stopped by user")
    }

    fun refreshParsePreference() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        bleScanner.parseKnownSensorsOnly = prefs.getBoolean("parse_known_sensors_only", false)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        bleScanner.stopScan()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    fun refreshKnownSensors() {
        serviceScope.launch {
            val sensors = sensorRepository.loadSensorConfigs()
            bleScanner.updateKnownSensors(sensors)
        }
    }

    val isBluetoothAvailable: Boolean get() = bleScanner.isBluetoothAvailable
    val isBluetoothEnabled: Boolean get() = bleScanner.isBluetoothEnabled
    val isScanActive: Boolean get() = bleScanner.isScanning

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TPMS Scanning",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing BLE scan for tyre pressure sensors"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, TpmsScanService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TPMS Active")
            .setContentText("Scanning for tyre pressure sensors…")
            .setSmallIcon(R.drawable.ic_tyre_notification)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
