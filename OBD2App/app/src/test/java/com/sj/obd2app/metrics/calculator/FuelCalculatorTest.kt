package com.sj.obd2app.metrics.calculator

import com.sj.obd2app.settings.FuelType
import org.junit.Assert.*
import org.junit.Test

class FuelCalculatorTest {

    private val fuelCalculator = FuelCalculator()
    private val DELTA = 0.01f

    // ── Unit Consistency Tests ─────────────────────────────────────────────────────
    // These tests specifically check for unit conversion bugs

    @Test
    fun `effectiveFuelRate returns correct units`() {
        // Test with fuel rate PID in L/h
        val result = fuelCalculator.effectiveFuelRate(5f, null, FuelType.PETROL.mafMlPerGram)
        
        assertNotNull(result)
        assertEquals(5f, result!!, DELTA) // Should return the same value in L/h
    }

    @Test
    fun `effectiveFuelRateMlMin returns correct units`() {
        // Test with fuel rate PID in L/h, should convert to ml/min
        val result = fuelCalculator.effectiveFuelRateMlMin(5f, null, FuelType.PETROL.mafMlPerGram)
        
        assertNotNull(result)
        // 5 L/h = 5000 ml/h = 83.33 ml/min
        val expected = (5.0 * 1000.0 / 60.0).toFloat()
        assertEquals(expected, result!!, DELTA)
    }

    @Test
    fun `instantaneous fuel efficiency returns realistic values`() {
        // Test with realistic inputs: 3.5 L/h at 60 km/h
        val result = fuelCalculator.instantaneous(3.5f, 60f)
        
        assertNotNull(result)
        val (lpk, kpl) = result!!
        
        // Should be around 5.8 L/100km and 17.2 km/L
        assertEquals(5.83f, lpk!!, 0.01f)
        assertEquals(17.17f, kpl!!, 0.1f)
        
        // Values should be realistic for a vehicle
        assertTrue("L/100km should be positive", lpk!! > 0f)
        assertTrue("km/L should be positive", kpl!! > 0f)
        assertTrue("L/100km should be reasonable", lpk!! in 1f..50f)
        assertTrue("km/L should be reasonable", kpl!! in 1f..100f)
    }

    @Test
    fun `trip averages return realistic values`() {
        // Test with realistic trip data: 5L fuel, 100km distance
        val result = fuelCalculator.tripAverages(5f, 100f)
        
        assertNotNull(result)
        val (lpk, kpl) = result!!
        
        // Should be exactly 5 L/100km and 20 km/L
        assertEquals(5f, lpk!!, DELTA)
        assertEquals(20f, kpl!!, DELTA)
    }

    // ── Cross-Validation Tests ───────────────────────────────────────────────────────
    // These tests compare FuelCalculator with FuelCalculations to ensure consistency

    
    // ── Edge Case Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `effectiveFuelRate handles edge cases correctly`() {
        // Test null inputs
        assertNull(fuelCalculator.effectiveFuelRate(null, null, FuelType.PETROL.mafMlPerGram))
        
