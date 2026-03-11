package com.sj.obd2app.metrics.collector

import android.content.Context
import com.sj.obd2app.gps.GpsDataItem
import com.sj.obd2app.gps.GpsDataSource
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.obd.Obd2DataItem
import com.sj.obd2app.obd.Obd2ServiceProvider
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class DataOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val calculator: MetricsCalculator
) {

    private val obdService = Obd2ServiceProvider.getService()
    private val gpsSource = GpsDataSource.getInstance(context)

    /** Most recently received OBD2 readings, keyed by PID */
    private var latestObd2: Map<String, String> = emptyMap()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startCollecting() {
        val combinedFlow = combine(
            obdService.obd2Data,
            gpsSource.gpsData
        ) { obdItems, gps ->
            // Update cached OBD2 data
            latestObd2 = obdItems.associate { it.pid to it.value }

            // Convert cached OBD2 to list format for calculator
            val obdList = latestObd2.entries.map { (pid, value) ->
                val cmd = com.sj.obd2app.obd.Obd2CommandRegistry.commands.firstOrNull { it.pid == pid }
                Obd2DataItem(pid = pid, name = cmd?.name ?: pid, value = value, unit = cmd?.unit ?: "")
            }

            Pair(obdList, gps)
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
