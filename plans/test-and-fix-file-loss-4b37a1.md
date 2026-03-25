# Test Reproduction and Fix File Loss Bug

Add comprehensive logging and test the file loss bug using Force Stop to simulate the 8-hour idle scenario.

## Test Procedure to Reproduce Bug

### Step 1: Setup Test Data
1. Create some vehicle profiles in the app
2. Create some custom dashboards (not just the seed dashboard)
3. Verify files exist in `<log_folder>/.obd/profiles/` and `<log_folder>/.obd/layouts/`
4. Note down the exact file names

### Step 2: Force Stop to Simulate 8-Hour Sleep
1. Go to Android Settings → Apps → OBD2App
2. Tap "Force Stop"
3. Confirm force stop

**Why this works:**
- Force Stop kills the app process completely
- Clears all in-memory cache including DocumentFile instances
- Next app launch starts fresh, same as after 8+ hours idle
- DocumentFile cache will be stale if it was cached before

### Step 3: Restart and Observe
1. Launch the app again
2. Navigate to Settings → check if profiles are visible
3. Navigate to Dashboards → check if custom dashboards are visible
4. Check logcat for the detailed logs we'll add

### Step 4: Verify Files Still Exist
1. Use a file manager app to browse to the log folder
2. Check if `.obd/profiles/` and `.obd/layouts/` directories exist
3. Check if the JSON files are still there physically
4. Compare with the list from Step 1

**Expected results if bug exists:**
- Profiles/dashboards appear missing in app
- Files still exist physically in file system
- Logs show `findFile()` returning null despite files existing
- Logs show `createDirectory()` being called on existing directories

## Implementation Plan

### Phase 1: Add Comprehensive Logging (30 min)

**Files to modify:**
1. `AppDataDirectory.kt` - Add detailed logging to all methods
2. `VehicleProfileRepository.kt` - Log profile loading
3. `LayoutRepository.kt` - Log dashboard loading and seeding

**Key logging points:**
- When `findFile()` is called and what it returns
- When `createDirectory()` is called
- When `listFiles()` is called and how many items returned
- File/directory existence checks

### Phase 2: Test and Analyze (15 min)

1. Build and install app with logging
2. Create test data (profiles + dashboards)
3. Force Stop the app
4. Restart and check behavior
5. Collect logcat output
6. Verify if files are physically deleted or just invisible

### Phase 3: Implement Fix Based on Evidence (30 min)

**If createDirectory() is destructive:**
- Never call `createDirectory()` on potentially existing directories
- Always use fresh `listFiles()` query instead of cached `findFile()`
- Or check physical existence before creating

**If files are just invisible (stale cache):**
- Force DocumentFile refresh on app startup
- Recreate DocumentFile chain from root URI each time
- Don't cache DocumentFile instances

**If seed logic is the issue:**
- Fix `seedDefaultDashboards()` to only run once ever
- Or force fresh query before checking if dashboards exist

## Detailed Logging Code

### AppDataDirectory.kt

