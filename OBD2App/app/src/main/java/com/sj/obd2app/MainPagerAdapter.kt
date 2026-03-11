package com.sj.obd2app

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sj.obd2app.ui.connect.ConnectFragment
import com.sj.obd2app.ui.details.DetailsFragment
import com.sj.obd2app.ui.settings.SettingsFragment
import com.sj.obd2app.ui.trip.TripFragment

/**
 * ViewPager2 adapter hosting the 5 main screens:
 * 0 = Trip, 1 = Connect, 2 = Dashboards, 3 = Details, 4 = Settings
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val PAGE_TRIP       = 0
        const val PAGE_CONNECT    = 1
        const val PAGE_DASHBOARDS = 2
        const val PAGE_DETAILS    = 3
        const val PAGE_SETTINGS   = 4
        const val PAGE_COUNT      = 5
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment = when (position) {
        PAGE_TRIP       -> TripFragment()
        PAGE_CONNECT    -> ConnectFragment()
        PAGE_DASHBOARDS -> DashboardsHostFragment()
        PAGE_DETAILS    -> DetailsFragment()
        PAGE_SETTINGS   -> SettingsFragment()
        else            -> TripFragment()
    }
}
