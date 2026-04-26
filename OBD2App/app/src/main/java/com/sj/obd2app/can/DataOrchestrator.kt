package com.sj.obd2app.can

import android.content.Context
import android.util.Log
import com.sj.obd2app.gps.GpsDataSource
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
 * Sample-and-Hold ticker for synchronized multi-source logging.
 *
 * Fires at a configurable rate (50 Hz or 100 Hz, set via Settings → "Sync ticker Hz") and
 * snapshots:
 *  - All active DBC signal values from [CanBusScanner] (zero-order hold via [CanBusScanner.snapshotLatest])
 *  - GPS metrics from [GpsDataSource] (speed, MSL altitude, lat/lon, bearing, accuracy)
 *  - Latest accelerometer samples from [AccelerometerSource] (averaged per tick: ax, ay, az)
 *
 * Each tick writes a single JSON line to [writer] with a unified wall-clock timestamp and all
 * current signal values, producing perfectly column-aligned output for MATLAB / Python analysis.
 *
 * If a signal has not updated since the last tick its last known value is re-emitted (zero-order hold).
 *
 * ### Lifecycle
 * Call [start] when the scan begins (after [CanBusScanner.start]) and [stop] when it ends.
 * The [writer] is owned and closed by the caller — [DataOrchestrator] never closes it.
 *
 * ### Threading
 * The ticker coroutine runs on [Dispatchers.IO]. All state reads are from thread-safe sources
 * ([ConcurrentHashMap], `@Volatile` GPS StateFlow, synchronized accelerometer drain).
 */
object DataOrchestrator {

    private const val TAG = "DataOrchestrator"

    private val stateMap: ConcurrentHashMap<String, Double> = ConcurrentHashMap()

    private var tickerJob: Job? = null

    val isRunning: Boolean get() = tickerJob?.isActive == true

    /**
     * Start the synchronised ticker.
     *
     * @param context  Used to read the Hz setting and look up data source singletons.
     * @param writer   Pre-opened, caller-owned [BufferedWriter] for the synchronized JSONL output.
     */
    fun start(context: Context, writer: BufferedWriter) {
        if (tickerJob?.isActive == true) {
            Log.w(TAG, "start() ignored — already running")
            return
        }
        stateMap.clear()

        val hz = AppSettings.getSyncTickerHz(context).coerceIn(1, 200)
        val intervalMs = 1000L / hz

        val gpsSource = GpsDataSource.getInstance(context)
        val accelSource = AccelerometerSource.getInstance(context)

        Log.i(TAG, "DataOrchestrator started at ${hz}Hz (${intervalMs}ms interval)")

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
                gpsSource.gpsData.value?.let { gps ->
                    stateMap["gps.speed_kmh"]  = round3(gps.speedKmh.toDouble())
                    stateMap["gps.alt_msl_m"]  = round3(gps.altitudeMsl)
                    stateMap["gps.lat"]         = gps.latitude
                    stateMap["gps.lon"]         = gps.longitude
                    stateMap["gps.accuracy_m"] = round3(gps.accuracyM.toDouble())
                    gps.bearingDeg?.let { stateMap["gps.bearing_deg"] = round3(it.toDouble()) }
                }

                // ── 3. Ingest accelerometer (average all samples since last tick)
                val accelSamples = accelSource.drainBuffer()
                if (accelSamples.isNotEmpty()) {
                    var ax = 0f; var ay = 0f; var az = 0f
                    for (s in accelSamples) { ax += s[0]; ay += s[1]; az += s[2] }
                    val n = accelSamples.size.toFloat()
                    stateMap["accel.x"] = round3((ax / n).toDouble())
                    stateMap["accel.y"] = round3((ay / n).toDouble())
                    stateMap["accel.z"] = round3((az / n).toDouble())
                }

                // ── 4. Write unified snapshot row ─────────────────────────────
                if (stateMap.isNotEmpty()) {
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

                // ── 5. Drift-corrected sleep until next tick ───────────────────
                nextTickAt += intervalMs
                val sleepMs = nextTickAt - System.currentTimeMillis()
                if (sleepMs > 0) delay(sleepMs)
                else nextTickAt = System.currentTimeMillis() // overrun — reset anchor
            }
        }
    }

    /**
     * Stop the ticker. Does NOT flush or close [writer] — the caller owns it.
     */
    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        stateMap.clear()
        Log.i(TAG, "DataOrchestrator stopped")
    }

    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}
