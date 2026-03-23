package com.sj.obd2app.metrics.calculator

import com.sj.obd2app.settings.FuelType

/**
 * Quartet data class for returning 4 values.
 * Used for ml/min based fuel efficiency calculations.
 */
data class Quartet<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/**
 * Fuel-related calculations extracted from MetricsCalculator.calculate().
 *
 * Handles fuel rate determination, efficiency calculations, range estimation,
 * cost calculation, and CO2 emissions. All methods are stateless and pure.
 */
class FuelCalculator {

    /**
     * Determines effective fuel rate (L/h) from available sources.
     *
     * Fallback chain:
     *  1. OBD-II direct fuel rate (PID 015E)
     *  2. MAF sensor (PID 0110) with diesel boost correction
     *  3. Speed-Density estimate (MAP + IAT + RPM + engine displacement) with diesel boost correction
     *
     * For diesel engines, applies boost-aware AFR correction based on MAP, RPM, and load.
     * MAF conversion: grams/second * ml_per_gram * afrCorrection / 1000 * 3600 = litres/hour
     */
    fun effectiveFuelRate(
        fuelRatePid: Float?,
        maf: Float?,
        mafMlPerGram: Double,
        mapKpa: Float? = null,
        iatC: Float? = null,
        rpm: Float? = null,
        displacementCc: Int = 0,
        vePct: Float = 85f,
        fuelType: FuelType = FuelType.PETROL,
        baroKpa: Float? = null,
        engineLoadPct: Float? = null,
        dieselCorrectionFactor: Float = 0.25f
    ): Float? {
        // Direct fuel rate PID takes priority (no correction needed - already accurate)
        if (fuelRatePid != null && fuelRatePid > 0f) return fuelRatePid
        
        // For diesel engines, calculate fuel rate directly using new AFR correction method
        if (fuelType == FuelType.DIESEL && 
            maf != null && 
            mapKpa != null && 
            baroKpa != null && 
            engineLoadPct != null) {
            return calculateDieselAfrCorrection(maf, engineLoadPct, mapKpa, baroKpa, fuelType, dieselCorrectionFactor).toFloat()
        }
        
        // MAF-based calculation for non-diesel engines
        if (maf != null && maf > 0f) {
            return (maf * mafMlPerGram / 1000.0 * 3600.0).toFloat()
        }
        
        // Speed-Density fallback for non-diesel engines
        val sdMaf = speedDensityMafGs(mapKpa, iatC, rpm, displacementCc, vePct)
        return if (sdMaf != null) {
            (sdMaf * mafMlPerGram / 1000.0 * 3600.0).toFloat()
        } else null
    }

    /**
     * Determines effective fuel rate (ml/min) from available sources.
     * Primary internal unit for better precision with small fuel rates.
     *
     * Fallback chain:
     *  1. OBD-II direct fuel rate (PID 015E)
     *  2. MAF sensor (PID 0110) with diesel boost correction
     *  3. Speed-Density estimate (MAP + IAT + RPM + engine displacement) with diesel boost correction
     *
     * For diesel engines, applies boost-aware AFR correction based on MAP, RPM, and load.
     * MAF conversion: grams/second * ml_per_gram * afrCorrection * 60 = ml/min
     * Uses Double precision for better accuracy.
     */
    fun effectiveFuelRateMlMin(
        fuelRatePid: Float?,
        maf: Float?,
        mafMlPerGram: Double,
        mapKpa: Float? = null,
        iatC: Float? = null,
        rpm: Float? = null,
        displacementCc: Int = 0,
        vePct: Float = 85f,
        fuelType: FuelType = FuelType.PETROL,
        baroKpa: Float? = null,
        engineLoadPct: Float? = null,
        dieselCorrectionFactor: Float = 0.25f
    ): Float? {
        // Direct fuel rate PID takes priority (convert L/h to ml/min)
        if (fuelRatePid != null && fuelRatePid > 0f) {
            return (fuelRatePid * 1000.0 / 60.0).toFloat()
        }
        
        // For diesel engines, calculate fuel rate directly using new AFR correction method
        if (fuelType == FuelType.DIESEL && 
            maf != null && 
            mapKpa != null && 
            baroKpa != null && 
            engineLoadPct != null) {
            val fuelRateLh = calculateDieselAfrCorrection(maf, engineLoadPct, mapKpa, baroKpa, fuelType, dieselCorrectionFactor).toFloat()
            return (fuelRateLh * 1000.0 / 60.0).toFloat()
        }
        
        // MAF-based calculation for non-diesel engines
        if (maf != null && maf > 0f) {
            return (maf * mafMlPerGram * 60.0).toFloat()
        }
        
        // Speed-Density fallback for non-diesel engines
        val sdMaf = speedDensityMafGs(mapKpa, iatC, rpm, displacementCc, vePct)
        return if (sdMaf != null) {
            (sdMaf * mafMlPerGram * 60.0).toFloat()
        } else null
    }

