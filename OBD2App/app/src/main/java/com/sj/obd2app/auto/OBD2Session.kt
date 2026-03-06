package com.sj.obd2app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto session that launches the main dashboard screen.
 */
class OBD2Session : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Return the dashboard screen for the car UI
        return OBD2DashboardScreen(carContext)
    }
}
