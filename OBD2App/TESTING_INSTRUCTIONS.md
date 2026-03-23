# Testing Instructions for File Loss Bug

## What Was Changed

Added comprehensive logging to diagnose the file loss issue:

### Files Modified:
1. **`AppDataDirectory.kt`** - Added detailed logging to all directory/file access methods
2. **`VehicleProfileRepository.kt`** - Added logging to profile loading
3. **`LayoutRepository.kt`** - Added logging to dashboard loading and seeding

## How to Test

### Step 1: Build and Install
1. Build the app with the new logging
2. Install on your device

### Step 2: Create Test Data
1. Open the app
2. Go to Settings → Create 2-3 vehicle profiles
3. Go to Dashboards → Create 1-2 custom dashboards (not just the seed dashboard)
4. **Important:** Use a file manager app to verify files exist in:
   - `<your_log_folder>/.obd/profiles/vehicle_profile_*.json`
   - `<your_log_folder>/.obd/layouts/dashboard_*.json`
5. Note down the exact file names

### Step 3: Simulate 8-Hour Sleep with Force Stop
1. Go to Android Settings → Apps → OBD2App
2. Tap **"Force Stop"**
3. Confirm force stop
4. Wait a few seconds

**Why this works:**
- Force Stop kills the app process completely
- Clears all in-memory DocumentFile cache
- Next launch simulates fresh start after 8+ hours idle

### Step 4: Restart and Observe
1. Launch the app again
2. Go to Settings → Check if your profiles are visible
3. Go to Dashboards → Check if your custom dashboards are visible

### Step 5: Check Logcat
Open Android Studio Logcat and filter by:
```
package:com.sj.obd2app tag:AppDataDirectory|tag:VehicleProfileRepository|tag:LayoutRepository
```

### Step 6: Verify Files Still Exist
Use file manager to check if the JSON files are still physically present in the `.obd` directory.

## What to Look For in Logs

### If Bug is "createDirectory() Wipes Files"
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: called
W/AppDataDirectory: getProfilesDirectoryDocumentFile: 'profiles' not found, creating new directory
D/AppDataDirectory: getProfilesDirectoryDocumentFile: created new 'profiles' directory
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items
```
**This means:** Stale cache caused `findFile()` to miss existing directory, `createDirectory()` created new empty one, files were wiped.

### If Bug is "Stale Cache (Files Invisible)"
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: found existing 'profiles' directory
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 0 items
```
**This means:** Directory found but `listFiles()` returns empty due to stale cache. Files still exist but are invisible.

### If Working Correctly
```
D/AppDataDirectory: getProfilesDirectoryDocumentFile: found existing 'profiles' directory
D/AppDataDirectory: listProfileFilesDocumentFile: listFiles() returned 3 items
D/AppDataDirectory:   [0] name='vehicle_profile_MyVehicle.json', isFile=true
D/AppDataDirectory:   [1] name='vehicle_profile_TestCar.json', isFile=true
D/AppDataDirectory:   [2] name='vehicle_profile_Bike.json', isFile=true
D/VehicleProfileRepository: getAllFromFiles: returning 3 profiles
```

### Seed Dashboard Bug Pattern
```
D/LayoutRepository: getSavedLayouts: useExternalStorage=true
D/AppDataDirectory: listLayoutFilesDocumentFile: listFiles() returned 0 items
D/LayoutRepository: getLayoutsFromExternalStorage: found 0 layout files
D/LayoutRepository: seedDefaultDashboards: getSavedLayouts() returned 0 layouts
W/LayoutRepository: seedDefaultDashboards: NO existing layouts found, starting seed process
I/LayoutRepository: Seeded dashboard: D1
```
**This means:** Stale cache made `getSavedLayouts()` return empty, triggering seed logic.

## Expected Results

**If bug exists:**
- Profiles/dashboards appear missing in app UI
- Files still exist physically in file system (verify with file manager)
- Logs show one of the patterns above

**If bug is fixed:**
- All profiles/dashboards load correctly
- Logs show files being found and loaded

## Next Steps After Testing

1. **Collect the logcat output** when reproducing the issue
2. **Share the logs** to confirm which pattern matches
3. **Verify physical file existence** with file manager
4. Based on the evidence, we'll implement the appropriate fix:
   - If `createDirectory()` is destructive → prevent calling it on existing directories
   - If stale cache → force DocumentFile refresh on app startup
   - If seed logic → fix to only run once or force fresh query

## Quick Logcat Filter

In Android Studio Logcat, use this filter:
```
package:com.sj.obd2app level:debug tag:AppDataDirectory|tag:VehicleProfileRepository|tag:LayoutRepository
```

Or to see warnings/errors only:
```
package:com.sj.obd2app level:warn tag:AppDataDirectory|tag:VehicleProfileRepository|tag:LayoutRepository
```
