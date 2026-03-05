package com.tpmsapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.tpmsapp.databinding.FragmentScanBinding
import com.tpmsapp.ui.adapter.RawAdvertisementAdapter
import com.tpmsapp.viewmodel.TpmsViewModel

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TpmsViewModel by activityViewModels()
    private lateinit var adapter: RawAdvertisementAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RawAdvertisementAdapter { raw ->
            AssignSensorDialogFragment.newInstance(raw.macAddress, raw.deviceName)
                .show(parentFragmentManager, "assign_sensor")
        }

        binding.recyclerScan.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
            this.adapter = this@ScanFragment.adapter
        }

        viewModel.discoveredDevices.observe(viewLifecycleOwner) { devices ->
            adapter.submitList(devices)
            binding.tvNoDevices.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
