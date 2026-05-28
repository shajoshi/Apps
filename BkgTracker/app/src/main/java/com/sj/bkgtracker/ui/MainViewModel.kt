package com.sj.bkgtracker.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.service.LocationForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    init {
        refreshAuthState()
        refreshPermissions()
        observeFlows()
        observeExpressMode()
    }

    fun refreshAuthState() {
        val user = FirebaseAuth.getInstance().currentUser
        _state.update { it.copy(isSignedIn = user != null, userEmail = user?.email.orEmpty()) }
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val bg = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val notif = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        _state.update { it.copy(fineLocationGranted = fine, backgroundLocationGranted = bg, notificationPermissionGranted = notif) }
    }

    private fun observeFlows() {
        viewModelScope.launch {
            combine(
                LocationCache.cacheSize,
                LocationCache.lastLocation,
                LocationCache.lastSkippedStatus,
                LocationCache.pointsLast24Hours,
                TrackingStateHolder.isTracking,
                SyncPrefs.syncState
            ) { values ->
                val size = values[0] as Int
                val lastLoc = values[1] as LocationRecord?
                val skipStatus = values[2] as String?
                val points24h = values[3] as Int
                val tracking = values[4] as Boolean
                val sync = values[5] as SyncPrefs.SyncStatus
                _state.update { current ->
                    current.copy(
                        cacheSize         = size,
                        lastLocation      = lastLoc,
                        lastSkippedStatus = skipStatus,
                        pointsLast24Hours = points24h,
                        isTracking        = tracking,
                        lastSyncTime      = sync.lastSyncTime,
                        lastSyncSuccess   = sync.success
                    )
                }
            }.collect {}
        }
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            MainIntent.SignOut -> signOut()
            MainIntent.ManualSync -> manualSync()
            MainIntent.ExpressSync -> requestExpressSync()
            MainIntent.StopExpressSync -> stopExpressSync()
            else -> { /* navigation intents handled in Activity */ }
        }
    }

    fun manualSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val records = LocationCache.drainAll(ctx)
            if (records.isEmpty()) return@launch
            _state.update { it.copy(isSyncing = true) }
            LocationRepositoryImpl().uploadBatch(records).fold(
                onSuccess = {
                    SyncPrefs.updateLastSync(ctx, success = true)
                },
                onFailure = {
                    LocationCache.requeue(ctx, records)
                    SyncPrefs.updateLastSync(ctx, success = false)
                }
            )
            _state.update { it.copy(isSyncing = false) }
        }
    }

    private fun observeExpressMode() {
        viewModelScope.launch {
            while (true) {
                val ctx = getApplication<Application>()
                ExpressSyncManager.checkExpiry(ctx)
                _state.update { current ->
                    current.copy(
                        isExpressMode = ExpressSyncManager.isExpressMode.value,
                        expressMinutesRemaining = ExpressSyncManager.minutesRemaining(),
                        expressRequestedBy = ExpressSyncManager.requestedBy.value,
                        expressStatusMessage = ExpressSyncManager.statusMessage.value
                    )
                }
                delay(30_000L) // refresh every 30s
            }
        }
        // Also observe statusMessage reactively for immediate updates from FCM
        viewModelScope.launch {
            ExpressSyncManager.statusMessage.collect { msg ->
                _state.update { it.copy(expressStatusMessage = msg) }
            }
        }
        viewModelScope.launch {
            ExpressSyncManager.isExpressMode.collect { isExpress ->
                val ctx = getApplication<Application>()
                _state.update { current ->
                    current.copy(
                        isExpressMode = isExpress,
                        expressMinutesRemaining = ExpressSyncManager.minutesRemaining(),
                        expressRequestedBy = ExpressSyncManager.requestedBy.value
                    )
                }
            }
        }
    }

    private fun requestExpressSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            LocationRepositoryImpl().requestExpressSync().fold(
                onSuccess = {
                    // Also activate locally immediately (don't wait for FCM round-trip)
                    val expiresAt = System.currentTimeMillis() + 3_600_000L
                    val email = FirebaseAuth.getInstance().currentUser?.email
                    ExpressSyncManager.activate(ctx, expiresAt, email)
                },
                onFailure = { /* silent fail, will show in logs */ }
            )
        }
    }

    private fun stopExpressSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            LocationRepositoryImpl().stopExpressSync().fold(
                onSuccess = {
                    val email = FirebaseAuth.getInstance().currentUser?.email
                    ExpressSyncManager.stopByUser(ctx, email)
                },
                onFailure = { /* silent fail, will show in logs */ }
            )
        }
    }

    fun clearExpressStatusMessage() {
        ExpressSyncManager.clearStatusMessage()
        _state.update { it.copy(expressStatusMessage = null) }
    }

    fun onSignInSuccess(email: String) {
        _state.update { it.copy(isSignedIn = true, userEmail = email) }
    }

    private fun signOut() {
        LocationForegroundService.stop(getApplication())
        FirebaseAuth.getInstance().signOut()
        _state.update { it.copy(isSignedIn = false, userEmail = "", isTracking = false) }
    }
}