```kotlin
import android.util.Log

object AppDataDirectory {
    private const val TAG = "AppDataDirectory"
    
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
        
        var obdDir = rootDir.findFile(OBD_DIR_NAME)
        if (obdDir == null) {
            Log.w(TAG, "getObdDirectoryDocumentFile: '$OBD_DIR_NAME' not found, creating")
            obdDir = rootDir.createDirectory(OBD_DIR_NAME)
            Log.d(TAG, "getObdDirectoryDocumentFile: createDirectory result: ${obdDir != null}")
        } else {
            Log.d(TAG, "getObdDirectoryDocumentFile: found existing '$OBD_DIR_NAME'")
        }
        
        return obdDir
    }
    
    private fun getProfilesDirectoryDocumentFile(context: Context): DocumentFile? {
        Log.d(TAG, "getProfilesDirectoryDocumentFile: called")
        val obdDir = getObdDirectoryDocumentFile(context) ?: return null
        
        var profilesDir = obdDir.findFile(PROFILES_DIR_NAME)
        if (profilesDir == null) {
            Log.w(TAG, "getProfilesDirectoryDocumentFile: '$PROFILES_DIR_NAME' not found, creating")
            profilesDir = obdDir.createDirectory(PROFILES_DIR_NAME)
            Log.d(TAG, "getProfilesDirectoryDocumentFile: createDirectory result: ${profilesDir != null}")
        } else {
            Log.d(TAG, "getProfilesDirectoryDocumentFile: found existing '$PROFILES_DIR_NAME'")
        }
        
        return profilesDir
    }
    
    fun listProfileFilesDocumentFile(context: Context): List<DocumentFile> {
        Log.d(TAG, "listProfileFilesDocumentFile: called")
        val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return emptyList()
        
        val allFiles = profilesDir.listFiles()
        Log.d(TAG, "listProfileFilesDocumentFile: listFiles() returned ${allFiles.size} items")
        
        allFiles.forEachIndexed { i, f ->
            Log.d(TAG, "  [$i] ${f.name} (isFile=${f.isFile})")
        }
        
        return allFiles.filter { 
            it.isFile && 
            it.name?.startsWith(PROFILE_FILE_PREFIX) == true && 
            it.name?.endsWith(".json") == true
        }
    }
    
    // Similar logging for layouts...
}
```

### VehicleProfileRepository.kt

```kotlin
private fun getAllFromFiles(): List<VehicleProfile> {
    Log.d(TAG, "getAllFromFiles: loading profiles from external storage")
    val profileFiles = AppDataDirectory.listProfileFilesDocumentFile(context)
    Log.d(TAG, "getAllFromFiles: found ${profileFiles.size} profile files")
    // ... rest
}
```

### LayoutRepository.kt

```kotlin
fun seedDefaultDashboards() {
    Log.d(TAG, "seedDefaultDashboards: called")
    val existing = getSavedLayouts()
    Log.d(TAG, "seedDefaultDashboards: existing layouts count = ${existing.size}")
    
    if (existing.isNotEmpty()) {
        Log.d(TAG, "seedDefaultDashboards: skipping seed (layouts exist)")
        return
    }
    
    Log.w(TAG, "seedDefaultDashboards: NO layouts found, seeding from assets")
    // ... rest
}
```

## Expected Log Patterns

### If createDirectory() wipes files:
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: called
W/AppDataDirectory: getProfilesDirectoryDocumentFile: 'profiles' not found, creating
D/AppDataDirectory: getProfilesDirectoryDocumentFile: createDirectory result: true
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items
```

### If stale cache (files invisible but not deleted):
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: found existing 'profiles'
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items
```

### If working correctly:
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: found existing 'profiles'
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 3 items
D/AppDataDirectory:   [0] vehicle_profile_MyVehicle.json (isFile=true)
D/AppDataDirectory:   [1] vehicle_profile_TestCar.json (isFile=true)
D/AppDataDirectory:   [2] vehicle_profile_Bike.json (isFile=true)
```

## Quick Test Checklist

- [ ] Add logging to AppDataDirectory.kt
- [ ] Add logging to VehicleProfileRepository.kt
- [ ] Add logging to LayoutRepository.kt
- [ ] Build and install app
- [ ] Create 2-3 test profiles
- [ ] Create 1-2 custom dashboards
- [ ] Note file names in file manager
- [ ] Force Stop app
- [ ] Restart app
- [ ] Check if profiles/dashboards visible
- [ ] Check logcat output
- [ ] Check if files still exist physically
- [ ] Analyze logs to determine root cause
- [ ] Implement appropriate fix

## Timeline

- **Logging implementation:** 30 minutes
- **Testing and log analysis:** 15 minutes  
- **Fix implementation:** 30 minutes
- **Total:** ~75 minutes
