# OBD2 Dashboard — Reusable Widget Library & Improved Add-Widget UX

Single-sentence summary: Decouple widget views from hardcoded metrics, introduce per-widget gauge range/tick settings, improve visuals, and replace the 2-spinner "Add Widget" dialog with a multi-step wizard that lets the user pick a widget style, bind a metric, position it, and configure its scale in one flow.

---

## Current State (problems identified)

| Area | Problem |
|---|---|
| **Widget coupling** | `RevCounterView` has `maxRpm = 8000f` hard-coded; `IfcBarView` hard-codes `maxValue = 30f`; `FuelBarView` assumes 0–100%. None accept user-configured min/max/ticks. |
| **Metric list** | `showAddWidgetDialog()` has only 5 hard-coded metrics. All 21 OBD PIDs + GPS sources should be available and filtered to *only what the ECU supports*. |
| **Add dialog UX** | A flat 2-spinner `AlertDialog` — no preview, no scale config, no size picker. |
| **Visual quality** | `RevCounterView` tick labels are plain integers (0–8); no major/minor ticks drawn. `SevenSegmentSpeedometerView` uses Typeface.MONOSPACE instead of a real digital font. `FuelBarView`/`IfcBarView` have no tick marks. Needle pivot circle leaks fill colour. No value animation. |
| **`DashboardWidget` model** | Stores no gauge-range info (`min`, `max`, `tickInterval`, `warningThreshold`). |
| **Editor live data** | `DashboardEditorFragment.renderCanvas()` still uses hard-coded demo values per `WidgetType` instead of routing through `Obd2ServiceProvider`. |

---

## Part 1 — Reusable, Metric-Agnostic Widget Library

### 1.1 Extend `DashboardWidget` model
Add range and unit fields so each widget instance carries its own scale and display unit:

```kotlin
data class DashboardWidget(
    ...
    var rangeMin: Float = 0f,
    var rangeMax: Float = 100f,
    var majorTickInterval: Float = 10f,   // e.g. every 1000 rpm
    var minorTickCount: Int = 4,           // subdivisions between major ticks
    var warningThreshold: Float? = null,   // e.g. 6000 rpm → red zone
    var decimalPlaces: Int = 1,
    var displayUnit: String = ""           // overrides metric default unit if set
)
```

### 1.1a Unit configurability
- Each `DashboardMetric` definition in `MetricDefaults` declares a **canonical unit** (e.g. `"km/h"`, `"rpm"`, `"°C"`, `"kPa"`).
- When the user selects a metric in the wizard, `displayUnit` is **auto-populated** from `MetricDefaults` — they never have to type it.
- In Step 3 of the wizard, the unit field is **editable** as a free-text field so the user can override (e.g. change `"kPa"` to `"psi"`, `"°C"` to `"°F"`, or add context like `"L/h"` → `"kmpl"`).
- The view layer renders whatever string is in `displayUnit`; no conversion logic is applied (display-only label).
- `DashboardGaugeView.metricUnit` is driven from `widget.displayUnit` at render time.

### 1.2 Per-metric defaults table
Add a `MetricDefaults` object (new file) that maps each PID / GPS metric to sensible defaults:

| Metric | min | max | majorTick | minorTicks | warning |
|---|---|---|---|---|---|
| Engine RPM | 0 | 8000 | 1000 | 4 | 6000 |
| Vehicle Speed | 0 | 220 | 20 | 4 | 180 |
| Coolant Temp | -40 | 130 | 20 | 4 | 100 |
| Throttle / Engine Load / Fuel Level | 0 | 100 | 25 | 4 | — |
| Timing Advance | -64 | 64 | 16 | 4 | — |
| Intake Air Temp / Ambient / Oil Temp | -40 | 130 | 20 | 4 | 100 |
| MAF Air Flow | 0 | 655 | 100 | 4 | — |
| Fuel Pressure | 0 | 765 | 100 | 4 | — |
| Intake MAP | 0 | 255 | 50 | 4 | — |
| O2 Voltage | 0 | 1.275 | 0.25 | 4 | — |
| Control Module Voltage | 10 | 16 | 1 | 4 | 14.5 |
| Engine Fuel Rate | 0 | 3276 | 500 | 4 | — |
| GPS Speed | 0 | 220 | 20 | 4 | — |
| GPS Altitude | -500 | 8848 | 500 | 4 | — |

Pre-populating these when the user selects a metric in the wizard means they never have to touch scale settings unless they want to customise.

### 1.3 Update `DashboardGaugeView` base class
Replace fixed internal constants with properties the host passes in:

