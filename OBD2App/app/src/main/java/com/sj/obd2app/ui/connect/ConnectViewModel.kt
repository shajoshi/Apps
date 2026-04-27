package com.sj.obd2app.ui.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.can.CanBusScanner
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.ObdStateManager
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for the Connect screen.
 *
 * Features:
 * - Lists paired Bluetooth devices
 * - Discovers nearby (unpaired) devices via BT scan
 * - Remembers the last successfully connected device MAC in SharedPreferences
 * - Auto-connects to the last device on app start if available
 */
class ConnectViewModel : ViewModel() {

    private val service get() = Obd2ServiceProvider.getService()

    private val _pairedDevices = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val pairedDevices: LiveData<List<BluetoothDevice>> = _pairedDevices

    /** Unified sectioned device list with headers */
    private val _allDevices = MutableLiveData<List<DeviceListItem>>(emptyList())
    val allDevices: LiveData<List<DeviceListItem>> = _allDevices

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    /** MAC address of the currently connected device, or null. */
    private val _connectedDeviceMac = MutableLiveData<String?>(null)
    val connectedDeviceMac: LiveData<String?> = _connectedDeviceMac

    /** In mock mode we show device names as plain strings instead. */
    private val _mockDeviceNames = MutableLiveData<List<String>>(emptyList())
    val mockDeviceNames: LiveData<List<String>> = _mockDeviceNames

    private val _connectionStatus = MutableLiveData("Disconnected")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    /** MAC of the device currently being connected to (CONNECTING state). Null otherwise. */
    private val _connectingDeviceMac = MutableLiveData<String?>(null)
    val connectingDeviceMac: LiveData<String?> = _connectingDeviceMac

    /** MAC of the last attempted device — retained so ERROR state can tint that row red. */
    private val _errorDeviceMac = MutableLiveData<String?>(null)
    val errorDeviceMac: LiveData<String?> = _errorDeviceMac

    /** Step-by-step log lines emitted during connection establishment. */
    private val _connectionLog = MutableLiveData<List<String>>(emptyList())
    val connectionLog: LiveData<List<String>> = _connectionLog

    /** Current mock mode state (for immediate access) */
    val currentMockMode: Boolean get() = ObdStateManager.isMockMode

    private var btAdapter: BluetoothAdapter? = null
    private val discoveredDevicesMap = mutableMapOf<String, DeviceInfo>() // MAC -> DeviceInfo with RSSI
    private var autoConnectRetryJob: Job? = null

    companion object {
        private const val AUTO_CONNECT_MAX_RETRIES = 3
        private const val AUTO_CONNECT_RETRY_DELAY_MS = 10_000L  // 10 seconds
        private val OBD_KEYWORDS = listOf("OBD", "ELM", "OBDII", "VGATE", "ICAR", "VEEPEAK", "KONNWEI", "CARISTA", "BLUEDRIVER", "CARLY")
        
        fun isObdLikelyDevice(name: String?): Boolean {
            return OBD_KEYWORDS.any { name?.uppercase()?.contains(it) ?: false }
        }
    }

