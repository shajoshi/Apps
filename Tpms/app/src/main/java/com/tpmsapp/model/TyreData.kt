package com.tpmsapp.model

data class TyreData(
    val position: TyrePosition,
    val pressureKpa: Float,
    val temperatureCelsius: Float,
    val batteryPercent: Int,
    val isAlarmLow: Boolean = false,
    val isAlarmHigh: Boolean = false,
    val isAlarmTemp: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val pressurePsi: Float get() = pressureKpa * 0.14503773f
    val pressureBar: Float get() = pressureKpa / 100f
}

enum class TyrePosition(val label: String, val shortLabel: String) {
    FRONT_LEFT("Front Left", "FL"),
    FRONT_RIGHT("Front Right", "FR"),
    REAR_LEFT("Rear Left", "RL"),
    REAR_RIGHT("Rear Right", "RR")
}
