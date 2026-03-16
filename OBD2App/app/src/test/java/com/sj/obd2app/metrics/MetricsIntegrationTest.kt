package com.sj.obd2app.metrics

import com.sj.obd2app.metrics.calculator.FuelCalculator
import com.sj.obd2app.metrics.calculator.PowerCalculator
import com.sj.obd2app.metrics.calculator.TripCalculator
import com.sj.obd2app.settings.FuelType
import com.sj.obd2app.settings.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration tests to verify the complete metrics calculation pipeline.
 * These tests catch unit conversion bugs that individual unit tests might miss.
 */
class MetricsIntegrationTest {

    private val fuelCalculator = FuelCalculator()
    private val powerCalculator = PowerCalculator()
    private val tripCalculator = TripCalculator()
    private val DELTA = 0.01f

    // ── Real-World Scenario Tests ─────────────────────────────────────────────────────

    @Test
    fun `Ronin scooter cruising scenario produces realistic values`() {
        val profile = VehicleProfile(
            name = "Ronin Test",
            fuelType = FuelType.E20,
            tankCapacityL = 14f,
            enginePowerBhp = 20f,
            vehicleMassKg = 240f
        )

        // Simulate cruising at 60 km/h
        val fuelRateLh = 3.5f
        val speedKmh = 60f
        val rpm = 3000f
        val actualTorquePct = 40f
        val refTorqueNm = 50
        val fuelLevel = 0.7f

        // Calculate all metrics
        val fuelRateEffective = fuelCalculator.effectiveFuelRate(fuelRateLh, null, profile.fuelType.mafMlPerGram)
        val (instantLpk, instantKpl) = fuelCalculator.instantaneous(fuelRateEffective, speedKmh)
        val (tripAvgLpk, tripAvgKpl) = fuelCalculator.tripAverages(2f, 100f) // 2L used in 100km (2.0 L/100km)
        val range = fuelCalculator.range(fuelLevel, profile.tankCapacityL, tripAvgLpk)
        val powerThermo = powerCalculator.thermodynamic(fuelRateEffective, profile.fuelType.energyDensityMJpL)
        val powerOBD = powerCalculator.fromObd(actualTorquePct, refTorqueNm, rpm)

        // Verify all values are realistic
        assertNotNull(fuelRateEffective)
        assertEquals(3.5f, fuelRateEffective!!, DELTA)

        assertNotNull(instantLpk)
        assertNotNull(instantKpl)
        assertTrue("Instant L/100km should be reasonable", instantLpk!! in 3f..15f)
        assertTrue("Instant km/L should be reasonable", instantKpl!! in 5f..30f)

        assertNotNull(tripAvgLpk)
        assertNotNull(tripAvgKpl)
        assertTrue("Trip avg L/100km should be reasonable", tripAvgLpk!! in 1f..15f)
        assertTrue("Trip avg km/L should be reasonable", tripAvgKpl!! in 5f..100f)

        assertNotNull(range)
        assertTrue("Range should be reasonable for scooter", range!! in 1f..50f)

        assertNotNull(powerThermo)
        assertTrue("Thermo power should be in kW range, not MW", powerThermo!! < 1000f)
        assertTrue("Thermo power should be reasonable for scooter", powerThermo!! in 5f..20f)

        assertNotNull(powerOBD)
        assertTrue("OBD power should be in kW range, not MW", powerOBD!! < 1000f)
        assertTrue("OBD power should be reasonable for scooter", powerOBD!! in 5f..25f)

        // Verify BHP conversions are reasonable
        val thermoBhp = powerThermo!! * 1.341f
        val obdBhp = powerOBD!! * 1.341f
        assertTrue("Thermo BHP should be reasonable", thermoBhp in 5f..30f)
        assertTrue("OBD BHP should be reasonable", obdBhp in 5f..35f)
    }

