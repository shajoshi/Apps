# Investigate and Fix File Loss Root Cause

Need to determine the actual root cause of file loss and simplify the confusing code in `AppDataDirectory.kt`.

## Current Understanding - Revised

**What I was wrong about:**
- `DocumentFile.createFile()` does NOT overwrite existing files
- It creates new files with appended numbers: `file.json`, `file (1).json`, `file (2).json`
- So the overwrite theory is incorrect

**What might actually be happening:**

### Theory 1: Stale Cache Causes Duplicate Files
1. Existing file: `vehicle_profile_MyVehicle.json`
2. `findFile()` uses stale cache, returns `null`
3. `createFile()` creates `vehicle_profile_MyVehicle (1).json`
4. App writes to the `(1)` file
5. Original file still exists but is never read
6. User sees "no profiles" because app is looking for exact name match

### Theory 2: Directory Creation Issue
1. `.obd/profiles/` directory exists with files
2. `findFile("profiles")` uses stale cache, returns `null`
3. `createDirectory("profiles")` might fail or return null
4. `getProfilesDirectoryDocumentFile()` returns `null`
5. All subsequent operations fail
6. Files exist but are inaccessible

### Theory 3: Files Are Actually Being Deleted
- Some code path is deleting files
- Need to search for `delete()` calls

## Code Readability Issues

Current code in `AppDataDirectory.kt` is confusing:

```kotlin
// Lines 85-92 - Too terse, hard to debug
fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    return profilesDir.findFile(fileName) 
        ?: profilesDir.createFile("application/json", fileName)
}
```

**Problems:**
- Elvis operator chains hide failures
- No logging to debug what's happening
- Unclear when files are created vs found
- `createFile()` might return null but we don't know why

## Proposed Refactoring

Make code explicit and debuggable:

```kotlin
fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    // Step 1: Get profiles directory
    val profilesDir = getProfilesDirectoryDocumentFile(context)
    if (profilesDir == null) {
        Log.w(TAG, "getProfileFileDocumentFile: profiles directory not available")
        return null
    }
    
    // Step 2: Sanitize filename
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    // Step 3: Try to find existing file
    val existingFile = profilesDir.findFile(fileName)
    if (existingFile != null) {
        Log.d(TAG, "getProfileFileDocumentFile: found existing file: $fileName")
        return existingFile
    }
    
    // Step 4: File doesn't exist, create it
    Log.d(TAG, "getProfileFileDocumentFile: creating new file: $fileName")
    val newFile = profilesDir.createFile("application/json", fileName)
    if (newFile == null) {
        Log.e(TAG, "getProfileFileDocumentFile: failed to create file: $fileName")
    }
    return newFile
}
```

**Benefits:**
- Each step is explicit
- Logging shows exactly what's happening
- Easy to add breakpoints
- Clear error handling

## Investigation Steps

1. **Add comprehensive logging** to understand the actual flow
2. **Check for delete() calls** that might be removing files
3. **Verify DocumentFile behavior** with test scenarios
4. **Simplify and clarify** all file access code

## Files to Refactor

1. `AppDataDirectory.kt`:
   - `getObdDirectoryDocumentFile()` - expand with logging
   - `getProfilesDirectoryDocumentFile()` - expand with logging
   - `getLayoutsDirectoryDocumentFile()` - expand with logging
   - `getProfileFileDocumentFile()` - expand with logging
   - `getLayoutFileDocumentFile()` - expand with logging
   - `listProfileFilesDocumentFile()` - add logging
   - `listLayoutFilesDocumentFile()` - add logging

2. Add `Log` import and `TAG` constant

## Questions to Answer

1. Are files actually being deleted, or just not found?
2. Are duplicate files being created (with `(1)` suffix)?
3. Does `findFile()` work correctly after app restart?
4. Does `createDirectory()` fail when directory already exists?

## Next Steps

1. Add logging to all methods in `AppDataDirectory.kt`
2. Reproduce the issue with logging enabled
3. Analyze logs to see actual behavior
4. Fix based on real evidence, not speculation
