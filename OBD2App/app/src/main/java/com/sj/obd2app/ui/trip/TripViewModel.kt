package com.sj.obd2app.ui.trip

import android.app.Application
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripLifecycleFacade
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.metrics.VehicleMetrics
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.sensors.AccelerometerSource
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
    val avgSpeed: String = "— km/h",
    val altitude: String = "— m",
    val coolantTemp: String = "— °C",
    val avgFuelKmpl: String = "— kmpl",
    val fuelCost: String = "— ₹",
    val cityPercent: String = "— %",
    val highwayPercent: String = "— %",
    val idlePercent: String = "— %",
    val powerThermoBhp: String = "— BHP",
    val instantKmpl: String = "— kmpl"
)


enum class IndicatorColor { GREEN, YELLOW, RED, GREY }

class TripViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val calculator = MetricsCalculator.getInstance(ctx)
    private val tripFacade = TripLifecycleFacade.getInstance(ctx)

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
                        _baseState.value = _baseState.value.copy(duration = d, sampleCount = s)
                    }
                    TripPhase.IDLE    -> {
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
        val isMock = !AppSettings.isObdConnectionEnabled(ctx) || Obd2ServiceProvider.useMock
        val (obdStatus, obdColor) = when {
            isMock -> "Simulation Mode" to IndicatorColor.YELLOW
            connState == Obd2Service.ConnectionState.CONNECTED  -> "Connected" to IndicatorColor.GREEN
            connState == Obd2Service.ConnectionState.CONNECTING -> "Connecting…" to IndicatorColor.YELLOW
            connState == Obd2Service.ConnectionState.ERROR      -> "Error" to IndicatorColor.RED
            else                                                -> "Disconnected" to IndicatorColor.GREY
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
        }

        // Trip fields are always rendered from the latest live metrics so the
        // screen keeps showing calculations even when the trip is idle.
        val distance    = "%.1f km".format(metrics.tripDistanceKm)
        val avgSpeed    = "%.1f km/h".format(metrics.tripAvgSpeedKmh)
        val altitude    = metrics.altitudeMslM?.let { "%.0f m".format(it) } ?: "— m"
        val coolantTemp = metrics.coolantTempC?.let { "%.0f °C".format(it) } ?: "— °C"
        val avgFuelKmpl = metrics.tripAvgKpl?.let { "%.1f kmpl".format(it) } ?: "— kmpl"
        val fuelCost    = metrics.fuelCostEstimate?.let { "₹%.2f".format(it) } ?: "— ₹"
        val cityPercent = "%.1f %%".format(metrics.pctCity)
        val highwayPercent = "%.1f %%".format(metrics.pctHighway)
        val idlePercent = "%.1f %%".format(metrics.pctIdle)

        // Power metrics in BHP
        val powerThermoBhp = metrics.powerThermoKw?.let {
            val bhp = it * 1.341f  // Convert kW to BHP
            "%.1f BHP".format(bhp)
        } ?: "— BHP"

        // Instantaneous fuel economy
        val instantKmpl = metrics.instantKpl?.let { "%.1f kmpl".format(it) } ?: "— kmpl"

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
            showGravityCard = accelEnabled || !accelAvail,
            gravityValues   = gravityStr,
            gravityMagnitude = gravityMagnitudeStr,
            gravityLabel    = gravityLabel,
            tripPhase       = phase,
            tripPhaseLabel  = phaseLabel,
            distanceKm      = distance,
            avgSpeed        = avgSpeed,
            altitude        = altitude,
            coolantTemp     = coolantTemp,
            avgFuelKmpl     = avgFuelKmpl,
            fuelCost        = fuelCost,
            cityPercent     = cityPercent,
            highwayPercent  = highwayPercent,
            idlePercent     = idlePercent,
            powerThermoBhp  = powerThermoBhp,
            instantKmpl     = instantKmpl
        )
    }

    fun startTrip() {
        if (_baseState.value.tripPhase != TripPhase.IDLE) return
        tripFacade.startTrip()
        Toast.makeText(ctx, "Trip started", Toast.LENGTH_SHORT).show()
    }

    fun stopTrip() {
        tripFacade.stopTrip()
        Toast.makeText(ctx, "Trip stopped", Toast.LENGTH_SHORT).show()
    }

    fun getLogShareUri() = calculator.getLogShareUri()

    
    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}
