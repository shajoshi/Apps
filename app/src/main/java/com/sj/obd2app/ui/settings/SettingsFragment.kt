package com.sj.obd2app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.sj.obd2app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    companion object {
        const val PREFS_NAME = "obd2_prefs"
        const val KEY_AUTO_CONNECT = "auto_connect_last_device"
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load saved setting (default: on)
        binding.switchAutoConnect.isChecked = prefs.getBoolean(KEY_AUTO_CONNECT, true)

        // Persist on change
        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_CONNECT, isChecked).apply()
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}