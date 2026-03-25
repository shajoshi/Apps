# Improve Dashboard Widget Legibility, Colour Consistency & Compactness

Unify colour usage across all widget views via `colorScheme`, eliminate hardcoded colours, reduce wasted padding to maximize data density, and enlarge value text for at-a-glance reading.

---

## A. Colour Inconsistencies Found

| Location | Hardcoded Colour | Should Be |
|---|---|---|
| `DialView` value text | `0xFF2196F3` (Material Blue) | `colorScheme.accent` |
| `NumericDisplayView` value text | `0xFF2196F3` | `colorScheme.accent` |
| `BarGaugeView` value text | `0xFF2196F3` | `colorScheme.accent` |
| `BarGaugeView` fill colour | `0xFFFFFF00` (yellow) | `colorScheme.accent` |
| `SevenSegmentView` metric-name opacity | `0xBB` (73%) | `0xCC` (80%) — standardise with others |
| All other metric-name opacity | `0xB3` (70%) | `0xCC` (80%) — standardise |
| `DialView` minor-tick opacity | `0x88` | `0x77` — standardise with BarGaugeView |
| `BarGaugeView` tick opacity | `0x66` | `0x77` — standardise |

**Note:** `TemperatureGaugeView` cold/normal/hot zone colours (`0xFF2196F3`, `0xFF4CAF50`, `0xFFF44336`) are semantically fixed and should stay as-is.

## B. Spacing Waste Found

| Widget | Waste | Fix |
|---|---|---|
| `BarGaugeView` (H) | `labelH = height * 0.22f` for a tiny label above | Reduce to `0.15f`, use freed space for taller bar |
| `BarGaugeView` (H) | `trackT` formula leaves ~35% of height unused above bar | Shrink gap so bar starts closer to metric name |
| `BarGaugeView` | `pad = minDim * 0.03f` + extra `1.5f` multiplier on sides (V) | Use uniform `0.02f` pad |
| `DialView` | `radius = min(w,h)/2 * 0.88f` — 12% margin | Increase to `0.93f` |
| `SevenSegmentView` | `digitAreaH = height * 0.75f`, digits only fill 90% of that | Raise to `0.82f` with `0.95f` fill |
| `NumericDisplayView` | `nameBaseline = nameSize * 0.95f` top margin + `height - nameSize * 0.35f` bottom | Tighten both to `0.75f` / `0.20f` |
| `TemperatureGaugeView` | `radius = min(w/2, cy) * 0.92f` | Increase to `0.95f` |

---

## Plan

### 1. Colour consistency — remove all hardcoded colours
- **`DialView.kt`**: replace `0xFF2196F3.toInt()` → `colorScheme.accent` for value text.
- **`NumericDisplayView.kt`**: replace `0xFF2196F3.toInt()` → `colorScheme.accent` for value text.
- **`BarGaugeView.kt`**: replace `0xFF2196F3.toInt()` → `colorScheme.accent` for value text; replace `0xFFFFFF00.toInt()` → `colorScheme.accent` for bar fill.
- Standardise metric-name opacity to `0xCC` (80%) in all views.
- Standardise minor-tick/tick opacity to `0x77` in `DialView` and `BarGaugeView`.

**Files:** `DialView.kt`, `NumericDisplayView.kt`, `BarGaugeView.kt`, `SevenSegmentView.kt`, `TemperatureGaugeView.kt`

### 2. Add glow helper to base class
- In `DashboardGaugeView.kt`, add `drawTextWithGlow(canvas, text, x, y, paint)` — draws a blurred shadow behind value text for dark-background separation.
- All widget views call this for the **value** string only.

**File:** `DashboardGaugeView.kt`

### 3. Compact BarGaugeView — tighter layout, larger value
- **Horizontal mode:** reduce `labelH` from `0.22f` → `0.15f`; move `trackT` up to reclaim vertical space; increase value text from `trackRect.height() * 0.52f` → `0.70f`.
- **Vertical mode:** increase value text from `minDim * 0.16f` → `0.22f`.
- Reduce `pad` from `minDim * 0.03f` → `0.02f`; remove `1.5f` side multiplier.
- Increase metric-name from `0.12f` → `0.14f`; unit superscript from `0.38f` → `0.45f`.

**File:** `BarGaugeView.kt`

### 4. Compact SevenSegmentView — larger digits, less wasted height
- Increase `digitAreaH` from `height * 0.75f` → `0.82f`; sizing fill from `0.90f` → `0.95f`; width divisor from `0.60f` → `0.55f`.
- Reduce ghost opacity from `0x1A` → `0x0F`.
- Increase metric-name from `height * 0.07f` → `0.09f`.

**File:** `SevenSegmentView.kt`

### 5. Compact NumericDisplayView — tighter margins
- Reduce top margin (`nameBaseline`) from `nameSize * 0.95f` → `0.75f`.
- Reduce bottom margin from `height - nameSize * 0.35f` → `height - nameSize * 0.20f`.
- Bump value size from `minDim * 0.55f` → `0.60f`; unit from `0.30f` → `0.38f`.

**File:** `NumericDisplayView.kt`

### 6. Compact DialView — use more of the bounding rect
- Increase `radius` multiplier from `0.88f` → `0.93f`.
- Increase value font from `radius * 0.45f` → `0.52f`.
- Add a dark semi-transparent pill behind the value readout.
- Increase metric-name from `radius * 0.12f` → `0.15f`.

**File:** `DialView.kt`

### 7. Compact TemperatureGaugeView — fill more space
- Increase `radius` multiplier from `0.92f` → `0.95f`.
- Increase value size from `radius * 0.36f` → `0.44f`.
- Increase metric-name from `radius * 0.10f` → `0.14f`.

**File:** `TemperatureGaugeView.kt`

### 8. Replace `isFakeBoldText` with real bold typeface
- All value/digit paints: `typeface = Typeface.DEFAULT_BOLD` (keep digital-7 for `SevenSegmentView`).

**Files:** all 5 widget view files

---

## Summary

| File | Colour Fix | Compactness | Legibility |
|---|---|---|---|
| `DashboardGaugeView.kt` | — | — | Add `drawTextWithGlow()` helper |
| `BarGaugeView.kt` | Remove 2 hardcoded colours, standardise opacities | Tighter padding, smaller labelH, bar fills more space | Larger value & unit text |
| `SevenSegmentView.kt` | Standardise label opacity | Taller digit area, bigger fill ratio | Larger digits, dimmer ghost |
| `NumericDisplayView.kt` | Remove hardcoded blue | Tighter top/bottom margins | Larger value & unit |
| `DialView.kt` | Remove hardcoded blue, standardise tick opacity | Larger radius, less wasted margin | Larger value, dark pill, bigger label |
| `TemperatureGaugeView.kt` | Standardise label opacity | Larger radius | Larger value & label |

All files in `OBD2App/app/src/main/java/com/sj/obd2app/ui/dashboard/views/`.
