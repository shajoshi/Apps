package com.tpmsapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.tpmsapp.databinding.FragmentSensorsBinding
import com.tpmsapp.model.TyrePosition
import com.tpmsapp.ui.adapter.SensorConfigAdapter
import com.tpmsapp.ui.adapter.SensorSlot
import com.tpmsapp.viewmodel.TpmsViewModel

class SensorsFragment : Fragment() {

    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TpmsViewModel by activityViewModels()
    private lateinit var adapter: SensorConfigAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SensorConfigAdapter(
            onRemove = { config -> viewModel.removeSensorConfig(config.position) },
            onAssign = { position ->
                AssignSensorDialogFragment.newInstance(null, null, position)
                    .show(parentFragmentManager, "assign_sensor")
            }
        )

        binding.recyclerSensors.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@SensorsFragment.adapter
        }

        viewModel.sensorConfigs.observe(viewLifecycleOwner) { configs ->
            val configMap = configs.associateBy { it.position }
            val slots = TyrePosition.values().map { pos ->
                SensorSlot(pos, configMap[pos])
            }
            adapter.submitList(slots)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
