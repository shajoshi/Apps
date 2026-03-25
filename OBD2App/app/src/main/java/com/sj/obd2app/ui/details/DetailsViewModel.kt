package com.sj.obd2app.ui.details

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.LastTripSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel for the Details screen.
 * Collects OBD-II data from the active service and exposes it as StateFlow.
 */
class DetailsViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val service = Obd2ServiceProvider.getService()
    private val calculator = MetricsCalculator.getInstance(application.applicationContext)

    private val _obd2Data = MutableStateFlow<List<Obd2DataItem>>(emptyList())
    val obd2Data: StateFlow<List<Obd2DataItem>> = _obd2Data

    private val _vehicleMetrics = MutableStateFlow<VehicleMetrics?>(null)
    val vehicleMetrics: StateFlow<VehicleMetrics?> = _vehicleMetrics

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // Cached data for offline viewing
    private val _cachedPids = MutableStateFlow<Map<String, String>>(emptyMap())
    val cachedPids: StateFlow<Map<String, String>> = _cachedPids

    private val _lastTripSnapshot = MutableStateFlow<LastTripSnapshot?>(null)
    val lastTripSnapshot: StateFlow<LastTripSnapshot?> = _lastTripSnapshot


    init {
        viewModelScope.launch {
            service.obd2Data.collect { data ->
                _obd2Data.value = data
            }
        }
        viewModelScope.launch {
            calculator.metrics.collect { metrics ->
                _vehicleMetrics.value = metrics
            }
        }
        // Observe OBD service connection state and update ObdStateManager
        viewModelScope.launch {
            combine(
                service.connectionState,
                service.connectedDeviceName
            ) { state, deviceName -> state to deviceName }
            .collect { (state, deviceName) ->
                // Update centralized state manager
                val obdState = when (state) {
                    Obd2Service.ConnectionState.CONNECTED -> ObdStateManager.ConnectionState.CONNECTED
                    Obd2Service.ConnectionState.CONNECTING -> ObdStateManager.ConnectionState.CONNECTING
                    Obd2Service.ConnectionState.ERROR -> ObdStateManager.ConnectionState.ERROR
                    else -> ObdStateManager.ConnectionState.DISCONNECTED
                }
                ObdStateManager.updateConnectionState(obdState, deviceName)
            }
        }
        
        // Observe centralized state for UI updates
        viewModelScope.launch {
            combine(
                ObdStateManager.connectionState,
                ObdStateManager.mode
            ) { connState, mode -> connState to mode }
            .collect { (connState, _) ->
                _isConnected.value = connState == ObdStateManager.ConnectionState.CONNECTED
                _connectionStatus.value = ObdStateManager.getConnectionStatusText()
                
                // Load cached data when disconnected
                if (!_isConnected.value) {
                    loadCachedData()
                }
            }
        }
        
        // Load initial cached data
        loadCachedData()
    }
    
    private fun loadCachedData() {
        viewModelScope.launch {
            try {
                val context = getApplication<android.app.Application>()
                
                // Load cached PIDs for last connected device
                val lastMac = AppSettings.getLastDeviceMac(context)
                if (!lastMac.isNullOrEmpty()) {
                    val cachedPids = AppSettings.getPidCache(context, lastMac) ?: emptyMap()
                    _cachedPids.value = cachedPids
                }
                
                // Load last trip snapshot
                val lastTrip = AppSettings.getLastTripSnapshot(context)
                _lastTripSnapshot.value = lastTrip
                
            } catch (e: Exception) {
                // Handle errors gracefully
                _cachedPids.value = emptyMap()
                _lastTripSnapshot.value = null
            }
        }
    }
}
