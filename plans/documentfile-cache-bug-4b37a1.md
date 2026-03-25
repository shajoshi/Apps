# Fix DocumentFile Cache Staleness Bug

The profiles and dashboards disappear after 8+ hours because `DocumentFile.listFiles()` returns stale cached data, not because SAF permissions expire.

## Root Cause Analysis

**The Real Problem:**
- `DocumentFile` internally caches directory listings
- When `listFiles()` is called, it queries the ContentProvider once and caches results
- The cache is never invalidated automatically
- After 8+ hours (app restart, files synced from git/cloud), the cache is stale
- `listFiles()` returns the OLD cached list (possibly empty if directory was created after cache)
- Files exist on disk but aren't visible to the app

**Why 8+ hours specifically:**
- App process is killed by Android after long idle
- On next launch, new `DocumentFile` instances are created
- If the `.obd/profiles/` or `.obd/layouts/` directories were created AFTER the initial SAF URI was granted
- The root DocumentFile's cached view doesn't include these subdirectories
- `findFile("profiles")` returns null even though directory exists

## Evidence from Code

Lines 127 and 141 in `AppDataDirectory.kt`:
```kotlin
return profilesDir.listFiles().filter { ... }
return layoutsDir.listFiles().filter { ... }
```

These call `listFiles()` on a `DocumentFile` that may have stale cache.

## The Fix

**Option 1: Force Cache Refresh (Recommended)**
Re-create the DocumentFile chain from the root URI on each access to force fresh queries:
- Don't reuse cached `DocumentFile` instances
- Always start from `DocumentFile.fromTreeUri()` 
- Let garbage collection handle old instances

**Option 2: Use ContentResolver Directly**
Query the ContentProvider directly instead of using DocumentFile:
- More control over caching
- More verbose code
- Better performance

**Option 3: Implement Cache Invalidation**
Track when files are written and invalidate DocumentFile references:
- Complex to implement correctly
- Easy to miss invalidation points
- Not worth the complexity

## Implementation Plan

1. **Modify `AppDataDirectory.kt`**:
   - Remove any potential caching of `DocumentFile` instances
   - Ensure each call to `getProfilesDirectoryDocumentFile()` and `getLayoutsDirectoryDocumentFile()` creates fresh DocumentFile chain
   - Add logging to verify fresh instances are created

2. **Add Diagnostic Logging**:
   - Log when `listFiles()` returns empty but directory exists
   - Log DocumentFile URI and existence checks
   - Help identify if this is indeed the cache issue

3. **Test Scenarios**:
   - Create profiles → kill app → wait → restart → verify profiles load
   - Sync files via git → restart app → verify files appear
   - Create directory externally → restart app → verify directory found

## Questions to Investigate

1. Are we caching `DocumentFile` instances anywhere?
2. Does `findFile()` also use cached data?
3. Should we query ContentProvider directly for better control?

## Files to Modify

- `AppDataDirectory.kt` - Ensure no DocumentFile caching
- Add detailed logging around `listFiles()` calls
- Possibly add a "refresh" mechanism if needed
