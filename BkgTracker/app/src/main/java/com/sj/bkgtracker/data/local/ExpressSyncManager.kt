package com.sj.bkgtracker.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExpressSyncManager {

    private const val PREFS_NAME = "express_sync_prefs"
    private const val KEY_EXPIRES_AT = "express_expires_at"
    private const val KEY_REQUESTED_BY = "express_requested_by"

    private val _isExpressMode = MutableStateFlow(false)
    val isExpressMode: StateFlow<Boolean> = _isExpressMode.asStateFlow()

    private val _requestedBy = MutableStateFlow<String?>(null)
    val requestedBy: StateFlow<String?> = _requestedBy.asStateFlow()

    private val _expiresAt = MutableStateFlow(0L)
    val expiresAt: StateFlow<Long> = _expiresAt.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Call once at app startup to restore persisted express mode state */
    fun initialise(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val savedBy = prefs.getString(KEY_REQUESTED_BY, null)
        if (saved > System.currentTimeMillis()) {
            _expiresAt.value = saved
            _requestedBy.value = savedBy
            _isExpressMode.value = true
            val timeStr = formatTime(saved)
            _statusMessage.value = "Express Sync mode till $timeStr activated by ${savedBy ?: "unknown"}"
        } else {
            deactivate(context)
        }
    }

    fun activate(context: Context, expiresAt: Long, requestedBy: String?) {
        _expiresAt.value = expiresAt
        _requestedBy.value = requestedBy
        _isExpressMode.value = true
        val timeStr = formatTime(expiresAt)
        _statusMessage.value = "Express Sync mode till $timeStr activated by ${requestedBy ?: "unknown"}"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_REQUESTED_BY, requestedBy)
            .apply()
    }

    fun deactivate(context: Context) {
        _isExpressMode.value = false
        _expiresAt.value = 0L
        _requestedBy.value = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_REQUESTED_BY)
            .apply()
    }

    fun stopByUser(context: Context, stoppedBy: String?) {
        _statusMessage.value = "Express Sync stopped by ${stoppedBy ?: "unknown"}"
        deactivate(context)
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private fun formatTime(epochMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    /** Check if express mode has expired; deactivate if so. Returns true if still active. */
    fun checkExpiry(context: Context): Boolean {
        if (_isExpressMode.value && System.currentTimeMillis() >= _expiresAt.value) {
            deactivate(context)
            return false
        }
        return _isExpressMode.value
    }

    /** Minutes remaining in express mode */
    fun minutesRemaining(): Int {
        if (!_isExpressMode.value) return 0
        val remaining = _expiresAt.value - System.currentTimeMillis()
        return if (remaining > 0) (remaining / 60_000).toInt() else 0
    }
}
