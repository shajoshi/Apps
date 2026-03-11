package com.sj.obd2app.metrics

import com.sj.obd2app.settings.FuelType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class PowerCalculationsTest {

    private val DELTA = 0.01f

    // ── powerAccelKw ─────────────────────────────────────────────────────────

    @Test
    fun `powerAccelKw correct at 1500kg 2ms2 20ms`() {
        // P = 1500 × 2 × 20 / 1000 = 60 kW
        assertEquals(60f, powerAccelKw(1500f, 2f, 20f)!!, DELTA)
    }

    @Test
    fun `powerAccelKw correct with negative fwdMean braking`() {
        // P = 1000 × (-3) × 10 / 1000 = -30 kW
        assertEquals(-30f, powerAccelKw(1000f, -3f, 10f)!!, DELTA)
    }

    @Test
    fun `powerAccelKw returns null when mass is zero`() {
        assertNull(powerAccelKw(0f, 2f, 20f))
    }

    @Test
    fun `powerAccelKw returns null when fwdMean is null`() {
        assertNull(powerAccelKw(1500f, null, 20f))
    }

    @Test
    fun `powerAccelKw returns null when speed is zero`() {
        assertNull(powerAccelKw(1500f, 2f, 0f))
    }

    @Test
    fun `powerAccelKw scales linearly with mass`() {
        val p1 = powerAccelKw(1000f, 1f, 10f)!!
        val p2 = powerAccelKw(2000f, 1f, 10f)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }

    @Test
    fun `powerAccelKw scales linearly with speed`() {
        val p1 = powerAccelKw(1000f, 1f, 10f)!!
        val p2 = powerAccelKw(1000f, 1f, 20f)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }

    // ── powerThermoKw ────────────────────────────────────────────────────────

    @Test
    fun `powerThermoKw correct for petrol at 10 Lh`() {
        // P = (10/3600) × 34.2e6 × 0.35 / 1000
        val expected = ((10.0 / 3600.0) * 34.2e6 * 0.35 / 1000.0).toFloat()
        assertEquals(expected, powerThermoKw(10f, FuelType.PETROL.energyDensityMJpL)!!, DELTA)
    }

    @Test
    fun `powerThermoKw correct for diesel at 8 Lh`() {
        val expected = ((8.0 / 3600.0) * FuelType.DIESEL.energyDensityMJpL * 1e6 * 0.35 / 1000.0).toFloat()
        assertEquals(expected, powerThermoKw(8f, FuelType.DIESEL.energyDensityMJpL)!!, DELTA)
    }

    @Test
    fun `powerThermoKw returns null when fuelRate is null`() {
        assertNull(powerThermoKw(null, FuelType.PETROL.energyDensityMJpL))
    }

    @Test
    fun `powerThermoKw returns null when fuelRate is zero`() {
        assertNull(powerThermoKw(0f, FuelType.PETROL.energyDensityMJpL))
    }

    @Test
    fun `powerThermoKw returns null when fuelRate is negative`() {
        assertNull(powerThermoKw(-1f, FuelType.PETROL.energyDensityMJpL))
    }

    @Test
    fun `powerThermoKw returns null when energyDensity is zero`() {
        assertNull(powerThermoKw(10f, 0.0))
    }

    @Test
    fun `powerThermoKw scales linearly with fuel rate`() {
        val p1 = powerThermoKw(10f, FuelType.PETROL.energyDensityMJpL)!!
        val p2 = powerThermoKw(20f, FuelType.PETROL.energyDensityMJpL)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }

    @Test
    fun `powerThermoKw greater for diesel than petrol at same rate due to energy density`() {
        val petrol = powerThermoKw(10f, FuelType.PETROL.energyDensityMJpL)!!
        val diesel = powerThermoKw(10f, FuelType.DIESEL.energyDensityMJpL)!!
        assertTrue("Diesel should produce more power at same fuel rate", diesel > petrol)
    }

    // ── powerOBDKw ───────────────────────────────────────────────────────────

    @Test
    fun `powerOBDKw correct at 100pct torque 200Nm 3000rpm`() {
        // P = (1.0 × 200 × 3000 × 2π) / 60000
        val expected = ((1.0f * 200 * 3000f * 2.0 * PI) / 60000.0).toFloat()
        assertEquals(expected, powerOBDKw(100f, 200, 3000f)!!, DELTA)
    }

    @Test
    fun `powerOBDKw correct at 50pct torque 300Nm 2000rpm`() {
        val expected = ((0.5f * 300 * 2000f * 2.0 * PI) / 60000.0).toFloat()
        assertEquals(expected, powerOBDKw(50f, 300, 2000f)!!, DELTA)
    }

    @Test
    fun `powerOBDKw returns null when actualTorquePct is null`() {
        assertNull(powerOBDKw(null, 200, 3000f))
    }

    @Test
    fun `powerOBDKw returns null when refTorqueNm is null`() {
        assertNull(powerOBDKw(100f, null, 3000f))
    }

    @Test
    fun `powerOBDKw returns null when rpm is null`() {
        assertNull(powerOBDKw(100f, 200, null))
    }

    @Test
    fun `powerOBDKw returns null when rpm is zero`() {
        assertNull(powerOBDKw(100f, 200, 0f))
    }

    @Test
    fun `powerOBDKw returns null when rpm is negative`() {
        assertNull(powerOBDKw(100f, 200, -100f))
    }

    @Test
    fun `powerOBDKw scales linearly with rpm`() {
        val p1 = powerOBDKw(100f, 200, 1000f)!!
        val p2 = powerOBDKw(100f, 200, 2000f)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }

    @Test
    fun `powerOBDKw scales linearly with torque percentage`() {
        val p1 = powerOBDKw(50f, 200, 3000f)!!
        val p2 = powerOBDKw(100f, 200, 3000f)!!
        assertEquals(p1 * 2f, p2, DELTA)
    }
}
