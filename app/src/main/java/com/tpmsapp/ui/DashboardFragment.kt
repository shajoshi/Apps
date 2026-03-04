package com.tpmsapp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.tpmsapp.databinding.FragmentDashboardBinding
import com.tpmsapp.model.TyreData
import com.tpmsapp.model.TyrePosition
import com.tpmsapp.viewmodel.TpmsViewModel

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TpmsViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.tyrePressures.observe(viewLifecycleOwner) { pressureMap ->
            updateTyreCard(pressureMap[TyrePosition.FRONT_LEFT], binding.cardFl)
            updateTyreCard(pressureMap[TyrePosition.FRONT_RIGHT], binding.cardFr)
            updateTyreCard(pressureMap[TyrePosition.REAR_LEFT], binding.cardRl)
            updateTyreCard(pressureMap[TyrePosition.REAR_RIGHT], binding.cardRr)
        }
    }

    private fun updateTyreCard(data: TyreData?, card: com.tpmsapp.ui.widget.TyreCardView) {
        if (data == null) {
            card.setNoData()
        } else {
            card.setTyreData(data)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
