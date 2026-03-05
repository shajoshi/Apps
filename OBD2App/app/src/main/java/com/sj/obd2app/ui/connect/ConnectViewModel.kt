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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
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

    companion object {
        private const val PREFS_NAME = "obd2_prefs"
        private const val KEY_LAST_DEVICE_MAC = "last_device_mac"
        private const val KEY_LAST_DEVICE_NAME = "last_device_name"
    }

    private val service = Obd2ServiceProvider.getService()

    private val _pairedDevices = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val pairedDevices: LiveData<List<BluetoothDevice>> = _pairedDevices

    private val _discoveredDevices = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: LiveData<List<BluetoothDevice>> = _discoveredDevices

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

    val isMockMode: Boolean get() = Obd2ServiceProvider.useMock

    private var btAdapter: BluetoothAdapter? = null
    private val discoveredSet = mutableSetOf<String>() // MAC addresses to avoid duplicates
    private val discoveredList = mutableListOf<BluetoothDevice>()

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
                    if (device != null && device.address !in discoveredSet) {
                        // Exclude already-paired devices from the discovered list
                        val paired = _pairedDevices.value?.map { it.address } ?: emptyList()
                        if (device.address !in paired) {
                            discoveredSet.add(device.address)
                            discoveredList.add(device)
                            _discoveredDevices.postValue(discoveredList.toList())
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.postValue(false)
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            service.connectionState.collect { state ->
                _isConnected.postValue(state == Obd2Service.ConnectionState.CONNECTED)
                _connectionStatus.postValue(
                    when (state) {
                        Obd2Service.ConnectionState.DISCONNECTED -> {
                            _connectedDeviceMac.postValue(null)
                            "Disconnected — tap a device to connect"
                        }
                        Obd2Service.ConnectionState.CONNECTING -> "Connecting…"
                        Obd2Service.ConnectionState.CONNECTED -> "Connected"
                        Obd2Service.ConnectionState.ERROR -> {
                            _connectedDeviceMac.postValue(null)
                            val msg = service.errorMessage.value
                            "Error${if (msg != null) ": $msg" else ""}"
                        }
                    }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun loadPairedDevices(context: Context) {
        if (Obd2ServiceProvider.useMock) {
            _mockDeviceNames.value = listOf("Mock OBD2 Adapter (DEMO)")
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
        discoveredSet.clear()
        discoveredList.clear()
        _discoveredDevices.value = emptyList()
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

    fun connectToDevice(device: BluetoothDevice, context: Context) {
        saveLastDevice(context, device)
        _connectedDeviceMac.value = device.address
        service.connect(device)
    }

    /** Connect via mock (no real BluetoothDevice needed). */
    fun connectMock() {
        service.connect(null)
    }

    fun disconnect() {
        service.disconnect()
    }

    // ── Persistence: remember last connected device ────────────────────

    @SuppressLint("MissingPermission")
    private fun saveLastDevice(context: Context, device: BluetoothDevice) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_DEVICE_MAC, device.address)
            .putString(KEY_LAST_DEVICE_NAME, device.name ?: "Unknown")
            .apply()
    }

    /**
     * Try to auto-connect to the last successfully used device.
     * Should be called after [loadPairedDevices].
     * Returns true if an auto-connect was attempted.
     */
    @SuppressLint("MissingPermission")
    fun tryAutoConnect(context: Context): Boolean {
        if (Obd2ServiceProvider.useMock) return false
        if (service.connectionState.value == Obd2Service.ConnectionState.CONNECTED ||
            service.connectionState.value == Obd2Service.ConnectionState.CONNECTING
        ) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastMac = prefs.getString(KEY_LAST_DEVICE_MAC, null) ?: return false

        // Look for the device in paired list first
        val device = _pairedDevices.value?.find { it.address == lastMac }
        if (device != null) {
            service.connect(device)
            return true
        }

        // If the adapter knows the device by MAC (even if not currently paired)
        val adapter = btAdapter ?: return false
        try {
            val remoteDevice = adapter.getRemoteDevice(lastMac)
            service.connect(remoteDevice)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun getLastDeviceName(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_DEVICE_NAME, null)
    }
}
