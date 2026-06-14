package com.sj.bkgtracker.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class ActivityLogEntry(
    val timestamp: Long,
    val activityName: String,
    val isStart: Boolean // true = started, false = ended
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

object ActivityLogCache {

    private val MAX_AGE_MS = 120 * 60 * 1000L // 120 minutes

    private val _logs = CopyOnWriteArrayList<ActivityLogEntry>()
    private val _logsFlow = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val logs: StateFlow<List<ActivityLogEntry>> = _logsFlow.asStateFlow()

    fun logActivity(activityName: String, isStart: Boolean) {
        val now = System.currentTimeMillis()
        val entry = ActivityLogEntry(now, activityName, isStart)
        _logs.add(entry)
        cleanupOldLogs(now)
        _logsFlow.value = _logs.toList()
    }

    fun getRecentLogs(): List<ActivityLogEntry> {
        val now = System.currentTimeMillis()
        cleanupOldLogs(now)
        return _logs.toList()
    }

    private fun cleanupOldLogs(now: Long) {
        val cutoff = now - MAX_AGE_MS
        _logs.removeAll { it.timestamp < cutoff }
    }

    fun clear() {
        _logs.clear()
        _logsFlow.value = emptyList()
    }
}
