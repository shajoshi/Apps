package com.sj.obd2app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * Host fragment for the Dashboards page inside the main ViewPager2.
 * Embeds its own NavHostFragment so the LayoutList → Editor back-stack
 * is contained within this page and doesn't interfere with the pager.
 */
class DashboardsHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboards_host, container, false)
}
