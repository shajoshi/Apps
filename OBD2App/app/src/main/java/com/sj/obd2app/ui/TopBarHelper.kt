package com.sj.obd2app.ui

import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.sj.obd2app.MainActivity
import com.sj.obd2app.MainPagerAdapter
import com.sj.obd2app.R
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.settings.AppSettings
import kotlinx.coroutines.runBlocking

/**
 * Attaches a navigation overflow PopupMenu to the given anchor view.
 * Navigates via the main ViewPager2 hosted in MainActivity.
 * Settings, Trip Summary, and Map View are disabled during active trips.
 */
fun Fragment.attachNavOverflow(anchor: View) {
    anchor.setOnClickListener {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.overflow, popup.menu)
        
        // Get current trip phase to control Settings access
        val metricsCalculator = MetricsCalculator.getInstance(requireContext())
        val currentPhase = runBlocking { metricsCalculator.tripPhase.value }
        
        val isTripActive = currentPhase != TripPhase.IDLE

        // Disable secondary destinations during active trips so users can see what is unavailable.
        popup.menu.findItem(R.id.nav_trip_summary)?.isEnabled = !isTripActive
        popup.menu.findItem(R.id.nav_settings)?.isEnabled = !isTripActive

        // CAN Bus Reader only appears when CAN Bus logging is enabled in Settings.
        val canBusEnabled = AppSettings.isCanBusLoggingEnabled(requireContext())
        popup.menu.findItem(R.id.nav_can_reader)?.let { item ->
            item.isVisible = canBusEnabled
            item.isEnabled = !isTripActive
        }
        
        popup.setOnMenuItemClickListener { item ->
            val activity = requireActivity() as? MainActivity
            when (item.itemId) {
                R.id.nav_trip        -> activity?.navigateToPage(MainPagerAdapter.PAGE_TRIP)
                R.id.nav_connect     -> activity?.navigateToPage(MainPagerAdapter.PAGE_CONNECT)
                R.id.nav_layout_list -> activity?.navigateToPage(MainPagerAdapter.PAGE_DASHBOARDS)
                R.id.nav_details     -> activity?.navigateToPage(MainPagerAdapter.PAGE_DETAILS)
                R.id.nav_trip_summary-> {
                    if (!isTripActive) {
                        activity?.navigateToPage(MainPagerAdapter.PAGE_TRIP_SUMMARY)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Trip Summary is not accessible during an active trip.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                R.id.nav_can_reader  -> {
                    if (!isTripActive) {
                        activity?.navigateToPage(MainPagerAdapter.PAGE_CAN_READER)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "CAN Bus Reader is not accessible during an active trip.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                R.id.nav_settings    -> {
                    if (!isTripActive) {
                        activity?.navigateToPage(MainPagerAdapter.PAGE_SETTINGS)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Settings not accessible during active trip. Please stop or complete the trip first.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            true
        }
        popup.show()
    }
}
