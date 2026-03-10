package com.sj.obd2app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Global application settings singleton backed by SharedPreferences("obd2_prefs").
 * All write operations call apply() immediately.
 */
object AppSettings {

    private const val PREFS_NAME = "obd2_prefs"

    private const val KEY_GLOBAL_POLLING_DELAY_MS  = "global_polling_delay_ms"
    private const val KEY_GLOBAL_COMMAND_DELAY_MS  = "global_command_delay_ms"
    private const val KEY_LOG_FOLDER_URI            = "log_folder_uri"
    private const val KEY_LOGGING_ENABLED           = "logging_enabled"
    private const val KEY_AUTO_SHARE_LOG            = "auto_share_log"
    private const val KEY_ACCELEROMETER_ENABLED     = "accelerometer_enabled"
    private const val KEY_ACTIVE_PROFILE_ID         = "active_profile_id"
    const val KEY_AUTO_CONNECT                      = "auto_connect_last_device"

    val DEFAULT_POLLING_DELAY_MS = 500L
    val DEFAULT_COMMAND_DELAY_MS = 50L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Auto-connect ──────────────────────────────────────────────────────────

    fun isAutoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    // ── Trip Logging ──────────────────────────────────────────────────────────

    fun isLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGING_ENABLED, false)

    fun setLoggingEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LOGGING_ENABLED, value).apply()

    fun isAutoShareLogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SHARE_LOG, false)

    fun setAutoShareLogEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_SHARE_LOG, value).apply()

    fun isAccelerometerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACCELEROMETER_ENABLED, false)

    fun setAccelerometerEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ACCELEROMETER_ENABLED, value).apply()

    // ── Log folder (SAF URI string) ───────────────────────────────────────────

    fun getLogFolderUri(context: Context): String? =
        prefs(context).getString(KEY_LOG_FOLDER_URI, null)

    fun setLogFolderUri(context: Context, uriString: String?) =
        prefs(context).edit().putString(KEY_LOG_FOLDER_URI, uriString).apply()

    // ── Global OBD2 polling delays ────────────────────────────────────────────

    fun getGlobalPollingDelayMs(context: Context): Long =
        prefs(context).getLong(KEY_GLOBAL_POLLING_DELAY_MS, DEFAULT_POLLING_DELAY_MS)

    fun setGlobalPollingDelayMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_GLOBAL_POLLING_DELAY_MS, ms).apply()

    fun getGlobalCommandDelayMs(context: Context): Long =
        prefs(context).getLong(KEY_GLOBAL_COMMAND_DELAY_MS, DEFAULT_COMMAND_DELAY_MS)

    fun setGlobalCommandDelayMs(context: Context, ms: Long) =
        prefs(context).edit().putLong(KEY_GLOBAL_COMMAND_DELAY_MS, ms).apply()

    // ── Active profile ────────────────────────────────────────────────────────

    fun getActiveProfileId(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_PROFILE_ID, null)

    fun setActiveProfileId(context: Context, id: String?) =
        prefs(context).edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply()

    // ── Effective polling delays (merges profile override + global) ───────────

    fun effectivePollingDelayMs(context: Context): Long {
        val profile = VehicleProfileRepository.getInstance(context).activeProfile
        return profile?.obdPollingDelayMs ?: getGlobalPollingDelayMs(context)
    }

    fun effectiveCommandDelayMs(context: Context): Long {
        val profile = VehicleProfileRepository.getInstance(context).activeProfile
        return profile?.obdCommandDelayMs ?: getGlobalCommandDelayMs(context)
    }
}
