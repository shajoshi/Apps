# Lean Angle Gauge Redesign

Replace the current circle+chevron gauge in DrivingViewDialog with a motorcycle-style lean angle dial inspired by the reference image.

## Design

### Gauge Layout (Canvas)
- **Outer arc ring**: Semi-circular arc (roughly 180°, from 9 o'clock to 3 o'clock through top)
  - Left half: Blue gradient (left lean)
  - Right half: Orange gradient (right lean)
  - Arc thickness ~12dp, with colored fill proportional to current lean angle
- **Tick marks**: Small white ticks at regular intervals (e.g., every 5° or 10°) along the arc, with "L" and "R" labels at the ends
- **Needle**: Tapered needle from center pointing outward, rotated by lean angle from 12 o'clock
  - Orange/red gradient fill (like the reference image)
  - Small center hub circle
- **Center text**: Lean angle value (e.g., "35°") and "LEAN" label below it
- **0° and max labels**: "0°" at left end, max angle at right end (outside the arc)

### Chevrons (Keep Existing)
- Retain the up/down/left/right chevrons for fwd/lat RMS outside the gauge
- Keep absolute label positioning (already implemented)

### What Changes
1. Replace the simple gray circle + thin pointer with the styled arc gauge + tapered needle
2. Add colored arc segments (blue left, orange right) that fill based on lean angle
3. Add tick marks around the arc
4. Style the needle as a tapered triangle (wide at center, thin at tip) instead of a thin line
5. Add "L" / "R" labels at arc ends
6. Keep all other UI elements (speed, event icons, chevrons, metrics rows) unchanged

### Implementation Details
- All drawing in the existing `Canvas` block
- Replace `drawCircle` + `drawLeanPointer` with new arc + needle drawing
- Arc sweep: 180° centered at top (from -90° to +90° in standard canvas coords)
- Lean angle maps to needle rotation: 0° = straight up, positive = right, negative = left
- Max displayable lean: ±45° (configurable constant)
- Colored arc fill: draw a second arc from center (0°) to current lean angle
- Needle: `Path` forming a narrow triangle, rotated by lean angle

## Files Modified
- `DrivingViewDialog.kt` — Replace gauge drawing code, update `drawLeanPointer`, add arc drawing helpers
