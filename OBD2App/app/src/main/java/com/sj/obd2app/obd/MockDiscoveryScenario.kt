package com.sj.obd2app.obd

/**
 * Defines test scenarios for PID discovery testing in the mock emulator.
 */
enum class MockDiscoveryScenario(
    val displayName: String,
    val description: String,
    val expectedPids: Int
) {
    JAGUAR_XF(
        "Jaguar XF",
        "Jaguar XF instrument cluster PIDs (yaw rate, pitch rate, roll rate, lateral/longitudinal acceleration, steering angle)",
        6
    ),
    TOYOTA_HYBRID(
        "Toyota Hybrid",
        "Toyota hybrid system PIDs (battery SOC, inverter temperature)",
        2
    ),
    MIXED_HEADERS(
        "Mixed Headers",
        "Multiple ECU simulation (engine, transmission, instrument cluster, hybrid)",
        10
    ),
    EMPTY_DISCOVERY(
        "Empty Discovery",
        "No custom PIDs found - tests discovery failure handling",
        0
    ),
    ERROR_HEAVY(
        "Error Heavy",
        "High failure rate testing - 50% of commands fail",
        1
    );
    
    companion object {
        fun fromString(value: String): MockDiscoveryScenario {
            return values().find { it.name.equals(value, ignoreCase = true) }
                ?: JAGUAR_XF
        }
        
        fun getDisplayNames(): Array<String> {
            return values().map { it.displayName }.toTypedArray()
        }
    }
}

/**
 * Data class for discovered PID information.
 */
data class DiscoveredPid(
    val header: String,
    val mode: String,
    val pid: String,
    val response: String,
    val byteCount: Int,
    val suggestedName: String,
    val suggestedUnit: String,
    val suggestedFormula: String
) {
    val commandString: String
        get() = "$mode$pid"
    
    val cacheKey: String
        get() = "EXT_${header}_${commandString}"
}
