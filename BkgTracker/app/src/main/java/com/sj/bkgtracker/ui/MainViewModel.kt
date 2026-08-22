package com.sj.bkgtracker.ui

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.AndroidViewModel
import java.io.File
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sj.bkgtracker.data.local.AppForegroundState
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
import kotlinx.coroutines.tasks.await

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
        val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = ctx.getSystemService(AlarmManager::class.java)
            alarmManager.canScheduleExactAlarms()
        } else true
        _state.update {
            it.copy(
                fineLocationGranted = fine,
                backgroundLocationGranted = bg,
                notificationPermissionGranted = notif,
                activityRecognitionGranted = activity,
                exactAlarmGranted = exactAlarm
            )
        }
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
            MainIntent.ClearFirestoreCache -> clearFirestoreCache()
            else -> { /* navigation intents handled in Activity */ }
        }
    }

    private fun clearFirestoreCache() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                Log.d(TAG, "[ClearFirestoreCache] Step 1/4: Terminating Firestore instance")
                FirebaseFirestore.getInstance().terminate().await()
                Log.d(TAG, "[ClearFirestoreCache] Step 2/4: Firestore terminated, clearing persistence")
                FirebaseFirestore.getInstance().clearPersistence().await()
                Log.d(TAG, "[ClearFirestoreCache] Step 3/4: Persistence cleared, deleting local Firestore files")

                // Delete any Firestore-related local files (equivalent to app-delete-data for Firestore)
                firestoreCacheRoots(ctx).forEach { dir ->
                    if (!dir.exists()) {
                        Log.d(TAG, "[ClearFirestoreCache] Skipping non-existent directory: ${dir.absolutePath}")
                        return@forEach
                    }
                    Log.d(TAG, "[ClearFirestoreCache] Scanning directory: ${dir.absolutePath}")
                    dir.walkTopDown().forEach { file ->
                        if (isFirestoreCachePath(file.absolutePath)) {
                            val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                            if (deleted) {
                                Log.d(TAG, "[ClearFirestoreCache] Deleted: ${file.absolutePath}")
                            } else {
                                Log.w(TAG, "[ClearFirestoreCache] Failed to delete: ${file.absolutePath}")
                            }
                        }
                    }
                }

                Log.d(TAG, "[ClearFirestoreCache] Step 4/4: Restarting app")
                showToastIfForeground(ctx, "Cache cleared — restarting app")
                restartApp(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "[ClearFirestoreCache] Failed to clear Firestore cache", e)
                showToastIfForeground(ctx, "Failed to clear cache: ${e.message}")
            }
        }
    }

    private fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private fun firestoreCacheRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        context.filesDir?.let { roots.add(it) }
        context.cacheDir?.let { roots.add(it) }
        context.getDatabasePath("_dummy_")?.parentFile?.let { roots.add(it) }
        val dataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.dataDir else context.filesDir?.parentFile
        dataDir?.let { roots.add(it) }
        return roots.distinct()
    }

    private fun isFirestoreCachePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("firestore") ||
               lower.contains("leveldb") ||
               lower.contains("com.google.firebase.firestore") ||
               lower.contains("firebase.firestore") ||
               (lower.endsWith(".ldb") && lower.contains("firestore"))
    }

    fun manualSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                showToastIfForeground(ctx, "No network — sync skipped")
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
                        // Use oldest record timestamp as 'since' for window tracking
                        val since = records.minOfOrNull { it.timestampMs } ?: 0L
                        UnifiedLocationCache.addPoints(currentUserId, records, since)
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
                showToastIfForeground(ctx, "No network — Express Sync requires internet")
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
                    showToastIfForeground(ctx, "Express Sync failed: ${e.message}")
                }
            )
        }
    }

    private fun stopExpressSync() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (!isNetworkAvailable(ctx)) {
                showToastIfForeground(ctx, "No network — cannot stop Express Sync remotely")
                return@launch
            }
            LocationRepositoryImpl(ctx).stopExpressSync().fold(
                onSuccess = {
                    val email = FirebaseAuth.getInstance().currentUser?.email
                    ExpressSyncManager.stopByUser(ctx, email)
                },
                onFailure = { e ->
                    showToastIfForeground(ctx, "Stop Express Sync failed: ${e.message}")
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

    private fun showToastIfForeground(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (AppForegroundState.isInForeground) {
            Toast.makeText(context, message, duration).show()
        } else {
            Log.d(TAG, "Toast suppressed (background): $message")
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
