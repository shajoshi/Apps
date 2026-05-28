package com.sj.bkgtracker.data.local

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tracks daily Firestore usage (writes, reads, points uploaded, FCM messages)
 * persisted to SharedPreferences. Counters auto-reset when the calendar day changes.
 */
object UsageTracker {

    private const val PREFS_NAME = "usage_tracker"
    private const val KEY_DATE = "usage_date"
    private const val KEY_WRITES = "firestore_writes"
    private const val KEY_READS = "firestore_reads"
    private const val KEY_POINTS_UPLOADED = "points_uploaded"
    private const val KEY_FCM_MESSAGES = "fcm_messages"
    private const val KEY_CF_INVOCATIONS = "cf_invocations"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun todayStr(): String = dateFormat.format(Date())

    /** Reset counters if day has changed */
    private fun rolloverIfNeeded(prefs: SharedPreferences) {
        val stored = prefs.getString(KEY_DATE, null)
        val today = todayStr()
        if (stored != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_WRITES, 0)
                .putInt(KEY_READS, 0)
                .putInt(KEY_POINTS_UPLOADED, 0)
                .putInt(KEY_FCM_MESSAGES, 0)
                .putInt(KEY_CF_INVOCATIONS, 0)
                .apply()
        }
    }

    fun recordWrites(context: Context, count: Int) {
        val p = prefs(context)
        rolloverIfNeeded(p)
        p.edit().putInt(KEY_WRITES, p.getInt(KEY_WRITES, 0) + count).apply()
    }

    fun recordReads(context: Context, count: Int) {
        val p = prefs(context)
        rolloverIfNeeded(p)
        p.edit().putInt(KEY_READS, p.getInt(KEY_READS, 0) + count).apply()
    }

    fun recordPointsUploaded(context: Context, count: Int) {
        val p = prefs(context)
        rolloverIfNeeded(p)
        p.edit().putInt(KEY_POINTS_UPLOADED, p.getInt(KEY_POINTS_UPLOADED, 0) + count).apply()
    }

    fun recordFcmMessage(context: Context) {
        val p = prefs(context)
        rolloverIfNeeded(p)
        p.edit().putInt(KEY_FCM_MESSAGES, p.getInt(KEY_FCM_MESSAGES, 0) + 1).apply()
    }

    fun recordCloudFunctionInvocation(context: Context) {
        val p = prefs(context)
        rolloverIfNeeded(p)
        p.edit().putInt(KEY_CF_INVOCATIONS, p.getInt(KEY_CF_INVOCATIONS, 0) + 1).apply()
    }

    data class DailyUsage(
        val date: String,
        val firestoreWrites: Int,
        val firestoreReads: Int,
        val pointsUploaded: Int,
        val fcmMessages: Int,
        val cloudFunctionInvocations: Int
    ) {
        fun estimatedCost(): String {
            val extraWrites = maxOf(0, firestoreWrites - 20_000)
            val extraReads = maxOf(0, firestoreReads - 50_000)
            val writeCost = extraWrites * 0.18 / 100_000
            val readCost = extraReads * 0.06 / 100_000
            val total = writeCost + readCost
            return if (total < 0.001) "Within free tier" else "~\$%.4f".format(total)
        }

        fun writeUtilisation(): Int =
            if (firestoreWrites == 0) 0 else minOf(100, firestoreWrites * 100 / 20_000)

        fun readUtilisation(): Int =
            if (firestoreReads == 0) 0 else minOf(100, firestoreReads * 100 / 50_000)
    }

    fun getTodayUsage(context: Context): DailyUsage {
        val p = prefs(context)
        rolloverIfNeeded(p)
        return DailyUsage(
            date = todayStr(),
            firestoreWrites = p.getInt(KEY_WRITES, 0),
            firestoreReads = p.getInt(KEY_READS, 0),
            pointsUploaded = p.getInt(KEY_POINTS_UPLOADED, 0),
            fcmMessages = p.getInt(KEY_FCM_MESSAGES, 0),
            cloudFunctionInvocations = p.getInt(KEY_CF_INVOCATIONS, 0)
        )
    }
}
