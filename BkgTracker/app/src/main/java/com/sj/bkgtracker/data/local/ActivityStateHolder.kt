package com.sj.bkgtracker.data.local

import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActivityStateHolder {

    private val _activityState = MutableStateFlow("Idle")
    val activityState: StateFlow<String> = _activityState.asStateFlow()

    private val _isInActivity = MutableStateFlow(false)
    val isInActivity: StateFlow<Boolean> = _isInActivity.asStateFlow()

    fun setActivityStarted(activityType: Int) {
        _activityState.value = activityName(activityType)
        _isInActivity.value = true
    }

    fun setActivityEnded(activityType: Int) {
        _activityState.value = "Idle"
        _isInActivity.value = false
    }

    fun activityName(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "Driving"
        DetectedActivity.ON_BICYCLE -> "Cycling"
        DetectedActivity.ON_FOOT -> "On Foot"
        DetectedActivity.WALKING -> "Walking"
        DetectedActivity.RUNNING -> "Running"
        DetectedActivity.STILL -> "Still"
        DetectedActivity.TILTING -> "Tilting"
        DetectedActivity.UNKNOWN -> "Unknown"
        else -> "Activity $type"
    }
}
