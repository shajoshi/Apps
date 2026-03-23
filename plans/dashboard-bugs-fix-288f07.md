# Dashboard JSON Corruption and Widget Visibility Bug Fix

Fix two critical bugs in the dashboard system: JSON corruption causing load failures and newly added widgets not rendering until save/reload.

## Issues Identified

### Issue 1: JSON Corruption - Extra Closing Brace
The dashboard JSON file `dashboard_D1.json` has an extra `}` on line 207, making it invalid JSON. Analysis confirms:
- **Brace count**: 25 opening braces vs 26 closing braces (one extra)
- **Location**: The extra `}}` appears at the very end of the file (position 4674)
- **Pattern**: All other `}}` patterns in the file have newlines between braces (normal widget closures), but the final one has no newline

**Investigation Findings**:
1. The save code path in `LayoutRepository.kt` is straightforward:
   - Line 47: `gson.toJson(layout)` generates the JSON string
   - Line 66: `output.write(json.toByteArray())` writes it once
   - Uses mode `"w"` which overwrites the file completely
   
2. The `DashboardMetricAdapter.serialize()` method looks correct - it creates a JsonObject and returns it properly for all metric types

3. **Most Likely Root Cause**: The corruption is NOT from the current save code, but from a **previous version** of the code that had a bug. According to the memory, there was a recent fix that changed `openOutputStream` mode from `"wt"` (write truncate) to `"w"` (write). The `"wt"` mode could have caused partial writes or corruption if the write was interrupted.

4. **Alternative Theory**: There may have been a race condition or double-write scenario in an older version of the code where the file was written to twice, or where an append operation occurred instead of overwrite.

**Conclusion**: The current save code appears correct. The corrupted file was likely created by an older buggy version before the recent file truncation fix. New saves should not produce this corruption.

### Issue 2: Newly Added Widgets Not Rendering
When a new widget is added through the wizard, it doesn't appear on screen until the dashboard is saved and reopened. 

**Root Cause**: In `AddWidgetWizardSheet.commitAndDismiss()`:
1. Line 148: `vm.addWidget()` is called, which updates the StateFlow
2. Line 151-161: Immediately after, `vm.updateWidgetRangeSettings()` is called
3. The StateFlow observer in `DashboardEditorFragment` (lines 510-549) uses selective update logic
4. When `updateWidgetRangeSettings()` is called immediately after `addWidget()`, the observer sees the widget ID already exists (from the first update), so it takes the `else` branch (line 534) and calls `updateSingleWidget()` instead of `renderCanvas()`
5. However, `updateSingleWidget()` (line 700) tries to find the widget view using `canvasContainer.findViewWithTag<FrameLayout>(widget.id)`, which returns `null` because the widget was never rendered in the first place
6. The null check on line 703 silently fails, and the widget is never displayed

## Fix Plan

### 1. Fix JSON Corruption (Manual Fix Only)
**Action**: Remove the extra `}` from line 207 of `dashboard_D1.json`

**Rationale**: 
- The current save code in `LayoutRepository.kt` is correct and should not produce this corruption
- The file was likely corrupted by an older version of the code (before the recent `"wt"` → `"w"` fix)
- No code changes needed - just fix the corrupted file manually
- Future saves will work correctly with the current code

### 2. Fix Widget Rendering Issue
**Problem**: When adding a widget via the wizard, two StateFlow updates occur in rapid succession:
1. `addWidget()` adds the widget to the layout
2. `updateWidgetRangeSettings()` modifies the same widget's properties

The observer's selective update logic sees the widget ID exists after the second update, calls `updateSingleWidget()` instead of `renderCanvas()`, but the widget view doesn't exist yet, so it silently fails.

**Solution Options**:

**Option A (Preferred)**: Batch the updates in `AddWidgetWizardSheet`
- Modify `commitAndDismiss()` to create the widget with final properties in one operation
- Add a new ViewModel method `addWidgetWithProperties()` that combines both operations
- This prevents the double StateFlow emission entirely

**Option B**: Add defensive fallback in the observer
- Modify the observer in `DashboardEditorFragment` to detect when `updateSingleWidget()` fails
- Fall back to `renderCanvas()` if the widget view is not found
- Less clean but more defensive

