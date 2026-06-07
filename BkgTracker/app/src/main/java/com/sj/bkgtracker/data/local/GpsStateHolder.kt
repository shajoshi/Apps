package com.sj.bkgtracker.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object GpsStateHolder {

    private val _gpsState = MutableStateFlow(GpsState.DEEP_IDLE)
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    private val _gpsInterval = MutableStateFlow(0L)
    val gpsInterval: StateFlow<Long> = _gpsInterval.asStateFlow()

    fun setGpsState(state: GpsState, intervalMs: Long) {
        _gpsState.value = state
        _gpsInterval.value = intervalMs
    }

    enum class GpsState {
        DEEP_IDLE,      // GPS completely off, minimal battery drain
        ACQUISITION,    // Fast GPS startup when satellites detected
        ACTIVE,         // Normal GPS tracking with movement
        EXPRESS         // High-frequency tracking (overrides all)
    }
}
