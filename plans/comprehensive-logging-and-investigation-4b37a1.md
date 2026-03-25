# Add Comprehensive Logging to Diagnose File Loss

Add detailed logging throughout file access code to understand why profile and dashboard files are being lost after 8+ hours of app idle time.

## Current Hypothesis - Uncertain

**What we know:**
- Files exist before app starts
- After 8+ hours idle, files are lost/overwritten when app starts
- `dashboard_D1.json` (seed file) survives
- Profile files and other dashboards disappear

**Possible causes to investigate:**

### Theory 1: Stale Cache + createDirectory() Issue
Lines 43-47, 58-61, 72-75 in `AppDataDirectory.kt`:
```kotlin
var obdDir = rootDir.findFile(OBD_DIR_NAME)
if (obdDir == null) {
    obdDir = rootDir.createDirectory(OBD_DIR_NAME)
}
```

**Question:** Does `createDirectory()` on an existing directory:
- Return the existing directory?
- Create a new empty directory (overwriting)?
- Fail and return null?

If stale cache causes `findFile()` to return null for existing directories, and `createDirectory()` creates new empty directories, **this would wipe all files**.

### Theory 2: Seed Dashboard Logic
`LayoutListFragment.loadLayouts()` calls `seedDefaultDashboards()` every time:
- Stale cache makes `getSavedLayouts()` return empty
- Seeding logic runs and writes `dashboard_D1.json`
- This explains why seed dashboard survives

**But this doesn't explain profile loss** - profiles have no seeding mechanism.

### Theory 3: File vs Directory Confusion
If `createFile()` or `createDirectory()` is called on a name that already exists as the opposite type, it might fail or overwrite.

## The Solution: Add Comprehensive Logging

Add logging at every step to see exactly what's happening:

### AppDataDirectory.kt - Complete Logging

```kotlin
import android.util.Log

object AppDataDirectory {
    private const val TAG = "AppDataDirectory"
    
    fun isUsingExternalStorage(context: Context): Boolean {
        val uriStr = AppSettings.getLogFolderUri(context)
        val result = uriStr != null && getObdDirectoryDocumentFile(context) != null
        Log.d(TAG, "isUsingExternalStorage: $result (uri=$uriStr)")
        return result
    }
    
    fun getObdDirectoryDocumentFile(context: Context): DocumentFile? {
        val uriStr = AppSettings.getLogFolderUri(context)
        if (uriStr == null) {
            Log.d(TAG, "getObdDirectoryDocumentFile: no URI configured")
            return null
        }
        
        Log.d(TAG, "getObdDirectoryDocumentFile: parsing URI: $uriStr")
        val uri = Uri.parse(uriStr)
        
        val rootDir = DocumentFile.fromTreeUri(context, uri)
        if (rootDir == null) {
            Log.e(TAG, "getObdDirectoryDocumentFile: fromTreeUri returned null!")
            return null
        }
        
        Log.d(TAG, "getObdDirectoryDocumentFile: root dir exists=${rootDir.exists()}, canRead=${rootDir.canRead()}, canWrite=${rootDir.canWrite()}")
        
        // Check if .obd directory exists
        Log.d(TAG, "getObdDirectoryDocumentFile: searching for '$OBD_DIR_NAME' directory")
        var obdDir = rootDir.findFile(OBD_DIR_NAME)
        
        if (obdDir == null) {
            Log.w(TAG, "getObdDirectoryDocumentFile: '$OBD_DIR_NAME' not found via findFile(), creating new directory")
            obdDir = rootDir.createDirectory(OBD_DIR_NAME)
            if (obdDir == null) {
                Log.e(TAG, "getObdDirectoryDocumentFile: createDirectory() returned null!")
                return null
            }
            Log.d(TAG, "getObdDirectoryDocumentFile: created new '$OBD_DIR_NAME' directory")
        } else {
            Log.d(TAG, "getObdDirectoryDocumentFile: found existing '$OBD_DIR_NAME' directory (uri=${obdDir.uri})")
        }
        
        return obdDir
    }
    
    private fun getProfilesDirectoryDocumentFile(context: Context): DocumentFile? {
        Log.d(TAG, "getProfilesDirectoryDocumentFile: called")
        val obdDir = getObdDirectoryDocumentFile(context)
        if (obdDir == null) {
            Log.w(TAG, "getProfilesDirectoryDocumentFile: obd directory is null")
            return null
        }
        
        Log.d(TAG, "getProfilesDirectoryDocumentFile: searching for '$PROFILES_DIR_NAME' directory")
        var profilesDir = obdDir.findFile(PROFILES_DIR_NAME)
        
        if (profilesDir == null) {
            Log.w(TAG, "getProfilesDirectoryDocumentFile: '$PROFILES_DIR_NAME' not found, creating")
            profilesDir = obdDir.createDirectory(PROFILES_DIR_NAME)
            if (profilesDir == null) {
                Log.e(TAG, "getProfilesDirectoryDocumentFile: createDirectory() returned null!")
                return null
            }
            Log.d(TAG, "getProfilesDirectoryDocumentFile: created new '$PROFILES_DIR_NAME' directory")
        } else {
            Log.d(TAG, "getProfilesDirectoryDocumentFile: found existing '$PROFILES_DIR_NAME' directory")
        }
        
        return profilesDir
    }
    
    private fun getLayoutsDirectoryDocumentFile(context: Context): DocumentFile? {
        Log.d(TAG, "getLayoutsDirectoryDocumentFile: called")
        val obdDir = getObdDirectoryDocumentFile(context)
        if (obdDir == null) {
            Log.w(TAG, "getLayoutsDirectoryDocumentFile: obd directory is null")
            return null
        }
        
        Log.d(TAG, "getLayoutsDirectoryDocumentFile: searching for '$LAYOUTS_DIR_NAME' directory")
        var layoutsDir = obdDir.findFile(LAYOUTS_DIR_NAME)
        
        if (layoutsDir == null) {
            Log.w(TAG, "getLayoutsDirectoryDocumentFile: '$LAYOUTS_DIR_NAME' not found, creating")
            layoutsDir = obdDir.createDirectory(LAYOUTS_DIR_NAME)
            if (layoutsDir == null) {
                Log.e(TAG, "getLayoutsDirectoryDocumentFile: createDirectory() returned null!")
                return null
            }
            Log.d(TAG, "getLayoutsDirectoryDocumentFile: created new '$LAYOUTS_DIR_NAME' directory")
        } else {
            Log.d(TAG, "getLayoutsDirectoryDocumentFile: found existing '$LAYOUTS_DIR_NAME' directory")
        }
        
        return layoutsDir
    }
    
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
            Log.e(TAG, "getProfileFileDocumentFile: createFile() returned null!")
        } else {
            Log.d(TAG, "getProfileFileDocumentFile: created new file '$fileName'")
        }
        return newFile
    }
    
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
            Log.e(TAG, "getLayoutFileDocumentFile: createFile() returned null!")
        } else {
            Log.d(TAG, "getLayoutFileDocumentFile: created new file '$fileName'")
        }
        return newFile
    }
    
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
}
```

