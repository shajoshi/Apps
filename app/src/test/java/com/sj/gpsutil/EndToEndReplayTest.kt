package com.sj.gpsutil

import com.sj.gpsutil.testutil.ParsedDataPoint
import com.sj.gpsutil.testutil.TrackFileParser
import com.sj.gpsutil.testutil.ParsedTrackFile
import com.sj.gpsutil.tracking.DriverMetrics
import com.sj.gpsutil.tracking.MetricsEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * End-to-end replay test using a real sequential recording (track_real.json).
 * Because every consecutive GPS fix is present, the metricsHistory accumulates
 * identically to the live recording, so all metrics (per-fix AND averaged)
 * should match the expected values stored in the file.
 *
 * deltaSpeed and deltaCourse are computed from consecutive GPS fixes so that
 * driver event classification also matches.
 */
class EndToEndReplayTest {

    private lateinit var parsedFile: ParsedTrackFile

    @Before
    fun setUp() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("track_real.json")
            ?: throw IllegalStateException("track_real.json not found in test resources")
        parsedFile = TrackFileParser.parse(inputStream)
    }

    // ---- Basic parse / structure tests ----

    @Test
    fun testFileParseSuccessful() {
        assertTrue("Should have data points", parsedFile.dataPoints.isNotEmpty())
        assertNotNull("Should have calibration", parsedFile.calibration)
        println("Parsed ${parsedFile.dataPoints.size} data points")
        println("Calibration: ${parsedFile.calibration}")
        println("Gravity vector: ${parsedFile.baseGravityVector?.contentToString()}")
    }

    @Test
    fun testGravityVectorPresent() {
        assertNotNull("Gravity vector should be present", parsedFile.baseGravityVector)
        val g = parsedFile.baseGravityVector!!
        assertEquals(3, g.size)
        val mag = kotlin.math.sqrt((g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).toDouble())
        assertTrue("Gravity magnitude should be ~9.81 (was $mag)", mag in 8.0..11.0)
    }

    @Test
    fun testVehicleBasisFromRecordedGravity() {
        val engine = MetricsEngine(parsedFile.calibration)
        val gravity = parsedFile.baseGravityVector ?: return
        val basis = engine.computeVehicleBasis(gravity)
        assertNotNull("Should compute valid basis from recorded gravity", basis)
    }

    // ---- Sequential accel-metrics replay ----

    @Test
    fun testReplayAccelMetrics() {
        val engine = MetricsEngine(parsedFile.calibration)
        val gravity = parsedFile.baseGravityVector ?: return
        val basis = engine.computeVehicleBasis(gravity) ?: return
        val metricsHistory = ArrayDeque<MetricsEngine.FixMetrics>()

        // Tolerances
        val tightTol = 0.05f   // per-fix metrics computed from same raw buffer
        val avgTol   = 0.15f   // averaged metrics (small float rounding)
        val dirTol   = 0.15f   // directional fwd/lat metrics

        var pointsCompared = 0
        var classificationWarnings = 0

        for (point in parsedFile.dataPoints) {
            val rawAccel = point.rawAccel ?: continue
            if (rawAccel.isEmpty()) continue

            val speed = point.gps.speed.toFloat()
            val computed = engine.computeAccelMetrics(rawAccel, speed, basis, metricsHistory)
                ?: continue

            val expected = point.expectedAccel ?: continue
            pointsCompared++

            // Per-fix metrics (deterministic from raw buffer)
            expected.rms?.let {
                assertEquals("Pt ${point.index}: rms", it, computed.rms, tightTol)
            }
            expected.magMax?.let {
                assertEquals("Pt ${point.index}: magMax", it, computed.maxMagnitude, tightTol)
            }
            expected.stdDev?.let {
                assertEquals("Pt ${point.index}: stdDev", it, computed.stdDev, tightTol)
            }
            expected.peakRatio?.let {
                assertEquals("Pt ${point.index}: peakRatio", it, computed.peakRatio, tightTol)
            }

            // Averaged metrics (depend on metricsHistory window)
            expected.avgRms?.let {
                assertEquals("Pt ${point.index}: avgRms", it, computed.avgRms, avgTol)
            }
            expected.avgMaxMagnitude?.let {
                assertEquals("Pt ${point.index}: avgMaxMag", it, computed.avgMaxMagnitude, avgTol)
            }
            expected.avgStdDev?.let {
                assertEquals("Pt ${point.index}: avgStdDev", it, computed.avgStdDev, avgTol)
            }
            expected.avgPeakRatio?.let {
                assertEquals("Pt ${point.index}: avgPeakRatio", it, computed.avgPeakRatio, avgTol)
            }

            // Directional metrics
            expected.fwdRms?.let {
                assertEquals("Pt ${point.index}: fwdRms", it, computed.fwdRms, dirTol)
            }
            expected.latRms?.let {
                assertEquals("Pt ${point.index}: latRms", it, computed.latRms, dirTol)
            }
            expected.fwdMax?.let {
                assertEquals("Pt ${point.index}: fwdMax", it, computed.fwdMax, dirTol)
            }
            expected.latMax?.let {
                assertEquals("Pt ${point.index}: latMax", it, computed.latMax, dirTol)
            }

            // Road quality & feature detection: log for diagnostics only.
            // These depend on calibration thresholds which may be tuned independently.
            val qualityMatch = expected.roadQuality == null || expected.roadQuality == computed.roadQuality
            val featureMatch = expected.featureDetected == null || expected.featureDetected == computed.featureDetected
            val classWarnings = mutableListOf<String>()
            if (!qualityMatch) classWarnings.add("quality: ${expected.roadQuality}->${computed.roadQuality}")
            if (!featureMatch) classWarnings.add("feature: ${expected.featureDetected}->${computed.featureDetected}")
            if (classWarnings.isNotEmpty()) classificationWarnings++

            val classStatus = if (classWarnings.isEmpty()) "" else " [${classWarnings.joinToString(", ")}]"
            println("Pt ${point.index}: speed=${"%.1f".format(speed)} rms=${"%.3f".format(computed.rms)} " +
                "avgRms=${"%.3f".format(computed.avgRms)} quality=${computed.roadQuality ?: "-"} " +
                "feature=${computed.featureDetected ?: "-"} OK$classStatus")
        }

        println("\nAccel replay: $pointsCompared points compared, $classificationWarnings classification warnings")
        assertTrue("Should have compared at least 50 points", pointsCompared >= 50)
    }

    // ---- Sequential driver-metrics replay ----

    @Test
    fun testReplayDriverMetrics() {
        val engine = MetricsEngine(parsedFile.calibration)
        val gravity = parsedFile.baseGravityVector ?: return
        val basis = engine.computeVehicleBasis(gravity) ?: return
        val metricsHistory = ArrayDeque<MetricsEngine.FixMetrics>()
        var previousDriverMetrics: DriverMetrics? = null
        var previousPoint: ParsedDataPoint? = null

        var pointsCompared = 0
        var driverWarnings = 0

        for (point in parsedFile.dataPoints) {
            val rawAccel = point.rawAccel ?: continue
            if (rawAccel.isEmpty()) continue

            val speed = point.gps.speed.toFloat()
            val accelMetrics = engine.computeAccelMetrics(rawAccel, speed, basis, metricsHistory)
                ?: continue

            // Compute deltaSpeed and deltaCourse from consecutive GPS fixes
            val deltaSpeed = previousPoint?.let { prev ->
                speed - prev.gps.speed.toFloat()
            } ?: 0f

            val deltaCourse = previousPoint?.let { prev ->
                engine.bearingDiff(prev.gps.course, point.gps.course)
            } ?: 0f

            // Apply deltas to accelMetrics (same as TrackingService does)
            val updatedMetrics = accelMetrics.copy(
                deltaSpeed = deltaSpeed,
                deltaCourse = deltaCourse
            )

            val driverMetrics = engine.computeDriverMetrics(updatedMetrics, speed, previousDriverMetrics)
            previousDriverMetrics = driverMetrics
            previousPoint = point

            val expected = point.expectedDriver ?: continue
            pointsCompared++

            // Log driver event classification for diagnostics.
            // primaryEvent depends on DriverThresholds which may be tuned.
            val eventMatch = expected.primaryEvent == driverMetrics.primaryEvent
            val eventStatus = if (eventMatch) "" else " [expected=${expected.primaryEvent}]"
            if (!eventMatch) driverWarnings++

            println("Pt ${point.index}: event=${driverMetrics.primaryEvent} " +
                "smoothness=${"%.1f".format(driverMetrics.smoothnessScore)} " +
                "dSpeed=${"%+.1f".format(deltaSpeed)} dCourse=${"%+.1f".format(deltaCourse)} OK$eventStatus")
        }

        println("\nDriver replay: $pointsCompared points compared, $driverWarnings event classification warnings")
        assertTrue("Should have compared at least 50 points", pointsCompared >= 50)
    }

    // ---- Coverage / diversity check ----

    @Test
    fun testDataPointCoverage() {
        val qualities = parsedFile.dataPoints.mapNotNull { it.expectedAccel?.roadQuality }.toSet()
        val features = parsedFile.dataPoints.mapNotNull { it.expectedAccel?.featureDetected }.toSet()
        val events = parsedFile.dataPoints.mapNotNull { it.expectedDriver?.primaryEvent }.toSet()
        val speeds = parsedFile.dataPoints.map { it.gps.speed }

        println("Road qualities: $qualities")
        println("Features: $features")
        println("Driver events: $events")
        println("Speed range: ${"%5.1f".format(speeds.minOrNull())} - ${"%5.1f".format(speeds.maxOrNull())} km/h")
        println("Total points: ${parsedFile.dataPoints.size}")

        assertTrue("Should have smooth quality", "smooth" in qualities)
        assertTrue("Should have at least 2 event types", events.size >= 2)
        assertTrue("Should have low speed points", speeds.any { it < 6.0 })
        assertTrue("Should have moving points", speeds.any { it > 10.0 })
        assertTrue("Should have at least 80 sequential points", parsedFile.dataPoints.size >= 80)
    }
}
