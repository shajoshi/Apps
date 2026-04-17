package com.sj.obd2app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.widget.Toast
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.CachedPidEntry
import com.sj.obd2app.settings.VehicleProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Service that manages the Bluetooth connection to an ELM327-compatible OBD-II adapter.
 * On connection, discovers which PIDs the ECU supports via bitmask queries (0100/0120/0140/0160),
 * then continuously polls only the supported PIDs.
 */
@SuppressLint("MissingPermission")
class BluetoothObd2Service(private val context: Context? = null) : Obd2Service {

    companion object {
        private const val TAG = "BluetoothObd2Service"

        /** Grace period before first poll cycle to let ECU finish power-on self-test */
        private const val STARTUP_GRACE_DELAY_MS = 3000L

        /** Extra delay after ATZ because some adapters/vehicles need a longer reset settle time. */
        private const val ATZ_SETTLE_DELAY_MS = 3000L

        /** Standard delay between non-reset init commands. */
        private const val INIT_COMMAND_DELAY_MS = 750L

        /** Extra delay after protocol selection before querying supported PIDs. */
        private const val PROTOCOL_SETTLE_DELAY_MS = 2000L

        /** Delay between supported PID discovery queries to reduce bus pressure. */
        private const val DISCOVERY_QUERY_DELAY_MS = 50L

        /** ELM327 initialisation commands (without protocol selection) */
        private val BASE_INIT_COMMANDS = listOf(
            "ATZ",   // Reset
            "ATE0",  // Echo off
            "ATL0",  // Linefeeds off
            "ATS0",  // Spaces off
            "ATH0",  // Headers off
            "ATAT1"  // Adaptive timing — ELM learns ECU latency, tightens timeout
        )

        /**
         * Build init commands with protocol selection.
         * If we have a cached protocol from a previous session, use it directly
         * (ATSP<N>) to avoid CAN bus probing noise that can trigger U-codes.
         * Otherwise, set max timeout and auto-detect.
         */
        private fun buildInitCommands(cachedProtocol: String?): List<String> {
            return if (cachedProtocol != null) {
                BASE_INIT_COMMANDS + "ATSP$cachedProtocol" // Skip probing — use known protocol
            } else {
                BASE_INIT_COMMANDS + listOf(
                    "ATSTFF", // Max timeout (~1s) — gives ECU time during protocol detection
                    "ATSP0"   // Auto-detect protocol
                )
            }
        }

        /**
         * Fast-tier PIDs — polled every cycle (~200ms target).
         * These are the high-frequency metrics needed for trip recording.
         */
        val FAST_PIDS = setOf(
            "010C",  // RPM
            "010D",  // Vehicle Speed
            "0110",  // MAF
            "0111",  // Throttle
            "015E"   // Engine Fuel Rate
        )

        /** Slow-tier PIDs are polled once every SLOW_TIER_MODULO fast cycles. */
        const val SLOW_TIER_MODULO = 5

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

    private var transport: Elm327Transport? = null
    private var pollingJob: Job? = null
    private var supportedPids: Set<Int> = emptySet()
    
    // Connection health monitoring
    private var consecutiveFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 10  // ~2-3 seconds of failures
    private var discoveryFailureBypassEnabled = false
    
    // Bluetooth connection logger removed - now using logcat only

    private val _connectionState = MutableStateFlow(Obd2Service.ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<Obd2Service.ConnectionState> = _connectionState

    private val _obd2Data = MutableStateFlow<List<Obd2DataItem>>(emptyList())
    override val obd2Data: StateFlow<List<Obd2DataItem>> = _obd2Data

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    override val connectionLog: StateFlow<List<String>> = _connectionLog

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    private fun log(msg: String) {
        _connectionLog.value = _connectionLog.value + msg
    }

    /**
     * Connect to the given Bluetooth device and start polling OBD-II data.
     * Automatically detects whether to use Classic Bluetooth or BLE.
     */
    override fun connect(device: BluetoothDevice?, forceBle: Boolean) {
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
                // Create the transport based on device type and forceBle setting
                transport = createTransport(btDevice, forceBle)
                log("Detected device type: ${transport!!.getTransportType()}")
                log("Establishing connection…")
                transport!!.connect()
                log("✓ Connection established")

                // Look up cached protocol for this device
                val cachedProto = context?.let {
                    AppSettings.getCachedProtocol(it, btDevice.address)
                }
                if (cachedProto != null) {
                    log("Using cached protocol: $cachedProto")
                } else {
                    log("No cached protocol — will auto-detect")
                }

                // Initialise ELM327
                log("Initialising ELM327 adapter…")
                delay(1000)
                val initCommands = buildInitCommands(cachedProto)
                for (cmd in initCommands) {
                    log("  → $cmd")
                    transport!!.sendCommand(cmd)
                    // ATZ (reset) needs extra time — clones can take a few seconds
                    val cmdDelay = if (cmd == "ATZ") ATZ_SETTLE_DELAY_MS else INIT_COMMAND_DELAY_MS
                    delay(cmdDelay)
                }
                log("✓ ELM327 initialised")

                // Let the CAN bus settle after protocol detection before
                // sending the first real OBD command (0100).  This avoids
                // flooding the bus while the ECU is still negotiating,
                // which can trigger U-codes (e.g. U0009) on some bikes.
                log("Waiting for CAN bus to settle…")
                delay(if (cachedProto != null) 1000L else 2000L)

                // Discover which PIDs the ECU supports
                log("Querying supported PIDs from ECU…")
                supportedPids = discoverSupportedPids()
                log("✓ ${supportedPids.size} PIDs supported — starting data polling")

                if (supportedPids.isEmpty()) {
                    // ELM returned no PIDs — adapter may not be properly powered or connected to ECU.
                    // Restore supportedPids from the last good cache so polling can still run.
                    context?.let { ctx ->
                        val cachedEntries = AppSettings.getPidCache(ctx, btDevice.address).orEmpty()
                        if (cachedEntries.isNotEmpty()) {
                            supportedPids = cachedEntries.values
                                .mapNotNull { it.rawPidId.toIntOrNull(16) }
                                .toSet()
                            log("⚠ 0 PIDs from ECU — restored ${supportedPids.size} PIDs from cache")
                        } else {
                            log("⚠ 0 PIDs from ECU and no cached PIDs available")
                        }
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                ctx,
                                "ELM returned 0 PIDs — check adapter power. Using cached PIDs.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    // Skip cache and protocol writes — do not overwrite a good cache with bad data
                } else {
                    // Cache discovered PIDs for this MAC address
                    context?.let { ctx ->
                        cacheDiscoveredPids(ctx, btDevice.address, supportedPids)
                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(ctx, "${supportedPids.size} PIDs supported", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // Lock protocol after auto-detection to skip negotiation on each send
                    val detectedProto = transport!!.sendCommand("ATDPN").trim()
                    if (detectedProto.isNotBlank() && detectedProto != "0" && !detectedProto.contains("?")) {
                        transport!!.sendCommand("ATSP$detectedProto")
                        log("✓ Protocol locked: ATSP$detectedProto")
                        delay(PROTOCOL_SETTLE_DELAY_MS)
                        // Cache the protocol so next connection skips ATSP0 probing
                        if (detectedProto != cachedProto) {
                            context?.let { ctx ->
                                val existingPids = AppSettings.getPidCache(ctx, btDevice.address) ?: emptyMap()
                                AppSettings.savePidCache(ctx, btDevice.address, existingPids, detectedProto)
                                log("✓ Protocol cached for future connections")
                            }
                        }
                    }
                }

                _connectedDeviceName.value = btDevice.name ?: btDevice.address
                _connectionState.value = Obd2Service.ConnectionState.CONNECTED
                startPolling()

            } catch (e: IOException) {
                val msg = "Connection failed: ${e.message}"
                log("✗ $msg")
                _errorMessage.value = msg
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                closeTransport()
            } catch (e: SecurityException) {
                val msg = "Bluetooth permission denied"
                log("✗ $msg")
                _errorMessage.value = msg
                _connectionState.value = Obd2Service.ConnectionState.ERROR
                closeTransport()
            }
        }
    }

    /**
     * Auto-detect device type and create appropriate transport.
     * If forceBle is true, always use BLE regardless of device type.
     * Otherwise, tries Classic Bluetooth first, falls back to BLE if device type is LE.
     */
    private fun createTransport(device: BluetoothDevice, forceBle: Boolean = false): Elm327Transport {
        // If force BLE is enabled, always use BLE transport
        if (forceBle) {
            log("Force BLE enabled - using BLE transport")
            return BleTransport(context ?: throw IllegalStateException("Context required for BLE"), device)
        }
        
        // Otherwise, auto-detect based on device type
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> {
                log("Device type: BLE only")
                BleTransport(context ?: throw IllegalStateException("Context required for BLE"), device)
            }
            BluetoothDevice.DEVICE_TYPE_DUAL -> {
                log("Device type: Dual mode (trying Classic first)")
                // For dual-mode devices, prefer Classic BT as it's more reliable for serial data
                ClassicBluetoothTransport(device)
            }
            else -> {
                log("Device type: Classic Bluetooth")
                ClassicBluetoothTransport(device)
            }
        }
    }

    /**
     * Disconnect from the OBD-II adapter.
     */
    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        closeTransport()
        supportedPids = emptySet()
        consecutiveFailures = 0
        discoveryFailureBypassEnabled = false
        _connectedDeviceName.value = null
        _connectionState.value = Obd2Service.ConnectionState.DISCONNECTED
        _connectionLog.value = emptyList()
    }

    /**
     * Enable or disable discovery mode.
     *
     * While discovery is active, consecutive failures are expected and should not
     * trigger the normal connection-loss cutoff.
     */
    fun setDiscoveryMode(enabled: Boolean) {
        discoveryFailureBypassEnabled = enabled
        consecutiveFailures = 0
    }

    /**
     * Discover which Mode 01 PIDs the ECU supports by querying the
     * standard bitmask PIDs (0100, 0120, 0140, 0160).
     *
     * Each response is a 4-byte (32-bit) bitmask where bit 0 (MSB) = base+1,
     * bit 31 (LSB) = base+32.  If bit 31 is set the next range is also available.
     */
    private suspend fun discoverSupportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()

        for ((basePid, query) in SUPPORTED_PID_QUERIES) {
            try {
                val raw = transport!!.sendCommand(query)

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

                // Give the ECU a little breathing room before the next discovery query.
                delay(DISCOVERY_QUERY_DELAY_MS)

            } catch (e: Exception) {
                android.util.Log.w(TAG, "Supported PID discovery failed for $query: ${e.message}", e)
                throw IOException("Supported PID discovery failed for $query", e)
            }
        }

        return supported
    }

