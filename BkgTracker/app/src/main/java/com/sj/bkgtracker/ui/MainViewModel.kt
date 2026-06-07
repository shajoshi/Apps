package com.sj.bkgtracker.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.data.local.ExpressSyncManager
import com.sj.bkgtracker.data.local.GpsStateHolder
import com.sj.bkgtracker.data.local.SyncPrefs
import com.sj.bkgtracker.data.local.TrackingStateHolder
import com.sj.bkgtracker.data.local.UnifiedLocationCache
import com.sj.bkgtracker.data.repository.LocationRepositoryImpl
import com.sj.bkgtracker.domain.model.LocationRecord
import com.sj.bkgtracker.service.LocationForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    init {
        refreshAuthState()
        refreshPermissions()
        
        // Initialize UnifiedLocationCache if user is already signed in
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            UnifiedLocationCache.setCurrentUser(currentUser.uid)
            UnifiedLocationCache.initialise(getApplication(), currentUser.uid)
        }
        
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
        val activity = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        _state.update { it.copy(fineLocationGranted = fine, backgroundLocationGranted = bg, notificationPermissionGranted = notif, activityRecognitionGranted = activity) }
    }

    private fun observeFlows() {
        // Clean up old points on initialization
        UnifiedLocationCache.cleanupOldPoints()
        
        viewModelScope.launch {
            combine(
                UnifiedLocationCache.cacheStats,
                UnifiedLocationCache.lastLocation,
                UnifiedLocationCache.lastSkippedStatus,
                TrackingStateHolder.isTracking,
                SyncPrefs.syncState,
                GpsStateHolder.gpsState,
                GpsStateHolder.gpsInterval
            ) { values ->
                val cacheStats = values[0] as UnifiedLocationCache.CacheStats
                val lastLoc = values[1] as LocationRecord?
                val skipStatus = values[2] as String?
                val tracking = values[3] as Boolean
                val sync = values[4] as SyncPrefs.SyncStatus
                val gpsState = values[5] as GpsStateHolder.GpsState
                val gpsInterval = values[6] as Long
                _state.update { current ->
                    current.copy(
                        unsavedSize       = cacheStats.unsavedPointsCount,
                        lastLocation      = lastLoc,
                        lastSkippedStatus = skipStatus,
                        isTracking        = tracking,
                        lastSyncTime      = sync.lastSyncTime,
                        lastSyncSuccess   = sync.success,
                        gpsState          = gpsState,
                        gpsIntervalMs     = gpsInterval,
                        totalCacheSize    = cacheStats.totalCachedPoints
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
            if (!isNetworkAvailable(ctx)) {
                Toast.makeText(ctx, "No network — sync skipped", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val records = UnifiedLocationCache.drainUnsavedPoints(ctx)
            if (records.isEmpty()) return@launch
            _state.update { it.copy(isSyncing = true) }
            LocationRepositoryImpl(ctx).uploadBatch(records).fold(
                onSuccess = {
                    SyncPrefs.updateLastSync(ctx, success = true)
                    // Add synced points to map cache for current user
                    val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                    if (currentUserId != null && records.isNotEmpty()) {
                        UnifiedLocationCache.addPoints(currentUserId, records)
                        Log.d(TAG, "Added ${records.size} synced points to unified cache for current user")
                    }
                },
                onFailure = {
                    UnifiedLocationCache.requeueUnsavedPoints(ctx, records)
                    SyncPrefs.updateLastSync(ctx, success = false)
                }
            )
            _state.update { it.copy(isSyncing = false) }
        }
    }

    private fun observeExpressMode() {
        viewModelScope.launch {
            combine(
                ExpressSyncManager.isExpressMode,
                ExpressSyncManager.requestedBy,
                ExpressSyncManager.statusMessage,
                ExpressSyncManager.expiresAt
            ) { isExpress, requestedBy, statusMsg, _ ->
                val ctx = getApplication<Application>()
                if (isExpress) ExpressSyncManager.checkExpiry(ctx)
                _state.update { current ->
                    current.copy(
                        isExpressMode = ExpressSyncManager.isExpressMode.value,
                        expressMinutesRemaining = ExpressSyncManager.minutesRemaining(),
                        expressRequestedBy = requestedBy,
                        expressStatusMessage = statusMsg
                    )
                }
            }.collect {}
        }
    }

    private fun requestExpressSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                Toast.makeText(ctx, "No network — Express Sync requires internet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            LocationRepositoryImpl(ctx).requestExpressSync().fold(
                onSuccess = {
                    // Also activate locally immediately (don't wait for FCM round-trip)
                    val expiresAt = System.currentTimeMillis() + 3_600_000L
                    val email = FirebaseAuth.getInstance().currentUser?.email
                    ExpressSyncManager.activate(ctx, expiresAt, email)
                },
                onFailure = { e ->
                    Toast.makeText(ctx, "Express Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun stopExpressSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                Toast.makeText(ctx, "No network — cannot stop Express Sync remotely", Toast.LENGTH_SHORT).show()
                return@launch
            }
            LocationRepositoryImpl(ctx).stopExpressSync().fold(
                onSuccess = {
                    val email = FirebaseAuth.getInstance().currentUser?.email
                    ExpressSyncManager.stopByUser(ctx, email)
                },
                onFailure = { e ->
                    Toast.makeText(ctx, "Stop Express Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun clearExpressStatusMessage() {
        ExpressSyncManager.clearStatusMessage()
        _state.update { it.copy(expressStatusMessage = null) }
    }

    fun onSignInSuccess(email: String) {
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId != null) {
            UnifiedLocationCache.setCurrentUser(userId)
            UnifiedLocationCache.initialise(getApplication(), userId)
        }
        _state.update { it.copy(isSignedIn = true, userEmail = email) }
    }

    private fun signOut() {
        LocationForegroundService.stop(getApplication())
        FirebaseAuth.getInstance().signOut()
        _state.update { it.copy(isSignedIn = false, userEmail = "", isTracking = false) }
    }

    private fun isNetworkAvailable(context: Application): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
