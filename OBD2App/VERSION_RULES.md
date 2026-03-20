# Version Control Rules for Shared Build Numbers

## Overview

The `app/version.properties` file is **tracked in git** to maintain synchronized build numbers across all developers. This ensures every build has a unique, sequential version code across the entire team.

## Critical Rules for Developers

### Rule 1: Always Pull Before Building
```bash
git pull origin main
# Then build your app
```

**Why:** Ensures you have the latest build number before incrementing it.

### Rule 2: Commit version.properties After Each Build
```bash
# After building
git add app/version.properties
git commit -m "Increment build to [BUILD_NUMBER]"
git push origin main
```

**Why:** Makes your build number available to other developers immediately.

### Rule 3: Handle Merge Conflicts Correctly

When you get a merge conflict in `version.properties`:

**ALWAYS take the HIGHER build number**

```properties
<<<<<<< HEAD
VERSION_CODE=42
=======
VERSION_CODE=45
>>>>>>> origin/main
```

**Resolution:** Use `45` (the higher number)

**Why:** Build numbers must always increment, never go backwards.

### Rule 4: Never Manually Decrement VERSION_CODE

❌ **NEVER DO THIS:**
```properties
VERSION_CODE=50  # Changed from 51 back to 50
```

✅ **ALWAYS INCREMENT:**
```properties
VERSION_CODE=51  # Changed from 50 to 51
```

**Why:** Build numbers must be unique and sequential. Going backwards creates duplicate version codes.

### Rule 5: Coordinate Major Releases

When updating VERSION_MAJOR, VERSION_MINOR, or VERSION_PATCH:

1. **Announce to team** (Slack/email): "I'm releasing version 1.1.0"
2. **Pull latest** to get current VERSION_CODE
3. **Update version numbers** in version.properties:
   ```properties
   VERSION_MAJOR=1
   VERSION_MINOR=1
   VERSION_PATCH=0
   VERSION_CODE=67  # Keep current, will auto-increment
   ```
4. **Build and commit immediately**
5. **Tag the release**:
   ```bash
   git tag v1.1.0-build68
   git push --tags
   ```

## Workflow Examples

### Scenario 1: Normal Development Build

```bash
# Morning - start work
git pull origin main

# Make code changes
# ... edit files ...

# Build the app (VERSION_CODE auto-increments)
./gradlew assembleDebug

# Commit everything including version.properties
git add .
git commit -m "Add new feature - build 43"
git push origin main
```

### Scenario 2: Merge Conflict in version.properties

```bash
# You built locally (VERSION_CODE=42)
git pull origin main

# Conflict! Someone else pushed VERSION_CODE=45
# File shows:
<<<<<<< HEAD
VERSION_CODE=42
=======
VERSION_CODE=45
>>>>>>> origin/main

# Resolution: Take the HIGHER number (45)
# Then increment by 1 for your build
VERSION_CODE=46

# Rebuild to use the correct version
./gradlew clean assembleDebug

# Commit and push
git add app/version.properties
git commit -m "Resolve version conflict - build 46"
git push origin main
```

### Scenario 3: Multiple Developers Building Simultaneously

**Developer A:**
```bash
git pull  # Gets VERSION_CODE=50
# Builds → VERSION_CODE becomes 51
git commit -m "Build 51"
git push
```

**Developer B (at same time):**
```bash
git pull  # Gets VERSION_CODE=50
# Builds → VERSION_CODE becomes 51 (same as A!)
git push  # FAILS - conflict detected

# Resolve:
git pull  # Gets A's build 51
# version.properties now shows 51
# Rebuild → VERSION_CODE becomes 52
git commit -m "Build 52"
git push  # SUCCESS
```

## Best Practices

### 1. Build Frequency
- **Don't build unnecessarily** - each build increments the counter
- Use "Run" for testing (still increments, but that's okay)
- Clean builds also increment the counter

### 2. Failed Builds
If your build fails:
- The VERSION_CODE still incremented in version.properties
- **Don't revert it** - just fix the issue and build again
- Gaps in build numbers are okay and expected

### 3. Branch Strategy

**Feature Branches:**
- Don't commit version.properties changes in feature branches
- Only commit version changes to main/develop branch
- Rebase before merging to get latest version

**Release Branches:**
- Lock VERSION_MAJOR.MINOR.PATCH
- Let VERSION_CODE continue incrementing
- Tag each release candidate: `v1.0.0-rc1-build123`

### 4. CI/CD Builds

If using CI/CD:
- CI builds should also increment VERSION_CODE
- Ensure CI commits version.properties back to repo
- Or use a separate version scheme for CI (e.g., add 10000 to distinguish)

## Troubleshooting

### Problem: Build numbers jumping by large amounts
**Cause:** Multiple developers building simultaneously
**Solution:** Normal behavior. Coordinate builds if sequential numbers are important.

### Problem: Duplicate version codes
**Cause:** Two developers built from same base and both pushed
**Solution:** Second push will fail. Pull, rebuild, push again.

### Problem: Version went backwards
**Cause:** Incorrect merge conflict resolution
**Solution:** 
```bash
# Fix version.properties to use higher number
VERSION_CODE=75  # Was incorrectly set to 60

# Rebuild
./gradlew clean assembleDebug

# Commit
git commit -am "Fix version regression - build 76"
git push
```

### Problem: Lost track of which build is which
**Solution:** Use git tags for important builds:
```bash
git tag -a build-123 -m "QA testing build"
git push --tags
```

## Quick Reference

| Action | Command |
|--------|---------|
| Before building | `git pull` |
| After building | `git add app/version.properties && git commit -m "Build X" && git push` |
| Merge conflict | Take HIGHER number, rebuild, commit |
| Release version | Update MAJOR/MINOR/PATCH, build, tag, push |
| Check current build | `cat app/version.properties` |

## Emergency: Reset Build Counter

Only if absolutely necessary (e.g., starting fresh):

1. **Get team agreement** - affects everyone
2. Edit version.properties:
   ```properties
   VERSION_CODE=1
   ```
3. Commit and push:
   ```bash
   git commit -am "Reset build counter to 1"
   git push
   ```
4. **Notify all developers** to pull immediately

## Summary

✅ **DO:**
- Pull before building
- Commit version.properties after building
- Take higher number in conflicts
- Let build numbers increment naturally
- Coordinate major releases

❌ **DON'T:**
- Manually decrement VERSION_CODE
- Skip committing version.properties
- Build without pulling first
- Panic about gaps in build numbers
- Revert version changes after failed builds
