# DocumentFile Cache Bug - Diagnostic Log Search Guide

Search for these specific log patterns to validate the DocumentFile cache staleness hypothesis.

## Primary Evidence Logs

### 1. Empty List Despite Directory Existing
Search for scenarios where `listFiles()` returns empty but the directory should have files:

**Logcat filters:**
```
tag:VehicleProfileRepository "No profile files found"
tag:LayoutRepository "No layout files found"
tag:VehicleProfileRepository level:debug
tag:LayoutRepository level:debug
```

**What to look for:**
- "No profile files found in external storage" followed by successful load from app-private storage
- Empty profile/layout lists when you know files exist
- Toast messages about "0 profile(s)" or "0 dashboard(s)" when files should be present

### 2. DocumentFile Creation Failures
Search for `null` returns from DocumentFile operations:

**Logcat filters:**
```
tag:AppDataDirectory
tag:AppDataDirectory "null"
tag:AppDataDirectory "returned null"
```

**What to look for:**
- "DocumentFile.fromTreeUri returned null" (this would indicate permission issues, NOT cache)
- Null checks failing in `getProfilesDirectoryDocumentFile()` or `getLayoutsDirectoryDocumentFile()`

### 3. File Load Errors
Search for exceptions during file reading:

**Logcat filters:**
```
tag:VehicleProfileRepository level:error
tag:LayoutRepository level:error
"Failed to load profile"
"Failed to load layout"
```

**What to look for:**
- FileNotFoundException (file exists but DocumentFile can't see it)
- SecurityException (permission issues)
- IOException during ContentResolver.openInputStream()

### 4. SAF URI and Permission Status
Check if SAF permissions are actually valid:

**Logcat filters:**
```
"persistedUriPermissions"
"takePersistableUriPermission"
"SAF"
tag:DataMigration
```

**What to look for:**
- Any messages about expired permissions (would disprove cache hypothesis)
- URI validation failures
- Permission grant/revoke events

## Diagnostic Code to Add Temporarily

Add these log statements to validate the hypothesis:

### In `AppDataDirectory.listProfileFilesDocumentFile()`:
```kotlin
fun listProfileFilesDocumentFile(context: Context): List<DocumentFile> {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return emptyList()
    
    Log.d("AppDataDirectory", "Listing profiles from: ${profilesDir.uri}")
    Log.d("AppDataDirectory", "profilesDir.exists() = ${profilesDir.exists()}")
    Log.d("AppDataDirectory", "profilesDir.isDirectory = ${profilesDir.isDirectory}")
    Log.d("AppDataDirectory", "profilesDir.canRead() = ${profilesDir.canRead()}")
    
    val allFiles = profilesDir.listFiles()
    Log.d("AppDataDirectory", "listFiles() returned ${allFiles.size} items")
    allFiles.forEach { 
        Log.d("AppDataDirectory", "  - ${it.name} (isFile=${it.isFile})")
    }
    
    return allFiles.filter { 
        it.isFile && 
        it.name?.startsWith(PROFILE_FILE_PREFIX) == true && 
        it.name?.endsWith(".json") == true
    }
}
```

### In `VehicleProfileRepository.getAllFromFiles()`:
```kotlin
private fun getAllFromFiles(): List<VehicleProfile> {
    Log.d(TAG, "getAllFromFiles() called")
    val profileFiles = AppDataDirectory.listProfileFilesDocumentFile(context)
    Log.d(TAG, "Found ${profileFiles.size} profile files")
    
    if (profileFiles.isEmpty()) {
        // Check if using external storage
        val uriStr = AppSettings.getLogFolderUri(context)
        Log.w(TAG, "No profiles found but URI is configured: $uriStr")
        
        // Try to verify directory exists via ContentResolver
        if (uriStr != null) {
            val uri = Uri.parse(uriStr)
            val rootDir = DocumentFile.fromTreeUri(context, uri)
            Log.d(TAG, "Root DocumentFile: exists=${rootDir?.exists()}, canRead=${rootDir?.canRead()}")
        }
    }
    // ... rest of method
}
```

## Expected Log Patterns

### If Cache Bug (Hypothesis Correct):
```
D/AppDataDirectory: Listing profiles from: content://.../.obd/profiles
D/AppDataDirectory: profilesDir.exists() = true
D/AppDataDirectory: profilesDir.isDirectory = true
D/AppDataDirectory: profilesDir.canRead() = true
D/AppDataDirectory: listFiles() returned 0 items    <-- STALE CACHE
W/VehicleProfileRepository: No profiles found but URI is configured: content://...
```

### If Permission Issue (Hypothesis Wrong):
```
D/AppDataDirectory: Listing profiles from: content://.../.obd/profiles
D/AppDataDirectory: profilesDir.exists() = false    <-- PERMISSION EXPIRED
D/AppDataDirectory: profilesDir.canRead() = false
```

### If Files Actually Don't Exist:
```
D/AppDataDirectory: Listing profiles from: content://.../.obd/profiles
D/AppDataDirectory: profilesDir.exists() = true
D/AppDataDirectory: listFiles() returned 0 items
(No warning about URI being configured)
```

## Manual Verification Steps

1. **Check physical files exist:**
   - Use a file manager app to browse to the selected folder
   - Verify `.obd/profiles/` directory exists
   - Verify `vehicle_profile_*.json` files are present
   - Note the file count

2. **Check app state:**
   - Launch app after 8+ hour idle
   - Note how many profiles appear in UI
   - Compare with physical file count

3. **Force refresh test:**
   - If files are missing, go to Settings
   - Re-select the SAME folder (don't change it)
   - Check if profiles/dashboards reappear
   - If they do, confirms cache bug

## Quick Test Command

Run this in Android Studio's Logcat filter:
```
package:com.sj.obd2app tag:AppDataDirectory|tag:VehicleProfileRepository|tag:LayoutRepository
```

Then reproduce the issue and look for the patterns above.
