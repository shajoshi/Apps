# OBD2 App: Phase 1 & 2 Refactoring Plan

Refactor `MetricsCalculator` from a 472-line monolith into focused components, then apply targeted performance optimizations to move heavy work off the main thread.

---

## Phase 1 — Break Down MetricsCalculator

### What changes

The existing `MetricsCalculator.kt` will be split into 5 new classes. **No public API changes** — existing ViewModels (`TripViewModel`, `DetailsViewModel`) continue to call `MetricsCalculator.getInstance()` and observe the same `metrics` / `tripPhase` flows.

```
metrics/
├── MetricsCalculator.kt       ← slimmed down orchestrator (~120 lines)
├── calculator/
│   ├── FuelCalculator.kt      ← fuel rate, L/100km, range, cost, CO₂
│   ├── PowerCalculator.kt     ← accel-based, thermodynamic, OBD torque power
│   └── TripCalculator.kt      ← trip averages, avg speed, drive mode %
├── collector/
│   └── DataOrchestrator.kt   ← combines OBD2 + GPS flows, triggers calculate()
└── (existing files unchanged)
│   ├── TripState.kt           ← no change
│   ├── AccelEngine.kt         ← no change
│   ├── AccelMetrics.kt        ← no change
│   ├── AccelCalibration.kt    ← no change
│   ├── MetricsLogger.kt       ← no change
│   ├── VehicleMetrics.kt      ← no change
│   ├── TripPhase.kt           ← no change
│   └── PidAvailabilityStore.kt ← no change
```

### New classes in detail

#### `calculator/FuelCalculator.kt`
Extracted from `MetricsCalculator.calculate()`:
- `effectiveFuelRate(fuelRatePid, maf, fuelType): Float?` — OBD direct or MAF fallback
- `instantaneous(fuelRate, speedKmh): Pair<Float?,Float?>` — L/100km + km/L
- `tripAverages(fuelUsedL, distKm): Pair<Float?,Float?>` — avg L/100km + km/L
- `range(fuelLevelPct, tankCapL, avgLpk): Float?`
- `cost(fuelUsedL, pricePerL): Float?`
- `co2(avgLpk, co2Factor): Float?`

#### `calculator/PowerCalculator.kt`
- `fromAccelerometer(massKg, fwdMean, speedMs): Float?`
- `thermodynamic(fuelRateLh, energyDensityMJpL): Float?`
- `fromObd(actualTorquePct, refTorqueNm, rpm): Float?`

#### `calculator/TripCalculator.kt`
- `averageSpeed(distKm, movingSec): Float?`
- `speedDiff(gpsSpeed, obdSpeed): Float?`

#### `collector/DataOrchestrator.kt`
- Moves the two `scope.launch { … }` coroutines out of `MetricsCalculator`
- Owns the fan-in logic: OBD2 update → recalculate; GPS update → recalculate
- Calls back into `MetricsCalculator.calculate()` (package-private)

#### `MetricsCalculator.kt` (slimmed)
- Keeps: singleton, `metrics` flow, `tripPhase` flow, trip control methods, `capturedGravityVector`, `supportedPids`
- Delegates all calculation detail to the three `calculator/` classes
- `startCollecting()` delegates to `DataOrchestrator`

---

## Phase 2 — Performance Optimizations

### 2.1 Move calculations to `Dispatchers.Default`

**Current problem**: `calculate()` is called inside `scope.launch { … }` on `Dispatchers.Default` (the scope uses `SupervisorJob() + Dispatchers.Default`). However GPS and OBD updates emit on the main looper. The flow collectors run on Default already, so this is partially correct — but the `_metrics.value = snapshot` write triggers downstream StateFlow observers which may execute on the UI thread.

**Fix**: Explicitly use `flowOn(Dispatchers.Default)` on the combined flow so all heavy computation stays off the main thread, and only the final `value` assignment reaches collectors.

### 2.2 Debounce rapid OBD2 + GPS co-updates

**Current problem**: Each OBD2 poll and each GPS update independently triggers a full `calculate()`. At 2 Hz GPS + 2 Hz OBD2 this is 4 full calculation passes per second with the same data.

**Fix**: In `DataOrchestrator`, combine both flows using `kotlinx.coroutines.flow.combine()` and add a `debounce(100)` so that simultaneous updates within 100 ms produce a single calculation pass.

```kotlin
combine(obdFlow, gpsFlow) { obd, gps -> Pair(obd, gps) }
    .debounce(100L)
    .flowOn(Dispatchers.Default)
    .collect { (obd, gps) -> recalculate(obd, gps) }
```

### 2.3 Remove redundant dual-flow subscription

**Current problem**: `MetricsCalculator.startCollecting()` creates **two separate** coroutines — one that collects OBD2 data, one for GPS — and each independently calls `calculate()`. When both emit at the same time, `calculate()` runs twice with the same effective data.

**Fix**: The `DataOrchestrator` collapses this into a single `combine()` flow as above, eliminating the redundant second run.

### 2.4 ViewModel lifecycle cleanup

**Current**: `DetailsViewModel` mixes `LiveData` and `StateFlow` unnecessarily; `viewModelScope` coroutines are created without cancellation guards.

**Fix**: Convert `DetailsViewModel` to use `StateFlow` throughout (matching `TripViewModel`), collected via `repeatOnLifecycle` in the Fragment to respect lifecycle correctly.

---

## Files Created / Modified

| File | Action |
|---|---|
| `metrics/calculator/FuelCalculator.kt` | **New** |
| `metrics/calculator/PowerCalculator.kt` | **New** |
| `metrics/calculator/TripCalculator.kt` | **New** |
| `metrics/collector/DataOrchestrator.kt` | **New** |
| `metrics/MetricsCalculator.kt` | **Refactored** (slimmed) |
| `ui/details/DetailsViewModel.kt` | **Refactored** (StateFlow) |

All other files remain unchanged. `TripViewModel.kt`, `TripFragment.kt`, `TripState.kt`, `AccelEngine.kt`, `MetricsLogger.kt`, `VehicleMetrics.kt` are untouched.

---

## Risks & Mitigations

- **Correctness**: All calculation logic is moved verbatim — no formula changes. Only structural reorganization.
- **Concurrency**: DataOrchestrator uses the same `SupervisorJob + Dispatchers.Default` scope as today.
- **Breaking changes**: Public surface of `MetricsCalculator` (singleton, `metrics`, `tripPhase`, trip control methods) is unchanged.