    /**
     * Continuously poll only the PIDs that the ECU reported as supported.
     *
     * PIDs are split into two tiers:
     *  - Fast tier (RPM, speed, MAF, throttle, fuel rate): polled every cycle
     *  - Slow tier (temps, fuel level, etc.): polled every SLOW_TIER_MODULO cycles
     *
     * Inter-command delay prevents CAN bus flooding; inter-cycle delay controls
     * overall polling rate.  Both are read from AppSettings so the user can tune them.
     * A startup grace period lets the ECU finish power-on self-test before polling.
     */
    private fun startPolling() {
        // Split supported commands into fast and slow tiers
        val allCommands = Obd2CommandRegistry.commands.filter { cmd ->
            val pidNumber = cmd.pid.substring(2).toIntOrNull(16) ?: return@filter false
            pidNumber in supportedPids
        }
        val fastCommands = allCommands.filter { it.pid in FAST_PIDS }
        val slowCommands = allCommands.filter { it.pid !in FAST_PIDS }

        // Load custom/extended PIDs from active vehicle profile (includes manufacturer presets)
        val customPids = context?.let { ctx ->
            VehicleProfileRepository.getInstance(ctx).activeProfile?.effectiveCustomPids
                ?.filter { it.enabled }
        } ?: emptyList()
        if (customPids.isNotEmpty()) {
            android.util.Log.d(TAG, "Custom PIDs configured: ${customPids.size} (incl. manufacturer presets)")
        }

        // Read delay settings once — avoids repeated file I/O inside the loop
        val commandDelayMs = context?.let { AppSettings.getGlobalCommandDelayMs(it) }
            ?: AppSettings.DEFAULT_COMMAND_DELAY_MS
        val pollingDelayMs = context?.let { AppSettings.getGlobalPollingDelayMs(it) }
            ?: AppSettings.DEFAULT_POLLING_DELAY_MS
        android.util.Log.d(TAG, "Polling config: commandDelay=${commandDelayMs}ms, cycleDelay=${pollingDelayMs}ms, startupGrace=${STARTUP_GRACE_DELAY_MS}ms")

        // Cached results — slow-tier values persist between cycles
        val cachedResults = mutableMapOf<String, Obd2DataItem>()
        
        // Reset failure counter when starting polling
        consecutiveFailures = 0

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            // Startup grace period — let the ECU finish power-on self-test
            android.util.Log.d(TAG, "Startup grace period: waiting ${STARTUP_GRACE_DELAY_MS}ms before polling")
            delay(STARTUP_GRACE_DELAY_MS)

            var cycleCount = 0
            // Warm-up: wait until the first slow-tier poll completes before
            // emitting data so the UI sees a stable, complete PID list.
            var warmUpComplete = false
            while (isActive && _connectionState.value == Obd2Service.ConnectionState.CONNECTED) {
                var cycleSuccessCount = 0
                
                // Check socket health every 10 cycles (~2 seconds)
                if (cycleCount % 10 == 0 && !isSocketHealthy()) {
                    android.util.Log.e(TAG, "Socket health check failed - marking connection as lost")
                    _connectionState.value = Obd2Service.ConnectionState.ERROR
                    _errorMessage.value = "Bluetooth socket disconnected"
                    break
                }
                
                // Poll fast-tier PIDs every cycle
                for (cmd in fastCommands) {
                    if (!isActive) break
                    try {
                        val raw = transport?.sendCommand(cmd.pid) ?: continue
                        val parsed = parseResponse(cmd, raw)
                        if (parsed != null) {
                            cachedResults[cmd.pid] = parsed
                            cycleSuccessCount++
                        }
                    } catch (e: IOException) {
                        android.util.Log.w(TAG, "PID ${cmd.pid} IOException: ${e.message}")
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "PID ${cmd.pid} error: ${e.message}")
                    }
                    if (commandDelayMs > 0) delay(commandDelayMs)
                }

                // Poll slow-tier PIDs every SLOW_TIER_MODULO cycles
                if (cycleCount % SLOW_TIER_MODULO == 0) {
                    for (cmd in slowCommands) {
                        if (!isActive) break
                        try {
                            val raw = transport?.sendCommand(cmd.pid) ?: continue
                            val parsed = parseResponse(cmd, raw)
                            if (parsed != null) {
                                cachedResults[cmd.pid] = parsed
                                cycleSuccessCount++
                            }
                        } catch (e: IOException) {
                            android.util.Log.w(TAG, "PID ${cmd.pid} IOException: ${e.message}")
                        } catch (e: Exception) {
                            android.util.Log.w(TAG, "PID ${cmd.pid} error: ${e.message}")
                        }
                        if (commandDelayMs > 0) delay(commandDelayMs)
                    }

                    // Poll custom/extended PIDs (same cadence as slow tier)
                    pollCustomPids(customPids, cachedResults, commandDelayMs)?.let { count ->
                        cycleSuccessCount += count
                    }
                }

                // Check if we got any successful reads this cycle
                if (cycleSuccessCount == 0) {
                    if (discoveryFailureBypassEnabled) {
                        continue
                    }

                    consecutiveFailures++
                    android.util.Log.w(TAG, "Polling cycle failed, consecutive failures: $consecutiveFailures")
                    
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        android.util.Log.e(TAG, "Too many consecutive failures ($consecutiveFailures) - marking connection as lost")
                        _connectionState.value = Obd2Service.ConnectionState.ERROR
                        _errorMessage.value = "Connection lost - no data received"
                        break
                    }
                } else {
                    // Reset counter on successful read
                    if (consecutiveFailures > 0) {
                        android.util.Log.d(TAG, "Polling cycle succeeded, resetting failure counter (was $consecutiveFailures)")
                    }
                    consecutiveFailures = 0
                }

