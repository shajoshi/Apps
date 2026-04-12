package com.sj.obd2app.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfileRepository
import com.sj.obd2app.settings.PidCache
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.google.gson.GsonBuilder
import com.sj.obd2app.ui.dashboard.model.DashboardMetric
import com.sj.obd2app.ui.dashboard.data.DashboardMetricAdapter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Handles export and import of app data (settings, profiles, layouts) to/from user-selected folders.
 * 
 * Export creates ZIP packages with:
 * - settings.json - All app settings
 * - profiles/ - All vehicle profile JSONs  
 * - layouts/ - All dashboard layout JSONs
 * - export_metadata.json - Export timestamp and version info
 */
object ExportImportManager {
    
    private const val TAG = "ExportImportManager"
    private const val SETTINGS_FILE = "settings.json"
    private const val METADATA_FILE = "export_metadata.json"
    private const val PROFILES_DIR = "profiles"
    private const val LAYOUTS_DIR = "layouts"
    
    // Gson instance with DashboardMetricAdapter for proper deserialization
    private val gson = GsonBuilder()
        .registerTypeAdapter(DashboardMetric::class.java, DashboardMetricAdapter())
        .setPrettyPrinting()
        .create()
    
    /**
     * Export all selected data types to the specified folder as a ZIP file.
     */
    fun exportData(
        context: Context,
        targetFolderUri: Uri,
        exportSettings: Boolean,
        exportProfiles: Boolean,
        exportLayouts: Boolean
    ): Boolean {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val zipFileName = "OBD2App_Export_$timestamp.zip"
            
            val targetFolder = DocumentFile.fromTreeUri(context, targetFolderUri)
                ?: return false
            
            val zipFile = targetFolder.createFile("application/zip", zipFileName)
                ?: return false
            
            val outputStream = context.contentResolver.openOutputStream(zipFile.uri)
                ?: return false.also {
                    Toast.makeText(context, "Failed to create export file", Toast.LENGTH_LONG).show()
                }

            outputStream.use { stream ->
                ZipOutputStream(stream).use { zipOut ->
                    // Add metadata
                    addMetadataToZip(zipOut, timestamp, exportSettings, exportProfiles, exportLayouts)

                    // Export settings
                    if (exportSettings) {
                        exportSettingsToZip(context, zipOut)
                    }

                    // Export profiles
                    if (exportProfiles) {
                        exportProfilesToZip(context, zipOut)
                    }

                    // Export layouts
                    if (exportLayouts) {
                        exportLayoutsToZip(context, zipOut)
                    }
                }
            }

            Log.i(TAG, "Export completed successfully: $zipFileName")
            Toast.makeText(context, "Data exported to $zipFileName", Toast.LENGTH_SHORT).show()
            true

        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Export a debug JSON snapshot to the given Uri.
     * Includes full PID cache data and related connection metadata.
     */
    fun exportDebugJson(context: Context, targetFileUri: Uri): Boolean {
        return try {
            val settings = AppSettings.getAllSettings(context)
            val pidCaches = AppSettings.getAllPidCaches(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

            val payload = JSONObject().apply {
                put("exportType", "debug")
                put("exportTimestamp", timestamp)
                put("appVersion", "1.0") // TODO: Get actual version
                put("connection", JSONObject().apply {
                    put("lastDeviceMac", settings.lastDeviceMac ?: "")
                    put("lastDeviceName", settings.lastDeviceName ?: "")
                    put("autoConnect", settings.autoConnect)
                    put("obdConnectionEnabled", settings.obdConnectionEnabled)
                    put("forceBleConnection", settings.forceBleConnection)
                })
                put("settings", JSONObject().apply {
                    put("activeProfileId", settings.activeProfileId ?: "")
                    put("defaultLayoutName", settings.defaultLayoutName ?: "")
                    put("globalPollingDelayMs", settings.globalPollingDelayMs)
                    put("globalCommandDelayMs", settings.globalCommandDelayMs)
                    put("loggingEnabled", settings.loggingEnabled)
                    put("autoShareLog", settings.autoShareLog)
                    put("accelerometerEnabled", settings.accelerometerEnabled)
                    put("btLoggingEnabled", settings.btLoggingEnabled)
                })
                put("pidCacheMap", serializePidCaches(pidCaches))
            }

            context.contentResolver.openOutputStream(targetFileUri, "wt")?.use { output ->
                output.write(payload.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return false

            Toast.makeText(context, "Debug JSON exported", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Debug export failed", e)
            Toast.makeText(context, "Debug export failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }
    
    /**
     * Import data from selected folder with smart merge behavior.
     *
     * Folder import only looks for loose files and subfolders. ZIP archives are
     * handled exclusively by [importZip].
     */
    fun importData(context: Context, sourceFolderUri: Uri): ImportResult {
        return try {
            val sourceFolder = DocumentFile.fromTreeUri(context, sourceFolderUri)
                ?: return ImportResult(false, "Could not access source folder")
            
            val result = ImportResult()

            // Import from individual files/folders only.
            importFromFolder(context, sourceFolder, result)
            
            // Set success status and message based on what was imported
            if (result.totalItemsImported > 0) {
                result.success = true
                if (result.message.isEmpty()) {
                    result.message = "Import completed successfully"
                }
            } else if (result.message.isEmpty()) {
                result.message = "No data found to import"
            }
            
            // Show simple Toast
            if (result.success) {
                Toast.makeText(context, "Import completed successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            }
            
            // Always show detailed notification
            showImportNotification(context, result)
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            val result = ImportResult(false, "Import failed: ${e.message}")
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            result
        }
    }

    /**
     * Import data directly from a ZIP file exported by the app.
     */
    fun importZip(context: Context, zipFileUri: Uri): ImportResult {
        return try {
            val zipFile = DocumentFile.fromSingleUri(context, zipFileUri)
                ?: return ImportResult(false, "Could not access ZIP file")

            if (zipFile.name?.endsWith(".zip", ignoreCase = true) != true) {
                return ImportResult(false, "Selected file is not a ZIP archive")
            }

            val result = ImportResult()
            importFromZip(context, zipFile, result)

            if (result.totalItemsImported > 0) {
                result.success = true
                if (result.message.isEmpty()) {
                    result.message = "Import completed successfully"
                }
            } else if (result.message.isEmpty()) {
                result.message = "No data found to import"
            }

            if (result.success) {
                Toast.makeText(context, "Import completed successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Import failed", Toast.LENGTH_SHORT).show()
            }

            showImportNotification(context, result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "ZIP import failed", e)
            val result = ImportResult(false, "Import failed: ${e.message}")
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            result
        }
    }
    
    private fun addMetadataToZip(
        zipOut: ZipOutputStream,
        timestamp: String,
        exportSettings: Boolean,
        exportProfiles: Boolean,
        exportLayouts: Boolean
    ) {
        val metadata = JSONObject().apply {
            put("exportTimestamp", timestamp)
            put("appVersion", "1.0") // TODO: Get actual version
            put("exportSettings", exportSettings)
            put("exportProfiles", exportProfiles)
            put("exportLayouts", exportLayouts)
        }
        
        zipOut.putNextEntry(ZipEntry(METADATA_FILE))
        zipOut.write(metadata.toString().toByteArray())
        zipOut.closeEntry()
    }
    
    private fun exportSettingsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val settings = AppSettings.getAllSettings(context)
            val settingsJson = JSONObject().apply {
                put("activeProfileId", settings.activeProfileId ?: "")
                put("defaultLayoutName", settings.defaultLayoutName ?: "")
                put("globalPollingDelayMs", settings.globalPollingDelayMs)
                put("globalCommandDelayMs", settings.globalCommandDelayMs)
                put("loggingEnabled", settings.loggingEnabled)
                put("autoShareLog", settings.autoShareLog)
                put("accelerometerEnabled", settings.accelerometerEnabled)
                put("autoConnect", settings.autoConnect)
                put("obdConnectionEnabled", settings.obdConnectionEnabled)
                put("btLoggingEnabled", settings.btLoggingEnabled)
                put("forceBleConnection", settings.forceBleConnection)
                put("lastDeviceMac", settings.lastDeviceMac ?: "")
                put("lastDeviceName", settings.lastDeviceName ?: "")
            }
            
            zipOut.putNextEntry(ZipEntry(SETTINGS_FILE))
            zipOut.write(settingsJson.toString().toByteArray())
            zipOut.closeEntry()
            
            Log.d(TAG, "Settings exported to ZIP")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
        }
    }
    
    private fun exportProfilesToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val profilesDir = File(context.filesDir, "profiles")
            if (profilesDir.exists()) {
                profilesDir.listFiles { file -> 
                    file.isFile && file.name.endsWith(".json") 
                }?.forEach { profileFile ->
                    zipOut.putNextEntry(ZipEntry("$PROFILES_DIR/${profileFile.name}"))
                    zipOut.write(profileFile.readBytes())
                    zipOut.closeEntry()
                }
            }
            Log.d(TAG, "Profiles exported to ZIP")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export profiles", e)
        }
    }
    
    private fun exportLayoutsToZip(context: Context, zipOut: ZipOutputStream) {
        try {
            val layoutsDir = File(context.filesDir, "layouts")
            if (layoutsDir.exists()) {
                layoutsDir.listFiles { file -> 
                    file.isFile && file.name.startsWith("dashboard_") && file.name.endsWith(".json")
                }?.forEach { layoutFile ->
                    zipOut.putNextEntry(ZipEntry("$LAYOUTS_DIR/${layoutFile.name}"))
                    zipOut.write(layoutFile.readBytes())
                    zipOut.closeEntry()
                }
            }
            Log.d(TAG, "Layouts exported to ZIP")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export layouts", e)
        }
    }
    
    private fun importFromZip(context: Context, zipFile: DocumentFile, result: ImportResult) {
        context.contentResolver.openInputStream(zipFile.uri)?.use { inputStream ->
            java.util.zip.ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                
                while (entry != null) {
                    when {
                        entry.name == SETTINGS_FILE -> {
                            importSettingsFromZip(context, zipIn, result)
                        }
                        entry.name.startsWith("$PROFILES_DIR/") -> {
                            importProfileFromZip(context, zipIn, entry.name, result)
                        }
                        entry.name.startsWith("$LAYOUTS_DIR/") -> {
                            importLayoutFromZip(context, zipIn, entry.name, result)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        }
    }
    
    private fun importFromFolder(context: Context, sourceFolder: DocumentFile, result: ImportResult) {
        // Import settings if settings.json exists
        sourceFolder.findFile("settings.json")?.let { settingsFile ->
            importSettingsFromFile(context, settingsFile, result)
        }
        
        // Import profiles from profiles/ directory
        sourceFolder.findFile("profiles")?.let { profilesDir ->
            profilesDir.listFiles()?.forEach { profileFile ->
                if (profileFile.name?.endsWith(".json") == true) {
                    importProfileFromFile(context, profileFile, result)
                }
            }
        }
        
        // Import layouts from layouts/ directory  
        sourceFolder.findFile("layouts")?.let { layoutsDir ->
            layoutsDir.listFiles()?.forEach { layoutFile ->
                if (layoutFile.name?.startsWith("dashboard_") == true && layoutFile.name?.endsWith(".json") == true) {
                    importLayoutFromFile(context, layoutFile, result)
                }
            }
        }
    }
    
    private fun importSettingsFromZip(context: Context, zipIn: java.util.zip.ZipInputStream, result: ImportResult) {
        try {
            val content = zipIn.readBytes().toString(Charsets.UTF_8)
            val json = JSONObject(content)
            
            // Update settings with imported values
            AppSettings.updatePendingSettings(context) { settings ->
                settings.activeProfileId = json.optString("activeProfileId", "").takeIf { it.isNotEmpty() }
                settings.defaultLayoutName = json.optString("defaultLayoutName", "").takeIf { it.isNotEmpty() }
                settings.globalPollingDelayMs = json.optLong("globalPollingDelayMs", settings.globalPollingDelayMs)
                settings.globalCommandDelayMs = json.optLong("globalCommandDelayMs", settings.globalCommandDelayMs)
                settings.loggingEnabled = json.optBoolean("loggingEnabled", settings.loggingEnabled)
                settings.autoShareLog = json.optBoolean("autoShareLog", settings.autoShareLog)
                settings.accelerometerEnabled = json.optBoolean("accelerometerEnabled", settings.accelerometerEnabled)
                settings.autoConnect = json.optBoolean("autoConnect", settings.autoConnect)
                settings.obdConnectionEnabled = json.optBoolean("obdConnectionEnabled", settings.obdConnectionEnabled)
                settings.btLoggingEnabled = json.optBoolean("btLoggingEnabled", settings.btLoggingEnabled)
                settings.forceBleConnection = json.optBoolean("forceBleConnection", settings.forceBleConnection)
                settings.lastDeviceMac = json.optString("lastDeviceMac", "").takeIf { it.isNotEmpty() }
                settings.lastDeviceName = json.optString("lastDeviceName", "").takeIf { it.isNotEmpty() }
            }
            
            result.settingsImported = true
            Log.d(TAG, "Settings imported from ZIP")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            result.addError("Failed to import settings: ${e.message}")
        }
    }
    
    private fun importProfileFromZip(context: Context, zipIn: java.util.zip.ZipInputStream, fileName: String, result: ImportResult) {
        try {
            val content = zipIn.readBytes().toString(Charsets.UTF_8)
            val profileJson = org.json.JSONObject(content)
            val profile = com.sj.obd2app.settings.VehicleProfile.fromJSON(profileJson)
            
            val repo = VehicleProfileRepository.getInstance(context)
            repo.save(profile)
            
            result.profilesImported.add(profile.name)
            Log.d(TAG, "Profile imported from ZIP: ${profile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import profile: $fileName", e)
            result.addError("Failed to import profile $fileName: ${e.message}")
        }
    }
    
    private fun importLayoutFromZip(context: Context, zipIn: java.util.zip.ZipInputStream, fileName: String, result: ImportResult) {
        try {
            val content = zipIn.readBytes().toString(Charsets.UTF_8)
            val layout = gson.fromJson(content, com.sj.obd2app.ui.dashboard.model.DashboardLayout::class.java)
            
            val repo = LayoutRepository(context)
            repo.saveLayout(layout)
            
            result.layoutsImported.add(layout.name)
            Log.d(TAG, "Layout imported from ZIP: ${layout.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import layout: $fileName", e)
            result.addError("Failed to import layout $fileName: ${e.message}")
        }
    }
    
    private fun importSettingsFromFile(context: Context, settingsFile: DocumentFile, result: ImportResult) {
        try {
            val content = context.contentResolver.openInputStream(settingsFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            
            val json = JSONObject(content)
            
            AppSettings.updatePendingSettings(context) { settings ->
                settings.activeProfileId = json.optString("activeProfileId", "").takeIf { it.isNotEmpty() }
                settings.defaultLayoutName = json.optString("defaultLayoutName", "").takeIf { it.isNotEmpty() }
                settings.globalPollingDelayMs = json.optLong("globalPollingDelayMs", settings.globalPollingDelayMs)
                settings.globalCommandDelayMs = json.optLong("globalCommandDelayMs", settings.globalCommandDelayMs)
                settings.loggingEnabled = json.optBoolean("loggingEnabled", settings.loggingEnabled)
                settings.autoShareLog = json.optBoolean("autoShareLog", settings.autoShareLog)
                settings.accelerometerEnabled = json.optBoolean("accelerometerEnabled", settings.accelerometerEnabled)
                settings.autoConnect = json.optBoolean("autoConnect", settings.autoConnect)
                settings.obdConnectionEnabled = json.optBoolean("obdConnectionEnabled", settings.obdConnectionEnabled)
                settings.btLoggingEnabled = json.optBoolean("btLoggingEnabled", settings.btLoggingEnabled)
                settings.forceBleConnection = json.optBoolean("forceBleConnection", settings.forceBleConnection)
                settings.lastDeviceMac = json.optString("lastDeviceMac", "").takeIf { it.isNotEmpty() }
                settings.lastDeviceName = json.optString("lastDeviceName", "").takeIf { it.isNotEmpty() }
            }
            
            result.settingsImported = true
            Log.d(TAG, "Settings imported from file")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings from file", e)
            result.addError("Failed to import settings: ${e.message}")
        }
    }
    
    private fun importProfileFromFile(context: Context, profileFile: DocumentFile, result: ImportResult) {
        try {
            val content = context.contentResolver.openInputStream(profileFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            
            val profileJson = org.json.JSONObject(content)
            val profile = com.sj.obd2app.settings.VehicleProfile.fromJSON(profileJson)
            
            val repo = VehicleProfileRepository.getInstance(context)
            repo.save(profile)
            
            result.profilesImported.add(profile.name)
            Log.d(TAG, "Profile imported from file: ${profile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import profile from file: ${profileFile.name}", e)
            result.addError("Failed to import profile ${profileFile.name}: ${e.message}")
        }
    }
    
    private fun importLayoutFromFile(context: Context, layoutFile: DocumentFile, result: ImportResult) {
        try {
            val content = context.contentResolver.openInputStream(layoutFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            
            val layout = gson.fromJson(content, com.sj.obd2app.ui.dashboard.model.DashboardLayout::class.java)
            
            val repo = LayoutRepository(context)
            repo.saveLayout(layout)
            
            result.layoutsImported.add(layout.name)
            Log.d(TAG, "Layout imported from file: ${layout.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import layout from file: ${layoutFile.name}", e)
            result.addError("Failed to import layout ${layoutFile.name}: ${e.message}")
        }
    }
    
    /**
     * Result of import operation with detailed feedback.
     */
    data class ImportResult(
        var success: Boolean = false,
        var message: String = "",
        var settingsImported: Boolean = false,
        val profilesImported: MutableList<String> = mutableListOf(),
        val layoutsImported: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf()
    ) {
        val totalItemsImported: Int get() = profilesImported.size + layoutsImported.size + if (settingsImported) 1 else 0
        
        fun addError(error: String) {
            errors.add(error)
            success = false
            if (message == "Import completed successfully") {
                message = "Import completed with issues"
            }
        }
        
        fun getSummary(): String {
            val summary = StringBuilder()
            if (settingsImported) summary.append("• Settings imported\n")
            if (profilesImported.isNotEmpty()) summary.append("• ${profilesImported.size} profile(s) imported\n")
            if (layoutsImported.isNotEmpty()) summary.append("• ${layoutsImported.size} layout(s) imported\n")
            if (errors.isNotEmpty()) {
                summary.append("• ${errors.size} error(s)\n")
            }
            return summary.toString()
        }
        
        fun getDetailedSummary(): String {
            val summary = StringBuilder()
            if (settingsImported) summary.append("• Settings\n")
            profilesImported.forEach { profile ->
                summary.append("• Profile: $profile\n")
            }
            layoutsImported.forEach { layout ->
                summary.append("• Layout: $layout\n")
            }
            if (errors.isNotEmpty()) {
                summary.append("• Errors:\n")
                errors.forEach { error ->
                    summary.append("  - $error\n")
                }
            }
            return summary.toString()
        }
    }
    
    private fun showImportNotification(context: Context, result: ImportResult) {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Notification permission not granted, skipping notification")
                // Show Toast instead if notification permission is denied
                Toast.makeText(context, "Import completed: ${result.getDetailedSummary()}", Toast.LENGTH_LONG).show()
                return
            }
        }
        
        val channelId = "import_feedback"
        
        // Create notification channel for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Import Feedback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Detailed feedback for data import operations"
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Import ${if (result.success) "Completed" else "Failed"}")
            .setContentText("${result.totalItemsImported} items imported")
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.getDetailedSummary()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        
        val notificationManager = NotificationManagerCompat.from(context)
        try {
            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification", e)
            // Fallback to Toast if notification fails
            Toast.makeText(context, "Import completed: ${result.getDetailedSummary()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun serializePidCaches(cacheMap: Map<String, PidCache>): JSONObject {
        val json = JSONObject()
        cacheMap.forEach { (macAddress, cache) ->
            json.put(macAddress, cache.toJSON())
        }
        return json
    }
}
