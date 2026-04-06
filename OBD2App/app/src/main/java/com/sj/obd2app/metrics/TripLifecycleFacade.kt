package com.sj.obd2app.metrics

import android.content.Context
import com.sj.obd2app.obd.ObdConnectionManager
import com.sj.obd2app.service.TripForegroundService
import kotlinx.coroutines.flow.StateFlow

/**
 * Central facade for trip lifecycle transitions.
 *
 * This class owns trip state, while [MetricsCalculator] keeps metrics state and
 * calculations. All UI entry points should use this facade for trip transitions
 * so the app follows a single lifecycle path.
 */
class TripLifecycleFacade private constructor(private val context: Context) {

    companion object {
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
        calculator.setTripPhase(TripPhase.RUNNING)
        calculator.startTripInternal()
        TripForegroundService.start(context)
    }

    fun stopTrip() {
        calculator.setTripPhase(TripPhase.IDLE)
        calculator.stopTripInternal()
        TripForegroundService.stop(context)
    }
}
