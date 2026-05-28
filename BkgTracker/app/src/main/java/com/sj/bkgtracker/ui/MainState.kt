package com.sj.bkgtracker.ui

import com.sj.bkgtracker.domain.model.LocationRecord

data class MainState(
    val isSignedIn: Boolean = false,
    val userEmail: String = "",
    val isTracking: Boolean = false,
    val lastLocation: LocationRecord? = null,
    val lastSkippedStatus: String? = null,
    val cacheSize: Int = 0,
    val lastSyncTime: Long = 0L,
    val lastSyncSuccess: Boolean = false,
    val isSyncing: Boolean = false,
    val isExpressMode: Boolean = false,
    val expressMinutesRemaining: Int = 0,
    val expressRequestedBy: String? = null,
    val expressStatusMessage: String? = null,
    val fineLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false
)

sealed class MainIntent {
    object SignIn : MainIntent()
    object SignOut : MainIntent()
    object RequestFineLocation : MainIntent()
    object RequestBackgroundLocation : MainIntent()
    object RequestNotificationPermission : MainIntent()
    object ManualSync : MainIntent()
    object ExpressSync : MainIntent()
    object StopExpressSync : MainIntent()
}
