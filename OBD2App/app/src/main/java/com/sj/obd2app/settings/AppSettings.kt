package com.sj.obd2app.settings

import android.content.Context
import android.content.SharedPreferences
import com.sj.obd2app.storage.AppDataDirectory
import org.json.JSONObject

/**
 * Global application settings singleton.
 * 
 * If external storage (.obd directory) is available, settings are stored in settings.json.
 * Otherwise, falls back to SharedPreferences for backward compatibility.
 * 
 * Exception: log_folder_uri always stays in SharedPreferences (bootstrap requirement).
 */
object AppSettings {

    data class SettingsData(
        var activeProfileId: String? = null,
        var defaultLayoutName: String? = null,
        var globalPollingDelayMs: Long = DEFAULT_POLLING_DELAY_MS,
        var globalCommandDelayMs: Long = DEFAULT_COMMAND_DELAY_MS,
        var loggingEnabled: Boolean = false,
        var autoShareLog: Boolean = false,
        var accelerometerEnabled: Boolean = false,
        var autoConnect: Boolean = true,
        var obdConnectionEnabled: Boolean = true,
        var lastDeviceMac: String? = null,
        var lastDeviceName: String? = null
    )

    @Volatile
    private var cachedSettings: SettingsData? = null
    @Volatile
    private var pendingSettings: SettingsData? = null
    private val cacheLock = Any()

    private const val PREFS_NAME = "obd2_prefs"

    private const val KEY_GLOBAL_POLLING_DELAY_MS  = "global_polling_delay_ms"
    private const val KEY_GLOBAL_COMMAND_DELAY_MS  = "global_command_delay_ms"
    private const val KEY_LOG_FOLDER_URI            = "log_folder_uri"
    private const val KEY_LOGGING_ENABLED           = "logging_enabled"
    private const val KEY_AUTO_SHARE_LOG            = "auto_share_log"
    private const val KEY_ACCELEROMETER_ENABLED     = "accelerometer_enabled"
    private const val KEY_ACTIVE_PROFILE_ID         = "active_profile_id"
    const val KEY_AUTO_CONNECT                      = "auto_connect_last_device"
    private const val KEY_OBD_CONNECTION_ENABLED    = "obd_connection_enabled"

    val DEFAULT_POLLING_DELAY_MS = 500L
    val DEFAULT_COMMAND_DELAY_MS = 50L

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun loadSettings(context: Context): SettingsData {
        synchronized(cacheLock) {
            cachedSettings?.let { return it }

            val settings = if (AppDataDirectory.isUsingExternalStorage(context)) {
                loadFromJson(context)
            } else {
                loadFromPreferences(context)
            }

            cachedSettings = settings
            return settings
        }
    }