        // Test zero/negative values
        assertNull(fuelCalculator.effectiveFuelRate(0f, null, FuelType.PETROL.mafMlPerGram))
        assertNull(fuelCalculator.effectiveFuelRate(-1f, null, FuelType.PETROL.mafMlPerGram))
    }

    @Test
    fun `instantaneous handles edge cases correctly`() {
        // Test null fuel rate
        val result1 = fuelCalculator.instantaneous(null, 60f)
        assertEquals(Pair(0f, 0f), result1)
        
        // Test zero/negative fuel rate
        val result2 = fuelCalculator.instantaneous(0f, 60f)
        assertEquals(Pair(0f, 0f), result2)
        
        // Test zero/negative speed
        val result3 = fuelCalculator.instantaneous(3.5f, 0f)
        assertEquals(Pair(0f, 0f), result3)
        
        val result4 = fuelCalculator.instantaneous(3.5f, -1f)
        assertEquals(Pair(0f, 0f), result4)
    }

    @Test
    fun `trip averages handles threshold correctly`() {
        // Test below threshold (should return 0,0)
        val result1 = fuelCalculator.tripAverages(0.1f, 0.05f) // 0.05 km < 0.1 km threshold
        assertEquals(Pair(0f, 0f), result1)
        
        // Test above threshold (should calculate)
        val result2 = fuelCalculator.tripAverages(0.1f, 0.2f) // 0.2 km > 0.1 km threshold
        assertNotNull(result2)
        assertTrue("Should calculate positive values", result2.first!! > 0f)
    }

    // ── MAF Fallback Tests ────────────────────────────────────────────────────────────

    @Test
    fun `effectiveFuelRate uses MAF when fuel rate PID is null`() {
        val maf = 15f // g/s
        val result = fuelCalculator.effectiveFuelRate(null, maf, FuelType.PETROL.mafMlPerGram)
        
        assertNotNull(result)
        assertTrue("Should calculate positive fuel rate from MAF", result!! > 0f)
        
        // Expected: 15 g/s × 0.09195 ml/g (1000/(AFR 14.7 × density 740)) / 1000 × 3600 = 4.96 L/h
        val expected = (15.0 * FuelType.PETROL.mafMlPerGram / 1000.0 * 3600.0).toFloat()
        assertEquals(expected, result!!, DELTA)
    }

    @Test
    fun `effectiveFuelRate prefers fuel rate PID over MAF`() {
        val fuelRatePid = 5f
        val maf = 15f
        val result = fuelCalculator.effectiveFuelRate(fuelRatePid, maf, FuelType.PETROL.mafMlPerGram)
        
        // Should use fuel rate PID, not MAF
        assertEquals(fuelRatePid, result!!, DELTA)
    }

    // ── Range Calculation Tests ───────────────────────────────────────────────────────

    @Test
    fun `range calculation returns realistic values`() {
        val fuelLevel = 0.7f // 70%
        val tankCapacity = 40f // 40L
        val avgConsumption = 8f // 8 L/100km
        
        val result = fuelCalculator.range(fuelLevel, tankCapacity, avgConsumption)
        
        assertNotNull(result)
        // Expected: (0.7 × 40) = 28L remaining, 28L / 8L per 100km = 3.5 × 100km = 350 km
        val expected = ((fuelLevel / 100f) * tankCapacity / avgConsumption * 100f)
        assertEquals(expected, result!!, DELTA)
    }

    @Test
    fun `range handles edge cases correctly`() {
        // Test null fuel level
        assertNull(fuelCalculator.range(null, 40f, 8f))
        
        // Test zero consumption
        assertNull(fuelCalculator.range(0.5f, 40f, 0f))
        
        // Test negative consumption
        assertNull(fuelCalculator.range(0.5f, 40f, -1f))
    }

    // ── Scaling Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `instantaneous fuel efficiency scales correctly`() {
        // Test that L/100km increases with higher fuel rate
        val p1 = fuelCalculator.instantaneous(2f, 60f)!!
        val p2 = fuelCalculator.instantaneous(4f, 60f)!!
        assertTrue("Higher fuel rate should increase L/100km", p2.first!! > p1.first!!)
        assertTrue("Higher fuel rate should decrease km/L", p2.second!! < p1.second!!)
        
        // Test that L/100km decreases with higher speed
        val p3 = fuelCalculator.instantaneous(3f, 30f)!!
        val p4 = fuelCalculator.instantaneous(3f, 60f)!!
        assertTrue("Higher speed should decrease L/100km", p4.first!! < p3.first!!)
        assertTrue("Higher speed should increase km/L", p4.second!! > p3.second!!)
    }

    @Test
    fun `trip averages scale correctly`() {
        val p1 = fuelCalculator.tripAverages(5f, 100f)!!
        val p2 = fuelCalculator.tripAverages(10f, 100f)!! // Double fuel
        assertEquals(p1.first!! * 2f, p2.first!!, DELTA)
        assertEquals(p1.second!! * 0.5f, p2.second!!, DELTA)
        
        val p3 = fuelCalculator.tripAverages(5f, 200f)!! // Double distance
        assertEquals(p1.first!! * 0.5f, p3.first!!, DELTA)
        assertEquals(p1.second!! * 2f, p3.second!!, DELTA)
    }

    // ── ml/min Based Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `ml_per_min based calculations match L_per_h based calculations`() {
        val fuelRateLh = 3.5f
        val speedKmh = 60f
        
        // Compare L/h based vs ml/min based instantaneous
        val lhResult = fuelCalculator.instantaneous(fuelRateLh, speedKmh)
        val fuelRateMlMin = (fuelRateLh * 1000f / 60f) // Convert L/h to ml/min
        val mlResult = fuelCalculator.instantaneousMl(fuelRateMlMin, speedKmh)
        
        assertNotNull(lhResult)
        assertNotNull(mlResult)
        
        // Results should be very close
        assertEquals(lhResult!!.first!!, mlResult!!.first!!, 0.1f)
        assertEquals(lhResult!!.second!!, mlResult!!.second!!, 0.1f)
    }

    @Test
    fun `ml_per_min trip averages match L_per_h trip averages`() {
        val fuelUsedL = 2f
        val distanceKm = 50f
        
        // Compare L/h based vs ml/min based trip averages
        val lhResult = fuelCalculator.tripAverages(fuelUsedL, distanceKm)
        val mlResult = fuelCalculator.tripAveragesMl(fuelUsedL * 1000.0, distanceKm)
        
        assertNotNull(lhResult)
        assertNotNull(mlResult)
        
        // Results should be very close
        assertEquals(lhResult!!.first!!, mlResult!!.first!!, 0.1f)
        assertEquals(lhResult!!.second!!, mlResult!!.second!!, 0.1f)
    }

    // ── Speed-Density Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `speedDensityMafGs returns correct MAF for Alto idle`() {
        // Alto K10 idle: MAP=26 kPa, IAT=40°C, RPM=704, displacement=998cc, VE=85%
        val result = fuelCalculator.speedDensityMafGs(
            mapKpa = 26f, iatC = 40f, rpm = 704f,
            displacementCc = 998, vePct = 85f
        )

        assertNotNull(result)
        // Expected: (26 × 0.998 × 0.85 × 704) / (34.44 × 313.15) ≈ 1.44 g/s
        assertTrue("MAF should be around 1.4 g/s for Alto idle", result!! in 1.2f..1.6f)
    }

    @Test
    fun `speedDensityMafGs returns correct MAF for highway cruise`() {
        // Alto cruising: MAP=70 kPa, IAT=45°C, RPM=3000, displacement=998cc, VE=85%
        val result = fuelCalculator.speedDensityMafGs(
            mapKpa = 70f, iatC = 45f, rpm = 3000f,
            displacementCc = 998, vePct = 85f
        )

        assertNotNull(result)
        // Higher MAP + RPM → significantly higher MAF
        assertTrue("MAF should be >10 g/s at highway cruise", result!! > 10f)
        assertTrue("MAF should be <30 g/s for 1.0L engine", result!! < 30f)
    }

    @Test
    fun `speedDensityMafGs scales with RPM`() {
        val low = fuelCalculator.speedDensityMafGs(50f, 40f, 1000f, 998, 85f)
        val high = fuelCalculator.speedDensityMafGs(50f, 40f, 3000f, 998, 85f)

        assertNotNull(low)
        assertNotNull(high)
        // Triple RPM → triple MAF (same MAP, IAT, VE)
        assertEquals(low!! * 3f, high!!, 0.1f)
    }

    @Test
    fun `speedDensityMafGs returns null for missing inputs`() {
        // Null MAP
        assertNull(fuelCalculator.speedDensityMafGs(null, 40f, 704f, 998, 85f))
        // Null IAT
        assertNull(fuelCalculator.speedDensityMafGs(26f, null, 704f, 998, 85f))
        // Null RPM
        assertNull(fuelCalculator.speedDensityMafGs(26f, 40f, null, 998, 85f))
        // Zero displacement (not set)
        assertNull(fuelCalculator.speedDensityMafGs(26f, 40f, 704f, 0, 85f))
        // Zero MAP
        assertNull(fuelCalculator.speedDensityMafGs(0f, 40f, 704f, 998, 85f))
        // Zero RPM
        assertNull(fuelCalculator.speedDensityMafGs(26f, 40f, 0f, 998, 85f))
        // Zero VE
        assertNull(fuelCalculator.speedDensityMafGs(26f, 40f, 704f, 998, 0f))
    }

    @Test
    fun `effectiveFuelRate falls back to Speed-Density when MAF is null`() {
        // No fuel rate PID, no MAF, but Speed-Density params available
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null, maf = null,
            mafMlPerGram = FuelType.PETROL.mafMlPerGram,
            mapKpa = 26f, iatC = 40f, rpm = 704f,
            displacementCc = 998, vePct = 85f
        )

        assertNotNull(result)
        // Alto idle: ~0.48 L/h — should be reasonable for 1.0L NA idle
        assertTrue("Fuel rate should be > 0.2 L/h at idle", result!! > 0.2f)
        assertTrue("Fuel rate should be < 1.5 L/h at idle", result!! < 1.5f)
    }

    @Test
    fun `effectiveFuelRate prefers MAF over Speed-Density`() {
        val maf = 15f // g/s
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null, maf = maf,
            mafMlPerGram = FuelType.PETROL.mafMlPerGram,
            mapKpa = 26f, iatC = 40f, rpm = 704f,
            displacementCc = 998, vePct = 85f
        )

        // Should use MAF, not Speed-Density
        val expectedFromMaf = (maf * FuelType.PETROL.mafMlPerGram / 1000.0 * 3600.0).toFloat()
        assertEquals(expectedFromMaf, result!!, DELTA)
    }

    @Test
    fun `effectiveFuelRate returns null when no source available`() {
        // No fuel rate PID, no MAF, no displacement → all three tiers fail
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null, maf = null,
            mafMlPerGram = FuelType.PETROL.mafMlPerGram,
            mapKpa = 26f, iatC = 40f, rpm = 704f,
            displacementCc = 0, vePct = 85f
        )

        assertNull(result)
    }

    @Test
    fun `effectiveFuelRateMlMin falls back to Speed-Density`() {
        val result = fuelCalculator.effectiveFuelRateMlMin(
            fuelRatePid = null, maf = null,
            mafMlPerGram = FuelType.PETROL.mafMlPerGram,
            mapKpa = 26f, iatC = 40f, rpm = 704f,
            displacementCc = 998, vePct = 85f
        )

        assertNotNull(result)
        // Should be consistent with L/h result: ~0.48 L/h = ~8 ml/min
        assertTrue("ml/min should be > 3 at idle", result!! > 3f)
        assertTrue("ml/min should be < 25 at idle", result!! < 25f)
    }

    // ── Diesel Boost Correction Tests ────────────────────────────────────────────

    @Test
    fun `calculateBoostPressure returns correct values`() {
        // Positive boost
        assertEquals(48f, fuelCalculator.calculateBoostPressure(141f, 93f), DELTA)
        
        // No boost (atmospheric)
        assertEquals(0f, fuelCalculator.calculateBoostPressure(93f, 93f), DELTA)
        
        // Vacuum (negative boost)
        assertEquals(-1f, fuelCalculator.calculateBoostPressure(92f, 93f), DELTA)
    }

    @Test
    fun `calculateDieselAfrCorrection returns 1_0 for non-diesel`() {
        val correction = fuelCalculator.calculateDieselAfrCorrection(
            mafGs = 15f,
            engineLoadPct = 64f,
            mapKpa = 141f, // 93 kPa barometric + 48 kPa boost
            baroKpa = 93f,
            fuelType = FuelType.PETROL
        )
        
        assertEquals(1.0, correction, DELTA.toDouble())
    }

    @Test
    fun `calculateDieselAfrCorrection heavy boost scenario`() {
        // Sample 477: 1453 RPM, 64.3% load, +48 kPa boost
        val correction = fuelCalculator.calculateDieselAfrCorrection(
            mafGs = 18f,
            engineLoadPct = 64.3f,
            mapKpa = 141f, // 93 kPa barometric + 48 kPa boost
            baroKpa = 93f,
            fuelType = FuelType.DIESEL
        )
        
        // Expected: 0.85 (boost) × 0.95 (RPM) × 1.05 (load) = 0.848
        assertEquals(0.848, correction, 0.01)
    }

    @Test
    fun `calculateDieselAfrCorrection light load vacuum scenario`() {
        // Sample 186: 1007 RPM, 27.1% load, -1 kPa boost (vacuum)
        val correction = fuelCalculator.calculateDieselAfrCorrection(
            mafGs = 8f,
            engineLoadPct = 27.1f,
            mapKpa = 92f, // 93 kPa barometric - 1 kPa vacuum
            baroKpa = 93f,
            fuelType = FuelType.DIESEL
        )
        
        // Expected: 0.40 (vacuum) × 0.90 (low RPM) × 0.95 (light load) = 0.342
        assertEquals(0.342, correction, 0.01)
    }

    @Test
    fun `calculateDieselAfrCorrection medium boost scenario`() {
        // Sample 196: 1625 RPM, 50.6% load, +1 kPa boost
        val correction = fuelCalculator.calculateDieselAfrCorrection(
            mafGs = 12f,
            engineLoadPct = 50.6f,
            mapKpa = 94f, // 93 kPa barometric + 1 kPa boost
            baroKpa = 93f,
            fuelType = FuelType.DIESEL
        )
        
        // Expected: 0.45 (minimal boost) × 1.00 (optimal RPM) × 1.00 (medium load) = 0.45
        assertEquals(0.45, correction, 0.01)
    }

    @Test
    fun `effectiveFuelRate applies diesel correction`() {
        // Sample 477: Heavy boost scenario
        val maf = 9.98f  // g/s
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null,
            maf = maf,
            mafMlPerGram = FuelType.DIESEL.mafMlPerGram,
            mapKpa = 141f,
            iatC = 32f,
            rpm = 1453f,
            displacementCc = 1248,
            vePct = 85f,
            fuelType = FuelType.DIESEL,
            baroKpa = 93f,
            engineLoadPct = 64.3f
        )
        
        // Without correction: 9.98 × 0.08210 × 3600 / 1000 = 2.95 L/h
        // With correction (0.848): 2.95 × 0.848 = 2.50 L/h
        assertNotNull(result)
        assertEquals(2.50f, result!!, 0.1f)
    }

    @Test
    fun `effectiveFuelRate no correction for petrol`() {
        val maf = 15f  // g/s
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null,
            maf = maf,
            mafMlPerGram = FuelType.PETROL.mafMlPerGram,
            mapKpa = 141f,
            iatC = 32f,
            rpm = 1453f,
            fuelType = FuelType.PETROL,
            baroKpa = 93f,
            engineLoadPct = 64.3f
        )
        
        // Should use standard calculation without correction
        // 15 × 0.09195 × 3600 / 1000 = 4.96 L/h
        assertNotNull(result)
        assertEquals(4.96f, result!!, 0.01f)
    }

    @Test
    fun `effectiveFuelRate diesel correction with missing parameters defaults to no correction`() {
        val maf = 9.98f  // g/s
        val result = fuelCalculator.effectiveFuelRate(
            fuelRatePid = null,
            maf = maf,
            mafMlPerGram = FuelType.DIESEL.mafMlPerGram,
            mapKpa = 141f,
            iatC = 32f,
            rpm = 1453f,
            fuelType = FuelType.DIESEL,
            baroKpa = null,  // Missing baro
            engineLoadPct = 64.3f
        )
        
        // Without correction (missing baro): 9.98 × 0.08210 × 3600 / 1000 = 2.95 L/h
        assertNotNull(result)
        assertEquals(2.95f, result!!, 0.1f)
    }

    @Test
    fun `effectiveFuelRateMlMin applies diesel correction`() {
        // Sample 477: Heavy boost scenario
        val maf = 9.98f  // g/s
        val result = fuelCalculator.effectiveFuelRateMlMin(
            fuelRatePid = null,
            maf = maf,
            mafMlPerGram = FuelType.DIESEL.mafMlPerGram,
            mapKpa = 141f,
            iatC = 32f,
            rpm = 1453f,
            displacementCc = 1248,
            vePct = 85f,
            fuelType = FuelType.DIESEL,
            baroKpa = 93f,
            engineLoadPct = 64.3f
        )
        
        // Without correction: 9.98 × 0.08210 × 60 = 49.16 ml/min
        // With correction (0.848): 49.16 × 0.848 = 41.69 ml/min
        assertNotNull(result)
        assertEquals(41.69f, result!!, 1.0f)
    }
}
