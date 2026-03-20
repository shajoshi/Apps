package com.sj.obd2app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.sj.obd2app.settings.AppSettings
import java.io.File

/**
 * Manages access to the .obd directory for storing app data files.
 * 
 * If a log folder is selected via SAF, data is stored in <selected_folder>/.obd/
 * Otherwise, falls back to app-private storage for backward compatibility.
 */
object AppDataDirectory {

    private const val OBD_DIR_NAME = ".obd"
    private const val PROFILES_DIR_NAME = "profiles"
    private const val LAYOUTS_DIR_NAME = "layouts"
    private const val SETTINGS_FILE_NAME = "obdapp_settings.json"
    private const val PROFILE_FILE_PREFIX = "vehicle_profile_"
    private const val DASHBOARD_FILE_PREFIX = "dashboard_"

    /**
     * Returns true if external storage (.obd directory) is available and should be used.
     */
    fun isUsingExternalStorage(context: Context): Boolean {
        val uriStr = AppSettings.getLogFolderUri(context)
        return uriStr != null && getObdDirectoryDocumentFile(context) != null
    }

    /**
     * Returns the .obd directory as a DocumentFile, or null if not available.
     * Creates the directory if it doesn't exist.
     */
    fun getObdDirectoryDocumentFile(context: Context): DocumentFile? {
        val uriStr = AppSettings.getLogFolderUri(context) ?: return null
        val uri = Uri.parse(uriStr)
        
        val rootDir = DocumentFile.fromTreeUri(context, uri) ?: return null
        
        // Check if .obd directory exists
        var obdDir = rootDir.findFile(OBD_DIR_NAME)
        if (obdDir == null) {
            // Create .obd directory
            obdDir = rootDir.createDirectory(OBD_DIR_NAME)
        }
        
        return obdDir
    }

    /**
     * Returns the profiles directory as a DocumentFile, or null if not available.
     */
    private fun getProfilesDirectoryDocumentFile(context: Context): DocumentFile? {
        val obdDir = getObdDirectoryDocumentFile(context) ?: return null
        
        var profilesDir = obdDir.findFile(PROFILES_DIR_NAME)
        if (profilesDir == null) {
            profilesDir = obdDir.createDirectory(PROFILES_DIR_NAME)
        }
        
        return profilesDir
    }

    /**
     * Returns the layouts directory as a DocumentFile, or null if not available.
     */
    private fun getLayoutsDirectoryDocumentFile(context: Context): DocumentFile? {
        val obdDir = getObdDirectoryDocumentFile(context) ?: return null
        
        var layoutsDir = obdDir.findFile(LAYOUTS_DIR_NAME)
        if (layoutsDir == null) {
            layoutsDir = obdDir.createDirectory(LAYOUTS_DIR_NAME)
        }
        
        return layoutsDir
    }

    /**
     * Returns a DocumentFile for a specific profile file.
     * Creates the file if it doesn't exist.
     * Uses format: vehicle_profile_<name>.json
     */
    fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
        val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
        
        return profilesDir.findFile(fileName) 
            ?: profilesDir.createFile("application/json", fileName)
    }


    /**
     * Returns a DocumentFile for a specific layout file.
     * Creates the file if it doesn't exist.
     * Uses format: dashboard_<name>.json
     */
    fun getLayoutFileDocumentFile(context: Context, layoutName: String): DocumentFile? {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return null
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${DASHBOARD_FILE_PREFIX}${safeName}.json"
        
        return layoutsDir.findFile(fileName)
            ?: layoutsDir.createFile("application/json", fileName)
    }

    /**
     * Returns a DocumentFile for the common settings file.
     * Creates the file if it doesn't exist.
     */
    fun getSettingsFileDocumentFile(context: Context): DocumentFile? {
        val obdDir = getObdDirectoryDocumentFile(context) ?: return null
        
        return obdDir.findFile(SETTINGS_FILE_NAME)
            ?: obdDir.createFile("application/json", SETTINGS_FILE_NAME)
    }

    /**
     * Lists all profile files in the profiles directory.
     * Matches pattern: vehicle_profile_*.json (excluding *_pids.json)
     */
    fun listProfileFilesDocumentFile(context: Context): List<DocumentFile> {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return emptyList()
        
        return profilesDir.listFiles().filter { 
            it.isFile && 
            it.name?.startsWith(PROFILE_FILE_PREFIX) == true && 
            it.name?.endsWith(".json") == true
        }
    }

    /**
     * Lists all layout files in the layouts directory.
     * Matches pattern: dashboard_*.json
     */
    fun listLayoutFilesDocumentFile(context: Context): List<DocumentFile> {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return emptyList()
        
        return layoutsDir.listFiles().filter { 
            it.isFile && 
            it.name?.startsWith(DASHBOARD_FILE_PREFIX) == true && 
            it.name?.endsWith(".json") == true
        }
    }

    /**
     * Deletes a specific profile file.
     */
    fun deleteProfileFile(context: Context, profileName: String): Boolean {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return false
        val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
        val file = profilesDir.findFile(fileName)
        return file?.delete() ?: false
    }


    /**
     * Deletes a specific layout file.
     */
    fun deleteLayoutFile(context: Context, layoutName: String): Boolean {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return false
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${DASHBOARD_FILE_PREFIX}${safeName}.json"
        val file = layoutsDir.findFile(fileName)
        return file?.delete() ?: false
    }

    /**
     * Returns the fallback directory for profiles in app-private storage.
     */
    fun getFallbackProfilesDir(context: Context): File {
        return File(context.filesDir, "profiles").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Returns the fallback directory for layouts in app-private storage.
     */
    fun getFallbackLayoutsDir(context: Context): File {
        return File(context.filesDir, "layouts").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Returns a File for a specific profile in app-private storage.
     * Uses format: vehicle_profile_<name>.json
     */
    fun getProfileFilePrivate(context: Context, profileName: String): File {
        val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        return File(getFallbackProfilesDir(context), "${PROFILE_FILE_PREFIX}${safeName}.json")
    }
    
    
    /**
     * Returns a File for a specific layout in app-private storage.
     * Uses format: dashboard_<name>.json
     */
    fun getLayoutFilePrivate(context: Context, layoutName: String): File {
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        return File(getFallbackLayoutsDir(context), "${DASHBOARD_FILE_PREFIX}${safeName}.json")
    }
    
    /**
     * Returns a File for settings in app-private storage.
     * Uses format: obdapp_settings.json
     */
    fun getSettingsFilePrivate(context: Context): File {
        return File(context.filesDir, SETTINGS_FILE_NAME)
    }
    
    /**
     * Lists all profile files in app-private storage.
     * Matches pattern: vehicle_profile_*.json (excluding *_pids.json)
     */
    fun listProfileFilesPrivate(context: Context): List<File> {
        val profilesDir = getFallbackProfilesDir(context)
        return profilesDir.listFiles { file ->
            file.isFile && 
            file.name.startsWith(PROFILE_FILE_PREFIX) && 
            file.name.endsWith(".json")
        }?.toList() ?: emptyList()
    }
    
    /**
     * Lists all layout files in app-private storage.
     * Matches pattern: dashboard_*.json
     */
    fun listLayoutFilesPrivate(context: Context): List<File> {
        val layoutsDir = getFallbackLayoutsDir(context)
        return layoutsDir.listFiles { file ->
            file.isFile && 
            file.name.startsWith(DASHBOARD_FILE_PREFIX) && 
            file.name.endsWith(".json")
        }?.toList() ?: emptyList()
    }
}
