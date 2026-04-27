package com.sj.obd2app.metrics

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.sj.obd2app.can.CanBusScanner
import com.sj.obd2app.can.CanDataOrchestrator
import com.sj.obd2app.can.CanProfileRepository
import com.sj.obd2app.obd.BluetoothObd2Service
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.obd.ObdConnectionManager
import com.sj.obd2app.service.TripForegroundService
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Central facade for trip lifecycle transitions.
 *
 * This class owns trip state, while [MetricsCalculator] keeps metrics state and
 * calculations. All UI entry points should use this facade for trip transitions
 * so the app follows a single lifecycle path.
 *
 * When CAN Bus logging is enabled (see [AppSettings.isCanBusLoggingEnabled]), trips are
 * driven by [CanBusScanner] instead of the OBD polling pipeline. OBD polling stays off for
 * the whole toggle lifetime (enforced by [BluetoothObd2Service.startPolling]), and trip
 * start/stop here simply forwards to the scanner and flips [TripPhase].
 */
class TripLifecycleFacade private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TripLifecycleFacade"

        @Volatile
        private var instance: TripLifecycleFacade? = null

        fun getInstance(context: Context): TripLifecycleFacade {
            return instance ?: synchronized(this) {
                instance ?: TripLifecycleFacade(context.applicationContext).also { instance = it }
            }
        }
    }

    private val calculator = MetricsCalculator.getInstance(context)
    private val connectionManager = ObdConnectionManager.getInstance(context)

    val tripPhase: StateFlow<TripPhase> = calculator.tripPhase

    fun startTrip() {
        if (AppSettings.isCanBusLoggingEnabled(context)) {
            startCanBusTrip()
        } else {
            startObdTrip()
        }
    }

    fun stopTrip() {
        if (AppSettings.isCanBusLoggingEnabled(context)) {
            stopCanBusTrip()
        } else {
            stopObdTrip()
        }
    }

    // ── OBD path (default) ────────────────────────────────────────────────────

    private fun startObdTrip() {
        calculator.setTripPhase(TripPhase.RUNNING)
        calculator.startTripInternal()
        TripForegroundService.start(context)
    }

    private fun stopObdTrip() {
        calculator.setTripPhase(TripPhase.IDLE)
        calculator.stopTripInternal()
        TripForegroundService.stop(context)
    }

    // ── CAN Bus path ──────────────────────────────────────────────────────────

    /**
     * Starts a trip in CAN Bus mode. Requires a starred default profile with at least one
     * signal selected and a connected ELM327 adapter. On any failure the trip is NOT marked
     * as RUNNING and a Toast is shown so the user can fix the preconditions.
     */
    private fun startCanBusTrip() {
        val profileId = AppSettings.getDefaultCanProfileId(context)
        val profile = profileId?.let { CanProfileRepository.getInstance(context).getById(it) }
        if (profile == null) {
            toast("No default CAN profile — star one in the CAN Bus Reader screen.")
            Log.w(TAG, "startCanBusTrip aborted: no default CAN profile")
            return
        }
        if (profile.selectedSignals.isEmpty()) {
            toast("CAN profile '${profile.name}' has no signals — edit it first.")
            Log.w(TAG, "startCanBusTrip aborted: profile has no signals")
            return
        }

        val svc = Obd2ServiceProvider.getService()
        if (svc.connectionState.value != Obd2Service.ConnectionState.CONNECTED) {
            val hint = if (com.sj.obd2app.obd.ObdStateManager.isMockMode)
                "Connect the Mock OBD2 Adapter before starting a CAN trip."
            else
                "Connect the ELM327 adapter before starting a CAN trip."
            toast(hint)
            Log.w(TAG, "startCanBusTrip aborted: adapter not connected")
            return
        }
        // In real mode we need BluetoothObd2Service so the scanner can borrow its transport.
        // In mock mode, CanBusScanner uses a synthetic frame source and does not touch the service.
        if (!com.sj.obd2app.obd.ObdStateManager.isMockMode && svc !is BluetoothObd2Service) {
            toast("CAN trip requires a Bluetooth ELM327 adapter in real mode.")
            Log.w(TAG, "startCanBusTrip aborted: not a BluetoothObd2Service in real mode")
            return
        }

        // If the scanner is already running in preview mode (auto-started on connect), stop it
        // cleanly and restart with previewMode=false so log files are created for this trip.
        // If it is Idle (e.g. preview was skipped), start fresh with logging enabled.
        if (CanBusScanner.state.value !is CanBusScanner.State.Idle) {
            CanBusScanner.stop()
            // Brief yield — stop() is synchronous state change; scan coroutine will cancel shortly.
        }
        CanBusScanner.start(context, profile, previewMode = false)

        // Start trip internals (AccelerometerSource, MetricsLogger, connection monitoring)
        // so accelerometer and GPS metrics flow into the trip JSON log.
        calculator.startTripInternal()
        CanDataOrchestrator.resetAccelBasis()
        CanDataOrchestrator.start(
            context = context,
            profile = profile,
            calculator = calculator,
            writer = null // raw JSONL writer owned by CanBusScanner; not needed here
        )

        calculator.setTripPhase(TripPhase.RUNNING)
        TripForegroundService.start(context)
        Log.i(TAG, "CAN Bus trip started with profile '${profile.name}'")
    }

    private fun stopCanBusTrip() {
        CanDataOrchestrator.stop()
        if (CanBusScanner.state.value !is CanBusScanner.State.Idle) {
            CanBusScanner.stop()
        }
        calculator.stopTripInternal()
        calculator.setTripPhase(TripPhase.IDLE)
        TripForegroundService.stop(context)
        Log.i(TAG, "CAN Bus trip stopped")
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast failed: ${e.message}")
        }
    }
}
