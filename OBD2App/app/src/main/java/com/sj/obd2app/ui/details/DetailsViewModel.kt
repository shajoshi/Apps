package com.sj.obd2app.ui.details

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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

    private var lastNonEmptyObd2Data: List<Obd2DataItem> = emptyList()
    private var lastNonNullMetrics: VehicleMetrics? = null

    init {
        viewModelScope.launch {
            service.obd2Data.collect { data ->
                if (data.isNotEmpty()) {
                    lastNonEmptyObd2Data = data
                    _obd2Data.value = data
                } else if (lastNonEmptyObd2Data.isNotEmpty()) {
                    _obd2Data.value = lastNonEmptyObd2Data
                }
            }
        }
        viewModelScope.launch {
            calculator.metrics.collect { metrics ->
                val hasData = metrics.rpm != null || metrics.vehicleSpeedKmh != null ||
                    metrics.coolantTempC != null || metrics.gpsLatitude != null ||
                    metrics.tripDistanceKm > 0f || metrics.tripFuelUsedL > 0f
                if (hasData) {
                    lastNonNullMetrics = metrics
                    _vehicleMetrics.value = metrics
                } else if (lastNonNullMetrics != null) {
                    _vehicleMetrics.value = lastNonNullMetrics
                } else {
                    _vehicleMetrics.value = metrics
                }
            }
        }
        viewModelScope.launch {
            service.connectionState.collect { state ->
                _isConnected.value = state == Obd2Service.ConnectionState.CONNECTED
                _connectionStatus.value = when (state) {
                    Obd2Service.ConnectionState.DISCONNECTED -> "Disconnected"
                    Obd2Service.ConnectionState.CONNECTING -> "Connecting…"
                    Obd2Service.ConnectionState.CONNECTED -> "Connected"
                    Obd2Service.ConnectionState.ERROR -> "Error"
                }
            }
        }
    }
}
