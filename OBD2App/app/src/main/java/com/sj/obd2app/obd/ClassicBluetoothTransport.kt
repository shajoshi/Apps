package com.sj.obd2app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/**
 * Classic Bluetooth (RFCOMM/SPP) transport for ELM327 adapters.
 */
@SuppressLint("MissingPermission")
class ClassicBluetoothTransport(private val device: BluetoothDevice) : Elm327Transport {
    
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val READ_WAIT_MS = 5L
    }
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var bufferedReader: BufferedReader? = null
    private var outputStream: OutputStream? = null
    
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket!!.connect()
            inputStream = socket!!.inputStream
            bufferedReader = BufferedReader(InputStreamReader(socket!!.inputStream, Charsets.ISO_8859_1))
            outputStream = socket!!.outputStream
        }
    }
    
    override suspend fun sendCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            val os = outputStream ?: throw IOException("Not connected")
            val reader = bufferedReader ?: throw IOException("Not connected")
            
            os.write("$command\r".toByteArray(Charsets.ISO_8859_1))
            os.flush()
            
            val response = StringBuilder()
            val startTime = System.currentTimeMillis()
            val timeout = 5000L
            
            while (System.currentTimeMillis() - startTime < timeout) {
                if (!reader.ready()) {
                    Thread.sleep(READ_WAIT_MS)
                    continue
                }
                val c = reader.read()
                if (c < 0) break
                if (c.toChar() == '>') break
                response.append(c.toChar())
            }
            
            response.toString()
                .replace("\r", "")
                .replace("\n", "")
                .replace(" ", "")
                .trim()
        }
    }
    
    override fun isHealthy(): Boolean {
        val sock = socket ?: return false
        return try {
            sock.isConnected && inputStream != null && outputStream != null
        } catch (e: Exception) {
            false
        }
    }
    
    override fun close() {
        try { bufferedReader?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        bufferedReader = null
        inputStream = null
        outputStream = null
        socket = null
    }
    
    override fun getTransportType(): String = "Classic Bluetooth (SPP)"
}