### VehicleProfileRepository.kt - Add Logging

```kotlin
private fun getAllFromFiles(): List<VehicleProfile> {
    Log.d(TAG, "getAllFromFiles: starting profile load from external storage")
    val profileFiles = AppDataDirectory.listProfileFilesDocumentFile(context)
    Log.d(TAG, "getAllFromFiles: found ${profileFiles.size} profile files")
    
    // Rest of method...
}
```

### LayoutRepository.kt - Add Logging

```kotlin
fun getSavedLayouts(): List<DashboardLayout> {
    Log.d(TAG, "getSavedLayouts: useExternalStorage=$useExternalStorage")
    return if (useExternalStorage) {
        getLayoutsFromExternalStorage()
    } else {
        getLayoutsFromAppStorage()
    }
}

private fun getLayoutsFromExternalStorage(): List<DashboardLayout> {
    Log.d(TAG, "getLayoutsFromExternalStorage: starting")
    val files = AppDataDirectory.listLayoutFilesDocumentFile(context)
    Log.d(TAG, "getLayoutsFromExternalStorage: found ${files.size} layout files")
    // Rest of method...
}

fun seedDefaultDashboards() {
    Log.d(TAG, "seedDefaultDashboards: called")
    val existing = getSavedLayouts()
    Log.d(TAG, "seedDefaultDashboards: getSavedLayouts() returned ${existing.size} layouts")
    
    if (existing.isNotEmpty()) {
        Log.d(TAG, "seedDefaultDashboards: existing layouts found, skipping seed")
        return
    }
    
    Log.w(TAG, "seedDefaultDashboards: NO existing layouts found, starting seed process")
    // Rest of method...
}
```

## Expected Log Patterns

### If createDirectory() is destructive:
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: searching for 'profiles' directory
W/AppDataDirectory: getProfilesDirectoryDocumentFile: 'profiles' not found, creating  <-- STALE CACHE
D/AppDataDirectory: getProfilesDirectoryDocumentFile: created new 'profiles' directory  <-- WIPES FILES
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items  <-- FILES GONE
```

### If files are just invisible:
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: found existing 'profiles' directory
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items  <-- STALE CACHE
```

## Implementation Steps

1. Add `import android.util.Log` to `AppDataDirectory.kt`
2. Add `TAG` constant
3. Expand all methods with detailed logging as shown above
4. Add logging to `VehicleProfileRepository.kt`
5. Add logging to `LayoutRepository.kt`
6. Reproduce the issue
7. Analyze logs to determine root cause
8. Implement fix based on evidence

## Files to Modify

1. `AppDataDirectory.kt` - Complete refactor with logging
2. `VehicleProfileRepository.kt` - Add logging to getAllFromFiles()
3. `LayoutRepository.kt` - Add logging to getSavedLayouts() and seedDefaultDashboards()
