package com.sj.obd2app.can

import android.content.Context
import android.util.Log
import com.sj.obd2app.gps.GpsDataItem
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.AccelEngine
import com.sj.obd2app.metrics.AccelMetrics
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.sensors.AccelerometerSource
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * Sample-and-Hold ticker for synchronized CAN + GPS + Accelerometer data collection.
 *
 * Fires at a configurable rate driven by [CanProfile.syncTickerHz] (default 50 Hz, range 1–200), and
 * on every tick:
 *  1. Snapshots all active DBC signal values from [CanBusScanner] (zero-order hold).
 *  2. Applies [CanProfile.tripMetricMapping] to populate named [VehicleMetrics] fields.
 *  3. Merges GPS from [GpsDataSource] (always included).
 *  4. If [AppSettings.isAccelerometerEnabled]: drains [AccelerometerSource] and computes
 *     accelerometer-derived metrics via [AccelEngine].
 *  5. Calls [MetricsCalculator.updateMetrics] to feed Trip / Dashboard screens.
 *  6. If logging is active: calls [MetricsCalculator.logMetrics] to write the trip JSON row.
 *
 * Additionally, when a [writer] is provided (CAN raw log mode), writes a unified JSONL row
 * for offline analysis (same schema as the previous DataOrchestrator).
 *
 * ### Lifecycle
 * Call [start] after [CanBusScanner.start]. Call [stop] when the scan / trip ends.
 * The optional [writer] is owned and closed by the caller — [CanDataOrchestrator] never closes it.
 *
 * ### Threading
 * The ticker coroutine runs on [Dispatchers.IO].
 */
object CanDataOrchestrator {

    private const val TAG = "CanDataOrchestrator"

    private val stateMap: ConcurrentHashMap<String, Double> = ConcurrentHashMap()

    private var tickerJob: Job? = null

    val isRunning: Boolean get() = tickerJob?.isActive == true

    /**
     * Metric keys used in [CanProfile.tripMetricMapping] → [VehicleMetrics] field mapping.
     * These must match the keys stored in the profile JSON.
     */
    object MetricKey {
        const val RPM = "rpm"
        const val VEHICLE_SPEED_KMH = "vehicleSpeedKmh"
        const val COOLANT_TEMP_C = "coolantTempC"
        const val THROTTLE_PCT = "throttlePct"
        const val MAF_GS = "mafGs"
        const val INTAKE_MAP_KPA = "intakeMapKpa"
        const val ENGINE_LOAD_PCT = "engineLoadPct"
        const val FUEL_LEVEL_PCT = "fuelLevelPct"
        const val INTAKE_TEMP_C = "intakeTempC"

        /** All supported metric keys in display order. */
        val ALL = listOf(
            RPM, VEHICLE_SPEED_KMH, COOLANT_TEMP_C, THROTTLE_PCT, MAF_GS,
            INTAKE_MAP_KPA, ENGINE_LOAD_PCT, FUEL_LEVEL_PCT, INTAKE_TEMP_C
        )

        /** Human-readable label for each key. */
        fun label(key: String): String = when (key) {
            RPM -> "Engine RPM"
            VEHICLE_SPEED_KMH -> "Vehicle Speed (km/h)"
            COOLANT_TEMP_C -> "Coolant Temp (°C)"
            THROTTLE_PCT -> "Throttle Position (%)"
            MAF_GS -> "MAF (g/s)"
            INTAKE_MAP_KPA -> "Intake MAP (kPa)"
            ENGINE_LOAD_PCT -> "Engine Load (%)"
            FUEL_LEVEL_PCT -> "Fuel Level (%)"
            INTAKE_TEMP_C -> "Intake Air Temp (°C)"
            else -> key
        }
    }

    // ── Accel engine (stateful across ticks) ─────────────────────────────────

    private val accelEngine = AccelEngine()
    @Volatile private var vehicleBasis: AccelEngine.VehicleBasis? = null
    @Volatile private var waitingForGravityCapture = false

    /**
     * Call when a new trip starts to reset the accel gravity-capture state.
     * [MetricsCalculator.startTripInternal] already starts AccelerometerSource; this
     * method resets the basis so the first gravity reading is captured fresh.
     */
    fun resetAccelBasis() {
        vehicleBasis = null
        waitingForGravityCapture = true
    }

