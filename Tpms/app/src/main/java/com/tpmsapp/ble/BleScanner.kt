package com.tpmsapp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.tpmsapp.model.SensorConfig
import com.tpmsapp.model.TyreData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _tyreDataFlow = MutableSharedFlow<TyreData>(replay = 4, extraBufferCapacity = 32)
    val tyreDataFlow: SharedFlow<TyreData> = _tyreDataFlow

    private val _rawAdvertisementFlow = MutableSharedFlow<RawAdvertisement>(extraBufferCapacity = 64)
    val rawAdvertisementFlow: SharedFlow<RawAdvertisement> = _rawAdvertisementFlow

    private var knownSensors: Map<String, SensorConfig> = emptyMap()
    var isScanning = false
        private set
    var parseKnownSensorsOnly: Boolean = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
        }
    }

    fun updateKnownSensors(sensors: List<SensorConfig>) {
        knownSensors = sensors.associateBy { it.macAddress.uppercase() }
    }

    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter not available or disabled")
            return
        }
        if (isScanning) return

        val scanner = adapter.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val filters = buildScanFilters()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scan started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "BLE scan stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission: ${e.message}")
        }
    }

    val isBluetoothAvailable: Boolean
        get() = bluetoothAdapter != null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    private fun buildScanFilters(): List<ScanFilter> {
        // Scan all advertisements when no sensors are configured yet (discovery mode).
        // Once sensors are paired, we could filter by MAC; however, Android BLE API
        // restricts MAC filtering to bonded devices on Android 10+, so we scan broadly
        // and filter in software.
        return emptyList()
    }

    private fun handleScanResult(result: ScanResult) {
        val mac = result.device.address.uppercase()
        val record: ScanRecord = result.scanRecord ?: return

        // Emit raw advertisement for discovery/logging screen
        val raw = RawAdvertisement(
            macAddress = mac,
            deviceName = getSafeName(result),
            rssi = result.rssi,
            manufacturerData = record.bytes,
            timestampMs = System.currentTimeMillis()
        )
        _rawAdvertisementFlow.tryEmit(raw)

        // Parse manufacturer-specific data (type 0xFF bytes)
        val mfData = extractManufacturerData(record) ?: return

        val config = knownSensors[mac]
        if (parseKnownSensorsOnly && config == null) return
        val tyreData = TpmsPacketParser.parse(mac, mfData, config?.position) ?: return

        _tyreDataFlow.tryEmit(tyreData)
    }

    private fun extractManufacturerData(record: ScanRecord): ByteArray? {
        val sparse = record.manufacturerSpecificData
        if (sparse != null && sparse.size() > 0) {
            return sparse.valueAt(0)
        }
        return null
    }

    @Suppress("MissingPermission")
    private fun getSafeName(result: ScanResult): String {
        return try {
            result.scanRecord?.deviceName
                ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    result.device.alias ?: result.device.name ?: "Unknown"
                else
                    result.device.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Unknown"
        }
    }
}

data class RawAdvertisement(
    val macAddress: String,
    val deviceName: String,
    val rssi: Int,
    val manufacturerData: ByteArray,
    val timestampMs: Long
) {
    fun manufacturerDataHex(): String = manufacturerData.joinToString(" ") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawAdvertisement) return false
        return macAddress == other.macAddress && timestampMs == other.timestampMs
    }

    override fun hashCode(): Int = 31 * macAddress.hashCode() + timestampMs.hashCode()
}