    /**
     * BroadcastReceiver for discovered BT devices during scan.
     * Must be registered/unregistered by the Fragment.
     */
    val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    
                    if (device != null) {
                        val paired = _pairedDevices.value?.map { it.address } ?: emptyList()
                        val isPaired = device.address in paired
                        val isObd = isLikelyObd(device)
                        
                        discoveredDevicesMap[device.address] = DeviceInfo(
                            device = device,
                            isPaired = isPaired,
                            isObd = isObd,
                            rssi = rssi
                        )
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.postValue(false)
                    rebuildSections()
                }
            }
        }
    }

    /** Application context — set once from the Fragment via [initContext]. */
    private var appContext: android.content.Context? = null

    /** Call once from [ConnectFragment.onViewCreated] so the ViewModel has a Context. */
    fun initContext(context: android.content.Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    init {
        viewModelScope.launch {
            service.connectionState.collect { state ->
                _isConnected.postValue(state == Obd2Service.ConnectionState.CONNECTED)
                _connectionStatus.postValue(
                    when (state) {
                        Obd2Service.ConnectionState.DISCONNECTED -> {
                            _connectedDeviceMac.postValue(null)
                            _connectingDeviceMac.postValue(null)
                            _errorDeviceMac.postValue(null)
                            "Disconnected — tap a device to connect"
                        }
                        Obd2Service.ConnectionState.CONNECTING -> "Connecting…"
                        Obd2Service.ConnectionState.CONNECTED -> {
                            // Promote connecting → connected so the green row fires
                            _connectedDeviceMac.postValue(_connectingDeviceMac.value)
                            _connectingDeviceMac.postValue(null)
                            _errorDeviceMac.postValue(null)
                            "Connected"
                        }
                        Obd2Service.ConnectionState.ERROR -> {
                            // Move the connecting MAC to error MAC so the row turns red
                            _errorDeviceMac.postValue(_connectingDeviceMac.value)
                            _connectedDeviceMac.postValue(null)
                            _connectingDeviceMac.postValue(null)
                            val msg = service.errorMessage.value
                            "Error${if (msg != null) ": $msg" else ""}"
                        }
                    }
                )
            }
        }
        viewModelScope.launch {
            combine(
                service.connectionState,
                service.connectedDeviceName
            ) { state, deviceName -> state to deviceName }
                .collect { (state, deviceName) ->
                    val normalizedState = when (state) {
                        Obd2Service.ConnectionState.CONNECTED -> ObdStateManager.ConnectionState.CONNECTED
                        Obd2Service.ConnectionState.CONNECTING -> ObdStateManager.ConnectionState.CONNECTING
                        Obd2Service.ConnectionState.ERROR -> ObdStateManager.ConnectionState.ERROR
                        else -> ObdStateManager.ConnectionState.DISCONNECTED
                    }
                    ObdStateManager.updateConnectionState(normalizedState, deviceName)
                }
        }
        viewModelScope.launch {
            service.connectionLog.collect { lines ->
                _connectionLog.postValue(lines)
            }
        }
        // Auto-start CAN scanner in preview mode when CAN mode is on and adapter connects.
        viewModelScope.launch {
            service.connectionState.collect { state ->
                val ctx = appContext ?: return@collect
                if (!AppSettings.isCanBusLoggingEnabled(ctx)) return@collect
                when (state) {
                    Obd2Service.ConnectionState.CONNECTED -> {
                        if (CanBusScanner.state.value is CanBusScanner.State.Idle) {
                            val profile = CanProfileRepository.getInstance(ctx).getDefault()
                            if (profile != null && profile.selectedSignals.isNotEmpty()) {
                                android.util.Log.i(
                                    "ConnectViewModel",
                                    "CAN mode: auto-starting scanner (preview) for '${profile.name}'"
                                )
                                CanBusScanner.start(ctx, profile, previewMode = true)
                            }
                        }
                    }
                    Obd2Service.ConnectionState.DISCONNECTED,
                    Obd2Service.ConnectionState.ERROR -> {
                        if (CanBusScanner.state.value !is CanBusScanner.State.Idle) {
                            CanBusScanner.stop()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun loadPairedDevices(context: Context) {
        if (Obd2ServiceProvider.useMock) {
            // Show Simulated CAN Adapter when CAN Bus logging is enabled, otherwise Mock OBD2 Adapter
            val deviceName = if (AppSettings.isCanBusLoggingEnabled(context)) {
                "Simulated CAN Adapter"
            } else {
                "Mock OBD2 Adapter (DEMO)"
            }
            _mockDeviceNames.value = listOf(deviceName)
            return
        }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            _connectionStatus.value = "Bluetooth not available on this device"
            return
        }
        btAdapter = adapter
        if (!adapter.isEnabled) {
            _connectionStatus.value = "Bluetooth is off — please enable it"
            _pairedDevices.value = emptyList()
            return
        }
        try {
            val paired = adapter.bondedDevices?.toList() ?: emptyList()
            _pairedDevices.value = paired
            rebuildSections()
        } catch (e: SecurityException) {
            _connectionStatus.value = "Bluetooth permission not granted"
            _pairedDevices.value = emptyList()
        }
    }

    /**
     * Start Bluetooth discovery to find nearby unpaired devices.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = btAdapter ?: return
        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        discoveredDevicesMap.clear()
        _isScanning.value = true
        adapter.startDiscovery()
    }

    /**
     * Stop Bluetooth discovery.
     */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        btAdapter?.cancelDiscovery()
        _isScanning.value = false
    }

    fun connectToDevice(deviceInfo: DeviceInfo, context: Context) {
        saveLastDevice(context, deviceInfo.device)
        _errorDeviceMac.value = null
        _connectingDeviceMac.value = deviceInfo.device.address
        val forceBle = AppSettings.isForceBleConnection(context)
        ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.CONNECTING, deviceInfo.device.name)
        service.connect(deviceInfo.device, forceBle)
    }

    /** Connect via mock (no real BluetoothDevice needed). */
    fun connectMock() {
        ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.CONNECTING)
        service.connect(null)
    }

    fun disconnect() {
        ObdStateManager.updateConnectionState(ObdStateManager.ConnectionState.DISCONNECTED)
        service.disconnect()
    }

    // ── Persistence: remember last connected device ────────────────────

    @SuppressLint("MissingPermission")
    private fun saveLastDevice(context: Context, device: BluetoothDevice) {
        AppSettings.setLastDevice(context, device.address, device.name ?: "Unknown")
    }

    /**
     * Try to auto-connect to the last successfully used device with retry logic.
     * Attempts up to 3 times at 10-second intervals. Shows Toast on each failure
     * and a final Toast when giving up.
     *
     * Should be called after [loadPairedDevices].
     */
    @SuppressLint("MissingPermission")
    fun tryAutoConnect(context: Context) {
        if (Obd2ServiceProvider.useMock) return
        if (service.connectionState.value == Obd2Service.ConnectionState.CONNECTED ||
            service.connectionState.value == Obd2Service.ConnectionState.CONNECTING
        ) return

        val lastMac = AppSettings.getLastDeviceMac(context)
        if (lastMac.isNullOrEmpty()) return

        autoConnectRetryJob?.cancel()
        autoConnectRetryJob = viewModelScope.launch {
            for (attempt in 1..AUTO_CONNECT_MAX_RETRIES) {
                // Check if already connected (e.g. manual connect happened)
                if (service.connectionState.value == Obd2Service.ConnectionState.CONNECTED) return@launch

                val connected = attemptSingleConnect(context, lastMac)
                if (connected) return@launch

                // Wait for the connection attempt to settle
                delay(AUTO_CONNECT_RETRY_DELAY_MS)

                // After delay, re-check if connection succeeded
                if (service.connectionState.value == Obd2Service.ConnectionState.CONNECTED) return@launch

                if (attempt < AUTO_CONNECT_MAX_RETRIES) {
                    Toast.makeText(
                        context,
                        "Auto-connect attempt $attempt/$AUTO_CONNECT_MAX_RETRIES failed, retrying...",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // All retries exhausted
            if (service.connectionState.value != Obd2Service.ConnectionState.CONNECTED) {
                Toast.makeText(
                    context,
                    "Could not auto-connect to last OBD device. Please connect manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Single auto-connect attempt. Returns true if the attempt was initiated.
     */
    @SuppressLint("MissingPermission")
    private fun attemptSingleConnect(context: Context, mac: String): Boolean {
        if (service.connectionState.value == Obd2Service.ConnectionState.CONNECTED ||
            service.connectionState.value == Obd2Service.ConnectionState.CONNECTING
        ) return true

        val forceBle = AppSettings.isForceBleConnection(context)

        // Look for the device in paired list first
        val device = _pairedDevices.value?.find { it.address == mac }
        if (device != null) {
            _connectingDeviceMac.postValue(device.address)
            service.connect(device, forceBle)
            return true
        }

        // If the adapter knows the device by MAC
        val adapter = btAdapter ?: return false
        return try {
            val remoteDevice = adapter.getRemoteDevice(mac)
            _connectingDeviceMac.postValue(remoteDevice.address)
            service.connect(remoteDevice, forceBle)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Cancel any ongoing auto-connect retry loop.
     */
    fun cancelAutoConnect() {
        autoConnectRetryJob?.cancel()
        autoConnectRetryJob = null
    }

    fun getLastDeviceName(context: Context): String? {
        return AppSettings.getLastDeviceName(context)
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyObd(device: BluetoothDevice): Boolean {
        val name = try { device.name?.uppercase() ?: "" } catch (_: Exception) { "" }
        return OBD_KEYWORDS.any { name.contains(it) }
    }

    /**
     * Rebuild the sectioned device list from paired and discovered devices.
     * Called after loading paired devices or completing a scan.
     */
    private fun rebuildSections() {
        val paired = _pairedDevices.value ?: emptyList()
        val items = mutableListOf<DeviceListItem>()
        
        // Combine paired devices (with default RSSI) and discovered devices (with actual RSSI)
        val allDeviceInfos = mutableMapOf<String, DeviceInfo>()
        
        // Add paired devices
        paired.forEach { device ->
            allDeviceInfos[device.address] = DeviceInfo(
                device = device,
                isPaired = true,
                isObd = isLikelyObd(device),
                rssi = 0 // Paired devices get neutral RSSI for sorting
            )
        }
        
        // Merge/update with discovered devices (which have RSSI)
        discoveredDevicesMap.forEach { (mac, info) ->
            allDeviceInfos[mac] = info
        }
        
        // Split into three categories
        val obdDevices = allDeviceInfos.values.filter { it.isObd }.sortedByDescending { it.rssi }
        val pairedOther = allDeviceInfos.values.filter { !it.isObd && it.isPaired }.sortedByDescending { it.rssi }
        val unpairedOther = allDeviceInfos.values.filter { !it.isObd && !it.isPaired }.sortedByDescending { it.rssi }
        
        // Build sectioned list
        if (obdDevices.isNotEmpty()) {
            items.add(DeviceListItem.Header("Potential OBD Devices", obdDevices.size))
            items.addAll(obdDevices.map { DeviceListItem.Device(it) })
        }
        
        if (pairedOther.isNotEmpty()) {
            items.add(DeviceListItem.Header("Other Paired Devices", pairedOther.size))
            items.addAll(pairedOther.map { DeviceListItem.Device(it) })
        }
        
        if (unpairedOther.isNotEmpty()) {
            items.add(DeviceListItem.Header("Other Unpaired Devices", unpairedOther.size))
            items.addAll(unpairedOther.map { DeviceListItem.Device(it) })
        }
        
        _allDevices.postValue(items)
    }

}
