# Fix MAF → Fuel Rate Conversion Constants

Correct the `mafMlPerGram` values in `FuelType` enum to use proper stoichiometric AFR-based conversion instead of the current incorrect fuel-density-only values.

## Root Cause

The current code computes:
```
fuel_L/h = MAF(g/s) × mafMlPerGram / 1000 × 3600
```

`mafMlPerGram` is supposed to be **ml of fuel per gram of air**, but current values (1.34, 1.18, 1.35) are just fuel density in ml/g — missing the AFR division. This makes the result **~14–17× too large**.

## Correct Formula

```
mafMlPerGram = 1000 / (AFR × fuel_density_g_per_L)
```

| Fuel | AFR | Density (g/L) | Current (WRONG) | Correct |
|------|-----|---------------|-----------------|---------|
| PETROL | 14.7 | 740 | 1.34 | **0.09195** |
| E20   | 13.8 | 790 | 1.34 (same as Petrol, wrong) | **0.09166** |
| DIESEL | 14.5 | 840 | 1.18 | **0.08210** |
| CNG    | 17.2 | 423 | 1.35 | **0.13740** |

Verification (Petrol, MAF=2 g/s):
- `2 × 0.09195 / 1000 × 3600 = 0.662 L/h` ✓ matches user's ~0.66 L/h

## Files to Change

1. **`VehicleProfile.kt`** — Update 4 `mafMlPerGram` values in `FuelType` enum
2. **`FuelTypeConstantsTest.kt`** — Update expected values for `mafMlPerGram` assertions
3. **`FuelCalculatorTest.kt`** — Check/update any tests using MAF-based fuel rate with hardcoded values
4. **`MetricsIntegrationTest.kt`** — Update Ronin scooter / car test scenario inputs if they pass MAF directly

## Notes
- The `effectiveFuelRate()` formula in `FuelCalculator.kt` itself is **correct** — no change needed there
- E20 is a separate bug: it currently has the same constant as PETROL despite having lower AFR (13.8 vs 14.7)
- CNG density ~423 g/L (compressed at reference), AFR=17.2
