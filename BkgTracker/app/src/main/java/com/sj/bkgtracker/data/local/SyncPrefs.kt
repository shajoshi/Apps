package com.sj.bkgtracker.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SyncPrefs {

    private const val PREFS_NAME = "sync_prefs"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_SYNC_SUCCESS = "last_sync_success"

    data class SyncStatus(val lastSyncTime: Long, val success: Boolean)

    private val _syncState = MutableStateFlow(SyncStatus(0L, false))
    val syncState: StateFlow<SyncStatus> = _syncState.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _syncState.value = SyncStatus(
            lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIME, 0L),
            success = prefs.getBoolean(KEY_LAST_SYNC_SUCCESS, false)
        )
    }

    fun updateLastSync(context: Context, success: Boolean) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNC_TIME, now)
            .putBoolean(KEY_LAST_SYNC_SUCCESS, success)
            .apply()
        _syncState.value = SyncStatus(lastSyncTime = now, success = success)
    }
}
