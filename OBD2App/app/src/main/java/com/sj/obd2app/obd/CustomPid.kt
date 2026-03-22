package com.sj.obd2app.obd

import java.util.UUID

/**
 * User-defined extended OBD-II PID for manufacturer-specific diagnostics.
 *
 * Supports any OBD mode (e.g. Mode 22 Enhanced Diagnostics) and custom ECU headers
 * (e.g. header "760" for ABS/DSC module on Jaguar).
 *
 * The [formula] field uses the standard Torque Pro / Car Scanner notation:
 *   A, B, C, D = response data bytes (0-indexed)
 *   Standard arithmetic: +, -, *, /, (, )
 *   Constants as decimal numbers
 *
 * Example for Jaguar XF Yaw Rate:
 *   header = "760", mode = "22", pid = "0456", bytesReturned = 2,
 *   formula = "((A*256)+B)/100", unit = "°/s"
 *
 * @param id               Unique identifier
 * @param name             Human-readable name, e.g. "Yaw Rate"
 * @param header           ECU header to target, e.g. "760". Empty string = default (7DF)
 * @param mode             OBD mode hex string, e.g. "22" for Enhanced Diagnostics
 * @param pid              PID hex bytes, e.g. "0456"
 * @param bytesReturned    Number of data bytes expected in the response
 * @param unit             Display unit, e.g. "°/s", "g", "°"
 * @param formula          Arithmetic expression to convert raw bytes to display value
 * @param signed           Whether the result uses two's complement (signed integer)
 * @param enabled          Whether this PID is currently being polled
 */
data class CustomPid(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val header: String = "",
    val mode: String = "22",
    val pid: String,
    val bytesReturned: Int = 2,
    val unit: String = "",
    val formula: String = "A",
    val signed: Boolean = false,
    val enabled: Boolean = true
) {
    /** Full command string to send: mode + pid, e.g. "220456" */
    val commandString: String get() = "$mode$pid"

    /** Expected response header: (mode + 0x40) + pid, e.g. "620456" */
    val responseHeader: String
        get() {
            val modeInt = mode.toIntOrNull(16) ?: return ""
            return String.format("%02X", modeInt + 0x40) + pid
        }

    /** Composite key used in the cached results map to avoid collisions with standard PIDs */
    val cacheKey: String get() = "EXT_${header.ifEmpty { "7DF" }}_$commandString"
}
