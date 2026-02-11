package com.sj.gpsutil.tracking

data class DriverMetrics(
    val events: List<String>,
    val primaryEvent: String,
    val smoothnessScore: Float,
    val jerk: Float,
    val reactionTimeMs: Float?
)
