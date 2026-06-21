package com.sj.bkgtracker.data.local

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Activity log that persists to NDJSON file.
 * No in-memory cache - reads directly from disk since the log is viewed sparingly.
 * Retention: 24 hours
 */
object ActivityLogCache {

    private val MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours

    /** Log an activity transition - appends to NDJSON file */
    fun logActivity(context: Context, activityName: String, isStart: Boolean) {
        val entry = ActivityLogEntry(
            timestamp = System.currentTimeMillis(),
            activityName = activityName,
            isStart = isStart
        )
        AppSettings.appendActivityLogEntry(context, entry)
    }

    /** Read all logs from disk, filtering out entries older than 24 hours */
    fun logs(context: Context): List<ActivityLogEntry> {
        val allLogs = AppSettings.loadActivityLog(context)
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return allLogs.filter { it.timestamp >= cutoff }
    }

    /** Clear all logs - deletes the NDJSON file */
    fun clear(context: Context) {
        AppSettings.clearActivityLog(context)
    }
}
