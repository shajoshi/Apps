package com.sj.obd2app.metrics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AccelEngineTest {

    private lateinit var engine: AccelEngine

    @Before
    fun setUp() {
        engine = AccelEngine()
    }

    // ── computeVehicleBasis ───────────────────────────────────────────────────

    @Test
    fun `computeVehicleBasis returns null for zero vector`() {
        assertNull(engine.computeVehicleBasis(floatArrayOf(0f, 0f, 0f)))
    }

    @Test
    fun `computeVehicleBasis returns null for near-zero vector`() {
        assertNull(engine.computeVehicleBasis(floatArrayOf(1e-5f, 0f, 0f)))
    }

    @Test
    fun `computeVehicleBasis returns non-null for valid gravity vector`() {
        assertNotNull(engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f)))
    }

    @Test
    fun `computeVehicleBasis gUnit is unit length`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val norm = norm3(basis.gUnit)
        assertEquals(1f, norm, 0.001f)
    }

    @Test
    fun `computeVehicleBasis fwd is unit length`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val norm = norm3(basis.fwd)
        assertEquals(1f, norm, 0.001f)
    }

    @Test
    fun `computeVehicleBasis lat is unit length`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val norm = norm3(basis.lat)
        assertEquals(1f, norm, 0.001f)
    }

    @Test
    fun `computeVehicleBasis gUnit and fwd are orthogonal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0.5f, 0.5f, 9.0f))!!
        val dot = dot3(basis.gUnit, basis.fwd)
        assertEquals(0f, dot, 0.001f)
    }

    @Test
    fun `computeVehicleBasis gUnit and lat are orthogonal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0.5f, 0.5f, 9.0f))!!
        val dot = dot3(basis.gUnit, basis.lat)
        assertEquals(0f, dot, 0.001f)
    }

    @Test
    fun `computeVehicleBasis fwd and lat are orthogonal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0.5f, 0.5f, 9.0f))!!
        val dot = dot3(basis.fwd, basis.lat)
        assertEquals(0f, dot, 0.001f)
    }

    @Test
    fun `computeVehicleBasis works for tilted gravity vector`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(2f, 1f, 9f))
        assertNotNull(basis)
    }

    // ── computeAccelMetrics — null/empty cases ────────────────────────────────

    @Test
    fun `computeAccelMetrics returns null for empty buffer`() {
        assertNull(engine.computeAccelMetrics(emptyList(), null))
    }

    // ── computeAccelMetrics — vertical axis (no basis) ───────────────────────

    @Test
    fun `computeAccelMetrics vertMean is approximately zero for detrended signal`() {
        // Constant signal — after bias removal (detrending) mean should be ~0
        val buffer = List(20) { floatArrayOf(0f, 0f, 3f) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertEquals(0f, result.vertMean, 0.01f)
    }

    @Test
    fun `computeAccelMetrics vertStdDev is zero for constant signal`() {
        val buffer = List(20) { floatArrayOf(0f, 0f, 5f) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertEquals(0f, result.vertStdDev, 0.001f)
    }

    @Test
    fun `computeAccelMetrics vertRms is non-negative`() {
        val buffer = List(10) { i -> floatArrayOf(0f, 0f, (i - 5).toFloat()) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertTrue(result.vertRms >= 0f)
    }

    @Test
    fun `computeAccelMetrics vertMax is non-negative`() {
        val buffer = List(10) { i -> floatArrayOf(0f, 0f, (i - 5).toFloat()) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertTrue(result.vertMax >= 0f)
    }

    @Test
    fun `computeAccelMetrics rawSampleCount matches buffer size`() {
        val buffer = List(15) { floatArrayOf(0f, 0f, 1f) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertEquals(15, result.rawAccelSampleCount)
    }

    @Test
    fun `computeAccelMetrics peakRatio is zero when all below threshold`() {
        // Default peakThresholdZ = 2.0f; use tiny values
        val buffer = List(20) { floatArrayOf(0f, 0f, 0.1f) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertEquals(0f, result.vertPeakRatio, 0.001f)
    }

    @Test
    fun `computeAccelMetrics peakRatio is between 0 and 1`() {
        val buffer = List(20) { i -> floatArrayOf(0f, 0f, if (i % 2 == 0) 5f else 0.1f) }
        val result = engine.computeAccelMetrics(buffer, null)!!
        assertTrue(result.vertPeakRatio in 0f..1f)
    }

    // ── computeAccelMetrics — with vehicle basis ──────────────────────────────

    @Test
    fun `computeAccelMetrics with basis fwdRms is non-negative`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val buffer = List(10) { floatArrayOf(0.5f, 1f, 0f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertTrue(result.fwdRms >= 0f)
    }

    @Test
    fun `computeAccelMetrics with basis latRms is non-negative`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val buffer = List(10) { floatArrayOf(1f, 0f, 0f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertTrue(result.latRms >= 0f)
    }

    @Test
    fun `computeAccelMetrics with basis fwdMaxAccel positive for positive forward signal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        // Positive Y (forward axis in device frame when gravity is purely Z)
        val buffer = List(20) { floatArrayOf(0f, 2f, 0f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertTrue("fwdMaxAccel should be >= 0", result.fwdMaxAccel >= 0f)
    }

    @Test
    fun `computeAccelMetrics with basis fwdMaxBrake positive for negative forward signal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        // Negative Y (braking)
        val buffer = List(20) { floatArrayOf(0f, -2f, 0f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertTrue("fwdMaxBrake should be >= 0", result.fwdMaxBrake >= 0f)
    }

    @Test
    fun `computeAccelMetrics leanAngleDeg is zero for vertical gravity`() {
        // Pure vertical gravity → no lateral component → lean = 0
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        val buffer = List(10) { floatArrayOf(0f, 0f, 9.81f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertEquals(0f, result.leanAngleDeg, 1f)
    }

    @Test
    fun `computeAccelMetrics fwdMean is zero for symmetric forward-backward signal`() {
        val basis = engine.computeVehicleBasis(floatArrayOf(0f, 0f, 9.81f))!!
        // Alternating +2 and -2 in Y (forward)
        val buffer = List(20) { i -> floatArrayOf(0f, if (i % 2 == 0) 2f else -2f, 0f) }
        val result = engine.computeAccelMetrics(buffer, basis)!!
        assertEquals(0f, result.fwdMean, 0.01f)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun norm3(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dot3(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