    /**
     * Calculates instantaneous fuel efficiency metrics.
     * Returns 0 for consumption when vehicle is not moving for better UX.
     * Uses Double precision for better accuracy with small fuel rates and low speeds.
     *
     * @param fuelRateLh Fuel rate in litres per hour
     * @param speedKmh Speed in km/h
     * @return Pair of (L/100km, km/L) - returns (0f, 0f) when vehicle is stopped
     */
    fun instantaneous(fuelRateLh: Float?, speedKmh: Float): Pair<Float?, Float?> {
        if (fuelRateLh == null || fuelRateLh <= 0f || speedKmh <= 0f) {
            return Pair(0f, 0f)
        }
        val lPer100km = (fuelRateLh.toDouble() / speedKmh.toDouble() * 100.0).toFloat()
        val kmPerL = 100f / lPer100km
        return Pair(lPer100km, kmPerL)
    }

    /**
     * Calculates instantaneous fuel efficiency metrics using ml/min.
     * Primary internal calculation for better precision.
     * Returns 0 for consumption when vehicle is not moving for better UX.
     * Uses Double precision for better accuracy with small fuel rates and low speeds.
     *
     * @param fuelRateMlMin Fuel rate in millilitres per minute
     * @param speedKmh Speed in km/h
     * @return Quartet of (L/100km, km/L, ml/km, km/ml) - returns (0f, 0f, 0f, 0f) when vehicle is stopped
     */
    fun instantaneousMl(fuelRateMlMin: Float?, speedKmh: Float): Quartet<Float?, Float?, Float?, Float?> {
        if (fuelRateMlMin == null || fuelRateMlMin <= 0f || speedKmh <= 0f) {
            return Quartet(0f, 0f, 0f, 0f)
        }
        // ml/km calculation (primary)
        val mlPerKm = (fuelRateMlMin.toDouble() * 60.0 / speedKmh.toDouble()).toFloat()
        
        // Convert to L/100km for display
        val lPer100km = (mlPerKm / 10.0).toFloat()
        
        // Efficiency calculations
        val kmPerMl = if (mlPerKm > 0f) (1000.0 / mlPerKm.toDouble()).toFloat() else 0f
        val kmPerL = 100f / lPer100km
        
        return Quartet(lPer100km, kmPerL, mlPerKm, kmPerMl)
    }

    /**
     * Calculates trip-average fuel efficiency metrics.
     * Returns 0 for consumption when distance is below 0.1 km (display-only gate).
     * Fuel amount gate removed — distance gate alone is sufficient.
     * Uses Double precision for better accuracy with small values.
     *
     * @param fuelUsedL Total fuel used in litres
     * @param distKm Total distance traveled in km
     * @return Pair of (avg L/100km, avg km/L) - returns (0f, 0f) when calculation unreliable
     */
    fun tripAverages(fuelUsedL: Float, distKm: Float): Pair<Float?, Float?> {
        // Minimum threshold for reliable efficiency calculation (display-only)
        if (distKm <= 0.1f) return Pair(0f, 0f)
        
        val lPer100km = ((fuelUsedL.toDouble() / distKm.toDouble()) * 100.0).toFloat()
        val kmPerL = (100.0 / lPer100km.toDouble()).toFloat()
        return Pair(lPer100km, kmPerL)
    }