    // ── Ticker ────────────────────────────────────────────────────────────────

    /**
     * Start the synchronized ticker.
     *
     * @param context  Used to read the Hz setting and look up data source singletons.
     * @param profile  Active CAN profile — its [CanProfile.tripMetricMapping] is used to
     *                 populate [VehicleMetrics] fields on every tick.
     * @param calculator  [MetricsCalculator] to push metric snapshots into.
     * @param writer   Optional pre-opened, caller-owned [BufferedWriter] for raw JSONL output.
     *                 Pass null in preview mode (no trip started yet).
     */
    fun start(
        context: Context,
        profile: CanProfile,
        calculator: MetricsCalculator,
        writer: BufferedWriter?
    ) {
        if (tickerJob?.isActive == true) {
            Log.w(TAG, "start() ignored — already running")
            return
        }
        stateMap.clear()

        val hz = profile.syncTickerHz.coerceIn(1, 200)
        val intervalMs = 1000L / hz

        val gpsSource = GpsDataSource.getInstance(context)
        val accelSource = AccelerometerSource.getInstance(context)
        val accelEnabled = AppSettings.isAccelerometerEnabled(context)

        Log.i(TAG, "CanDataOrchestrator started at ${hz}Hz (${intervalMs}ms interval) " +
            "accel=${accelEnabled} writer=${writer != null}")

        tickerJob = CoroutineScope(Dispatchers.IO).launch {
            var nextTickAt = System.currentTimeMillis()

            while (isActive) {
                val now = System.currentTimeMillis()

                // ── 1. Ingest latest CAN signal values (zero-order hold) ───────
                val canSnapshot = CanBusScanner.snapshotLatest()
                for ((key, sample) in canSnapshot) {
                    stateMap[key] = sample.value
                }

                // ── 2. Ingest latest GPS fix ──────────────────────────────────
                val gps = gpsSource.gpsData.value
                gps?.let {
                    stateMap["gps.speed_kmh"]  = round3(it.speedKmh.toDouble())
                    stateMap["gps.alt_msl_m"]  = round3(it.altitudeMsl)
                    stateMap["gps.lat"]         = it.latitude
                    stateMap["gps.lon"]         = it.longitude
                    stateMap["gps.accuracy_m"] = round3(it.accuracyM.toDouble())
                    it.bearingDeg?.let { b -> stateMap["gps.bearing_deg"] = round3(b.toDouble()) }
                }

                // ── 3. Ingest accelerometer (average all samples since last tick)
                var accelMetrics: AccelMetrics? = null
                if (accelEnabled && accelSource.isAvailable) {
                    // Capture first gravity reading after trip start
                    if (waitingForGravityCapture) {
                        val gv = accelSource.gravityVector
                        if (gv != null) {
                            vehicleBasis = accelEngine.computeVehicleBasis(gv)
                            waitingForGravityCapture = false
                        }
                    }

                    val accelSamples = accelSource.drainBuffer()
                    if (accelSamples.isNotEmpty()) {
                        // Write raw JSONL accel averages (for offline log)
                        var ax = 0f; var ay = 0f; var az = 0f
                        for (s in accelSamples) { ax += s[0]; ay += s[1]; az += s[2] }
                        val n = accelSamples.size.toFloat()
                        stateMap["accel.x"] = round3((ax / n).toDouble())
                        stateMap["accel.y"] = round3((ay / n).toDouble())
                        stateMap["accel.z"] = round3((az / n).toDouble())

                        // Compute structured AccelMetrics for VehicleMetrics
                        accelMetrics = accelEngine.computeAccelMetrics(accelSamples, vehicleBasis)
                    }
                }

                // ── 4. Build VehicleMetrics from CAN mapping + GPS + accel ────
                val metrics = buildVehicleMetrics(
                    now = now,
                    canSnapshot = canSnapshot,
                    mapping = profile.tripMetricMapping,
                    gps = gps,
                    accelMetrics = accelMetrics
                )

                // ── 5. Push to MetricsCalculator (feeds Trip / Dashboard) ─────
                calculator.updateMetrics(metrics)
                if (calculator.isLoggingActive) {
                    calculator.logMetrics(metrics)
                }

                // ── 6. Write unified JSONL row (for offline analysis) ─────────
                if (writer != null && stateMap.isNotEmpty()) {
                    try {
                        val sb = StringBuilder("{\"t\":").append(now)
                        for ((k, v) in stateMap) {
                            sb.append(",\"").append(k).append("\":").append(v)
                        }
                        sb.append("}")
                        writer.appendLine(sb.toString())
                    } catch (e: Exception) {
                        Log.w(TAG, "tick write failed: ${e.message}", e)
                    }
                }

                // ── 7. Drift-corrected sleep until next tick ───────────────────
                nextTickAt += intervalMs
                val sleepMs = nextTickAt - System.currentTimeMillis()
                if (sleepMs > 0) delay(sleepMs)
                else nextTickAt = System.currentTimeMillis() // overrun — reset anchor
            }
        }
    }

