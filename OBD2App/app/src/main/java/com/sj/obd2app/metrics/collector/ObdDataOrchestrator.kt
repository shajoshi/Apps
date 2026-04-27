package com.sj.obd2app.metrics.collector

import android.content.Context
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Orchestrates data collection from OBD2 and GPS sources.
 *
 * Combines the two flows with debouncing to avoid redundant calculations
 * when both sources emit simultaneously. All processing happens on Dispatchers.Default.
 */
class ObdDataOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val calculator: MetricsCalculator
) {

    private val gpsSource = GpsDataSource.getInstance(context)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startCollecting() {
        val obdService = Obd2ServiceProvider.getService()
        val combinedFlow = combine(
            obdService.obd2Data,
            gpsSource.gpsData
        ) { obdItems, gps ->
            Pair(obdItems, gps)
        }
        .flowOn(Dispatchers.Default)
        .debounce(100L) // Wait 100ms after last emission to batch rapid updates

        scope.launch {
            combinedFlow.collect { (obdItems, gps) ->
                val snapshot = calculator.calculate(obdItems, gps)
                calculator.updateMetrics(snapshot)
                if (AppSettings.isLoggingEnabled(context) && calculator.isLoggingActive) {
                    calculator.logMetrics(snapshot)
                }
            }
        }
    }
}
