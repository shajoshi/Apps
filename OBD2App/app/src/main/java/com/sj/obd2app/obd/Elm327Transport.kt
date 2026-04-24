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

    // ── Streaming (CAN monitor mode) ──────────────────────────────────────────
    //
    // Monitor-mode commands (e.g. ATMA) return a continuous stream of lines ended by \r,
    // without the terminating '>' prompt. Standard [sendCommand] blocks waiting for '>'
    // and is therefore unusable here. These optional methods support raw streaming;
    // implementations that cannot stream (e.g. BLE) should throw [UnsupportedOperationException].

    /**
     * Send [command] terminated by CR, without consuming any response.
     * Intended for commands whose output is then consumed via [readStreamLine].
     */
    suspend fun sendRaw(command: String) {
        throw UnsupportedOperationException("sendRaw not supported by ${getTransportType()}")
    }

    /**
     * Read the next line (terminated by `\r` or `\n`) from the adapter, or `null` if no data
     * arrived within [timeoutMs]. Does not wait for the `>` prompt. Strips whitespace.
     * Returns an empty string for blank lines (useful as a heartbeat).
     */
    suspend fun readStreamLine(timeoutMs: Long): String? {
        throw UnsupportedOperationException("readStreamLine not supported by ${getTransportType()}")
    }

    /**
     * Consume and discard any bytes currently buffered on the input stream. Used when exiting
     * monitor mode to flush residual frames and the `>` prompt.
     */
    suspend fun drainInput(timeoutMs: Long = 150L) {
        // default: no-op
    }
}