    /**
     * Stop the ticker. Does NOT flush or close any writer — the caller owns it.
     */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        stateMap.clear()
        vehicleBasis = null
        waitingForGravityCapture = false
        Log.i(TAG, "CanDataOrchestrator stopped")
    }

    // ── VehicleMetrics builder ────────────────────────────────────────────────

    private fun buildVehicleMetrics(
        now: Long,
        canSnapshot: Map<String, CanBusScanner.LatestSample>,
        mapping: Map<String, String>,
        gps: GpsDataItem?,
        accelMetrics: AccelMetrics?
    ): VehicleMetrics {
        // Helper: resolve a metric key → Float? via the mapping
        fun mapped(key: String): Float? {
            val signalKey = mapping[key] ?: return null
            return canSnapshot[signalKey]?.value?.toFloat()
        }

        return VehicleMetrics(
            timestampMs = now,

            // CAN-mapped primary metrics
            rpm                = mapped(MetricKey.RPM),
            vehicleSpeedKmh    = mapped(MetricKey.VEHICLE_SPEED_KMH),
            coolantTempC       = mapped(MetricKey.COOLANT_TEMP_C),
            throttlePct        = mapped(MetricKey.THROTTLE_PCT),
            mafGs              = mapped(MetricKey.MAF_GS),
            intakeMapKpa       = mapped(MetricKey.INTAKE_MAP_KPA),
            engineLoadPct      = mapped(MetricKey.ENGINE_LOAD_PCT),
            fuelLevelPct       = mapped(MetricKey.FUEL_LEVEL_PCT),
            intakeTempC        = mapped(MetricKey.INTAKE_TEMP_C),

            // GPS (always populated when a fix is available)
            gpsLatitude        = gps?.latitude,
            gpsLongitude       = gps?.longitude,
            gpsSpeedKmh        = gps?.speedKmh,
            altitudeMslM       = gps?.altitudeMsl,
            gpsAccuracyM       = gps?.accuracyM,
            gpsBearingDeg      = gps?.bearingDeg,

            // All raw decoded CAN signal values (signal ref key → decoded value)
            canSignalValues    = canSnapshot.mapValues { it.value.value },

            // Accelerometer-derived (null when accel disabled or no samples)
            accelVertRms       = accelMetrics?.vertRms,
            accelVertMax       = accelMetrics?.vertMax,
            accelVertMean      = accelMetrics?.vertMean,
            accelVertStdDev    = accelMetrics?.vertStdDev,
            accelVertPeakRatio = accelMetrics?.vertPeakRatio,
            accelFwdRms        = accelMetrics?.fwdRms,
            accelFwdMax        = accelMetrics?.fwdMax,
            accelFwdMaxBrake   = accelMetrics?.fwdMaxBrake,
            accelFwdMaxAccel   = accelMetrics?.fwdMaxAccel,
            accelFwdMean       = accelMetrics?.fwdMean,
            accelLatRms        = accelMetrics?.latRms,
            accelLatMax        = accelMetrics?.latMax,
            accelLatMean       = accelMetrics?.latMean,
            accelLeanAngleDeg  = accelMetrics?.leanAngleDeg,
            accelRawSampleCount = accelMetrics?.rawAccelSampleCount
        )
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
