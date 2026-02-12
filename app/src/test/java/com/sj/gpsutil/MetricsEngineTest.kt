package com.sj.gpsutil

import com.sj.gpsutil.data.CalibrationSettings
import com.sj.gpsutil.tracking.MetricsEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MetricsEngineTest {

    private lateinit var engine: MetricsEngine
    private lateinit var defaultCalibration: CalibrationSettings

    @Before
    fun setUp() {
        defaultCalibration = CalibrationSettings(
            rmsSmoothMax = 3.5f,
            peakThresholdZ = 5.0f,
            movingAverageWindow = 1,
            stdDevSmoothMax = 3.0f,
            rmsRoughMin = 4.0f,
            peakRatioRoughMin = 0.4f,
            stdDevRoughMin = 5.0f,
            magMaxSevereMin = 25.0f
        )
        engine = MetricsEngine(
            defaultCalibration,
            MetricsEngine.DriverThresholds(
                hardBrakeFwdMax = 35f,
                hardAccelFwdMax = 35f,
                swerveLatMax = 15f,
                aggressiveCornerLatMax = 15f,
                aggressiveCornerDCourse = 15f,
                minSpeedKmph = 6f,
                movingAvgWindow = 10,
                reactionTimeBrakeMax = 20f,
                reactionTimeLatMax = 15f,
                smoothnessRmsMax = 10f,
                fallLeanAngle = 40f
            )
        )
    }

    // --- Vehicle Basis Tests ---

    @Test
    fun testVehicleBasisOrthonormality() {
        // Typical phone gravity: mostly in Y and Z
        val gravity = floatArrayOf(0.281f, 6.335f, 7.659f)
        val basis = engine.computeVehicleBasis(gravity)

        assertNotNull("Basis should not be null for valid gravity", basis)
        basis!!

        // Check unit vectors (magnitude ≈ 1)
        assertNearOne(magnitude(basis.gUnit), "gUnit magnitude")
        assertNearOne(magnitude(basis.fwd), "fwd magnitude")
        assertNearOne(magnitude(basis.lat), "lat magnitude")

        // Check orthogonality (dot products ≈ 0)
        assertNearZero(dot(basis.gUnit, basis.fwd), "gUnit · fwd")
        assertNearZero(dot(basis.gUnit, basis.lat), "gUnit · lat")
        assertNearZero(dot(basis.fwd, basis.lat), "fwd · lat")
    }

    @Test
    fun testVehicleBasisDegenerateGravity() {
        val gravity = floatArrayOf(0f, 0f, 0f)
        val basis = engine.computeVehicleBasis(gravity)
        assertNull("Basis should be null for zero gravity", basis)
    }

    @Test
    fun testVehicleBasisNearZeroGravity() {
        val gravity = floatArrayOf(0.0001f, 0.0001f, 0.0001f)
        val basis = engine.computeVehicleBasis(gravity)
        assertNull("Basis should be null for near-zero gravity", basis)
    }

    @Test
    fun testVehicleBasisPureZGravity() {
        // Phone lying flat: gravity = [0, 0, 9.81]
        val gravity = floatArrayOf(0f, 0f, 9.81f)
        val basis = engine.computeVehicleBasis(gravity)
        assertNotNull(basis)
        basis!!
        assertNearOne(magnitude(basis.gUnit), "gUnit magnitude")
        assertNearOne(magnitude(basis.fwd), "fwd magnitude")
        assertNearOne(magnitude(basis.lat), "lat magnitude")
        assertNearZero(dot(basis.gUnit, basis.fwd), "gUnit · fwd")
    }

    // --- Moving Average Tests ---

    @Test
    fun testMovingAverageLength() {
        val data = List(20) { floatArrayOf(it.toFloat(), 0f, 0f) }
        val result = engine.applyMovingAverage(data, 5)
        assertEquals("Output length should match input", data.size, result.size)
    }

    @Test
    fun testMovingAverageSmoothing() {
        // Create noisy data
        val data = List(50) { i ->
            val noise = if (i % 2 == 0) 5f else -5f
            floatArrayOf(noise, 0f, 0f)
        }
        val smoothed = engine.applyMovingAverage(data, 5)

        // Smoothed variance should be less than raw variance
        val rawVariance = data.map { it[0] * it[0] }.average()
        val smoothedVariance = smoothed.map { it[0] * it[0] }.average()
        assertTrue("Smoothed variance ($smoothedVariance) should be less than raw ($rawVariance)",
            smoothedVariance < rawVariance)
    }

    @Test
    fun testMovingAverageWindowSizeOne() {
        val data = List(10) { floatArrayOf(it.toFloat(), 0f, 0f) }
        val result = engine.applyMovingAverage(data, 1)
        // Window size 1 should return approximately the same data
        for (i in data.indices) {
            assertEquals(data[i][0], result[i][0], 0.01f)
        }
    }

    // --- Road Quality Classification Tests ---

    @Test
    fun testRoadQualitySmooth() {
        // avgRms < rmsSmoothMax(3.5) AND avgStdDev < stdDevSmoothMax(3.0)
        val quality = engine.classifyRoadQuality(avgRms = 1.0f, avgStdDev = 1.0f)
        assertEquals("smooth", quality)
    }

    @Test
    fun testRoadQualityRough() {
        // avgRms >= rmsRoughMin(4.0) AND avgStdDev >= stdDevRoughMin(5.0)
        val quality = engine.classifyRoadQuality(avgRms = 5.0f, avgStdDev = 6.0f)
        assertEquals("rough", quality)
    }

    @Test
    fun testRoadQualityAverage() {
        // In between smooth and rough thresholds
        val quality = engine.classifyRoadQuality(avgRms = 3.8f, avgStdDev = 4.0f)
        assertEquals("average", quality)
    }

    @Test
    fun testRoadQualitySmoothBoundary() {
        // Exactly at smooth threshold
        val quality = engine.classifyRoadQuality(avgRms = 3.5f, avgStdDev = 3.0f)
        // 3.5 is NOT < 3.5, so should be average
        assertEquals("average", quality)
    }

    // --- Feature Detection Tests ---

    @Test
    fun testFeatureDetectionPothole() {
        // rms > rmsRoughMin(4.0), magMax > magMaxSevereMin(25.0), peakRatio < peakRatioRoughMin(0.4)
        val feature = engine.detectFeatureFromMetrics(rms = 5.0f, magMax = 30.0f, peakRatio = 0.2f)
        assertEquals("pothole", feature)
    }

    @Test
    fun testFeatureDetectionBump() {
        // rms > rmsRoughMin(4.0), magMax > magMaxSevereMin(25.0), peakRatio >= peakRatioRoughMin(0.4)
        val feature = engine.detectFeatureFromMetrics(rms = 5.0f, magMax = 30.0f, peakRatio = 0.5f)
        assertEquals("bump", feature)
    }

    @Test
    fun testFeatureDetectionNone() {
        // rms <= rmsRoughMin(4.0) → no feature
        val feature = engine.detectFeatureFromMetrics(rms = 3.0f, magMax = 30.0f, peakRatio = 0.5f)
        assertNull("No feature when rms below threshold", feature)
    }

    @Test
    fun testFeatureDetectionNoSevere() {
        // rms > threshold but magMax not severe
        val feature = engine.detectFeatureFromMetrics(rms = 5.0f, magMax = 20.0f, peakRatio = 0.5f)
        assertNull("No feature when magMax below severe threshold", feature)
    }

    // --- Speed Hump Detection Tests ---

    @Test
    fun testSpeedHumpDetected() {
        // Create oscillating vertical accel with decay pattern
        // Needs: ≥8 peaks above 5.0, ≥20 zero crossings, duration ≤8s,
        //        peak-to-peak ≥10, and second-half avg < 0.7 * first-half avg
        val rawVertAccel = mutableListOf<Float>()
        for (i in 0 until 600) {
            val t = i / 100f
            val amplitude = 20f * kotlin.math.exp(-0.15 * t).toFloat()
            val value = amplitude * kotlin.math.sin(2 * Math.PI * 5.0 * t).toFloat()
            rawVertAccel.add(value)
        }
        val result = engine.detectSpeedHumpPattern(rawVertAccel, fwdMax = 5f, speed = 15f, samplingRate = 100f)
        assertEquals("speed_bump", result)
    }

    @Test
    fun testSpeedHumpNotDetected_InsufficientPeaks() {
        // Very short, no oscillation
        val rawVertAccel = List(50) { 0.5f }
        val result = engine.detectSpeedHumpPattern(rawVertAccel, fwdMax = 5f, speed = 15f, samplingRate = 100f)
        assertNull("Should not detect speed bump with no peaks", result)
    }

    // --- Driver Event Classification Tests ---

    @Test
    fun testDriverEventHardBrake() {
        val events = engine.classifyDriverEvent(
            fwdMax = 40f, latMax = 5f, deltaSpeed = -10f, deltaCourse = 0f, speed = 30f
        )
        assertTrue("Should contain hard_brake", events.contains("hard_brake"))
    }

    @Test
    fun testDriverEventHardAccel() {
        val events = engine.classifyDriverEvent(
            fwdMax = 40f, latMax = 5f, deltaSpeed = 10f, deltaCourse = 0f, speed = 30f
        )
        assertTrue("Should contain hard_accel", events.contains("hard_accel"))
    }

    @Test
    fun testDriverEventSwerve() {
        val events = engine.classifyDriverEvent(
            fwdMax = 5f, latMax = 20f, deltaSpeed = 0f, deltaCourse = 5f, speed = 30f
        )
        assertTrue("Should contain swerve", events.contains("swerve"))
    }

    @Test
    fun testDriverEventAggressiveCorner() {
        val events = engine.classifyDriverEvent(
            fwdMax = 5f, latMax = 20f, deltaSpeed = 0f, deltaCourse = 20f, speed = 30f
        )
        assertTrue("Should contain aggressive_corner", events.contains("aggressive_corner"))
        assertTrue("Should also contain swerve (latMax > threshold)", events.contains("swerve"))
    }

    @Test
    fun testDriverEventLowSpeed() {
        val events = engine.classifyDriverEvent(
            fwdMax = 40f, latMax = 20f, deltaSpeed = -10f, deltaCourse = 20f, speed = 3f
        )
        assertEquals("Should only contain low_speed", listOf("low_speed"), events)
    }

    @Test
    fun testDriverEventNormal() {
        val events = engine.classifyDriverEvent(
            fwdMax = 5f, latMax = 5f, deltaSpeed = 1f, deltaCourse = 2f, speed = 30f
        )
        assertEquals("Should only contain normal", listOf("normal"), events)
    }

    @Test
    fun testPrimaryEventPriority() {
        // hard_brake + swerve → primary should be hard_brake
        val events = engine.classifyDriverEvent(
            fwdMax = 40f, latMax = 20f, deltaSpeed = -10f, deltaCourse = 5f, speed = 30f
        )
        assertTrue(events.contains("hard_brake"))
        assertTrue(events.contains("swerve"))

        // Simulate priority selection
        val priority = listOf("hard_brake", "swerve", "aggressive_corner", "hard_accel", "normal", "low_speed")
        val primaryEvent = priority.first { it in events }
        assertEquals("hard_brake", primaryEvent)
    }

    // --- Smoothness Score Tests ---

    @Test
    fun testSmoothnessScore() {
        // combined = 0.2*2 + 0.8*5 = 0.4 + 4.0 = 4.4
        // score = max(0, 1 - 4.4/10) * 100 = 56.0
        val score = engine.computeSmoothnessScore(fwdRms = 2f, latRms = 5f)
        assertEquals(56.0f, score, 0.1f)
    }

    @Test
    fun testSmoothnessScorePerfect() {
        val score = engine.computeSmoothnessScore(fwdRms = 0f, latRms = 0f)
        assertEquals(100.0f, score, 0.1f)
    }

    @Test
    fun testSmoothnessScoreClamped() {
        // Very high RMS → clamped to 0
        val score = engine.computeSmoothnessScore(fwdRms = 50f, latRms = 50f)
        assertEquals(0.0f, score, 0.1f)
    }

    // --- Bearing Diff Tests ---

    @Test
    fun testBearingDiffSimple() {
        assertEquals(20f, engine.bearingDiff(350f, 10f), 0.01f)
    }

    @Test
    fun testBearingDiffNegative() {
        assertEquals(-20f, engine.bearingDiff(10f, 350f), 0.01f)
    }

    @Test
    fun testBearingDiff180() {
        assertEquals(180f, engine.bearingDiff(0f, 180f), 0.01f)
    }

    @Test
    fun testBearingDiffZero() {
        assertEquals(0f, engine.bearingDiff(90f, 90f), 0.01f)
    }

    @Test
    fun testBearingDiffWrapAround() {
        // 350 to 10 = +20 (crossing 0)
        assertEquals(20f, engine.bearingDiff(350f, 10f), 0.01f)
        // 10 to 350 = -20 (crossing 0 backwards)
        assertEquals(-20f, engine.bearingDiff(10f, 350f), 0.01f)
    }

    // --- AccelMetrics from raw samples (integration test) ---

    @Test
    fun testAccelMetricsFromStaticSamples() {
        // Simulate stationary phone: all samples near gravity
        val gravity = floatArrayOf(0.281f, 6.335f, 7.659f)
        val basis = engine.computeVehicleBasis(gravity)
        assertNotNull(basis)

        // Generate 100 samples near gravity (small noise)
        val samples = List(100) { i ->
            floatArrayOf(
                gravity[0] + (i % 3 - 1) * 0.01f,
                gravity[1] + (i % 5 - 2) * 0.01f,
                gravity[2] + (i % 7 - 3) * 0.01f
            )
        }

        val history = ArrayDeque<MetricsEngine.FixMetrics>()
        val metrics = engine.computeAccelMetrics(samples, 0f, basis, history)

        assertNotNull("Metrics should not be null", metrics)
        metrics!!

        // For stationary samples, RMS should be very small (detrended)
        assertTrue("RMS should be small for stationary: ${metrics.rms}", metrics.rms < 1.0f)
        // Road quality should be null at 0 speed
        assertNull("Road quality should be null at 0 speed", metrics.roadQuality)
    }

    @Test
    fun testAccelMetricsLowSpeedGating() {
        val gravity = floatArrayOf(0f, 0f, 9.81f)
        val basis = engine.computeVehicleBasis(gravity)

        // Generate noisy samples that would normally trigger road quality
        val samples = List(100) { i ->
            floatArrayOf(0f, 0f, 9.81f + (i % 10 - 5) * 2f)
        }

        val history = ArrayDeque<MetricsEngine.FixMetrics>()
        val metrics = engine.computeAccelMetrics(samples, 3f, basis, history)

        assertNotNull(metrics)
        metrics!!
        assertNull("Road quality should be null at low speed", metrics.roadQuality)
        assertNull("Feature should be null at low speed", metrics.featureDetected)
    }

    // --- Helper functions ---

    private fun magnitude(v: FloatArray): Float {
        return sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    }

    private fun assertNearOne(value: Float, label: String) {
        assertTrue("$label should be ≈ 1.0 but was $value", abs(value - 1.0f) < 0.01f)
    }

    private fun assertNearZero(value: Float, label: String) {
        assertTrue("$label should be ≈ 0.0 but was $value", abs(value) < 0.01f)
    }
}
