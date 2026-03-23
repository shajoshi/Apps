package com.sj.obd2app.obd

import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.random.Random

/**
 * Processes OBD commands for the mock emulator, simulating ELM327 behavior.
 * Handles AT commands, header switching, and PID queries with realistic timing.
 */
class MockObd2CommandProcessor(private val mockData: JSONObject) {
    
    private var currentHeader = "7DF"
    private var failureRate = 0.1
    private val nodataPids = mutableSetOf<String>()
    private val errorPids = mutableSetOf<String>()
    
    init {
        loadErrorSimulation()
    }
    
    /**
     * Process an OBD command with realistic timing and return response.
     */
    suspend fun processCommand(command: String): String {
        // Simulate realistic 100ms delay
        delay(100)
        
        val normalizedCommand = command.trim().uppercase()
        
        return when {
            normalizedCommand.startsWith("AT SH") -> {
                handleHeaderSwitch(normalizedCommand)
            }
            normalizedCommand.startsWith("AT") -> {
                handleAtCommand(normalizedCommand)
            }
            normalizedCommand.matches(Regex("[0-9A-F]{4}")) -> {
                handlePidQuery(normalizedCommand)
            }
            else -> "?"
        }
    }
    
    /**
     * Handle ECU header switching commands.
     */
    private fun handleHeaderSwitch(command: String): String {
        // Handle both "AT SH760" and "AT SH 760" formats
        val header = command.removePrefix("AT SH").trim()
        if (header.matches(Regex("[0-9A-F]{3}"))) {
            currentHeader = header
            return "OK"
        }
        return "?"
    }
    
    /**
     * Handle basic AT commands.
     */
    private fun handleAtCommand(command: String): String {
        return when (command) {
            "ATZ" -> "ELM327 v1.5"
            "AT E0" -> "OK"
            "AT L0" -> "OK"
            "AT S0" -> "OK"
            "AT SP 0" -> "OK"
            "AT DP" -> "AUTO"
            else -> "OK"
        }
    }
    
    /**
     * Handle PID queries (Mode 21, 22, 23).
     */
    private fun handlePidQuery(query: String): String {
        // Simulate random failures
        if (Random.nextDouble() < failureRate) {
            return "ERROR"
        }
        
        // Check for specific NODATA PIDs
        if (query in nodataPids) {
            return "NODATA"
        }
        
        // Check for specific ERROR PIDs
        if (query in errorPids) {
            return "ERROR"
        }
        
        // Get header-specific response
        val customPids = mockData.getJSONObject("customPids")
        val headerPids = if (customPids.has(currentHeader)) {
            customPids.getJSONObject(currentHeader)
        } else {
            JSONObject()
        }
        
        return if (headerPids.has(query)) {
            headerPids.getJSONObject(query).getString("response")
        } else {
            "NODATA"
        }
    }
    
    /**
     * Load error simulation settings from mock data.
     */
    private fun loadErrorSimulation() {
        if (mockData.has("errorSimulation")) {
            val errorSim = mockData.getJSONObject("errorSimulation")
            failureRate = errorSim.getDouble("failureRate")
            
            errorSim.getJSONArray("nodataPids")?.let { array ->
                for (i in 0 until array.length()) {
                    nodataPids.add(array.getString(i))
                }
            }
            
            errorSim.getJSONArray("errorPids")?.let { array ->
                for (i in 0 until array.length()) {
                    errorPids.add(array.getString(i))
                }
            }
        }
    }
    
    /**
     * Set test scenario for discovery testing.
     */
    fun setTestScenario(scenario: String) {
        if (mockData.has("testScenarios")) {
            val scenarios = mockData.getJSONObject("testScenarios")
            if (scenarios.has(scenario)) {
                val scenarioData = scenarios.getJSONObject(scenario)
                
                // Override failure rate if specified
                if (scenarioData.has("failureRate")) {
                    failureRate = scenarioData.getDouble("failureRate")
                }
                
                // Set active headers
                if (scenarioData.has("activeHeaders")) {
                    val headers = scenarioData.getJSONArray("activeHeaders")
                    // For testing, we'll switch to the first active header
                    if (headers.length() > 0) {
                        currentHeader = headers.getString(0)
                    }
                }
            }
        }
    }
    
    /**
     * Get current active header.
     */
    fun getCurrentHeader(): String = currentHeader
    
    /**
     * Get all custom PIDs for current header.
     */
    fun getCurrentHeaderPids(): Map<String, JSONObject> {
        val customPids = mockData.getJSONObject("customPids")
        return if (customPids.has(currentHeader)) {
            val headerPids = customPids.getJSONObject(currentHeader)
            val result = mutableMapOf<String, JSONObject>()
            for (key in headerPids.keys()) {
                result[key] = headerPids.getJSONObject(key)
            }
            result
        } else {
            emptyMap()
        }
    }
    
    /**
     * Reset to default state.
     */
    fun reset() {
        currentHeader = "7DF"
        loadErrorSimulation()
    }
}
