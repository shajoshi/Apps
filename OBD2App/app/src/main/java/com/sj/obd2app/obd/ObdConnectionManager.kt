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
 * - Monitors OBD connection state only during RUNNING trips
 * - Pauses reconnection attempts when trip is PAUSED
 * - Resumes reconnection attempts when trip resumes
 * - Adaptive backoff reconnection: 5 attempts at 10s intervals, then 60s intervals
 * - Only starts monitoring if OBD was connected before or during the trip
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
    private var isPaused = false
    private var hadConnectionBeforeTrip = false
    
    /**
     * Start monitoring OBD connection state during an active trip.
     * Only starts if OBD was connected before or during the trip.
     * Called when trip starts (RUNNING phase).
     *
     * @param obdWasConnected true if OBD was connected when the trip started
     */
    fun startMonitoring(obdWasConnected: Boolean = false) {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring, ignoring duplicate start")
            return
        }
        
        hadConnectionBeforeTrip = obdWasConnected
        if (!hadConnectionBeforeTrip) {
            Log.d(TAG, "OBD was not connected at trip start — skipping monitoring")
            return
        }
        
        isMonitoring = true
        isPaused = false
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
        isPaused = false
        hadConnectionBeforeTrip = false
        monitoringJob?.cancel()
        monitoringJob = null
        reconnectionJob?.cancel()
        reconnectionJob = null
        attemptCount = 0
        manualDisconnect = false
    }
    
    /**
     * Pause reconnection attempts during trip pause.
     * Monitoring stays active but reconnection loop is suspended.
     */
    fun pauseMonitoring() {
        if (!isMonitoring || isPaused) return
        
        isPaused = true
        reconnectionJob?.cancel()
        reconnectionJob = null
        Log.d(TAG, "Paused reconnection attempts (trip paused)")
    }
    
    /**
     * Resume reconnection attempts when trip resumes.
     * If OBD is currently disconnected, restarts the reconnection loop.
     */
    fun resumeMonitoring() {
        if (!isMonitoring || !isPaused) return
        
        isPaused = false
        Log.d(TAG, "Resumed reconnection monitoring (trip resumed)")
        
        // If currently disconnected, restart reconnection loop
        val currentState = obdService.connectionState.value
        if (currentState == Obd2Service.ConnectionState.DISCONNECTED ||
            currentState == Obd2Service.ConnectionState.ERROR) {
            if (!manualDisconnect && reconnectionJob == null) {
                Log.d(TAG, "OBD still disconnected after resume — restarting reconnection")
                startReconnectionLoop()
            }
        }
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
                    // Only auto-reconnect during RUNNING phase (not PAUSED)
                    if (!manualDisconnect && !isPaused && reconnectionJob == null) {
                        Log.d(TAG, "OBD disconnected during running trip, starting reconnection")
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
     * One-shot auto-connect attempt at trip start.
     * If auto-connect setting is ON and OBD is not connected, tries to connect
     * to the last known device. Shows Toast warning if connection is not possible.
     * Returns true if OBD is already connected or a connect attempt was initiated.
     */
    @SuppressLint("MissingPermission")
    fun tryConnectForTripStart(): Boolean {
        val currentState = obdService.connectionState.value
        if (currentState == Obd2Service.ConnectionState.CONNECTED) return true
        if (currentState == Obd2Service.ConnectionState.CONNECTING) return true

        if (!AppSettings.isAutoConnect(context)) {
            Log.d(TAG, "Auto-connect disabled — skipping trip-start connect")
            showToast("No OBD connected — recording GPS/Accel only")
            return false
        }

        val deviceMac = AppSettings.getLastDeviceMac(context)
        if (deviceMac.isNullOrEmpty()) {
            Log.d(TAG, "No last device MAC — cannot auto-connect at trip start")
            showToast("No OBD connected — recording GPS/Accel only")
            return false
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth unavailable at trip start")
            showToast("Bluetooth unavailable — recording GPS/Accel only")
            return false
        }

        return try {
            val device = btAdapter.getRemoteDevice(deviceMac)
            Log.d(TAG, "Trip start: auto-connecting to ${device.name ?: deviceMac}")
            scope.launch(Dispatchers.Main) { obdService.connect(device) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Trip start auto-connect failed: ${e.message}")
            showToast("No OBD connected — recording GPS/Accel only")
            false
        }
    }

    private fun showToast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Mark the next disconnect as manual (user-initiated).
     * This prevents auto-reconnection.
     */
    fun markManualDisconnect() {
        manualDisconnect = true
        Log.d(TAG, "Marked as manual disconnect - auto-reconnect disabled")
    }
    
    /**
     * Handle Bluetooth bond loss by properly disconnecting and clearing device.
     * Called when a device bond is lost (user tapped "Forget Device").
     */
    fun onBondLost() {
        Log.w(TAG, "Bluetooth bond lost - forcing disconnect and clearing device")
        
        // Stop any reconnection attempts
        reconnectionJob?.cancel()
        reconnectionJob = null
        
        // Force disconnect from OBD service
        obdService.disconnect()
        
        // Clear the last known device to prevent auto-reconnection
        lastKnownDeviceMac = null
        AppSettings.setLastDevice(context, "", null)
        
        // Stop monitoring since bond is lost
        stopMonitoring()
        
        // Mark as manual disconnect to prevent auto-reconnection
        manualDisconnect = true
        
        Log.i(TAG, "Bond loss handled - device cleared, user must manually reconnect")
    }
}
