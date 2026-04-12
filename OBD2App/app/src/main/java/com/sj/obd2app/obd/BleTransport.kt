package com.sj.obd2app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * BLE (GATT) transport for ELM327 adapters.
 * 
 * Common BLE ELM327 UUIDs:
 * - Service: 0000fff0-0000-1000-8000-00805f9b34fb
 * - TX (write): 0000fff1-0000-1000-8000-00805f9b34fb or 0000fff2-0000-1000-8000-00805f9b34fb
 * - RX (notify): 0000fff2-0000-1000-8000-00805f9b34fb or 0000fff1-0000-1000-8000-00805f9b34fb
 * 
 * Some adapters use Nordic UART Service (NUS):
 * - Service: 6e400001-b5a3-f393-e0a9-e50e24dcca9e
 * - TX: 6e400002-b5a3-f393-e0a9-e50e24dcca9e
 * - RX: 6e400003-b5a3-f393-e0a9-e50e24dcca9e
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice
) : Elm327Transport {
    
    companion object {
        private const val TAG = "BleTransport"
        
        // Common ELM327 BLE service UUIDs
        private val ELM327_SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        // Note: fff1 has NOTIFY (RX), fff2 is write-only (TX) on many adapters
        private val ELM327_TX_CHAR_UUID_1 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val ELM327_TX_CHAR_UUID_2 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val ELM327_RX_CHAR_UUID_1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val ELM327_RX_CHAR_UUID_2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        
        // Nordic UART Service (NUS) - used by some adapters
        private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Client Characteristic Configuration Descriptor (for enabling notifications)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        // Max response buffer size to prevent memory exhaustion
        private const val MAX_RESPONSE_BUFFER_SIZE = 4096
        private const val RESPONSE_POLL_DELAY_MS = 20L
    }
    
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    private val responseMutex = Mutex()
    private val responseBuffer = StringBuilder()
    private var responseComplete = false
    
    private var connectionState = BluetoothProfile.STATE_DISCONNECTED
    private var servicesDiscovered = false
    private var notificationsEnabled = false
    private val verboseLogging: Boolean = context.let { AppSettings.isBtLoggingEnabled(it) }
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connectionState = newState
            if (verboseLogging) {
                android.util.Log.d(TAG, "Connection state change - status: $status, newState: $newState")
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (verboseLogging) {
                            android.util.Log.d(TAG, "GATT connected successfully, discovering services...")
                        }
                        servicesDiscovered = false
                        gatt.discoverServices()
                    } else {
                        android.util.Log.e(TAG, "GATT connection failed with status: $status")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (verboseLogging) {
                        android.util.Log.d(TAG, "GATT disconnected, status: $status")
                    }
                    servicesDiscovered = false
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (verboseLogging) {
                android.util.Log.d(TAG, "Services discovered callback - status: $status")
            }
            servicesDiscovered = true
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (verboseLogging) {
                    android.util.Log.d(TAG, "Services discovered successfully")
                }
                discoverCharacteristics(gatt)
            } else {
                android.util.Log.e(TAG, "Service discovery failed with status: $status")
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (verboseLogging) {
                android.util.Log.d(TAG, "Descriptor write callback - status: $status, descriptor: ${descriptor.uuid}")
            }
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) {
                android.util.Log.d(TAG, "Notifications enabled successfully")
            } else {
                android.util.Log.e(TAG, "Failed to enable notifications, status: $status")
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic, value)
        }
        
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleCharacteristicChanged(characteristic, characteristic.value)
            }
        }
    }
    
    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val data = String(value, Charsets.ISO_8859_1)
        if (verboseLogging) {
            val hexData = value.joinToString(" ") { "%02X".format(it) }
            android.util.Log.v(TAG, "RX: '$data' (hex: $hexData)")
        }
        
        synchronized(responseBuffer) {
            if (responseBuffer.length + data.length > MAX_RESPONSE_BUFFER_SIZE) {
                android.util.Log.w(TAG, "Response buffer overflow (${responseBuffer.length + data.length} > $MAX_RESPONSE_BUFFER_SIZE), truncating")
                responseBuffer.clear()
                responseComplete = true
                return
            }
            responseBuffer.append(data)
            if (verboseLogging) {
                android.util.Log.v(TAG, "Response buffer now: '${responseBuffer}'")
            }
            if (data.contains('>')) {
                responseComplete = true
                if (verboseLogging) {
                    android.util.Log.d(TAG, "Response complete (found '>')")
                }
            }
        }
    }
    
    private fun discoverCharacteristics(gatt: BluetoothGatt) {
        // Log all available services and characteristics for debugging
        if (verboseLogging) {
            logAllServicesAndCharacteristics(gatt)
        }
        
        // Try ELM327 service first
        var service = gatt.getService(ELM327_SERVICE_UUID)
        if (service != null) {
            android.util.Log.d(TAG, "Found ELM327 service")
            txCharacteristic = service.getCharacteristic(ELM327_TX_CHAR_UUID_1)
                ?: service.getCharacteristic(ELM327_TX_CHAR_UUID_2)
            rxCharacteristic = service.getCharacteristic(ELM327_RX_CHAR_UUID_1)
                ?: service.getCharacteristic(ELM327_RX_CHAR_UUID_2)
        }
        
        // Try Nordic UART Service if ELM327 not found
        if (txCharacteristic == null || rxCharacteristic == null) {
            service = gatt.getService(NUS_SERVICE_UUID)
            if (service != null) {
                android.util.Log.d(TAG, "Found Nordic UART service")
                txCharacteristic = service.getCharacteristic(NUS_TX_CHAR_UUID)
                rxCharacteristic = service.getCharacteristic(NUS_RX_CHAR_UUID)
            }
        }
        
        // Try auto-detection if standard services not found
        if (txCharacteristic == null || rxCharacteristic == null) {
            android.util.Log.d(TAG, "Standard services not found, attempting auto-detection...")
            autoDetectCharacteristics(gatt)
        }
        
        // Enable notifications on RX characteristic
        rxCharacteristic?.let { rx ->
            if (verboseLogging) {
                android.util.Log.d(TAG, "Setting up notifications for RX characteristic: ${rx.uuid}")
                android.util.Log.d(TAG, "RX characteristic properties: ${getCharacteristicProperties(rx)}")
            }
            
            val notifySet = gatt.setCharacteristicNotification(rx, true)
            if (verboseLogging) {
                android.util.Log.d(TAG, "setCharacteristicNotification returned: $notifySet")
            }
            
            val descriptor = rx.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                // Check if characteristic supports NOTIFY or INDICATE
                val supportsNotify = (rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val supportsIndicate = (rx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                
                if (verboseLogging) {
                    android.util.Log.d(TAG, "Characteristic supports - NOTIFY: $supportsNotify, INDICATE: $supportsIndicate")
                }
                
                // Use INDICATE if NOTIFY is not supported
                val descriptorValue = if (supportsIndicate && !supportsNotify) {
                    if (verboseLogging) {
                        android.util.Log.d(TAG, "Using ENABLE_INDICATION_VALUE")
                    }
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    if (verboseLogging) {
                        android.util.Log.d(TAG, "Using ENABLE_NOTIFICATION_VALUE")
                    }
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }
                
                val writeResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, descriptorValue)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = descriptorValue
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (verboseLogging) {
                    android.util.Log.d(TAG, "writeDescriptor returned: $writeResult")
                }
            } else {
                android.util.Log.e(TAG, "CCCD descriptor not found! Notifications may not work.")
                if (verboseLogging) {
                    android.util.Log.d(TAG, "Available descriptors: ${rx.descriptors.map { it.uuid }}")
                }
            }
        }
    }
    
    private fun logAllServicesAndCharacteristics(gatt: BluetoothGatt) {
        android.util.Log.d(TAG, "=== Available Services and Characteristics ===")
        gatt.services.forEach { service ->
            android.util.Log.d(TAG, "Service: ${service.uuid}")
            service.characteristics.forEach { characteristic ->
                val properties = getCharacteristicProperties(characteristic)
                android.util.Log.d(TAG, "  Characteristic: ${characteristic.uuid} - $properties")
            }
        }
        android.util.Log.d(TAG, "=============================================")
    }
    
    private fun getCharacteristicProperties(characteristic: BluetoothGattCharacteristic): String {
        val props = mutableListOf<String>()
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            props.add("READ")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            props.add("WRITE")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            props.add("WRITE_NO_RESPONSE")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            props.add("NOTIFY")
        }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            props.add("INDICATE")
        }
        return props.joinToString("|")
    }
    
    private fun autoDetectCharacteristics(gatt: BluetoothGatt) {
        // Look for any characteristics that could be TX/RX
        gatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                // Try to identify TX characteristic (writeable)
                if (txCharacteristic == null && 
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                     characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                    txCharacteristic = characteristic
                    android.util.Log.d(TAG, "Auto-detected TX characteristic: ${characteristic.uuid}")
                }
                
                // Try to identify RX characteristic (notifiable)
                if (rxCharacteristic == null && 
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    rxCharacteristic = characteristic
                    android.util.Log.d(TAG, "Auto-detected RX characteristic: ${characteristic.uuid}")
                }
            }
        }
    }
    
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            // Clean up any existing connection first
            gatt?.let {
                android.util.Log.d(TAG, "Cleaning up existing GATT connection")
                try {
                    it.disconnect()
                    it.close()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Error cleaning up old connection: ${e.message}")
                }
                gatt = null
            }
            
            // Reset state
            connectionState = BluetoothProfile.STATE_DISCONNECTED
            servicesDiscovered = false
            notificationsEnabled = false
            txCharacteristic = null
            rxCharacteristic = null
            
            gatt = suspendCancellableCoroutine { continuation ->
                if (verboseLogging) {
                    android.util.Log.d(TAG, "Attempting to connect to device: ${device.address}, type: ${device.type}")
                }
                
                // Small delay to ensure cleanup is complete
                Thread.sleep(100)
                
                // Use explicit BLE transport for better compatibility
                val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (verboseLogging) {
                        android.util.Log.d(TAG, "Using TRANSPORT_LE for connection")
                    }
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
                
                if (g == null) {
                    android.util.Log.e(TAG, "connectGatt returned null")
                    continuation.resumeWithException(IOException("Failed to connect GATT"))
                } else {
                    gatt = g
                    if (verboseLogging) {
                        android.util.Log.d(TAG, "GATT connection object created, waiting for connection...")
                    }
                    
                    // Give the connection a moment to start
                    Thread.sleep(200)
                    
                    // Wait for connection and service discovery with timeout
                    var attempts = 0
                    val maxAttempts = 10 // 10 * 500ms = 5 seconds
                    
                    while (attempts < maxAttempts) {
                        Thread.sleep(500)
                        attempts++
                        
                        if (verboseLogging) {
                            android.util.Log.d(TAG, "Connection check $attempts: state=$connectionState (0=disconnected, 1=connecting, 2=connected), servicesDiscovered=$servicesDiscovered, notificationsEnabled=$notificationsEnabled")
                        }
                        
                        if (connectionState == BluetoothProfile.STATE_CONNECTED && servicesDiscovered) {
                            // Check if characteristics were found and notifications enabled
                            if (txCharacteristic != null && rxCharacteristic != null) {
                                if (notificationsEnabled) {
                                    android.util.Log.d(TAG, "Successfully connected. TX: ${txCharacteristic?.uuid}, RX: ${rxCharacteristic?.uuid}")
                                    continuation.resume(g)
                                    return@suspendCancellableCoroutine
                                } else if (attempts >= 4) {
                                    // Some devices don't fire descriptor write callback, proceed anyway after 2 seconds
                                    android.util.Log.w(TAG, "Descriptor write callback not received, proceeding anyway...")
                                    // Give extra time for the descriptor write to complete
                                    Thread.sleep(500)
                                    notificationsEnabled = true
                                    android.util.Log.d(TAG, "Successfully connected (fallback). TX: ${txCharacteristic?.uuid}, RX: ${rxCharacteristic?.uuid}")
                                    continuation.resume(g)
                                    return@suspendCancellableCoroutine
                                } else {
                                    android.util.Log.d(TAG, "Characteristics found, waiting for notifications to be enabled...")
                                }
                            } else {
                                if (verboseLogging) {
                                    android.util.Log.w(TAG, "Connected but characteristics not found. TX: ${txCharacteristic != null}, RX: ${rxCharacteristic != null}")
                                }
                                // Try manual service discovery
                                if (attempts == 3) {
                                    if (verboseLogging) {
                                        android.util.Log.d(TAG, "Attempting manual service discovery...")
                                    }
                                    g.discoverServices()
                                }
                            }
                        }
                        
                        // If disconnected, fail immediately
                        if (connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                            android.util.Log.e(TAG, "Connection lost during discovery")
                            g.close()
                            continuation.resumeWithException(IOException("Connection lost during service discovery"))
                            return@suspendCancellableCoroutine
                        }
                    }
                    
                    // Timeout reached
                    android.util.Log.e(TAG, "Connection timeout. Final state: connected=${connectionState == BluetoothProfile.STATE_CONNECTED}, servicesDiscovered=$servicesDiscovered")
                    android.util.Log.e(TAG, "Characteristics: TX=${txCharacteristic != null}, RX=${rxCharacteristic != null}")
                    
                    // Last resort - try to force service discovery
                    if (connectionState == BluetoothProfile.STATE_CONNECTED && !servicesDiscovered) {
                        if (verboseLogging) {
                            android.util.Log.d(TAG, "Last resort: forcing service discovery...")
                        }
                        g.discoverServices()
                        Thread.sleep(1000)
                        
                        if (txCharacteristic != null && rxCharacteristic != null) {
                            android.util.Log.d(TAG, "Last resort succeeded. TX: ${txCharacteristic?.uuid}, RX: ${rxCharacteristic?.uuid}")
                            continuation.resume(g)
                            return@suspendCancellableCoroutine
                        }
                    }
                    
                    g.close()
                    continuation.resumeWithException(
                        IOException("Failed to discover ELM327 characteristics. Check logs for available services.")
                    )
                }
            }
        }
    }
    
    override suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            responseMutex.withLock {
                if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                    throw IOException("BLE not connected (state=$connectionState)")
                }
                val tx = txCharacteristic ?: throw IOException("TX characteristic not available")
                val g = gatt ?: throw IOException("GATT not available")
                
                if (verboseLogging) {
                    android.util.Log.v(TAG, "TX: $command")
                }
                synchronized(responseBuffer) {
                    responseBuffer.clear()
                    responseComplete = false
                }
                
                // Send command
                val cmdBytes = "$command\r".toByteArray(Charsets.ISO_8859_1)
                
                val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(tx, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = cmdBytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(tx)
                }
                
                if (!writeOk) {
                    throw IOException("Failed to write characteristic")
                }
                
                // Wait for response with timeout
                try {
                    withTimeout(5000L) {
                        while (!responseComplete) {
                            delay(RESPONSE_POLL_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    throw IOException("Command timeout: $command")
                }
                
                // Return cleaned response
                synchronized(responseBuffer) {
                    responseBuffer.toString()
                        .replace("\r", "")
                        .replace("\n", "")
                        .replace(" ", "")
                        .replace(">", "")
                        .trim()
                }
            }
        }
    }
    
    override fun isHealthy(): Boolean {
        val g = gatt ?: return false
        return try {
            txCharacteristic != null && rxCharacteristic != null
        } catch (e: Exception) {
            false
        }
    }
    
    fun disconnect() {
        gatt?.let {
            if (verboseLogging) {
                android.util.Log.d(TAG, "Disconnecting GATT")
            }
            it.disconnect()
            it.close()
        }
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
    }
    
    override fun close() {
        try {
            disconnect()
        } catch (_: Exception) {}
    }
    
    override fun getTransportType(): String = "BLE (GATT)"
}
