---
description: Investigation into 50% lower fuel economy readings for turbo diesel Brezza
---

# Diesel Fuel Economy Calculation Issue - Investigation Report

The OBD2 app is showing fuel economy of 6-10 kmpl for the turbo diesel Brezza, which is approximately 50% lower than the vehicle dashboard and Torque app readings.

## Root Cause Analysis

### 1. **Critical Issue: Diesel AFR Assumption is Incorrect**

The current implementation uses a **stoichiometric AFR of 14.5:1** for diesel engines, which is fundamentally flawed for real-world diesel operation.

**Key Problem:**
- **Diesel engines NEVER operate at stoichiometric ratio** during normal driving
- Diesel engines run **lean** (excess air) at all times, typically between **18:1 to 65:1 AFR**
- At idle: ~65:1 AFR
- Light load: ~30-40:1 AFR  
- Heavy load: ~18-25:1 AFR
- Stoichiometric (14.5:1) is only theoretical - never used in practice

**Current Code Issue:**
```kotlin
// VehicleProfile.kt line 27
DIESEL("Diesel", 0.08210, 26.4, 38.6)
// mafMlPerGram = 1000 / (14.5 × 840) = 0.08210
```

This assumes the engine burns fuel at 14.5:1 ratio, but diesel engines actually run much leaner. This causes **significant overestimation** of fuel consumption.

### 2. **Evidence from Log Data**

Analyzing sample #500 from the log file:
- **MAF Reading:** 14.01 g/s
- **Speed:** 35.3 km/h
- **RPM:** 1549
- **Engine Load:** 36.5%
- **Calculated Fuel Rate:** 4.14 L/h
- **Calculated Fuel Economy:** 8.53 kmpl

**Manual Calculation:**
```
Fuel rate = 14.01 g/s × 0.08210 ml/g × 3600 s/h / 1000 = 4.14 L/h
Fuel economy = 35.3 km/h / 4.14 L/h × 100 = 8.53 L/100km = 11.7 kmpl
```

However, if the actual AFR is ~25:1 (typical for this load):
```
Actual mafMlPerGram = 1000 / (25 × 840) = 0.04762
Fuel rate = 14.01 × 0.04762 × 3600 / 1000 = 2.40 L/h
Fuel economy = 35.3 / 2.40 × 100 = 14.7 kmpl ✓ (closer to dashboard!)
```

### 3. **Why MAF-Based Calculation Fails for Diesel**

**Fundamental Problem:**
- MAF sensors measure **total air flow**
- For petrol engines: AFR is relatively constant (~14.7:1), so MAF → fuel is reliable
- For diesel engines: AFR varies wildly (18-65:1), making MAF → fuel conversion unreliable without knowing actual AFR

**Diesel Fuel Injection:**
- Diesel engines control power by **varying fuel quantity**, not air quantity
- Air intake is nearly unrestricted (no throttle plate in most diesels)
- The ECU injects precise fuel amounts based on driver demand
- MAF reading doesn't directly correlate to fuel consumption

## Data Analysis Summary

From the log file:
- Vehicle: Brezza (turbo diesel, 90 BHP, 1400 kg)
- Fuel Type: DIESEL (correctly configured)
- MAF readings: 5-17 g/s during normal driving
- Calculated economy: 4-12 kmpl (app)
- Expected economy: 12-20 kmpl (dashboard/Torque)
- **Discrepancy: ~50% underestimation** (matches 14.5:1 vs ~25:1 AFR ratio)

## Available OBD PIDs

The log shows the vehicle **does NOT** report:
- ❌ PID 015E (Engine Fuel Rate) - not available
- ✅ PID 0110 (MAF) - available and being used
- ✅ PID 010B (MAP) - available
- ✅ PID 010F (Intake Air Temp) - available
- ✅ PID 010C (RPM) - available
- ✅ PID 0104 (Engine Load) - available

**Current Fallback Chain:**
1. PID 015E (not available) ❌
2. MAF-based (currently used, but inaccurate) ⚠️
3. Speed-Density (not configured - displacement = 0) ❌

## Recommended Solutions

### **Option 1: Use Engine Load-Based Correction (Recommended)**

Diesel AFR varies with engine load. Apply a correction factor based on load:

