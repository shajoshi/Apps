package com.sj.obd2app.ui.details

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
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
        viewModelScope.launch {
            combine(
                service.connectionState,
                service.connectedDeviceName
            ) { state, deviceName -> state to deviceName }
            .collect { (state, deviceName) ->
                val isMock = !AppSettings.isObdConnectionEnabled(getApplication()) ||
                    Obd2ServiceProvider.useMock
                _isConnected.value = state == Obd2Service.ConnectionState.CONNECTED
                _connectionStatus.value = when {
                    isMock -> "Simulation Mode"
                    state == Obd2Service.ConnectionState.CONNECTED ->
                        if (deviceName != null) "Connected \u00B7 $deviceName" else "Connected"
                    state == Obd2Service.ConnectionState.CONNECTING -> "Connecting\u2026"
                    state == Obd2Service.ConnectionState.ERROR -> "Error"
                    else -> "Disconnected"
                }
            }
        }
    }
}
