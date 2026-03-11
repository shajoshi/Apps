package com.sj.obd2app.metrics

import com.sj.obd2app.settings.FuelType
import org.junit.Assert.*
import org.junit.Test

class FuelCalculationsTest {

    private val DELTA = 0.001f

    // ── effectiveFuelRate ─────────────────────────────────────────────────────

    @Test
    fun `effectiveFuelRate returns pid when pid is positive`() {
        val result = effectiveFuelRate(fuelRatePid = 8.5f, maf = 20f, mafLitreFactor = FuelType.PETROL.mafLitreFactor)
        assertEquals(8.5f, result!!, DELTA)
    }

    @Test
    fun `effectiveFuelRate falls back to MAF when pid is null`() {
        // 20 g/s × 0.0000746 L/g × 3600 s/h = 5.3712 L/h
        val expected = (20f * FuelType.PETROL.mafLitreFactor * 3600.0).toFloat()
        val result = effectiveFuelRate(fuelRatePid = null, maf = 20f, mafLitreFactor = FuelType.PETROL.mafLitreFactor)
        assertEquals(expected, result!!, DELTA)
    }

    @Test
    fun `effectiveFuelRate falls back to MAF when pid is zero`() {
        val expected = (10f * FuelType.PETROL.mafLitreFactor * 3600.0).toFloat()
        val result = effectiveFuelRate(fuelRatePid = 0f, maf = 10f, mafLitreFactor = FuelType.PETROL.mafLitreFactor)
        assertEquals(expected, result!!, DELTA)
    }

    @Test
    fun `effectiveFuelRate returns null when both pid and maf are null`() {
        assertNull(effectiveFuelRate(fuelRatePid = null, maf = null, mafLitreFactor = FuelType.PETROL.mafLitreFactor))
    }

    @Test
    fun `effectiveFuelRate returns null when pid is null and maf is zero`() {
        assertNull(effectiveFuelRate(fuelRatePid = null, maf = 0f, mafLitreFactor = FuelType.PETROL.mafLitreFactor))
    }

    @Test
    fun `effectiveFuelRate uses diesel MAF factor correctly`() {
        val expected = (20f * FuelType.DIESEL.mafLitreFactor * 3600.0).toFloat()
        val result = effectiveFuelRate(null, 20f, FuelType.DIESEL.mafLitreFactor)
        assertEquals(expected, result!!, DELTA)
    }

    // ── instantLper100km ──────────────────────────────────────────────────────

    @Test
    fun `instantLper100km returns null when speed is zero`() {
        assertNull(instantLper100km(fuelRateLh = 10f, speedKmh = 0f))
    }

    @Test
    fun `instantLper100km returns null when speed is exactly 2`() {
        assertNull(instantLper100km(fuelRateLh = 10f, speedKmh = 2f))
    }

    @Test
    fun `instantLper100km returns null when fuelRate is null`() {
        assertNull(instantLper100km(fuelRateLh = null, speedKmh = 100f))
    }

    @Test
    fun `instantLper100km correct at 100 kmh and 10 Lh`() {
        // 10 L/h × 100 / 100 km/h = 10 L/100km
        assertEquals(10f, instantLper100km(10f, 100f)!!, DELTA)
    }

    @Test
    fun `instantLper100km correct at 50 kmh and 5 Lh`() {
        // 5 L/h × 100 / 50 km/h = 10 L/100km
        assertEquals(10f, instantLper100km(5f, 50f)!!, DELTA)
    }

    @Test
    fun `instantLper100km correct at 60 kmh and 6 Lh`() {
        assertEquals(10f, instantLper100km(6f, 60f)!!, DELTA)
    }

    // ── instantKpl ────────────────────────────────────────────────────────────

    @Test
    fun `instantKpl returns null when instantLpk is null`() {
        assertNull(instantKpl(null))
    }

    @Test
    fun `instantKpl returns null when instantLpk is zero`() {
        assertNull(instantKpl(0f))
    }

    @Test
    fun `instantKpl correct at 10 L per 100km`() {
        // 100 / 10 = 10 km/L
        assertEquals(10f, instantKpl(10f)!!, DELTA)
    }

    @Test
    fun `instantKpl correct at 5 L per 100km`() {
        // 100 / 5 = 20 km/L
        assertEquals(20f, instantKpl(5f)!!, DELTA)
    }

    // ── tripAvgLper100km ─────────────────────────────────────────────────────

    @Test
    fun `tripAvgLper100km returns null when distKm is below threshold`() {
        assertNull(tripAvgLper100km(fuelUsedL = 1f, distKm = 0.05f))
    }

    @Test
    fun `tripAvgLper100km returns null when distKm is exactly zero`() {
        assertNull(tripAvgLper100km(fuelUsedL = 0f, distKm = 0f))
    }

    @Test
    fun `tripAvgLper100km correct at 100L used over 1000 km`() {
        // 100 × 100 / 1000 = 10 L/100km
        assertEquals(10f, tripAvgLper100km(100f, 1000f)!!, DELTA)
    }

    @Test
    fun `tripAvgLper100km correct at 5L used over 100 km`() {
        assertEquals(5f, tripAvgLper100km(5f, 100f)!!, DELTA)
    }

    // ── tripAvgKpl ───────────────────────────────────────────────────────────

    @Test
    fun `tripAvgKpl returns null when avgLpk is null`() {
        assertNull(tripAvgKpl(null))
    }

    @Test
    fun `tripAvgKpl returns null when avgLpk is zero`() {
        assertNull(tripAvgKpl(0f))
    }

