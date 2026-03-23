package com.sj.obd2app.storage

import android.content.Context
import android.net.Uri
import android.util.Log
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

    private const val TAG = "AppDataDirectory"
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
        val result = uriStr != null && getObdDirectoryDocumentFile(context) != null
        Log.d(TAG, "isUsingExternalStorage: $result (uri=$uriStr)")
        return result
    }

    /**
     * Returns the .obd directory as a DocumentFile, or null if not available.
     * Creates the directory if it doesn't exist.
     */
    fun getObdDirectoryDocumentFile(context: Context): DocumentFile? {
        val uriStr = AppSettings.getLogFolderUri(context)
        if (uriStr == null) {
            Log.d(TAG, "getObdDirectoryDocumentFile: no URI configured")
            return null
        }
        
        Log.d(TAG, "getObdDirectoryDocumentFile: URI=$uriStr")
        val uri = Uri.parse(uriStr)
        
        val rootDir = DocumentFile.fromTreeUri(context, uri)
        if (rootDir == null) {
            Log.e(TAG, "getObdDirectoryDocumentFile: fromTreeUri returned null")
            return null
        }
        
        Log.d(TAG, "getObdDirectoryDocumentFile: root exists=${rootDir.exists()}, canRead=${rootDir.canRead()}")
        
        // Check if .obd directory exists
        var obdDir = rootDir.findFile(OBD_DIR_NAME)
        if (obdDir == null) {
            Log.w(TAG, "getObdDirectoryDocumentFile: '$OBD_DIR_NAME' not found, creating new directory")
            obdDir = rootDir.createDirectory(OBD_DIR_NAME)
            if (obdDir == null) {
                Log.e(TAG, "getObdDirectoryDocumentFile: createDirectory failed")
            } else {
                Log.d(TAG, "getObdDirectoryDocumentFile: created new '$OBD_DIR_NAME' directory")
            }
        } else {
            Log.d(TAG, "getObdDirectoryDocumentFile: found existing '$OBD_DIR_NAME' directory")
        }
        
        return obdDir
    }

    /**
     * Returns the profiles directory as a DocumentFile, or null if not available.
     */
    private fun getProfilesDirectoryDocumentFile(context: Context): DocumentFile? {
        Log.d(TAG, "getProfilesDirectoryDocumentFile: called")
        val obdDir = getObdDirectoryDocumentFile(context)
        if (obdDir == null) {
            Log.w(TAG, "getProfilesDirectoryDocumentFile: obd directory is null")
            return null
        }
        
        var profilesDir = obdDir.findFile(PROFILES_DIR_NAME)
        if (profilesDir == null) {
            Log.w(TAG, "getProfilesDirectoryDocumentFile: '$PROFILES_DIR_NAME' not found, creating new directory")
            profilesDir = obdDir.createDirectory(PROFILES_DIR_NAME)
            if (profilesDir == null) {
                Log.e(TAG, "getProfilesDirectoryDocumentFile: createDirectory failed")
            } else {
                Log.d(TAG, "getProfilesDirectoryDocumentFile: created new '$PROFILES_DIR_NAME' directory")
            }
        } else {
            Log.d(TAG, "getProfilesDirectoryDocumentFile: found existing '$PROFILES_DIR_NAME' directory")
        }
        
        return profilesDir
    }

    /**
     * Returns the layouts directory as a DocumentFile, or null if not available.
     */
    private fun getLayoutsDirectoryDocumentFile(context: Context): DocumentFile? {
        Log.d(TAG, "getLayoutsDirectoryDocumentFile: called")
        val obdDir = getObdDirectoryDocumentFile(context)
        if (obdDir == null) {
            Log.w(TAG, "getLayoutsDirectoryDocumentFile: obd directory is null")
            return null
        }
        
        var layoutsDir = obdDir.findFile(LAYOUTS_DIR_NAME)
        if (layoutsDir == null) {
            Log.w(TAG, "getLayoutsDirectoryDocumentFile: '$LAYOUTS_DIR_NAME' not found, creating new directory")
            layoutsDir = obdDir.createDirectory(LAYOUTS_DIR_NAME)
            if (layoutsDir == null) {
                Log.e(TAG, "getLayoutsDirectoryDocumentFile: createDirectory failed")
            } else {
                Log.d(TAG, "getLayoutsDirectoryDocumentFile: created new '$LAYOUTS_DIR_NAME' directory")
            }
        } else {
            Log.d(TAG, "getLayoutsDirectoryDocumentFile: found existing '$LAYOUTS_DIR_NAME' directory")
        }
        
        return layoutsDir
    }

    /**
     * Returns a DocumentFile for a specific profile file.
     * Creates the file if it doesn't exist.
     * Uses format: vehicle_profile_<name>.json
     */
    fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
        Log.d(TAG, "getProfileFileDocumentFile: called for profile '$profileName'")
        
        val profilesDir = getProfilesDirectoryDocumentFile(context)
        if (profilesDir == null) {
            Log.w(TAG, "getProfileFileDocumentFile: profiles directory is null")
            return null
        }
        
        val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
        Log.d(TAG, "getProfileFileDocumentFile: looking for file '$fileName'")
        
        val existingFile = profilesDir.findFile(fileName)
        if (existingFile != null) {
            Log.d(TAG, "getProfileFileDocumentFile: found existing file '$fileName'")
            return existingFile
        }
        
        Log.w(TAG, "getProfileFileDocumentFile: file '$fileName' not found, creating new file")
        val newFile = profilesDir.createFile("application/json", fileName)
        if (newFile == null) {
            Log.e(TAG, "getProfileFileDocumentFile: createFile failed")
        } else {
            Log.d(TAG, "getProfileFileDocumentFile: created new file '$fileName'")
        }
        return newFile
    }


    /**
     * Returns a DocumentFile for a specific layout file.
     * Creates the file if it doesn't exist.
     * Uses format: dashboard_<name>.json
     */
    fun getLayoutFileDocumentFile(context: Context, layoutName: String): DocumentFile? {
        Log.d(TAG, "getLayoutFileDocumentFile: called for layout '$layoutName'")
        
        val layoutsDir = getLayoutsDirectoryDocumentFile(context)
        if (layoutsDir == null) {
            Log.w(TAG, "getLayoutFileDocumentFile: layouts directory is null")
            return null
        }
        
        val safeName = layoutName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val fileName = "${DASHBOARD_FILE_PREFIX}${safeName}.json"
        Log.d(TAG, "getLayoutFileDocumentFile: looking for file '$fileName'")
        
        val existingFile = layoutsDir.findFile(fileName)
        if (existingFile != null) {
            Log.d(TAG, "getLayoutFileDocumentFile: found existing file '$fileName'")
            return existingFile
        }
        
        Log.w(TAG, "getLayoutFileDocumentFile: file '$fileName' not found, creating new file")
        val newFile = layoutsDir.createFile("application/json", fileName)
        if (newFile == null) {
            Log.e(TAG, "getLayoutFileDocumentFile: createFile failed")
        } else {
            Log.d(TAG, "getLayoutFileDocumentFile: created new file '$fileName'")
        }
        return newFile
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
        Log.d(TAG, "listProfileFilesDocumentFile: called")
        
        val profilesDir = getProfilesDirectoryDocumentFile(context)
        if (profilesDir == null) {
            Log.w(TAG, "listProfileFilesDocumentFile: profiles directory is null, returning empty list")
            return emptyList()
        }
        
        Log.d(TAG, "listProfileFilesDocumentFile: calling listFiles() on profiles directory")
        val allFiles = profilesDir.listFiles()
        Log.d(TAG, "listProfileFilesDocumentFile: listFiles() returned ${allFiles.size} items")
        
        allFiles.forEachIndexed { index, file ->
            Log.d(TAG, "  [$index] name='${file.name}', isFile=${file.isFile}, isDirectory=${file.isDirectory}")
        }
        
        val filtered = allFiles.filter { 
            it.isFile && 
            it.name?.startsWith(PROFILE_FILE_PREFIX) == true && 
            it.name?.endsWith(".json") == true
        }
        
        Log.d(TAG, "listProfileFilesDocumentFile: after filtering, ${filtered.size} profile files found")
        return filtered
    }

    /**
     * Lists all layout files in the layouts directory.
     * Matches pattern: dashboard_*.json
     */
    fun listLayoutFilesDocumentFile(context: Context): List<DocumentFile> {
        Log.d(TAG, "listLayoutFilesDocumentFile: called")
        
        val layoutsDir = getLayoutsDirectoryDocumentFile(context)
        if (layoutsDir == null) {
            Log.w(TAG, "listLayoutFilesDocumentFile: layouts directory is null, returning empty list")
            return emptyList()
        }
        
        Log.d(TAG, "listLayoutFilesDocumentFile: calling listFiles() on layouts directory")
        val allFiles = layoutsDir.listFiles()
        Log.d(TAG, "listLayoutFilesDocumentFile: listFiles() returned ${allFiles.size} items")
        
        allFiles.forEachIndexed { index, file ->
            Log.d(TAG, "  [$index] name='${file.name}', isFile=${file.isFile}, isDirectory=${file.isDirectory}")
        }
        
        val filtered = allFiles.filter { 
            it.isFile && 
            it.name?.startsWith(DASHBOARD_FILE_PREFIX) == true && 
            it.name?.endsWith(".json") == true
        }
        
        Log.d(TAG, "listLayoutFilesDocumentFile: after filtering, ${filtered.size} layout files found")
        return filtered
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