```kotlin
abstract class DashboardGaugeView : View {
    var rangeMin: Float = 0f
    var rangeMax: Float = 100f
    var majorTickInterval: Float = 10f
    var minorTickCount: Int = 4
    var warningThreshold: Float? = null
}
```

All six concrete views (`DialView`, `SevenSegmentView`, `BarGaugeView`, `NumericDisplayView`, `TemperatureGaugeView`, and the retired `IfcBarView`) read these fields instead of internal constants.

### 1.4 Visual improvements per widget

**`DialView`** *(renamed from `RevCounterView`)*
- Draw **major tick lines** at every `majorTickInterval` (with numeric labels) and **minor tick lines** (shorter, dimmer) in between.
- Draw a **red arc** from `warningThreshold` to `rangeMax`.
- Animate needle with `ValueAnimator` (200 ms, `DecelerateInterpolator`).
- Fix pivot circle: use accent colour (not `arcPaint` which leaks fill).
- Label text auto-scales to `radius * 0.1f`.

**`SevenSegmentView`** *(renamed from `SevenSegmentSpeedometerView`)*
- Bundle `digital-7.ttf` in `assets/fonts/` and load it via `Typeface.createFromAsset()`.
- Add a **dim "ghost" layer** behind the digits (draw "888" in `surface` colour at 30% opacity first — true segment display effect).
- Add an animated sweep when value changes.

**FuelBarView** → refactored into a generic **`BarGaugeView`**
- Horizontal or vertical mode (attribute / property flag).
- Draw tick marks along the bar at `majorTickInterval` steps.
- Warning zone coloured from 0 to `warningThreshold` (or reversed, configurable).
- Segment glow effect (painted gradient, not flat fill).

**IfcBarView** → removed; replaced by `BarGaugeView` bound to "Engine Fuel Rate" PID.

**NumericDisplayView**
- Add optional **trend arrow** (↑ / ↓ / —) comparing last two readings.
- Coloured value text when above `warningThreshold`.
- Respect `decimalPlaces` from `DashboardWidget`.

**New widget: `TemperatureGaugeView`** (arc gauge, not circular — like a thermometer sweep, 180° arc)
- Useful for Coolant Temp, Intake Air Temp, Oil Temp.
- Colour zones: blue (cold), green (normal), red (hot).

### 1.5 `DashboardEditorFragment.renderCanvas()` — wire live data
Replace hard-coded demo values with actual `Obd2ServiceProvider` / `GpsDataSource` `StateFlow` collection per widget, keyed by `DashboardMetric`.

---

## Part 2 — Improved "Add Widget" UX (Multi-step Wizard)

Replace the `AlertDialog` with a **`BottomSheetDialogFragment`** (`AddWidgetWizardSheet`) with **3 steps** navigated by a horizontal `ViewPager2`:

### Step 1 — Pick Visual Style
- Horizontal scroll of **widget preview cards** (not a spinner), each showing a mini live-rendered thumbnail of the widget type with its colour scheme applied.
- Widget types: Rev Counter (circular dial), Digital Speed (7-seg), Bar Gauge (horizontal), Bar Gauge (vertical), Numeric, Temperature Arc.

### Step 2 — Bind a Metric
- **Grouped list** of all available metrics:
  - *OBD-II — Engine*: RPM, Load, Throttle, Timing Advance, Oil Temp
  - *OBD-II — Fuel*: Tank Level, Fuel Pressure, Fuel Rate, Short/Long trim
  - *OBD-II — Air/Temp*: Coolant Temp, Intake Air, Intake MAP, MAF, Ambient, Barometric
  - *OBD-II — Electrical*: Module Voltage, O2 Sensor
  - *OBD-II — Distance*: Distance MIL, Distance Cleared, Run Time
  - *GPS*: Speed, Altitude (MSL)
- Metrics the ECU reported as unsupported are shown greyed out (but still selectable for demo/preview).
- Tapping a metric auto-populates Step 3 with the defaults from `MetricDefaults`.

### Step 3 — Configure Scale & Place
Divided into two sections:

**Scale settings** (auto-filled from `MetricDefaults`, fully editable):
- Min / Max value fields
- Major tick interval
- Minor tick subdivisions
- Warning threshold (optional)
- Decimal places

**Placement** (shown as a mini grid thumbnail):
- Size presets: Small (2×2), Medium (4×4), Large (6×6), Wide (6×3), Tall (3×6)
- Initial position: Auto (finds first free slot) or Manual (user drags after placing)