                // Mark warm-up complete after the first slow-tier cycle
                if (!warmUpComplete && cycleCount >= SLOW_TIER_MODULO) {
                    warmUpComplete = true
                    android.util.Log.d(TAG, "Warm-up complete — ${cachedResults.size} PIDs cached")
                }

                // Only emit to UI after warm-up so the list appears fully populated
                // and sorted by PID for deterministic, stable row ordering.
                if (warmUpComplete && cachedResults.isNotEmpty()) {
                    _obd2Data.value = cachedResults.values.sortedBy { it.pid }
                }

                cycleCount++
                // Inter-cycle delay — controls overall polling rate and gives ECU breathing room
                delay(pollingDelayMs)
            }
            
            // Cleanup after polling stops due to error
            if (_connectionState.value == Obd2Service.ConnectionState.ERROR) {
                android.util.Log.d(TAG, "Polling stopped due to error, closing transport")
                closeTransport()
            }
        }
    }

    /**
     * Public method for PID discovery to send commands.
     */
    suspend fun sendCommandForDiscovery(command: String): String {
        return transport?.sendCommand(command) ?: throw IOException("Not connected")
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

    /**
     * Check if the transport connection is still healthy.
     */
    private fun isSocketHealthy(): Boolean {
        return transport?.isHealthy() ?: false
    }

    /**
     * Poll custom/extended PIDs, grouping by header to minimise AT SH switches.
     * Returns the number of successful reads, or null if no custom PIDs configured.
     */
    private suspend fun pollCustomPids(
        customPids: List<CustomPid>,
        cachedResults: MutableMap<String, Obd2DataItem>,
        commandDelayMs: Long = 0L
    ): Int? {
        if (customPids.isEmpty()) return null

        var successCount = 0
        var currentHeader: String? = null

        // Group by header to minimise AT SH commands
        val grouped = customPids.groupBy { it.header.uppercase().ifEmpty { "7DF" } }

        for ((header, pids) in grouped) {
            // Switch header if different from current
            if (currentHeader != header) {
                try {
                    transport?.sendCommand("ATSH$header")
                    if (commandDelayMs > 0) delay(commandDelayMs)
                    currentHeader = header
                    android.util.Log.d(TAG, "Custom PID: switched header to $header")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to switch header to $header: ${e.message}")
                    continue // Skip this header group
                }
            }

            for (cp in pids) {
                try {
                    val raw = transport?.sendCommand(cp.commandString) ?: continue
                    val parsed = parseExtendedResponse(cp, raw)
                    if (parsed != null) {
                        cachedResults[cp.cacheKey] = parsed
                        successCount++
                    }
                } catch (e: IOException) {
                    android.util.Log.w(TAG, "Custom PID ${cp.name} IOException: ${e.message}")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Custom PID ${cp.name} error: ${e.message}")
                }
                if (commandDelayMs > 0) delay(commandDelayMs)
            }
        }

        // Restore default header after custom PIDs
        if (currentHeader != null) {
            try {
                transport?.sendCommand("ATSH7DF")
                android.util.Log.d(TAG, "Custom PID: restored default header 7DF")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to restore default header: ${e.message}")
            }
        }

        return successCount
    }

    /**
     * Parse the response from an extended/custom PID command.
     *
     * Extended responses use the format: (mode+0x40) + pid + data bytes
     * e.g. for Mode 22, PID 0456: response header is "620456", then data follows.
     */
    private fun parseExtendedResponse(customPid: CustomPid, raw: String): Obd2DataItem? {
        if (raw.contains("NODATA", ignoreCase = true) ||
            raw.contains("UNABLE", ignoreCase = true) ||
            raw.contains("ERROR", ignoreCase = true) ||
            raw.contains("?")
        ) {
            return null
        }

        val expectedHeader = customPid.responseHeader
        if (expectedHeader.isEmpty()) return null

        val headerIdx = raw.indexOf(expectedHeader, ignoreCase = true)
        if (headerIdx < 0) return null

        val dataStart = headerIdx + expectedHeader.length
        val hexData = raw.substring(dataStart)

        val neededChars = customPid.bytesReturned * 2
        if (hexData.length < neededChars) return null

        val bytes = IntArray(customPid.bytesReturned)
        for (i in 0 until customPid.bytesReturned) {
            val hex = hexData.substring(i * 2, i * 2 + 2)
            bytes[i] = hex.toIntOrNull(16) ?: return null
        }

        val value = try {
            val result = PidFormulaParser.evaluate(customPid.formula, bytes, customPid.signed)
            PidFormulaParser.format(result)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Custom PID ${customPid.name} formula error: ${e.message}")
            return null
        }

        return Obd2DataItem(
            pid = customPid.cacheKey,
            name = customPid.name,
            value = value,
            unit = customPid.unit
        )
    }

    private fun cacheDiscoveredPids(context: Context, macAddress: String, supportedPids: Set<Int>) {
        try {
            val previousCache = AppSettings.getPidCache(context, macAddress).orEmpty().toMutableMap()
            val pidMap = previousCache
            
            supportedPids.forEach { pidNumber ->
                val rawPidId = pidNumber.toString(16).uppercase().padStart(2, '0')
                val commandString = "01$rawPidId"

                // Find the command with this PID number
                val command = Obd2CommandRegistry.commands.find { cmd ->
                    cmd.pid.substring(2).toIntOrNull(16) == pidNumber
                }

                val displayName = command?.name ?: "PID $rawPidId"
                val cachedValue = pidMap[displayName]?.value.orEmpty()

                pidMap[displayName] = CachedPidEntry(
                    rawPidId = rawPidId,
                    commandString = command?.pid ?: commandString,
                    displayName = displayName,
                    value = cachedValue
                )
            }
            
            // Save to AppSettings
            AppSettings.savePidCache(context, macAddress, pidMap)
            log("✓ Cached ${pidMap.size} PIDs for MAC: $macAddress")
            
        } catch (e: Exception) {
            log("✗ Failed to cache PIDs: ${e.message}")
        }
    }

    private fun closeTransport() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
    }
}
