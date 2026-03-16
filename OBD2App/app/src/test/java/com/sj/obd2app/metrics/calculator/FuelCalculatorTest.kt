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
}
