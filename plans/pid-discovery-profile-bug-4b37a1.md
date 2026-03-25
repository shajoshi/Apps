# PID Discovery Profile Saving Bug

## Bug Confirmed

You are absolutely correct! The discovered PIDs are being saved to the **active profile** instead of the **profile that was opened for discovery**.

## Root Cause Analysis

### Current Flow:
1. **VehicleProfileEditSheet.kt** (line 77):
   ```kotlin
   CustomPidListSheet.newInstance(editingProfile?.id).show(parentFragmentManager, "custom_pid_list")
   ```
   - Passes the specific profile ID being edited

2. **CustomPidListSheet.kt** (line 63):
   ```kotlin
   val discoverySheet = PidDiscoverySheet.newInstance(profileId)
   ```
   - Correctly passes the profileId to PidDiscoverySheet

3. **PidDiscoverySheet.kt** (lines 66-68):
   ```kotlin
   val argProfileId = arguments?.getString(ARG_PROFILE_ID)
   val fallbackId = com.sj.obd2app.settings.AppSettings.getActiveProfileId(requireContext())
   activeProfileId = argProfileId ?: fallbackId
   ```
   - **THE BUG IS HERE**: If argProfileId is null, it falls back to the active profile

### The Problem:
The fallback logic is incorrect. When `CustomPidListSheet.newInstance(profileId)` is called with a valid profileId, it should always use that profileId, not fall back to the active profile.

### Why argProfileId becomes null:
Looking at the flow, the profileId should be passed correctly. Let me check if there's an issue with how the argument is preserved.

## Investigation Points:

1. **Argument Bundle Preservation**: Check if the Bundle is being preserved correctly through fragment recreation
2. **Null Check Logic**: The fallback should only be used when discovery is opened directly without a specific profile

## The Fix:

The issue is in the fallback logic. It should only fall back to active profile when discovery is opened without any profile context (e.g., from a global menu). When opened from a profile-specific context, it should always use that profile.

### Proposed Fix:

```kotlin
// In PidDiscoverySheet.kt onViewCreated
val argProfileId = arguments?.getString(ARG_PROFILE_ID)
if (argProfileId != null) {
    // Opened with specific profile - use it
    activeProfileId = argProfileId
    android.util.Log.d("PidDiscoverySheet", "Using specified profile: $argProfileId")
} else {
    // Opened without profile context - use active profile
    val fallbackId = com.sj.obd2app.settings.AppSettings.getActiveProfileId(requireContext())
    activeProfileId = fallbackId
    android.util.Log.d("PidDiscoverySheet", "Using active profile: $fallbackId")
}
```

### Additional Logging for Debugging:

Add more detailed logging to trace the issue:

```kotlin
// In PidDiscoverySheet.kt
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    
    discoveryService = PidDiscoveryService.getInstance()
    profileRepository = VehicleProfileRepository.getInstance(requireContext())
    
    // Debug logging
    android.util.Log.d("PidDiscoverySheet", "Arguments bundle: ${arguments}")
    android.util.Log.d("PidDiscoverySheet", "ARG_PROFILE_ID value: ${arguments?.getString(ARG_PROFILE_ID)}")
    android.util.Log.d("PidDiscoverySheet", "Bundle contents: ${arguments?.keySet()?.map { "$it=${arguments?.get(it)}" }}")
    
    val argProfileId = arguments?.getString(ARG_PROFILE_ID)
    val fallbackId = com.sj.obd2app.settings.AppSettings.getActiveProfileId(requireContext())
    
    if (argProfileId != null) {
        activeProfileId = argProfileId
        android.util.Log.d("PidDiscoverySheet", "PROFILE CONTEXT: Using specified profile $argProfileId")
    } else {
        activeProfileId = fallbackId
        android.util.Log.d("PidDiscoverySheet", "GLOBAL CONTEXT: Using active profile $fallbackId")
    }
    
    // ... rest of onViewCreated
}
```

## Test Scenario to Reproduce:

1. Have multiple vehicle profiles
2. Set Profile A as active
3. Open Profile B for editing
4. Go to Custom PIDs → Discover PIDs
5. Discover and save some PIDs
6. **Expected**: PIDs saved to Profile B
7. **Actual**: PIDs saved to Profile A (active profile)

## Why This Happens:

The current logic assumes that if no profile ID is in arguments, it should fall back to active profile. But this fallback is being triggered even when a profile ID should be present, suggesting either:
1. The Bundle is not being preserved correctly
2. The profile ID is not being passed correctly
3. There's a fragment recreation issue

## Solution Priority:

**High Priority** - This is a critical UX bug that causes user data to be saved to the wrong profile, leading to confusion and potential data loss.
