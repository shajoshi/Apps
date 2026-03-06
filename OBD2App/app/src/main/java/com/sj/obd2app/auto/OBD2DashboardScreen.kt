package com.sj.obd2app.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.sj.obd2app.obd.Obd2Service
import com.sj.obd2app.obd.Obd2ServiceProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Screen displayed on the car's head unit.
 * Observes OBD-II data and formats it into a simple list of rows.
 */
class OBD2DashboardScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val obd2Service = Obd2ServiceProvider.getService()
    
    private var rpm: String = "—"
    private var speed: String = "—"
    private var coolant: String = "—"
    private var load: String = "—"
    private var connectionStatus: String = "Disconnected"

    private var dataJob: Job? = null
    private var statusJob: Job? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // Start collecting data streams when the screen is visible
        dataJob = lifecycleScope.launch {
            obd2Service.obd2Data.collect { items ->
                var updated = false
                for (item in items) {
                    when (item.pid) {
                        "010C" -> { rpm = item.value; updated = true }
                        "010D" -> { speed = item.value; updated = true }
                        "0105" -> { coolant = item.value; updated = true }
                        "0104" -> { load = item.value; updated = true }
                    }
                }
                if (updated) invalidate() // Requests a screen update
            }
        }
        
        statusJob = lifecycleScope.launch {
            obd2Service.connectionState.collect { state ->
                connectionStatus = when (state) {
                    Obd2Service.ConnectionState.DISCONNECTED -> "Disconnected"
                    Obd2Service.ConnectionState.CONNECTING -> "Connecting..."
                    Obd2Service.ConnectionState.CONNECTED -> "Connected"
                    Obd2Service.ConnectionState.ERROR -> "Error"
                }
                invalidate()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // Stop listening while not visible
        dataJob?.cancel()
        statusJob?.cancel()
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()

        // Connection Summary Row
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("OBD-II Status")
                .addText(connectionStatus)
                .build()
        )

        // Metrics Rows
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Engine RPM")
                .addText("$rpm rpm")
                .build()
        )
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Vehicle Speed")
                .addText("$speed km/h")
                .build()
        )

        paneBuilder.addRow(
            Row.Builder()
                .setTitle("Coolant Temp")
                .addText("$coolant °C")
                .build()
        )

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("OBD2 Dashboard")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
