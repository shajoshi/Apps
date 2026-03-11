package com.sj.obd2app.metrics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TripStateTest {

    private lateinit var state: TripState

    @Before
    fun setUp() {
        state = TripState()
        state.reset()
    }

    // ── Distance accumulation ─────────────────────────────────────────────────

    @Test
    fun `distance accumulates correctly at 100 kmh for 1 second`() {
        // At 100 km/h, in 1 second: 100 / 3600 = 0.02778 km
        val before = state.tripDistanceKm
        simulateUpdate(state, speedKmh = 100f, fuelRateLh = 0f, durationMs = 1000L)
        val delta = state.tripDistanceKm - before
        assertEquals(100f / 3600f, delta, 0.0005f)
    }

    @Test
    fun `distance accumulates correctly at 60 kmh for 60 seconds`() {
        // At 60 km/h for 60 s = 1 km
        simulateUpdate(state, speedKmh = 60f, fuelRateLh = 0f, durationMs = 60_000L)
        assertEquals(1.0f, state.tripDistanceKm, 0.01f)
    }

    @Test
    fun `distance does not change at zero speed`() {
        simulateUpdate(state, speedKmh = 0f, fuelRateLh = 0f, durationMs = 10_000L)
        assertEquals(0f, state.tripDistanceKm, 0.0001f)
    }

    // ── Fuel accumulation ─────────────────────────────────────────────────────

    @Test
    fun `fuel accumulates correctly at 10 Lh for 3600 seconds`() {
        // 10 L/h × 1 h = 10 L
        simulateUpdate(state, speedKmh = 60f, fuelRateLh = 10f, durationMs = 3_600_000L)
        assertEquals(10f, state.tripFuelUsedL, 0.01f)
    }

    @Test
    fun `fuel does not change at zero rate`() {
        simulateUpdate(state, speedKmh = 60f, fuelRateLh = 0f, durationMs = 10_000L)
        assertEquals(0f, state.tripFuelUsedL, 0.0001f)
    }

    @Test
    fun `fuel accumulates correctly at 5 Lh for 1800 seconds`() {
        // 5 L/h × 0.5 h = 2.5 L
        simulateUpdate(state, speedKmh = 60f, fuelRateLh = 5f, durationMs = 1_800_000L)
        assertEquals(2.5f, state.tripFuelUsedL, 0.01f)
    }

    // ── Time buckets ──────────────────────────────────────────────────────────

    @Test
    fun `moving time increments when speed is above 2 kmh`() {
        simulateUpdate(state, speedKmh = 30f, fuelRateLh = 0f, durationMs = 5_000L)
        assertEquals(5L, state.movingTimeSec)
        assertEquals(0L, state.stoppedTimeSec)
    }

    @Test
    fun `stopped time increments when speed is at 2 kmh exactly`() {
        simulateUpdate(state, speedKmh = 2f, fuelRateLh = 0f, durationMs = 5_000L)
        assertEquals(5L, state.stoppedTimeSec)
        assertEquals(0L, state.movingTimeSec)
    }

    @Test
    fun `stopped time increments when speed is zero`() {
        simulateUpdate(state, speedKmh = 0f, fuelRateLh = 0f, durationMs = 8_000L)
        assertEquals(8L, state.stoppedTimeSec)
        assertEquals(0L, state.movingTimeSec)
    }

    @Test
    fun `moving and stopped time both accumulate over mixed speed`() {
        simulateUpdate(state, speedKmh = 50f, fuelRateLh = 0f, durationMs = 10_000L) // 10 s moving
        simulateUpdate(state, speedKmh = 0f, fuelRateLh = 0f, durationMs = 5_000L)   // 5 s stopped
        assertEquals(10L, state.movingTimeSec)
        assertEquals(5L, state.stoppedTimeSec)
    }

    // ── Max speed ────────────────────────────────────────────────────────────

    @Test
    fun `maxSpeedKmh tracks peak speed`() {
        simulateUpdate(state, speedKmh = 50f, fuelRateLh = 0f, durationMs = 1000L)
        simulateUpdate(state, speedKmh = 120f, fuelRateLh = 0f, durationMs = 1000L)
        simulateUpdate(state, speedKmh = 80f, fuelRateLh = 0f, durationMs = 1000L)
        assertEquals(120f, state.maxSpeedKmh, 0.001f)
    }

    @Test
    fun `maxSpeedKmh starts at zero`() {
        assertEquals(0f, state.maxSpeedKmh, 0.001f)
    }

    @Test
    fun `maxSpeedKmh does not decrease`() {
        simulateUpdate(state, speedKmh = 100f, fuelRateLh = 0f, durationMs = 1000L)
        simulateUpdate(state, speedKmh = 10f, fuelRateLh = 0f, durationMs = 1000L)
        assertEquals(100f, state.maxSpeedKmh, 0.001f)
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all accumulators`() {
        simulateUpdate(state, speedKmh = 100f, fuelRateLh = 10f, durationMs = 10_000L)
        state.reset()
        assertEquals(0f, state.tripDistanceKm, 0.0001f)
        assertEquals(0f, state.tripFuelUsedL, 0.0001f)
        assertEquals(0L, state.movingTimeSec)
        assertEquals(0L, state.stoppedTimeSec)
        assertEquals(0f, state.maxSpeedKmh, 0.0001f)
        assertTrue(state.speedWindow.isEmpty())
    }

    // ── driveModePercents ─────────────────────────────────────────────────────

    @Test
    fun `driveModePercents returns zeros when window is empty`() {
        val (city, hwy, idle) = state.driveModePercents()
        assertEquals(0f, city, 0.001f)
        assertEquals(0f, hwy, 0.001f)
        assertEquals(0f, idle, 0.001f)
    }

    @Test
    fun `driveModePercents returns 100pct idle when all speed is zero`() {
        repeat(10) { simulateUpdate(state, speedKmh = 0f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, idle) = state.driveModePercents()
        assertEquals(0f, city, 0.001f)
        assertEquals(0f, hwy, 0.001f)
        assertEquals(100f, idle, 0.5f)
    }

    @Test
    fun `driveModePercents returns 100pct city when all speed is city`() {
        repeat(10) { simulateUpdate(state, speedKmh = 40f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, idle) = state.driveModePercents()
        assertEquals(100f, city, 0.5f)
        assertEquals(0f, hwy, 0.001f)
        assertEquals(0f, idle, 0.001f)
    }

    @Test
    fun `driveModePercents returns 100pct highway when all speed is highway`() {
        repeat(10) { simulateUpdate(state, speedKmh = 100f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, idle) = state.driveModePercents()
        assertEquals(0f, city, 0.001f)
        assertEquals(100f, hwy, 0.5f)
        assertEquals(0f, idle, 0.001f)
    }

    @Test
    fun `driveModePercents sums to 100`() {
        repeat(5) { simulateUpdate(state, speedKmh = 0f, fuelRateLh = 0f, durationMs = 1000L) }
        repeat(5) { simulateUpdate(state, speedKmh = 40f, fuelRateLh = 0f, durationMs = 1000L) }
        repeat(5) { simulateUpdate(state, speedKmh = 100f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, idle) = state.driveModePercents()
        assertEquals(100f, city + hwy + idle, 0.5f)
    }

    @Test
    fun `driveModePercents city boundary at exactly 60 kmh is city`() {
        repeat(10) { simulateUpdate(state, speedKmh = 60f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, _) = state.driveModePercents()
        assertEquals(100f, city, 0.5f)
        assertEquals(0f, hwy, 0.001f)
    }

    @Test
    fun `driveModePercents highway starts above 60 kmh`() {
        repeat(10) { simulateUpdate(state, speedKmh = 61f, fuelRateLh = 0f, durationMs = 1000L) }
        val (city, hwy, _) = state.driveModePercents()
        assertEquals(0f, city, 0.001f)
        assertEquals(100f, hwy, 0.5f)
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Simulates a single [TripState.update] call as if [durationMs] have elapsed
     * since the last update, by directly setting [TripState.lastUpdateMs].
     */
    private fun simulateUpdate(state: TripState, speedKmh: Float, fuelRateLh: Float, durationMs: Long) {
        state.lastUpdateMs = System.currentTimeMillis() - durationMs
        state.update(speedKmh, fuelRateLh)
    }
}