    private fun loadFromJson(context: Context): SettingsData {
        val settingsFile = AppDataDirectory.getSettingsFileDocumentFile(context)
        if (settingsFile == null || !settingsFile.exists()) {
            return SettingsData()
        }

        return try {
            val content = context.contentResolver.openInputStream(settingsFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return SettingsData()

            val json = JSONObject(content)
            SettingsData(
                activeProfileId = json.optString("activeProfileId", "").takeIf { it.isNotEmpty() },
                defaultLayoutName = json.optString("defaultLayoutName", "").takeIf { it.isNotEmpty() },
                globalPollingDelayMs = json.optLong("globalPollingDelayMs", DEFAULT_POLLING_DELAY_MS),
                globalCommandDelayMs = json.optLong("globalCommandDelayMs", DEFAULT_COMMAND_DELAY_MS),
                loggingEnabled = json.optBoolean("loggingEnabled", false),
                autoShareLog = json.optBoolean("autoShareLog", false),
                accelerometerEnabled = json.optBoolean("accelerometerEnabled", false),
                autoConnect = json.optBoolean("autoConnect", true),
                obdConnectionEnabled = json.optBoolean("obdConnectionEnabled", true),
                lastDeviceMac = json.optString("lastDeviceMac", "").takeIf { it.isNotEmpty() },
                lastDeviceName = json.optString("lastDeviceName", "").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            SettingsData()
        }
    }

    private fun loadFromPreferences(context: Context): SettingsData {
        val p = prefs(context)
        return SettingsData(
            activeProfileId = p.getString(KEY_ACTIVE_PROFILE_ID, null),
            defaultLayoutName = null,
            globalPollingDelayMs = p.getLong(KEY_GLOBAL_POLLING_DELAY_MS, DEFAULT_POLLING_DELAY_MS),
            globalCommandDelayMs = p.getLong(KEY_GLOBAL_COMMAND_DELAY_MS, DEFAULT_COMMAND_DELAY_MS),
            loggingEnabled = p.getBoolean(KEY_LOGGING_ENABLED, false),
            autoShareLog = p.getBoolean(KEY_AUTO_SHARE_LOG, false),
            accelerometerEnabled = p.getBoolean(KEY_ACCELEROMETER_ENABLED, false),
            autoConnect = p.getBoolean(KEY_AUTO_CONNECT, true),
            obdConnectionEnabled = p.getBoolean(KEY_OBD_CONNECTION_ENABLED, true),
            lastDeviceMac = p.getString("last_device_mac", null),
            lastDeviceName = p.getString("last_device_name", null)
        )
    }

    private fun saveSettings(context: Context, settings: SettingsData) {
        synchronized(cacheLock) {
            cachedSettings = settings

            if (AppDataDirectory.isUsingExternalStorage(context)) {
                saveToJson(context, settings)
            } else {
                saveToPreferences(context, settings)
            }
        }
    }

    private fun saveToJson(context: Context, settings: SettingsData) {
        val settingsFile = AppDataDirectory.getSettingsFileDocumentFile(context) ?: return

        val json = JSONObject().apply {
            settings.activeProfileId?.let { put("activeProfileId", it) }
            settings.defaultLayoutName?.let { put("defaultLayoutName", it) }
            put("globalPollingDelayMs", settings.globalPollingDelayMs)
            put("globalCommandDelayMs", settings.globalCommandDelayMs)
            put("loggingEnabled", settings.loggingEnabled)
            put("autoShareLog", settings.autoShareLog)
            put("accelerometerEnabled", settings.accelerometerEnabled)
            put("autoConnect", settings.autoConnect)
            put("obdConnectionEnabled", settings.obdConnectionEnabled)
            settings.lastDeviceMac?.let { put("lastDeviceMac", it) }
            settings.lastDeviceName?.let { put("lastDeviceName", it) }
        }

        try {
            context.contentResolver.openOutputStream(settingsFile.uri, "wt")?.use { output ->
                output.write(json.toString(2).toByteArray())
            }
        } catch (e: Exception) {
            // Fall back to preferences on error
            saveToPreferences(context, settings)
        }
    }

    private fun saveToPreferences(context: Context, settings: SettingsData) {
        prefs(context).edit().apply {
            settings.activeProfileId?.let { putString(KEY_ACTIVE_PROFILE_ID, it) } ?: remove(KEY_ACTIVE_PROFILE_ID)
            putLong(KEY_GLOBAL_POLLING_DELAY_MS, settings.globalPollingDelayMs)
            putLong(KEY_GLOBAL_COMMAND_DELAY_MS, settings.globalCommandDelayMs)
            putBoolean(KEY_LOGGING_ENABLED, settings.loggingEnabled)
            putBoolean(KEY_AUTO_SHARE_LOG, settings.autoShareLog)
            putBoolean(KEY_ACCELEROMETER_ENABLED, settings.accelerometerEnabled)
            putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            putBoolean(KEY_OBD_CONNECTION_ENABLED, settings.obdConnectionEnabled)
            settings.lastDeviceMac?.let { putString("last_device_mac", it) } ?: remove("last_device_mac")
            settings.lastDeviceName?.let { putString("last_device_name", it) } ?: remove("last_device_name")
        }.apply()
    }

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedSettings = null
            pendingSettings = null
        }
    }

    /**
     * Get pending settings for editing. If no pending changes exist, returns a copy of current settings.
     */
    fun getPendingSettings(context: Context): SettingsData {
        synchronized(cacheLock) {
            if (pendingSettings == null) {
                pendingSettings = loadSettings(context).copy()
            }
            return pendingSettings!!
        }
    }

    /**
     * Update pending settings without saving to disk.
     */
    fun updatePendingSettings(context: Context, update: (SettingsData) -> Unit) {
        synchronized(cacheLock) {
            val pending = getPendingSettings(context)
            update(pending)
            pendingSettings = pending
        }
    }