**Option C**: Fix `updateSingleWidget()` to handle missing views
- Make `updateSingleWidget()` call `renderCanvas()` if the view is not found
- Simple one-line fix but doesn't address the root cause

**Recommendation**: Use **Option A** as it's the cleanest solution and prevents unnecessary double updates.

### 3. Add Defensive Logging
- Add error logging in `updateSingleWidget()` when a widget view is not found
- This will help detect similar issues in the future

## Implementation Steps - Option A

### Step 1: Fix Corrupted JSON File
**File**: `c:\Code\Apps\OBD2App\tmp\dashboard_D1.json`
- Remove the extra `}` from line 207
- Change `}}` to `}`

### Step 2: Create New Batched ViewModel Method
**File**: `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\ui\dashboard\DashboardEditorViewModel.kt`

Add new method after `addWidget()` (around line 154):
```kotlin
/**
 * Adds a new widget with all properties set in a single operation.
 * This prevents double StateFlow emissions that can cause rendering issues.
 */
fun addWidgetWithProperties(
    type: WidgetType,
    metric: DashboardMetric,
    gridW: Int = 4,
    gridH: Int = 4,
    rangeMin: Float,
    rangeMax: Float,
    majorTickInterval: Float,
    minorTickCount: Int,
    warningThreshold: Float?,
    decimalPlaces: Int,
    displayUnit: String
) {
    val layout = _currentLayout.value
    val newZ = (layout.widgets.maxOfOrNull { it.zOrder } ?: -1) + 1
    val (slotX, slotY) = findFirstFreeSlot(layout, gridW, gridH)

    val newWidget = DashboardWidget(
        id = UUID.randomUUID().toString(),
        type = type,
        metric = metric,
        gridX = slotX,
        gridY = slotY,
        gridW = gridW,
        gridH = gridH,
        zOrder = newZ,
        rangeMin = rangeMin,
        rangeMax = rangeMax,
        majorTickInterval = majorTickInterval,
        minorTickCount = minorTickCount,
        warningThreshold = warningThreshold,
        decimalPlaces = decimalPlaces,
        displayUnit = displayUnit
    )

    _currentLayout.value = layout.copy(widgets = layout.widgets + newWidget)
    _selectedWidgetId.value = newWidget.id
}
```

### Step 3: Update Wizard to Use Batched Method
**File**: `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\ui\dashboard\wizard\AddWidgetWizardSheet.kt`

Replace `commitAndDismiss()` method (lines 142-163):
```kotlin
private fun commitAndDismiss() {
    val type   = state.selectedType   ?: return
    val metric = state.selectedMetric ?: return

    // Obtain the shared ViewModel from the parent fragment (DashboardEditorFragment)
    val vm = ViewModelProvider(requireParentFragment())[DashboardEditorViewModel::class.java]
    
    // Use the batched method to add widget with all properties in one operation
    // This prevents double StateFlow emission and ensures the widget renders immediately
    vm.addWidgetWithProperties(
        type = type,
        metric = metric,
        gridW = state.gridW,
        gridH = state.gridH,
        rangeMin = state.rangeMin,
        rangeMax = state.rangeMax,
        majorTickInterval = state.majorTickInterval,
        minorTickCount = state.minorTickCount,
        warningThreshold = state.warningThreshold,
        decimalPlaces = state.decimalPlaces,
        displayUnit = state.displayUnit
    )
    
    dismiss()
}
```

### Step 4: Add Defensive Logging
**File**: `c:\Code\Apps\OBD2App\app\src\main\java\com\sj\obd2app\ui\dashboard\DashboardEditorFragment.kt`

In `updateSingleWidget()` method (around line 702), add logging when wrapper is null:
```kotlin
val wrapper = canvasContainer.findViewWithTag<FrameLayout>(widget.id)
if (wrapper == null) {
    android.util.Log.w("DashUIEdit", "updateSingleWidget: Widget view not found for id=${widget.id.take(8)}, falling back to full render")
    renderCanvas(viewModel.currentLayout.value)
    return
}
```

### Expected Outcome
- JSON file will be valid and loadable
- New widgets will appear immediately on screen when added via wizard
- No save/reload required to see newly added widgets
- Single StateFlow emission per widget addition (cleaner, more efficient)
