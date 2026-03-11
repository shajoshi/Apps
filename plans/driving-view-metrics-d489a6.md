# Driving View — Signed RMS + Lean Angle in App

Add 5 new driver metrics (signed fwdRms, signed latRms, lean angle, fwdMean, latMean) to `computeAccelMetrics`, propagate through data model and JSON export, and create a "Driving View" full-screen dialog with a visual gauge UI.

## Part 1: Computation (Backend)

### 1a. `AccelMetrics.kt` — Add 3 new fields
- `signedFwdRms: Float` — `copysign(fwdRms, fwdMean)` — positive = accelerating, negative = braking
- `signedLatRms: Float` — `copysign(latRms, latMean)` — positive = right, negative = left
- `leanAngleDeg: Float` — motorcycle lean angle from per-window gravity vs baseline gravity, projected onto lateral axis (atan2 method from `raw_xy_decompose.py`)

### 1b. `TrackingService.kt` — `computeAccelMetrics()`
After existing fwd/lat metric computation (~line 407), add:

```kotlin
// Signed RMS: apply sign of mean to indicate dominant direction
val signedFwdRms = if (fwdMean != 0f) kotlin.math.copySign(fwdRms, fwdMean) else fwdRms
val signedLatRms = if (latMean != 0f) kotlin.math.copySign(latRms, latMean) else latRms

// Lean angle: rotation of per-window gravity away from baseline, in lateral plane
val leanAngleDeg = run {
    if (biasNorm > 1e-3f && useLat != null && useG != null) {
        val wgX = biasX / biasNorm
        val wgY = biasY / biasNorm
        val wgZ = biasZ / biasNorm
        val latComp = wgX * useLat[0] + wgY * useLat[1] + wgZ * useLat[2]
        val vertComp = wgX * useG[0] + wgY * useG[1] + wgZ * useG[2]
        Math.toDegrees(kotlin.math.atan2(latComp.toDouble(), vertComp.toDouble())).toFloat()
    } else 0f
}
```

Note: `biasX/Y/Z` and `biasNorm` are already computed earlier in the function. `useG` = `gUnitBasis` (baseline gravity from recording start), `useLat` = `latUnit`. This mirrors the Python logic exactly.

Pass new fields to `AccelMetrics(...)` constructor.

### 1c. `TrackingSample` — Add 3 new fields
- `accelSignedFwdRms: Float?`
- `accelSignedLatRms: Float?`
- `accelLeanAngleDeg: Float?`

### 1d. `TrackingService.onCreate` — Wire new fields into `TrackingSample` construction (~line 148)
```kotlin
accelSignedFwdRms = accelMetrics?.signedFwdRms,
accelSignedLatRms = accelMetrics?.signedLatRms,
accelLeanAngleDeg = accelMetrics?.leanAngleDeg,
```

### 1e. `JsonWriter.kt` — Export new fields (after latMax block, ~line 218)
```kotlin
sample.accelSignedFwdRms?.let { writer.write(",\n          \"signedFwdRms\": ${...}") }
sample.accelSignedLatRms?.let { writer.write(",\n          \"signedLatRms\": ${...}") }
sample.accelLeanAngleDeg?.let { writer.write(",\n          \"leanAngleDeg\": ${...}") }
```

## Part 2: Driving View UI

### 2a. New file: `DrivingViewDialog.kt` in `ui/` package

A full-screen dialog composable showing a visual driving gauge, matching the hand-drawn schematic:

```
┌──────────────────────────────┐
│      Accel: X.X m/s²         │  ← fwdRms value, top center
│         ↑↑↑                  │  ← arrows pointing UP, count = magnitude
│                              │
│   ←←  ○ lean°  →→ m/s²      │  ← circle with tilt pointer at lean angle
│  m/s²                        │    left/right arrows = latRms magnitude
│                              │
│         ↓↓                   │  ← arrows pointing DOWN when braking
│      Brake: X.X m/s²         │  ← negative fwdRms value, bottom center
└──────────────────────────────┘
```

**Visual elements:**
- **Center circle** with a **tilt pointer line** rotated by `leanAngleDeg` — drawn with `Canvas` composable
- **Chevrons** (up to 3 on each side): 1 chevron per ~1.5 m/s² (so 3 chevrons ≈ 4.5+ m/s²)
  - **Up chevrons** (above circle): **green**, shown when `signedFwdRms > 0` (accelerating)
  - **Down chevrons** (below circle): **red**, shown when `signedFwdRms < 0` (braking)
  - **Right chevrons** (right of circle): **yellow**, shown when `signedLatRms > 0`
  - **Left chevrons** (left of circle): **yellow**, shown when `signedLatRms < 0`
- **Numeric labels**: "Accel: X.X m/s²" at top, "Brake: X.X m/s²" at bottom, lateral values on sides
- **Lean angle text** inside or below the circle

**Data source:** Reads `TrackingState.latestSample` flow — same pattern as `TrackingScreen`.

### 2b. `TrackingScreen.kt` — Add "Driving View" button
Above the Start button row (~line 369), add:

```kotlin
Button(onClick = { showDrivingView = true }) {
    Text("Driving View")
}
```

With state: `var showDrivingView by remember { mutableStateOf(false) }`

And render the dialog:
```kotlin
if (showDrivingView) {
    DrivingViewDialog(onDismiss = { showDrivingView = false })
}
```

## Files Modified

| File | Change |
|------|--------|
| `AccelMetrics.kt` | +3 fields |
| `TrackingService.kt` | +15 lines in `computeAccelMetrics()`, +3 lines in sample construction |
| `TrackingState.kt` | +3 fields on `TrackingSample` |
| `JsonWriter.kt` | +9 lines for export |
| `TrackingScreen.kt` | +button + dialog toggle state |
| `DrivingViewDialog.kt` | **New file** — ~200 lines, Canvas-based gauge UI |
