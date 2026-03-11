# Dashboard Editor UX Fixes

Four usability improvements to the dashboard canvas editor and Add Widget wizard.

---

## Bug 1 — Blank canvas tap deselects widget

**Problem:** `WidgetTouchHandler` selects a widget on `ACTION_DOWN`, but tapping blank canvas has no handler — so selection is never cleared.

**Fix:**
- In `DashboardEditorFragment.renderCanvas()`, add a touch listener to `canvasContainer` itself (when in edit mode) that calls `viewModel.selectWidget(null)` on a tap.
- Must only fire when no child widget captured the event — use `ACTION_UP` on the container combined with a flag so child touches that return `true` don't bubble.
- `selectWidget(null)` already closes the properties panel and clears selection; `renderCanvas` is re-called via `currentLayout` collect, which will re-render all widgets with no selection border.

---

## Bug 2 — Selected widget: Delete + Change data source actions

**Problem:** The properties panel (`panel_properties`) only shows Bring Front / Send Back / Delete / Alpha slider. There is no "Change Metric" option for a selected widget.

**Fix:**
- Add a **"Change Source"** button to `panel_properties` in `fragment_dashboard_editor.xml`.
- Wire it in `setupPropertyPanel()`: opens the **existing** `AddWidgetWizardSheet` but in a "re-source" mode — Step 2 only (skip Step 1 since type is fixed), pre-selecting the current metric.
- On wizard commit in re-source mode: call `viewModel.updateWidgetMetric(widgetId, newMetric)` — a new ViewModel method that updates only the `metric` field and re-applies `MetricDefaults` scale settings.
- Add `updateWidgetMetric()` to `DashboardEditorViewModel`.
- Delete is **already wired** (`btnDeleteWidget` → `viewModel.removeSelectedWidget()`); no change needed.

---

## Bug 3 — "Choose Style" (Step 1) dialog starts in middle; no scrollbar

**Problem:** `AddWidgetWizardSheet` is a `BottomSheetDialogFragment` — by default it starts at a peek height in the middle of the screen.

**Fix:**
- Override `onStart()` in `AddWidgetWizardSheet` to force full-screen expand:
  ```kotlin
  override fun onStart() {
      super.onStart()
      dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
          BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
      }
  }
  ```
- In `page_wizard_step1.xml`: add `android:scrollbars="vertical"` to the `RecyclerView` (`rv_widget_types`) and set `android:scrollbarStyle="insideOverlay"`.  
- Same scrollbar fix for `page_wizard_step2.xml` (`rv_metrics`) since it has same issue.

---

## Bug 4 — Step 3 Configure fields are generic; not widget-type-specific

**Problem:** `Step3ConfigPage` always shows all fields (rangeMin/Max, ticks, warning, decimals, unit) regardless of widget type. Some controls are irrelevant:
- `SEVEN_SEGMENT` / `NUMERIC_DISPLAY`: ticks and warning threshold are meaningless.
- `BAR_GAUGE_H` / `BAR_GAUGE_V`: minor tick count is irrelevant.
- `TEMPERATURE_ARC`: all fields useful but warning threshold especially relevant.

**Fix — visibility rules based on `state.selectedType`:**

| Widget Type | Hide |
|---|---|
| `SEVEN_SEGMENT`, `NUMERIC_DISPLAY` | Major tick interval, Minor tick count, Warning threshold |
| `BAR_GAUGE_H`, `BAR_GAUGE_V` | Minor tick count |
| `DIAL`, `TEMPERATURE_ARC` | (show all) |

- In `Step3ConfigPage.onViewCreated()`, read `state.selectedType` and `GONE`/`VISIBLE` the relevant field rows (label + EditText pairs wrapped in a container each).
- In `page_wizard_step3.xml`: wrap each field pair in a `LinearLayout` with an id (e.g. `row_major_tick`, `row_minor_ticks`, `row_warning`) so they can be toggled as a unit.

---

## Files to change

| File | Change |
|---|---|
| `DashboardEditorFragment.kt` | Blank-tap deselect on canvas; wire "Change Source" button |
| `DashboardEditorViewModel.kt` | Add `updateWidgetMetric()` |
| `fragment_dashboard_editor.xml` | Add "Change Source" button to `panel_properties` |
| `AddWidgetWizardSheet.kt` | Force full-expand in `onStart()`; support optional re-source mode (start at step 2, pass current widgetId) |
| `WizardState.kt` | Add `resourceWidgetId: String? = null` field |
| `Step3ConfigPage.kt` | Hide irrelevant fields based on `state.selectedType` |
| `page_wizard_step1.xml` | Add vertical scrollbar to RecyclerView |
| `page_wizard_step2.xml` | Add vertical scrollbar to RecyclerView |
| `page_wizard_step3.xml` | Wrap field pairs in rows with IDs |
