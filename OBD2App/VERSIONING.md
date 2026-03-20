# App Versioning System

## Overview

The OBD2 app uses an automatic build version incrementing system that tracks every build with a unique version code and traceable version name.

## How It Works

### Version Components

The app version consists of:
- **VERSION_MAJOR**: Major release number (e.g., 1.x.x)
- **VERSION_MINOR**: Minor release number (e.g., x.0.x)
- **VERSION_PATCH**: Patch/bugfix number (e.g., x.x.0)
- **VERSION_CODE**: Auto-incremented build number (increments on every build)

### Automatic Build Incrementing

Every time you build the app in Android Studio (debug or release), the build number automatically increments:

1. The build system reads `app/version.properties`
2. Increments `VERSION_CODE` by 1
3. Saves the new value back to the file
4. Generates a traceable version name with timestamp

### Version Name Format

The generated version name follows this format:
```
{MAJOR}.{MINOR}.{PATCH}-build{CODE}-{TIMESTAMP}
```

**Example:** `1.0.0-build42-20260320-1635`

This tells you:
- Version: 1.0.0
- Build number: 42
- Built on: March 20, 2026 at 4:35 PM

## Managing Versions

### For Regular Builds

No action needed! The build number increments automatically.

### For New Releases

When releasing a new version, manually update `app/version.properties`:

```properties
VERSION_MAJOR=1    # Increment for breaking changes
VERSION_MINOR=1    # Increment for new features
VERSION_PATCH=0    # Increment for bug fixes
VERSION_CODE=42    # Leave as-is (auto-increments)
```

**Example workflow:**
- Bug fix release: `1.0.0` → `1.0.1` (increment PATCH)
- New feature: `1.0.1` → `1.1.0` (increment MINOR, reset PATCH)
- Breaking change: `1.1.0` → `2.0.0` (increment MAJOR, reset others)

## Traceability

### Finding Build Information

The version name appears in:
- App settings/about screen (if implemented)
- APK/AAB filename: `OBD2Viewer-{versionName}.apk`
- Android manifest
- Play Store listing

### Build Tracking

Each build is uniquely identifiable by:
1. **Build number** - Sequential, never repeats
2. **Timestamp** - Exact date/time of build
3. **Version number** - Semantic version

This allows you to:
- Track which build a user is running
- Identify when a specific build was created
- Correlate builds with git commits (use timestamp)

## Developer Notes

### Multiple Developers

The `version.properties` file is **tracked in git** to maintain synchronized build numbers across all developers. This ensures every build has a unique, sequential version code.

**Critical workflow:**
1. **Always `git pull` before building** - get the latest build number
2. **Build your app** - VERSION_CODE auto-increments
3. **Commit and push version.properties** - share your build number with team
4. **Handle conflicts correctly** - always take the HIGHER build number

See `VERSION_RULES.md` for detailed rules and conflict resolution procedures.

For release builds, the release manager should:
1. Announce the release to the team
2. Pull latest changes
3. Update VERSION_MAJOR/MINOR/PATCH in version.properties
4. Build the release
5. Tag the git commit with the version

### CI/CD Integration

For automated builds, you can:
- Override VERSION_CODE via environment variable
- Use git commit count as VERSION_CODE
- Sync VERSION_CODE with CI build number

## File Locations

- **Version config**: `app/version.properties`
- **Build script**: `app/build.gradle.kts`
- **Gitignore entry**: `.gitignore` (line 75)

## Troubleshooting

**Build number not incrementing?**
- Check that `app/version.properties` exists and is writable
- Verify the file isn't locked by another process
- Try cleaning and rebuilding: Build → Clean Project → Rebuild Project

**Version conflicts after git pull?**
- **Expected behavior** when multiple developers build simultaneously
- **Resolution:** Always take the HIGHER build number
- Rebuild your app to use the correct incremented version
- See `VERSION_RULES.md` for detailed conflict resolution steps

**Build numbers jumping by large amounts?**
- Normal when multiple developers are building
- Each developer's build increments the shared counter
- Gaps in build numbers are acceptable

**Want to reset build number?**
- **Coordinate with entire team first** - affects everyone
- Edit `app/version.properties` and set `VERSION_CODE=1`
- Commit and push immediately
- Notify all developers to pull the change
