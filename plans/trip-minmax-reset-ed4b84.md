# Hybrid Min/Max Reset Strategy

Implement automatic reset when dashboard is first displayed plus a manual reset button for user control during active trips.

## Context

The max/min tracking feature has been implemented in the base `DashboardGaugeView` class and visual indicators added to `BarGaugeView` and `DialView`. The user wants a middle ground approach combining automatic initialization with manual control.

## Implementation Strategy

### Part 1: Automatic Reset on Dashboard Display
- **Location**: `DashboardEditorFragment.renderCanvas()` method
- **Timing**: After `applyWidgetSettings()` but before starting live data
- **Scope**: Only for `DialView` and `BarGaugeView` types
- **Benefit**: Fresh start when dashboard loads, handles view lifecycle naturally

### Part 2: Manual Reset Button
- **Location**: Top strip of dashboard (near trip controls)
- **Visibility**: Only when trip is RUNNING (not in edit mode)
- **Behavior**: Resets min/max for all gauges in current dashboard
- **Icon**: Reset/clear icon (material design refresh or clear icon)
- **Tooltip**: "Reset Min/Max Values"

## Technical Implementation

### Automatic Reset in renderCanvas()
```kotlin
// After creating gauge view and applying settings
val gaugeView = createViewForWidgetType(widget.type)
applyWidgetSettings(gaugeView, widget, layout.colorScheme)

// Reset min/max for dial and bar gauges
if (gaugeView is DialView || gaugeView is BarGaugeView) {
    gaugeView.resetTripMinMax()
}
```

### Manual Reset Button
1. **Add button to layout**: `fragment_dashboard_editor.xml` - near trip controls
2. **Button visibility logic**: Show only when trip is RUNNING and not in edit mode
3. **Reset logic**: Iterate through all gauge views in canvasContainer and call resetTripMinMax()
4. **Visual feedback**: Brief toast message "Min/Max values reset"

### Button Placement
- **Position**: Right side of top strip, after trip controls
- **Icon**: Material Design "refresh" or "clear_all" icon
- **Size**: Same as other control buttons
- **Spacing**: Consistent with existing button spacing

## Benefits of This Approach

1. **Automatic initialization**: No manual action needed when dashboard first loads
2. **User control**: Can reset mid-trip if they want to track a new segment
3. **Simple implementation**: No need to maintain gauge references across lifecycle
4. **Flexible**: Works with dashboard switching and view recreation
5. **Intuitive**: Reset button appears only when relevant (trip running)

## Edge Cases Handled

- **Dashboard switching**: Each dashboard gets fresh min/max when displayed
- **Edit mode**: Reset button hidden during editing
- **Trip idle**: Reset button hidden when no trip is active
- **View recreation**: Automatic reset handles new view instances

## Implementation Steps

1. Add reset button to XML layout
2. Implement button visibility logic in `setupTopStrip()`
3. Add reset click handler that iterates through gauge views
4. Add automatic reset call in `renderCanvas()` for dial/bar gauges
5. Test with different widget combinations and trip states
