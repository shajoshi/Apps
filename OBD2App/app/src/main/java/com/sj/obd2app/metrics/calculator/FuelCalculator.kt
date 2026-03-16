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
     * Prefers OBD-II direct fuel rate (PID 015E), falls back to MAF-based calculation.
     * MAF conversion: grams/second * ml_per_gram / 1000 * 3600 = litres/hour
     */
    fun effectiveFuelRate(fuelRatePid: Float?, maf: Float?, mafMlPerGram: Double): Float? {
        return when {
            fuelRatePid != null && fuelRatePid > 0f -> fuelRatePid
            maf != null && maf > 0f -> (maf * mafMlPerGram / 1000.0 * 3600.0).toFloat()
            else -> null
        }
    }

    /**
     * Determines effective fuel rate (ml/min) from available sources.
     * Primary internal unit for better precision with small fuel rates.
     *
     * Prefers OBD-II direct fuel rate (PID 015E), falls back to MAF-based calculation.
     * MAF conversion: grams/second * ml_per_gram * 60 = ml/min
     * Uses Double precision for better accuracy.
     */
    fun effectiveFuelRateMlMin(fuelRatePid: Float?, maf: Float?, mafMlPerGram: Double): Float? {
        return when {
            fuelRatePid != null && fuelRatePid > 0f -> (fuelRatePid * 1000.0 / 60.0).toFloat()
            maf != null && maf > 0f -> (maf * mafMlPerGram * 60.0).toFloat()
            else -> null
        }
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
}
