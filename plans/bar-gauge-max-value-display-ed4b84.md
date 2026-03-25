# Bar Gauge Max Value Display & Visibility Fix

Add max value text display at 87.5% position on bar gauges and fix value text visibility when it overlaps with the bar fill color.

## Requirements Summary

Based on user clarifications:
1. **Max value text**: Display `tripMaxValue` at the 87.5% position of the bar (midpoint between 75-100%, both horizontal and vertical)
2. **Text size**: 60% of current value font size
3. **Text color**: Red (matching the max tick mark color `0xFFFF0000`)
4. **Visibility fix**: Add dark semi-transparent background pill behind current value text (similar to DialView implementation)
5. **Apply to**: Both horizontal and vertical bar gauge orientations

## Current State Analysis

### Existing Max/Min Tracking
- `tripMaxValue` and `tripMinValue` already tracked in `DashboardGaugeView` base class
- Red tick marks already drawn at max value position (lines 150-165)
- Blue tick marks already drawn at min value position (lines 168-182)

### Current Value Display
- Current value displayed centered in track (line 203)
- Uses `drawTextWithGlow()` for visibility
- Size: `minDim * 0.22f` (vertical) or `trackRect.height() * 0.70f` (horizontal)
- **Problem**: When bar fill color matches text color, text becomes invisible

### Layout Structure
- **Vertical bars**: Track runs top to bottom, value centered
- **Horizontal bars**: Track runs left to right, value centered
- Track rectangle: `trackRect` defines the bar area

## Implementation Plan

### Part 1: Add Dark Background Pill Behind Current Value

**Location**: Before drawing current value text (around line 203)

**Implementation**:
1. Create semi-transparent dark pill paint (similar to DialView lines 205-208)
2. Measure current value text width
3. Calculate pill dimensions with padding
4. Draw rounded rectangle behind value text
5. Then draw value text on top

**Code approach**:
```kotlin
// Create pill background
val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    color = (colorScheme.background and 0x00FFFFFF) or 0xAA000000.toInt()
}
val pillHalfW = textPaint.measureText(valueStr) / 2f + valueSize * 0.15f
val pillHalfH = valueSize * 0.55f
val pillRect = RectF(
    valueCx - pillHalfW, trackCy - valueOffset - pillHalfH,
    valueCx + pillHalfW, trackCy - valueOffset + pillHalfH
)
canvas.drawRoundRect(pillRect, pillHalfH * 0.3f, pillHalfH * 0.3f, pillPaint)
```

### Part 2: Add Max Value Text Display

**Location**: After current value display (around line 215)

**Implementation**:
1. Check if `tripMaxValue` exists
2. Format max value with same decimal places
3. Calculate position at 87.5% of bar length (midpoint between 75-100%)
4. Calculate text size (60% of current value size)
5. Draw dark pill background behind max value text
6. Draw max value text in red on top of pill

**Position calculations**:
- **Horizontal**: X = `trackRect.left + trackRect.width() * 0.875f`, Y = `trackCy`
- **Vertical**: X = `width / 2f`, Y = `trackRect.bottom - trackRect.height() * 0.875f`

**Code approach**:
```kotlin
tripMaxValue?.let { maxVal ->
    val maxValueStr = String.format(fmt, maxVal)
    val maxValueSize = valueSize * 0.60f
    val maxValuePaint = Paint(textPaint).apply {
        textSize = maxValueSize
        color = 0xFFFF0000.toInt()  // Red
        textAlign = Paint.Align.CENTER
    }
    
    val maxValueOffset = (maxValuePaint.descent() + maxValuePaint.ascent()) / 2
    
    // Calculate position
    val maxX = if (isVertical) width / 2f else trackRect.left + trackRect.width() * 0.875f
    val maxY = if (isVertical) trackRect.bottom - trackRect.height() * 0.875f else trackCy
    
    // Draw pill background for max value
    val maxPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (colorScheme.background and 0x00FFFFFF) or 0xAA000000.toInt()
    }
    val maxPillHalfW = maxValuePaint.measureText(maxValueStr) / 2f + maxValueSize * 0.15f
    val maxPillHalfH = maxValueSize * 0.55f
    val maxPillRect = RectF(
        maxX - maxPillHalfW, maxY - maxValueOffset - maxPillHalfH,
        maxX + maxPillHalfW, maxY - maxValueOffset + maxPillHalfH
    )
    canvas.drawRoundRect(maxPillRect, maxPillHalfH * 0.3f, maxPillHalfH * 0.3f, maxPillPaint)
    
    // Draw max value text
    drawTextWithGlow(canvas, maxValueStr, maxX, maxY - maxValueOffset, maxValuePaint)
}
```

### Part 3: Consider Overlap Prevention

**Potential issue**: Max value text at 87.5% might overlap with current value text if current value is near 87.5%

**Solutions**:
1. **Option A**: Only show max value if it's sufficiently different from current value
2. **Option B**: Adjust max value position slightly if too close to current value
3. **Option C**: Show max value regardless (simpler, user can see both)

**Recommendation**: Start with Option C (always show), can refine later if needed. The 87.5% position reduces overlap risk compared to 75%.

## Files to Modify

1. **`BarGaugeView.kt`**: 
   - Add dark pill background before current value text (around line 197-203)
   - Add max value text display after current value (around line 215)

## Testing Considerations

- Test with both horizontal and vertical orientations
- Test with different value ranges and current values
- Verify max value appears at correct 75% position
- Verify current value is visible against bar fill
- Test with warning state (orange color)
- Test when current value is near 75% (potential overlap)

## Edge Cases

1. **No max value yet**: `tripMaxValue` is null - handled by `?.let`
2. **Max value = current value**: Both texts will show same number
3. **Very small bars**: Text might be cramped - existing size calculations should handle
4. **Long decimal values**: May need to ensure text doesn't overflow bar bounds

## Visual Result

**Before**: Current value text invisible when bar fill matches text color
**After**: 
- Current value always visible with dark background pill
- Max value shown in red at 87.5% position with its own dark background pill
- Both values protected from visibility issues with bar fill, ticks, or overlapping elements
- Clear visual hierarchy: current value (large, centered) vs max value (smaller, red, at 87.5%)
