package com.sj.obd2app

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.sj.obd2app.ui.dashboard.data.LayoutRepository
import com.sj.obd2app.ui.dashboard.model.DashboardOrientation

/**
 * Host fragment for the Dashboards page inside the main ViewPager2.
 * Embeds its own NavHostFragment so the LayoutList → Editor back-stack
 * is contained within this page and doesn't interfere with the pager.
 *
 * On first display, navigates directly to the default (starred) dashboard
 * if one is set. Falls through to LayoutListFragment otherwise.
 */
class DashboardsHostFragment : Fragment() {

    private var hasAutoNavigatedToDefault = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboards_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null) return

        view.post { navigateToDefaultDashboardIfNeeded() }
    }

    override fun onResume() {
        super.onResume()
        navigateToDefaultDashboardIfNeeded()
    }

    private fun navigateToDefaultDashboardIfNeeded() {
        if (hasAutoNavigatedToDefault) return

        val repo = LayoutRepository(requireContext())
        val defaultName = repo.getDefaultLayoutName() ?: return

        val layout = repo.getSavedLayouts().find { it.name == defaultName }
        if (layout != null) {
            val currentOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                DashboardOrientation.LANDSCAPE else DashboardOrientation.PORTRAIT
            if (layout.orientation != currentOrientation) {
                val required = layout.orientation.name.lowercase().replaceFirstChar { it.uppercase() }
                Toast.makeText(
                    requireContext(),
                    "\"$defaultName\" requires $required mode. Opening dashboard list.",
                    Toast.LENGTH_LONG
                ).show()
                hasAutoNavigatedToDefault = true
                return
            }
        }

        val navHost = childFragmentManager.findFragmentById(R.id.dashboards_nav_host) as? NavHostFragment
            ?: return
        val navController = navHost.navController

        hasAutoNavigatedToDefault = true
        val bundle = Bundle().apply {
            putString("layout_name", defaultName)
            putString("mode", "view")
            putBoolean("is_new", false)
        }
        // Check current destination to use appropriate navigation method
        val currentDest = navController.currentDestination?.id
        if (currentDest == R.id.nav_layout_list) {
            navController.navigate(R.id.action_layoutList_to_editor, bundle)
        } else {
            // Navigate directly to the editor destination if not on layout list
            navController.navigate(R.id.nav_editor, bundle)
        }
    }
}
