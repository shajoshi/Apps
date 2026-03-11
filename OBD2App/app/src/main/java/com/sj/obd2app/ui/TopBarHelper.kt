package com.sj.obd2app.ui

import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.sj.obd2app.MainActivity
import com.sj.obd2app.MainPagerAdapter
import com.sj.obd2app.R

/**
 * Attaches a navigation overflow PopupMenu to the given anchor view.
 * Navigates via the main ViewPager2 hosted in MainActivity.
 */
fun Fragment.attachNavOverflow(anchor: View) {
    anchor.setOnClickListener {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val activity = requireActivity() as? MainActivity
            when (item.itemId) {
                R.id.nav_trip        -> activity?.navigateToPage(MainPagerAdapter.PAGE_TRIP)
                R.id.nav_connect     -> activity?.navigateToPage(MainPagerAdapter.PAGE_CONNECT)
                R.id.nav_layout_list -> activity?.navigateToPage(MainPagerAdapter.PAGE_DASHBOARDS)
                R.id.nav_details     -> activity?.navigateToPage(MainPagerAdapter.PAGE_DETAILS)
                R.id.nav_settings    -> activity?.navigateToPage(MainPagerAdapter.PAGE_SETTINGS)
            }
            true
        }
        popup.show()
    }
}