**"Add to Dashboard"** button → places widget at chosen grid slot, auto-selects it so the user can immediately fine-position with drag.

---

## Implementation Plan

### Phase A — Model changes (no visible breakage)
1. Extend `DashboardWidget` with range/tick fields (default values keep existing layouts valid).
2. Add `MetricDefaults.kt` — static map from metric to default range config.
3. Update `DashboardMetricAdapter` (Gson) to serialize new fields.

### Phase B — View library refactor
4. Update `DashboardGaugeView` base class (add range properties, remove internal constants from subclasses).
5. Refactor `RevCounterView` — tick marks, warning arc, value animation.
6. Refactor `SevenSegmentSpeedometerView` — ghost segments, digital font.
7. Refactor `FuelBarView` + `IfcBarView` → merge into generic `BarGaugeView` with orientation flag.
8. Improve `NumericDisplayView` — trend arrow, threshold colouring.
9. Add new `TemperatureGaugeView`.
10. Update `WidgetType` enum: rename `REV_COUNTER` → `DIAL`, `SPEEDOMETER_7SEG` → `SEVEN_SEGMENT`; add `BAR_GAUGE_H`, `BAR_GAUGE_V`, `TEMPERATURE_ARC`; deprecate `IFC_BAR`.

### Phase C — Editor live data wiring
11. In `DashboardEditorFragment.renderCanvas()` — collect `StateFlow` from `Obd2ServiceProvider` and `GpsDataSource` per widget, call `view.setValue()` on each update.
12. Update `DashboardEditorViewModel.addWidget()` to auto-apply `MetricDefaults`.
13. Update `DashboardEditorViewModel` to expose `updateWidgetRangeSettings()`.

### Phase D — Add Widget Wizard
14. Create `AddWidgetWizardSheet : BottomSheetDialogFragment` + `ViewPager2` with 3 page fragments.
15. Create `Step1WidgetTypePage` — horizontal RecyclerView of widget preview cards.
16. Create `Step2MetricPage` — grouped `ExpandableListView` / `RecyclerView` with PID categories.
17. Create `Step3ConfigPage` — scale form + size preset selector.
18. Wire wizard result back to `DashboardEditorViewModel.addWidget()`.
19. Update `dialog_add_widget.xml` → remove; add new layout files for wizard.

### Phase E — Polish
20. Add `ValueAnimator` to `setValue()` in base `DashboardGaugeView`.
21. Bundle `digital-7.ttf` in `assets/fonts/`.
22. Regression test: existing saved JSON layouts deserialize correctly with new nullable fields.

---

## Files Affected

| File | Change |
|---|---|
| `model/DashboardWidget.kt` | Add range/tick/unit fields |
| `model/WidgetType.kt` | Add new types, rename `REV_COUNTER` → `DIAL`, `SPEEDOMETER_7SEG` → `SEVEN_SEGMENT` |
| `model/MetricDefaults.kt` | **New** |
| `data/LayoutRepository.kt` | Gson adapter update |
| `views/DashboardGaugeView.kt` | Add range properties |
| `views/DialView.kt` | Rename from `RevCounterView`, ticks, warning arc, animation |
| `views/SevenSegmentView.kt` | Rename from `SevenSegmentSpeedometerView`, ghost segments, digital font |
| `views/BarGaugeView.kt` | Rename from `FuelBarView`, orientation |
| `views/IfcBarView.kt` | Delete (merge into BarGaugeView) |
| `views/NumericDisplayView.kt` | Trend arrow, threshold colour |
| `views/TemperatureGaugeView.kt` | **New** |
| `DashboardEditorFragment.kt` | Live data wiring, wizard call |
| `DashboardEditorViewModel.kt` | Range update fn, MetricDefaults |
| `ui/dashboard/wizard/` | **New package** — 4 new files |
| `res/layout/dialog_add_widget.xml` | Remove / replace |
| `assets/fonts/digital-7.ttf` | **New** — open-source digital font |

---

## Resolved Decisions

| Question | Decision |
|---|---|
| Units | **km/h** as default; each widget carries `displayUnit` auto-filled from `MetricDefaults`, overridable per-widget in the wizard |
| Wizard placement | **Auto-place then drag** — Step 3 places widget at first free slot; user drags to final position |
| Temperature widget | **New `TemperatureGaugeView`** (180° arc with colour zones) |
| View naming | `RevCounterView` → **`DialView`**; `SevenSegmentSpeedometerView` → **`SevenSegmentView`** |
| Digital font | **`digital-7.ttf`** bundled in `assets/fonts/` |
