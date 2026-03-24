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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "BleScanner"
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scannerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            // Move heavy processing to background thread to prevent main thread freezing
            scannerScope.launch {
                handleScanResult(result)
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            // Move heavy processing to background thread to prevent main thread freezing
            scannerScope.launch {
                results.forEach { handleScanResult(it) }
            }
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
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(false)
                }
            }
            .build()

        val filters = buildScanFilters()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scan started with Android 16-compatible settings")
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

    fun cleanup() {
        stopScan()
        scannerScope.cancel()
        Log.d(TAG, "BleScanner cleanup completed")
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

        // Extract all advertisement data
        val completeRawBytes = record.bytes ?: ByteArray(0)
        val serviceUuids = record.serviceUuids?.map { it.toString() } ?: emptyList()
        val serviceData = mutableMapOf<String, ByteArray>()
        record.serviceUuids?.forEach { uuid ->
            record.getServiceData(uuid)?.let { data ->
                serviceData[uuid.toString()] = data
            }
        }
        val txPowerLevel = record.txPowerLevel
        val advertisementFlags = record.advertiseFlags

        // Emit raw advertisement for discovery/logging screen
        val raw = RawAdvertisement(
            macAddress = mac,
            deviceName = getSafeName(result),
            rssi = result.rssi,
            manufacturerData = record.bytes,
            timestampMs = System.currentTimeMillis(),
            completeRawBytes = completeRawBytes,
            serviceUuids = serviceUuids,
            serviceData = serviceData,
            txPowerLevel = txPowerLevel,
            advertisementFlags = advertisementFlags
        )
        _rawAdvertisementFlow.tryEmit(raw)

        // Log complete raw advertisement data to console for reverse-engineering
        logRawAdvertisement(mac, result, record)

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
                    result.device.alias ?: result.device.name ?: "UNNAMED"
                else
                    result.device.name ?: "UNNAMED"
        } catch (e: SecurityException) {
            "UNNAMED"
        }
    }

    private fun logRawAdvertisement(mac: String, result: ScanResult, record: ScanRecord) {
        val sb = StringBuilder()
        sb.appendLine("\n=== BLE Advertisement Detected ===")
        sb.appendLine("MAC: $mac")
        sb.appendLine("Name: ${getSafeName(result)}")
        sb.appendLine("RSSI: ${result.rssi} dBm")
        sb.appendLine("Timestamp: ${System.currentTimeMillis()} ms")
        
        // Complete raw bytes
        val rawBytes = record.bytes
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            sb.appendLine("--- Complete Raw Bytes (${rawBytes.size} bytes) ---")
            sb.appendLine(formatHexDump(rawBytes))
        }
        
        // Manufacturer data
        val mfData = record.manufacturerSpecificData
        if (mfData != null && mfData.size() > 0) {
            sb.appendLine("--- Manufacturer Data ---")
            for (i in 0 until mfData.size()) {
                val companyId = mfData.keyAt(i)
                val data = mfData.valueAt(i)
                sb.appendLine("Company ID: 0x${companyId.toString(16).uppercase().padStart(4, '0')}")
                sb.appendLine("Data: ${formatHexString(data)}")
            }
        }
        
        // Service UUIDs
        val serviceUuids = record.serviceUuids
        if (!serviceUuids.isNullOrEmpty()) {
            sb.appendLine("--- Service UUIDs ---")
            serviceUuids.forEach { uuid ->
                sb.appendLine(uuid.toString())
                record.getServiceData(uuid)?.let { data ->
                    sb.appendLine("  Service Data: ${formatHexString(data)}")
                }
            }
        }
        
        // TX Power
        if (record.txPowerLevel != Int.MIN_VALUE) {
            sb.appendLine("TX Power: ${record.txPowerLevel} dBm")
        }
        
        // Advertisement flags
        if (record.advertiseFlags != -1) {
            sb.appendLine("Flags: 0x${record.advertiseFlags.toString(16).uppercase()}")
        }
        
        sb.appendLine("================================")
        Log.d(TAG, sb.toString())
    }

    private fun formatHexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices step 16) {
            sb.append("${i.toString(16).uppercase().padStart(4, '0')}: ")
            val end = minOf(i + 16, bytes.size)
            for (j in i until end) {
                sb.append("%02X ".format(bytes[j]))
            }
            for (j in end until i + 16) {
                sb.append("   ")
            }
            sb.append(" | ")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun formatHexString(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }
}

data class RawAdvertisement(
    val macAddress: String,
    val deviceName: String,
    val rssi: Int,
    val manufacturerData: ByteArray,
    val timestampMs: Long,
    val completeRawBytes: ByteArray = ByteArray(0),
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val txPowerLevel: Int? = null,
    val advertisementFlags: Int? = null
) {
    fun manufacturerDataHex(): String = manufacturerData.joinToString(" ") { "%02X".format(it) }
    
    fun completeRawBytesHex(): String = completeRawBytes.joinToString(" ") { "%02X".format(it) }
    
    fun formatHexDump(): String {
        val sb = StringBuilder()
        for (i in completeRawBytes.indices step 16) {
            sb.append("${i.toString(16).uppercase().padStart(4, '0')}: ")
            val end = minOf(i + 16, completeRawBytes.size)
            for (j in i until end) {
                sb.append("%02X ".format(completeRawBytes[j]))
            }
            for (j in end until i + 16) {
                sb.append("   ")
            }
            sb.append(" | ")
            for (j in i until end) {
                val c = completeRawBytes[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawAdvertisement) return false
        return macAddress == other.macAddress && timestampMs == other.timestampMs
    }

    override fun hashCode(): Int = 31 * macAddress.hashCode() + timestampMs.hashCode()
}
