# Fix File Overwrite Bug in AppDataDirectory

Files are being overwritten on app startup because `findFile()` uses stale cache and `createFile()` blindly creates new files.

## Root Cause

**Lines 90-91 and 105-106 in `AppDataDirectory.kt`:**
```kotlin
return profilesDir.findFile(fileName) 
    ?: profilesDir.createFile("application/json", fileName)
```

**The Bug Flow:**
1. User has existing `vehicle_profile_MyVehicle.json` in `.obd/profiles/`
2. App starts, creates DocumentFile for root URI
3. `findFile("profiles")` uses stale cache, doesn't see the directory
4. `createDirectory("profiles")` creates new empty directory (or returns existing)
5. Later, `getProfileFileDocumentFile("MyVehicle")` is called
6. `findFile("vehicle_profile_MyVehicle.json")` searches in stale cached directory listing
7. Returns `null` because cache doesn't include the existing file
8. `createFile("application/json", "vehicle_profile_MyVehicle.json")` creates NEW empty file
9. **Existing file data is overwritten/lost**

## Why This Happens After 8+ Hours

- App process killed after long idle
- On restart, DocumentFile instances created fresh
- But the cache is populated from initial directory scan
- If files were added externally (git sync, manual copy), they're not in the cache
- `findFile()` can't see them
- `createFile()` overwrites them

## The Fix

**Option 1: Never Auto-Create Files (Recommended)**
Remove the `?: createFile()` fallback. Only create files explicitly when saving:

```kotlin
fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    return profilesDir.findFile(fileName)  // Just find, don't create
}

// Add separate method for creating new files
fun createProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    // Delete if exists (to handle stale cache), then create fresh
    profilesDir.findFile(fileName)?.delete()
    return profilesDir.createFile("application/json", fileName)
}
```

**Option 2: Query ContentResolver Directly**
Bypass DocumentFile cache entirely by querying ContentProvider:

```kotlin
fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    // Force refresh by re-querying children
    val children = profilesDir.listFiles()  // This queries ContentProvider
    return children.firstOrNull { it.name == fileName }
}
```

**Option 3: Check File Existence Before Creating**
Verify file doesn't exist before calling createFile:

```kotlin
fun getProfileFileDocumentFile(context: Context, profileName: String): DocumentFile? {
    val profilesDir = getProfilesDirectoryDocumentFile(context) ?: return null
    val safeName = profileName.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val fileName = "${PROFILE_FILE_PREFIX}${safeName}.json"
    
    // Force fresh query
    val existing = profilesDir.listFiles().firstOrNull { it.name == fileName }
    if (existing != null) return existing
    
    // Only create if truly doesn't exist
    return profilesDir.createFile("application/json", fileName)
}
```

## Recommended Solution

**Use Option 1** - Separate read and write paths:

1. **For reading** (loading profiles/layouts):
   - Use `findFile()` only
   - Don't auto-create
   - If null, file genuinely doesn't exist

2. **For writing** (saving profiles/layouts):
   - Use dedicated create method
   - Delete existing file first (handles stale cache)
   - Create fresh file
   - Write data

This prevents accidental overwrites while ensuring writes always succeed.

## Files to Modify

1. **`AppDataDirectory.kt`**:
   - Change `getProfileFileDocumentFile()` to read-only
   - Change `getLayoutFileDocumentFile()` to read-only
   - Add `createProfileFileDocumentFile()`
   - Add `createLayoutFileDocumentFile()`
   - Change `getSettingsFileDocumentFile()` similarly

2. **`VehicleProfileRepository.kt`**:
   - Update `saveToFile()` to use create method

3. **`LayoutRepository.kt`**:
   - Update `saveToExternalStorage()` to use create method

## Testing

1. Create profiles/layouts manually in folder
2. Start app → verify files load correctly
3. Save profile → verify doesn't create duplicate
4. Kill app, wait, restart → verify files still load
5. Sync files via git → restart app → verify new files appear
