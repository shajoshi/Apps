# Android 16 Support Plan for OBD2App

This plan outlines the necessary changes to support Android 16 (API level 36, codename "Baklava") in the OBD2App, focusing on compatibility updates and new requirement compliance.

## Current State Analysis

The app currently targets:
- **compileSdk**: 36 (Android 15)
- **targetSdk**: 36 (Android 15)  
- **minSdk**: 26 (Android 8.0)
- **AGP Version**: 9.0.1
- **Kotlin**: 2.0.21

## Required Changes for Android 16 Support

### 1. Update Target SDK Version
- **Change**: Update `targetSdk` from 36 to 37 (Android 16)
- **Files**: `app/build.gradle.kts`
- **Impact**: Enables Android 16 APIs and behavior changes

### 2. Update Compile SDK Version  
- **Change**: Update `compileSdk` from 36 to 37
- **Files**: `app/build.gradle.kts`
- **Impact**: Access to Android 16 APIs and compilation against new platform

### 3. Handle Edge-to-Edge Enforcement
- **Issue**: `R.attr#windowOptOutEdgeToEdgeEnforcement` is deprecated and disabled in Android 16
- **Current State**: App already implements edge-to-edge padding in MainActivity (lines 82-87)
- **Action**: Verify edge-to-edge implementation works correctly, no changes needed
- **Files**: `MainActivity.kt` (already compliant)

### 4. Predictive Back Navigation
- **Issue**: Predictive back animations enabled by default, `onBackPressed` not called
- **Current State**: App doesn't override `onBackPressed` method
- **Action**: Add `android:enableOnBackInvokedCallback="false"` to maintain current behavior
- **Files**: `AndroidManifest.xml` (application or activity level)

### 5. Bluetooth Bond Loss Handling
- **New Feature**: Android 16 introduces `ACTION_KEY_MISSING` and `ACTION_ENCRYPTION_CHANGE` intents
- **Current State**: App uses standard Bluetooth connection handling
- **Action**: Optional enhancement - add receivers for better bond loss awareness
- **Files**: New broadcast receiver classes, manifest updates

### 6. Local Network Permission (Future Preparation)
- **Timeline**: Will be enforced in Android 17 (26Q2), but preparation starts in Android 16
- **Impact**: Apps accessing local network need `NEARBY_WIFI_DEVICES` permission
- **Current State**: App only uses Bluetooth Classic, not local network
- **Action**: No immediate changes needed, but monitor for future requirements

### 7. Dependency Updates
- **Change**: Update AndroidX libraries to support Android 16
- **Files**: `gradle/libs.versions.toml`
- **Specific Libraries**: 
  - AndroidX Core KTX
  - Lifecycle libraries
  - Navigation libraries
  - Material Components

### 8. Testing Requirements
- **Device Testing**: Test on Android 16 devices/emulators
- **Compatibility**: Verify backward compatibility to Android 8.0
- **Focus Areas**: Bluetooth connectivity, edge-to-edge UI, back navigation

## Implementation Priority

### High Priority (Required for Android 16)
1. Update targetSdk and compileSdk to 37
2. Add predictive back navigation opt-out
3. Update dependency versions

### Medium Priority (Recommended)
1. Implement enhanced Bluetooth bond loss handling
2. Comprehensive testing on Android 16

### Low Priority (Future Preparation)
1. Monitor local network permission developments
2. Plan for Android 17 requirements

## Risk Assessment

### Low Risk
- SDK version updates
- Predictive back opt-out
- Edge-to-edge compliance (already implemented)

### Medium Risk  
- Dependency updates (potential breaking changes)
- Bluetooth bond loss handling (OEM variations)

### High Risk
- None identified

## Timeline Estimate

- **Phase 1** (SDK Updates): 2-4 hours
- **Phase 2** (Testing & Validation): 4-6 hours  
- **Phase 3** (Enhanced Features): 6-8 hours (optional)

**Total Estimated Time**: 6-10 hours for basic support, 12-18 hours with enhancements

## Success Criteria

1. ✅ App compiles and runs on Android 16
2. ✅ All existing functionality preserved
3. ✅ Back navigation works as expected
4. ✅ Bluetooth connectivity remains stable
5. ✅ Edge-to-edge UI displays correctly
6. ✅ No regression on minimum supported Android version (8.0)
