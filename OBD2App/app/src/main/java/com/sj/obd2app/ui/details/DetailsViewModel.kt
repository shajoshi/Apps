package com.sj.obd2app.ui.details

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import kotlinx.coroutines.launch

/**
 * ViewModel for the Details screen.
 * Collects OBD-II data from the active service and exposes it as LiveData.
 */
class DetailsViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val service = Obd2ServiceProvider.getService()
    private val calculator = MetricsCalculator.getInstance(application.applicationContext)

    private val _obd2Data = MutableLiveData<List<Obd2DataItem>>(emptyList())
    val obd2Data: LiveData<List<Obd2DataItem>> = _obd2Data

    private val _vehicleMetrics = MutableLiveData<VehicleMetrics>()
    val vehicleMetrics: LiveData<VehicleMetrics> = _vehicleMetrics

    private val _connectionStatus = MutableLiveData<String>("Disconnected")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    init {
        viewModelScope.launch {
            service.obd2Data.collect { data ->
                _obd2Data.postValue(data)
            }
        }
        viewModelScope.launch {
            calculator.metrics.collect { metrics ->
                _vehicleMetrics.postValue(metrics)
            }
        }
        viewModelScope.launch {
            service.connectionState.collect { state ->
                _isConnected.postValue(state == Obd2Service.ConnectionState.CONNECTED)
                _connectionStatus.postValue(
                    when (state) {
                        Obd2Service.ConnectionState.DISCONNECTED -> "Disconnected"
                        Obd2Service.ConnectionState.CONNECTING -> "Connecting…"
                        Obd2Service.ConnectionState.CONNECTED -> "Connected"
                        Obd2Service.ConnectionState.ERROR -> "Error"
                    }
                )
            }
        }
    }
}
