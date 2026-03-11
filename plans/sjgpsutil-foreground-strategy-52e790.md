# Plan to Implement SJGpsUtil Foreground Service Strategy

## Summary
Implement the SJGpsUtil foreground service strategy to ensure the OBD app shows a persistent notification with visible icon when running trips in background.

## Key Findings from SJGpsUtil Analysis
- Uses `R.mipmap.ic_launcher` for notification icon (app launcher icon)
- Has `ensureForeground()` method that manages foreground state properly
- Notification shows simple status text
- Service starts foreground when recording begins
- Proper notification channel with LOW importance to avoid disturbing user

## Current OBD App Issues
- Notification may not be showing due to icon or foreground management issues
- Using custom `ic_notification.xml` drawable instead of launcher icon
- No `ensureForeground()` method - always calls `startForeground()` in `onCreate`

## Implementation Plan

### 1. Update Notification Icon
- Change from `ic_notification` to `R.mipmap.ic_launcher` (app icon) for better visibility
- This matches SJGpsUtil approach and ensures icon is always visible in status bar

### 2. Add ensureForeground() Method  
- Add `isForeground` boolean flag to track foreground state
- Add `ensureForeground()` method that:
  - Calls `startForeground()` only if not already foreground
  - Updates notification if already foreground
- Update `observeTripState()` to use `ensureForeground()` instead of direct notification updates

### 3. Simplify Notification Content
- Follow SJGpsUtil pattern: simple status text like "Trip in progress"
- Remove duration/distance from notification (keep in app UI only)
- This reduces notification complexity and matches their working approach

### 4. Verify Service Startup
- Ensure service starts properly on trip begin
- Test background behavior and notification persistence

## Expected Results
- Notification appears immediately when trip starts
- Icon is visible in status bar when app is backgrounded  
- Notification persists and updates correctly during trip lifecycle
- App stays alive during background trips
