package com.sj.obd2app.obd

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/**
 * Service for discovering custom PIDs by brute force scanning.
 * Scans read-only modes (21, 22, 23) across common ECU headers.
 */
class PidDiscoveryService private constructor() {
    
    companion object {
        @Volatile
        private var instance: PidDiscoveryService? = null
        
        fun getInstance(): PidDiscoveryService {
            return instance ?: synchronized(this) {
                instance ?: PidDiscoveryService().also { instance = it }
            }
        }
        
        // Constants for potentially dangerous PID ranges to skip during discovery
        private const val TRANSMISSION_CONTROL_START = 0x80
        private const val TRANSMISSION_CONTROL_END = 0x9F
        private const val ACTUATOR_CONTROL_START = 0xE0
        private const val ACTUATOR_CONTROL_END = 0xEF
        private const val MFG_CONTROL_START = 0x80
        private const val MFG_CONTROL_END = 0xFF
        
        // Constants for PID ranges
        private const val PID_RANGE_START = 0x00
        private const val PID_RANGE_END = 0xFF
        private const val MAX_CONSOLE_MESSAGES = 1000
    }
    
    // Discovery state
    private var discoveryJob: Job? = null
    private val _discoveryState = MutableStateFlow(DiscoveryState.IDLE)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState
    
    private val _discoveryProgress = MutableStateFlow(DiscoveryProgress())
    val discoveryProgress: StateFlow<DiscoveryProgress> = _discoveryProgress
    
    private val _discoveredPids = MutableStateFlow<List<DiscoveredPid>>(emptyList())
    val discoveredPids: StateFlow<List<DiscoveredPid>> = _discoveredPids
    
    private val _consoleOutput = MutableStateFlow<List<String>>(emptyList())
    val consoleOutput: StateFlow<List<String>> = _consoleOutput
    
    // Configuration
    private val commonHeaders = listOf("7E0", "7E1", "7E2", "760", "7E4")
    private val scanModes = listOf("21", "22", "23")
    private val actuatorPidRanges = listOf(
        "2100".."21FF", // Some actuator control PIDs
        "2200".."22FF", // Skip some potentially dangerous ranges
        "2300".."23FF"
    )
    private val discoveryInitCommands = listOf("ATE0", "ATL0", "ATS0")
    
    // Service references
    private var obdService: Obd2Service? = null
    
