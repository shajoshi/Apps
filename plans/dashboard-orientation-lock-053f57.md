# Dashboard Orientation Lock

Add an `orientation` field to `DashboardLayout` so each dashboard is locked to PORTRAIT or LANDSCAPE, determined at creation time from the device's current screen size; block opening in the wrong orientation with a toast.

---

## Changes

### 1. `DashboardLayout.kt` — add `orientation` field

```kotlin
enum class DashboardOrientation { PORTRAIT, LANDSCAPE }

data class DashboardLayout(
    val name: String,
    val colorScheme: ColorScheme,
    val widgets: List<DashboardWidget>,
    val orientation: DashboardOrientation = DashboardOrientation.PORTRAIT  // default for legacy
)
```

- Gson deserialises old saved files without the field → `orientation` defaults to `PORTRAIT` (backwards-compatible).

---

### 2. `DashboardEditorFragment.kt` — detect orientation at creation time

When `isNewLayout = true`, after `setupCanvasSize` fires and `canvasGridW`/`canvasGridH` are known, set the orientation on the ViewModel:

```kotlin
if (isNewLayout) {
    val orient = if (canvasGridW >= canvasGridH) DashboardOrientation.LANDSCAPE
                 else DashboardOrientation.PORTRAIT
    viewModel.setOrientation(orient)
}
```

Also guard `onScrollViewLayout` to **not** call `clampWidgetsToBounds` during orientation mismatch (irrelevant — wrong orientation is blocked before reaching the editor).

---

### 3. `DashboardEditorViewModel.kt` — add `setOrientation`

```kotlin
fun setOrientation(orient: DashboardOrientation) {
    _currentLayout.value = _currentLayout.value.copy(orientation = orient)
}
```

---

### 4. `LayoutListFragment.kt` — block open/edit in wrong orientation

Helper:
```kotlin
private fun currentOrientation(): DashboardOrientation {
    val cfg = resources.configuration
    return if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE)
        DashboardOrientation.LANDSCAPE else DashboardOrientation.PORTRAIT
}
```

In `openDashboard(name, mode)` — check before navigating:
```kotlin
private fun openDashboard(name: String, mode: String) {
    val layout = repo.getSavedLayouts().find { it.name == name } ?: return
    if (layout.orientation != currentOrientation()) {
        val required = layout.orientation.name.lowercase().replaceFirstChar { it.uppercase() }
        Toast.makeText(requireContext(),
            "\"$name\" requires $required mode. Please rotate your device.",
            Toast.LENGTH_LONG).show()
        return   // stay on list
    }
    val bundle = Bundle().apply {
        putString("layout_name", name)
        putString("mode", mode)
    }
    findNavController().navigate(R.id.action_layoutList_to_editor, bundle)
}
```

Also apply the same guard to the double-tap shortcut path.

---

### 5. `LayoutsAdapter` — show orientation badge in meta text

```
5 widgets  ·  Landscape  ·  16 Mar 2026
```

Update `holder.txtMeta.text` in `onBindViewHolder` to append the orientation.

---

### 6. `LayoutListFragment` — orientation indicator on `onResume`

On `onResume`, call `loadLayouts()` (already done) so the list always reflects the current state when user rotates and returns.

---

## Files Changed

| File | Change |
|------|--------|
| `model/DashboardLayout.kt` | Add `DashboardOrientation` enum + `orientation` field with default |
| `DashboardEditorViewModel.kt` | Add `setOrientation()` |
| `DashboardEditorFragment.kt` | Detect & set orientation when `isNewLayout` |
| `LayoutListFragment.kt` | Guard `openDashboard`, add orientation badge in meta |

---

## Backwards Compatibility

- Existing saved JSON files have no `orientation` field → Gson gives `null` → Kotlin default `PORTRAIT` applies.
- Users can see the orientation badge in the list and know which dashboards work in which mode.
- No migration step needed.
