# Speed-Density Fuel Rate Estimation (No MAF/Fuel Rate PID)

Add a 3rd-tier fuel rate fallback using the Speed-Density method for vehicles (like Maruti Alto) that lack MAF sensor (PID 0110) and direct fuel rate PID (015E), using MAP, IAT, RPM, and engine displacement.

## Background

**Current fallback chain** in `FuelCalculator.effectiveFuelRate()`:
1. PID 015E (direct fuel rate) → not available on Alto
2. MAF PID 0110 → not available on Alto
3. **→ null** (all fuel metrics blank)

**Available Alto PIDs** for Speed-Density:
- `intakeMapKpa` (MAP, PID 010B) = 26 kPa at idle
- `intakeTempC` (IAT, PID 010F) = 40°C
- `rpm` (PID 010C) = 704 at idle
- `baroPressureKpa` (PID 0133) = 98 kPa (optional, for VE correction)
- `engineLoadPct` (PID 0104) = 19.6% (for cross-validation)

## Speed-Density Formula

```
MAF_estimated (g/s) = (MAP_kPa × Vd_L × VE × RPM) / (2 × R × IAT_K × 60)

Where:
  MAP_kPa      = Manifold Absolute Pressure (PID 010B)
  Vd_L         = Engine displacement in litres (e.g. 0.998 for Alto K10)
  VE           = Volumetric efficiency (0.0–1.0, default 0.85 for NA petrol)
  RPM          = Engine speed (PID 010C)
  R            = Specific gas constant for air = 0.287 kJ/(kg·K) = 287 J/(kg·K)
  IAT_K        = Intake Air Temperature in Kelvin (PID 010F + 273.15)
  ÷ 2          = 4-stroke engine (1 intake per 2 revolutions)
  ÷ 60         = RPM to revolutions per second

Simplified:
  MAF_g/s = (MAP × Vd × VE × RPM) / (120 × 0.287 × IAT_K)
          = (MAP × Vd × VE × RPM) / (34.44 × IAT_K)
```

Once we have MAF_estimated, the existing `mafMlPerGram` conversion handles the rest.

### Sanity check (Alto idle):
- MAP=26, Vd=0.998, VE=0.85, RPM=704, IAT=313K
- MAF = (26 × 0.998 × 0.85 × 704) / (34.44 × 313) = **1.44 g/s**
- Fuel rate = 1.44 × 0.09195 / 1000 × 3600 = **0.48 L/h** (reasonable for 1.0L NA idle)

## Implementation Plan

### Step 1: Add `engineDisplacementCc` and `volumetricEfficiencyPct` to `VehicleProfile`

**File:** `VehicleProfile.kt`
- Add `engineDisplacementCc: Int = 0` (0 = not set, disables Speed-Density)
- Add `volumetricEfficiencyPct: Float = 85f` (default 85% for NA petrol)

### Step 2: Add Speed-Density method to `FuelCalculator`

**File:** `FuelCalculator.kt`
- Add `speedDensityMafGs(mapKpa, iatC, rpm, displacementCc, vePct)` → returns estimated MAF in g/s
- Returns `null` if any required input is null/zero or displacement is 0

### Step 3: Extend `effectiveFuelRate()` and `effectiveFuelRateMlMin()` fallback chain

**File:** `FuelCalculator.kt`
- Add Speed-Density as tier 3 fallback after MAF:
  1. PID 015E direct fuel rate
  2. MAF-based
  3. **Speed-Density** (MAP + IAT + RPM + displacement + VE) → estimated MAF → fuel rate

Both methods will accept new optional parameters: `mapKpa`, `iatC`, `rpm`, `displacementCc`, `vePct`.

### Step 4: Wire up in `MetricsCalculator.performCalculations()`

**File:** `MetricsCalculator.kt`
- Read `engineDisplacementCc` and `volumetricEfficiencyPct` from active profile
- Pass MAP, IAT, RPM, displacement, VE to the updated `effectiveFuelRate()` calls

### Step 5: Add `engineDisplacementCc` and `volumetricEfficiencyPct` to profile UI

**Files:** `sheet_vehicle_profile_edit.xml`, `VehicleProfileEditSheet.kt`
- Add "Engine Displacement (cc)" input field (hint: "e.g. 998")
- Add "Volumetric Efficiency %" input field (hint: "85", helper text: "85% typical for NA petrol")
- Wire save/load for both fields

### Step 6: Add unit tests

**File:** `FuelCalculatorTest.kt`
- Test `speedDensityMafGs` with known values (Alto idle sanity check)
- Test fallback chain: null MAF + valid SD params → uses Speed-Density
- Test edge cases: displacement=0 disables SD, null MAP/IAT/RPM returns null
- Test fuel rate output is in expected range for typical driving scenarios

## Files Changed

| File | Change |
|------|--------|
| `VehicleProfile.kt` | Add `engineDisplacementCc`, `volumetricEfficiencyPct` fields |
| `FuelCalculator.kt` | Add `speedDensityMafGs()`, extend fallback chain |
| `MetricsCalculator.kt` | Pass SD params to fuel rate calls |
| `sheet_vehicle_profile_edit.xml` | Add displacement + VE input fields |
| `VehicleProfileEditSheet.kt` | Wire new fields save/load |
| `FuelCalculatorTest.kt` | Add Speed-Density tests |

## Notes

- The Speed-Density estimate is inherently approximate (~±10-15%) since VE varies with RPM, load, and temperature. A fixed VE of 85% is a reasonable default for naturally aspirated petrol engines.
- Fuel trim corrections (STFT + LTFT) from your Alto could optionally be applied to improve accuracy in a future enhancement. The ECU reports LTFT=13.3% which means the ECU is adding ~13% extra fuel — applying `(1 + (stft + ltft) / 100)` to the estimated MAF would account for this.
- The `engineLoadPct` PID can serve as a cross-validation signal but is not used in the core calculation.
