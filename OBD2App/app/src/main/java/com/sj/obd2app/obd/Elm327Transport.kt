package com.sj.obd2app.obd

import java.io.IOException

/**
 * Abstraction for communication with an ELM327 adapter.
 * Implementations handle either Classic Bluetooth (RFCOMM/SPP) or BLE (GATT).
 */
interface Elm327Transport {
    
    /**
     * Establish connection to the device.
     * @throws IOException if connection fails
     */
    suspend fun connect()
    
    /**
     * Send a command and return the raw response.
     * @param command The AT/OBD command to send (without CR)
     * @return Raw response string from the adapter
     * @throws IOException if communication fails
     */
    suspend fun sendCommand(command: String): String
    
    /**
     * Check if the connection is still healthy.
     */
    fun isHealthy(): Boolean
    
    /**
     * Close the connection and release resources.
     */
    fun close()
    
    /**
     * Get a human-readable description of this transport type.
     */
    fun getTransportType(): String
}
