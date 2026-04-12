package com.sj.obd2app.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.sj.obd2app.databinding.FragmentDashboardBinding
import com.sj.obd2app.ui.attachNavOverflow

/**
 * Dashboard screen — shows key OBD-II gauges (RPM, Speed, Coolant Temp,
 * Throttle, Engine Load, Fuel Level) in Material Card widgets.
 */
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DashboardViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[DashboardViewModel::class.java]
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)

        binding.topBarInclude.txtTopBarTitle.text = getString(com.sj.obd2app.R.string.dashboard_title)
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)

        viewModel.rpm.observe(viewLifecycleOwner) { binding.textGaugeRpm.text = it }
        viewModel.speed.observe(viewLifecycleOwner) { binding.textGaugeSpeed.text = it }
        viewModel.coolantTemp.observe(viewLifecycleOwner) { binding.textGaugeCoolant.text = it }
        viewModel.throttle.observe(viewLifecycleOwner) { binding.textGaugeThrottle.text = it }
        viewModel.engineLoad.observe(viewLifecycleOwner) { binding.textGaugeEngineLoad.text = it }
        viewModel.fuelLevel.observe(viewLifecycleOwner) { binding.textGaugeFuelLevel.text = it }
        viewModel.connectionStatus.observe(viewLifecycleOwner) {
            binding.textDashboardStatus.text = it
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
