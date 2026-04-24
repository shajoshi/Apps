package com.sj.obd2app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sj.obd2app.ui.can.CanReaderFragment
import com.sj.obd2app.ui.connect.ConnectFragment
import com.sj.obd2app.ui.details.DetailsFragment
import com.sj.obd2app.ui.mapview.MapViewFragment
import com.sj.obd2app.ui.settings.SettingsFragment
import com.sj.obd2app.ui.trip.TripFragment
import com.sj.obd2app.ui.tripsummary.TripSummaryFragment

/**
 * ViewPager2 adapter hosting the 8 main screens:
 * 0 = Connect, 1 = Trip, 2 = Dashboards, 3 = Details, 4 = Trip Summary, 5 = Map View,
 * 6 = Settings, 7 = CAN Bus Reader.
 * Trip Summary, Map View, Settings, and CAN Bus Reader are accessed from overflow actions only.
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val PAGE_CONNECT      = 0
        const val PAGE_TRIP         = 1
        const val PAGE_DASHBOARDS   = 2
        const val PAGE_DETAILS      = 3
        const val PAGE_TRIP_SUMMARY = 4
        const val PAGE_MAP_VIEW     = 5
        const val PAGE_SETTINGS     = 6
        const val PAGE_CAN_READER   = 7
        const val PAGE_COUNT        = 8
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment = when (position) {
        PAGE_CONNECT      -> ConnectFragment()
        PAGE_TRIP         -> TripFragment()
        PAGE_DASHBOARDS   -> DashboardsHostFragment()
        PAGE_DETAILS      -> DetailsFragment()
        PAGE_TRIP_SUMMARY -> TripSummaryFragment()
        PAGE_MAP_VIEW     -> MapViewFragment()
        PAGE_SETTINGS     -> SettingsFragment()
        PAGE_CAN_READER   -> CanReaderFragment()
        else              -> ConnectFragment()
    }
}