    @Test
    fun `tripAvgKpl correct at 10 L per 100km`() {
        assertEquals(10f, tripAvgKpl(10f)!!, DELTA)
    }

    @Test
    fun `tripAvgKpl correct at 20 L per 100km`() {
        assertEquals(5f, tripAvgKpl(20f)!!, DELTA)
    }

    // ── rangeRemainingKm ─────────────────────────────────────────────────────

    @Test
    fun `rangeRemainingKm returns null when fuelLevelPct is null`() {
        assertNull(rangeRemainingKm(null, 40f, 10f))
    }

    @Test
    fun `rangeRemainingKm returns null when avgLpk is null`() {
        assertNull(rangeRemainingKm(50f, 40f, null))
    }

    @Test
    fun `rangeRemainingKm returns null when avgLpk is zero`() {
        assertNull(rangeRemainingKm(50f, 40f, 0f))
    }

    @Test
    fun `rangeRemainingKm correct at 50pct fuel 40L tank 10L per 100km`() {
        // 0.5 × 40 = 20 L remaining; 20 / (10/100) = 200 km
        assertEquals(200f, rangeRemainingKm(50f, 40f, 10f)!!, DELTA)
    }

    @Test
    fun `rangeRemainingKm correct at 100pct fuel 60L tank 8L per 100km`() {
        // 60 / 0.08 = 750 km
        assertEquals(750f, rangeRemainingKm(100f, 60f, 8f)!!, DELTA)
    }

    // ── fuelCost ─────────────────────────────────────────────────────────────

    @Test
    fun `fuelCost returns null when price is zero`() {
        assertNull(fuelCost(10f, 0f))
    }

    @Test
    fun `fuelCost returns null when price is negative`() {
        assertNull(fuelCost(10f, -1f))
    }

    @Test
    fun `fuelCost correct at 5L and 1_50 per litre`() {
        assertEquals(7.5f, fuelCost(5f, 1.5f)!!, DELTA)
    }

    @Test
    fun `fuelCost correct at 30L and 2_00 per litre`() {
        assertEquals(60f, fuelCost(30f, 2f)!!, DELTA)
    }

    // ── avgCo2gPerKm ─────────────────────────────────────────────────────────

    @Test
    fun `avgCo2gPerKm returns null when avgLpk is null`() {
        assertNull(avgCo2gPerKm(null, FuelType.PETROL.co2Factor))
    }

    @Test
    fun `avgCo2gPerKm correct for petrol at 10 L per 100km`() {
        // 10 × 23.1 = 231 g/km
        assertEquals(231f, avgCo2gPerKm(10f, FuelType.PETROL.co2Factor)!!, 0.01f)
    }

    @Test
    fun `avgCo2gPerKm correct for diesel at 8 L per 100km`() {
        // 8 × 26.4 = 211.2 g/km
        assertEquals(211.2f, avgCo2gPerKm(8f, FuelType.DIESEL.co2Factor)!!, 0.01f)
    }

    // ── fuelFlowCcMin ─────────────────────────────────────────────────────────

    @Test
    fun `fuelFlowCcMin returns null when fuelRate is null`() {
        assertNull(fuelFlowCcMin(null))
    }

    @Test
    fun `fuelFlowCcMin correct at 6 Lh`() {
        // 6 × 1000 / 60 = 100 cc/min
        assertEquals(100f, fuelFlowCcMin(6f)!!, DELTA)
    }

    @Test
    fun `fuelFlowCcMin correct at 1_2 Lh`() {
        // 1.2 × 1000 / 60 = 20 cc/min
        assertEquals(20f, fuelFlowCcMin(1.2f)!!, DELTA)
    }

    // ── speedDiff ────────────────────────────────────────────────────────────

    @Test
    fun `speedDiff returns null when gpsSpeed is null`() {
        assertNull(speedDiff(null, 60f))
    }

    @Test
    fun `speedDiff returns null when obdSpeed is null`() {
        assertNull(speedDiff(60f, null))
    }

    @Test
    fun `speedDiff returns null when both are null`() {
        assertNull(speedDiff(null, null))
    }

    @Test
    fun `speedDiff correct positive difference`() {
        assertEquals(3f, speedDiff(63f, 60f)!!, DELTA)
    }

    @Test
    fun `speedDiff correct negative difference`() {
        assertEquals(-2f, speedDiff(58f, 60f)!!, DELTA)
    }

    @Test
    fun `speedDiff zero when equal`() {
        assertEquals(0f, speedDiff(60f, 60f)!!, DELTA)
    }

    // ── tripAvgSpeed ─────────────────────────────────────────────────────────

    @Test
    fun `tripAvgSpeed returns null when movingSec is zero`() {
        assertNull(tripAvgSpeed(100f, 0L))
    }

    @Test
    fun `tripAvgSpeed correct at 100km over 3600s`() {
        // 100 km / (3600 s / 3600) = 100 km/h
        assertEquals(100f, tripAvgSpeed(100f, 3600L)!!, DELTA)
    }

    @Test
    fun `tripAvgSpeed correct at 50km over 1800s`() {
        // 50 km / (1800/3600) = 100 km/h
        assertEquals(100f, tripAvgSpeed(50f, 1800L)!!, DELTA)
    }

    @Test
    fun `tripAvgSpeed correct at 30km over 3600s`() {
        assertEquals(30f, tripAvgSpeed(30f, 3600L)!!, DELTA)
    }
}
