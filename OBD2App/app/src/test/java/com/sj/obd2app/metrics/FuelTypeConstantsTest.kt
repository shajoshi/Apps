package com.sj.obd2app.metrics

import com.sj.obd2app.settings.FuelType
import org.junit.Assert.*
import org.junit.Test

class FuelTypeConstantsTest {

    // ── PETROL ───────────────────────────────────────────────────────────────

    @Test
    fun `PETROL mafMlPerGram is correct`() {
        // 1000 / (AFR 14.7 × density 740 g/L) = 0.09195
        assertEquals(0.09195, FuelType.PETROL.mafMlPerGram, 1e-5)
    }

    @Test
    fun `PETROL co2Factor is correct`() {
        assertEquals(23.1, FuelType.PETROL.co2Factor, 0.001)
    }

    @Test
    fun `PETROL energyDensityMJpL is correct`() {
        assertEquals(34.2, FuelType.PETROL.energyDensityMJpL, 0.001)
    }

    // ── DIESEL ───────────────────────────────────────────────────────────────

    @Test
    fun `DIESEL mafMlPerGram is correct`() {
        // 1000 / (AFR 14.5 × density 840 g/L) = 0.08210
        assertEquals(0.08210, FuelType.DIESEL.mafMlPerGram, 1e-5)
    }

    @Test
    fun `DIESEL co2Factor is correct`() {
        assertEquals(26.4, FuelType.DIESEL.co2Factor, 0.001)
    }

    @Test
    fun `DIESEL energyDensityMJpL is correct`() {
        assertEquals(38.6, FuelType.DIESEL.energyDensityMJpL, 0.001)
    }

    // ── E20 ──────────────────────────────────────────────────────────────────

    @Test
    fun `E20 mafMlPerGram is correct`() {
        // 1000 / (AFR 13.8 × density 790 g/L) = 0.09166
        assertEquals(0.09166, FuelType.E20.mafMlPerGram, 1e-5)
    }

    @Test
    fun `E20 co2Factor is correct`() {
        assertEquals(22.3, FuelType.E20.co2Factor, 0.001)
    }

    @Test
    fun `E20 energyDensityMJpL is correct`() {
        assertEquals(27.4, FuelType.E20.energyDensityMJpL, 0.001)
    }

    // ── CNG ──────────────────────────────────────────────────────────────────

    @Test
    fun `CNG mafMlPerGram is correct`() {
        // 1000 / (AFR 17.2 × density 423 g/L) = 0.13740
        assertEquals(0.13740, FuelType.CNG.mafMlPerGram, 1e-5)
    }

    @Test
    fun `CNG co2Factor is correct`() {
        assertEquals(16.0, FuelType.CNG.co2Factor, 0.001)
    }

    @Test
    fun `CNG energyDensityMJpL is correct`() {
        assertEquals(23.0, FuelType.CNG.energyDensityMJpL, 0.001)
    }

    // ── Invariants for all fuel types ─────────────────────────────────────────

    @Test
    fun `all fuel types have positive mafMlPerGram`() {
        FuelType.entries.forEach { ft ->
            assertTrue("${ft.name} mafMlPerGram must be > 0", ft.mafMlPerGram > 0.0)
        }
    }

    @Test
    fun `all fuel types have positive co2Factor`() {
        FuelType.entries.forEach { ft ->
            assertTrue("${ft.name} co2Factor must be > 0", ft.co2Factor > 0.0)
        }
    }

    @Test
    fun `all fuel types have positive energyDensity`() {
        FuelType.entries.forEach { ft ->
            assertTrue("${ft.name} energyDensityMJpL must be > 0", ft.energyDensityMJpL > 0.0)
        }
    }

    @Test
    fun `diesel has higher co2Factor than petrol`() {
        assertTrue(FuelType.DIESEL.co2Factor > FuelType.PETROL.co2Factor)
    }

    @Test
    fun `diesel has higher energy density than petrol`() {
        assertTrue(FuelType.DIESEL.energyDensityMJpL > FuelType.PETROL.energyDensityMJpL)
    }

    @Test
    fun `CNG has lowest co2Factor of all types`() {
        val minCo2 = FuelType.entries.minOf { it.co2Factor }
        assertEquals(FuelType.CNG.co2Factor, minCo2, 0.001)
    }
}
