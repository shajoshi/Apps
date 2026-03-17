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
    private const val SETTINGS_FILE_NAME = "settings.json"

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
    private fun getObdDirectoryDocumentFile(context: Context): DocumentFile? {
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
     */
    fun getProfileFileDocumentFile(context: Context, profileId: String): DocumentFile? {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
        val fileName = "$profileId.json"
        
        return profilesDir.findFile(fileName) 
            ?: profilesDir.createFile("application/json", fileName)
    }

    /**
     * Returns a DocumentFile for a specific profile's PID availability file.
     * Creates the file if it doesn't exist.
     */
    fun getProfilePidsFileDocumentFile(context: Context, profileId: String): DocumentFile? {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
        val fileName = "${profileId}_pids.json"
        
        return profilesDir.findFile(fileName)
            ?: profilesDir.createFile("application/json", fileName)
    }

    /**
     * Returns a DocumentFile for a specific layout file.
     * Creates the file if it doesn't exist.
     */
    fun getLayoutFileDocumentFile(context: Context, layoutName: String): DocumentFile? {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return null
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val fileName = "$safeName.json"
        
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
     */
    fun listProfileFilesDocumentFile(context: Context): List<DocumentFile> {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return emptyList()
        
        return profilesDir.listFiles().filter { 
            it.isFile && it.name?.endsWith(".json") == true && !it.name!!.endsWith("_pids.json")
        }
    }

    /**
     * Lists all layout files in the layouts directory.
     */
    fun listLayoutFilesDocumentFile(context: Context): List<DocumentFile> {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return emptyList()
        
        return layoutsDir.listFiles().filter { 
            it.isFile && it.name?.endsWith(".json") == true
        }
    }

    /**
     * Deletes a specific profile file.
     */
    fun deleteProfileFile(context: Context, profileId: String): Boolean {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return false
        val fileName = "$profileId.json"
        val file = profilesDir.findFile(fileName)
        return file?.delete() ?: false
    }

    /**
     * Deletes a specific profile's PID file.
     */
    fun deleteProfilePidsFile(context: Context, profileId: String): Boolean {
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return false
        val fileName = "${profileId}_pids.json"
        val file = profilesDir.findFile(fileName)
        return file?.delete() ?: false
    }

    /**
     * Deletes a specific layout file.
     */
    fun deleteLayoutFile(context: Context, layoutName: String): Boolean {
        val layoutsDir = getLayoutsDirectoryDocumentFile(context) ?: return false
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "")
        val fileName = "$safeName.json"
        val file = layoutsDir.findFile(fileName)
        return file?.delete() ?: false
    }

    /**
     * Returns the fallback directory for profiles in app-private storage.
     */
    fun getFallbackProfilesDir(context: Context): File {
        return File(context.filesDir, "profiles_fallback").apply {
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
}
