package com.sj.obd2app.ui.trip

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.sj.obd2app.databinding.FragmentTripBinding
import com.sj.obd2app.metrics.TripPhase
import com.sj.obd2app.settings.AppSettings
import com.sj.obd2app.settings.VehicleProfileRepository
import com.sj.obd2app.ui.attachNavOverflow
import kotlinx.coroutines.launch

class TripFragment : Fragment() {

    private var _binding: FragmentTripBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: TripViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[TripViewModel::class.java]
        _binding = FragmentTripBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val profileName = VehicleProfileRepository.getInstance(requireContext()).activeProfile?.name ?: "Profile"
        binding.topBarInclude.txtTopBarTitle.text = "Trip - $profileName"
        attachNavOverflow(binding.topBarInclude.btnTopOverflow)

        binding.btnStart.setOnClickListener {
            when (viewModel.uiState.value.tripPhase) {
                TripPhase.IDLE    -> viewModel.startTrip()
                TripPhase.PAUSED  -> viewModel.resumeTrip()
                else               -> { /* ignore */ }
            }
        }
        binding.btnPause.setOnClickListener {
            val phase = viewModel.uiState.value.tripPhase
            if (phase == TripPhase.RUNNING) viewModel.pauseTrip()
            else viewModel.resumeTrip()
        }
        binding.btnStop.setOnClickListener {
            viewModel.stopTrip()
            maybeShareLog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                applyState(state)
            }
        }
    }

    private fun applyState(state: TripUiState) {
        // OBD2
        binding.indicatorObd.backgroundTintList =
            android.content.res.ColorStateList.valueOf(indicatorColor(state.obdIndicator))
        binding.tvObdStatus.text = state.obdStatus

        // Logging
        binding.indicatorLogging.backgroundTintList =
            android.content.res.ColorStateList.valueOf(indicatorColor(state.loggingIndicator))
        binding.tvLoggingStatus.text = state.loggingStatus

        // GPS
        binding.indicatorGps.backgroundTintList =
            android.content.res.ColorStateList.valueOf(indicatorColor(state.gpsIndicator))
        binding.tvGpsStatus.text = state.gpsStatus
        binding.tvGpsDetail.text = state.gpsDetail
        binding.tvGpsDetail.visibility = if (state.gpsDetail.isEmpty()) View.GONE else View.VISIBLE

        // Accel
        binding.indicatorAccel.backgroundTintList =
            android.content.res.ColorStateList.valueOf(indicatorColor(state.accelIndicator))
        binding.tvAccelStatus.text = state.accelStatus
        binding.tvAccelPower.text = state.accelPower
        binding.tvAccelPower.visibility = if (state.accelPower.isEmpty()) View.GONE else View.VISIBLE

        // Gravity card
        binding.cardGravity.visibility = if (state.showGravityCard) View.VISIBLE else View.GONE
        binding.tvGravityValues.text = state.gravityValues
        binding.tvGravityMagnitude.text = state.gravityMagnitude
        binding.tvGravityLabel.text = state.gravityLabel

        // Trip stats
        binding.tvTripPhase.text = state.tripPhaseLabel
        binding.tvTripSamples.text = state.sampleCount
        binding.tvTripDuration.text = state.duration
        binding.tvTripDistance.text = state.distanceKm
        binding.tvFuelCost.text = state.fuelCost
        binding.tvIdlePercent.text = state.idlePercent

        // Phase colour on trip phase label
        binding.tvTripPhase.setTextColor(
            when (state.tripPhase) {
                TripPhase.RUNNING -> Color.parseColor("#4CAF50")
                TripPhase.PAUSED  -> Color.parseColor("#FFC107")
                TripPhase.IDLE    -> Color.parseColor("#AAAAAA")
            }
        )

        // Button visibility per phase
        when (state.tripPhase) {
            TripPhase.IDLE -> {
                binding.btnStart.visibility = View.VISIBLE
                binding.btnStart.text = "Start"
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.GONE
            }
            TripPhase.RUNNING -> {
                binding.btnStart.visibility = View.GONE
                binding.btnPause.visibility = View.VISIBLE
                binding.btnPause.text = "Pause"
                binding.btnStop.visibility = View.VISIBLE
            }
            TripPhase.PAUSED -> {
                binding.btnStart.visibility = View.VISIBLE
                binding.btnStart.text = "Resume"
                binding.btnPause.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
            }
        }
    }

    private fun maybeShareLog() {
        if (!AppSettings.isAutoShareLogEnabled(requireContext())) return
        val uri = viewModel.getLogShareUri() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share trip log"))
    }

    private fun indicatorColor(color: IndicatorColor): Int = when (color) {
        IndicatorColor.GREEN  -> Color.parseColor("#4CAF50")
        IndicatorColor.YELLOW -> Color.parseColor("#FFC107")
        IndicatorColor.RED    -> Color.parseColor("#CF6679")
        IndicatorColor.GREY   -> Color.parseColor("#888888")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
