# OBD2 MetricsCalculator Pre-Refactoring Test Suite

Create a JVM unit test suite that pins all calculation behaviour in `MetricsCalculator` before the Phase 1/2 refactoring, so regressions are immediately visible after the split.

---

## Strategy

`MetricsCalculator` requires Android `Context` (for `SharedPreferences`, `AccelerometerSource`, etc.), so it **cannot be instantiated in a plain JVM test**. The solution is to test the two layers independently:

1. **Pure calculation functions** — extracted as package-private (internal) Kotlin top-level functions or extracted directly into the 3 new `calculator/` classes, tested with zero mocking
2. **`AccelEngine`** — already pure Kotlin, fully testable as-is
3. **`TripState`** — already pure Kotlin, fully testable as-is
4. **`FuelType`** constants — verify the constant values used in calculations

No mocking framework or additional libraries are needed beyond `junit:4.13.2` (already in the build).

---

## New dependencies needed

Add one library to `build.gradle.kts` (unit test only):

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

Also add the kotlin plugin to `build.gradle.kts` since `kotlin-android` is currently missing from the `plugins {}` block — required for the new `test/` source set to compile.

---

## Files to Create

All tests go in the standard JVM test source set:
`app/src/test/java/com/sj/obd2app/metrics/`

| File | Tests |
|---|---|
| `FuelCalculationTest.kt` | Fuel rate selection, L/100km, km/L, range, cost, CO₂ |
| `PowerCalculationTest.kt` | Accel-based power, thermodynamic power, OBD torque power |
| `TripStateTest.kt` | Distance accumulation, fuel accumulation, time buckets, drive mode % |
| `AccelEngineTest.kt` | Basis construction, detrending, RMS/max/mean/std, lean angle |
| `FuelTypeConstantsTest.kt` | MAF factor, CO₂ factor, energy density values |

---

## Test Details

### `FuelCalculationTest.kt`

Tests the exact formulas from `MetricsCalculator.calculate()` as standalone pure functions.

```
effectiveFuelRate:
  - returns fuelRatePid when pid != null and > 0
  - falls back to MAF × mafLitreFactor × 3600 when pid is null
  - returns null when both pid and maf are null
  - falls back to MAF when pid == 0f

instantLper100km:
  - returns null when speedKmh <= 2
  - = fuelRateLh × 100 / speedKmh at speed = 100 km/h, rate = 10 L/h → 10 L/100km
  - = fuelRateLh × 100 / speedKmh at speed = 50 km/h, rate = 5 L/h → 10 L/100km

instantKpl:
  - = 100 / instantLpk → 10 L/100km gives 10 km/L

tripAvgLper100km:
  - null when tripDistanceKm < 0.1
  - = fuelUsed × 100 / dist (100 L / 1000 km = 10 L/100km)

range:
  - = (fuelPct / 100 × tankL) / (avgLpk / 100)
  - 50% full, 40 L tank, 10 L/100km → 200 km

cost:
  - = fuelUsedL × pricePerLitre (5.0 L × 1.50 = 7.50)
  - null when pricePerLitre <= 0

co2:
  - = tripAvgLpk × co2Factor (10 L/100km × 23.1 = 231 g/km for petrol)
```

### `PowerCalculationTest.kt`

```
accelPower:
  - = mass × fwdAcc × speedMs / 1000
  - 1500 kg × 2 m/s² × 20 m/s = 60 kW
  - null when mass == 0
  - null when speedMs == 0

thermoPower:
  - = (rate / 3600 × energyDensity × 1e6 × 0.35) / 1000
  - petrol: 10 L/h → ~ 33.25 kW
  - null when fuelRate is null

obdPower:
  - = (torquePct / 100 × refNm × rpm × 2π) / 60000
  - 100% × 200 Nm × 3000 rpm = (200 × 3000 × 2π) / 60000 ≈ 62.83 kW
  - null when any input is null
```

### `TripStateTest.kt`

```
update() — distance:
  - 100 km/h for 1 second → 0.02778 km added
  - 0 km/h for 10 seconds → 0 km added

update() — fuel:
  - 10 L/h for 1 hour → 10 L added
  - 0 L/h → 0 L added

update() — time buckets:
  - speed > 2 km/h → increments movingTimeSec
  - speed <= 2 km/h → increments stoppedTimeSec

update() — maxSpeed:
  - tracks peak correctly across multiple updates

driveModePercents():
  - empty window → (0, 0, 0)
  - all idle (0 km/h) → pctIdle = 100
  - all city (30 km/h) → pctCity = 100
  - all highway (90 km/h) → pctHighway = 100
  - mix → sums to 100%
  - 60-second window evicts old entries
```

### `AccelEngineTest.kt`

```
computeVehicleBasis():
  - gravity pointing straight down [0, 0, 9.8] → valid basis, ĝ ≈ [0,0,1]
  - zero vector → returns null
  - orthonormality: |ĝ| = 1, |fwd| = 1, |lat| = 1, ĝ·fwd ≈ 0, ĝ·lat ≈ 0, fwd·lat ≈ 0

computeAccelMetrics():
  - empty buffer → returns null
  - constant [1,0,0] samples → vertMean known, fwdMean known (depends on basis)
  - RMS of [1,1,1,1] = 1.0
  - stdDev of constant signal = 0
  - fwdMaxAccel > 0 for positive forward samples
  - fwdMaxBrake > 0 for negative forward samples
  - peakRatio = 0 when all samples below threshold
  - peakRatio = 1 when all samples above threshold
  - leanAngleDeg ≈ 0 when gravity is vertical (no lateral component)
```

### `FuelTypeConstantsTest.kt`

```
PETROL:
  - mafLitreFactor = 0.0000746
  - co2Factor = 23.1
  - energyDensityMJpL = 34.2

DIESEL:
  - mafLitreFactor = 0.0000594
  - co2Factor = 26.4
  - energyDensityMJpL = 38.6

All types:
  - mafLitreFactor > 0
  - co2Factor > 0
  - energyDensityMJpL > 0
```

---

## How the pure functions are tested

Since the formulas are currently embedded in `MetricsCalculator.calculate()`, we have two choices:
- **Option A (preferred)**: Extract the formulas into `internal` top-level functions in the same `metrics` package, callable from tests in the same package
- **Option B**: Duplicate the formulas in the test class as expected-value calculations, treating `MetricsCalculator` output as a black box (requires a full integration test with mocked Android)

**We will use Option A** — add `internal` helper functions alongside the existing classes without modifying `MetricsCalculator`'s public API. This makes the logic directly testable before AND after refactoring, and mirrors exactly what the Phase 1 split will produce.

---

## Build changes required

### `gradle/libs.versions.toml` — add:
```toml
[versions]
coroutinesTest = "1.7.3"

[libraries]
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }
```

### `app/build.gradle.kts` — add:
```kotlin
plugins {
    alias(libs.plugins.kotlin.android)   // currently missing
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.test)
}
```

---

## Run command (after implementation)

```
./gradlew :app:test
```
