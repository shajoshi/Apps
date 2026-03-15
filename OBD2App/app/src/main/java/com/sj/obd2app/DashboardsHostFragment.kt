package com.sj.obd2app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.sj.obd2app.ui.dashboard.data.LayoutRepository

/**
 * Host fragment for the Dashboards page inside the main ViewPager2.
 * Embeds its own NavHostFragment so the LayoutList → Editor back-stack
 * is contained within this page and doesn't interfere with the pager.
 *
 * On first display, navigates directly to the default (starred) dashboard
 * if one is set. Falls through to LayoutListFragment otherwise.
 */
class DashboardsHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboards_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Only auto-navigate on first creation, not on config changes
        if (savedInstanceState != null) return

        val repo = LayoutRepository(requireContext())
        val defaultName = repo.getDefaultLayoutName() ?: return  // no default → stay on LayoutList

        // Get the child NavController and navigate to the editor with the default dashboard
        val navHost = childFragmentManager.findFragmentById(R.id.dashboards_nav_host) as? NavHostFragment
            ?: return
        val navController = navHost.navController

        val bundle = Bundle().apply {
            putString("layout_name", defaultName)
            putString("mode", "view")
            putBoolean("is_new", false)
        }
        navController.navigate(R.id.action_layoutList_to_editor, bundle)
    }
}
