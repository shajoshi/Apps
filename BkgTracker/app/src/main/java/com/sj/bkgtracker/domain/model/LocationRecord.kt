package com.sj.bkgtracker.domain.model

data class LocationRecord(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val accuracyM: Float,
    val speedKmh: Float = 0f,
    val altitudeM: Double = 0.0,
    val bearingDeg: Float? = null
)