    @Test
    fun `Car highway cruising scenario produces realistic values`() {
        val profile = VehicleProfile(
            name = "Test Car",
            fuelType = FuelType.PETROL,
            tankCapacityL = 50f,
            enginePowerBhp = 100f,
            vehicleMassKg = 1500f
        )

        // Simulate highway cruising at 100 km/h
        val fuelRateLh = 8f
        val speedKmh = 100f
        val rpm = 2500f
        val actualTorquePct = 30f
        val refTorqueNm = 200
        val fuelLevel = 0.6f

        // Calculate all metrics
        val fuelRateEffective = fuelCalculator.effectiveFuelRate(fuelRateLh, null, profile.fuelType.mafMlPerGram)
        val (instantLpk, instantKpl) = fuelCalculator.instantaneous(fuelRateEffective, speedKmh)
        val (tripAvgLpk, tripAvgKpl) = fuelCalculator.tripAverages(8f, 100f) // 8L used in 100km (8.0 L/100km)
        val range = fuelCalculator.range(fuelLevel, profile.tankCapacityL, tripAvgLpk)
        val powerThermo = powerCalculator.thermodynamic(fuelRateEffective, profile.fuelType.energyDensityMJpL)
        val powerOBD = powerCalculator.fromObd(actualTorquePct, refTorqueNm, rpm)

        // Verify all values are realistic
        assertNotNull(fuelRateEffective)
        assertEquals(8f, fuelRateEffective!!, DELTA)

        assertNotNull(instantLpk)
        assertNotNull(instantKpl)
        assertTrue("Instant L/100km should be reasonable for car", instantLpk!! in 5f..20f)
        assertTrue("Instant km/L should be reasonable for car", instantKpl!! in 3f..15f)

        assertNotNull(tripAvgLpk)
        assertNotNull(tripAvgKpl)
        assertTrue("Trip avg L/100km should be reasonable for car", tripAvgLpk!! in 3f..20f)
        assertTrue("Trip avg km/L should be reasonable for car", tripAvgKpl!! in 3f..30f)

        assertNotNull(range)
        assertTrue("Range should be reasonable for car", range!! in 1f..50f)

        assertNotNull(powerThermo)
        assertTrue("Thermo power should be in kW range, not MW", powerThermo!! < 1000f)
        assertTrue("Thermo power should be reasonable for car", powerThermo!! in 20f..100f)

        assertNotNull(powerOBD)
        assertTrue("OBD power should be in kW range, not MW", powerOBD!! < 1000f)
        assertTrue("OBD power should be reasonable for car", powerOBD!! in 15f..80f)

        // Verify BHP conversions are reasonable
        val thermoBhp = powerThermo!! * 1.341f
        val obdBhp = powerOBD!! * 1.341f
        assertTrue("Thermo BHP should be reasonable for car", thermoBhp in 25f..135f)
        assertTrue("OBD BHP should be reasonable for car", obdBhp in 20f..110f)
    }

    // ── Unit Conversion Bug Detection Tests ───────────────────────────────────────────

    @Test
    fun `detects kW vs Watt conversion bugs in power calculations`() {
        val fuelRate = 5f
        val energyDensity = FuelType.PETROL.energyDensityMJpL

        val powerResult = powerCalculator.thermodynamic(fuelRate, energyDensity)

        assertNotNull(powerResult)
        
        // This is the key test: power should be in kW, not W
        // If there's a kW vs W bug, this will be 1000x too large
        assertTrue("Power should be in reasonable kW range (not MW)", powerResult!! < 1000f)
        assertTrue("Power should be positive for valid inputs", powerResult!! > 0f)
        
        // Expected calculation: (5 L/h × 34.2 MJ/L ÷ 3.6) × 0.35 ≈ 16.6 kW
        val expected = ((5.0 * 34.2 / 3.6) * 0.35).toFloat()
        assertEquals(expected, powerResult, 1.0f) // Allow some tolerance
    }

    @Test
    fun `detects unit conversion bugs in fuel efficiency calculations`() {
        val fuelRate = 4f
        val speed = 80f

        val (lpk, kpl) = fuelCalculator.instantaneous(fuelRate, speed)

        assertNotNull(lpk)
        assertNotNull(kpl)
        
        // These should be reasonable fuel efficiency values
        // If there's a unit conversion bug, these would be way off
        assertTrue("L/100km should be reasonable", lpk!! in 1f..100f)
        assertTrue("km/L should be reasonable", kpl!! in 1f..100f)
        
        // Verify the reciprocal relationship
        assertEquals(100f / lpk, kpl, 0.1f)
    }

