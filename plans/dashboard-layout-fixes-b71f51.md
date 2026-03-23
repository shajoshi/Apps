# Dashboard Layout Editor Fixes

Fix three issues with the Dashboard Layout editor: widget positioning preservation, grid density, and text visibility.

## Issues Identified

### 1. Widget Position Preservation
**Problem:** When widgets overlap and a new widget is added or an existing widget is edited, all widgets get repositioned (often to the center).

**Root Cause:** The issue is in `DashboardEditorFragment.kt` line 492-497. When a widget is added, the widget IDs change (new widget added), triggering a full `renderCanvas()` call. The `renderCanvas()` method recreates all widget views from scratch. While the widget data model preserves positions correctly, there may be an issue with how the views are being recreated or positioned.

**Analysis:** Looking at the code:
- `addWidget()` in ViewModel correctly preserves existing widget positions (lines 299-300, 261-262 in ViewModel)
- `updateWidgetProperties()` explicitly preserves gridX and gridY (lines 299-300)
- The selective update logic (lines 482-507) should use `updateSingleWidget()` for property changes
- However, when a new widget is added, `widgetIdsChanged` becomes true, forcing a full re-render

**Solution:** The issue is likely that `renderCanvas()` is being called when it shouldn't be. The code already has logic to use `updateSingleWidget()` for property changes, but adding a widget triggers a full re-render. This is actually correct behavior for adding widgets. The real issue might be elsewhere - possibly in how widget positions are being calculated or stored.

**Need to verify:** Check if there's any code that modifies widget positions during the add/edit flow that shouldn't.

### 2. Grid Density
**Current State:** Grid size is hardcoded at `gridSizePx = 100` in `DashboardEditorFragment.kt` line 49.

**Solution:** Reduce `gridSizePx` to increase grid density. Suggested values:
- 75px for moderate increase (33% more grid points)
- 50px for high density (2x more grid points)
- 60px for balanced approach

### 3. Text Color Visibility
**Problem:** Text fields in add/update widget screens use `#AAAACC` (light gray) which is not visible on dark backgrounds.

**Affected Files:**
- `widget_properties_editor.xml` - Multiple TextView labels use `#AAAACC`
- Labels for: "Min value", "Max value", "Major tick interval", "Minor tick count", "Warning threshold", "Decimal places", "Display unit"

**Solution:** Change `textColor` from `#AAAACC` to `#FFFFFF` or `#EEEEEE` for better contrast.

## Root Cause Analysis - Widget Positioning Issue

**The Problem:** When `updateWidgetRangeSettings()` is called after adding a widget (in `AddWidgetWizardSheet.kt` line 152), it explicitly copies position fields (gridX, gridY, gridW, gridH) even though they haven't changed. This is redundant since `w.copy()` already preserves all fields not explicitly changed.

**Why this might cause issues:** The explicit copying in lines 261-266 of `DashboardEditorViewModel.kt` is unnecessary and may be masking a deeper issue. The widget positions should already be preserved by the `w.copy()` call.

**However**, the real issue is likely NOT in the ViewModel code (which correctly preserves positions), but rather in how the Fragment handles the layout updates. When widgets are added, the observer triggers a full `renderCanvas()` because widget IDs changed. This should work correctly, but we need to verify the rendering doesn't inadvertently modify positions.

## Implementation Plan

1. **Simplify `updateWidgetRangeSettings()`** - Remove redundant explicit copying of position/size fields
2. **Increase grid density** - Change `gridSizePx` from 100 to 60 (user confirmed)
3. **Make grid visible during drag** - Show grid overlay when widget is being moved (user requested)
4. **Fix text colors** - Update `widget_properties_editor.xml` to use `#FFFFFF` for label text colors

## Implementation Details

### 1. Widget Positioning Fix
- Simplify `updateWidgetRangeSettings()` to only copy the fields it actually changes
- Verify `updateWidgetProperties()` also doesn't have redundant field copying

### 2. Grid Density
- Change `gridSizePx = 100` to `gridSizePx = 60` in `DashboardEditorFragment.kt`

### 3. Grid Visibility Improvements
The grid is currently amber/orange (`#FFB74D`) at 53% opacity with 3px dots. User reports never seeing it.

**Changes:**
- Increase dot size from 3px to 5px for better visibility
- Increase opacity from 53% (`0x88`) to 80% (`0xCC`) for better contrast
- Keep grid visible in edit mode (already implemented)
- Grid will become more visible with 60px spacing (more dots on screen)

### 4. Text Color Fix
- In `widget_properties_editor.xml`, change all label `textColor` from `#AAAACC` to `#FFFFFF`
