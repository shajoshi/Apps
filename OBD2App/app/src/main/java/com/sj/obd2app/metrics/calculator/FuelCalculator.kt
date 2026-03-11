package com.sj.obd2app.metrics.calculator

import com.sj.obd2app.settings.FuelType

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
     * MAF conversion: grams/second * litre_factor = litres/hour
     */
    fun effectiveFuelRate(fuelRatePid: Float?, maf: Float?, mafLitreFactor: Double): Float? {
        return fuelRatePid ?: maf?.let { it * mafLitreFactor.toFloat() }
    }

    /**
     * Calculates instantaneous fuel efficiency metrics.
     *
     * @param fuelRateLh Fuel rate in litres per hour
     * @param speedKmh Speed in km/h
     * @return Pair of (L/100km, km/L) or (null, null) if inputs invalid
     */
    fun instantaneous(fuelRateLh: Float?, speedKmh: Float): Pair<Float?, Float?> {
        if (fuelRateLh == null || fuelRateLh <= 0f || speedKmh <= 0f) {
            return Pair(null, null)
        }
        val lPer100km = (fuelRateLh / speedKmh) * 100f
        val kmPerL = 100f / lPer100km
        return Pair(lPer100km, kmPerL)
    }

    /**
     * Calculates trip-average fuel efficiency metrics.
     *
     * @param fuelUsedL Total fuel used in litres
     * @param distKm Total distance traveled in km
     * @return Pair of (avg L/100km, avg km/L) or (null, null) if distance is zero
     */
    fun tripAverages(fuelUsedL: Float, distKm: Float): Pair<Float?, Float?> {
        if (distKm <= 0f) return Pair(null, null)
        val lPer100km = (fuelUsedL / distKm) * 100f
        val kmPerL = 100f / lPer100km
        return Pair(lPer100km, kmPerL)
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
     *
     * @param avgLpk Average fuel consumption in L/100km
     * @param co2Factor Grams of CO2 per litre of fuel
     * @return CO2 emissions in g/km, or null if calculation impossible
     */
    fun co2(avgLpk: Float?, co2Factor: Double): Float? {
        return avgLpk?.let { (it / 100f) * co2Factor.toFloat() }
    }
}
