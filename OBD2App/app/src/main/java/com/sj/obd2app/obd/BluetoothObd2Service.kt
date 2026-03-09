package com.sj.obd2app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.sj.obd2app.settings.AppSettings
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Service that manages the Bluetooth connection to an ELM327-compatible OBD-II adapter.
 * On connection, discovers which PIDs the ECU supports via bitmask queries (0100/0120/0140/0160),
 * then continuously polls only the supported PIDs.
 */
@SuppressLint("MissingPermission")
class BluetoothObd2Service(private val context: Context? = null) : Obd2Service {

    companion object {
        /** Standard SPP (Serial Port Profile) UUID */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** ELM327 initialisation commands */
        private val INIT_COMMANDS = listOf(
            "ATZ",   // Reset
            "ATE0",  // Echo off
            "ATL0",  // Linefeeds off
            "ATS0",  // Spaces off
            "ATH0",  // Headers off
            "ATSP0"  // Auto-detect protocol
        )

        /**
         * Supported-PIDs discovery commands.
         * Each returns a 4-byte (32-bit) bitmask indicating which PIDs are supported
         * in the range starting from (base PID + 1).
         *   0100 → PIDs 01–20
         *   0120 → PIDs 21–40
         *   0140 → PIDs 41–60
         *   0160 → PIDs 61–80
         */
        private val SUPPORTED_PID_QUERIES = listOf(
            0x00 to "0100",
            0x20 to "0120",
            0x40 to "0140",
            0x60 to "0160"
        )

        @Volatile
        private var instance: BluetoothObd2Service? = null

        fun getInstance(context: Context? = null): BluetoothObd2Service {
            return instance ?: synchronized(this) {
                instance ?: BluetoothObd2Service(context?.applicationContext).also { instance = it }
            }
        }
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var pollingJob: Job? = null
    private var supportedPids: Set<Int> = emptySet()

    private val _connectionState = MutableStateFlow(Obd2Service.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<Obd2Service.ConnectionState> = _connectionState

    private val _obd2Data = MutableStateFlow<List<Obd2DataItem>>(emptyList())
    override val obd2Data: StateFlow<List<Obd2DataItem>> = _obd2Data

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    override val connectionLog: StateFlow<List<String>> = _connectionLog

    private fun log(msg: String) {
        _connectionLog.value = _connectionLog.value + msg
    }

    /**
     * Connect to the given Bluetooth device and start polling OBD-II data.
     */
    override fun connect(device: BluetoothDevice?) {
        val btDevice = device ?: return
        if (_connectionState.value == Obd2Service.ConnectionState.CONNECTING ||
            _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
            return
        }

        _connectionState.value = Obd2Service.ConnectionState.CONNECTING
        _errorMessage.value = null
        _connectionLog.value = emptyList()
        log("Connecting to ${btDevice.name ?: btDevice.address}…")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Open RFCOMM socket
                log("Opening Bluetooth socket (RFCOMM/SPP)…")
                socket = btDevice.createRfcommSocketToServiceRecord(SPP_UUID)
                socket!!.connect()
                log("✓ Bluetooth socket connected")

                inputStream = socket!!.inputStream
                outputStream = socket!!.outputStream

                // Initialise ELM327
                log("Initialising ELM327 adapter…")
                delay(1000) // Give the adapter time to boot after ATZ
                for (cmd in INIT_COMMANDS) {
                    log("  → $cmd")
                    sendCommand(cmd)
                    delay(500)
                }
                log("✓ ELM327 initialised")

                // Discover which PIDs the ECU supports
                log("Querying supported PIDs from ECU…")
                supportedPids = discoverSupportedPids()
                log("✓ ${supportedPids.size} PIDs supported — starting data polling")

                _connectionState.value = Obd2Service.ConnectionState.CONNECTED
                startPolling()

            } catch (e: IOException) {
                val msg = "Connection failed: ${e.message}"
                log("✗ $msg")
                _errorMessage.value = msg
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                closeSocket()
            } catch (e: SecurityException) {
                val msg = "Bluetooth permission denied"
                log("✗ $msg")
                _errorMessage.value = msg
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                closeSocket()
            }
        }
    }