    /**
     * Save pending settings to disk and clear pending state.
     */
    fun savePendingSettings(context: Context) {
        synchronized(cacheLock) {
            pendingSettings?.let { pending ->
                saveSettings(context, pending)
                pendingSettings = null
            }
        }
    }

    /**
     * Discard pending settings without saving.
     */
    fun discardPendingSettings() {
        synchronized(cacheLock) {
            pendingSettings = null
        }
    }

    /**
     * Check if there are unsaved pending changes.
     */
    fun hasPendingChanges(): Boolean {
        synchronized(cacheLock) {
            return pendingSettings != null
        }
    }

    // ── OBD Connection (Yes = real BT, No = simulate) ────────────────────────

    fun isObdConnectionEnabled(context: Context): Boolean =
        loadSettings(context).obdConnectionEnabled

    fun setObdConnectionEnabled(context: Context, value: Boolean) {
        val settings = loadSettings(context)
        settings.obdConnectionEnabled = value
        saveSettings(context, settings)
    }

    // ── Auto-connect ──────────────────────────────────────────────────────────

    fun isAutoConnect(context: Context): Boolean =
        loadSettings(context).autoConnect

    fun setAutoConnect(context: Context, value: Boolean) {
        val settings = loadSettings(context)
        settings.autoConnect = value
        saveSettings(context, settings)
    }

    // ── Trip Logging ──────────────────────────────────────────────────────────

    fun isLoggingEnabled(context: Context): Boolean =
        loadSettings(context).loggingEnabled

    fun setLoggingEnabled(context: Context, value: Boolean) {
        val settings = loadSettings(context)
        settings.loggingEnabled = value
        saveSettings(context, settings)
    }

    fun isAutoShareLogEnabled(context: Context): Boolean =
        loadSettings(context).autoShareLog

    fun setAutoShareLogEnabled(context: Context, value: Boolean) {
        val settings = loadSettings(context)
        settings.autoShareLog = value
        saveSettings(context, settings)
    }

    fun isAccelerometerEnabled(context: Context): Boolean =
        loadSettings(context).accelerometerEnabled

    fun setAccelerometerEnabled(context: Context, value: Boolean) {
        val settings = loadSettings(context)
        settings.accelerometerEnabled = value
        saveSettings(context, settings)
    }

    // ── Log folder (SAF URI string) ───────────────────────────────────────────

    fun getLogFolderUri(context: Context): String? =
        prefs(context).getString(KEY_LOG_FOLDER_URI, null)

    fun setLogFolderUri(context: Context, uriString: String?) =
        prefs(context).edit().putString(KEY_LOG_FOLDER_URI, uriString).apply()

    // ── Global OBD2 polling delays ────────────────────────────────────────────

    fun getGlobalPollingDelayMs(context: Context): Long =
        loadSettings(context).globalPollingDelayMs

    fun setGlobalPollingDelayMs(context: Context, ms: Long) {
        val settings = loadSettings(context)
        settings.globalPollingDelayMs = ms
        saveSettings(context, settings)
    }

    fun getGlobalCommandDelayMs(context: Context): Long =
        loadSettings(context).globalCommandDelayMs

    fun setGlobalCommandDelayMs(context: Context, ms: Long) {
        val settings = loadSettings(context)
        settings.globalCommandDelayMs = ms
        saveSettings(context, settings)
    }

    // ── Active profile ────────────────────────────────────────────────────────

    fun getActiveProfileId(context: Context): String? =
        loadSettings(context).activeProfileId

    fun setActiveProfileId(context: Context, id: String?) {
        val settings = loadSettings(context)
        settings.activeProfileId = id
        saveSettings(context, settings)
    }

    // ── Default layout ────────────────────────────────────────────────────────

    fun getDefaultLayoutName(context: Context): String? =
        loadSettings(context).defaultLayoutName

    fun setDefaultLayoutName(context: Context, name: String?) {
        val settings = loadSettings(context)
        settings.defaultLayoutName = name
        saveSettings(context, settings)
    }

    // ── Last connected device ─────────────────────────────────────────────────

    fun getLastDeviceMac(context: Context): String? =
        loadSettings(context).lastDeviceMac

    fun getLastDeviceName(context: Context): String? =
        loadSettings(context).lastDeviceName

    fun setLastDevice(context: Context, mac: String?, name: String?) {
        val settings = loadSettings(context)
        settings.lastDeviceMac = mac
        settings.lastDeviceName = name
        saveSettings(context, settings)
    }

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
