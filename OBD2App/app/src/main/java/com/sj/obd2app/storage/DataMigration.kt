package com.sj.obd2app.storage

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfileRepository
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import org.json.JSONObject
import java.io.File

/**
 * Handles one-time migration of app data from SharedPreferences and app-private storage
 * to the .obd directory in the user-selected folder.
 */
object DataMigration {

    private const val TAG = "DataMigration"
    private const val PREFS_NAME = "obd2_prefs"
    private const val KEY_MIGRATION_COMPLETE = "data_migrated_to_obd"

    /**
     * Performs migration if needed. Should be called on app startup.
     * 
     * Migration happens if:
     * 1. A log folder is selected (external storage available)
     * 2. Migration hasn't been completed yet
     */
    fun migrateIfNeeded(context: Context) {
        // Check if external storage is available
        if (!AppDataDirectory.isUsingExternalStorage(context)) {
            Log.d(TAG, "External storage not available, skipping migration")
            return
        }

        // Check if migration already completed
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
            Log.d(TAG, "Migration already completed")
            return
        }

        Log.i(TAG, "Starting data migration to .obd directory")

        try {
            // Migrate in order
            migrateSettings(context)
            migrateProfiles(context)
            migrateLayouts(context)
            migratePidAvailability(context)

            // Mark migration as complete
            prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).apply()
            Log.i(TAG, "Migration completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            // Don't mark as complete so it can retry next time
        }
    }

    /**
     * Migrates settings from SharedPreferences to settings.json
     */
    private fun migrateSettings(context: Context) {
        Log.d(TAG, "Migrating settings...")
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dashboardPrefs = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)
        
        val settingsJson = JSONObject().apply {
            // Active profile
            val activeProfileId = prefs.getString("active_profile_id", null)
            if (activeProfileId != null) put("activeProfileId", activeProfileId)
            
            // Default layout
            val defaultLayout = dashboardPrefs.getString("default_layout_name", null)
            if (defaultLayout != null) put("defaultLayoutName", defaultLayout)
            
            // Global delays
            put("globalPollingDelayMs", prefs.getLong("global_polling_delay_ms", AppSettings.DEFAULT_POLLING_DELAY_MS))
            put("globalCommandDelayMs", prefs.getLong("global_command_delay_ms", AppSettings.DEFAULT_COMMAND_DELAY_MS))
            
            // Flags
            put("loggingEnabled", prefs.getBoolean("logging_enabled", false))
            put("autoShareLog", prefs.getBoolean("auto_share_log", false))
            put("accelerometerEnabled", prefs.getBoolean("accelerometer_enabled", false))
            put("autoConnect", prefs.getBoolean(AppSettings.KEY_AUTO_CONNECT, true))
            put("obdConnectionEnabled", prefs.getBoolean("obd_connection_enabled", true))
            
            // Last connected device
            val lastMac = prefs.getString("last_device_mac", null)
            val lastName = prefs.getString("last_device_name", null)
            if (lastMac != null) put("lastDeviceMac", lastMac)
            if (lastName != null) put("lastDeviceName", lastName)
        }
        
        // Write to settings.json
        val settingsFile = AppDataDirectory.getSettingsFileDocumentFile(context)
        if (settingsFile != null) {
            context.contentResolver.openOutputStream(settingsFile.uri)?.use { output ->
                output.write(settingsJson.toString(2).toByteArray())
            }
            Log.d(TAG, "Settings migrated successfully")
        } else {
            Log.w(TAG, "Could not create settings file")
        }
    }

    /**
     * Migrates vehicle profiles from SharedPreferences to individual JSON files
     */
    private fun migrateProfiles(context: Context) {
        Log.d(TAG, "Migrating profiles...")
        
        val repo = VehicleProfileRepository.getInstance(context)
        val profiles = repo.getAll()
        
        Log.d(TAG, "Found ${profiles.size} profiles to migrate")
        
        profiles.forEach { profile ->
            val profileJson = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("fuelType", profile.fuelType.name)
                put("tankCapacityL", profile.tankCapacityL.toDouble())
                put("fuelPricePerLitre", profile.fuelPricePerLitre.toDouble())
                put("enginePowerBhp", profile.enginePowerBhp.toDouble())
                if (profile.vehicleMassKg > 0f) put("vehicleMassKg", profile.vehicleMassKg.toDouble())
                if (profile.obdPollingDelayMs != null) put("obdPollingDelayMs", profile.obdPollingDelayMs)
                if (profile.obdCommandDelayMs != null) put("obdCommandDelayMs", profile.obdCommandDelayMs)
            }
            
            val profileFile = AppDataDirectory.getProfileFileDocumentFile(context, profile.id)
            if (profileFile != null) {
                context.contentResolver.openOutputStream(profileFile.uri)?.use { output ->
                    output.write(profileJson.toString(2).toByteArray())
                }
                Log.d(TAG, "Migrated profile: ${profile.name}")
            }
        }
    }

    /**
     * Migrates dashboard layouts from app-private storage to .obd/layouts
     */
    private fun migrateLayouts(context: Context) {
        Log.d(TAG, "Migrating layouts...")
        
        val oldLayoutsDir = File(context.filesDir, "layouts")
        if (!oldLayoutsDir.exists()) {
            Log.d(TAG, "No layouts to migrate")
            return
        }
        
        val layoutFiles = oldLayoutsDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        Log.d(TAG, "Found ${layoutFiles.size} layouts to migrate")
        
        layoutFiles.forEach { oldFile ->
            val layoutName = oldFile.nameWithoutExtension
            val newFile = AppDataDirectory.getLayoutFileDocumentFile(context, layoutName)
            
            if (newFile != null) {
                val content = oldFile.readText()
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    output.write(content.toByteArray())
                }
                Log.d(TAG, "Migrated layout: $layoutName")
            }
        }
    }

    /**
     * Migrates PID availability data from SharedPreferences to individual JSON files
     */
    private fun migratePidAvailability(context: Context) {
        Log.d(TAG, "Migrating PID availability...")
        
        val prefs = context.getSharedPreferences("pid_availability", Context.MODE_PRIVATE)
        val allKeys = prefs.all.keys
        
        Log.d(TAG, "Found ${allKeys.size} profile PID sets to migrate")
        
        allKeys.forEach { profileId ->
            val jsonStr = prefs.getString(profileId, null)
            if (jsonStr != null) {
                val pidsFile = AppDataDirectory.getProfilePidsFileDocumentFile(context, profileId)
                if (pidsFile != null) {
                    context.contentResolver.openOutputStream(pidsFile.uri)?.use { output ->
                        // Pretty print the JSON
                        val json = JSONObject(jsonStr)
                        output.write(json.toString(2).toByteArray())
                    }
                    Log.d(TAG, "Migrated PIDs for profile: $profileId")
                }
            }
        }
    }

    /**
     * Resets the migration flag. Use for testing or if migration needs to be re-run.
     */
    fun resetMigrationFlag(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, false).apply()
        Log.i(TAG, "Migration flag reset")
    }
}
