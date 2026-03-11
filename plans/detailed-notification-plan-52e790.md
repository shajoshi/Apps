# Plan to Show Detailed Trip Info in Foreground Notification

## Summary
Implement SJGpsUtil's foreground service management strategy while keeping the detailed trip duration, state, and distance information in the notification.

## Current Implementation Analysis
The notification already shows detailed info:
- **State**: "Trip in progress" / "Trip paused" / "Trip stopped"
- **Duration**: Formatted time from `calculator.elapsedTripSec()`
- **Distance**: Formatted km from `metrics.tripDistanceKm`
- **Content**: "$status • $duration • $distance"

## Issues Fixed
- ✅ Calculator initialization moved to `onCreate` (fixed notification not showing)
- ✅ Timer state moved to `MetricsCalculator` singleton (fixed duration loss on background)

## Remaining Issues to Fix
- Notification icon may not be visible enough
- Foreground management could be improved like SJGpsUtil

## Implementation Plan

### 1. Use App Launcher Icon
- Change from `ic_notification.xml` to `R.mipmap.ic_launcher` for better visibility
- Matches SJGpsUtil approach and ensures icon appears in status bar

### 2. Add ensureForeground() Method
- Add `isForeground` boolean flag to track foreground state
- Add `ensureForeground()` method that:
  - Calls `startForeground()` only if not already foreground
  - Updates notification if already foreground
- This prevents duplicate `startForeground()` calls and improves reliability

### 3. Keep Detailed Notification Content
- **Keep** the detailed trip info (duration, state, distance) in notification
- **Do NOT** simplify like SJGpsUtil - user specifically wants this detailed info
- Notification format: "Trip in progress • 12:34 • 45.6 km"

### 4. Improve Notification Updates
- Update `observeTripState()` to use `ensureForeground()` for proper foreground management
- Ensure notification updates immediately when trip state changes

## Expected Results
- Notification appears with visible app icon when trip starts
- Shows real-time trip duration, current state, and distance covered
- Persists and updates correctly when app is backgrounded
- App stays alive during background trips with detailed status info