    /**
     * Calculates trip-average fuel efficiency metrics using milliliters.
     * Primary internal calculation for better precision.
     * Returns 0 for consumption when distance is below 0.1 km (display-only gate).
     * Uses Double precision for better accuracy with small values.
     *
     * @param fuelUsedMl Total fuel used in millilitres
     * @param distKm Total distance traveled in km
     * @return Quartet of (avg L/100km, avg km/L, avg ml/km, avg km/ml) - returns (0f, 0f, 0f, 0f) when calculation unreliable
     */
    fun tripAveragesMl(fuelUsedMl: Double, distKm: Float): Quartet<Float?, Float?, Float?, Float?> {
        // Minimum threshold for reliable efficiency calculation (display-only)
        if (distKm <= 0.1f) return Quartet(0f, 0f, 0f, 0f)
        
        // ml/km calculation (primary)
        val mlPerKm = (fuelUsedMl / distKm.toDouble()).toFloat()
        
        // Convert to L/100km for display
        val lPer100km = (mlPerKm / 10.0).toFloat()
        
        // Efficiency calculations
        val kmPerMl = if (mlPerKm > 0f) (1000.0 / mlPerKm.toDouble()).toFloat() else 0f
        val kmPerL = 100f / lPer100km
        
        return Quartet(lPer100km, kmPerL, mlPerKm, kmPerMl)
    }

    /**
     * Estimates remaining range based on current fuel level and average consumption.
     *
     * @param fuelLevelPct Current fuel level as percentage (0-100)
     * @param tankCapL Tank capacity in litres
     * @param avgLpk Average fuel consumption in L/100km
     * @return Estimated range in km, or null if calculation impossible
     */
    fun range(fuelLevelPct: Float?, tankCapL: Float, avgLpk: Float?): Float? {
        if (fuelLevelPct == null || avgLpk == null || avgLpk <= 0f) return null
        val fuelRemainingL = (fuelLevelPct / 100f) * tankCapL
        return (fuelRemainingL / avgLpk) * 100f
    }

    /**
     * Calculates fuel cost for the trip.
     *
     * @param fuelUsedL Total fuel used in litres
     * @param pricePerLitre Fuel price per litre
     * @return Total fuel cost, or null if price is zero
     */
    fun cost(fuelUsedL: Float, pricePerLitre: Float): Float? {
        return if (pricePerLitre > 0f) fuelUsedL * pricePerLitre else null
    }

    /**
     * Estimates CO2 emissions per km based on fuel consumption.
     *
     * Uses fuel type's CO2 emission factor (grams of CO2 per litre of fuel).
     * Uses Double precision for better accuracy with small consumption values.
     *
     * @param avgLpk Average fuel consumption in L/100km
     * @param co2Factor Grams of CO2 per litre of fuel
     * @return CO2 emissions in g/km, or null if calculation impossible
     */
    fun co2(avgLpk: Float?, co2Factor: Double): Float? {
        return avgLpk?.let { (it * co2Factor).toFloat() }
    }

    /**
     * Converts fuel rate from L/h to cc/min (cubic centimeters per minute).
     * 1 L = 1000 cc, 1 hour = 60 minutes
     *
     * @param fuelRateLh Fuel rate in litres per hour
     * @return Fuel rate in cc/min, or null if input is null
     */
    fun fuelFlowCcMin(fuelRateLh: Float?): Float? {
        return fuelRateLh?.let { (it * 1000f / 60f) }
    }

    /**
     * Calculates boost pressure from MAP and barometric pressure.
     * Positive values indicate boost, negative values indicate vacuum.
     *
     * @param mapKpa Manifold Absolute Pressure in kPa
     * @param baroKpa Barometric pressure in kPa
     * @return Boost pressure in kPa (can be negative for vacuum)
     */
    fun calculateBoostPressure(mapKpa: Float, baroKpa: Float): Float {
        return mapKpa - baroKpa
    }

