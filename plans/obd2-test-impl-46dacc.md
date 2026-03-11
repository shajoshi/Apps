# OBD2 Test Suite Implementation Plan (Option A)

Implement Option A: extract calculation formulas from `MetricsCalculator.calculate()` into `internal` top-level functions, then write JVM unit tests against those functions — no mocking, no Android runtime needed.

---

## What "Option A" means concretely

- Add **3 new `internal` Kotlin files** in `metrics/` containing only pure functions
- Add **5 new test files** in `src/test/` that call those functions directly
- **Zero changes** to `MetricsCalculator.kt`'s public API or behaviour
- The extracted functions are the exact code that will become the bodies of `FuelCalculator`, `PowerCalculator`, and `TripCalculator` during Phase 1 refactoring

---

## Step 1 — Build changes

### `gradle/libs.versions.toml`
Add:
```toml
[versions]
coroutinesTest = "1.7.3"          # same version as coroutines

[libraries]
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
```

### `app/build.gradle.kts`
- Add `alias(libs.plugins.kotlin.android)` to `plugins {}`
- Add `testImplementation(libs.kotlinx.coroutines.test)` to `dependencies {}`

---

## Step 2 — New production files (pure internal functions)

### `metrics/FuelCalculations.kt`
Internal top-level functions extracted verbatim from `MetricsCalculator.calculate()`:

```kotlin
internal fun effectiveFuelRate(fuelRatePid: Float?, maf: Float?, mafLitreFactor: Double): Float?
internal fun instantLper100km(fuelRateLh: Float?, speedKmh: Float): Float?
internal fun instantKpl(instantLpk: Float?): Float?
internal fun tripAvgLper100km(fuelUsedL: Float, distKm: Float): Float?
internal fun tripAvgKpl(avgLpk: Float?): Float?
internal fun rangeRemainingKm(fuelLevelPct: Float?, tankCapL: Float, avgLpk: Float?): Float?
internal fun fuelCost(fuelUsedL: Float, pricePerLitre: Float): Float?
internal fun avgCo2gPerKm(avgLpk: Float?, co2Factor: Double): Float?
internal fun fuelFlowCcMin(fuelRateLh: Float?): Float?
internal fun speedDiff(gpsSpeed: Float?, obdSpeed: Float?): Float?
internal fun tripAvgSpeed(distKm: Float, movingSec: Long): Float?
```

### `metrics/PowerCalculations.kt`
```kotlin
internal fun powerAccelKw(massKg: Float, fwdMean: Float?, speedMs: Float): Float?
internal fun powerThermoKw(fuelRateLh: Float?, energyDensityMJpL: Double): Float?
internal fun powerOBDKw(actualTorquePct: Float?, refTorqueNm: Int?, rpm: Float?): Float?
```

### These files have **no Android imports** — plain Kotlin math only.

---

## Step 3 — Wire MetricsCalculator to use the new functions

Replace the inline calculation blocks in `MetricsCalculator.calculate()` with calls to the extracted functions. The output is **identical** — this is a pure refactoring of the internals. Public interface unchanged.

---

## Step 4 — New test files

All in: `app/src/test/java/com/sj/obd2app/metrics/`

### `FuelCalculationsTest.kt` (~25 cases)
- `effectiveFuelRate`: prefers PID, falls back to MAF, returns null when both absent/zero
- `instantLper100km`: null when speed ≤ 2, correct value at known inputs
- `instantKpl`: inverse of L/100km
- `tripAvgLper100km`: null when dist < 0.1 km, correct at known inputs
- `rangeRemainingKm`: correct at known fuel level + tank + efficiency
- `fuelCost`: correct multiplication; null when price ≤ 0
- `avgCo2gPerKm`: correct value for petrol co2Factor
- `fuelFlowCcMin`: rate × 1000 / 60
- `speedDiff`: gps − obd; null when either is null
- `tripAvgSpeed`: dist / (movingSec / 3600)

### `PowerCalculationsTest.kt` (~12 cases)
- `powerAccelKw`: 1500 kg × 2 m/s² × 20 m/s = 60 kW; null when mass=0; null when speed=0
- `powerThermoKw`: petrol 10 L/h → ~33.25 kW; null when rate null/zero
- `powerOBDKw`: 100% × 200 Nm × 3000 rpm ≈ 62.83 kW; null when any input null

### `TripStateTest.kt` (~18 cases)
Tests `TripState` class directly — no extraction needed (already pure Kotlin):
- `update()` distance integration at 100 km/h for 1 s
- `update()` fuel integration at 10 L/h for 3600 s
- moving/stopped time bucket classification at speed thresholds
- `maxSpeedKmh` tracking
- `driveModePercents()`: all-idle, all-city, all-highway, mix, empty window
- 60-second window eviction

### `AccelEngineTest.kt` (~20 cases)
Tests `AccelEngine` directly — already pure Kotlin:
- `computeVehicleBasis()`: orthonormal result, null on zero vector, correct ĝ direction
- `computeAccelMetrics()`: null on empty buffer, RMS of uniform signal, stdDev=0 for constant, fwdMaxAccel/Brake sign correctness, peakRatio bounds, leanAngle≈0 for vertical gravity

### `FuelTypeConstantsTest.kt` (~8 cases)
- Verify exact constant values for PETROL, DIESEL, E20, CNG
- All factors > 0 for all types

---

## Files created/modified summary

| Path | Action |
|---|---|
| `gradle/libs.versions.toml` | Modified — add coroutines-test |
| `app/build.gradle.kts` | Modified — add kotlin plugin + test dep |
| `metrics/FuelCalculations.kt` | **New** — internal pure functions |
| `metrics/PowerCalculations.kt` | **New** — internal pure functions |
| `metrics/MetricsCalculator.kt` | Modified — call extracted functions (no API change) |
| `test/.../FuelCalculationsTest.kt` | **New** |
| `test/.../PowerCalculationsTest.kt` | **New** |
| `test/.../TripStateTest.kt` | **New** |
| `test/.../AccelEngineTest.kt` | **New** |
| `test/.../FuelTypeConstantsTest.kt` | **New** |

`TripCalculator` functions (`avgSpeed`, `speedDiff`) are small enough to be included in `FuelCalculations.kt` or inline — no separate file needed since `TripState` is tested directly.

---

## Run command after implementation
```
./gradlew :app:test --tests "com.sj.obd2app.metrics.*"
```
