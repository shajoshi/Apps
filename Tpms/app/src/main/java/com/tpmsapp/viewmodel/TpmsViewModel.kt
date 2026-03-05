package com.tpmsapp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tpmsapp.ble.RawAdvertisement
import com.tpmsapp.data.SensorRepository
import com.tpmsapp.model.SensorConfig
import com.tpmsapp.model.TyreData
import com.tpmsapp.model.TyrePosition
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class TpmsViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorRepository = SensorRepository(application)

    private val _tyrePressures = MutableLiveData<Map<TyrePosition, TyreData>>(emptyMap())
    val tyrePressures: LiveData<Map<TyrePosition, TyreData>> = _tyrePressures

    private val _discoveredDevices = MutableLiveData<List<RawAdvertisement>>(emptyList())
    val discoveredDevices: LiveData<List<RawAdvertisement>> = _discoveredDevices

    private val _sensorConfigs = MutableLiveData<List<SensorConfig>>(emptyList())
    val sensorConfigs: LiveData<List<SensorConfig>> = _sensorConfigs

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val discoveredMap = mutableMapOf<String, RawAdvertisement>()
    private val pressureMap   = mutableMapOf<TyrePosition, TyreData>()

    init {
        loadSensorConfigs()
    }

    fun loadSensorConfigs() {
        viewModelScope.launch {
            val configs = sensorRepository.loadSensorConfigs()
            _sensorConfigs.postValue(configs)
        }
    }

    fun saveSensorConfig(config: SensorConfig) {
        viewModelScope.launch {
            sensorRepository.saveSensorConfig(config)
            loadSensorConfigs()
        }
    }

    fun removeSensorConfig(position: TyrePosition) {
        viewModelScope.launch {
            sensorRepository.removeSensorConfig(position)
            loadSensorConfigs()
        }
    }

    fun collectTyreData(flow: SharedFlow<TyreData>) {
        viewModelScope.launch {
            flow.collect { data ->
                pressureMap[data.position] = data
                _tyrePressures.postValue(pressureMap.toMap())
            }
        }
    }

    fun collectRawAdvertisements(flow: SharedFlow<RawAdvertisement>) {
        viewModelScope.launch {
            flow.collect { raw ->
                discoveredMap[raw.macAddress] = raw
                _discoveredDevices.postValue(discoveredMap.values.sortedByDescending { it.rssi })
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun postError(message: String) {
        _errorMessage.postValue(message)
    }
}
