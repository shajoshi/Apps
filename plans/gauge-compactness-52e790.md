# Gauge Compactness & Readability Improvements

Redesign all 5 gauge views to maximise info density — large value, small superscript unit, compact name — following the reference app patterns from w1/w2.

---

## What the reference apps do well

| Pattern | Detail |
|---|---|
| **Name above value, tiny** | Small label in top-left/center, not a banner |
| **Value is the dominant element** | Takes 50–60% of widget area |
| **Unit inline or superscript** | Never isolated on its own line |
| **No wasted padding** | Minimal top/bottom margins |
| **Compact "pill" numerics** | Small widgets (e.g. "Intake 54°C") use ~40×60dp effectively |

---

## Current problems per widget

### SevenSegmentView *(partially fixed)*
- ✅ Unit already moved to superscript (previous session)
- Name still takes up too much vertical space relative to digits
- Ghost layer alpha formula produces visible off-color tint

### NumericDisplayView
- Name + unit each on separate lines below value = 30%+ of height wasted at bottom
- Value size is capped at `minOf(w,h)*0.42f` — too conservative for wide widgets
- Trend arrow overlaps value on narrow widgets

### DialView
- Metric name and unit together take 25%+ of radius below the needle pivot
- Tick labels are large (`radius*0.12f`) — crowd the scale at small sizes
- Value readout only `radius*0.26f` — too small relative to arc size

### BarGaugeView
- Name + unit concatenated in one string at bottom — not wrapped, gets clipped
- Horizontal bar: value overlaid on the bar is hard to read when bar is short
- Vertical bar: value centred in track but `0.14f` of min dimension — tiny

### TemperatureGaugeView
- Arc `cy` fixed at `height*0.62f` — leaves large dead area below arc
- Metric name + unit drawn below, eating into the already-small lower area
- Tick labels `radius*0.14f` — too large for small widget sizes

---

## Proposed changes per widget

### 1. NumericDisplayView
- **Value**: increase to `minOf(w,h)*0.48f`, shift baseline to `cy - 5%h`
- **Unit**: superscript top-right of value block (~30% of value size), aligned `LEFT` from right edge of value text
- **Name**: single line, `8% h`, centered, placed `nameSize*1.4f` below value baseline, 70% opacity
- **Trend arrow**: move to top-left corner, small (10% of min dim), never overlaps value

### 2. SevenSegmentView *(refine)*
- Reduce `nameSize` from `8%h` to `7%h`; tighten spacing to `nameSize*1.3f`
- Fix ghost alpha: use `colorScheme.accent and 0x00FFFFFF or 0x1A000000` (10% opacity)
- Ensure name is clipped if too long (add `Paint.measureText` guard to ellipsize)

### 3. DialView
- **Tick labels**: reduce to `radius*0.10f` (from `0.12f`)
- **Value readout**: increase to `radius*0.32f` (from `0.26f`)
- **Unit**: move next to value as inline superscript at `radius*0.25f` size
- **Name**: reduce to `radius*0.09f`, move slightly closer under value
- **Needle**: no change (already good)

### 4. BarGaugeView — Horizontal
- **Value + unit**: draw value large (`trackHeight*0.55f`) centred in track; unit superscript top-right of value
- **Name**: small (`8% minDim`), top-left of widget (not bottom), 70% opacity
- **Ticks**: halve minor tick density for small sizes

### 4b. BarGaugeView — Vertical
- **Value**: centred in track, size `trackWidth*0.45f`
- **Name**: small horizontal text at top-center (✅ confirmed)
- **Unit**: superscript above value

### 5. TemperatureGaugeView
- Shift `cy` up to `height*0.55f` (from `0.62f`) to reclaim space below
- **Value readout**: increase to `radius*0.36f` (from `0.32f`)
- **Unit**: superscript immediately right of value (not separate line)
- **Name**: `radius*0.10f`, single line below value, 70% opacity

---

## Shared pattern applied to all

```
┌──────────────────────────┐
│ name (small, 70% opacity)│  ← top-left or top-center, 7-8% height
│                          │
│      VALUE   ᵘⁿⁱᵗ       │  ← dominant, unit as superscript
│                          │
│  [gauge arc / bar / seg] │
└──────────────────────────┘
```

- Unit always superscript (35% of value text size), raised by ~65% of value cap-height
- Name always ≤ 8% of widget height, 70% opacity, not bold
- No element on its own isolated line if it can be attached to another

---

## Files to edit

| File | Change scope |
|---|---|
| `NumericDisplayView.kt` | Value size, unit superscript, name below, trend arrow repositioned |
| `SevenSegmentView.kt` | Refine name size/spacing, fix ghost alpha |
| `DialView.kt` | Tick labels smaller, value larger, unit superscript |
| `BarGaugeView.kt` | Name top-left, value+unit in bar, both orientations |
| `TemperatureGaugeView.kt` | cy shift up, value larger, unit superscript, name compact |

No changes to `DashboardGaugeView.kt`, layout XMLs, or ViewModel.
