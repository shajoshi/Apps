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
import kotlinx.coroutines.Dispatchers
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
        private val ELM327_TX_CHAR_UUID_1 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val ELM327_TX_CHAR_UUID_2 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val ELM327_RX_CHAR_UUID_1 = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val ELM327_RX_CHAR_UUID_2 = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        
        // Nordic UART Service (NUS) - used by some adapters
        private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        
        // Client Characteristic Configuration Descriptor (for enabling notifications)
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    
    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    
    private val responseMutex = Mutex()
    private val responseBuffer = StringBuilder()
    private var responseComplete = false
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    android.util.Log.d(TAG, "GATT connected, discovering services...")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    android.util.Log.d(TAG, "GATT disconnected")
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.d(TAG, "Services discovered")
                discoverCharacteristics(gatt)
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
        android.util.Log.v(TAG, "RX: $data")
        
        synchronized(responseBuffer) {
            responseBuffer.append(data)
            if (data.contains('>')) {
                responseComplete = true
            }
        }
    }
    
    private fun discoverCharacteristics(gatt: BluetoothGatt) {
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
        
        // Enable notifications on RX characteristic
        rxCharacteristic?.let { rx ->
            gatt.setCharacteristicNotification(rx, true)
            val descriptor = rx.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                android.util.Log.d(TAG, "Enabled notifications on RX characteristic")
            }
        }
    }
    
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            gatt = suspendCancellableCoroutine { continuation ->
                val g = device.connectGatt(context, false, gattCallback)
                if (g == null) {
                    continuation.resumeWithException(IOException("Failed to connect GATT"))
                } else {
                    gatt = g
                    // Wait for service discovery
                    Thread.sleep(2000)
                    
                    if (txCharacteristic == null || rxCharacteristic == null) {
                        g.close()
                        continuation.resumeWithException(
                            IOException("Failed to discover ELM327 characteristics")
                        )
                    } else {
                        continuation.resume(g)
                    }
                }
            }
        }
    }
    
    override suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            responseMutex.withLock {
                val tx = txCharacteristic ?: throw IOException("Not connected")
                val g = gatt ?: throw IOException("Not connected")
                
                // Clear previous response
                synchronized(responseBuffer) {
                    responseBuffer.clear()
                    responseComplete = false
                }
                
                // Send command
                val cmdBytes = "$command\r".toByteArray(Charsets.ISO_8859_1)
                android.util.Log.v(TAG, "TX: $command")
                
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(tx, cmdBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = cmdBytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(tx)
                }
                
                if (success != BluetoothGatt.GATT_SUCCESS && !success.toString().toBoolean()) {
                    throw IOException("Failed to write characteristic")
                }
                
                // Wait for response with timeout
                try {
                    withTimeout(5000L) {
                        while (!responseComplete) {
                            Thread.sleep(10)
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
    
    override fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
        rxCharacteristic = null
    }
    
    override fun getTransportType(): String = "BLE (GATT)"
}
