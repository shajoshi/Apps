package com.sj.obd2app.ui.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.sj.obd2app.R
import com.sj.obd2app.databinding.FragmentDetailsBinding
import com.sj.obd2app.ui.attachNavOverflow

/**
 * Details screen — displays all OBD-II values in a scrollable table
 * with columns: Parameter | Value | Unit.
 */
class DetailsFragment : Fragment() {

    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DetailsViewModel
    private lateinit var adapter: Obd2Adapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[DetailsViewModel::class.java]
        _binding = FragmentDetailsBinding.inflate(inflater, container, false)

        binding.topBarInclude.txtTopBarTitle.text = getString(R.string.menu_details)
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)

        adapter = Obd2Adapter()
        binding.recyclerviewObd2.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerviewObd2.adapter = adapter

        // Observe OBD2 data
        viewModel.obd2Data.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.textPidCount.text = getString(R.string.pid_count_format, items.size)
        }

        // Observe connection status
        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.textConnectionStatus.text = status
        }

        viewModel.isConnected.observe(viewLifecycleOwner) { connected ->
            val color = if (connected) {
                requireContext().getColor(android.R.color.holo_green_light)
            } else {
                requireContext().getColor(android.R.color.holo_red_dark)
            }
            binding.statusIndicator.setBackgroundColor(color)
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
