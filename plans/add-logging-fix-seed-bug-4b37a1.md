# Add Comprehensive Logging and Fix Seed Dashboard Bug

Add detailed logging to diagnose file loading issues and fix the bug where `seedDefaultDashboards()` overwrites existing files due to stale cache.

## Root Cause Identified

**The Actual Bug:**
Line 67 in `LayoutListFragment.kt` calls `seedDefaultDashboards()` on every `onResume()`:

```kotlin
private fun loadLayouts() {
    repo.seedDefaultDashboards()  // Called EVERY TIME
    val layouts = repo.getSavedLayouts()
    ...
}
```

**What happens after 8+ hours:**
1. App restarts, DocumentFile cache is stale
2. `getSavedLayouts()` returns empty list (cache doesn't see existing files)
3. `seedDefaultDashboards()` thinks no dashboards exist
4. Seeds `dashboard_D1.json` from assets
5. **Existing user dashboards are not seen and appear lost**

**Why dashboard_D1.json survives:**
- It's being re-seeded from assets every time
- User's custom dashboards are invisible due to stale cache

## Solution: Two-Part Fix

### Part 1: Add Comprehensive Logging

Add logging throughout `AppDataDirectory.kt` to trace the entire file access flow:

**Files to modify:**
1. `AppDataDirectory.kt` - Add logging to all methods
2. `VehicleProfileRepository.kt` - Add logging to load/save operations
3. `LayoutRepository.kt` - Add logging to load/save/seed operations
4. `DataMigration.kt` - Add logging to startup checks

**Logging points:**
- When directories are accessed/created
- When files are found/not found via `findFile()`
- When files are created via `createFile()`
- When `listFiles()` is called and how many items returned
- When reading file contents
- When writing file contents

### Part 2: Fix Seed Logic

**Option A: Only seed on first install (Recommended)**
Track whether seeding has been done and never repeat:

```kotlin
fun seedDefaultDashboards() {
    // Check if we've already seeded
    val prefs = context.getSharedPreferences("obd2_prefs", Context.MODE_PRIVATE)
    if (prefs.getBoolean("dashboards_seeded", false)) {
        Log.d(TAG, "Dashboards already seeded, skipping")
        return
    }
    
    val existing = getSavedLayouts()
    Log.d(TAG, "seedDefaultDashboards: found ${existing.size} existing layouts")
    
    if (existing.isNotEmpty()) {
        Log.d(TAG, "Existing layouts found, skipping seed")
        return
    }
    
    // Seed logic...
    
    // Mark as seeded
    prefs.edit().putBoolean("dashboards_seeded", true).apply()
}
```

**Option B: Check physical file existence**
Query ContentProvider directly instead of relying on cached `getSavedLayouts()`:

```kotlin
fun seedDefaultDashboards() {
    // Force fresh query by recreating DocumentFile chain
    val layoutsDir = if (useExternalStorage) {
        AppDataDirectory.getLayoutsDirectoryDocumentFile(context)
    } else {
        null
    }
    
    val hasExisting = if (layoutsDir != null) {
        // Force fresh listFiles() call
        val files = layoutsDir.listFiles()
        Log.d(TAG, "seedDefaultDashboards: fresh query found ${files.size} files")
        files.any { it.name?.startsWith("dashboard_") == true }
    } else {
        AppDataDirectory.listLayoutFilesPrivate(context).isNotEmpty()
    }
    
    if (hasExisting) {
        Log.d(TAG, "Existing dashboards found, skipping seed")
        return
    }
    
    // Seed logic...
}
```

## Detailed Logging Plan

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
        
        Log.d(TAG, "getObdDirectoryDocumentFile: URI = $uriStr")
        val uri = Uri.parse(uriStr)
        
        val rootDir = DocumentFile.fromTreeUri(context, uri)
        if (rootDir == null) {
            Log.w(TAG, "getObdDirectoryDocumentFile: fromTreeUri returned null")
            return null
        }
        
        Log.d(TAG, "getObdDirectoryDocumentFile: root exists=${rootDir.exists()}, canRead=${rootDir.canRead()}")
        
        var obdDir = rootDir.findFile(OBD_DIR_NAME)
        if (obdDir == null) {
            Log.d(TAG, "getObdDirectoryDocumentFile: .obd not found, creating")
            obdDir = rootDir.createDirectory(OBD_DIR_NAME)
            if (obdDir == null) {
                Log.e(TAG, "getObdDirectoryDocumentFile: failed to create .obd directory")
            } else {
                Log.d(TAG, "getObdDirectoryDocumentFile: created .obd directory")
            }
        } else {
            Log.d(TAG, "getObdDirectoryDocumentFile: found existing .obd directory")
        }
        
        return obdDir
    }
    
    // Similar detailed logging for all other methods...
}
```

### VehicleProfileRepository.kt

```kotlin
private fun getAllFromFiles(): List<VehicleProfile> {
    Log.d(TAG, "getAllFromFiles: starting")
    val profileFiles = AppDataDirectory.listProfileFilesDocumentFile(context)
    Log.d(TAG, "getAllFromFiles: found ${profileFiles.size} profile files")
    
    profileFiles.forEachIndexed { index, file ->
        Log.d(TAG, "  [$index] ${file.name} (uri=${file.uri})")
    }
    
    // Rest of method...
}
```

### LayoutRepository.kt

```kotlin
fun seedDefaultDashboards() {
    Log.d(TAG, "seedDefaultDashboards: called")
    
    val existing = getSavedLayouts()
    Log.d(TAG, "seedDefaultDashboards: getSavedLayouts returned ${existing.size} layouts")
    
    if (existing.isNotEmpty()) {
        Log.d(TAG, "seedDefaultDashboards: existing layouts found, skipping seed")
        return
    }
    
    Log.d(TAG, "seedDefaultDashboards: no existing layouts, starting seed")
    // Rest of method...
}
```

## Expected Log Output

**When bug occurs:**
```
D/AppDataDirectory: getLayoutsDirectoryDocumentFile: found existing layouts directory
D/AppDataDirectory: listLayoutFilesDocumentFile: calling listFiles()
D/AppDataDirectory: listLayoutFilesDocumentFile: listFiles() returned 0 items  <-- STALE CACHE
D/LayoutRepository: getSavedLayouts: found 0 layouts
D/LayoutRepository: seedDefaultDashboards: no existing layouts, starting seed
D/LayoutRepository: Seeded dashboard: D1
```

**When working correctly:**
```
D/AppDataDirectory: listLayoutFilesDocumentFile: listFiles() returned 3 items
D/AppDataDirectory:   [0] dashboard_D1.json
D/AppDataDirectory:   [1] dashboard_MyCustom.json
D/AppDataDirectory:   [2] dashboard_Night.json
D/LayoutRepository: getSavedLayouts: found 3 layouts
D/LayoutRepository: seedDefaultDashboards: existing layouts found, skipping seed
```

## Implementation Checklist

1. Add `Log` import to `AppDataDirectory.kt`
2. Add `TAG` constant to `AppDataDirectory.kt`
3. Expand all methods with detailed logging
4. Add logging to `VehicleProfileRepository.getAllFromFiles()`
5. Add logging to `LayoutRepository.getSavedLayouts()`
6. Add logging to `LayoutRepository.seedDefaultDashboards()`
7. Fix seed logic (Option A or B)
8. Test with logging enabled
9. Analyze logs to confirm root cause
10. Verify fix works

## Files to Modify

1. `AppDataDirectory.kt` - Add logging throughout
2. `VehicleProfileRepository.kt` - Add logging to load operations
3. `LayoutRepository.kt` - Add logging and fix seed logic
4. `DataMigration.kt` - Add logging to startup validation
