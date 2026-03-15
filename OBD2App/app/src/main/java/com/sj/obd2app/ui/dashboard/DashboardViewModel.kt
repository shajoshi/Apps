package com.sj.obd2app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 * Exposes key OBD-II metrics (RPM, Speed, Coolant Temp, Throttle, Engine Load, Fuel Level)
 * for the gauge cards.
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val service = Obd2ServiceProvider.getService()

    private val _rpm = MutableLiveData("—")
    val rpm: LiveData<String> = _rpm

    private val _speed = MutableLiveData("—")
    val speed: LiveData<String> = _speed

    private val _coolantTemp = MutableLiveData("—")
    val coolantTemp: LiveData<String> = _coolantTemp

    private val _throttle = MutableLiveData("—")
    val throttle: LiveData<String> = _throttle

    private val _engineLoad = MutableLiveData("—")
    val engineLoad: LiveData<String> = _engineLoad

    private val _fuelLevel = MutableLiveData("—")
    val fuelLevel: LiveData<String> = _fuelLevel

    private val _connectionStatus = MutableLiveData("Disconnected")
    val connectionStatus: LiveData<String> = _connectionStatus

    init {
        viewModelScope.launch {
            service.obd2Data.collect { items ->
                for (item in items) {
                    when (item.pid) {
                        "010C" -> _rpm.postValue(item.value)
                        "010D" -> _speed.postValue(item.value)
                        "0105" -> _coolantTemp.postValue(item.value)
                        "0111" -> _throttle.postValue(item.value)
                        "0104" -> _engineLoad.postValue(item.value)
                        "012F" -> _fuelLevel.postValue(item.value)
                    }
                }
            }
        }
        viewModelScope.launch {
            service.connectionState.collect { state ->
                val isMock = !AppSettings.isObdConnectionEnabled(ctx) || Obd2ServiceProvider.useMock
                _connectionStatus.postValue(
                    when {
                        isMock -> "Simulation Mode — live data"
                        state == Obd2Service.ConnectionState.CONNECTED   -> "Connected — live data"
                        state == Obd2Service.ConnectionState.CONNECTING  -> "Connecting…"
                        state == Obd2Service.ConnectionState.ERROR       -> "Connection error"
                        else                                              -> "Disconnected"
                    }
                )
            }
        }
    }
}
