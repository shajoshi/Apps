package com.sj.obd2app.metrics.calculator

import com.sj.obd2app.settings.FuelType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class PowerCalculatorTest {

    private val powerCalculator = PowerCalculator()
    private val DELTA = 0.01f

    // ── Unit Consistency Tests ─────────────────────────────────────────────────────
    // These tests specifically check for unit conversion bugs

    @Test
    fun `thermodynamic power returns kW not Watts`() {
        // With 10 L/h and petrol (34.2 MJ/L), should be ~33 kW, not 33,000 kW
        val result = powerCalculator.thermodynamic(10f, FuelType.PETROL.energyDensityMJpL)
        
        // Should be in kW range (tens of kW), not MW range (thousands of kW)
        assertNotNull(result)
        assertTrue("Power should be in kW range, not MW", result!! < 1000f)
        assertTrue("Power should be positive for valid inputs", result > 0f)
        
        // Expected: (10 L/h × 34.2 MJ/L ÷ 3.6) × 0.35 ≈ 33.3 kW
        val expected = ((10.0 * 34.2 / 3.6) * 0.35).toFloat()
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `accelerometer power returns kW not Watts`() {
        // With 1500kg, 2 m/s², 20 m/s: P = 1500 × 2 × 20 = 60,000 W = 60 kW
        val result = powerCalculator.fromAccelerometer(1500f, 2f, 20f)
        
        // Should be in kW range, not MW range
        assertNotNull(result)
        assertTrue("Power should be in kW range, not MW", result!! < 1000f)
        assertEquals(60f, result, DELTA)
    }

    @Test
    fun `OBD torque power returns kW not Watts`() {
        // With 100% torque, 200Nm, 3000 RPM: P = 200 × (2π × 3000/60) ≈ 62,832 W = 62.8 kW
        val result = powerCalculator.fromObd(100f, 200, 3000f)
        
        // Should be in kW range, not MW range  
        assertNotNull(result)
        assertTrue("Power should be in kW range, not MW", result!! < 1000f)
        
        val expected = (200.0 * 2.0 * PI * 3000.0 / 60.0 / 1000.0).toFloat()
        assertEquals(expected, result, DELTA)
    }

    // ── Realistic Value Tests ───────────────────────────────────────────────────────
    // These tests ensure values are realistic for typical vehicles

    @Test
    fun `thermodynamic power produces realistic values for Ronin scooter`() {
        // Ronin: E20 fuel (27.4 MJ/L), typical fuel rate 3.5 L/h
        val result = powerCalculator.thermodynamic(3.5f, FuelType.E20.energyDensityMJpL)
        
        assertNotNull(result)
        // Should be around 9-10 kW for a small scooter at cruise
        assertTrue("Power should be realistic for small scooter", result!! in 5f..20f)
    }

    @Test
    fun `accelerometer power produces realistic values for Ronin scooter`() {
        // Ronin: 240 kg, mild acceleration, city speed
        val result = powerCalculator.fromAccelerometer(240f, 0.5f, 8.33f) // 30 km/h
        
        assertNotNull(result)
        // Should be around 1 kW for mild acceleration
        assertTrue("Power should be realistic for small scooter acceleration", result!! in 0.1f..5f)
    }

    @Test
    fun `OBD power produces realistic values for typical engine`() {
        // Typical small engine: 50% torque, 150Nm, 4000 RPM
        val result = powerCalculator.fromObd(50f, 150, 4000f)
        
        assertNotNull(result)
        // Should be around 15-35 kW
        assertTrue("Power should be realistic for small engine", result!! in 10f..40f)
    }

    // ── Cross-Validation Tests ───────────────────────────────────────────────────────
    // These tests compare PowerCalculator with PowerCalculations to ensure consistency

    @Test
    fun `PowerCalculator matches PowerCalculations for thermodynamic`() {
        val fuelRate = 5f
        val energyDensity = FuelType.PETROL.energyDensityMJpL
        
        val calculatorResult = powerCalculator.thermodynamic(fuelRate, energyDensity)
        val calculationsResult = com.sj.obd2app.metrics.powerThermoKw(fuelRate, energyDensity)
        
        assertEquals(calculationsResult!!, calculatorResult!!, DELTA)
    }

    @Test
    fun `PowerCalculator matches PowerCalculations for accelerometer`() {
        val mass = 1000f
        val accel = 1.5f
        val speed = 15f
        
        val calculatorResult = powerCalculator.fromAccelerometer(mass, accel, speed)
        val calculationsResult = com.sj.obd2app.metrics.powerAccelKw(mass, accel, speed)
        
        assertEquals(calculationsResult!!, calculatorResult!!, DELTA)
    }

    @Test
    fun `PowerCalculator matches PowerCalculations for OBD torque`() {
        val torquePct = 75f
        val refTorque = 200
        val rpm = 2500f
        
        val calculatorResult = powerCalculator.fromObd(torquePct, refTorque, rpm)
        val calculationsResult = com.sj.obd2app.metrics.powerOBDKw(torquePct, refTorque, rpm)
        
        assertEquals(calculationsResult!!, calculatorResult!!, DELTA)
    }

    // ── Edge Case Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `thermodynamic power handles edge cases correctly`() {
        assertNull(powerCalculator.thermodynamic(null, FuelType.PETROL.energyDensityMJpL))
        assertNull(powerCalculator.thermodynamic(0f, FuelType.PETROL.energyDensityMJpL))
        assertNull(powerCalculator.thermodynamic(-1f, FuelType.PETROL.energyDensityMJpL))
        assertNull(powerCalculator.thermodynamic(10f, 0.0))
    }

    @Test
    fun `accelerometer power handles edge cases correctly`() {
        assertNull(powerCalculator.fromAccelerometer(0f, 1f, 10f))
        assertNull(powerCalculator.fromAccelerometer(1000f, null, 10f))
        assertNull(powerCalculator.fromAccelerometer(1000f, 1f, 0f))
    }

    @Test
    fun `OBD power handles edge cases correctly`() {
        assertNull(powerCalculator.fromObd(null, 200, 3000f))
        assertNull(powerCalculator.fromObd(100f, null, 3000f))
        assertNull(powerCalculator.fromObd(100f, 200, null))
        assertNull(powerCalculator.fromObd(100f, 200, 0f))
        assertNull(powerCalculator.fromObd(100f, 200, -100f))
    }

    // ── Scaling Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `thermodynamic power scales linearly with fuel rate`() {
        val p1 = powerCalculator.thermodynamic(5f, FuelType.PETROL.energyDensityMJpL)!!
        val p2 = powerCalculator.thermodynamic(10f, FuelType.PETROL.energyDensityMJpL)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }

    @Test
    fun `accelerometer power scales linearly with all inputs`() {
        // Test mass scaling
        val p1 = powerCalculator.fromAccelerometer(1000f, 1f, 10f)!!
        val p2 = powerCalculator.fromAccelerometer(2000f, 1f, 10f)!!
        assertEquals(p1 * 2f, p2, DELTA)
        
        // Test acceleration scaling
        val p3 = powerCalculator.fromAccelerometer(1000f, 2f, 10f)!!
        assertEquals(p1 * 2f, p3, DELTA)
        
        // Test speed scaling
        val p4 = powerCalculator.fromAccelerometer(1000f, 1f, 20f)!!
        assertEquals(p1 * 2f, p4, DELTA)
    }

    @Test
    fun `OBD power scales linearly with torque and RPM`() {
        // Test torque scaling
        val p1 = powerCalculator.fromObd(50f, 200, 3000f)!!
        val p2 = powerCalculator.fromObd(100f, 200, 3000f)!!
        assertEquals(p1 * 2f, p2, DELTA)
        
        // Test RPM scaling
        val p3 = powerCalculator.fromObd(100f, 200, 1500f)!!
        assertEquals(p2 * 0.5f, p3, DELTA)
    }
}