```kotlin
fun dieselMafCorrection(engineLoadPct: Float): Double {
    return when {
        engineLoadPct < 20 -> 0.50  // Very lean at idle/light load (~30:1 AFR)
        engineLoadPct < 40 -> 0.60  // Light load (~25:1 AFR)
        engineLoadPct < 60 -> 0.70  // Medium load (~20:1 AFR)
        engineLoadPct < 80 -> 0.80  // Heavy load (~18:1 AFR)
        else -> 0.90  // Full load (~16:1 AFR, approaching stoich)
    }
}

// In effectiveFuelRate():
if (fuelType == FuelType.DIESEL && maf != null) {
    val correction = dieselMafCorrection(engineLoadPct ?: 50f)
    return (maf * mafMlPerGram * correction * 3600.0 / 1000.0).toFloat()
}
```

**Pros:**
- Uses existing MAF sensor data
- Engine load is always available
- Accounts for variable AFR
- Simple to implement

**Cons:**
- Still an approximation
- May need tuning for specific engines

### **Option 2: Speed-Density Method**

Configure engine displacement and use MAP/IAT/RPM to estimate air mass:

```kotlin
// In VehicleProfile for Brezza:
engineDisplacementCc = 1248  // 1.2L K-series diesel
volumetricEfficiencyPct = 90f  // Typical for turbo diesel
```

**Pros:**
- Independent of MAF sensor
- Can work without MAF
- More accurate for diesels with known VE

**Cons:**
- Requires engine displacement configuration
- VE varies with boost pressure (turbo)
- Still needs AFR correction

### **Option 3: Fuel Injector Pulse Width (Advanced)**

Some diesel ECUs expose fuel injector data via manufacturer-specific PIDs:

- Maruti/Suzuki: Check for custom PIDs for fuel injection timing/quantity
- May require reverse engineering ECU protocol

**Pros:**
- Most accurate - direct fuel measurement
- No AFR assumptions needed

**Cons:**
- Vehicle-specific
- May not be available
- Requires custom PID implementation

### **Option 4: Empirical Calibration Factor**

Add a user-adjustable calibration multiplier:

```kotlin
data class VehicleProfile(
    // ...
    val fuelCalibrationFactor: Float = 1.0f  // User can adjust based on real-world data
)

// In fuel calculation:
val fuelRate = baseFuelRate * profile.fuelCalibrationFactor
```

**Pros:**
- Simple fallback
- User can calibrate to match dashboard
- Works for any vehicle

**Cons:**
- Requires manual calibration
- Not automatic
- Doesn't explain the physics

## Immediate Action Plan

1. **Implement Option 1** (Engine Load Correction) - highest impact, easiest implementation
2. **Add Option 4** (Calibration Factor) - allows user override
3. **Document diesel limitations** in app/help section
4. **Consider Option 3** for future enhancement (custom PIDs)

## Technical Implementation

### Files to Modify:

1. **`FuelCalculator.kt`**
   - Add `dieselLoadCorrection()` function
   - Modify `effectiveFuelRate()` to apply correction for diesel
   - Modify `effectiveFuelRateMlMin()` similarly

2. **`VehicleProfile.kt`**
   - Add `fuelCalibrationFactor: Float = 1.0f` property
   - Add `engineDisplacementCc` (already exists, needs user configuration)

3. **`MetricsCalculator.kt`**
   - Pass `engineLoadPct` to fuel calculator
   - Apply calibration factor if set

### Testing Strategy:

1. Test with log file data (sample #500):
   - Current: 8.53 kmpl
   - With 0.60 correction (40% load): 14.2 kmpl ✓
   
2. Compare across different load conditions
3. Validate against dashboard readings during test drive

## Alternative Metrics Available

The log shows these metrics are available and accurate:
- ✅ GPS speed (hybrid with OBD)
- ✅ Distance traveled (GPS-based)
- ✅ Engine load, RPM, temperatures
- ✅ Accelerometer data for driving analysis

Consider de-emphasizing instant fuel economy and focusing on:
- Trip distance and time
- Average speed
- Driving style analysis (acceleration/braking patterns)
- Route elevation changes

## Conclusion

The 50% fuel economy discrepancy is caused by using stoichiometric AFR (14.5:1) for diesel engines that actually operate lean (18-65:1). The MAF-based calculation overestimates fuel consumption by assuming too much fuel per gram of air.

**Recommended immediate fix:** Implement engine load-based correction factor to adjust MAF conversion for diesel's variable AFR operation.
