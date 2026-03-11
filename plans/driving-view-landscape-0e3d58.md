# Driving View Responsive Layout

Make DrivingViewDialog fully responsive to orientation, screen size, multi-window, and foldables using Android best practices (`BoxWithConstraints`, constraint-based layout decisions, state hoisting).

## Android Best Practices Applied

Per [official Android guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes):

1. **`BoxWithConstraints` for layout decisions** — uses actual space given to the composable, not physical screen size. Correct for a Dialog which may not be full-screen.
2. **No device-type logic** — no `isTablet`, no physical screen queries. Decisions based solely on available `maxWidth` / `maxHeight` constraints.
3. **All data always available** — every metric is always passed to sub-composables; nothing conditionally loaded based on size.
4. **Layout decisions passed as state** — compute `isWide` boolean and `scaleFactor` once at the top, pass down as parameters.
5. **Reusable nested composables** — extract gauge and info panels as helper composables that accept sizing params, making them reusable and testable.
6. **Handles runtime changes** — `BoxWithConstraints` automatically recomposes when constraints change (rotation, multi-window resize, fold/unfold).

## Current Layout (Portrait — single vertical Column)

| # | Component | Sizing |
|---|-----------|--------|
| 1 | Control bar (Test, Back, ▶ ⏸ ⏹) | fixed ~48dp |
| 2 | Speed + Altitude text | `headlineMedium` (~28sp) |
| 3 | **Gauge + Chevrons** (Box) | `weight(1f)` |
| 4 | Status row (quality · feature · event) | fixed ~20dp |
| 5 | Events / Time / Smooth row | fixed ~50dp |
| 6 | Z RMS / Z Peak / StdDev Z row | fixed ~50dp |

**Problem:** In landscape/wide mode (~400dp height), the gauge gets crushed to ~220dp.

## Approach: Constraint-Based Layout + Continuous Scaling

### Layout Decision (at Dialog root)

```kotlin
BoxWithConstraints(modifier = Modifier.fillMaxSize().background(BgDark)) {
    val isWide = maxWidth > maxHeight  // wide = landscape or wide multi-window
    val shortSide = minOf(maxWidth, maxHeight)
    val scaleFactor = (shortSide / 400.dp).coerceIn(0.7f, 1.3f)
    
    if (isWide) {
        WideLayout(scaleFactor, ...)   // two-column
    } else {
        TallLayout(scaleFactor, ...)   // current single-column
    }
}
```

### Tall Layout (portrait / narrow window — current, with scaling)
Same vertical Column. Apply `scaleFactor` to font sizes for small/large phones.

### Wide Layout (landscape / wide window — new two-column)

```
┌─────────────────────────────────────────────────┐
│ Control bar (Test, Back, ▶ ⏸ ⏹)               │
├──────────────────────┬──────────────────────────┤
│                      │  Speed · Altitude        │
│                      │  Quality · Feature · Evt │
│   Gauge + Chevrons   │  Events · Time · Smooth  │
│   (weight 0.55)      │  Z RMS · Z Peak · StdDev │
│                      │         (weight 0.45)    │
└──────────────────────┴──────────────────────────┘
```

### Scaled Dimensions

| Element | Tall base | Wide base | × scaleFactor |
|---------|----------|-----------|---------------|
| Speed/alt font | 28.sp | 22.sp | yes |
| Chevron text | 28.sp | 20.sp | yes |
| Chevron stroke | 8f | 6f | yes |
| MetricLabel value | 22.sp | 16.sp | yes |
| MetricLabel label | 16.sp | 12.sp | yes |
| Status row text | 16.sp | 13.sp | yes |
| Gauge `scale` | 0.75f | 0.90f | no |
| Spacers | 8.dp | 4.dp | no |

## Implementation Steps

### Step 1: Wrap Dialog content in `BoxWithConstraints`
- Replace outer `Column(Modifier.fillMaxSize())` with `BoxWithConstraints`
- Compute `isWide` and `scaleFactor` from constraints
- No new dependencies needed — `BoxWithConstraints` is in `androidx.compose.foundation.layout`

### Step 2: Extract helper composables
- **`ControlBar(...)`** — Test, Back, Play/Pause/Stop (shared by both layouts)
- **`GaugePanel(scaleFactor, gaugeScale, ...)`** — gauge Box + chevrons Canvas
- **`InfoPanel(scaleFactor, ...)`** — speed/altitude, status row, metric rows
- All accept sizing params; no internal size queries

### Step 3: Tall layout (ColumnScope)
- Control bar → speed/alt → GaugePanel(weight=1f) → status → metrics
- Same as current, but font sizes multiplied by `scaleFactor`

### Step 4: Wide layout (Column + Row)
- Control bar full-width on top
- Row below: GaugePanel(weight=0.55f) | InfoPanel(weight=0.45f)
- InfoPanel stacks speed/alt, status, metrics vertically with reduced fonts

### Step 5: Scale all hardcoded sizes
- Chevron canvas: `28.sp * scaleFactor`, dp offsets scaled
- MetricLabel: add `valueFontSize` and `labelFontSize` params
- Status/event text: scaled

### Step 6: SemiCircularGauge.kt — No changes
Already adapts to canvas size via `minOf(canvasW/2, canvasH*0.75)`.

## Files Changed

| File | Scope |
|------|-------|
| `DrivingViewDialog.kt` | **Major** — `BoxWithConstraints` wrapper, extract 3 helper composables, scale sizes |
| `SemiCircularGauge.kt` | **None** — already responsive |
| `build.gradle.kts` / `libs.versions.toml` | **None** — no new dependencies |
| All other files | **None** |
