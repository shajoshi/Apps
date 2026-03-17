package com.sj.obd2app.ui.details

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sj.obd2app.R
import com.sj.obd2app.databinding.FragmentDetailsBinding
import com.sj.obd2app.metrics.MetricsCalculator
import com.sj.obd2app.ui.attachNavOverflow
import kotlinx.coroutines.launch

/**
 * Details screen — displays all OBD-II values in a scrollable table
 * with columns: Parameter | Value | Unit.
 */
class DetailsFragment : Fragment() {

    private var _binding: FragmentDetailsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: DetailsViewModel
    private lateinit var adapter: Obd2Adapter
    private var isTripActive = false

    private val sectionExpanded = mutableMapOf(
        "obd" to true, "gps" to true, "fuel" to true, "trip" to true, "accel" to true
    )

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

        setupCollapsibleSections()

        // Observe OBD2 data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.obd2Data.collect { items ->
                adapter.submitList(items)
                binding.textPidCount.text = getString(R.string.pid_count_format, items.size)
            }
        }

        // Observe connection status + indicator dot color
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionStatus.collect { status ->
                binding.textConnectionStatus.text = status
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isConnected.collect { connected ->
                val isMock = com.sj.obd2app.obd.Obd2ServiceProvider.useMock
                val color = when {
                    isMock -> Color.parseColor("#FFA000")
                    connected -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#F44336")
                }
                binding.statusIndicator.setBackgroundColor(color)
            }
        }

        // Observe vehicle metrics
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.vehicleMetrics.collect { metrics ->
                metrics?.let { bindVehicleMetrics(it) }
            }
        }

        // Observe mock mode changes to update connection status
        val connectViewModel = ViewModelProvider(requireActivity())[com.sj.obd2app.ui.connect.ConnectViewModel::class.java]
        connectViewModel.isMockMode.observe(viewLifecycleOwner) { isMock ->
            // Refresh connection status to update color indicator
            val connected = viewModel.isConnected.value
            val color = when {
                isMock -> Color.parseColor("#FFA000")
                connected -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#F44336")
            }
            binding.statusIndicator.setBackgroundColor(color)
        }

        // Observe trip state to manage wake lock
        viewLifecycleOwner.lifecycleScope.launch {
            MetricsCalculator.getInstance(requireContext()).tripPhase.collect { phase ->
                when (phase) {
                    com.sj.obd2app.metrics.TripPhase.RUNNING -> {
                        if (!isTripActive) {
                            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            isTripActive = true
                        }
                    }
                    com.sj.obd2app.metrics.TripPhase.IDLE, com.sj.obd2app.metrics.TripPhase.PAUSED -> {
                        if (isTripActive) {
                            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            isTripActive = false
                        }
                    }
                }
            }
        }

        return binding.root
    }

    private fun setupCollapsibleSections() {
        setupSection("obd", binding.headerObd, binding.bodyObd, binding.chevronObd)
        setupSection("gps", binding.headerGps, binding.bodyGps, binding.chevronGps)
        setupSection("fuel", binding.headerFuel, binding.bodyFuel, binding.chevronFuel)
        setupSection("trip", binding.headerTrip, binding.bodyTrip, binding.chevronTrip)
        setupSection("accel", binding.headerAccel, binding.bodyAccel, binding.chevronAccel)
    }

    private fun setupSection(key: String, header: LinearLayout, body: LinearLayout, chevron: TextView) {
        header.setOnClickListener {
            val expanded = sectionExpanded[key] != false
            val nowExpanded = !expanded
            sectionExpanded[key] = nowExpanded
            body.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            chevron.text = if (nowExpanded) "▲" else "▼"
        }
    }

    private fun bindVehicleMetrics(metrics: com.sj.obd2app.metrics.VehicleMetrics) {
        // GPS Data
        binding.tvGpsLatitude.text = metrics.gpsLatitude?.let { "%.6f".format(it) } ?: "—"
        binding.tvGpsLongitude.text = metrics.gpsLongitude?.let { "%.6f".format(it) } ?: "—"
        binding.tvGpsSpeed.text = metrics.gpsSpeedKmh?.let { "%.1f km/h".format(it) } ?: "— km/h"
        binding.tvGpsAltitude.text = metrics.altitudeMslM?.let { "%.1f m".format(it) } ?: "— m"
        binding.tvGpsAccuracy.text = metrics.gpsAccuracyM?.let { "%.1f m".format(it) } ?: "— m"
        binding.tvGpsBearing.text = metrics.gpsBearingDeg?.let { "%.1f °".format(it) } ?: "— °"
        binding.tvGpsSatellites.text = metrics.gpsSatelliteCount?.toString() ?: "—"

        // Fuel Efficiency
        binding.tvInstantConsumption.text = metrics.instantLper100km?.let { "%.1f L/100km".format(it) } ?: "— L/100km"
        binding.tvInstantEfficiency.text = metrics.instantKpl?.let { "%.1f km/L".format(it) } ?: "— km/L"
        binding.tvTripFuelUsed.text = "%.1f L".format(metrics.tripFuelUsedL)
        binding.tvTripAvgConsumption.text = metrics.tripAvgLper100km?.let { "%.1f L/100km".format(it) } ?: "— L/100km"
        binding.tvTripAvgEfficiency.text = metrics.tripAvgKpl?.let { "%.1f km/L".format(it) } ?: "— km/L"
        binding.tvFuelCostEstimate.text = metrics.fuelCostEstimate?.let { "₹%.2f".format(it) } ?: "— ₹"
        binding.tvRangeRemaining.text = metrics.rangeRemainingKm?.let { "%.0f km".format(it) } ?: "— km"
        binding.tvCo2Emissions.text = metrics.avgCo2gPerKm?.let { "%.1f g/km".format(it) } ?: "— g/km"

        // Trip Computer
        binding.tvTripDistance.text = "%.1f km".format(metrics.tripDistanceKm)
        binding.tvTripTime.text = formatDuration(metrics.tripTimeSec)
        binding.tvAvgSpeed.text = metrics.tripAvgSpeedKmh?.let { "%.1f km/h".format(it) } ?: "— km/h"
        binding.tvMaxSpeed.text = "%.1f km/h".format(metrics.tripMaxSpeedKmh)
        binding.tvCityPct.text = "%.1f %%".format(metrics.pctCity)
        binding.tvHighwayPct.text = "%.1f %%".format(metrics.pctHighway)
        binding.tvIdlePct.text = "%.1f %%".format(metrics.pctIdle)
        binding.tvSpeedDiff.text = metrics.spdDiffKmh?.let { "%.1f km/h".format(it) } ?: "— km/h"

        // Accelerometer
        binding.tvPowerAccel.text = metrics.powerAccelKw?.let { 
            val bhp = it * 1.341f  // Convert kW to BHP (1 kW ≈ 1.341 HP)
            "%.1f BHP (%.1f kW)".format(bhp, it)
        } ?: "— BHP (— kW)"
        binding.tvPowerThermo.text = metrics.powerThermoKw?.let { 
            val bhp = it * 1.341f  // Convert kW to BHP (1 kW ≈ 1.341 HP)
            "%.1f BHP (%.1f kW)".format(bhp, it)
        } ?: "— BHP (— kW)"
        binding.tvPowerObd.text = metrics.powerOBDKw?.let { 
            val bhp = it * 1.341f  // Convert kW to BHP (1 kW ≈ 1.341 HP)
            "%.1f BHP (%.1f kW)".format(bhp, it)
        } ?: "— BHP (— kW)"
        binding.tvAccelVertRms.text = metrics.accelVertRms?.let { "%.2f m/s²".format(it) } ?: "— m/s²"
        binding.tvAccelVertMax.text = metrics.accelVertMax?.let { "%.2f m/s²".format(it) } ?: "— m/s²"
        binding.tvAccelVertMean.text = metrics.accelVertMean?.let { "%.2f m/s²".format(it) } ?: "— m/s²"
        binding.tvAccelFwdRms.text = metrics.accelFwdRms?.let { "%.2f m/s²".format(it) } ?: "— m/s²"
        binding.tvAccelLatRms.text = metrics.accelLatRms?.let { "%.2f m/s²".format(it) } ?: "— m/s²"
        binding.tvAccelLeanAngle.text = metrics.accelLeanAngleDeg?.let { "%.1f °".format(it) } ?: "— °"
        binding.tvAccelSampleCount.text = metrics.accelRawSampleCount?.toString() ?: "—"
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up wake lock if fragment is destroyed while trip is active
        if (isTripActive) {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            isTripActive = false
        }
        _binding = null
    }
}
