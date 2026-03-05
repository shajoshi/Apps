package com.sj.obd2app.obd

/**
 * Represents a single OBD-II command (Mode 01 PID).
 *
 * @param pid        The PID string to send, e.g. "010C"
 * @param name       Human-readable parameter name
 * @param unit       Unit of measurement
 * @param bytesReturned Number of data bytes in the response
 * @param parse      Lambda to convert the raw data bytes into a human-readable value string
 */
data class Obd2Command(
    val pid: String,
    val name: String,
    val unit: String,
    val bytesReturned: Int,
    val parse: (IntArray) -> String
)
