package com.sj.obd2app.ui.trip

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.sensors.AccelerometerSource
import com.sj.obd2app.service.TripForegroundService
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class TripUiState(
    val obdStatus: String = "—",
    val obdIndicator: IndicatorColor = IndicatorColor.GREY,
    val loggingStatus: String = "—",
    val loggingIndicator: IndicatorColor = IndicatorColor.GREY,
    val gpsStatus: String = "—",
    val gpsDetail: String = "",
    val gpsIndicator: IndicatorColor = IndicatorColor.GREY,
    val accelStatus: String = "—",
    val accelPower: String = "",
    val accelIndicator: IndicatorColor = IndicatorColor.GREY,
    val showGravityCard: Boolean = false,
    val gravityValues: String = "X: —   Y: —   Z: —",
    val gravityMagnitude: String = "— g",
    val gravityLabel: String = "Awaiting trip start…",
    val tripPhase: TripPhase = TripPhase.IDLE,
    val tripPhaseLabel: String = "IDLE",
    val sampleCount: String = "0",
    val duration: String = "00:00",
    val distanceKm: String = "0.0 km",
    val fuelCost: String = "— $",
    val idlePercent: String = "— %"
)

enum class IndicatorColor { GREEN, YELLOW, RED, GREY }

class TripViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val calculator = MetricsCalculator.getInstance(ctx)

    // ── Sensor/OBD/phase state (everything except timer fields) ──────────────
    private val _baseState = MutableStateFlow(TripUiState())

    // ── Public state — ticker patches in-place via update{} ──────────────────
    val uiState: StateFlow<TripUiState> get() = _baseState

    init {
        // Collect sensor/OBD state. Merges timer fields atomically so they are never lost.
        viewModelScope.launch {
            combine(
                calculator.metrics,
                calculator.tripPhase,
                Obd2ServiceProvider.getService().connectionState
            ) { metrics, phase, connState ->
                buildUiState(metrics, phase, connState)
            }.collect { incoming ->
                val current = _baseState.value
                Log.d(TAG, "combine emit phase=${incoming.tripPhase} " +
                    "keeping dur=${current.duration} samples=${current.sampleCount}")
                _baseState.value = incoming.copy(
                    duration    = current.duration,
                    sampleCount = current.sampleCount
                )
            }
        }

        // 1-second ticker: sole writer of duration + sampleCount.
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                when (_baseState.value.tripPhase) {
                    TripPhase.RUNNING -> {
                        val d = formatDuration(calculator.elapsedTripSec())
                        val s = calculator.currentSampleNo.toString()
                        Log.d(TAG, "ticker RUNNING dur=$d samples=$s")
                        _baseState.value = _baseState.value.copy(duration = d, sampleCount = s)
                    }
                    TripPhase.PAUSED  -> Log.d(TAG, "ticker PAUSED frozen " +
                        "dur=${_baseState.value.duration} samples=${_baseState.value.sampleCount}")
                    TripPhase.IDLE    -> {
                        Log.d(TAG, "ticker IDLE reset")
                        _baseState.value = _baseState.value.copy(duration = "00:00", sampleCount = "0")
                    }
                }
            }
        }
    }

    private fun buildUiState(
        metrics: VehicleMetrics,
        phase: TripPhase,
        connState: Obd2Service.ConnectionState
    ): TripUiState {

        // OBD2
        val (obdStatus, obdColor) = when (connState) {
            Obd2Service.ConnectionState.CONNECTED    -> "Connected" to IndicatorColor.GREEN
            Obd2Service.ConnectionState.CONNECTING   -> "Connecting…" to IndicatorColor.YELLOW
            Obd2Service.ConnectionState.ERROR        -> "Error" to IndicatorColor.RED
            else                                     -> "Disconnected" to IndicatorColor.GREY
        }

        // Logging
        val loggingEnabled = AppSettings.isLoggingEnabled(ctx)
        val loggingStatus = if (loggingEnabled) "On" else "Off"
        val loggingColor = if (loggingEnabled) IndicatorColor.GREEN else IndicatorColor.YELLOW

        // GPS
        val hasFix = metrics.gpsLatitude != null
        val (gpsStatus, gpsDetail, gpsColor) = if (hasFix) {
            val speed   = metrics.gpsSpeedKmh?.let { "%.0f km/h".format(it) } ?: "—"
            val alt     = metrics.altitudeMslM?.let { "%.0f m".format(it) } ?: "—"
            val acc     = metrics.gpsAccuracyM?.let { "±%.0f m".format(it) } ?: ""
            Triple("Fix", "$speed · Alt $alt · $acc", IndicatorColor.GREEN)
        } else {
            Triple("No fix", "", IndicatorColor.YELLOW)
        }

        // Accel
        val accelEnabled = AppSettings.isAccelerometerEnabled(ctx)
        val accelAvail   = AccelerometerSource.getInstance(ctx).isAvailable
        val gravityVec   = calculator.capturedGravityVector

        val (accelStatus, accelColor) = when {
            !accelEnabled          -> "Disabled" to IndicatorColor.GREY
            !accelAvail            -> "No sensor" to IndicatorColor.RED
            gravityVec != null     -> "Active" to IndicatorColor.GREEN
            else                   -> "Awaiting start" to IndicatorColor.YELLOW
        }

        // Accel power — use powerAccelKw from metrics (already signed via accelFwdMean)
        val accelPowerStr = metrics.powerAccelKw?.let {
            val bhp = it * 1.341f  // Convert kW to BHP (1 kW ≈ 1.341 HP)
            val sign = if (bhp >= 0f) "+" else ""
            "$sign%.1f BHP".format(bhp)
        } ?: ""

        // Gravity vector display
        val gravityStr = gravityVec?.let {
            "X: %+.2f   Y: %+.2f   Z: %+.2f".format(it[0], it[1], it[2])
        } ?: "X: —   Y: —   Z: —"

        val gravityMagnitudeStr = gravityVec?.let {
            val magnitude = kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
            "%.2f g".format(magnitude)
        } ?: "— g"

        val gravityLabel = when {
            gravityVec != null        -> "Captured — basis locked"
            phase == TripPhase.IDLE   -> "Awaiting trip start…"
            else                      -> "Capturing…"
        }

        // Phase label
        val phaseLabel = when (phase) {
            TripPhase.IDLE    -> "IDLE"
            TripPhase.RUNNING -> "RUNNING"
            TripPhase.PAUSED  -> "PAUSED"
        }

        // Trip fields — duration/sampleCount are intentionally NOT set here;
        // they are merged back in the collect lambda and owned by the 1-s ticker.
        val distance = if (phase == TripPhase.IDLE) "0.0 km" else "%.1f km".format(metrics.tripDistanceKm)
        val fuelCost = if (phase == TripPhase.IDLE) "— $" 
                      else metrics.fuelCostEstimate?.let { "%.2f $".format(it) } ?: "— $"
        val idlePercent = if (phase == TripPhase.IDLE) "— %" 
                         else "%.1f %%".format(metrics.pctIdle)

        return TripUiState(
            obdStatus       = obdStatus,
            obdIndicator    = obdColor,
            loggingStatus   = loggingStatus,
            loggingIndicator = loggingColor,
            gpsStatus       = gpsStatus,
            gpsDetail       = gpsDetail,
            gpsIndicator    = gpsColor,
            accelStatus     = accelStatus,
            accelPower      = accelPowerStr,
            accelIndicator  = accelColor,
            showGravityCard = accelEnabled,
            gravityValues   = gravityStr,
            gravityMagnitude = gravityMagnitudeStr,
            gravityLabel    = gravityLabel,
            tripPhase       = phase,
            tripPhaseLabel  = phaseLabel,
            distanceKm      = distance,
            fuelCost        = fuelCost,
            idlePercent     = idlePercent
        )
    }

    fun startTrip() {
        if (_baseState.value.tripPhase != TripPhase.IDLE) {
            Log.w(TAG, "startTrip ignored: not IDLE (phase=${_baseState.value.tripPhase})")
            return
        }
        Log.d(TAG, "startTrip")
        calculator.startTrip()
        TripForegroundService.start(ctx)
        Toast.makeText(ctx, "Trip started", Toast.LENGTH_SHORT).show()
    }

    fun pauseTrip() {
        Log.d(TAG, "pauseTrip dur=${_baseState.value.duration} samples=${_baseState.value.sampleCount}")
        calculator.pauseTrip()
    }

    fun resumeTrip() {
        if (_baseState.value.tripPhase != TripPhase.PAUSED) {
            Log.w(TAG, "resumeTrip ignored: not PAUSED (phase=${_baseState.value.tripPhase})")
            return
        }
        Log.d(TAG, "resumeTrip dur=${_baseState.value.duration} samples=${_baseState.value.sampleCount}")
        calculator.resumeTrip()
    }

    fun stopTrip() {
        Log.d(TAG, "stopTrip")
        calculator.stopTrip()
        TripForegroundService.stop(ctx)
        Toast.makeText(ctx, "Trip stopped", Toast.LENGTH_SHORT).show()
    }

    fun getLogShareUri() = calculator.getLogShareUri()

    companion object {
        private const val TAG = "TripViewModel"
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}