    @Test
    fun `detects ml_per_min vs L_per_h conversion bugs`() {
        val fuelRateLh = 6f

        // Test both calculation methods
        val lhResult = fuelCalculator.instantaneous(fuelRateLh, 60f)
        val fuelRateMlMin = (fuelRateLh * 1000f / 60f) // Convert L/h to ml/min
        val mlResult = fuelCalculator.instantaneousMl(fuelRateMlMin, 60f)

        assertNotNull(lhResult)
        assertNotNull(mlResult)
        
        // Results should be very close if conversions are correct
        assertEquals("L/h and ml/min methods should give similar results", 
                    lhResult!!.first!!, mlResult!!.first!!, 0.5f)
        assertEquals("L/h and ml/min methods should give similar results", 
                    lhResult!!.second!!, mlResult!!.second!!, 0.5f)
    }

    // ── Consistency Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `power calculations are consistent across different methods`() {
        val fuelRate = 3f
        val energyDensity = FuelType.PETROL.energyDensityMJpL

        // Thermodynamic power
        val thermoPower = powerCalculator.thermodynamic(fuelRate, energyDensity)

        // Estimate equivalent OBD power (rough approximation)
        val estimatedObdPower = powerCalculator.fromObd(50f, 150, 2500f)

        assertNotNull(thermoPower)
        assertNotNull(estimatedObdPower)

        // Both should be in reasonable kW range
        assertTrue("Thermo power should be reasonable", thermoPower!! in 5f..50f)
        assertTrue("OBD power should be reasonable", estimatedObdPower!! in 5f..50f)

        // They should be in the same order of magnitude (within factor of 3)
        val ratio = thermoPower!! / estimatedObdPower!!
        assertTrue("Power estimates should be in same order of magnitude", ratio in 0.33f..3.0f)
    }

    @Test
    fun `fuel efficiency calculations are mathematically consistent`() {
        val fuelRate = 5f
        val speed = 90f

        val (lpk, kpl) = fuelCalculator.instantaneous(fuelRate, speed)

        assertNotNull(lpk)
        assertNotNull(kpl)

        // Mathematical consistency: L/100km × km/L should equal 100
        val product = lpk!! * kpl!!
        assertEquals(100f, product, 0.1f)

        // Test with different values
        val (lpk2, kpl2) = fuelCalculator.instantaneous(8f, 120f)
        assertEquals(100f, lpk2!! * kpl2!!, 0.1f)
    }

    // ── Edge Case Integration Tests ───────────────────────────────────────────────────

    @Test
    fun `all calculations handle zero speed gracefully`() {
        val fuelRate = 3f
        val speed = 0f

        val (lpk, kpl) = fuelCalculator.instantaneous(fuelRate, speed)
        val powerAccel = powerCalculator.fromAccelerometer(1000f, 1f, 0f)

        // Should handle zero speed without crashes
        assertEquals(Pair(0f, 0f), Pair(lpk, kpl))
        assertNull(powerAccel) // Accelerometer power should be null at zero speed
    }

    @Test
    fun `all calculations handle null inputs gracefully`() {
        val powerThermo = powerCalculator.thermodynamic(null, FuelType.PETROL.energyDensityMJpL)
        val powerObd = powerCalculator.fromObd(null, 200, 3000f)
        val (lpk, kpl) = fuelCalculator.instantaneous(null, 60f)

        assertNull(powerThermo)
        assertNull(powerObd)
        assertEquals(Pair(0f, 0f), Pair(lpk, kpl))
    }

    // ── Performance Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `calculations complete quickly`() {
        val startTime = System.nanoTime()

        // Run many calculations
        repeat(1000) {
            fuelCalculator.instantaneous(3.5f, 60f)
            powerCalculator.thermodynamic(3.5f, FuelType.PETROL.energyDensityMJpL)
            powerCalculator.fromObd(50f, 200, 3000f)
        }

        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000

        // Should complete quickly (less than 100ms for 1000 iterations)
        assertTrue("Calculations should be fast", durationMs < 100)
    }
}
