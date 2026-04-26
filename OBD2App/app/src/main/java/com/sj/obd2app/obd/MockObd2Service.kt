package com.sj.obd2app.obd

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/**
 * Mock implementation of [Obd2Service] that loads baseline values from
 * `assets/mock_obd2_data.json` and simulates live fluctuation.
 * Enhanced for PID discovery testing with custom PID simulation.
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
        private var enhancedMockData: JSONObject? = null
        private var commandProcessor: MockObd2CommandProcessor? = null
        private var appContext: Context? = null

        /** Must be called once with an application Context to load the JSON. */
        fun init(context: Context) {
            appContext = context.applicationContext
            if (baselineData.isEmpty()) {
                baselineData = loadFromAssets(context)
            }
            // Load enhanced mock data for discovery testing
            try {
                val json = context.assets.open("mock_obd2_enhanced.json")
                    .bufferedReader()
                    .use { it.readText() }
                enhancedMockData = JSONObject(json)
                commandProcessor = MockObd2CommandProcessor(enhancedMockData!!)
            } catch (e: IOException) {
                // Fallback to basic mode if enhanced data not available
                android.util.Log.w("MockObd2Service", "Enhanced mock data not available, using basic mode")
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

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    /**
     * Simulate connecting — device and forceBle parameters are ignored.
     * After a short delay, starts emitting fluctuating data.
     */
    override fun connect(device: BluetoothDevice?, forceBle: Boolean) {
        if (_connectionState.value == Obd2Service.ConnectionState.CONNECTING ||
            _connectionState.value == Obd2Service.ConnectionState.CONNECTED
        ) return

        _connectionState.value = Obd2Service.ConnectionState.CONNECTING
        _errorMessage.value = null
        _connectionLog.value = emptyList()

        // Update centralized state manager
        ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.CONNECTING)

        val isCanMode = appContext?.let { AppSettings.isCanBusLoggingEnabled(it) } ?: false

        if (isCanMode) {
            CoroutineScope(Dispatchers.IO).launch {
                appendConnectionLog("Connecting to Simulated CAN Adapter…")
                delay(300)
                appendConnectionLog("✓ Transport ready (simulated)")
                delay(300)
                appendConnectionLog("CAN Bus mode — skipping OBD PID discovery")
                delay(200)
                appendConnectionLog("No cached protocol — will auto-detect")
                delay(300)
                appendConnectionLog("Waiting for CAN bus to settle…")
                delay(500)
                appendConnectionLog("✓ Ready for CAN sniffing — adapter idle until scan starts")
                _connectionState.value = Obd2Service.ConnectionState.CONNECTED
                ObdStateManager.updateConnectionState(
                    ObdStateManager.ConnectionState.CONNECTED, "Simulated CAN Adapter"
                )
                // No OBD polling in CAN mode — CanBusScanner drives the data flow.
            }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                appendConnectionLog("Connecting to Mock OBD2 Adapter…")
                delay(500)
                appendConnectionLog("✓ Bluetooth socket connected")
                delay(500)
                appendConnectionLog("✓ ELM327 initialised (mock)")
                delay(500)
                appendConnectionLog("✓ 20 PIDs supported — starting data polling")
                _connectionState.value = Obd2Service.ConnectionState.CONNECTED
                ObdStateManager.updateConnectionState(
                    ObdStateManager.ConnectionState.CONNECTED, "Mock OBD2 Adapter"
                )
                startPolling()
            }
        }
    }

    /** Allows [CanBusScanner] to append lines to the connection log in mock CAN mode. */
    fun appendConnectionLog(msg: String) {
        _connectionLog.value = _connectionLog.value + msg
    }

    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        _connectionState.value = Obd2Service.ConnectionState.DISCONNECTED
        _obd2Data.value = emptyList()
        _connectionLog.value = emptyList()
        // Update centralized state manager
        ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.DISCONNECTED)
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
    
    // ===== PID Discovery Testing Methods =====
    
    /**
     * Set test scenario for discovery testing.
     */
    fun setTestScenario(scenario: MockDiscoveryScenario) {
        commandProcessor?.setTestScenario(scenario.name)
    }
    
    /**
     * Get current active header for testing.
     */
    fun getCurrentHeader(): String {
        return commandProcessor?.getCurrentHeader() ?: "7DF"
    }
    
    /**
     * Simulate sending a command for discovery testing.
     */
    suspend fun sendCommand(command: String): String {
        return commandProcessor?.processCommand(command) ?: "NO PROCESSOR"
    }
    
    /**
     * Get all custom PIDs for current header.
     */
    fun getCurrentHeaderPids(): List<DiscoveredPid> {
        val processor = commandProcessor ?: return emptyList()
        val headerPids = processor.getCurrentHeaderPids()
        
        return headerPids.map { (pid, data) ->
            val mode = pid.substring(0, 2)
            DiscoveredPid(
                header = getCurrentHeader(),
                mode = mode,
                pid = pid,
                response = data.getString("response"),
                byteCount = data.getInt("bytes"),
                suggestedName = data.getString("name"),
                suggestedUnit = data.optString("unit", ""),
                suggestedFormula = data.getString("formula")
            )
        }
    }
    
    /**
     * Reset mock to default state.
     */
    fun reset() {
        commandProcessor?.reset()
    }
    
    /**
     * Check if enhanced mock data is available.
     */
    fun isEnhancedModeAvailable(): Boolean {
        return enhancedMockData != null && commandProcessor != null
    }
}
