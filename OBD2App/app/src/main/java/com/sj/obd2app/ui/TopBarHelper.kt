package com.sj.obd2app.ui

import android.view.View
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.sj.obd2app.R

/**
 * Attaches a navigation overflow PopupMenu to the given anchor view.
 * Items: Connect, Saved Dashboards, Details, Settings.
 */
fun Fragment.attachNavOverflow(anchor: View) {
    anchor.setOnClickListener {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val nav = findNavController()
            when (item.itemId) {
                R.id.nav_connect     -> nav.navigate(R.id.nav_connect)
                R.id.nav_layout_list -> nav.navigate(R.id.nav_layout_list)
                R.id.nav_details     -> nav.navigate(R.id.nav_details)
                R.id.nav_settings    -> nav.navigate(R.id.nav_settings)
            }
            true
        }
        popup.show()
    }
}