    /**
     * Disconnect from the OBD-II adapter.
     */
    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        closeSocket()
        supportedPids = emptySet()
        _connectionState.value = Obd2Service.ConnectionState.DISCONNECTED
        _obd2Data.value = emptyList()
        _connectionLog.value = emptyList()
    }

    /**
     * Discover which Mode 01 PIDs the ECU supports by querying the
     * standard bitmask PIDs (0100, 0120, 0140, 0160).
     *
     * Each response is a 4-byte (32-bit) bitmask where bit 0 (MSB) = base+1,
     * bit 31 (LSB) = base+32.  If bit 31 is set the next range is also available.
     */
    private fun discoverSupportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()

        for ((basePid, query) in SUPPORTED_PID_QUERIES) {
            try {
                val raw = sendCommand(query)

                // Skip if adapter can't answer
                if (raw.contains("NODATA", true) ||
                    raw.contains("UNABLE", true) ||
                    raw.contains("ERROR", true) ||
                    raw.contains("?")
                ) break

                // Response header is "41XX" where XX matches the PID byte
                val header = "41" + query.substring(2)
                val idx = raw.indexOf(header, ignoreCase = true)
                if (idx < 0) break

                val dataHex = raw.substring(idx + header.length)
                if (dataHex.length < 8) break // need 4 bytes = 8 hex chars

                // Parse the 32-bit bitmask
                val mask = dataHex.substring(0, 8).toLongOrNull(16) ?: break

                for (bit in 0 until 32) {
                    // Bit 0 (MSB) corresponds to PID (base+1)
                    if (mask and (1L shl (31 - bit)) != 0L) {
                        supported.add(basePid + bit + 1)
                    }
                }

                // If bit 31 (the "next range available" flag) is NOT set, stop
                if (mask and 1L == 0L) break

            } catch (_: Exception) {
                break
            }
        }

        return supported
    }

    /**
     * Continuously poll only the PIDs that the ECU reported as supported.
     */
    private fun startPolling() {
        // Filter registry to only supported PIDs
        val commandsToQuery = Obd2CommandRegistry.commands.filter { cmd ->
            // Extract the PID number from the command string (e.g. "010C" → 0x0C = 12)
            val pidNumber = cmd.pid.substring(2).toIntOrNull(16) ?: return@filter false
            pidNumber in supportedPids
        }

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
                val results = mutableListOf<Obd2DataItem>()
                for (cmd in commandsToQuery) {
                    if (!isActive) break
                    try {
                        val raw = sendCommand(cmd.pid)
                        val parsed = parseResponse(cmd, raw)
                        if (parsed != null) {
                            results.add(parsed)
                        }
                    } catch (e: Exception) {
                        // Skip on error
                    }
                    val cmdDelay = if (context != null) AppSettings.effectiveCommandDelayMs(context) else AppSettings.DEFAULT_COMMAND_DELAY_MS
                    delay(cmdDelay)
                }
                if (results.isNotEmpty()) {
                    _obd2Data.value = results
                }
                val pollDelay = if (context != null) AppSettings.effectivePollingDelayMs(context) else AppSettings.DEFAULT_POLLING_DELAY_MS
                delay(pollDelay) // Pause between full polling cycles
            }
        }
    }

    /**
     * Send an AT/OBD command and return the raw response string.
     */
    private fun sendCommand(command: String): String {
        val os = outputStream ?: throw IOException("Not connected")
        val iStream = inputStream ?: throw IOException("Not connected")

        // Flush any pending data
        while (iStream.available() > 0) {
            iStream.read()
        }

        // Send command with carriage return
        os.write("$command\r".toByteArray())
        os.flush()

        // Read response until '>' prompt
        val response = StringBuilder()
        val startTime = System.currentTimeMillis()
        val timeout = 5000L

        while (System.currentTimeMillis() - startTime < timeout) {
            if (iStream.available() > 0) {
                val b = iStream.read()
                if (b < 0) break
                val c = b.toChar()
                if (c == '>') break
                response.append(c)
            } else {
                Thread.sleep(10)
            }
        }

        return response.toString()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .trim()
    }

    /**
     * Parse the hex response from the adapter into an [Obd2DataItem].
     * Returns null if the PID is not supported or the response is invalid.
     */
    private fun parseResponse(command: Obd2Command, raw: String): Obd2DataItem? {
        // Responses like "NODATA", "UNABLE TO CONNECT", "?" indicate unsupported PID
        if (raw.contains("NODATA", ignoreCase = true) ||
            raw.contains("UNABLE", ignoreCase = true) ||
            raw.contains("ERROR", ignoreCase = true) ||
            raw.contains("?")
        ) {
            return null
        }

        // Expected response starts with "41XX" where XX = PID byte(s)
        // e.g. for PID "010C" the response header is "410C"
        val expectedHeader = "41" + command.pid.substring(2)
        val headerIdx = raw.indexOf(expectedHeader, ignoreCase = true)
        if (headerIdx < 0) return null

        val dataStart = headerIdx + expectedHeader.length
        val hexData = raw.substring(dataStart)

        // We need command.bytesReturned * 2 hex characters
        val neededChars = command.bytesReturned * 2
        if (hexData.length < neededChars) return null

        val bytes = IntArray(command.bytesReturned)
        for (i in 0 until command.bytesReturned) {
            val hex = hexData.substring(i * 2, i * 2 + 2)
            bytes[i] = hex.toIntOrNull(16) ?: return null
        }

        val value = try {
            command.parse(bytes)
        } catch (e: Exception) {
            return null
        }

        return Obd2DataItem(
            pid = command.pid,
            name = command.name,
            value = value,
            unit = command.unit
        )
    }

    private fun closeSocket() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
    }
}
