package com.sj.obd2app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/**
 * Manages OBD connection monitoring and automatic reconnection during active trips.
 * 
 * Features:
 * - Monitors OBD connection state only during RUNNING or PAUSED trips
 * - Adaptive backoff reconnection: 5 attempts at 10s intervals, then 60s intervals
 * - Resets attempt counter on successful reconnection
 * - Notifies user of connection status changes
 */
class ObdConnectionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ObdConnectionManager"
        
        private const val FAST_RETRY_INTERVAL_MS = 10_000L  // 10 seconds
        private const val SLOW_RETRY_INTERVAL_MS = 60_000L  // 60 seconds
        private const val FAST_RETRY_COUNT = 5  // First 5 attempts at 10s
        
        @Volatile
        private var instance: ObdConnectionManager? = null
        
        fun getInstance(context: Context): ObdConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ObdConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val metricsCalculator = MetricsCalculator.getInstance(context)
    private val obdService = Obd2ServiceProvider.getService()
    // Bluetooth connection logger removed - now using logcat only
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var monitoringJob: Job? = null
    private var reconnectionJob: Job? = null
    private var lastKnownDeviceMac: String? = null
    private var attemptCount = 0
    private var manualDisconnect = false
    private var isMonitoring = false
    
    /**
     * Start monitoring OBD connection state during an active trip.
     * Called when trip starts (RUNNING phase).
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring, ignoring duplicate start")
            return
        }
        
        isMonitoring = true
        attemptCount = 0
        manualDisconnect = false
        lastKnownDeviceMac = AppSettings.getLastDeviceMac(context)
        
        Log.d(TAG, "Starting OBD connection monitoring for trip")
        
        monitoringJob = scope.launch {
            observeConnectionState()
        }
    }
    
    /**
     * Stop monitoring OBD connection state.
     * Called when trip stops (IDLE phase).
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        Log.d(TAG, "Stopping OBD connection monitoring")
        
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        reconnectionJob?.cancel()
        reconnectionJob = null
        attemptCount = 0
        manualDisconnect = false
    }
    
    /**
     * Observe OBD connection state and trip phase.
     * Triggers reconnection when disconnected during active trip.
     */
    private suspend fun observeConnectionState() {
        combine(
            obdService.connectionState,
            metricsCalculator.tripPhase
        ) { connectionState, tripPhase ->
            Pair(connectionState, tripPhase)
        }.collect { (connectionState, tripPhase) ->
            
            // Stop monitoring if trip is no longer active
            if (tripPhase == TripPhase.IDLE) {
                Log.d(TAG, "Trip ended, stopping monitoring")
                stopMonitoring()
                return@collect
            }
            
            // Handle connection state changes during active trip
            when (connectionState) {
                Obd2Service.ConnectionState.CONNECTED -> {
                    // Connection restored - reset attempt counter
                    if (attemptCount > 0) {
                        Log.d(TAG, "OBD reconnected successfully after $attemptCount attempts")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "OBD reconnected", Toast.LENGTH_SHORT).show()
                        }
                        resetAttemptCounter()
                    }
                    // Cancel any ongoing reconnection attempts
                    reconnectionJob?.cancel()
                    reconnectionJob = null
                }
                
                Obd2Service.ConnectionState.DISCONNECTED,
                Obd2Service.ConnectionState.ERROR -> {
                    // Only auto-reconnect if not a manual disconnect
                    if (!manualDisconnect && reconnectionJob == null) {
                        Log.d(TAG, "OBD disconnected during trip, starting reconnection attempts")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context, 
                                "OBD disconnected - attempting reconnection...", 
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        startReconnectionLoop()
                    }
                }
                
                Obd2Service.ConnectionState.CONNECTING -> {
                    // Connection attempt in progress, do nothing
                }
            }
        }
    }
    
    /**
     * Start the reconnection loop with adaptive backoff.
     */
    private fun startReconnectionLoop() {
        reconnectionJob?.cancel()
        reconnectionJob = scope.launch {
            while (isActive && isMonitoring) {
                attemptCount++
                val interval = getRetryInterval()
                
                Log.d(TAG, "Reconnection attempt #$attemptCount (next retry in ${interval/1000}s)")
                
                // Attempt reconnection
                attemptReconnection()
                
                // Wait for the appropriate interval before next attempt
                delay(interval)
            }
        }
    }
    
    /**
     * Attempt to reconnect to the last known OBD device.
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptReconnection() {
        val deviceMac = lastKnownDeviceMac
        if (deviceMac.isNullOrEmpty()) {
            Log.w(TAG, "No last known device MAC, cannot reconnect")
            return
        }
        
        // Check if Bluetooth is enabled
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled, cannot reconnect")
            return
        }
        
        // Check if already connected or connecting
        val currentState = obdService.connectionState.value
        if (currentState == Obd2Service.ConnectionState.CONNECTED ||
            currentState == Obd2Service.ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connected/connecting, skipping reconnection attempt")
            return
        }
        
        try {
            // Get the Bluetooth device
            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceMac)
            Log.d(TAG, "Attempting to reconnect to ${device.name ?: deviceMac}")
            
            // Trigger connection
            withContext(Dispatchers.Main) {
                obdService.connect(device)
            }
            
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid MAC address: $deviceMac", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Reconnection attempt failed", e)
        }
    }
    
    /**
     * Get the retry interval based on attempt count.
     * First 5 attempts: 10 seconds
     * After 5 attempts: 60 seconds
     */
    private fun getRetryInterval(): Long {
        return if (attemptCount < FAST_RETRY_COUNT) {
            FAST_RETRY_INTERVAL_MS
        } else {
            SLOW_RETRY_INTERVAL_MS
        }
    }
    
    /**
     * Reset the attempt counter after successful reconnection.
     */
    private fun resetAttemptCounter() {
        Log.d(TAG, "Resetting attempt counter (was $attemptCount)")
        attemptCount = 0
    }
    
    /**
     * Mark the next disconnect as manual (user-initiated).
     * This prevents auto-reconnection.
     */
    fun markManualDisconnect() {
        manualDisconnect = true
        Log.d(TAG, "Marked as manual disconnect - auto-reconnect disabled")
    }
}
