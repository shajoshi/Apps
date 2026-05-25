package com.sj.bkgtracker.ui

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.data.local.LocationCache
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.sj.bkgtracker.service.LocationForegroundService
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
                TrackingStateHolder.isTracking,
                SyncPrefs.syncState
            ) { size, lastLoc, tracking, sync ->
                _state.update { current ->
                    current.copy(
                        cacheSize      = size,
                        lastLocation   = lastLoc,
                        isTracking     = tracking,
                        lastSyncTime   = sync.lastSyncTime,
                        lastSyncSuccess = sync.success
                    )
                }
            }.collect {}
        }
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            MainIntent.SignOut -> signOut()
            MainIntent.ManualSync -> manualSync()
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

    fun onSignInSuccess(email: String) {
        _state.update { it.copy(isSignedIn = true, userEmail = email) }
    }

    private fun signOut() {
        LocationForegroundService.stop(getApplication())
        FirebaseAuth.getInstance().signOut()
        _state.update { it.copy(isSignedIn = false, userEmail = "", isTracking = false) }
    }
}
