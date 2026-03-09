package com.sj.obd2app.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.IOException
import kotlin.random.Random

/**
 * Mock implementation of [Obd2Service] that loads baseline values from
 * `assets/mock_obd2_data.json` and simulates live fluctuation.
 *
 * Usage:
 * ```
 * MockObd2Service.init(applicationContext)
 * Obd2ServiceProvider.useMock = true
 * ```
 */
class MockObd2Service private constructor() : Obd2Service {

    companion object {
        @Volatile
        private var instance: MockObd2Service? = null
        private var baselineData: List<Obd2DataItem> = emptyList()

        /** Must be called once with an application Context to load the JSON. */
        fun init(context: Context) {
            if (baselineData.isEmpty()) {
                baselineData = loadFromAssets(context)
            }
        }

        fun getInstance(): MockObd2Service {
            return instance ?: synchronized(this) {
                instance ?: MockObd2Service().also { instance = it }
            }
        }

        private fun loadFromAssets(context: Context): List<Obd2DataItem> {
            return try {
                val json = context.assets.open("mock_obd2_data.json")
                    .bufferedReader()
                    .use { it.readText() }

                val array = JSONArray(json)
                val items = mutableListOf<Obd2DataItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(
                        Obd2DataItem(
                            pid = obj.getString("pid"),
                            name = obj.getString("name"),
                            value = obj.getString("value"),
                            unit = obj.getString("unit")
                        )
                    )
                }
                items
            } catch (e: IOException) {
                emptyList()
            }
        }
    }

    private var pollingJob: Job? = null

    private val _connectionState = MutableStateFlow(Obd2Service.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<Obd2Service.ConnectionState> = _connectionState

    private val _obd2Data = MutableStateFlow<List<Obd2DataItem>>(emptyList())
    override val obd2Data: StateFlow<List<Obd2DataItem>> = _obd2Data

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    override val connectionLog: StateFlow<List<String>> = _connectionLog

    /**
     * Simulate connecting — device parameter is ignored.
     * After a short delay, starts emitting fluctuating data.
     */
    override fun connect(device: BluetoothDevice?) {
        if (_connectionState.value == Obd2Service.ConnectionState.CONNECTING ||
            _connectionState.value == Obd2Service.ConnectionState.CONNECTED
        ) return

        _connectionState.value = Obd2Service.ConnectionState.CONNECTING
        _errorMessage.value = null
        _connectionLog.value = listOf("Connecting to Mock OBD2 Adapter…")

        CoroutineScope(Dispatchers.IO).launch {
            delay(500)
            _connectionLog.value = _connectionLog.value + "✓ Bluetooth socket connected"
            delay(500)
            _connectionLog.value = _connectionLog.value + "✓ ELM327 initialised (mock)"
            delay(500)
            _connectionLog.value = _connectionLog.value + "✓ 20 PIDs supported — starting data polling"
            _connectionState.value = Obd2Service.ConnectionState.CONNECTED
            startPolling()
        }
    }

    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        _connectionState.value = Obd2Service.ConnectionState.DISCONNECTED
        _obd2Data.value = emptyList()
        _connectionLog.value = emptyList()
    }

    /**
     * Simulate live data by adding small random fluctuations to baseline values.
     */
    private fun startPolling() {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
                val simulated = baselineData.map { item ->
                    val baseVal = item.value.toDoubleOrNull()
                    if (baseVal != null) {
                        // Add ±5% random jitter
                        val jitter = baseVal * (Random.nextDouble(-0.05, 0.05))
                        val newVal = baseVal + jitter
                        val formatted = when {
                            item.value.contains(".") -> {
                                val decimals = item.value.substringAfter(".").length
                                String.format("%.${decimals}f", newVal)
                            }
                            else -> String.format("%.0f", newVal)
                        }
                        item.copy(value = formatted)
                    } else {
                        item
                    }
                }
                _obd2Data.value = simulated
                delay(1000) // Update every second
            }
        }
    }
}