    /**
     * Start PID discovery with specified options.
     */
    fun startDiscovery(
        obdService: Obd2Service,
        selectedHeaders: List<String> = commonHeaders,
        selectedModes: List<String> = scanModes
    ) {
        // Atomic check-and-set to prevent race condition
        if (!_discoveryState.compareAndSet(DiscoveryState.IDLE, DiscoveryState.SCANNING)) {
            return // State was not IDLE, another discovery is running
        }
        
        this.obdService = obdService
        _discoveredPids.value = emptyList()
        _consoleOutput.value = emptyList()

        (obdService as? BluetoothObd2Service)?.setDiscoveryMode(true)

        discoveryJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                logConsole("Starting PID discovery...")
                if (!initializeAdapterForDiscovery()) {
                    logConsole("Discovery aborted: adapter initialization failed")
                    _discoveryState.value = DiscoveryState.ERROR
                    return@launch
                }
                logConsole("Headers: ${selectedHeaders.joinToString(", ")}")
                logConsole("Modes: ${selectedModes.joinToString(", ")}")
                
                val allDiscovered = mutableListOf<DiscoveredPid>()
                var totalCommands = 0
                var successfulCommands = 0
                
                for (header in selectedHeaders) {
                    logConsole("\n=== Scanning Header $header ===")
                    
                    // Switch to this header
                    if (!switchHeader(header)) {
                        logConsole("Failed to switch to header $header, skipping...")
                        continue
                    }
                    
                    for (mode in selectedModes) {
                        logConsole("Scanning Mode $mode...")
                        
                        // Scan PIDs for this mode
                        val modePids = scanMode(header, mode)
                        allDiscovered.addAll(modePids)
                        totalCommands += getPidCount(mode)
                        successfulCommands += modePids.size
                        
                        // Yield to allow cancellation
                        yield()
                    }
                }
                
                // Update results
                _discoveredPids.value = allDiscovered
                _discoveryState.value = DiscoveryState.COMPLETED
                
                logConsole("\n=== Discovery Complete ===")
                logConsole("Total PIDs found: ${allDiscovered.size}")
                logConsole("Success rate: $successfulCommands/$totalCommands commands")
                
            } catch (e: CancellationException) {
                _discoveryState.value = DiscoveryState.CANCELLED
                logConsole("\nDiscovery cancelled by user")
            } catch (e: Exception) {
                _discoveryState.value = DiscoveryState.ERROR
                logConsole("\nDiscovery error: ${e.message}")
            } finally {
                (obdService as? BluetoothObd2Service)?.setDiscoveryMode(false)
                discoveryJob = null
            }
        }
    }
    
    /**
     * Put the adapter into a clean discovery mode before scanning.
     * Echo, linefeeds, and spaces can corrupt NO DATA responses and break parsing.
     */
    private suspend fun initializeAdapterForDiscovery(): Boolean {
        for (command in discoveryInitCommands) {
            val response = sendCommand(command)
            if (response.contains("ERROR", ignoreCase = true) ||
                response.contains("UNABLE", ignoreCase = true) ||
                response.contains("?")) {
                logConsole("$command -> FAILED: $response")
                return false
            }

            logConsole("$command -> ${response.trim()}")
            delay(100)
        }

        return true
    }
    
    /**
     * Stop the current discovery process.
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        (obdService as? BluetoothObd2Service)?.setDiscoveryMode(false)
        _discoveryState.value = DiscoveryState.CANCELLED
        logConsole("Stopping discovery...")
    }
    
    /**
     * Reset discovery state to IDLE.
     */
    fun reset() {
        stopDiscovery()
        _discoveryState.value = DiscoveryState.IDLE
        _discoveredPids.value = emptyList()
        _consoleOutput.value = emptyList()
        _discoveryProgress.value = DiscoveryProgress()
        obdService = null
    }
    
    /**
     * Switch to specified ECU header.
     */
    private suspend fun switchHeader(header: String): Boolean {
        // Validate header format to prevent command injection
        if (!header.matches(Regex("^[0-9A-F]{3}$"))) {
            logConsole("Invalid header format: $header (must be 3 hex characters)")
            return false
        }
        
        return try {
            val response = sendCommand("AT SH$header")
            val success = response.contains("OK", ignoreCase = true)
            if (success) {
                logConsole("[HEADER $header] -> OK")
            } else {
                logConsole("[HEADER $header] -> ERROR: $response")
            }
            success
        } catch (e: Exception) {
            logConsole("[HEADER $header] -> EXCEPTION: ${e.message}")
            false
        }
    }
    
    /**
     * Scan all PIDs for a specific mode.
     */
    private suspend fun scanMode(header: String, mode: String): List<DiscoveredPid> {
        val discovered = mutableListOf<DiscoveredPid>()
        val pidRange = when (mode) {
            "21" -> PID_RANGE_START..PID_RANGE_END
            "22" -> PID_RANGE_START..PID_RANGE_END
            "23" -> PID_RANGE_START..PID_RANGE_END
            else -> PID_RANGE_START..PID_RANGE_END
        }
        
        var commandsInMode = 0
        
        for (pid in pidRange) {
            // Check if we should cancel
            if (discoveryJob?.isActive == false) break
            
            // Skip potentially dangerous PIDs
            if (isActuatorPid(mode, pid)) {
                continue
            }
            
            val pidHex = pid.toString(16).uppercase().padStart(2, '0')
            val command = "$mode$pidHex"
            commandsInMode++
            
            // Update progress
            val totalScanned = getScannedCount(header, mode, pid)
            val totalToScan = getTotalScanCount()
            _discoveryProgress.value = DiscoveryProgress(
                currentHeader = header,
                currentMode = mode,
                currentPid = command,
                scanned = totalScanned,
                total = totalToScan
            )
            
            // Send command and parse response
            val response = sendCommand(command)
            val discoveredPid = parseResponse(header, mode, pidHex, response)
            
            if (discoveredPid != null) {
                discovered.add(discoveredPid)
                logConsole("[HEADER $header] $command: VALID (${discoveredPid.byteCount} bytes) -> $response")
            } else {
                when {
                    response.contains("NODATA", ignoreCase = true) -> {
                        logConsole("[HEADER $header] $command: NODATA")
                    }
                    response.contains("ERROR", ignoreCase = true) -> {
                        logConsole("[HEADER $header] $command: ERROR")
                    }
                    response.contains("UNABLE", ignoreCase = true) -> {
                        logConsole("[HEADER $header] $command: UNABLE TO CONNECT")
                    }
                    response.contains("?") -> {
                        logConsole("[HEADER $header] $command: NO RESPONSE")
                    }
                    else -> {
                        logConsole("[HEADER $header] $command: UNKNOWN -> $response")
                    }
                }
            }
            
            // Rate limiting - 100ms delay
            delay(100)
        }
        
        return discovered
    }
    
    /**
     * Send command to OBD service.
     */
    private suspend fun sendCommand(command: String): String {
        return try {
            val service = obdService
            when (service) {
                is MockObd2Service -> {
                    service.sendCommand(command)
                }
                is BluetoothObd2Service -> {
                    service.sendCommandForDiscovery(command)
                }
                else -> "NO SERVICE"
            }
        } catch (e: Exception) {
            "EXCEPTION: ${e.message}"
        }
    }
    
    /**
     * Parse OBD response and create DiscoveredPid if valid.
     */
    private fun parseResponse(header: String, mode: String, pid: String, response: String): DiscoveredPid? {
        // Skip error responses
        if (response.contains("NODATA", ignoreCase = true) ||
            response.contains("ERROR", ignoreCase = true) ||
            response.contains("UNABLE", ignoreCase = true) ||
            response.contains("?") ||
            response.contains("EXCEPTION")) {
            return null
        }
        
        // Expected response format: "62 04 56 01 F4" for Mode 22 PID 0456
        // Response byte = mode + 0x40, so mode 22 → response byte 62
        val expectedResponseByte = when (mode) {
            "21" -> "61"
            "22" -> "62" 
            "23" -> "63"
            else -> return null
        }
        
        // Normalize: strip all spaces and work with raw hex
        val rawHex = response.replace(" ", "").uppercase()
        
        // Validate hex string format
        if (!rawHex.matches(Regex("^[0-9A-F]*$"))) {
            logConsole("Invalid hex characters in response: $response")
            return null
        }
        
        val matchPrefix = "$expectedResponseByte$pid".uppercase()
        
        val prefixIndex = rawHex.indexOf(matchPrefix)
        if (prefixIndex < 0) return null
        
        // Data bytes start after the prefix
        val dataHex = rawHex.substring(prefixIndex + matchPrefix.length)
        if (dataHex.isEmpty()) return null
        
        // Split into 2-char byte pairs
        val dataBytes = dataHex.chunked(2).filter { it.length == 2 }
        if (dataBytes.isEmpty()) return null
        
        val byteCount = dataBytes.size
        val suggestedFormula = suggestFormula(byteCount, dataBytes)
        val suggestedName = suggestName(mode, pid)
        val suggestedUnit = suggestUnit(mode, pid, suggestedFormula)
        
        return DiscoveredPid(
            header = header,
            mode = mode,
            pid = pid,
            response = response.trim(),
            byteCount = byteCount,
            suggestedName = suggestedName,
            suggestedUnit = suggestedUnit,
            suggestedFormula = suggestedFormula
        )
    }
    
    /**
     * Check if PID might be an actuator control (skip for safety).
     */
    private fun isActuatorPid(mode: String, pid: Int): Boolean {
        // Skip known potentially dangerous ranges
        return when (mode) {
            "21" -> pid in TRANSMISSION_CONTROL_START..TRANSMISSION_CONTROL_END // Some transmission control ranges
            "22" -> pid in ACTUATOR_CONTROL_START..ACTUATOR_CONTROL_END // Some actuator ranges
            "23" -> pid in MFG_CONTROL_START..MFG_CONTROL_END // Manufacturer-specific control
            else -> false
        }
    }
    
    /**
     * Suggest formula based on byte count and data patterns.
     */
    private fun suggestFormula(byteCount: Int, dataBytes: List<String>): String {
        return when (byteCount) {
            1 -> {
                val value = dataBytes[0].toIntOrNull(16) ?: 0
                when {
                    value > 200 -> "A-40" // Likely temperature
                    value > 100 -> "A*0.5" // Likely percentage
                    else -> "A" // Raw value
                }
            }
            2 -> {
                val high = dataBytes[0].toIntOrNull(16) ?: 0
                val low = dataBytes[1].toIntOrNull(16) ?: 0
                val combined = (high shl 8) or low
                
                when {
                    combined > 0x8000 -> "((A*256)+B)-32768" // Signed 16-bit
                    combined > 10000 -> "((A*256)+B)/100" // Scaled value
                    else -> "((A*256)+B)" // Raw 16-bit
                }
            }
            else -> "A" // Default to first byte
        }
    }
    
    /**
     * Suggest name based on mode and PID.
     */
    private fun suggestName(mode: String, pid: String): String {
        return when (mode) {
            "21" -> "Transmission PID $pid"
            "22" -> "Extended PID $pid"
            "23" -> "Manufacturer PID $pid"
            else -> "Custom PID $mode$pid"
        }
    }
    
    /**
     * Suggest unit based on formula and context.
     */
    private fun suggestUnit(mode: String, pid: String, formula: String): String {
        return when {
            formula.contains("-40") -> "°C"
            formula.contains("*0.5") -> "%"
            formula.contains("/100") -> when {
                pid.contains("56") || pid.contains("57") -> "°/s" // Yaw/acceleration
                else -> "unit"
            }
            else -> ""
        }
    }
    
    /**
     * Log message to console output.
     */
    private fun logConsole(message: String) {
        val timestamp = System.currentTimeMillis()
        val newMessage = "[$timestamp] $message"
        val current = _consoleOutput.value.toMutableList()
        current.add(newMessage)
        
        // Keep only last MAX_CONSOLE_MESSAGES messages to prevent memory issues
        if (current.size > MAX_CONSOLE_MESSAGES) {
            current.removeAt(0)
        }
        _consoleOutput.value = current
    }
    
    /**
     * Get number of PIDs to scan for a mode.
     */
    private fun getPidCount(mode: String): Int {
        return when (mode) {
            "21", "22", "23" -> PID_RANGE_END - PID_RANGE_START + 1 // 00 to FF
            else -> 0
        }
    }
    
    /**
     * Calculate total number of commands to scan.
     */
    private fun getTotalScanCount(): Int {
        return scanModes.sumOf { getPidCount(it) }
    }
    
    /**
     * Calculate current progress count.
     */
    private fun getScannedCount(currentHeader: String, currentMode: String, currentPid: Int): Int {
        val headerIndex = commonHeaders.indexOf(currentHeader)
        val modeIndex = scanModes.indexOf(currentMode)
        
        var count = 0
        
        // Full headers before current
        for (h in 0 until headerIndex) {
            for (m in scanModes) {
                count += getPidCount(m)
            }
        }
        
        // Full modes before current in current header
        for (m in 0 until modeIndex) {
            count += getPidCount(scanModes[m])
        }
        
        // Current progress in current mode
        count += currentPid + 1
        
        return count
    }
}

/**
 * Discovery state enumeration.
 */
enum class DiscoveryState {
    IDLE,
    SCANNING,
    COMPLETED,
    CANCELLED,
    ERROR
}

/**
 * Discovery progress data.
 */
data class DiscoveryProgress(
    val currentHeader: String = "",
    val currentMode: String = "",
    val currentPid: String = "",
    val scanned: Int = 0,
    val total: Int = 0
) {
    val progressPercent: Int
        get() = if (total > 0) (scanned * 100) / total else 0
}
