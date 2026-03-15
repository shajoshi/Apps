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
import kotlinx.coroutines.runBlocking

/**
 * Attaches a navigation overflow PopupMenu to the given anchor view.
 * Navigates via the main ViewPager2 hosted in MainActivity.
 * Settings navigation is disabled during active trips (RUNNING or PAUSED).
 */
fun Fragment.attachNavOverflow(anchor: View) {
    anchor.setOnClickListener {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.overflow, popup.menu)
        
        // Get current trip phase to control Settings access
        val metricsCalculator = MetricsCalculator.getInstance(requireContext())
        val currentPhase = runBlocking { metricsCalculator.tripPhase.value }
        
        // Disable Settings menu item during active trips
        val settingsItem = popup.menu.findItem(R.id.nav_settings)
        settingsItem.isEnabled = currentPhase == TripPhase.IDLE
        
        popup.setOnMenuItemClickListener { item ->
            val activity = requireActivity() as? MainActivity
            when (item.itemId) {
                R.id.nav_trip        -> activity?.navigateToPage(MainPagerAdapter.PAGE_TRIP)
                R.id.nav_connect     -> activity?.navigateToPage(MainPagerAdapter.PAGE_CONNECT)
                R.id.nav_layout_list -> activity?.navigateToPage(MainPagerAdapter.PAGE_DASHBOARDS)
                R.id.nav_details     -> activity?.navigateToPage(MainPagerAdapter.PAGE_DETAILS)
                R.id.nav_settings    -> {
                    if (currentPhase == TripPhase.IDLE) {
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
