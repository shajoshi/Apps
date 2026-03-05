package com.tpmsapp.ui

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.tpmsapp.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>("pressure_unit")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, newValue ->
                pref.summary = when (newValue) {
                    "psi" -> getString(R.string.unit_psi)
                    "bar" -> getString(R.string.unit_bar)
                    else  -> getString(R.string.unit_kpa)
                }
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("run_in_background")?.let { pref ->
            pref.setOnPreferenceChangeListener { _, _ ->
                true
            }
        }
    }
}
