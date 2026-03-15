package com.sj.obd2app.metrics

/**
 * Pure internal functions for fuel and trip efficiency calculations.
 * Extracted from [MetricsCalculator.calculate] to enable JVM unit testing
 * and to serve as the bodies for the Phase-1 FuelCalculator component.
 *
 * All functions are stateless and have no Android dependencies.
 */

/**
 * Determines effective fuel rate (L/h).
 * Prefers the direct OBD2 PID (015E); falls back to MAF-based estimate.
 * Uses Double precision for MAF calculation to prevent precision loss with small values.
 */
internal fun effectiveFuelRate(
    fuelRatePid: Float?,
    maf: Float?,
    mafLitreFactor: Double
): Float? = when {
    fuelRatePid != null && fuelRatePid > 0f -> fuelRatePid
    maf != null && maf > 0f -> (maf * mafLitreFactor * 3600.0).toFloat()
    else -> null
}

/**
 * Determines effective fuel rate (ml/min) - primary internal unit.
 * Prefers the direct OBD2 PID (015E); falls back to MAF-based estimate.
 * Uses Double precision for better accuracy with small fuel rates.
 */
internal fun effectiveFuelRateMlMin(
    fuelRatePid: Float?,
    maf: Float?,
    mafLitreFactor: Double
): Float? = when {
    fuelRatePid != null && fuelRatePid > 0f -> (fuelRatePid * 1000.0 / 60.0).toFloat()
    maf != null && maf > 0f -> (maf * mafLitreFactor * 1000.0 / 60.0).toFloat()
    else -> null
}

/**
 * Instantaneous consumption in L/100km.
 * Returns null when speed ≤ 2 km/h (vehicle effectively stopped).
 * Uses Double precision for better accuracy with small fuel rates.
 */
internal fun instantLper100km(fuelRateLh: Float?, speedKmh: Float): Float? =
    if (fuelRateLh != null && speedKmh > 2f) ((fuelRateLh.toDouble() * 100.0) / speedKmh.toDouble()).toFloat() else null

/**
 * Instantaneous consumption in ml/km - primary internal unit.
 * Returns null when speed ≤ 2 km/h (vehicle effectively stopped).
 * Uses Double precision for better accuracy with small fuel rates.
 */
internal fun instantMlPerKm(fuelRateMlMin: Float?, speedKmh: Float): Float? =
    if (fuelRateMlMin != null && speedKmh > 2f) ((fuelRateMlMin.toDouble() * 60.0) / speedKmh.toDouble()).toFloat() else null

/**
 * Instantaneous efficiency in km/L (reciprocal of L/100km).
 * Uses Double precision for better accuracy with small values.
 */
internal fun instantKpl(instantLpk: Float?): Float? =
    if (instantLpk != null && instantLpk > 0f) (100.0 / instantLpk.toDouble()).toFloat() else null

/**
 * Instantaneous efficiency in km/ml - primary internal unit.
 * Returns null when speed ≤ 2 km/h (vehicle effectively stopped).
 * Uses Double precision for better accuracy with small values.
 */
internal fun instantKmPerMl(instantMlPerKm: Float?): Float? =
    if (instantMlPerKm != null && instantMlPerKm > 0f) (1000.0 / instantMlPerKm.toDouble()).toFloat() else null

/**
 * Trip average consumption in L/100km.
 * Returns null when trip distance is below the 0.1 km minimum threshold.
 * Fuel amount gate removed — distance gate alone is sufficient.
 * Uses Double precision for better accuracy with small values.
 */
internal fun tripAvgLper100km(fuelUsedL: Float, distKm: Float): Float? =
    if (distKm > 0.1f) ((fuelUsedL.toDouble() * 100.0) / distKm.toDouble()).toFloat() else null

/**
 * Trip average consumption in ml/km - primary internal unit.
 * Returns null when trip distance is below the 0.1 km minimum threshold.
 * Uses Double precision for better accuracy with small values.
 */
internal fun tripAvgMlPerKm(fuelUsedMl: Double, distKm: Float): Float? =
    if (distKm > 0.1f) ((fuelUsedMl / distKm.toDouble()).toFloat()) else null

/**
 * Trip average efficiency in km/L (reciprocal of trip average L/100km).
 * Uses Double precision for better accuracy with small values.
 */
internal fun tripAvgKpl(avgLpk: Float?): Float? =
    if (avgLpk != null && avgLpk > 0f) (100.0 / avgLpk.toDouble()).toFloat() else null

/**
 * Trip average efficiency in km/ml - primary internal unit.
 * Returns null when trip distance is below the 0.5 km minimum threshold.
 * Uses Double precision for better accuracy with small values.
 */
internal fun tripAvgKmPerMl(avgMlPerKm: Float?): Float? =
    if (avgMlPerKm != null && avgMlPerKm > 0f) (1000.0 / avgMlPerKm.toDouble()).toFloat() else null

/**
 * Estimated remaining range in km based on current fuel level and trip average efficiency.
 */
internal fun rangeRemainingKm(fuelLevelPct: Float?, tankCapL: Float, avgLpk: Float?): Float? =
    if (fuelLevelPct != null && avgLpk != null && avgLpk > 0f)
        (fuelLevelPct / 100f * tankCapL) / (avgLpk / 100f)
    else null

/**
 * Estimated fuel cost for the trip.
 * Returns null when price is not configured (≤ 0).
 */
internal fun fuelCost(fuelUsedL: Float, pricePerLitre: Float): Float? =
    if (pricePerLitre > 0f) fuelUsedL * pricePerLitre else null

/**
 * Average CO₂ emissions in g/km.
 * [co2Factor] is the fuel-type-specific factor (g CO₂ per L/100km).
 * Uses Double precision for better accuracy with small consumption values.
 */
internal fun avgCo2gPerKm(avgLpk: Float?, co2Factor: Double): Float? =
    if (avgLpk != null) (avgLpk * co2Factor).toFloat() else null

/**
 * Converts fuel rate from L/h to cc/min.
 * Uses Double precision for better accuracy with small fuel rates.
 */
internal fun fuelFlowCcMin(fuelRateLh: Float?): Float? =
    fuelRateLh?.let { (it * 1000.0 / 60.0).toFloat() }

/**
 * Speed difference between GPS and OBD2 speed (km/h).
 * Useful as a cross-sensor sanity check.
 */
internal fun speedDiff(gpsSpeed: Float?, obdSpeed: Float?): Float? =
    if (gpsSpeed != null && obdSpeed != null) gpsSpeed - obdSpeed else null

/**
 * Trip average speed (km/h) computed from distance and moving time.
 * Returns null when the vehicle has not been moving.
 * Uses Double precision for better accuracy with small distances.
 */
internal fun tripAvgSpeed(distKm: Float, movingSec: Long): Float? =
    if (movingSec > 0L) (distKm.toDouble() / (movingSec / 3600.0)).toFloat() else null
