# Fuel Accumulation / Display Split & Double Precision

Separates raw trip accumulation from display-ready metrics, removes the previous blocking thresholds from accumulation, gates fuel efficiency display at 0.5 km, and upgrades internal math to Double throughout.

---

## Background: How the current pipeline works

```
GPS (1 Hz, ~500ms min)  ──┐
OBD poll (per-PID cycle)  ─┤─► DataOrchestrator.combine() ──► debounce(100ms) ──► calculate() ──► VehicleMetrics ──► UI + Logger
```

**Current trigger:** `DataOrchestrator` uses `combine(obd2Data, gpsData)`, meaning a calculation fires whenever **either** source emits. With `debounce(100ms)` this roughly means "fire 100ms after the most recent OBD or GPS event".

**OBD polling:** Each PID is queried sequentially per cycle; a full cycle emits a new `obd2Data` list. Typical ELM327 cycle ≈ 200–500ms per PID, so a 10-PID config may take 2–5 seconds per full cycle. The `obd2Data` flow emits once per full cycle (not per PID).

**GPS polling:** Requested at 1s interval / 500ms min. Emits `GpsDataItem` on each fix.

**Quality impact:** Because `combine` fires on both, if GPS fires at 1Hz and OBD cycle is slower (e.g. 3s), most calculation ticks are GPS-only with stale OBD values. This is fine for distance accumulation (uses hybrid speed which prefers GPS > 20 km/h) but means dt between OBD-driven ticks can be large. **No change proposed to timing model** — the `debounce(100ms)` already handles burst batching well.

---

## Issues to Fix

### 1. Previous thresholds blocked accumulation — revert
In the last session, `tripAvgLper100km` and `tripAvgKpl` were gated behind `distKm > 0.1f && fuelUsedL > 0.01f`. These are **display** functions but they also gate **accumulation reading**. The actual `TripState` accumulation is already correct (no threshold). No change needed to `TripState.update()`.

### 2. Separate accumulation from display gating
- **`TripState`** → accumulates distance and fuel unconditionally (already does this; keep as-is)
- **`FuelCalculations.tripAvgLper100km` / `FuelCalculator.tripAverages`** → currently gate at 0.1 km; change gate to **0.5 km** (display-only, no effect on raw totals)
- Remove the separate 0.01L fuel threshold — the 0.5 km distance gate is sufficient

### 3. Show zero fuel efficiency for first 0.5 km
Change threshold in both `FuelCalculations.kt` (`tripAvgLper100km`) and `FuelCalculator.kt` (`tripAverages`) from `distKm > 0.1f` to `distKm > 0.5f`. Fuel total and distance total continue accumulating from zero regardless.

### 4. Upgrade internal math to Double
| Location | Change |
|----------|--------|
| `TripState` | Already Double internally ✅ |
| `FuelCalculations.tripAvgLper100km` | Change Float ops to Double |
| `FuelCalculations.tripAvgKpl` | Change Float ops to Double |
| `FuelCalculations.instantLper100km` | Change Float ops to Double |
| `FuelCalculator.tripAverages` | Change Float ops to Double |
| `FuelCalculator.instantaneous` | Already Double ✅ |
| `VehicleMetrics` trip fields | Keep as Float (display/UI) — no API break needed |
| `TripCalculator.averageSpeed` | Change to Double internally |

The public API of `VehicleMetrics` stays `Float?` — only the *intermediate math* inside calculators uses Double before returning Float.

---

## Files to Change

| File | Change |
|------|--------|
| `FuelCalculations.kt` | Threshold 0.1→0.5 km; remove 0.01L fuel gate; Double math in `tripAvgLper100km`, `tripAvgKpl`, `instantLper100km` |
| `FuelCalculator.kt` | Threshold 0.1→0.5 km; remove 0.01L fuel gate; Double math in `tripAverages` |
| `TripCalculator.kt` | Double math in `averageSpeed` |

`TripState.kt`, `DataOrchestrator.kt`, `MetricsCalculator.kt`, `VehicleMetrics.kt` — **no changes needed**

---

## Not Changing
- Logging trigger (DataOrchestrator combine+debounce) — current model is correct; GPS-triggered ticks with latest OBD values is the right approach
- `TripState.update()` accumulation — already correct, no blocking
- Public `VehicleMetrics` API types — stays Float to avoid cascading UI changes