    /**
     * Calculates AFR correction factor for turbocharged diesel engines.
     * Uses load-based AFR determination for MJD 1.3L diesel calibration.
     * Returns fuel rate directly instead of correction factor.
     *
     * @param mafGs Mass air flow in grams/second
     * @param engineLoadPct Engine load percentage (0-100)
     * @param mapKpa Manifold absolute pressure in kPa
     * @param baroKpa Barometric pressure in kPa
     * @param fuelType Fuel type (correction only applied for DIESEL)
     * @param dieselCorrectionFactor Diesel correction factor from vehicle profile
     * @return Fuel rate in L/h, or 1.0 for non-diesel fuels (no correction)
     */
    fun calculateDieselAfrCorrection(
        mafGs: Float,
        engineLoadPct: Float,
        mapKpa: Float,
        baroKpa: Float,
        fuelType: FuelType,
        dieselCorrectionFactor: Float = 0.25f
    ): Double {
        // Only apply correction for diesel engines
        if (fuelType != FuelType.DIESEL) return 1.0
        
        // Convert load percentage to decimal (0-1)
        val loadDecimal = engineLoadPct / 100.0f
        
        // Determine AFR based on load - Torque Pro style for MJD 1.3L diesel
        val afr = when {
            loadDecimal < 0.35f -> 100f    // Extremely lean (typical diesel idle - Torque Pro conservative)
            loadDecimal < 0.7f -> 75f      // Lean (highway cruise - realistic diesel)
            else -> 50f                    // Normal diesel load (heavy acceleration)
        }
        
        // Calculate fuel rate directly (g/s)
        val fuelGps = mafGs / afr
        
        // Convert to L/h
        val fuelLh = fuelGps * 3.6f / fuelType.mafMlPerGram.toFloat()
        
        // Apply correction factor from vehicle profile
        // This factor accounts for real-world vs theoretical differences
        return (fuelLh * dieselCorrectionFactor).toDouble()
    }

    /**
     * Estimates MAF (g/s) using the Speed-Density method for vehicles without a MAF sensor.
     *
     * Formula: MAF = (MAP × Vd × VE × RPM) / (2 × R × IAT_K × 60)
     *
     * Where:
     *   MAP   = Manifold Absolute Pressure in kPa (PID 010B)
     *   Vd    = Engine displacement in litres
     *   VE    = Volumetric efficiency (0–1)
     *   RPM   = Engine speed (PID 010C)
     *   R     = Specific gas constant for air = 0.287 kJ/(kg·K)
     *   IAT_K = Intake Air Temperature in Kelvin (PID 010F + 273.15)
     *   ÷ 2   = 4-stroke engine (1 intake per 2 revolutions)
     *   ÷ 60  = RPM → rev/s
     *
     * Simplified denominator constant: 2 × 0.287 × 60 = 34.44
     *
     * @param mapKpa Manifold Absolute Pressure in kPa
     * @param iatC Intake Air Temperature in °C
     * @param rpm Engine speed in RPM
     * @param displacementCc Engine displacement in cc (0 = not set → returns null)
     * @param vePct Volumetric efficiency as percentage (e.g. 85 for 85%)
     * @return Estimated MAF in g/s, or null if any required input is missing
     */
    fun speedDensityMafGs(
        mapKpa: Float?,
        iatC: Float?,
        rpm: Float?,
        displacementCc: Int,
        vePct: Float
    ): Float? {
        if (mapKpa == null || mapKpa <= 0f) return null
        if (iatC == null) return null
        if (rpm == null || rpm <= 0f) return null
        if (displacementCc <= 0) return null
        if (vePct <= 0f) return null

        val vdLitres = displacementCc / 1000.0
        val ve = vePct / 100.0
        val iatK = iatC + 273.15

        // MAF (g/s) = (MAP × Vd × VE × RPM) / (34.44 × IAT_K)
        val maf = (mapKpa * vdLitres * ve * rpm) / (34.44 * iatK)
        return maf.toFloat()
    }
}
