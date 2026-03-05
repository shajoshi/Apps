package com.sj.obd2app.obd

/**
 * Represents a single OBD-II data reading to display in the UI.
 */
data class Obd2DataItem(
    val pid: String,
    val name: String,
    val value: String,
    val unit: String
)
